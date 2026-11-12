//! Ghost text: words the user has dictated or typed on the phone but not sent,
//! drawn at the program's own input caret so they read as part of its prompt.
//! Nothing here reaches the program; the host sends the words as one paste
//! when the user presses Enter.
//!
//! Where to draw is decided from the screen alone: Claude Code and Codex both
//! park the visible terminal cursor at their input caret, on a row that starts
//! with their prompt glyph (`❯ ` / `› `), and indent wrapped input by two
//! columns. When that shape is not on screen (a dialog, a hidden cursor, the
//! view scrolled into history, another program), the words go to a strip on
//! the bottom rows instead, never somewhere that merely looks plausible.
//!
//! Also here: which cells near the bottom hold something, so the host can float
//! its controls where they cover nothing that matters.

/// Whose prompt the screen shows, as the host knows from the pane's agent.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum PromptKind {
    Claude,
    Codex,
    Other,
}

impl PromptKind {
    fn glyphs(self) -> &'static [char] {
        match self {
            // Older Claude Code versions drew `>`.
            Self::Claude => &['❯', '>'],
            Self::Codex => &['›'],
            Self::Other => &[],
        }
    }
}

/// How one run of ghost text is drawn.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum GhostStyle {
    /// Settled words.
    Text,
    /// The tail an engine may still revise: underlined.
    Partial,
    /// A recording waiting for the desktop: warning colour, outlined.
    Chip,
    /// A queued message's text, dimmer than a draft.
    Echo,
    /// What is happening to a queued message.
    Tag,
    /// A message that may have been sent: warning colour.
    Warn,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct GhostSpan {
    pub text: String,
    pub style: GhostStyle,
}

/// Colours as 0xRRGGBB, from the host's chrome so they follow its theme.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct GhostColors {
    pub text: u32,
    pub dim: u32,
    pub warn: u32,
    pub background: u32,
}

/// What to draw: the draft at the caret and queued messages above the prompt.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Ghost {
    pub kind: PromptKind,
    pub draft: Vec<GhostSpan>,
    /// Queued messages, oldest first, one line each.
    pub echoes: Vec<Vec<GhostSpan>>,
    /// Columns kept clear on the right, where the host floats its controls,
    /// on drawn rows from `reserve_from` down.
    pub reserve: usize,
    pub reserve_from: usize,
    pub colors: GhostColors,
}

/// Where the drawn screen is busy, for a host that floats controls over it.
/// Positions are in the window's logical pixels.
#[derive(Clone, Debug, PartialEq)]
pub struct LayoutHint {
    pub left: f32,
    pub top: f32,
    pub cell_width: f32,
    pub cell_height: f32,
    pub cols: usize,
    pub rows: usize,
    /// Whether the program's prompt was found (the draft is at its caret).
    pub anchored: bool,
    /// The bottom rows' [`busy_columns`], from row `busy_from` down: only
    /// those can hold the host's controls, and output above them then never
    /// changes the hint.
    pub busy: Vec<usize>,
    pub busy_from: usize,
    /// The first row of the bottom block that must stay in view, see [`guard_row`].
    pub guard: usize,
}

/// The program's input caret, in the rows as drawn.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct Anchor {
    /// The row holding the prompt glyph.
    pub prompt_row: usize,
    /// The caret: where the next typed character would go.
    pub row: usize,
    pub col: usize,
    /// Where wrapped input continues.
    pub indent: usize,
}

/// How far above the caret wrapped input may reach.
const MAX_INPUT_ROWS: usize = 8;

/// The program's input caret, when the screen has its prompt's shape there.
/// `cursor` is the visible cursor in drawn rows, or None when it is hidden
/// or the view is scrolled into history.
pub fn locate(
    rows: &[Vec<char>],
    cursor: Option<(usize, usize)>,
    kind: PromptKind,
) -> Option<Anchor> {
    let (row, col) = cursor?;
    let glyphs = kind.glyphs();
    if glyphs.is_empty() || row >= rows.len() {
        return None;
    }
    for prompt_row in (row.saturating_sub(MAX_INPUT_ROWS)..=row).rev() {
        let cells = &rows[prompt_row];
        let at = |i: usize| cells.get(i).copied().unwrap_or(' ');
        if glyphs.contains(&at(0)) && at(1) == ' ' {
            // The caret sits after the glyph, never on it.
            return (prompt_row < row || col >= 2).then_some(Anchor {
                prompt_row,
                row,
                col,
                indent: 2,
            });
        }
        // Only wrapped input, indented under the prompt, lies between.
        if !(at(0) == ' ' && at(1) == ' ') {
            return None;
        }
    }
    None
}

/// One run of ghost text placed on the grid.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Placed {
    pub row: usize,
    pub col: usize,
    pub text: String,
    pub style: GhostStyle,
}

/// How many bottom rows a layout hint reports.
pub const HINT_ROWS: usize = 20;

/// The most rows the draft may take; the rest is elided at the start.
pub const MAX_DRAFT_ROWS: usize = 6;
/// The most rows the strip takes when no prompt is on screen.
pub const MAX_STRIP_ROWS: usize = 3;

/// Where every ghost run goes on a `rows`×`cols` view: the draft from the
/// caret (or a strip on the bottom rows) and the queued messages above it.
pub fn place(ghost: &Ghost, anchor: Option<Anchor>, rows: usize, cols: usize) -> Vec<Placed> {
    if rows == 0 || cols == 0 {
        return Vec::new();
    }
    // A row under the host's controls is narrower; never narrower than 8 cells.
    let width_of = |row: usize| {
        if row >= ghost.reserve_from {
            cols.saturating_sub(ghost.reserve).max(8.min(cols))
        } else {
            cols
        }
    };
    let mut out = Vec::new();
    // Where the queued messages end: just above the prompt's frame, or the strip.
    let echoes_bottom = match anchor {
        Some(anchor) => {
            let mut lines = wrap(&ghost.draft, anchor.col, anchor.indent, &|line| {
                width_of(anchor.row + line)
            });
            // Rows past the view's bottom are dropped from the front: the
            // caret end of a long draft is the part being spoken.
            let room = (rows - anchor.row).min(MAX_DRAFT_ROWS);
            let keep = lines.len().saturating_sub(room);
            if keep > 0 {
                lines.drain(..keep);
                // The first kept line starts where wrapped input does.
                if let Some(first) = lines.first_mut()
                    && let Some(run) = first.first_mut()
                {
                    run.text.insert(0, '…');
                }
            }
            for (i, line) in lines.into_iter().enumerate() {
                if line.is_empty() {
                    continue;
                }
                let mut col = if i == 0 && keep == 0 {
                    anchor.col
                } else {
                    anchor.indent
                };
                for span in line {
                    let len = span.text.chars().count();
                    out.push(Placed {
                        row: anchor.row + i,
                        col,
                        text: span.text,
                        style: span.style,
                    });
                    col += len;
                }
            }
            // Claude frames its prompt with a rule above; Codex leaves a blank row.
            anchor.prompt_row.saturating_sub(1)
        }
        None => {
            let has_draft = ghost.draft.iter().any(|s| !s.text.is_empty());
            // The strip is on the bottom rows, under the controls if anywhere.
            let lines = if has_draft {
                let mut lines = wrap(&ghost.draft, 1, 1, &|_| width_of(rows - 1));
                lines.retain(|line| !line.is_empty());
                lines
            } else {
                Vec::new()
            };
            let lines = &lines[lines.len().saturating_sub(MAX_STRIP_ROWS)..];
            let top = rows.saturating_sub(lines.len());
            for (i, line) in lines.iter().enumerate() {
                let mut col = 1;
                for span in line {
                    let len = span.text.chars().count();
                    out.push(Placed {
                        row: top + i,
                        col,
                        text: span.text.clone(),
                        style: span.style,
                    });
                    col += len;
                }
            }
            top
        }
    };
    let shown = ghost.echoes.len().min(echoes_bottom);
    for (i, echo) in ghost.echoes[ghost.echoes.len() - shown..]
        .iter()
        .enumerate()
    {
        let row = echoes_bottom - shown + i;
        let mut col = 0;
        for span in echo {
            let room = width_of(row).saturating_sub(col);
            if room == 0 {
                break;
            }
            let text = clip(&span.text, room);
            let len = text.chars().count();
            out.push(Placed {
                row,
                col,
                text,
                style: span.style,
            });
            col += len;
        }
    }
    out
}

/// `text` cut to `room` cells, marked with an ellipsis when cut.
fn clip(text: &str, room: usize) -> String {
    let count = text.chars().count();
    if count <= room {
        return text.to_owned();
    }
    let mut out: String = text.chars().take(room.saturating_sub(1)).collect();
    out.push('…');
    out
}

/// Wraps styled runs into lines, line `i` being `width_of(i)` cells wide: the
/// first from `first_col`, the rest from `indent`. Words move whole when they
/// fit on a fresh line. Line 0 stays (maybe empty) so indices match rows.
fn wrap(
    spans: &[GhostSpan],
    first_col: usize,
    indent: usize,
    width_of: &dyn Fn(usize) -> usize,
) -> Vec<Vec<GhostSpan>> {
    let mut lines: Vec<Vec<GhostSpan>> = vec![Vec::new()];
    let mut col = first_col;
    let mut width = width_of(0);
    if col >= width {
        lines.push(Vec::new());
        col = indent;
        width = width_of(1);
    }
    for span in spans {
        // A chip is one unit: it never splits across lines.
        let pieces = if span.style == GhostStyle::Chip {
            vec![span.text.clone()]
        } else {
            split_words(&span.text)
        };
        for piece in pieces {
            let len = piece.chars().count();
            if piece.trim().is_empty() {
                // Spaces never start a continuation line nor push one.
                if col > indent && col + len <= width {
                    push(lines.last_mut(), &piece, span.style);
                    col += len;
                }
                continue;
            }
            // A word moves whole to a fresh line when it fits there.
            let next_room = width_of(lines.len()).saturating_sub(indent).max(1);
            if col + len > width && col > indent && len <= next_room {
                lines.push(Vec::new());
                col = indent;
                width = width_of(lines.len() - 1);
            }
            // A word longer than a line breaks where the line ends.
            let mut rest: Vec<char> = piece.chars().collect();
            while !rest.is_empty() {
                let room = width.saturating_sub(col);
                if room == 0 {
                    lines.push(Vec::new());
                    col = indent;
                    width = width_of(lines.len() - 1);
                    continue;
                }
                let take = room.min(rest.len());
                let head: String = rest.drain(..take).collect();
                push(lines.last_mut(), &head, span.style);
                col += take;
            }
        }
    }
    // Trailing empty lines carry nothing; a leading one keeps row numbering.
    while lines.len() > 1 && lines.last().is_some_and(Vec::is_empty) {
        lines.pop();
    }
    lines
}

/// Words and the runs of spaces between them, as separate pieces, so a space
/// past the line's end never pushes a word that fits to the next line.
fn split_words(text: &str) -> Vec<String> {
    let flat = text.replace(['\n', '\r', '\t'], " ");
    let mut out: Vec<String> = Vec::new();
    for c in flat.chars() {
        match out.last_mut() {
            Some(last) if (last.ends_with(' ')) == (c == ' ') => last.push(c),
            _ => out.push(c.to_string()),
        }
    }
    out
}

fn push(line: Option<&mut Vec<GhostSpan>>, text: &str, style: GhostStyle) {
    let Some(line) = line else { return };
    if text.is_empty() {
        return;
    }
    match line.last_mut() {
        Some(last) if last.style == style => last.text.push_str(text),
        _ => line.push(GhostSpan {
            text: text.to_owned(),
            style,
        }),
    }
}

/// Characters that only frame things: rules and box edges. A row of these
/// may be covered by the host's floating controls.
fn framing(c: char) -> bool {
    c == ' ' || ('\u{2500}'..='\u{257f}').contains(&c)
}

/// For each drawn row, how many columns from the left hold something the
/// floating controls must not cover: 0 for a blank or framing-only row.
///
/// With a prompt on screen, rows below the caret are the program's hint and
/// status lines, which may be covered (only ghost text there counts); wrapped
/// input above the caret takes its whole row; the caret row holds up to the
/// caret. Ghost text counts wherever it is drawn.
pub fn busy_columns(rows: &[Vec<char>], anchor: Option<Anchor>, placed: &[Placed]) -> Vec<usize> {
    let mut busy: Vec<usize> = rows
        .iter()
        .map(|cells| {
            if cells.iter().all(|&c| framing(c)) {
                0
            } else {
                cells.iter().rposition(|&c| c != ' ').map_or(0, |i| i + 1)
            }
        })
        .collect();
    if let Some(anchor) = anchor {
        let cols = rows.first().map_or(0, Vec::len);
        for (row, value) in busy.iter_mut().enumerate() {
            if row > anchor.row {
                *value = 0;
            } else if row == anchor.row {
                *value = anchor.col;
            } else if row >= anchor.prompt_row {
                *value = cols;
            }
        }
    }
    for run in placed {
        if let Some(value) = busy.get_mut(run.row) {
            *value = (*value).max(run.col + run.text.chars().count());
        }
    }
    busy
}

/// The first drawn row of what must stay in view at the bottom: the prompt
/// with its frame and the queued messages above it, or, with no prompt, the
/// block of drawn rows the bottom of the screen ends with (a dialog). A host
/// floats extra controls (a key row) above it. Never above the top third, so
/// a screen full of text still leaves room.
pub fn guard_row(screen: &[Vec<char>], anchor: Option<Anchor>, placed: &[Placed]) -> usize {
    let rows = screen.len();
    let mut top = match anchor {
        Some(anchor) => anchor.prompt_row.saturating_sub(1),
        None => {
            let mut top = rows;
            // Frames count here: a dialog's bottom edge is part of it.
            while top > 0 && screen[top - 1].iter().any(|&c| c != ' ') {
                top -= 1;
            }
            top
        }
    };
    for run in placed {
        top = top.min(run.row);
    }
    top.max(rows / 3)
}

#[cfg(test)]
#[allow(clippy::expect_used)]
mod tests {
    use super::*;

    fn screen(lines: &[&str], cols: usize) -> Vec<Vec<char>> {
        lines
            .iter()
            .map(|line| {
                let mut cells: Vec<char> = line.chars().collect();
                cells.resize(cols, ' ');
                cells
            })
            .collect()
    }

    fn text(s: &str) -> GhostSpan {
        GhostSpan {
            text: s.into(),
            style: GhostStyle::Text,
        }
    }

    fn ghost(draft: Vec<GhostSpan>) -> Ghost {
        Ghost {
            kind: PromptKind::Claude,
            draft,
            echoes: Vec::new(),
            reserve: 0,
            reserve_from: 0,
            colors: GhostColors {
                text: 0,
                dim: 0,
                warn: 0,
                background: 0,
            },
        }
    }

    // Captured from Claude Code 2.1.322 at 100x30 (trimmed): the cursor sits
    // after `❯ `, visible, and wrapped input is indented by two columns.
    const CLAUDE: &[&str] = &[
        "● All green.",
        "",
        "────────────────────────────────────────",
        "❯ hello there this is a long dictated",
        "  instruction ok",
        "────────────────────────────────────────",
        "  -- INSERT -- ⏵⏵ bypass permissions on",
    ];

    #[test]
    fn claudes_caret_is_found_on_its_prompt_and_under_wrapped_input() {
        let rows = screen(CLAUDE, 40);
        let anchor = locate(&rows, Some((4, 16)), PromptKind::Claude);
        assert_eq!(
            anchor,
            Some(Anchor {
                prompt_row: 3,
                row: 4,
                col: 16,
                indent: 2
            })
        );
        let empty = screen(&["────", "❯ ", "────"], 40);
        assert_eq!(
            locate(&empty, Some((1, 2)), PromptKind::Claude).map(|a| a.prompt_row),
            Some(1)
        );
    }

    #[test]
    fn codex_uses_its_own_glyph() {
        let rows = screen(
            &["", "› summarise what", "  changed", "", "  ? for shortcuts"],
            40,
        );
        assert!(locate(&rows, Some((2, 9)), PromptKind::Codex).is_some());
        // Claude's glyph is not Codex's prompt, and the reverse.
        assert!(locate(&rows, Some((2, 9)), PromptKind::Claude).is_none());
    }

    #[test]
    fn no_prompt_shape_means_no_anchor() {
        let rows = screen(CLAUDE, 40);
        // Hidden cursor (a dialog) or scrolled view.
        assert_eq!(locate(&rows, None, PromptKind::Claude), None);
        // A cursor on a row that is neither the prompt nor indented input.
        assert_eq!(locate(&rows, Some((0, 5)), PromptKind::Claude), None);
        // A dialog's highlighted choice inside a box is not the prompt.
        let dialog = screen(&["│ ❯ 1. Yes      │", "│   2. No       │"], 40);
        assert_eq!(locate(&dialog, Some((0, 4)), PromptKind::Claude), None);
        // Programs the host does not know get the strip.
        assert_eq!(locate(&rows, Some((4, 16)), PromptKind::Other), None);
        // Something unindented between caret and glyph breaks the chain.
        let broken = screen(&["❯ a", "b", "  c"], 40);
        assert_eq!(locate(&broken, Some((2, 3)), PromptKind::Claude), None);
    }

    #[test]
    fn the_draft_starts_at_the_caret_and_wraps_under_the_prompt() {
        let rows = screen(CLAUDE, 40);
        let anchor = locate(&rows, Some((4, 16)), PromptKind::Claude);
        let mut g = ghost(vec![
            text("also check the remote test "),
            GhostSpan {
                text: "for the same sleep".into(),
                style: GhostStyle::Partial,
            },
        ]);
        g.reserve = 10;
        let placed = place(&g, anchor, rows.len(), 40);
        assert_eq!(
            placed[0],
            Placed {
                row: 4,
                col: 16,
                text: "also check the".into(),
                style: GhostStyle::Text
            }
        );
        // Width is 40 - 10: no run reaches into the reserved columns.
        assert!(
            placed.iter().all(|p| p.col + p.text.chars().count() <= 30),
            "{placed:?}"
        );
        // Continuation lines start at the prompt's indent.
        assert!(placed.iter().filter(|p| p.row > 4).all(|p| p.col >= 2));
        assert!(placed.iter().any(|p| p.style == GhostStyle::Partial));
    }

    #[test]
    fn without_a_prompt_the_draft_takes_a_strip_at_the_bottom() {
        let rows = screen(CLAUDE, 40);
        let placed = place(
            &ghost(vec![text("no wait, keep the build dir")]),
            None,
            rows.len(),
            40,
        );
        assert_eq!(
            placed,
            vec![Placed {
                row: 6,
                col: 1,
                text: "no wait, keep the build dir".into(),
                style: GhostStyle::Text
            }]
        );
        assert!(place(&ghost(Vec::new()), None, rows.len(), 40).is_empty());
    }

    #[test]
    fn queued_messages_sit_above_the_prompts_frame_oldest_first() {
        let rows = screen(CLAUDE, 40);
        let anchor = locate(&rows, Some((3, 2)), PromptKind::Claude);
        let mut g = ghost(Vec::new());
        g.echoes = vec![
            vec![
                text("❯ first"),
                GhostSpan {
                    text: " queued".into(),
                    style: GhostStyle::Tag,
                },
            ],
            vec![text("❯ second")],
        ];
        let placed = place(&g, anchor, rows.len(), 40);
        // The rule above the prompt is row 2: echoes take rows 0 and 1.
        assert_eq!(placed[0].row, 0);
        assert_eq!(placed[0].text, "❯ first");
        assert_eq!(
            placed[1],
            Placed {
                row: 0,
                col: 7,
                text: " queued".into(),
                style: GhostStyle::Tag
            }
        );
        assert_eq!(placed[2].row, 1);
        // Too many for the room above: the newest stay.
        g.echoes.push(vec![text("❯ third")]);
        let placed = place(&g, anchor, rows.len(), 40);
        assert_eq!(placed.iter().map(|p| p.row).max(), Some(1));
        assert!(placed.iter().any(|p| p.text == "❯ third"));
    }

    #[test]
    fn a_long_draft_keeps_its_caret_end_on_screen() {
        let rows = screen(CLAUDE, 20);
        let anchor = Some(Anchor {
            prompt_row: 5,
            row: 5,
            col: 2,
            indent: 2,
        });
        let long = "word ".repeat(40);
        let placed = place(&ghost(vec![text(&long)]), anchor, 7, 20);
        assert!(placed.iter().all(|p| p.row < 7));
        assert!(placed[0].text.starts_with('…'));
    }

    #[test]
    fn the_reserve_narrows_only_the_rows_under_the_controls() {
        let anchor = Some(Anchor {
            prompt_row: 0,
            row: 0,
            col: 2,
            indent: 2,
        });
        let mut g = ghost(vec![text(&"abcd ".repeat(20))]);
        g.reserve = 20;
        g.reserve_from = 2;
        let placed = place(&g, anchor, 10, 40);
        let end = |row: usize| {
            placed
                .iter()
                .filter(|p| p.row == row)
                .map(|p| p.col + p.text.chars().count())
                .max()
                .unwrap_or(0)
        };
        assert!(end(0) > 20 && end(1) > 20, "{placed:?}");
        assert!(end(2) <= 20 && end(3) <= 20, "{placed:?}");
    }

    #[test]
    fn a_caret_at_the_edge_starts_the_draft_on_the_next_row() {
        let anchor = Some(Anchor {
            prompt_row: 0,
            row: 0,
            col: 40,
            indent: 2,
        });
        let placed = place(&ghost(vec![text("go")]), anchor, 10, 40);
        assert_eq!(placed[0].row, 1);
        assert_eq!(placed[0].col, 2);
    }

    #[test]
    fn the_guard_keeps_the_prompt_or_the_dialog_in_view() {
        let rows = screen(CLAUDE, 40);
        let anchor = locate(&rows, Some((4, 16)), PromptKind::Claude);
        // The rule above the prompt (row 2) and everything below stay.
        assert_eq!(guard_row(&rows, anchor, &[]), 2);
        // No prompt: the drawn block the screen ends with.
        let dialog = screen(
            &["", "", "", "", "", "", "╭──╮", "│ ❯ 1. Yes │", "╰──╯"],
            40,
        );
        assert_eq!(guard_row(&dialog, None, &[]), 6);
        // Never above the top third.
        let full = screen(&["x"; 9], 40);
        assert_eq!(guard_row(&full, None, &[]), 3);
    }

    #[test]
    fn a_chip_never_splits() {
        let anchor = Some(Anchor {
            prompt_row: 0,
            row: 0,
            col: 12,
            indent: 2,
        });
        let g = ghost(vec![
            text("fix "),
            GhostSpan {
                text: "◷ 0:09 waiting for desktop".into(),
                style: GhostStyle::Chip,
            },
        ]);
        let placed = place(&g, anchor, 10, 40);
        let chip = placed
            .iter()
            .find(|p| p.style == GhostStyle::Chip)
            .expect("chip placed");
        assert_eq!((chip.row, chip.col), (1, 2));
    }

    #[test]
    fn busy_columns_free_the_hint_area_and_frames_but_not_the_input() {
        let rows = screen(CLAUDE, 40);
        let anchor = locate(&rows, Some((4, 16)), PromptKind::Claude);
        let busy = busy_columns(&rows, anchor, &[]);
        assert_eq!(busy[0], 12); // transcript
        assert_eq!(busy[2], 0); // rule
        assert_eq!(busy[3], 40); // wrapped input above the caret
        assert_eq!(busy[4], 16); // caret row
        assert_eq!(busy[5], 0); // rule below
        assert_eq!(busy[6], 0); // status line: the hint area
        // Without a prompt, everything counts as drawn.
        let busy = busy_columns(&rows, None, &[]);
        assert_eq!(
            busy[6],
            "  -- INSERT -- ⏵⏵ bypass permissions on".chars().count()
        );
        // Ghost text counts where it is drawn.
        let placed = vec![Placed {
            row: 6,
            col: 2,
            text: "abc".into(),
            style: GhostStyle::Text,
        }];
        assert_eq!(busy_columns(&rows, anchor, &placed)[6], 5);
    }
}
