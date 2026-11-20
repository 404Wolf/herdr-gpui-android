//! The host's ghost text (unsent words drawn at the program's caret, see
//! `gpui_rio::ghost`), handed over as JSON and kept for the renderer.
//!
//! JSON: `{"kind":"claude"|"codex"|"other","reserve":N,"reserveFrom":ROW,
//! "colors":{"text":RGB,"dim":RGB,"warn":RGB,"background":RGB},
//! "draft":[{"t":"..","s":"text"|"partial"|"chip"|"selected"|"dim"}, {"t":"","s":"caret"}],
//! "echoes":[[{"t":"..","s":"echo"|"tag"|"warn"|"chip"|"bright"|"info"|"danger"}]],
//! "controls":[{"id":"talk","spans":[{"t":"talk","s":"dim"|"bright"|"danger"|"info"|"warn"}]}]}`.
//! Colours also take "bright", "danger" and "info".
// The JNI entry points that call in here exist only on Android.
#![cfg_attr(not(target_os = "android"), allow(dead_code, unused_imports))]
use crate::{Error, Result};
use gpui_rio::ghost::{Control, Ghost, GhostColors, GhostSpan, GhostStyle, PromptKind};
use serde::Deserialize;

/// The most text a ghost may carry; the composer's own bound is lower.
const MAX_TEXT_BYTES: usize = 65_536;
const MAX_SPANS: usize = 512;
const MAX_ECHOES: usize = 8;
/// ✕, undo, the queue, the status words and three slots (type, talk, send), or a selected
/// row's actions (up to four) with the queue, the status words and the slots (Wolf's ask 4).
const MAX_CONTROLS: usize = 10;
/// The host's ladder of lines, widest first.
const MAX_RUNGS: usize = 8;
/// A selected queue row's actions: send again, retry, edit, drop, back.
const MAX_ROW_ACTIONS: usize = 5;
const MAX_CONTROL_ID: usize = 16;
/// A queue row's id (a message's or a recording's UUID).
const MAX_ECHO_ID: usize = 48;
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
    /// The host's id for each echo, reported back with its cells as `echo-<id>`.
    #[serde(default, rename = "echoIds")]
    echo_ids: Vec<String>,
    #[serde(default)]
    controls: Vec<WireControl>,
    /// Every line the controls may take, widest first (see `Ghost::rungs`).
    #[serde(default)]
    rungs: Vec<Vec<WireControl>>,
    /// The queue row whose actions show, by its id in `echoIds` (see `Ghost::selected`).
    #[serde(default)]
    selected: Option<String>,
    /// That row's actions, widest first (see `Ghost::row_rungs`).
    #[serde(default, rename = "rowRungs")]
    row_rungs: Vec<Vec<WireControl>>,
}

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
struct WireControl {
    id: String,
    spans: Vec<WireSpan>,
}

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
struct WireColors {
    text: u32,
    dim: u32,
    warn: u32,
    background: u32,
    #[serde(default)]
    bright: u32,
    #[serde(default)]
    danger: u32,
    #[serde(default)]
    info: u32,
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
        "dim" => GhostStyle::Dim,
        "bright" => GhostStyle::Bright,
        "danger" => GhostStyle::Danger,
        "info" => GhostStyle::Info,
        "selected" => GhostStyle::Selected,
        "caret" => GhostStyle::Caret,
        _ => return Err(Error::InvalidCommand),
    };
    // Line breaks are drawn as spaces; other controls never reach the screen.
    let control = wire
        .t
        .chars()
        .any(|c| c.is_control() && c != '\n' && c != '\t');
    // A caret marks a place; it carries no text.
    let caret_text = style == GhostStyle::Caret && !wire.t.is_empty();
    if !allowed.contains(&style) || control || caret_text {
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
    let control_spans = wire
        .controls
        .iter()
        .chain(wire.rungs.iter().flatten())
        .chain(wire.row_rungs.iter().flatten())
        .map(|c| c.spans.len())
        .sum::<usize>();
    let spans = wire.draft.len() + wire.echoes.iter().map(Vec::len).sum::<usize>() + control_spans;
    let bytes = wire
        .draft
        .iter()
        .chain(wire.echoes.iter().flatten())
        .chain(wire.controls.iter().flat_map(|c| c.spans.iter()))
        .chain(wire.rungs.iter().flatten().flat_map(|c| c.spans.iter()))
        .chain(wire.row_rungs.iter().flatten().flat_map(|c| c.spans.iter()))
        .map(|s| s.t.len())
        .sum::<usize>();
    if wire.controls.len() > MAX_CONTROLS
        || wire.rungs.len() > MAX_RUNGS
        || wire.rungs.iter().any(|r| r.len() > MAX_CONTROLS)
        || wire.row_rungs.len() > MAX_RUNGS
        || wire.row_rungs.iter().any(|r| r.len() > MAX_ROW_ACTIONS)
        || wire
            .controls
            .iter()
            .chain(wire.rungs.iter().flatten())
            .chain(wire.row_rungs.iter().flatten())
            .any(|c| {
            c.id.is_empty()
                || c.id.len() > MAX_CONTROL_ID
                || !c
                    .id
                    .chars()
                    .all(|ch| ch.is_ascii_alphanumeric() || ch == '-')
        })
    {
        return Err(Error::InvalidCommand);
    }
    let bad_id = |id: &String| {
        id.is_empty()
            || id.len() > MAX_ECHO_ID
            || !id.chars().all(|ch| ch.is_ascii_alphanumeric() || ch == '-')
    };
    if !wire.echo_ids.is_empty()
        && (wire.echo_ids.len() != wire.echoes.len() || wire.echo_ids.iter().any(bad_id))
    {
        return Err(Error::InvalidCommand);
    }
    if wire.selected.as_ref().is_some_and(bad_id) {
        return Err(Error::InvalidCommand);
    }
    if spans > MAX_SPANS
        || wire.echoes.len() > MAX_ECHOES
        || bytes > MAX_TEXT_BYTES
        || wire.reserve > MAX_RESERVE
    {
        return Err(Error::InvalidText);
    }
    // "dim": the mark where a dictation was cut without a verified seam.
    let draft_styles = [
        GhostStyle::Text,
        GhostStyle::Partial,
        GhostStyle::Chip,
        GhostStyle::Selected,
        GhostStyle::Caret,
        GhostStyle::Dim,
    ];
    // A queue row: its words, a selected row bright, its state in the state's colour.
    let echo_styles = [
        GhostStyle::Echo,
        GhostStyle::Tag,
        GhostStyle::Warn,
        GhostStyle::Chip,
        GhostStyle::Bright,
        GhostStyle::Info,
        GhostStyle::Danger,
    ];
    let control_styles = [
        GhostStyle::Dim,
        GhostStyle::Bright,
        GhostStyle::Danger,
        GhostStyle::Info,
        GhostStyle::Warn,
    ];
    let controls_of = |line: Vec<WireControl>| -> Result<Vec<Control>> {
        line.into_iter()
            .map(|c| {
                Ok(Control {
                    id: c.id,
                    spans: c
                        .spans
                        .into_iter()
                        .map(|s| span(s, &control_styles))
                        .collect::<Result<_>>()?,
                })
            })
            .collect()
    };
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
        echo_ids: wire.echo_ids,
        controls: controls_of(wire.controls)?,
        rungs: wire
            .rungs
            .into_iter()
            .map(controls_of)
            .collect::<Result<_>>()?,
        selected: wire.selected,
        row_rungs: wire
            .row_rungs
            .into_iter()
            .map(controls_of)
            .collect::<Result<_>>()?,
        reserve: wire.reserve,
        reserve_from: wire.reserve_from,
        colors: GhostColors {
            text: wire.colors.text,
            dim: wire.colors.dim,
            warn: wire.colors.warn,
            background: wire.colors.background,
            bright: wire.colors.bright,
            danger: wire.colors.danger,
            info: wire.colors.info,
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
        "targetsTop": hint.targets_top,
        "blockFirst": hint.rows_geometry.first,
        "blockRows": hint.rows_geometry.rows,
        "rowGap": hint.rows_geometry.gap,
        "rowShift": hint.rows_geometry.shift,
        "draftShown": hint.draft_shown,
        "controls": hint
            .controls
            .iter()
            .map(|c| serde_json::json!({"id": c.id, "row": c.row, "start": c.start, "end": c.end}))
            .collect::<Vec<_>>(),
        "echoes": hint
            .echoes
            .iter()
            .map(|c| serde_json::json!({"id": c.id, "row": c.row, "start": c.start, "end": c.end}))
            .collect::<Vec<_>>(),
        "rowActions": hint
            .row_actions
            .iter()
            .map(|c| serde_json::json!({"id": c.id, "row": c.row, "start": c.start, "end": c.end}))
            .collect::<Vec<_>>(),
        "busy": hint.busy,
        "busyFrom": hint.busy_from,
        // [row, col, cells, first character in the draft, characters shown, 1 for a cluster]
        "draft": hint
            .draft_runs
            .iter()
            .map(|r| [r.row, r.col, r.len, r.source, r.chars, usize::from(r.cluster)])
            .collect::<Vec<_>>(),
        // [row, first cell, past the last]: every drawn draft cell, one per row.
        "draftSpans": hint.draft_spans,
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
    fn echo_ids_must_match_the_echoes_and_be_plain() {
        let echo = r#"[{"t":"❯ hi","s":"echo"}]"#;
        let with = |ids: &str, n: usize| {
            let echoes = vec![echo; n].join(",");
            parse(&format!(
                r#"{{"kind":"claude",{COLORS},"echoes":[{echoes}],"echoIds":{ids}}}"#
            ))
        };
        let ok = with(r#"["6f1c2a9e-0000-4000-8000-000000000001","link"]"#, 2).expect("two plain ids");
        assert_eq!(ok.echo_ids, vec!["6f1c2a9e-0000-4000-8000-000000000001", "link"]);
        assert!(with(r#"["a"]"#, 2).is_err(), "one id per echo");
        assert!(with(r#"["a b","c"]"#, 2).is_err(), "plain characters only");
        assert!(with(r#"["","c"]"#, 2).is_err());
        assert!(with(&format!(r#"["{}","c"]"#, "x".repeat(49)), 2).is_err());
        // None given: echoes are reported by index, as before.
        assert!(with("[]", 2).expect("no ids").echo_ids.is_empty());
    }

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
        let with_controls = format!(
            r#"{{"kind":"claude",{COLORS},"controls":[{{"id":"talk","spans":[{{"t":"● rec 0:06","s":"danger"}}]}},{{"id":"send","spans":[{{"t":"send","s":"bright"}},{{"t":"·2","s":"info"}}]}}]}}"#
        );
        let ghost = parse(&with_controls).expect("controls");
        assert_eq!(ghost.controls[1].spans[1].style, GhostStyle::Info);
        // A control's id is a short word; a draft style in a control is refused.
        assert!(
            parse(&format!(
                r#"{{"kind":"claude",{COLORS},"controls":[{{"id":"a b","spans":[]}}]}}"#
            ))
            .is_err()
        );
        assert!(parse(&format!(r#"{{"kind":"claude",{COLORS},"controls":[{{"id":"talk","spans":[{{"t":"x","s":"partial"}}]}}]}}"#)).is_err());
    }

    #[test]
    fn every_dictation_update_the_phone_sends_passes_the_real_bridge_limits() {
        // The Java composer writes these through its own GhostText (rust/src/testdata/dictation_ladders.jsonl);
        // each must parse here, with the bridge's real limit on alternatives (MAX_RUNGS).
        let lines = include_str!("testdata/dictation_ladders.jsonl");
        let mut failures = Vec::new();
        for (n, line) in lines.lines().filter(|l| !l.is_empty()).enumerate() {
            if let Err(error) = parse(line) {
                failures.push(format!("line {}: {error}", n + 1));
            }
        }
        assert!(failures.is_empty(), "{} rejected:\n{}", failures.len(), failures.join("\n"));
    }

    #[test]
    fn the_hosts_ladder_of_lines_is_kept_widest_first() {
        let json = format!(
            r#"{{"kind":"claude",{COLORS},"controls":[{{"id":"talk","spans":[{{"t":"talk","s":"dim"}}]}}],"rungs":[[{{"id":"clear","spans":[{{"t":"✕","s":"dim"}}]}},{{"id":"gap-type","spans":[{{"t":"────","s":"dim"}}]}},{{"id":"talk","spans":[{{"t":"talk","s":"dim"}}]}}],[{{"id":"talk","spans":[{{"t":"talk","s":"dim"}}]}}]]}}"#
        );
        let ghost = parse(&json).expect("rungs");
        assert_eq!(ghost.rungs.len(), 2);
        assert_eq!(ghost.rungs[0][1].id, "gap-type");
        // A rung is a line of controls: draft styles are refused there, as in controls.
        let bad = format!(
            r#"{{"kind":"claude",{COLORS},"rungs":[[{{"id":"talk","spans":[{{"t":"x","s":"partial"}}]}}]]}}"#
        );
        assert!(parse(&bad).is_err());
    }

    #[test]
    fn a_draft_may_carry_the_dim_mark_of_an_unverified_cut() {
        let json = format!(
            r#"{{"kind":"claude",{COLORS},"draft":[{{"t":"make the stop bu","s":"text"}},{{"t":"⁞","s":"dim"}},{{"t":"tton bigger","s":"partial"}}]}}"#
        );
        let ghost = parse(&json).expect("a cut mark in the draft");
        assert_eq!(ghost.draft[1].style, GhostStyle::Dim);
        assert_eq!(ghost.draft[1].text, "⁞");
    }

    #[test]
    fn a_draft_carries_the_keyboards_cursor_and_selection() {
        let json = format!(
            r#"{{"kind":"claude",{COLORS},"draft":[{{"t":"fix ","s":"text"}},{{"t":"","s":"caret"}},{{"t":"the","s":"selected"}}]}}"#
        );
        let ghost = parse(&json).expect("valid ghost");
        assert_eq!(ghost.draft[1].style, GhostStyle::Caret);
        assert_eq!(ghost.draft[2].style, GhostStyle::Selected);
        // A caret carries no text, and belongs only in the draft.
        assert!(
            parse(&format!(
                r#"{{"kind":"claude",{COLORS},"draft":[{{"t":"x","s":"caret"}}]}}"#
            ))
            .is_err()
        );
        assert!(
            parse(&format!(
                r#"{{"kind":"claude",{COLORS},"echoes":[[{{"t":"","s":"caret"}}]]}}"#
            ))
            .is_err()
        );
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
    fn up_to_ten_controls_and_coloured_queue_rows_are_accepted() {
        let control = |id: &str| format!(r#"{{"id":"{id}","spans":[{{"t":"{id}","s":"dim"}}]}}"#);
        let six: Vec<String> = ["type", "talk", "clear", "undo", "send", "queue"].iter().map(|id| control(id)).collect();
        let ghost = parse(&format!(r#"{{"kind":"claude",{COLORS},"controls":[{}]}}"#, six.join(",")))
            .expect("six controls");
        assert_eq!(ghost.controls.len(), 6);
        assert_eq!(ghost.controls[2].id, "clear");
        // Ten (the fixed slots with a selected row's actions, the queue and the status words),
        // never eleven (Wolf's ask 4).
        let ten: Vec<String> = [
            "row-again", "row-edit", "row-drop", "row-back", "queue", "status", "type", "talk", "send", "gap-send",
        ]
        .iter()
        .map(|id| control(id))
        .collect();
        let ghost = parse(&format!(r#"{{"kind":"claude",{COLORS},"controls":[{}]}}"#, ten.join(",")))
            .expect("ten controls");
        assert_eq!(ghost.controls.len(), 10);
        let eleven = format!("{},{}", ten.join(","), control("more"));
        assert!(parse(&format!(r#"{{"kind":"claude",{COLORS},"controls":[{eleven}]}}"#)).is_err());
        let rows = format!(
            r#"{{"kind":"claude",{COLORS},"echoes":[[{{"t":"❯ hi","s":"bright"}},{{"t":"  ● sending…","s":"info"}}],[{{"t":"❯ x","s":"echo"}},{{"t":"  ✗ not sent","s":"danger"}}]]}}"#
        );
        let ghost = parse(&rows).expect("rows");
        assert_eq!(ghost.echoes[0][0].style, GhostStyle::Bright);
        assert_eq!(ghost.echoes[1][1].style, GhostStyle::Danger);
        // A row is still never a draft's revisable tail.
        assert!(parse(&format!(r#"{{"kind":"claude",{COLORS},"echoes":[[{{"t":"x","s":"partial"}}]]}}"#)).is_err());
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
            busy_from: 38,
            controls: vec![gpui_rio::ghost::PlacedControl {
                id: "talk".into(),
                row: 39,
                start: 60,
                end: 64,
            }],
            echoes: vec![gpui_rio::ghost::PlacedControl {
                id: "echo-1".into(),
                row: 30,
                start: 0,
                end: 40,
            }],
            row_actions: vec![gpui_rio::ghost::PlacedControl {
                id: "row-drop".into(),
                row: 30,
                start: 50,
                end: 54,
            }],
            targets_top: 38,
            draft_shown: false,
            guard: 1,
            draft_runs: vec![gpui_rio::ghost::DraftRun {
                row: 37,
                col: 2,
                len: 9,
                source: 14,
                chars: 9,
                cluster: false,
            }],
            draft_spans: vec![[37, 2, 11], [38, 2, 30]],
            rows_geometry: gpui_rio::rows::RowGeometry::for_draft(15., Some((37, 38)), 38),
        };
        let value: serde_json::Value = serde_json::from_str(&hint_json(&hint)).expect("json");
        assert_eq!(value["echoes"][0]["id"], "echo-1");
        assert_eq!(value["echoes"][0]["row"], 30);
        assert_eq!(value["cellHeight"], 15.0);
        assert_eq!(value["busy"][1], 12);
        assert_eq!(value["anchored"], true);
        assert_eq!(value["controls"][0]["id"], "talk");
        assert_eq!(value["controls"][0]["start"], 60);
        assert_eq!(value["targetsTop"], 38);
        assert_eq!(value["blockFirst"], 37);
        assert_eq!(value["blockRows"], 2);
        assert_eq!(value["rowGap"], 4.0);
        assert_eq!(value["rowShift"], 15.0);
        assert_eq!(value["draft"][0], serde_json::json!([37, 2, 9, 14, 9, 0]));
        assert_eq!(value["draftSpans"], serde_json::json!([[37, 2, 11], [38, 2, 30]]));
        assert_eq!(value["rowActions"][0]["id"], "row-drop");
        assert_eq!(value["rowActions"][0]["row"], 30);
        assert_eq!(value["rowActions"][0]["start"], 50);
    }

    /// Review of #35 (r2): a selected row's actions travel apart from the controls' line, by
    /// the row's own id, so the line's slots never make room for them (Wolf's ask 4).
    #[test]
    fn wolf_ask4_a_selected_rows_actions_come_with_its_id() {
        let control = |id: &str, t: &str| format!(r#"{{"id":"{id}","spans":[{{"t":"{t}","s":"warn"}}]}}"#);
        let wide = [control("row-again", "send again"), control("row-edit", "edit"), control("row-drop", "drop"),
            control("row-back", "back")].join(",");
        let narrow = [control("row-again", "again"), control("row-edit", "edit"), control("row-drop", "drop")].join(",");
        let with = |selected: &str, rungs: &str| {
            parse(&format!(
                r#"{{"kind":"claude",{COLORS},"echoes":[[{{"t":"❯ x","s":"bright"}}]],"echoIds":["m-1"],"selected":{selected},"rowRungs":{rungs}}}"#
            ))
        };
        let ghost = with(r#""m-1""#, &format!("[[{wide}],[{narrow}]]")).expect("row actions");
        assert_eq!(ghost.selected.as_deref(), Some("m-1"));
        assert_eq!(ghost.row_rungs.len(), 2);
        assert_eq!(ghost.row_rungs[0][0].id, "row-again");
        assert_eq!(ghost.row_rungs[1][0].spans[0].text, "again");
        // A row id like any other: plain, short.
        assert!(with(r#""m 1""#, "[]").is_err());
        // At most five actions on a row.
        let six = [wide.clone(), control("row-x", "x"), control("row-y", "y")].join(",");
        assert!(with(r#""m-1""#, &format!("[[{six}]]")).is_err());
    }
}
