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
//! at the right end of Codex's prompt row, or, without a prompt, where they
//! were last drawn or on free cells at the bottom right. They are never
//! dropped: with no free cells they cover the end of the bottom row.
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
    /// Draft text the keyboard has selected: drawn reversed, in the info colour.
    Selected,
    /// Where the keyboard's cursor is in the draft: an empty run, drawn as a
    /// thin bar before the next cell. Without one the bar follows the draft.
    Caret,
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
    /// The host's id for each echo (same order), reported back with its cells as `echo-<id>`;
    /// missing ids are reported by index.
    pub echo_ids: Vec<String>,
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
    /// The queue rows as drawn above the prompt, `echo-N` for `Ghost::echoes[N]`, oldest
    /// first; the host lays touch targets on them while its queue is open.
    pub echoes: Vec<PlacedControl>,
    /// The first row the host's touch targets may cover: the prompt's caret
    /// row (text above it is the program's, to scroll and select).
    pub targets_top: usize,
    /// Whether the draft was drawn; when not, the host shows it itself.
    pub draft_shown: bool,
    /// The draft's runs as drawn at the prompt, so a tap on its words puts
    /// the host's cursor there ([`draft_runs`]).
    pub draft_runs: Vec<DraftRun>,
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
    /// For a run of the draft at the prompt: which of the draft's characters
    /// (counted across its spans, a caret counting none) its first cell shows.
    pub source: Option<usize>,
}

/// A run of the draft as drawn: `len` cells from `col` on `row`, the first
/// showing the draft's character `source`. The host maps a tap there to a
/// place in its text.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct DraftRun {
    pub row: usize,
    pub col: usize,
    pub len: usize,
    pub source: usize,
}

/// The draft's drawn runs among `placed`, for the host's taps.
pub fn draft_runs(placed: &[Placed]) -> Vec<DraftRun> {
    placed
        .iter()
        .filter(|p| p.style != GhostStyle::Caret)
        .filter_map(|p| {
            p.source.map(|source| DraftRun {
                row: p.row,
                col: p.col,
                len: p.text.chars().count(),
                source,
            })
        })
        .filter(|run| run.len > 0)
        .collect()
}

/// A span with the draft character its text starts at.
type Located = (GhostSpan, usize);

/// `spans` with where each starts in the draft.
fn located(spans: &[GhostSpan]) -> Vec<Located> {
    let mut at = 0;
    spans
        .iter()
        .map(|span| {
            let start = at;
            at += span.text.chars().count();
            (span.clone(), start)
        })
        .collect()
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
    let draft_rows = anchor.map_or(0, |a| rows.saturating_sub(a.row));
    place_with(ghost, anchor, rows, cols, draft_rows, &width_of)
}

/// `draft_rows`: how many rows from the caret's down the draft may use.
fn place_with(
    ghost: &Ghost,
    anchor: Option<Anchor>,
    rows: usize,
    cols: usize,
    draft_rows: usize,
    width_of: &dyn Fn(usize) -> usize,
) -> Vec<Placed> {
    if rows == 0 || cols == 0 {
        return Vec::new();
    }
    let mut out = Vec::new();
    // Where the queued messages end: just above the prompt's frame, or the strip.
    let echoes_bottom = match anchor {
        Some(anchor) => {
            let width_at = |line: usize| width_of(anchor.row + line);
            let room = draft_rows.min(rows - anchor.row).clamp(1, MAX_DRAFT_ROWS);
            let draft = located(&ghost.draft);
            let mut lines = wrap(&draft, anchor.col, anchor.indent, &width_at);
            // Too long for its rows: the caret end, where the words are being
            // typed or spoken, is packed into them against each row's own free
            // width, from where wrapped input starts, behind an ellipsis.
            let elided = lines.len() > room;
            if elided {
                lines = tail(&draft, room, anchor.indent, &width_at);
            }
            for (i, line) in lines.into_iter().enumerate() {
                if line.is_empty() {
                    continue;
                }
                let mut col = if i == 0 && !elided {
                    anchor.col
                } else {
                    anchor.indent
                };
                for (span, source) in line {
                    let len = span.text.chars().count();
                    out.push(Placed {
                        row: anchor.row + i,
                        col,
                        text: span.text,
                        style: span.style,
                        source: Some(source),
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
                source: None,
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
    /// Where each queue row was drawn: `echo-<id>` with the host's `Ghost::echo_ids`, else
    /// `echo-N` for `Ghost::echoes[N]`.
    pub echoes: Vec<PlacedControl>,
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
fn control_line(controls: &[Control]) -> (Vec<GhostSpan>, Vec<(String, usize, usize)>) {
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
    for (i, control) in controls.iter().enumerate() {
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
    if !controls.is_empty() {
        push(" ──", GhostStyle::Dim, &mut spans, &mut col);
    }
    (spans, marks)
}

/// The controls for a grid too narrow for the host's line: only the clear (✕) and the queue
/// control (items 75, 76), then "talk" (the stop while recording) last, never dropped. The
/// decoration gives way first: the frame (` a ─ b ─ talk ──`), then the spaces between them;
/// then "talk" is cut, to one cell at the least; then the queue's count ("≡12" to "≡"). Only on
/// a grid too narrow even for `✕≡●` does the clear give way, then the queue; "talk" never.
fn narrow_line(controls: &[Control], cols: usize) -> (Vec<GhostSpan>, Vec<(String, usize, usize)>) {
    let width = |c: &Control| -> usize { c.spans.iter().map(|s| s.text.chars().count()).sum() };
    let Some(main) = controls
        .iter()
        .find(|c| c.id == "talk")
        .or(controls.last())
    else {
        return (Vec::new(), Vec::new());
    };
    let mut others: Vec<Control> = controls
        .iter()
        .filter(|c| (c.id == "clear" || c.id == "queue") && c.id != main.id)
        .cloned()
        .collect();
    let mut shortened = false;
    loop {
        let sum: usize = others.iter().map(width).sum();
        let n = others.len();
        // Framed, with "talk" at two cells at least.
        let fixed = 1 + sum + 3 * n + 3;
        if cols >= 8 && cols >= fixed + 2 {
            let mut line = others.clone();
            line.push(fit_control(main, cols - fixed));
            return control_line(&line);
        }
        // Spaced, then tight.
        for (gap, least) in [(1, 2), (0, 1)] {
            let fixed = sum + gap * n;
            if cols >= fixed + least {
                let mut line = others.clone();
                line.push(fit_control(main, cols - fixed));
                return bare_line(&line, gap);
            }
        }
        if !shortened {
            // The queue's count goes before any control does.
            shortened = true;
            for c in others.iter_mut().filter(|c| c.id == "queue") {
                let short = fit_control(c, 1);
                *c = short;
            }
            continue;
        }
        if others.is_empty() {
            return bare_line(&[fit_control(main, cols)], 0);
        }
        // A grid under three or four cells: the clear goes, then the queue.
        let at = others.iter().position(|c| c.id == "clear").unwrap_or(0);
        others.remove(at);
    }
}

/// `control` cut to `room` cells: to its first character when only one cell is left (so "● stop"
/// is still "●", not "…").
fn fit_control(control: &Control, room: usize) -> Control {
    if room == 1 {
        let first = control.spans.iter().find_map(|s| s.text.chars().next().map(|ch| (ch, s.style)));
        if let Some((ch, style)) = first {
            return Control {
                id: control.id.clone(),
                spans: vec![GhostSpan {
                    text: ch.to_string(),
                    style,
                }],
            };
        }
    }
    clip_control(control, room)
}

/// The controls with `gap` spaces between them and no frame, each one's cells marked.
fn bare_line(controls: &[Control], gap: usize) -> (Vec<GhostSpan>, Vec<(String, usize, usize)>) {
    let mut spans = Vec::new();
    let mut marks = Vec::new();
    let mut col = 0;
    for (i, control) in controls.iter().enumerate() {
        if i > 0 && gap > 0 {
            spans.push(GhostSpan {
                text: " ".repeat(gap),
                style: GhostStyle::Dim,
            });
            col += gap;
        }
        let start = col;
        for span in &control.spans {
            col += span.text.chars().count();
            spans.push(span.clone());
        }
        marks.push((control.id.clone(), start, col));
    }
    (spans, marks)
}

/// Where the controls were drawn in the frame before, for a frame without
/// the program's caret (a hidden cursor, the view scrolled into history).
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct Previous {
    pub row: usize,
    /// The view is scrolled into history: the controls stay on that row over
    /// whatever scrolled under them, so stop never moves from under the thumb.
    pub scrolled: bool,
}

/// The draft, queued messages and the host's controls for one frame over
/// `screen` (the rows as drawn). With Claude's prompt the controls are set
/// into the rule under it; with another prompt (Codex), at the right end of
/// the caret row, the draft wrapping short of them. With no prompt they stay
/// on the row they had (`previous`) while it is still a rule or blank there,
/// or always while scrolled; else on the lowest row whose right end is blank,
/// the draft on the row above it when that end is blank too. The controls are
/// never dropped for lack of room: when the host's line is too wide for the
/// grid (its words follow the last layout hint, which lags a fold or a zoom),
/// only its clear (✕), queue and "talk" controls are drawn, "talk" last and
/// cut to the grid if need be (the clear gives way first, then the queue,
/// never "talk"); and with no
/// free row they cover the right end of the bottom row. The draft is never
/// drawn over the program's text.
pub fn place_all(
    ghost: &Ghost,
    anchor: Option<Anchor>,
    screen: &[Vec<char>],
    previous: Option<Previous>,
) -> Layout {
    let rows = screen.len();
    let cols = screen.first().map_or(0, Vec::len);
    if rows == 0 || cols == 0 {
        return Layout::default();
    }
    let width = |line: &[GhostSpan]| -> usize { line.iter().map(|s| s.text.chars().count()).sum() };
    let (mut line, mut marks) = control_line(&ghost.controls);
    let mut len = width(&line);
    // At the prompt the draft needs room beside the controls.
    let roomy = len + if anchor.is_some() { 8 } else { 0 } <= cols;
    if len > 0 && !roomy {
        // The host's words are for a wider grid (it picks them from the last
        // layout hint, which lags a fold or a zoom): see [`narrow_line`].
        (line, marks) = narrow_line(&ghost.controls, cols);
        len = width(&line);
    }
    let fits = len > 0 && len <= cols && marks.iter().any(|(_, start, end)| end > start);
    let mut layout = Layout::default();
    // The row the controls go on, if any.
    let controls_row = match anchor {
        Some(anchor) if fits => {
            let below = (anchor.row + 1..rows.min(anchor.row + 3)).find(|&r| rule(&screen[r]));
            Some(below.unwrap_or(anchor.row))
        }
        None if fits => previous
            .filter(|p| {
                p.row < rows
                    && (p.scrolled || rule(&screen[p.row]) || free_tail(&screen[p.row]) > len)
            })
            .map(|p| p.row)
            .or_else(|| {
                (rows.saturating_sub(16)..rows)
                    .rev()
                    .find(|&r| free_tail(&screen[r]) > len)
            })
            .or(Some(rows - 1)),
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
                source: None,
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
                // On the controls' row, the draft ends a cell short of them.
                if Some(row) == controls_row && !layout.controls.is_empty() {
                    (cols - len).saturating_sub(1)
                } else {
                    cols
                }
            };
            // The controls win their cells: a wrapped draft line on their row
            // (wrap never narrows a line below 8 cells) is cut short of them.
            let controls_from = cols - len;
            // The draft never paints over the program's text below the caret:
            // it may use the caret's row and then only rows holding nothing of
            // the program's, blank ones and the prompt's frame (Claude's rule),
            // so Claude's status line under the rule stays readable. A longer
            // draft shows its caret end, as at the view's bottom.
            let free_below = screen[anchor.row + 1..]
                .iter()
                .take_while(|cells| rule(cells) || cells.iter().all(|c| c.is_whitespace()))
                .count();
            let draft = place_with(ghost, Some(anchor), rows, cols, 1 + free_below, &width_of)
                .into_iter()
                .filter_map(|mut run| {
                    if Some(run.row) != controls_row || layout.controls.is_empty() {
                        return Some(run);
                    }
                    let room = controls_from.saturating_sub(run.col);
                    if room == 0 {
                        return None;
                    }
                    run.text = clip(&run.text, room);
                    Some(run)
                });
            let draft: Vec<Placed> = draft.collect();
            layout.echoes = echo_marks(ghost, anchor, &draft);
            // Shown only if some of its words were placed: with no free cells
            // at the caret (the controls fill its row) nothing is, and the host
            // shows the draft itself instead of losing it.
            layout.draft_shown = has_draft
                && draft.iter().any(|p| {
                    matches!(
                        p.style,
                        GhostStyle::Text
                            | GhostStyle::Partial
                            | GhostStyle::Chip
                            | GhostStyle::Selected
                    ) && !p
                        .text
                        .trim_matches(|c: char| c == '…' || c.is_whitespace())
                        .is_empty()
                });
            layout.placed.extend(draft);
        }
        // Without Claude's prompt on screen the draft goes to the host's own prompt, below.
        None => {}
    }
    if has_draft && !layout.draft_shown {
        ghost_prompt(ghost, &mut layout, controls_row, screen);
    }
    layout
}

/// How many rows the host's own prompt gives a draft (more shows its caret end).
pub const GHOST_PROMPT_ROWS: usize = 3;

/// The draft never leaves the terminal and is never lost from view: when Claude's prompt
/// cannot take it (busy or streaming, a dialog, no room at its caret, scrolled away), it is
/// drawn at the bottom, just above the controls' row, as a prompt of the host's own in the
/// terminal's font and Claude's style: a rule, then `❯ ` and the draft, wrapping onto the
/// rows below with its caret, chips and marks, and its runs reported for taps as at
/// Claude's prompt. Its rows are painted over whatever the program drew there; its rule
/// only over a blank row or a rule, so a dialog's last line above stays readable.
fn ghost_prompt(ghost: &Ghost, layout: &mut Layout, controls_row: Option<usize>, screen: &[Vec<char>]) {
    let rows = screen.len();
    let cols = screen.first().map_or(0, Vec::len);
    if cols < 8 {
        return;
    }
    // The rows just above the controls, or the screen's last rows without them.
    let bottom = controls_row.unwrap_or(rows);
    let cells: usize = ghost.draft.iter().map(|s| s.text.chars().count()).sum();
    let width = cols - 2;
    // One cell more for the caret at the end.
    let needed = (cells + 1).div_ceil(width);
    let lines = needed.clamp(1, GHOST_PROMPT_ROWS).min(bottom);
    if lines == 0 {
        return;
    }
    let top = bottom - lines;
    let rule_row = top
        .checked_sub(1)
        .filter(|&r| rule(&screen[r]) || screen[r].iter().all(|c| c.is_whitespace()));
    // The prompt's rows first, blank on the program's background, so nothing shows through.
    for row in rule_row.unwrap_or(top)..bottom {
        layout.placed.push(Placed {
            row,
            col: 0,
            text: " ".repeat(cols),
            style: GhostStyle::Text,
            source: None,
        });
    }
    if let Some(row) = rule_row {
        layout.placed.push(Placed {
            row,
            col: 0,
            text: "─".repeat(cols),
            style: GhostStyle::Dim,
            source: None,
        });
    }
    layout.placed.push(Placed {
        row: top,
        col: 0,
        text: "❯".to_owned(),
        style: GhostStyle::Text,
        source: None,
    });
    let anchor = Anchor {
        prompt_row: top,
        row: top,
        col: 2,
        indent: 2,
    };
    let full = |_: usize| cols;
    let mut placed = place_with(ghost, Some(anchor), rows, cols, lines, &full);
    if layout.echoes.is_empty() {
        layout.echoes = echo_marks(ghost, anchor, &placed);
    } else {
        // Claude's prompt is on screen and its queue rows are drawn above it already.
        placed.retain(|p| p.source.is_some());
    }
    layout.draft_shown = placed.iter().any(|p| p.source.is_some());
    layout.placed.extend(placed);
}

/// Where each shown queue row went: the rows above the prompt's frame (`place_with` puts the
/// newest that fit there, oldest first), one mark per row spanning its drawn words.
fn echo_marks(ghost: &Ghost, anchor: Anchor, placed: &[Placed]) -> Vec<PlacedControl> {
    let bottom = anchor.prompt_row.saturating_sub(1);
    let shown = ghost.echoes.len().min(bottom);
    let first = ghost.echoes.len() - shown;
    (0..shown)
        .filter_map(|i| {
            let row = bottom - shown + i;
            let runs = placed.iter().filter(|p| p.row == row);
            let start = runs.clone().map(|p| p.col).min()?;
            let end = runs.map(|p| p.col + p.text.chars().count()).max()?;
            Some(PlacedControl {
                id: match ghost.echo_ids.get(first + i) {
                    Some(id) => format!("echo-{id}"),
                    None => format!("echo-{}", first + i),
                },
                row,
                start,
                end,
            })
        })
        .collect()
}

/// `control`'s words cut to `room` cells, marked with an ellipsis when cut.
fn clip_control(control: &Control, room: usize) -> Control {
    let total: usize = control.spans.iter().map(|s| s.text.chars().count()).sum();
    if total <= room {
        return control.clone();
    }
    let mut left = room.saturating_sub(1);
    let mut spans: Vec<GhostSpan> = Vec::new();
    for span in &control.spans {
        let text: String = span.text.chars().take(left).collect();
        left -= text.chars().count();
        if !text.is_empty() {
            spans.push(GhostSpan {
                text,
                style: span.style,
            });
        }
        if left == 0 {
            break;
        }
    }
    match spans.last_mut() {
        Some(last) => last.text.push('…'),
        None if room > 0 => spans.push(GhostSpan {
            text: "…".into(),
            style: GhostStyle::Dim,
        }),
        None => {}
    }
    Control {
        id: control.id.clone(),
        spans,
    }
}

/// The most of the end of `spans` that, behind "…", wraps into `room` lines
/// from column `indent` (line `i` ending at `width_of(i)`): what a long draft
/// shows so that its caret end is always on screen.
fn tail(
    spans: &[Located],
    room: usize,
    indent: usize,
    width_of: &dyn Fn(usize) -> usize,
) -> Vec<Vec<Located>> {
    let total: usize = spans.iter().map(|s| s.0.text.chars().count()).sum();
    let packed = |drop: usize| {
        let kept = drop_front_located(spans, drop);
        // The ellipsis stands for the dropped words: a tap on it goes to where
        // the shown ones start.
        let first = kept
            .iter()
            .find(|(s, _)| s.style != GhostStyle::Caret)
            .map_or(total, |(_, at)| *at);
        let mut rest = vec![(
            GhostSpan {
                text: "…".into(),
                style: GhostStyle::Text,
            },
            first,
        )];
        rest.extend(kept);
        wrap(&rest, indent, indent, width_of)
    };
    // The fewest characters dropped from the front that still fit.
    let (mut lo, mut hi) = (0, total);
    while lo < hi {
        let mid = (lo + hi) / 2;
        if packed(mid).len() <= room {
            hi = mid;
        } else {
            lo = mid + 1;
        }
    }
    let lines = packed(lo);
    // Wrapping is not strictly monotone (a word moves whole): step on until it fits.
    if lines.len() <= room {
        return lines;
    }
    (lo + 1..=total)
        .map(packed)
        .find(|lines| lines.len() <= room)
        .unwrap_or_default()
}

/// `spans` without their first `n` characters; a chip cut into goes whole.
#[cfg(test)]
fn drop_front(spans: &[GhostSpan], n: usize) -> Vec<GhostSpan> {
    drop_front_located(&located(spans), n)
        .into_iter()
        .map(|(span, _)| span)
        .collect()
}

/// [`drop_front`], each kept span still saying where it starts in the draft.
fn drop_front_located(spans: &[Located], mut n: usize) -> Vec<Located> {
    let mut out = Vec::new();
    for (span, at) in spans {
        if span.style == GhostStyle::Caret {
            // An elided caret goes to where the shown words start.
            out.push((span.clone(), *at));
            continue;
        }
        let len = span.text.chars().count();
        if n >= len {
            n -= len;
            continue;
        }
        if n > 0 && span.style == GhostStyle::Chip {
            n = 0;
            continue;
        }
        out.push((
            GhostSpan {
                text: span.text.chars().skip(n).collect(),
                style: span.style,
            },
            at + n,
        ));
        n = 0;
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
    spans: &[Located],
    first_col: usize,
    indent: usize,
    width_of: &dyn Fn(usize) -> usize,
) -> Vec<Vec<Located>> {
    let mut lines: Vec<Vec<Located>> = vec![Vec::new()];
    let mut col = first_col;
    let mut width = width_of(0);
    if col >= width {
        lines.push(Vec::new());
        col = indent;
        width = width_of(1);
    }
    for (span, start) in spans {
        if span.style == GhostStyle::Caret {
            // Takes no cell, and is never merged into a neighbouring run.
            if let Some(line) = lines.last_mut() {
                line.push((
                    GhostSpan {
                        text: String::new(),
                        style: GhostStyle::Caret,
                    },
                    *start,
                ));
            }
            continue;
        }
        // A chip is one unit: it never splits across lines.
        let pieces = if span.style == GhostStyle::Chip {
            vec![span.text.clone()]
        } else {
            split_words(&span.text)
        };
        // Where the next piece starts in the draft.
        let mut at = *start;
        for piece in pieces {
            let len = piece.chars().count();
            let piece_at = at;
            at += len;
            if piece.trim().is_empty() {
                // Spaces never start a continuation line nor push one.
                if col > indent && col + len <= width {
                    push(lines.last_mut(), &piece, span.style, piece_at);
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
            let mut head_at = piece_at;
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
                push(lines.last_mut(), &head, span.style, head_at);
                head_at += take;
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

fn push(line: Option<&mut Vec<Located>>, text: &str, style: GhostStyle, at: usize) {
    let Some(line) = line else { return };
    if text.is_empty() {
        return;
    }
    match line.last_mut() {
        // Merged only when it continues the run in the draft too.
        Some((last, start)) if last.style == style && *start + last.text.chars().count() == at => {
            last.text.push_str(text)
        }
        _ => line.push((
            GhostSpan {
                text: text.to_owned(),
                style,
            },
            at,
        )),
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
            echo_ids: Vec::new(),
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
        let layout = place_all(&controls(ghost(vec![text("Testing")])), anchor, &rows, None);
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
        let layout = place_all(&g, anchor, &rows, None);
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

    fn styled(t: &str, style: GhostStyle) -> GhostSpan {
        GhostSpan {
            text: t.into(),
            style,
        }
    }

    /// Every drawn draft run shows the draft's own characters from its source.
    fn assert_sources(g: &Ghost, layout: &Layout) {
        let draft: Vec<char> = g
            .draft
            .iter()
            .flat_map(|s| s.text.chars())
            .map(|c| if c == '\n' { ' ' } else { c })
            .collect();
        let runs = super::draft_runs(&layout.placed);
        assert!(!runs.is_empty());
        for run in runs {
            let placed = layout
                .placed
                .iter()
                .find(|p| p.row == run.row && p.col == run.col && p.source == Some(run.source))
                .expect("run placed");
            if placed.text == "…" {
                continue;
            }
            // A run cut short of the controls ends in "…".
            let shown: Vec<char> = placed.text.trim_end_matches('…').chars().collect();
            assert_eq!(
                &draft[run.source..run.source + shown.len()],
                shown.as_slice(),
                "{placed:?}"
            );
        }
    }

    #[test]
    fn each_drawn_run_knows_which_characters_of_the_draft_it_shows() {
        // Wrapped, with a chip and the keyboard's marks between the words.
        let (rows, anchor) = claude_box(24);
        let g = controls(ghost(vec![
            text("please keep the "),
            styled("[0:09 waiting]", GhostStyle::Chip),
            text(" build"),
            styled("", GhostStyle::Caret),
            styled(" dir", GhostStyle::Selected),
            text(" and rerun"),
        ]));
        let layout = place_all(&g, anchor, &rows, None);
        assert_sources(&g, &layout);
        // Elided: the "…" stands for the first shown character.
        let (rows, anchor) = claude_box(30);
        let g = ghost(vec![text(&"word ".repeat(60)), text("end")]);
        let layout = place_all(&g, anchor, &rows, None);
        assert_sources(&g, &layout);
        let runs = super::draft_runs(&layout.placed);
        let first = &runs[0];
        assert_eq!((first.len, first.col), (1, 2));
        assert_eq!(first.source, runs[1].source);
        assert_eq!(runs.last().map(|r| r.source + r.len), Some(303));
    }

    #[test]
    fn the_keyboards_cursor_is_drawn_inside_the_draft() {
        // "fix the build" with the cursor after "fix " and "the" selected.
        let (rows, anchor) = claude_box(60);
        let g = ghost(vec![
            text("fix "),
            styled("", GhostStyle::Caret),
            styled("the", GhostStyle::Selected),
            text(" build"),
        ]);
        let layout = place_all(&g, anchor, &rows, None);
        let caret: Vec<&Placed> = layout
            .placed
            .iter()
            .filter(|p| p.style == GhostStyle::Caret)
            .collect();
        assert_eq!(caret.len(), 1);
        // The prompt's text starts at col 2: "fix " takes 2..6.
        assert_eq!((caret[0].row, caret[0].col), (3, 6));
        let selected = layout
            .placed
            .iter()
            .find(|p| p.style == GhostStyle::Selected)
            .expect("selection drawn");
        assert_eq!(
            (selected.row, selected.col, selected.text.as_str()),
            (3, 6, "the")
        );
        assert_eq!(drawn(&layout, 3, 60), "  fix the build");
        assert!(layout.draft_shown);
    }

    #[test]
    fn a_cursor_in_the_elided_start_of_a_long_draft_stays_on_screen() {
        // 30 cols, a draft far longer than the rows it may use, the cursor
        // at its very start: the caret end is shown, the cursor after "…".
        let (rows, anchor) = claude_box(30);
        let long = "word ".repeat(60);
        let g = ghost(vec![styled("", GhostStyle::Caret), text(&long)]);
        let layout = place_all(&g, anchor, &rows, None);
        let caret = layout
            .placed
            .iter()
            .find(|p| p.style == GhostStyle::Caret)
            .expect("caret kept");
        assert_eq!(caret.row, 3);
        assert_eq!(drawn(&layout, 3, 30).chars().nth(2), Some('…'));
        assert_eq!(caret.col, 3);
    }

    #[test]
    fn a_cursor_wraps_with_its_words() {
        // The cursor after a word that wraps to the next row moves with it.
        let (rows, anchor) = claude_box(20);
        let g = ghost(vec![
            text("one two three "),
            text("four"),
            styled("", GhostStyle::Caret),
        ]);
        let layout = place_all(&g, anchor, &rows, None);
        let caret = layout
            .placed
            .iter()
            .find(|p| p.style == GhostStyle::Caret)
            .expect("caret kept");
        let four = layout
            .placed
            .iter()
            .find(|p| p.text.contains("four"))
            .expect("four drawn");
        assert_eq!(caret.row, four.row);
        assert_eq!(caret.col, four.col + four.text.trim_end().chars().count());
    }

    /// Wolf's ask 1: the draft is drawn in the terminal in every state, at Claude's prompt
    /// when it can take it, else at the host's own prompt above the controls; never only in
    /// an Android view (the composer has none any more).
    #[test]
    fn wolf_ask1_the_draft_is_drawn_in_the_terminal_in_every_state() {
        let g = || {
            controls(ghost(vec![
                text("fix the stop "),
                styled("", GhostStyle::Caret),
                text("button"),
            ]))
        };
        let shown = |layout: &Layout, what: &str| {
            assert!(layout.draft_shown, "{what}: {layout:?}");
            let runs = draft_runs(&layout.placed);
            assert!(!runs.is_empty(), "{what}: no draft runs");
            assert!(
                layout.placed.iter().any(|p| p.style == GhostStyle::Caret),
                "{what}: the caret is kept"
            );
            assert!(
                layout.placed.iter().any(|p| p.text.contains("button")),
                "{what}: {layout:?}"
            );
        };
        // At Claude's prompt.
        let (rows, anchor) = claude_status_screen(60);
        shown(&place_all(&g(), anchor, &rows, None), "at Claude's prompt");
        // Claude busy, streaming output: no prompt on screen.
        let busy = screen(
            &[
                "● Reading src/main.rs",
                "  ⎿ 120 lines",
                "✻ Brewing… (12s · esc to interrupt)",
                "",
                "",
                "",
            ],
            60,
        );
        let layout = place_all(&g(), None, &busy, None);
        shown(&layout, "Claude busy");
        let row = layout.controls[0].row;
        assert!(drawn(&layout, row - 1, 60).starts_with("❯ fix the stop button"));
        // A dialog over the prompt, every row of the screen written.
        let full = screen(&["x".repeat(60).as_str(); 6], 60);
        shown(&place_all(&g(), None, &full, None), "a full screen");
        // Scrolled into history: the controls keep their row, the draft goes above it.
        let layout = place_all(
            &g(),
            None,
            &busy,
            Some(Previous {
                row: 4,
                scrolled: true,
            }),
        );
        shown(&layout, "scrolled");
        assert!(draft_runs(&layout.placed).iter().all(|r| r.row < 4));
        // A narrow cover screen with the full recording line.
        let mut rec = full_line(OFFLINE);
        rec.draft = g().draft;
        let narrow = screen(&["", "", "", "", "", ""], 30);
        shown(&place_all(&rec, None, &narrow, None), "cover screen, recording");
        // A long draft shows its caret end in at most GHOST_PROMPT_ROWS rows.
        let mut long = g();
        long.draft = vec![text(&format!("{} END", "word ".repeat(80))), styled("", GhostStyle::Caret)];
        let layout = place_all(&long, None, &busy, None);
        let draft_rows: std::collections::BTreeSet<usize> =
            draft_runs(&layout.placed).iter().map(|r| r.row).collect();
        assert!(draft_rows.len() <= GHOST_PROMPT_ROWS, "{draft_rows:?}");
        assert!(layout.placed.iter().any(|p| p.text.contains("END")));
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
            None,
        );
        let row = layout.controls[0].row;
        assert_eq!(row, 5);
        assert!(drawn(&layout, 5, 60).ends_with("send ──"));
        // The draft in the host's own prompt, just above the controls (Wolf's ask 1: the
        // only input is the draft drawn in the terminal, never an Android box).
        assert_eq!(drawn(&layout, 4, 60), "❯ no wait, keep the build dir");
        assert!(layout.draft_shown);
        // Nothing over the dialog's rows, not even the prompt's rule.
        assert!(layout.placed.iter().all(|p| p.row >= 4));
        // A screen whose bottom rows are all text: the controls still cover the bottom
        // row's end, and the draft takes the row above it, painted over the program's.
        let full = screen(&["x".repeat(60).as_str(); 4], 60);
        let layout = place_all(&controls(ghost(vec![text("hi")])), None, &full, None);
        assert_eq!(layout.controls.len(), 3);
        assert!(layout.controls.iter().all(|c| c.row == 3));
        assert!(drawn(&layout, 3, 60).ends_with(" type ─ talk ─ send ──"));
        assert!(layout.draft_shown);
        assert!(drawn(&layout, 2, 60).starts_with("❯ hi"));
        assert_eq!(layout.targets_top, 3);
    }

    /// Claude's prompt box with the stop control while recording, as the host
    /// sends it when there is room.
    fn recording(mut g: Ghost) -> Ghost {
        g.controls = vec![
            control("type", "type"),
            control("talk", "● rec 0:05 · tap to stop"),
            control("send", "send"),
        ];
        g
    }

    /// Claude's prompt box `cols` wide, the caret after `❯ ` on row 3 and its
    /// lower rule on row 4.
    fn claude_box(cols: usize) -> (Vec<Vec<char>>, Option<Anchor>) {
        let rule = "─".repeat(cols);
        let rows = screen(
            &["* Brewing…", "", &rule, "❯\u{a0}", &rule, "  -- INSERT --"],
            cols,
        );
        let anchor = locate(&rows, Some((3, 2)), PromptKind::Claude);
        assert!(anchor.is_some());
        (rows, anchor)
    }

    /// The host's full line while recording, with three messages queued.
    fn full_line(talk: &str) -> Ghost {
        let mut g = ghost(Vec::new());
        g.controls = vec![
            control("type", "type"),
            control("talk", talk),
            control("send", "send·3"),
        ];
        g
    }

    const ONLINE: &str = "● rec 0:05 · tap to stop";
    const OFFLINE: &str = "desktop offline · rec 0:05 · tap to stop";

    #[test]
    fn stop_is_drawn_when_the_hosts_words_are_too_wide_for_a_tiny_grid() {
        // Pinch zoom at 24 pt on the cover screen (~28 cols), and with a 1.25
        // system font scale (~22 cols): the host's words are for a wider grid.
        for cols in [22, 28] {
            let (rows, anchor) = claude_box(cols);
            for talk in [ONLINE, OFFLINE] {
                let layout = place_all(&full_line(talk), anchor, &rows, None);
                assert_eq!(layout.controls.len(), 1, "{cols} cols, {talk}");
                let stop = &layout.controls[0];
                assert_eq!(
                    (stop.id.as_str(), stop.row),
                    ("talk", 4),
                    "{cols} cols, {talk}"
                );
                assert!(stop.end <= cols && stop.end > stop.start);
                // Whole when it fits the grid, else cut with an ellipsis.
                let line = drawn(&layout, 4, cols);
                if talk.chars().count() + 4 <= cols {
                    assert!(
                        line.ends_with(&format!(" {talk} ──")),
                        "{cols} cols: {line:?}"
                    );
                } else {
                    assert!(line.ends_with("… ──"), "{cols} cols: {line:?}");
                    assert!(line.trim_start().starts_with(&talk[..3]), "{line:?}");
                }
            }
        }
        // The host's own shortest words fit as they are, with the draft's margin.
        let (rows, anchor) = claude_box(22);
        let mut g = ghost(Vec::new());
        g.controls = vec![control("talk", "● stop")];
        let layout = place_all(&g, anchor, &rows, None);
        assert_eq!(drawn(&layout, 4, 22).trim_start(), "● stop ──");
    }

    #[test]
    fn the_first_frame_after_a_fold_still_draws_stop() {
        // Unfolded at 100 cols the full offline line fits; folded to 62 it
        // does not, and the host's narrower words come only with the next
        // layout hint. This frame already draws the stop, whole.
        let g = full_line(OFFLINE);
        let (wide, anchor) = claude_box(100);
        assert_eq!(place_all(&g, anchor, &wide, None).controls.len(), 3);
        let (folded, anchor) = claude_box(62);
        let layout = place_all(&g, anchor, &folded, None);
        assert_eq!(layout.controls.len(), 1);
        assert_eq!(layout.controls[0].id, "talk");
        assert!(drawn(&layout, 4, 62).ends_with(&format!(" {OFFLINE} ──")));
    }

    #[test]
    fn a_wrapped_draft_never_covers_the_stop() {
        // The host's words from a wider grid: only the stop is drawn, in the
        // rule under the prompt, and a long draft wraps onto that row. The
        // stop keeps its cells; the draft there is cut short of them, or left
        // out where the stop takes the whole row.
        let draft = "please keep the build directory and rerun only the failing tests";
        for (cols, talk) in [(22, OFFLINE), (34, ONLINE)] {
            let (rows, anchor) = claude_box(cols);
            let mut g = full_line(talk);
            g.draft = vec![text(draft)];
            let layout = place_all(&g, anchor, &rows, None);
            let stop = layout.controls.first().expect("stop drawn");
            assert_eq!((stop.id.as_str(), stop.row), ("talk", 4));
            let cells: Vec<char> = drawn(&layout, 4, cols).chars().collect();
            let label: String = cells[stop.start..stop.end].iter().collect();
            assert!(
                talk.starts_with(label.trim_end_matches('…')),
                "{cols}: {label:?}"
            );
            for run in layout
                .placed
                .iter()
                .filter(|p| p.row == 4 && p.style == GhostStyle::Text)
            {
                assert!(
                    run.col + run.text.chars().count() < stop.start,
                    "{run:?} covers {stop:?}"
                );
            }
            assert!(layout.draft_shown);
        }
        // With a few cells to spare, the wrapped line keeps them.
        let (rows, anchor) = claude_box(34);
        let mut g = full_line(ONLINE);
        g.draft = vec![text(draft)];
        let layout = place_all(&g, anchor, &rows, None);
        let kept = layout
            .placed
            .iter()
            .find(|p| p.row == 4 && p.style == GhostStyle::Text)
            .expect("the draft keeps the cells left of the stop");
        assert_eq!(kept.col, 2);
    }

    #[test]
    fn under_eight_columns_the_stop_drops_its_frame() {
        for cols in 2..8 {
            let rows = screen(&["x".repeat(cols).as_str(); 3], cols);
            let layout = place_all(&full_line(OFFLINE), None, &rows, None);
            let stop = layout.controls.first().expect("stop drawn");
            assert_eq!(stop.id, "talk");
            assert_eq!((stop.start, stop.end), (0, cols), "{cols} cols");
            assert!(drawn(&layout, 2, cols).starts_with('d'), "{cols} cols");
        }
    }

    #[test]
    fn a_long_draft_never_paints_over_claudes_status_line() {
        // Claude's box at 40 cols: caret row 3, rule row 4, the status line on
        // row 5. A draft of several lines may use the caret row and the rule,
        // never the status line; it shows its caret end.
        let (rows, anchor) = claude_box(40);
        let mut g = full_line("talk");
        g.controls = vec![control("talk", "talk")];
        let draft = "make the stop button bigger and keep it reachable while I scroll and fix the hide button";
        g.draft = vec![text(draft)];
        let layout = place_all(&g, anchor, &rows, None);
        let draft_runs: Vec<_> = layout
            .placed
            .iter()
            .filter(|p| p.style == GhostStyle::Text)
            .collect();
        assert!(
            draft_runs.iter().all(|p| p.row == 3 || p.row == 4),
            "{draft_runs:?}"
        );
        assert!(
            layout.placed.iter().all(|p| p.row != 5),
            "{:?}",
            layout.placed
        );
        // The caret end is the part kept; the start is elided.
        assert!(
            drawn(&layout, 3, 40).contains('…'),
            "{:?}",
            drawn(&layout, 3, 40)
        );
        let shown: String = draft_runs
            .iter()
            .map(|p| p.text.as_str())
            .collect::<Vec<_>>()
            .join(" ");
        assert!(shown.ends_with("button"), "{shown:?}");
        assert!(layout.draft_shown);
    }

    /// Claude's box at `cols` with a status line right under its rule: caret
    /// row 3, rule row 4, status row 5.
    fn claude_status_screen(cols: usize) -> (Vec<Vec<char>>, Option<Anchor>) {
        let rule = "─".repeat(cols);
        let rows = screen(
            &["", "", &rule, "❯", &rule, "  ? status: must stay readable"],
            cols,
        );
        let anchor = Some(Anchor {
            prompt_row: 3,
            row: 3,
            col: 2,
            indent: 2,
        });
        (rows, anchor)
    }

    fn draft_runs(layout: &Layout) -> Vec<&Placed> {
        layout
            .placed
            .iter()
            .filter(|p| p.style == GhostStyle::Text)
            .collect()
    }

    #[test]
    fn the_caret_end_survives_a_narrow_rule_row() {
        // 40 cols, idle "talk", a 103-cell draft, two rows of room (the caret
        // row and the rule, narrower by the controls): the end is packed into
        // those rows' own widths, never clipped away.
        let (rows, anchor) = claude_status_screen(40);
        let mut g = ghost(vec![text(&format!("{} END", "z".repeat(99)))]);
        g.controls = vec![control("talk", "talk")];
        let layout = place_all(&g, anchor, &rows, None);
        let runs = draft_runs(&layout);
        assert!(runs.iter().all(|p| p.row < 5), "{runs:?}");
        let shown: String = runs.iter().map(|p| p.text.as_str()).collect();
        assert!(
            shown.starts_with('…') && shown.ends_with(" END"),
            "{runs:?}"
        );
        let talk = &layout.controls[0];
        for run in runs.iter().filter(|p| p.row == talk.row) {
            assert!(run.col + run.text.chars().count() < talk.start, "{run:?}");
        }
    }

    #[test]
    fn the_caret_end_survives_when_the_controls_fill_the_rule_row() {
        // 22 cols with the full offline controls: the stop takes the whole rule
        // row, so the draft's end goes on the caret row alone.
        let (rows, anchor) = claude_status_screen(22);
        let mut g = full_line(OFFLINE);
        g.draft = vec![text(&format!("{} END", "z".repeat(99)))];
        let layout = place_all(&g, anchor, &rows, None);
        let runs = draft_runs(&layout);
        assert!(runs.iter().all(|p| p.row == 3), "{runs:?}");
        let shown: String = runs.iter().map(|p| p.text.as_str()).collect();
        assert!(shown.ends_with(" END"), "{runs:?}");
        assert!(layout.controls.iter().any(|c| c.id == "talk"));
    }

    #[test]
    fn an_elided_draft_stays_inside_the_grid() {
        // 76 cells with program text right under the caret row: one row of
        // room; the ellipsis counts against it, nothing runs past column 40.
        let (mut rows, anchor) = claude_status_screen(40);
        rows[4] = vec!['x'; 40];
        let g = ghost(vec![text(&"z".repeat(76))]);
        let layout = place_all(&g, anchor, &rows, None);
        assert!(!layout.placed.is_empty());
        assert!(
            layout
                .placed
                .iter()
                .all(|p| p.row == 3 && p.col + p.text.chars().count() <= 40),
            "{:?}",
            layout.placed
        );
    }

    #[test]
    fn with_no_free_cells_at_the_caret_the_draft_goes_to_the_hosts_own_prompt() {
        // Codex at 22 cols with the full offline controls on its caret row and
        // its hint right below: no cell is free at the caret, so the draft goes
        // to the host's own prompt above the controls (Wolf's ask 1: never an
        // Android view), its caret end shown.
        let cols = 22;
        let rows = screen(&["", "", "› ", "  ? for shortcuts", "", ""], cols);
        let anchor = locate(&rows, Some((2, 2)), PromptKind::Codex);
        let mut g = full_line(OFFLINE);
        g.kind = PromptKind::Codex;
        g.draft = vec![text(&format!("{} END", "z".repeat(99)))];
        let layout = place_all(&g, anchor, &rows, None);
        assert!(layout.controls.iter().any(|c| c.id == "talk"));
        assert!(
            layout.placed.iter().all(|p| p.row != 3),
            "{:?}",
            layout.placed
        );
        let row = layout.controls[0].row;
        assert!(layout.draft_shown, "{layout:?}");
        let runs = draft_runs(&layout.placed);
        assert!(!runs.is_empty() && runs.iter().all(|r| r.row < row), "{runs:?}");
        assert!(layout.placed.iter().any(|p| p.text.contains("END")), "the caret end shows");
        // With room, the same draft is placed and reported shown.
        let (rows, anchor) = claude_status_screen(40);
        let layout = place_all(&g, anchor, &rows, None);
        assert!(layout.draft_shown);
    }

    #[test]
    fn a_chip_cut_at_the_front_goes_whole() {
        let spans = vec![
            GhostSpan {
                text: "⟦clip⟧".into(),
                style: GhostStyle::Chip,
            },
            text(" and the rest"),
        ];
        assert_eq!(drop_front(&spans, 2), vec![text(" and the rest")]);
        assert_eq!(drop_front(&spans, 0), spans);
        assert_eq!(drop_front(&spans, 8), vec![text("nd the rest")]);
    }

    #[test]
    fn blank_rows_under_the_caret_still_take_the_draft() {
        // Nothing of the program's under the caret: the draft may wrap there.
        let rows = screen(&["older output", "", "› ", "", "", ""], 30);
        let anchor = locate(&rows, Some((2, 2)), PromptKind::Codex);
        let mut g = ghost(vec![text(
            "one two three four five six seven eight nine ten eleven",
        )]);
        g.kind = PromptKind::Codex;
        let layout = place_all(&g, anchor, &rows, None);
        let last = layout
            .placed
            .iter()
            .map(|p| p.row)
            .max()
            .expect("draft drawn");
        assert_eq!(last, 3, "wrapped onto the blank row: {:?}", layout.placed);
        // A row with text below them stops it.
        let rows = screen(&["older output", "", "› ", "  ? for shortcuts", "", ""], 30);
        let layout = place_all(&g, anchor, &rows, None);
        assert!(
            layout.placed.iter().all(|p| p.row == 2),
            "{:?}",
            layout.placed
        );
    }

    #[test]
    fn a_control_is_cut_across_its_spans() {
        let talk = Control {
            id: "talk".into(),
            spans: vec![
                GhostSpan {
                    text: "talk".into(),
                    style: GhostStyle::Dim,
                },
                GhostSpan {
                    text: "·12".into(),
                    style: GhostStyle::Warn,
                },
            ],
        };
        assert_eq!(clip_control(&talk, 7), talk);
        let cut = clip_control(&talk, 6);
        let words: Vec<&str> = cut.spans.iter().map(|s| s.text.as_str()).collect();
        assert_eq!(words, ["talk", "·…"]);
        assert_eq!(clip_control(&talk, 1).spans[0].text, "…");
        assert!(clip_control(&talk, 0).spans.is_empty());
    }

    #[test]
    fn scrolled_into_history_the_controls_stay_on_their_row() {
        let mut lines = vec!["older output that fills the whole row with text, edge to edge"; 9];
        lines[8] = "  -- INSERT -- ⏵⏵ bypass permissions on";
        let rows = screen(&lines, 60);
        let g = recording(ghost(vec![text("half a thought")]));
        // Scrolled: no caret, and history covers the rule's row. The controls
        // stay where they were drawn, over what scrolled under them.
        let previous = Previous {
            row: 6,
            scrolled: true,
        };
        let layout = place_all(&g, None, &rows, Some(previous));
        let talk = layout
            .controls
            .iter()
            .find(|c| c.id == "talk")
            .expect("stop drawn while scrolled");
        assert_eq!(talk.row, 6);
        assert!(drawn(&layout, 6, 60).ends_with("● rec 0:05 · tap to stop ─ send ──"));
        assert_eq!(layout.targets_top, 6);
        // Not scrolled, the old row holds text now (a dialog): it is not kept.
        let live = Previous {
            row: 6,
            scrolled: false,
        };
        let layout = place_all(&g, None, &rows, Some(live));
        assert!(layout.controls.iter().all(|c| c.row == 8));
        // A previous row past the bottom (the view shrank) is ignored.
        let gone = Previous {
            row: 40,
            scrolled: true,
        };
        let layout = place_all(&g, None, &rows, Some(gone));
        assert!(layout.controls.iter().all(|c| c.row == 8));
    }

    #[test]
    fn a_hidden_caret_keeps_the_controls_in_claudes_rule() {
        let rows = screen(
            &[
                "* Working… (12s · esc to interrupt)",
                "",
                "────────────────────────────────────────────────────────────",
                "❯\u{a0}",
                "────────────────────────────────────────────────────────────",
                "  -- INSERT -- ⏵⏵ bypass permissions on",
            ],
            60,
        );
        let g = recording(ghost(Vec::new()));
        let anchored = place_all(
            &g,
            locate(&rows, Some((3, 2)), PromptKind::Claude),
            &rows,
            None,
        );
        let row = anchored.controls[0].row;
        assert_eq!(row, 4);
        // Claude hides its cursor for a frame while it redraws: no caret, but
        // the rule is still there, so the controls do not jump.
        let previous = Previous {
            row,
            scrolled: false,
        };
        let layout = place_all(&g, None, &rows, Some(previous));
        assert_eq!(layout.controls, anchored.controls);
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
                style: GhostStyle::Text,
                source: Some(0),
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
                style: GhostStyle::Text,
                source: Some(0),
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
                style: GhostStyle::Tag,
                source: None,
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
    fn a_line_too_wide_for_the_grid_keeps_clear_the_queue_and_the_stop() {
        // What the host sends for a wider grid (its words lag a fold); the stop last.
        let control = |id: &str, t: &str| Control {
            id: id.into(),
            spans: vec![GhostSpan {
                text: t.into(),
                style: GhostStyle::Dim,
            }],
        };
        let mut g = ghost(Vec::new());
        g.controls = vec![
            control("type", "type"),
            control("clear", "✕"),
            control("queue", "≡2"),
            control("talk", "● rec 0:05 · tap to stop"),
        ];
        for cols in 5..60 {
            let rows = screen(CLAUDE, cols);
            let anchor = locate(&rows, Some((3, 2)), PromptKind::Claude);
            for a in [anchor, None] {
                let layout = place_all(&g, a, &rows, None);
                let ids: Vec<&str> = layout.controls.iter().map(|c| c.id.as_str()).collect();
                assert!(ids.contains(&"talk"), "{cols}: {ids:?}");
                assert!(ids.contains(&"clear"), "{cols}: {ids:?}");
                assert!(ids.contains(&"queue"), "{cols}: {ids:?}");
                assert_eq!(ids.last(), Some(&"talk"), "{cols}: the stop stays last");
                assert!(layout.controls.iter().all(|c| c.end <= cols), "{cols}: on the grid");
            }
        }
        // The decoration goes first, then talk's words, then the queue's count.
        let (line, marks) = narrow_line(&g.controls, 5);
        let drawn: String = line.iter().map(|s| s.text.as_str()).collect();
        assert_eq!(drawn, "✕≡2●…");
        assert_eq!(marks.len(), 3);
        let mut big = g.clone();
        big.controls[2] = control("queue", "≡99+");
        let (line, marks) = narrow_line(&big.controls, 5);
        let drawn: String = line.iter().map(|s| s.text.as_str()).collect();
        assert!(drawn.starts_with("✕≡●"), "the count gives way before a control: {drawn}");
        assert_eq!(drawn.chars().count(), 5);
        assert_eq!(marks.len(), 3);
        // Only under four cells does the clear give way, then the queue; the stop never.
        let ids = |cols| -> Vec<String> { narrow_line(&g.controls, cols).1.into_iter().map(|m| m.0).collect() };
        assert_eq!(ids(3), vec!["clear", "queue", "talk"]);
        assert_eq!(ids(2), vec!["queue", "talk"]);
        assert_eq!(ids(1), vec!["talk"]);
    }

    /// The Java ladder's line at every width from 5 to 140 (`ControlLineTest` writes the same
    /// table and fails if it differs), drawn by this renderer: ✕, the queue and talk always.
    #[test]
    fn the_hosts_line_at_every_width_keeps_clear_the_queue_and_talk() {
        let table = include_str!("testdata/control_widths.tsv");
        let mut failures = Vec::new();
        for row in table.lines().filter(|l| !l.is_empty()) {
            let mut parts = row.split('\t');
            let cols: usize = parts.next().unwrap().parse().unwrap();
            let state = parts.next().unwrap();
            let mut g = ghost(Vec::new());
            g.controls = parts
                .map(|p| {
                    let (id, text) = p.split_once('=').unwrap();
                    Control {
                        id: id.into(),
                        spans: vec![GhostSpan {
                            text: text.into(),
                            style: GhostStyle::Dim,
                        }],
                    }
                })
                .collect();
            let blank = vec![vec![' '; cols]; 10];
            let claude = screen(CLAUDE, cols);
            let cases = [
                (&blank, Some(Anchor { prompt_row: 5, row: 6, col: 2, indent: 2 })),
                (&blank, None),
                (&claude, locate(&claude, Some((3, 2)), PromptKind::Claude)),
                (&claude, None),
            ];
            for (rows, anchor) in cases {
                let layout = place_all(&g, anchor, rows, None);
                let ids: Vec<&str> = layout.controls.iter().map(|c| c.id.as_str()).collect();
                let ok = ["clear", "queue", "talk"].iter().all(|id| ids.contains(id))
                    && layout.controls.iter().all(|c| c.end <= cols && c.end > c.start);
                if !ok {
                    failures.push(format!("{state} {cols} {}: {ids:?}", anchor.is_some()));
                }
            }
        }
        assert!(failures.is_empty(), "{} failures:\n{}", failures.len(), failures.join("\n"));
    }

    #[test]
    fn each_drawn_queue_row_is_reported_for_its_touch_target() {
        let rows = screen(CLAUDE, 40);
        let anchor = locate(&rows, Some((3, 2)), PromptKind::Claude);
        let mut g = ghost(Vec::new());
        g.echoes = vec![
            vec![text("❯ first")],
            vec![
                text("❯ second"),
                GhostSpan {
                    text: "   ◌ queued 1st".into(),
                    style: GhostStyle::Tag,
                },
            ],
        ];
        let layout = place_all(&g, anchor, &rows, None);
        // The rule above the prompt is row 2: rows 0 and 1, each spanning its drawn words.
        assert_eq!(
            layout.echoes,
            vec![
                PlacedControl {
                    id: "echo-0".into(),
                    row: 0,
                    start: 0,
                    end: 7
                },
                PlacedControl {
                    id: "echo-1".into(),
                    row: 1,
                    start: 0,
                    end: 23
                },
            ]
        );
        // More rows than room: the newest are drawn, and keep their own indices.
        g.echoes.insert(0, vec![text("❯ zeroth")]);
        let layout = place_all(&g, anchor, &rows, None);
        let ids: Vec<&str> = layout.echoes.iter().map(|e| e.id.as_str()).collect();
        assert_eq!(ids, vec!["echo-1", "echo-2"]);
        // No prompt, no rows drawn: nothing to tap.
        assert!(place_all(&g, None, &rows, None).echoes.is_empty());
        // With the host's ids, each drawn row is reported by its own id, whatever its place.
        g.echo_ids = vec!["m-zero".into(), "m-one".into(), "m-two".into()];
        let layout = place_all(&g, anchor, &rows, None);
        let ids: Vec<&str> = layout.echoes.iter().map(|e| e.id.as_str()).collect();
        assert_eq!(ids, vec!["echo-m-one", "echo-m-two"]);
    }

    #[test]
    fn a_long_draft_keeps_its_caret_end_on_screen() {
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
            source: None,
        }];
        assert_eq!(busy_columns(&rows, anchor, &placed)[6], 5);
    }
}
