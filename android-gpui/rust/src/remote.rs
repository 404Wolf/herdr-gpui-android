//! herdr-core sessions over embedded SSH. No TUI bridge or binary projection.
use crate::model;
use herdr_remote_transport::{CancellationToken, Config, Credentials, IrohTarget, Transport};
use serde::Deserialize;
use serde_json::{Value, json};
use std::{
    collections::HashMap,
    io,
    sync::{
        Arc, Mutex, MutexGuard, OnceLock,
        atomic::{AtomicU64, Ordering},
        mpsc,
    },
    time::Duration,
};
use zeroize::Zeroizing;
const MAX_CONFIG: usize = 8192;
pub const MAX_CREDENTIAL_JSON: usize = 420_000;
#[derive(Debug, thiserror::Error)]
pub enum Error {
    #[error("Invalid remote configuration")]
    Config,
    #[error("Provide a password or private key")]
    Credentials,
    #[error("Remote session is closed")]
    Closed,
    #[error("Remote session capacity or queue limit reached")]
    Capacity,
    #[error("Select a current terminal before sending input")]
    StaleTarget,
    #[error("herdr-core protocol: {0}")]
    Protocol(String),
    /// The core answered an RPC with an error: a definite reply, so whatever
    /// the call would have done was refused, never half-sent.
    #[error("herdr-core protocol: \"{kind}\": {message}")]
    Rpc { kind: String, message: String },
    #[error("Saved pane is unreadable")]
    Snapshot,
    /// A project's new window started and is shown, but its tab is not in
    /// herdr-app's layout, so the phone cannot rename or move it.
    #[error("The window started, but its tab could not be saved in the layout ({0}); rename or move it on the desktop")]
    WindowNotPlaced(#[source] Box<Error>),
    #[error(transparent)]
    Json(#[from] serde_json::Error),
    #[error(transparent)]
    Input(#[from] crate::Error),
    #[error(transparent)]
    Transport(#[from] herdr_remote_transport::Error),
    #[error(transparent)]
    IrohTarget(#[from] herdr_remote_transport::IrohTargetError),
    #[error(transparent)]
    Terminal(#[from] gpui_rio::Error),
    #[error(transparent)]
    Io(#[from] io::Error),
}
pub type Result<T> = std::result::Result<T, Error>;
#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
struct WireConfig {
    transport: String,
    #[serde(default)]
    host: String,
    #[serde(default = "default_port")]
    port: u16,
    #[serde(default)]
    endpoint_id: String,
    #[serde(default)]
    endpoint_ticket: String,
    #[serde(default)]
    relay_url: String,
    username: String,
    session: String,
    #[serde(default)]
    expected_fingerprint: String,
}
fn default_port() -> u16 {
    22
}
pub(crate) fn parse_config(raw: &str) -> Result<(Config, String)> {
    if raw.len() > MAX_CONFIG {
        return Err(Error::Config);
    }
    let wire: WireConfig = serde_json::from_str(raw)?;
    if wire.username.is_empty()
        || wire.username.len() > 256
        || wire.username.chars().any(char::is_control)
        || wire.session.len() > 128
    {
        return Err(Error::Config);
    }

    let transport = match wire.transport.as_str() {
        "tcp"
            if !wire.host.is_empty()
                && wire.host.len() <= 253
                && wire.port != 0
                && !wire.host.chars().any(char::is_control) =>
        {
            Transport::Tcp {
                host: wire.host,
                port: wire.port,
            }
        }
        "iroh" => Transport::Iroh {
            target: IrohTarget::from_profile(
                &wire.endpoint_id,
                &wire.endpoint_ticket,
                &wire.relay_url,
            )?,
        },
        _ => return Err(Error::Config),
    };
    Ok((
        Config {
            transport,
            username: wire.username,
            expected_fingerprint: if wire.expected_fingerprint.is_empty() {
                None
            } else {
                Some(wire.expected_fingerprint)
            },
        },
        wire.session,
    ))
}

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
struct WireCredentials {
    password: Option<Zeroizing<String>>,
    private_key: Option<Zeroizing<String>>,
    passphrase: Option<Zeroizing<String>>,
}
pub(crate) fn parse_credentials(raw: &str) -> Result<Credentials> {
    if raw.len() > MAX_CREDENTIAL_JSON {
        return Err(Error::Credentials);
    }
    let mut wire: WireCredentials = serde_json::from_str(raw)?;
    match (
        &mut wire.password,
        &mut wire.private_key,
        &mut wire.passphrase,
    ) {
        (Some(password), None, None) if !password.is_empty() && password.len() <= 4096 => {
            Ok(Credentials::password(std::mem::take(&mut **password)))
        }
        (None, Some(key), passphrase)
            if !key.is_empty()
                && key.len() <= 65_536
                && passphrase.as_ref().is_none_or(|value| value.len() <= 4096) =>
        {
            Ok(Credentials::private_key(
                std::mem::take(&mut **key),
                passphrase
                    .as_mut()
                    .map(|value| std::mem::take(&mut **value)),
            ))
        }
        _ => Err(Error::Credentials),
    }
}
/// Called by Java's background executor. Inspection does not authenticate.
pub fn inspect(raw: &str) -> Result<String> {
    let (config, _) = parse_config(raw)?;
    let runtime = tokio::runtime::Builder::new_multi_thread()
        .worker_threads(1)
        .enable_all()
        .build()?;
    let key = runtime.block_on(herdr_remote_transport::inspect_host_key(
        &config,
        CancellationToken::new(),
    ))?;
    Ok(serde_json::to_string(&json!({"fingerprint": key}))?)
}

/// Profile fields for pasted Iroh text and an optional relay: the endpoint ID
/// that keys SSH trust, a ticket only when it adds addresses, and the relay in
/// canonical form. Parsing only, no network.
pub fn iroh_target(text: &str, relay: &str) -> Result<String> {
    let target = text.parse::<IrohTarget>()?.with_relay(relay)?;
    Ok(serde_json::to_string(&json!({
        "endpoint_id": target.endpoint_id(),
        "endpoint_ticket": target.ticket().unwrap_or_default(),
        "relay_url": target.relay_url().unwrap_or_default(),
    }))?)
}

/// A new device key made here, never sent anywhere: `private` (OpenSSH, for
/// the encrypted identity store) and `public` (the authorized_keys line).
pub fn generate_identity(comment: &str) -> Result<String> {
    if comment.is_empty() || comment.len() > 128 || comment.chars().any(char::is_control) {
        return Err(Error::Config);
    }
    let (private, public) = herdr_remote_transport::generate_ed25519(comment)?;
    Ok(serde_json::to_string(
        &json!({"private": private.as_str(), "public": public}),
    )?)
}

pub(crate) struct State {
    pub(crate) view: Value,
    pub(crate) terminal: Option<Arc<gpui_rio::Session>>,
    pub(crate) render_id: u64,
    /// What the phone keeps of the pane on screen itself (item 77, ask 2).
    pub(crate) local: Local,
}

/// The pane on screen, as the phone itself keeps it, for one attach stream
/// only: the copy being read back instead of the live grid, what the phone
/// watched of an agent's full screen, and when the live grid's first paint
/// is in. A new attach (a switch, a reattach after the stream ended, a new
/// connection) starts empty: nothing watched on one stream is ever read back
/// on another, since the phone cannot prove it is still the same terminal.
#[cfg_attr(not(any(feature = "android-ui", test)), allow(dead_code))]
#[derive(Default)]
pub(crate) struct Local {
    /// The phone's own copy scrolled instead of the live grid: its render
    /// id and terminal. Back at its newest row the live grid returns.
    reader: Option<(u64, Arc<gpui_rio::Session>)>,
    /// When the pane on screen attached, and the output count then.
    attached: Option<(std::time::Instant, u64)>,
    /// The output count at the last look, and when it last moved.
    output: u64,
    moved_at: Option<std::time::Instant>,
    /// The live grid has its first paint: until then whatever was drawn stays
    /// (the copy read offline, or the pane switched from), never a blank grid.
    settled: bool,
    /// The output count when the screen was last captured.
    captured: u64,
    /// A first drag is building the phone's copy (outside the session's
    /// lock): the rows dragged meanwhile, applied when it is shown.
    building: Option<i32>,
    /// The computer the UI shows this session as (the poll's host): the
    /// phone's copy of a pane carries it with the pane, for its links.
    host: String,
    /// Wheel reports sent to the program for drags past the top of the
    /// phone's copy (it scrolls its own view back): drags go to it until as
    /// many come back down, or it is at its newest row again.
    wheeled: i64,
    /// The terminal this stream is of, from the attach reply: its PTY's
    /// process and its created_ms. None: not reported, nothing is watched.
    incarnation: Option<Incarnation>,
    /// What the phone watched of the agent's full screen on this stream.
    watched: Option<Shared>,
    /// The pane list stopped agreeing with the attach (its process went
    /// missing or changed): what was watched is gone, and nothing is watched
    /// again on this stream, even if the same process id shows up again.
    lost: bool,
    /// The status marker and program last seen for the agent (see is_agent).
    seen: Option<(String, String)>,
}

impl Local {
    /// A pane just attached, with `output` bytes heard so far; `incarnation`
    /// from the attach reply's terminal record.
    pub(crate) fn attached(output: u64, incarnation: Option<Incarnation>) -> Self {
        Self {
            attached: Some((std::time::Instant::now(), output)),
            output,
            captured: output,
            incarnation,
            ..Self::default()
        }
    }

    /// The stream ended (the core's CLOSE): nothing watched on it is read back.
    pub(crate) fn end_stream(&mut self) {
        self.lose();
    }

    fn lose(&mut self) {
        self.reader = None;
        self.watched = None;
        self.lost = true;
        self.wheeled = 0;
    }
}

/// Which terminal an attach stream is of, from the attach reply's terminal
/// record: its PTY's process id (a new one on every spawn, kept recovery
/// included) and its created_ms.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) struct Incarnation {
    pid: u64,
    born: u64,
}

impl Incarnation {
    /// None when the record names no process.
    pub(crate) fn of(terminal: &Value) -> Option<Self> {
        let pid = terminal["pid"].as_u64().filter(|pid| *pid != 0)?;
        Some(Self { pid, born: terminal["created_ms"].as_u64().unwrap_or(0) })
    }

    /// Whether the pane list's entry still names this terminal.
    #[cfg(any(feature = "android-ui", test))]
    fn listed(self, entry: Option<&Value>) -> bool {
        entry.is_some_and(|e| e["pid"].as_u64() == Some(self.pid) && e["created_ms"].as_u64().unwrap_or(0) == self.born)
    }
}

/// What the phone watched of an agent's full screen on one attach stream:
/// the rows that left its top, and the last screen it looked at (live, never
/// the program's own scrolled view).
#[derive(Default)]
pub(crate) struct Watched {
    transcript: crate::transcript::Transcript,
    last: Option<crate::snapshot::Screen>,
}

impl Watched {
    /// Its copy: the rows, then the last screen watched. None before a look.
    fn rows(&self) -> Option<crate::snapshot::Rows> {
        self.last.as_ref().map(|last| crate::snapshot::Rows::of_transcript(last, &self.transcript))
    }
}

/// Shared by the poll (which adds to it) and the first drag or a save
/// (which copy it, off the session's lock).
pub(crate) type Shared = Arc<Mutex<Watched>>;

impl State {
    /// Whether the pane on screen runs an agent now (see is_agent).
    fn agent(&mut self) -> bool {
        let pane = self.view["pane_id"].as_str().unwrap_or("").to_owned();
        let Self { view, local, .. } = self;
        !pane.is_empty() && is_agent(view, &pane, &mut local.seen)
    }

    /// What was watched of the pane on screen, if it still runs the agent:
    /// whose copy is that pane's copy.
    pub(crate) fn agent_watched(&mut self) -> Option<Shared> {
        if self.local.lost || !self.agent() {
            return None;
        }
        self.local.watched.clone()
    }
}

#[cfg_attr(not(any(feature = "android-ui", test)), allow(dead_code))]
/// Quiet this long after its first bytes, a fresh attach is painted.
const SETTLE_QUIET: Duration = Duration::from_millis(150);
#[cfg_attr(not(any(feature = "android-ui", test)), allow(dead_code))]
/// At the latest the live grid is shown this long after its attach.
const SETTLE_MAX: Duration = Duration::from_millis(1500);
pub(crate) struct Session {
    /// This session's handle (the phone's id for it): a kept grid remembers
    /// which session showed it, since every core numbers its panes t1, t2…
    pub(crate) id: u64,
    pub(crate) state: Mutex<State>,
    pub(crate) cancel: CancellationToken,
    pub(crate) commands: mpsc::SyncSender<Operation>,
    /// The latest window switch asked for and not yet made. A slot, not the
    /// queue: a switch can never be refused because terminal output filled
    /// the queue, and quick taps collapse into the last one.
    pub(crate) focus: Mutex<Option<(String, std::time::Instant)>>,
    /// When herdr-core last sent any byte, for the phone's liveness check.
    pub(crate) heard: Heard,
    /// Whether the phone shows this session: only a visible viewer may size
    /// the pane's grid (core_session's `Claim`).
    pub(crate) visible: std::sync::atomic::AtomicBool,
    /// Wolf's uses of the phone this session reports (item 71): the phone's
    /// own [`USE`], which every session shares, since one person holds one
    /// phone (a host switched to just now was just used). Tests give each
    /// session its own.
    pub(crate) uses: &'static LastUse,
}

/// The last time bytes arrived, lock-free: the socket loop marks it on every
/// read and the phone's poll asks how long the link has been quiet. herdr-core
/// is refreshed every two seconds, so a long silence means the path is dead
/// (a subway tunnel), which TCP alone would report only after many seconds.
pub(crate) struct Heard {
    born: std::time::Instant,
    at_ms: AtomicU64,
    /// Bytes of pane output received so far (every pane this session showed):
    /// the phone checkpoints the pane on screen only while this moves.
    output: AtomicU64,
}
impl Default for Heard {
    fn default() -> Self {
        Self {
            born: std::time::Instant::now(),
            at_ms: AtomicU64::new(0),
            output: AtomicU64::new(0),
        }
    }
}
impl Heard {
    pub(crate) fn printed(&self, bytes: usize) {
        self.output.fetch_add(bytes as u64, Ordering::Relaxed);
    }
    /// Read by the UI's poll (and tests); a remote-only build has no reader.
    #[cfg(any(feature = "android-ui", test))]
    pub(crate) fn output(&self) -> u64 {
        self.output.load(Ordering::Relaxed)
    }
    /// The same count, for the session's own attach.
    pub(crate) fn output_now(&self) -> u64 {
        self.output.load(Ordering::Relaxed)
    }
    pub(crate) fn mark(&self) {
        let now = u64::try_from(self.born.elapsed().as_millis()).unwrap_or(u64::MAX);
        self.at_ms.store(now, Ordering::Relaxed);
    }
    /// Milliseconds since the last byte (or since the session began).
    pub(crate) fn quiet_ms(&self) -> u64 {
        let now = u64::try_from(self.born.elapsed().as_millis()).unwrap_or(u64::MAX);
        now.saturating_sub(self.at_ms.load(Ordering::Relaxed))
    }
}
pub(crate) enum Operation {
    Discover(mpsc::SyncSender<Result<String>>),
    Action(String, mpsc::SyncSender<Result<String>>),
    Frame(u32, u8, Vec<u8>),
}
static SESSIONS: OnceLock<Mutex<HashMap<u64, Arc<Session>>>> = OnceLock::new();
static NEXT: AtomicU64 = AtomicU64::new(1);
pub(crate) fn lock<T>(value: &Mutex<T>) -> MutexGuard<'_, T> {
    value
        .lock()
        .unwrap_or_else(std::sync::PoisonError::into_inner)
}
fn sessions() -> &'static Mutex<HashMap<u64, Arc<Session>>> {
    SESSIONS.get_or_init(Mutex::default)
}
fn session(id: u64) -> Result<Arc<Session>> {
    lock(sessions()).get(&id).cloned().ok_or(Error::Closed)
}
pub fn renderer(id: u64) -> Option<Arc<gpui_rio::Session>> {
    if let Some((cached, terminal)) = lock(cached()).as_ref()
        && *cached == id
    {
        return Some(terminal.clone());
    }
    if let Some(held) = lock(held()).as_ref()
        && held.id == id
    {
        return Some(held.terminal.clone());
    }
    for owner in lock(sessions()).values() {
        let state = lock(&owner.state);
        if state.render_id == id {
            return state.terminal.clone();
        }
        if let Some((reader, terminal)) = &state.local.reader
            && *reader == id
        {
            return Some(terminal.clone());
        }
    }
    None
}
pub fn connect_credentials(raw: &str, credentials: &str) -> Result<u64> {
    // No pane is attached (no grid claimed) until one is focused.
    connect_credentials_grid(raw, credentials, "80,24,8,16", false)
}
pub fn connect_credentials_grid(
    raw: &str,
    credentials: &str,
    grid: &str,
    active: bool,
) -> Result<u64> {
    let (config, _) = parse_config(raw)?;
    let credentials = parse_credentials(credentials)?;
    let parts: Vec<u16> = grid.split(',').filter_map(|s| s.parse().ok()).collect();
    let (cols, rows) = if parts.len() == 4 {
        (parts[0].clamp(20, 400), parts[1].clamp(5, 200))
    } else {
        (80, 24)
    };
    let mut all = lock(sessions());
    if all.len() >= 8 {
        return Err(Error::Capacity);
    }
    let id = NEXT.fetch_add(1, Ordering::Relaxed);
    // Terminal output acknowledges every read through this queue; a busy
    // pane overran 256 while a slow refresh held the session thread.
    let (tx, rx) = mpsc::sync_channel(4096);
    let owner = Arc::new(Session {
        id,
        state: Mutex::new(State {
            view: json!({"state":"connecting","status":"Connecting to herdr-core over SSH","boot_id":"","pane_id":"","focused_pane":"","panes":[],"workspaces":[],"tabs":[],"title":"Herdr Core","token":1,"chrome_token":1,"revision":1,"fetch":null,"scroll":{"offset":0,"max":0,"rows":0}}),
            terminal: None,
            render_id: 0,
            local: Default::default(),
        }),
        cancel: CancellationToken::new(),
        commands: tx,
        focus: Mutex::new(None),
        heard: Heard::default(),
        visible: std::sync::atomic::AtomicBool::new(true),
        uses: &USE,
    });
    let worker = owner.clone();
    std::thread::Builder::new()
        .name("herdr-core-session".into())
        .spawn(move || {
            if let Err(error) = run(&worker, config, credentials, cols, rows, active, rx) {
                let mut state = lock(&worker.state);
                state.view["state"] = json!("closed");
                state.view["status"] = json!(error.to_string());
                bump(&mut state.view);
            }
        })?;
    all.insert(id, owner);
    Ok(id)
}
pub(crate) fn bump(view: &mut Value) {
    let revision = view["revision"].as_u64().unwrap_or(0) + 1;
    view["revision"] = json!(revision);
    view["token"] = json!(revision);
    view["chrome_token"] = json!(revision);
}
/// The phone shows (true) or hides (false) this session. Hidden, it sends no
/// RESIZE and gives up its claim on the pane's grid; shown, it offers a fresh
/// measured size first.
pub fn set_visible(id: u64, visible: bool) -> Result<()> {
    session(id)?
        .visible
        .store(visible, Ordering::Relaxed);
    Ok(())
}
/// Wolf just used the phone (item 71, latest-touched device wins): a touch
/// anywhere in Herdr, a key, Herdr coming to the front, the keyboard showing,
/// a fold or unfold. The session on screen tells its core at once (at most
/// once a second), and the next attach says how long ago this was.
pub fn used() {
    USE.mark();
}
/// The phone's last use, process-wide: whatever Wolf touched, the pane on
/// screen is the one he chose.
pub(crate) struct LastUse {
    born: OnceLock<std::time::Instant>,
    /// Milliseconds after `born` of the last use, plus one (0: never).
    at_ms: AtomicU64,
    /// Uses so far: a session sees a new one by a change.
    count: AtomicU64,
}
impl LastUse {
    pub(crate) const fn new() -> Self {
        Self {
            born: OnceLock::new(),
            at_ms: AtomicU64::new(0),
            count: AtomicU64::new(0),
        }
    }
    fn now_ms(&self) -> u64 {
        let born = self.born.get_or_init(std::time::Instant::now);
        u64::try_from(born.elapsed().as_millis()).unwrap_or(u64::MAX)
    }
    pub(crate) fn mark(&self) {
        let now = self.now_ms();
        self.at_ms.store(now.saturating_add(1), Ordering::Relaxed);
        self.count.fetch_add(1, Ordering::Relaxed);
    }
    pub(crate) fn count(&self) -> u64 {
        self.count.load(Ordering::Relaxed)
    }
    /// Milliseconds since the last use, the true age however old (the core
    /// orders devices by it); [`NEVER_USED_MS`] when there was none: the
    /// attach's `idle_ms`.
    pub(crate) fn idle_ms(&self) -> u64 {
        let now = self.now_ms();
        match self.at_ms.load(Ordering::Relaxed) {
            0 => NEVER_USED_MS,
            at => now.saturating_sub(at - 1),
        }
    }
}
/// `idle_ms` for a phone never used since Herdr started: older than any
/// core's uptime, so the core gives it no focus history (t1x1), and older
/// than any real age, so it never sorts ahead of one. i64::MAX, so it reads
/// the same as a signed or an unsigned number.
pub(crate) const NEVER_USED_MS: u64 = i64::MAX as u64;
pub(crate) static USE: LastUse = LastUse::new();
pub fn close(id: u64) {
    let Some(owner) = lock(sessions()).remove(&id) else {
        return;
    };
    owner.cancel.cancel();
    // The phone keeps this terminal's last frame on screen while it reconnects
    // (Mailbox::hold): keep its grid too, so the conversation still scrolls.
    let mut state = lock(&owner.state);
    let reading = state.local.reader.take();
    let watched = state.agent_watched();
    if let Some(terminal) = state.terminal.take() {
        let pane = state.view["pane_id"].as_str().unwrap_or("").to_owned();
        // This grid is newer than any copy of the same pane kept on a switch.
        lock(departed()).retain(|kept| !kept.from(id, &pane));
        *lock(closed()) = Some(Kept { session: id, terminal: terminal.clone(), pane, watched: watched.clone() });
        let shown = state.render_id;
        drop(state);
        match reading {
            // Being read back already: it stays, at the same row.
            Some((reader, copy)) => hold_as(reader, copy),
            None => {
                // The live grid at once; this runs on the UI thread.
                hold_as(shown, terminal);
                if let Some(watched) = watched {
                    // An agent's full screen: the phone's copy of it replaces
                    // that frame once it is built, so the conversation still
                    // scrolls with no link (not one screen).
                    let slot = held();
                    std::thread::spawn(move || {
                        let Some(rows) = lock(&watched).rows() else {
                            return;
                        };
                        let Some(copy) = read_back(rows, "") else {
                            return;
                        };
                        let mut held = lock(slot);
                        if held.as_ref().is_some_and(|h| h.id == shown) {
                            let reader = crate::core_session::next_render_id();
                            *held = Some(Held { id: reader, terminal: copy });
                            drop(held);
                            show_render(None, reader, Some(shown), None);
                        }
                    });
                }
            }
        }
    }
}

fn pane_entry<'a>(view: &'a Value, pane: &str) -> Option<&'a Value> {
    view["panes"].as_array().and_then(|panes| panes.iter().find(|p| p["id"] == pane))
}

/// Whether `pane` runs an agent (Claude Code, Codex): one that reports its
/// status, or that Herdr labelled an agent, and is still the program that
/// did. `seen` is the status marker and program this attach last saw for
/// it: a status reported again (a new marker) shows the agent is what runs
/// now; the same marker with another program is that program (a pager after
/// the agent left) under the agent's old status. Only an agent's full screen
/// is a conversation the phone keeps and reads back itself; any other
/// full-screen program (vim, less, htop) scrolls itself, with the drag as
/// wheel input.
fn is_agent(view: &Value, pane: &str, seen: &mut Option<(String, String)>) -> bool {
    let Some(entry) = pane_entry(view, pane) else {
        return false;
    };
    let reports = entry["status"].as_str().is_some_and(|s| !s.is_empty());
    let labelled = entry["kind"].as_str().is_some_and(|k| !k.is_empty());
    if !reports && !labelled {
        return false;
    }
    let program = entry["program"].as_str().unwrap_or("").to_owned();
    let marker = marker(view, pane);
    match seen {
        Some((at, was)) if *at == marker && *was != program => false,
        Some((at, _)) if *at == marker => true,
        _ => {
            *seen = Some((marker, program));
            true
        }
    }
}
/// Draws render id `id` (for `generation`, else the UI's current one), only
/// while `instead_of` is drawn when given. It shows `target` (host, pane);
/// with none, the same as the frame it replaces (the phone's copy of that
/// pane), so links found on it keep their target. Nothing without a UI.
#[cfg(feature = "android-ui")]
fn show_render(generation: Option<i64>, id: u64, instead_of: Option<u64>, target: Option<(&str, &str)>) {
    let mut mailbox = model::mailbox();
    let now = mailbox.state();
    if instead_of.is_some_and(|old| now.shell != old) {
        return;
    }
    let target = match target {
        Some((host, pane)) => Some(model::FrameTarget::new(host, pane)),
        None => now.frame_target.clone(),
    };
    mailbox.set_shell(generation.unwrap_or(now.generation), id, target);
}
#[cfg(not(feature = "android-ui"))]
fn show_render(_generation: Option<i64>, _id: u64, _instead_of: Option<u64>, _target: Option<(&str, &str)>) {}
/// Most grids of switched-away panes kept at once, each until the phone saves
/// it. A grid is at most the phone's scrollback (10,000 rows), so this bounds
/// what a quick run of switches can keep alive to two such grids.
pub(crate) const DEPARTED_MAX: usize = 2;
/// Grids of panes a session just switched away from, oldest first: an attach
/// drops its outgoing grid, and the phone saves it from here on its own thread
/// (maybe after the next attach), so a pane looked at online can be read offline.
fn departed() -> &'static Mutex<std::collections::VecDeque<Kept>> {
    static DEPARTED: OnceLock<Mutex<std::collections::VecDeque<Kept>>> = OnceLock::new();
    DEPARTED.get_or_init(Mutex::default)
}
/// Session `session` is replacing `pane`'s grid with another pane's: keep it
/// for saving. Only the grid object moves here; nothing is sent and no size
/// changes.
#[cfg(test)]
pub(crate) fn depart(session: u64, terminal: Arc<gpui_rio::Session>, pane: String, _boot: String) {
    depart_as(session, terminal, pane, None);
}
/// [`depart`], with what was watched of the agent it ran, if it ran one
/// (its copy is that, not its grid).
pub(crate) fn depart_as(session: u64, terminal: Arc<gpui_rio::Session>, pane: String, watched: Option<Shared>) {
    if pane.is_empty() {
        return;
    }
    {
        // A copy kept from an earlier close of the same session's pane is older.
        let mut closed = lock(closed());
        if closed.as_ref().is_some_and(|kept| kept.from(session, &pane)) {
            closed.take();
        }
    }
    let mut kept = lock(departed());
    kept.retain(|other| !other.from(session, &pane));
    kept.push_back(Kept { session, terminal, pane, watched });
    while kept.len() > DEPARTED_MAX {
        kept.pop_front();
    }
}
#[cfg(test)]
pub(crate) fn departed_holds(terminal: &Arc<gpui_rio::Session>) -> bool {
    lock(departed()).iter().any(|kept| Arc::ptr_eq(&kept.terminal, terminal))
}
/// Serializes the tests that count what the shared departed slot holds.
#[cfg(test)]
pub(crate) fn departed_test_lock() -> MutexGuard<'static, ()> {
    static LOCK: Mutex<()> = Mutex::new(());
    lock(&LOCK)
}
/// The last closed session's grid, kept for saving even after a new pane
/// attaches (the save runs on a worker, maybe after that attach).
#[cfg(test)]
pub(crate) fn closed_for_test(session: u64, terminal: Arc<gpui_rio::Session>, pane: &str) {
    *lock(closed()) = Some(Kept { session, terminal, pane: pane.into(), watched: None });
}
fn closed() -> &'static Mutex<Option<Kept>> {
    static CLOSED: OnceLock<Mutex<Option<Kept>>> = OnceLock::new();
    CLOSED.get_or_init(Mutex::default)
}
/// A grid kept for saving after its session stopped showing it, with the
/// session that showed it: pane ids repeat across computers (each core has
/// its own t1), so a save names the session too.
struct Kept {
    session: u64,
    terminal: Arc<gpui_rio::Session>,
    pane: String,
    /// What was watched of the agent it ran, when it ran one: its copy.
    watched: Option<Shared>,
}
impl Kept {
    fn from(&self, session: u64, pane: &str) -> bool {
        self.session == session && self.pane == pane
    }
}
#[cfg(test)]
pub(crate) fn hold(terminal: Arc<gpui_rio::Session>) {
    hold_as(crate::core_session::next_render_id(), terminal);
}
/// Makes `terminal` the held frame, drawn under render id `id`.
fn hold_as(id: u64, terminal: Arc<gpui_rio::Session>) {
    *lock(held()) = Some(Held { id, terminal });
}
/// The grid the held frame draws.
#[cfg_attr(not(any(feature = "android-ui", test)), allow(dead_code))]
struct Held {
    id: u64,
    terminal: Arc<gpui_rio::Session>,
}
/// The terminal of the last closed session (or a saved pane read back), which
/// the held frame draws.
#[cfg(not(test))]
fn held() -> &'static Mutex<Option<Held>> {
    static HELD: OnceLock<Mutex<Option<Held>>> = OnceLock::new();
    HELD.get_or_init(Mutex::default)
}
/// In tests the held frame is per thread: every test runs on its own
/// thread, so another test's attach (on its own session thread) can never
/// release this test's held frame.
#[cfg(test)]
fn held() -> &'static Mutex<Option<Held>> {
    thread_local! {
        static HELD: &'static Mutex<Option<Held>> = Box::leak(Box::default());
    }
    HELD.with(|held| *held)
}
/// A saved pane being drawn with no session: its render id and terminal.
type Cached = Option<(u64, Arc<gpui_rio::Session>)>;
#[cfg(not(test))]
fn cached() -> &'static Mutex<Cached> {
    static CACHED: OnceLock<Mutex<Cached>> = OnceLock::new();
    CACHED.get_or_init(Mutex::default)
}
/// Per thread in tests, like [`held`].
#[cfg(test)]
fn cached() -> &'static Mutex<Cached> {
    thread_local! {
        static CACHED: &'static Mutex<Cached> = Box::leak(Box::default());
    }
    CACHED.with(|cached| *cached)
}
/// A saved pane (or an uncached pane's note) read and not drawn yet: the
/// phone first checks it is still the pick ([`show_opened`]).
#[cfg(not(test))]
fn opened() -> &'static Mutex<Cached> {
    static OPENED: OnceLock<Mutex<Cached>> = OnceLock::new();
    OPENED.get_or_init(Mutex::default)
}
/// Per thread in tests, like [`held`].
#[cfg(test)]
fn opened() -> &'static Mutex<Cached> {
    thread_local! {
        static OPENED: &'static Mutex<Cached> = Box::leak(Box::default());
    }
    OPENED.with(|opened| *opened)
}
/// A new pane attached: its live grid is the one to scroll and draw now.
#[cfg(test)]
pub(crate) fn release_held() {
    lock(held()).take();
    lock(cached()).take();
    lock(opened()).take();
}

/// The live grid of a fresh attach is shown once its first paint is in (its
/// bytes went quiet) or SETTLE_MAX passed, never blank before. Then the held
/// frame (the last one, a saved pane, a copy being read) lets go: nothing
/// read on another stream is carried onto this one.
#[cfg(any(feature = "android-ui", test))]
pub(crate) fn settle(owner: &Session, state: &mut State) -> bool {
    let local = &mut state.local;
    if local.settled {
        return true;
    }
    let Some((at, start)) = local.attached else {
        return false;
    };
    let now = std::time::Instant::now();
    let output = owner.heard.output();
    if output != local.output {
        local.output = output;
        local.moved_at = Some(now);
    }
    let painted = output != start && local.moved_at.is_some_and(|moved| now - moved >= SETTLE_QUIET);
    if !painted && now - at < SETTLE_MAX {
        return false;
    }
    local.settled = true;
    lock(held()).take();
    lock(cached()).take();
    true
}

/// Draws `rows` (a copy) in a terminal of its own at the newest row, for
/// reading back. Encodes and parses with no lock held.
fn read_back(rows: crate::snapshot::Rows, pane: &str) -> Option<Arc<gpui_rio::Session>> {
    let (cols, lines) = (u16::try_from(rows.cols).ok()?, u16::try_from(rows.lines).ok()?);
    let (bytes, _) = rows.encode(crate::snapshot::MAX_BYTES);
    let reader = crate::snapshot::restore(crate::snapshot::Saved::of(cols, lines, pane, bytes)).ok()?;
    // Parsed on its own thread: the first drag scrolls a whole copy.
    let deadline = std::time::Instant::now() + Duration::from_secs(3);
    while reader.is_alive() && std::time::Instant::now() < deadline {
        std::thread::sleep(Duration::from_millis(2));
    }
    Some(reader)
}

/// The phone's copy of an agent's full screen to read back: what it
/// watched, then the screen now, looked at first so the two join without a
/// row missed or doubled. None when `terminal` is not full screen or nothing
/// was watched yet. The transcript's lock, then the grid's (only to copy
/// the screen), in the poll's order; never under the session's lock.
fn reader_for(watched: &Shared, terminal: &gpui_rio::Session, pane: &str) -> Option<Arc<gpui_rio::Session>> {
    let rows = {
        let mut watched = lock(watched);
        if watched.transcript.rows() == 0 {
            return None;
        }
        let screen = crate::snapshot::Screen::of(&terminal.grid().lock())?;
        watched.transcript.observe(screen.rows.clone(), screen.cols);
        watched.last = Some(screen);
        watched.rows()?
    };
    read_back(rows, pane)
}

/// The pane's status marker as the list has it: it moves whenever the
/// program reports again.
fn marker(view: &Value, pane: &str) -> String {
    let Some(entry) = pane_entry(view, pane) else {
        return String::new();
    };
    format!("{}/{}/{}", entry["status"].as_str().unwrap_or(""), entry["status_ms"], entry["turn"])
}

/// Keeps what the phone watches of the agent's full screen on screen: each
/// new screen is aligned with the last one and the rows that left its top
/// are kept. Only while the pane list agrees with the attach on which
/// terminal this is; once it does not, everything watched on this stream is
/// dropped, for good. Nothing is asked of the core.
#[cfg(any(feature = "android-ui", test))]
pub(crate) fn keep_local(owner: &Session, state: &mut State) {
    if !state.local.settled || state.local.lost {
        return;
    }
    let Some(terminal) = state.terminal.clone() else {
        return;
    };
    let pane = state.view["pane_id"].as_str().unwrap_or("").to_owned();
    let listed = state.local.incarnation.is_some_and(|me| me.listed(pane_entry(&state.view, &pane)));
    if !listed {
        let reading = state.local.reader.is_some();
        state.local.lose();
        if reading {
            show_render(None, state.render_id, None, None);
        }
        return;
    }
    // Only an agent's conversation is kept (see is_agent).
    if !state.agent() {
        return;
    }
    let output = owner.heard.output();
    // Handed back to the program's own scrolling: its view is its history,
    // not new rows; looked at again once it is back at its newest row.
    if output == state.local.captured || state.local.wheeled > 0 {
        return;
    }
    let shared = state.local.watched.get_or_insert_with(Shared::default).clone();
    // The poll never waits while a copy is being taken (a first drag, a
    // save): this look is skipped, and the next poll looks again.
    let mut watched = match shared.try_lock() {
        Ok(watched) => watched,
        Err(std::sync::TryLockError::Poisoned(poisoned)) => poisoned.into_inner(),
        Err(std::sync::TryLockError::WouldBlock) => return,
    };
    let Some(screen) = crate::snapshot::Screen::of(&terminal.grid().lock()) else {
        return;
    };
    state.local.captured = output;
    watched.transcript.observe(screen.rows.clone(), screen.cols);
    watched.last = Some(screen);
}

#[cfg(test)]
pub(crate) fn transcript_rows(owner: &Session) -> Option<Vec<String>> {
    let watched = lock(&owner.state).local.watched.clone()?;
    let watched = lock(&watched);
    let last = watched.last.as_ref()?;
    Some(watched.transcript.reader_rows(&last.rows).map(|r| r.text.clone()).collect())
}
/// `pane`'s copy, as session `id` last showed it: the phone's copy of that
/// conversation, for reading it later with no link (the phone seals it and
/// writes it). The grid is the newest that session left: its live one while
/// it shows `pane`, else the one kept when a switch left it (freed once
/// copied), else the one kept when it closed. Grids of other sessions never
/// count, even for the same pane id. An agent's full screen's copy is what
/// the phone watched of it, then the last screen it watched (never the
/// program's own scrolled view); a save changes nothing of what is watched.
/// Only rows are copied under the locks; formatting and the file are built
/// with none held, never on the UI thread.
pub fn copy_pane(id: u64, pane: &str) -> Result<Option<Vec<u8>>> {
    if pane.is_empty() {
        return Ok(None);
    }
    let live = lock(sessions()).get(&id).cloned().and_then(|owner| {
        let mut state = lock(&owner.state);
        if state.view["pane_id"] == pane {
            let watched = state.agent_watched();
            state.terminal.clone().map(|terminal| (terminal, watched))
        } else {
            None
        }
    });
    let switched = || {
        let mut kept = lock(departed());
        let at = kept.iter().position(|kept| kept.from(id, pane))?;
        kept.remove(at).map(|kept| (kept.terminal, kept.watched))
    };
    let last_closed = || {
        lock(closed()).as_ref().filter(|kept| kept.from(id, pane)).map(|kept| (kept.terminal.clone(), kept.watched.clone()))
    };
    let Some((terminal, watched)) = live.or_else(switched).or_else(last_closed) else {
        return Ok(None);
    };
    let watched = watched.and_then(|watched| lock(&watched).rows());
    let rows = match watched {
        Some(rows) => rows,
        None => {
            // The grid guard drops before the owned cells are formatted.
            let cells = crate::snapshot::Cells::of(&terminal.grid().lock());
            cells.rows()
        }
    };
    Ok(Some(rows.file(pane)?))
}
/// Test stand-in for the phone's save: [`copy_pane`] written to `path`.
#[cfg(test)]
pub(crate) fn save_pane(id: u64, pane: &str, path: &str) -> Result<bool> {
    let Some(copy) = copy_pane(id, pane)? else {
        return Ok(false);
    };
    crate::snapshot::write(&copy, std::path::Path::new(path))?;
    Ok(true)
}
/// Test stand-in for the phone's open: [`open_saved`] of `path`'s bytes, drawn.
#[cfg(test)]
pub(crate) fn open_saved_file(path: &str) -> Result<u64> {
    let id = open_saved(std::fs::read(path)?)?;
    show_opened(id);
    Ok(id)
}
/// Reads a saved pane (its file's bytes, opened by the phone) into a
/// terminal of its own, drawn under a new render id (returned) once the
/// phone has checked it is still the pick ([`show_opened`]); then it is the
/// held frame until a live pane attaches. It is for reading with no link
/// only: nothing in it is ever joined to a live pane. Never on the UI thread.
pub fn open_saved(bytes: Vec<u8>) -> Result<u64> {
    let terminal = crate::snapshot::restore(crate::snapshot::parse(bytes)?)?;
    let id = crate::core_session::next_render_id();
    *lock(opened()) = Some((id, terminal));
    Ok(id)
}
/// Makes `pane`'s stand-in when it has no saved copy: `title` and one dim
/// `note` line on a `cols`×`rows` grid, drawn under a new render id
/// (returned) once shown ([`show_opened`]). Never touches a session or the disk.
pub fn hold_note(pane: &str, title: &str, note: &str, cols: u16, rows: u16) -> Result<u64> {
    let terminal = crate::snapshot::restore(crate::snapshot::Saved::of(
        cols.clamp(20, 400),
        rows.clamp(5, 200),
        pane,
        crate::snapshot::note(title, note),
    ))?;
    let id = crate::core_session::next_render_id();
    *lock(opened()) = Some((id, terminal));
    Ok(id)
}
/// The phone checked the pane opened as `id` is still the pick: it becomes
/// the held frame (until a live pane attaches). False when a later open
/// replaced it (nothing changes then).
pub fn show_opened(id: u64) -> bool {
    let Some((opened, terminal)) = lock(opened()).take_if(|(opened, _)| *opened == id) else {
        return false;
    };
    *lock(cached()) = Some((opened, terminal.clone()));
    hold_as(opened, terminal);
    true
}
/// Scrolls the held frame's own history, with no link: `delta` rows back
/// (positive) or forward, or back to the newest line when `latest`. Never sends
/// anything, so even a program that takes wheel input is scrolled locally.
/// `{"local_offset": rows back from the newest}`; 0 when nothing is held.
pub fn scroll_held(delta: i64, latest: bool) -> String {
    let offset = lock(held()).as_ref().map_or(0, |held| {
        let mut grid = held.terminal.grid().lock();
        let scroll = if latest {
            rio_vt::crosswords::grid::Scroll::Bottom
        } else {
            rio_vt::crosswords::grid::Scroll::Delta(delta.clamp(-1000, 1000) as i32)
        };
        grid.scroll_display(scroll);
        grid.display_offset()
    });
    json!({"local_offset": offset}).to_string()
}
pub fn poll(id: u64, _since: u64, _mirror: bool) -> Result<String> {
    let owner = session(id)?;
    let mut view = lock(&owner.state).view.clone();
    view["quiet_ms"] = json!(owner.heard.quiet_ms());
    Ok(view.to_string())
}
#[cfg(feature = "android-ui")]
pub fn poll_terminal(
    id: u64,
    since: u64,
    _chrome: u64,
    generation: i64,
    host: &str,
    _shortcuts: &str,
) -> Result<String> {
    if generation <= 0 || model::mailbox().state().generation != generation {
        return Err(Error::StaleTarget);
    }
    let owner = session(id)?;
    let mut state = lock(&owner.state);
    if state.local.host != host {
        state.local.host = host.to_owned();
    }
    let settled = settle(&owner, &mut state);
    keep_local(&owner, &mut state);
    {
        let mut mailbox = model::mailbox();
        // Before its pane attaches and paints, a session draws nothing new: a
        // held frame (the last one, or a saved pane) or the pane switched from
        // stays until there is something live to show (item 77: a reconnect
        // never blanks the screen). The renderer is recorded with the host
        // and pane it was attached for, under the same lock that installs it.
        let attaching = state.view["state"] == "connecting";
        if state.render_id != 0 && settled {
            let shown = state.local.reader.as_ref().map_or(state.render_id, |(reader, _)| *reader);
            let pane = state.view["pane_id"].as_str().unwrap_or("");
            mailbox.set_shell(generation, shown, Some(model::FrameTarget::new(host, pane)));
        } else if state.render_id == 0 && !attaching && !mailbox.state().held {
            mailbox.set_shell(generation, 0, None);
        }
    }
    // How far the local history is scrolled back, for a "jump to latest" control.
    let local_offset = match &state.local.reader {
        Some((_, reader)) => reader.grid().lock().display_offset(),
        None => state.terminal.as_ref().map_or(0, |terminal| terminal.grid().lock().display_offset()),
    };
    let view = &state.view;
    if since != 0 && view["token"].as_u64() == Some(since) {
        // The phone polls ten times a second; resending (and reparsing) the
        // whole pane tree each time, under the activity's state lock, starved
        // its UI thread while agents were busy. Only what moves without a
        // revision goes back.
        return Ok(json!({
            "unchanged": true,
            "state": view["state"],
            "pane_id": view["pane_id"],
            "boot_id": view["boot_id"],
            "scroll": view["scroll"],
            "fetch": view["fetch"],
            "local_offset": local_offset,
            "quiet_ms": owner.heard.quiet_ms(),
            "output": owner.heard.output(),
        })
        .to_string());
    }
    let mut view = view.clone();
    view["local_offset"] = json!(local_offset);
    view["quiet_ms"] = json!(owner.heard.quiet_ms());
    view["output"] = json!(owner.heard.output());
    Ok(view.to_string())
}
pub fn focus(id: u64, terminal: &str) -> Result<String> {
    if terminal.is_empty() || terminal.len() > 128 {
        return Err(Error::StaleTarget);
    }
    let owner = session(id)?;
    // Not checked against the view: the phone's list may be newer or older
    // than this copy. A window that is gone fails its attach, which the
    // session recovers from by staying on the window it was showing.
    *lock(&owner.focus) = Some((terminal.into(), std::time::Instant::now()));
    Ok(json!({"queued":true}).to_string())
}
pub fn focus_tab(id: u64, tab: &str) -> Result<String> {
    focus_group(id, "tab_id", tab)
}
pub fn focus_workspace(id: u64, space: &str) -> Result<String> {
    focus_group(id, "workspace_id", space)
}
fn focus_group(id: u64, field: &str, value: &str) -> Result<String> {
    let owner = session(id)?;
    let target = lock(&owner.state).view["panes"]
        .as_array()
        .and_then(|p| p.iter().find(|p| p[field] == value))
        .and_then(|p| p["id"].as_str())
        .map(str::to_owned)
        .ok_or(Error::StaleTarget)?;
    focus(id, &target)
}
pub fn input(id: u64, terminal: &str, raw: &str) -> Result<String> {
    let owner = session(id)?;
    let state = lock(&owner.state);
    if state.view["pane_id"] != terminal || state.view["state"] != "ready" {
        return Err(Error::StaleTarget);
    }
    let terminal = state.terminal.as_ref().ok_or(Error::StaleTarget)?;
    let sent = crate::shell::send(terminal, &model::parse_command(raw)?);
    Ok(json!({"queued":sent}).to_string())
}
/// Create a shell using herdr-app labels, or end the selected terminal.
pub fn session_action(id: u64, action: &str) -> Result<String> {
    if !matches!(
        action,
        "tab.new" | "agent.claude" | "agent.codex" | "pane.close"
    ) && crate::core_session::TabEdit::parse(action).is_none()
    {
        return Err(Error::Config);
    }
    let owner = session(id)?;
    let (tx, rx) = mpsc::sync_channel(1);
    owner
        .commands
        .try_send(Operation::Action(action.into(), tx))
        .map_err(|_| Error::Capacity)?;
    rx.recv_timeout(Duration::from_secs(20))
        .map_err(|_| Error::Closed)?
}
/// Sends one outbox message with `terminal.send`, which pastes it and presses
/// Enter, refusing a program that waits on a dialog. Blocks the caller (the
/// outbox's own thread) until the core answers. `{"outcome": ..}` is `sent`,
/// `blocked`, `gone`, `unsure` (it may have been typed: never resend it
/// unasked), or `retry` (it never left the phone: the session was gone or full,
/// or closed before taking the request).
pub fn deliver(id: u64, terminal: &str, text: &str) -> Result<String> {
    let action = json!({"op":"pane.deliver","terminal":terminal,"text":text}).to_string();
    if crate::core_session::TabEdit::parse(&action).is_none() {
        return Err(Error::Input(crate::Error::InvalidText));
    }
    let retry = || json!({"outcome":"retry"}).to_string();
    let Ok(owner) = session(id) else {
        return Ok(retry());
    };
    let (tx, rx) = mpsc::sync_channel(1);
    if owner.commands.try_send(Operation::Action(action, tx)).is_err() {
        return Ok(retry());
    }
    let wait = crate::core_session::DELIVER_WAIT + Duration::from_secs(15);
    Ok(match rx.recv_timeout(wait) {
        Ok(Ok(reply)) => reply,
        // The connection answered with an error after taking the request.
        Ok(Err(error)) => json!({"outcome":"unsure","why":error.to_string()}).to_string(),
        // Taken but unanswered: it may be typing right now.
        Err(mpsc::RecvTimeoutError::Timeout) => json!({"outcome":"unsure","why":"no answer"}).to_string(),
        // The worker dropped the request without running it (an action
        // always replies once it ran), so nothing was sent.
        Err(mpsc::RecvTimeoutError::Disconnected) => retry(),
    })
}
pub struct UiPaint<'a> {
    pub generation: i64,
    pub title: &'a str,
    pub status: &'a str,
    pub shortcuts: &'a str,
}
pub fn scroll_local(id: u64, delta: i64, paint: UiPaint<'_>) -> Result<String> {
    let owner = session(id)?;
    let delta = delta.clamp(-1000, 1000) as i32;
    let scroll = rio_vt::crosswords::grid::Scroll::Delta(delta);
    let _ = (paint.title, paint.status, paint.shortcuts);
    let local = || Ok(json!({"queued":true,"local":true}).to_string());
    let wheel = || Ok(json!({"queued":true}).to_string());
    let (terminal, watched, render_id) = {
        let mut state = lock(&owner.state);
        // Being read back: the phone's own copy scrolls, nothing is sent; at
        // its newest row the live grid is drawn again.
        if let Some((_, reader)) = state.local.reader.clone() {
            let (before, after) = {
                let mut grid = reader.grid().lock();
                let before = grid.display_offset();
                grid.scroll_display(scroll);
                (before, grid.display_offset())
            };
            if after == 0 && delta < 0 {
                state.local.reader = None;
                show_render(Some(paint.generation), state.render_id, None, None);
            } else if delta > 0 {
                // Past the top of what the phone has: the rest of the drag
                // goes on in the program's own view (wheel input).
                let rest = delta - i32::try_from(after.saturating_sub(before)).unwrap_or(delta);
                if rest > 0 {
                    hand_back(&mut state, rest, paint.generation);
                    return wheel();
                }
            }
            return local();
        }
        // The copy is being built: these rows are applied when it is shown.
        if let Some(pending) = &mut state.local.building {
            *pending = pending.saturating_add(delta);
            return local();
        }
        let Some(terminal) = state.terminal.clone() else {
            return wheel();
        };
        // Handed back to the program's own scrolling: it gets the drags until
        // they bring its view back down, or it is at its newest row already.
        if state.local.wheeled > 0 {
            if delta < 0 && at_newest(&state, &terminal) {
                state.local.wheeled = 0;
                return local();
            }
            let sent = wheel_reports(&terminal, delta);
            state.local.wheeled = (state.local.wheeled + sent).max(0);
            return wheel();
        }
        // A full-screen agent (Claude Code) keeps its history to itself:
        // dragging back scrolls the phone's copy of what it watched, at once
        // and with no round trip (item 77, ask 2). Without one yet, it gets
        // wheel input as before; plain output scrolls the attach's history.
        let watched = if delta > 0 { state.agent_watched() } else { None };
        if watched.is_some() {
            state.local.building = Some(0);
        }
        (terminal, watched, state.render_id)
    };
    if let Some(watched) = watched {
        let pane = lock(&owner.state).view["pane_id"].as_str().unwrap_or("").to_owned();
        // Built with the session's lock released: the poll (and the UI
        // waiting on it) never waits for the copy to be encoded and parsed.
        let built = reader_for(&watched, &terminal, &pane);
        // Scrolled before the session's lock is taken again: its grid can
        // still be busy with the copy's last bytes.
        let rest = built.as_ref().map(|reader| {
            let mut grid = reader.grid().lock();
            grid.scroll_display(scroll);
            delta - i32::try_from(grid.display_offset()).unwrap_or(delta)
        });
        let mut state = lock(&owner.state);
        // A later attach has its own Local: this build's state is not its.
        if state.render_id != render_id {
            return local();
        }
        let pending = state.local.building.take().unwrap_or(0);
        if state.local.reader.is_some() || state.terminal.is_none() {
            return local();
        }
        if let (Some(reader), Some(rest)) = (built, rest) {
            if rest > 0 {
                // Longer than the whole copy: the program's own view goes on.
                hand_back(&mut state, rest.saturating_add(pending.max(0)), paint.generation);
                return wheel();
            }
            let shown = crate::core_session::next_render_id();
            let host = state.local.host.clone();
            state.local.reader = Some((shown, reader));
            show_render(Some(paint.generation), shown, None, Some((&host, &pane)));
            drop(state);
            // The rows dragged while it was built.
            if pending != 0 {
                return scroll_local(id, i64::from(pending), paint);
            }
            return local();
        }
    }
    if !terminal.wheel(delta) {
        terminal.grid().lock().scroll_display(scroll);
    }
    wheel()
}

/// Leaves the phone's copy for the program's own view of the agent (the
/// live grid), and sends it `rows` more to scroll back.
fn hand_back(state: &mut State, rows: i32, generation: i64) {
    state.local.reader = None;
    show_render(Some(generation), state.render_id, None, None);
    if let Some(terminal) = state.terminal.clone() {
        state.local.wheeled = wheel_reports(&terminal, rows).max(0);
    }
}

/// Sends `rows` of wheel input (positive: back); the reports really sent,
/// signed (at most 30 a drag), 0 when the program takes none.
fn wheel_reports(terminal: &gpui_rio::Session, rows: i32) -> i64 {
    if rows == 0 || !terminal.wheel(rows) {
        return 0;
    }
    i64::from(rows.signum()) * i64::from(rows.unsigned_abs().min(30))
}

/// Whether the program's own view is at its newest row: its screen is the
/// last one the phone watched, or later (it moved up from it, with output).
fn at_newest(state: &State, terminal: &gpui_rio::Session) -> bool {
    let Some(watched) = &state.local.watched else {
        return true;
    };
    let Ok(watched) = watched.try_lock() else {
        return false;
    };
    let (Some(last), Some(now)) = (watched.last.as_ref(), crate::snapshot::Screen::of(&terminal.grid().lock())) else {
        return true;
    };
    let last: Vec<&str> = last.rows.iter().map(|r| r.text.as_str()).collect();
    let now: Vec<&str> = now.rows.iter().map(|r| r.text.as_str()).collect();
    matches!(crate::transcript::moved(&last, &now), crate::transcript::Moved::Still | crate::transcript::Moved::Up(_))
}
pub fn scroll_to(id: u64, offset: i64) -> Result<String> {
    let owner = session(id)?;
    if offset == 0 {
        let mut state = lock(&owner.state);
        // Back to the newest line: the live grid, not the copy read back.
        if state.local.reader.take().is_some() {
            show_render(None, state.render_id, None, None);
        }
        if let Some(terminal) = &state.terminal {
            terminal
                .grid()
                .lock()
                .scroll_display(rio_vt::crosswords::grid::Scroll::Bottom);
        }
    }
    Ok(json!({"queued":false}).to_string())
}

fn run(
    owner: &Arc<Session>,
    config: Config,
    credentials: Credentials,
    cols: u16,
    rows: u16,
    active: bool,
    commands: mpsc::Receiver<Operation>,
) -> Result<()> {
    let runtime = tokio::runtime::Builder::new_multi_thread()
        .worker_threads(1)
        .enable_all()
        .build()?;
    let ssh = runtime.block_on(herdr_remote_transport::connect(
        &config,
        credentials,
        owner.cancel.clone(),
    ))?;
    let bridge = runtime.block_on(ssh.into_bridge("herdr-core connect"))?;
    let (socket, guard) = bridge.into_parts();
    let result = crate::core_session::run(owner, socket, cols, rows, active, commands);
    runtime.block_on(guard.shutdown());
    result
}

/// Discovery uses the already authenticated SSH/core connection, so Android
/// needs neither root nor access to another app's private LocalAPI socket.
pub fn discover(id: u64) -> Result<String> {
    let owner = session(id)?;
    let (tx, rx) = mpsc::sync_channel(1);
    owner
        .commands
        .try_send(Operation::Discover(tx))
        .map_err(|_| Error::Capacity)?;
    rx.recv_timeout(Duration::from_secs(120))
        .map_err(|_| Error::Closed)?
}

#[cfg(test)]
pub(crate) fn register_for_test(owner: Arc<Session>) {
    lock(sessions()).insert(owner.id, owner);
}
/// The pane on screen attached again (its stream ended): `terminal` is the
/// attach reply's terminal record.
#[cfg(test)]
pub(crate) fn reattach_for_test(owner: &Session, terminal: &Value) {
    lock(&owner.state).local = Local::attached(owner.heard.output_now(), Incarnation::of(terminal));
}
/// The PTY process id the tests' panes report.
#[cfg(test)]
pub(crate) const TEST_PID: u64 = 4242;
/// The pane on screen's attach named the terminal its list entry names now.
#[cfg(test)]
pub(crate) fn attach_as_listed_for_test(owner: &Session) {
    let mut state = lock(&owner.state);
    let pane = state.view["pane_id"].as_str().unwrap_or("").to_owned();
    let entry = pane_entry(&state.view, &pane).cloned().unwrap_or_default();
    state.local.incarnation = Incarnation::of(&entry);
}
/// What the phone watched of `owner`'s pane: `transcript`, then its screen now.
#[cfg(test)]
pub(crate) fn adopt_for_test(owner: &Session, transcript: crate::transcript::Transcript) {
    let mut state = lock(&owner.state);
    let last = state.terminal.as_ref().and_then(|t| crate::snapshot::Screen::of(&t.grid().lock()));
    state.local.watched = Some(Arc::new(Mutex::new(Watched { transcript, last })));
}
#[cfg(test)]
pub(crate) fn held_for_test() -> Option<(u64, Arc<gpui_rio::Session>)> {
    lock(held()).as_ref().map(|held| (held.id, held.terminal.clone()))
}
#[cfg(test)]
impl Local {
    /// A pane attached and painted long ago (its attach named TEST_PID).
    pub(crate) fn settled_for_test() -> Self {
        Self { settled: true, incarnation: Some(Incarnation { pid: TEST_PID, born: 0 }), ..Self::default() }
    }
    /// A pane just attached whose attach named TEST_PID.
    pub(crate) fn attached_for_test(output: u64) -> Self {
        Self::attached(output, Some(Incarnation { pid: TEST_PID, born: 0 }))
    }
    pub(crate) fn reader_for_test(&self) -> Option<(u64, Arc<gpui_rio::Session>)> {
        self.reader.clone()
    }
}

#[cfg(test)]
#[allow(clippy::unwrap_used)]
mod last_use_tests {
    use super::*;

    /// t217's probe on fc3d430: a one-day cap made a phone last used two days
    /// ago look newer than a desktop idle for a day and a half.
    #[test]
    fn a_reconnect_after_two_days_keeps_two_days_of_idle_age() {
        let uses = LastUse::new();
        uses.born
            .set(std::time::Instant::now().checked_sub(Duration::from_secs(172_800)).unwrap())
            .unwrap();
        uses.at_ms.store(1, Ordering::Relaxed);
        uses.count.store(1, Ordering::Relaxed);
        let age = uses.idle_ms();
        assert!(age >= 172_800_000, "a two-day-old use must not become a one-day-old use on reconnect: {age}");
        assert!(age < NEVER_USED_MS, "a real age always sorts ahead of never");
    }

    #[test]
    fn never_used_is_older_than_any_real_age_and_reads_as_a_signed_number() {
        let uses = LastUse::new();
        assert_eq!(uses.idle_ms(), NEVER_USED_MS);
        assert_eq!(json!(uses.idle_ms()).as_i64(), Some(i64::MAX));
    }
}
