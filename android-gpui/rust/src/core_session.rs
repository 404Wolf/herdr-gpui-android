//! Core framing, request correlation and terminal stream ownership.
use crate::remote::{Error, Operation, Result, Session, bump, lock};
use serde_json::{Value, json};
use std::{
    collections::VecDeque,
    io::{self, Read, Write},
    os::unix::net::UnixStream,
    sync::{
        Arc,
        atomic::{AtomicU64, Ordering},
        mpsc,
    },
    time::{Duration, Instant},
};
const MAX_FRAME: usize = 16 * 1024 * 1024;
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
    output: Option<mpsc::SyncSender<Vec<u8>>>,
    events: u32,
    event_carry: Vec<u8>,
    dirty: bool,
}
impl Connection<'_> {
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
        self.serial += 1;
        let id = self.serial;
        write_frame(
            &mut self.socket,
            0,
            0,
            &serde_json::to_vec(&json!({"jsonrpc":"2.0","id":id,"method":method,"params":params}))?,
        )?;
        let deadline = Instant::now() + Duration::from_secs(15);
        while Instant::now() < deadline {
            let Some(frame) = self.read()? else { continue };
            if frame.channel == 0 && frame.kind == 0 {
                let reply: Value = serde_json::from_slice(&frame.bytes)?;
                if reply["id"] != id {
                    return Err(Error::Protocol("uncorrelated RPC reply".into()));
                }
                if let Some(error) = reply.get("error") {
                    return Err(Error::Protocol(format!(
                        "{}: {}",
                        error["data"]["kind"], error["message"]
                    )));
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
                if message["method"] != "heartbeat" {
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
                    if let Some(output) = &self.output {
                        // 256 KiB starting credit bounds this queue. Credit is returned
                        // only as Rio consumes bytes, never when the network receives them.
                        output.try_send(frame.bytes).map_err(|_| Error::Capacity)?;
                    }
                }
                2 => {
                    self.output = None;
                    let mut state = lock(&self.owner.state);
                    state.view["state"] = json!("connecting");
                    state.view["status"] =
                        json!("Terminal stream ended; select a terminal to attach again");
                    state.terminal = None;
                    state.render_id = 0;
                    bump(&mut state.view);
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
                Some(edit) => self.edit_tab(edit, cols, rows)?,
                None => return Err(Error::StaleTarget),
            },
        }
        Ok(json!({"queued":true}).to_string())
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
                Err(Error::Protocol(message)) if message.contains("conflict") => continue,
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
            TabEdit::Rename { space, tab, name } => self.edit_doc(|doc| {
                tab_mut(doc, &space, &tab)?["name"] = json!(name);
                Ok(())
            }),
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
            TabEdit::Interrupt { terminal } => {
                self.call("terminal.interrupt", json!({"terminal":terminal}))?;
                Ok(())
            }
            TabEdit::Kill { terminal } => {
                self.call("terminal.kill", json!({"terminal":terminal}))?;
                self.refresh()
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
        let mut state = lock(&self.owner.state);
        project(&mut state.view, &list["terminals"], &doc["value"])?;
        Ok(())
    }
    fn attach(&mut self, terminal: &str, cols: u16, rows: u16) -> Result<()> {
        if self.channel != 0 {
            write_frame(&mut self.socket, self.channel, 2, &[])?;
        }
        self.channel = 0;
        self.output = None;
        {
            let mut state = lock(&self.owner.state);
            state.terminal = None;
            state.render_id = 0;
            state.view["state"] = json!("connecting");
            bump(&mut state.view);
        }
        let reply = self.call(
            "terminal.attach",
            json!({"terminal":terminal,"cols":cols,"rows":rows,"history":1000,"display":display()}),
        )?;
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
        let output = StreamRead {
            rx,
            chunks: VecDeque::new(),
            commands: commands.clone(),
            channel,
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
    Interrupt { terminal: String },
    Kill { terminal: String },
}
impl TabEdit {
    pub(crate) fn parse(action: &str) -> Option<Self> {
        if !action.starts_with('{') || action.len() > 1024 {
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
            "pane.interrupt" => Self::Interrupt { terminal: id("terminal")? },
            "pane.kill" => Self::Kill { terminal: id("terminal")? },
            _ => return None,
        })
    }
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
struct StreamRead {
    rx: mpsc::Receiver<Vec<u8>>,
    chunks: VecDeque<u8>,
    commands: mpsc::SyncSender<Operation>,
    channel: u32,
}
impl Read for StreamRead {
    fn read(&mut self, buffer: &mut [u8]) -> io::Result<usize> {
        if buffer.is_empty() {
            return Ok(0);
        }
        if self.chunks.is_empty() {
            match self.rx.recv_timeout(Duration::from_millis(50)) {
                Ok(bytes) => self.chunks.extend(bytes),
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
    mut cols: u16,
    mut rows: u16,
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
    };
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
    if let Some(first) = first.filter(|_| active) {
        connection.attach(&first, cols, rows)?;
    } else {
        let mut state = lock(&owner.state);
        state.view["state"] = json!("ready");
        state.view["status"] = json!("No live terminals on this host");
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
                Ok(Operation::Focus(terminal, asked)) => {
                    let queued = asked.elapsed();
                    connection.attach(&terminal, cols, rows)?;
                    #[cfg(feature = "android-ui")]
                    log::warn!(
                        "switch {terminal}: queued {queued:?}, attached after {:?}",
                        asked.elapsed()
                    );
                    #[cfg(not(feature = "android-ui"))]
                    let _ = (queued, asked);
                }
                Ok(Operation::Frame(channel, kind, bytes)) if channel == connection.channel => {
                    // Later attaches and new shells start at the viewer's
                    // current size, not the size it connected with.
                    if let (3, [c0, c1, r0, r1]) = (kind, bytes.as_slice()) {
                        (cols, rows) = (
                            u16::from_le_bytes([*c0, *c1]),
                            u16::from_le_bytes([*r0, *r1]),
                        );
                    }
                    write_frame(&mut connection.socket, channel, kind, &bytes)?
                }
                Ok(_) => {}
                Err(_) => break,
            }
        }
        if let Some(frame) = connection.read()? {
            connection.deliver(frame)?;
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
                        tabs.push(json!({"id":tab_id,"workspace_id":id,"label":tab["name"].as_str().unwrap_or(tab_id),"focused":false,"zoomed":false,"status":""}));
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
            "kind": kind.unwrap_or(""),
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
            &json!([{"id":"t1","state":"live","title":"Agent","labels":{"herdr.space":"s1","herdr.tab":"a","role":"pm","project":"billing","agent.kind":"codex"},"status":{"state":"blocked"}},{"id":"t2","state":"live","labels":{"role":"none"},"parent":"t1","argv":["/usr/bin/claude"]},{"id":"t3","state":"live","cwd":"/home/w/botd","labels":{"herdr.space":"sp9","where":"botd / w1-backend"}},{"id":"t4","state":"live","cwd":"/home/w/zulip/","labels":{"herdr.space":"sp8"}}]),
            &json!({"spaces":[{"id":"s1","name":"Code","tabs":[{"id":"a","name":"Fix"}]}]}),
        )?;
        assert_eq!(view["workspaces"][0]["label"], "Code");
        assert_eq!(view["tabs"][0]["label"], "Fix");
        assert_eq!(view["panes"][0]["status"], "blocked");
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

#[cfg(test)]
mod connection_tests {
    #![allow(clippy::unwrap_used, clippy::expect_used)]
    use super::*;
    use crate::remote::State;
    use herdr_remote_transport::CancellationToken;
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
            state: Mutex::new(State {
                view: json!({"revision":0,"panes":[]}),
                terminal: None,
                render_id: 0,
            }),
            cancel: CancellationToken::new(),
            commands,
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
            for expected in [
                "events.subscribe",
                "terminal.list",
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
            // Closing the viewer transport never requests terminal.kill or server.stop.
            let mut byte = [0];
            loop {
                match core.read(&mut byte) {
                    Ok(0) => break,
                    Ok(_) => panic!("unexpected trailing command"),
                    Err(e)
                        if matches!(
                            e.kind(),
                            io::ErrorKind::WouldBlock | io::ErrorKind::TimedOut
                        ) => {}
                    Err(_) => break,
                }
            }
        });
        events.recv_timeout(Duration::from_secs(5)).unwrap();
        let terminal = lock(&owner.state).terminal.clone().unwrap();
        assert!(terminal.type_text("x"));
        events.recv_timeout(Duration::from_secs(5)).unwrap();
        owner.cancel.cancel();
        assert!(runtime.join().unwrap().is_ok());
        peer.join().unwrap();
    }
}
