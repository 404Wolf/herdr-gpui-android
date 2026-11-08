//! Bounded diffd HTTP and WebSocket traffic on the existing authenticated SSH session.
use crate::remote::{self, lock};
use herdr_remote_transport::{CancellationToken, LoopbackService};
use serde_json::{Value, json};
use std::{
    collections::VecDeque,
    io::{self, Read, Write},
    sync::{
        Arc, Mutex,
        atomic::{AtomicU64, AtomicUsize, Ordering},
        mpsc,
    },
    time::{Duration, Instant},
};
use tungstenite::{Message, protocol::WebSocketConfig};
const MAX_BYTES: usize = 16 * 1024 * 1024;
const MAX_EVENTS: usize = 64;
static NEXT: AtomicU64 = AtomicU64::new(1);
static WATCHERS: AtomicUsize = AtomicUsize::new(0);
struct WatchPermit;
impl WatchPermit {
    fn acquire() -> Result<Self> {
        WATCHERS
            .fetch_update(Ordering::AcqRel, Ordering::Acquire, |count| {
                (count < 16).then_some(count + 1)
            })
            .map(|_| Self)
            .map_err(|_| Error::Capacity)
    }
}
impl Drop for WatchPermit {
    fn drop(&mut self) {
        WATCHERS.fetch_sub(1, Ordering::AcqRel);
    }
}
#[derive(Debug, thiserror::Error)]
pub enum Error {
    #[error("diffd is not connected")]
    Disconnected,
    #[error("diffd size or queue limit reached")]
    Capacity,
    #[error("Invalid diffd response")]
    Protocol,
    #[error("diffd returned HTTP {0}")]
    Http(u16),
    #[error("diffd I/O failed")]
    Io(#[from] io::Error),
    #[error("diffd SSH forwarding failed")]
    Transport(#[from] herdr_remote_transport::Error),
    #[error("diffd WebSocket failed")]
    WebSocket(#[source] Box<tungstenite::Error>),
    #[error("Invalid diffd JSON")]
    Json(#[from] serde_json::Error),
}
impl From<tungstenite::Error> for Error {
    fn from(error: tungstenite::Error) -> Self {
        Self::WebSocket(Box::new(error))
    }
}
type Result<T> = std::result::Result<T, Error>;
#[derive(Clone)]
struct Connection {
    service: LoopbackService,
    runtime: tokio::runtime::Handle,
    cancel: CancellationToken,
}
impl Connection {
    fn open(&self) -> Result<std::os::unix::net::UnixStream> {
        if self.cancel.is_cancelled() {
            return Err(Error::Disconnected);
        }
        let socket = self.runtime.block_on(async {
            tokio::select! {
                biased;
                _ = self.cancel.cancelled() => Err(herdr_remote_transport::Error::Cancelled),
                result = self.service.open() => result,
            }
        })?;
        socket.set_read_timeout(Some(Duration::from_secs(10)))?;
        socket.set_write_timeout(Some(Duration::from_secs(5)))?;
        Ok(socket)
    }
}
struct Feed {
    id: u64,
    cancel: CancellationToken,
    events: Arc<Mutex<VecDeque<String>>>,
    send: mpsc::SyncSender<String>,
}
impl Drop for Feed {
    fn drop(&mut self) {
        self.cancel.cancel();
    }
}
#[derive(Default)]
pub(crate) struct Client {
    connection: Option<Connection>,
    feed: Option<Feed>,
}
impl Client {
    pub(crate) fn connect(
        &mut self,
        service: LoopbackService,
        runtime: tokio::runtime::Handle,
        cancel: CancellationToken,
    ) {
        self.connection = Some(Connection {
            service,
            runtime,
            cancel,
        });
    }
}

pub fn list(handle: u64) -> remote::Result<String> {
    let owner = remote::session(handle)?;
    let connection = lock(&owner.reviews)
        .connection
        .clone()
        .ok_or(Error::Disconnected)?;
    let mut socket = connection.open()?;
    // HTTP/1.0 bounds the parser to a close-delimited body, without chunking or
    // redirects. Host is the actual destination, never a remote-supplied URL.
    socket.write_all(
        b"GET /api/reviews HTTP/1.0\r\nHost: 127.0.0.1:3433\r\nConnection: close\r\n\r\n",
    )?;
    let deadline = Instant::now() + Duration::from_secs(15);
    struct Deadline<'a>(&'a mut std::os::unix::net::UnixStream, Instant);
    impl Read for Deadline<'_> {
        fn read(&mut self, buffer: &mut [u8]) -> io::Result<usize> {
            let remaining = self
                .1
                .checked_duration_since(Instant::now())
                .ok_or(io::ErrorKind::TimedOut)?;
            self.0
                .set_read_timeout(Some(remaining.min(Duration::from_secs(10))))?;
            self.0.read(buffer)
        }
    }
    let result = read_http(&mut Deadline(&mut socket, deadline))?;
    Ok(serde_json::to_string(&result)?)
}
fn read_http(reader: &mut impl Read) -> Result<Value> {
    let mut bytes = Vec::new();
    reader
        .take((MAX_BYTES + 1) as u64)
        .read_to_end(&mut bytes)?;
    if bytes.len() > MAX_BYTES {
        return Err(Error::Capacity);
    }
    let end = bytes
        .windows(4)
        .position(|w| w == b"\r\n\r\n")
        .filter(|i| *i <= 16 * 1024)
        .ok_or(Error::Protocol)?;
    let headers = std::str::from_utf8(&bytes[..end]).map_err(|_| Error::Protocol)?;
    let mut status_line = headers
        .lines()
        .next()
        .ok_or(Error::Protocol)?
        .split_ascii_whitespace();
    if !matches!(status_line.next(), Some("HTTP/1.0" | "HTTP/1.1")) {
        return Err(Error::Protocol);
    }
    let code = status_line
        .next()
        .filter(|code| code.len() == 3 && code.bytes().all(|b| b.is_ascii_digit()))
        .ok_or(Error::Protocol)?;
    let status = code.parse::<u16>().map_err(|_| Error::Protocol)?;
    if status != 200 {
        return Err(Error::Http(status));
    }
    let body = &bytes[end + 4..];
    for header in headers.lines().skip(1) {
        let (name, value) = header.split_once(':').ok_or(Error::Protocol)?;
        if name.eq_ignore_ascii_case("transfer-encoding") {
            return Err(Error::Protocol);
        }
        if name.eq_ignore_ascii_case("content-length")
            && value.trim().parse::<usize>().ok() != Some(body.len())
        {
            return Err(Error::Protocol);
        }
    }
    let result: Value = serde_json::from_slice(body)?;
    if !result.is_array() {
        return Err(Error::Protocol);
    }
    Ok(result)
}
fn valid_id(id: &str) -> bool {
    !id.is_empty()
        && id.len() <= 128
        && id
            .bytes()
            .all(|b| b.is_ascii_alphanumeric() || b == b'-' || b == b'_')
}
pub fn watch(handle: u64, review: &str) -> remote::Result<String> {
    if !valid_id(review) {
        return Err(Error::Protocol.into());
    }
    let owner = remote::session(handle)?;
    let mut client = lock(&owner.reviews);
    let mut connection = client.connection.clone().ok_or(Error::Disconnected)?;
    client.feed = None;
    let id = NEXT.fetch_add(1, Ordering::Relaxed);
    let cancel = connection.cancel.child_token();
    connection.cancel = cancel.clone();
    let events = Arc::new(Mutex::new(VecDeque::new()));
    let (tx, rx) = mpsc::sync_channel(32);
    let feed = Feed {
        id,
        cancel: cancel.clone(),
        events: events.clone(),
        send: tx,
    };
    let review = review.to_owned();
    let permit = WatchPermit::acquire()?;
    std::thread::Builder::new()
        .name("diffd-review".into())
        .spawn(move || {
            let _permit = permit;
            if let Err(error) = stream(connection, &review, &cancel, &events, rx) {
                let mut queue = lock(&events);
                queue.clear();
                queue.push_back(
                    json!({"type":"transportError", "message":error.to_string()}).to_string(),
                );
                cancel.cancel();
            }
        })?;
    client.feed = Some(feed);
    Ok(json!({"watch":id}).to_string())
}
fn stream(
    connection: Connection,
    review: &str,
    cancel: &CancellationToken,
    events: &Mutex<VecDeque<String>>,
    commands: mpsc::Receiver<String>,
) -> Result<()> {
    let socket = connection.open()?;
    run_socket(socket, review, cancel, events, commands)
}
fn run_socket(
    socket: std::os::unix::net::UnixStream,
    review: &str,
    cancel: &CancellationToken,
    events: &Mutex<VecDeque<String>>,
    commands: mpsc::Receiver<String>,
) -> Result<()> {
    socket.set_read_timeout(Some(Duration::from_millis(100)))?;
    let config = WebSocketConfig::default()
        .max_message_size(Some(MAX_BYTES))
        .max_frame_size(Some(MAX_BYTES));
    let handshake = tungstenite::client::client_with_config(
        format!("ws://127.0.0.1:3433/api/reviews/{review}/ws"),
        socket,
        Some(config),
    );
    let deadline = Instant::now() + Duration::from_secs(10);
    let mut attempt = handshake;
    let mut ws = loop {
        if cancel.is_cancelled() {
            return Ok(());
        }
        if Instant::now() >= deadline {
            return Err(Error::Io(io::ErrorKind::TimedOut.into()));
        }
        match attempt {
            Ok((ws, _)) => break ws,
            Err(tungstenite::HandshakeError::Failure(e)) => return Err(e.into()),
            Err(tungstenite::HandshakeError::Interrupted(mid)) => attempt = mid.handshake(),
        }
    };
    // Tungstenite retains partially read frames across read timeouts. Never replay
    // outgoing user messages; acknowledgements come from diffd's normal protocol.
    while !cancel.is_cancelled() {
        send_pending(cancel, &commands, |command| {
            ws.send(Message::Text(command.into())).map_err(Error::from)
        })?;
        if cancel.is_cancelled() {
            break;
        }
        match ws.read() {
            Ok(Message::Text(text)) => {
                let _: Value = serde_json::from_str(&text)?;
                push_event(&mut lock(events), text.to_string())?;
            }
            Ok(Message::Close(_)) => return Err(Error::Disconnected),
            Ok(_) => {}
            Err(tungstenite::Error::Io(e))
                if matches!(
                    e.kind(),
                    io::ErrorKind::WouldBlock
                        | io::ErrorKind::TimedOut
                        | io::ErrorKind::Interrupted
                ) => {}
            Err(e) => return Err(e.into()),
        }
    }
    // Closing a review ends only its channel, never the terminal or SSH session.
    Ok(())
}
fn send_pending(
    cancel: &CancellationToken,
    commands: &mpsc::Receiver<String>,
    mut send: impl FnMut(String) -> Result<()>,
) -> Result<()> {
    for command in commands.try_iter().take(32) {
        if cancel.is_cancelled() {
            break;
        }
        send(command)?;
    }
    Ok(())
}
fn push_event(queue: &mut VecDeque<String>, event: String) -> Result<()> {
    if queue.len() >= MAX_EVENTS
        || queue.iter().map(String::len).sum::<usize>() + event.len() > MAX_BYTES
    {
        return Err(Error::Capacity);
    }
    queue.push_back(event);
    Ok(())
}
pub fn poll(handle: u64, watch: u64) -> remote::Result<String> {
    let owner = remote::session(handle)?;
    let client = lock(&owner.reviews);
    let feed = client
        .feed
        .as_ref()
        .filter(|f| f.id == watch)
        .ok_or(Error::Disconnected)?;
    let events: Vec<_> = lock(&feed.events).drain(..).collect();
    Ok(format!("[{}]", events.join(",")))
}
pub fn send(handle: u64, watch: u64, message: &str) -> remote::Result<String> {
    if message.len() > 64 * 1024 {
        return Err(Error::Capacity.into());
    }
    let value: Value = serde_json::from_str(message)?;
    if !matches!(
        value["type"].as_str(),
        Some("comment" | "reply" | "chat" | "resolve" | "drafting" | "read")
    ) {
        return Err(Error::Protocol.into());
    }
    let owner = remote::session(handle)?;
    let client = lock(&owner.reviews);
    let feed = client
        .feed
        .as_ref()
        .filter(|f| f.id == watch && !f.cancel.is_cancelled())
        .ok_or(Error::Disconnected)?;
    feed.send
        .try_send(message.to_owned())
        .map_err(|_| Error::Capacity)?;
    Ok(json!({"queued":true}).to_string())
}
pub fn stop(handle: u64, watch: u64) {
    if let Ok(owner) = remote::session(handle) {
        let mut client = lock(&owner.reviews);
        if client.feed.as_ref().is_some_and(|f| f.id == watch) {
            client.feed = None;
        }
    }
}
#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn websocket_uses_review_endpoint_and_delivers_ordered_messages_once() -> Result<()> {
        let (phone, server) = std::os::unix::net::UnixStream::pair()?;
        server.set_read_timeout(Some(Duration::from_secs(2)))?;
        server.set_write_timeout(Some(Duration::from_secs(2)))?;
        let (tx, rx) = mpsc::sync_channel(2);
        tx.send(r#"{"type":"chat","messageId":"m1","body":"test"}"#.into())
            .map_err(|_| Error::Disconnected)?;
        let peer = std::thread::spawn(move || -> Result<()> {
            struct CheckRequest;
            impl tungstenite::handshake::server::Callback for CheckRequest {
                fn on_request(
                    self,
                    request: &tungstenite::handshake::server::Request,
                    response: tungstenite::handshake::server::Response,
                ) -> std::result::Result<
                    tungstenite::handshake::server::Response,
                    tungstenite::handshake::server::ErrorResponse,
                > {
                    assert_eq!(request.uri().path(), "/api/reviews/r_test/ws");
                    assert_eq!(request.headers()["host"], "127.0.0.1:3433");
                    Ok(response)
                }
            }
            let mut ws =
                tungstenite::accept_hdr(server, CheckRequest).map_err(|_| Error::Protocol)?;
            ws.send(Message::Text(r#"{"type":"state","state":{}}"#.into()))?;
            let sent = ws.read()?;
            assert_eq!(
                sent.to_text()?,
                r#"{"type":"chat","messageId":"m1","body":"test"}"#
            );
            ws.send(Message::Text(r#"{"type":"ack","id":"m1"}"#.into()))?;
            ws.close(None)?;
            Ok(())
        });
        let events = Mutex::new(VecDeque::new());
        let result = run_socket(phone, "r_test", &CancellationToken::new(), &events, rx);
        assert!(matches!(result, Err(Error::Disconnected)));
        peer.join().map_err(|_| Error::Protocol)??;
        let queue = lock(&events);
        assert_eq!(queue.len(), 2);
        assert_eq!(serde_json::from_str::<Value>(&queue[0])?["type"], "state");
        assert_eq!(serde_json::from_str::<Value>(&queue[1])?["type"], "ack");
        Ok(())
    }
    #[test]
    fn cancellation_during_a_write_does_not_flush_the_remaining_commands() -> Result<()> {
        let (tx, rx) = mpsc::sync_channel(2);
        tx.send("first".into()).map_err(|_| Error::Disconnected)?;
        tx.send("second".into()).map_err(|_| Error::Disconnected)?;
        let cancel = CancellationToken::new();
        let mut sent = Vec::new();
        send_pending(&cancel, &rx, |message| {
            sent.push(message);
            cancel.cancel();
            Ok(())
        })?;
        assert_eq!(sent, ["first"]);
        Ok(())
    }
    #[test]
    fn slow_ui_is_bounded_by_event_count_and_total_bytes() -> Result<()> {
        let mut queue = VecDeque::new();
        for _ in 0..MAX_EVENTS {
            push_event(&mut queue, "{}".into())?;
        }
        assert!(matches!(
            push_event(&mut queue, "{}".into()),
            Err(Error::Capacity)
        ));
        queue.clear();
        push_event(&mut queue, "x".repeat(MAX_BYTES))?;
        assert!(matches!(
            push_event(&mut queue, "x".into()),
            Err(Error::Capacity)
        ));
        Ok(())
    }
    #[test]
    fn http_requires_complete_bounded_successful_json_list() {
        assert_eq!(
            read_http(&mut b"HTTP/1.0 200 OK\r\nContent-Length: 2\r\n\r\n[]".as_slice()).ok(),
            Some(json!([]))
        );
        assert!(matches!(
            read_http(&mut b"HTTP/1.0 404 Not Found\r\n\r\nno".as_slice()),
            Err(Error::Http(404))
        ));
        assert!(matches!(
            read_http(&mut b"HTTP/1.0 200 OK\r\nContent-Length: 3\r\n\r\n[]".as_slice()),
            Err(Error::Protocol)
        ));
        assert!(matches!(
            read_http(&mut b"HTTP/1.0 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n[]".as_slice()),
            Err(Error::Protocol)
        ));
        assert!(matches!(
            read_http(&mut b"NOTHTTP 200 OK\r\n\r\n[]".as_slice()),
            Err(Error::Protocol)
        ));
        assert!(read_http(&mut io::repeat(b'x')).is_err());
        let result = read_http(&mut b"HTTP/1.0 200 OK\r\n\r\n[".as_slice());
        assert!(matches!(&result, Err(Error::Json(_))));
        if let Err(error) = result {
            assert!(std::error::Error::source(&error).is_some());
        }
    }
    #[test]
    fn review_id_cannot_change_the_forwarded_endpoint_or_path() {
        assert!(valid_id("r_019-ab"));
        for id in ["", "../other", "a?b", "http://evil", "a\r\nHost:evil", "☃"] {
            assert!(!valid_id(id));
        }
    }
}
