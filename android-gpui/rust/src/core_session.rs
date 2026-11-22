//! Core framing, request correlation and terminal stream ownership.
use crate::remote::{Error, Operation, Result, Session, bump, lock};
use serde_json::{Value, json};
use std::{
    collections::VecDeque,
    io::{self, Read, Write},
    os::unix::net::UnixStream,
    sync::{
        Arc, Mutex, Weak,
        atomic::{AtomicU64, Ordering},
        mpsc,
    },
    time::{Duration, Instant},
};
const MAX_FRAME: usize = 16 * 1024 * 1024;
/// A render id no session uses yet, for a terminal the UI draws.
pub(crate) fn next_render_id() -> u64 {
    NEXT_RENDER.fetch_add(1, Ordering::Relaxed)
}
const RENDER_ID_BASE: u64 = 1 << 32;
static NEXT_RENDER: AtomicU64 = AtomicU64::new(RENDER_ID_BASE);

#[derive(Debug, PartialEq)]
struct Frame {
    channel: u32,
    kind: u8,
    bytes: Vec<u8>,
}
#[derive(Default)]
struct Decoder {
    bytes: Vec<u8>,
}
impl Decoder {
    fn next(&mut self) -> Result<Option<Frame>> {
        if self.bytes.len() < 9 {
            return Ok(None);
        }
        let len =
            u32::from_le_bytes(self.bytes[5..9].try_into().map_err(|_| Error::Config)?) as usize;
        if len > MAX_FRAME {
            return Err(Error::Protocol("oversized frame".into()));
        }
        if self.bytes.len() < 9 + len {
            return Ok(None);
        }
        let frame = Frame {
            channel: u32::from_le_bytes(self.bytes[..4].try_into().map_err(|_| Error::Config)?),
            kind: self.bytes[4],
            bytes: self.bytes[9..9 + len].to_vec(),
        };
        self.bytes.drain(..9 + len);
        Ok(Some(frame))
    }
}
fn write_frame(socket: &mut impl Write, channel: u32, kind: u8, bytes: &[u8]) -> Result<()> {
    if bytes.len() > MAX_FRAME {
        return Err(Error::Capacity);
    }
    let mut header = [0; 9];
    header[..4].copy_from_slice(&channel.to_le_bytes());
    header[4] = kind;
    header[5..].copy_from_slice(&(bytes.len() as u32).to_le_bytes());
    socket.write_all(&header)?;
    socket.write_all(bytes)?;
    Ok(())
}
/// The view after herdr-core refused to attach `terminal` and nothing else is attached: ready
/// with no pane, and why, so the phone stops waiting for a landing that will not come.
fn refused(view: &mut Value, terminal: &str, why: &str) {
    view["state"] = json!("ready");
    view["pane_id"] = json!("");
    view["refused"] = json!({"terminal": terminal, "reason": why.chars().take(240).collect::<String>()});
    view["status"] = json!("herdr-core refused that terminal");
    bump(view);
}

/// How long the phone waits for an RPC: 15 s, or longer than the core itself was asked to
/// take (`machine.exec`'s `timeout`, in seconds) plus a margin, so the phone never gives up on a
/// command the computer is still allowed to run.
fn rpc_wait(params: &Value) -> Duration {
    const BASE: Duration = Duration::from_secs(15);
    const MARGIN: Duration = Duration::from_secs(5);
    const MOST: Duration = Duration::from_secs(120);
    params["timeout"]
        .as_u64()
        .map(|seconds| (Duration::from_secs(seconds) + MARGIN).clamp(BASE, MOST))
        .unwrap_or(BASE)
}

/// A reply's id against the call awaited: this call's, an earlier call's that timed out (ids
/// only grow), or one this session never sent.
#[derive(Debug, PartialEq, Eq)]
enum ReplyOrder {
    This,
    Late,
    Unknown,
}

fn reply_order(reply: &Value, awaited: u64) -> ReplyOrder {
    match reply.as_u64() {
        Some(id) if id == awaited => ReplyOrder::This,
        Some(id) if id < awaited => ReplyOrder::Late,
        _ => ReplyOrder::Unknown,
    }
}

struct Connection<'a> {
    socket: UnixStream,
    /// Replies to calls this session had given up on, dropped (see `reply_order`).
    late_replies: u64,
    /// The session's queue, for slow RPCs to keep pumping what Wolf types (`call_slow`).
    commands: Option<&'a mpsc::Receiver<Operation>>,
    decoder: Decoder,
    owner: &'a Arc<Session>,
    serial: u64,
    channel: u32,
    output: Option<mpsc::SyncSender<Chunk>>,
    events: u32,
    event_carry: Vec<u8>,
    dirty: bool,
    /// The attached stream sends RESIZE (the PTY's grid) before repainting.
    negotiated: bool,
    /// How this viewer claims the attached pane's grid.
    claim: Claim,
    /// Whether to ask for `resize_history`: the core's hello offered it, and
    /// no attach reply has failed to echo it (a path that cannot carry it).
    /// Then the repaint after a RESIZE may carry the pane's history after an
    /// ESC[3J, which makes the phone's scrollback after a resize exactly the
    /// core's. Nothing depends on it arriving: the phone's own no-loss
    /// handling of a resize (the old screen kept on the paint's first ED 2)
    /// is erased by that ESC[3J when the history comes, and stands when only
    /// the screen comes (a paint the core had to cut to its cap).
    history_on_resize: bool,
    /// The attached stream ended with the pane still listed (a relay closes
    /// it when the path loses a confirmed `resize_history`, or on overflow):
    /// attach it again from the session loop. Any attach clears it.
    reattach: Option<String>,
    /// When the last such reattach was made: the next waits until
    /// REATTACH_EVERY has passed (it is put off, never dropped), so a pane
    /// that keeps closing is reattached at most that often. Only a refused
    /// attach leaves it ended.
    reattached_at: Option<Instant>,
    /// The pane whose stream the core ended: the next attach of it (the
    /// automatic reattach, also one parked while hidden and made when shown,
    /// or a person picking it again) is `resumed` where the path confirmed
    /// `resize_history`, so the owner owes one whole history replacement.
    ended: Option<String>,
    /// The core's hello advertised `automatic_input`: the terminal's own
    /// answers to the program's queries go as AUTOMATIC_INPUT, which the
    /// core never counts as someone using the pane (item 71).
    automatic_input: bool,
    /// The core's hello offered `read-bounded` and `incarnation`: a transcript
    /// read is bounded and says which program it read. Without both, the
    /// phone reads nothing and caches nothing (an old core keeps today's
    /// behaviour).
    transcripts: bool,
    /// An operation taken off the queue while a transcript read waited, that
    /// needs the RPC slot or the size: it runs first after the read, and
    /// nothing after it was taken, so the queue's order holds.
    held: Option<Operation>,
    /// What this viewer told the core about Wolf using it (item 71).
    focus: Focus,
    /// The attached stream has delivered output since its attach: the view
    /// says `painted`, and only then may a held message be sent into it.
    painted: bool,
}

/// Item 71, latest-touched device wins: with a core whose hello offers
/// `viewer_focus` and `focus_by_use`, the attach says whether Herdr is in
/// front showing the pane (`focused`) and how long ago Wolf last used the
/// phone (`idle_ms`); then, while the pane is shown, every use (a touch, a
/// key, Herdr coming to the front) is a WINDOW_FOCUS 1, at most one a second,
/// and hiding or leaving the pane a WINDOW_FOCUS 0 before VISIBILITY 0 or
/// DETACH. Anything older gets none of it.
#[derive(Default)]
struct Focus {
    /// The first hop's hello offered `viewer_focus` and `focus_by_use`.
    offered: bool,
    /// The current attach confirmed `focus_updates` and `focus_by_use`.
    frames: bool,
    /// The core was last told this viewer is focused (by the attach or a 1).
    on: bool,
    /// When the last WINDOW_FOCUS 1 went.
    sent_at: Option<Instant>,
    /// The phone's use count (`Session::uses`) last looked at.
    uses: u64,
    /// The attached terminal's typed inputs last looked at: typing (a key,
    /// the raw keyboard, a paste) is a use too.
    typed: u64,
}

/// History rows asked for in the repaint after a RESIZE: the attach's own
/// (`attach_params`). That ESC[3J replaces the whole scrollback, so fewer
/// would cut the phone's history shorter than the attach gave it, and more
/// buys nothing the phone ever had. Bounding the bytes is the core's cap.
const RESIZE_HISTORY: usize = 1000;
/// At most one automatic reattach of an ended stream this often.
const REATTACH_EVERY: Duration = Duration::from_secs(5);

/// Frame kinds this viewer sends besides CREDIT (1).
const DATA: u8 = 0;
const DETACH: u8 = 2;
const RESIZE: u8 = 3;
/// Bytes for the program that nobody typed: the terminal's answers.
const AUTOMATIC_INPUT: u8 = 6;
/// herdr-core's viewer VISIBILITY frame: one byte, 0 hidden or 1 visible.
const VISIBILITY: u8 = 7;
/// herdr-core's viewer WINDOW_FOCUS frame: one byte, 1 Wolf is using this
/// viewer (again), 0 he left it.
const WINDOW_FOCUS: u8 = 8;
/// WINDOW_FOCUS 1 goes on a use's leading edge, at most this often.
const FOCUS_EVERY: Duration = Duration::from_secs(1);
/// How long a fresh attach waits for the terminal's own measured RESIZE
/// before offering the size it attached with and showing itself.
const MEASURE_WAIT: Duration = Duration::from_secs(1);

/// herdr-core sizes a pane from its eligible viewers. A phone in a pocket must
/// not hold a desktop's terminals at its own small grid, so this viewer is
/// eligible only while the activity is on screen (`Session::visible`).
///
/// A core and relay path that negotiated `viewer_visibility` get VISIBILITY
/// frames: the attach starts hidden, shows itself only after a measured
/// RESIZE, and hides without detaching. Anything older is detached while
/// hidden (no RESIZE alone releases the claim) and reattached, with a fresh
/// RESIZE, when shown again.
#[derive(Default)]
struct Claim {
    /// The core's hello advertised `viewer_visibility`.
    offered: bool,
    /// The current attach confirmed VISIBILITY frames on every hop.
    frames: bool,
    /// The core was last told this viewer is visible.
    shown: bool,
    /// A RESIZE was sent since the attach (or since the last hide).
    measured: bool,
    attached_at: Option<Instant>,
    /// Without frames: the pane detached while hidden, attached again when shown.
    parked: Option<String>,
}
impl Connection<'_> {
    fn wants_visible(&self) -> bool {
        self.owner.visible.load(Ordering::Relaxed)
    }
    /// Whether an attach now would not claim the grid while hidden: it is
    /// shown, or the core can be told it is hidden (frames, as far as known).
    fn may_attach(&self) -> bool {
        self.wants_visible()
            || self.claim.offered && (self.claim.frames || self.claim.attached_at.is_none())
    }
    /// The viewer's size for the attached pane; marks it measured.
    fn resize(&mut self, cols: u16, rows: u16) -> Result<()> {
        let mut bytes = Vec::from(cols.to_le_bytes());
        bytes.extend(rows.to_le_bytes());
        write_frame(&mut self.socket, self.channel, RESIZE, &bytes)?;
        self.claim.measured = true;
        Ok(())
    }
    /// Wolf left the pane (hidden, switched away, closed): WINDOW_FOCUS 0,
    /// before whatever hides or detaches it.
    fn unfocus(&mut self) -> Result<()> {
        if self.focus.frames && self.focus.on && self.channel != 0 {
            write_frame(&mut self.socket, self.channel, WINDOW_FOCUS, &[0])?;
        }
        self.focus.on = false;
        Ok(())
    }
    /// While the pane is shown: WINDOW_FOCUS 1 when it becomes shown without
    /// being focused, and for each new use, at most one a second (leading edge).
    /// A use while the pane waits to be shown (its measured RESIZE) is kept
    /// and sent once it is; a use while hidden is not Wolf using this pane.
    /// Hidden before ever being shown, a focused attach is unfocused.
    fn sync_focus(&mut self) -> Result<()> {
        // Only a session with a core that has the rule reports or records uses.
        if !self.focus.offered {
            return Ok(());
        }
        let typed = lock(&self.owner.state).terminal.as_ref().map(|t| t.inputs());
        if let Some(typed) = typed.filter(|typed| *typed != self.focus.typed) {
            self.focus.typed = typed;
            self.owner.uses.mark();
        }
        if !self.focus.frames || self.channel == 0 {
            return Ok(());
        }
        let uses = self.owner.uses.count();
        if !self.wants_visible() {
            self.focus.uses = uses;
            // VISIBILITY 0 unfocuses a shown pane (sync_claim); this one never was.
            return self.unfocus();
        }
        if self.claim.frames && !self.claim.shown {
            // Measuring before showing: any use waits for the show.
            return Ok(());
        }
        let used = uses != self.focus.uses;
        self.focus.uses = uses;
        let due = self
            .focus
            .sent_at
            .is_none_or(|at| at.elapsed() >= FOCUS_EVERY);
        if !self.focus.on || used && due {
            write_frame(&mut self.socket, self.channel, WINDOW_FOCUS, &[1])?;
            self.focus.on = true;
            self.focus.sent_at = Some(Instant::now());
        }
        Ok(())
    }
    fn detach(&mut self) -> Result<()> {
        self.unfocus()?;
        if self.channel != 0 {
            write_frame(&mut self.socket, self.channel, DETACH, &[])?;
        }
        self.channel = 0;
        self.output = None;
        self.claim.shown = false;
        Ok(())
    }
    /// Brings the core's view of this viewer in line with `Session::visible`.
    fn sync_claim(&mut self, cols: u16, rows: u16) -> Result<()> {
        let want = self.wants_visible();
        if self.claim.frames && self.channel != 0 {
            if want && !self.claim.shown {
                let waited = self
                    .claim
                    .attached_at
                    .is_none_or(|at| at.elapsed() >= MEASURE_WAIT);
                if !self.claim.measured && !waited {
                    return Ok(());
                }
                if !self.claim.measured {
                    self.resize(cols, rows)?;
                }
                write_frame(&mut self.socket, self.channel, VISIBILITY, &[1])?;
                self.claim.shown = true;
            } else if !want && self.claim.shown {
                self.unfocus()?;
                write_frame(&mut self.socket, self.channel, VISIBILITY, &[0])?;
                self.claim.shown = false;
                // Showing again offers a fresh size first.
                self.claim.measured = false;
            }
            return Ok(());
        }
        if !want && self.channel != 0 {
            let pane = lock(&self.owner.state).view["pane_id"]
                .as_str()
                .unwrap_or("")
                .to_owned();
            self.claim.parked = (!pane.is_empty()).then_some(pane);
            self.detach()?;
        } else if want
            && self.channel == 0
            && let Some(pane) = self.claim.parked.take()
        {
            self.attach(&pane, cols, rows)?;
            if !self.claim.frames {
                self.resize(cols, rows)?;
            }
        }
        Ok(())
    }
    /// Best effort as the session ends: the core forgets this viewer now
    /// rather than when the transport finally times out.
    fn release(&mut self) {
        if self.channel == 0 {
            return;
        }
        let _ = self.unfocus();
        if self.claim.frames && self.claim.shown {
            let _ = write_frame(&mut self.socket, self.channel, VISIBILITY, &[0]);
        }
        let _ = self.detach();
    }
    fn read(&mut self) -> Result<Option<Frame>> {
        if self.owner.cancel.is_cancelled() {
            return Err(Error::Closed);
        }
        if let Some(frame) = self.decoder.next()? {
            return Ok(Some(frame));
        }
        let mut buffer = [0; 64 * 1024];
        match self.socket.read(&mut buffer) {
            Ok(0) => Err(Error::Closed),
            Ok(n) => {
                self.owner.heard.mark();
                self.decoder.bytes.extend_from_slice(&buffer[..n]);
                self.decoder.next()
            }
            Err(e)
                if matches!(
                    e.kind(),
                    io::ErrorKind::TimedOut
                        | io::ErrorKind::WouldBlock
                        | io::ErrorKind::Interrupted
                ) =>
            {
                Ok(None)
            }
            Err(e) => Err(e.into()),
        }
    }
    fn call(&mut self, method: &str, params: Value) -> Result<Value> {
        let wait = rpc_wait(&params);
        self.call_within(method, params, wait)
    }
    /// A call that may take seconds (a command on the machine, a file put, a refresh): what
    /// Wolf types keeps going out while it waits (audit A5), as a transcript read's does.
    fn call_slow(&mut self, method: &str, params: Value) -> Result<Value> {
        let wait = rpc_wait(&params);
        let commands = self.commands;
        self.call_pumping(method, params, wait, commands)
    }
    fn call_within(&mut self, method: &str, params: Value, wait: Duration) -> Result<Value> {
        self.call_pumping(method, params, wait, None)
    }
    /// As `call_within`; with `commands`, what Wolf types keeps going out
    /// while the reply is awaited (see `pump`).
    fn call_pumping(
        &mut self,
        method: &str,
        params: Value,
        wait: Duration,
        commands: Option<&mpsc::Receiver<Operation>>,
    ) -> Result<Value> {
        self.serial += 1;
        let id = self.serial;
        write_frame(
            &mut self.socket,
            0,
            0,
            &serde_json::to_vec(&json!({"jsonrpc":"2.0","id":id,"method":method,"params":params}))?,
        )?;
        let deadline = Instant::now() + wait;
        while Instant::now() < deadline {
            if let Some(commands) = commands {
                self.pump(commands)?;
            }
            let Some(frame) = self.read()? else { continue };
            if frame.channel == 0 && frame.kind == 0 {
                let reply: Value = serde_json::from_slice(&frame.bytes)?;
                match reply_order(&reply["id"], id) {
                    // A call this session gave up on answered late: it is over, drop it.
                    ReplyOrder::Late => {
                        self.late_replies += 1;
                        #[cfg(feature = "android-ui")]
                        log::debug!("dropped a late RPC reply ({} so far)", self.late_replies);
                        continue;
                    }
                    ReplyOrder::Unknown => return Err(Error::Protocol("uncorrelated RPC reply".into())),
                    ReplyOrder::This => {}
                }
                if let Some(error) = reply.get("error") {
                    return Err(Error::Rpc {
                        kind: error["data"]["kind"].as_str().unwrap_or("").into(),
                        message: error["message"].as_str().unwrap_or("").into(),
                    });
                }
                return reply
                    .get("result")
                    .cloned()
                    .ok_or_else(|| Error::Protocol("missing RPC result".into()));
            }
            self.deliver(frame)?;
        }
        Err(Error::Timeout { method: method.to_owned() })
    }
    /// While a slow RPC (a transcript read) waits for its reply: keystrokes,
    /// output credit and other frames for the attached pane, held messages and
    /// focus still go out, in queue order, since they are only frames. The
    /// first operation that needs the RPC slot or changes the size (a RESIZE,
    /// which may reattach) is held, and with it everything queued after it,
    /// until the reply is in.
    fn pump(&mut self, commands: &mpsc::Receiver<Operation>) -> Result<()> {
        for _ in 0..64 {
            if self.held.is_some() {
                break;
            }
            let Ok(operation) = commands.try_recv() else { break };
            match operation {
                Operation::Frame(channel, kind, bytes)
                    if channel == self.channel && channel != 0 && kind != RESIZE =>
                {
                    write_frame(&mut self.socket, channel, kind, &bytes)?
                }
                Operation::Compose(compose, reply) => self.compose(compose, &reply)?,
                other => self.held = Some(other),
            }
        }
        self.sync_focus()
    }
    fn deliver(&mut self, frame: Frame) -> Result<()> {
        if frame.channel == self.events && frame.kind == 0 {
            self.event_carry.extend_from_slice(&frame.bytes);
            if self.event_carry.len() > MAX_FRAME {
                return Err(Error::Capacity);
            }
            while let Some(end) = self.event_carry.iter().position(|b| *b == b'\n') {
                let message: Value = serde_json::from_slice(&self.event_carry[..end])?;
                self.event_carry.drain(..=end);
                if let Some(open) = open_file_event(&message) {
                    // Not a fact the projection needs: no refresh, only the view.
                    let mut state = lock(&self.owner.state);
                    state.view["open_file"] = open;
                    bump(&mut state.view);
                } else if message["method"] != "heartbeat" {
                    #[cfg(feature = "android-ui")]
                    if message["method"] == "missed"
                        || (message["method"] == "event" && message["params"]["type"] == "foreground")
                    {
                        let mut state = lock(&self.owner.state);
                        if message["method"] == "missed" {
                            state.invalidate_footer_foreground();
                        } else if message["params"]["terminal"] == state.view["pane_id"] {
                            state.footer_foreground_changed(&message["params"]["data"]);
                        }
                    }
                    self.dirty = true;
                }
            }
            write_frame(
                &mut self.socket,
                self.events,
                1,
                &(frame.bytes.len() as u32).to_le_bytes(),
            )?;
        } else if frame.channel == self.channel && self.channel != 0 {
            match frame.kind {
                0 => {
                    self.owner.heard.printed(frame.bytes.len());
                    if !self.painted && self.output.is_some() {
                        self.painted = true;
                        lock(&self.owner.state).painted = true;
                    }
                    if let Some(output) = &self.output {
                        // 256 KiB starting credit bounds this queue. Credit is returned
                        // only as Rio consumes bytes, never when the network receives them.
                        output.try_send(Chunk::Data(frame.bytes)).map_err(|_| Error::Capacity)?;
                    }
                }
                // RESIZE from the core: the PTY's authoritative grid, sent before
                // the paint at that size (only on a path that negotiated it). It
                // joins the same ordered queue as DATA, so it applies only after
                // every earlier byte was parsed (StreamRead::read).
                3 if self.negotiated => {
                    if let (Some(output), Some((cols, rows))) = (&self.output, resize_frame(&frame.bytes)) {
                        output.try_send(Chunk::Grid(cols, rows)).map_err(|_| Error::Capacity)?;
                    }
                }
                2 => {
                    self.output = None;
                    // That stream is gone: no focus frame goes on it. The
                    // reattach (or a pick) negotiates focus again.
                    self.focus.frames = false;
                    self.focus.on = false;
                    let mut state = lock(&self.owner.state);
                    let pane = state.view["pane_id"].as_str().unwrap_or("").to_owned();
                    if !pane.is_empty() {
                        self.ended = Some(pane.clone());
                    }
                    state.view["state"] = json!("connecting");
                    state.view["status"] =
                        json!("Terminal stream ended; select a terminal to attach again");
                    #[cfg(feature = "android-ui")]
                    state.invalidate_footer_foreground();
                    state.terminal = None;
                    state.render_id = 0;
                    state.painted = false;
                    bump(&mut state.view);
                    drop(state);
                    // Attached again by the session loop, at most once per
                    // REATTACH_EVERY: a close sooner than that is put off, not
                    // dropped. A pane whose program ended refuses the attach
                    // and stays ended.
                    if !pane.is_empty() {
                        self.reattach = Some(pane);
                    }
                }
                _ => {}
            }
        }
        Ok(())
    }
    fn action(&mut self, action: &str, cols: u16, rows: u16) -> Result<String> {
        let view = lock(&self.owner.state).view.clone();
        let terminal = view["pane_id"].as_str().unwrap_or("");
        let selected = view["panes"]
            .as_array()
            .and_then(|p| p.iter().find(|p| p["id"] == terminal));
        match action {
            "tab.new" | "agent.claude" | "agent.codex" => {
                let unique = format!(
                    "mobile-{}-{}",
                    std::process::id(),
                    NEXT_RENDER.fetch_add(1, Ordering::Relaxed)
                );
                let space = selected
                    .and_then(|p| p["workspace_id"].as_str())
                    .filter(|s| *s != "loose")
                    .unwrap_or(&unique);
                let cwd = selected.and_then(|p| p["cwd"].as_str());
                // No `name`: herdr-core names are unique ids (a-z 0-9 - _ .), and
                // "Mobile shell" was refused, so these actions never ran. The
                // app shows the program's title instead.
                let mut run = json!({"cwd":cwd,"keep":true,"labels":{"herdr.space":space,"herdr.tab":unique},"size":{"cols":cols,"rows":rows},"display":display()});
                if let Some(program) = match action {
                    "agent.claude" => Some("claude"),
                    "agent.codex" => Some("codex"),
                    _ => None,
                } {
                    // Through the machine's login shell, as a typed command would
                    // be: its PATH has the Nix wrappers, which run interactive
                    // sessions as `aswap run <agent> --auto` with that machine's
                    // account, so a new agent never stops at a login screen.
                    // keep: when the agent exits, the terminal stays as a shell.
                    run["line"] = json!(format!("exec \"${{SHELL:-/bin/sh}}\" -lic {program}"));
                    run["labels"]["agent.kind"] = json!(program);
                }
                let result = self.call("terminal.run", run)?;
                let terminal = result["address"]
                    .as_str()
                    .ok_or_else(|| Error::Protocol("missing terminal address".into()))?;
                self.refresh()?;
                self.attach(terminal, cols, rows)?;
            }
            "pane.close" if !terminal.is_empty() => {
                self.call("terminal.kill", json!({"terminal":terminal}))?;
                self.refresh()?;
            }
            _ => match TabEdit::parse(action) {
                Some(TabEdit::DeaconStart { machine }) => {
                    return self.start_deacon(&machine, cols, rows);
                }
                Some(TabEdit::GitLook { paths }) => return self.git_look(&paths),
                Some(TabEdit::FileRead { terminal, path, limit }) => {
                    return Ok(match self.read_file(&view, &terminal, &path, limit) {
                        Ok(reply) => reply,
                        Err(error) => json!({"queued":false,"error":error.to_string()}),
                    }
                    .to_string());
                }
                Some(TabEdit::SpaceOpen { folder }) => {
                    // Fresh, not cached: a retry after a slow answer must find
                    // the space it already made instead of making a second.
                    let list = self.call("terminal.list", json!({}))?;
                    let doc = self.call("doc.get", json!({"name":"herdr-app"}))?;
                    if let Some(terminal) = existing_at(&list["terminals"], &doc["value"], &folder) {
                        self.refresh()?;
                        return Ok(json!({"queued":true,"terminal":terminal,"existing":true}).to_string());
                    }
                    let (terminal, layout_error) =
                        self.launch_space("", Some(&folder), None, json!({}), cols, rows)?;
                    let mut reply = json!({"queued":true,"terminal":terminal});
                    if let Some(error) = layout_error {
                        reply["layout_error"] = json!(error);
                    }
                    return Ok(reply.to_string());
                }
                Some(TabEdit::Send { terminal, text }) => {
                    // Never forced: a blocked program is refused (hc's exit 9),
                    // and the phone says so instead of typing into its dialog.
                    return Ok(match self.call("terminal.send", json!({"terminal":terminal,"text":text})) {
                        Ok(_) => json!({"queued":true,"terminal":terminal}),
                        Err(error) => send_refusal(&error),
                    }
                    .to_string());
                }
                Some(edit) => self.edit_tab(edit, cols, rows)?,
                None => match crate::phone_ops::PhoneOp::parse(action) {
                    Some(op) => {
                        let reply = op.run(|method, params| self.call_slow(method, params))?;
                        // Done is done: a failed re-read leaves the view for the next poll, never an error.
                        if op.edits() && self.refresh().is_err() {
                            self.dirty = true;
                        }
                        return Ok(reply);
                    }
                    None => return Err(Error::StaleTarget),
                },
            },
        }
        Ok(json!({"queued":true}).to_string())
    }
    /// A held message, typed into the attach it was tapped over and nowhere
    /// else: every part goes on that attach's channel, in order, or the
    /// reply says how far it got. Answered before a write error ends the
    /// session, so the phone never reads a lost reply as "nothing typed".
    fn compose(
        &mut self,
        compose: crate::remote::Compose,
        reply: &mpsc::SyncSender<crate::remote::Typed>,
    ) -> Result<()> {
        use crate::remote::Typed;
        let current = self.channel != 0
            && self.output.is_some()
            && self.painted
            && lock(&self.owner.state).render_id == compose.attach;
        if !current {
            let _ = reply.send(Typed::Unwritten);
            return Ok(());
        }
        for part in &compose.parts {
            if let Err(error) = write_frame(&mut self.socket, self.channel, DATA, part) {
                // Some of it may have left: the paste may sit unsent in the input box.
                let _ = reply.send(Typed::Unsure);
                return Err(error);
            }
        }
        let _ = reply.send(Typed::Written);
        Ok(())
    }
    /// One bounded transcript read (see `remote::read_transcript`). Never on a
    /// core without `read-bounded` and `incarnation`: its read formats the
    /// whole history under its lock and may scroll a full-screen program.
    fn read_transcript(
        &mut self,
        request: &crate::remote::ReadRequest,
        commands: &mpsc::Receiver<Operation>,
    ) -> Result<String> {
        if !self.transcripts {
            return Ok(json!({"supported":false}).to_string());
        }
        let params = json!({
            "terminal": request.terminal,
            "lines": request.lines,
            "max_bytes": request.max_bytes,
            "stitch": false,
        });
        match self.call_pumping("terminal.read", params, READ_WAIT, Some(commands)) {
            Ok(result) => Ok(transcript_reply(&result, request.max_bytes)?.to_string()),
            Err(Error::Rpc { kind, .. }) => Ok(json!({"supported":true,"error":kind}).to_string()),
            Err(error) => Err(error),
        }
    }
    /// `terminal.put`: the file lands beside the terminal on its machine, in a
    /// folder of the terminal's own, for its program to read; nothing is typed.
    fn put(&mut self, file: &crate::remote::PutFile) -> Result<String> {
        use base64::Engine;
        let params = json!({
            "terminal": file.terminal,
            "name": file.name,
            "data": base64::engine::general_purpose::STANDARD.encode(&file.bytes),
        });
        let commands = self.commands;
        let put = self.call_pumping("terminal.put", params, PUT_WAIT, commands);
        match put {
            Ok(result) => {
                let path = result["path"]
                    .as_str()
                    .filter(|p| p.starts_with('/') && p.len() <= 4096 && !p.chars().any(char::is_control))
                    .ok_or_else(|| Error::Protocol("missing put path".into()))?;
                Ok(json!({"queued":true,"path":path,"bytes":result["bytes"]}).to_string())
            }
            // A refusal after the bytes began to arrive can leave a partial file at the destination (the
            // core's put.rs creates the final name before writing; its cleanup is a tracked core follow-up),
            // so the phone reports the kind and never says the file is absent.
            Err(Error::Rpc { kind, .. }) => Ok(json!({"queued":false,"kind":kind}).to_string()),
            Err(error) => Err(error),
        }
    }
    /// This computer's deacon, as the desktop's Fleet starts one: a `deacon`
    /// space with a `deacon` tab running Claude, labelled `role=deacon` from
    /// birth. Rechecks first: an existing local deacon is returned, never a
    /// second launch. `{"queued":true,"terminal":..,"started":bool}`.
    fn start_deacon(&mut self, machine: &str, cols: u16, rows: u16) -> Result<String> {
        let existing = self.call("terminal.list", json!({}))?;
        let doc = self.call("doc.get", json!({"name":"herdr-app"}))?;
        if let Some(terminal) = canonical_deacon(&existing["terminals"], &doc["value"]) {
            self.refresh()?;
            return Ok(json!({"queued":true,"terminal":terminal,"started":false}).to_string());
        }
        // The Deacon's own folder on this computer ($HOME/.herdr/deacon, made
        // if missing), never the folder of whatever happens to be focused.
        let folder = self.deacon_folder()?;
        // Resolving it took a round trip: check again, and launch only if no
        // Deacon appeared meanwhile and this computer still answers (a failed
        // call ends here with its error, nothing launched).
        let again = self.call("terminal.list", json!({}))?;
        let doc = self.call("doc.get", json!({"name":"herdr-app"}))?;
        if let Some(terminal) = canonical_deacon(&again["terminals"], &doc["value"]) {
            self.refresh()?;
            return Ok(json!({"queued":true,"terminal":terminal,"started":false}).to_string());
        }
        let (terminal, layout_error) = self.launch_space(
            "deacon",
            Some(&folder),
            Some(deacon_line(machine)),
            json!({"role":"deacon","agent.kind":"claude"}),
            cols,
            rows,
        )?;
        // The deacon runs and is labelled either way: report it for recovery,
        // and a retry finds it instead of launching another.
        let mut reply = json!({"queued":true,"terminal":terminal,"started":true});
        if let Some(error) = layout_error {
            reply["layout_error"] = json!(error);
        }
        Ok(reply.to_string())
    }
    /// `$HOME/.herdr/deacon` on this computer, created if missing, as an
    /// absolute path (one exec, as the desktop does; the same path as git.look).
    fn deacon_folder(&mut self) -> Result<String> {
        use base64::Engine;
        let result = self.call_slow("machine.exec", json!({"argv":["sh","-c",DEACON_DIR],"timeout":10}))?;
        if result["code"] != 0 {
            return Err(Error::Protocol("the Deacon's folder could not be made".into()));
        }
        let bytes = base64::engine::general_purpose::STANDARD
            .decode(result["stdout"].as_str().unwrap_or(""))
            .map_err(|_| Error::Protocol("invalid command output".into()))?;
        deacon_dir(&String::from_utf8_lossy(&bytes))
            .ok_or_else(|| Error::Protocol("the Deacon's folder could not be read".into()))
    }
    /// A new space in herdr-app's document with one tab running `line` (a
    /// shell when None) in `folder` (the core's default when None), labelled
    /// at birth. The terminal is returned even when the document update
    /// fails, with that error, so a caller can recover instead of relaunching.
    fn launch_space(
        &mut self,
        name: &str,
        folder: Option<&str>,
        line: Option<String>,
        labels: Value,
        cols: u16,
        rows: u16,
    ) -> Result<(String, Option<String>)> {
        let unique = format!(
            "{:x}{:016x}",
            std::process::id(),
            NEXT_RENDER.fetch_add(1, Ordering::Relaxed)
                ^ std::time::SystemTime::now()
                    .duration_since(std::time::UNIX_EPOCH)
                    .map(|d| d.as_nanos() as u64)
                    .unwrap_or(0)
        );
        let (space, tab, block) = (format!("sp{unique}"), format!("tb{unique}"), format!("pb{unique}"));
        let mut run = json!({
            "keep": true,
            "labels": {"herdr.space": space, "herdr.tab": tab},
            "size": {"cols": cols, "rows": rows},
            "display": display(),
        });
        if let Some(folder) = folder {
            run["cwd"] = json!(folder);
        }
        if let Some(line) = line {
            run["line"] = json!(line);
        }
        if let Some(extra) = labels.as_object() {
            for (key, value) in extra {
                run["labels"][key] = value.clone();
            }
        }
        let result = self.call("terminal.run", run)?;
        let terminal = result["address"]
            .as_str()
            .ok_or_else(|| Error::Protocol("missing terminal address".into()))?
            .to_owned();
        // Labelled at birth above; set again so a core that drops run labels
        // still has them (an agent labelling itself is the last backup). A
        // failure here is reported, never a reason to launch again.
        let mut label_error = None;
        if labels.as_object().is_some_and(|l| !l.is_empty())
            && let Err(error) = self.call("terminal.label", json!({"terminal":terminal,"labels":labels}))
        {
            label_error = Some(format!("it could not be labelled ({error}); it will label itself"));
        }
        let folder = folder.map(|f| json!(f)).unwrap_or_else(|| result["cwd"].clone());
        let root = json!({"children":[{"content":{"terminal":terminal},"id":block,"type":"block"}],"id":tab,"type":"row"});
        let tab_name = if name.is_empty() { "shell" } else { name };
        // edit_doc re-reads the document each try and retries on a version
        // conflict, so every other space and tab is kept.
        let entry = json!({"folder":folder,"id":space,"name":name,"tabs":[{"id":tab,"name":tab_name,"root":root}]});
        let placed = self.edit_doc(|doc| add_space(doc, &entry));
        let layout_error = placed.err().map(|e| format!("its space could not be added ({e})"));
        Ok((terminal, label_error.into_iter().chain(layout_error).reduce(|a, b| format!("{a}; {b}"))))
    }
    /// One exec on this computer answers, for each folder, its repo (git
    /// common dir), checkout top and branch, and lists each repo's worktrees
    /// once (FleetTree.parseGitLook reads it). Only folders the phone already
    /// shows are asked about; output is bounded.
    fn git_look(&mut self, paths: &[String]) -> Result<String> {
        let mut argv = vec![json!("sh"), json!("-c"), json!(GIT_LOOK), json!("sh")];
        argv.extend(paths.iter().map(|p| json!(p)));
        let result = self.call_slow("machine.exec", json!({"argv":argv,"timeout":10}))?;
        let text = exec_stdout(&result)?;
        Ok(json!({"queued":true,"text":text,"truncated":result["truncated"]}).to_string())
    }
    /// A text file for the file view, read on the machine `terminal` runs on:
    /// a relative path from that terminal's folder, `~/` from its home.
    /// Bounded to the first `limit` lines, at most FILE_LINES (and file.read's 8 MB).
    fn read_file(&mut self, view: &Value, terminal: &str, path: &str, limit: u64) -> Result<Value> {
        let machine = terminal.rsplit_once('/').map(|(machine, _)| machine);
        let cwd = view["panes"]
            .as_array()
            .and_then(|panes| panes.iter().find(|p| p["id"] == terminal))
            .and_then(|pane| pane["cwd"].as_str())
            .filter(|cwd| cwd.starts_with('/'))
            .map(str::to_owned);
        let (path, cwd) = match path.strip_prefix("~/") {
            Some(rest) => {
                let mut exec = json!({"argv":["sh","-c",r#"printf %s "$HOME""#],"timeout":5});
                if let Some(machine) = machine {
                    exec["on"] = json!(machine);
                }
                let result = self.call_slow("machine.exec", exec)?;
                let home = exec_stdout(&result)?;
                (rest.to_owned(), Some(home).filter(|h| h.starts_with('/')))
            }
            None => (path.to_owned(), cwd),
        };
        let mut params = json!({"path":path,"offset":1,"limit":limit.min(FILE_LINES)});
        if let Some(cwd) = &cwd {
            params["cwd"] = json!(cwd);
        }
        if let Some(machine) = machine {
            params["on"] = json!(machine);
        }
        let result = self.call("file.read", params)?;
        let shown = if path.starts_with('/') {
            path.clone()
        } else {
            cwd.map_or(path.clone(), |cwd| format!("{}/{path}", cwd.trim_end_matches('/')))
        };
        Ok(json!({
            "queued": true,
            "path": shown,
            "text": result["text"],
            "lines": result["lines"],
            "last": result["last"],
        }))
    }
    /// Changes herdr-app's layout document, which the desktop app reads too,
    /// so a rename or reorder made here shows there. Retries on a version race.
    fn edit_doc(&mut self, mut change: impl FnMut(&mut Value) -> Result<()>) -> Result<()> {
        for _ in 0..3 {
            let doc = self.call("doc.get", json!({"name":"herdr-app"}))?;
            let mut value = doc["value"].clone();
            change(&mut value)?;
            match self.call(
                "doc.set",
                json!({"name":"herdr-app","value":value,"version":doc["version"]}),
            ) {
                Ok(_) => return self.refresh(),
                Err(Error::Rpc { kind, .. }) if kind == "conflict" => continue,
                Err(error) => return Err(error),
            }
        }
        Err(Error::Protocol("the layout kept changing; try again".into()))
    }
    /// The terminals the phone's view puts in this space's tab.
    fn tab_terminals(&self, space: &str, tab: &str) -> Vec<String> {
        lock(&self.owner.state).view["panes"]
            .as_array()
            .map(|panes| {
                panes
                    .iter()
                    .filter(|p| p["workspace_id"] == space && p["tab_id"] == tab)
                    .filter_map(|p| p["id"].as_str().map(str::to_owned))
                    .collect()
            })
            .unwrap_or_default()
    }
    fn edit_tab(&mut self, edit: TabEdit, cols: u16, rows: u16) -> Result<()> {
        match edit {
            TabEdit::Rename { space, tab, name } => self.edit_doc(|doc| rename_tab(doc, &space, &tab, &name)),
            TabEdit::Move { space, tab, index } => self.edit_doc(|doc| {
                let tabs = tabs_mut(doc, &space)?;
                let at = tabs
                    .iter()
                    .position(|t| t["id"] == tab.as_str())
                    .ok_or(Error::StaleTarget)?;
                let item = tabs.remove(at);
                tabs.insert(index.min(tabs.len()), item);
                Ok(())
            }),
            TabEdit::Close { space, tab } => {
                for terminal in self.tab_terminals(&space, &tab) {
                    self.call("terminal.kill", json!({"terminal":terminal}))?;
                }
                self.edit_doc(|doc| {
                    let tabs = tabs_mut(doc, &space)?;
                    tabs.retain(|t| t["id"] != tab.as_str());
                    Ok(())
                })
            }
            TabEdit::Split { space, tab } => {
                let cwd = {
                    let state = lock(&self.owner.state);
                    let shown = state.view["pane_id"].clone();
                    state.view["panes"]
                        .as_array()
                        .and_then(|p| p.iter().find(|p| p["id"] == shown))
                        .map(|p| p["cwd"].clone())
                        .unwrap_or(Value::Null)
                };
                let run = json!({"cwd":cwd,"keep":true,"labels":{"herdr.space":space,"herdr.tab":tab},"size":{"cols":cols,"rows":rows},"display":display()});
                let result = self.call("terminal.run", run)?;
                let terminal = result["address"]
                    .as_str()
                    .ok_or_else(|| Error::Protocol("missing terminal address".into()))?
                    .to_owned();
                let block = format!("p{:x}", NEXT_RENDER.fetch_add(1, Ordering::Relaxed));
                self.edit_doc(|doc| {
                    let item = tab_mut(doc, &space, &tab)?;
                    let leaf = json!({"content":{"terminal":terminal},"id":block,"type":"block"});
                    let root = &mut item["root"];
                    if let Some(children) = root["children"].as_array_mut() {
                        children.push(leaf);
                    } else {
                        let old = root.take();
                        *root = json!({"children":[old, leaf],"id":tab,"type":"row"});
                    }
                    Ok(())
                })?;
                self.attach(&terminal, cols, rows)
            }
            TabEdit::SpaceRename { space, name } => self.edit_doc(|doc| {
                doc["spaces"]
                    .as_array_mut()
                    .and_then(|spaces| spaces.iter_mut().find(|s| s["id"] == space.as_str()))
                    .ok_or(Error::StaleTarget)?["name"] = json!(name);
                Ok(())
            }),
            TabEdit::SpaceMove { space, index } => self.edit_doc(|doc| {
                let spaces = doc["spaces"].as_array_mut().ok_or(Error::StaleTarget)?;
                let at = spaces
                    .iter()
                    .position(|s| s["id"] == space.as_str())
                    .ok_or(Error::StaleTarget)?;
                let item = spaces.remove(at);
                spaces.insert(index.min(spaces.len()), item);
                Ok(())
            }),
            TabEdit::SpaceNew { space, kind } => {
                let cwd = {
                    let state = lock(&self.owner.state);
                    state.view["panes"]
                        .as_array()
                        .and_then(|p| p.iter().find(|p| p["workspace_id"] == space.as_str()))
                        .map(|p| p["cwd"].clone())
                        .unwrap_or(Value::Null)
                };
                let tab = format!(
                    "mobile-{}-{}",
                    std::process::id(),
                    NEXT_RENDER.fetch_add(1, Ordering::Relaxed)
                );
                let mut run = json!({"cwd":cwd,"keep":true,"labels":{"herdr.space":space,"herdr.tab":tab},"size":{"cols":cols,"rows":rows},"display":display()});
                if kind != "shell" {
                    run["line"] = json!(format!("exec \"${{SHELL:-/bin/sh}}\" -lic {kind}"));
                    run["labels"]["agent.kind"] = json!(kind);
                }
                let result = self.call("terminal.run", run)?;
                let terminal = result["address"]
                    .as_str()
                    .ok_or_else(|| Error::Protocol("missing terminal address".into()))?
                    .to_owned();
                self.refresh()?;
                self.attach(&terminal, cols, rows)
            }
            TabEdit::WindowNew {
                space,
                lead,
                folder,
                kind,
            } => {
                let (cwd, space, space_name) = {
                    let state = lock(&self.owner.state);
                    let cwd = folder.map(Value::from).unwrap_or_else(|| {
                        state.view["panes"]
                            .as_array()
                            .and_then(|p| p.iter().find(|p| p["id"] == lead.as_str()))
                            .map(|p| p["cwd"].clone())
                            .unwrap_or(Value::Null)
                    });
                    let (space, name) = window_space(&state.view, &space, &lead);
                    (cwd, space, name)
                };
                let tab = format!(
                    "mobile-{}-{}",
                    std::process::id(),
                    NEXT_RENDER.fetch_add(1, Ordering::Relaxed)
                );
                let run = window_run(&space, &tab, &lead, cwd.clone(), kind, cols, rows);
                let result = self.call("terminal.run", run)?;
                let terminal = result["address"]
                    .as_str()
                    .ok_or_else(|| Error::Protocol("missing terminal address".into()))?
                    .to_owned();
                // Its tab in herdr-app's layout, so it can be renamed, moved or
                // closed from here and the desktop places it without a repair;
                // a space the layout lacks (one only labels name) is added to it.
                let block = format!("p{:x}", NEXT_RENDER.fetch_add(1, Ordering::Relaxed));
                let entry = window_tab(&tab, &terminal, &block, kind);
                let placed = self.edit_doc(|doc| add_window_tab(doc, &space, &space_name, &cwd, &entry));
                // Labelled at birth; set again for a core that drops run labels.
                // A failure leaves the run's own label, which a current core keeps.
                let _ = self.call(
                    "terminal.label",
                    json!({"terminal":terminal,"labels":{"parent":lead}}),
                );
                self.refresh()?;
                self.attach(&terminal, cols, rows)?;
                // Started, and shown; but say so when its tab is not in the
                // layout, rather than offer a menu that cannot work.
                placed.map_err(|error| Error::WindowNotPlaced(Box::new(error)))
            }
            TabEdit::OrderMove {
                scope,
                id,
                target,
                after,
                shown,
            } => {
                // One compare-and-set per try, from the doc as it is now: a drop
                // never writes back another window's stale order.
                for _ in 0..5 {
                    let doc = self.call("doc.get", json!({"name":"projects.order"}))?;
                    // Nothing moves: done. A list at its cap is refused out loud (Capacity), never as a no-op.
                    let Some(value) = order_move(&doc["value"], &scope, &id, &target, after, &shown)? else {
                        return Ok(());
                    };
                    match self.call(
                        "doc.set",
                        json!({"name":"projects.order","value":value,"version":doc["version"]}),
                    ) {
                        Ok(_) => return self.refresh(),
                        Err(Error::Rpc { kind, .. }) if kind == "conflict" => continue,
                        Err(error) => return Err(error),
                    }
                }
                Err(Error::Protocol("the window order kept changing; try again".into()))
            }
            TabEdit::Interrupt { terminal } => {
                self.call("terminal.interrupt", json!({"terminal":terminal}))?;
                Ok(())
            }
            TabEdit::Kill { terminal } => {
                self.call("terminal.kill", json!({"terminal":terminal}))?;
                self.refresh()
            }
            TabEdit::GroupSet { terminal, group } => {
                // A PM dropped into another group, or back onto its repo (factory-r8f.8): its
                // herdr.group label only, presentation; "" removes it.
                self.call(
                    "terminal.label",
                    json!({"terminal":terminal,"labels":{"herdr.group":group.unwrap_or_default()}}),
                )?;
                self.refresh()
            }
            TabEdit::DeaconStart { machine } => self.start_deacon(&machine, cols, rows).map(drop),
            TabEdit::GitLook { .. }
            | TabEdit::FileRead { .. }
            | TabEdit::SpaceOpen { .. }
            | TabEdit::Send { .. } => {
                Err(Error::StaleTarget)
            }
            TabEdit::ToSpace { space, tab, to } => {
                for terminal in self.tab_terminals(&space, &tab) {
                    self.call(
                        "terminal.label",
                        json!({"terminal":terminal,"labels":{"herdr.space":to}}),
                    )?;
                }
                self.edit_doc(|doc| {
                    let tabs = tabs_mut(doc, &space)?;
                    let at = tabs
                        .iter()
                        .position(|t| t["id"] == tab.as_str())
                        .ok_or(Error::StaleTarget)?;
                    let item = tabs.remove(at);
                    tabs_mut(doc, &to)?.push(item);
                    Ok(())
                })
            }
        }
    }
    fn discover(&mut self) -> Result<String> {
        use base64::Engine;
        let result = self.call_slow(
            "machine.exec",
            json!({"argv":["tailscale","status","--json"],"timeout":5}),
        )?;
        if result["code"] != 0 || result["truncated"] == true {
            return Err(Error::Protocol(
                "tailscale status failed on bootstrap host".into(),
            ));
        }
        let bytes = base64::engine::general_purpose::STANDARD
            .decode(result["stdout"].as_str().unwrap_or(""))
            .map_err(|_| Error::Protocol("invalid command output".into()))?;
        let status: Value = serde_json::from_slice(&bytes)?;
        let mut peers = discovery_peers(&status)?;
        let deadline = Instant::now() + Duration::from_secs(30);
        for peer in &mut peers {
            if peer["self"] == true {
                peer["core"] = json!("available");
                continue;
            }
            if peer["online"] != true {
                peer["core"] = json!("offline");
                continue;
            }
            if Instant::now() >= deadline
                || !matches!(
                    peer["os"].as_str(),
                    Some("linux" | "macOS" | "windows" | "freebsd")
                )
            {
                continue;
            }
            let host = peer["host"].as_str().unwrap_or("");
            // Peer names enter argv, never a shell string. Reuse only the
            // bootstrap machine's existing trust; never accept new host keys.
            let probe = self.call_slow("machine.exec", json!({"argv":["ssh","-oBatchMode=yes","-oStrictHostKeyChecking=yes","-oConnectTimeout=2","--",host,"command -v herdr-core >/dev/null && herdr-core server status >/dev/null"],"timeout":3}))?;
            peer["core"] = json!(if probe["code"] == 0 {
                "available"
            } else {
                "unverified"
            });
        }
        Ok(json!({"peers":peers}).to_string())
    }
    fn refresh(&mut self) -> Result<()> {
        // Subscribe precedes reading the app document; doc events/missed force
        // another read. Polling also handles older cores and unlabelled workers.
        self.dirty = false;
        let list = self.call_slow("terminal.list", json!({}))?;
        #[cfg(feature = "android-ui")]
        {
            let mut state = lock(&self.owner.state);
            let selected = state.view["pane_id"].as_str();
            let terminal = list["terminals"].as_array().and_then(|terminals| {
                terminals.iter().find(|terminal| selected.is_some_and(|pane| address(terminal) == Some(pane)))
            });
            state.observe_footer_foreground(terminal, Instant::now());
        }
        let doc = self.call_slow("doc.get", json!({"name":"herdr-app"}))?;
        // Wolf's order of the projects (item 62), kept in this core's doc
        // projects.order by the desktop's sidebar; the phone's project
        // switcher reads it. A core without it (older, or never dragged) has
        // no order: birth order then.
        let order = self
            .call_slow("doc.get", json!({"name":"projects.order"}))
            .map(|o| o["value"].clone())
            .unwrap_or(Value::Null);
        let mut state = lock(&self.owner.state);
        project(&mut state.view, &list["terminals"], &doc["value"])?;
        if state.view["projects_order"] != order {
            state.view["projects_order"] = order;
            bump(&mut state.view);
        }
        Ok(())
    }
    fn attach(&mut self, terminal: &str, cols: u16, rows: u16) -> Result<()> {
        // This attach replaces any reattach still waiting for its turn.
        self.reattach = None;
        // Taken only once an attach of that pane is accepted (below): a pick
        // the core refuses leaves it for the fallback to the previous pane.
        let resumed = self.ended.as_deref() == Some(terminal);
        self.detach()?;
        {
            let mut state = lock(&self.owner.state);
            // The outgoing pane's grid is kept for the phone to save (item 77:
            // panes looked at online read offline). Only the grid object moves;
            // what is sent to the core, and when, is unchanged.
            if let Some(outgoing) = state.terminal.take() {
                let pane = state.view["pane_id"].as_str().unwrap_or("");
                if pane != terminal {
                    crate::remote::depart(self.owner.id, outgoing, pane.to_owned());
                }
            }
            #[cfg(feature = "android-ui")]
            state.invalidate_footer_foreground();
            state.render_id = 0;
            state.painted = false;
            state.view["state"] = json!("connecting");
            bump(&mut state.view);
        }
        // Herdr in front showing it, and how long ago Wolf last used the phone.
        let focus = self
            .focus
            .offered
            .then(|| (self.wants_visible(), self.owner.uses.idle_ms()));
        let uses = self.owner.uses.count();
        let mut params = attach_params(terminal, cols, rows, self.claim.offered, focus);
        if self.history_on_resize {
            params["resize_history"] = json!(RESIZE_HISTORY);
        }
        if resumed && self.history_on_resize {
            // The same pane again after the core ended its stream, on a path
            // that confirmed `resize_history`: the owner then owes one whole
            // history replacement (at once on the main screen, or on the
            // return to it), which replaces this grid's history; the phone
            // replays nothing itself. Elsewhere an ordinary attach (its
            // history, maybe some rows twice; on the alternate screen an old
            // owner sends none, so the main history is not brought back).
            params["resumed"] = json!(true);
        }
        let reply = self.call("terminal.attach", params)?;
        if resumed {
            self.ended = None;
        }
        // Only the owner's echo confirms it (relays pass on only that): no
        // echo, and the path cannot carry it, so it is not asked again.
        if self.history_on_resize && reply["resize_history"].as_u64().unwrap_or(0) == 0 {
            self.history_on_resize = false;
        }
        // Uses before this attach are in its idle_ms, not a WINDOW_FOCUS of their own.
        // Frames only where the pane's owner applies the rule through every hop:
        // the reply says focus_by_use as well as focus_updates.
        self.focus.frames =
            self.focus.offered && reply["focus_updates"] == true && reply["focus_by_use"] == true;
        self.focus.on = self.focus.frames && focus.is_some_and(|(focused, _)| focused);
        self.focus.sent_at = None;
        self.focus.uses = uses;
        self.focus.typed = 0;
        // A core (and path) that will send RESIZE before repainting says so;
        // an older one does not, and the phone's own size stays the grid.
        self.negotiated = reply["resize_notifications"] == true;
        self.claim.frames = self.claim.offered && reply["visibility_updates"] == true;
        self.claim.shown = false;
        self.claim.measured = false;
        self.claim.attached_at = Some(Instant::now());
        self.claim.parked = None;
        let channel = reply["channel"]
            .as_u64()
            .filter(|c| *c > 0 && *c <= u32::MAX as u64)
            .ok_or_else(|| Error::Protocol("invalid attach channel".into()))?
            as u32;
        let (tx, rx) = mpsc::sync_channel(256);
        let commands = self.owner.commands.clone();
        let input = StreamWrite {
            commands: commands.clone(),
            channel,
            kind: DATA,
        };
        // Answers the core left to this viewer (a cursor report, device
        // attributes): AUTOMATIC_INPUT where the core knows it, else DATA as before.
        let answers = StreamWrite {
            commands: commands.clone(),
            channel,
            kind: if self.automatic_input {
                AUTOMATIC_INPUT
            } else {
                DATA
            },
        };
        let session_slot = Arc::new(Mutex::new(Weak::new()));
        let output = StreamRead {
            rx,
            chunks: VecDeque::new(),
            commands: commands.clone(),
            channel,
            session: session_slot.clone(),
        };
        let terminal_session = gpui_rio::Session::start_with_replies(
            output,
            input,
            answers,
            move |cols, rows| {
                let mut bytes = Vec::from(cols.to_le_bytes());
                bytes.extend(rows.to_le_bytes());
                let _ = commands.try_send(Operation::Frame(channel, 3, bytes));
            },
            crate::shell::theme(&crate::model::palette()),
            cols,
            rows,
        )?;
        // The PTY's grid, set before a single replay byte can reach the parser
        // (the output channel opens just below): the first frame is laid out
        // at the program's width, never the phone's.
        if let Some((cols, rows)) = initial_grid(&reply) {
            terminal_session.set_grid(cols, rows);
        }
        *lock(&session_slot) = Arc::downgrade(&terminal_session);
        crate::remote::release_held();
        self.channel = channel;
        self.output = Some(tx);
        self.painted = false;
        let mut state = lock(&self.owner.state);
        state.terminal = Some(terminal_session);
        state.render_id = NEXT_RENDER.fetch_add(1, Ordering::Relaxed);
        state.painted = false;
        state.view["pane_id"] = json!(terminal);
        state.view["focused_pane"] = json!(terminal);
        state.view["state"] = json!("ready");
        state.view["status"] = json!("Connected to herdr-core");
        // An attach that landed answers any earlier refusal.
        if let Some(view) = state.view.as_object_mut() {
            view.remove("refused");
        }
        mark_focus(&mut state.view);
        bump(&mut state.view);
        Ok(())
    }
}
/// A change asked for from the phone's tab strip or rail, as JSON:
/// `{"op":"tab.rename","space":..,"tab":..,"name":..}`, `tab.move` with
/// `index`, `tab.close`, `tab.split`, `tab.space` with `to`; `space.rename`
/// with `name`, `space.move` with `index`, `space.new` with `kind`
/// (shell, claude or codex); `pane.interrupt` and `pane.kill` with `terminal`.
#[derive(Debug, PartialEq)]
pub(crate) enum TabEdit {
    Rename { space: String, tab: String, name: String },
    Move { space: String, tab: String, index: usize },
    Close { space: String, tab: String },
    Split { space: String, tab: String },
    ToSpace { space: String, tab: String, to: String },
    SpaceRename { space: String, name: String },
    SpaceMove { space: String, index: usize },
    SpaceNew { space: String, kind: &'static str },
    /// A new window of a project (the desktop's C-a c): a tab in `space`,
    /// in `folder` (else the lead's), its terminal labelled parent=<lead>
    /// so it is that project's window wherever its tab goes.
    WindowNew { space: String, lead: String, folder: Option<String>, kind: &'static str },
    /// One drag in the core doc projects.order, as herdr-projects-order moves it:
    /// `id` just before or after `target` in `scope`'s list (an [`OrderScope`]);
    /// `shown`: that list as drawn. A project's window (item 79, `windows.move`)
    /// is the scope `windows:<project>`; the groups and a group's projects
    /// (factory-r8f.8, `order.move`) are `groups` and `group:<name>`.
    OrderMove { scope: OrderScope, id: String, target: String, after: bool, shown: Vec<String> },
    Interrupt { terminal: String },
    Kill { terminal: String },
    /// A PM's sidebar group (factory-r8f.8): `terminal`'s herdr.group label set to
    /// `group`, or removed (None). Display only: no other label is written.
    GroupSet { terminal: String, group: Option<String> },
    DeaconStart { machine: String },
    GitLook { paths: Vec<String> },
    /// `limit`: lines to read, at most FILE_LINES (the phone's offline copies ask for fewer).
    FileRead { terminal: String, path: String, limit: u64 },
    SpaceOpen { folder: String },
    Send { terminal: String, text: String },
}
/// A JSON edit's bound: room for one compose command's text, JSON-escaped.
pub(crate) const MAX_ACTION: usize = 2 * crate::model::MAX_TEXT_BYTES + 1024;
impl TabEdit {
    pub(crate) fn parse(action: &str) -> Option<Self> {
        if !action.starts_with('{') || action.len() > MAX_ACTION {
            return None;
        }
        let value: Value = serde_json::from_str(action).ok()?;
        let id = |key: &str| {
            value[key]
                .as_str()
                .filter(|v| !v.is_empty() && v.len() <= 128 && !v.chars().any(char::is_control))
                .map(str::to_owned)
        };
        let name = || {
            let name = value["name"].as_str()?.trim();
            (!name.is_empty() && name.chars().count() <= 64 && !name.chars().any(char::is_control))
                .then(|| name.to_owned())
        };
        let index = || usize::try_from(value["index"].as_u64()?).ok().map(|i| i.min(512));
        // An absolute folder on that computer: bounded, no control characters.
        let folder = |v: &Value| {
            v.as_str()
                .filter(|p| p.starts_with('/') && p.len() <= 1024 && !p.chars().any(char::is_control))
                .map(str::to_owned)
        };
        Some(match value["op"].as_str()? {
            "tab.rename" => Self::Rename { space: id("space")?, tab: id("tab")?, name: name()? },
            "tab.move" => Self::Move { space: id("space")?, tab: id("tab")?, index: index()? },
            "tab.close" => Self::Close { space: id("space")?, tab: id("tab")? },
            "tab.split" => Self::Split { space: id("space")?, tab: id("tab")? },
            "tab.space" => Self::ToSpace { space: id("space")?, tab: id("tab")?, to: id("to")? },
            "space.rename" => Self::SpaceRename { space: id("space")?, name: name()? },
            "space.move" => Self::SpaceMove { space: id("space")?, index: index()? },
            "space.new" => Self::SpaceNew {
                space: id("space")?,
                kind: match value["kind"].as_str()? {
                    "shell" => "shell",
                    "claude" => "claude",
                    "codex" => "codex",
                    _ => return None,
                },
            },
            "window.new" => Self::WindowNew {
                space: id("space")?,
                // A lead on this core only: a parent never names another computer's terminal.
                lead: id("lead").filter(|l| !l.contains('/'))?,
                folder: match &value["folder"] {
                    Value::Null => None,
                    v => Some(folder(v)?),
                },
                kind: match value["kind"].as_str()? {
                    "shell" => "shell",
                    "claude" => "claude",
                    "codex" => "codex",
                    _ => return None,
                },
            },
            "windows.move" => {
                // As herdr-projects-order bounds them: 1 to 200 characters, at most 1000 names.
                let name = |v: &Value| {
                    v.as_str()
                        .filter(|x| !x.is_empty() && x.len() <= 200 && !x.chars().any(char::is_control))
                        .map(str::to_owned)
                };
                let shown = value["shown"].as_array()?;
                if shown.len() > 1000 {
                    return None;
                }
                Self::OrderMove {
                    scope: OrderScope::Windows(name(&value["project"])?),
                    id: name(&value["id"])?,
                    target: name(&value["target"])?,
                    after: value["after"].as_bool()?,
                    shown: shown.iter().map(name).collect::<Option<Vec<_>>>()?,
                }
            }
            "order.move" => {
                let name = |v: &Value| {
                    v.as_str()
                        .filter(|x| !x.is_empty() && x.len() <= 200 && !x.chars().any(char::is_control))
                        .map(str::to_owned)
                };
                let shown = value["shown"].as_array()?;
                if shown.len() > 1000 {
                    return None;
                }
                let (id, target) = (name(&value["id"])?, name(&value["target"])?);
                if id == target {
                    return None;
                }
                Self::OrderMove {
                    scope: OrderScope::parse(value["scope"].as_str()?)?,
                    id,
                    target,
                    after: value["after"].as_bool()?,
                    shown: shown.iter().map(name).collect::<Option<Vec<_>>>()?,
                }
            }
            "pane.interrupt" => Self::Interrupt { terminal: id("terminal")? },
            "pane.kill" => Self::Kill { terminal: id("terminal")? },
            "group.set" => Self::GroupSet {
                // A lead on this core only, as window.new's.
                terminal: id("terminal").filter(|t| !t.contains('/'))?,
                group: match &value["group"] {
                    Value::Null => None,
                    v => Some(
                        v.as_str()
                            .map(str::trim)
                            .filter(|g| !g.is_empty() && *g != "none" && g.chars().count() <= 40 && !g.chars().any(char::is_control))?
                            .to_owned(),
                    ),
                },
            },
            "deacon.start" => Self::DeaconStart { machine: name().unwrap_or_default() },
            "git.look" => Self::GitLook {
                paths: {
                    let list = value["paths"].as_array()?;
                    if list.is_empty() || list.len() > 96 {
                        return None;
                    }
                    list.iter().map(&folder).collect::<Option<Vec<_>>>()?
                },
            },
            "file.read" => Self::FileRead {
                terminal: id("terminal")?,
                path: value["path"]
                    .as_str()
                    .filter(|p| !p.is_empty() && p.len() <= 4096 && !p.chars().any(char::is_control))
                    .map(str::to_owned)?,
                limit: match &value["limit"] {
                    Value::Null => FILE_LINES,
                    limit => limit.as_u64().filter(|l| (1..=FILE_LINES).contains(l))?,
                },
            },
            "space.open" => Self::SpaceOpen { folder: folder(&value["folder"])? },
            "pane.send" => Self::Send {
                terminal: id("terminal")?,
                text: value["text"]
                    .as_str()
                    .filter(|t| !t.trim().is_empty() && t.chars().count() <= 1000 && !t.chars().any(char::is_control))
                    .map(str::to_owned)?,
            },
            _ => return None,
        })
    }
}
/// The terminal.run of a project's new window (`WindowNew`): a tab of its own
/// in `space`, in `cwd`, working for `lead` (parent=<lead>), as the desktop's
/// C-a c adopts it; an agent through the login shell, as `space.new` starts one.
fn window_run(space: &str, tab: &str, lead: &str, cwd: Value, kind: &str, cols: u16, rows: u16) -> Value {
    let mut run = json!({"cwd":cwd,"keep":true,"labels":{"herdr.space":space,"herdr.tab":tab,"parent":lead},"size":{"cols":cols,"rows":rows},"display":display()});
    if kind != "shell" {
        run["line"] = json!(format!("exec \"${{SHELL:-/bin/sh}}\" -lic {kind}"));
        run["labels"]["agent.kind"] = json!(kind);
    }
    run
}
/// The herdr-app tab of a project's new window: unnamed, so it goes by its
/// agent's task, as a tab the desktop makes does.
fn window_tab(tab: &str, terminal: &str, block: &str, kind: &str) -> Value {
    let root = json!({"children":[{"content":{"terminal":terminal},"id":block,"type":"block"}],"id":tab,"type":"row"});
    json!({"id":tab,"name":kind,"named":false,"root":root})
}
/// Where a project's new window goes: its lead's space, named as the phone
/// shows it; for a lead in no space ("loose", no herdr.space label) a space
/// of its own, named after the lead's project, else its folder.
fn window_space(view: &Value, space: &str, lead: &str) -> (String, String) {
    if space != "loose" && !space.is_empty() {
        let name = view["workspaces"]
            .as_array()
            .and_then(|s| s.iter().find(|s| s["id"] == space))
            .and_then(|s| s["label"].as_str())
            .unwrap_or(space);
        return (space.to_owned(), name.to_owned());
    }
    let pane = view["panes"].as_array().and_then(|p| p.iter().find(|p| p["id"] == lead));
    let text = |key: &str| pane.and_then(|p| p[key].as_str()).filter(|v| !v.is_empty());
    let name = text("project")
        .or_else(|| text("cwd").and_then(|cwd| cwd.trim_end_matches('/').rsplit('/').next()).filter(|n| !n.is_empty()))
        .unwrap_or(lead);
    let id = format!("mobile-{}-{}", std::process::id(), NEXT_RENDER.fetch_add(1, Ordering::Relaxed));
    (id, name.to_owned())
}
/// Puts a new window's tab in herdr-app's layout: appended to `space`'s
/// tabs, the space itself added when the layout lacks it (a space only
/// labels name), so the window can be renamed, moved and closed.
fn add_window_tab(doc: &mut Value, space: &str, name: &str, folder: &Value, entry: &Value) -> Result<()> {
    if doc.is_null() {
        *doc = json!({});
    }
    if !doc["spaces"].is_array() {
        doc["spaces"] = json!([]);
    }
    let spaces = doc["spaces"].as_array_mut().ok_or(Error::StaleTarget)?;
    match spaces.iter_mut().find(|s| s["id"] == space) {
        Some(found) => {
            if !found["tabs"].is_array() {
                found["tabs"] = json!([]);
            }
            let tabs = found["tabs"].as_array_mut().ok_or(Error::StaleTarget)?;
            if !tabs.iter().any(|t| t["id"] == entry["id"]) {
                tabs.push(entry.clone());
            }
            Ok(())
        }
        None => {
            spaces.push(json!({"folder":folder,"id":space,"name":name,"tabs":[entry]}));
            Ok(())
        }
    }
}
/// A rename names the tab for good: `named`, so its window goes by this name
/// and no longer by its agent's task.
fn rename_tab(doc: &mut Value, space: &str, tab: &str, name: &str) -> Result<()> {
    let item = tab_mut(doc, space, tab)?;
    item["name"] = json!(name);
    item["named"] = json!(true);
    Ok(())
}
/// Which list of projects.order a drop moves, as herdr-projects-order names
/// them: `repos`, `repo:<name>` (`repo:` for no repo), `groups`,
/// `group:<name>` (factory-r8f.8), or `windows:<project>` (item 79).
#[derive(Debug, Clone, PartialEq, Eq)]
pub(crate) enum OrderScope {
    Repos,
    Repo(String),
    Groups,
    Group(String),
    Windows(String),
}
impl OrderScope {
    fn parse(scope: &str) -> Option<Self> {
        if scope.len() > 210 || scope.chars().any(char::is_control) {
            return None;
        }
        match scope {
            "repos" => return Some(Self::Repos),
            "groups" => return Some(Self::Groups),
            _ => {}
        }
        if let Some(repo) = scope.strip_prefix("repo:") {
            return Some(Self::Repo(repo.to_owned()));
        }
        if let Some(group) = scope.strip_prefix("group:") {
            return (!group.is_empty()).then(|| Self::Group(group.to_owned()));
        }
        let project = scope.strip_prefix("windows:")?;
        (!project.is_empty()).then(|| Self::Windows(project.to_owned()))
    }
    /// Its list in the doc: the top key, and the key in that object (None: a top list).
    fn place(&self) -> (&'static str, Option<&str>) {
        match self {
            Self::Repos => ("repos", None),
            Self::Groups => ("groups", None),
            Self::Repo(repo) => ("projects", Some(repo)),
            Self::Group(group) => ("grouped", Some(group)),
            Self::Windows(project) => ("windows", Some(project)),
        }
    }
}
/// herdr-projects-order's bound on one list.
const ORDER_MAX_IDS: usize = 1000;
/// The keys herdr-projects-order reads; any other key is a newer writer's, kept as it is.
const ORDER_KNOWN: [&str; 5] = ["repos", "projects", "windows", "groups", "grouped"];
/// herdr-projects-order's reading of the projects.order doc: lists of names
/// only; `windows`, `groups` and `grouped` only once something is stored
/// there, so older readers see the doc as before; and every key it doesn't
/// know kept as it is, so a move here never erases another surface's order.
fn order_clean(value: &Value) -> Value {
    let names = |v: &Value| -> Vec<Value> {
        v.as_array()
            .map(|a| a.iter().filter(|x| x.is_string()).cloned().collect())
            .unwrap_or_default()
    };
    let lists = |v: &Value| -> serde_json::Map<String, Value> {
        v.as_object()
            .map(|o| o.iter().filter(|(_, ids)| ids.is_array()).map(|(k, ids)| (k.clone(), Value::from(names(ids)))).collect())
            .unwrap_or_default()
    };
    let mut out = json!({"repos": names(&value["repos"]), "projects": lists(&value["projects"])});
    let windows = lists(&value["windows"]);
    if !windows.is_empty() {
        out["windows"] = Value::from(windows);
    }
    let groups = names(&value["groups"]);
    if !groups.is_empty() {
        out["groups"] = Value::from(groups);
    }
    let grouped = lists(&value["grouped"]);
    if !grouped.is_empty() {
        out["grouped"] = Value::from(grouped);
    }
    if let Some(all) = value.as_object() {
        for (key, kept) in all.iter().filter(|(key, _)| !ORDER_KNOWN.contains(&key.as_str())) {
            out[key] = kept.clone();
        }
    }
    out
}
/// One drag in projects.order, as herdr-projects-order makes it: `scope`'s
/// stored list (a project's windows less those no longer shown: they ended),
/// then the names `shown` adds, with `id` just before or after `target`.
/// Only that list changes. Ok(None): nothing moves (either is missing, or it
/// is already there); Err(Capacity): the list would pass herdr-projects-order's
/// 1000 names, which it refuses too.
fn order_move(value: &Value, scope: &OrderScope, id: &str, target: &str, after: bool, shown: &[String]) -> Result<Option<Value>> {
    let mut v = order_clean(value);
    let (top, key) = scope.place();
    let list = match key {
        None => &v[top],
        Some(key) => &v[top][key],
    };
    let stored: Vec<String> = list
        .as_array()
        .map(|a| a.iter().filter_map(|x| x.as_str().map(str::to_owned)).collect())
        .unwrap_or_default();
    let windows = matches!(scope, OrderScope::Windows(_));
    let stored: Vec<String> =
        if windows && !shown.is_empty() { stored.into_iter().filter(|x| shown.contains(x)).collect() } else { stored };
    let mut ids = stored.clone();
    ids.extend(shown.iter().filter(|x| !stored.contains(x)).cloned());
    if id == target || !ids.iter().any(|x| x == id) || !ids.iter().any(|x| x == target) {
        return Ok(None);
    }
    let mut rest: Vec<String> = ids.iter().filter(|x| *x != id).cloned().collect();
    let Some(at) = rest.iter().position(|x| x == target).map(|i| i + usize::from(after)) else {
        return Ok(None);
    };
    rest.insert(at, id.to_owned());
    if rest == ids {
        return Ok(None);
    }
    if rest.len() > ORDER_MAX_IDS {
        return Err(Error::Capacity);
    }
    match key {
        None => v[top] = json!(rest),
        Some(key) => {
            if !v[top].is_object() {
                v[top] = json!({});
            }
            v[top][key] = json!(rest);
        }
    }
    Ok(Some(v))
}
/// Appends one space to herdr-app's document, keeping every space and tab
/// already there (edit_doc hands it the freshest version on each try).
fn add_space(doc: &mut Value, entry: &Value) -> Result<()> {
    if doc.is_null() {
        *doc = json!({});
    }
    if !doc["spaces"].is_array() {
        doc["spaces"] = json!([]);
    }
    let spaces = doc["spaces"].as_array_mut().ok_or(Error::StaleTarget)?;
    if !spaces.iter().any(|s| s["id"] == entry["id"]) {
        spaces.push(entry.clone());
    }
    Ok(())
}
/// What the phone hears when terminal.send fails: `refused: "blocked"` (code
/// 9, as hc exits) when the program waits on its own question, else the error.
fn send_refusal(error: &Error) -> Value {
    match error {
        Error::Rpc { kind, .. } if kind == "blocked" => {
            json!({"queued":false,"refused":"blocked","code":9})
        }
        _ => json!({"queued":false,"error":error.to_string()}),
    }
}
/// How long a bounded transcript read may take: the core formats only the
/// rows asked for, so this is generous.
pub(crate) const READ_WAIT: Duration = Duration::from_secs(10);
/// A bounded read's answer as the phone keeps it: the text (within the bound
/// asked for, else a protocol error), whether the core cut it, whether it is
/// the screen only, and the
/// incarnation it read, or null when the core could not say (then the phone
/// keeps nothing of it).
fn transcript_reply(result: &Value, max_bytes: usize) -> Result<Value> {
    let text = result["text"]
        .as_str()
        .ok_or_else(|| Error::Protocol("missing read text".into()))?;
    // The bound is on JSON-escaped bytes, which are never fewer than the raw ones.
    if text.len() > max_bytes {
        return Err(Error::Protocol("read past its bound".into()));
    }
    let incarnation = result["incarnation"]
        .as_str()
        .filter(|i| !i.is_empty() && i.len() <= 64 && i.chars().all(|c| c.is_ascii_alphanumeric()));
    Ok(json!({
        "supported": true,
        "text": text,
        "truncated": result["truncated"].as_bool().unwrap_or(false),
        // The screen alone (a full-screen program read without stitching), not
        // its history: offline, the phone says so instead of offering more.
        "screen": result["how"] == "screen",
        "incarnation": incarnation,
    }))
}
/// A put sends up to 8 MiB (about 11 MB of base64) down the phone's link.
pub(crate) const PUT_WAIT: Duration = Duration::from_secs(60);
/// The phone's git look, one `sh -c` over the folders it shows (see git_look).
const GIT_LOOK: &str = r#"seen='
'
tab=$(printf '\t')
counts=''
for p in "$@"; do
  c=$(git -C "$p" rev-parse --path-format=absolute --git-common-dir 2>/dev/null) || { printf 'P\t%s\n' "$p"; continue; }
  t=$(git -C "$p" rev-parse --show-toplevel 2>/dev/null)
  b=$(git -C "$p" symbolic-ref --short -q HEAD 2>/dev/null)
  hit=0
  a='?'; d='?'
  while IFS="$tab" read -r top ahead behind; do
    if [ "$top" = "$t" ]; then
      a=$ahead; d=$behind; hit=1; break
    fi
  done <<EOF
$counts
EOF
  if [ "$hit" = 0 ]; then
    if git -C "$p" rev-parse --verify '@{upstream}' >/dev/null 2>&1; then
      ab=$(git -C "$p" rev-list --left-right --count 'HEAD...@{upstream}' 2>/dev/null) || ab=''
      if [ -n "$ab" ]; then
        IFS="$tab" read -r a d <<EOF
$ab
EOF
      fi
    else
      a=0; d=0
    fi
    counts="$counts
$t$tab$a$tab$d"
  fi
  printf 'P\t%s\t%s\t%s\t%s\t%s\t%s\n' "$p" "$c" "$t" "$b" "$a" "$d"
  case "$seen" in *"
$c
"*) continue;; esac
  seen="$seen$c
"
  u=$(git -C "$p" remote get-url origin 2>/dev/null) || u=$(git -C "$p" remote -v 2>/dev/null | sed -n '1s/^[^	]*	\([^ ]*\).*/\1/p')
  printf 'U\t%s\t%s\n' "$c" "$u"
  printf 'R\t%s\n' "$c"
  w=$(git -C "$p" worktree list --porcelain 2>/dev/null)
  if [ "${#w}" -gt 2000000 ]; then
    printf '%s' "$w" | head -c 2000000; printf '\nT\n'
  else
    printf '%s\n' "$w"
  fi
  printf '\nE\n'
done"#;
/// terminal.attach's parameters: the pane's size is an offer; the phone asks
/// for RESIZE frames so it can follow the PTY's authoritative grid.
fn attach_params(
    terminal: &str,
    cols: u16,
    rows: u16,
    visibility: bool,
    focus: Option<(bool, u64)>,
) -> Value {
    let mut params = json!({"terminal":terminal,"cols":cols,"rows":rows,"history":1000,"display":display(),"resize_notifications":true});
    if visibility {
        // Hidden until a measured RESIZE: the attach-time size is only the
        // phone's estimate and must not lead the grid.
        params["visibility_updates"] = json!(true);
        params["visible"] = json!(false);
    }
    if let Some((focused, idle_ms)) = focus {
        // Item 71: whether Herdr is in front showing this pane, and how long
        // ago Wolf last used the phone (an attach he caused: just now).
        params["focus_updates"] = json!(true);
        params["focused"] = json!(focused);
        params["idle_ms"] = json!(idle_ms);
    }
    params
}
/// Whether the core's hello lists `feature`.
fn offers(hello: &Value, feature: &str) -> bool {
    hello["result"]["features"]
        .as_array()
        .is_some_and(|features| features.iter().any(|f| f == feature))
}
/// The PTY's grid from an attach reply, when the path negotiated RESIZE.
fn initial_grid(reply: &Value) -> Option<(u16, u16)> {
    if reply["resize_notifications"] != true {
        return None;
    }
    let size = |key: &str| reply["terminal"][key].as_u64().and_then(|v| u16::try_from(v).ok()).filter(|v| *v > 0);
    Some((size("cols")?, size("rows")?))
}
/// A RESIZE frame's payload: columns then rows, little-endian u16 each.
fn resize_frame(bytes: &[u8]) -> Option<(u16, u16)> {
    match bytes {
        [c0, c1, r0, r1] => {
            let (cols, rows) = (u16::from_le_bytes([*c0, *c1]), u16::from_le_bytes([*r0, *r1]));
            (cols > 0 && rows > 0).then_some((cols, rows))
        }
        _ => None,
    }
}
/// The deacon's shell line: from ~/Config when it exists (Wolf's own config,
/// never a user repo), else home; Claude with its brief as one argument. The
/// machine name is reduced to plain characters, so it can sit in the quotes.
fn deacon_line(machine: &str) -> String {
    let name: String = machine.chars().filter(|c| !c.is_control()).take(64).collect();
    let name = name.trim();
    let here = if name.is_empty() { "this computer".to_owned() } else { format!("this computer ({name})") };
    let task = format!(
        "You are the Deacon for {here}; use the deacon skill. If you are not labelled yet, run hc label $HC_TERMINAL_ID role=deacon. Take inventory only until Wolf gives you a goal. Do not start duplicate project managers or workers, and do not restart or switch anything."
    );
    // The terminal starts in the Deacon's folder (terminal.run's cwd).
    format!("exec \"${{SHELL:-/bin/sh}}\" -lic 'claude \"$0\"' {}", sh_quote(&task))
}
/// Makes and prints the Deacon's folder on the computer that runs it. Fixed
/// text: nothing from the phone is put into it.
const DEACON_DIR: &str = r#"mkdir -p "$HOME/.herdr/deacon" && cd "$HOME/.herdr/deacon" && pwd"#;
/// The folder DEACON_DIR printed: one absolute path ending in .herdr/deacon.
fn deacon_dir(stdout: &str) -> Option<String> {
    let path = stdout.strip_suffix('\n').unwrap_or(stdout);
    (path.starts_with('/')
        && path.trim_end_matches('/').ends_with("/.herdr/deacon")
        && !path.chars().any(char::is_control))
    .then(|| path.to_owned())
}
/// Lines the file view reads at most; a longer file says it was cut.
const FILE_LINES: u64 = 20_000;
/// What machine.exec wrote to stdout, as text.
fn exec_stdout(result: &Value) -> Result<String> {
    use base64::Engine;
    let bytes = base64::engine::general_purpose::STANDARD
        .decode(result["stdout"].as_str().unwrap_or(""))
        .map_err(|_| Error::Protocol("invalid command output".into()))?;
    Ok(String::from_utf8_lossy(&bytes).into_owned())
}
/// An `open_file` event (an agent's `hc open-file`, proposed for herdr-core):
/// `{"terminal","path","line","seq"}` for the file view, or None for any
/// other message or a malformed one.
fn open_file_event(message: &Value) -> Option<Value> {
    let params = &message["params"];
    if message["method"] != "event" || params["type"] != "open_file" {
        return None;
    }
    let terminal = params["terminal"].as_str().filter(|t| !t.is_empty() && t.len() <= 128)?;
    let path = params["data"]["path"]
        .as_str()
        .filter(|p| p.starts_with('/') && p.len() <= 4096 && !p.chars().any(char::is_control))?;
    let line = params["data"]["line"].as_u64().filter(|l| *l <= u64::from(u32::MAX)).unwrap_or(0);
    Some(json!({"terminal":terminal,"path":path,"line":line,"seq":params["seq"].as_u64().unwrap_or(0)}))
}
/// One POSIX shell word for any text: single quotes, each ' as '\''.
fn sh_quote(text: &str) -> String {
    format!("'{}'", text.replace('\'', r"'\''"))
}
/// This core's one deacon, as the Fleet picks it: a live local terminal
/// labelled role=deacon (oldest first, then address), else the first
/// unlabelled agent in a space named "deacon" (space, then tab order).
/// Federated `machine/tN` entries belong to other computers and never count.
fn canonical_deacon(terminals: &Value, doc: &Value) -> Option<String> {
    let live: Vec<&Value> = terminals
        .as_array()?
        .iter()
        .filter(|t| t["state"] == "live")
        .filter(|t| address(t).is_some_and(|a| !a.contains('/')))
        .collect();
    fn role(t: &Value) -> Option<&str> {
        t["labels"]["role"].as_str().filter(|r| !r.is_empty() && *r != "none")
    }
    let labelled = live
        .iter()
        .filter(|t| role(t) == Some("deacon"))
        .min_by_key(|t| (t["created_ms"].as_u64().unwrap_or(u64::MAX), address(t).unwrap_or("").to_owned()));
    if let Some(t) = labelled {
        return address(t).map(str::to_owned);
    }
    let spaces = doc["spaces"].as_array()?;
    let place = |t: &Value| -> Option<(usize, usize)> {
        let space = t["labels"]["herdr.space"].as_str()?;
        let tab = t["labels"]["herdr.tab"].as_str().unwrap_or("");
        let at = spaces.iter().position(|s| {
            s["id"] == space && s["name"].as_str().is_some_and(|n| n.eq_ignore_ascii_case("deacon"))
        })?;
        let tabs = spaces[at]["tabs"].as_array();
        let tab_at = tabs.and_then(|ts| ts.iter().position(|x| x["id"] == tab)).unwrap_or(usize::MAX);
        Some((at, tab_at))
    };
    let agent = |t: &Value| {
        t["status"]["state"].as_str().is_some_and(|s| !s.is_empty())
            || t["labels"]["agent.kind"].as_str().is_some_and(|k| !k.is_empty() && k != "none")
    };
    live.iter()
        .filter(|t| role(t).is_none() && agent(t))
        .filter_map(|t| place(t).map(|p| (p, t["created_ms"].as_u64().unwrap_or(u64::MAX), *t)))
        .min_by_key(|(p, created, t)| (*p, *created, address(t).unwrap_or("").to_owned()))
        .and_then(|(_, _, t)| address(t).map(str::to_owned))
}
/// A live local terminal already at `folder`: one in a space whose folder it
/// is, else one whose working directory is in it.
fn existing_at(terminals: &Value, doc: &Value, folder: &str) -> Option<String> {
    let folder = folder.trim_end_matches('/');
    let within = |path: &str| {
        let path = path.trim_end_matches('/');
        path == folder || path.strip_prefix(folder).is_some_and(|rest| rest.starts_with('/'))
    };
    let live: Vec<&Value> = terminals
        .as_array()?
        .iter()
        .filter(|t| t["state"] == "live" && address(t).is_some_and(|a| !a.contains('/')))
        .collect();
    let spaces: Vec<&str> = doc["spaces"]
        .as_array()
        .map(|list| {
            list.iter()
                .filter(|s| s["folder"].as_str().is_some_and(|f| f.trim_end_matches('/') == folder))
                .filter_map(|s| s["id"].as_str())
                .collect()
        })
        .unwrap_or_default();
    live.iter()
        .find(|t| t["labels"]["herdr.space"].as_str().is_some_and(|s| spaces.contains(&s)))
        .or_else(|| live.iter().find(|t| t["cwd"].as_str().is_some_and(within)))
        .and_then(|t| address(t).map(str::to_owned))
}
fn address(terminal: &Value) -> Option<&str> {
    terminal["address"].as_str().or(terminal["id"].as_str())
}
fn tabs_mut<'a>(doc: &'a mut Value, space: &str) -> Result<&'a mut Vec<Value>> {
    doc["spaces"]
        .as_array_mut()
        .and_then(|spaces| spaces.iter_mut().find(|s| s["id"] == space))
        .and_then(|s| s["tabs"].as_array_mut())
        .ok_or(Error::StaleTarget)
}
fn tab_mut<'a>(doc: &'a mut Value, space: &str, tab: &str) -> Result<&'a mut Value> {
    tabs_mut(doc, space)?
        .iter_mut()
        .find(|t| t["id"] == tab)
        .ok_or(Error::StaleTarget)
}
/// One item of an attached stream, in the core's order.
enum Chunk {
    Data(Vec<u8>),
    /// The PTY's authoritative grid (a RESIZE frame).
    Grid(u16, u16),
}
struct StreamRead {
    rx: mpsc::Receiver<Chunk>,
    chunks: VecDeque<u8>,
    commands: mpsc::SyncSender<Operation>,
    channel: u32,
    /// The session this stream feeds, for in-order grid changes.
    session: Arc<Mutex<Weak<gpui_rio::Session>>>,
}
impl Read for StreamRead {
    /// A Grid item is reached only once every earlier byte was handed out
    /// (and so parsed: Rio parses each read before reading again). It is
    /// queued with the session and this read returns no bytes, so Rio's
    /// reader applies it next, at its parser boundary: it first ends a
    /// synchronized update pending under the old grid, then switches grids,
    /// and only then reads the DATA after it.
    fn read(&mut self, buffer: &mut [u8]) -> io::Result<usize> {
        if buffer.is_empty() {
            return Ok(0);
        }
        while self.chunks.is_empty() {
            match self.rx.recv_timeout(Duration::from_millis(50)) {
                Ok(Chunk::Data(bytes)) => self.chunks.extend(bytes),
                Ok(Chunk::Grid(cols, rows)) => {
                    if let Some(session) = lock(&self.session).upgrade() {
                        session.queue_grid(cols, rows);
                    }
                    return Err(io::ErrorKind::TimedOut.into());
                }
                Err(mpsc::RecvTimeoutError::Timeout) => return Err(io::ErrorKind::TimedOut.into()),
                Err(_) => return Ok(0),
            }
        }
        let n = buffer.len().min(self.chunks.len());
        for byte in &mut buffer[..n] {
            *byte = self
                .chunks
                .pop_front()
                .ok_or(io::ErrorKind::UnexpectedEof)?;
        }
        self.commands
            .try_send(Operation::Frame(
                self.channel,
                1,
                (n as u32).to_le_bytes().to_vec(),
            ))
            .map_err(|_| io::ErrorKind::BrokenPipe)?;
        Ok(n)
    }
}
struct StreamWrite {
    commands: mpsc::SyncSender<Operation>,
    channel: u32,
    /// DATA, or AUTOMATIC_INPUT for the terminal's own answers.
    kind: u8,
}
impl Write for StreamWrite {
    fn write(&mut self, bytes: &[u8]) -> io::Result<usize> {
        self.commands
            .try_send(Operation::Frame(self.channel, self.kind, bytes.to_vec()))
            .map_err(|_| io::ErrorKind::BrokenPipe)?;
        Ok(bytes.len())
    }
    fn flush(&mut self) -> io::Result<()> {
        Ok(())
    }
}

/// The palette as herdr-core display facts: the core answers the program's
/// color queries (OSC 10/11) with them and strips those queries from the
/// stream, so the phone's emulator never answers twice.
fn display() -> Value {
    let palette = crate::model::palette();
    json!({
        "fg": format!("#{:06x}", palette.foreground),
        "bg": format!("#{:06x}", palette.background),
    })
}

pub(crate) fn run(
    owner: &Arc<Session>,
    mut socket: UnixStream,
    cols: u16,
    rows: u16,
    active: bool,
    commands: mpsc::Receiver<Operation>,
) -> Result<()> {
    socket.set_read_timeout(Some(Duration::from_millis(50)))?;
    socket.set_write_timeout(Some(Duration::from_secs(5)))?;
    socket.write_all(b"{\"jsonrpc\":\"2.0\",\"id\":0,\"method\":\"hello\",\"params\":{\"generation\":1,\"framed\":true}}\n")?;
    // Preserve bytes read past the plain-mode newline in the framed decoder.
    let mut hello = Vec::new();
    let deadline = Instant::now() + Duration::from_secs(15);
    loop {
        if owner.cancel.is_cancelled() {
            return Err(Error::Closed);
        }
        if Instant::now() >= deadline {
            return Err(Error::Protocol("hello timed out".into()));
        }
        let mut byte = [0];
        match socket.read(&mut byte) {
            Ok(0) => return Err(Error::Closed),
            Ok(_) if byte[0] == b'\n' => break,
            Ok(_) => hello.push(byte[0]),
            Err(e)
                if matches!(
                    e.kind(),
                    io::ErrorKind::WouldBlock
                        | io::ErrorKind::TimedOut
                        | io::ErrorKind::Interrupted
                ) =>
            {
                continue;
            }
            Err(e) => return Err(e.into()),
        }
        if hello.len() > 1024 * 1024 {
            return Err(Error::Capacity);
        }
    }
    let hello: Value = serde_json::from_slice(&hello)?;
    if hello["id"] != 0 || hello["result"]["generation"] != 1 {
        return Err(Error::Protocol("incompatible herdr-core handshake".into()));
    }
    {
        let mut state = lock(&owner.state);
        state.view["boot_id"] = hello["result"]["core"]["id"].clone();
        // What the phone may rely on from this core, for its offline transcripts.
        state.view["features"] = json!({
            "read_bounded": offers(&hello, "read-bounded"),
            "incarnation": offers(&hello, "incarnation"),
        });
    }
    let mut connection = Connection {
        socket,
        late_replies: 0,
        commands: Some(&commands),
        decoder: Decoder::default(),
        owner,
        serial: 0,
        channel: 0,
        output: None,
        events: 0,
        event_carry: Vec::new(),
        dirty: false,
        negotiated: false,
        claim: Claim {
            offered: offers(&hello, "viewer_visibility"),
            ..Claim::default()
        },
        history_on_resize: offers(&hello, "resize_history"),
        reattach: None,
        reattached_at: None,
        ended: None,
        automatic_input: offers(&hello, "automatic_input"),
        transcripts: offers(&hello, "read-bounded") && offers(&hello, "incarnation"),
        held: None,
        focus: Focus {
            offered: offers(&hello, "viewer_focus") && offers(&hello, "focus_by_use"),
            ..Focus::default()
        },
        painted: false,
    };
    let result = serve(&mut connection, cols, rows, active, &commands);
    connection.release();
    match result {
        // Cancelled while waiting on the socket: the phone closed the session,
        // which is how it ends normally, not a failure.
        Err(Error::Closed) if owner.cancel.is_cancelled() => Ok(()),
        result => result,
    }
}

fn serve(
    connection: &mut Connection<'_>,
    mut cols: u16,
    mut rows: u16,
    active: bool,
    commands: &mpsc::Receiver<Operation>,
) -> Result<()> {
    let owner = connection.owner;
    let subscribed = connection.call("events.subscribe", json!({}))?;
    connection.events = subscribed["channel"]
        .as_u64()
        .ok_or_else(|| Error::Protocol("missing event channel".into()))?
        as u32;
    connection.refresh()?;
    let first = lock(&owner.state).view["panes"]
        .as_array()
        .and_then(|panes| panes.first())
        .and_then(|p| p["id"].as_str())
        .map(str::to_owned);
    if let Some(first) = first.as_ref().filter(|_| active) {
        if connection.may_attach() {
            connection.attach(first, cols, rows)?;
        } else {
            connection.claim.parked = Some(first.clone());
        }
    } else {
        let mut state = lock(&owner.state);
        state.view["state"] = json!("ready");
        state.view["status"] = json!(if first.is_some() {
            "Connected to herdr-core"
        } else {
            "No live terminals on this host"
        });
        bump(&mut state.view);
    }
    let mut refreshed = Instant::now();
    while !owner.cancel.is_cancelled() {
        for _ in 0..64 {
            let next = connection.held.take().map_or_else(|| commands.try_recv(), Ok);
            match next {
                Ok(Operation::Discover(reply)) => {
                    let _ = reply.send(connection.discover());
                }
                Ok(Operation::Action(action, reply, until)) => {
                    // Its caller gave up (and was told Busy): never run it late, so a retry
                    // cannot start a second shell or Deacon.
                    if until.is_some_and(|until| Instant::now() >= until) {
                        let _ = reply.send(Err(Error::Busy));
                    } else {
                        let _ = reply.send(connection.action(&action, cols, rows));
                    }
                }
                Ok(Operation::Put(file, reply)) => {
                    let _ = reply.send(connection.put(&file));
                }
                Ok(Operation::Compose(compose, reply)) => connection.compose(compose, &reply)?,
                Ok(Operation::Read(request, reply)) => {
                    let _ = reply.send(connection.read_transcript(&request, commands));
                }
                Ok(Operation::Frame(channel, kind, bytes)) if channel == connection.channel => {
                    // Later attaches and new shells start at the viewer's
                    // current size, not the size it connected with.
                    if let (RESIZE, [c0, c1, r0, r1]) = (kind, bytes.as_slice()) {
                        (cols, rows) = (
                            u16::from_le_bytes([*c0, *c1]),
                            u16::from_le_bytes([*r0, *r1]),
                        );
                        // Hidden, the size is only remembered: it is offered
                        // again, fresh, when the viewer is shown.
                        if !connection.wants_visible() {
                            continue;
                        }
                        connection.claim.measured = true;
                    }
                    write_frame(&mut connection.socket, channel, kind, &bytes)?
                }
                Ok(_) => {}
                Err(_) => break,
            }
        }
        connection.sync_claim(cols, rows)?;
        connection.sync_focus()?;
        let mut switch = lock(&owner.focus).take();
        if !connection.may_attach() {
            // Hidden without VISIBILITY frames: remember the pick, claim nothing.
            if let Some((terminal, _)) = switch.take() {
                // A person's pick: an owed reattach of another pane is off.
                if connection.reattach.as_ref().is_some_and(|owed| *owed != terminal) {
                    connection.reattach = None;
                }
                connection.claim.parked = Some(terminal);
            }
        }
        if let Some((terminal, asked)) = switch {
            let previous = lock(&owner.state).view["pane_id"]
                .as_str()
                .unwrap_or("")
                .to_owned();
            match connection.attach(&terminal, cols, rows) {
                Ok(()) => {
                    #[cfg(feature = "android-ui")]
                    log::warn!("switch {terminal}: attached after {:?}", asked.elapsed());
                }
                // herdr-core refused (the window closed, an agent restarted
                // under a new id): stay on the window that was showing and
                // refresh, instead of ending the session.
                Err(reason @ (Error::Protocol(_) | Error::Timeout { .. } | Error::Rpc { .. })) => {
                    #[cfg(feature = "android-ui")]
                    log::warn!("switch {terminal} refused: {reason}");
                    #[cfg(not(feature = "android-ui"))]
                    let _ = reason;
                    let why = reason.to_string();
                    connection.refresh()?;
                    let back = !previous.is_empty()
                        && previous != terminal
                        && connection.attach(&previous, cols, rows).map_or_else(
                            |error| match error {
                                Error::Protocol(_) | Error::Timeout { .. } | Error::Rpc { .. } => Ok(false),
                                other => Err(other),
                            },
                            |()| Ok(true),
                        )?;
                    if !back {
                        connection.refresh()?;
                        // Nothing to fall back to (a session opened for this switch, or the
                        // previous pane refused too): say so, instead of "connecting" forever.
                        refused(&mut lock(&owner.state).view, &terminal, &why);
                    }
                }
                Err(error) => return Err(error),
            }
            #[cfg(not(feature = "android-ui"))]
            let _ = asked;
        }
        if let Some(frame) = connection.read()? {
            connection.deliver(frame)?;
        }
        if connection.reattached_at.is_none_or(|at| at.elapsed() >= REATTACH_EVERY)
            && let Some(pane) = connection.reattach.take()
            // A newer pick (also one parked while hidden) wins over the old pane.
            && connection.claim.parked.as_ref().is_none_or(|parked| *parked == pane)
            && lock(&owner.state).view["pane_id"] == pane.as_str()
        {
            if !connection.may_attach() {
                // Hidden by now on a path that cannot say so: parked like any
                // hidden pane, attached when shown.
                connection.detach()?;
                connection.claim.parked = Some(pane);
            } else {
                connection.reattached_at = Some(Instant::now());
                match connection.attach(&pane, cols, rows) {
                    Ok(()) => {}
                    // Refused (the program ended, the pane is gone): it stays ended.
                    Err(Error::Protocol(_) | Error::Timeout { .. } | Error::Rpc { .. }) => {
                        let mut state = lock(&owner.state);
                        state.view["state"] = json!("connecting");
                        state.view["status"] = json!("Terminal stream ended; select a terminal to attach again");
                        bump(&mut state.view);
                    }
                    Err(error) => return Err(error),
                }
            }
        }
        if refreshed.elapsed() >= Duration::from_secs(2)
            || (connection.dirty
                && refreshed.elapsed()
                    >= if connection.channel != 0 {
                        // The session on screen: quick, but no busier than the
                        // rail's eye needs while agents report constantly.
                        Duration::from_millis(400)
                    } else {
                        // A host only sampled for the rail or Home.
                        Duration::from_secs(1)
                    })
        {
            let started = Instant::now();
            connection.refresh()?;
            #[cfg(feature = "android-ui")]
            if started.elapsed() > Duration::from_secs(1) {
                log::warn!("refresh took {:?}", started.elapsed());
            }
            #[cfg(not(feature = "android-ui"))]
            let _ = started;
            refreshed = Instant::now();
        }
    }
    Ok(())
}

/// The herdr-app document owns layout; labels associate terminals with its IDs.
/// Unlabelled hc terminals remain available without inventing server layout.
fn project(view: &mut Value, terminals: &Value, doc: &Value) -> Result<()> {
    let terminals = terminals
        .as_array()
        .ok_or_else(|| Error::Protocol("invalid terminal list".into()))?;
    if terminals.len() > 1024 {
        return Err(Error::Capacity);
    }
    let mut spaces = Vec::new();
    let mut tabs = Vec::new();
    let mut panes = Vec::new();
    if let Some(items) = doc["spaces"].as_array() {
        for space in items {
            let Some(id) = space["id"].as_str() else {
                continue;
            };
            spaces.push(json!({"id":id,"label":space["name"].as_str().unwrap_or(id),"focused":false,"status":""}));
            if let Some(items) = space["tabs"].as_array() {
                for tab in items {
                    if let Some(tab_id) = tab["id"].as_str() {
                        // `named`: whether someone named the tab (the desktop's
                        // window names: an unnamed one goes by its task).
                        tabs.push(json!({"id":tab_id,"workspace_id":id,"label":tab["name"].as_str().unwrap_or(tab_id),"named":tab["named"].as_bool(),"focused":false,"zoomed":false,"status":""}));
                    }
                }
            }
        }
    }
    for terminal in terminals {
        let Some(id) = terminal["address"].as_str().or(terminal["id"].as_str()) else {
            continue;
        };
        if terminal["state"] != "live" {
            continue;
        }
        // Empty labels are a parked terminal (the desktop's Park, the phone's
        // tab.detach): in no tab, so loose, its own window, under its lead.
        let space = terminal["labels"]["herdr.space"]
            .as_str()
            .filter(|s| !s.is_empty())
            .unwrap_or("loose");
        let tab = terminal["labels"]["herdr.tab"].as_str().filter(|t| !t.is_empty()).unwrap_or(id);
        if !spaces.iter().any(|s| s["id"] == space) {
            // A space missing from the app document is named as the desktop
            // shows it: the `where` label's space part, else the folder.
            let named = terminal["labels"]["where"]
                .as_str()
                .and_then(|w| w.split(" / ").next())
                .filter(|w| !w.is_empty())
                .or_else(|| {
                    terminal["cwd"]
                        .as_str()
                        .and_then(|cwd| cwd.trim_end_matches('/').rsplit('/').next())
                        .filter(|name| !name.is_empty())
                });
            let label = if space == "loose" {
                "Other terminals"
            } else {
                named.unwrap_or(space)
            };
            spaces.push(json!({"id":space,"label":label,"focused":false,"status":""}));
        }
        if !tabs
            .iter()
            .any(|t| t["id"] == tab && t["workspace_id"] == space)
        {
            tabs.push(json!({"id":tab,"workspace_id":space,"label":terminal["name"].as_str().unwrap_or(tab),"focused":false,"zoomed":false,"status":""}));
        }
        let title = terminal["title"]
            .as_str()
            .filter(|s| !s.is_empty())
            .or(terminal["name"].as_str())
            .unwrap_or(id);
        // The agent hierarchy (deacon, managers, workers) as the deacon skill
        // labels it, with hc's own parent as the fallback.
        let labels = &terminal["labels"];
        let label = |key: &str| {
            labels[key]
                .as_str()
                .filter(|v| !v.is_empty() && *v != "none" && v.len() <= 128)
        };
        // Only a harness the agent hook named (claude, codex, ...): a shell's
        // own program name says nothing about an agent.
        let kind = label("agent.kind");
        panes.push(json!({
            "id": id,
            "title": title,
            "cwd": terminal["cwd"],
            "workspace_id": space,
            "tab_id": tab,
            "status": terminal["status"]["state"].as_str().unwrap_or(""),
            "parent": label("parent").or(terminal["parent"].as_str()).unwrap_or(""),
            "role": label("role").unwrap_or(""),
            "project": label("project").unwrap_or(""),
            // The repo a project sits under, when a label says so (item 61).
            "repo": label("repo").unwrap_or(""),
            "kind": kind.unwrap_or(""),
            // The agent's session id (factory-9zz.12): the phone asks for its history by it.
            "session": label("agent.session").filter(|s| crate::agent_history::plain_session(s)).unwrap_or(""),
            // Its hc name ("phone-rail"): what the phone's rows call it, never its id (factory-jmc).
            "name": terminal["name"].as_str().filter(|n| !n.is_empty() && n.len() <= 128).unwrap_or(""),
            // The factory's join labels: the bead a worker is on, a helper's role (BeadsModel).
            "issue": label("issue").unwrap_or(""),
            "task": label("task").unwrap_or(""),
            "helper": label("helper").unwrap_or(""),
            // A PM's sidebar group (factory-r8f.6): presentation only. herdr.group,
            // else the older group= label, as every reader takes it (factory-r8f.8);
            // the older label on its own too, which a drop never clears.
            "group": label("herdr.group").or_else(|| label("group")).unwrap_or(""),
            "old_group": label("group").unwrap_or(""),
            // What the deacon view shows under each agent: the program's own
            // status message, why it is blocked, and since when (core clock, ms).
            "msg": terminal["status"]["msg"].as_str().map(|m| m.chars().take(240).collect::<String>()).unwrap_or_default(),
            "wait_kind": terminal["status"]["kind"].as_str().unwrap_or(""),
            "status_ms": terminal["status_ms"].as_u64().unwrap_or(0),
            // Picks the canonical deacon when a computer has several candidates.
            "created_ms": terminal["created_ms"].as_u64().unwrap_or(0),
            // Which program runs in it (feature `incarnation`): a transcript
            // is shown offline only for the incarnation it was read from.
            "incarnation": terminal["incarnation"]
                .as_str()
                .filter(|i| !i.is_empty() && i.len() <= 64 && i.chars().all(|c| c.is_ascii_alphanumeric())),
            // What runs in it now: a deacon whose claude ended is a kept shell.
            "program": terminal["foreground"]["name"].as_str().unwrap_or(""),
        }));
    }
    if spaces.len() > 256 || tabs.len() > 512 {
        return Err(Error::Capacity);
    }
    // Compare with focus already marked: the fresh lists start unfocused, so
    // comparing them bare changed the revision on every refresh and made the
    // phone rebuild its chrome every couple of seconds for nothing.
    let mut next = json!({"pane_id": view["pane_id"], "panes": panes, "workspaces": spaces, "tabs": tabs});
    mark_focus(&mut next);
    if [&view["panes"], &view["workspaces"], &view["tabs"]]
        != [&next["panes"], &next["workspaces"], &next["tabs"]]
    {
        view["panes"] = next["panes"].take();
        view["workspaces"] = next["workspaces"].take();
        view["tabs"] = next["tabs"].take();
        bump(view);
    }
    mark_focus(view);
    Ok(())
}

fn mark_focus(view: &mut Value) {
    let selected = view["pane_id"].as_str().unwrap_or("");
    let pane = view["panes"]
        .as_array()
        .and_then(|p| p.iter().find(|p| p["id"] == selected))
        .cloned();
    let space = pane
        .as_ref()
        .and_then(|p| p["workspace_id"].as_str())
        .unwrap_or("");
    let tab = pane
        .as_ref()
        .and_then(|p| p["tab_id"].as_str())
        .unwrap_or("");
    if let Some(spaces) = view["workspaces"].as_array_mut() {
        for item in spaces {
            item["focused"] = json!(item["id"] == space);
        }
    }
    if let Some(tabs) = view["tabs"].as_array_mut() {
        for item in tabs {
            item["focused"] = json!(item["id"] == tab && item["workspace_id"] == space);
        }
    }
}

/// Tailnet peers from `tailscale status --json`, the bootstrap host first.
/// Only names safe as one SSH argv word survive.
fn discovery_peers(status: &Value) -> Result<Vec<Value>> {
    let mut peers = Vec::new();
    let entries = std::iter::once((&status["Self"], true)).chain(
        status["Peer"]
            .as_object()
            .into_iter()
            .flat_map(|p| p.values())
            .map(|p| (p, false)),
    );
    for (peer, is_self) in entries {
        let dns = peer["DNSName"].as_str().unwrap_or("").trim_end_matches('.');
        let host = if dns.is_empty() {
            peer["TailscaleIPs"][0].as_str().unwrap_or("")
        } else {
            dns
        };
        if host.is_empty()
            || host.starts_with('-')
            || host.len() > 253
            || !host
                .bytes()
                .all(|c| c.is_ascii_alphanumeric() || b".:-".contains(&c))
        {
            continue;
        }
        peers.push(json!({"host":host,"name":peer["HostName"].as_str().unwrap_or(host),"online":peer["Online"].as_bool().unwrap_or(is_self),"os":peer["OS"].as_str().unwrap_or(""),"self":is_self,"core":"unverified"}));
        if peers.len() > 256 {
            return Err(Error::Capacity);
        }
    }
    peers.sort_by_key(|p| {
        let name = p["name"].as_str().unwrap_or("");
        (
            p["online"] != true,
            !matches!(name, "server" | "laptop" | "desktop"),
            name.to_lowercase(),
        )
    });
    Ok(peers)
}

#[cfg(test)]
mod tests {
    #![allow(clippy::unwrap_used, clippy::expect_used)]
    use super::{DEACON_DIR, deacon_dir};
    #[test]
    fn heard_reports_quiet_time_since_the_last_byte() {
        let heard = crate::remote::Heard::default();
        std::thread::sleep(Duration::from_millis(40));
        let before = heard.quiet_ms();
        assert!(before >= 40);
        heard.mark();
        assert!(heard.quiet_ms() < before);
        // Pane output is counted apart from the link's own traffic.
        assert_eq!(heard.output(), 0);
        heard.printed(12);
        heard.printed(3);
        assert_eq!(heard.output(), 15);
    }

    #[test]
    fn the_deacons_folder_is_home_herdr_deacon_made_and_read_back() {
        // The script, run for real with HOME in a scratch folder (spaces and quotes included).
        let home = std::env::temp_dir().join(format!("herdr deacon 'home' {}", std::process::id()));
        let _ = std::fs::remove_dir_all(&home);
        std::fs::create_dir_all(&home).ok();
        let out = std::process::Command::new("sh")
            .args(["-c", DEACON_DIR])
            .env("HOME", &home)
            .output()
            .map(|o| String::from_utf8_lossy(&o.stdout).into_owned())
            .unwrap_or_default();
        let made = home.join(".herdr/deacon");
        assert!(made.is_dir());
        let canonical = std::fs::canonicalize(&made).ok().map(|p| p.to_string_lossy().into_owned());
        assert_eq!(deacon_dir(&out), canonical, "{out}");
        let _ = std::fs::remove_dir_all(&home);
        // Only an absolute .herdr/deacon path is accepted.
        assert_eq!(deacon_dir("/home/wolf/.herdr/deacon\n").as_deref(), Some("/home/wolf/.herdr/deacon"));
        assert_eq!(deacon_dir("/home/wolf/Config\n"), None);
        assert_eq!(deacon_dir("relative/.herdr/deacon\n"), None);
        assert_eq!(deacon_dir("/a/.herdr/deacon\n/b\n"), None);
        assert_eq!(deacon_dir(""), None);
    }
    use super::{GIT_LOOK, add_space, attach_params, canonical_deacon, deacon_line, existing_at, initial_grid, resize_frame, send_refusal};
    #[test]
    fn the_attach_replys_grid_is_used_only_when_negotiated() {
        let reply = json!({"channel":3,"resize_notifications":true,"terminal":{"cols":174,"rows":69}});
        assert_eq!(initial_grid(&reply), Some((174, 69)));
        let old = json!({"channel":3,"terminal":{"cols":174,"rows":69}});
        assert_eq!(initial_grid(&old), None);
        let off = json!({"channel":3,"resize_notifications":false,"terminal":{"cols":174,"rows":69}});
        assert_eq!(initial_grid(&off), None);
        let empty = json!({"channel":3,"resize_notifications":true,"terminal":{"cols":0,"rows":69}});
        assert_eq!(initial_grid(&empty), None);
    }
    #[test]
    fn attach_asks_for_resize_frames_and_reads_them() {
        let params = attach_params("t3", 120, 40, false, None);
        assert_eq!(params["resize_notifications"], true);
        assert_eq!((params["cols"].as_u64(), params["rows"].as_u64()), (Some(120), Some(40)));
        let mut bytes = Vec::from(80u16.to_le_bytes());
        bytes.extend(24u16.to_le_bytes());
        assert_eq!(resize_frame(&bytes), Some((80, 24)));
        assert_eq!(resize_frame(&[0, 0, 24, 0]), None);
        assert_eq!(resize_frame(&[80, 0, 24]), None);
    }
    #[test]
    fn a_send_to_a_blocked_program_is_refused_never_forced() {
        let rpc = |kind: &str| Error::Rpc { kind: kind.into(), message: format!("t3 is {kind}") };
        let refused = send_refusal(&rpc("blocked"));
        assert_eq!(refused["queued"], false);
        assert_eq!(refused["refused"], "blocked");
        assert_eq!(refused["code"], 9);
        let other = send_refusal(&rpc("not_found"));
        assert_eq!(other["queued"], false);
        assert!(other["refused"].is_null());
        assert!(other["error"].as_str().is_some_and(|e| e.contains("not_found")));
    }
    #[test]
    fn reopening_a_worktree_finds_the_space_already_there() {
        let doc = json!({"spaces":[{"id":"sp1","folder":"/w/lane/","tabs":[]}]});
        let t = |id: &str, space: &str, cwd: &str| json!({"address":id,"state":"live","cwd":cwd,"labels":{"herdr.space":space}});
        // The space made by a first try whose answer timed out: found by its folder.
        let first = json!([t("t1", "other", "/w"), t("t7", "sp1", "/home")]);
        assert_eq!(existing_at(&first, &doc, "/w/lane").as_deref(), Some("t7"));
        // Or a terminal already working inside it.
        let inside = json!([t("t2", "x", "/w/lane-2"), t("t3", "x", "/w/lane/src")]);
        assert_eq!(existing_at(&inside, &json!({}), "/w/lane").as_deref(), Some("t3"));
        // Nothing there: open it.
        assert_eq!(existing_at(&json!([t("t2", "x", "/w/lane-2"), t("server/t3", "x", "/w/lane")]), &json!({}), "/w/lane"), None);
    }
    #[test]
    fn a_deacon_whose_claude_was_missing_is_found_again_not_relaunched() {
        // The first start's terminal: labelled at birth, claude missing, kept as a shell.
        let kept = json!([{"address":"t9","state":"live","labels":{"role":"deacon","herdr.space":"spX"},"status":{},"created_ms":5}]);
        assert_eq!(canonical_deacon(&kept, &json!({})).as_deref(), Some("t9"));
    }
    #[test]
    fn adding_a_space_keeps_every_existing_space_and_tab() {
        let mut doc = json!({"spaces":[{"id":"s1","name":"botd","tabs":[{"id":"a","name":"pm"},{"id":"b"}]}],"other":1});
        let entry = json!({"id":"spX","name":"deacon","tabs":[{"id":"tbX","name":"deacon"}]});
        assert!(add_space(&mut doc, &entry).is_ok());
        assert!(add_space(&mut doc, &entry).is_ok());
        assert_eq!(doc["spaces"].as_array().map(Vec::len), Some(2));
        assert_eq!(doc["spaces"][0]["tabs"].as_array().map(Vec::len), Some(2));
        assert_eq!(doc["other"], 1);
        assert_eq!(doc["spaces"][1]["name"], "deacon");
        let mut empty = Value::Null;
        assert!(add_space(&mut empty, &entry).is_ok());
        assert_eq!(empty["spaces"][0]["id"], "spX");
    }
    #[test]
    fn git_look_names_each_repo_once_with_its_worktrees() {
        let dir = std::env::temp_dir().join(format!("herdr-git-look-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&dir);
        let main = dir.join("kache");
        let run = |args: &[&str]| {
            std::process::Command::new("git").args(args).output().map(|o| o.status.success()).unwrap_or(false)
        };
        std::fs::create_dir_all(&main).ok();
        let m = main.to_string_lossy().into_owned();
        if !run(&["-C", &m, "init", "-q", "-b", "main"]) {
            // A host without git skips this test; the CI shell sets HERDR_TESTS_REQUIRE_GIT.
            assert!(
                std::env::var_os("HERDR_TESTS_REQUIRE_GIT").is_none(),
                "`git init` failed, and HERDR_TESTS_REQUIRE_GIT is set"
            );
            return;
        }
        assert!(run(&["-C", &m, "-c", "user.name=t", "-c", "user.email=t@t", "commit", "-q", "--allow-empty", "-m", "x"]));
        assert!(run(&["-C", &m, "branch", "--no-track", "upstream"]));
        let lane = dir.join("custom place/lane 1");
        let l = lane.to_string_lossy().into_owned();
        assert!(run(&["-C", &m, "worktree", "add", "-q", "-b", "lane-1", &l]));
        assert!(run(&[
            "-C",
            &l,
            "branch",
            "--set-upstream-to=upstream",
            "lane-1"
        ]));
        assert!(run(&[
            "-C",
            &l,
            "-c",
            "user.name=t",
            "-c",
            "user.email=t@t",
            "commit",
            "-q",
            "--allow-empty",
            "-m",
            "ahead"
        ]));
        assert!(run(&[
            "-C",
            &m,
            "-c",
            "user.name=t",
            "-c",
            "user.email=t@t",
            "commit",
            "-q",
            "--allow-empty",
            "-m",
            "behind"
        ]));
        assert!(run(&["-C", &m, "branch", "-f", "upstream", "main"]));
        let sub = lane.join("src");
        assert!(std::fs::create_dir_all(&sub).is_ok());
        let sub = sub.to_string_lossy().into_owned();
        let out = std::process::Command::new("sh")
            .args([
                "-c",
                GIT_LOOK,
                "sh",
                &m,
                &l,
                &format!("{l}/"),
                &sub,
                "/nonexistent",
            ])
            .output()
            .map(|o| String::from_utf8_lossy(&o.stdout).into_owned())
            .unwrap_or_default();
        let _ = std::fs::remove_dir_all(&dir);
        let common = format!("{m}/.git");
        assert!(
            out.contains(&format!("P\t{m}\t{common}\t{m}\tmain\t0\t0\n")),
            "{out}"
        );
        assert!(
            out.contains(&format!("P\t{l}\t{common}\t{l}\tlane-1\t1\t1\n")),
            "{out}"
        );
        assert!(
            out.contains(&format!("P\t{l}/\t{common}\t{l}\tlane-1\t1\t1\n")),
            "{out}"
        );
        assert!(
            out.contains(&format!("P\t{sub}\t{common}\t{l}\tlane-1\t1\t1\n")),
            "{out}"
        );
        assert!(out.contains("P\t/nonexistent\n"), "{out}");
        assert_eq!(out.matches("\nR\t").count() + usize::from(out.starts_with("R\t")), 1, "{out}");
        assert!(out.contains(&format!("worktree {l}\n")) && out.contains("branch refs/heads/lane-1\n"), "{out}");
        assert!(out.contains("\nE\nP\t"), "{out}");
    }
    use serde_json::{Value, json};
    #[test]
    fn deacon_line_quotes_any_machine_name_as_one_word() {
        let line = deacon_line("Wolf's \"box\" $(rm -rf ~) `x`; echo");
        // The brief is one single-quoted word; the only way out of it is '\''.
        let brief = line.rsplit_once(" -lic 'claude \"$0\"' ").map(|(_, b)| b).unwrap_or("");
        assert!(brief.starts_with('\'') && brief.ends_with('\''));
        assert!(brief.contains(r"Wolf'\''s"));
        // A real shell reads it back as the exact brief, nothing run.
        let said = std::process::Command::new("sh")
            .arg("-c")
            .arg(format!("printf %s {brief}"))
            .output()
            .map(|o| String::from_utf8_lossy(&o.stdout).into_owned())
            .unwrap_or_default();
        assert!(said.contains("(Wolf's \"box\" $(rm -rf ~) `x`; echo)"), "{said}");
        // No cd: the Deacon starts in its own folder, given as terminal.run's cwd.
        assert!(line.starts_with("exec \"${SHELL:-/bin/sh}\" -lic "));
        assert!(!line.contains("Config"));
        assert!(deacon_line("").contains("Deacon for this computer;"));
        assert!(deacon_line("a\nb").contains("(ab)"));
    }
    #[test]
    fn canonical_deacon_prefers_the_oldest_labelled_then_the_deacon_space() {
        let doc = json!({"spaces":[
            {"id":"s1","name":"botd","tabs":[{"id":"a"}]},
            {"id":"s2","name":"Deacon","tabs":[{"id":"x"},{"id":"y"}]}
        ]});
        let t = |id: &str, labels: Value, created: u64, status: &str| json!({
            "address":id,"state":"live","labels":labels,"created_ms":created,"status":{"state":status}
        });
        // Duplicate labels: the oldest wins, a federated one never counts.
        let both = json!([
            t("t9", json!({"role":"deacon"}), 50, "idle"),
            t("t3", json!({"role":"deacon"}), 10, "working"),
            t("server/t1", json!({"role":"deacon"}), 1, "idle"),
        ]);
        assert_eq!(canonical_deacon(&both, &doc).as_deref(), Some("t3"));
        // No label: the first unlabelled agent of the deacon space, by tab.
        let fallback = json!([
            t("t5", json!({"herdr.space":"s2","herdr.tab":"y"}), 1, "idle"),
            t("t6", json!({"herdr.space":"s2","herdr.tab":"x"}), 9, "working"),
            t("t7", json!({"herdr.space":"s2","herdr.tab":"x","role":"pm"}), 0, "idle"),
            t("t8", json!({"herdr.space":"s1","herdr.tab":"a"}), 0, "idle"),
            t("t4", json!({"herdr.space":"s2","herdr.tab":"x"}), 0, ""),
        ]);
        assert_eq!(canonical_deacon(&fallback, &doc).as_deref(), Some("t6"));
        // None at all: a start may launch.
        let none = json!([t("t8", json!({"herdr.space":"s1"}), 0, "idle"),
            json!({"address":"t2","state":"finished","labels":{"role":"deacon"}})]);
        assert_eq!(canonical_deacon(&none, &doc), None);
    }
    #[test]
    fn open_file_events_are_read_and_bounded() {
        let event = |data: Value| json!({"jsonrpc":"2.0","method":"event","params":{"type":"open_file","terminal":"server/t7","seq":9,"data":data}});
        assert_eq!(
            open_file_event(&event(json!({"path":"/w/a.rs","line":12}))),
            Some(json!({"terminal":"server/t7","path":"/w/a.rs","line":12,"seq":9}))
        );
        assert_eq!(
            open_file_event(&event(json!({"path":"/w/a.rs"}))).map(|v| v["line"].clone()),
            Some(json!(0))
        );
        assert_eq!(open_file_event(&event(json!({"path":"rel.rs"}))), None);
        assert_eq!(open_file_event(&event(json!({"path":"/a\nb"}))), None);
        let other = json!({"method":"event","params":{"type":"status","terminal":"t1","data":{"path":"/x"}}});
        assert_eq!(open_file_event(&other), None);
    }
    #[test]
    fn file_reads_parse_a_terminal_and_a_bounded_path() {
        assert_eq!(
            TabEdit::parse(r#"{"op":"file.read","terminal":"t3","path":"src/a.rs"}"#),
            Some(TabEdit::FileRead { terminal: "t3".into(), path: "src/a.rs".into(), limit: FILE_LINES })
        );
        // The phone's offline copies ask for fewer lines; never more than a tap reads.
        assert_eq!(
            TabEdit::parse(r#"{"op":"file.read","terminal":"t3","path":"a.md","limit":2000}"#),
            Some(TabEdit::FileRead { terminal: "t3".into(), path: "a.md".into(), limit: 2000 })
        );
        assert_eq!(TabEdit::parse(r#"{"op":"file.read","terminal":"t3","path":"a.md","limit":0}"#), None);
        let over = format!(r#"{{"op":"file.read","terminal":"t3","path":"a.md","limit":{}}}"#, FILE_LINES + 1);
        assert_eq!(TabEdit::parse(&over), None);
        assert_eq!(TabEdit::parse(r#"{"op":"file.read","terminal":"t3","path":""}"#), None);
        assert_eq!(TabEdit::parse(r#"{"op":"file.read","path":"/a"}"#), None);
        assert_eq!(TabEdit::parse(r#"{"op":"file.read","terminal":"t3","path":"/a
b"}"#), None);
    }
    #[test]
    fn a_new_project_window_is_a_layout_tab_that_renames_for_good() {
        let mut doc = json!({"spaces":[{"id":"s1","name":"monopoly","tabs":[]}]});
        let tab = window_tab("mobile-1-9", "t44", "p9", "claude");
        add_window_tab(&mut doc, "s1", "monopoly", &Value::Null, &tab).unwrap();
        add_window_tab(&mut doc, "s1", "monopoly", &Value::Null, &tab).unwrap();
        assert_eq!(doc["spaces"][0]["tabs"].as_array().unwrap().len(), 1, "added once");
        let terminals = json!([{"address":"t44","state":"live","labels":{"herdr.space":"s1","herdr.tab":"mobile-1-9","parent":"t5"},"title":"Fix tests","status":{}}]);
        let mut view = json!({"pane_id":"t44"});
        project(&mut view, &terminals, &doc).unwrap();
        assert_eq!(view["tabs"][0]["named"], false, "goes by its task until renamed");
        // A rename finds it in the layout and names it for good.
        rename_tab(&mut doc, "s1", "mobile-1-9", "review").unwrap();
        project(&mut view, &terminals, &doc).unwrap();
        assert_eq!((view["tabs"][0]["label"].clone(), view["tabs"][0]["named"].clone()), (json!("review"), json!(true)));
    }

    #[test]
    fn a_pms_group_reaches_the_phone_herdr_group_first() {
        let doc = json!({"spaces":[{"id":"s1","name":"work","tabs":[{"id":"x"}]}]});
        let terminals = json!([
            {"address":"t5","state":"live","labels":{"herdr.space":"s1","herdr.tab":"x","role":"pm","project":"app","herdr.group":"herdr"},"title":"pm","status":{}},
            {"address":"t6","state":"live","labels":{"herdr.space":"s1","herdr.tab":"x","role":"pm","project":"web","group":"not-ours"},"title":"pm","status":{}},
            {"address":"t7","state":"live","labels":{"herdr.space":"s1","herdr.tab":"x","role":"pm","herdr.group":"x".repeat(129)},"title":"pm","status":{}},
            {"address":"t8","state":"live","labels":{"herdr.space":"s1","herdr.tab":"x","role":"pm","project":"api","herdr.group":"new","group":"old"},"title":"pm","status":{}},
            {"address":"t9","state":"live","labels":{"herdr.space":"s1","herdr.tab":"x","role":"pm","project":"cli"},"title":"pm","status":{}}]);
        let mut view = json!({"pane_id":"t5"});
        project(&mut view, &terminals, &doc).unwrap();
        let group = |id: &str| view["panes"].as_array().unwrap().iter().find(|p| p["id"] == id).unwrap()["group"].clone();
        let old = |id: &str| view["panes"].as_array().unwrap().iter().find(|p| p["id"] == id).unwrap()["old_group"].clone();
        assert_eq!(group("t5"), "herdr");
        // herdr.group ?? group (factory-r8f.8): the older label groups too, and is said on its own.
        assert_eq!(group("t6"), "not-ours");
        assert_eq!(old("t6"), "not-ours");
        assert_eq!(group("t8"), "new", "herdr.group comes first");
        assert_eq!(old("t8"), "old");
        assert_eq!(group("t9"), "");
        assert_eq!(old("t9"), "");
        assert_eq!(group("t7"), "", "an overlong label is no group");
    }

    #[test]
    fn a_parked_agent_survives_a_refresh_loose_and_still_under_its_pm() {
        let doc = json!({"spaces":[{"id":"s1","name":"work","tabs":[{"id":"x"}]}]});
        let terminals = json!([
            {"address":"t5","state":"live","labels":{"herdr.space":"s1","herdr.tab":"x","role":"pm","project":"app"},"title":"pm","status":{}},
            {"address":"t44","state":"live","labels":{"herdr.space":"","herdr.tab":"","parent":"t5","agent.kind":"claude"},"title":"Fix tests","status":{"state":"working"}}]);
        let mut view = json!({"pane_id":"t5"});
        for _ in 0..2 {
            project(&mut view, &terminals, &doc).unwrap();
            let parked = view["panes"].as_array().unwrap().iter().find(|p| p["id"] == "t44").unwrap().clone();
            assert_eq!(parked["workspace_id"], "loose");
            assert_eq!(parked["tab_id"], "t44");
            assert_eq!(parked["parent"], "t5");
            assert_eq!(parked["status"], "working");
            assert!(!view["workspaces"].as_array().unwrap().iter().any(|s| s["id"] == ""), "no blank-named space");
        }
    }

    #[test]
    fn a_window_whose_tab_was_not_saved_keeps_the_layout_failure_as_its_source() {
        use std::error::Error as _;
        let error = Error::WindowNotPlaced(Box::new(Error::Rpc { kind: "denied".into(), message: "read-only".into() }));
        // The layout write's own error, typed, as the source (boxed: Error holds itself).
        let source = error.source().and_then(|s| s.downcast_ref::<Box<Error>>()).map(|b| &**b);
        assert!(matches!(source, Some(Error::Rpc { kind, .. }) if kind == "denied"));
        assert!(matches!(&error, Error::WindowNotPlaced(cause) if matches!(**cause, Error::Rpc { .. })));
        assert!(error.to_string().starts_with("The window started, but its tab could not be saved in the layout"));
        assert!(error.to_string().contains("read-only"));
    }

    #[test]
    fn a_window_of_a_lead_the_layout_lacks_gets_a_space_there_and_renames() {
        // A PM only labels place (herdr.space s9, not in herdr-app's layout),
        // and one in no space at all.
        let terminals = json!([
            {"address":"t5","state":"live","labels":{"herdr.space":"s9","herdr.tab":"x","role":"pm","project":"research-ui"},"cwd":"/w/monopoly","title":"pm","status":{}},
            {"address":"t6","state":"live","labels":{"role":"pm","project":"incident-fixes"},"cwd":"/w/other","title":"pm","status":{}}]);
        let mut doc = json!({"spaces":[{"id":"s1","name":"work","tabs":[]}]});
        let mut view = json!({"pane_id":"t5"});
        project(&mut view, &terminals, &doc).unwrap();
        assert_eq!(window_space(&view, "s9", "t5"), ("s9".to_owned(), "monopoly".to_owned()));
        let tab = window_tab("mobile-1-9", "t44", "p9", "claude");
        add_window_tab(&mut doc, "s9", "monopoly", &json!("/w/monopoly"), &tab).unwrap();
        add_window_tab(&mut doc, "s9", "monopoly", &json!("/w/monopoly"), &tab).unwrap();
        assert_eq!(doc["spaces"][1]["tabs"].as_array().unwrap().len(), 1, "added once");
        let mut terminals = terminals;
        terminals.as_array_mut().unwrap().push(json!({"address":"t44","state":"live","labels":{"herdr.space":"s9","herdr.tab":"mobile-1-9","parent":"t5"},"title":"Fix tests","status":{}}));
        project(&mut view, &terminals, &doc).unwrap();
        assert!(view["panes"].as_array().unwrap().iter().any(|p| p["id"] == "t5" && p["workspace_id"] == "s9"), "the lead keeps its space");
        rename_tab(&mut doc, "s9", "mobile-1-9", "review").unwrap();
        project(&mut view, &terminals, &doc).unwrap();
        let renamed = view["tabs"].as_array().unwrap().iter().find(|t| t["id"] == "mobile-1-9").unwrap().clone();
        assert_eq!((renamed["label"].clone(), renamed["named"].clone()), (json!("review"), json!(true)));
        // A lead in no space: a space of its own, named after its project.
        let (id, name) = window_space(&view, "loose", "t6");
        assert!(id.starts_with("mobile-") && id != "loose");
        assert_eq!(name, "incident-fixes");
        // A layout space without a tab list gets one.
        let mut bare = json!({"spaces":[{"id":"s2","name":"b"}]});
        add_window_tab(&mut bare, "s2", "b", &Value::Null, &tab).unwrap();
        assert_eq!(bare["spaces"][0]["tabs"][0]["id"], "mobile-1-9");
    }

    #[test]
    fn window_moves_keep_their_own_list_in_projects_order() {
        let ids = |v: &[&str]| v.iter().map(|x| x.to_string()).collect::<Vec<_>>();
        let w = |p: &str| OrderScope::Windows(p.to_owned());
        // As herdr-projects-order's test_item_79: its own list beside the others.
        let got = order_move(&json!({"repos":[],"projects":{"monopoly":["cleanup","qos"]}}), &w("research-ui"), "t1s7", "t1s6", false,
            &ids(&["t1s6", "t1s7", "tab:s3/4"])).unwrap().unwrap();
        assert_eq!(got, json!({"repos":[],"projects":{"monopoly":["cleanup","qos"]},"windows":{"research-ui":["t1s7","t1s6","tab:s3/4"]}}));
        // Windows that ended leave the list at the next move; a new one joins.
        let again = order_move(&got, &w("research-ui"), "t1new", "t1s6", false, &ids(&["t1s6", "t1new"])).unwrap().unwrap();
        assert_eq!(again["windows"]["research-ui"], json!(["t1new", "t1s6"]));
        // Nothing moves: the same place, a name not shown (the lead), or onto itself.
        assert!(order_move(&again, &w("research-ui"), "t1new", "t1s6", false, &ids(&["t1new", "t1s6"])).unwrap().is_none());
        assert!(order_move(&again, &w("research-ui"), "t9r", "t1s6", false, &ids(&["t1new", "t1s6"])).unwrap().is_none());
        assert!(order_move(&again, &w("research-ui"), "t1s6", "t1s6", true, &ids(&["t1new", "t1s6"])).unwrap().is_none());
        // After, and a doc that was never written.
        let fresh = order_move(&Value::Null, &w("x#t1"), "a", "c", true, &ids(&["a", "b", "c"])).unwrap().unwrap();
        assert_eq!(fresh, json!({"repos":[],"projects":{},"windows":{"x#t1":["b","c","a"]}}));
        // A doc with no windows yet reads without the key, as the tool keeps it.
        assert_eq!(order_clean(&json!({"repos":["a"],"projects":{},"windows":{}})), json!({"repos":["a"],"projects":{}}));
    }

    #[test]
    fn a_move_keeps_every_key_it_does_not_own_and_moves_groups() {
        let ids = |v: &[&str]| v.iter().map(|x| x.to_string()).collect::<Vec<_>>();
        // factory-r8f.8: a window's move used to drop the groups' order and any newer key.
        let doc = json!({"repos":["mono"],"projects":{"mono":["qos"]},"groups":["herdr","agents"],
            "grouped":{"herdr":["factory","aswap"]},"pinned":{"x":1},"future":["kept"]});
        let got = order_move(&doc, &OrderScope::Windows("factory".into()), "t2", "t1", false, &ids(&["t1", "t2"])).unwrap().unwrap();
        for key in ["repos", "projects", "groups", "grouped", "pinned", "future"] {
            assert_eq!(got[key], doc[key], "{key}");
        }
        assert_eq!(got["windows"]["factory"], json!(["t2", "t1"]));
        // The groups, and one group's projects, by the same rule; only that list changes.
        let groups = order_move(&doc, &OrderScope::Groups, "agents", "herdr", false, &ids(&["herdr", "agents"])).unwrap().unwrap();
        assert_eq!(groups["groups"], json!(["agents", "herdr"]));
        assert_eq!(groups["grouped"], doc["grouped"]);
        let inside = order_move(&doc, &OrderScope::Group("herdr".into()), "factory", "aswap", true, &ids(&["factory", "aswap", "newpm"])).unwrap().unwrap();
        assert_eq!(inside["grouped"]["herdr"], json!(["aswap", "factory", "newpm"]));
        assert_eq!(inside["groups"], doc["groups"]);
        // A new group's list starts from what is shown; a group's list is not a repo's.
        let fresh = order_move(&doc, &OrderScope::Group("agents".into()), "b", "a", false, &ids(&["a", "b"])).unwrap().unwrap();
        assert_eq!(fresh["grouped"], json!({"herdr":["factory","aswap"],"agents":["b","a"]}));
        assert_eq!(fresh["projects"], doc["projects"]);
        // Groups and grouped read only once something is stored, as the tool writes them.
        assert_eq!(order_clean(&json!({"repos":[],"projects":{},"groups":[],"grouped":{}})), json!({"repos":[],"projects":{}}));
    }

    #[test]
    fn the_shared_drop_cases_move_the_list_they_name() {
        // t47c's fixture (home-manager-conf 8cfb8310, test-fixtures/order-cases.json), the phone's copy.
        let cases: Value = serde_json::from_str(include_str!("../../app/src/test/resources/order-cases.json")).unwrap();
        let mut moves = 0;
        for case in cases["drops"].as_array().unwrap() {
            let Some(m) = case["expect"].get("move") else { continue };
            let scope = OrderScope::parse(m["scope"].as_str().unwrap()).unwrap();
            let peers: Vec<String> = m["peers"].as_array().unwrap().iter().map(|x| x.as_str().unwrap().to_owned()).collect();
            let (id, target, after) = (m["id"].as_str().unwrap(), m["target"].as_str().unwrap(), m["after"].as_bool().unwrap());
            let got = order_move(&case["order"], &scope, id, target, after, &peers).unwrap().unwrap_or_else(|| panic!("{}", case["name"]));
            let (top, key) = scope.place();
            let list = match key { None => &got[top], Some(k) => &got[top][k] };
            let mut want: Vec<String> = peers.iter().filter(|x| *x != id).cloned().collect();
            let at = want.iter().position(|x| x == target).unwrap() + usize::from(after);
            want.insert(at, id.to_owned());
            assert_eq!(list, &json!(want), "{}", case["name"]);
            moves += 1;
        }
        assert!(moves >= 6, "the fixture's move cases: {moves}");
    }

    #[test]
    fn a_list_at_its_cap_is_refused_out_loud_not_taken_for_a_no_op() {
        // Audit t483: a refusal at 1000 names was a silent success.
        let names: Vec<String> = (0..1000).map(|i| format!("p{i}")).collect();
        let doc = json!({"repos":[],"projects":{},"grouped":{"herdr":names}});
        let more: Vec<String> = names.iter().cloned().chain(["new".to_owned()]).collect();
        assert!(matches!(
            order_move(&doc, &OrderScope::Group("herdr".into()), "new", "p0", false, &more),
            Err(Error::Capacity)
        ));
        // At the cap itself, a move within it still works.
        assert!(order_move(&doc, &OrderScope::Group("herdr".into()), "p9", "p0", false, &names).unwrap().is_some());
    }

    #[test]
    fn a_group_drop_sets_or_clears_herdr_group_only() {
        assert_eq!(
            TabEdit::parse(r#"{"op":"group.set","terminal":"t5","group":" herdr "}"#),
            Some(TabEdit::GroupSet { terminal: "t5".into(), group: Some("herdr".into()) })
        );
        assert_eq!(
            TabEdit::parse(r#"{"op":"group.set","terminal":"t5","group":null}"#),
            Some(TabEdit::GroupSet { terminal: "t5".into(), group: None })
        );
        for bad in [r#""group":"""#, r#""group":"none""#, r#""group":"a
b""#, r#""group":7"#] {
            let json = format!(r#"{{"op":"group.set","terminal":"t5",{bad}}}"#);
            assert_eq!(TabEdit::parse(&json), None, "{bad}");
        }
        let long = format!(r#"{{"op":"group.set","terminal":"t5","group":"{}"}}"#, "x".repeat(41));
        assert_eq!(TabEdit::parse(&long), None);
        assert_eq!(TabEdit::parse(r#"{"op":"group.set","terminal":"laptop/t5","group":"herdr"}"#), None);
        assert_eq!(TabEdit::parse(r#"{"op":"group.set","group":"herdr"}"#), None);
    }

    #[test]
    fn order_moves_parse_their_scope() {
        assert_eq!(
            TabEdit::parse(r#"{"op":"order.move","scope":"group:herdr","id":"aswap","target":"factory","after":false,"shown":["factory","aswap"]}"#),
            Some(TabEdit::OrderMove { scope: OrderScope::Group("herdr".into()), id: "aswap".into(), target: "factory".into(), after: false, shown: vec!["factory".into(), "aswap".into()] })
        );
        assert!(matches!(TabEdit::parse(r#"{"op":"order.move","scope":"groups","id":"a","target":"b","after":true,"shown":[]}"#),
            Some(TabEdit::OrderMove { scope: OrderScope::Groups, .. })));
        assert!(matches!(TabEdit::parse(r#"{"op":"order.move","scope":"repo:","id":"a","target":"b","after":true,"shown":[]}"#),
            Some(TabEdit::OrderMove { scope: OrderScope::Repo(r), .. }) if r.is_empty()));
        for bad in ["group:", "windows:", "teams", "", "group:a\nb"] {
            let json = format!(r#"{{"op":"order.move","scope":"{bad}","id":"a","target":"b","after":true,"shown":[]}}"#);
            assert_eq!(TabEdit::parse(&json), None, "{bad}");
        }
        assert_eq!(TabEdit::parse(r#"{"op":"order.move","scope":"groups","id":"a","target":"a","after":true,"shown":[]}"#), None);
    }

    #[test]
    fn a_project_window_works_for_its_lead_like_c_a_c() {
        let run = window_run("s1", "mobile-1-2", "t5", json!("/w/x"), "codex", 80, 24);
        assert_eq!(run["labels"]["parent"], "t5");
        assert_eq!(run["labels"]["herdr.space"], "s1");
        assert_eq!(run["labels"]["agent.kind"], "codex");
        assert_eq!(run["cwd"], "/w/x");
        assert!(run["labels"]["herdr.tab"].as_str().is_some_and(|t| t.starts_with("mobile-")));
        let shell = window_run("s1", "mobile-1-3", "t5", Value::Null, "shell", 80, 24);
        assert!(shell["line"].is_null() && shell["labels"]["agent.kind"].is_null());
        assert_eq!(shell["labels"]["parent"], "t5");
    }

    #[test]
    fn tab_edits_parse_only_bounded_known_operations() {
        use super::TabEdit;
        assert_eq!(
            TabEdit::parse(r#"{"op":"tab.rename","space":"s1","tab":"a","name":" Fix "}"#),
            Some(TabEdit::Rename { space: "s1".into(), tab: "a".into(), name: "Fix".into() })
        );
        assert_eq!(
            TabEdit::parse(r#"{"op":"tab.move","space":"s1","tab":"a","index":2}"#),
            Some(TabEdit::Move { space: "s1".into(), tab: "a".into(), index: 2 })
        );
        assert_eq!(TabEdit::parse(r#"{"op":"tab.rename","space":"s1","tab":"a","name":"a\nb"}"#), None);
        assert_eq!(TabEdit::parse(r#"{"op":"tab.rm","space":"s1","tab":"a"}"#), None);
        assert_eq!(
            TabEdit::parse(r#"{"op":"deacon.start","name":"Macro (Google)"}"#),
            Some(TabEdit::DeaconStart { machine: "Macro (Google)".into() })
        );
        assert_eq!(
            TabEdit::parse(r#"{"op":"deacon.start"}"#),
            Some(TabEdit::DeaconStart { machine: String::new() })
        );
        assert_eq!(
            TabEdit::parse(r#"{"op":"git.look","paths":["/w/kache","/w/x y"]}"#),
            Some(TabEdit::GitLook { paths: vec!["/w/kache".into(), "/w/x y".into()] })
        );
        assert_eq!(TabEdit::parse(r#"{"op":"git.look","paths":["relative"]}"#), None);
        assert_eq!(TabEdit::parse(r#"{"op":"git.look","paths":[]}"#), None);
        assert_eq!(
            TabEdit::parse(r#"{"op":"space.open","folder":"/w/kache-wt"}"#),
            Some(TabEdit::SpaceOpen { folder: "/w/kache-wt".into() })
        );
        assert_eq!(TabEdit::parse(r#"{"op":"space.open","folder":"/w/a\nb"}"#), None);
        // A project's new window (the desktop's C-a c): its lead on this core only.
        assert_eq!(
            TabEdit::parse(r#"{"op":"window.new","space":"s1","lead":"t5","kind":"claude","folder":"/w/x"}"#),
            Some(TabEdit::WindowNew { space: "s1".into(), lead: "t5".into(), folder: Some("/w/x".into()), kind: "claude" })
        );
        assert_eq!(
            TabEdit::parse(r#"{"op":"window.new","space":"s1","lead":"t5","kind":"shell"}"#),
            Some(TabEdit::WindowNew { space: "s1".into(), lead: "t5".into(), folder: None, kind: "shell" })
        );
        assert_eq!(TabEdit::parse(r#"{"op":"window.new","space":"s1","lead":"laptop/t5","kind":"shell"}"#), None);
        assert_eq!(
            TabEdit::parse(r#"{"op":"windows.move","project":"same#t31","id":"t1c","target":"tab:s1/4","after":true,"shown":["t1c","tab:s1/4"]}"#),
            Some(TabEdit::OrderMove { scope: OrderScope::Windows("same#t31".into()), id: "t1c".into(), target: "tab:s1/4".into(), after: true, shown: vec!["t1c".into(), "tab:s1/4".into()] })
        );
        assert_eq!(TabEdit::parse(r#"{"op":"windows.move","project":"p","id":"a","target":"b","shown":[]}"#), None);
        assert_eq!(TabEdit::parse(r#"{"op":"windows.move","project":"p","id":"a\nb","target":"b","after":false,"shown":[]}"#), None);
        assert_eq!(TabEdit::parse(r#"{"op":"window.new","space":"s1","lead":"t5","kind":"vim"}"#), None);
        assert_eq!(TabEdit::parse(r#"{"op":"window.new","space":"s1","lead":"t5","kind":"shell","folder":"rel"}"#), None);
        assert_eq!(
            TabEdit::parse(r#"{"op":"pane.send","terminal":"t3","text":"I want to start a new project on server."}"#),
            Some(TabEdit::Send { terminal: "t3".into(), text: "I want to start a new project on server.".into() })
        );
        assert_eq!(TabEdit::parse(r#"{"op":"pane.send","terminal":"t3","text":"a\nb"}"#), None);
        assert_eq!(TabEdit::parse("tab.new"), None);
    }

    #[test]
    fn tab_moves_and_renames_edit_the_layout_document() {
        let mut doc = json!({"spaces":[{"id":"s1","tabs":[{"id":"a","name":"A"},{"id":"b","name":"B"},{"id":"c","name":"C"}]}]});
        tab_mut(&mut doc, "s1", "b").unwrap()["name"] = json!("Bee");
        let tabs = tabs_mut(&mut doc, "s1").unwrap();
        let item = tabs.remove(2);
        tabs.insert(0, item);
        let names: Vec<_> = doc["spaces"][0]["tabs"].as_array().unwrap().iter().map(|t| t["name"].as_str().unwrap().to_owned()).collect();
        assert_eq!(names, ["C", "A", "Bee"]);
        assert!(tabs_mut(&mut doc, "nope").is_err());
    }

    use super::*;
    #[test]
    fn an_attach_reply_resize_and_data_in_one_read_come_out_in_order() -> Result<()> {
        // A negotiated attach: the reply on channel 0, the initial RESIZE at
        // byte 0 of channel 5, then its first paint, all in one read.
        let mut bytes = Vec::new();
        write_frame(&mut bytes, 0, 0, br#"{"id":4,"result":{"channel":5}}"#)?;
        let mut size = Vec::from(100u16.to_le_bytes());
        size.extend(30u16.to_le_bytes());
        write_frame(&mut bytes, 5, 3, &size)?;
        write_frame(&mut bytes, 5, 0, b"paint")?;
        let mut decoder = Decoder::default();
        decoder.bytes.extend_from_slice(&bytes);
        let reply = decoder.next()?.map(|f| (f.channel, f.kind));
        // What the call() that read the reply leaves behind stays buffered, in order.
        assert_eq!(reply, Some((0, 0)));
        let resize = decoder.next()?;
        assert_eq!(resize.as_ref().map(|f| (f.channel, f.kind)), Some((5, 3)));
        assert_eq!(resize.and_then(|f| resize_frame(&f.bytes)), Some((100, 30)));
        assert_eq!(decoder.next()?.map(|f| (f.channel, f.kind, f.bytes)), Some((5, 0, b"paint".to_vec())));
        assert_eq!(decoder.next()?, None);
        Ok(())
    }
    #[test]
    fn fragmented_and_coalesced_frames_survive_idle_reads() -> Result<()> {
        let mut bytes = Vec::new();
        write_frame(&mut bytes, 7, 0, b"hello")?;
        write_frame(&mut bytes, 0, 0, b"{}")?;
        for split in 0..bytes.len() {
            let mut decoder = Decoder::default();
            let mut received = Vec::new();
            decoder.bytes.extend_from_slice(&bytes[..split]);
            while let Some(frame) = decoder.next()? {
                received.push(frame);
            }
            decoder.bytes.extend_from_slice(&bytes[split..]);
            while let Some(frame) = decoder.next()? {
                received.push(frame);
            }
            assert_eq!(
                received,
                vec![
                    Frame {
                        channel: 7,
                        kind: 0,
                        bytes: b"hello".to_vec()
                    },
                    Frame {
                        channel: 0,
                        kind: 0,
                        bytes: b"{}".to_vec()
                    }
                ]
            );
        }
        Ok(())
    }
    #[test]
    fn oversized_frame_fails_before_payload_allocation() {
        let mut decoder = Decoder { bytes: vec![0; 9] };
        decoder.bytes[5..].copy_from_slice(&((MAX_FRAME + 1) as u32).to_le_bytes());
        assert!(matches!(decoder.next(), Err(Error::Protocol(_))));
    }
    #[test]
    fn app_document_and_labels_supply_spaces_tabs_and_status() -> Result<()> {
        let mut view = json!({"revision":0});
        project(
            &mut view,
            &json!([{"id":"t1","state":"live","title":"Agent","labels":{"herdr.space":"s1","herdr.tab":"a","role":"pm","project":"billing","agent.kind":"codex"},"status":{"state":"blocked","msg":"Run the migration?","kind":"question"},"status_ms":42},{"id":"t2","state":"live","labels":{"role":"none"},"parent":"t1","argv":["/usr/bin/claude"]},{"id":"t3","state":"live","cwd":"/home/w/botd","labels":{"herdr.space":"sp9","where":"botd / w1-backend"}},{"id":"t4","state":"live","cwd":"/home/w/zulip/","labels":{"herdr.space":"sp8"}}]),
            &json!({"spaces":[{"id":"s1","name":"Code","tabs":[{"id":"a","name":"Fix"}]}]}),
        )?;
        assert_eq!(view["workspaces"][0]["label"], "Code");
        assert_eq!(view["tabs"][0]["label"], "Fix");
        assert_eq!(view["panes"][0]["status"], "blocked");
        assert_eq!(view["panes"][0]["msg"], "Run the migration?");
        assert_eq!(view["panes"][0]["wait_kind"], "question");
        assert_eq!(view["panes"][0]["status_ms"], 42);
        assert_eq!(view["panes"][1]["msg"], "");
        assert_eq!(
            [
                &view["panes"][0]["role"],
                &view["panes"][0]["project"],
                &view["panes"][0]["kind"]
            ],
            ["pm", "billing", "codex"]
        );
        assert_eq!(
            [
                &view["panes"][1]["parent"],
                &view["panes"][1]["kind"],
                &view["panes"][1]["role"]
            ],
            ["t1", "", ""]
        );
        let label = |id: &str| {
            view["workspaces"]
                .as_array()
                .and_then(|spaces| spaces.iter().find(|s| s["id"] == id))
                .map(|s| s["label"].clone())
        };
        assert_eq!(label("sp9"), Some(json!("botd")));
        assert_eq!(label("sp8"), Some(json!("zulip")));
        assert_eq!(view["panes"][1]["workspace_id"], "loose");
        assert_eq!(view["panes"][1]["role"], "");
        Ok(())
    }
    #[test]
    fn discovery_lists_self_first_then_online_peers_and_drops_unsafe_names() -> Result<()> {
        let peers = discovery_peers(&json!({
            "Self": {"HostName":"server","DNSName":"server.tail.ts.net.","OS":"linux"},
            "Peer": {
                "a": {"HostName":"zed","DNSName":"zed.tail.ts.net.","OS":"linux","Online":false},
                "b": {"HostName":"laptop","DNSName":"laptop.tail.ts.net.","OS":"linux","Online":true},
                "c": {"HostName":"evil","DNSName":"-oProxyCommand=x.","OS":"linux","Online":true},
                "d": {"HostName":"ipad","DNSName":"","TailscaleIPs":["100.64.0.9"],"OS":"iOS","Online":true}
            }
        }))?;
        let hosts: Vec<_> = peers
            .iter()
            .map(|p| p["host"].as_str().unwrap_or(""))
            .collect();
        assert_eq!(
            hosts,
            [
                "laptop.tail.ts.net",
                "server.tail.ts.net",
                "100.64.0.9",
                "zed.tail.ts.net"
            ]
        );
        assert_eq!(peers[1]["self"], true);
        Ok(())
    }
}

/// A mock core reads the phone's hello line on a socket with a short read
/// timeout (so its frame loop can poll). A loaded machine may send the hello
/// later than that, so time-outs are retried here until a long deadline.
#[cfg(test)]
#[allow(clippy::unwrap_used)]
fn read_hello(core: &UnixStream, hello: &mut String) {
    let mut reader = io::BufReader::new(core.try_clone().unwrap());
    let deadline = Instant::now() + Duration::from_secs(60);
    let mut line = Vec::new();
    while !line.ends_with(b"\n") {
        match io::BufRead::read_until(&mut reader, b'\n', &mut line) {
            Ok(0) => panic!("the phone closed before its hello"),
            Ok(_) => {}
            Err(e) if matches!(e.kind(), io::ErrorKind::WouldBlock | io::ErrorKind::TimedOut) => {
                assert!(Instant::now() < deadline, "no hello from the phone in 60 s");
            }
            Err(e) => panic!("reading the hello: {e}"),
        }
    }
    *hello = String::from_utf8(line).unwrap();
}

#[cfg(test)]
mod connection_tests {
    #![allow(clippy::unwrap_used, clippy::expect_used)]
    use super::*;
    use crate::remote::State;
    use herdr_remote_transport::CancellationToken;
    use std::collections::HashMap;
    use std::sync::Mutex;

    fn frame(socket: &mut UnixStream, decoder: &mut Decoder) -> Frame {
        let until = Instant::now() + Duration::from_secs(5);
        loop {
            if let Some(frame) = decoder.next().unwrap() {
                return frame;
            }
            assert!(Instant::now() < until, "peer stalled");
            let mut bytes = [0; 4096];
            match socket.read(&mut bytes) {
                Ok(0) => panic!("peer closed"),
                Ok(n) => decoder.bytes.extend_from_slice(&bytes[..n]),
                Err(e)
                    if matches!(
                        e.kind(),
                        io::ErrorKind::TimedOut | io::ErrorKind::WouldBlock
                    ) => {}
                Err(e) => panic!("{e}"),
            }
        }
    }
    #[test]
    fn core_handshake_attach_credit_input_and_detach_use_only_core_protocol() {
        let (phone, mut core) = UnixStream::pair().unwrap();
        core.set_read_timeout(Some(Duration::from_millis(50)))
            .unwrap();
        let (commands, rx) = mpsc::sync_channel(256);
        let owner = Arc::new(Session {
            id: 0,
            state: Mutex::new(State::new(json!({"revision":0,"panes":[]}))),
            cancel: CancellationToken::new(),
            commands,
            focus: Mutex::new(None),
            heard: crate::remote::Heard::default(),
            visible: std::sync::atomic::AtomicBool::new(true),
            uses: Box::leak(Box::new(crate::remote::LastUse::new())),
        });
        let worker = owner.clone();
        let runtime = std::thread::spawn(move || run(&worker, phone, 80, 24, true, rx));
        let (observed, events) = mpsc::sync_channel(2);
        let peer = std::thread::spawn(move || {
            let mut hello = String::new();
            read_hello(&core, &mut hello);
            let hello: Value = serde_json::from_str(&hello).unwrap();
            assert_eq!(hello["method"], "hello");
            assert_eq!(hello["params"], json!({"framed":true,"generation":1}));
            core.write_all(b"{\"jsonrpc\":\"2.0\",\"id\":0,\"result\":{\"generation\":1,\"core\":{\"id\":\"fixture\"},\"framed\":true}}\n").unwrap();
            let mut decoder = Decoder::default();
            // The second doc.get is projects.order (the project switcher's order).
            for expected in [
                "events.subscribe",
                "terminal.list",
                "doc.get",
                "doc.get",
                "terminal.attach",
            ] {
                let request: Value =
                    serde_json::from_slice(&frame(&mut core, &mut decoder).bytes).unwrap();
                assert_eq!(request["method"], expected);
                let result = match expected {
                    "events.subscribe" => json!({"channel":1,"seq":0,"terminals":[]}),
                    "terminal.list" => {
                        json!({"terminals":[{"address":"t1","state":"live","labels":{},"cwd":"/tmp"}]})
                    }
                    "doc.get" => json!({"value":null,"version":0}),
                    _ => {
                        assert_eq!(request["params"]["terminal"], "t1");
                        json!({"channel":2})
                    }
                };
                write_frame(
                    &mut core,
                    0,
                    0,
                    &serde_json::to_vec(
                        &json!({"jsonrpc":"2.0","id":request["id"],"result":result}),
                    )
                    .unwrap(),
                )
                .unwrap();
            }
            write_frame(&mut core, 2, 0, b"hello core\r\n").unwrap();
            let credit = frame(&mut core, &mut decoder);
            assert_eq!((credit.channel, credit.kind), (2, 1));
            assert_eq!(credit.bytes, 12u32.to_le_bytes());
            observed.send(()).unwrap();
            let input = frame(&mut core, &mut decoder);
            assert_eq!((input.channel, input.kind), (2, 0));
            assert_eq!(input.bytes, b"x");
            observed.send(()).unwrap();
            // Closing the viewer releases its claim with a DETACH and never
            // requests terminal.kill or server.stop.
            let mut trailing = Vec::new();
            let mut bytes = [0; 4096];
            loop {
                match core.read(&mut bytes) {
                    Ok(0) => break,
                    Ok(n) => decoder.bytes.extend_from_slice(&bytes[..n]),
                    Err(e)
                        if matches!(
                            e.kind(),
                            io::ErrorKind::WouldBlock | io::ErrorKind::TimedOut
                        ) => {}
                    Err(_) => break,
                }
                while let Some(frame) = decoder.next().unwrap() {
                    trailing.push((frame.channel, frame.kind, frame.bytes));
                }
            }
            assert_eq!(trailing, vec![(2, DETACH, Vec::new())]);
        });
        events.recv_timeout(Duration::from_secs(5)).unwrap();
        let terminal = lock(&owner.state).terminal.clone().unwrap();
        assert!(terminal.type_text("x"));
        events.recv_timeout(Duration::from_secs(5)).unwrap();
        owner.cancel.cancel();
        assert!(runtime.join().unwrap().is_ok());
        peer.join().unwrap();
    }
    /// Stage 1's one-tap send: a held message is typed only into the attach
    /// that was on screen at the tap, after its first paint, as one paste
    /// then Enter; a stale or unpainted attach gets nothing (E5, E7, E14).
    #[test]
    fn a_held_message_is_typed_only_into_the_painted_attach_it_was_tapped_over() {
        use crate::remote::{Typed, compose_on};
        let (phone, mut core) = UnixStream::pair().unwrap();
        core.set_read_timeout(Some(Duration::from_millis(50))).unwrap();
        let (commands, rx) = mpsc::sync_channel(256);
        let owner = Arc::new(Session {
            id: 0,
            state: Mutex::new(State::new(json!({"revision":0,"panes":[]}))),
            cancel: CancellationToken::new(),
            commands,
            focus: Mutex::new(None),
            heard: crate::remote::Heard::default(),
            visible: std::sync::atomic::AtomicBool::new(true),
            uses: Box::leak(Box::new(crate::remote::LastUse::new())),
        });
        let worker = owner.clone();
        let runtime = std::thread::spawn(move || run(&worker, phone, 80, 24, true, rx));
        let (paint, paint_rx) = mpsc::sync_channel::<()>(1);
        let (typed, typed_rx) = mpsc::sync_channel(8);
        let peer = std::thread::spawn(move || {
            let mut hello = String::new();
            read_hello(&core, &mut hello);
            core.write_all(b"{\"jsonrpc\":\"2.0\",\"id\":0,\"result\":{\"generation\":1,\"core\":{\"id\":\"fixture\"},\"framed\":true}}\n").unwrap();
            let mut decoder = Decoder::default();
            for expected in ["events.subscribe", "terminal.list", "doc.get", "doc.get", "terminal.attach"] {
                let request: Value = serde_json::from_slice(&frame(&mut core, &mut decoder).bytes).unwrap();
                assert_eq!(request["method"], expected);
                let result = match expected {
                    "events.subscribe" => json!({"channel":1,"seq":0,"terminals":[]}),
                    "terminal.list" => json!({"terminals":[{"address":"t1","state":"live","labels":{},"cwd":"/tmp"}]}),
                    "doc.get" => json!({"value":null,"version":0}),
                    _ => json!({"channel":2}),
                };
                let reply = json!({"jsonrpc":"2.0","id":request["id"],"result":result});
                write_frame(&mut core, 0, 0, &serde_json::to_vec(&reply).unwrap()).unwrap();
            }
            // The first paint comes only when the test says so.
            paint_rx.recv_timeout(Duration::from_secs(10)).unwrap();
            write_frame(&mut core, 2, 0, b"$ ").unwrap();
            // Everything the phone types on the attach's channel from now on.
            let until = Instant::now() + Duration::from_secs(10);
            while Instant::now() < until {
                let mut bytes = [0; 4096];
                match core.read(&mut bytes) {
                    Ok(0) => break,
                    Ok(n) => decoder.bytes.extend_from_slice(&bytes[..n]),
                    Err(e) if matches!(e.kind(), io::ErrorKind::WouldBlock | io::ErrorKind::TimedOut) => {}
                    Err(_) => break,
                }
                while let Some(frame) = decoder.next().unwrap() {
                    if frame.channel == 2 && frame.kind == DATA {
                        typed.send(frame.bytes).unwrap();
                    }
                }
            }
        });
        let until = Instant::now() + Duration::from_secs(20);
        while lock(&owner.state).render_id == 0 {
            assert!(Instant::now() < until, "never attached");
            std::thread::sleep(Duration::from_millis(10));
        }
        let attach = lock(&owner.state).render_id;
        let wait = Duration::from_secs(5);
        // Attached but not painted yet: nothing is typed.
        assert_eq!(compose_on(&owner, attach, "too early", wait), Typed::Unwritten);
        paint.send(()).unwrap();
        while !lock(&owner.state).painted {
            assert!(Instant::now() < until, "never painted");
            std::thread::sleep(Duration::from_millis(10));
        }
        // Another attach than the one on screen (an older tap): nothing is typed.
        assert_eq!(compose_on(&owner, attach + 1, "stale tap", wait), Typed::Unwritten);
        assert_eq!(compose_on(&owner, 0, "no attach", wait), Typed::Unwritten);
        // The attach on screen: one paste, then Enter, in that order.
        assert_eq!(compose_on(&owner, attach, "go\nnow", wait), Typed::Written);
        assert_eq!(typed_rx.recv_timeout(Duration::from_secs(5)).unwrap(), b"go\rnow");
        assert_eq!(typed_rx.recv_timeout(Duration::from_secs(5)).unwrap(), b"\r");
        // Nothing else was typed: not the early tap, not the stale ones.
        assert!(typed_rx.recv_timeout(Duration::from_millis(300)).is_err());
        owner.cancel.cancel();
        assert!(runtime.join().unwrap().is_ok());
        peer.join().unwrap();
        // The session is gone: a later tap writes nothing.
        assert_eq!(compose_on(&owner, attach, "after the end", wait), Typed::Unwritten);
    }
    #[test]
    fn a_coalesced_attach_reply_initial_resize_and_paint_are_all_applied_without_echo() {
        let (phone, mut core) = UnixStream::pair().unwrap();
        core.set_read_timeout(Some(Duration::from_millis(50))).unwrap();
        let (commands, rx) = mpsc::sync_channel(256);
        let owner = Arc::new(Session {
            id: 0,
            state: Mutex::new(State::new(json!({"revision":0,"panes":[]}))),
            cancel: CancellationToken::new(),
            commands,
            focus: Mutex::new(None),
            heard: crate::remote::Heard::default(),
            visible: std::sync::atomic::AtomicBool::new(true),
            uses: Box::leak(Box::new(crate::remote::LastUse::new())),
        });
        let worker = owner.clone();
        let runtime = std::thread::spawn(move || run(&worker, phone, 80, 24, true, rx));
        let (observed, events) = mpsc::sync_channel(2);
        let peer = std::thread::spawn(move || {
            let mut hello = String::new();
            read_hello(&core, &mut hello);
            core.write_all(b"{\"jsonrpc\":\"2.0\",\"id\":0,\"result\":{\"generation\":1,\"core\":{\"id\":\"fixture\"},\"framed\":true}}\n").unwrap();
            let mut decoder = Decoder::default();
            // The second doc.get is projects.order (the project switcher's order).
            for expected in ["events.subscribe", "terminal.list", "doc.get", "doc.get", "terminal.attach"] {
                let request: Value = serde_json::from_slice(&frame(&mut core, &mut decoder).bytes).unwrap();
                assert_eq!(request["method"], expected);
                let result = match expected {
                    "events.subscribe" => json!({"channel":1,"seq":0,"terminals":[]}),
                    "terminal.list" => json!({"terminals":[{"address":"t1","state":"live","labels":{},"cwd":"/tmp"}]}),
                    "doc.get" => json!({"value":null,"version":0}),
                    _ => {
                        assert_eq!(request["params"]["resize_notifications"], true);
                        json!({"channel":2,"resize_notifications":true,"terminal":{"cols":100,"rows":30}})
                    }
                };
                let mut bytes = Vec::new();
                write_frame(&mut bytes, 0, 0,
                    &serde_json::to_vec(&json!({"jsonrpc":"2.0","id":request["id"],"result":result})).unwrap()).unwrap();
                if expected == "terminal.attach" {
                    // The initial RESIZE (always sent, even at the same size) and the
                    // first paint, coalesced with the reply into ONE write.
                    let mut size = Vec::from(100u16.to_le_bytes());
                    size.extend(30u16.to_le_bytes());
                    write_frame(&mut bytes, 2, 3, &size).unwrap();
                    write_frame(&mut bytes, 2, 0, b"hello core\r\n").unwrap();
                }
                core.write_all(&bytes).unwrap();
            }
            // The paint arrived (it is credited), and the phone sent no RESIZE back.
            let credit = frame(&mut core, &mut decoder);
            assert_eq!((credit.channel, credit.kind), (2, 1));
            assert_eq!(credit.bytes, 12u32.to_le_bytes());
            observed.send(()).unwrap();
            let until = Instant::now() + Duration::from_millis(300);
            while Instant::now() < until {
                let mut bytes = [0; 4096];
                match core.read(&mut bytes) {
                    Ok(0) => break,
                    Ok(n) => decoder.bytes.extend_from_slice(&bytes[..n]),
                    Err(_) => {}
                }
                while let Some(frame) = decoder.next().unwrap() {
                    assert_ne!(frame.kind, 3, "the phone echoed a resize");
                }
            }
            observed.send(()).unwrap();
            // Stay connected until the phone closes, as a core would.
            let mut byte = [0];
            loop {
                match core.read(&mut byte) {
                    Ok(0) => break,
                    Ok(_) => {}
                    Err(e) if matches!(e.kind(), io::ErrorKind::WouldBlock | io::ErrorKind::TimedOut) => {}
                    Err(_) => break,
                }
            }
        });
        events.recv_timeout(Duration::from_secs(5)).unwrap();
        let terminal = lock(&owner.state).terminal.clone().unwrap();
        assert!(terminal.negotiated());
        assert_eq!(terminal.grid_size(), (100, 30));
        let deadline = Instant::now() + Duration::from_secs(2);
        while !terminal.screen_text().contains("hello core") {
            assert!(Instant::now() < deadline, "the coalesced paint was lost");
            std::thread::sleep(Duration::from_millis(10));
        }
        events.recv_timeout(Duration::from_secs(5)).unwrap();
        owner.cancel.cancel();
        assert!(runtime.join().unwrap().is_ok());
        peer.join().unwrap();
    }
    /// Runs the phone's core session against a fixture core that answers each
    /// request with `answer(method, params, nth call of that method)`, asks for
    /// one Start Deacon, and returns the reply and every method the core saw.
    fn start_deacon_against(
        answer: impl Fn(&str, &Value, usize) -> std::result::Result<Value, &'static str> + Send + 'static,
    ) -> (Result<String>, Vec<(String, Value)>) {
        action_against(r#"{"op":"deacon.start","name":"laptop"}"#, answer)
    }

    /// Runs one phone action against a scripted core; returns its reply and
    /// every request the core saw, in order.
    fn action_against(
        action: &str,
        answer: impl Fn(&str, &Value, usize) -> std::result::Result<Value, &'static str> + Send + 'static,
    ) -> (Result<String>, Vec<(String, Value)>) {
        operation_against(|tx| Operation::Action(action.into(), tx, None), answer)
    }

    /// Audit A4: an action whose caller already gave up (and was told Busy) never runs, so a
    /// retry cannot launch a second shell; one still in time runs as before.
    #[test]
    fn an_action_its_caller_gave_up_on_never_runs_late() {
        let answer = |method: &str, _: &Value, _: usize| -> std::result::Result<Value, &'static str> {
            Ok(match method {
                "terminal.run" => json!({"address":"t9"}),
                "terminal.attach" => json!({"channel":2}),
                other => base_answer(other),
            })
        };
        let past = Instant::now() - Duration::from_millis(1);
        let (result, seen) = operation_against(|tx| Operation::Action("tab.new".into(), tx, Some(past)), answer);
        assert!(matches!(result, Err(Error::Busy)), "{result:?}");
        assert!(seen.iter().all(|(method, _)| method != "terminal.run"), "{seen:?}");
        let soon = Instant::now() + Duration::from_secs(30);
        let (result, seen) = operation_against(|tx| Operation::Action("tab.new".into(), tx, Some(soon)), answer);
        assert!(result.is_ok(), "{result:?}");
        assert!(seen.iter().any(|(method, _)| method == "terminal.run"), "{seen:?}");
    }

    /// As `action_against`, for any operation the worker takes.
    fn operation_against(
        make: impl FnOnce(mpsc::SyncSender<Result<String>>) -> Operation,
        answer: impl Fn(&str, &Value, usize) -> std::result::Result<Value, &'static str> + Send + 'static,
    ) -> (Result<String>, Vec<(String, Value)>) {
        operation_against_with(&[], make, answer)
    }

    /// As [`operation_against`], with a core whose hello offers `features`.
    fn operation_against_with(
        features: &'static [&'static str],
        make: impl FnOnce(mpsc::SyncSender<Result<String>>) -> Operation,
        answer: impl Fn(&str, &Value, usize) -> std::result::Result<Value, &'static str> + Send + 'static,
    ) -> (Result<String>, Vec<(String, Value)>) {
        let (phone, mut core) = UnixStream::pair().unwrap();
        core.set_read_timeout(Some(Duration::from_millis(50))).unwrap();
        let (commands, rx) = mpsc::sync_channel(256);
        let owner = Arc::new(Session {
            id: 0,
            state: Mutex::new(State::new(json!({"revision":0,"panes":[]}))),
            cancel: CancellationToken::new(),
            commands: commands.clone(),
            focus: Mutex::new(None),
            heard: crate::remote::Heard::default(),
            visible: std::sync::atomic::AtomicBool::new(true),
            uses: Box::leak(Box::new(crate::remote::LastUse::new())),
        });
        let worker = owner.clone();
        let runtime = std::thread::spawn(move || run(&worker, phone, 80, 24, true, rx));
        let seen = Arc::new(Mutex::new(Vec::new()));
        let log = seen.clone();
        let peer = std::thread::spawn(move || {
            let mut hello = String::new();
            read_hello(&core, &mut hello);
            let mut answer_hello = serde_json::to_vec(&json!({"jsonrpc":"2.0","id":0,"result":{
                "generation":1,"core":{"id":"fixture"},"framed":true,"features":features}})).unwrap();
            answer_hello.push(b'\n');
            core.write_all(&answer_hello).unwrap();
            let mut decoder = Decoder::default();
            let mut counts: HashMap<String, usize> = HashMap::new();
            loop {
                let until = Instant::now() + Duration::from_secs(5);
                let request = loop {
                    if let Some(frame) = decoder.next().unwrap() {
                        if frame.channel == 0 { break Some(frame); }
                        continue;
                    }
                    if Instant::now() > until { break None; }
                    let mut bytes = [0; 4096];
                    match core.read(&mut bytes) {
                        Ok(0) => break None,
                        Ok(n) => decoder.bytes.extend_from_slice(&bytes[..n]),
                        Err(e) if matches!(e.kind(), io::ErrorKind::WouldBlock | io::ErrorKind::TimedOut) => {}
                        Err(_) => break None,
                    }
                };
                let Some(frame) = request else { break };
                let request: Value = serde_json::from_slice(&frame.bytes).unwrap();
                let method = request["method"].as_str().unwrap_or("").to_owned();
                let nth = { let n = counts.entry(method.clone()).or_insert(0); *n += 1; *n };
                lock(&log).push((method.clone(), request["params"].clone()));
                let reply = match answer(&method, &request["params"], nth) {
                    Ok(result) => json!({"jsonrpc":"2.0","id":request["id"],"result":result}),
                    Err(kind) => json!({"jsonrpc":"2.0","id":request["id"],"error":{"code":-32000,"message":kind,"data":{"kind":kind}}}),
                };
                write_frame(&mut core, 0, 0, &serde_json::to_vec(&reply).unwrap()).unwrap();
            }
        });
        let (tx, reply) = mpsc::sync_channel(1);
        commands.try_send(make(tx)).unwrap();
        let result = reply.recv_timeout(Duration::from_secs(60)).unwrap();
        owner.cancel.cancel();
        let _ = runtime.join();
        let _ = peer.join();
        let methods = lock(&seen).clone();
        (result, methods)
    }

    fn base_answer(method: &str) -> Value {
        match method {
            "events.subscribe" => json!({"channel":1,"seq":0,"terminals":[]}),
            "terminal.list" => json!({"terminals":[]}),
            "doc.get" => json!({"value":{"spaces":[]},"version":1}),
            _ => json!({}),
        }
    }

    fn exec_ok(path: &str) -> Value {
        use base64::Engine;
        json!({"code":0,"stdout":base64::engine::general_purpose::STANDARD.encode(format!("{path}\n")),"truncated":false})
    }

    #[test]
    fn a_layout_edit_retries_when_the_core_reports_a_version_conflict() {
        let action = r#"{"op":"tab.rename","space":"s1","tab":"a","name":"Bee"}"#;
        let (result, seen) = action_against(action, |method, _, nth| match method {
            "doc.get" => Ok(json!({"value":{"spaces":[{"id":"s1","tabs":[{"id":"a","name":"A"}]}]},"version":nth})),
            "doc.set" if nth == 1 => Err("conflict"),
            m => Ok(base_answer(m)),
        });
        assert!(result.is_ok(), "{result:?}");
        assert_eq!(seen.iter().filter(|(m, _)| m == "doc.set").count(), 2);
    }

    /// Stage 2: a transcript is read only from a core that offers
    /// `read-bounded` and `incarnation`, as one bounded, never-stitched
    /// `terminal.read`; an old core is asked nothing.
    #[test]
    fn a_transcript_read_is_bounded_never_stitched_and_only_where_the_core_offers_it() {
        use crate::remote::ReadRequest;
        let request = || ReadRequest::new("t7", 2000, 65536).unwrap();
        // An old core: nothing is asked, nothing is cached.
        let (result, seen) = operation_against(
            |tx| Operation::Read(request(), tx),
            |method, _, _| match method {
                "terminal.read" => panic!("an old core is never read"),
                m => Ok(base_answer(m)),
            },
        );
        assert_eq!(serde_json::from_str::<Value>(&result.unwrap()).unwrap(), json!({"supported":false}));
        assert!(seen.iter().all(|(m, _)| m != "terminal.read"));
        // One of the two features alone is not enough.
        let (result, _) = operation_against_with(&["read-bounded"], |tx| Operation::Read(request(), tx), |method, _, _| {
            assert_ne!(method, "terminal.read");
            Ok(base_answer(method))
        });
        assert_eq!(serde_json::from_str::<Value>(&result.unwrap()).unwrap()["supported"], false);
        // A core that offers both: one bounded read that never scrolls a full-screen program.
        let both: &[&str] = &["read-bounded", "incarnation"];
        let (result, seen) = operation_against_with(both, |tx| Operation::Read(request(), tx), |method, _, _| match method {
            "terminal.read" => Ok(json!({"text":"a\nb","complete":false,"how":"history","truncated":true,"incarnation":"0a1b2c3d4e5f6a7b"})),
            m => Ok(base_answer(m)),
        });
        let reply: Value = serde_json::from_str(&result.unwrap()).unwrap();
        assert_eq!(reply, json!({"supported":true,"text":"a\nb","truncated":true,"screen":false,"incarnation":"0a1b2c3d4e5f6a7b"}));
        // A full-screen program read without stitching is its screen only, and says so.
        let (result, _) = operation_against_with(both, |tx| Operation::Read(request(), tx), |method, _, _| match method {
            "terminal.read" => Ok(json!({"text":"top","complete":false,"how":"screen","truncated":false,"incarnation":"ab"})),
            m => Ok(base_answer(m)),
        });
        assert_eq!(serde_json::from_str::<Value>(&result.unwrap()).unwrap()["screen"], true);
        let read = seen.iter().find(|(m, _)| m == "terminal.read").unwrap();
        assert_eq!(read.1, json!({"terminal":"t7","lines":2000,"max_bytes":65536,"stitch":false}));
        assert!(seen.iter().all(|(m, _)| m != "terminal.send" && m != "terminal.keys"));
        // No incarnation in the answer: the phone gets none, and keeps nothing.
        let (result, _) = operation_against_with(both, |tx| Operation::Read(request(), tx), |method, _, _| match method {
            "terminal.read" => Ok(json!({"text":"x","truncated":false,"incarnation":null})),
            m => Ok(base_answer(m)),
        });
        assert!(serde_json::from_str::<Value>(&result.unwrap()).unwrap()["incarnation"].is_null());
        // A refusal says why; text past the bound asked for is a broken core, never kept.
        let (result, _) = operation_against_with(both, |tx| Operation::Read(request(), tx), |method, _, _| match method {
            "terminal.read" => Err("not_found"),
            m => Ok(base_answer(m)),
        });
        assert_eq!(serde_json::from_str::<Value>(&result.unwrap()).unwrap()["error"], "not_found");
        let (result, _) = operation_against_with(
            both,
            |tx| Operation::Read(ReadRequest::new("t7", 10, 4).unwrap(), tx),
            |method, _, _| match method {
                "terminal.read" => Ok(json!({"text":"far too long","truncated":false,"incarnation":"ab"})),
                m => Ok(base_answer(m)),
            },
        );
        assert!(result.is_err());
    }

    #[test]
    fn a_transcript_read_is_of_a_direct_terminal_within_its_bounds() {
        use crate::remote::{MAX_READ_BYTES, MAX_READ_LINES, ReadRequest};
        assert!(ReadRequest::new("t7", MAX_READ_LINES, MAX_READ_BYTES).is_ok());
        assert!(matches!(ReadRequest::new("macro/t7", 10, 10), Err(Error::StaleTarget)));
        assert!(matches!(ReadRequest::new("", 10, 10), Err(Error::StaleTarget)));
        assert!(matches!(ReadRequest::new("t7", 0, 10), Err(Error::Config)));
        assert!(matches!(ReadRequest::new("t7", MAX_READ_LINES + 1, 10), Err(Error::Config)));
        assert!(matches!(ReadRequest::new("t7", 10, MAX_READ_BYTES + 1), Err(Error::Config)));
    }

    #[test]
    fn the_pane_list_carries_each_programs_incarnation_when_the_core_has_one() {
        let mut view = json!({"pane_id":"t1","panes":[],"workspaces":[],"tabs":[],"revision":0,"token":0,"chrome_token":0});
        let terminals = json!([
            {"address":"t1","state":"live","labels":{},"incarnation":"0123456789abcdef"},
            {"address":"t2","state":"live","labels":{}},
            {"address":"t3","state":"live","labels":{},"incarnation":"bad value!"}
        ]);
        project(&mut view, &terminals, &json!({})).unwrap();
        let incarnation = |id: &str| view["panes"].as_array().unwrap().iter().find(|p| p["id"] == id).unwrap()["incarnation"].clone();
        assert_eq!(incarnation("t1"), "0123456789abcdef");
        assert!(incarnation("t2").is_null());
        assert!(incarnation("t3").is_null());
    }

    #[test]
    fn an_image_is_put_beside_its_terminal_and_its_path_comes_back() {
        use base64::Engine;
        let bytes = b"\x89PNG fixture".to_vec();
        let file = crate::remote::PutFile::new("server/t7", "herdr-paste-5.png", bytes.clone()).unwrap();
        let (result, seen) = operation_against(
            |tx| Operation::Put(file, tx),
            |method, _, _| match method {
                "terminal.put" => Ok(json!({"path":"/run/herdr/t7/herdr-paste-5.png","bytes":13})),
                m => Ok(base_answer(m)),
            },
        );
        let reply: Value = serde_json::from_str(&result.unwrap()).unwrap();
        assert_eq!(reply["path"], "/run/herdr/t7/herdr-paste-5.png");
        assert_eq!(reply["queued"], true);
        let put = seen.iter().find(|(m, _)| m == "terminal.put").unwrap();
        assert_eq!(put.1["terminal"], "server/t7");
        assert_eq!(put.1["name"], "herdr-paste-5.png");
        // The bytes cross as base64, over the one core connection, nothing typed.
        assert_eq!(put.1["data"], base64::engine::general_purpose::STANDARD.encode(&bytes));
        assert!(seen.iter().all(|(m, _)| m != "terminal.send" && m != "terminal.keys"));
    }

    #[test]
    fn a_core_that_refuses_a_put_says_why_and_a_broken_link_is_an_error() {
        for kind in ["needs_update", "unknown_method", "not_found", "io"] {
            let file = crate::remote::PutFile::new("t7", "a.png", vec![1, 2, 3]).unwrap();
            let (result, _) = operation_against(
                |tx| Operation::Put(file, tx),
                move |method, _, _| match method {
                    "terminal.put" => Err(kind),
                    m => Ok(base_answer(m)),
                },
            );
            let reply: Value = serde_json::from_str(&result.unwrap()).unwrap();
            assert_eq!((reply["queued"].as_bool(), reply["kind"].as_str()), (Some(false), Some(kind)));
        }
    }

    #[test]
    fn a_put_is_bounded_and_its_name_is_one_plain_file_name() {
        use crate::remote::{MAX_PUT, PutFile};
        assert!(PutFile::new("t1", "herdr-paste-1.jpg", vec![0; MAX_PUT]).is_ok());
        assert!(matches!(PutFile::new("t1", "a.png", vec![0; MAX_PUT + 1]), Err(Error::PutBounds)));
        assert!(matches!(PutFile::new("t1", "a.png", Vec::new()), Err(Error::PutBounds)));
        for bad in ["", ".", "..", "../a.png", "a/b.png", "a\nb.png", &"x".repeat(129)] {
            assert!(matches!(PutFile::new("t1", bad, vec![1]), Err(Error::PutName)), "{bad:?}");
        }
        assert!(matches!(PutFile::new("", "a.png", vec![1]), Err(Error::StaleTarget)));
    }

    #[test]
    fn a_file_is_read_on_its_terminals_machine_from_its_folder_or_home() {
        let answer = |method: &str, _: &Value, _| Ok(match method {
            "terminal.list" => json!({"terminals":[{"address":"server/t7","state":"live","cwd":"/w/proj"}]}),
            "terminal.attach" => json!({"channel":3}),
            "machine.exec" => json!({"code":0,"stdout":"L2hvbWUvd29sZg==","truncated":false}),
            "file.read" => json!({"text":"fn main() {}\n","first":1,"last":1,"lines":1}),
            m => base_answer(m),
        });
        let (result, seen) = action_against(r#"{"op":"file.read","terminal":"server/t7","path":"src/main.rs"}"#, answer);
        let reply: Value = serde_json::from_str(&result.unwrap()).unwrap();
        assert_eq!((reply["path"].as_str(), reply["text"].as_str()), (Some("/w/proj/src/main.rs"), Some("fn main() {}\n")));
        let read = seen.iter().find(|(m, _)| m == "file.read").unwrap();
        assert_eq!(read.1, json!({"path":"src/main.rs","cwd":"/w/proj","on":"server","offset":1,"limit":FILE_LINES}));
        // ~/ is that machine's home, asked of that machine.
        let (result, seen) = action_against(r#"{"op":"file.read","terminal":"server/t7","path":"~/notes.md"}"#, answer);
        let reply: Value = serde_json::from_str(&result.unwrap()).unwrap();
        assert_eq!(reply["path"], "/home/wolf/notes.md");
        let exec = seen.iter().find(|(m, _)| m == "machine.exec").unwrap();
        assert_eq!(exec.1["on"], "server");
        let read = seen.iter().find(|(m, _)| m == "file.read").unwrap();
        assert_eq!((read.1["path"].as_str(), read.1["cwd"].as_str()), (Some("notes.md"), Some("/home/wolf")));
        // A failed read is the phone's message to show, not a dropped session.
        let (result, _) = action_against(r#"{"op":"file.read","terminal":"server/t7","path":"/bin/sh"}"#, |method, _, _| match method {
            "file.read" => Err("not_text"),
            m => Ok(base_answer(m)),
        });
        let reply: Value = serde_json::from_str(&result.unwrap()).unwrap();
        assert_eq!(reply["queued"], false);
        assert!(reply["error"].as_str().unwrap().contains("not_text"));
    }

    #[test]
    fn start_deacon_runs_in_the_targets_own_deacon_folder() {
        let (result, seen) = start_deacon_against(|method, _, _| Ok(match method {
            "machine.exec" => exec_ok("/home/wolf/.herdr/deacon"),
            "terminal.run" => json!({"address":"t9","cwd":"/home/wolf/.herdr/deacon"}),
            "doc.set" => json!({"version":2}),
            m => base_answer(m),
        }));
        let reply: Value = serde_json::from_str(&result.unwrap()).unwrap();
        assert_eq!(reply["started"], true);
        let exec = seen.iter().find(|(m, _)| m == "machine.exec").unwrap();
        assert_eq!(exec.1["argv"], json!(["sh","-c",DEACON_DIR]));
        let run = seen.iter().find(|(m, _)| m == "terminal.run").unwrap();
        assert_eq!(run.1["cwd"], "/home/wolf/.herdr/deacon");
        assert!(!run.1["line"].as_str().unwrap_or("").contains("Config"));
        let set = seen.iter().find(|(m, _)| m == "doc.set").unwrap();
        assert_eq!(set.1["value"]["spaces"][0]["folder"], "/home/wolf/.herdr/deacon");
        // The folder was resolved before the second duplicate check, which came before the launch.
        let order: Vec<&str> = seen.iter().map(|(m, _)| m.as_str()).collect();
        let exec_at = order.iter().position(|m| *m == "machine.exec").unwrap();
        let run_at = order.iter().position(|m| *m == "terminal.run").unwrap();
        assert!(order[exec_at..run_at].contains(&"terminal.list"), "{order:?}");
    }

    #[test]
    fn a_deacon_that_appeared_while_the_folder_resolved_is_returned_not_relaunched() {
        let (result, seen) = start_deacon_against(|method, _, nth| Ok(match method {
            "machine.exec" => exec_ok("/home/wolf/.herdr/deacon"),
            // The 1st list is the session's own refresh, the 2nd the first check;
            // by the 3rd (after the folder resolved) another client's Deacon is up.
            "terminal.list" if nth >= 3 => json!({"terminals":[{"address":"t7","state":"live","labels":{"role":"deacon"},"created_ms":5}]}),
            m => base_answer(m),
        }));
        let reply: Value = serde_json::from_str(&result.unwrap()).unwrap();
        assert_eq!((reply["terminal"].as_str(), reply["started"].as_bool()), (Some("t7"), Some(false)));
        assert!(!seen.iter().any(|(m, _)| m == "terminal.run"), "launched a duplicate");
    }

    #[test]
    fn a_computer_that_stops_answering_while_the_folder_resolves_gets_no_launch() {
        // The folder could not be made, or the computer went away mid-start: nothing runs.
        let (result, seen) = start_deacon_against(|method, _, _| match method {
            "machine.exec" => Err("unavailable"),
            m => Ok(base_answer(m)),
        });
        assert!(result.is_err());
        assert!(!seen.iter().any(|(m, _)| m == "terminal.run"));
        let (result, seen) = start_deacon_against(|method, _, nth| match method {
            "machine.exec" => Ok(exec_ok("/home/wolf/.herdr/deacon")),
            "terminal.list" if nth >= 3 => Err("unavailable"),
            m => Ok(base_answer(m)),
        });
        assert!(result.is_err());
        assert!(!seen.iter().any(|(m, _)| m == "terminal.run"));
    }

    #[test]
    fn a_held_frame_scrolls_its_own_history_with_no_link_and_lets_go_on_attach() {
        // The closed slot is shared with the departed-pane test.
        let _serial = crate::remote::departed_test_lock();
        let (tx, rx) = mpsc::sync_channel(16);
        let (commands, _credits) = mpsc::sync_channel(64);
        let slot = Arc::new(Mutex::new(Weak::new()));
        let output = StreamRead { rx, chunks: VecDeque::new(), commands: commands.clone(), channel: 9, session: slot.clone() };
        let input = StreamWrite { commands, channel: 9, kind: DATA };
        let session = gpui_rio::Session::start(output, input, |_, _| {}, crate::shell::theme(&crate::model::palette()), 20, 4).unwrap();
        *lock(&slot) = Arc::downgrade(&session);
        let lines: String = (0..30).map(|n| format!("line {n}\r\n")).collect();
        tx.send(Chunk::Data(lines.into_bytes())).unwrap();
        let deadline = Instant::now() + Duration::from_secs(5);
        while session.grid().lock().history_size() < 20 {
            assert!(Instant::now() < deadline, "the lines never reached the history");
            std::thread::sleep(Duration::from_millis(10));
        }
        // The link is gone: the stream ends, the grid stays.
        drop(tx);
        crate::remote::hold(session.clone());
        let offset = |reply: String| serde_json::from_str::<Value>(&reply).unwrap()["local_offset"].as_u64().unwrap();
        assert_eq!(offset(crate::remote::scroll_held(5, false)), 5);
        assert_eq!(offset(crate::remote::scroll_held(-2, false)), 3);
        assert!(session.screen_text().contains("line"));
        assert_eq!(offset(crate::remote::scroll_held(0, true)), 0);
        // Saved for its own pane only, then read back as the held frame.
        let path = std::env::temp_dir().join(format!("herdr-held-{}.snap", std::process::id()));
        let path = path.to_str().unwrap();
        assert!(!crate::remote::save_pane(0, "held-other", path).unwrap());
        crate::remote::closed_for_test(0, session.clone(), "t9");
        assert!(!crate::remote::save_pane(0, "held-other", path).unwrap());
        assert!(crate::remote::save_pane(0, "t9", path).unwrap());
        let id = crate::remote::open_saved(path).unwrap();
        let restored = crate::remote::renderer(id).unwrap();
        let deadline = Instant::now() + Duration::from_secs(5);
        while restored.is_alive() {
            assert!(Instant::now() < deadline);
            std::thread::sleep(Duration::from_millis(5));
        }
        assert_eq!(restored.screen_text(), session.screen_text());
        assert_eq!(offset(crate::remote::scroll_held(4, false)), 4);
        assert_eq!(session.grid().lock().display_offset(), 0, "the restored copy scrolls, not the old grid");
        std::fs::remove_file(path).unwrap();
        // A new pane attached: nothing is held, and a scroll is a no-op.
        crate::remote::release_held();
        assert_eq!(offset(crate::remote::scroll_held(5, false)), 0);
        assert!(crate::remote::renderer(id).is_none());
    }
    /// A grid for tests: 20x4, with `text` written into it.
    fn grid_with(text: &str) -> Arc<gpui_rio::Session> {
        let (tx, rx) = mpsc::sync_channel(16);
        let (commands, _credits) = mpsc::sync_channel(64);
        let slot = Arc::new(Mutex::new(Weak::new()));
        let output = StreamRead { rx, chunks: VecDeque::new(), commands: commands.clone(), channel: 9, session: slot.clone() };
        let input = StreamWrite { commands, channel: 9, kind: DATA };
        let session = gpui_rio::Session::start(output, input, |_, _| {}, crate::shell::theme(&crate::model::palette()), 20, 4).unwrap();
        *lock(&slot) = Arc::downgrade(&session);
        tx.send(Chunk::Data(text.as_bytes().to_vec())).unwrap();
        drop(tx);
        let deadline = Instant::now() + Duration::from_secs(5);
        while !session.screen_text().contains(text) {
            assert!(Instant::now() < deadline, "the text never reached the grid");
            std::thread::sleep(Duration::from_millis(10));
        }
        session
    }

    /// A grid started at `start` (the phone's own size), then given the
    /// core's `grid` before any byte (as an attach does), with `lines`
    /// numbered rows written into it: "row 0" first, the caret on a blank row
    /// after the last.
    fn written(start: (u16, u16), grid: (u16, u16), lines: usize) -> Arc<gpui_rio::Session> {
        let (tx, rx) = mpsc::sync_channel(16);
        let (commands, _credits) = mpsc::sync_channel(64);
        let slot = Arc::new(Mutex::new(Weak::new()));
        let output = StreamRead { rx, chunks: VecDeque::new(), commands: commands.clone(), channel: 9, session: slot.clone() };
        let input = StreamWrite { commands, channel: 9, kind: DATA };
        let session = gpui_rio::Session::start(output, input, |_, _| {}, crate::shell::theme(&crate::model::palette()), start.0, start.1).unwrap();
        *lock(&slot) = Arc::downgrade(&session);
        session.set_grid(grid.0, grid.1);
        let text: String = (0..lines).map(|n| format!("row {n}\r\n")).collect();
        tx.send(Chunk::Data(text.into_bytes())).unwrap();
        drop(tx);
        let last = format!("row {}", lines - 1);
        let deadline = Instant::now() + Duration::from_secs(5);
        while !session.screen_text().contains(&last) || session.is_alive() {
            assert!(Instant::now() < deadline, "the rows never reached the grid");
            std::thread::sleep(Duration::from_millis(5));
        }
        session
    }

    /// The rows drawn `offset` rows back, read the way the renderer reads
    /// them (Line(row - offset) for each screen row), each trimmed; every
    /// screen row is returned, blank ones too.
    fn rows_back(session: &gpui_rio::Session, offset: i32) -> Vec<String> {
        use rio_vt::crosswords::{grid::Scroll, pos::{Column, Line}};
        let mut grid = session.grid().lock();
        grid.scroll_display(Scroll::Bottom);
        grid.scroll_display(Scroll::Delta(offset));
        let shown = grid.display_offset() as i32;
        let rows = (0..grid.screen_lines() as i32)
            .map(|row| {
                (0..grid.columns())
                    .map(|col| match grid.grid[Line(row - shown)][Column(col)].c() {
                        '\0' => ' ',
                        c => c,
                    })
                    .collect::<String>()
                    .trim_end()
                    .to_owned()
            })
            .collect();
        grid.scroll_display(Scroll::Bottom);
        rows
    }

    /// What `offset` rows back must show on a `rows`-row screen after
    /// `lines` numbered rows and a blank caret row: rows in order, newest last.
    fn expected_back(lines: usize, rows: usize, offset: usize) -> Vec<String> {
        // Screen rows are the last `rows` of: row 0 .. row lines-1, then "".
        let all: Vec<String> = (0..lines).map(|n| format!("row {n}")).chain([String::new()]).collect();
        let bottom = all.len() - offset;
        all[bottom - rows..bottom].to_vec()
    }

    #[test]
    fn scrolled_back_a_grid_shows_its_history_in_order_whatever_size_it_started_at() {
        for (start, grid) in [((20, 4), (20, 4)), ((20, 6), (20, 4)), ((20, 3), (20, 4)), ((30, 5), (20, 4))] {
            let session = written(start, grid, 30);
            let history = session.grid().lock().history_size();
            assert_eq!(history, 27, "{start:?} -> {grid:?}");
            for offset in [1, 5, 21, 27] {
                assert_eq!(rows_back(&session, offset as i32), expected_back(30, 4, offset), "{start:?} -> {grid:?} at {offset}");
            }
        }
    }

    /// All rows from the oldest history row to the last screen row, read the
    /// way the renderer reads them: what scrolling all the way back shows,
    /// then the screen.
    pub(super) fn all_rows(session: &gpui_rio::Session) -> Vec<String> {
        let history = session.grid().lock().history_size();
        let mut out: Vec<String> = Vec::new();
        for offset in (0..=history).rev() {
            let shown = rows_back(session, offset as i32);
            if offset == history {
                out.extend(shown);
            } else if let Some(last) = shown.last() {
                out.push(last.clone());
            }
        }
        out
    }

    #[test]
    fn a_grid_made_smaller_then_larger_again_keeps_its_scrollback_in_order() {
        // Each size the phone's pane goes through (keyboard up and down, a
        // fold, the core's RESIZE): rows go into history and come back.
        for (lines, small) in [(8usize, 5u16), (8, 3), (30, 5), (30, 8), (12, 9)] {
            let session = written((20, 10), (20, 10), lines);
            let before = all_rows(&session);
            session.set_grid(20, small);
            session.set_grid(20, 10);
            let history = session.grid().lock().history_size();
            let after = all_rows(&session);
            assert_eq!(after.iter().filter(|r| !r.is_empty()).collect::<Vec<_>>(),
                before.iter().filter(|r| !r.is_empty()).collect::<Vec<_>>(),
                "{lines} rows, 10 -> {small} -> 10, history {history}");
            // No row is shown twice: history never repeats the screen.
            let mut seen = std::collections::HashSet::new();
            for row in after.iter().filter(|r| !r.is_empty()) {
                assert!(seen.insert(row.clone()), "{row} shown twice after 10 -> {small} -> 10: {after:?}");
            }
        }
    }

    /// What herdr-core sends a viewer to repaint a pane (ghostty-vt
    /// `Terminal::paint`): a reset ending in ESC[H ESC[2J, then (on a first
    /// paint) the history pushed up with a CRLF and `rows - 1` newlines (exactly
    /// off the top, home-manager-conf #103) and erased, then the scroll region
    /// and the screen rows from the top.
    pub(super) fn core_paint(history: &[String], screen: &[String], rows: usize) -> Vec<u8> {
        let mut out = String::from("\x1b[!p\x1b[?2026l\x1b[?1049l\x1b[?1047l\x1b[?47l\x1b[H\x1b[2J");
        if !history.is_empty() {
            out.push_str(&history.join("\r\n"));
            out.push_str("\x1b[0m\r\n");
            out.push_str(&"\n".repeat(rows.saturating_sub(1)));
            out.push_str("\x1b[H\x1b[2J");
        }
        out.push_str(&format!("\x1b[1;{rows}r\x1b[H"));
        out.push_str(&screen.join("\r\n"));
        out.into_bytes()
    }

    /// A pane fed `chunks` in order, as one attach would deliver them.
    fn painted(cols: u16, rows: u16, chunks: Vec<Vec<u8>>) -> Arc<gpui_rio::Session> {
        let (tx, rx) = mpsc::sync_channel(16);
        let (commands, _credits) = mpsc::sync_channel(64);
        let slot = Arc::new(Mutex::new(Weak::new()));
        let output = StreamRead { rx, chunks: VecDeque::new(), commands: commands.clone(), channel: 9, session: slot.clone() };
        let input = StreamWrite { commands, channel: 9, kind: DATA };
        let session = gpui_rio::Session::start(output, input, |_, _| {}, crate::shell::theme(&crate::model::palette()), cols, rows).unwrap();
        *lock(&slot) = Arc::downgrade(&session);
        for chunk in chunks {
            tx.send(Chunk::Data(chunk)).unwrap();
        }
        drop(tx);
        let deadline = Instant::now() + Duration::from_secs(5);
        while session.is_alive() {
            assert!(Instant::now() < deadline, "the paint was never parsed");
            std::thread::sleep(Duration::from_millis(5));
        }
        session
    }

    /// Item 77 (A): scrolling back showed the screen again, as if the rows
    /// had rotated. Each core repaint (first paint, a RESIZE, showing the
    /// viewer again) starts with ESC[2J, which copied the screen into the
    /// phone's scrollback. The scrollback is the pane's history, once, and
    /// meets the screen with no blank row between (rev-pr38's seam).
    #[test]
    fn core_repaints_never_copy_the_screen_into_the_scrollback() {
        let line = |name: &str, n: usize| format!("{name} {n}");
        // A pane longer than the screen: 30 history rows, a 10-row screen.
        let history: Vec<String> = (0..30).map(|n| line("old", n)).collect();
        let screen: Vec<String> = (0..10).map(|n| line("now", n)).collect();
        let first = core_paint(&history, &screen, 10);
        let again = core_paint(&[], &screen, 10);
        let pane = painted(20, 10, vec![first, again.clone(), again]);
        // Every row counts, blank ones too.
        let expected: Vec<String> = history.iter().chain(&screen).cloned().collect();
        assert_eq!(all_rows(&pane), expected);
        assert_eq!(pane.grid().lock().history_size(), history.len());
        // Every offset shows rows in order, newest at the bottom.
        let history_rows = pane.grid().lock().history_size();
        let top = rows_back(&pane, history_rows as i32);
        assert_eq!(top.first().map(String::as_str), Some("old 0"));
        // A pane that fits its screen has nothing to scroll back to, however often it is repainted.
        let short: Vec<String> = (0..4).map(|n| line("note", n)).collect();
        let fits = painted(20, 10, vec![core_paint(&[], &short, 10), core_paint(&[], &short, 10), core_paint(&[], &short, 10)]);
        assert_eq!(fits.grid().lock().history_size(), 0);
        assert_eq!(rows_back(&fits, 5), rows_back(&fits, 0), "nothing to scroll back to");
    }

    /// A pane fed `chunks` (DATA and the core's RESIZE) in stream order.
    /// What herdr-core sends after a RESIZE to a viewer that asked for
    /// `resize_history` (home-manager-conf #88): CAN, the paint's reset
    /// (ending ESC[H ESC[2J), ESC[3J, then the history and the screen.
    pub(super) fn core_resize_paint(history: &[String], screen: &[String], rows: usize) -> Vec<u8> {
        let paint = core_paint(history, screen, rows);
        let reset = b"\x1b[H\x1b[2J";
        let at = paint.windows(reset.len()).position(|w| w == reset).unwrap() + reset.len();
        [b"\x18".as_slice(), &paint[..at], b"\x1b[3J", &paint[at..]].concat()
    }

    fn streamed(cols: u16, rows: u16, chunks: Vec<Chunk>) -> Arc<gpui_rio::Session> {
        let (tx, rx) = mpsc::sync_channel(64);
        let (commands, _credits) = mpsc::sync_channel(4096);
        let slot = Arc::new(Mutex::new(Weak::new()));
        let output = StreamRead { rx, chunks: VecDeque::new(), commands: commands.clone(), channel: 9, session: slot.clone() };
        let input = StreamWrite { commands, channel: 9, kind: DATA };
        let pane = gpui_rio::Session::start(output, input, |_, _| {}, crate::shell::theme(&crate::model::palette()), cols, rows).unwrap();
        *lock(&slot) = Arc::downgrade(&pane);
        pane.set_grid(cols, rows);
        for chunk in chunks {
            tx.send(chunk).unwrap();
        }
        drop(tx);
        let deadline = Instant::now() + Duration::from_secs(5);
        while pane.is_alive() {
            assert!(Instant::now() < deadline, "the stream was never parsed");
            std::thread::sleep(Duration::from_millis(5));
        }
        pane
    }

    /// Item 77 / review of #24: after the core resizes a pane it repaints
    /// only its screen, and it may have moved more (or fewer) rows into its
    /// history than the phone did. The repaint right after a RESIZE keeps the
    /// phone's old screen in history, so no row is ever lost; at worst a few
    /// show twice. Repaints without a RESIZE still copy nothing.
    #[test]
    fn a_core_resize_never_loses_a_row_whichever_side_moved_more() {
        let all: Vec<String> = (0..50).map(|n| format!("line {n}")).collect();
        let first = core_paint(&all[..40], &all[40..], 10);
        // The core moved 4 rows into its history (the phone moves 2), and the
        // core moved none on the way back (the phone pulls some back).
        for (rows, shown) in [(8u16, 6usize), (8, 8), (6, 6), (12, 10)] {
            let after = core_paint(&[], &all[all.len() - shown..], usize::from(rows));
            let pane = streamed(20, 10, vec![Chunk::Data(first.clone()), Chunk::Grid(20, rows), Chunk::Data(after)]);
            let rows_seen: Vec<String> = all_rows(&pane).into_iter().filter(|r| !r.is_empty()).collect();
            for line in &all {
                assert!(rows_seen.contains(line), "{line} lost at 10 -> {rows} rows: {rows_seen:?}");
            }
            // In order: each line first appears after the one before it.
            let firsts: Vec<usize> = all.iter().map(|l| rows_seen.iter().position(|r| r == l).unwrap()).collect();
            assert!(firsts.windows(2).all(|w| w[0] < w[1]), "out of order at 10 -> {rows}: {rows_seen:?}");
        }
        // A repaint with no resize before it copies nothing.
        let again = core_paint(&[], &all[40..], 10);
        let pane = streamed(20, 10, vec![Chunk::Data(first), Chunk::Data(again.clone()), Chunk::Data(again)]);
        let rows_seen: Vec<String> = all_rows(&pane).into_iter().filter(|r| !r.is_empty()).collect();
        assert_eq!(rows_seen, all);
    }

    /// With a core that sends its history after a RESIZE, the scrollback
    /// becomes exactly the core's, whichever side moved more rows: the old
    /// screen the phone keeps on the paint's first erase is cleared by the
    /// ESC[3J that follows. No row is lost and none shows twice.
    #[test]
    fn a_history_repaint_after_a_resize_leaves_exactly_the_cores_scrollback() {
        let all: Vec<String> = (0..50).map(|n| format!("line {n}")).collect();
        let first = core_paint(&all[..40], &all[40..], 10);
        for (rows, shown) in [(8u16, 6usize), (8, 8), (6, 6), (12, 10)] {
            // The core's history after its own resize, with a row only it still holds.
            let at = all.len() - shown;
            let mut history = vec!["only the core".to_owned()];
            history.extend_from_slice(&all[..at]);
            let repaint = core_resize_paint(&history, &all[at..], usize::from(rows));
            let pane = streamed(20, 10, vec![Chunk::Data(first.clone()), Chunk::Grid(20, rows), Chunk::Data(repaint)]);
            let rows_seen: Vec<String> = all_rows(&pane).into_iter().filter(|r| !r.is_empty()).collect();
            let expected: Vec<String> = history.iter().chain(&all[at..]).cloned().collect();
            assert_eq!(rows_seen, expected, "10 -> {rows} rows");
        }
    }

    /// The core's policy (#88): a RESIZE is followed by ESC[3J and the
    /// history only when the whole confirmed N fits; otherwise by a
    /// screen-only paint with no ESC[3J. That paint must keep every row the
    /// phone held, whichever side moved more rows, even when it arrives cut
    /// into 3-byte pieces (escape sequences split across frames).
    #[test]
    fn a_screen_only_paint_after_a_resize_keeps_the_old_history() {
        let all: Vec<String> = (0..50).map(|n| format!("line {n}")).collect();
        let pieces = |bytes: Vec<u8>| bytes.chunks(3).map(|piece| Chunk::Data(piece.to_vec())).collect::<Vec<_>>();
        for (rows, shown) in [(8u16, 5usize), (8, 8), (6, 6), (12, 10)] {
            let mut chunks = pieces(core_paint(&all[..40], &all[40..], 10));
            chunks.push(Chunk::Grid(20, rows));
            // Screen-only: no ESC[3J, no history, just the core's screen.
            let screen_only = core_paint(&[], &all[all.len() - shown..], usize::from(rows));
            assert!(!screen_only.windows(4).any(|w| w == b"\x1b[3J"));
            chunks.extend(pieces(screen_only));
            let pane = streamed(20, 10, chunks);
            let rows_seen: Vec<String> = all_rows(&pane).into_iter().filter(|r| !r.is_empty()).collect();
            for line in &all {
                assert!(rows_seen.contains(line), "{line} lost at 10 -> {rows}: {rows_seen:?}");
            }
            let firsts: Vec<usize> = all.iter().map(|l| rows_seen.iter().position(|r| r == l).unwrap()).collect();
            assert!(firsts.windows(2).all(|w| w[0] < w[1]), "out of order at 10 -> {rows}: {rows_seen:?}");
        }
    }

    /// The core's repair (#88): a history replacement that was cut (live
    /// output overflowed the backlog, or the line dropped) is sent again
    /// whole later, with no RESIZE before it. Whatever the phone held then,
    /// rows doubled by a screen-only paint or a replacement cut short, the
    /// repair leaves exactly the core's scrollback.
    #[test]
    fn a_repair_without_a_resize_leaves_exactly_the_cores_scrollback() {
        let all: Vec<String> = (0..50).map(|n| format!("line {n}")).collect();
        let first = core_paint(&all[..40], &all[40..], 10);
        let screen_only = core_paint(&[], &all[42..], 8);
        let mut cut = core_resize_paint(&all[..42], &all[42..], 8);
        cut.truncate(cut.len() / 2);
        let repair = core_resize_paint(&all[..42], &all[42..], 8);
        let seen = |chunks: Vec<Chunk>| -> Vec<String> {
            all_rows(&streamed(20, 10, chunks)).into_iter().filter(|r| !r.is_empty()).collect()
        };
        for (name, before) in [("screen-only", screen_only), ("cut", cut)] {
            let held = || vec![Chunk::Data(first.clone()), Chunk::Grid(20, 8), Chunk::Data(before.clone())];
            assert_ne!(seen(held()), all, "{name}: the phone's rows differ before the repair");
            let mut repaired = held();
            repaired.push(Chunk::Data(repair.clone()));
            assert_eq!(seen(repaired), all, "{name}");
        }
    }

    /// A saved copy read back (a switch, a close or a checkpoint wrote it).
    fn restored(session: &gpui_rio::Session, max_bytes: usize) -> Arc<gpui_rio::Session> {
        let bytes = crate::snapshot::encode(&session.grid().lock(), max_bytes);
        let (cols, rows) = session.grid_size();
        let copy = crate::snapshot::restore(crate::snapshot::Saved { cols, rows, pane: "t1".into(), bytes }).unwrap();
        let deadline = Instant::now() + Duration::from_secs(5);
        while copy.is_alive() {
            assert!(Instant::now() < deadline, "the copy was never parsed");
            std::thread::sleep(Duration::from_millis(5));
        }
        copy
    }

    #[test]
    fn a_saved_copy_scrolls_back_through_the_same_history_in_order() {
        let session = written((20, 6), (20, 4), 30);
        let copy = restored(&session, crate::snapshot::MAX_BYTES);
        assert_eq!(copy.grid().lock().history_size(), 27);
        for offset in [1, 5, 21, 27] {
            assert_eq!(rows_back(&copy, offset), expected_back(30, 4, offset as usize), "at {offset}");
        }
    }

    #[test]
    fn a_copy_cut_to_its_size_cap_keeps_the_newest_history_in_order() {
        let session = written((20, 4), (20, 4), 300);
        // Room for the screen and about 50 rows of history ("row NNN" + CRLF).
        let copy = restored(&session, 60 * 9);
        let history = copy.grid().lock().history_size();
        assert!((40..60).contains(&history), "kept {history} rows");
        for offset in [1, history / 2, history] {
            assert_eq!(rows_back(&copy, offset as i32), expected_back(300, 4, offset), "at {offset}");
        }
    }

    #[test]
    fn a_grid_wider_than_the_pane_scrolls_back_in_order_and_keeps_its_pan() {
        // The core holds a 40-column grid; the phone's pane is 20 wide (#15).
        let session = written((20, 4), (40, 4), 30);
        assert_eq!(session.grid_size(), (40, 4));
        assert_eq!(rows_back(&session, 9), expected_back(30, 4, 9));
        // Scrolled back, the drawn window starts at the top row and the panned column.
        assert_eq!(gpui_rio::view_origin((40, 4), (20, 4), (3, 0), true, Some(12)), (0, 12));
        assert_eq!(gpui_rio::view_origin((40, 4), (20, 4), (3, 0), true, None), (0, 0));
    }

    #[test]
    fn a_pane_switched_away_from_is_saved_once_from_the_kept_grid_and_the_slot_stays_bounded() {
        let _serial = crate::remote::departed_test_lock();
        let dir = std::env::temp_dir().join(format!("herdr-departed-{}", std::process::id()));
        std::fs::create_dir_all(&dir).unwrap();
        let path = dir.join("p.snap");
        let path = path.to_str().unwrap();
        let read_back = |path: &str| {
            let saved = crate::snapshot::load(std::path::Path::new(path)).unwrap();
            let restored = crate::snapshot::restore(saved).unwrap();
            let deadline = Instant::now() + Duration::from_secs(5);
            while restored.is_alive() {
                assert!(Instant::now() < deadline);
                std::thread::sleep(Duration::from_millis(5));
            }
            restored.screen_text()
        };
        // Closed first, then looked at again and switched away from: the
        // switch's grid is the newer one and wins.
        crate::remote::closed_for_test(0, grid_with("old copy"), "dep-a");
        let newer = grid_with("new copy");
        crate::remote::depart(0, newer.clone(), "dep-a".into());
        assert!(crate::remote::save_pane(0, "dep-a", path).unwrap());
        assert!(read_back(path).contains("new copy"));
        // Freed once saved, and the older closed copy went with the switch.
        assert!(!crate::remote::departed_holds(&newer));
        assert!(!crate::remote::save_pane(0, "dep-a", path).unwrap());
        // Never more than DEPARTED_MAX grids: the oldest goes first.
        let grids: Vec<_> = (0..=crate::remote::DEPARTED_MAX).map(|n| grid_with(&format!("pane {n}"))).collect();
        for (n, grid) in grids.iter().enumerate() {
            crate::remote::depart(0, grid.clone(), format!("dep-b{n}"));
        }
        assert!(!crate::remote::departed_holds(&grids[0]));
        assert!(grids[1..].iter().all(crate::remote::departed_holds));
        assert!(!crate::remote::save_pane(0, "dep-b0", path).unwrap());
        // An empty pane id keeps nothing.
        let nameless = grid_with("nameless");
        crate::remote::depart(0, nameless.clone(), String::new());
        assert!(!crate::remote::departed_holds(&nameless));
        for n in 1..=crate::remote::DEPARTED_MAX {
            assert!(crate::remote::save_pane(0, &format!("dep-b{n}"), path).unwrap());
        }
        std::fs::remove_dir_all(&dir).unwrap();
    }
    /// Review #22 finding 2: two computers both have a t1. A save queued for
    /// one computer's t1 must write that computer's grid, even after the
    /// other's t1 was switched away from (or closed) before the save ran.
    #[test]
    fn a_saved_pane_is_the_grid_its_own_session_showed_never_another_computers_same_pane_id() {
        let _serial = crate::remote::departed_test_lock();
        let dir = std::env::temp_dir().join(format!("herdr-origin-{}", std::process::id()));
        std::fs::create_dir_all(&dir).unwrap();
        let path = dir.join("a.snap");
        let path = path.to_str().unwrap();
        let text = |path: &str| {
            let copy = crate::snapshot::restore(crate::snapshot::load(std::path::Path::new(path)).unwrap()).unwrap();
            let deadline = Instant::now() + Duration::from_secs(5);
            while copy.is_alive() {
                assert!(Instant::now() < deadline);
                std::thread::sleep(Duration::from_millis(5));
            }
            copy.screen_text()
        };
        // Session 901 (computer A) leaves its t1; then session 902 (B) leaves its t1.
        crate::remote::depart(901, grid_with("host A data"), "t1".into());
        crate::remote::depart(902, grid_with("host B data"), "t1".into());
        // A's queued save runs now: A's grid, not B's.
        assert!(crate::remote::save_pane(901, "t1", path).unwrap());
        assert!(text(path).contains("host A data"));
        // B's is still kept for B, and a save for a session that kept nothing finds nothing.
        assert!(!crate::remote::save_pane(903, "t1", path).unwrap());
        assert!(crate::remote::save_pane(902, "t1", path).unwrap());
        assert!(text(path).contains("host B data"));
        // Closes are kept per session too: B's close does not drop A's switch copy, nor the reverse.
        crate::remote::depart(901, grid_with("A again"), "t1".into());
        crate::remote::closed_for_test(902, grid_with("B closed"), "t1");
        crate::remote::depart(902, grid_with("B moved"), "t2".into());
        assert!(crate::remote::save_pane(901, "t1", path).unwrap());
        assert!(text(path).contains("A again"));
        assert!(crate::remote::save_pane(902, "t1", path).unwrap());
        assert!(text(path).contains("B closed"));
        assert!(crate::remote::save_pane(902, "t2", path).unwrap());
        std::fs::remove_dir_all(&dir).unwrap();
    }

    /// Review #22 finding 4: the grid is copied under its lock and written
    /// after it is released, so a save stuck on the disk never blocks the
    /// painter (which takes the same lock).
    #[test]
    fn a_save_waiting_on_the_disk_does_not_hold_the_grid() {
        let _serial = crate::remote::departed_test_lock();
        let dir = std::env::temp_dir().join(format!("herdr-fifo-{}", std::process::id()));
        std::fs::create_dir_all(&dir).unwrap();
        let path = dir.join("p.snap");
        // The save writes p.tmp first: a FIFO there blocks it until a reader opens it.
        let fifo = dir.join("p.tmp");
        let _ = std::fs::remove_file(&fifo);
        assert!(std::process::Command::new("mkfifo").arg(&fifo).status().unwrap().success());
        let grid = grid_with("painted");
        crate::remote::depart(904, grid.clone(), "t1".into());
        let target = path.to_str().unwrap().to_owned();
        let saver = std::thread::spawn(move || crate::remote::save_pane(904, "t1", &target));
        // Wait until the save is blocked opening the FIFO, then the grid must be free.
        std::thread::sleep(Duration::from_millis(300));
        assert!(!saver.is_finished(), "the save should be waiting on the FIFO");
        assert!(grid.grid().try_lock_unfair().is_some(), "the grid is locked during disk I/O");
        // Release the writer: read the FIFO to its end.
        let mut bytes = Vec::new();
        std::fs::File::open(&fifo).unwrap().read_to_end(&mut bytes).unwrap();
        // (A FIFO cannot be synced, so this save then fails; a file would not.)
        let _ = saver.join().unwrap();
        assert!(String::from_utf8_lossy(&bytes).contains("painted"));
        std::fs::remove_dir_all(&dir).unwrap();
    }

    #[test]
    fn a_resize_inside_a_synchronized_update_flushes_it_at_the_old_grid_first() {
        // BSU, then a move to column 30 on a 20-column grid plus "A" (buffered
        // by the pending update), then RESIZE to 40, then ESU and a second move.
        let (tx, rx) = mpsc::sync_channel(16);
        let (commands, _credits) = mpsc::sync_channel(64);
        let slot = Arc::new(Mutex::new(Weak::new()));
        let output = StreamRead { rx, chunks: VecDeque::new(), commands: commands.clone(), channel: 9, session: slot.clone() };
        let input = StreamWrite { commands, channel: 9, kind: DATA };
        let session = gpui_rio::Session::start(output, input, |_, _| {}, crate::shell::theme(&crate::model::palette()), 20, 4).unwrap();
        assert!(session.set_grid(20, 4));
        *lock(&slot) = Arc::downgrade(&session);
        tx.send(Chunk::Data(b"\x1b[?2026h".to_vec())).unwrap();
        tx.send(Chunk::Data(b"\x1b[1;30HA".to_vec())).unwrap();
        tx.send(Chunk::Grid(40, 4)).unwrap();
        tx.send(Chunk::Data(b"\x1b[?2026l\x1b[2;30HB".to_vec())).unwrap();
        let cell = |row: usize, col: usize| {
            session.screen_text().lines().nth(row).and_then(|line| line.chars().nth(col)).unwrap_or(' ')
        };
        let deadline = Instant::now() + Duration::from_secs(2);
        while cell(1, 29) != 'B' {
            assert!(Instant::now() < deadline, "the content after ESU never rendered at the new grid:\n{}", session.screen_text());
            std::thread::sleep(Duration::from_millis(5));
        }
        assert_eq!(session.grid_size(), (40, 4));
        // The buffered move was parsed under the old 20-column grid: column 20.
        assert_eq!(cell(0, 19), 'A', "{}", session.screen_text());
        assert_eq!(cell(0, 29), ' ', "{}", session.screen_text());
    }

    #[test]
    fn a_resize_never_overtakes_older_data_still_queued() {
        // DATA at the old grid, RESIZE, DATA: all queued before Rio parses any.
        let (tx, rx) = mpsc::sync_channel(16);
        let (commands, _credits) = mpsc::sync_channel(64);
        let slot = Arc::new(Mutex::new(Weak::new()));
        let output = StreamRead { rx, chunks: VecDeque::new(), commands: commands.clone(), channel: 9, session: slot.clone() };
        let input = StreamWrite { commands, channel: 9, kind: DATA };
        let session = gpui_rio::Session::start(output, input, |_, _| {}, crate::shell::theme(&crate::model::palette()), 20, 4).unwrap();
        assert!(session.set_grid(20, 4));
        // As attach does: the slot is set before the stream's first item.
        *lock(&slot) = Arc::downgrade(&session);
        // One burst, as the network thread queues it, before Rio parses any:
        // old DATA (go to row 1 column 30: on a 20-column grid, column 20),
        // RESIZE to 40 columns, new DATA (the same move: column 30).
        tx.send(Chunk::Data(b"\x1b[1;30HA".to_vec())).unwrap();
        tx.send(Chunk::Grid(40, 4)).unwrap();
        tx.send(Chunk::Data(b"\x1b[2;30HB".to_vec())).unwrap();
        let cell = |row: usize, col: usize| {
            session.screen_text().lines().nth(row).and_then(|line| line.chars().nth(col)).unwrap_or(' ')
        };
        let deadline = Instant::now() + Duration::from_secs(2);
        while cell(1, 29) != 'B' {
            assert!(Instant::now() < deadline, "later DATA never met the new grid:\n{}", session.screen_text());
            std::thread::sleep(Duration::from_millis(5));
        }
        assert_eq!(session.grid_size(), (40, 4));
        // The old DATA was parsed at the old grid: A sits in column 20, not 30.
        assert_eq!(cell(0, 19), 'A', "{}", session.screen_text());
        assert_eq!(cell(0, 29), ' ', "{}", session.screen_text());
    }

    // t21s's probes from the #27 review (gpui-pr27-artifacts), kept as regression tests.
    fn reviewer_rows(chunks: Vec<Chunk>) -> Vec<String> {
        all_rows(&streamed(20, 10, chunks)).into_iter().filter(|s| !s.is_empty()).collect()
    }

    #[test]
    fn reviewer_interrupted_paint_then_two_resizes_and_repair() {
        let all: Vec<String> = (0..70).map(|n| format!("row {n:03}")).collect();
        let first = core_paint(&all[..60], &all[60..], 10);
        let paint8 = core_resize_paint(&all[..62], &all[62..], 8);
        let paint6 = core_resize_paint(&all[..64], &all[64..], 6);
        let paint12 = core_resize_paint(&all[..58], &all[58..], 12);
        // Interrupt inside CAN/reset, ED3, history, the history/screen separator,
        // and final screen, then resize again. Final repair is unfragmented in
        // stream meaning only: DATA splits every escape into three-byte pieces.
        for cut in [0, 1, 2, 5, 20, 35, 45, 80, paint8.len()/2, paint8.len()-2, paint8.len()] {
            for (final_rows, final_paint) in [(6, &paint6), (12, &paint12)] {
                let mut chunks = vec![Chunk::Data(first.clone()), Chunk::Grid(20, 8)];
                chunks.extend(paint8[..cut].chunks(3).map(|b| Chunk::Data(b.to_vec())));
                chunks.push(Chunk::Grid(20, final_rows));
                chunks.extend(final_paint.chunks(3).map(|b| Chunk::Data(b.to_vec())));
                assert_eq!(reviewer_rows(chunks), all, "cut {cut}, final rows {final_rows}");
            }
        }
    }

    #[test]
    fn reviewer_alt_resize_and_main_repair_keep_history() {
        let all: Vec<String> = (0..50).map(|n| format!("row {n:03}")).collect();
        let first = core_paint(&all[..40], &all[40..], 10);
        let enter = b"\x1b[?1049h\x1b[H\x1b[2JALT".to_vec();
        let alt = b"\x18\x1b[!p\x1b[?2026l\x1b[?1049l\x1b[?1047l\x1b[?47l\x1b[H\x1b[2J\x1b[?1049h\x1b[H\x1b[2JALT resized".to_vec();
        let repair = core_resize_paint(&all[..42], &all[42..], 8);
        let mut chunks = vec![Chunk::Data(first), Chunk::Data(enter), Chunk::Grid(20, 8)];
        chunks.extend(alt.chunks(3).map(|b| Chunk::Data(b.to_vec())));
        chunks.extend(repair.chunks(3).map(|b| Chunk::Data(b.to_vec())));
        assert_eq!(reviewer_rows(chunks), all);
    }

}

/// A scripted herdr-core on the far end of the socket: it answers every RPC
/// and records, in order, each attach and each DETACH, RESIZE and VISIBILITY
/// frame the phone sends, so the tests can say what claimed the grid when.
#[cfg(test)]
mod claim_tests {
    #![allow(clippy::unwrap_used, clippy::expect_used)]
    use super::*;
    use crate::remote::State;
    use herdr_remote_transport::CancellationToken;
    use std::sync::atomic::AtomicBool;

    #[derive(Debug, PartialEq)]
    enum Seen {
        Attach(Value),
        Frame(u32, u8, Vec<u8>),
        /// A `terminal.read` asked, and its (delayed) reply written.
        Read,
        Replied,
    }

    /// How the scripted core answers beyond the defaults: whether it echoes
    /// the `resize_history` an attach asked for (the owner's confirmation),
    /// and from which attach on (counting from 1) it refuses attaches.
    #[derive(Default)]
    struct Script {
        echo: bool,
        /// Echo `resize_history` only on attaches up to this one (counting from 1).
        echo_until: Option<usize>,
        /// The attach reply confirms RESIZE frames (and gives a 90x40 grid).
        resizes: bool,
        refuse_from: Option<usize>,
        /// How long the core takes to answer a `terminal.read` (it keeps reading meanwhile).
        read_delay: Duration,
    }

    struct Mock {
        owner: Arc<Session>,
        seen: mpsc::Receiver<Seen>,
        /// Frames for the core to write to the phone: (channel, kind, bytes).
        push: mpsc::Sender<(u32, u8, Vec<u8>)>,
        session: Option<std::thread::JoinHandle<Result<()>>>,
        peer: Option<std::thread::JoinHandle<()>>,
    }

    impl Mock {
        /// `features` is what hello advertises; `confirm` is whether the
        /// attach reply confirms VISIBILITY frames when asked for them.
        fn start(features: Value, confirm: bool, visible: bool, active: bool) -> Self {
            Self::start_with(features, confirm, visible, active, false)
        }

        /// As `start`; `used`: Wolf used the phone just before the session began.
        fn start_with(features: Value, confirm: bool, visible: bool, active: bool, used: bool) -> Self {
            Self::begin(features, confirm, visible, active, Script::default(), used)
        }

        fn scripted(features: Value, confirm: bool, visible: bool, active: bool, script: Script) -> Self {
            Self::begin(features, confirm, visible, active, script, false)
        }

        /// Each mock session reads its own record of use, so no test sees another's typing.
        fn begin(features: Value, confirm: bool, visible: bool, active: bool, script: Script, used: bool) -> Self {
            let uses: &'static crate::remote::LastUse = Box::leak(Box::new(crate::remote::LastUse::new()));
            if used {
                uses.mark();
            }
            let (phone, mut core) = UnixStream::pair().unwrap();
            core.set_read_timeout(Some(Duration::from_millis(20))).unwrap();
            let (commands, rx) = mpsc::sync_channel(256);
            let owner = Arc::new(Session {
                id: 0,
                state: Mutex::new(State::new(json!({"revision":0,"panes":[]}))),
                cancel: CancellationToken::new(),
                commands,
                focus: Mutex::new(None),
                heard: crate::remote::Heard::default(),
                visible: AtomicBool::new(visible),
                uses,
            });
            let worker = owner.clone();
            let session = std::thread::spawn(move || run(&worker, phone, 90, 40, active, rx));
            let (tx, seen) = mpsc::channel();
            let (push, pushed) = mpsc::channel::<(u32, u8, Vec<u8>)>();
            // The owner applies the focus rule (attach replies say focus_by_use).
            let rule = !features.as_array().is_some_and(|f| f.iter().any(|f| f == "no_rule"));
            let peer = std::thread::spawn(move || {
                let mut attaches = 0;
                let mut killed = false;
                let mut hello = String::new();
                read_hello(&core, &mut hello);
                let reply = json!({"jsonrpc":"2.0","id":0,"result":{"generation":1,"core":{"id":"fixture"},"framed":true,"features":features}});
                core.write_all(format!("{reply}\n").as_bytes()).unwrap();
                let mut decoder = Decoder::default();
                let mut next_channel = 2;
                let mut bytes = [0; 4096];
                let mut reading: Option<(Instant, Value)> = None;
                loop {
                    if reading.as_ref().is_some_and(|(due, _)| Instant::now() >= *due) {
                        let (_, id) = reading.take().unwrap();
                        let reply = json!({"jsonrpc":"2.0","id":id,"result":{"text":"x","truncated":false,"incarnation":"ab"}});
                        if write_frame(&mut core, 0, 0, &serde_json::to_vec(&reply).unwrap()).is_err() { return; }
                        let _ = tx.send(Seen::Replied);
                    }
                    while let Ok((channel, kind, bytes)) = pushed.try_recv() {
                        // The phone hung up (the test is closing): done, as on a read error.
                        if write_frame(&mut core, channel, kind, &bytes).is_err() { return; }
                    }
                    match core.read(&mut bytes) {
                        Ok(0) => return,
                        Ok(n) => decoder.bytes.extend_from_slice(&bytes[..n]),
                        Err(e) if matches!(e.kind(), io::ErrorKind::WouldBlock | io::ErrorKind::TimedOut) => {}
                        Err(_) => return,
                    }
                    while let Some(frame) = decoder.next().unwrap() {
                        if frame.channel != 0 {
                            if matches!(frame.kind, DATA | AUTOMATIC_INPUT | DETACH | RESIZE | VISIBILITY | WINDOW_FOCUS) {
                                let _ = tx.send(Seen::Frame(frame.channel, frame.kind, frame.bytes));
                            }
                            continue;
                        }
                        let request: Value = serde_json::from_slice(&frame.bytes).unwrap();
                        let result = match request["method"].as_str().unwrap() {
                            "events.subscribe" => json!({"channel":1,"seq":0,"terminals":[]}),
                            "terminal.kill" => { killed = true; json!({}) },
                            "terminal.list" if killed => json!({"terminals": [{"address":"t2","state":"live","labels":{},"cwd":"/tmp"}]}),
                            "terminal.list" => json!({"terminals":[
                                {"address":"t1","state":"live","labels":{},"cwd":"/tmp"},
                                {"address":"t2","state":"live","labels":{},"cwd":"/tmp"}]}),
                            "doc.get" => json!({"value":null,"version":0}),
                            "terminal.read" => {
                                let _ = tx.send(Seen::Read);
                                reading = Some((Instant::now() + script.read_delay, request["id"].clone()));
                                continue;
                            }
                            "terminal.attach" => {
                                let asked = request["params"]["visibility_updates"] == true;
                                let _ = tx.send(Seen::Attach(request["params"].clone()));
                                attaches += 1;
                                // A pane that ended under a stale pick.
                                if request["params"]["terminal"] == "gone" {
                                    let error = json!({"jsonrpc":"2.0","id":request["id"],"error":{"code":1,"message":"gone has finished","data":{"kind":"finished"}}});
                                    if write_frame(&mut core, 0, 0, &serde_json::to_vec(&error).unwrap()).is_err() { return; }
                                    continue;
                                }
                                if killed || script.refuse_from.is_some_and(|from| attaches >= from) {
                                    let error = json!({"jsonrpc":"2.0","id":request["id"],
                                        "error":{"code":1,"message":"t1 has finished","data":{"kind":"finished"}}});
                                    if write_frame(&mut core, 0, 0, &serde_json::to_vec(&error).unwrap()).is_err() { return; }
                                    continue;
                                }
                                next_channel += 1;
                                let focus = request["params"]["focus_updates"] == true;
                                let mut result = json!({"channel":next_channel - 1,"resize_notifications":script.resizes,"terminal":{"cols":90,"rows":40},
                                       "visibility_updates": asked && confirm,
                                       "focus_updates": focus && confirm});
                                if focus && confirm && rule {
                                    result["focus_by_use"] = json!(true);
                                }
                                if script.echo && script.echo_until.is_none_or(|n| attaches <= n) && request["params"].get("resize_history").is_some() {
                                    result["resize_history"] = request["params"]["resize_history"].clone();
                                }
                                result
                            }
                            other => panic!("unexpected {other}"),
                        };
                        let reply = json!({"jsonrpc":"2.0","id":request["id"],"result":result});
                        if write_frame(&mut core, 0, 0, &serde_json::to_vec(&reply).unwrap()).is_err() { return; }
                    }
                }
            });
            Self { owner, seen, push, session: Some(session), peer: Some(peer) }
        }

        fn next(&self) -> Seen {
            self.seen.recv_timeout(Duration::from_secs(5)).expect("the core saw nothing more")
        }

        /// Nothing more reaches the core within this window.
        fn quiet(&self, window: Duration) {
            if let Ok(seen) = self.seen.recv_timeout(window) {
                panic!("unexpected {seen:?}");
            }
        }

        fn set_visible(&self, visible: bool) {
            self.owner.visible.store(visible, Ordering::Relaxed);
        }

        /// The attached terminal, as the phone's view types into it.
        fn terminal(&self) -> Arc<gpui_rio::Session> {
            let deadline = Instant::now() + Duration::from_secs(5);
            loop {
                if let Some(terminal) = lock(&self.owner.state).terminal.clone() {
                    return terminal;
                }
                assert!(Instant::now() < deadline, "no terminal attached");
                std::thread::sleep(Duration::from_millis(5));
            }
        }

        /// What the phone's terminal does when its view is laid out.
        fn measured(&self, channel: u32, cols: u16, rows: u16) {
            let mut bytes = Vec::from(cols.to_le_bytes());
            bytes.extend(rows.to_le_bytes());
            self.owner.commands.try_send(Operation::Frame(channel, RESIZE, bytes)).unwrap();
        }

        /// Ends the session as the activity does; returns what it sent last.
        fn close(mut self) -> Vec<Seen> {
            self.owner.cancel.cancel();
            let _ = self.session.take().unwrap().join().unwrap();
            self.peer.take().unwrap().join().unwrap();
            self.seen.try_iter().collect()
        }
    }

    fn size(cols: u16, rows: u16) -> Vec<u8> {
        let mut bytes = Vec::from(cols.to_le_bytes());
        bytes.extend(rows.to_le_bytes());
        bytes
    }

    /// A transcript read the core is slow to answer never holds back typing:
    /// keys go out while it waits; a RESIZE queued during it, and everything
    /// queued after that, waits for the reply, in order.
    #[test]
    fn typing_goes_out_while_a_transcript_read_waits_and_order_holds() {
        let script = Script { read_delay: Duration::from_millis(750), ..Script::default() };
        let core = Mock::scripted(json!(["read-bounded", "incarnation"]), true, true, true, script);
        assert!(matches!(core.next(), Seen::Attach(_)));
        core.measured(2, 100, 30);
        assert_eq!(core.next(), Seen::Frame(2, RESIZE, size(100, 30)));

        let (tx, reply) = mpsc::sync_channel(1);
        let request = crate::remote::ReadRequest::new("t2", 2000, 65536).unwrap();
        core.owner.commands.try_send(Operation::Read(request, tx)).unwrap();
        assert_eq!(core.next(), Seen::Read);
        let typed = Instant::now();
        core.owner.commands.try_send(Operation::Frame(2, DATA, b"a".to_vec())).unwrap();
        assert_eq!(core.next(), Seen::Frame(2, DATA, b"a".to_vec()), "a key waits for no read");
        assert!(typed.elapsed() < Duration::from_millis(400), "{:?}", typed.elapsed());
        core.measured(2, 80, 24);
        core.owner.commands.try_send(Operation::Frame(2, DATA, b"b".to_vec())).unwrap();
        assert_eq!(core.next(), Seen::Replied);
        assert_eq!(core.next(), Seen::Frame(2, RESIZE, size(80, 24)));
        assert_eq!(core.next(), Seen::Frame(2, DATA, b"b".to_vec()));
        let answer: Value = serde_json::from_str(&reply.recv_timeout(Duration::from_secs(5)).unwrap().unwrap()).unwrap();
        assert_eq!(answer["text"], "x");
        core.close();
    }

    #[test]
    fn a_visibility_core_sees_hidden_attach_then_measured_resize_then_shown() {
        let core = Mock::start(json!(["viewer_visibility"]), true, true, true);
        let Seen::Attach(params) = core.next() else { panic!("attach first") };
        assert_eq!(params["terminal"], "t1");
        assert_eq!(params["visibility_updates"], true);
        assert_eq!(params["visible"], false, "the attach-time estimate never leads");
        core.measured(2, 100, 30);
        assert_eq!(core.next(), Seen::Frame(2, RESIZE, size(100, 30)));
        assert_eq!(core.next(), Seen::Frame(2, VISIBILITY, vec![1]));

        // Backgrounded: hidden, and no RESIZE while hidden.
        core.set_visible(false);
        assert_eq!(core.next(), Seen::Frame(2, VISIBILITY, vec![0]));
        core.measured(2, 60, 20);
        core.quiet(Duration::from_millis(300));

        // Foregrounded: a fresh measured RESIZE comes before showing.
        core.set_visible(true);
        assert_eq!(core.next(), Seen::Frame(2, RESIZE, size(60, 20)));
        assert_eq!(core.next(), Seen::Frame(2, VISIBILITY, vec![1]));

        assert_eq!(
            core.close(),
            vec![Seen::Frame(2, VISIBILITY, vec![0]), Seen::Frame(2, DETACH, Vec::new())]
        );
    }

    #[test]
    fn without_a_measured_resize_the_attach_size_is_offered_before_showing() {
        let core = Mock::start(json!(["viewer_visibility"]), true, true, true);
        assert!(matches!(core.next(), Seen::Attach(_)));
        assert_eq!(core.next(), Seen::Frame(2, RESIZE, size(90, 40)));
        assert_eq!(core.next(), Seen::Frame(2, VISIBILITY, vec![1]));
        core.close();
    }

    #[test]
    fn an_older_core_is_detached_while_hidden_and_reattached_with_a_fresh_resize() {
        let core = Mock::start(json!([]), true, true, true);
        let Seen::Attach(params) = core.next() else { panic!("attach first") };
        assert_eq!(params["terminal"], "t1");
        assert!(params.get("visibility_updates").is_none(), "never asked of an older core");
        core.measured(2, 100, 30);
        assert_eq!(core.next(), Seen::Frame(2, RESIZE, size(100, 30)));

        core.set_visible(false);
        assert_eq!(core.next(), Seen::Frame(2, DETACH, Vec::new()));
        core.measured(2, 60, 20);
        core.quiet(Duration::from_millis(300));

        core.set_visible(true);
        let Seen::Attach(params) = core.next() else { panic!("reattach") };
        assert_eq!(params["terminal"], "t1", "the same pane comes back");
        assert_eq!(core.next(), Seen::Frame(3, RESIZE, size(100, 30)));
        assert_eq!(core.close(), vec![Seen::Frame(3, DETACH, Vec::new())]);
    }

    /// The program asks for a cursor report (DSR 6) and device attributes (DA)
    /// and the phone types: what core sees as the terminal's answers and as typing.
    fn answers_and_typing(features: Value) -> Vec<Seen> {
        let core = Mock::start(features, true, true, true);
        assert!(matches!(core.next(), Seen::Attach(_)));
        assert_eq!(core.next(), Seen::Frame(2, RESIZE, size(90, 40)));
        assert_eq!(core.next(), Seen::Frame(2, VISIBILITY, vec![1]));
        let terminal = core.terminal();
        core.push.send((2, DATA, b"\x1b[6n".to_vec())).unwrap();
        let mut seen = vec![core.next()];
        core.push.send((2, DATA, b"\x1b[c".to_vec())).unwrap();
        seen.push(core.next());
        assert!(terminal.type_text("ls"));
        seen.push(core.next());
        // A core without the focus rule: typing is no use this session records.
        std::thread::sleep(Duration::from_millis(150));
        assert_eq!(core.owner.uses.count(), 0, "uses recorded without focus_by_use");
        core.close();
        seen
    }

    #[test]
    fn a_query_from_the_program_is_answered_as_automatic_input_not_typing() {
        let seen = answers_and_typing(json!(["viewer_visibility", "automatic_input"]));
        assert_eq!(seen[0], Seen::Frame(2, AUTOMATIC_INPUT, b"\x1b[1;1R".to_vec()));
        let Seen::Frame(2, AUTOMATIC_INPUT, ref attributes) = seen[1] else { panic!("DA: {:?}", seen[1]) };
        assert!(attributes.starts_with(b"\x1b[?"));
        assert_eq!(seen[2], Seen::Frame(2, DATA, b"ls".to_vec()), "typing stays DATA");
    }

    #[test]
    fn an_older_core_gets_the_answers_as_data_as_before() {
        let seen = answers_and_typing(json!(["viewer_visibility"]));
        assert_eq!(seen[0], Seen::Frame(2, DATA, b"\x1b[1;1R".to_vec()));
        assert!(matches!(seen[1], Seen::Frame(2, DATA, _)));
        assert_eq!(seen[2], Seen::Frame(2, DATA, b"ls".to_vec()));
    }

    const FOCUS_CORE: [&str; 3] = ["viewer_visibility", "viewer_focus", "focus_by_use"];

    #[test]
    fn a_focus_core_hears_each_use_at_most_once_a_second_and_a_zero_before_hiding() {
        // Wolf used the phone just before the attach.
        let core = Mock::start_with(json!(FOCUS_CORE), true, true, true, true);
        let Seen::Attach(params) = core.next() else { panic!("attach first") };
        assert_eq!(params["focus_updates"], true);
        assert_eq!(params["focused"], true, "Herdr in front, showing the pane");
        let idle = params["idle_ms"].as_u64().unwrap();
        assert!(idle < 5_000, "Wolf used the phone just now: {idle} ms");
        assert_eq!(core.next(), Seen::Frame(2, RESIZE, size(90, 40)));
        assert_eq!(core.next(), Seen::Frame(2, VISIBILITY, vec![1]));
        // The attach said it all: no WINDOW_FOCUS until Wolf does something.
        core.quiet(Duration::from_millis(300));

        core.owner.uses.mark();
        assert_eq!(core.next(), Seen::Frame(2, WINDOW_FOCUS, vec![1]));
        // Leading edge: more uses within the second add nothing.
        core.owner.uses.mark();
        core.owner.uses.mark();
        core.quiet(Duration::from_millis(400));
        std::thread::sleep(FOCUS_EVERY);
        core.owner.uses.mark();
        assert_eq!(core.next(), Seen::Frame(2, WINDOW_FOCUS, vec![1]));

        // Hidden: 0 first, then VISIBILITY 0; uses while hidden say nothing.
        core.set_visible(false);
        assert_eq!(core.next(), Seen::Frame(2, WINDOW_FOCUS, vec![0]));
        assert_eq!(core.next(), Seen::Frame(2, VISIBILITY, vec![0]));
        core.owner.uses.mark();
        core.quiet(Duration::from_millis(300));

        // Shown again (a resume): sized, visible, then focused.
        core.set_visible(true);
        assert_eq!(core.next(), Seen::Frame(2, RESIZE, size(90, 40)));
        assert_eq!(core.next(), Seen::Frame(2, VISIBILITY, vec![1]));
        assert_eq!(core.next(), Seen::Frame(2, WINDOW_FOCUS, vec![1]));

        assert_eq!(
            core.close(),
            vec![
                Seen::Frame(2, WINDOW_FOCUS, vec![0]),
                Seen::Frame(2, VISIBILITY, vec![0]),
                Seen::Frame(2, DETACH, Vec::new())
            ]
        );
    }

    /// #27 attaches a pane again by itself when the core ends its stream.
    /// That stream is gone: no focus frame goes on it, and the new attach
    /// carries the focus (Herdr still shows the pane) and the true idle time.
    #[test]
    fn a_stream_the_core_ended_gets_no_focus_frames_and_its_reattach_is_focused() {
        let core = Mock::start_with(json!(FOCUS_CORE), true, true, true, true);
        let Seen::Attach(_) = core.next() else { panic!("attach first") };
        assert_eq!(core.next(), Seen::Frame(2, RESIZE, size(90, 40)));
        assert_eq!(core.next(), Seen::Frame(2, VISIBILITY, vec![1]));
        core.owner.uses.mark();
        assert_eq!(core.next(), Seen::Frame(2, WINDOW_FOCUS, vec![1]));
        // The core ends the stream, and Wolf keeps using the phone.
        core.push.send((2, DETACH, Vec::new())).unwrap();
        std::thread::sleep(FOCUS_EVERY);
        core.owner.uses.mark();
        let mut before = Vec::new();
        let again = loop {
            match core.next() {
                Seen::Attach(params) => break params,
                seen => before.push(seen),
            }
        };
        assert!(
            !before.iter().any(|seen| matches!(seen, Seen::Frame(2, WINDOW_FOCUS, _))),
            "a focus frame on the ended stream: {before:?}"
        );
        assert_eq!(again["terminal"], "t1");
        assert_eq!(again["focus_updates"], true);
        assert_eq!(again["focused"], true, "Herdr still shows the pane");
        assert!(again["idle_ms"].as_u64().unwrap() < 5_000);
        assert_eq!(core.next(), Seen::Frame(3, RESIZE, size(90, 40)));
        assert_eq!(core.next(), Seen::Frame(3, VISIBILITY, vec![1]));
        core.close();
    }

    #[test]
    fn an_unfocused_attach_still_says_how_long_ago_the_phone_was_used() {
        // t1x1 (core #94): idle_ms dates an unfocused attach's history too.
        let core = Mock::start_with(json!(FOCUS_CORE), true, false, true, true);
        let Seen::Attach(params) = core.next() else { panic!("attach first") };
        assert_eq!(params["focus_updates"], true);
        assert_eq!(params["focused"], false, "Herdr is not showing it");
        assert!(params["idle_ms"].as_u64().unwrap() < 5_000);
        core.owner.uses.mark();
        core.quiet(Duration::from_millis(300));
        core.close();
    }

    #[test]
    fn typing_on_a_focus_core_is_a_use_and_says_so() {
        let core = Mock::start(json!(FOCUS_CORE), true, true, true);
        assert!(matches!(core.next(), Seen::Attach(_)));
        assert_eq!(core.next(), Seen::Frame(2, RESIZE, size(90, 40)));
        assert_eq!(core.next(), Seen::Frame(2, VISIBILITY, vec![1]));
        assert!(core.terminal().type_text("ls"));
        let mut seen = vec![core.next(), core.next()];
        seen.sort_by_key(|seen| matches!(seen, Seen::Frame(_, WINDOW_FOCUS, _)));
        assert_eq!(
            seen,
            vec![Seen::Frame(2, DATA, b"ls".to_vec()), Seen::Frame(2, WINDOW_FOCUS, vec![1])]
        );
        assert_eq!(core.owner.uses.count(), 1);
        core.close();
    }

    #[test]
    fn leaving_a_pane_for_another_unfocuses_it_first() {
        let _departed = crate::remote::departed_test_lock();
        let core = Mock::start(json!(FOCUS_CORE), true, true, true);
        assert!(matches!(core.next(), Seen::Attach(_)));
        assert_eq!(core.next(), Seen::Frame(2, RESIZE, size(90, 40)));
        assert_eq!(core.next(), Seen::Frame(2, VISIBILITY, vec![1]));
        *lock(&core.owner.focus) = Some(("t2".into(), Instant::now()));
        assert_eq!(core.next(), Seen::Frame(2, WINDOW_FOCUS, vec![0]));
        assert_eq!(core.next(), Seen::Frame(2, DETACH, Vec::new()));
        let Seen::Attach(params) = core.next() else { panic!("attach the pick") };
        assert_eq!(params["terminal"], "t2");
        assert_eq!(params["focused"], true);
        core.close();
    }

    #[test]
    fn a_core_without_focus_by_use_is_never_asked_or_told() {
        for features in [json!(["viewer_visibility", "viewer_focus"]), json!(["viewer_visibility"])] {
            let core = Mock::start(features, true, true, true);
            let Seen::Attach(params) = core.next() else { panic!("attach first") };
            for key in ["focus_updates", "focused", "idle_ms"] {
                assert!(params.get(key).is_none(), "{key} asked of an older core");
            }
            assert_eq!(core.next(), Seen::Frame(2, RESIZE, size(90, 40)));
            assert_eq!(core.next(), Seen::Frame(2, VISIBILITY, vec![1]));
            core.owner.uses.mark();
            core.quiet(Duration::from_millis(300));
            assert_eq!(
                core.close(),
                vec![Seen::Frame(2, VISIBILITY, vec![0]), Seen::Frame(2, DETACH, Vec::new())]
            );
        }
    }

    #[test]
    fn a_path_that_does_not_confirm_focus_gets_no_focus_frames() {
        // Asked, not confirmed (an older relay on the way): nothing is sent.
        let core = Mock::start(json!(FOCUS_CORE), false, true, true);
        let Seen::Attach(params) = core.next() else { panic!("attach first") };
        assert_eq!(params["focus_updates"], true);
        core.owner.uses.mark();
        core.quiet(Duration::from_millis(1300));
        assert!(core.close().iter().all(|seen| !matches!(seen, Seen::Frame(_, WINDOW_FOCUS, _))));
    }

    #[test]
    fn an_owner_that_does_not_apply_the_rule_gets_no_focus_frames() {
        // focus_updates confirmed, focus_by_use absent from the reply (an owner
        // or hop without the rule; "no_rule" makes the mock leave it out).
        let core = Mock::start(json!(["viewer_visibility", "viewer_focus", "focus_by_use", "no_rule"]), true, true, true);
        let Seen::Attach(params) = core.next() else { panic!("attach first") };
        assert_eq!(params["focus_updates"], true);
        assert_eq!(core.next(), Seen::Frame(2, RESIZE, size(90, 40)));
        assert_eq!(core.next(), Seen::Frame(2, VISIBILITY, vec![1]));
        core.owner.uses.mark();
        core.quiet(Duration::from_millis(300));
        assert_eq!(
            core.close(),
            vec![Seen::Frame(2, VISIBILITY, vec![0]), Seen::Frame(2, DETACH, Vec::new())]
        );
    }

    #[test]
    fn idle_is_the_time_since_the_phones_last_use() {
        let uses = crate::remote::LastUse::new();
        assert_eq!(uses.idle_ms(), crate::remote::NEVER_USED_MS, "never used");
        assert_eq!(uses.count(), 0);
        uses.mark();
        assert!(uses.idle_ms() < 1_000);
        assert_eq!(uses.count(), 1);
        std::thread::sleep(Duration::from_millis(30));
        assert!(uses.idle_ms() >= 30);
    }

    #[test]
    fn a_relay_that_drops_visibility_falls_back_to_detaching() {
        let core = Mock::start(json!(["viewer_visibility"]), false, true, true);
        let Seen::Attach(params) = core.next() else { panic!("attach first") };
        assert_eq!(params["visibility_updates"], true);
        core.quiet(Duration::from_millis(1300));
        core.set_visible(false);
        assert_eq!(core.next(), Seen::Frame(2, DETACH, Vec::new()));
        core.quiet(Duration::from_millis(300));
        assert!(core.close().is_empty(), "nothing left to release");
    }

    #[test]
    fn a_session_hidden_from_the_start_claims_no_grid_on_an_older_core() {
        let core = Mock::start(json!([]), true, false, true);
        core.quiet(Duration::from_millis(500));
        core.set_visible(true);
        let Seen::Attach(params) = core.next() else { panic!("attach when shown") };
        assert_eq!(params["terminal"], "t1");
        assert_eq!(core.next(), Seen::Frame(2, RESIZE, size(90, 40)));
        core.close();
    }

    /// Item 77 keeps the outgoing pane's grid on a switch; what the core sees
    /// (attach parameters, DETACH, RESIZE and VISIBILITY frames, in order, byte
    /// for byte) is the same as before that change: this test passes unchanged
    /// on phone-feedback 0ba5116.
    #[test]
    fn a_switch_sends_the_core_exactly_what_it_did_and_keeps_the_outgoing_grid() {
        let _serial = crate::remote::departed_test_lock();
        let core = Mock::start(json!(["viewer_visibility"]), true, true, true);
        let Seen::Attach(first) = core.next() else { panic!("attach first") };
        assert_eq!(first, attach_params("t1", 90, 40, true, None));
        core.measured(2, 100, 30);
        assert_eq!(core.next(), Seen::Frame(2, RESIZE, size(100, 30)));
        assert_eq!(core.next(), Seen::Frame(2, VISIBILITY, vec![1]));
        let outgoing = lock(&core.owner.state).terminal.clone().unwrap();

        *lock(&core.owner.focus) = Some(("t2".into(), Instant::now()));
        assert_eq!(core.next(), Seen::Frame(2, DETACH, Vec::new()));
        let Seen::Attach(second) = core.next() else { panic!("attach the pick") };
        assert_eq!(second, attach_params("t2", 100, 30, true, None), "the attach starts at the measured size");
        core.measured(3, 100, 30);
        assert_eq!(core.next(), Seen::Frame(3, RESIZE, size(100, 30)));
        assert_eq!(core.next(), Seen::Frame(3, VISIBILITY, vec![1]));
        core.quiet(Duration::from_millis(300));
        assert!(crate::remote::departed_holds(&outgoing), "t1's grid is kept for the phone to save");
        assert_eq!(
            core.close(),
            vec![Seen::Frame(3, VISIBILITY, vec![0]), Seen::Frame(3, DETACH, Vec::new())]
        );
    }

    /// The history repaint is asked for only from a core whose hello offers
    /// it, and asked again only while the owner echoes it: no echo means the
    /// path cannot carry it. An older core gets exactly the attach it always got.
    #[test]
    fn resize_history_is_asked_for_only_while_offered_and_echoed() {
        // Its switches put mock session 0's t1 in the shared departed slot.
        let _serial = crate::remote::departed_test_lock();
        let echoing = Mock::scripted(json!(["viewer_visibility", "resize_history"]), true, true, true,
            Script { echo: true, ..Script::default() });
        let Seen::Attach(params) = echoing.next() else { panic!("attach first") };
        assert_eq!(params["resize_history"], 1000);
        let mut without = params.clone();
        without.as_object_mut().unwrap().remove("resize_history");
        assert_eq!(without, attach_params("t1", 90, 40, true, None), "nothing else changes");
        *lock(&echoing.owner.focus) = Some(("t2".into(), Instant::now()));
        let second = loop {
            if let Seen::Attach(params) = echoing.next() { break params }
        };
        assert_eq!(second["resize_history"], 1000, "echoed: asked again");
        echoing.close();

        // Offered but not echoed (a relay whose owner lacks it): asked once, then not.
        let silent = Mock::scripted(json!(["viewer_visibility", "resize_history"]), true, true, true, Script::default());
        let Seen::Attach(params) = silent.next() else { panic!("attach first") };
        assert_eq!(params["resize_history"], 1000);
        *lock(&silent.owner.focus) = Some(("t2".into(), Instant::now()));
        let second = loop {
            if let Seen::Attach(params) = silent.next() { break params }
        };
        assert!(second.get("resize_history").is_none(), "not echoed: not asked again");
        silent.close();

        let older = Mock::start(json!(["viewer_visibility"]), true, true, true);
        let Seen::Attach(params) = older.next() else { panic!("attach first") };
        assert!(params.get("resize_history").is_none());
        assert_eq!(params, attach_params("t1", 90, 40, true, None));
        older.close();
    }

    /// A stream the core closes while the pane is still there (a relay does
    /// that when the path loses a confirmed `resize_history`) is attached
    /// again; a pane that refuses (its program ended) stays ended.
    #[test]
    fn a_stream_closed_again_soon_is_attached_again_when_the_wait_ends() {
        let core = Mock::scripted(json!(["viewer_visibility", "resize_history"]), true, true, true,
            Script { echo: true, refuse_from: Some(3), ..Script::default() });
        let Seen::Attach(first) = core.next() else { panic!("attach first") };
        assert_eq!(first["terminal"], "t1");
        // The core ends the stream: the phone attaches t1 again by itself.
        core.push.send((2, 2, Vec::new())).unwrap();
        let again = loop {
            if let Seen::Attach(params) = core.next() { break params }
        };
        assert_eq!(again["terminal"], "t1");
        let reattached = Instant::now();
        // Ended again 1 s later: no loop, but not dropped either. It is
        // attached again once REATTACH_EVERY has passed since the last one.
        std::thread::sleep(Duration::from_secs(1));
        core.push.send((3, 2, Vec::new())).unwrap();
        std::thread::sleep(Duration::from_millis(500));
        assert_eq!(lock(&core.owner.state).view["state"], "connecting");
        let third = loop {
            if let Seen::Attach(params) = core.next() { break params }
        };
        // Not before the wait was over, and soon after it.
        let after = reattached.elapsed();
        assert_eq!(third["terminal"], "t1");
        assert!(after >= REATTACH_EVERY && after < REATTACH_EVERY + Duration::from_millis(1500), "{after:?}");
        // That one is refused (the program ended): the pane stays ended, and
        // nothing is tried again.
        let deadline = Instant::now() + Duration::from_secs(5);
        while lock(&core.owner.state).view["status"] != "Terminal stream ended; select a terminal to attach again" {
            assert!(Instant::now() < deadline);
            std::thread::sleep(Duration::from_millis(10));
        }
        let deadline = Instant::now() + REATTACH_EVERY + Duration::from_millis(500);
        while let Some(left) = deadline.checked_duration_since(Instant::now()) {
            if let Ok(seen) = core.seen.recv_timeout(left) {
                assert!(!matches!(seen, Seen::Attach(_)), "tried again after a refusal");
            }
        }
        core.close();
    }

    /// A reattach put off past the moment the phone hid the session, on a
    /// core that cannot be told it is hidden, is parked like any hidden pane:
    /// nothing is attached while hidden, and the pane comes back when shown.
    #[test]
    fn a_reattach_put_off_while_hidden_waits_to_be_shown() {
        let core = Mock::start(json!([]), true, true, true);
        assert!(matches!(core.next(), Seen::Attach(_)));
        core.push.send((2, 2, Vec::new())).unwrap();
        let again = loop {
            if let Seen::Attach(params) = core.next() { break params }
        };
        assert_eq!(again["terminal"], "t1");
        core.push.send((3, 2, Vec::new())).unwrap();
        std::thread::sleep(Duration::from_millis(200));
        core.set_visible(false);
        let deadline = Instant::now() + REATTACH_EVERY + Duration::from_millis(1000);
        while let Some(left) = deadline.checked_duration_since(Instant::now()) {
            if let Ok(seen) = core.seen.recv_timeout(left) {
                assert!(!matches!(seen, Seen::Attach(_)), "attached while hidden");
            }
        }
        core.set_visible(true);
        let shown = loop {
            if let Seen::Attach(params) = core.next() { break params }
        };
        assert_eq!(shown["terminal"], "t1", "the same pane comes back");
        core.close();
    }

    /// When the reattach is refused, the pane says it ended.
    #[test]
    fn a_refused_reattach_leaves_the_pane_ended() {
        let core = Mock::scripted(json!(["viewer_visibility"]), true, true, true,
            Script { refuse_from: Some(2), ..Script::default() });
        assert!(matches!(core.next(), Seen::Attach(_)));
        core.push.send((2, 2, Vec::new())).unwrap();
        let again = loop {
            if let Seen::Attach(params) = core.next() { break params }
        };
        assert_eq!(again["terminal"], "t1", "tried once");
        let deadline = Instant::now() + Duration::from_secs(5);
        while lock(&core.owner.state).view["status"] != "Terminal stream ended; select a terminal to attach again" {
            assert!(Instant::now() < deadline);
            std::thread::sleep(Duration::from_millis(10));
        }
        core.close();
    }

    #[test]
    fn an_inactive_session_attaches_nothing_until_a_pane_is_picked() {
        let core = Mock::start(json!(["viewer_visibility"]), true, true, false);
        core.quiet(Duration::from_millis(500));
        *lock(&core.owner.focus) = Some(("t2".into(), Instant::now()));
        let Seen::Attach(params) = core.next() else { panic!("attach the pick") };
        assert_eq!(params["terminal"], "t2");
        // Closed only once the attach was answered: closing while it waits
        // for its reply ends the session before there is a channel to detach.
        let deadline = Instant::now() + Duration::from_secs(5);
        while lock(&core.owner.state).view["pane_id"] != "t2" {
            assert!(Instant::now() < deadline, "the attach was never answered");
            std::thread::sleep(Duration::from_millis(5));
        }
        assert!(core.close().iter().any(|seen| *seen == Seen::Frame(2, DETACH, Vec::new())));
    }

    // t21s's probes from the #27 review (gpui-pr27-artifacts), kept as regression tests.
    fn reviewer_wait(mut done: impl FnMut() -> bool) {
        let until = Instant::now() + Duration::from_secs(5);
        while !done() {
            assert!(Instant::now() < until, "fixture condition timed out");
            std::thread::sleep(Duration::from_millis(5));
        }
    }

    fn reviewer_attach(core: &Mock) -> Value {
        loop {
            if let Seen::Attach(params) = core.next() { return params; }
        }
    }

    #[test]
    fn reviewer_downgrade_echo_is_sticky_across_close_and_switch() {
        let _serial = crate::remote::departed_test_lock();
        let core = Mock::scripted(json!(["viewer_visibility", "resize_history"]), true, true, true,
            Script { echo: true, echo_until: Some(1), ..Script::default() });
        assert_eq!(reviewer_attach(&core)["resize_history"], 1000);
        reviewer_wait(|| lock(&core.owner.state).terminal.is_some());
        core.push.send((2, 2, br#"{"reason":"relay_overflow"}"#.to_vec())).unwrap();
        assert_eq!(reviewer_attach(&core)["resize_history"], 1000);
        reviewer_wait(|| lock(&core.owner.state).terminal.is_some());
        *lock(&core.owner.focus) = Some(("t2".into(), Instant::now()));
        let third = reviewer_attach(&core);
        assert_eq!(third["terminal"], "t2");
        assert!(third.get("resize_history").is_none(), "downgrade must disable later requests");
        reviewer_wait(|| lock(&core.owner.state).view["pane_id"] == "t2");
        core.close();
    }

    #[test]
    fn reviewer_a_user_switch_cancels_the_owed_reattach() {
        let _serial = crate::remote::departed_test_lock();
        let core = Mock::start(json!(["viewer_visibility"]), true, true, true);
        reviewer_attach(&core);
        core.push.send((2, 2, Vec::new())).unwrap();
        reviewer_attach(&core);
        reviewer_wait(|| lock(&core.owner.state).terminal.is_some());
        core.push.send((3, 2, Vec::new())).unwrap();
        reviewer_wait(|| lock(&core.owner.state).terminal.is_none());
        *lock(&core.owner.focus) = Some(("t2".into(), Instant::now()));
        assert_eq!(reviewer_attach(&core)["terminal"], "t2");
        reviewer_wait(|| lock(&core.owner.state).view["pane_id"] == "t2");
        let until = Instant::now() + REATTACH_EVERY + Duration::from_millis(500);
        while let Some(left) = until.checked_duration_since(Instant::now()) {
            if let Ok(seen) = core.seen.recv_timeout(left) {
                assert!(!matches!(seen, Seen::Attach(_)), "unexpected old-pane reattach: {seen:?}");
            }
        }
        core.close();
    }

    #[test]
    fn reviewer_hidden_user_pick_wins_over_the_owed_reattach() {
        let _serial = crate::remote::departed_test_lock();
        let core = Mock::start(json!([]), false, true, true);
        reviewer_attach(&core);
        core.push.send((2, 2, Vec::new())).unwrap();
        reviewer_attach(&core);
        reviewer_wait(|| lock(&core.owner.state).terminal.is_some());
        core.push.send((3, 2, Vec::new())).unwrap();
        reviewer_wait(|| lock(&core.owner.state).terminal.is_none());
        core.set_visible(false);
        loop {
            if core.next() == Seen::Frame(3, DETACH, Vec::new()) { break; }
        }
        *lock(&core.owner.focus) = Some(("t2".into(), Instant::now()));
        reviewer_wait(|| lock(&core.owner.focus).is_none());
        let until = Instant::now() + REATTACH_EVERY + Duration::from_millis(500);
        while let Some(left) = until.checked_duration_since(Instant::now()) {
            if let Ok(seen) = core.seen.recv_timeout(left) {
                assert!(!matches!(seen, Seen::Attach(_)), "attached while hidden");
            }
        }
        core.set_visible(true);
        let shown = reviewer_attach(&core);
        core.close();
        assert_eq!(shown["terminal"], "t2", "the user's newer parked pick must win");
    }

    #[test]
    fn reviewer_close_on_alt_screen_retains_main_history() {
        let core = Mock::scripted(json!(["viewer_visibility", "resize_history"]), true, true, true,
            Script { echo: true, resizes: true, ..Script::default() });
        reviewer_attach(&core);
        reviewer_wait(|| lock(&core.owner.state).terminal.is_some());
        let first = lock(&core.owner.state).terminal.as_ref().unwrap().clone();
        let all: Vec<String> = (0..50).map(|n| format!("row {n:03}")).collect();
        let paint = connection_tests::core_paint(&all[..40], &all[40..], 40);
        core.push.send((2, 0, paint)).unwrap();
        reviewer_wait(|| first.screen_text().contains("row 049"));
        let rows = |p: &gpui_rio::Session| connection_tests::all_rows(p)
            .into_iter().filter(|s| !s.is_empty()).collect::<Vec<_>>();
        assert_eq!(rows(&first), all, "fixture starts with all main rows");
        core.push.send((2, 0, b"\x1b[?1049h\x1b[H\x1b[2JALT before close".to_vec())).unwrap();
        reviewer_wait(|| first.screen_text().contains("ALT before close"));
        core.push.send((2, 2, br#"{"reason":"relay_overflow"}"#.to_vec())).unwrap();
        let reattach = reviewer_attach(&core);
        eprintln!("automatic reattach parameters: {reattach}");
        reviewer_wait(|| lock(&core.owner.state).terminal.as_ref().is_some_and(|p| !Arc::ptr_eq(p, &first)));
        let second = lock(&core.owner.state).terminal.as_ref().unwrap().clone();
        // A fresh attach on the alternate screen has no history in its
        // first paint. No `resumed` loss is recorded by the owner, so exit
        // from alt has only the program's screen, not a history repair.
        let mut alt = connection_tests::core_paint(&[], &[], 40);
        alt.extend_from_slice(b"\x1b[?1049h\x1b[H\x1b[2JALT after reattach");
        core.push.send((3, 0, alt)).unwrap();
        reviewer_wait(|| second.screen_text().contains("ALT after reattach"));
        // The owner contract (#88): this resumed, opted-in stream owes a
        // whole history replacement on its return to the main screen.
        let main = owner_main_paint(&reattach, &all[..40], &all[40..], 40);
        core.push.send((3, 0, main)).unwrap();
        reviewer_wait(|| second.screen_text().contains("row 049"));
        let got = rows(&second);
        core.close();
        assert_eq!(got, all, "main history held before a stream CLOSE must survive reattach on alt");
    }

    #[test]
    fn reviewer_user_closed_pane_is_never_revived_or_started() {
        let core = Mock::start(json!(["viewer_visibility"]), true, true, true);
        reviewer_attach(&core);
        core.push.send((2, 2, Vec::new())).unwrap();
        reviewer_attach(&core);
        reviewer_wait(|| lock(&core.owner.state).terminal.is_some());
        core.push.send((3, 2, Vec::new())).unwrap();
        reviewer_wait(|| lock(&core.owner.state).terminal.is_none());
        let (tx, rx) = mpsc::sync_channel(1);
        core.owner.commands.send(Operation::Action("pane.close".into(), tx, None)).unwrap();
        assert!(rx.recv_timeout(Duration::from_secs(5)).unwrap().is_ok());
        // Current code still attempts the pending attach, but the owner's
        // finished/missing rejection must leave the pane ended. No run/start
        // RPC is supported by this mock: either would panic the test.
        assert_eq!(reviewer_attach(&core)["terminal"], "t1");
        reviewer_wait(|| lock(&core.owner.state).view["status"] == "Terminal stream ended; select a terminal to attach again");
        assert!(lock(&core.owner.state).terminal.is_none());
        let until = Instant::now() + REATTACH_EVERY + Duration::from_millis(500);
        while let Some(left) = until.checked_duration_since(Instant::now()) {
            if let Ok(seen) = core.seen.recv_timeout(left) {
                assert!(!matches!(seen, Seen::Attach(_)), "reattempt after closed-pane refusal");
            }
        }
        core.close();
    }

    /// What a #88 owner sends a stream when its program is (back) on the main
    /// screen: to a `resumed` attach that asked `resize_history`, the one
    /// whole history replacement it owes (CAN, reset, ESC[3J, history,
    /// screen); otherwise the screen only.
    fn owner_main_paint(request: &Value, history: &[String], screen: &[String], rows: usize) -> Vec<u8> {
        if request["resumed"] == true && request["resize_history"].as_u64().unwrap_or(0) > 0 {
            connection_tests::core_resize_paint(history, screen, rows)
        } else {
            connection_tests::core_paint(&[], screen, rows)
        }
    }

    /// #27 (t21s's reviews, t1jf's decision): the reattach of a pane whose
    /// stream the core ended is `resumed` with `resize_history`, also when it
    /// was put off while hidden on a path without visibility frames (parked)
    /// and made when shown; the owner's replacement on the return to the main
    /// screen then gives back every row.
    #[test]
    fn a_parked_reattach_shown_later_is_resumed_and_gets_the_history_back() {
        let core = Mock::scripted(json!(["resize_history"]), false, true, true,
            Script { echo: true, resizes: true, ..Script::default() });
        reviewer_attach(&core);
        reviewer_wait(|| lock(&core.owner.state).terminal.is_some());
        let first = lock(&core.owner.state).terminal.clone().unwrap();
        let all: Vec<String> = (0..50).map(|n| format!("row {n:03}")).collect();
        core.push.send((2, 0, connection_tests::core_paint(&all[..40], &all[40..], 40))).unwrap();
        reviewer_wait(|| first.screen_text().contains("row 049"));
        core.push.send((2, 0, b"\x1b[?1049h\x1b[H\x1b[2JALT".to_vec())).unwrap();
        reviewer_wait(|| first.screen_text().contains("ALT"));
        // Ended, reattached at once, then ended again within 5 s.
        core.push.send((2, 2, Vec::new())).unwrap();
        let again = reviewer_attach(&core);
        assert_eq!((again["resumed"].clone(), again["resize_history"].clone()), (json!(true), json!(1000)));
        reviewer_wait(|| lock(&core.owner.state).terminal.as_ref().is_some_and(|p| !Arc::ptr_eq(p, &first)));
        core.push.send((3, 2, Vec::new())).unwrap();
        reviewer_wait(|| lock(&core.owner.state).terminal.is_none());
        core.set_visible(false);
        let until = Instant::now() + REATTACH_EVERY + Duration::from_millis(500);
        while let Some(left) = until.checked_duration_since(Instant::now()) {
            if let Ok(seen) = core.seen.recv_timeout(left) {
                assert!(!matches!(seen, Seen::Attach(_)), "attached while hidden");
            }
        }
        core.set_visible(true);
        let shown = reviewer_attach(&core);
        assert_eq!(shown["terminal"], "t1");
        assert_eq!(shown["resumed"], true, "still the reattach after park and show");
        reviewer_wait(|| lock(&core.owner.state).terminal.is_some());
        let third = lock(&core.owner.state).terminal.clone().unwrap();
        let mut alt = connection_tests::core_paint(&[], &[], 40);
        alt.extend_from_slice(b"\x1b[?1049h\x1b[H\x1b[2JALT again");
        core.push.send((4, 0, alt)).unwrap();
        reviewer_wait(|| third.screen_text().contains("ALT again"));
        core.push.send((4, 0, owner_main_paint(&shown, &all[..40], &all[40..], 40))).unwrap();
        reviewer_wait(|| third.screen_text().contains("row 049"));
        let rows: Vec<String> = connection_tests::all_rows(&third).into_iter().filter(|s| !s.is_empty()).collect();
        core.close();
        assert_eq!(rows, all);
    }

    /// (B), documented: where the path does not confirm `resize_history` (an
    /// old owner or relay, a downgrade), the reattach is an ordinary attach
    /// asking the attach's history. On the main screen that history comes
    /// back (some rows maybe twice, none lost). On the alternate screen an
    /// old owner sends none, so on the return to main the old main history is
    /// gone: the same as before #27 on such paths.
    #[test]
    fn without_resize_history_the_reattach_is_ordinary() {
        let all: Vec<String> = (0..50).map(|n| format!("row {n:03}")).collect();
        for on_alt in [false, true] {
            let core = Mock::scripted(json!(["viewer_visibility"]), true, true, true,
                Script { resizes: true, ..Script::default() });
            reviewer_attach(&core);
            reviewer_wait(|| lock(&core.owner.state).terminal.is_some());
            let first = lock(&core.owner.state).terminal.clone().unwrap();
            core.push.send((2, 0, connection_tests::core_paint(&all[..40], &all[40..], 40))).unwrap();
            reviewer_wait(|| first.screen_text().contains("row 049"));
            if on_alt {
                core.push.send((2, 0, b"\x1b[?1049h\x1b[H\x1b[2JALT".to_vec())).unwrap();
                reviewer_wait(|| first.screen_text().contains("ALT"));
            }
            core.push.send((2, 2, Vec::new())).unwrap();
            let again = reviewer_attach(&core);
            assert!(again.get("resumed").is_none() && again.get("resize_history").is_none());
            assert_eq!(again["history"], 1000, "never history 0: the core sends what it has");
            reviewer_wait(|| lock(&core.owner.state).terminal.as_ref().is_some_and(|p| !Arc::ptr_eq(p, &first)));
            let second = lock(&core.owner.state).terminal.clone().unwrap();
            if on_alt {
                // The old owner's attach paint on the alternate screen: no history.
                let mut alt = connection_tests::core_paint(&[], &[], 40);
                alt.extend_from_slice(b"\x1b[?1049h\x1b[H\x1b[2JALT again");
                core.push.send((3, 0, alt)).unwrap();
                reviewer_wait(|| second.screen_text().contains("ALT again"));
                core.push.send((3, 0, owner_main_paint(&again, &all[..40], &all[40..], 40))).unwrap();
            } else {
                core.push.send((3, 0, connection_tests::core_paint(&all[..40], &all[40..], 40))).unwrap();
            }
            reviewer_wait(|| second.screen_text().contains("row 049"));
            let rows: Vec<String> = connection_tests::all_rows(&second).into_iter().filter(|s| !s.is_empty()).collect();
            core.close();
            if on_alt {
                assert_eq!(rows, all[40..].to_vec(), "documented: the old main history is not brought back");
            } else {
                assert_eq!(rows, all, "the attach's own history brings every row back");
            }
        }
    }

    /// t21s, #27 at ea56f49: a pick the core refuses must not use up the
    /// previous pane's owed replacement; the fallback to it is `resumed`.
    #[test]
    fn reviewer_refused_pick_keeps_the_previous_panes_resume_debt() {
        let _serial = crate::remote::departed_test_lock();
        let core = Mock::scripted(json!(["viewer_visibility", "resize_history"]), true, true, true,
            Script { echo: true, resizes: true, ..Script::default() });
        reviewer_attach(&core);
        reviewer_wait(|| lock(&core.owner.state).terminal.is_some());
        // First CLOSE establishes the cooldown for the next CLOSE.
        core.push.send((2, 2, Vec::new())).unwrap();
        let again = reviewer_attach(&core);
        assert_eq!(again["resumed"], true);
        reviewer_wait(|| lock(&core.owner.state).terminal.is_some());
        let previous = lock(&core.owner.state).terminal.as_ref().unwrap().clone();
        let all: Vec<String> = (0..50).map(|n| format!("row {n:03}")).collect();
        core.push.send((3, 0, owner_main_paint(&again, &all[..40], &all[40..], 40))).unwrap();
        reviewer_wait(|| previous.screen_text().contains("row 049"));
        core.push.send((3, 0, b"\x1b[?1049h\x1b[H\x1b[2JALT".to_vec())).unwrap();
        reviewer_wait(|| previous.screen_text().contains("ALT"));
        core.push.send((3, 2, Vec::new())).unwrap();
        reviewer_wait(|| lock(&core.owner.state).terminal.is_none());
        // A stale pane pick fails; the normal connection loop falls back to t1.
        *lock(&core.owner.focus) = Some(("gone".into(), Instant::now()));
        assert_eq!(reviewer_attach(&core)["terminal"], "gone");
        let fallback = reviewer_attach(&core);
        assert_eq!(fallback["terminal"], "t1");
        assert_eq!(fallback["resize_history"], 1000);
        eprintln!("fallback after refused pick: {fallback}");
        reviewer_wait(|| lock(&core.owner.state).terminal.as_ref().is_some_and(|p| !Arc::ptr_eq(p, &previous)));
        let pane = lock(&core.owner.state).terminal.as_ref().unwrap().clone();
        let mut alt = connection_tests::core_paint(&[], &[], 40);
        alt.extend_from_slice(b"\x1b[?1049h\x1b[H\x1b[2JALT restored");
        core.push.send((4, 0, alt)).unwrap();
        reviewer_wait(|| pane.screen_text().contains("ALT restored"));
        core.push.send((4, 0, owner_main_paint(&fallback, &all[..40], &all[40..], 40))).unwrap();
        reviewer_wait(|| pane.screen_text().contains("row 049"));
        let got: Vec<String> = connection_tests::all_rows(&pane).into_iter().filter(|s| !s.is_empty()).collect();
        core.close();
        assert_eq!(got, all, "path A fallback to the previous pane still owes its repair");
    }
}

/// t217's review probes on fc3d430 (PR #29), kept: explicit state
/// transitions, frames captured on a socket pair, no timing guesses.
#[cfg(test)]
#[allow(clippy::unwrap_used, clippy::expect_used)]
mod focus_review_tests {
    use super::*;
    use crate::remote::{Heard, State};
    use herdr_remote_transport::CancellationToken;
    use std::sync::{Mutex, atomic::AtomicBool};

    fn owner() -> Arc<Session> {
        let (commands, _) = mpsc::sync_channel(256);
        Arc::new(Session {
            id: 0,
            state: Mutex::new(State::new(json!({"revision":0,"panes":[]}))),
            cancel: CancellationToken::new(),
            commands,
            focus: Mutex::new(None),
            heard: Heard::default(),
            visible: AtomicBool::new(true),
            uses: Box::leak(Box::new(crate::remote::LastUse::new())),
        })
    }

    /// After an automatic reconnect attached with an old idle_ms: focused,
    /// not yet visible, waiting for the view's measured RESIZE.
    fn awaiting_measurement(owner: &Arc<Session>) -> (Connection<'_>, UnixStream) {
        let (socket, peer) = UnixStream::pair().unwrap();
        peer.set_nonblocking(true).unwrap();
        let connection = Connection {
            socket,
            late_replies: 0,
            commands: None,
            decoder: Decoder::default(),
            owner,
            serial: 1,
            channel: 2,
            output: None,
            events: 1,
            event_carry: Vec::new(),
            dirty: false,
            negotiated: false,
            claim: Claim {
                offered: true,
                frames: true,
                attached_at: Some(Instant::now()),
                ..Claim::default()
            },
            history_on_resize: false,
            reattach: None,
            reattached_at: None,
            ended: None,
            automatic_input: true,
            transcripts: false,
            held: None,
            focus: Focus {
                offered: true,
                frames: true,
                on: true,
                uses: owner.uses.count(),
                ..Focus::default()
            },
            painted: false,
        };
        (connection, peer)
    }

    #[test]
    fn a_refused_attach_with_nothing_to_fall_back_to_is_ready_with_no_pane_and_says_why() {
        let mut view = json!({"state":"connecting","pane_id":"","revision":3});
        refused(&mut view, "t1b6", &"x".repeat(500));
        assert_eq!(view["state"], "ready");
        assert_eq!(view["pane_id"], "");
        assert_eq!(view["refused"]["terminal"], "t1b6");
        assert_eq!(view["refused"]["reason"].as_str().unwrap().chars().count(), 240);
        assert_ne!(view["revision"], 3, "the view changed, so the phone reads it");
    }

    /// Audit A5: keys typed while a command runs on the machine go out before its reply.
    #[test]
    fn typing_flows_while_a_slow_call_waits() {
        let owner = owner();
        let (mut connection, mut peer) = awaiting_measurement(&owner);
        connection.socket.set_read_timeout(Some(Duration::from_millis(10))).unwrap();
        let (tx, rx) = mpsc::sync_channel(8);
        connection.commands = Some(&rx);
        tx.send(Operation::Frame(2, 0, b"typed".to_vec())).unwrap();
        // No reply comes: the call times out, but the keys left while it waited.
        let commands = connection.commands;
        let short = Duration::from_millis(80);
        let result = connection.call_pumping("machine.exec", json!({"argv":["true"]}), short, commands);
        assert!(matches!(result, Err(Error::Timeout { .. })), "{result:?}");
        let sent = frames(&mut peer);
        assert!(sent.iter().any(|(channel, kind, bytes)| (*channel, *kind) == (2, 0) && bytes == b"typed"), "{sent:?}");
        // An operation that needs the RPC slot waits for the reply instead of going out.
        let (reply, _) = mpsc::sync_channel(1);
        tx.send(Operation::Action("tab.new".into(), reply, None)).unwrap();
        let _ = connection.call_pumping("machine.exec", json!({"argv":["true"]}), short, commands);
        assert!(matches!(connection.held, Some(Operation::Action(..))));
    }

    #[test]
    fn the_phone_waits_longer_than_the_core_was_asked_to_take() {
        assert_eq!(rpc_wait(&json!({})), Duration::from_secs(15));
        assert_eq!(rpc_wait(&json!({"timeout":5})), Duration::from_secs(15));
        assert_eq!(rpc_wait(&json!({"timeout":20})), Duration::from_secs(25));
        assert_eq!(rpc_wait(&json!({"timeout":100000})), Duration::from_secs(120));
        assert_eq!(rpc_wait(&json!({"timeout":"20"})), Duration::from_secs(15));
    }

    #[test]
    fn a_reply_to_a_call_given_up_on_is_dropped_and_the_next_call_still_gets_its_own() {
        assert_eq!(reply_order(&json!(4), 4), ReplyOrder::This);
        assert_eq!(reply_order(&json!(3), 4), ReplyOrder::Late);
        assert_eq!(reply_order(&json!(5), 4), ReplyOrder::Unknown);
        assert_eq!(reply_order(&json!("4"), 4), ReplyOrder::Unknown);
        let owner = owner();
        let (mut connection, mut peer) = awaiting_measurement(&owner);
        connection.socket.set_read_timeout(Some(Duration::from_millis(10))).unwrap();
        // The first call times out: a typed Timeout, never "uncorrelated" or Closed.
        let first = connection.call_within("machine.exec", json!({}), Duration::from_millis(60));
        assert!(matches!(&first, Err(Error::Timeout { method }) if method == "machine.exec"), "{first:?}");
        let asked: Vec<Value> = frames(&mut peer).into_iter().map(|(_, _, b)| serde_json::from_slice(&b).unwrap()).collect();
        let late = asked[0]["id"].as_u64().unwrap();
        // Its reply arrives during the next call, before that call's own reply.
        let reply = |id: u64, result: Value| {
            serde_json::to_vec(&json!({"jsonrpc":"2.0","id":id,"result":result})).unwrap()
        };
        write_frame(&mut peer, 0, 0, &reply(late, json!({"stdout":"late"}))).unwrap();
        write_frame(&mut peer, 0, 0, &reply(late + 1, json!({"stdout":"mine"}))).unwrap();
        let second = connection.call("terminal.list", json!({})).unwrap();
        assert_eq!(second["stdout"], "mine");
        assert_eq!(connection.late_replies, 1);
        // A reply this session never asked for still ends it as a protocol error.
        write_frame(&mut peer, 0, 0, &reply(late + 9, json!({}))).unwrap();
        let third = connection.call_within("doc.get", json!({}), Duration::from_secs(2));
        assert!(matches!(third, Err(Error::Protocol(_))), "{third:?}");
    }

    fn frames(peer: &mut UnixStream) -> Vec<(u32, u8, Vec<u8>)> {
        let mut bytes = Vec::new();
        let result = peer.read_to_end(&mut bytes);
        assert!(matches!(result, Err(e) if e.kind() == io::ErrorKind::WouldBlock));
        let mut decoder = Decoder { bytes };
        let mut frames = Vec::new();
        while let Some(frame) = decoder.next().unwrap() {
            frames.push((frame.channel, frame.kind, frame.bytes));
        }
        frames
    }

    #[test]
    fn a_touch_while_waiting_for_size_is_reported_when_the_pane_is_shown() {
        let owner = owner();
        let (mut connection, mut peer) = awaiting_measurement(&owner);
        owner.uses.mark();
        connection.sync_focus().unwrap();
        assert!(frames(&mut peer).is_empty());
        connection.resize(90, 40).unwrap();
        connection.sync_claim(90, 40).unwrap();
        connection.sync_focus().unwrap();
        let kinds: Vec<_> = frames(&mut peer).into_iter().map(|(_, kind, _)| kind).collect();
        assert_eq!(
            kinds,
            [RESIZE, VISIBILITY, WINDOW_FOCUS],
            "the touch after attach must refresh the old attach idle_ms when shown"
        );
    }

    #[test]
    fn an_untouched_reconnect_is_shown_without_a_focus_frame() {
        let owner = owner();
        let (mut connection, mut peer) = awaiting_measurement(&owner);
        connection.resize(90, 40).unwrap();
        connection.sync_claim(90, 40).unwrap();
        connection.sync_focus().unwrap();
        let kinds: Vec<_> = frames(&mut peer).into_iter().map(|(_, kind, _)| kind).collect();
        assert_eq!(kinds, [RESIZE, VISIBILITY], "its attach's idle_ms stands");
    }

    #[test]
    fn hiding_before_first_measurement_unfocuses_the_focused_attach() {
        let owner = owner();
        let (mut connection, mut peer) = awaiting_measurement(&owner);
        owner.visible.store(false, Ordering::Relaxed);
        connection.sync_claim(90, 40).unwrap();
        connection.sync_focus().unwrap();
        assert_eq!(
            frames(&mut peer),
            [(2, WINDOW_FOCUS, vec![0])],
            "the focused attach must receive 0 even though VISIBILITY is already 0"
        );
    }

    #[test]
    fn first_use_after_hide_bypasses_the_existing_rate_limit() {
        let owner = owner();
        let (mut connection, mut peer) = awaiting_measurement(&owner);
        connection.claim.shown = true;
        connection.focus.sent_at = Some(Instant::now());
        owner.visible.store(false, Ordering::Relaxed);
        connection.sync_claim(90, 40).unwrap();
        assert_eq!(
            frames(&mut peer),
            [(2, WINDOW_FOCUS, vec![0]), (2, VISIBILITY, vec![0])]
        );
        owner.visible.store(true, Ordering::Relaxed);
        connection.resize(90, 40).unwrap();
        connection.sync_claim(90, 40).unwrap();
        owner.uses.mark();
        connection.sync_focus().unwrap();
        let frames = frames(&mut peer);
        assert_eq!(frames.last(), Some(&(2, WINDOW_FOCUS, vec![1])));
    }
}
