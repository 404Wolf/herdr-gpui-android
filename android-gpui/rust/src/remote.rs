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

/// The pane on screen, as the phone itself keeps it: the copy being read
/// back instead of the live grid, and when the live grid's first paint is in.
#[cfg_attr(not(any(feature = "android-ui", test)), allow(dead_code))]
#[derive(Default)]
pub(crate) struct Local {
    /// The phone's own copy scrolled instead of the live grid (a full-screen
    /// program's transcript, or the copy read while the link was down): its
    /// render id and terminal. Back at its newest row the live grid returns.
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
}

impl Local {
    /// A pane just attached, with `output` bytes heard so far.
    pub(crate) fn attached(output: u64) -> Self {
        Self { attached: Some((std::time::Instant::now(), output)), output, captured: output, ..Self::default() }
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
    /// A full-screen pane whose history the phone asks the core for (a
    /// stitched `terminal.read`), set by the UI's poll, taken by the session.
    pub(crate) seed: Mutex<Option<String>>,
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
        seed: Mutex::new(None),
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
    if let Some(terminal) = state.terminal.take() {
        let pane = state.view["pane_id"].as_str().unwrap_or("").to_owned();
        let me = ident(&state.view, &pane);
        let agent = agent_ident(&state.view, &pane);
        // This grid is newer than any copy of the same pane kept on a switch.
        lock(departed()).retain(|kept| !kept.from(id, &pane));
        *lock(closed()) = Some(Kept {
            session: id,
            terminal: terminal.clone(),
            pane: pane.clone(),
            ident: me.clone(),
            agent: agent.clone(),
        });
        let shown = state.render_id;
        drop(state);
        let key = Some(me);
        match reading {
            // Being read back already: it stays, at the same row.
            Some((reader, copy)) => hold_as(reader, copy, key),
            None => {
                // The live grid at once; this runs on the UI thread.
                hold_as(shown, terminal.clone(), key.clone());
                if let Some(agent) = agent {
                    // An agent's full screen: the phone's transcript of it
                    // replaces that frame once it is built, so the conversation
                    // still scrolls with no link (not one screen).
                    let slot = held();
                    std::thread::spawn(move || {
                        let Some(copy) = reader_for(&agent, &terminal) else {
                            return;
                        };
                        let mut held = lock(slot);
                        if held.as_ref().is_some_and(|h| h.id == shown) {
                            let reader = crate::core_session::next_render_id();
                            *held = Some(Held { id: reader, terminal: copy, key });
                            drop(held);
                            show_render(None, reader, Some(shown), None);
                        }
                    });
                }
            }
        }
    }
}
/// Which terminal a pane is: the core (its boot id), the pane, the
/// terminal's incarnation (`created_ms`: a registry restored or rolled back
/// can number a new terminal t1 again) and the program it runs. A copy is
/// only ever joined to the terminal it was made of.
#[derive(Clone, Debug, Default, PartialEq, Eq, Hash)]
pub(crate) struct Ident {
    pub boot: String,
    pub pane: String,
    pub born: u64,
    pub program: String,
}

fn pane_entry<'a>(view: &'a Value, pane: &str) -> Option<&'a Value> {
    view["panes"].as_array().and_then(|panes| panes.iter().find(|p| p["id"] == pane))
}

/// `pane` of the session whose view this is.
pub(crate) fn ident(view: &Value, pane: &str) -> Ident {
    let entry = pane_entry(view, pane);
    Ident {
        boot: view["boot_id"].as_str().unwrap_or("").to_owned(),
        pane: pane.to_owned(),
        born: entry.and_then(|e| e["created_ms"].as_u64()).unwrap_or(0),
        program: entry.and_then(|e| e["program"].as_str()).unwrap_or("").to_owned(),
    }
}

/// The program an agent was seen running as, per terminal, with the status
/// marker it had then: a status reported again (a new marker) shows the
/// agent is what runs now; the same marker with another program is that
/// program (a pager after the agent left) under the agent's old status.
type Agents = HashMap<(String, String, u64), (String, String)>;
fn agents() -> &'static Mutex<Agents> {
    static AGENTS: OnceLock<Mutex<Agents>> = OnceLock::new();
    AGENTS.get_or_init(Mutex::default)
}
const AGENTS_MAX: usize = 256;

/// `pane`, when it runs an agent (Claude Code, Codex): one that reports its
/// status, or that Herdr labelled an agent, and is still the program that
/// did. Only an agent's full screen is a conversation the phone keeps and
/// reads back itself; any other full-screen program (vim, less, htop)
/// scrolls itself, with the drag as wheel input.
pub(crate) fn agent_ident(view: &Value, pane: &str) -> Option<Ident> {
    let entry = pane_entry(view, pane)?;
    let reports = entry["status"].as_str().is_some_and(|s| !s.is_empty());
    let labelled = entry["kind"].as_str().is_some_and(|k| !k.is_empty());
    if !reports && !labelled {
        return None;
    }
    let me = ident(view, pane);
    let (marker, _) = marker(view, pane);
    let mut seen = lock(agents());
    let key = (me.boot.clone(), me.pane.clone(), me.born);
    match seen.get(&key) {
        Some((at, program)) if *at == marker && *program != me.program => return None,
        Some((at, _)) if *at == marker => {}
        _ => {
            if seen.len() >= AGENTS_MAX {
                seen.clear();
            }
            seen.insert(key, (marker, me.program.clone()));
        }
    }
    Some(me)
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
pub(crate) fn depart(session: u64, terminal: Arc<gpui_rio::Session>, pane: String, boot: String) {
    let ident = Ident { boot, pane: pane.clone(), ..Ident::default() };
    depart_as(session, terminal, pane, ident, None);
}
/// [`depart`] of the terminal `ident`, which ran `agent` if any (its
/// transcript is its copy).
pub(crate) fn depart_as(
    session: u64,
    terminal: Arc<gpui_rio::Session>,
    pane: String,
    ident: Ident,
    agent: Option<Ident>,
) {
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
    kept.push_back(Kept { session, terminal, pane, ident, agent });
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
    *lock(closed()) = Some(Kept { session, terminal, pane: pane.into(), ident: Ident::default(), agent: None });
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
    /// The terminal it is (its copy says so), and the agent it ran when
    /// kept, if it ran one: whose transcript is its copy.
    ident: Ident,
    agent: Option<Ident>,
}
impl Kept {
    fn from(&self, session: u64, pane: &str) -> bool {
        self.session == session && self.pane == pane
    }
}
#[cfg(test)]
pub(crate) fn hold(terminal: Arc<gpui_rio::Session>) {
    hold_as(crate::core_session::next_render_id(), terminal, None);
}
/// Makes `terminal` the held frame, drawn under render id `id`.
fn hold_as(id: u64, terminal: Arc<gpui_rio::Session>, key: Option<Ident>) {
    *lock(held()) = Some(Held { id, terminal, key });
}
/// The grid the held frame draws.
#[cfg_attr(not(any(feature = "android-ui", test)), allow(dead_code))]
struct Held {
    id: u64,
    terminal: Arc<gpui_rio::Session>,
    /// The terminal it shows, when known: a live attach of the same terminal
    /// keeps the place it was read at. Pane ids repeat across computers
    /// (every core has a t1) and within one core whose registry was restored,
    /// so the pane alone is never enough.
    key: Option<Ident>,
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
#[cfg(test)]
pub(crate) fn release_held() {
    lock(held()).take();
    lock(cached()).take();
}

/// The live grid of a fresh attach is shown once its first paint is in (its
/// bytes went quiet) or SETTLE_MAX passed, never blank before. Then a held
/// frame of the same pane that was being read back stays as the reader, at
/// the same row (a reconnect never jumps); any other held frame lets go.
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
    let pane = state.view["pane_id"].as_str().unwrap_or("");
    let me = ident(&state.view, pane);
    if let Some(held) = lock(held()).take()
        && !me.boot.is_empty()
        && held.key.as_ref() == Some(&me)
        && held.terminal.grid().lock().display_offset() > 0
    {
        state.local.reader = Some((held.id, held.terminal));
    }
    lock(cached()).take();
    true
}

/// One full-screen pane's transcript on the phone, by core boot and pane.
#[cfg_attr(not(any(feature = "android-ui", test)), allow(dead_code))]
struct Transcribed {
    ident: Ident,
    transcript: crate::transcript::Transcript,
    /// The pane's status marker when a seed was last asked for: asked once
    /// per marker, so a refused or failed read is never retried in a loop.
    asked: Option<String>,
    /// The marker whose read waited for the core to take the phone's width,
    /// and how many times it waited (at most SEED_TRIES).
    waited: Option<(String, u8)>,
    /// When a read that waited may be asked for again.
    not_before: Option<std::time::Instant>,
    used: u64,
}
impl Transcribed {
    fn is(&self, ident: &Ident) -> bool {
        self.ident == *ident
    }
}
/// Transcripts kept in memory at once (each at most transcript::MAX_BYTES).
const TRANSCRIPTS: usize = 6;
fn transcripts() -> &'static Mutex<Vec<Transcribed>> {
    static KEPT: OnceLock<Mutex<Vec<Transcribed>>> = OnceLock::new();
    KEPT.get_or_init(Mutex::default)
}
static USED: AtomicU64 = AtomicU64::new(1);
/// `ident`'s transcript, made if there is none; the least recently used goes beyond TRANSCRIPTS.
fn transcribed<'a>(store: &'a mut Vec<Transcribed>, ident: &Ident) -> &'a mut Transcribed {
    let used = USED.fetch_add(1, Ordering::Relaxed);
    let at = match store.iter().position(|kept| kept.is(ident)) {
        Some(at) => at,
        None => {
            if store.len() >= TRANSCRIPTS
                && let Some(oldest) = (0..store.len()).min_by_key(|i| store[*i].used)
            {
                store.remove(oldest);
            }
            store.push(Transcribed {
                ident: ident.clone(),
                transcript: crate::transcript::Transcript::default(),
                asked: None,
                waited: None,
                not_before: None,
                used,
            });
            store.len() - 1
        }
    };
    store[at].used = used;
    &mut store[at]
}
/// A transcript read back from a saved copy of the terminal `ident` names,
/// unless the phone already keeps a newer one. A copy with no identity (an
/// older file) is never joined to a live pane.
fn adopt(ident: &Ident, transcript: crate::transcript::Transcript) {
    if ident.boot.is_empty() {
        return;
    }
    let mut store = lock(transcripts());
    let kept = transcribed(&mut store, ident);
    // Live capture since the attach found nothing yet: the saved copy leads,
    // and the next screen is aligned with its last one.
    if kept.transcript.rows() == 0 {
        kept.transcript = transcript;
    }
}

/// The phone's copy of a full-screen program to read back: its transcript
/// then its screen now, in a terminal of its own at the newest row. The
/// transcript first looks at that very screen, so the two join without a
/// row missed or doubled. None when `terminal` is not full screen or no
/// history was kept yet. Encodes and parses: never under the session's lock.
fn reader_for(ident: &Ident, terminal: &gpui_rio::Session) -> Option<Arc<gpui_rio::Session>> {
    if ident.pane.is_empty() {
        return None;
    }
    let (bytes, cols, rows) = {
        let mut store = lock(transcripts());
        let kept = store.iter_mut().find(|kept| kept.is(ident))?;
        if kept.transcript.rows() == 0 {
            return None;
        }
        let grid = terminal.grid().lock();
        if !grid.mode().contains(rio_vt::crosswords::Mode::ALT_SCREEN) {
            return None;
        }
        kept.transcript.observe(crate::transcript::screen_rows(&grid), grid.columns());
        let (bytes, _, _) = crate::snapshot::encode_transcript(&grid, &kept.transcript, crate::snapshot::MAX_BYTES);
        (bytes, u16::try_from(grid.columns()).ok()?, u16::try_from(grid.screen_lines()).ok()?)
    };
    let reader = crate::snapshot::restore(crate::snapshot::Saved::of(cols, rows, &ident.pane, bytes)).ok()?;
    // Parsed on its own thread: the first drag scrolls a whole copy.
    let deadline = std::time::Instant::now() + Duration::from_secs(3);
    while reader.is_alive() && std::time::Instant::now() < deadline {
        std::thread::sleep(Duration::from_millis(2));
    }
    Some(reader)
}

/// The pane's status as the list has it, and whether it is busy (a seed is
/// never asked for while it works or waits on a dialog).
#[cfg_attr(not(any(feature = "android-ui", test)), allow(dead_code))]
fn marker(view: &Value, pane: &str) -> (String, bool) {
    let entry = view["panes"].as_array().and_then(|panes| panes.iter().find(|p| p["id"] == pane));
    let Some(entry) = entry else {
        return (String::new(), true);
    };
    let status = entry["status"].as_str().unwrap_or("");
    let marker = format!("{}/{}/{}", status, entry["status_ms"], entry["turn"]);
    (marker, matches!(status, "working" | "blocked" | "error"))
}

/// Keeps the transcript of the full-screen pane on screen: each new screen
/// is aligned with the last one and the rows that left its top are kept.
/// Asks the session for a seed (a stitched read) when one is owed, once per
/// status marker of the pane, never while it is busy.
#[cfg(any(feature = "android-ui", test))]
pub(crate) fn keep_local(owner: &Session, state: &mut State) {
    if !state.local.settled {
        return;
    }
    let Some(terminal) = state.terminal.clone() else {
        return;
    };
    let pane = state.view["pane_id"].as_str().unwrap_or("").to_owned();
    // Only an agent's conversation is kept (see agent_ident).
    let Some(agent) = (!pane.is_empty()).then(|| agent_ident(&state.view, &pane)).flatten() else {
        return;
    };
    let output = owner.heard.output();
    let screen = {
        let grid = terminal.grid().lock();
        if !grid.mode().contains(rio_vt::crosswords::Mode::ALT_SCREEN) {
            return;
        }
        (output != state.local.captured).then(|| (crate::transcript::screen_rows(&grid), grid.columns()))
    };
    state.local.captured = output;
    let (marker, busy) = marker(&state.view, &pane);
    let ask = {
        let mut store = lock(transcripts());
        let kept = transcribed(&mut store, &agent);
        if let Some((rows, cols)) = screen {
            kept.transcript.observe(rows, cols);
        }
        // The read holds this phone's keys too: never while it is being used
        // (a prompt being typed, a pane being switched to); asked once it is quiet.
        let quiet = owner.uses.idle_ms() >= SEED_QUIET_MS;
        let ask = !busy
            && quiet
            && kept.transcript.wants_seed()
            && kept.asked.as_deref() != Some(marker.as_str())
            && kept.not_before.is_none_or(|at| std::time::Instant::now() >= at);
        if ask {
            kept.asked = Some(marker);
        }
        ask
    };
    if ask {
        *lock(&owner.seed) = Some(agent.pane);
    }
}

/// The phone must have been left alone this long before a stitched read is
/// asked for: the read holds the phone's own keys as well as the desk's.
#[cfg_attr(not(any(feature = "android-ui", test)), allow(dead_code))]
pub(crate) const SEED_QUIET_MS: u64 = 3_000;
/// How many times one marker's read waits for the core to take the phone's
/// width, and how long each wait is.
#[cfg_attr(not(any(feature = "android-ui", test)), allow(dead_code))]
const SEED_TRIES: u8 = 5;
#[cfg(not(test))]
#[cfg_attr(not(feature = "android-ui"), allow(dead_code))]
const SEED_RETRY: Duration = Duration::from_secs(1);
#[cfg(test)]
const SEED_RETRY: Duration = Duration::from_millis(20);

/// `pane`'s read was not made: the core's pane is not at the phone's width
/// yet (an attach's resize is still on its way), and a read now would be rows
/// of another width, wrapped on the phone. Asked again after SEED_RETRY, at
/// most SEED_TRIES times for the same marker; then, as for any refused read,
/// not before the pane's status moves on.
#[cfg(test)]
pub(crate) fn seed_later(boot: &str, pane: &str) {
    seed_later_of(&Ident { boot: boot.into(), pane: pane.into(), ..Ident::default() });
}
#[cfg_attr(not(any(feature = "android-ui", test)), allow(dead_code))]
pub(crate) fn seed_later_of(ident: &Ident) {
    let mut store = lock(transcripts());
    let kept = transcribed(&mut store, ident);
    let Some(marker) = kept.asked.clone() else {
        return;
    };
    let tries = match &kept.waited {
        Some((waited, tries)) if *waited == marker => tries + 1,
        _ => 1,
    };
    kept.waited = Some((marker, tries));
    if tries < SEED_TRIES {
        kept.asked = None;
        kept.not_before = Some(std::time::Instant::now() + SEED_RETRY);
    }
}

/// The phone was used after the read was asked for: put it off (not counted
/// as a refusal), asked again once the phone is quiet.
#[cfg_attr(not(any(feature = "android-ui", test)), allow(dead_code))]
pub(crate) fn seed_put_off(ident: &Ident) {
    let mut store = lock(transcripts());
    let kept = transcribed(&mut store, ident);
    kept.asked = None;
    kept.not_before = Some(std::time::Instant::now() + SEED_RETRY);
}

#[cfg(test)]
pub(crate) fn seeded(boot: &str, pane: &str, reply: &Value) -> bool {
    seeded_of(&Ident { boot: boot.into(), pane: pane.into(), ..Ident::default() }, reply)
}
/// The core's stitched read of the terminal `ident` names (`terminal.read`'s
/// reply), merged into its transcript. False when it was no stitched read or
/// could not be placed.
pub(crate) fn seeded_of(ident: &Ident, reply: &Value) -> bool {
    if reply["how"] != "stitched" {
        return false;
    }
    let Some(text) = reply["text"].as_str() else {
        return false;
    };
    let mut store = lock(transcripts());
    transcribed(&mut store, ident).transcript.seed(text, reply["complete"] == true)
}

#[cfg(test)]
pub(crate) fn transcript_rows(boot: &str, pane: &str) -> Option<Vec<String>> {
    let store = lock(transcripts());
    let ident = Ident { boot: boot.into(), pane: pane.into(), ..Ident::default() };
    let kept = store.iter().find(|kept| kept.is(&ident))?;
    Some(kept.transcript.reader_rows(kept.transcript.screen()).map(|r| r.text.clone()).collect())
}
/// `pane`'s copy, as session `id` last showed it: the phone's copy of that
/// conversation, for reading it later with no link (the phone seals it and
/// writes it). The grid is the newest that session left: its live one while
/// it shows `pane`, else the one kept when a switch left it (freed once
/// copied), else the one kept when it closed. Grids of other sessions never
/// count, even for the same pane id. A full-screen program's copy is the
/// phone's transcript of it, then its screen. None when there is none. The
/// grid is copied under its lock, never on the UI thread.
pub fn copy_pane(id: u64, pane: &str) -> Result<Option<Vec<u8>>> {
    if pane.is_empty() {
        return Ok(None);
    }
    let live = lock(sessions()).get(&id).cloned().and_then(|owner| {
        let state = lock(&owner.state);
        if state.view["pane_id"] == pane {
            let me = ident(&state.view, pane);
            let agent = agent_ident(&state.view, pane);
            state.terminal.clone().map(|terminal| (terminal, me, agent))
        } else {
            None
        }
    });
    let switched = || {
        let mut kept = lock(departed());
        let at = kept.iter().position(|kept| kept.from(id, pane))?;
        kept.remove(at).map(|kept| (kept.terminal, kept.ident, kept.agent))
    };
    let last_closed = || {
        lock(closed())
            .as_ref()
            .filter(|kept| kept.from(id, pane))
            .map(|kept| (kept.terminal.clone(), kept.ident.clone(), kept.agent.clone()))
    };
    let Some((terminal, mut me, agent)) = live.or_else(switched).or_else(last_closed) else {
        return Ok(None);
    };
    if me.pane.is_empty() {
        me.pane = pane.to_owned();
    }
    // Only the transcript of the agent this grid ran is its copy: never
    // another terminal's, nor an agent's under the pager that followed it.
    let mut store = lock(transcripts());
    let grid = terminal.grid().lock();
    let transcript = agent.and_then(|agent| store.iter_mut().find(|kept| kept.is(&agent))).map(|kept| {
        if grid.mode().contains(rio_vt::crosswords::Mode::ALT_SCREEN) {
            // The very screen copied, so history and screen join exactly.
            kept.transcript.observe(crate::transcript::screen_rows(&grid), grid.columns());
        }
        &kept.transcript
    });
    Ok(Some(crate::snapshot::copy_of(&grid, &me, transcript)?))
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
/// Test stand-in for the phone's open: [`open_saved`] of `path`'s bytes.
#[cfg(test)]
pub(crate) fn open_saved_file(path: &str) -> Result<u64> {
    open_saved(std::fs::read(path)?)
}
/// Reads a saved pane (its file's bytes, opened by the phone) and makes it
/// the held frame, drawn under a new render id (returned) until a live pane
/// attaches. It scrolls like any held frame. A full-screen program's
/// transcript in it is what the phone keeps adding to once that pane is live
/// again on the same core. Never on the UI thread.
pub fn open_saved(bytes: Vec<u8>) -> Result<u64> {
    let saved = crate::snapshot::parse(bytes)?;
    let ident = saved.ident();
    if let Some(transcript) = crate::snapshot::transcript_of(&saved) {
        adopt(&ident, transcript);
    }
    let key = (!ident.boot.is_empty()).then_some(ident);
    let terminal = crate::snapshot::restore(saved)?;
    let id = crate::core_session::next_render_id();
    *lock(cached()) = Some((id, terminal.clone()));
    hold_as(id, terminal, key);
    Ok(id)
}
/// Keeps a full-screen pane's transcript from its saved copy (the file's
/// bytes), drawing nothing: a live session that just switched to the pane
/// scrolls back through it at once. A newer transcript already kept wins.
pub fn adopt_saved(bytes: Vec<u8>) -> Result<()> {
    let saved = crate::snapshot::parse(bytes)?;
    if let Some(transcript) = crate::snapshot::transcript_of(&saved) {
        adopt(&saved.ident(), transcript);
    }
    Ok(())
}
/// Makes `pane`'s stand-in the held frame when it has no saved copy: `title`
/// and one dim `note` line on a `cols`×`rows` grid, drawn under a new render id
/// (returned) until a live pane attaches. Never touches a session or the disk.
pub fn hold_note(pane: &str, title: &str, note: &str, cols: u16, rows: u16) -> Result<u64> {
    let terminal = crate::snapshot::restore(crate::snapshot::Saved::of(
        cols.clamp(20, 400),
        rows.clamp(5, 200),
        pane,
        crate::snapshot::note(title, note),
    ))?;
    let id = crate::core_session::next_render_id();
    *lock(cached()) = Some((id, terminal.clone()));
    hold_as(id, terminal, None);
    Ok(id)
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
    // Not before the pane's own frame is drawn: until then the screen still
    // shows the pane switched from, and keys meant for it must not go here.
    if state.view["pane_id"] != terminal || state.view["state"] != "ready" || !state.local.settled {
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
    let (terminal, agent, render_id) = {
        let mut state = lock(&owner.state);
        // Being read back: the phone's own copy scrolls, nothing is sent; at
        // its newest row the live grid is drawn again.
        if let Some((_, reader)) = &state.local.reader {
            let offset = {
                let mut grid = reader.grid().lock();
                grid.scroll_display(scroll);
                grid.display_offset()
            };
            if offset == 0 && delta < 0 {
                state.local.reader = None;
                show_render(Some(paint.generation), state.render_id, None, None);
            }
            return Ok(json!({"queued":true,"local":true}).to_string());
        }
        // The copy is being built: these rows are applied when it is shown.
        if let Some(pending) = &mut state.local.building {
            *pending = pending.saturating_add(delta);
            return Ok(json!({"queued":true,"local":true}).to_string());
        }
        let Some(terminal) = state.terminal.clone() else {
            return Ok(json!({"queued":true}).to_string());
        };
        let pane = state.view["pane_id"].as_str().unwrap_or("").to_owned();
        // A full-screen agent (Claude Code) keeps its history to itself:
        // dragging back scrolls the phone's transcript of it, at once and
        // with no round trip (item 77, ask 2). Without one yet, it gets wheel
        // input as before; plain output scrolls the attach's local history.
        let agent = (delta > 0).then(|| agent_ident(&state.view, &pane)).flatten();
        if agent.is_some() {
            state.local.building = Some(0);
        }
        (terminal, agent, state.render_id)
    };
    if let Some(agent) = agent {
        // Built with the session's lock released: the poll (and the UI
        // waiting on it) never waits for the copy to be encoded and parsed.
        let built = reader_for(&agent, &terminal);
        let mut state = lock(&owner.state);
        let pending = state.local.building.take().unwrap_or(0);
        let same = state.render_id == render_id && state.local.reader.is_none() && state.terminal.is_some();
        if let (Some(reader), true) = (built, same) {
            reader.grid().lock().scroll_display(rio_vt::crosswords::grid::Scroll::Delta(delta.saturating_add(pending)));
            let shown = crate::core_session::next_render_id();
            let host = state.local.host.clone();
            state.local.reader = Some((shown, reader));
            show_render(Some(paint.generation), shown, None, Some((&host, &agent.pane)));
            return Ok(json!({"queued":true,"local":true}).to_string());
        }
        if !same {
            return Ok(json!({"queued":true,"local":true}).to_string());
        }
    }
    if !terminal.wheel(delta) {
        terminal.grid().lock().scroll_display(scroll);
    }
    Ok(json!({"queued":true}).to_string())
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
/// A new run of the app, for one core's panes: nothing watched of them.
#[cfg(test)]
pub(crate) fn forget_transcripts_for_test(boot: &str) {
    lock(transcripts()).retain(|kept| kept.ident.boot != boot);
}
#[cfg(test)]
pub(crate) fn adopt_for_test(boot: &str, pane: &str, transcript: crate::transcript::Transcript) {
    adopt(&Ident { boot: boot.into(), pane: pane.into(), ..Ident::default() }, transcript);
}
#[cfg(test)]
pub(crate) fn held_for_test() -> Option<(u64, Arc<gpui_rio::Session>)> {
    lock(held()).as_ref().map(|held| (held.id, held.terminal.clone()))
}
#[cfg(test)]
impl Local {
    /// A pane attached and painted long ago.
    pub(crate) fn settled_for_test() -> Self {
        Self { settled: true, ..Self::default() }
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
