//! herdr-core sessions over embedded SSH. No TUI bridge or binary projection.
use crate::model;
#[cfg(feature = "android-ui")]
use gpui_rio::ghost::PromptKind;
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
    time::{Duration, Instant},
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
    #[error("A file to put on the host is empty or over 8 MB")]
    PutBounds,
    #[error("A file to put on the host needs one plain file name")]
    PutName,
    #[error("herdr-core protocol: {0}")]
    Protocol(String),
    /// The core did not answer `method` within the phone's wait. The call may still finish on
    /// the computer; its late reply is dropped, never mistaken for another call's.
    #[error("herdr-core did not answer {method} in time")]
    Timeout { method: String },
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

#[derive(Default)]
pub(crate) struct State {
    pub(crate) view: Value,
    pub(crate) terminal: Option<Arc<gpui_rio::Session>>,
    /// The attach on screen (the phone's AttachId): a new one on every
    /// attach, 0 while none is. A held message's send is bound to it.
    pub(crate) render_id: u64,
    /// Whether this attach's stream has delivered its first paint.
    pub(crate) painted: bool,
    #[cfg(feature = "android-ui")]
    footer_foreground: Option<ForegroundObservation>,
}

impl State {
    pub(crate) fn new(view: Value) -> Self {
        Self { view, ..Self::default() }
    }
}

#[cfg(feature = "android-ui")]
struct ForegroundObservation {
    renderer: u64,
    pid: u64,
    name: Box<str>,
    kind: PromptKind,
    first: Instant,
    latest: Instant,
}

#[cfg(feature = "android-ui")]
impl State {
    /// Only core replies extend an observation. Repeated UI polls of a
    /// cached reply cannot confirm that a foreground process is still there.
    pub(crate) fn observe_footer_foreground(&mut self, terminal: Option<&Value>, at: Instant) {
        let Some((pid, name, kind)) = terminal.filter(|_| self.render_id != 0).and_then(foreground_agent)
        else {
            self.invalidate_footer_foreground();
            return;
        };
        if let Some(previous) = &mut self.footer_foreground
            && previous.renderer == self.render_id
            && previous.pid == pid
            && previous.name.as_ref() == name
            && previous.kind == kind
        {
            previous.latest = at;
            return;
        }
        self.invalidate_footer_foreground();
        self.footer_foreground = Some(ForegroundObservation {
            renderer: self.render_id,
            pid,
            name: name.into(),
            kind,
            first: at,
            latest: at,
        });
    }

    pub(crate) fn footer_foreground_changed(&mut self, report: &Value) {
        // The core also emits foreground events for cwd changes. Those still
        // describe the same foreground process.
        if self.footer_foreground.as_ref().is_some_and(|seen| {
            report["pid"].as_u64() != Some(seen.pid)
                || report["name"].as_str() != Some(seen.name.as_ref())
        }) {
            self.invalidate_footer_foreground();
        }
    }

    pub(crate) fn invalidate_footer_foreground(&mut self) {
        if self.footer_foreground.take().is_some() {
            model::mailbox().clear_footer_kind(self.render_id);
        }
    }

    fn footer_kind(&self) -> PromptKind {
        self.footer_foreground.as_ref().map_or(PromptKind::Other, |seen| {
            if seen.renderer == self.render_id && seen.latest.duration_since(seen.first) >= Duration::from_secs(1) {
                seen.kind
            } else {
                PromptKind::Other
            }
        })
    }
}

#[cfg(feature = "android-ui")]
fn foreground_agent(terminal: &Value) -> Option<(u64, &str, PromptKind)> {
    if terminal["state"] != "live" {
        return None;
    }
    let foreground = &terminal["foreground"];
    let pid = foreground["pid"].as_u64().filter(|&pid| pid > 0)?;
    let name = foreground["name"].as_str()?;
    let agent = terminal["labels"]["agent.kind"].as_str()?;
    let matches = |names: &[&str]| names.iter().any(|program| name.eq_ignore_ascii_case(program));
    let claude = agent.eq_ignore_ascii_case("claude") || agent.eq_ignore_ascii_case("claude-code");
    let kind = if claude && matches(&["claude", "claude-code", "claude.exe", "claude-code.exe"]) {
        PromptKind::Claude
    } else if agent.eq_ignore_ascii_case("codex") && matches(&["codex", "codex.exe"]) {
        PromptKind::Codex
    } else if agent.eq_ignore_ascii_case("gemini") && matches(&["gemini", "gemini.exe", "node", "node.exe"]) {
        // Gemini CLI is a Node program: its foreground process may be named `node`. The
        // agent label must name Gemini, so a plain `node` never counts.
        PromptKind::Gemini
    } else {
        return None;
    };
    Some((pid, name, kind))
}

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
    pub(crate) focus: Mutex<Option<(String, Instant)>>,
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
    born: Instant,
    at_ms: AtomicU64,
    /// Bytes of pane output received so far (every pane this session showed):
    /// the phone checkpoints the pane on screen only while this moves.
    output: AtomicU64,
}
impl Default for Heard {
    fn default() -> Self {
        Self {
            born: Instant::now(),
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
    /// A file for a terminal's machine (`terminal.put`): bytes, so not an Action's bounded JSON.
    Put(PutFile, mpsc::SyncSender<Result<String>>),
    Frame(u32, u8, Vec<u8>),
    /// A held message for the attach `Compose::attach` only.
    Compose(Compose, mpsc::SyncSender<Typed>),
    /// A bounded transcript read (`terminal.read`, feature `read-bounded`).
    Read(ReadRequest, mpsc::SyncSender<Result<String>>),
}
/// The most rows and JSON-escaped bytes one transcript read asks for: the
/// core formats only those rows, and the reply is bounded by `max_bytes`.
pub(crate) const MAX_READ_LINES: usize = 5000;
pub(crate) const MAX_READ_BYTES: usize = 1024 * 1024;
/// A validated transcript read of one terminal of this session's own core.
#[derive(Debug, Clone, PartialEq, Eq)]
pub(crate) struct ReadRequest {
    pub(crate) terminal: String,
    pub(crate) lines: usize,
    pub(crate) max_bytes: usize,
}
impl ReadRequest {
    /// A relayed address (`machine/t7`) is refused: identity is proven only
    /// over a direct link, so nothing read through a relay is cached.
    pub(crate) fn new(terminal: &str, lines: usize, max_bytes: usize) -> Result<Self> {
        let plain = !terminal.is_empty()
            && terminal.len() <= 128
            && !terminal.contains('/')
            && !terminal.chars().any(char::is_control);
        if !plain {
            return Err(Error::StaleTarget);
        }
        if lines == 0 || lines > MAX_READ_LINES || max_bytes == 0 || max_bytes > MAX_READ_BYTES {
            return Err(Error::Config);
        }
        Ok(Self { terminal: terminal.into(), lines, max_bytes })
    }
}
/// A held message's bytes (the paste, then Enter), bound to one attach.
pub(crate) struct Compose {
    pub(crate) attach: u64,
    pub(crate) parts: Vec<Vec<u8>>,
}
/// How far a held message got. The phone never learns more: the bytes go
/// to a local SSH bridge, and the core may drop a retired viewer's input.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(crate) enum Typed {
    /// Every byte was written to the attach it was tapped over.
    Written,
    /// Nothing was written: that attach was gone, unpainted, or the link was.
    Unwritten,
    /// Some may have been written (the link failed during the write, or no
    /// answer came): it may have been sent, or sit unsent in the input box.
    Unsure,
}
impl Typed {
    fn word(self) -> &'static str {
        match self {
            Self::Written => "written",
            Self::Unwritten => "unwritten",
            Self::Unsure => "unsure",
        }
    }
}
/// The most one `terminal.put` takes: the core's own bound, 8 MiB raw.
pub(crate) const MAX_PUT: usize = 8 * 1024 * 1024;
/// A validated file to put beside a terminal: what the desktop's paste saves as
/// `herdr-paste-<nanos>.<ext>`, here sent over the core connection instead.
pub(crate) struct PutFile {
    pub(crate) terminal: String,
    pub(crate) name: String,
    pub(crate) bytes: Vec<u8>,
}
impl PutFile {
    pub(crate) fn new(terminal: &str, name: &str, bytes: Vec<u8>) -> Result<Self> {
        if terminal.is_empty() || terminal.len() > 128 || terminal.chars().any(char::is_control) {
            return Err(Error::StaleTarget);
        }
        if bytes.is_empty() || bytes.len() > MAX_PUT {
            return Err(Error::PutBounds);
        }
        let plain = !name.is_empty()
            && name.len() <= 128
            && name != "."
            && name != ".."
            && !name.contains(['/', '\\'])
            && !name.chars().any(char::is_control);
        if !plain {
            return Err(Error::PutName);
        }
        Ok(Self { terminal: terminal.into(), name: name.into(), bytes })
    }
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
    for owner in lock(sessions()).values() {
        let state = lock(&owner.state);
        if state.render_id == id {
            return state.terminal.clone();
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
        state: Mutex::new(State::new(json!({"state":"connecting","status":"Connecting to herdr-core over SSH","boot_id":"","pane_id":"","focused_pane":"","panes":[],"workspaces":[],"tabs":[],"title":"Herdr Core","token":1,"chrome_token":1,"revision":1,"fetch":null,"scroll":{"offset":0,"max":0,"rows":0}}))),
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
                #[cfg(feature = "android-ui")]
                state.invalidate_footer_foreground();
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
    born: OnceLock<Instant>,
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
        let born = self.born.get_or_init(Instant::now);
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
    if let Some(terminal) = state.terminal.take() {
        let pane = state.view["pane_id"].as_str().unwrap_or("").to_owned();
        // This grid is newer than any copy of the same pane kept on a switch.
        lock(departed()).retain(|kept| !kept.from(id, &pane));
        *lock(closed()) = Some(Kept { session: id, terminal: terminal.clone(), pane });
        hold(terminal);
    }
}
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
pub(crate) fn depart(session: u64, terminal: Arc<gpui_rio::Session>, pane: String) {
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
    kept.push_back(Kept { session, terminal, pane });
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
    *lock(closed()) = Some(Kept { session, terminal, pane: pane.into() });
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
}
impl Kept {
    fn from(&self, session: u64, pane: &str) -> bool {
        self.session == session && self.pane == pane
    }
}
pub(crate) fn hold(terminal: Arc<gpui_rio::Session>) {
    *lock(held()) = Some(Held { terminal });
}
/// The grid the held frame draws.
struct Held {
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
/// A new pane attached: its live grid is the one to scroll and draw now.
pub(crate) fn release_held() {
    lock(held()).take();
    lock(cached()).take();
}
/// Saves `pane`'s grid, as session `id` last showed it, to `path`: the
/// phone's copy of that conversation, for reading it later with no link. The
/// grid is the newest that session left: its live one while it shows `pane`,
/// else the one kept when a switch left it (freed once saved), else the one
/// kept when it closed. Grids of other sessions never count, even for the
/// same pane id. False when there is none. The grid is copied under its lock
/// and written after it is released, so a slow disk never holds up painting.
/// Disk work: never on the UI thread.
pub fn save_pane(id: u64, pane: &str, path: &str) -> Result<bool> {
    if pane.is_empty() {
        return Ok(false);
    }
    let live = lock(sessions()).get(&id).cloned().and_then(|owner| {
        let state = lock(&owner.state);
        if state.view["pane_id"] == pane { state.terminal.clone() } else { None }
    });
    let switched = || {
        let mut kept = lock(departed());
        let at = kept.iter().position(|kept| kept.from(id, pane))?;
        kept.remove(at).map(|kept| kept.terminal)
    };
    let last_closed = || {
        lock(closed())
            .as_ref()
            .filter(|kept| kept.from(id, pane))
            .map(|kept| kept.terminal.clone())
    };
    let Some(terminal) = live.or_else(switched).or_else(last_closed) else {
        return Ok(false);
    };
    let copy = crate::snapshot::copy(&terminal.grid().lock(), pane)?;
    crate::snapshot::write(&copy, std::path::Path::new(path))?;
    Ok(true)
}
/// Reads a saved pane from `path` and makes it the held frame, drawn under a
/// new render id (returned) until a live pane attaches. It scrolls like any
/// held frame. Disk work: never on the UI thread.
pub fn open_saved(path: &str) -> Result<u64> {
    let saved = crate::snapshot::load(std::path::Path::new(path))?;
    let terminal = crate::snapshot::restore(saved)?;
    let id = crate::core_session::next_render_id();
    *lock(cached()) = Some((id, terminal.clone()));
    hold(terminal);
    Ok(id)
}
/// Makes `pane`'s stand-in the held frame when it has no saved copy: `title`
/// and one dim `note` line on a `cols`×`rows` grid, drawn under a new render id
/// (returned) until a live pane attaches. Never touches a session or the disk.
pub fn hold_note(pane: &str, title: &str, note: &str, cols: u16, rows: u16) -> Result<u64> {
    let terminal = crate::snapshot::restore(crate::snapshot::Saved {
        cols: cols.clamp(20, 400),
        rows: rows.clamp(5, 200),
        pane: pane.into(),
        bytes: crate::snapshot::note(title, note),
    })?;
    let id = crate::core_session::next_render_id();
    *lock(cached()) = Some((id, terminal.clone()));
    hold(terminal);
    Ok(id)
}
/// One step of the phone's own scrolling of `grid`, sending nothing:
/// `{"local_offset": rows back from the newest, "history": rows kept above the
/// screen, "moved": whether the view changed}`. A full-screen program's grid
/// keeps no history, so a step back there never moves: the caller says so
/// rather than leave a drag unanswered.
pub(crate) fn scroll_grid<U: rio_vt::event::EventListener>(
    grid: &mut rio_vt::crosswords::Crosswords<U>,
    scroll: rio_vt::crosswords::grid::Scroll,
) -> Value {
    let before = grid.display_offset();
    grid.scroll_display(scroll);
    let offset = grid.display_offset();
    json!({"local_offset": offset, "history": grid.history_size(), "moved": offset != before})
}
/// The answer of a scroll with no grid to move.
fn scrolled_nothing() -> Value {
    json!({"local_offset": 0, "history": 0, "moved": false})
}
/// A step of `delta` rows back (positive) or forward, at most 1000 either way.
fn delta_scroll(delta: i64) -> rio_vt::crosswords::grid::Scroll {
    rio_vt::crosswords::grid::Scroll::Delta(delta.clamp(-1000, 1000) as i32)
}
/// Scrolls the held frame's own history, with no link: `delta` rows back
/// (positive) or forward, or back to the newest line when `latest`. Never sends
/// anything, so even a program that takes wheel input is scrolled locally.
/// See [`scroll_grid`] for the answer; all zero when nothing is held.
pub fn scroll_held(delta: i64, latest: bool) -> String {
    let scroll = if latest {
        rio_vt::crosswords::grid::Scroll::Bottom
    } else {
        delta_scroll(delta)
    };
    lock(held())
        .as_ref()
        .map_or_else(scrolled_nothing, |held| {
            scroll_grid(&mut held.terminal.grid().lock(), scroll)
        })
        .to_string()
}
pub fn poll(id: u64, _since: u64, _mirror: bool) -> Result<String> {
    let owner = session(id)?;
    let mut view = {
        let state = lock(&owner.state);
        let mut view = state.view.clone();
        view["attach"] = json!(state.render_id);
        view["painted"] = json!(state.painted);
        view
    };
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
    let state = lock(&owner.state);
    {
        let mut mailbox = model::mailbox();
        // Before its pane attaches, a session draws nothing: a held frame (the
        // last one, or a saved pane) stays until there is something live.
        // The renderer is recorded with the host and pane it was attached
        // for, under the same lock that installs it.
        if state.render_id != 0 || !mailbox.state().held {
            let pane = state.view["pane_id"].as_str().unwrap_or("");
            let target = model::FrameTarget {
                footer_kind: state.footer_kind(),
                ..model::FrameTarget::new(host, pane)
            };
            mailbox.set_shell(generation, state.render_id, Some(target));
        }
    }
    // How far the local history is scrolled back, for a "jump to latest" control.
    let local_offset = state
        .terminal
        .as_ref()
        .map_or(0, |terminal| terminal.grid().lock().display_offset());
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
            "attach": state.render_id,
            "painted": state.painted,
            "scroll": view["scroll"],
            "fetch": view["fetch"],
            "local_offset": local_offset,
            "quiet_ms": owner.heard.quiet_ms(),
            "output": owner.heard.output(),
        })
        .to_string());
    }
    let mut view = view.clone();
    view["attach"] = json!(state.render_id);
    view["painted"] = json!(state.painted);
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
    *lock(&owner.focus) = Some((terminal.into(), Instant::now()));
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
        && crate::phone_ops::PhoneOp::parse(action).is_none()
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
/// Types a held message into the attach `attach` (the phone's AttachId, the
/// one on screen when Wolf tapped Send): its paste, then Enter, both on that
/// attach's stream or not at all. Blocks the caller (the outbox's own
/// thread) until the session thread wrote it. `{"outcome": ..}` is
/// `written` (every byte left for that attach; nothing says it arrived),
/// `unwritten` (nothing was written: that attach is gone or not painted,
/// or the session is) or `unsure`.
pub fn compose(id: u64, attach: u64, text: &str) -> Result<String> {
    if !composable(text) {
        return Err(Error::Input(crate::Error::InvalidText));
    }
    let Ok(owner) = session(id) else {
        return Ok(outcome(Typed::Unwritten));
    };
    Ok(outcome(compose_on(&owner, attach, text, Duration::from_secs(15))))
}
fn outcome(typed: Typed) -> String {
    json!({"outcome": typed.word()}).to_string()
}
/// A held message's text: not blank, one compose command at most, and no
/// control but line breaks and tabs.
fn composable(text: &str) -> bool {
    !text.trim().is_empty()
        && text.len() <= model::MAX_TEXT_BYTES
        && !text.chars().any(|c| c.is_control() && c != '\n' && c != '\t')
}
pub(crate) fn compose_on(owner: &Session, attach: u64, text: &str, wait: Duration) -> Typed {
    let parts = {
        let state = lock(&owner.state);
        // Checked again by the session thread just before it writes; this
        // check only spares encoding for an attach that is already gone.
        if attach == 0 || state.render_id != attach || !state.painted || state.view["state"] != "ready" {
            return Typed::Unwritten;
        }
        let Some(terminal) = state.terminal.as_ref() else {
            return Typed::Unwritten;
        };
        let enter = gpui_rio::KeyPress {
            key: "enter".into(),
            control: false,
            alt: false,
            shift: false,
            text: None,
        };
        let Some(enter) = terminal.key_bytes(&enter) else {
            return Typed::Unwritten;
        };
        vec![terminal.paste_bytes(text), enter]
    };
    let (tx, rx) = mpsc::sync_channel(1);
    if owner
        .commands
        .try_send(Operation::Compose(Compose { attach, parts }, tx))
        .is_err()
    {
        return Typed::Unwritten;
    }
    match rx.recv_timeout(wait) {
        Ok(typed) => typed,
        // Taken and not answered: it may be writing right now.
        Err(mpsc::RecvTimeoutError::Timeout) => Typed::Unsure,
        // The session ended with the request still queued: never run.
        Err(mpsc::RecvTimeoutError::Disconnected) => Typed::Unwritten,
    }
}
/// Reads `terminal`'s screen and up to `lines` rows of history as text, cut to
/// `max_bytes`, for the phone's offline transcript: one `terminal.read` with
/// `stitch: false` (a full-screen program is never scrolled) on this session's
/// connection, only when its core's hello offered `read-bounded` and
/// `incarnation`. `{"supported":false}` otherwise, and nothing is asked;
/// else `{"supported":true,"text":..,"truncated":..,"incarnation":..}` or
/// `{"supported":true,"error":kind}`. Blocks: worker threads only.
pub fn read_transcript(id: u64, terminal: &str, lines: usize, max_bytes: usize) -> Result<String> {
    let request = ReadRequest::new(terminal, lines, max_bytes)?;
    let owner = session(id)?;
    let (tx, rx) = mpsc::sync_channel(1);
    owner
        .commands
        .try_send(Operation::Read(request, tx))
        .map_err(|_| Error::Capacity)?;
    rx.recv_timeout(crate::core_session::READ_WAIT + Duration::from_secs(5))
        .map_err(|_| Error::Closed)?
}
/// Puts an image (or any file) beside `terminal` on its machine, over the
/// session's own core connection, and returns where it landed:
/// `{"queued":true,"path":..,"bytes":..}`, or `{"queued":false,"kind":..}` when
/// the core refused (`needs_update`: its Herdr predates `terminal.put`).
/// Blocks up to about a minute: worker threads only. Types nothing.
pub fn put_file(id: u64, terminal: &str, name: &str, bytes: Vec<u8>) -> Result<String> {
    let file = PutFile::new(terminal, name, bytes)?;
    let owner = session(id)?;
    let (tx, rx) = mpsc::sync_channel(1);
    owner
        .commands
        .try_send(Operation::Put(file, tx))
        .map_err(|_| Error::Capacity)?;
    rx.recv_timeout(crate::core_session::PUT_WAIT + Duration::from_secs(15))
        .map_err(|_| Error::Closed)?
}
pub struct UiPaint<'a> {
    pub generation: i64,
    pub title: &'a str,
    pub status: &'a str,
    pub shortcuts: &'a str,
}
pub fn scroll_local(id: u64, delta: i64, paint: UiPaint<'_>) -> Result<String> {
    let owner = session(id)?;
    let state = lock(&owner.state);
    if let Some(terminal) = &state.terminal {
        let delta = delta.clamp(-1000, 1000) as i32;
        // Full-screen agents (Claude Code, Codex) scroll themselves: they get
        // wheel input. Plain output scrolls the attach's local history.
        if !terminal.wheel(delta) {
            terminal
                .grid()
                .lock()
                .scroll_display(rio_vt::crosswords::grid::Scroll::Delta(delta));
        }
    }
    let _ = (paint.generation, paint.title, paint.status, paint.shortcuts);
    Ok(json!({"queued":true}).to_string())
}
/// Scrolls session `id`'s live grid on the phone only, while its link is in
/// doubt: never wheel input, which a full-screen agent would wait on over a
/// dead link. Answers as [`scroll_grid`] does.
pub fn scroll_local_only(id: u64, delta: i64) -> Result<String> {
    let owner = session(id)?;
    let state = lock(&owner.state);
    let answer = state.terminal.as_ref().map_or_else(scrolled_nothing, |terminal| {
        scroll_grid(&mut terminal.grid().lock(), delta_scroll(delta))
    });
    Ok(answer.to_string())
}
/// Most files one look at a pane names, and the history rows above its screen
/// it reads: a look is cheap and bounded, whatever the pane printed.
pub(crate) const FILE_REFS_MAX: usize = 64;
pub(crate) const FILE_REFS_HISTORY: usize = 400;
/// The files session `id`'s pane on screen names (paths, Markdown links,
/// OSC 8 `file://` targets: what a tap there would open), for the phone to
/// keep copies to read offline: `{"pane": its id, "files": [...]}`. Reads the
/// phone's own grid; nothing is sent.
pub fn file_refs(id: u64) -> Result<String> {
    let owner = session(id)?;
    let state = lock(&owner.state);
    let pane = state.view["pane_id"].as_str().unwrap_or("").to_owned();
    let files = state.terminal.as_ref().map_or_else(Vec::new, |terminal| {
        gpui_rio::links::grid_files(&terminal.grid().lock(), FILE_REFS_HISTORY, FILE_REFS_MAX)
    });
    Ok(json!({"pane": pane, "files": files}).to_string())
}
pub fn scroll_to(id: u64, offset: i64) -> Result<String> {
    let owner = session(id)?;
    if offset == 0
        && let Some(terminal) = &lock(&owner.state).terminal
    {
        terminal
            .grid()
            .lock()
            .scroll_display(rio_vt::crosswords::grid::Scroll::Bottom);
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
#[allow(clippy::unwrap_used)]
mod last_use_tests {
    use super::*;

    /// t217's probe on fc3d430: a one-day cap made a phone last used two days
    /// ago look newer than a desktop idle for a day and a half.
    #[test]
    fn a_reconnect_after_two_days_keeps_two_days_of_idle_age() {
        let uses = LastUse::new();
        uses.born
            .set(Instant::now().checked_sub(Duration::from_secs(172_800)).unwrap())
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

#[cfg(all(test, feature = "android-ui"))]
mod footer_tests {
    use super::*;
    use gpui_rio::footer::hidden_on_screen;
    use rio_vt::{
        ansi::CursorShape,
        crosswords::{Crosswords, CrosswordsSize},
        event::{VoidListener, WindowId},
        performer::handler::Processor,
    };

    const TRANSCRIPT: &str = concat!(
        "\x1b[4;1H",
        "────────────────────────────────────────\r\n",
        "❯ Inspect the saved transcript\r\n",
        "────────────────────────────────────────\r\n",
        "  -- INSERT -- ⏵⏵ bypass permissions on (shift+tab to cycle) · 1 shell · 3 agents\r\n",
        "Open https://example.com/after",
        "\x1b[5;3H",
    );

    fn grid() -> Crosswords<VoidListener> {
        Crosswords::new(
            CrosswordsSize::new(96, 8),
            CursorShape::Block,
            VoidListener,
            WindowId::from(0),
            0,
            0,
        )
    }

    fn attached() -> State {
        State { render_id: u64::MAX, ..State::default() }
    }

    fn terminal(agent: &str, program: &str, pid: u64) -> Value {
        json!({"address":"t1","state":"live","labels":{"agent.kind":agent},"foreground":{"name":program,"pid":pid}})
    }

    fn confirm(state: &mut State, terminal: &Value, first: Instant) -> Instant {
        let latest = first + Duration::from_secs(1);
        state.observe_footer_foreground(Some(terminal), first);
        state.observe_footer_foreground(Some(terminal), latest);
        latest
    }

    #[test]
    fn editor_output_before_the_foreground_update_keeps_the_transcript() {
        let mut state = attached();
        let claude = terminal("claude", "claude", 10);
        let exit = confirm(&mut state, &claude, Instant::now());
        let mut grid = grid();
        let mut parser = Processor::default();
        parser.advance(&mut grid, TRANSCRIPT.as_bytes());
        assert_eq!(hidden_on_screen(&grid, state.footer_kind()), Some(6..7));

        // The agent exits. At 12 ms Neovim's alternate screen is here, but
        // the core still reports the old foreground until after 500 ms.
        parser.advance(&mut grid, b"\x1b[?1049h");
        parser.advance(&mut grid, TRANSCRIPT.as_bytes());
        state.observe_footer_foreground(Some(&claude), exit + Duration::from_millis(12));
        assert_eq!(state.footer_kind(), PromptKind::Claude);
        assert_eq!(hidden_on_screen(&grid, state.footer_kind()), None);
        let editor = terminal("claude", "nvim", 11);
        state.observe_footer_foreground(Some(&editor), exit + Duration::from_millis(504));
        assert_eq!(state.footer_kind(), PromptKind::Other);
        assert_eq!(hidden_on_screen(&grid, state.footer_kind()), None);
    }

    #[test]
    fn a_claude_labeled_nvim_transcript_keeps_its_mode_line_on_the_main_screen() {
        let mut grid = grid();
        Processor::default().advance(&mut grid, TRANSCRIPT.as_bytes());
        assert_eq!(hidden_on_screen(&grid, PromptKind::Claude), Some(6..7));

        let mut state = attached();
        let mut at = Instant::now();
        for (program, hidden) in [
            ("nvim", None),
            ("claude", Some(6..7)),
            ("less", None),
            ("bash", None),
            ("codex", None),
            ("aswap", None),
            ("", None),
        ] {
            at = confirm(&mut state, &terminal("claude", program, 10), at);
            assert_eq!(hidden_on_screen(&grid, state.footer_kind()), hidden, "{program}");
        }
    }

    #[test]
    fn foreground_must_be_confirmed_for_one_second_on_the_same_renderer() {
        let mut state = attached();
        let first = Instant::now();
        let claude = terminal("claude", "claude", 10);
        state.observe_footer_foreground(Some(&claude), first);
        assert_eq!(state.footer_kind(), PromptKind::Other);
        state.observe_footer_foreground(Some(&claude), first + Duration::from_millis(999));
        assert_eq!(state.footer_kind(), PromptKind::Other);
        let stable = first + Duration::from_secs(1);
        state.observe_footer_foreground(Some(&claude), stable);
        assert_eq!(state.footer_kind(), PromptKind::Claude);

        let replacement = terminal("claude", "claude", 11);
        state.observe_footer_foreground(Some(&replacement), stable);
        assert_eq!(state.footer_kind(), PromptKind::Other);
        let stable = confirm(&mut state, &replacement, stable);
        assert_eq!(state.footer_kind(), PromptKind::Claude);

        state.footer_foreground_changed(&json!({"name":"claude","pid":11,"cwd":"/tmp/work"}));
        assert_eq!(state.footer_kind(), PromptKind::Claude);
        state.footer_foreground_changed(&json!({"name":"nvim","pid":12}));
        assert_eq!(state.footer_kind(), PromptKind::Other);
        state.observe_footer_foreground(Some(&replacement), stable);
        assert_eq!(state.footer_kind(), PromptKind::Other);
        confirm(&mut state, &replacement, stable);
        assert_eq!(state.footer_kind(), PromptKind::Claude);

        state.render_id -= 1;
        assert_eq!(state.footer_kind(), PromptKind::Other);
    }

    #[test]
    fn codex_requires_an_agent_label_and_matching_foreground_program() {
        let mut state = attached();
        let at = confirm(&mut state, &terminal("codex", "codex", 10), Instant::now());
        assert_eq!(state.footer_kind(), PromptKind::Codex);
        for report in [
            terminal("codex", "nvim", 10),
            terminal("", "codex", 10),
            terminal("codex", "codex", 0),
        ] {
            state.observe_footer_foreground(Some(&report), at);
            assert_eq!(state.footer_kind(), PromptKind::Other);
        }
    }

    /// Gemini CLI runs under Node: `gemini` or `node` count, only with Gemini's agent label.
    #[test]
    fn gemini_requires_its_agent_label_and_its_program_or_node() {
        for program in ["gemini", "node"] {
            let mut state = attached();
            let at = confirm(&mut state, &terminal("gemini", program, 10), Instant::now());
            assert_eq!(state.footer_kind(), PromptKind::Gemini, "{program}");
            for report in [terminal("gemini", "nvim", 10), terminal("", "node", 10), terminal("claude", "node", 10)] {
                state.observe_footer_foreground(Some(&report), at);
                assert_eq!(state.footer_kind(), PromptKind::Other);
            }
        }
    }
}
