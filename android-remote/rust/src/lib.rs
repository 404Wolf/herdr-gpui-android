//! Embedded, strictly pinned SSH transport. The caller owns the Tokio runtime.
//! Dropping a session or bridge cancels its network work without joining a thread.

mod iroh_target;

use iroh::{Endpoint, EndpointAddr, RelayMap, RelayMode, endpoint::presets};
pub use iroh_target::{
    IrohTarget, IrohTargetError, MAX_IROH_ADDRESSES, MAX_IROH_RELAY_LEN, MAX_IROH_TARGET_LEN,
};
use russh::{
    ChannelMsg, MethodKind, client,
    keys::{HashAlg, PrivateKeyWithHashAlg, PublicKeyOrCertificate},
};
use std::{
    future::Future,
    io,
    pin::Pin,
    sync::{Arc, Mutex},
    task::{Context, Poll},
    time::Duration,
};
use tokio::io::{AsyncRead, AsyncWrite, ReadBuf};
pub use tokio_util::sync::CancellationToken;
use zeroize::Zeroizing;

pub type Result<T> = std::result::Result<T, Error>;
const CONNECT_TIMEOUT: Duration = Duration::from_secs(15);
const CHANNEL_TIMEOUT: Duration = Duration::from_secs(15);
// rustonbsd/iroh-ssh 0.2.12 forwards one bidirectional stream directly to SSH.
// There is no application header or capability exchange before the SSH banner.
const IROH_SSH_ALPN: &[u8] = b"/iroh/ssh";

#[derive(Debug, thiserror::Error)]
pub enum Error {
    #[error("operation cancelled")]
    Cancelled,
    #[error("operation timed out")]
    Timeout,
    #[error("this transport is not supported yet")]
    UnsupportedTransport,
    #[error("a SHA256 host-key fingerprint is required before authentication")]
    MissingFingerprint,
    #[error("the server host key does not match the saved fingerprint")]
    HostKeyMismatch,
    #[error("the SSH server rejected authentication")]
    AuthenticationRejected,
    #[error(
        "SSH accepted this credential but requires additional authentication; multi-factor authentication is not supported"
    )]
    AuthenticationIncomplete,
    #[error(
        "the SSH server does not offer {method} authentication at this stage; choose a supported method or check the server authentication policy"
    )]
    AuthenticationMethodUnavailable { method: &'static str },
    #[error("the SSH server rejected the command")]
    CommandRejected,
    #[error("the SSH channel closed before an exit status was received")]
    MissingExitStatus,
    #[error("remote output exceeded its {stream} byte limit ({limit})")]
    OutputLimit { stream: &'static str, limit: usize },
    #[error("invalid transport configuration: {0}")]
    InvalidConfig(&'static str),
    #[error(transparent)]
    IrohTarget(#[from] IrohTargetError),
    #[error("SSH transport failed")]
    Ssh(#[from] russh::Error),
    #[error("SSH private key could not be decoded")]
    PrivateKey(#[from] russh::keys::Error),
    #[error("transport I/O failed")]
    Io(#[from] io::Error),
    #[error("Iroh endpoint could not start")]
    IrohBind(#[from] iroh::endpoint::BindError),
    #[error("Iroh connection failed")]
    IrohConnect(#[from] iroh::endpoint::ConnectError),
    #[error("Iroh stream failed")]
    IrohStream(#[from] iroh::endpoint::ConnectionError),
}

/// A new Ed25519 identity made on this device: the unencrypted OpenSSH private
/// key (for the caller's own encrypted store) and its `ssh-ed25519 AAAA… comment`
/// line for authorized_keys. The seed comes from the OS RNG and is wiped.
pub fn generate_ed25519(comment: &str) -> Result<(Zeroizing<String>, String)> {
    use ring::rand::SecureRandom;
    use russh::keys::ssh_key::{LineEnding, private::Ed25519Keypair};
    let mut seed = Zeroizing::new([0u8; 32]);
    ring::rand::SystemRandom::new()
        .fill(seed.as_mut())
        .map_err(|_| Error::Io(io::Error::other("system random number generator failed")))?;
    let mut key = russh::keys::PrivateKey::from(Ed25519Keypair::from_seed(&seed));
    key.set_comment(comment);
    let private = key
        .to_openssh(LineEnding::LF)
        .map_err(russh::keys::Error::from)?;
    let public = key
        .public_key()
        .to_openssh()
        .map_err(russh::keys::Error::from)?;
    Ok((private, public))
}

#[derive(Clone, Debug)]
pub enum Transport {
    Tcp { host: String, port: u16 },
    Iroh { target: IrohTarget },
}

#[derive(Clone, Debug)]
pub struct Config {
    pub transport: Transport,
    pub username: String,
    pub expected_fingerprint: Option<String>,
}

/// Secrets are never formatted by this type, and its owned buffers zeroize on drop.
pub struct Credentials(CredentialKind);
enum CredentialKind {
    Password(Zeroizing<String>),
    PrivateKey {
        encoded: Zeroizing<String>,
        passphrase: Option<Zeroizing<String>>,
    },
}
impl Credentials {
    pub fn password(password: String) -> Self {
        Self(CredentialKind::Password(Zeroizing::new(password)))
    }
    pub fn private_key(encoded: String, passphrase: Option<String>) -> Self {
        Self(CredentialKind::PrivateKey {
            encoded: Zeroizing::new(encoded),
            passphrase: passphrase.map(Zeroizing::new),
        })
    }
}

#[derive(Clone, Copy, Debug)]
pub struct ExecLimits {
    pub timeout: Duration,
    pub max_stdout_bytes: usize,
    pub max_stderr_bytes: usize,
}
impl Default for ExecLimits {
    fn default() -> Self {
        Self {
            timeout: Duration::from_secs(15),
            max_stdout_bytes: 1024 * 1024,
            max_stderr_bytes: 64 * 1024,
        }
    }
}
#[derive(Debug)]
pub struct ExecOutput {
    pub stdout: Vec<u8>,
    pub stderr: Vec<u8>,
    pub exit_status: u32,
}

struct HostKeyHandler {
    expected: Option<String>,
    observed: Arc<Mutex<Option<String>>>,
}
impl client::Handler for HostKeyHandler {
    type Error = russh::Error;
    async fn check_server_key(
        &mut self,
        key: &PublicKeyOrCertificate,
    ) -> std::result::Result<bool, Self::Error> {
        let fingerprint = key.public_key().fingerprint(HashAlg::Sha256).to_string();
        if let Ok(mut observed) = self.observed.lock() {
            *observed = Some(fingerprint.clone());
        }
        Ok(self.expected.as_deref() == Some(fingerprint.as_str()))
    }
}

/// Cancels the underlying socket even when russh owns a background task.
struct ConnectionGuard(CancellationToken, Option<tokio::task::JoinHandle<()>>);
impl ConnectionGuard {
    async fn shutdown(&mut self) {
        self.0.cancel();
        if let Some(task) = self.1.take() {
            let _ = task.await;
        }
    }
}
impl Drop for ConnectionGuard {
    fn drop(&mut self) {
        self.0.cancel();
        // Detach the Iroh close task so QUIC can drain close packets. Joining or
        // aborting it here would block the UI or strand the remote SSH socket.
    }
}
struct CancelStream<S> {
    stream: S,
    cancelled: Pin<Box<dyn Future<Output = ()> + Send>>,
}
impl<S: AsyncRead + Unpin> AsyncRead for CancelStream<S> {
    fn poll_read(
        mut self: Pin<&mut Self>,
        cx: &mut Context<'_>,
        buf: &mut ReadBuf<'_>,
    ) -> Poll<io::Result<()>> {
        if self.cancelled.as_mut().poll(cx).is_ready() {
            return Poll::Ready(Ok(()));
        }
        Pin::new(&mut self.stream).poll_read(cx, buf)
    }
}
impl<S: AsyncWrite + Unpin> AsyncWrite for CancelStream<S> {
    fn poll_write(
        mut self: Pin<&mut Self>,
        cx: &mut Context<'_>,
        bytes: &[u8],
    ) -> Poll<io::Result<usize>> {
        if self.cancelled.as_mut().poll(cx).is_ready() {
            return Poll::Ready(Err(io::Error::new(
                io::ErrorKind::BrokenPipe,
                "SSH cancelled",
            )));
        }
        Pin::new(&mut self.stream).poll_write(cx, bytes)
    }
    fn poll_flush(mut self: Pin<&mut Self>, cx: &mut Context<'_>) -> Poll<io::Result<()>> {
        if self.cancelled.as_mut().poll(cx).is_ready() {
            return Poll::Ready(Ok(()));
        }
        Pin::new(&mut self.stream).poll_flush(cx)
    }
    fn poll_shutdown(mut self: Pin<&mut Self>, cx: &mut Context<'_>) -> Poll<io::Result<()>> {
        Pin::new(&mut self.stream).poll_shutdown(cx)
    }
}

async fn bounded<T>(
    token: &CancellationToken,
    timeout: Duration,
    work: impl Future<Output = Result<T>>,
) -> Result<T> {
    tokio::select! {
        biased;
        _ = token.cancelled() => Err(Error::Cancelled),
        result = tokio::time::timeout(timeout, work) => result.map_err(|_| Error::Timeout)?,
    }
}

enum DialTarget {
    Tcp {
        host: String,
        port: u16,
    },
    Iroh {
        endpoint: Endpoint,
        address: EndpointAddr,
    },
}

// Tuple-based DNS resolution expects bare IPv6, unlike a host:port string.
// Only strip brackets after validating an IPv6 literal; never reinterpret a URL.
fn tcp_host(host: &str) -> Result<&str> {
    if host.contains(['[', ']']) {
        let literal = host
            .strip_prefix('[')
            .and_then(|value| value.strip_suffix(']'))
            .filter(|value| value.parse::<std::net::Ipv6Addr>().is_ok())
            .ok_or(Error::InvalidConfig(
                "brackets require an IPv6 address without a port",
            ))?;
        return Ok(literal);
    }
    Ok(host)
}

fn authentication_result(result: client::AuthResult, attempted: MethodKind) -> Result<()> {
    match result {
        client::AuthResult::Success => Ok(()),
        client::AuthResult::Failure {
            partial_success: true,
            ..
        } => Err(Error::AuthenticationIncomplete),
        client::AuthResult::Failure {
            remaining_methods, ..
        } if !remaining_methods.is_empty() && !remaining_methods.contains(&attempted) => {
            Err(Error::AuthenticationMethodUnavailable {
                method: (&attempted).into(),
            })
        }
        client::AuthResult::Failure { .. } => Err(Error::AuthenticationRejected),
    }
}

async fn dial_target(config: &Config) -> Result<DialTarget> {
    match &config.transport {
        Transport::Tcp { host, port } if !host.trim().is_empty() && *port != 0 => {
            Ok(DialTarget::Tcp {
                host: tcp_host(host)?.to_owned(),
                port: *port,
            })
        }
        Transport::Tcp { .. } => Err(Error::InvalidConfig("host and port are required")),
        Transport::Iroh { target } => {
            // Only the explicit Iroh connection path starts discovery or relay work.
            let endpoint = match target.relay() {
                // The user's relay alone: no N0 relays, address lookup or publishing.
                Some(relay) => {
                    Endpoint::builder(presets::Minimal)
                        .relay_mode(RelayMode::Custom(RelayMap::from(relay.clone())))
                        .bind()
                        .await?
                }
                // A ticket's addresses are dialled alongside N0 discovery and relays.
                None => Endpoint::bind(presets::N0).await?,
            };
            Ok(DialTarget::Iroh {
                endpoint,
                address: target.address(),
            })
        }
    }
}
async fn handshake(
    target: impl Future<Output = Result<DialTarget>>,
    token: &CancellationToken,
    expected: Option<String>,
    observed: Arc<Mutex<Option<String>>>,
) -> Result<(client::Handle<HostKeyHandler>, ConnectionGuard)> {
    let owned = token.child_token();
    let mut guard = ConnectionGuard(owned.clone(), None);
    let result = bounded(token, CONNECT_TIMEOUT, async {
        let handle = match target.await? {
            DialTarget::Tcp { host, port } => {
                let stream = tokio::net::TcpStream::connect((host.as_str(), port)).await?;
                stream.set_nodelay(true)?;
                ssh_handshake(stream, owned, expected, observed).await?
            }
            DialTarget::Iroh { endpoint, address } => {
                // Fully complete the QUIC handshake; never use replayable 0-RTT.
                let connection = endpoint.connect(address, IROH_SSH_ALPN).await?;
                let lifetime = owned.clone();
                let closing = connection.clone();
                guard.1 = Some(tokio::spawn(async move {
                    // Own both handles through the whole session/bridge lifetime.
                    lifetime.cancelled().await;
                    closing.close(0u32.into(), b"SSH session closed");
                    let _ = tokio::time::timeout(Duration::from_secs(4), endpoint.close()).await;
                }));
                let (send, recv) = connection.open_bi().await?;
                // Writing SSH's banner opens the stream at the peer. Waiting for a
                // server banner first would deadlock the raw-stream receiver.
                ssh_handshake(tokio::io::join(recv, send), owned, expected, observed).await?
            }
        };
        Ok(handle)
    })
    .await;
    match result {
        Ok(handle) => Ok((handle, guard)),
        Err(error) => {
            // Inspection rejects the key deliberately. Finish QUIC closure before
            // returning even when the caller destroys its runtime immediately.
            guard.shutdown().await;
            Err(error)
        }
    }
}

async fn ssh_handshake<S: AsyncRead + AsyncWrite + Unpin + Send + 'static>(
    stream: S,
    owned: CancellationToken,
    expected: Option<String>,
    observed: Arc<Mutex<Option<String>>>,
) -> Result<client::Handle<HostKeyHandler>> {
    let stream = CancelStream {
        stream,
        cancelled: Box::pin(owned.cancelled_owned()),
    };
    let settings = client::Config {
        window_size: 256 * 1024,
        channel_buffer_size: 8,
        nodelay: true,
        ..Default::default()
    };
    let handler = HostKeyHandler { expected, observed };
    Ok(client::connect_stream(Arc::new(settings), stream, handler).await?)
}

/// Observes and rejects the server key, without attempting any authentication.
/// The caller must obtain independent approval before persisting the returned key.
pub async fn inspect_host_key(config: &Config, token: CancellationToken) -> Result<String> {
    inspect_with_target(dial_target(config), token).await
}

async fn inspect_with_target(
    target: impl Future<Output = Result<DialTarget>>,
    token: CancellationToken,
) -> Result<String> {
    let observed = Arc::new(Mutex::new(None));
    let result = handshake(target, &token, None, observed.clone()).await;
    if token.is_cancelled() {
        return Err(Error::Cancelled);
    }
    if let Some(fingerprint) = observed.lock().ok().and_then(|value| value.clone()) {
        return Ok(fingerprint);
    }
    match result {
        Err(error) => Err(error),
        Ok(_) => Err(Error::HostKeyMismatch),
    }
}

pub struct Session {
    handle: client::Handle<HostKeyHandler>,
    guard: ConnectionGuard,
}

/// Rejects unknown or changed keys before sending credentials. No reconnect/replay.
pub async fn connect(
    config: &Config,
    credentials: Credentials,
    token: CancellationToken,
) -> Result<Session> {
    connect_with_target(config, credentials, token, dial_target(config)).await
}

async fn connect_with_target(
    config: &Config,
    credentials: Credentials,
    token: CancellationToken,
    target: impl Future<Output = Result<DialTarget>>,
) -> Result<Session> {
    let expected = config
        .expected_fingerprint
        .as_deref()
        .filter(|value| value.starts_with("SHA256:"))
        .ok_or(Error::MissingFingerprint)?;
    if config.username.trim().is_empty() {
        return Err(Error::InvalidConfig("username is required"));
    }
    let observed = Arc::new(Mutex::new(None));
    let result = handshake(target, &token, Some(expected.to_owned()), observed.clone()).await;
    let (mut handle, mut guard) = match result {
        Ok(connection) => connection,
        Err(error) => {
            if observed
                .lock()
                .ok()
                .and_then(|value| value.clone())
                .is_some_and(|actual| actual != expected)
            {
                return Err(Error::HostKeyMismatch);
            }
            return Err(error);
        }
    };
    let authentication = bounded(&guard.0, CONNECT_TIMEOUT, async {
        let attempted = match &credentials.0 {
            CredentialKind::Password(_) => MethodKind::Password,
            CredentialKind::PrivateKey { .. } => MethodKind::PublicKey,
        };
        let result = match credentials.0 {
            CredentialKind::Password(password) => {
                handle
                    .authenticate_password(&config.username, password.as_str())
                    .await?
            }
            CredentialKind::PrivateKey {
                encoded,
                passphrase,
            } => {
                let key = russh::keys::decode_secret_key(
                    &encoded,
                    passphrase.as_ref().map(|secret| secret.as_str()),
                )?;
                // Never fall back to legacy SHA-1 signatures for RSA keys.
                let hash = Some(
                    handle
                        .best_supported_rsa_hash()
                        .await?
                        .flatten()
                        .unwrap_or(HashAlg::Sha256),
                );
                handle
                    .authenticate_publickey(
                        &config.username,
                        PrivateKeyWithHashAlg::new(Arc::new(key), hash),
                    )
                    .await?
            }
        };
        authentication_result(result, attempted)
    })
    .await;
    if let Err(error) = authentication {
        guard.shutdown().await;
        return Err(error);
    }
    Ok(Session { handle, guard })
}

fn append_bounded(
    target: &mut Vec<u8>,
    bytes: &[u8],
    limit: usize,
    stream: &'static str,
) -> Result<()> {
    if bytes.len() > limit.saturating_sub(target.len()) {
        return Err(Error::OutputLimit { stream, limit });
    }
    target.extend_from_slice(bytes);
    Ok(())
}

impl Session {
    /// Close and drain transport resources before destroying the caller's runtime.
    /// Call this on a background thread; ordinary Drop remains nonblocking.
    pub async fn shutdown(mut self) {
        self.guard.shutdown().await;
    }

    pub async fn exec_bounded(&mut self, command: &str, limits: ExecLimits) -> Result<ExecOutput> {
        let result = bounded(&self.guard.0, limits.timeout, async {
            let mut channel = self.handle.channel_open_session().await?;
            channel.exec(true, command.as_bytes()).await?;
            channel.eof().await?;
            let mut stdout = Vec::new();
            let mut stderr = Vec::new();
            let mut status = None;
            while let Some(message) = channel.wait().await {
                match message {
                    ChannelMsg::Data { data } => {
                        append_bounded(&mut stdout, &data, limits.max_stdout_bytes, "stdout")?
                    }
                    ChannelMsg::ExtendedData { data, .. } => {
                        append_bounded(&mut stderr, &data, limits.max_stderr_bytes, "stderr")?
                    }
                    ChannelMsg::ExitStatus { exit_status } => status = Some(exit_status),
                    ChannelMsg::Failure => return Err(Error::CommandRejected),
                    ChannelMsg::Close => break,
                    _ => {}
                }
            }
            Ok(ExecOutput {
                stdout,
                stderr,
                exit_status: status.ok_or(Error::MissingExitStatus)?,
            })
        })
        .await;
        // Failed operations must not leave a remote command running behind the UI.
        if result.is_err() {
            self.guard.0.cancel();
        }
        result
    }

    #[cfg(unix)]
    pub async fn into_bridge(mut self, command: &str) -> Result<Bridge> {
        let prepared = bounded(&self.guard.0, CHANNEL_TIMEOUT, async {
            let mut channel = self.handle.channel_open_session().await?;
            channel.exec(true, command.as_bytes()).await?;
            expect_success(&mut channel).await?;
            let (socket, pump_socket) = std::os::unix::net::UnixStream::pair()?;
            pump_socket.set_nonblocking(true)?;
            let pump_socket = tokio::net::UnixStream::from_std(pump_socket)?;
            Ok((channel, socket, pump_socket))
        })
        .await;
        let (channel, socket, mut pump_socket) = match prepared {
            Ok(prepared) => prepared,
            Err(error) => {
                self.shutdown().await;
                return Err(error);
            }
        };
        let token = self.guard.0.clone();
        let cancel = token.clone();
        let iroh_close = self.guard.1.take();
        let task = tokio::spawn(async move {
            // Session and its socket guard remain owned until the pump finishes.
            let _session = self;
            let mut stream = channel.into_stream();
            tokio::select! {
                biased;
                _ = cancel.cancelled() => {},
                _ = tokio::io::copy_bidirectional(&mut pump_socket, &mut stream) => {},
            }
        });
        Ok(Bridge {
            socket,
            guard: BridgeGuard {
                token,
                task,
                iroh_close,
            },
        })
    }

    /// An interactive login shell on a remote PTY of `columns` × `rows`: the
    /// raw terminal bytes, not Herdr's protocol. The returned socket carries
    /// them both ways; [`ShellSize`] changes the PTY's window size.
    #[cfg(unix)]
    pub async fn into_shell(mut self, term: &str, columns: u32, rows: u32) -> Result<Shell> {
        let prepared = bounded(&self.guard.0, CHANNEL_TIMEOUT, async {
            let mut channel = self.handle.channel_open_session().await?;
            channel
                .request_pty(true, term, columns, rows, 0, 0, &[])
                .await?;
            expect_success(&mut channel).await?;
            channel.request_shell(true).await?;
            expect_success(&mut channel).await?;
            let (socket, pump_socket) = std::os::unix::net::UnixStream::pair()?;
            pump_socket.set_nonblocking(true)?;
            let pump_socket = tokio::net::UnixStream::from_std(pump_socket)?;
            Ok((channel, socket, pump_socket))
        })
        .await;
        let (channel, socket, pump_socket) = match prepared {
            Ok(prepared) => prepared,
            Err(error) => {
                self.shutdown().await;
                return Err(error);
            }
        };
        let (size, mut resized) = tokio::sync::watch::channel((columns, rows));
        let token = self.guard.0.clone();
        let cancel = token.clone();
        let iroh_close = self.guard.1.take();
        let task = tokio::spawn(async move {
            use tokio::io::{AsyncReadExt, AsyncWriteExt};
            // Session and its socket guard remain owned until the pump finishes.
            let _session = self;
            let (mut remote, local) = channel.split();
            let (mut from_app, mut to_app) = pump_socket.into_split();
            let mut buffer = vec![0; 32 * 1024];
            let mut resizable = true;
            loop {
                tokio::select! {
                    biased;
                    _ = cancel.cancelled() => break,
                    message = remote.wait() => match message {
                        Some(ChannelMsg::Data { data } | ChannelMsg::ExtendedData { data, .. }) => {
                            if to_app.write_all(&data).await.is_err() {
                                break;
                            }
                        }
                        Some(ChannelMsg::Eof | ChannelMsg::Close) | None => break,
                        Some(_) => {}
                    },
                    read = from_app.read(&mut buffer) => match read {
                        Ok(0) | Err(_) => break,
                        Ok(read) => {
                            if local.data(&buffer[..read]).await.is_err() {
                                break;
                            }
                        }
                    },
                    changed = resized.changed(), if resizable => {
                        if changed.is_err() {
                            // The size handle is gone; the PTY keeps its last size.
                            resizable = false;
                            continue;
                        }
                        let (columns, rows) = *resized.borrow_and_update();
                        if local.window_change(columns, rows, 0, 0).await.is_err() {
                            break;
                        }
                    }
                }
            }
        });
        Ok(Shell {
            socket,
            size: ShellSize(size),
            guard: BridgeGuard {
                token,
                task,
                iroh_close,
            },
        })
    }
}

/// Waits for the server to accept a channel request; refusal is an error.
async fn expect_success(channel: &mut russh::Channel<client::Msg>) -> Result<()> {
    loop {
        match channel.wait().await {
            Some(ChannelMsg::Success) => return Ok(()),
            Some(ChannelMsg::WindowAdjusted { .. }) => {}
            _ => return Err(Error::CommandRejected),
        }
    }
}

#[cfg(unix)]
pub struct Bridge {
    socket: std::os::unix::net::UnixStream,
    guard: BridgeGuard,
}
#[cfg(unix)]
impl Bridge {
    pub fn into_parts(self) -> (std::os::unix::net::UnixStream, BridgeGuard) {
        (self.socket, self.guard)
    }
}

/// A remote shell's PTY bytes, its window size and the guard that ends it.
#[cfg(unix)]
pub struct Shell {
    socket: std::os::unix::net::UnixStream,
    size: ShellSize,
    guard: BridgeGuard,
}
#[cfg(unix)]
impl Shell {
    pub fn into_parts(self) -> (std::os::unix::net::UnixStream, ShellSize, BridgeGuard) {
        (self.socket, self.size, self.guard)
    }
}

/// Changes a remote shell's window size. Only the newest pending size is sent.
#[cfg(unix)]
#[derive(Clone)]
pub struct ShellSize(tokio::sync::watch::Sender<(u32, u32)>);
#[cfg(unix)]
impl ShellSize {
    pub fn resize(&self, columns: u32, rows: u32) {
        // No receiver means the shell already ended; there is nothing to resize.
        let _ = self.0.send((columns, rows));
    }
}
#[cfg(unix)]
pub struct BridgeGuard {
    token: CancellationToken,
    task: tokio::task::JoinHandle<()>,
    iroh_close: Option<tokio::task::JoinHandle<()>>,
}
#[cfg(unix)]
impl BridgeGuard {
    /// Stop the byte pump and finish bounded QUIC shutdown before its runtime ends.
    pub async fn shutdown(mut self) {
        self.token.cancel();
        self.task.abort();
        let _ = (&mut self.task).await;
        if let Some(task) = self.iroh_close.take() {
            let _ = task.await;
        }
    }
}
#[cfg(unix)]
impl Drop for BridgeGuard {
    fn drop(&mut self) {
        self.token.cancel();
        self.task.abort();
    }
}

#[cfg(test)]
mod tests;
