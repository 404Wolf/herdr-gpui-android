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

/// The desktop factory mod's scripts (home-manager-conf
/// users/wolf/programs/herdr-core/app/factory/src/feed.ts STORES_SH and
/// DETAIL_SH at 5e6c79eb), byte for byte, so the phone reads beads exactly as
/// the Work panel does; BeadsModel parses their output. Both are read-only.
const STORES_SH: &str = include_str!("beads/stores.sh");
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
    /// One bead's `bd show` and `bd comments` (DETAIL_SH), from the store of
    /// `project` (a store name, or `repo:<name>`'s project).
    BeadsDetail { project: String, id: String },
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
            "beads.detail" => {
                only(&["project", "id"]).then_some(())?;
                let project = value["project"].as_str().filter(|p| store_name(p))?.to_owned();
                let id = value["id"].as_str().filter(|i| bead_id(i))?.to_owned();
                Some(Self::BeadsDetail { project, id })
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
    pub(crate) fn edits(&self) -> bool {
        matches!(self, Self::TabDetach { .. })
    }

    /// Runs the op with `call` (the core session's RPC) and returns the
    /// action reply the app reads: `{"queued":true,"usage":{..}}`,
    /// `{"queued":true,"missing":true}` when the machine has no aswap, or
    /// `{"queued":true,"error":".."}` when aswap failed.
    pub(crate) fn run(&self, mut call: impl FnMut(&str, Value) -> Result<Value>) -> Result<String> {
        match self {
            Self::UsageLook => {
                let result = call("machine.exec", json!({"argv":["sh","-c",USAGE_LOOK],"timeout":10}))?;
                Ok(usage_reply(&result)?.to_string())
            }
            Self::TabDetach { space, tab, park, kill } => {
                detach(&mut call, space, tab, park, kill)?;
                Ok(json!({"queued":true,"parked":park}).to_string())
            }
            Self::BeadsStores { known } => {
                let mut argv = vec![json!("sh"), json!("-c"), json!(WITH_LOGIN_PATH), json!("sh"), json!(STORES_SH), json!(STORE_ROOT), json!(REPOS_FILE)];
                argv.extend(known.iter().map(|k| json!(k)));
                let result = call("machine.exec", json!({"argv":argv,"timeout":20}))?;
                Ok(script_reply(&result)?.to_string())
            }
            Self::BeadsDetail { project, id } => {
                let argv = json!(["sh", "-c", WITH_LOGIN_PATH, "sh", DETAIL_SH, STORE_ROOT, REPOS_FILE, project, id]);
                let result = call("machine.exec", json!({"argv":argv,"timeout":10}))?;
                Ok(script_reply(&result)?.to_string())
            }
        }
    }
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
            Some(PhoneOp::BeadsDetail { project: "repo:herdr".into(), id: "factory-setup.21".into() })
        );
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
        let reply = PhoneOp::BeadsDetail { project: "factory".into(), id: "factory-setup.21".into() }
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

    #[test]
    fn the_beads_scripts_only_read() {
        for script in [STORES_SH, DETAIL_SH] {
            assert!(script.contains("--json"));
            for write in ["bd update", "bd close", "bd create", "bd comment ", "bd delete", "bd sync", "rm "] {
                assert!(!script.contains(write), "{write}");
            }
        }
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
            r#"{"op":"beads.detail","project":"demo","id":"demo-1.2"}"#,
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
        assert_eq!(say(127, ""), json!({"queued":true,"missing":true}));
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
        let failed = PhoneOp::UsageLook.run(|_, _| Err(Error::Closed));
        assert!(matches!(failed, Err(Error::Closed)));
    }
}
