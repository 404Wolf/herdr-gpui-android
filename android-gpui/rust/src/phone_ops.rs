//! Read-only looks the phone asks of the machine it is connected to, apart
//! from core_session's layout edits: `{"op":"usage.look"}` runs that machine's
//! `aswap list --json` (cache-only: no network, no quota spent) through
//! `machine.exec`. The core session tries [`PhoneOp::parse`] for an action it
//! does not know and hands the op its RPC call, so this module never owns the
//! socket.
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

#[derive(Debug, PartialEq, Eq)]
pub(crate) enum PhoneOp {
    /// The selected machine's aswap accounts and their cached usage.
    UsageLook,
}

impl PhoneOp {
    pub(crate) fn parse(action: &str) -> Option<Self> {
        if !action.starts_with('{') || action.len() > 256 {
            return None;
        }
        let value: Value = serde_json::from_str(action).ok()?;
        match value["op"].as_str()? {
            "usage.look" => Some(Self::UsageLook),
            _ => None,
        }
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
        }
    }
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

    #[test]
    fn parses_only_its_own_ops() {
        assert_eq!(PhoneOp::parse(r#"{"op":"usage.look"}"#), Some(PhoneOp::UsageLook));
        assert_eq!(PhoneOp::parse(r#"{"op":"tab.rename"}"#), None);
        assert_eq!(PhoneOp::parse("usage.look"), None);
        assert_eq!(PhoneOp::parse(&format!(r#"{{"op":"usage.look","pad":"{}"}}"#, "x".repeat(300))), None);
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
