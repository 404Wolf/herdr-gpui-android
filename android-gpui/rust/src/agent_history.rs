//! An agent's conversation as plain text, from its own session file (factory-9zz.12): Claude
//! Code's and Codex's JSONL, rendered the way their screens show the turns, tool output cut
//! short. A full-screen agent keeps no scrollback in its terminal, so this is how the phone has
//! real history to read offline. The file is found on the machine by history.sh from the
//! terminal's own labels only; this module only reads text it was given.

/// The harnesses whose session files are read.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) enum Kind {
    Claude,
    Codex,
}

impl Kind {
    pub(crate) fn parse(label: &str) -> Option<Kind> {
        match label {
            "claude" => Some(Kind::Claude),
            "codex" => Some(Kind::Codex),
            _ => None,
        }
    }

    pub(crate) fn name(self) -> &'static str {
        match self {
            Kind::Claude => "claude",
            Kind::Codex => "codex",
        }
    }
}

/// How much of the file's end is read at most.
pub(crate) const TAIL_BYTES: usize = 1 << 20;
/// The rendered text, at most (JSON-escaped bytes).
pub(crate) const MAX_TEXT: usize = 256 << 10;
const TOOL_INPUT_CHARS: usize = 200;
const TOOL_OUTPUT_CHARS: usize = 400;
const MESSAGE_CHARS: usize = 8000;

/// A session id as harnesses write them: letters, digits and dashes only, so it can never name a path.
pub(crate) fn plain_session(id: &str) -> bool {
    !id.is_empty() && id.len() <= 128 && id.bytes().all(|b| b.is_ascii_alphanumeric() || b == b'-')
}

/// The conversation in `jsonl` (one JSON object a line) as plain text, oldest first.
pub(crate) fn render(kind: Kind, jsonl: &str) -> String {
    let mut out = String::new();
    for line in jsonl.lines() {
        let Ok(v) = serde_json::from_str::<serde_json::Value>(line) else { continue };
        let turn = match kind {
            Kind::Claude => claude_turn(&v),
            Kind::Codex => codex_turn(&v),
        };
        if let Some(turn) = turn.filter(|t| !t.trim().is_empty()) {
            if !out.is_empty() {
                out.push('\n');
            }
            out.push_str(turn.trim_end());
            out.push('\n');
        }
    }
    out
}

fn cut(text: &str, chars: usize) -> String {
    let text = text.trim();
    if text.chars().count() <= chars {
        return text.to_string();
    }
    let mut short: String = text.chars().take(chars).collect();
    short.push('…');
    short
}

/// `body` with every line after the first indented under `lead`.
fn lead(lead: &str, body: &str) -> String {
    let pad = " ".repeat(lead.chars().count());
    let mut out = String::new();
    for (i, line) in body.lines().enumerate() {
        out.push_str(if i == 0 { lead } else { &pad });
        out.push_str(line);
        out.push('\n');
    }
    out
}

fn text_of(content: &serde_json::Value) -> String {
    match content {
        serde_json::Value::String(s) => s.clone(),
        serde_json::Value::Array(parts) => parts
            .iter()
            .filter_map(|p| p.get("text").and_then(|t| t.as_str()))
            .collect::<Vec<_>>()
            .join("\n"),
        _ => String::new(),
    }
}

fn claude_turn(v: &serde_json::Value) -> Option<String> {
    let kind = v.get("type")?.as_str()?;
    if !matches!(kind, "user" | "assistant") || v.get("isMeta").and_then(|m| m.as_bool()) == Some(true) {
        return None;
    }
    let content = v.get("message")?.get("content")?;
    let mut out = String::new();
    match content {
        serde_json::Value::String(s) if kind == "user" => out.push_str(&lead("> ", &cut(s, MESSAGE_CHARS))),
        serde_json::Value::Array(parts) => {
            for part in parts {
                match part.get("type").and_then(|t| t.as_str()) {
                    Some("text") => {
                        let text = part.get("text").and_then(|t| t.as_str()).unwrap_or("");
                        let mark = if kind == "user" { "> " } else { "● " };
                        out.push_str(&lead(mark, &cut(text, MESSAGE_CHARS)));
                    }
                    Some("tool_use") => {
                        let name = part.get("name").and_then(|n| n.as_str()).unwrap_or("tool");
                        let input = part.get("input").map(|i| i.to_string()).unwrap_or_default();
                        out.push_str(&lead("● ", &format!("{name}({})", cut(&input, TOOL_INPUT_CHARS))));
                    }
                    Some("tool_result") => {
                        let text = part.get("content").map(text_of).unwrap_or_default();
                        out.push_str(&lead("  ⎿ ", &cut(&text, TOOL_OUTPUT_CHARS)));
                    }
                    _ => {}
                }
            }
        }
        _ => {}
    }
    Some(out)
}

fn codex_turn(v: &serde_json::Value) -> Option<String> {
    if v.get("type")?.as_str()? != "response_item" {
        return None;
    }
    let p = v.get("payload")?;
    match p.get("type")?.as_str()? {
        "message" => {
            let role = p.get("role")?.as_str()?;
            let text = p.get("content").map(text_of).unwrap_or_default();
            match role {
                "user" => Some(lead("› ", &cut(&text, MESSAGE_CHARS))),
                "assistant" => Some(lead("• ", &cut(&text, MESSAGE_CHARS))),
                _ => None,
            }
        }
        "function_call" | "custom_tool_call" | "local_shell_call" => {
            let name = p.get("name").and_then(|n| n.as_str()).unwrap_or("tool");
            let input = p
                .get("input")
                .or_else(|| p.get("arguments"))
                .or_else(|| p.get("action"))
                .map(|i| i.as_str().map_or_else(|| i.to_string(), str::to_string))
                .unwrap_or_default();
            Some(lead("• Ran ", &format!("{name} {}", cut(&input, TOOL_INPUT_CHARS))))
        }
        "function_call_output" | "custom_tool_call_output" => {
            let output = p.get("output")?;
            let text = match output {
                serde_json::Value::String(s) => s.clone(),
                other => text_of(other),
            };
            Some(lead("  └ ", &cut(&text, TOOL_OUTPUT_CHARS)))
        }
        _ => None,
    }
}

/// `text` cut to at most `max` bytes counted as JSON-escaped, oldest lines first.
pub(crate) fn bound(text: String, max: usize) -> (String, bool) {
    let escaped = |s: &str| serde_json::to_string(s).map_or(s.len(), |e| e.len());
    if escaped(&text) <= max {
        return (text, false);
    }
    let mut keep: Vec<&str> = Vec::new();
    let mut size = 2;
    for line in text.lines().rev() {
        let n = escaped(line) - 2 + 2;
        if size + n > max {
            break;
        }
        size += n;
        keep.push(line);
    }
    keep.reverse();
    let mut out = keep.join("\n");
    out.push('\n');
    (out, true)
}

#[cfg(test)]
#[allow(clippy::unwrap_used)]
mod tests {
    use super::*;

    #[test]
    fn a_session_id_never_names_a_path() {
        assert!(plain_session("01a1f1fe-268f-7ce3-9547-c422c66af57b"));
        for bad in ["", "../x", "a/b", "a.jsonl", "a b", &"x".repeat(200)] {
            assert!(!plain_session(bad), "{bad}");
        }
    }

    #[test]
    fn claudes_turns_read_as_the_screen_shows_them_with_tool_output_cut() {
        let big = "y".repeat(5000);
        let jsonl = [
            r#"{"type":"user","message":{"role":"user","content":"fix the build"}}"#.to_string(),
            r#"{"type":"assistant","message":{"role":"assistant","content":[{"type":"text","text":"Looking."},{"type":"tool_use","name":"Bash","input":{"command":"cargo build"}}]}}"#.to_string(),
            format!(r#"{{"type":"user","message":{{"role":"user","content":[{{"type":"tool_result","content":"{big}"}}]}}}}"#),
            r#"{"type":"system","subtype":"x"}"#.to_string(),
            r#"{"type":"user","isMeta":true,"message":{"role":"user","content":"<meta>"}}"#.to_string(),
            "not json".to_string(),
        ]
        .join("\n");
        let text = render(Kind::Claude, &jsonl);
        assert!(text.starts_with("> fix the build\n"), "{text}");
        assert!(text.contains("● Looking.\n● Bash({\"command\":\"cargo build\"})"));
        let out = text.lines().find(|l| l.starts_with("  ⎿ ")).unwrap();
        assert!(out.chars().count() <= TOOL_OUTPUT_CHARS + 6 && out.ends_with('…'));
        assert!(!text.contains("<meta>"));
    }

    #[test]
    fn codexs_turns_read_as_the_screen_shows_them() {
        let jsonl = [
            r#"{"type":"response_item","payload":{"type":"message","role":"user","content":[{"type":"input_text","text":"status?"}]}}"#,
            r#"{"type":"response_item","payload":{"type":"message","role":"developer","content":[{"type":"input_text","text":"secret instructions"}]}}"#,
            r#"{"type":"response_item","payload":{"type":"custom_tool_call","name":"exec","input":"ls"}}"#,
            r#"{"type":"response_item","payload":{"type":"custom_tool_call_output","output":[{"type":"input_text","text":"a\nb"}]}}"#,
            r#"{"type":"response_item","payload":{"type":"message","role":"assistant","content":[{"type":"output_text","text":"All green."}]}}"#,
            r#"{"type":"event_msg","payload":{"type":"token_count"}}"#,
        ]
        .join("\n");
        assert_eq!(render(Kind::Codex, &jsonl), "› status?\n\n• Ran exec ls\n\n  └ a\n    b\n\n• All green.\n");
    }

    #[test]
    fn the_bound_keeps_the_newest_lines() {
        let long: String = (0..2000).map(|i| format!("line {i}\n")).collect();
        let (kept, truncated) = bound(long, 200);
        assert!(truncated && kept.ends_with("line 1999\n") && serde_json::to_string(&kept).unwrap().len() <= 200);
        assert_eq!(bound("short\n".into(), 200), ("short\n".to_string(), false));
    }
}
