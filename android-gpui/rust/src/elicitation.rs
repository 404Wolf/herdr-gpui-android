//! Agent question dialogs read from the screen, so the phone can answer them
//! with native controls (factory-setup.33).
//!
//! Claude Code's AskUserQuestion and permission prompts, Codex's
//! request_user_input and approvals, and Gemini CLI's AskUserDialog and tool
//! confirmations all draw the same shape: a question, a numbered option list
//! with exactly one cursor row (`❯`, `›` or `●` before the number) and a key
//! hint line below it (`Enter to select`, `esc to interrupt`, ...), with a
//! tab row above when there are several questions. [`parse`] recognises that
//! shape and nothing else, so a numbered list in an agent's reply never reads
//! as a dialog. The inventory it was written against is in
//! `ELICITATION.md`.
//!
//! [`step`] turns the answers the user chose on the phone into the next few
//! keys, from the dialog as it is on screen now. The host sends them, waits
//! for the screen to change, parses again and asks for the next step, so every
//! key is chosen against what the agent actually shows, never replayed blind.
//! Nothing here sends anything: the host runs [`step`] only after the user
//! pressed Submit (or Chat, or Cancel) on the phone.

use gpui_rio::ghost::PromptKind;
use rio_vt::{
    crosswords::{
        Crosswords,
        pos::{Column, Line as GridLine},
    },
    event::EventListener,
};
use serde::{Deserialize, Serialize};

/// Bottom rows scanned: a tall dialog with descriptions fits; history above is never read.
pub const SCAN_ROWS: usize = 60;
/// Most choices one dialog reports; a longer list is not taken for a dialog.
const MAX_CHOICES: usize = 32;
/// Most characters kept from one line of the dialog.
const MAX_LINE_CHARS: usize = 512;
/// Most context lines kept around the question.
const MAX_DETAIL: usize = 10;
/// Most host round trips one submission may take before it gives up.
pub const MAX_STEPS: u32 = 256;
/// Most characters erased from a custom answer before retyping it.
const MAX_ERASE: usize = 512;

/// One screen row: its characters, and which cells are drawn emphasised
/// (a non-default background, inverse or underline: how Claude and Gemini
/// mark the active tab).
#[derive(Clone, Debug, Default, PartialEq, Eq, Hash)]
pub struct Row {
    pub text: Vec<char>,
    pub marked: Vec<bool>,
}

impl Row {
    /// A row with no emphasis.
    pub fn plain(text: &str) -> Self {
        let text: Vec<char> = text.chars().collect();
        let marked = vec![false; text.len()];
        Self { text, marked }
    }

    /// A row whose cells `from..to` are emphasised.
    pub fn marked(text: &str, from: usize, to: usize) -> Self {
        let mut row = Self::plain(text);
        let end = to.min(row.marked.len());
        for cell in row.marked.iter_mut().take(end).skip(from) {
            *cell = true;
        }
        row
    }

}

#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize)]
#[serde(rename_all = "lowercase")]
pub enum Harness {
    Claude,
    Codex,
    Gemini,
}

impl Harness {
    fn from_cursor(glyph: char) -> Option<Self> {
        match glyph {
            '❯' => Some(Self::Claude),
            '›' => Some(Self::Codex),
            '●' => Some(Self::Gemini),
            _ => None,
        }
    }

    /// Whether a pane the host knows as `kind` may show this harness's dialog.
    /// A pane of unknown kind may show any of them (Gemini is screen-detected).
    fn fits(self, kind: PromptKind) -> bool {
        match kind {
            PromptKind::Claude => self == Self::Claude,
            PromptKind::Codex => self == Self::Codex,
            PromptKind::Other => true,
        }
    }
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize)]
#[serde(rename_all = "lowercase")]
pub enum Kind {
    /// The agent asks the user something (AskUserQuestion, request_user_input, ask_user).
    Question,
    /// A permission, approval or trust prompt.
    Confirm,
    /// The last tab of a multi-question dialog: review and submit.
    Review,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize)]
#[serde(rename_all = "lowercase")]
pub enum Role {
    Answer,
    /// Free text typed in place ("Type something.", "Enter a custom value").
    Custom,
    /// Claude's "Chat about this": declines the dialog to talk instead.
    Chat,
    /// The unnumbered row that ends a multi-select ("Next", "Done").
    Next,
    Submit,
    Cancel,
}

#[derive(Clone, Debug, PartialEq, Eq, Serialize)]
pub struct Choice {
    pub number: Option<u8>,
    pub label: String,
    pub description: String,
    /// A multi-select box: Some(checked).
    pub checked: Option<bool>,
    pub role: Role,
}

#[derive(Clone, Debug, PartialEq, Eq, Serialize)]
pub struct Tab {
    pub label: String,
    /// None when the harness does not show it (Codex).
    pub answered: Option<bool>,
    /// Claude's "Submit" and Gemini's "Review" tab.
    pub submit: bool,
}

#[derive(Clone, Debug, PartialEq, Eq, Serialize)]
pub struct Dialog {
    pub harness: Harness,
    pub kind: Kind,
    pub tabs: Vec<Tab>,
    pub active_tab: Option<usize>,
    pub question: String,
    /// Context shown with the question: the command to approve, review answers.
    pub detail: Vec<String>,
    pub multi: bool,
    pub choices: Vec<Choice>,
    /// Index into `choices` of the row the agent's cursor is on.
    pub cursor: usize,
    /// Codex's notes for the highlighted option, when its notes editor is open.
    pub note: Option<String>,
}

impl Dialog {
    fn active(&self) -> usize {
        self.active_tab.unwrap_or(0)
    }

    fn find(&self, role: Role) -> Option<usize> {
        self.choices.iter().position(|choice| choice.role == role)
    }

    fn numbered(&self, number: u8) -> Option<usize> {
        self.choices.iter().position(|choice| choice.number == Some(number))
    }
}

const CURSORS: [char; 3] = ['❯', '›', '●'];
/// Key hints drawn under a dialog's list. Not `esc to interrupt`: both agents
/// also print it beside their working spinner, above transcript lines.
const HINTS: [&str; 6] = [
    "enter to select",
    "enter to submit",
    "enter to confirm",
    "esc to cancel",
    "enter continue",
    "enter confirm",
];

/// A row after Gemini's box border is taken off: its characters with their marks.
struct Line {
    text: Vec<char>,
    marked: Vec<bool>,
}

impl Line {
    fn new(row: &Row) -> Self {
        let mut text: Vec<char> = row.text.iter().map(|&c| if c == '\0' { ' ' } else { c }).collect();
        let mut marked = row.marked.clone();
        marked.resize(text.len(), false);
        // Gemini draws its dialogs in a rounded box: `│ … │`.
        let first = text.iter().position(|c| !c.is_whitespace());
        let last = text.iter().rposition(|c| !c.is_whitespace());
        if let (Some(first), Some(last)) = (first, last)
            && first < last
            && text[first] == '│'
            && text[last] == '│'
        {
            text.truncate(last);
            marked.truncate(last);
            text.drain(..=first);
            marked.drain(..=first);
        }
        Self { text, marked }
    }

    fn string(&self) -> String {
        let s: String = self.text.iter().take(MAX_LINE_CHARS).collect();
        s.trim().to_owned()
    }

    fn is_blank(&self) -> bool {
        self.text.iter().all(|c| c.is_whitespace())
    }

    fn indent(&self) -> usize {
        self.text.iter().take_while(|c| c.is_whitespace()).count()
    }

    /// A full rule: Claude's `─` separators, its dotted `╌` frame, Gemini's box edges.
    fn is_rule(&self) -> bool {
        let s = self.string();
        s.chars().count() >= 3
            && s.chars().all(|c| matches!(c, '─' | '╌' | '━' | '╭' | '╮' | '╰' | '╯' | '▄' | '▀'))
    }

    fn is_hint(&self) -> bool {
        let s = self.string().to_lowercase();
        HINTS.iter().any(|hint| s.contains(hint))
    }
}

/// An option row: `❯ 3. Label`, with or without the cursor.
struct OptionRow {
    glyph: Option<char>,
    number: u8,
    rest: String,
}

fn option_row(line: &Line) -> Option<OptionRow> {
    let chars = &line.text;
    let mut at = line.indent();
    let mut glyph = None;
    if let Some(&c) = chars.get(at)
        && CURSORS.contains(&c)
    {
        glyph = Some(c);
        at += 1;
        while chars.get(at).is_some_and(|c| *c == ' ') {
            at += 1;
        }
    }
    let digits: String = chars[at.min(chars.len())..].iter().take_while(|c| c.is_ascii_digit()).collect();
    if digits.is_empty() || digits.len() > 2 {
        return None;
    }
    at += digits.len();
    if chars.get(at) != Some(&'.') || chars.get(at + 1) != Some(&' ') {
        return None;
    }
    let number: u8 = digits.parse().ok()?;
    let rest: String = chars[at + 1..].iter().take(MAX_LINE_CHARS).collect();
    let rest = rest.trim().to_owned();
    if rest.is_empty() {
        return None;
    }
    Some(OptionRow { glyph, number, rest })
}

/// An unnumbered row inside the list: Claude's `Next`, Gemini's `Done`, Codex's notes editor.
struct PlainRow {
    glyph: Option<char>,
    text: String,
}

fn plain_row(line: &Line) -> PlainRow {
    let s = line.string();
    let mut chars = s.chars();
    match chars.next() {
        Some(c) if CURSORS.contains(&c) => PlainRow { glyph: Some(c), text: chars.as_str().trim().to_owned() },
        _ => PlainRow { glyph: None, text: s },
    }
}

/// `[ ] Label` / `[✔] Label` / `[x] Label`.
fn checkbox(label: &str) -> (Option<bool>, String) {
    for (prefix, checked) in [("[ ]", false), ("[✔]", true), ("[x]", true), ("[✓]", true), ("[X]", true)] {
        if let Some(rest) = label.strip_prefix(prefix) {
            return (Some(checked), rest.trim().to_owned());
        }
    }
    (None, label.to_owned())
}

/// The choices block found above a hint row; `top` is the row of option 1.
struct Block {
    top: usize,
    choices: Vec<Choice>,
    cursor: usize,
    glyph: char,
    note: Option<String>,
}

/// Most rows between the hint and the last row of the list (Codex's notes editor sits there).
const HINT_GAP: usize = 4;

fn block(lines: &[Line], hint: usize) -> Option<Block> {
    // Read bottom-up from the hint, so options come out last-first and each
    // option's description rows are met before the option itself.
    let mut items: Vec<(Choice, Option<char>)> = Vec::new();
    let mut pending: Vec<String> = Vec::new();
    let mut note = None;
    let mut expected: Option<u8> = None;
    let mut top = None;
    let mut row = hint;
    while row > 0 {
        row -= 1;
        let line = &lines[row];
        if expected == Some(0) {
            break;
        }
        if line.is_blank() || line.is_rule() {
            continue;
        }
        if items.is_empty() && note.is_none() && pending.is_empty() && hint - row > HINT_GAP {
            return None;
        }
        if let Some(option) = option_row(line) {
            if expected.is_some_and(|next| option.number != next) || option.number == 0 {
                return None;
            }
            pending.reverse();
            let (checked, label) = checkbox(&option.rest);
            let label = label.strip_suffix(" ✓").map(str::to_owned).unwrap_or(label);
            items.push((
                Choice { number: Some(option.number), label, description: pending.join(" "), checked, role: Role::Answer },
                option.glyph,
            ));
            pending.clear();
            expected = Some(option.number - 1);
            top = Some(row);
            continue;
        }
        let plain = plain_row(line);
        let numbered = items.iter().any(|item| item.0.number.is_some());
        // Claude's `Next` and Gemini's `Done` close a multi-select list.
        if matches!(plain.text.as_str(), "Next" | "Done") && !items.iter().any(|item| item.0.role == Role::Next) {
            pending.reverse();
            items.push((
                Choice { number: None, label: plain.text, description: pending.join(" "), checked: None, role: Role::Next },
                plain.glyph,
            ));
            pending.clear();
            continue;
        }
        // Codex's notes editor under the options: `› Add notes` (empty) or `› the notes`.
        if plain.glyph == Some('›') && items.is_empty() && note.is_none() {
            note = Some(if plain.text == "Add notes" { String::new() } else { plain.text });
            continue;
        }
        // A description: indented past the numbers, below its option.
        if plain.glyph.is_none() && line.indent() >= 3 {
            pending.push(plain.text);
            continue;
        }
        // Anything else ends the list; inside it, the rows are not a dialog.
        if numbered {
            break;
        }
        return None;
    }
    // Option 1 must be on screen: a list scrolled past it hides what the user would pick.
    if expected != Some(0) || items.len() < 2 || items.len() > MAX_CHOICES {
        return None;
    }
    let top = top?;
    items.reverse();
    let cursors: Vec<usize> = items.iter().enumerate().filter(|(_, item)| item.1.is_some()).map(|(i, _)| i).collect();
    // Codex also draws `›` on its notes editor; that row is not a choice.
    let [cursor] = cursors[..] else {
        return None;
    };
    let glyph = items[cursor].1?;
    let choices = items.into_iter().map(|item| item.0).collect();
    Some(Block { top, choices, cursor, glyph, note })
}

fn tabs_from(line: &Line) -> Option<(Vec<Tab>, Option<usize>)> {
    let text = &line.text;
    let claude = text.iter().any(|c| matches!(c, '☐' | '☒'));
    let gemini = text.iter().any(|c| matches!(c, '□' | '≡')) || (text.contains(&'│') && text.contains(&'✓'));
    if !claude && !gemini {
        return None;
    }
    let is_mark = |c: char| matches!(c, '☐' | '☒' | '✔' | '□' | '✓' | '≡');
    let mut tabs = Vec::new();
    let mut active = None;
    let mut at = 0;
    while at < text.len() {
        let (answered, submit) = match text[at] {
            '☐' | '□' => (Some(false), false),
            '☒' | '✓' => (Some(true), false),
            '✔' | '≡' => (None, true),
            _ => {
                at += 1;
                continue;
            }
        };
        let start = at;
        at += 1;
        while at < text.len() && !is_mark(text[at]) && !matches!(text[at], '│' | '→') {
            at += 1;
        }
        let label: String = text[start + 1..at].iter().collect();
        // Claude paints ` ☐ Label ` on a background, Gemini underlines the label: either
        // way inside the mark..label span, short of the cell before the next tab's mark.
        let end = at.saturating_sub(1).max(start + 1);
        if line.marked.get(start..end).is_some_and(|cells| cells.iter().any(|&m| m)) {
            active = Some(tabs.len());
        }
        tabs.push(Tab { label: label.trim().to_owned(), answered, submit });
    }
    (!tabs.is_empty()).then_some((tabs, active))
}

/// Codex's `Question 2/3 (1 unanswered)`.
fn codex_progress(line: &Line) -> Option<(Vec<Tab>, Option<usize>)> {
    let s = line.string();
    let rest = s.strip_prefix("Question ")?;
    let (current, rest) = rest.split_once('/')?;
    let total: String = rest.chars().take_while(char::is_ascii_digit).collect();
    let current: usize = current.parse().ok()?;
    let total: usize = total.parse().ok()?;
    if current == 0 || current > total || total > MAX_CHOICES {
        return None;
    }
    let tabs = (1..=total).map(|n| Tab { label: format!("Question {n}"), answered: None, submit: false }).collect();
    Some((tabs, Some(current - 1)))
}

/// `Reason: …?`, `Environment: local`: a labelled context line, never the question.
fn labelled(text: &str) -> bool {
    text.split_once(": ").is_some_and(|(label, _)| {
        !label.is_empty() && label.len() <= 16 && label.chars().all(|c| c.is_alphabetic() || c == ' ')
    })
}

/// The bottom [`SCAN_ROWS`] lines the pane shows now, or None while it is
/// scrolled into history (what is on screen then is not the live dialog).
/// A line the terminal wrapped at the pane's edge comes back as one row, as the
/// agent wrote it: on a narrow pane a long option or hint wraps, and its tail
/// must not read as a row of its own.
pub fn screen_rows<U: EventListener>(grid: &Crosswords<U>) -> Option<Vec<Row>> {
    if grid.display_offset() != 0 {
        return None;
    }
    let lines = grid.screen_lines();
    let columns = grid.columns();
    let mut rows: Vec<Row> = Vec::new();
    let mut continues = false;
    for line in lines.saturating_sub(SCAN_ROWS)..lines {
        let cells = &grid.grid[GridLine(line as i32)];
        if !continues || rows.is_empty() {
            rows.push(Row::default());
        }
        let Some(row) = rows.last_mut() else {
            continue;
        };
        for column in 0..columns {
            let square = &cells[Column(column)];
            // A wide character's second cell holds no glyph of its own.
            if square.is_spacer() {
                continue;
            }
            if row.text.len() >= MAX_LINE_CHARS * 4 {
                break;
            }
            row.text.push(match square.c() {
                '\0' => ' ',
                c => c,
            });
            row.marked.push(grid.grid.style_of(square).renders_on_blank());
        }
        continues = columns > 0 && wraps(cells[Column(columns - 1)].wrapline(), cells[Column(columns - 1)].c());
    }
    Some(rows)
}

/// Whether a row runs on into the next: the terminal marked it wrapped, or (a pane
/// replayed from herdr-core's snapshot keeps no wrap marks) its last cell holds text
/// that is not a rule, so the agent's line went past the edge.
fn wraps(marked: bool, last: char) -> bool {
    marked || !(last.is_whitespace() || last == '\0' || matches!(last, '─' | '╌' | '━' | '▄' | '▀' | '│' | '╮' | '╯'))
}

/// The dialog on screen, if any. `rows` are the pane's rows, top to bottom;
/// only the last [`SCAN_ROWS`] are read.
pub fn parse(rows: &[Row], kind: PromptKind) -> Option<Dialog> {
    let rows = &rows[rows.len().saturating_sub(SCAN_ROWS)..];
    let lines: Vec<Line> = rows.iter().map(Line::new).collect();
    // The bottom-most hint row with a dialog above it.
    for hint in (0..lines.len()).rev() {
        if !lines[hint].is_hint() {
            continue;
        }
        if let Some(dialog) = parse_at(&lines, hint, kind) {
            return Some(dialog);
        }
        if let Some(dialog) = gemini_review(&lines, hint, kind) {
            return Some(dialog);
        }
    }
    None
}

fn parse_at(lines: &[Line], hint: usize, kind: PromptKind) -> Option<Dialog> {
    let Block { top, mut choices, cursor, glyph, note } = block(lines, hint)?;
    let harness = Harness::from_cursor(glyph)?;
    if !harness.fits(kind) {
        return None;
    }
    if harness == Harness::Codex {
        // Codex draws each description in a column on its option's row.
        for choice in &mut choices {
            if let Some(split) = choice.label.find("  ") {
                choice.description = choice.label[split..].trim().to_owned();
                choice.label.truncate(split);
            }
        }
    }
    // Above the list: the question, its context, then the tab row or Codex's progress.
    let mut question = None;
    let mut below = Vec::new();
    let mut above = Vec::new();
    let mut tabs = Vec::new();
    let mut active_tab = None;
    let mut multi = choices.iter().any(|choice| choice.checked.is_some());
    let mut blanks = 0;
    let mut row = top;
    while row > 0 && top - row < 24 {
        row -= 1;
        let line = &lines[row];
        if line.is_blank() {
            blanks += 1;
            if question.is_some() && blanks >= 2 {
                break;
            }
            continue;
        }
        blanks = 0;
        if let Some((found, active)) = tabs_from(line).or_else(|| codex_progress(line)) {
            tabs = found;
            active_tab = active;
            break;
        }
        if line.is_rule() {
            // Claude frames a permission's command in dotted rules; a solid one ends the dialog.
            if line.string().starts_with('╌') {
                continue;
            }
            break;
        }
        let text = line.string();
        // Transcript above the dialog: Codex's `• Running …`, Claude's `⎿` results.
        if question.is_some() && (text.starts_with('•') || text.starts_with('⎿')) {
            break;
        }
        if text == "(Select all that apply)" {
            multi = true;
        } else if question.is_none() && (text.ends_with('?') || text.ends_with(':')) && !labelled(&text) {
            question = Some(text);
        } else if question.is_none() {
            below.push(text);
        } else if above.len() + below.len() < MAX_DETAIL {
            above.push(text);
        } else {
            break;
        }
    }
    above.reverse();
    below.reverse();
    let question = match question {
        Some(question) => question,
        // No question-shaped line: the nearest line above the list asks it.
        None => below.pop()?,
    };
    let detail: Vec<String> = above.into_iter().chain(below).take(MAX_DETAIL).collect();

    assign_roles(harness, &mut choices, !tabs.is_empty() || multi);
    let on_submit_tab = active_tab.and_then(|t| tabs.get(t)).is_some_and(|tab| tab.submit);
    let kind = if on_submit_tab || choices.iter().any(|c| c.role == Role::Submit) {
        Kind::Review
    } else if !tabs.is_empty() || multi || choices.iter().any(|c| matches!(c.role, Role::Custom | Role::Chat)) {
        Kind::Question
    } else {
        Kind::Confirm
    };
    Some(Dialog { harness, kind, tabs, active_tab, question, detail, multi, choices, cursor, note })
}

/// `asking`: the dialog has question tabs or check boxes, so it is a question, not a prompt.
fn assign_roles(harness: Harness, choices: &mut [Choice], asking: bool) {
    for choice in choices.iter_mut() {
        choice.role = match choice.label.as_str() {
            "Chat about this" if harness == Harness::Claude => Role::Chat,
            "Submit answers" => Role::Submit,
            "Cancel" => Role::Cancel,
            _ => choice.role,
        };
    }
    let last_numbered = choices.iter().rposition(|c| c.number.is_some());
    match harness {
        // The free-text row is the last option before "Chat about this" (and
        // a multi-select's Next); its label is whatever was typed into it.
        Harness::Claude => {
            if let Some(chat) = choices.iter().position(|c| c.role == Role::Chat)
                && let Some(custom) = choices[..chat].iter().rposition(|c| c.number.is_some())
                && choices[custom].role == Role::Answer
            {
                choices[custom].role = Role::Custom;
            }
        }
        // AskUserDialog ends its options with the free-text row ("Enter a custom value",
        // or the question's own placeholder). A lone question with its own placeholder
        // reads as an ordinary option; trust and tool prompts have no free-text row.
        Harness::Gemini => {
            if let Some(last) = last_numbered
                && choices[last].role == Role::Answer
                && (asking || choices[last].label == "Enter a custom value")
            {
                choices[last].role = Role::Custom;
            }
        }
        Harness::Codex => {}
    }
}

/// Gemini's Review tab has no option list: `Review your answers:` and `Enter to submit`.
fn gemini_review(lines: &[Line], hint: usize, kind: PromptKind) -> Option<Dialog> {
    if !Harness::Gemini.fits(kind) || !lines[hint].string().to_lowercase().contains("enter to submit") {
        return None;
    }
    let mut detail = Vec::new();
    let mut row = hint;
    while row > 0 && hint - row < 24 {
        row -= 1;
        let line = &lines[row];
        if line.is_blank() {
            continue;
        }
        if let Some((tabs, active)) = tabs_from(line) {
            if !active.and_then(|t| tabs.get(t)).is_some_and(|tab| tab.submit) {
                return None;
            }
            detail.reverse();
            let question = if detail.first().is_some_and(|d: &String| d.starts_with("Review your answers")) {
                detail.remove(0)
            } else {
                "Review your answers".to_owned()
            };
            return Some(Dialog {
                harness: Harness::Gemini,
                kind: Kind::Review,
                tabs,
                active_tab: active,
                question,
                detail: detail.into_iter().take(MAX_DETAIL).collect(),
                multi: false,
                choices: Vec::new(),
                cursor: 0,
                note: None,
            });
        }
        if detail.len() >= MAX_DETAIL * 2 {
            return None;
        }
        detail.push(line.string());
    }
    None
}

/// What the user chose for one question on the phone.
#[derive(Clone, Debug, Default, PartialEq, Eq, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct Answer {
    /// Tab index the question is on (0 for a single question).
    #[serde(default)]
    pub tab: usize,
    /// The question as the phone showed it: the step stops if the screen asks something else.
    pub question: String,
    /// Option numbers picked: one for single-select, any for multi-select.
    #[serde(default)]
    pub choose: Vec<u8>,
    /// Free text for the custom row ("Type something.").
    #[serde(default)]
    pub text: Option<String>,
    /// Codex's notes on the picked option.
    #[serde(default)]
    pub note: Option<String>,
}

#[derive(Clone, Copy, Debug, Default, PartialEq, Eq, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum Finish {
    /// Answer every question, then submit (Claude's Submit tab, Gemini's Review, Codex's last Enter).
    #[default]
    Submit,
    /// Claude's "Chat about this".
    Chat,
    /// Esc: dismiss the dialog without answering.
    Cancel,
}

#[derive(Clone, Debug, Default, PartialEq, Eq, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct Goal {
    #[serde(default)]
    pub answers: Vec<Answer>,
    #[serde(default)]
    pub finish: Finish,
}

/// How far a submission got. The host keeps it between steps and passes it back.
#[derive(Clone, Debug, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct Progress {
    /// Answers already committed.
    #[serde(default)]
    pub next: usize,
    /// Backspaces still to send to erase the current answer's old text, once
    /// erasing began. Counted from the text as first seen: a custom placeholder
    /// would otherwise never read as empty.
    #[serde(default)]
    pub erase_left: Option<usize>,
    /// The current answer's text was typed (typed once: a narrow row may cut it off).
    #[serde(default)]
    pub typed: bool,
    /// The key that submits or dismisses the dialog was sent.
    #[serde(default)]
    pub finished: bool,
    #[serde(default)]
    pub steps: u32,
    /// The screen when keys were last sent ([`fingerprint`]), until it changes.
    #[serde(default)]
    pub sent_on: Option<u64>,
    /// Steps spent waiting for that screen to change.
    #[serde(default)]
    pub waited: u32,
}

#[derive(Clone, Debug, PartialEq, Eq, Serialize)]
#[serde(rename_all = "lowercase")]
pub enum Step {
    /// Press these keys (names the input command accepts), then parse again.
    Keys(Vec<&'static str>),
    /// Type this text, then parse again.
    Text(String),
    /// The screen is between states: parse again after the next change.
    Wait,
    /// The dialog is answered.
    Done,
    /// Stop; the user finishes on the terminal.
    Fail(Failure),
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize)]
#[serde(rename_all = "snake_case")]
pub enum Failure {
    /// The dialog on screen asks something other than what the user answered.
    QuestionChanged,
    /// An answer names an option the dialog does not have.
    NoSuchOption,
    /// This harness cannot do what was asked (Chat on Codex, notes on Claude).
    Unsupported,
    /// Too many steps without finishing.
    TooManySteps,
    /// The tab to answer is not on screen.
    NoSuchTab,
}

/// Most keys in one input command; a longer move takes several steps.
const KEY_BATCH: usize = 16;

fn moves(from: usize, to: usize) -> Vec<&'static str> {
    if to >= from { vec!["down"; (to - from).min(KEY_BATCH)] } else { vec!["up"; (from - to).min(KEY_BATCH)] }
}

fn tab_moves(from: usize, to: usize) -> Vec<&'static str> {
    if to >= from { vec!["right"; (to - from).min(KEY_BATCH)] } else { vec!["left"; (from - to).min(KEY_BATCH)] }
}

/// The next batch of backspaces erasing `current`, or None once it is erased.
fn erase(current: &str, progress: &mut Progress) -> Option<Step> {
    let left = progress.erase_left.get_or_insert_with(|| current.chars().count().min(MAX_ERASE));
    if *left == 0 {
        return None;
    }
    let batch = (*left).min(KEY_BATCH);
    *left -= batch;
    Some(Step::Keys(vec!["backspace"; batch]))
}

/// The next keys toward `goal` from `dialog` (None: no dialog on screen now).
/// Every step is chosen from the screen as it is, so a key the agent dropped
/// or a redraw in between costs a step, never a wrong answer.
pub fn step(dialog: Option<&Dialog>, goal: &Goal, progress: &mut Progress) -> Step {
    progress.steps += 1;
    if progress.steps > MAX_STEPS {
        return Step::Fail(Failure::TooManySteps);
    }
    let Some(dialog) = dialog else {
        // Gone after the final key: answered. Gone before it: a redraw.
        return if progress.finished { Step::Done } else { Step::Wait };
    };
    if progress.finished {
        return Step::Wait;
    }
    match goal.finish {
        Finish::Cancel => {
            progress.finished = true;
            return Step::Keys(vec!["esc"]);
        }
        Finish::Chat => {
            return match dialog.find(Role::Chat) {
                Some(chat) => press(dialog, chat, progress, true),
                None => Step::Fail(Failure::Unsupported),
            };
        }
        Finish::Submit => {}
    }
    if let Some(answer) = goal.answers.get(progress.next) {
        let last = progress.next + 1 == goal.answers.len();
        return answer_step(dialog, answer, progress, last);
    }
    // Every answer is in. A dialog without a review tab submitted on the last
    // one; a review tab submits here.
    if dialog.kind == Kind::Review {
        return match dialog.find(Role::Submit) {
            Some(submit) => press(dialog, submit, progress, true),
            // Gemini's Review tab has no list: Enter submits.
            None => {
                progress.finished = true;
                Step::Keys(vec!["enter"])
            }
        };
    }
    match dialog.tabs.iter().position(|tab| tab.submit) {
        Some(submit) => Step::Keys(tab_moves(dialog.active(), submit)),
        None => Step::Fail(Failure::NoSuchTab),
    }
}

/// Moves the cursor to `index`, then presses Enter there.
fn press(dialog: &Dialog, index: usize, progress: &mut Progress, finishes: bool) -> Step {
    if dialog.cursor != index {
        return Step::Keys(moves(dialog.cursor, index));
    }
    progress.finished |= finishes;
    Step::Keys(vec!["enter"])
}

fn answer_step(dialog: &Dialog, answer: &Answer, progress: &mut Progress, last: bool) -> Step {
    if dialog.tabs.len() > 1 && dialog.active() != answer.tab {
        if answer.tab >= dialog.tabs.len() {
            return Step::Fail(Failure::NoSuchTab);
        }
        return Step::Keys(tab_moves(dialog.active(), answer.tab));
    }
    if dialog.kind == Kind::Review || dialog.question != answer.question {
        return Step::Fail(Failure::QuestionChanged);
    }
    if answer.note.is_some() && dialog.harness != Harness::Codex {
        return Step::Fail(Failure::Unsupported);
    }
    // Typed text never carries a key: a line break or escape would press Enter or Esc
    // in the agent's answer row (the host sends one line).
    if [&answer.text, &answer.note].into_iter().flatten().any(|text| text.chars().any(char::is_control)) {
        return Step::Fail(Failure::Unsupported);
    }
    // With no review tab, committing the last answer submits the dialog.
    let submits = last && !dialog.tabs.iter().any(|tab| tab.submit);
    if dialog.multi {
        return multi_step(dialog, answer, progress, submits);
    }
    let target = match (&answer.text, answer.choose.as_slice()) {
        (Some(_), []) => dialog.find(Role::Custom),
        (None, [number]) => dialog.numbered(*number).filter(|&i| dialog.choices[i].role == Role::Answer),
        _ => None,
    };
    let Some(target) = target else {
        return Step::Fail(Failure::NoSuchOption);
    };
    if dialog.cursor != target {
        return Step::Keys(moves(dialog.cursor, target));
    }
    if let Some(text) = &answer.text
        && let Some(step) = type_custom(dialog, target, text, progress)
    {
        return step;
    }
    if let Some(note) = &answer.note
        && let Some(step) = type_note(dialog, note, progress)
    {
        return step;
    }
    commit(progress, submits)
}

/// Gemini's `All of the above` checks every box; it is pressed only when picked.
const ALL_OF_THE_ABOVE: &str = "All of the above";

fn multi_step(dialog: &Dialog, answer: &Answer, progress: &mut Progress, submits: bool) -> Step {
    let picked = |number: Option<u8>| number.is_some_and(|n| answer.choose.contains(&n));
    for &number in &answer.choose {
        if dialog.numbered(number).is_none_or(|i| dialog.choices[i].role != Role::Answer) {
            return Step::Fail(Failure::NoSuchOption);
        }
    }
    for (index, choice) in dialog.choices.iter().enumerate() {
        if choice.role != Role::Answer || (choice.label == ALL_OF_THE_ABOVE && !picked(choice.number)) {
            continue;
        }
        if choice.checked.is_some_and(|checked| checked != picked(choice.number)) {
            return press(dialog, index, progress, false);
        }
    }
    if let Some(custom) = dialog.find(Role::Custom) {
        let checked = dialog.choices[custom].checked == Some(true);
        match &answer.text {
            // Already there, ticked: nothing to type.
            Some(text) if checked && dialog.choices[custom].label == *text => {}
            Some(text) => {
                if !progress.typed && dialog.cursor != custom {
                    return Step::Keys(moves(dialog.cursor, custom));
                }
                if let Some(step) = type_custom(dialog, custom, text, progress) {
                    return step;
                }
                if !checked {
                    return press(dialog, custom, progress, false);
                }
            }
            // Gemini submits the question on Enter in its custom row instead of unticking it.
            None if checked && dialog.harness == Harness::Gemini => return Step::Fail(Failure::Unsupported),
            None if checked => return press(dialog, custom, progress, false),
            None => {}
        }
    }
    match dialog.find(Role::Next) {
        Some(next) if dialog.cursor != next => Step::Keys(moves(dialog.cursor, next)),
        Some(_) => commit(progress, submits),
        None => Step::Fail(Failure::Unsupported),
    }
}

fn commit(progress: &mut Progress, submits: bool) -> Step {
    progress.next += 1;
    progress.typed = false;
    progress.erase_left = None;
    progress.finished |= submits;
    Step::Keys(vec!["enter"])
}

/// What a custom row shows before anything is typed.
fn placeholder(label: &str) -> bool {
    matches!(label, "Type something." | "Type something" | "Enter a custom value" | "")
}

/// Types the custom answer once, first erasing what the row held unless it reads as empty.
fn type_custom(dialog: &Dialog, index: usize, text: &str, progress: &mut Progress) -> Option<Step> {
    if progress.typed {
        return None;
    }
    let label = &dialog.choices[index].label;
    if label == text {
        progress.typed = true;
        return None;
    }
    if (!placeholder(label) || progress.erase_left.is_some())
        && let Some(step) = erase(label, progress)
    {
        return Some(step);
    }
    progress.typed = true;
    Some(Step::Text(text.to_owned()))
}

/// Opens Codex's notes for the highlighted option and types them once.
fn type_note(dialog: &Dialog, note: &str, progress: &mut Progress) -> Option<Step> {
    if progress.typed {
        return None;
    }
    let current = match &dialog.note {
        None => return Some(Step::Keys(vec!["tab"])),
        Some(current) => current,
    };
    if current == note {
        progress.typed = true;
        return None;
    }
    if (!current.is_empty() || progress.erase_left.is_some())
        && let Some(step) = erase(current, progress)
    {
        return Some(step);
    }
    progress.typed = true;
    Some(Step::Text(note.to_owned()))
}

/// The pane kind as the host names it (`GhostText`'s kinds).
pub fn prompt_kind(name: &str) -> crate::Result<PromptKind> {
    match name {
        "claude" => Ok(PromptKind::Claude),
        "codex" => Ok(PromptKind::Codex),
        // Gemini has no prompt kind yet (factory-setup.36 adds one): read from the screen alone.
        "gemini" | "other" => Ok(PromptKind::Other),
        _ => Err(crate::Error::InvalidCommand),
    }
}

/// Most bytes of goal or progress JSON the host may pass.
pub const MAX_GOAL_JSON: usize = 64 * 1024;

/// The dialog on `rows` as JSON for the host, or None.
pub fn dialog_json(rows: &[Row], kind: PromptKind) -> crate::Result<Option<String>> {
    parse(rows, kind).map(|dialog| serde_json::to_string(&dialog)).transpose().map_err(Into::into)
}

#[derive(Serialize)]
struct StepReply<'a> {
    step: &'a Step,
    progress: &'a Progress,
    dialog: Option<&'a Dialog>,
}

/// Steps a sent key may take to show on screen before the next step reads it anyway
/// (a key that changes nothing, such as Backspace on an empty field).
pub const MAX_WAIT_STEPS: u32 = 12;

/// Fingerprints stay below 2^53: the host's JSON keeps progress numbers as
/// doubles past `Long`, and a rounded fingerprint would never match again.
const FINGERPRINT_MASK: u64 = (1 << 53) - 1;

/// The rows' text and marks, to tell when the agent has redrawn after a key.
pub fn fingerprint(rows: &[Row]) -> u64 {
    use std::hash::{Hash, Hasher};
    let mut hasher = std::collections::hash_map::DefaultHasher::new();
    rows.hash(&mut hasher);
    hasher.finish() & FINGERPRINT_MASK
}

/// [`step`] that first waits for the screen to change after the last keys,
/// so the next keys are never chosen from a frame drawn before them.
pub fn step_after_redraw(rows: Option<&[Row]>, kind: PromptKind, goal: &Goal, progress: &mut Progress) -> (Step, Option<Dialog>) {
    let screen = rows.map(fingerprint);
    if progress.sent_on.is_some() && progress.sent_on == screen && progress.waited < MAX_WAIT_STEPS {
        progress.waited += 1;
        return (Step::Wait, None);
    }
    progress.sent_on = None;
    progress.waited = 0;
    let dialog = rows.and_then(|rows| parse(rows, kind));
    let step = step(dialog.as_ref(), goal, progress);
    if matches!(step, Step::Keys(_) | Step::Text(_)) {
        progress.sent_on = screen;
    }
    (step, dialog)
}

/// One [`step`] across the JNI boundary: `{"step":…, "progress":…, "dialog":…}`.
/// An empty `progress` starts a submission.
pub fn step_json(rows: Option<&[Row]>, kind: PromptKind, goal: &str, progress: &str) -> crate::Result<String> {
    if goal.len() > MAX_GOAL_JSON || progress.len() > MAX_GOAL_JSON {
        return Err(crate::Error::InvalidCommand);
    }
    let goal: Goal = serde_json::from_str(goal)?;
    let mut progress: Progress = if progress.is_empty() { Progress::default() } else { serde_json::from_str(progress)? };
    let (step, dialog) = step_after_redraw(rows, kind, &goal, &mut progress);
    Ok(serde_json::to_string(&StepReply { step: &step, progress: &progress, dialog: dialog.as_ref() })?)
}

#[cfg(test)]
#[path = "elicitation_tests.rs"]
mod tests;
