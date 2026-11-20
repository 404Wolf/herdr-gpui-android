//! Screen-only recognition and the row projection shared by paint and hit testing.

use rio_vt::{
    ansi::CursorShape,
    crosswords::{
        Crosswords, Mode,
        pos::{Column, Line},
    },
    event::EventListener,
};
use std::ops::Range;

use crate::ghost::{self, PromptKind};

/// Enough for a wrapped prompt and the few rows below it, independent of grid height.
const SCAN_ROWS: usize = 18;
const FOOTER_ROWS: usize = 8;
const MAX_WRAP_ROWS: usize = 4;

pub fn hidden_on_screen<U: EventListener>(
    grid: &Crosswords<U>,
    kind: PromptKind,
) -> Option<Range<usize>> {
    if kind == PromptKind::Other
        || grid.display_offset() != 0
        || grid.mode().contains(Mode::ALT_SCREEN)
    {
        return None;
    }
    let cursor = grid.cursor();
    if cursor.content == CursorShape::Hidden {
        return None;
    }
    let rows = grid.screen_lines();
    let cols = grid.columns();
    let start = rows.saturating_sub(SCAN_ROWS);
    let caret = (cursor.pos.row.0 as usize).checked_sub(start)?;
    // The viewport may be panned or shorter than the grid. Recognize the
    // footer from whole terminal rows before projecting into that window.
    let bottom: Vec<_> = (start..rows)
        .map(|row| {
            let cells = &grid.grid[Line(row as i32)];
            (0..cols)
                .map(|col| match cells[Column(col)].c() {
                    '\0' => ' ',
                    c => c,
                })
                .collect()
        })
        .collect();
    let wrapped = |row| grid.grid[Line((start + row) as i32)][Column(cols - 1)].wrapline();
    detect(&bottom, wrapped, Some((caret, cursor.pos.col.0)), kind)
        .map(|rows| rows.start + start..rows.end + start)
}

fn detect(
    screen: &[Vec<char>],
    wrapped: impl Fn(usize) -> bool,
    cursor: Option<(usize, usize)>,
    kind: PromptKind,
) -> Option<Range<usize>> {
    let anchor = ghost::locate(screen, cursor, kind)?;
    let start = match kind {
        PromptKind::Claude => {
            if !rule(screen.get(anchor.prompt_row.checked_sub(1)?)?)
                || !rule(screen.get(anchor.row + 1)?)
            {
                return None;
            }
            anchor.row + 2
        }
        PromptKind::Codex => {
            if !screen
                .get(anchor.row + 1)?
                .iter()
                .all(|c| c.is_whitespace())
            {
                return None;
            }
            anchor.row + 2
        }
        PromptKind::Other => return None,
    };
    if start < screen.len().saturating_sub(FOOTER_ROWS) {
        return None;
    }
    let (mut text, mut end) = logical_row(screen, &wrapped, start)?;
    let first_end = end;
    let shortcuts_at = |row| {
        let row: String = screen
            .get(row)
            .map(|cells: &Vec<char>| cells.iter().collect())?;
        codex_shortcuts(&row).map(str::is_empty)
    };
    loop {
        let matched = match kind {
            PromptKind::Claude => claude_status(text.trim()),
            PromptKind::Codex => {
                codex_status(text.trim(), end == first_end && shortcuts_at(end).is_some())
            }
            PromptKind::Other => false,
        };
        if matched {
            break;
        }
        // Absolute cursor positioning or a replay can lose the VT wrap bit.
        // Infer a continuation only at a filled right edge, and only when the
        // combined text matches the grammar (no free-form Codex title here).
        if end >= start + MAX_WRAP_ROWS || screen[end - 1].last().is_none_or(|c| c.is_whitespace())
        {
            return None;
        }
        let (continuation, next) = logical_row(screen, &wrapped, end)?;
        if next > start + MAX_WRAP_ROWS {
            return None;
        }
        text.push_str(&continuation);
        end = next;
    }
    match kind {
        PromptKind::Claude => {
            // Claude can also break before a whole count, rather than soft-wrap it.
            while end < (start + MAX_WRAP_ROWS).min(screen.len()) && !wrapped(end) {
                let row: String = screen[end].iter().collect();
                let Some(rest) = row.trim().strip_prefix('·') else {
                    break;
                };
                if !rest.split('·').all(count) {
                    break;
                }
                end += 1;
            }
        }
        PromptKind::Codex => {
            // The shortcut strip can also carry important warnings. Keep it
            // in full whenever it carries anything besides the static hints.
            if shortcuts_at(end) == Some(true) && !wrapped(end) {
                end += 1;
            }
        }
        PromptKind::Other => return None,
    }
    Some(start..end)
}

fn rule(row: &[char]) -> bool {
    let mut marks = row.iter().copied().filter(|c| !c.is_whitespace());
    marks.by_ref().take(6).filter(|&c| c == '─').count() == 6 && marks.all(|c| c == '─')
}

fn logical_row(
    screen: &[Vec<char>],
    wrapped: &impl Fn(usize) -> bool,
    start: usize,
) -> Option<(String, usize)> {
    let mut text = String::new();
    for (row, cells) in screen.iter().enumerate().skip(start).take(MAX_WRAP_ROWS) {
        text.extend(cells.iter());
        if !wrapped(row) {
            return Some((text, row + 1));
        }
    }
    None
}

fn claude_status(text: &str) -> bool {
    let mut sections = text.split('·');
    let Some(first) = sections.next() else {
        return false;
    };
    let first = first.trim();
    let mode = first
        .strip_prefix("-- INSERT --")
        .or_else(|| first.strip_prefix("-- NORMAL --"));
    let head = mode.unwrap_or(first).trim();
    let permission = head.strip_suffix(" (shift+tab to cycle)").unwrap_or(head);
    ((mode.is_some() && head.is_empty())
        || matches!(
            permission,
            "⏵⏵ bypass permissions on"
                | "⏵⏵ accept edits on"
                | "⏸ plan mode on"
                | "⏵⏵ auto mode on"
                | "? for shortcuts"
        ))
        && sections.all(count)
}

fn count(text: &str) -> bool {
    let text = text.trim();
    let text = text.strip_prefix("← ").unwrap_or(text);
    text.split_once(' ').is_some_and(|(n, noun)| {
        n.parse::<u32>().is_ok()
            && matches!(
                noun,
                "shell" | "shells" | "agent" | "agents" | "feedback draft" | "feedback drafts"
            )
    })
}

fn codex_shortcuts(text: &str) -> Option<&str> {
    let rest = text
        .trim()
        .strip_prefix("← for agents · ? for shortcuts")?
        .trim_start();
    (rest.is_empty() || rest.starts_with("⚠ ")).then_some(rest)
}

fn codex_status(text: &str, has_shortcuts: bool) -> bool {
    let Some((model, details)) = text.split_once('·') else {
        return false;
    };
    let mut words = model.split_whitespace();
    let Some(name) = words.next().map(str::to_ascii_lowercase) else {
        return false;
    };
    let Some(version) = name.strip_prefix("gpt-") else {
        return false;
    };
    if !version.starts_with(|c: char| c.is_ascii_digit())
        || !version
            .bytes()
            .all(|b| b.is_ascii_alphanumeric() || matches!(b, b'.' | b'-'))
        || words.next().is_some_and(|effort| {
            !matches!(
                effort,
                "none" | "minimal" | "low" | "medium" | "high" | "xhigh" | "max"
            )
        })
        || words.next().is_some()
    {
        return false;
    }
    let (detail, tail) = details.trim().split_once('·').unwrap_or((details, ""));
    let detail = detail.trim();
    let tail = tail.trim();
    // Stock Codex includes a context percentage. Builds that use a model,
    // directory and thread title also show the dedicated shortcut strip.
    (context_left(detail) && (tail.is_empty() || directory(tail)))
        || (directory(detail) && context_left(tail))
        || (has_shortcuts && directory(detail))
}

fn context_left(text: &str) -> bool {
    let Some((value, suffix)) = text.split_once('%') else {
        return false;
    };
    value.parse::<u8>().is_ok_and(|n| n <= 100) && matches!(suffix, " left" | " context left")
}

fn directory(text: &str) -> bool {
    text == "~" || text.starts_with("~/") || text.starts_with('/')
}

/// A projection of one contiguous footer out of the grid. Every other row has
/// one visual index and the same terminal row for selection, links and input.
#[derive(Clone, Debug, Default)]
pub struct Rows {
    len: usize,
    hidden: Range<usize>,
    deferred: bool,
}

impl Rows {
    pub fn new(rows: usize, hidden: Option<Range<usize>>) -> Self {
        let mut projection = Self::default();
        projection.update(rows, false, hidden);
        projection
    }

    pub fn count(&self) -> usize {
        self.len
    }

    /// Returns whether previously hidden rows were revealed.
    pub fn update(&mut self, rows: usize, touching: bool, footer: Option<Range<usize>>) -> bool {
        let footer = footer.unwrap_or_default();
        let hidden = if touching {
            // Keep only rows that are still confirmed as footer. Any newly
            // recognized rows can collapse after the gesture ends.
            let start = self.hidden.start.max(footer.start);
            let end = self.hidden.end.min(footer.end);
            if start < end { start..end } else { 0..0 }
        } else {
            footer.clone()
        };
        let revealed =
            self.collapsed() && (hidden.start > self.hidden.start || hidden.end < self.hidden.end);
        self.deferred = touching && hidden != footer;
        self.hidden = hidden;
        self.len = rows - self.hidden.len();
        revealed
    }

    pub fn deferred(&self) -> bool {
        self.deferred
    }

    pub fn collapsed(&self) -> bool {
        !self.hidden.is_empty()
    }

    pub fn source(&self, row: usize) -> Option<usize> {
        (row < self.len).then(|| {
            if row < self.hidden.start {
                row
            } else {
                row + self.hidden.len()
            }
        })
    }

    /// The source cell drawn at the point, before the display offset.
    pub fn cell_at(
        &self,
        point: (f32, f32),
        cell: (f32, f32),
        origin: (i32, usize),
        (columns, rows): (usize, usize),
    ) -> Option<(usize, usize)> {
        let (col, row) =
            crate::tap::cell_at(point, cell, origin, (columns, rows.min(self.count())))?;
        self.source(row)
            .filter(|&row| row < rows)
            .map(|row| (col, row))
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::ghost;

    fn screen(lines: &[&str]) -> Vec<Vec<char>> {
        lines.iter().map(|line| line.chars().collect()).collect()
    }

    const RULE: &str = "────────────────────────────────────────";
    // Footer text from live screens, with directories and thread titles anonymized.
    const CLAUDE: &str =
        "  -- INSERT -- ⏵⏵ bypass permissions on (shift+tab to cycle) · ← 3 agents";
    const CLAUDE_SHELL: &str = "  -- INSERT -- ⏵⏵ bypass permissions on · 1 shell · ← 3 agents";
    const CODEX: &str = "  GPT-6.2-Sol high · ~/demo · Demo task…                  Vim: Insert";
    const CODEX_WARNINGS: &str =
        "  ← for agents · ? for shortcuts              ⚠ 2 warnings · f2 to view";

    #[test]
    fn detects_the_claude_mode_and_counts_under_the_prompt_frame() {
        for status in [
            CLAUDE,
            CLAUDE_SHELL,
            "  -- NORMAL -- ⏵⏵ accept edits on (shift+tab to cycle) · 2 shells · 3 agents",
            "  -- NORMAL --",
            "  ⏵⏵ accept edits on (shift+tab to cycle)",
        ] {
            let rows = screen(&["Output", RULE, "❯\u{a0}", RULE, status]);
            assert_eq!(
                detect(&rows, |_| false, Some((2, 2)), PromptKind::Claude),
                Some(4..5)
            );
        }
    }

    #[test]
    fn detects_only_the_safe_parts_of_codexs_footer() {
        let rows = screen(&[
            "Output",
            "› Ask Codex to do anything",
            "",
            CODEX,
            CODEX_WARNINGS,
        ]);
        assert_eq!(
            detect(&rows, |_| false, Some((1, 2)), PromptKind::Codex),
            Some(3..4)
        );

        let rows = screen(&[
            "Output",
            "› Ask Codex to do anything",
            "",
            CODEX,
            "  ← for agents · ? for shortcuts",
        ]);
        assert_eq!(
            detect(&rows, |_| false, Some((1, 2)), PromptKind::Codex),
            Some(3..5)
        );

        let rows = screen(&[
            "Output",
            "› Ask Codex to do anything",
            "",
            "  gpt-5.2 medium · 84% context left",
        ]);
        assert_eq!(
            detect(&rows, |_| false, Some((1, 2)), PromptKind::Codex),
            Some(3..4)
        );
    }

    #[test]
    fn detects_narrow_and_wrapped_footers_without_losing_following_text() {
        let rows = screen(&[
            RULE,
            "❯ ",
            RULE,
            "  -- INSERT -- ⏵⏵ bypass permissions on",
            " (shift+tab to cycle) · 1 shell · 3 agents",
            "Review required",
        ]);
        assert_eq!(
            detect(&rows, |row| row == 3, Some((1, 2)), PromptKind::Claude),
            Some(3..5)
        );

        let rows = screen(&[RULE, "❯ ", RULE, CLAUDE_SHELL, "    · 1 feedback draft"]);
        assert_eq!(
            detect(&rows, |_| false, Some((1, 2)), PromptKind::Claude),
            Some(3..5)
        );

        let rows = screen(&["› ", "", "  gpt-5.2 high · 84% context ", "left"]);
        assert_eq!(
            detect(&rows, |row| row == 2, Some((0, 2)), PromptKind::Codex),
            Some(2..4)
        );
    }

    #[test]
    fn detects_a_repainted_wrap_at_the_right_edge() {
        // A core repaint emits CRLF between physical screen rows, which loses
        // the original VT wrap bit. This is the captured mode line at 49 cells.
        let rows = screen(&[
            RULE,
            "❯ ",
            RULE,
            "  -- INSERT -- ⏵⏵ bypass permissions on (shift+tab",
            " to cycle) · 1 shell · 3 agents",
        ]);
        assert_eq!(
            detect(&rows, |_| false, Some((1, 2)), PromptKind::Claude),
            Some(3..5)
        );
        let mut away_from_edge = rows.clone();
        away_from_edge[3].extend([' '; 3]);
        assert_eq!(
            detect(&away_from_edge, |_| false, Some((1, 2)), PromptKind::Claude),
            None
        );
    }

    #[test]
    fn keeps_ambiguous_rows_dialogs_and_non_agent_panes() {
        let framed = screen(&[RULE, "❯ ", RULE, CLAUDE]);
        assert_eq!(
            detect(&framed, |_| false, Some((1, 2)), PromptKind::Other),
            None
        );
        assert_eq!(detect(&framed, |_| false, None, PromptKind::Claude), None);
        let unframed = screen(&["❯ ", CLAUDE, ""]);
        assert_eq!(
            detect(&unframed, |_| false, Some((0, 2)), PromptKind::Claude),
            None
        );

        for text in [
            "-- NORMAL -- output from Vim",
            "⏵⏵ bypass permissions on: approve this?",
            "⏵⏵ accept edits on (shift+tab to cycle) · 3 agents failed",
            "Try shift+tab to cycle · 1 shell",
        ] {
            let rows = screen(&[RULE, "❯ ", RULE, text, ""]);
            assert_eq!(
                detect(&rows, |_| false, Some((1, 2)), PromptKind::Claude),
                None
            );
        }
        for text in [
            "gpt-5.2 medium · 189% context left",
            "gpt-5.2 medium · 84% context left (approx.)",
            "GPT-6.2-Sol high · ~/demo · Docs snippet",
            "gpt-5.2 high · 84% context left · https://example.com",
        ] {
            let rows = screen(&["› ", "", text, ""]);
            assert_eq!(
                detect(&rows, |_| false, Some((0, 2)), PromptKind::Codex),
                None
            );
        }
        let rows = screen(&["› look at this", "  gpt-5.2 medium · 84% context left"]);
        assert_eq!(
            detect(&rows, |_| false, Some((1, 2)), PromptKind::Codex),
            None
        );
    }

    #[test]
    fn collapse_and_touch_use_the_same_rows_when_clipped() {
        let rows = screen(&[
            "earlier output",
            "output",
            "› Ask Codex",
            "",
            CODEX,
            CODEX_WARNINGS,
        ]);
        let hidden = detect(&rows, |_| false, Some((2, 2)), PromptKind::Codex);
        let projected = Rows::new(rows.len(), hidden);
        let projected_rows: Vec<_> = (0..projected.count())
            .filter_map(|row| projected.source(row))
            .collect();
        assert_eq!(projected_rows, [0, 1, 2, 3, 5]);
        let origin =
            crate::view_origin((30, projected.count() as u16), (30, 4), (2, 2), false, None);
        assert_eq!(origin, (1, 0));
        let drawn: Vec<_> = projected_rows[1..]
            .iter()
            .map(|&row| rows[row].clone())
            .collect();
        let anchor = ghost::locate(&drawn, Some((1, 2)), PromptKind::Codex);
        assert_eq!(anchor.map(|anchor| anchor.row), Some(1));

        let touch = |y| {
            projected
                .cell_at((18., y), (9., 20.), origin, (30, rows.len()))
                .map(|(_, row)| row)
        };
        assert_eq!(touch(21.), Some(2));
        assert_eq!(touch(61.), Some(5));
        assert_eq!(touch(81.), None);
        let scrolled = Rows::new(
            rows.len(),
            detect(&rows, |_| false, None, PromptKind::Codex),
        );
        assert_eq!(scrolled.source(4), Some(4));
        assert_eq!(scrolled.source(5), Some(5));
    }

    fn codex_screen() -> Crosswords<rio_vt::event::VoidListener> {
        use rio_vt::{
            crosswords::CrosswordsSize,
            event::{VoidListener, WindowId},
            performer::handler::Processor,
        };

        let mut grid = Crosswords::new(
            CrosswordsSize::new(96, 12),
            CursorShape::Block,
            VoidListener,
            WindowId::from(0),
            0,
            0,
        );
        Processor::default().advance(
            &mut grid,
            concat!(
                "\x1b[8;1H› \r\n\r\n",
                "  gpt-5.2 medium · 84% context left\r\n",
                "https://example.com/after\r\n",
                "https://example.com/other\x1b[8;3H",
            )
            .as_bytes(),
        );
        grid
    }

    #[test]
    fn foreground_confirmation_keeps_the_original_link_through_lift() {
        use crate::links::{Link, grid_link_at};
        use rio_vt::crosswords::pos::Pos;

        let grid = codex_screen();
        let target = |rows: &Rows| {
            let (col, source) = rows.cell_at((20., 201.), (10., 20.), (0, 0), (96, 12))?;
            grid_link_at(&grid, Pos::new(Line(source as i32), Column(col)))
                .map(|(_, link)| (source, link))
        };
        let mut rows = Rows::new(12, hidden_on_screen(&grid, PromptKind::Other));
        let down = target(&rows);
        assert_eq!(
            down,
            Some((10, Link::Web("https://example.com/after".into())))
        );

        // The only change between finger-down and GPUI's synthetic click is
        // that the foreground has now been confirmed.
        let confirmed = hidden_on_screen(&grid, PromptKind::Codex);
        assert_eq!(confirmed, Some(9..10));
        assert!(!rows.update(12, true, confirmed.clone()));
        assert_eq!(target(&rows), down);
        assert!(rows.deferred());

        assert!(!rows.update(12, false, confirmed));
        let collapsed = target(&rows);
        assert_eq!(
            collapsed,
            Some((11, Link::Web("https://example.com/other".into())))
        );
        assert!(!rows.deferred());

        // An eligibility or settings change must reveal the row immediately.
        assert!(rows.update(12, true, None));
        assert_eq!(target(&rows), down);
        assert!(!rows.deferred());
    }

    #[test]
    fn a_footer_overwritten_during_a_touch_is_revealed_immediately() {
        use rio_vt::performer::handler::Processor;

        let mut grid = codex_screen();
        let hidden = hidden_on_screen(&grid, PromptKind::Codex);
        assert_eq!(hidden, Some(9..10));
        let mut rows = Rows::new(12, hidden);
        assert_eq!(rows.source(9), Some(10));
        let at = |rows: &Rows, y| rows.cell_at((20., y), (10., 20.), (0, 0), (96, 12));
        let above = at(&rows, 161.);
        let below = at(&rows, 181.);
        assert_eq!(above, Some((2, 8)));
        assert_eq!(below, Some((2, 10)));

        Processor::default().advance(
            &mut grid,
            b"\x1b[10;1H\x1b[2KERROR: permission denied\x1b[8;3H",
        );
        let hidden = hidden_on_screen(&grid, PromptKind::Codex);
        assert_eq!(hidden, None);
        assert!(rows.update(12, true, hidden));

        assert_eq!(rows.source(9), Some(9), "replacement output must be drawn");
        assert_eq!(rows.count(), 12);
        assert_eq!(at(&rows, 161.), above);
        // The original link is still present at row 10, but a tap here was
        // remapped to the error row and must be cancelled.
        assert_eq!(at(&rows, 181.), Some((2, 9)));
        assert_ne!(at(&rows, 181.), below);
    }

    #[test]
    fn a_footer_revealed_in_scrollback_stays_visible_until_lift() {
        let mut rows = Rows::new(12, Some(9..10));
        assert!(rows.update(12, true, None));
        assert_eq!(rows.count(), 12);
        assert_eq!(rows.source(9), Some(9));

        // A drag back to the live screen sees the footer again. Its collapse
        // waits for the same gesture to end.
        assert!(!rows.update(12, true, Some(9..10)));
        assert_eq!(rows.count(), 12);
        assert!(rows.deferred());
        assert!(!rows.update(12, false, Some(9..10)));
        assert_eq!(rows.source(9), Some(10));
    }

    #[test]
    fn only_the_overlap_of_two_footer_ranges_stays_collapsed_during_touch() {
        let mut rows = Rows::new(12, Some(8..10));
        assert!(rows.update(12, true, Some(9..11)));
        assert!(rows.deferred());
        assert_eq!(rows.source(8), Some(8));
        assert_eq!(rows.source(9), Some(10));

        assert!(!rows.update(12, false, Some(9..11)));
        assert!(!rows.deferred());
        assert_eq!(rows.source(9), Some(11));
    }
}
