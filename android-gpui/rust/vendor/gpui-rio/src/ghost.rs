//! Ghost text: words the user has dictated or typed on the phone but not sent,
//! drawn at the program's own input caret so they read as part of its prompt.
//! Nothing here reaches the program; the host sends the words as one paste
//! when the user presses Enter.
//!
//! Where to draw is decided from the screen alone: Claude Code and Codex both
//! park the visible terminal cursor at their input caret, on a row that starts
//! with their prompt glyph (`❯ ` / `› `), and indent wrapped input by two
//! columns. When that shape is not on screen (a dialog, a hidden cursor, the
//! view scrolled into history, another program), nothing is drawn over what
//! the program drew: the draft goes to free cells at the bottom right, or the
//! host shows it itself.
//!
//! The host's controls are drawn here too, as words set into the program's own
//! frame ([`Control`]): into the rule under Claude's prompt like a box title,
//! at the right end of Codex's prompt row, or on free cells at the bottom right.
//! The host puts its touch targets on the cells reported for them.

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
    /// A control's quiet word ("type"), and the rule drawn around controls.
    Dim,
    /// The main action's word ("send").
    Bright,
    /// Recording.
    Danger,
    /// Connecting, transcribing, a queued count.
    Info,
}

/// One of the host's controls, drawn as words in the program's frame.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Control {
    /// What the host calls it; reported back with its cells.
    pub id: String,
    pub spans: Vec<GhostSpan>,
}

/// Where a control's words were drawn: `start..end` on drawn row `row`.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct PlacedControl {
    pub id: String,
    pub row: usize,
    pub start: usize,
    pub end: usize,
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
    pub bright: u32,
    pub danger: u32,
    pub info: u32,
}

/// What to draw: the draft at the caret and queued messages above the prompt.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Ghost {
    pub kind: PromptKind,
    pub draft: Vec<GhostSpan>,
    /// Queued messages, oldest first, one line each.
    pub echoes: Vec<Vec<GhostSpan>>,
    /// The host's controls, right to left in reading order (drawn left to right).
    pub controls: Vec<Control>,
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
    /// The controls as drawn; empty when there was no room for them.
    pub controls: Vec<PlacedControl>,
    /// The first row the host's touch targets may cover: the prompt's caret
    /// row (text above it is the program's, to scroll and select).
    pub targets_top: usize,
    /// Whether the draft was drawn; when not, the host shows it itself.
    pub draft_shown: bool,
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
        // Claude Code follows its glyph with a no-break space (U+00A0), so
        // any whitespace counts, there and in the indent below.
        if glyphs.contains(&at(0)) && at(1).is_whitespace() {
            // The caret sits after the glyph, never on it.
            return (prompt_row < row || col >= 2).then_some(Anchor {
                prompt_row,
                row,
                col,
                indent: 2,
            });
        }
        // Only wrapped input, indented under the prompt, lies between.
        if !(at(0).is_whitespace() && at(1).is_whitespace()) {
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
/// Where every ghost run goes on a `rows`×`cols` view: the draft from the
/// caret and the queued messages above the prompt. Without a prompt on screen
/// nothing is drawn: ghost text never covers what the program drew (the host
/// shows the draft in its own controls instead).
pub fn place(ghost: &Ghost, anchor: Option<Anchor>, rows: usize, cols: usize) -> Vec<Placed> {
    // A row under the host's controls is narrower; never narrower than 8 cells.
    let width_of = |row: usize| {
        if row >= ghost.reserve_from {
            cols.saturating_sub(ghost.reserve).max(8.min(cols))
        } else {
            cols
        }
    };
    place_with(ghost, anchor, rows, cols, &width_of)
}

fn place_with(
    ghost: &Ghost,
    anchor: Option<Anchor>,
    rows: usize,
    cols: usize,
    width_of: &dyn Fn(usize) -> usize,
) -> Vec<Placed> {
    if rows == 0 || cols == 0 {
        return Vec::new();
    }
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
        None => return Vec::new(),
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

/// Everything drawn over the screen in one frame, with where the controls went.
#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub struct Layout {
    pub placed: Vec<Placed>,
    pub controls: Vec<PlacedControl>,
    pub targets_top: usize,
    pub draft_shown: bool,
}

/// A row that only frames: a rule of `─` (Claude's prompt box edges).
fn rule(cells: &[char]) -> bool {
    let drawn = cells.iter().filter(|&&c| c == '─').count();
    drawn * 2 >= cells.len() && cells.iter().all(|&c| c == '─' || c == ' ')
}

/// Cells free at the end of a row: trailing blanks.
fn free_tail(cells: &[char]) -> usize {
    cells.iter().rev().take_while(|c| c.is_whitespace()).count()
}

/// The controls as one line: ` a ─ b ─ c ──`, each control's cells marked.
fn control_line(ghost: &Ghost) -> (Vec<GhostSpan>, Vec<(String, usize, usize)>) {
    let mut spans = Vec::new();
    let mut marks = Vec::new();
    let mut col = 0;
    let push = |text: &str, style: GhostStyle, spans: &mut Vec<GhostSpan>, col: &mut usize| {
        spans.push(GhostSpan {
            text: text.to_owned(),
            style,
        });
        *col += text.chars().count();
    };
    for (i, control) in ghost.controls.iter().enumerate() {
        push(
            if i == 0 { " " } else { " ─ " },
            GhostStyle::Dim,
            &mut spans,
            &mut col,
        );
        let start = col;
        for span in &control.spans {
            push(&span.text, span.style, &mut spans, &mut col);
        }
        marks.push((control.id.clone(), start, col));
    }
    if !ghost.controls.is_empty() {
        push(" ──", GhostStyle::Dim, &mut spans, &mut col);
    }
    (spans, marks)
}

/// The draft, queued messages and the host's controls for one frame over
/// `screen` (the rows as drawn). With Claude's prompt the controls are set
/// into the rule under it; with another prompt (Codex), at the right end of
/// the caret row, the draft wrapping short of them; with no prompt, on the
/// lowest row whose right end is blank, the draft on the row above it when
/// that end is blank too. Nothing is ever drawn over the program's text.
pub fn place_all(ghost: &Ghost, anchor: Option<Anchor>, screen: &[Vec<char>]) -> Layout {
    let rows = screen.len();
    let cols = screen.first().map_or(0, Vec::len);
    if rows == 0 || cols == 0 {
        return Layout::default();
    }
    let (line, marks) = control_line(ghost);
    let len: usize = line.iter().map(|s| s.text.chars().count()).sum();
    let fits = len > 0 && len + 8 <= cols;
    let mut layout = Layout::default();
    // The row the controls go on, if any.
    let controls_row = match anchor {
        Some(anchor) if fits => {
            let below = (anchor.row + 1..rows.min(anchor.row + 3)).find(|&r| rule(&screen[r]));
            Some(below.unwrap_or(anchor.row))
        }
        None if fits => (rows.saturating_sub(16)..rows)
            .rev()
            .find(|&r| free_tail(&screen[r]) > len),
        _ => None,
    };
    layout.targets_top = anchor.map_or(controls_row.unwrap_or(rows), |a| a.row);
    if let Some(row) = controls_row {
        let mut col = cols - len;
        for span in &line {
            let n = span.text.chars().count();
            layout.placed.push(Placed {
                row,
                col,
                text: span.text.clone(),
                style: span.style,
            });
            col += n;
        }
        layout.controls = marks
            .into_iter()
            .map(|(id, start, end)| PlacedControl {
                id,
                row,
                start: cols - len + start,
                end: cols - len + end,
            })
            .collect();
    }
    let has_draft = ghost.draft.iter().any(|s| !s.text.trim().is_empty());
    match anchor {
        Some(anchor) => {
            // The draft and the echoes, short of the controls on their row.
            let width_of = |row: usize| {
                if Some(row) == controls_row && !layout.controls.is_empty() {
                    cols.saturating_sub(len + 1).max(8)
                } else {
                    cols
                }
            };
            layout
                .placed
                .extend(place_with(ghost, Some(anchor), rows, cols, &width_of));
            layout.draft_shown = has_draft;
        }
        None => {
            // One line on the row above the controls, only on its blank end.
            if let Some(row) = controls_row
                .and_then(|r| r.checked_sub(1))
                .filter(|_| has_draft)
            {
                let room = free_tail(&screen[row]).saturating_sub(1).min(cols / 2);
                if room >= 12 {
                    let text: String = ghost
                        .draft
                        .iter()
                        .map(|s| s.text.replace(['\n', '\r', '\t'], " "))
                        .collect();
                    let text = text.trim();
                    let count = text.chars().count();
                    let shown: String = if count <= room {
                        text.to_owned()
                    } else {
                        let tail: String = text.chars().skip(count - (room - 1)).collect();
                        format!("…{tail}")
                    };
                    let n = shown.chars().count();
                    layout.placed.push(Placed {
                        row,
                        col: cols - n - 1,
                        text: shown,
                        style: GhostStyle::Text,
                    });
                    layout.draft_shown = true;
                }
            }
        }
    }
    layout
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
            controls: Vec::new(),
            reserve: 0,
            reserve_from: 0,
            colors: GhostColors {
                text: 0,
                dim: 0,
                warn: 0,
                background: 0,
                bright: 0,
                danger: 0,
                info: 0,
            },
        }
    }

    fn control(id: &str, word: &str) -> Control {
        Control {
            id: id.into(),
            spans: vec![GhostSpan {
                text: word.into(),
                style: GhostStyle::Dim,
            }],
        }
    }

    fn controls(mut g: Ghost) -> Ghost {
        g.controls = vec![
            control("type", "type"),
            control("talk", "talk"),
            control("send", "send"),
        ];
        g
    }

    /// The text drawn on `row`, cell by cell, over a blank row of `cols`.
    fn drawn(layout: &Layout, row: usize, cols: usize) -> String {
        let mut cells = vec![' '; cols];
        for run in layout.placed.iter().filter(|p| p.row == row) {
            for (i, c) in run.text.chars().enumerate() {
                cells[run.col + i] = c;
            }
        }
        cells.into_iter().collect::<String>().trim_end().to_owned()
    }

    #[test]
    fn claudes_controls_are_set_into_the_rule_under_its_prompt() {
        let rows = screen(
            &[
                "* Brewed for 3s",
                "",
                "────────────────────────────────────────────────────────────",
                "❯\u{a0}",
                "────────────────────────────────────────────────────────────",
                "  -- INSERT -- ⏵⏵ bypass permissions on",
            ],
            60,
        );
        let anchor = locate(&rows, Some((3, 2)), PromptKind::Claude);
        let layout = place_all(&controls(ghost(vec![text("Testing")])), anchor, &rows);
        assert_eq!(drawn(&layout, 4, 60).trim_start(), "type ─ talk ─ send ──");
        assert!(drawn(&layout, 4, 60).ends_with("send ──"));
        let talk = layout
            .controls
            .iter()
            .find(|c| c.id == "talk")
            .expect("talk drawn");
        assert_eq!((talk.row, talk.end - talk.start), (4, 4));
        assert_eq!(&drawn(&layout, 3, 60)[2..], "Testing");
        // Targets may cover the caret row and below, never the transcript above.
        assert_eq!(layout.targets_top, 3);
        assert!(layout.draft_shown);
    }

    #[test]
    fn codex_gets_its_controls_at_the_end_of_its_prompt_row_and_the_draft_wraps_short() {
        let rows = screen(&["", "› ", "", "  ? for shortcuts"], 40);
        let anchor = locate(&rows, Some((1, 2)), PromptKind::Codex);
        let mut g = controls(ghost(vec![text(&"word ".repeat(8))]));
        g.kind = PromptKind::Codex;
        let layout = place_all(&g, anchor, &rows);
        assert!(drawn(&layout, 1, 40).ends_with("send ──"));
        let first_control = layout
            .controls
            .iter()
            .map(|c| c.start)
            .min()
            .expect("controls");
        // Draft runs on the caret row end before the controls begin.
        assert!(
            layout
                .placed
                .iter()
                .filter(|p| p.row == 1 && p.style == GhostStyle::Text)
                .all(|p| p.col + p.text.chars().count() < first_control)
        );
    }

    #[test]
    fn with_a_dialog_the_controls_and_draft_take_free_cells_only() {
        let rows = screen(
            &[
                "╭──────────────────╮",
                "│ Do you want to?  │",
                "│ ❯ 1. Yes         │",
                "╰──────────────────╯",
                "",
                "",
            ],
            60,
        );
        let layout = place_all(
            &controls(ghost(vec![text("no wait, keep the build dir")])),
            None,
            &rows,
        );
        let row = layout.controls[0].row;
        assert_eq!(row, 5);
        assert!(drawn(&layout, 5, 60).ends_with("send ──"));
        assert!(drawn(&layout, 4, 60).ends_with("no wait, keep the build dir"));
        // Nothing over the dialog's rows.
        assert!(layout.placed.iter().all(|p| p.row >= 4));
        // A screen whose bottom rows are all text has no room: nothing drawn.
        let full = screen(&["x".repeat(60).as_str(); 4], 60);
        let layout = place_all(&controls(ghost(vec![text("hi")])), None, &full);
        assert!(layout.placed.is_empty() && layout.controls.is_empty() && !layout.draft_shown);
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
    fn without_a_prompt_nothing_is_drawn_over_the_program() {
        let rows = screen(CLAUDE, 40);
        let mut g = ghost(vec![text("no wait, keep the build dir")]);
        g.echoes = vec![vec![text("❯ queued")]];
        assert!(place(&g, None, rows.len(), 40).is_empty());
    }

    // Wolf's screen (Claude Code 2.1.319, vim mode, 74 columns): the prompt
    // glyph is followed by a no-break space, the status line is the last
    // row, and the cursor sits after the glyph. The draft must go on the
    // prompt row, never on the status line below it.
    #[test]
    fn claudes_no_break_space_after_its_glyph_still_reads_as_the_prompt() {
        let rows = screen(
            &[
                "✻ Brewed for 3s · done 11:35 AM",
                "",
                "──────────────────────────────────────────",
                "❯\u{a0}",
                "──────────────────────────────────────────",
                "  -- INSERT -- ⏵⏵ bypass permissions on (shift+tab to cycle)",
            ],
            74,
        );
        let anchor = locate(&rows, Some((3, 2)), PromptKind::Claude);
        assert_eq!(
            anchor,
            Some(Anchor {
                prompt_row: 3,
                row: 3,
                col: 2,
                indent: 2
            })
        );
        let placed = place(
            &ghost(vec![text("Testing, this is a test.")]),
            anchor,
            rows.len(),
            74,
        );
        assert_eq!(
            placed,
            vec![Placed {
                row: 3,
                col: 2,
                text: "Testing, this is a test.".into(),
                style: GhostStyle::Text
            }]
        );
        // Wrapped input under it may be indented with no-break spaces too.
        let wrapped = screen(&["❯\u{a0}one two", "\u{a0}\u{a0}three"], 74);
        assert!(locate(&wrapped, Some((1, 7)), PromptKind::Claude).is_some());
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
