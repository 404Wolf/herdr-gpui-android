//! Ops the phone asks of the machine it is connected to, apart from
//! core_session's layout edits: `{"op":"usage.look"}` runs that machine's
//! `aswap list --json` (cache-only: no network, no quota spent) through
//! `machine.exec`; `{"op":"tab.detach",..}` closes a tab while its agents keep
//! running, as the desktop's Park does; `beads.stores` and `beads.detail` read
//! the machine's beads stores with the desktop Work panel's own scripts. The
//! core session tries
//! [`PhoneOp::parse`] for an action it does not know and hands the op its RPC
//! call, so this module never owns the socket.
//!
//! aswap's JSON is reduced here, before it reaches Java: only an account's
//! label, provider, state and numbers pass. Anything else aswap prints (paths,
//! credential modes, sync details, any field it adds later) is dropped, so a
//! token or email can never reach the UI, logs or screenshots through this op.

use crate::remote::{Error, Result};
use serde_json::{Map, Value, json};

/// The aswap `list --json` schema this reduction reads.
pub(crate) const USAGE_SCHEMA: u64 = 1;
/// Bounds on what the reduction keeps.
const MAX_ACCOUNTS: usize = 64;
const MAX_WINDOWS: usize = 8;
const MAX_WORD: usize = 64;
/// `machine.exec`'s exit code when the login shell cannot find aswap.
const NOT_FOUND: i64 = 127;
/// aswap on that machine's own PATH: through its login shell, as a typed
/// command would find it (the core's service environment may not have the
/// Nix profile). `exec` so no extra shell outlives a timeout.
const USAGE_LOOK: &str = r#"command -v aswap >/dev/null 2>&1 && exec aswap list --json
exec "${SHELL:-/bin/sh}" -lc 'exec aswap list --json'"#;

/// The desktop factory mod's scripts, byte for byte, so the phone reads beads
/// exactly as the Work panel does; BeadsModel parses their output. STORES_SH
/// is home-manager-conf users/wolf/programs/herdr-core/app/factory/src/feed.ts
/// at 5e6c79eb; DETAIL_SH (its fast read of the store's export, with the
/// revision and "fresh"/"maybe") and COMMENTS_SH are that feed.ts at 51c4ffbb
/// (factory/beads-stores-reader), which try the project's root store, then each
/// repo store the machine's `beads-stores list` names (else repos.json's). They
/// never write a store (DETAIL_SH keeps its own answers and freshness marks
/// under ~/.cache/herdr-beads).
const STORES_SH: &str = include_str!("beads/stores.sh");
/// The phone's whole-store mirror (factory-9zz.14): each changed store's `bd export`, read-only,
/// fingerprinted by its Dolt manifest's bytes so an unchanged store costs no bd at all.
const DUMP_SH: &str = include_str!("beads/dump.sh");
const COMMENTS_SH: &str = include_str!("beads/comments.sh");
/// The phone's own: DETAIL_SH then COMMENTS_SH in one exec (beads/read.sh), so a
/// bead's plan and comments arrive together, and none is read for a bead with none.
const READ_SH: &str = include_str!("beads/read.sh");
/// The status doc read (that mod's doc.ts DOC_SH at 6046ee51): `~/deacon/work/<name>.md`
/// with its mtime and checksum from that machine's own clock, or "same" when the
/// phone's `key` still matches. Read-only; at most 200 kB of text.
const DOC_SH: &str = include_str!("docs/doc.sh");
/// An agent's session file (factory-9zz.12), found from its labels only (history/history.sh).
const HISTORY_SH: &str = include_str!("history/history.sh");
/// Runs a vendored script ($1) with bd on that machine's login PATH when the
/// core's own PATH lacks it (a service environment without the Nix profile),
/// as USAGE_LOOK does for aswap; the scripts themselves stay byte for byte.
const WITH_LOGIN_PATH: &str = r#"command -v bd >/dev/null 2>&1 || PATH=$("${SHELL:-/bin/sh}" -lc 'printf %s "$PATH"' 2>/dev/null)
script=$1; shift
exec sh -c "$script" sh "$@""#;
const DETAIL_SH: &str = include_str!("beads/detail.sh");
/// Where the stores are on that machine: the beads module's defaults.
const STORE_ROOT: &str = "~/beads";
const REPOS_FILE: &str = "~/.config/beads/repos.json";
/// At most this many known `project=fingerprint` args (stores skipped when unchanged).
const MAX_KNOWN: usize = 256;
/// At most this many machines in a `machines.list` answer.
const MAX_MACHINES: usize = 64;

/// At most this many terminals in one detached tab.
const MAX_TAB_TERMINALS: usize = 32;

#[derive(Debug, PartialEq, Eq)]
pub(crate) enum PhoneOp {
    /// The selected machine's aswap accounts and their cached usage.
    UsageLook,
    /// Close `tab` of `space` while its agents keep running: the desktop's
    /// Park (herdr-app `TerminalChange::Park`) for each of `park` (its
    /// herdr.space/herdr.tab labels cleared, keep off, so it lives on in the
    /// rail under its lead and ends with its program), `kill` for the tab's
    /// idle shells, then the tab leaves herdr-app's layout. Each id must still
    /// be in that tab, and the tab is dropped only when nothing else is left
    /// in it.
    TabDetach { space: String, tab: String, park: Vec<String>, kill: Vec<String> },
    /// Every beads store's list (STORES_SH); `known` are `project=fingerprint`
    /// pairs from the last read, whose unchanged stores are not listed again.
    BeadsStores { known: Vec<String> },
    /// Every beads store's whole export (DUMP_SH), for the phone's mirror; `known` as for
    /// BeadsStores. `on` names another machine of this core (`machine.list`): the deacon's
    /// Tasks read every machine through the one it is on.
    BeadsDump { known: Vec<String>, on: Option<String> },
    /// The machines this core knows (`machine.list`): their names and whether they are connected.
    MachinesList,
    /// One bead (DETAIL_SH), from the store of `project` (a store name, or
    /// `repo:<name>`'s project): from the store's export when it can, else
    /// `bd show`; `confirm` asks bd itself (the export said "maybe").
    BeadsDetail { project: String, id: String, confirm: bool },
    /// One bead's comments (COMMENTS_SH), read when it is opened in Tasks.
    BeadsComments { project: String, id: String },
    /// One bead's plan and comments together (READ_SH), for the Tasks tab:
    /// one exec instead of two in a row; `confirm` as for BeadsDetail.
    BeadsRead { project: String, id: String, confirm: bool },
    /// A status doc (DOC_SH): `name` is `_deacon`, `<project>` or
    /// `<project>/<agent>` under ~/deacon/work; `key` the `mtime:cksum` the
    /// phone already shows, so an unchanged doc comes back "same", without text.
    DocRead { name: String, key: Option<String> },
    /// An agent's conversation from its own session file (factory-9zz.12): `kind` and `session`
    /// are the terminal's `agent.kind`/`agent.session` labels; `path` the file found last time
    /// (checked again there), `key` its `size:mtime` the phone keeps, so an unchanged file
    /// comes back "same" without text.
    AgentHistory { kind: crate::agent_history::Kind, session: String, path: Option<String>, key: Option<String> },
}

impl PhoneOp {
    pub(crate) fn parse(action: &str) -> Option<Self> {
        if !action.starts_with('{') || action.len() > 32 * 1024 {
            return None;
        }
        let value: Value = serde_json::from_str(action).ok()?;
        let id = |v: &Value| {
            v.as_str()
                .filter(|v| !v.is_empty() && v.len() <= 128 && !v.contains('/') && !v.chars().any(char::is_control))
                .map(str::to_owned)
        };
        let ids = |key: &str| -> Option<Vec<String>> {
            let all = value[key].as_array().map_or(&[][..], Vec::as_slice);
            (all.len() <= MAX_TAB_TERMINALS).then_some(())?;
            all.iter().map(id).collect()
        };
        // Strict: an op with a field it does not take is refused, not half-read.
        let only = |keys: &[&str]| value.as_object().is_some_and(|o| o.keys().all(|k| k == "op" || keys.contains(&k.as_str())));
        match value["op"].as_str()? {
            "usage.look" => only(&[]).then_some(Self::UsageLook),
            "beads.stores" => {
                only(&["known"]).then_some(())?;
                let all = value["known"].as_array().map_or(&[][..], Vec::as_slice);
                (all.len() <= MAX_KNOWN).then_some(())?;
                let known = all.iter().map(|v| v.as_str().filter(|k| known_pair(k)).map(str::to_owned)).collect::<Option<_>>()?;
                Some(Self::BeadsStores { known })
            }
            "beads.dump" => {
                only(&["known", "on"]).then_some(())?;
                let all = value["known"].as_array().map_or(&[][..], Vec::as_slice);
                (all.len() <= MAX_KNOWN).then_some(())?;
                let known = all.iter().map(|v| v.as_str().filter(|k| known_pair(k)).map(str::to_owned)).collect::<Option<_>>()?;
                let on = match &value["on"] {
                    Value::Null => None,
                    Value::String(m) if machine_name(m) => Some(m.clone()),
                    _ => return None,
                };
                Some(Self::BeadsDump { known, on })
            }
            "machines.list" => only(&[]).then_some(Self::MachinesList),
            "beads.detail" => {
                only(&["project", "id", "confirm"]).then_some(())?;
                let project = value["project"].as_str().filter(|p| store_name(p))?.to_owned();
                let id = value["id"].as_str().filter(|i| bead_id(i))?.to_owned();
                let confirm = match &value["confirm"] {
                    Value::Null => false,
                    Value::Bool(b) => *b,
                    _ => return None,
                };
                Some(Self::BeadsDetail { project, id, confirm })
            }
            "beads.read" => {
                only(&["project", "id", "confirm"]).then_some(())?;
                let project = value["project"].as_str().filter(|p| store_name(p))?.to_owned();
                let id = value["id"].as_str().filter(|i| bead_id(i))?.to_owned();
                let confirm = match &value["confirm"] {
                    Value::Null => false,
                    Value::Bool(b) => *b,
                    _ => return None,
                };
                Some(Self::BeadsRead { project, id, confirm })
            }
            "beads.comments" => {
                only(&["project", "id"]).then_some(())?;
                let project = value["project"].as_str().filter(|p| store_name(p))?.to_owned();
                let id = value["id"].as_str().filter(|i| bead_id(i))?.to_owned();
                Some(Self::BeadsComments { project, id })
            }
            "doc.read" => {
                only(&["name", "key"]).then_some(())?;
                let name = value["name"].as_str().filter(|n| doc_name(n))?.to_owned();
                let key = match &value["key"] {
                    Value::Null => None,
                    Value::String(k) if doc_key(k) => Some(k.clone()),
                    _ => return None,
                };
                Some(Self::DocRead { name, key })
            }
            "agent.history" => {
                only(&["kind", "session", "path", "key"]).then_some(())?;
                let kind = crate::agent_history::Kind::parse(value["kind"].as_str()?)?;
                let session = value["session"].as_str().filter(|s| crate::agent_history::plain_session(s))?.to_owned();
                let path = match &value["path"] {
                    Value::Null => None,
                    Value::String(p) if history_path(p, &session) => Some(p.clone()),
                    _ => return None,
                };
                let key = match &value["key"] {
                    Value::Null => None,
                    Value::String(k) if doc_key(k) => Some(k.clone()),
                    _ => return None,
                };
                Some(Self::AgentHistory { kind, session, path, key })
            }
            "tab.detach" => {
                only(&["space", "tab", "park", "kill"]).then_some(())?;
                let park = ids("park")?;
                (!park.is_empty()).then_some(())?;
                Some(Self::TabDetach { space: id(&value["space"])?, tab: id(&value["tab"])?, park, kill: ids("kill")? })
            }
            _ => None,
        }
    }

    /// The op changed the layout or labels, so the view must be read again.
    /// How long the op's command may run on the machine (its `machine.exec` timeout), seconds;
    /// None for an op that runs none. The caller waits at least this long for the answer.
    pub(crate) fn exec_secs(&self) -> Option<u64> {
        match self {
            Self::MachinesList | Self::TabDetach { .. } => None,
            Self::BeadsDump { .. } => Some(60),
            Self::BeadsStores { .. } => Some(20),
            Self::BeadsRead { .. } => Some(15),
            Self::UsageLook
            | Self::BeadsDetail { .. }
            | Self::BeadsComments { .. }
            | Self::AgentHistory { .. }
            | Self::DocRead { .. } => Some(10),
        }
    }

    pub(crate) fn edits(&self) -> bool {
        matches!(self, Self::TabDetach { .. })
    }

    /// Runs the op with `call` (the core session's RPC) and returns the
    /// action reply the app reads: `{"queued":true,"usage":{..}}`,
    /// `{"queued":true,"missing":true}` when the machine has no aswap, or
    /// `{"queued":true,"error":".."}` when aswap failed; a look's reply also
    /// carries `timing` (`rpc_ms`, the core's `exec_ms`) for the timing log.
    pub(crate) fn run(&self, mut call: impl FnMut(&str, Value) -> Result<Value>) -> Result<String> {
        let timeout = self.exec_secs().unwrap_or(10);
        match self {
            Self::UsageLook => {
                let asked = std::time::Instant::now();
                let result = call("machine.exec", json!({"argv":["sh","-c",USAGE_LOOK],"timeout":timeout}))?;
                let mut reply = usage_reply(&result)?;
                // Where the wait went, for the phone's timing log: the round trip, and the core's own run of aswap.
                reply["timing"] = json!({
                    "rpc_ms": u64::try_from(asked.elapsed().as_millis()).unwrap_or(u64::MAX),
                    "exec_ms": result["ms"].as_u64(),
                });
                Ok(reply.to_string())
            }
            Self::TabDetach { space, tab, park, kill } => {
                detach(&mut call, space, tab, park, kill)?;
                Ok(json!({"queued":true,"parked":park}).to_string())
            }
            Self::BeadsStores { known } => {
                let mut argv = vec![json!("sh"), json!("-c"), json!(WITH_LOGIN_PATH), json!("sh"), json!(STORES_SH), json!(STORE_ROOT), json!(REPOS_FILE)];
                argv.extend(known.iter().map(|k| json!(k)));
                let result = call("machine.exec", json!({"argv":argv,"timeout":timeout}))?;
                Ok(script_reply(&result)?.to_string())
            }
            Self::BeadsDump { known, on } => {
                let mut argv = vec![json!("sh"), json!("-c"), json!(WITH_LOGIN_PATH), json!("sh"), json!(DUMP_SH), json!(STORE_ROOT), json!(REPOS_FILE)];
                argv.extend(known.iter().map(|k| json!(k)));
                let mut params = json!({"argv":argv,"timeout":timeout});
                if let Some(on) = on {
                    params["on"] = json!(on);
                }
                let result = call("machine.exec", params)?;
                Ok(script_reply(&result)?.to_string())
            }
            Self::MachinesList => {
                let result = call("machine.list", json!({}))?;
                let machines: Vec<Value> = result["machines"]
                    .as_array()
                    .map_or(&[][..], Vec::as_slice)
                    .iter()
                    .filter_map(|m| {
                        let name = m["name"].as_str().filter(|n| machine_name(n))?;
                        Some(json!({"name":name,"state":m["state"].as_str().unwrap_or(""),"temporary":m["temporary"] == true}))
                    })
                    .take(MAX_MACHINES)
                    .collect();
                Ok(json!({"queued":true,"machines":machines}).to_string())
            }
            Self::BeadsDetail { project, id, confirm } => {
                let mut argv = vec![json!("sh"), json!("-c"), json!(WITH_LOGIN_PATH), json!("sh"), json!(DETAIL_SH), json!(STORE_ROOT), json!(REPOS_FILE), json!(project), json!(id)];
                if *confirm {
                    argv.push(json!("confirm"));
                }
                let result = call("machine.exec", json!({"argv":argv,"timeout":timeout}))?;
                Ok(script_reply(&result)?.to_string())
            }
            Self::BeadsRead { project, id, confirm } => {
                let mut argv = vec![json!("sh"), json!("-c"), json!(WITH_LOGIN_PATH), json!("sh"), json!(READ_SH), json!(DETAIL_SH), json!(COMMENTS_SH)];
                argv.extend([json!(STORE_ROOT), json!(REPOS_FILE), json!(project), json!(id)]);
                if *confirm {
                    argv.push(json!("confirm"));
                }
                let result = call("machine.exec", json!({"argv":argv,"timeout":timeout}))?;
                Ok(script_reply(&result)?.to_string())
            }
            Self::BeadsComments { project, id } => {
                let argv = json!(["sh", "-c", WITH_LOGIN_PATH, "sh", COMMENTS_SH, STORE_ROOT, REPOS_FILE, project, id]);
                let result = call("machine.exec", json!({"argv":argv,"timeout":timeout}))?;
                Ok(script_reply(&result)?.to_string())
            }
            Self::AgentHistory { kind, session, path, key } => {
                let tail = crate::agent_history::TAIL_BYTES.to_string();
                let argv = json!(["sh", "-c", HISTORY_SH, "sh", kind.name(), session, path.as_deref().unwrap_or(""),
                    key.as_deref().unwrap_or(""), tail]);
                let result = call("machine.exec", json!({"argv":argv,"timeout":timeout}))?;
                Ok(history_reply(*kind, &result)?.to_string())
            }
            Self::DocRead { name, key } => {
                let argv = json!(["sh", "-c", DOC_SH, "sh", name, key.as_deref().unwrap_or("")]);
                let result = call("machine.exec", json!({"argv":argv,"timeout":timeout}))?;
                Ok(script_reply(&result)?.to_string())
            }
        }
    }
}

/// A path history.sh found before: absolute, plain, ending in the session's own file name.
fn history_path(path: &str, session: &str) -> bool {
    path.len() <= 512
        && path.starts_with('/')
        && !path.split('/').any(|part| part == "..")
        && !path.chars().any(char::is_control)
        && path.ends_with(&format!("{session}.jsonl"))
}

/// history.sh's answer for the app: `{"unavailable":..}`, `{"same":true,"path","key"}`, or the
/// rendered conversation `{"path","key","text","truncated"}`; never the raw file.
fn history_reply(kind: crate::agent_history::Kind, result: &Value) -> Result<Value> {
    if let Some(why) = exec_failure(result, "the history read") {
        return Ok(json!({"queued":true,"error":why}));
    }
    let out = exec_text(result)?;
    let mut lines = out.splitn(4, '\n');
    let first = lines.next().unwrap_or("");
    if let Some(why) = first.strip_prefix("unavailable") {
        let why = match why.trim() {
            "0" => "its session file is not on that machine",
            "kind" => "no history for this harness",
            _ => "more than one session file matches",
        };
        return Ok(json!({"queued":true,"unavailable":why}));
    }
    let path = first.strip_prefix("path ").unwrap_or("").to_owned();
    let key = lines.next().and_then(|k| k.strip_prefix("key ")).unwrap_or("").to_owned();
    match lines.next() {
        Some("same") => Ok(json!({"queued":true,"same":true,"path":path,"key":key})),
        Some("data") => {
            let tail = lines.next().unwrap_or("");
            // A file longer than what was read begins mid-line: that first piece is dropped.
            let size = key.split_once(':').and_then(|(s, _)| s.parse::<usize>().ok()).unwrap_or(0);
            let tail = if size > crate::agent_history::TAIL_BYTES {
                tail.split_once('\n').map_or("", |(_, rest)| rest)
            } else {
                tail
            };
            let (text, cut) = crate::agent_history::bound(crate::agent_history::render(kind, tail), crate::agent_history::MAX_TEXT);
            Ok(json!({"queued":true,"path":path,"key":key,"text":text,"truncated":cut || result["truncated"] == true}))
        }
        _ => Ok(json!({"queued":true,"error":"couldn't read the history's answer"})),
    }
}

/// A machine's name as herdr-core gives it (`rohan` in `rohan/t7`): plain, short, no path.
fn machine_name(name: &str) -> bool {
    !name.is_empty()
        && name.len() <= 64
        && !name.starts_with(['-', '.'])
        && name.chars().all(|c| c.is_ascii_alphanumeric() || matches!(c, '-' | '_' | '.'))
}

/// A store's name as STORES_SH prints it: a folder under the root, or `repo:<name>`.
fn store_name(name: &str) -> bool {
    let bare = name.strip_prefix("repo:").unwrap_or(name);
    !bare.is_empty()
        && name.len() <= 128
        && bare != "."
        && bare != ".."
        && bare.chars().all(|c| c.is_ascii_alphanumeric() || "._-".contains(c))
}

/// A status doc's name under ~/deacon/work: `_deacon`, `<project>` or `<project>/<agent>`,
/// each part a plain file name (never `.`, `..`, hidden or an option).
fn doc_name(name: &str) -> bool {
    let parts: Vec<&str> = name.split('/').collect();
    name.len() <= 160
        && (1..=2).contains(&parts.len())
        && parts.iter().all(|p| {
            !p.is_empty()
                && p.len() <= 64
                && !p.starts_with('.')
                && !p.starts_with('-')
                && p.chars().all(|c| c.is_ascii_alphanumeric() || "._-".contains(c))
        })
}

/// DOC_SH's `mtime:cksum`, both decimal.
fn doc_key(key: &str) -> bool {
    key.len() <= 40
        && key.split_once(':').is_some_and(|(m, c)| {
            !m.is_empty() && !c.is_empty() && m.chars().all(|x| x.is_ascii_digit()) && c.chars().all(|x| x.is_ascii_digit())
        })
}

/// A bead id: `factory-setup.21`, `bd-a1b2`.
fn bead_id(id: &str) -> bool {
    !id.is_empty() && id.len() <= 64 && !id.starts_with('-') && id.chars().all(|c| c.is_ascii_alphanumeric() || "._-".contains(c))
}

/// `project=fingerprint` (a find -printf %T@ time).
fn known_pair(pair: &str) -> bool {
    pair.split_once('=').is_some_and(|(project, fp)| {
        store_name(project) && !fp.is_empty() && fp.len() <= 32 && fp.chars().all(|c| c.is_ascii_digit() || c == '.')
    })
}

/// A script's stdout for BeadsModel: `{"queued":true,"text":..,"truncated":..}`,
/// or why it failed. The scripts exit 0 and frame their own errors, so another
/// exit is the exec itself failing (a timeout).
fn script_reply(result: &Value) -> Result<Value> {
    if let Some(why) = exec_failure(result, "the beads read") {
        return Ok(json!({"queued":true,"error":why}));
    }
    Ok(json!({"queued":true,"text":exec_text(result)?,"truncated":result["truncated"] == true}))
}

/// Why machine.exec's run did not finish cleanly, in words, or None for exit 0.
/// A timeout and a signal are the exec's, not the program's: they say so.
fn exec_failure(result: &Value, what: &str) -> Option<String> {
    if result["timed_out"] == true {
        return Some(format!("{what} timed out on the machine"));
    }
    match result["code"].as_i64() {
        Some(0) => None,
        Some(code) => Some(format!("{what} exited with {code}")),
        None => Some(format!("{what} was stopped before it finished")),
    }
}

fn exec_text(result: &Value) -> Result<String> {
    use base64::Engine;
    let bytes = base64::engine::general_purpose::STANDARD
        .decode(result["stdout"].as_str().unwrap_or(""))
        .map_err(|_| Error::Protocol("invalid command output".into()))?;
    Ok(String::from_utf8_lossy(&bytes).into_owned())
}

/// The live terminals the core puts in `space`/`tab`, as the view builder
/// does: by their labels, a missing or empty one being a parked terminal's
/// (space "loose", the tab its own id).
fn in_tab(list: &Value, space: &str, tab: &str) -> Vec<String> {
    let label = |t: &Value, key: &str| t["labels"][key].as_str().filter(|v| !v.is_empty()).map(str::to_owned);
    list["terminals"]
        .as_array()
        .map(|all| {
            all.iter()
                .filter(|t| t["state"] == "live")
                .filter_map(|t| {
                    let id = t["address"].as_str().or(t["id"].as_str())?;
                    let at_space = label(t, "herdr.space").unwrap_or_else(|| "loose".into());
                    let at_tab = label(t, "herdr.tab").unwrap_or_else(|| id.to_owned());
                    (at_space == space && at_tab == tab).then(|| id.to_owned())
                })
                .collect()
        })
        .unwrap_or_default()
}

fn detach(
    call: &mut impl FnMut(&str, Value) -> Result<Value>,
    space: &str,
    tab: &str,
    park: &[String],
    kill: &[String],
) -> Result<()> {
    // Checked against the core's labels now, not the phone's view: a terminal
    // that moved since the sheet opened is not parked or killed here.
    let there = in_tab(&call("terminal.list", json!({}))?, space, tab);
    if park.iter().chain(kill).any(|id| !there.contains(id)) {
        return Err(Error::StaleTarget);
    }
    // The layout first: if it fails, nothing has changed and the phone can try
    // again. A tab that gained a terminal since the sheet opened stays for it.
    let whole = there.iter().all(|id| park.contains(id) || kill.contains(id));
    if whole && space != "loose" {
        drop_tab(call, space, tab)?;
    }
    for terminal in park {
        call("terminal.label", json!({"terminal":terminal,"labels":{"herdr.space":"","herdr.tab":""}}))?;
        call("terminal.keep", json!({"terminal":terminal,"keep":false}))?;
    }
    for terminal in kill {
        call("terminal.kill", json!({"terminal":terminal}))?;
    }
    Ok(())
}

/// Removes `tab` from `space` in herdr-app's layout, retrying a version race.
fn drop_tab(call: &mut impl FnMut(&str, Value) -> Result<Value>, space: &str, tab: &str) -> Result<()> {
    for _ in 0..3 {
        let doc = call("doc.get", json!({"name":"herdr-app"}))?;
        let mut value = doc["value"].clone();
        let Some(tabs) = value["spaces"]
            .as_array_mut()
            .and_then(|spaces| spaces.iter_mut().find(|s| s["id"] == space))
            .and_then(|s| s["tabs"].as_array_mut())
        else {
            return Ok(());
        };
        let before = tabs.len();
        tabs.retain(|t| t["id"] != tab);
        if tabs.len() == before {
            return Ok(());
        }
        match call("doc.set", json!({"name":"herdr-app","value":value,"version":doc["version"]})) {
            Ok(_) => return Ok(()),
            Err(Error::Rpc { kind, .. }) if kind == "conflict" => continue,
            Err(error) => return Err(error),
        }
    }
    Err(Error::Protocol("the layout kept changing; try again".into()))
}

fn usage_reply(result: &Value) -> Result<Value> {
    use base64::Engine;
    if result["timed_out"] != true && result["code"].as_i64() == Some(NOT_FOUND) {
        return Ok(json!({"queued":true,"missing":true}));
    }
    // aswap's own stderr is not shown: it may name files or accounts.
    if let Some(why) = exec_failure(result, "aswap list") {
        return Ok(json!({"queued":true,"error":why}));
    }
    if result["truncated"] == true {
        return Ok(json!({"queued":true,"error":"aswap's list was too long"}));
    }
    let bytes = base64::engine::general_purpose::STANDARD
        .decode(result["stdout"].as_str().unwrap_or(""))
        .map_err(|_| Error::Protocol("invalid command output".into()))?;
    let Ok(list) = serde_json::from_slice::<Value>(&bytes) else {
        // The login shell may print before it (a profile's output): not aswap's fault.
        return Ok(json!({"queued":true,"error":"couldn't read aswap's output"}));
    };
    Ok(json!({"queued":true,"usage":reduce_usage(&list)}))
}

/// The fields the Usage view shows, bounded. A schema other than
/// [`USAGE_SCHEMA`] keeps only its number, so the app says the format
/// changed instead of guessing at it.
pub(crate) fn reduce_usage(list: &Value) -> Value {
    let schema = list["schema_version"].as_u64().unwrap_or(0);
    if schema != USAGE_SCHEMA {
        return json!({"schema_version":schema,"accounts":[]});
    }
    let accounts: Vec<Value> = list["accounts"]
        .as_array()
        .map(|all| all.iter().take(MAX_ACCOUNTS).filter_map(reduce_account).collect())
        .unwrap_or_default();
    let mut out = json!({"schema_version":schema,"accounts":accounts});
    // The footer: auto-switch per provider, and whether sync is failing.
    // auto's resume_prompt (Wolf's own text) and sync's event log stay behind.
    if let Some(mode) = word(&list["credential_mode"]) {
        out["credential_mode"] = json!(mode);
    }
    if let Some(auto) = list["auto"].as_object() {
        let mut kept = Map::new();
        for (provider, setting) in auto.iter().take(MAX_WINDOWS) {
            let Some(provider) = word(&json!(provider)) else { continue };
            kept.insert(
                provider,
                json!({"enabled": setting["enabled"] == true, "threshold": number(&setting["threshold"])}),
            );
        }
        out["auto"] = Value::Object(kept);
    }
    if list["sync"].is_object() {
        out["sync"] = json!({
            "failures": number(&list["sync"]["failures"]),
            "last_sync": number(&list["sync"]["last_sync"]),
        });
    }
    out
}

fn reduce_account(account: &Value) -> Option<Value> {
    let mut out = Map::new();
    out.insert("label".into(), json!(word(&account["label"])?));
    out.insert("provider".into(), json!(word(&account["provider"])?));
    out.insert("active".into(), json!(account["active"] == true));
    out.insert("reserved".into(), json!(account["reserved"] == true));
    for key in ["usage_status", "usage_reason"] {
        if let Some(text) = word(&account[key]) {
            out.insert(key.into(), json!(text));
        }
    }
    for key in ["remaining_percent", "fetched_at", "last_attempt", "next_poll_at", "age_seconds"] {
        if let Some(number) = number(&account[key]) {
            out.insert(key.into(), json!(number));
        }
    }
    let error = &account["usage_error"];
    if error.is_object() {
        let mut kept = Map::new();
        if let Some(class) = word(&error["class"]) {
            kept.insert("class".into(), json!(class));
        }
        for key in ["http_status", "retry_after_seconds"] {
            if let Some(number) = number(&error[key]) {
                kept.insert(key.into(), json!(number));
            }
        }
        out.insert("usage_error".into(), Value::Object(kept));
    }
    let windows: Vec<Value> = account["usage"]["windows"]
        .as_array()
        .map(|all| {
            all.iter()
                .take(MAX_WINDOWS)
                .filter_map(|w| {
                    Some(json!({
                        "name": word(&w["name"])?,
                        "used_percent": number(&w["used_percent"]),
                        "resets_at": number(&w["resets_at"]),
                        "duration_minutes": number(&w["duration_minutes"]),
                    }))
                })
                .collect()
        })
        .unwrap_or_default();
    out.insert("windows".into(), json!(windows));
    // Other labels with the same login: labels only, as aswap gives them.
    let duplicates: Vec<String> = account["duplicates"]
        .as_array()
        .map(|all| all.iter().take(MAX_WINDOWS).filter_map(word).collect())
        .unwrap_or_default();
    out.insert("duplicates".into(), json!(duplicates));
    Some(Value::Object(out))
}

/// A short single-line word (label, provider, status): bounded, no control characters.
fn word(value: &Value) -> Option<String> {
    let text = value.as_str()?.trim();
    (!text.is_empty() && text.chars().count() <= MAX_WORD && !text.chars().any(char::is_control))
        .then(|| text.to_owned())
}

fn number(value: &Value) -> Option<f64> {
    value.as_f64().filter(|n| n.is_finite())
}

#[cfg(test)]
mod tests {
    use super::*;
    use base64::Engine;

    fn exec(code: i64, stdout: &str) -> Value {
        json!({"code":code,"stdout":base64::engine::general_purpose::STANDARD.encode(stdout),"truncated":false})
    }

    fn sample() -> Value {
        json!({
            "schema_version": 1,
            "credential_mode": "refresh-owner",
            "sync": {"failures": 2, "last_sync": 1, "events": [{"kind": "/secret/sync-path"}]},
            "auto": {"claude": {"enabled": true, "threshold": 90.0, "resume_prompt": "private words", "reserved": ["x"]}},
            "accounts": [{
                "label": "demo-a", "provider": "claude", "active": true,
                "usage_status": "ok", "usage_error": null, "remaining_percent": 40.0,
                "fetched_at": 1_700_000_000, "next_poll_at": 1_700_000_300,
                "token": "sk-secret", "email": "someone@example.com", "path": "/home/x/.claude",
                "usage": {"windows": [
                    {"name": "five_hour", "duration_minutes": 300, "used_percent": 60.0, "resets_at": 1_700_003_600, "extra": "x"}
                ]},
                "duplicates": ["demo-b"]
            }, {
                "label": "demo-c", "provider": "codex", "active": false,
                "usage_status": "backoff", "usage_reason": "provider-limit", "age_seconds": 90,
                "usage_error": {"class": "provider-limit", "source": "/secret/file", "http_status": 429, "retry_after_seconds": 120},
                "usage": {"windows": []}
            }]
        })
    }

    /// Every call a fake core saw: method and params, in order.
    type Seen = std::rc::Rc<std::cell::RefCell<Vec<(String, Value)>>>;

    /// A fake core: answers by method, recording every call.
    fn core(terminals: Value, conflicts: usize) -> (impl FnMut(&str, Value) -> Result<Value>, Seen) {
        let seen = std::rc::Rc::new(std::cell::RefCell::new(Vec::new()));
        let log = seen.clone();
        let mut left = conflicts;
        let call = move |method: &str, params: Value| {
            log.borrow_mut().push((method.to_owned(), params));
            match method {
                "terminal.list" => Ok(json!({"terminals": terminals.clone()})),
                "doc.get" => Ok(json!({"version":7,"value":{"spaces":[{"id":"s1","tabs":[{"id":"a"},{"id":"b"}]}]}})),
                "doc.set" if left > 0 => {
                    left -= 1;
                    Err(Error::Rpc { kind: "conflict".into(), message: "version".into() })
                }
                _ => Ok(json!({})),
            }
        };
        (call, seen)
    }

    fn live(id: &str, space: &str, tab: &str) -> Value {
        json!({"address":id,"state":"live","labels":{"herdr.space":space,"herdr.tab":tab,"parent":"t1"}})
    }

    #[test]
    fn detach_parks_agents_like_the_desktop_kills_idle_shells_and_drops_the_tab() {
        let op = PhoneOp::parse(r#"{"op":"tab.detach","space":"s1","tab":"a","park":["t5"],"kill":["t6"]}"#);
        let Some(op) = op else { panic!("tab.detach did not parse") };
        assert!(op.edits());
        let (call, seen) = core(json!([live("t5", "s1", "a"), live("t6", "s1", "a"), live("t7", "s1", "b")]), 1);
        let reply = op.run(call).ok().and_then(|r| serde_json::from_str::<Value>(&r).ok()).unwrap_or_default();
        assert_eq!(reply, json!({"queued":true,"parked":["t5"]}));
        let seen = seen.borrow();
        let methods: Vec<&str> = seen.iter().map(|(m, _)| m.as_str()).collect();
        // The layout first (so a failure there changes nothing), then the terminals.
        assert_eq!(
            methods,
            ["terminal.list", "doc.get", "doc.set", "doc.get", "doc.set", "terminal.label", "terminal.keep", "terminal.kill"]
        );
        // Only tab a leaves the layout; the retry after the version race writes it again.
        assert_eq!(seen[4].1["value"]["spaces"][0]["tabs"], json!([{"id":"b"}]));
        assert_eq!(seen[4].1["version"], 7);
        assert_eq!(seen[5].1, json!({"terminal":"t5","labels":{"herdr.space":"","herdr.tab":""}}));
        assert_eq!(seen[6].1, json!({"terminal":"t5","keep":false}));
        assert_eq!(seen[7].1, json!({"terminal":"t6"}));
    }

    #[test]
    fn detach_refuses_a_terminal_that_left_the_tab_and_keeps_a_tab_with_a_newcomer() {
        let op = PhoneOp::TabDetach { space: "s1".into(), tab: "a".into(), park: vec!["t5".into()], kill: vec![] };
        let (call, seen) = core(json!([live("t5", "s1", "b")]), 0);
        assert!(matches!(op.run(call), Err(Error::StaleTarget)));
        assert_eq!(seen.borrow().len(), 1, "nothing changed");
        // A terminal the sheet did not know about is still in the tab: park ours, keep the tab.
        let (call, seen) = core(json!([live("t5", "s1", "a"), live("t9", "s1", "a")]), 0);
        assert!(op.run(call).is_ok());
        assert!(!seen.borrow().iter().any(|(m, _)| m == "doc.set" || m == "terminal.kill"));
    }

    #[test]
    fn a_layout_that_keeps_failing_leaves_every_terminal_as_it_was() {
        let op = PhoneOp::TabDetach { space: "s1".into(), tab: "a".into(), park: vec!["t5".into()], kill: vec![] };
        let (call, seen) = core(json!([live("t5", "s1", "a")]), 3);
        assert!(matches!(op.run(call), Err(Error::Protocol(_))));
        assert!(!seen.borrow().iter().any(|(m, _)| m.starts_with("terminal.label") || m == "terminal.keep" || m == "terminal.kill"));
        // So the same detach can simply be tried again.
        let (call, _) = core(json!([live("t5", "s1", "a")]), 0);
        assert!(op.run(call).is_ok());
    }

    #[test]
    fn a_parked_terminal_shown_loose_can_be_detached_again_without_a_layout_edit() {
        // Parked by the desktop: empty labels, so the view shows it as loose, its own tab.
        let parked = json!({"address":"t5","state":"live","labels":{"herdr.space":"","herdr.tab":"","parent":"t1"}});
        let op = PhoneOp::TabDetach { space: "loose".into(), tab: "t5".into(), park: vec!["t5".into()], kill: vec![] };
        let (call, seen) = core(json!([parked]), 0);
        assert!(op.run(call).is_ok());
        assert!(!seen.borrow().iter().any(|(m, _)| m.starts_with("doc.")));
    }

    #[test]
    fn detach_needs_something_to_park_and_plain_local_ids() {
        assert_eq!(PhoneOp::parse(r#"{"op":"tab.detach","space":"s1","tab":"a","park":[]}"#), None);
        assert_eq!(PhoneOp::parse(r#"{"op":"tab.detach","space":"s1","tab":"a"}"#), None);
        assert_eq!(PhoneOp::parse(r#"{"op":"tab.detach","space":"s1","tab":"a","park":["laptop/t5"]}"#), None);
        assert_eq!(PhoneOp::parse(r#"{"op":"tab.detach","space":"","tab":"a","park":["t5"]}"#), None);
        assert_eq!(
            PhoneOp::parse(r#"{"op":"tab.detach","space":"s1","tab":"a","park":["t5"]}"#),
            Some(PhoneOp::TabDetach { space: "s1".into(), tab: "a".into(), park: vec!["t5".into()], kill: vec![] })
        );
        assert!(!PhoneOp::UsageLook.edits());
    }

    #[test]
    fn beads_ops_take_only_plain_names_ids_and_fingerprints() {
        assert_eq!(
            PhoneOp::parse(r#"{"op":"beads.detail","project":"repo:herdr","id":"factory-setup.21"}"#),
            Some(PhoneOp::BeadsDetail { project: "repo:herdr".into(), id: "factory-setup.21".into(), confirm: false })
        );
        assert_eq!(
            PhoneOp::parse(r#"{"op":"beads.detail","project":"x","id":"x-1","confirm":true}"#),
            Some(PhoneOp::BeadsDetail { project: "x".into(), id: "x-1".into(), confirm: true })
        );
        assert_eq!(PhoneOp::parse(r#"{"op":"beads.detail","project":"x","id":"x-1","confirm":"yes"}"#), None);
        assert_eq!(
            PhoneOp::parse(r#"{"op":"beads.comments","project":"x","id":"x-1"}"#),
            Some(PhoneOp::BeadsComments { project: "x".into(), id: "x-1".into() })
        );
        assert_eq!(PhoneOp::parse(r#"{"op":"beads.comments","project":"x","id":"x-1","confirm":true}"#), None);
        for bad in [r#""project":"../x","id":"a-1""#, r#""project":"a/b","id":"a-1""#, r#""project":"x","id":"--all""#,
            r#""project":"x","id":"a 1""#, r#""project":"","id":"a-1""#] {
            assert_eq!(PhoneOp::parse(&format!(r#"{{"op":"beads.detail",{bad}}}"#)), None, "{bad}");
        }
        assert_eq!(
            PhoneOp::parse(r#"{"op":"beads.stores","known":["factory=1795296364.1234"]}"#),
            Some(PhoneOp::BeadsStores { known: vec!["factory=1795296364.1234".into()] })
        );
        assert_eq!(PhoneOp::parse(r#"{"op":"beads.stores"}"#), Some(PhoneOp::BeadsStores { known: vec![] }));
        assert_eq!(PhoneOp::parse(r#"{"op":"beads.stores","known":["factory=$(rm)"]}"#), None);
        assert_eq!(PhoneOp::parse(r#"{"op":"beads.stores","known":["factory"]}"#), None);
        assert!(!PhoneOp::BeadsStores { known: vec![] }.edits());
    }

    #[test]
    fn beads_reads_pass_the_scripts_args_as_argv_and_their_text_back() {
        let mut seen = Vec::new();
        let reply = PhoneOp::BeadsDetail { project: "factory".into(), id: "factory-setup.21".into(), confirm: false }
            .run(|method, params| {
                seen.push((method.to_owned(), params));
                Ok(exec(0, "\u{1e}{\"id\":\"factory-setup.21\"}\n\u{1d}[]\n\u{1f}ok\n"))
            })
            .ok()
            .and_then(|r| serde_json::from_str::<Value>(&r).ok())
            .unwrap_or_default();
        assert_eq!(reply["text"], "\u{1e}{\"id\":\"factory-setup.21\"}\n\u{1d}[]\n\u{1f}ok\n");
        let argv = &seen[0].1["argv"];
        // The vendored script runs as it is, under the login-PATH wrapper, with its own args after it.
        assert_eq!(argv[2], WITH_LOGIN_PATH);
        assert_eq!(argv[4], DETAIL_SH);
        assert_eq!(argv.as_array().map(|a| a[5..].to_vec()), Some(vec![json!(STORE_ROOT), json!(REPOS_FILE), json!("factory"), json!("factory-setup.21")]));
        // A confirm asks bd itself: the script's own fifth argument.
        let mut asked = Vec::new();
        let _ = PhoneOp::BeadsDetail { project: "factory".into(), id: "a-1".into(), confirm: true }.run(|_, params| {
            asked.push(params["argv"].as_array().and_then(|a| a.last().cloned()));
            Ok(exec(0, ""))
        });
        assert_eq!(asked, vec![Some(json!("confirm"))]);
        let mut both = Vec::new();
        let _ = PhoneOp::BeadsRead { project: "factory".into(), id: "a-1".into(), confirm: true }.run(|_, params| {
            both.push(params["argv"].clone());
            Ok(exec(0, ""))
        });
        // One exec: the wrapper, then both vendored scripts as they are, then the store args and confirm.
        assert_eq!(both.len(), 1);
        assert_eq!((both[0][4].clone(), both[0][5].clone(), both[0][6].clone()), (json!(READ_SH), json!(DETAIL_SH), json!(COMMENTS_SH)));
        assert_eq!(both[0].as_array().map(|a| a[7..].to_vec()), Some(vec![json!(STORE_ROOT), json!(REPOS_FILE), json!("factory"), json!("a-1"), json!("confirm")]));
        assert_eq!(
            PhoneOp::parse(r#"{"op":"beads.read","project":"x","id":"x-1"}"#),
            Some(PhoneOp::BeadsRead { project: "x".into(), id: "x-1".into(), confirm: false })
        );
        assert_eq!(PhoneOp::parse(r#"{"op":"beads.read","project":"../x","id":"x-1"}"#), None);
        assert!(READ_SH.contains(r#"exec sh -c "$c" sh "$1" "$2" "$3" "$4""#) && !READ_SH.contains("rm "));
        let mut comments = Vec::new();
        let _ = PhoneOp::BeadsComments { project: "factory".into(), id: "a-1".into() }.run(|_, params| {
            comments.push(params["argv"].clone());
            Ok(exec(0, ""))
        });
        assert_eq!(comments[0][4], json!(COMMENTS_SH));
        assert_eq!(comments[0].as_array().map(|a| a[5..].to_vec()), Some(vec![json!(STORE_ROOT), json!(REPOS_FILE), json!("factory"), json!("a-1")]));
        let stores = PhoneOp::BeadsStores { known: vec!["a=1".into()] }
            .run(|_, params| {
                assert_eq!(params["argv"].as_array().and_then(|a| a.last().cloned()), Some(json!("a=1")));
                Ok(exec(124, ""))
            })
            .ok()
            .and_then(|r| serde_json::from_str::<Value>(&r).ok())
            .unwrap_or_default();
        assert_eq!(stores["error"], "the beads read exited with 124");
    }

    /// factory-9zz.14: the whole-store mirror, here or (the deacon's Tasks) on a named machine.
    #[test]
    fn the_beads_dump_takes_fingerprints_and_a_plain_machine_name() {
        assert_eq!(
            PhoneOp::parse(r#"{"op":"beads.dump","known":["factory=2091239757.147"]}"#),
            Some(PhoneOp::BeadsDump { known: vec!["factory=2091239757.147".into()], on: None })
        );
        assert_eq!(
            PhoneOp::parse(r#"{"op":"beads.dump","known":[],"on":"small-pickle"}"#),
            Some(PhoneOp::BeadsDump { known: vec![], on: Some("small-pickle".into()) })
        );
        for bad in [r#""on":"../x""#, r#""on":"a b""#, r#""on":"-x""#, r#""on":"""#, r#""on":7"#, r#""known":["x=$(rm)"]"#, r#""root":"/""#] {
            assert_eq!(PhoneOp::parse(&format!(r#"{{"op":"beads.dump",{bad}}}"#)), None, "{bad}");
        }
        assert_eq!(PhoneOp::parse(&format!(r#"{{"op":"beads.dump","on":"{}"}}"#, "m".repeat(65))), None);
        let mut seen = Vec::new();
        let reply = PhoneOp::BeadsDump { known: vec!["a=1.2".into()], on: Some("server".into()) }
            .run(|method, params| {
                seen.push((method.to_owned(), params));
                Ok(exec(0, "\u{1e}a\n\u{1c}1.2\n\u{1f}same\n"))
            })
            .ok()
            .and_then(|r| serde_json::from_str::<Value>(&r).ok())
            .unwrap_or_default();
        assert_eq!(reply["text"], "\u{1e}a\n\u{1c}1.2\n\u{1f}same\n");
        let (method, params) = &seen[0];
        assert_eq!(method, "machine.exec");
        assert_eq!(params["on"], "server");
        assert_eq!(params["argv"][4], json!(DUMP_SH));
        assert_eq!(params["argv"].as_array().and_then(|a| a.last().cloned()), Some(json!("a=1.2")));
        // Here: no `on` at all, so the core runs it on its own machine.
        let mut here = Vec::new();
        let _ = PhoneOp::BeadsDump { known: vec![], on: None }.run(|_, params| {
            here.push(params);
            Ok(exec(0, ""))
        });
        assert!(here[0].get("on").is_none());
    }

    #[test]
    fn the_machine_list_keeps_plain_names_only() {
        assert_eq!(PhoneOp::parse(r#"{"op":"machines.list"}"#), Some(PhoneOp::MachinesList));
        assert_eq!(PhoneOp::parse(r#"{"op":"machines.list","on":"x"}"#), None);
        let reply = PhoneOp::MachinesList
            .run(|method, _| {
                assert_eq!(method, "machine.list");
                Ok(json!({"machines":[
                    {"name":"laptop","state":"connected","temporary":false,"target":"laptop"},
                    {"name":"bad name","state":"connected"},
                    {"name":"sandbox-1","state":"unreachable","temporary":true}]}))
            })
            .ok()
            .and_then(|r| serde_json::from_str::<Value>(&r).ok())
            .unwrap_or_default();
        assert_eq!(
            reply["machines"],
            json!([{"name":"laptop","state":"connected","temporary":false},{"name":"sandbox-1","state":"unreachable","temporary":true}])
        );
    }

    /// DUMP_SH run for real against a fake bd: frames, gzip, "same" by the manifest's bytes
    /// (not its mtime, which every read touches), and bd's failure said per store.
    #[cfg(unix)]
    #[test]
    fn the_dump_script_frames_each_store_and_skips_unchanged_ones() -> std::result::Result<(), Box<dyn std::error::Error>> {
        use base64::Engine as _;
        use std::io::Write as _;
        let dir = std::env::temp_dir().join(format!("herdr-dump-{}", std::process::id()));
        let root = dir.join("beads");
        let bin = dir.join("bin");
        for store in ["alpha", "broken"] {
            std::fs::create_dir_all(root.join(store).join(".beads/embeddeddolt/db/.dolt/noms"))?;
            std::fs::write(root.join(store).join(".beads/embeddeddolt/db/.dolt/noms/manifest"), format!("5:{store}:root"))?;
        }
        std::fs::create_dir_all(&bin)?;
        let bd = bin.join("bd");
        std::fs::write(
            &bd,
            "#!/bin/sh\ncase \"$BEADS_DIR\" in *broken*) echo 'Error: locked' >&2; exit 3;; esac\n\
             touch \"$BEADS_DIR\"/embeddeddolt/db/.dolt/noms/manifest\nprintf '{\"id\":\"a-1\",\"title\":\"One\"}\\n'\n",
        )?;
        std::process::Command::new("chmod").arg("+x").arg(&bd).status()?;
        let path = format!("{}:{}", bin.display(), std::env::var("PATH").unwrap_or_default());
        let run = |known: &[&str]| -> std::io::Result<String> {
            let out = std::process::Command::new("sh")
                .arg("-c")
                .arg(DUMP_SH)
                .arg("sh")
                .arg(&root)
                .arg(dir.join("none.json"))
                .args(known)
                .env("PATH", &path)
                .output()?;
            Ok(String::from_utf8_lossy(&out.stdout).into_owned())
        };
        let first = run(&[])?;
        let chunks: Vec<&str> = first.split('\u{1e}').skip(1).collect();
        assert_eq!(chunks.len(), 2, "{first}");
        let alpha = chunks[0];
        assert!(alpha.starts_with("alpha\n\u{1c}"), "{alpha}");
        let mut lines = alpha.lines().skip(1);
        let fp = lines.next().ok_or("no fingerprint")?.trim_start_matches('\u{1c}').to_owned();
        assert!(known_pair(&format!("alpha={fp}")), "{fp}");
        let encoding = lines.next().ok_or("no encoding")?.trim_start_matches('\u{1d}').to_owned();
        let payload = base64::engine::general_purpose::STANDARD.decode(lines.next().ok_or("no payload")?)?;
        let text = if encoding == "gzip" {
            let mut gunzip = std::process::Command::new("gzip")
                .arg("-dc")
                .stdin(std::process::Stdio::piped())
                .stdout(std::process::Stdio::piped())
                .spawn()?;
            gunzip.stdin.take().ok_or("no stdin")?.write_all(&payload)?;
            String::from_utf8(gunzip.wait_with_output()?.stdout)?
        } else {
            String::from_utf8(payload)?
        };
        assert_eq!(text, "{\"id\":\"a-1\",\"title\":\"One\"}\n");
        assert_eq!(lines.next(), Some("\u{1f}0"));
        assert!(
            chunks[1].starts_with("broken\n") && chunks[1].contains("\u{1d}-\nError: locked") && chunks[1].ends_with("\u{1f}3\n"),
            "{}",
            chunks[1]
        );
        // The read touched the manifest's mtime, not its bytes: the store is still "same".
        let again = run(&[&format!("alpha={fp}")])?;
        assert!(again.contains(&format!("\u{1e}alpha\n\u{1c}{fp}\n\u{1f}same\n")), "{again}");
        // A write (new bytes): exported again.
        std::fs::write(root.join("alpha/.beads/embeddeddolt/db/.dolt/noms/manifest"), "5:alpha:next")?;
        assert!(!run(&[&format!("alpha={fp}")])?.contains("\u{1f}same"));
        let _ = std::fs::remove_dir_all(&dir);
        Ok(())
    }

    /// A bead in a store only the machine's discovery knows (no repos.json entry, not under the
    /// root): one beads.read finds its plan and its comments there, through the vendored
    /// DETAIL_SH and COMMENTS_SH asking `beads-stores list --no-terminals` (no core call from
    /// inside the phone's exec); a `skip:` candidate is never read.
    #[cfg(unix)]
    #[test]
    fn a_bead_read_finds_its_bead_in_a_discovered_store() -> std::result::Result<(), Box<dyn std::error::Error>> {
        let dir = std::env::temp_dir().join(format!("herdr-discover-read-{}", std::process::id()));
        let found = dir.join("code/macro-bots/.beads");
        let skipped = dir.join("code/clone/.beads");
        let bin = dir.join("bin");
        for d in [&found, &skipped, &bin, &dir.join("beads")] {
            std::fs::create_dir_all(d)?;
        }
        let script = |name: &str, body: String| -> std::io::Result<()> {
            let file = bin.join(name);
            std::fs::write(&file, format!("#!/bin/sh\n{body}"))?;
            std::process::Command::new("chmod").arg("+x").arg(&file).status().map(|_| ())
        };
        // bd knows mb-1 only in the discovered store; reading the skipped one would be a failure.
        script(
            "bd",
            format!(
                "case \"$BEADS_DIR\" in {s}) echo read-skipped; exit 7;; {f}) ;; *) exit 1;; esac\n\
                 case \"$1\" in show) echo '[{{\"id\":\"mb-1\",\"title\":\"Teammates\",\"comment_count\":1}}]';; \
                 comments) echo '[{{\"text\":\"kept\",\"author\":\"a\"}}]';; esac\n",
                s = skipped.display(),
                f = found.display(),
            ),
        )?;
        script(
            "beads-stores",
            format!(
                "[ \"$2\" = --no-terminals ] || exit 9\nprintf 'repo:clone\\t{s}\\tskip:export-only\\t-\\n'\nprintf 'repo:macro-bots\\t{f}\\tfound\\t-\\n'\n",
                s = skipped.display(),
                f = found.display(),
            ),
        )?;
        let out = std::process::Command::new("sh")
            .arg("-c")
            .arg(READ_SH)
            .arg("sh")
            .arg(DETAIL_SH)
            .arg(COMMENTS_SH)
            .arg(dir.join("beads"))
            .arg(dir.join("none.json"))
            .arg("teammates")
            .arg("mb-1")
            .env("PATH", format!("{}:/usr/bin:/bin:{}", bin.display(), std::env::var("PATH").unwrap_or_default()))
            .env("HOME", &dir)
            .env_remove("XDG_CACHE_HOME")
            .output()?;
        let text = String::from_utf8_lossy(&out.stdout).into_owned();
        let (plan, comments) = text.split_once("\u{7}\n").ok_or("no comments part")?;
        assert!(plan.contains("\"Teammates\"") && !plan.contains("\u{1f}missing"), "{text}");
        assert!(comments.contains("\"kept\"") && comments.ends_with("\u{1f}ok\n"), "{text}");
        assert!(!text.contains("read-skipped"), "{text}");
        let _ = std::fs::remove_dir_all(&dir);
        Ok(())
    }

    /// The discovery contract (home-manager-conf modules/beads/SPEC.md): when the machine has
    /// `beads-stores`, DUMP_SH exports exactly the stores it lists (a found in-repo store with no
    /// repos.json entry included), in its order and under its keys; when it is missing or fails,
    /// today's walk of the root and repos.json, so a machine never shows fewer stores than before.
    #[cfg(unix)]
    #[test]
    fn the_dump_script_reads_the_stores_the_machine_discovers() -> std::result::Result<(), Box<dyn std::error::Error>> {
        use base64::Engine as _;
        let dir = std::env::temp_dir().join(format!("herdr-discover-{}", std::process::id()));
        let root = dir.join("beads");
        let found = dir.join("code/macro-bots/.beads");
        let bin = dir.join("bin");
        for store in [root.join("alpha/.beads"), found.clone()] {
            std::fs::create_dir_all(store.join("embeddeddolt/db/.dolt/noms"))?;
            std::fs::write(store.join("embeddeddolt/db/.dolt/noms/manifest"), "5:x:root")?;
        }
        std::fs::create_dir_all(&bin)?;
        let script = |name: &str, body: &str| -> std::io::Result<()> {
            let file = bin.join(name);
            std::fs::write(&file, format!("#!/bin/sh\n{body}"))?;
            std::process::Command::new("chmod").arg("+x").arg(&file).status().map(|_| ())
        };
        // bd answers with the store it was pointed at, so each frame says which directory it read.
        script("bd", "printf '{\"dir\":\"%s\"}\\n' \"$BEADS_DIR\"\n")?;
        let run = |stores: Option<&str>| -> std::result::Result<Vec<(String, String)>, Box<dyn std::error::Error>> {
            let _ = std::fs::remove_file(bin.join("beads-stores"));
            if let Some(body) = stores {
                script("beads-stores", body)?;
            }
            let out = std::process::Command::new("sh")
                .arg("-c")
                .arg(DUMP_SH)
                .arg("sh")
                .arg(&root)
                .arg(dir.join("none.json"))
                .env("PATH", format!("{}:/usr/bin:/bin:{}", bin.display(), std::env::var("PATH").unwrap_or_default()))
                .output()?;
            let text = String::from_utf8_lossy(&out.stdout).into_owned();
            let mut frames = Vec::new();
            for chunk in text.split('\u{1e}').skip(1) {
                let key = chunk.lines().next().unwrap_or("").to_owned();
                let enc = chunk.lines().nth(2).unwrap_or("");
                let body = chunk.lines().nth(3).unwrap_or("");
                let read = if enc == "\u{1d}plain" {
                    String::from_utf8(base64::engine::general_purpose::STANDARD.decode(body)?)?
                } else {
                    let mut gunzip = std::process::Command::new("sh")
                        .arg("-c")
                        .arg("base64 -d | gzip -dc")
                        .stdin(std::process::Stdio::piped())
                        .stdout(std::process::Stdio::piped())
                        .spawn()?;
                    std::io::Write::write_all(&mut gunzip.stdin.take().ok_or("no stdin")?, body.as_bytes())?;
                    String::from_utf8(gunzip.wait_with_output()?.stdout)?
                };
                frames.push((key, read.trim().to_owned()));
            }
            Ok(frames)
        };
        let alpha = (String::from("alpha"), format!("{{\"dir\":\"{}/alpha/.beads\"}}", root.display()));
        // The machine's own list: its keys, its directories, its order, and the root and repos
        // file it was asked about, with no core call from inside the phone's own exec
        // (--no-terminals); a skipped candidate ("skip:<reason>") is never read.
        let listed = format!(
            "[ \"$1 $2 $3 $4 $5 $6\" = 'list --no-terminals --root {r} --repos {f}' ] || exit 9\n\
             printf 'alpha\\t{r}/alpha/.beads\\troot\\t-\\n'\n\
             printf 'repo:clone\\t{b}\\tskip:export-only\\t-\\n'\n\
             printf 'repo:macro-bots\\t{b}\\tfound\\t{m}\\n'\n",
            r = root.display(),
            f = dir.join("none.json").display(),
            b = found.display(),
            m = dir.join("code/macro-bots").display(),
        );
        assert_eq!(
            run(Some(&listed))?,
            vec![alpha.clone(), (String::from("repo:macro-bots"), format!("{{\"dir\":\"{}\"}}", found.display()))]
        );
        // Missing, or failing (an older or broken generation): today's walk, today's keys.
        assert_eq!(run(None)?, vec![alpha.clone()]);
        assert_eq!(run(Some("echo half\nexit 3\n"))?, vec![alpha]);
        // It answered and found nothing: nothing, not the walk.
        assert_eq!(run(Some("exit 0\n"))?, vec![]);
        let _ = std::fs::remove_dir_all(&dir);
        Ok(())
    }

    #[test]
    fn a_status_doc_is_read_by_a_plain_name_under_deacon_work_only() {
        assert_eq!(PhoneOp::parse(r#"{"op":"doc.read","name":"_deacon"}"#), Some(PhoneOp::DocRead { name: "_deacon".into(), key: None }));
        assert_eq!(
            PhoneOp::parse(r#"{"op":"doc.read","name":"factory/phone-rail","key":"1795350000:123456"}"#),
            Some(PhoneOp::DocRead { name: "factory/phone-rail".into(), key: Some("1795350000:123456".into()) })
        );
        for bad in ["../x", "a/../b", ".hidden", "a/b/c", "-x", "a b", "", "a/", "/etc/passwd", "a/.x"] {
            assert_eq!(PhoneOp::parse(&format!(r#"{{"op":"doc.read","name":"{bad}"}}"#)), None, "{bad}");
        }
        assert_eq!(PhoneOp::parse(r#"{"op":"doc.read","name":"x","key":"$(rm)"}"#), None);
        assert_eq!(PhoneOp::parse(r#"{"op":"doc.read","name":"x","path":"/etc"}"#), None);
        let mut seen = Vec::new();
        let reply = PhoneOp::DocRead { name: "factory".into(), key: Some("1:2".into()) }
            .run(|_, params| {
                seen.push(params["argv"].clone());
                Ok(exec(0, "same\n1795350000\n"))
            })
            .ok()
            .and_then(|r| serde_json::from_str::<Value>(&r).ok())
            .unwrap_or_default();
        assert_eq!(reply["text"], "same\n1795350000\n");
        // No login shell: the doc needs no bd; the name and key are the script's own args.
        assert_eq!(seen[0], json!(["sh", "-c", DOC_SH, "sh", "factory", "1:2"]));
        assert!(DOC_SH.contains("$HOME/deacon/work/$1.md") && DOC_SH.contains("head -c 200000"));
        assert!(!DOC_SH.contains("rm ") && !DOC_SH.contains(" > ") && !DOC_SH.contains(">>"));
    }

    #[test]
    fn the_beads_scripts_only_read() {
        for script in [STORES_SH, DETAIL_SH, COMMENTS_SH, DUMP_SH] {
            assert!(script.contains("--json") || script.contains("bd export >"));
            for write in ["bd update", "bd close", "bd create", "bd comment ", "bd delete", "bd sync", "rm -r", "rm -f \"$1", "rm -f \"$2"] {
                assert!(!script.contains(write), "{write}");
            }
        }
        // DUMP_SH removes only its own two mktemp files.
        assert_eq!(DUMP_SH.matches("rm ").count(), 1);
        assert!(DUMP_SH.contains(r#"trap 'rm -f "$out" "$err"' EXIT"#) && DUMP_SH.contains("out=$(mktemp"));
        // DETAIL_SH writes only its own answers and marks, under ~/.cache/herdr-beads, and prunes only there.
        assert!(DETAIL_SH.contains(r#"cache="${XDG_CACHE_HOME:-$HOME/.cache}/herdr-beads""#));
        assert_eq!(DETAIL_SH.matches("-delete").count(), 1);
        assert!(DETAIL_SH.contains(r#"find "$cache" -type f -mmin +1440 -delete"#));
        // The desktop's framing, which BeadsModel parses.
        assert!(STORES_SH.contains("\\036%s\\n\\034%s\\n") && DETAIL_SH.contains("\\037missing"));
    }

    #[test]
    fn the_session_entry_point_lets_every_phone_op_through() {
        // The JNI entry (remote::session_action) refuses unknown actions as Config before any
        // session is looked up; a phone op must get past that gate to the core session.
        for op in [
            r#"{"op":"usage.look"}"#,
            r#"{"op":"beads.stores","known":[]}"#,
            r#"{"op":"beads.dump","known":[]}"#,
            r#"{"op":"machines.list"}"#,
            r#"{"op":"beads.detail","project":"demo","id":"demo-1.2"}"#,
            r#"{"op":"beads.comments","project":"demo","id":"demo-1.2"}"#,
            r#"{"op":"beads.read","project":"demo","id":"demo-1.2"}"#,
            r#"{"op":"doc.read","name":"factory/phone-rail"}"#,
            r#"{"op":"tab.detach","space":"s1","tab":"a","park":["t5"]}"#,
        ] {
            let refused = crate::remote::session_action(u64::MAX, op);
            assert!(!matches!(refused, Err(Error::Config)), "{op} was refused at the gate");
        }
        assert!(matches!(crate::remote::session_action(u64::MAX, r#"{"op":"no.such"}"#), Err(Error::Config)));
    }

    #[test]
    fn parses_only_its_own_ops() {
        assert_eq!(PhoneOp::parse(r#"{"op":"usage.look"}"#), Some(PhoneOp::UsageLook));
        assert_eq!(PhoneOp::parse(r#"{"op":"tab.rename"}"#), None);
        assert_eq!(PhoneOp::parse("usage.look"), None);
        // Strict: a field the op does not take refuses the whole op.
        assert_eq!(PhoneOp::parse(r#"{"op":"usage.look","on":"laptop"}"#), None);
        assert_eq!(PhoneOp::parse(r#"{"op":"beads.detail","project":"demo","id":"demo-1","argv":["rm"]}"#), None);
        assert_eq!(PhoneOp::parse(r#"{"op":"tab.detach","space":"s1","tab":"a","park":["t5"],"force":true}"#), None);
        assert_eq!(PhoneOp::parse(r#"{"op":"beads.stores","known":[],"root":"/"}"#), None);
        assert_eq!(PhoneOp::parse(&format!(r#"{{"op":"beads.stores","known":["{}"]}}"#, "x".repeat(40_000))), None);
    }

    #[test]
    fn the_reduction_keeps_numbers_and_drops_everything_else() {
        let reduced = reduce_usage(&sample());
        let text = reduced.to_string();
        for leaked in ["sk-secret", "example.com", "/home/x", "/secret/", "private words", "extra", "events"] {
            assert!(!text.contains(leaked), "{leaked} leaked: {text}");
        }
        let a = &reduced["accounts"][0];
        assert_eq!(a["label"], "demo-a");
        assert_eq!(a["provider"], "claude");
        assert_eq!(a["active"], true);
        assert_eq!(a["remaining_percent"], 40.0);
        assert_eq!(a["windows"][0]["name"], "five_hour");
        assert_eq!(a["windows"][0]["used_percent"], 60.0);
        assert_eq!(a["windows"][0]["resets_at"], 1_700_003_600.0);
        assert_eq!(a["duplicates"], json!(["demo-b"]));
        assert_eq!(a["reserved"], false);
        assert_eq!(reduced["credential_mode"], "refresh-owner");
        assert_eq!(reduced["auto"], json!({"claude":{"enabled":true,"threshold":90.0}}));
        assert_eq!(reduced["sync"], json!({"failures":2.0,"last_sync":1.0}));
        assert!(a.get("usage_error").is_none());
        let c = &reduced["accounts"][1];
        assert_eq!(c["usage_status"], "backoff");
        assert_eq!(c["usage_reason"], "provider-limit");
        assert_eq!(c["age_seconds"], 90.0);
        assert_eq!(c["usage_error"], json!({"class":"provider-limit","http_status":429.0,"retry_after_seconds":120.0}));
    }

    #[test]
    fn another_schema_keeps_only_its_number() {
        let mut list = sample();
        list["schema_version"] = json!(2);
        assert_eq!(reduce_usage(&list), json!({"schema_version":2,"accounts":[]}));
        assert_eq!(reduce_usage(&json!({})), json!({"schema_version":0,"accounts":[]}));
    }

    #[test]
    fn bounds_accounts_windows_and_words() {
        let many: Vec<Value> = (0..100)
            .map(|i| json!({"label":format!("a{i}"),"provider":"claude","usage":{"windows":vec![json!({"name":"w"}); 20]}}))
            .collect();
        let reduced = reduce_usage(&json!({"schema_version":1,"accounts":many}));
        assert_eq!(reduced["accounts"].as_array().map(Vec::len), Some(MAX_ACCOUNTS));
        assert_eq!(reduced["accounts"][0]["windows"].as_array().map(Vec::len), Some(MAX_WINDOWS));
        // An account without a usable label or provider is left out, not shown blank.
        let odd = json!({"schema_version":1,"accounts":[
            {"label":"x\ny","provider":"claude"}, {"label":"ok","provider":""}, {"label":"y".repeat(65),"provider":"pi"}
        ]});
        assert_eq!(reduce_usage(&odd)["accounts"], json!([]));
    }

    #[test]
    fn run_asks_the_machine_once_and_says_missing_failed_or_garbled() {
        let mut seen = Vec::new();
        let reply = PhoneOp::UsageLook
            .run(|method, params| {
                seen.push((method.to_owned(), params));
                Ok(exec(0, &sample().to_string()))
            })
            .map(|r| serde_json::from_str::<Value>(&r).ok());
        let reply = reply.ok().flatten().unwrap_or_default();
        assert_eq!(reply["queued"], true);
        assert_eq!(reply["usage"]["accounts"][0]["label"], "demo-a");
        assert_eq!(seen.len(), 1);
        assert_eq!(seen[0].0, "machine.exec");
        let script = seen[0].1["argv"][2].as_str().unwrap_or("");
        assert!(script.contains("aswap list --json"));
        // Cache-only: never a refresh or status, which spend quota.
        assert!(!script.contains("refresh") && !script.contains("status"));

        let say = |code: i64, out: &str| {
            PhoneOp::UsageLook
                .run(|_, _| Ok(exec(code, out)))
                .ok()
                .and_then(|r| serde_json::from_str::<Value>(&r).ok())
                .unwrap_or_default()
        };
        let missing = say(127, "");
        assert_eq!((missing["queued"].clone(), missing["missing"].clone()), (json!(true), json!(true)));
        assert_eq!(say(1, "secret stderr")["error"], "aswap list exited with 1");
        assert_eq!(say(0, "not json")["error"], "couldn't read aswap's output");
        // The exec's own end, not aswap's: a timeout or a signal says so.
        let timed = |result: Value| {
            PhoneOp::UsageLook
                .run(|_, _| Ok(result.clone()))
                .ok()
                .and_then(|r| serde_json::from_str::<Value>(&r).ok())
                .unwrap_or_default()
        };
        assert_eq!(timed(json!({"code":null,"timed_out":true,"stdout":""}))["error"], "aswap list timed out on the machine");
        assert_eq!(timed(json!({"code":127,"timed_out":true,"stdout":""}))["error"], "aswap list timed out on the machine");
        assert_eq!(timed(json!({"code":null,"signal":9,"stdout":""}))["error"], "aswap list was stopped before it finished");
        // Every reply says where its time went: the round trip, and the core's own run (its `ms`).
        let mut timed_exec = exec(0, &sample().to_string());
        timed_exec["ms"] = json!(73);
        let reply: Value = PhoneOp::UsageLook
            .run(|_, _| Ok(timed_exec.clone()))
            .ok()
            .and_then(|r| serde_json::from_str(&r).ok())
            .unwrap_or_default();
        assert_eq!(reply["timing"]["exec_ms"], 73);
        assert!(reply["timing"]["rpc_ms"].is_u64());
        assert!(say(127, "")["timing"]["exec_ms"].is_null());
        let failed = PhoneOp::UsageLook.run(|_, _| Err(Error::Closed));
        assert!(matches!(failed, Err(Error::Closed)));
    }

    #[test]
    #[allow(clippy::unwrap_used)]
    fn an_agents_history_is_asked_only_by_its_labels_and_a_path_it_found() {
        use crate::agent_history::Kind;
        let session = "01a1f1fe-268f-7ce3-9547-c422c66af57b";
        assert_eq!(
            PhoneOp::parse(&format!(r#"{{"op":"agent.history","kind":"codex","session":"{session}"}}"#)),
            Some(PhoneOp::AgentHistory { kind: Kind::Codex, session: session.into(), path: None, key: None })
        );
        let path = format!("/home/wolf/.codex/sessions/2026/11/19/rollout-2026-11-19T04-08-47-{session}.jsonl");
        assert!(PhoneOp::parse(&format!(r#"{{"op":"agent.history","kind":"codex","session":"{session}","path":"{path}","key":"12:34"}}"#)).is_some());
        for bad in [
            r#"{"op":"agent.history","kind":"gemini","session":"abc"}"#.to_string(),
            r#"{"op":"agent.history","kind":"claude","session":"../x"}"#.to_string(),
            format!(r#"{{"op":"agent.history","kind":"claude","session":"{session}","path":"/etc/passwd"}}"#),
            format!(r#"{{"op":"agent.history","kind":"claude","session":"{session}","path":"/a/../{session}.jsonl"}}"#),
            format!(r#"{{"op":"agent.history","kind":"claude","session":"{session}","key":"x"}}"#),
            format!(r#"{{"op":"agent.history","kind":"claude","session":"{session}","extra":1}}"#),
        ] {
            assert_eq!(PhoneOp::parse(&bad), None, "{bad}");
        }
    }

    #[test]
    #[allow(clippy::unwrap_used)]
    fn the_history_answer_is_rendered_and_never_the_raw_file() {
        use crate::agent_history::Kind;
        use base64::Engine as _;
        let exec = |out: &str| json!({"code":0,"stdout":base64::engine::general_purpose::STANDARD.encode(out),"truncated":false});
        let none = history_reply(Kind::Claude, &exec("unavailable 0\n")).unwrap();
        assert_eq!(none["unavailable"], "its session file is not on that machine");
        let two = history_reply(Kind::Claude, &exec("unavailable 2\n")).unwrap();
        assert_eq!(two["unavailable"], "more than one session file matches");
        let same = history_reply(Kind::Claude, &exec("path /h/.claude/projects/x/s.jsonl\nkey 10:20\nsame\n")).unwrap();
        assert_eq!((same["same"].clone(), same["key"].clone()), (json!(true), json!("10:20")));
        let file = r#"{"type":"user","message":{"role":"user","content":"hello"}}"#;
        let data = history_reply(Kind::Claude, &exec(&format!("path /h/.claude/projects/x/s.jsonl\nkey 60:20\ndata\n{file}\n"))).unwrap();
        assert_eq!(data["text"], "> hello\n", "a short file is read whole, its first line kept");
        assert!(!data.to_string().contains("\"type\":\"user\""), "the raw JSON never leaves");
        let long = history_reply(Kind::Claude, &exec(&format!("path p.jsonl\nkey 99999999:20\ndata\ncut{{\n{file}\n"))).unwrap();
        assert_eq!(long["text"], "> hello\n", "a longer file's first, cut line is dropped");
        let failed = history_reply(Kind::Claude, &json!({"code":1,"stdout":""})).unwrap();
        assert!(failed["error"].as_str().unwrap().contains("exited with 1"));
    }

    /// history.sh itself, in a scratch HOME: found by labels only, one regular file or nothing.
    #[cfg(unix)]
    #[test]
    #[allow(clippy::unwrap_used)]
    fn history_sh_finds_exactly_one_regular_file_and_never_follows_a_link() {
        let home = std::env::temp_dir().join(format!("herdr-history-sh-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&home);
        let run = |args: &[&str]| -> String {
            let out = std::process::Command::new("sh")
                .arg("-c")
                .arg(HISTORY_SH)
                .arg("sh")
                .args(args)
                .env("HOME", &home)
                .output()
                .unwrap();
            String::from_utf8_lossy(&out.stdout).into_owned()
        };
        let day = home.join(".codex/sessions/2026/11/19");
        std::fs::create_dir_all(&day).unwrap();
        assert_eq!(run(&["codex", "aaaa-1", "", "", "100"]), "unavailable 0\n");
        let file = day.join("rollout-2026-11-19T04-08-47-aaaa-1.jsonl");
        std::fs::write(&file, "one\ntwo\n").unwrap();
        let first = run(&["codex", "aaaa-1", "", "", "100"]);
        assert!(first.starts_with(&format!("path {}\nkey 8:", file.display())), "{first}");
        assert!(first.ends_with("data\none\ntwo\n"), "{first}");
        let key = first.lines().nth(1).unwrap().strip_prefix("key ").unwrap().to_string();
        assert!(run(&["codex", "aaaa-1", &file.display().to_string(), &key, "100"]).ends_with("same\n"));
        // A path outside that harness's folder is never read: it is found again.
        assert!(run(&["codex", "aaaa-1", "/etc/passwd", "", "100"]).starts_with(&format!("path {}", file.display())));
        std::fs::write(day.join("rollout-2026-11-20T00-00-00-aaaa-1.jsonl"), "x\n").unwrap();
        assert_eq!(run(&["codex", "aaaa-1", "", "", "100"]), "unavailable 2\n", "two matches: neither");
        let project = home.join(".claude/projects/-x");
        std::fs::create_dir_all(&project).unwrap();
        std::os::unix::fs::symlink("/etc/hostname", project.join("bbbb-2.jsonl")).unwrap();
        assert_eq!(run(&["claude", "bbbb-2", "", "", "100"]), "unavailable 0\n", "a link is not a session file");
        let _ = std::fs::remove_dir_all(&home);
    }
}
