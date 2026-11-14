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

/// Where the controls were drawn in the frame before, for a frame without
/// the program's caret (a hidden cursor, the view scrolled into history).
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct Previous {
    pub row: usize,
    /// The view is scrolled into history: the controls stay on that row over
    /// whatever scrolled under them, so stop never moves from under the thumb.
    pub scrolled: bool,
}

/// The rows a frame was drawn with: the pane's and the grid's heights and
/// the grid row drawn at the pane's top (the view origin's row, negative for
/// a grid drawn against the pane's bottom).
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub struct DrawnRows {
    pub pane: u16,
    pub grid: u16,
    pub origin: i32,
}

impl Previous {
    /// The controls' row from a frame drawn with `from`, for one drawn with
    /// `to`. The same heights: unchanged, whatever the origin did (scrolling
    /// a larger grid into history moves its origin but changes no height, and
    /// the controls stay pinned to their pane row while scrolled). The same
    /// grid in a pane of another height (a keyboard shown or hidden before the host resizes,
    /// as while the link is down): rebased by the origin's move, onto the
    /// same grid row. A grid of another height (the host's resize arrived):
    /// `None`, as its rows need not hold what they held. `None` too when the
    /// rebased row is above the pane; one past its bottom is left to
    /// [`place_all`], which ignores it. Either way the controls then find
    /// their row afresh.
    pub fn carried(self, from: DrawnRows, to: DrawnRows) -> Option<Self> {
        if from.grid != to.grid {
            return None;
        }
        if from.pane == to.pane {
            return Some(self);
        }
        let row = i64::try_from(self.row).ok()? + i64::from(from.origin) - i64::from(to.origin);
        Some(Self {
            row: usize::try_from(row).ok()?,
            ..self
        })
    }
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
/// only its "talk" control is drawn, cut to the grid if need be; and with no
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
        // layout hint, which lags a fold or a zoom): its main control alone,
        // "talk" (the stop while recording), cut to the grid if need be, so
        // stop is never missing for a frame or for good.
        // Under 8 cells the frame's ` ` and ` ──` would leave the words no room.
        let framed = cols >= 8;
        let room = if framed { cols - 4 } else { cols };
        let main = ghost
            .controls
            .iter()
            .find(|c| c.id == "talk")
            .or(ghost.controls.last())
            .map(|c| clip_control(c, room));
        (line, marks) = match main {
            Some(main) if !framed => {
                let end = width(&main.spans);
                (main.spans, vec![(main.id, 0, end)])
            }
            main => control_line(main.as_slice()),
        };
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
            // The controls win their cells: a wrapped draft line on their row
            // (wrap never narrows a line below 8 cells) is cut short of them.
            let controls_from = cols - len;
            let draft = place_with(ghost, Some(anchor), rows, cols, &width_of)
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
            layout.placed.extend(draft);
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
        assert!(drawn(&layout, 4, 60).ends_with("no wait, keep the build dir"));
        // Nothing over the dialog's rows.
        assert!(layout.placed.iter().all(|p| p.row >= 4));
        // A screen whose bottom rows are all text has no free cells: the
        // controls still cover the bottom row's end, the draft is not drawn.
        let full = screen(&["x".repeat(60).as_str(); 4], 60);
        let layout = place_all(&controls(ghost(vec![text("hi")])), None, &full, None);
        assert_eq!(layout.controls.len(), 3);
        assert!(layout.controls.iter().all(|c| c.row == 3));
        assert!(drawn(&layout, 3, 60).ends_with(" type ─ talk ─ send ──"));
        assert!(!layout.draft_shown);
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
    fn a_hidden_caret_after_the_keyboard_hides_offline_keeps_the_controls_at_the_bottom() {
        // A 24-row grid filling a 24-row pane, the controls in the rule on its
        // last row (pane row 23). The keyboard hides with the link down: the
        // pane is 52 rows, the grid still 24, drawn against the bottom (origin
        // row -28), so its rule is on pane row 51 and rows 0..28 are empty.
        let mut lines = vec!["older output that fills the whole row with text, edge to edge"; 23];
        lines.push("────────────────────────────────────────────────────────────");
        let grid = screen(&lines, 60);
        let g = recording(ghost(Vec::new()));
        let before = place_all(&g, None, &grid, None);
        assert!(before.controls.iter().all(|c| c.row == 23));
        let mut tall = vec![vec![' '; 60]; 28];
        tall.extend(grid);
        let filled = DrawnRows {
            pane: 24,
            grid: 24,
            origin: 0,
        };
        let anchored = DrawnRows {
            pane: 52,
            grid: 24,
            origin: -28,
        };
        // A frame with no caret: the controls' last row, carried from origin
        // row 0 to -28, is the same rule, now at the pane's bottom.
        let previous = Previous {
            row: 23,
            scrolled: false,
        };
        let carried = previous.carried(filled, anchored);
        assert_eq!(
            carried,
            Some(Previous {
                row: 51,
                scrolled: false
            })
        );
        let layout = place_all(&g, None, &tall, carried);
        assert!(!layout.controls.is_empty());
        assert!(
            layout.controls.iter().all(|c| c.row == 51),
            "{:?}",
            layout.controls
        );
        assert_eq!(drawn(&layout, 51, 60), drawn(&before, 23, 60));
        // Uncarried, the stale row 23 is a blank one above the grid, which
        // place_all would keep: the adapter carries it before calling it.
        let stale = place_all(&g, None, &tall, Some(previous));
        assert!(stale.controls.iter().all(|c| c.row == 23));
        // The keyboard shown again before the host resizes: back on row 23.
        assert_eq!(
            carried.and_then(|p| p.carried(anchored, filled)),
            Some(previous)
        );
        // Unchanged rows (a scroll, a repaint): the row is kept as it was.
        assert_eq!(previous.carried(filled, filled), Some(previous));
        // Moved above the pane: dropped. The keyboard shown with the link down
        // over a grid filling the pane: a 12-row pane, the window following
        // the caret at the grid's bottom (origin row 12), row 5 now above it.
        let shrunk = DrawnRows {
            pane: 12,
            grid: 24,
            origin: 12,
        };
        let high = Previous {
            row: 5,
            scrolled: false,
        };
        assert_eq!(high.carried(filled, shrunk), None);
        let kept = previous.carried(filled, shrunk);
        assert_eq!(kept.map(|p| p.row), Some(11));
    }

    #[test]
    fn scrolling_a_larger_grid_keeps_the_controls_row() {
        // A 40-row grid in a 24-row pane: at the bottom its window follows the
        // caret (origin row 16); scrolled into history it starts at the top
        // (origin row 0). Neither height changes, so the row is not moved.
        let following = DrawnRows {
            pane: 24,
            grid: 40,
            origin: 16,
        };
        let history = DrawnRows {
            origin: 0,
            ..following
        };
        let previous = Previous {
            row: 23,
            scrolled: true,
        };
        assert_eq!(previous.carried(following, history), Some(previous));
        assert_eq!(previous.carried(history, following), Some(previous));
        // Scrolled, the controls stay on that pane row over the history drawn
        // there, so stop never moves from under the thumb.
        let rows = screen(
            &["older output that fills the whole row with text, edge to edge"; 24],
            60,
        );
        let g = recording(ghost(Vec::new()));
        let layout = place_all(&g, None, &rows, previous.carried(following, history));
        assert!(!layout.controls.is_empty());
        assert!(
            layout.controls.iter().all(|c| c.row == 23),
            "{:?}",
            layout.controls
        );
    }

    #[test]
    fn a_host_grid_of_a_new_height_drops_the_controls_last_row() {
        // After the keyboard hid offline (a 24-row grid at the bottom of a
        // 52-row pane, the controls on pane row 51), the link is back and the
        // host's grid catches up to 52 rows: drawn from the top, origin 0. Its
        // rows are the program's new layout; row 23 holds nothing now.
        let anchored = DrawnRows {
            pane: 52,
            grid: 24,
            origin: -28,
        };
        let caught_up = DrawnRows {
            pane: 52,
            grid: 52,
            origin: 0,
        };
        let mut lines = vec!["older output that fills the whole row with text, edge to edge"; 23];
        lines.resize(52, "");
        let rows = screen(&lines, 60);
        let g = recording(ghost(Vec::new()));
        let previous = Previous {
            row: 51,
            scrolled: false,
        };
        // Not rebased onto row 23 (the old keyboard boundary): dropped, so a
        // frame with no caret finds the controls' row afresh, at the bottom.
        let carried = previous.carried(anchored, caught_up);
        assert_eq!(carried, None);
        let layout = place_all(&g, None, &rows, carried);
        assert!(!layout.controls.is_empty());
        assert!(
            layout.controls.iter().all(|c| c.row == 51),
            "{:?}",
            layout.controls
        );
        // Rebased by the origin's move instead, it would sit on row 23.
        let rebased = Previous {
            row: 23,
            scrolled: false,
        };
        let stuck = place_all(&g, None, &rows, Some(rebased));
        assert!(stuck.controls.iter().all(|c| c.row == 23));
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
