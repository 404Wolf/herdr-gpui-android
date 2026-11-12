//! The host's ghost text (unsent words drawn at the program's caret, see
//! `gpui_rio::ghost`), handed over as JSON and kept for the renderer.
//!
//! JSON: `{"kind":"claude"|"codex"|"other","reserve":N,"reserveFrom":ROW,
//! "colors":{"text":RGB,"dim":RGB,"warn":RGB,"background":RGB},
//! "draft":[{"t":"..","s":"text"|"partial"|"chip"}],
//! "echoes":[[{"t":"..","s":"echo"|"tag"|"warn"}]]}`.
// The JNI entry points that call in here exist only on Android.
#![cfg_attr(not(target_os = "android"), allow(dead_code, unused_imports))]
use crate::{Error, Result};
use gpui_rio::ghost::{Ghost, GhostColors, GhostSpan, GhostStyle, PromptKind};
use serde::Deserialize;

/// The most text a ghost may carry; the composer's own bound is lower.
const MAX_TEXT_BYTES: usize = 65_536;
const MAX_SPANS: usize = 64;
const MAX_ECHOES: usize = 8;
/// Columns a host may keep clear; past this the draft would have no room.
const MAX_RESERVE: usize = 64;

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
struct Wire {
    kind: String,
    #[serde(default)]
    reserve: usize,
    /// The drawn row from which `reserve` applies (none given: every row).
    #[serde(default, rename = "reserveFrom")]
    reserve_from: usize,
    colors: WireColors,
    #[serde(default)]
    draft: Vec<WireSpan>,
    #[serde(default)]
    echoes: Vec<Vec<WireSpan>>,
}

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
struct WireColors {
    text: u32,
    dim: u32,
    warn: u32,
    background: u32,
}

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
struct WireSpan {
    t: String,
    s: String,
}

fn span(wire: WireSpan, allowed: &[GhostStyle]) -> Result<GhostSpan> {
    let style = match wire.s.as_str() {
        "text" => GhostStyle::Text,
        "partial" => GhostStyle::Partial,
        "chip" => GhostStyle::Chip,
        "echo" => GhostStyle::Echo,
        "tag" => GhostStyle::Tag,
        "warn" => GhostStyle::Warn,
        _ => return Err(Error::InvalidCommand),
    };
    // Line breaks are drawn as spaces; other controls never reach the screen.
    let control = wire
        .t
        .chars()
        .any(|c| c.is_control() && c != '\n' && c != '\t');
    if !allowed.contains(&style) || control {
        return Err(Error::InvalidCommand);
    }
    Ok(GhostSpan {
        text: wire.t,
        style,
    })
}

/// A ghost from the host's JSON, bounded; unknown fields or styles are refused.
pub(crate) fn parse(json: &str) -> Result<Ghost> {
    if json.len() > MAX_TEXT_BYTES * 2 {
        return Err(Error::InvalidText);
    }
    let wire: Wire = serde_json::from_str(json)?;
    let kind = match wire.kind.as_str() {
        "claude" => PromptKind::Claude,
        "codex" => PromptKind::Codex,
        "other" => PromptKind::Other,
        _ => return Err(Error::InvalidCommand),
    };
    let spans = wire.draft.len() + wire.echoes.iter().map(Vec::len).sum::<usize>();
    let bytes = wire
        .draft
        .iter()
        .chain(wire.echoes.iter().flatten())
        .map(|s| s.t.len())
        .sum::<usize>();
    if spans > MAX_SPANS
        || wire.echoes.len() > MAX_ECHOES
        || bytes > MAX_TEXT_BYTES
        || wire.reserve > MAX_RESERVE
    {
        return Err(Error::InvalidText);
    }
    let draft_styles = [GhostStyle::Text, GhostStyle::Partial, GhostStyle::Chip];
    let echo_styles = [
        GhostStyle::Echo,
        GhostStyle::Tag,
        GhostStyle::Warn,
        GhostStyle::Chip,
    ];
    Ok(Ghost {
        kind,
        draft: wire
            .draft
            .into_iter()
            .map(|s| span(s, &draft_styles))
            .collect::<Result<_>>()?,
        echoes: wire
            .echoes
            .into_iter()
            .map(|echo| {
                echo.into_iter()
                    .map(|s| span(s, &echo_styles))
                    .collect::<Result<_>>()
            })
            .collect::<Result<_>>()?,
        reserve: wire.reserve,
        reserve_from: wire.reserve_from,
        colors: GhostColors {
            text: wire.colors.text,
            dim: wire.colors.dim,
            warn: wire.colors.warn,
            background: wire.colors.background,
        },
    })
}

/// The JSON a layout hint is handed to the host as (logical pixels).
pub(crate) fn hint_json(hint: &gpui_rio::ghost::LayoutHint) -> String {
    serde_json::json!({
        "left": hint.left,
        "top": hint.top,
        "cellWidth": hint.cell_width,
        "cellHeight": hint.cell_height,
        "cols": hint.cols,
        "rows": hint.rows,
        "anchored": hint.anchored,
        "guard": hint.guard,
        "busy": hint.busy,
    })
    .to_string()
}

#[cfg(feature = "android-ui")]
mod slot {
    use super::*;
    use std::sync::{Arc, Mutex};

    static GHOST: Mutex<Option<Arc<Ghost>>> = Mutex::new(None);

    /// Replaces the ghost (None clears it) and wakes the view.
    pub(crate) fn set(ghost: Option<Ghost>) {
        let next = ghost.map(Arc::new);
        match GHOST.lock() {
            Ok(mut slot) => *slot = next,
            Err(poisoned) => *poisoned.into_inner() = next,
        }
        crate::model::mailbox().touch();
    }

    pub(crate) fn current() -> Option<Arc<Ghost>> {
        match GHOST.lock() {
            Ok(slot) => slot.clone(),
            Err(poisoned) => poisoned.into_inner().clone(),
        }
    }
}
#[cfg(feature = "android-ui")]
pub(crate) use slot::{current, set};

#[cfg(test)]
#[allow(clippy::expect_used)]
mod tests {
    use super::*;

    const COLORS: &str =
        r#""colors":{"text":11720918,"dim":4868682,"warn":16763755,"background":0}"#;

    #[test]
    fn a_ghost_parses_with_its_draft_and_echoes() {
        let json = format!(
            r#"{{"kind":"claude","reserve":18,{COLORS},"draft":[{{"t":"also ","s":"text"}},{{"t":"check","s":"partial"}}],"echoes":[[{{"t":"❯ hi","s":"echo"}},{{"t":" queued","s":"tag"}}]]}}"#
        );
        let ghost = parse(&json).expect("valid ghost");
        assert_eq!(ghost.kind, PromptKind::Claude);
        assert_eq!(ghost.reserve, 18);
        assert_eq!(ghost.draft[1].style, GhostStyle::Partial);
        assert_eq!(ghost.echoes[0][1].text, " queued");
        assert_eq!(ghost.colors.warn, 0xFFCB6B);
    }

    #[test]
    fn malformed_or_oversized_ghosts_are_refused() {
        assert!(parse(&format!(r#"{{"kind":"vim",{COLORS}}}"#)).is_err());
        assert!(parse(&format!(r#"{{"kind":"claude",{COLORS},"extra":1}}"#)).is_err());
        assert!(
            parse(&format!(
                r#"{{"kind":"claude",{COLORS},"draft":[{{"t":"x","s":"bold"}}]}}"#
            ))
            .is_err()
        );
        // An echo style in the draft, or a draft style in an echo, is refused.
        assert!(
            parse(&format!(
                r#"{{"kind":"claude",{COLORS},"draft":[{{"t":"x","s":"echo"}}]}}"#
            ))
            .is_err()
        );
        assert!(
            parse(&format!(
                r#"{{"kind":"claude",{COLORS},"echoes":[[{{"t":"x","s":"partial"}}]]}}"#
            ))
            .is_err()
        );
        assert!(parse(&format!(r#"{{"kind":"claude","reserve":999,{COLORS}}}"#)).is_err());
        let big = "a".repeat(MAX_TEXT_BYTES + 1);
        assert!(
            parse(&format!(
                r#"{{"kind":"claude",{COLORS},"draft":[{{"t":"{big}","s":"text"}}]}}"#
            ))
            .is_err()
        );
        assert!(parse(&format!(r#"{{"kind":"other",{COLORS}}}"#)).is_ok());
    }

    #[test]
    fn a_hint_is_handed_over_as_flat_json() {
        let hint = gpui_rio::ghost::LayoutHint {
            left: 0.,
            top: 30.,
            cell_width: 6.5,
            cell_height: 15.,
            cols: 72,
            rows: 40,
            anchored: true,
            busy: vec![0, 12],
            guard: 1,
        };
        let value: serde_json::Value = serde_json::from_str(&hint_json(&hint)).expect("json");
        assert_eq!(value["cellHeight"], 15.0);
        assert_eq!(value["busy"][1], 12);
        assert_eq!(value["anchored"], true);
    }
}
