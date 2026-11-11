//! Raw SSH shells: the host's own login shell on a remote PTY, parsed and drawn
//! by the vendored Rio terminal instead of Herdr's protocol. Herdr's panes,
//! rail and agent status do not exist in this mode; the host's shell does.
use crate::{
    model::{self, Command, Palette},
    remote::{self, Error, Result},
};
use gpui_rio::{KeyPress, Session, TerminalColor, TerminalTheme};
use herdr_remote_transport::{BridgeGuard, CancellationToken};
use std::{
    collections::HashMap,
    sync::{
        Arc, LazyLock, Mutex, MutexGuard, PoisonError,
        atomic::{AtomicU64, Ordering},
    },
    time::Duration,
};

/// Each shell holds an SSH connection, a runtime and a scrollback grid.
const MAX_SHELLS: usize = 4;
/// What remote programs are told they draw on; Rio implements xterm's set.
const TERM: &str = "xterm-256color";
/// How long the reader idles before flushing an unfinished synchronized update.
const READ_TIMEOUT: Duration = Duration::from_millis(50);

struct Shell {
    session: Arc<Session>,
    guard: BridgeGuard,
    runtime: tokio::runtime::Runtime,
}

static SHELLS: LazyLock<Mutex<HashMap<u64, Shell>>> = LazyLock::new(Default::default);
static NEXT_ID: AtomicU64 = AtomicU64::new(1);

fn shells() -> MutexGuard<'static, HashMap<u64, Shell>> {
    SHELLS.lock().unwrap_or_else(PoisonError::into_inner)
}

/// The palette as a terminal theme, so colour queries match what is drawn.
pub(crate) fn theme(palette: &Palette) -> TerminalTheme {
    let color = |rgb: u32| TerminalColor {
        r: (rgb >> 16) as u8,
        g: (rgb >> 8) as u8,
        b: rgb as u8,
    };
    TerminalTheme::new(
        color(palette.background),
        color(palette.foreground),
        palette.ansi.map(color),
    )
}

/// Connects, starts a login shell on a `columns` × `rows` PTY and returns its
/// handle. Blocks for the SSH handshake, so call it from a worker thread.
pub fn open(config: &str, credentials: &str, columns: u16, rows: u16) -> Result<u64> {
    if shells().len() >= MAX_SHELLS {
        return Err(Error::Capacity);
    }
    let (config, _) = remote::parse_config(config)?;
    let credentials = remote::parse_credentials(credentials)?;
    let runtime = tokio::runtime::Builder::new_multi_thread()
        .worker_threads(1)
        .enable_all()
        .build()?;
    let ssh = runtime.block_on(herdr_remote_transport::connect(
        &config,
        credentials,
        CancellationToken::new(),
    ))?;
    let shell = runtime.block_on(ssh.into_shell(TERM, u32::from(columns), u32::from(rows)))?;
    let (socket, size, guard) = shell.into_parts();
    let started = (|| -> Result<Arc<Session>> {
        socket.set_read_timeout(Some(READ_TIMEOUT))?;
        let output = socket.try_clone()?;
        Ok(Session::start(
            output,
            socket,
            move |columns, rows| size.resize(u32::from(columns), u32::from(rows)),
            theme(&model::palette()),
            columns,
            rows,
        )?)
    })();
    let session = match started {
        Ok(session) => session,
        Err(error) => {
            runtime.block_on(guard.shutdown());
            return Err(error);
        }
    };
    let mut all = shells();
    if all.len() >= MAX_SHELLS {
        drop(all);
        runtime.block_on(guard.shutdown());
        return Err(Error::Capacity);
    }
    let id = NEXT_ID.fetch_add(1, Ordering::Relaxed);
    all.insert(
        id,
        Shell {
            session,
            guard,
            runtime,
        },
    );
    Ok(id)
}

pub fn session(id: u64) -> Option<Arc<Session>> {
    shells().get(&id).map(|shell| shell.session.clone())
}

/// Sends one mailbox command (the JSON `nativeTakeCommand` returns). False when
/// the shell's input queue is full and the command was not sent.
pub fn input(id: u64, raw: &str) -> Result<bool> {
    let command = model::parse_command(raw)?;
    let session = session(id).ok_or(Error::Closed)?;
    Ok(send(&session, &command))
}

/// Whether the remote shell is still running.
pub fn alive(id: u64) -> bool {
    session(id).is_some_and(|session| session.is_alive())
}

/// The rows on screen, for copying.
pub fn text(id: u64) -> Option<String> {
    session(id).map(|session| session.screen_text())
}

/// Forgets the shell and ends its SSH connection on a background thread, so
/// any thread may call this, including Android's UI thread.
pub fn close(id: u64) -> bool {
    let Some(Shell { guard, runtime, .. }) = shells().remove(&id) else {
        return false;
    };
    // If no thread can start, the guard's drop still cancels the connection.
    let _ = std::thread::Builder::new()
        .name("herdr-shell-close".into())
        .spawn(move || {
            runtime.block_on(guard.shutdown());
            drop(runtime);
        });
    true
}

pub(crate) fn send(session: &Session, command: &Command) -> bool {
    match command {
        Command::Text { text } => session.type_text(text),
        Command::Keys { keys } => keys.iter().all(|key| send_key(session, key)),
        Command::Submit { text } => session.type_text(text) && send_key(session, "enter"),
        Command::Compose { text } => session.paste(text) && send_key(session, "enter"),
        Command::Close => false,
    }
}

/// One of the app's key names (see `model::valid_key`) as a terminal key.
/// Single characters that are not terminal keys (space, digits, y/n) are typed.
fn send_key(session: &Session, name: &str) -> bool {
    let (modifier, key) = name.split_once('+').unwrap_or(("", name));
    let key = if key == "esc" { "escape" } else { key };
    let typed = if key == "space" { " " } else { key };
    let press = KeyPress {
        key: key.into(),
        control: modifier == "ctrl",
        alt: modifier == "alt",
        shift: modifier == "shift",
        text: (typed.chars().count() == 1).then(|| typed.into()),
    };
    if session.key(&press) {
        return true;
    }
    match &press.text {
        Some(text) if modifier.is_empty() => session.type_text(text),
        _ => false,
    }
}

#[cfg(test)]
mod tests {
    #![allow(clippy::unwrap_used)]
    use super::{Command, close, input, send, theme};
    use crate::{model, remote::Error};
    use gpui_rio::Session;
    use std::{io::Read, os::unix::net::UnixStream, time::Duration};

    #[test]
    fn commands_become_the_bytes_a_shell_expects() {
        let (local, mut remote) = UnixStream::pair().unwrap();
        local
            .set_read_timeout(Some(Duration::from_millis(20)))
            .unwrap();
        remote
            .set_read_timeout(Some(Duration::from_secs(2)))
            .unwrap();
        let session = Session::start(
            local.try_clone().unwrap(),
            local,
            |_, _| {},
            theme(&model::palette()),
            80,
            24,
        )
        .unwrap();
        let keys = [
            "ctrl+c",
            "esc",
            "alt+x",
            "space",
            "y",
            "7",
            "shift+tab",
            "up",
            "f5",
            "backspace",
        ];
        assert!(send(
            &session,
            &Command::Keys {
                keys: keys.map(String::from).to_vec()
            }
        ));
        assert!(send(&session, &Command::Submit { text: "ls".into() }));
        assert!(send(&session, &Command::Text { text: "é".into() }));
        // Not bracketed: the shell never enabled bracketed paste. Escapes are
        // dropped and line breaks become carriage returns before the Enter.
        assert!(send(
            &session,
            &Command::Compose {
                text: "a\nb\x1b".into()
            }
        ));
        let expected = "\x03\x1b\x1bx y7\x1b[Z\x1b[A\x1b[15~\x7fls\réa\rb\r".as_bytes();
        let mut received = vec![0; expected.len()];
        remote.read_exact(&mut received).unwrap();
        assert_eq!(received, expected);
    }

    #[test]
    fn unknown_shells_fail_closed() {
        assert!(matches!(
            input(u64::MAX, r#"{"type":"text","text":"x"}"#),
            Err(Error::Closed)
        ));
        // Invalid commands are refused before any shell is looked up.
        assert!(matches!(
            input(u64::MAX, r#"{"type":"close"}"#),
            Err(Error::Input(_))
        ));
        assert!(!super::alive(u64::MAX));
        assert!(super::text(u64::MAX).is_none());
        assert!(!close(u64::MAX));
    }
}
