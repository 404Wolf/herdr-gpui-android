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
struct Connection<'a> {
    socket: UnixStream,
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
    /// The grid of the pane whose stream the core ended: the next attach of
    /// that pane (the automatic reattach, also parked and shown later, or a
    /// person picking it again) starts its new grid from its main screen, so
    /// history the core does not send again (an attach on the alternate
    /// screen carries none) is not lost. Read only once its reader has parsed
    /// every byte the stream delivered (see [`Carried::copy`]).
    carried: Option<Carried>,
}

/// The grid of a pane whose stream the core ended (`Connection::carried`).
struct Carried {
    pane: String,
    grid: Arc<gpui_rio::Session>,
    closed: Instant,
}

/// How long a carried grid's reader may take to parse what its stream
/// delivered before it is copied anyway.
const CARRY_DRAIN: Duration = Duration::from_secs(2);

impl Carried {
    /// Its main screen and scrollback as VT bytes, with the grid they were
    /// copied at. Waits (on the session loop, never the UI) until its reader
    /// has parsed every byte the ended stream delivered (it stops at the
    /// stream's end), at most [`CARRY_DRAIN`] after the end.
    fn copy(self) -> ((u16, u16), Vec<u8>) {
        while self.grid.is_alive() && self.closed.elapsed() < CARRY_DRAIN {
            std::thread::sleep(Duration::from_millis(5));
        }
        let grid = self.grid.grid().lock();
        let size = (grid.columns() as u16, grid.screen_lines() as u16);
        (size, crate::snapshot::encode_main(&grid, crate::snapshot::MAX_BYTES))
    }
}

/// History rows asked for in the repaint after a RESIZE: the attach's own
/// (`attach_params`). That ESC[3J replaces the whole scrollback, so fewer
/// would cut the phone's history shorter than the attach gave it, and more
/// buys nothing the phone ever had. Bounding the bytes is the core's cap.
const RESIZE_HISTORY: usize = 1000;
/// At most one automatic reattach of an ended stream this often.
const REATTACH_EVERY: Duration = Duration::from_secs(5);

/// Frame kinds this viewer sends besides DATA (0) and CREDIT (1).
const DETACH: u8 = 2;
const RESIZE: u8 = 3;
/// herdr-core's viewer VISIBILITY frame: one byte, 0 hidden or 1 visible.
const VISIBILITY: u8 = 7;
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
    fn detach(&mut self) -> Result<()> {
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
        self.call_within(method, params, Duration::from_secs(15))
    }
    fn call_within(&mut self, method: &str, params: Value, wait: Duration) -> Result<Value> {
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
            let Some(frame) = self.read()? else { continue };
            if frame.channel == 0 && frame.kind == 0 {
                let reply: Value = serde_json::from_slice(&frame.bytes)?;
                if reply["id"] != id {
                    return Err(Error::Protocol("uncorrelated RPC reply".into()));
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
        Err(Error::Protocol(format!("{method} timed out")))
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
                    let mut state = lock(&self.owner.state);
                    let pane = state.view["pane_id"].as_str().unwrap_or("").to_owned();
                    // Kept for the next attach of this pane, which copies its
                    // main screen once its reader has parsed what the ended
                    // stream delivered (read only: it may still be on screen).
                    self.carried = state
                        .terminal
                        .clone()
                        .filter(|_| !pane.is_empty())
                        .map(|grid| Carried { pane: pane.clone(), grid, closed: Instant::now() });
                    state.view["state"] = json!("connecting");
                    state.view["status"] =
                        json!("Terminal stream ended; select a terminal to attach again");
                    state.terminal = None;
                    state.render_id = 0;
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
                Some(TabEdit::FileRead { terminal, path }) => {
                    return Ok(match self.read_file(&view, &terminal, &path) {
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
                Some(TabEdit::Deliver { terminal, text }) => {
                    // The outbox's send: never forced, and the reply says
                    // whether the text was typed, refused, or cannot be known.
                    let sent = self.call_within(
                        "terminal.send",
                        json!({"terminal":terminal,"text":text}),
                        DELIVER_WAIT,
                    );
                    return Ok(delivery(&sent).to_string());
                }
                Some(edit) => self.edit_tab(edit, cols, rows)?,
                None => return Err(Error::StaleTarget),
            },
        }
        Ok(json!({"queued":true}).to_string())
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
        let result = self.call("machine.exec", json!({"argv":["sh","-c",DEACON_DIR],"timeout":10}))?;
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
        let result = self.call("machine.exec", json!({"argv":argv,"timeout":10}))?;
        let text = exec_stdout(&result)?;
        Ok(json!({"queued":true,"text":text,"truncated":result["truncated"]}).to_string())
    }
    /// A text file for the file view, read on the machine `terminal` runs on:
    /// a relative path from that terminal's folder, `~/` from its home.
    /// Bounded to the first FILE_LINES lines (and file.read's 8 MB).
    fn read_file(&mut self, view: &Value, terminal: &str, path: &str) -> Result<Value> {
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
                let result = self.call("machine.exec", exec)?;
                let home = exec_stdout(&result)?;
                (rest.to_owned(), Some(home).filter(|h| h.starts_with('/')))
            }
            None => (path.to_owned(), cwd),
        };
        let mut params = json!({"path":path,"offset":1,"limit":FILE_LINES});
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
            TabEdit::WindowsMove {
                project,
                id,
                target,
                after,
                shown,
            } => {
                // One compare-and-set per try, from the doc as it is now: a drop
                // never writes back another window's stale order.
                for _ in 0..5 {
                    let doc = self.call("doc.get", json!({"name":"projects.order"}))?;
                    let Some(value) = order_move(&doc["value"], &project, &id, &target, after, &shown) else {
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
            TabEdit::DeaconStart { machine } => self.start_deacon(&machine, cols, rows).map(drop),
            TabEdit::GitLook { .. }
            | TabEdit::FileRead { .. }
            | TabEdit::SpaceOpen { .. }
            | TabEdit::Send { .. }
            | TabEdit::Deliver { .. } => {
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
        let result = self.call(
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
            let probe = self.call("machine.exec", json!({"argv":["ssh","-oBatchMode=yes","-oStrictHostKeyChecking=yes","-oConnectTimeout=2","--",host,"command -v herdr-core >/dev/null && herdr-core server status >/dev/null"],"timeout":3}))?;
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
        let list = self.call("terminal.list", json!({}))?;
        let doc = self.call("doc.get", json!({"name":"herdr-app"}))?;
        // Wolf's order of the projects (item 62), kept in this core's doc
        // projects.order by the desktop's sidebar; the phone's project
        // switcher reads it. A core without it (older, or never dragged) has
        // no order: birth order then.
        let order = self
            .call("doc.get", json!({"name":"projects.order"}))
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
        // The same pane again after its stream ended: start from what it held.
        let resume = self.carried.take().filter(|carried| carried.pane == terminal).map(|carried| carried.copy());
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
            state.render_id = 0;
            state.view["state"] = json!("connecting");
            bump(&mut state.view);
        }
        let mut params = attach_params(terminal, cols, rows, self.claim.offered);
        if self.history_on_resize {
            params["resize_history"] = json!(RESIZE_HISTORY);
        }
        if resume.is_some() {
            // The same pane again after the core ended its stream: the grid
            // starts from what the phone held. `resumed` makes the owner owe
            // a whole history replacement (sent at once, or on the return to
            // the main screen); without `resize_history` the path cannot
            // send one, so ask no history that would repeat what is held.
            params["resumed"] = json!(true);
            if !self.history_on_resize {
                params["history"] = json!(0);
            }
        }
        let reply = self.call("terminal.attach", params)?;
        // Only the owner's echo confirms it (relays pass on only that): no
        // echo, and the path cannot carry it, so it is not asked again.
        if self.history_on_resize && reply["resize_history"].as_u64().unwrap_or(0) == 0 {
            self.history_on_resize = false;
        }
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
        };
        let session_slot = Arc::new(Mutex::new(Weak::new()));
        let output = StreamRead {
            rx,
            chunks: VecDeque::new(),
            uncredited: 0,
            commands: commands.clone(),
            channel,
            session: session_slot.clone(),
        };
        let terminal_session = gpui_rio::Session::start(
            output,
            input,
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
        // The carried main screen, through the ordered reader: at the grid it
        // was copied at, then back to this stream's grid, all before a byte
        // of this stream (the output opens just below).
        if let Some((carried_grid, bytes)) = resume {
            if let Some(grid) = initial_grid(&reply) {
                let _ = tx.try_send(Chunk::Grid(carried_grid.0, carried_grid.1));
                let _ = tx.try_send(Chunk::Local(bytes));
                let _ = tx.try_send(Chunk::Grid(grid.0, grid.1));
            } else {
                let _ = tx.try_send(Chunk::Local(bytes));
            }
        }
        crate::remote::release_held();
        self.channel = channel;
        self.output = Some(tx);
        let mut state = lock(&self.owner.state);
        state.terminal = Some(terminal_session);
        state.render_id = NEXT_RENDER.fetch_add(1, Ordering::Relaxed);
        state.view["pane_id"] = json!(terminal);
        state.view["focused_pane"] = json!(terminal);
        state.view["state"] = json!("ready");
        state.view["status"] = json!("Connected to herdr-core");
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
    /// Wolf's drag of a project's window (item 79): `id` just before or after
    /// `target` in the core doc projects.order's `windows[<project>]`, the
    /// list the desktop's status line keeps; `shown`: the project's windows
    /// after its lead as drawn (the lead never moves).
    WindowsMove { project: String, id: String, target: String, after: bool, shown: Vec<String> },
    Interrupt { terminal: String },
    Kill { terminal: String },
    DeaconStart { machine: String },
    GitLook { paths: Vec<String> },
    FileRead { terminal: String, path: String },
    SpaceOpen { folder: String },
    Send { terminal: String, text: String },
    /// An outbox message: like `Send`, but a composer's draft, so it may hold
    /// line breaks and tabs and be as long as one compose command.
    Deliver { terminal: String, text: String },
}
/// A JSON edit's bound: an outbox message is up to one compose command's text,
/// and JSON escaping at most doubles the line breaks and tabs it may hold.
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
                Self::WindowsMove {
                    project: name(&value["project"])?,
                    id: name(&value["id"])?,
                    target: name(&value["target"])?,
                    after: value["after"].as_bool()?,
                    shown: shown.iter().map(name).collect::<Option<Vec<_>>>()?,
                }
            }
            "pane.interrupt" => Self::Interrupt { terminal: id("terminal")? },
            "pane.kill" => Self::Kill { terminal: id("terminal")? },
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
            },
            "space.open" => Self::SpaceOpen { folder: folder(&value["folder"])? },
            "pane.send" => Self::Send {
                terminal: id("terminal")?,
                text: value["text"]
                    .as_str()
                    .filter(|t| !t.trim().is_empty() && t.chars().count() <= 1000 && !t.chars().any(char::is_control))
                    .map(str::to_owned)?,
            },
            "pane.deliver" => Self::Deliver {
                terminal: id("terminal")?,
                text: value["text"]
                    .as_str()
                    .filter(|t| {
                        !t.trim().is_empty()
                            && t.len() <= crate::model::MAX_TEXT_BYTES
                            && !t.chars().any(|c| c.is_control() && c != '\n' && c != '\t')
                    })
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
/// herdr-projects-order's reading of the projects.order doc: lists of names
/// only, `windows` only once something is stored there.
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
    out
}
/// One window move in projects.order (item 79), as herdr-projects-order makes
/// it: the stored list for `project` less the windows no longer shown (they
/// ended), then those `shown` adds, with `id` just before or after `target`.
/// None: nothing moves (either is missing, or it is already there).
fn order_move(value: &Value, project: &str, id: &str, target: &str, after: bool, shown: &[String]) -> Option<Value> {
    let mut v = order_clean(value);
    let stored: Vec<String> = v["windows"][project]
        .as_array()
        .map(|a| a.iter().filter_map(|x| x.as_str().map(str::to_owned)).collect())
        .unwrap_or_default();
    let stored: Vec<String> = if shown.is_empty() { stored } else { stored.into_iter().filter(|x| shown.contains(x)).collect() };
    let mut ids = stored.clone();
    ids.extend(shown.iter().filter(|x| !stored.contains(x)).cloned());
    if id == target || !ids.iter().any(|x| x == id) || !ids.iter().any(|x| x == target) {
        return None;
    }
    let mut rest: Vec<String> = ids.iter().filter(|x| *x != id).cloned().collect();
    let at = rest.iter().position(|x| x == target)? + usize::from(after);
    rest.insert(at, id.to_owned());
    if rest == ids || rest.len() > 1000 {
        return None;
    }
    if !v["windows"].is_object() {
        v["windows"] = json!({});
    }
    v["windows"][project] = json!(rest);
    Some(v)
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
/// How long the outbox's `terminal.send` may take: the core waits for a
/// starting agent to settle, for a person to pause typing, then for the turn
/// to start, each a few seconds.
pub(crate) const DELIVER_WAIT: Duration = Duration::from_secs(60);
/// What an outbox send did, as `{"outcome": ..}`:
/// `sent` (the core typed it), `blocked` (refused: the program waits on a
/// dialog, nothing typed), `gone` (no such terminal, nothing typed), or
/// `unsure` (anything else: the text may have been typed, so it must never be
/// sent again without the user's word).
fn delivery(sent: &Result<Value>) -> Value {
    match sent {
        Ok(_) => json!({"outcome":"sent"}),
        Err(Error::Rpc { kind, .. }) if kind == "blocked" => json!({"outcome":"blocked"}),
        Err(Error::Rpc { kind, .. }) if kind == "not_found" => json!({"outcome":"gone"}),
        Err(error) => json!({"outcome":"unsure","why":error.to_string()}),
    }
}
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
fn attach_params(terminal: &str, cols: u16, rows: u16, visibility: bool) -> Value {
    let mut params = json!({"terminal":terminal,"cols":cols,"rows":rows,"history":1000,"display":display(),"resize_notifications":true});
    if visibility {
        // Hidden until a measured RESIZE: the attach-time size is only the
        // phone's estimate and must not lead the grid.
        params["visibility_updates"] = json!(true);
        params["visible"] = json!(false);
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
    /// Bytes from the phone itself (a carried main screen), parsed in order
    /// like DATA but never credited to the core.
    Local(Vec<u8>),
    /// The PTY's authoritative grid (a RESIZE frame).
    Grid(u16, u16),
}
struct StreamRead {
    rx: mpsc::Receiver<Chunk>,
    chunks: VecDeque<u8>,
    /// Bytes at the front of `chunks` that came from the phone itself (a
    /// carried main screen), not from the core: no CREDIT for them.
    uncredited: usize,
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
                Ok(Chunk::Local(bytes)) => {
                    // Only reached with nothing else queued: these bytes lead.
                    self.uncredited += bytes.len();
                    self.chunks.extend(bytes);
                }
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
        let local = n.min(self.uncredited);
        self.uncredited -= local;
        if n > local {
            self.commands
                .try_send(Operation::Frame(
                    self.channel,
                    1,
                    ((n - local) as u32).to_le_bytes().to_vec(),
                ))
                .map_err(|_| io::ErrorKind::BrokenPipe)?;
        }
        Ok(n)
    }
}
struct StreamWrite {
    commands: mpsc::SyncSender<Operation>,
    channel: u32,
}
impl Write for StreamWrite {
    fn write(&mut self, bytes: &[u8]) -> io::Result<usize> {
        self.commands
            .try_send(Operation::Frame(self.channel, 0, bytes.to_vec()))
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
    lock(&owner.state).view["boot_id"] = hello["result"]["core"]["id"].clone();
    let mut connection = Connection {
        socket,
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
        carried: None,
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
            match commands.try_recv() {
                Ok(Operation::Discover(reply)) => {
                    let _ = reply.send(connection.discover());
                }
                Ok(Operation::Action(action, reply)) => {
                    let _ = reply.send(connection.action(&action, cols, rows));
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
        let mut switch = lock(&owner.focus).take();
        if !connection.may_attach() {
            // Hidden without VISIBILITY frames: remember the pick, claim nothing.
            if let Some((terminal, _)) = switch.take() {
                // A person's pick: an owed reattach of another pane is off.
                if connection.reattach.as_ref().is_some_and(|owed| *owed != terminal) {
                    connection.reattach = None;
                    connection.carried = None;
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
                Err(reason @ (Error::Protocol(_) | Error::Rpc { .. })) => {
                    #[cfg(feature = "android-ui")]
                    log::warn!("switch {terminal} refused: {reason}");
                    #[cfg(not(feature = "android-ui"))]
                    let _ = reason;
                    connection.refresh()?;
                    if !previous.is_empty()
                        && previous != terminal
                        && let Err(Error::Protocol(_) | Error::Rpc { .. }) =
                            connection.attach(&previous, cols, rows)
                    {
                        connection.refresh()?;
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
                    Err(Error::Protocol(_) | Error::Rpc { .. }) => {
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
        let space = terminal["labels"]["herdr.space"]
            .as_str()
            .unwrap_or("loose");
        let tab = terminal["labels"]["herdr.tab"].as_str().unwrap_or(id);
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
            // What the deacon view shows under each agent: the program's own
            // status message, why it is blocked, and since when (core clock, ms).
            "msg": terminal["status"]["msg"].as_str().map(|m| m.chars().take(240).collect::<String>()).unwrap_or_default(),
            "wait_kind": terminal["status"]["kind"].as_str().unwrap_or(""),
            "status_ms": terminal["status_ms"].as_u64().unwrap_or(0),
            // Picks the canonical deacon when a computer has several candidates.
            "created_ms": terminal["created_ms"].as_u64().unwrap_or(0),
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
    use super::{GIT_LOOK, add_space, attach_params, canonical_deacon, deacon_line, delivery, existing_at, initial_grid, resize_frame, send_refusal};
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
        let params = attach_params("t3", 120, 40, false);
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
    fn an_outbox_send_says_whether_it_was_typed() {
        let rpc = |kind: &str| Err(Error::Rpc { kind: kind.into(), message: "m".into() });
        assert_eq!(delivery(&Ok(json!({"turn":3})))["outcome"], "sent");
        assert_eq!(delivery(&rpc("blocked"))["outcome"], "blocked");
        assert_eq!(delivery(&rpc("not_found"))["outcome"], "gone");
        // A core that may have typed before failing (its save timed out).
        assert_eq!(delivery(&rpc("unavailable"))["outcome"], "unsure");
        // The link failed or the reply never came: it may have been typed.
        assert_eq!(delivery(&Err(Error::Closed))["outcome"], "unsure");
        assert_eq!(delivery(&Err(Error::Protocol("terminal.send timed out".into())))["outcome"], "unsure");
    }
    #[test]
    fn outbox_messages_keep_line_breaks_but_no_other_controls() {
        let parse = |text: &str| {
            TabEdit::parse(&json!({"op":"pane.deliver","terminal":"t3","text":text}).to_string())
        };
        assert_eq!(
            parse("first line\n\tsecond"),
            Some(TabEdit::Deliver { terminal: "t3".into(), text: "first line\n\tsecond".into() })
        );
        assert_eq!(parse("a\u{1b}[2J"), None);
        assert_eq!(parse("ok\r"), None);
        assert_eq!(parse(" \n "), None);
        let longest = "é\n".repeat(crate::model::MAX_TEXT_BYTES / 3);
        assert!(parse(&longest).is_some());
        assert_eq!(parse(&"x".repeat(crate::model::MAX_TEXT_BYTES + 1)), None);
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
            return; // no git on this host
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
            Some(TabEdit::FileRead { terminal: "t3".into(), path: "src/a.rs".into() })
        );
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
        // As herdr-projects-order's test_item_79: its own list beside the others.
        let got = order_move(&json!({"repos":[],"projects":{"monopoly":["cleanup","qos"]}}), "research-ui", "t1s7", "t1s6", false,
            &ids(&["t1s6", "t1s7", "tab:s3/4"])).unwrap();
        assert_eq!(got, json!({"repos":[],"projects":{"monopoly":["cleanup","qos"]},"windows":{"research-ui":["t1s7","t1s6","tab:s3/4"]}}));
        // Windows that ended leave the list at the next move; a new one joins.
        let again = order_move(&got, "research-ui", "t1new", "t1s6", false, &ids(&["t1s6", "t1new"])).unwrap();
        assert_eq!(again["windows"]["research-ui"], json!(["t1new", "t1s6"]));
        // Nothing moves: the same place, a name not shown (the lead), or onto itself.
        assert!(order_move(&again, "research-ui", "t1new", "t1s6", false, &ids(&["t1new", "t1s6"])).is_none());
        assert!(order_move(&again, "research-ui", "t9r", "t1s6", false, &ids(&["t1new", "t1s6"])).is_none());
        assert!(order_move(&again, "research-ui", "t1s6", "t1s6", true, &ids(&["t1new", "t1s6"])).is_none());
        // After, and a doc that was never written.
        let fresh = order_move(&Value::Null, "x#t1", "a", "c", true, &ids(&["a", "b", "c"])).unwrap();
        assert_eq!(fresh, json!({"repos":[],"projects":{},"windows":{"x#t1":["b","c","a"]}}));
        // A doc with no windows yet reads without the key, as the tool keeps it.
        assert_eq!(order_clean(&json!({"repos":["a"],"projects":{},"windows":{}})), json!({"repos":["a"],"projects":{}}));
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
            Some(TabEdit::WindowsMove { project: "same#t31".into(), id: "t1c".into(), target: "tab:s1/4".into(), after: true, shown: vec!["t1c".into(), "tab:s1/4".into()] })
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
    use std::io::BufRead;
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
            state: Mutex::new(State {
                view: json!({"revision":0,"panes":[]}),
                terminal: None,
                render_id: 0,
            }),
            cancel: CancellationToken::new(),
            commands,
            focus: Mutex::new(None),
            heard: crate::remote::Heard::default(),
            visible: std::sync::atomic::AtomicBool::new(true),
        });
        let worker = owner.clone();
        let runtime = std::thread::spawn(move || run(&worker, phone, 80, 24, true, rx));
        let (observed, events) = mpsc::sync_channel(2);
        let peer = std::thread::spawn(move || {
            let mut hello = String::new();
            io::BufReader::new(core.try_clone().unwrap())
                .read_line(&mut hello)
                .unwrap();
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
    #[test]
    fn a_coalesced_attach_reply_initial_resize_and_paint_are_all_applied_without_echo() {
        let (phone, mut core) = UnixStream::pair().unwrap();
        core.set_read_timeout(Some(Duration::from_millis(50))).unwrap();
        let (commands, rx) = mpsc::sync_channel(256);
        let owner = Arc::new(Session {
            id: 0,
            state: Mutex::new(State {
                view: json!({"revision":0,"panes":[]}),
                terminal: None,
                render_id: 0,
            }),
            cancel: CancellationToken::new(),
            commands,
            focus: Mutex::new(None),
            heard: crate::remote::Heard::default(),
            visible: std::sync::atomic::AtomicBool::new(true),
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
        let (phone, mut core) = UnixStream::pair().unwrap();
        core.set_read_timeout(Some(Duration::from_millis(50))).unwrap();
        let (commands, rx) = mpsc::sync_channel(256);
        let owner = Arc::new(Session {
            id: 0,
            state: Mutex::new(State { view: json!({"revision":0,"panes":[]}), terminal: None, render_id: 0 }),
            cancel: CancellationToken::new(),
            commands: commands.clone(),
            focus: Mutex::new(None),
            heard: crate::remote::Heard::default(),
            visible: std::sync::atomic::AtomicBool::new(true),
        });
        let worker = owner.clone();
        let runtime = std::thread::spawn(move || run(&worker, phone, 80, 24, true, rx));
        let seen = Arc::new(Mutex::new(Vec::new()));
        let log = seen.clone();
        let peer = std::thread::spawn(move || {
            let mut hello = String::new();
            read_hello(&core, &mut hello);
            core.write_all(b"{\"jsonrpc\":\"2.0\",\"id\":0,\"result\":{\"generation\":1,\"core\":{\"id\":\"fixture\"},\"framed\":true}}\n").unwrap();
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
        commands.try_send(Operation::Action(action.into(), tx)).unwrap();
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

    #[test]
    fn an_outbox_message_is_sent_with_terminal_send_and_its_outcome_reported() {
        let action = r#"{"op":"pane.deliver","terminal":"t3","text":"two\nlines"}"#;
        let (result, seen) = action_against(action, |method, _, _| Ok(base_answer(method)));
        assert_eq!(serde_json::from_str::<Value>(&result.unwrap()).unwrap()["outcome"], "sent");
        let send = seen.iter().find(|(m, _)| m == "terminal.send").unwrap();
        // Never forced, and the line break survives as one paste.
        assert_eq!(send.1, json!({"terminal":"t3","text":"two\nlines"}));
        for (kind, outcome) in [("blocked", "blocked"), ("not_found", "gone"), ("internal", "unsure")] {
            let (result, _) = action_against(action, move |method, _, _| match method {
                "terminal.send" => Err(kind),
                m => Ok(base_answer(m)),
            });
            assert_eq!(serde_json::from_str::<Value>(&result.unwrap()).unwrap()["outcome"], outcome);
        }
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
        let output = StreamRead { rx, chunks: VecDeque::new(), uncredited: 0, commands: commands.clone(), channel: 9, session: slot.clone() };
        let input = StreamWrite { commands, channel: 9 };
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
        let output = StreamRead { rx, chunks: VecDeque::new(), uncredited: 0, commands: commands.clone(), channel: 9, session: slot.clone() };
        let input = StreamWrite { commands, channel: 9 };
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
        let output = StreamRead { rx, chunks: VecDeque::new(), uncredited: 0, commands: commands.clone(), channel: 9, session: slot.clone() };
        let input = StreamWrite { commands, channel: 9 };
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
    /// paint) the history pushed up with newlines and erased, then the scroll
    /// region and the screen rows from the top.
    pub(super) fn core_paint(history: &[String], screen: &[String], rows: usize) -> Vec<u8> {
        let mut out = String::from("\x1b[!p\x1b[?2026l\x1b[?1049l\x1b[?1047l\x1b[?47l\x1b[H\x1b[2J");
        if !history.is_empty() {
            out.push_str(&history.join("\r\n"));
            out.push_str("\x1b[0m\r\n");
            out.push_str(&"\n".repeat(rows));
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
        let output = StreamRead { rx, chunks: VecDeque::new(), uncredited: 0, commands: commands.clone(), channel: 9, session: slot.clone() };
        let input = StreamWrite { commands, channel: 9 };
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
    /// phone's scrollback. The scrollback is the pane's history, once.
    #[test]
    fn core_repaints_never_copy_the_screen_into_the_scrollback() {
        let line = |name: &str, n: usize| format!("{name} {n}");
        // A pane longer than the screen: 30 history rows, a 10-row screen.
        let history: Vec<String> = (0..30).map(|n| line("old", n)).collect();
        let screen: Vec<String> = (0..10).map(|n| line("now", n)).collect();
        let first = core_paint(&history, &screen, 10);
        let again = core_paint(&[], &screen, 10);
        let pane = painted(20, 10, vec![first, again.clone(), again]);
        let shown: Vec<String> = all_rows(&pane).into_iter().filter(|r| !r.is_empty()).collect();
        let expected: Vec<String> = history.iter().chain(&screen).cloned().collect();
        assert_eq!(shown, expected);
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
    fn core_resize_paint(history: &[String], screen: &[String], rows: usize) -> Vec<u8> {
        let paint = core_paint(history, screen, rows);
        let reset = b"\x1b[H\x1b[2J";
        let at = paint.windows(reset.len()).position(|w| w == reset).unwrap() + reset.len();
        [b"\x18".as_slice(), &paint[..at], b"\x1b[3J", &paint[at..]].concat()
    }

    fn streamed(cols: u16, rows: u16, chunks: Vec<Chunk>) -> Arc<gpui_rio::Session> {
        let (tx, rx) = mpsc::sync_channel(64);
        let (commands, _credits) = mpsc::sync_channel(4096);
        let slot = Arc::new(Mutex::new(Weak::new()));
        let output = StreamRead { rx, chunks: VecDeque::new(), uncredited: 0, commands: commands.clone(), channel: 9, session: slot.clone() };
        let input = StreamWrite { commands, channel: 9 };
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
        let output = StreamRead { rx, chunks: VecDeque::new(), uncredited: 0, commands: commands.clone(), channel: 9, session: slot.clone() };
        let input = StreamWrite { commands, channel: 9 };
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
        let output = StreamRead { rx, chunks: VecDeque::new(), uncredited: 0, commands: commands.clone(), channel: 9, session: slot.clone() };
        let input = StreamWrite { commands, channel: 9 };
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
    }

    /// How the scripted core answers beyond the defaults: whether it echoes
    /// the `resize_history` an attach asked for (the owner's confirmation),
    /// and from which attach on (counting from 1) it refuses attaches.
    #[derive(Default)]
    struct Script {
        echo: bool,
        /// Echo `resize_history` only on attaches up to this one (counting from 1).
        echo_until: Option<usize>,
        /// The attach reply confirms RESIZE frames (and gives `grid`, else 90x40).
        resizes: bool,
        grid: Option<(u16, u16)>,
        refuse_from: Option<usize>,
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
            Self::scripted(features, confirm, visible, active, Script::default())
        }

        fn scripted(features: Value, confirm: bool, visible: bool, active: bool, script: Script) -> Self {
            let (phone, mut core) = UnixStream::pair().unwrap();
            core.set_read_timeout(Some(Duration::from_millis(20))).unwrap();
            let (commands, rx) = mpsc::sync_channel(256);
            let owner = Arc::new(Session {
                id: 0,
                state: Mutex::new(State {
                    view: json!({"revision":0,"panes":[]}),
                    terminal: None,
                    render_id: 0,
                }),
                cancel: CancellationToken::new(),
                commands,
                focus: Mutex::new(None),
                heard: crate::remote::Heard::default(),
                visible: AtomicBool::new(visible),
            });
            let worker = owner.clone();
            let session = std::thread::spawn(move || run(&worker, phone, 90, 40, active, rx));
            let (tx, seen) = mpsc::channel();
            let (push, pushed) = mpsc::channel::<(u32, u8, Vec<u8>)>();
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
                loop {
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
                            if matches!(frame.kind, DETACH | RESIZE | VISIBILITY) {
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
                            "terminal.attach" => {
                                let asked = request["params"]["visibility_updates"] == true;
                                let _ = tx.send(Seen::Attach(request["params"].clone()));
                                attaches += 1;
                                if killed || script.refuse_from.is_some_and(|from| attaches >= from) {
                                    let error = json!({"jsonrpc":"2.0","id":request["id"],
                                        "error":{"code":1,"message":"t1 has finished","data":{"kind":"finished"}}});
                                    if write_frame(&mut core, 0, 0, &serde_json::to_vec(&error).unwrap()).is_err() { return; }
                                    continue;
                                }
                                next_channel += 1;
                                let mut result = json!({"channel":next_channel - 1,"resize_notifications":script.resizes,"terminal":{"cols":script.grid.map_or(90, |g| g.0),"rows":script.grid.map_or(40, |g| g.1)},
                                       "visibility_updates": asked && confirm});
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
        assert_eq!(first, attach_params("t1", 90, 40, true));
        core.measured(2, 100, 30);
        assert_eq!(core.next(), Seen::Frame(2, RESIZE, size(100, 30)));
        assert_eq!(core.next(), Seen::Frame(2, VISIBILITY, vec![1]));
        let outgoing = lock(&core.owner.state).terminal.clone().unwrap();

        *lock(&core.owner.focus) = Some(("t2".into(), Instant::now()));
        assert_eq!(core.next(), Seen::Frame(2, DETACH, Vec::new()));
        let Seen::Attach(second) = core.next() else { panic!("attach the pick") };
        assert_eq!(second, attach_params("t2", 100, 30, true), "the attach starts at the measured size");
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
        assert_eq!(without, attach_params("t1", 90, 40, true), "nothing else changes");
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
        assert_eq!(params, attach_params("t1", 90, 40, true));
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
        let main = connection_tests::core_paint(&[], &all[40..], 40);
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
        core.owner.commands.send(Operation::Action("pane.close".into(), tx)).unwrap();
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

    /// #27 re-review (t21s) (a): the carried main screen survives a
    /// reattach put off while hidden on a path without visibility frames
    /// (parked) and made when shown; that attach is `resumed`.
    #[test]
    fn a_parked_reattach_shown_later_keeps_the_main_history() {
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
        // Ended, reattached at once (carrying it), then ended again within 5 s.
        core.push.send((2, 2, Vec::new())).unwrap();
        assert_eq!(reviewer_attach(&core)["resumed"], true);
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
        assert_eq!(shown["resumed"], true, "the carried screen went through park and show");
        reviewer_wait(|| lock(&core.owner.state).terminal.is_some());
        let third = lock(&core.owner.state).terminal.clone().unwrap();
        let mut alt = connection_tests::core_paint(&[], &[], 40);
        alt.extend_from_slice(b"\x1b[?1049h\x1b[H\x1b[2JALT again");
        core.push.send((4, 0, alt)).unwrap();
        reviewer_wait(|| third.screen_text().contains("ALT again"));
        core.push.send((4, 0, connection_tests::core_paint(&[], &all[40..], 40))).unwrap();
        reviewer_wait(|| third.screen_text().contains("row 049"));
        let rows: Vec<String> = connection_tests::all_rows(&third).into_iter().filter(|s| !s.is_empty()).collect();
        core.close();
        assert_eq!(rows, all);
    }

    /// (b): the CLOSE comes while the old grid's reader is still parsing
    /// what the stream delivered before it (held here by its grid lock).
    /// The carried copy waits for that, so on a path without
    /// `resize_history` (no history asked, nothing to repair) every row the
    /// stream delivered survives.
    #[test]
    fn a_close_behind_unparsed_output_carries_every_row_delivered() {
        let core = Mock::scripted(json!(["viewer_visibility"]), true, true, true,
            Script { resizes: true, ..Script::default() });
        reviewer_attach(&core);
        reviewer_wait(|| lock(&core.owner.state).terminal.is_some());
        let first = lock(&core.owner.state).terminal.clone().unwrap();
        let all: Vec<String> = (0..300).map(|n| format!("row {n:03}")).collect();
        let held = first.grid().lock();
        for piece in connection_tests::core_paint(&all[..260], &all[260..], 40).chunks(512) {
            core.push.send((2, 0, piece.to_vec())).unwrap();
        }
        core.push.send((2, 2, Vec::new())).unwrap();
        reviewer_wait(|| lock(&core.owner.state).terminal.is_none());
        drop(held);
        let again = reviewer_attach(&core);
        assert_eq!(again["resumed"], true);
        assert_eq!(again["history"], 0, "no resize_history: no history asked, none repeated");
        reviewer_wait(|| lock(&core.owner.state).terminal.as_ref().is_some_and(|p| !Arc::ptr_eq(p, &first)));
        let second = lock(&core.owner.state).terminal.clone().unwrap();
        core.push.send((3, 0, connection_tests::core_paint(&[], &all[260..], 40))).unwrap();
        reviewer_wait(|| second.screen_text().contains("row 299"));
        let rows: Vec<String> = connection_tests::all_rows(&second).into_iter().filter(|s| !s.is_empty()).collect();
        core.close();
        assert_eq!(rows, all);
    }

    /// (c): the carried screen is replayed at the grid it was copied at,
    /// before this stream's grid, whatever the phone offers: rows as wide as
    /// that grid (wider than the phone's 90 columns) come back unwrapped.
    #[test]
    fn a_carried_screen_is_replayed_at_its_own_grid() {
        let core = Mock::scripted(json!(["viewer_visibility"]), true, true, true,
            Script { resizes: true, grid: Some((100, 20)), ..Script::default() });
        reviewer_attach(&core);
        reviewer_wait(|| lock(&core.owner.state).terminal.as_ref().is_some_and(|p| p.grid_size() == (100, 20)));
        let first = lock(&core.owner.state).terminal.clone().unwrap();
        let all: Vec<String> = (0..30).map(|n| format!("{n:03} {}", "x".repeat(95))).collect();
        core.push.send((2, 0, connection_tests::core_paint(&all[..10], &all[10..], 20))).unwrap();
        reviewer_wait(|| first.screen_text().contains("029 "));
        core.push.send((2, 2, Vec::new())).unwrap();
        reviewer_attach(&core);
        reviewer_wait(|| lock(&core.owner.state).terminal.as_ref().is_some_and(|p| !Arc::ptr_eq(p, &first)));
        let second = lock(&core.owner.state).terminal.clone().unwrap();
        reviewer_wait(|| second.screen_text().contains("029 "));
        let rows: Vec<String> = connection_tests::all_rows(&second).into_iter().filter(|s| !s.is_empty()).collect();
        core.close();
        assert_eq!(second.grid_size(), (100, 20));
        assert_eq!(rows, all, "replayed at 100x20, not at the phone's 90 columns");
    }
}
