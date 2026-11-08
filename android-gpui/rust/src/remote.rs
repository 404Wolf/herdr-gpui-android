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
}
pub(crate) struct Session {
    pub(crate) state: Mutex<State>,
    pub(crate) cancel: CancellationToken,
    pub(crate) commands: mpsc::SyncSender<Operation>,
}
pub(crate) enum Operation {
    Focus(String),
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
    for owner in lock(sessions()).values() {
        let state = lock(&owner.state);
        if state.render_id == id {
            return state.terminal.clone();
        }
    }
    None
}
pub fn connect_credentials(raw: &str, credentials: &str) -> Result<u64> {
    connect_credentials_grid(raw, credentials, "80,24,8,16", true)
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
    let (tx, rx) = mpsc::sync_channel(256);
    let owner = Arc::new(Session {
        state: Mutex::new(State {
            view: json!({"state":"connecting","status":"Connecting to herdr-core over SSH","boot_id":"","pane_id":"","focused_pane":"","panes":[],"workspaces":[],"tabs":[],"title":"Herdr Core","token":1,"chrome_token":1,"revision":1,"fetch":null,"scroll":{"offset":0,"max":0,"rows":0}}),
            terminal: None,
            render_id: 0,
        }),
        cancel: CancellationToken::new(),
        commands: tx,
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
pub fn close(id: u64) {
    if let Some(owner) = lock(sessions()).remove(&id) {
        owner.cancel.cancel();
    }
}
pub fn poll(id: u64, _since: u64, _mirror: bool) -> Result<String> {
    Ok(lock(&session(id)?.state).view.to_string())
}
#[cfg(feature = "android-ui")]
pub fn poll_terminal(
    id: u64,
    since: u64,
    _chrome: u64,
    generation: i64,
    _name: &str,
    _shortcuts: &str,
) -> Result<String> {
    if generation <= 0 || model::mailbox().state().generation != generation {
        return Err(Error::StaleTarget);
    }
    let owner = session(id)?;
    let state = lock(&owner.state);
    model::mailbox().set_shell(generation, state.render_id);
    let _ = since;
    Ok(state.view.to_string())
}
pub fn focus(id: u64, terminal: &str) -> Result<String> {
    let owner = session(id)?;
    if !lock(&owner.state).view["panes"]
        .as_array()
        .is_some_and(|panes| panes.iter().any(|p| p["id"] == terminal))
    {
        return Err(Error::StaleTarget);
    }
    owner
        .commands
        .try_send(Operation::Focus(terminal.into()))
        .map_err(|_| Error::Capacity)?;
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
    if !matches!(action, "tab.new" | "pane.close") {
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
        terminal
            .grid()
            .lock()
            .scroll_display(rio_vt::crosswords::grid::Scroll::Delta(
                delta.clamp(-1000, 1000) as i32,
            ));
    }
    let _ = (paint.generation, paint.title, paint.status, paint.shortcuts);
    Ok(json!({"queued":true}).to_string())
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
