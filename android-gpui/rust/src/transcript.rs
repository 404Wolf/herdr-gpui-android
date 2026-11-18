//! A full-screen program's history, kept on the phone (item 77, ask 2:
//! scrolling Claude is local and fast, also while offline).
//!
//! Claude Code runs full screen (the alternate screen): it keeps its
//! conversation to itself, the core replays no history for it, and the
//! phone's grid only ever holds one screen. Scrolling it used to send wheel
//! input to Claude and wait for its redraw over the link; offline there was
//! nothing to scroll. So the phone keeps the rows itself:
//!
//! - **Live capture.** Each new screen is compared with the last one. When the
//!   conversation moved up by `d` rows, the `d` rows that left the top are
//!   appended to the history, with their colours (row alignment by rows that
//!   appear once on both screens). A move down (the program's own view was
//!   scrolled back, or its prompt shrank) is remembered so the same rows are
//!   not added twice when it moves up again.
//! - **A seed from the core.** `terminal.read` reads a full-screen app's
//!   history by scrolling it (the core's stitched read), as plain text. It
//!   fills what live capture never saw (before the attach, or a gap when the
//!   screen changed too much at once); rows captured live replace the plain
//!   copy where both have them.
//!
//! The phone scrolls this history itself ([`Transcript::reader_rows`]) and
//! saves it, so nothing waits on the link.
use rio_vt::crosswords::Crosswords;
use rio_vt::crosswords::pos::{Column, Line, Pos};
use rio_vt::crosswords::square::Wide;
use rio_vt::event::EventListener;
use std::collections::{HashMap, VecDeque};

/// The most one pane's history may take (Wolf's budget: 1 MiB a pane); the
/// oldest rows go first.
pub(crate) const MAX_BYTES: usize = 1024 * 1024;
/// A row counts as evidence of where the screen moved only with this many
/// characters that are not spaces: short rows ("", "│", "  ⎿") repeat everywhere.
const MIN_ANCHOR_CHARS: usize = 3;
/// At least this many such rows must agree on a move before it is trusted.
const MIN_VOTES: usize = 2;

/// One screen row: its text (trailing blanks trimmed), what it is compared
/// by, and the bytes that draw it again with its colours.
#[derive(Clone, Debug, PartialEq, Eq)]
pub(crate) struct Row {
    pub text: String,
    pub vt: String,
}

impl Row {
    /// A row the core sent as text (a seed): drawn in the default colours.
    pub(crate) fn plain(text: &str) -> Self {
        let text: String = text.chars().filter(|c| !c.is_control()).collect();
        let text = text.trim_end().to_owned();
        Self { vt: text.clone(), text }
    }

    fn bytes(&self) -> usize {
        self.vt.len() + self.text.len() + 2
    }
}

/// The screen rows of `grid`'s active screen (the alternate one for a
/// full-screen program), whatever its display offset.
pub(crate) fn screen_rows<U: EventListener>(grid: &Crosswords<U>) -> Vec<Row> {
    let cols = grid.columns();
    (0..grid.screen_lines() as i32)
        .map(|line| Row { text: row_text(grid, line, cols), vt: crate::snapshot::row(grid, line, cols) })
        .collect()
}

fn row_text<U: EventListener>(grid: &Crosswords<U>, line: i32, cols: usize) -> String {
    let mut text = String::new();
    for column in 0..cols {
        let pos = Pos::new(Line(line), Column(column));
        if grid.grid[pos].wide() == Wide::Spacer {
            continue;
        }
        text.extend(grid.grid.cell_text(pos).map(|c| if c == '\0' { ' ' } else { c }));
    }
    text.truncate(text.trim_end().len());
    text
}

/// How the screen moved from one look to the next.
#[derive(Debug, PartialEq, Eq)]
pub(crate) enum Moved {
    /// Nothing moved (maybe a spinner or the prompt changed in place).
    Still,
    /// The rows moved up by this many: that many left the top.
    Up(usize),
    /// The rows moved down by this many (the view was scrolled back).
    Down(usize),
    /// Too much changed to tell: rows may have been missed.
    Lost,
}

fn anchor(text: &str) -> bool {
    text.chars().filter(|c| !c.is_whitespace()).count() >= MIN_ANCHOR_CHARS
}

/// How often each row that can vote appears.
fn count<'a>(rows: &[&'a str]) -> HashMap<&'a str, usize> {
    let mut n: HashMap<&'a str, usize> = HashMap::new();
    for row in rows.iter().filter(|r| anchor(r)) {
        *n.entry(*row).or_default() += 1;
    }
    n
}

/// Where the rows of `a` are in `b`: `d` with `a[i] == b[i - d]`, voted by
/// the rows that appear exactly once in each. `None` when no row votes;
/// otherwise each distance and its votes.
fn votes(a: &[&str], b: &[&str]) -> HashMap<i64, usize> {
    let (in_a, in_b) = (count(a), count(b));
    let at_b: HashMap<&str, usize> =
        b.iter().enumerate().filter(|(_, r)| in_b.get(**r) == Some(&1)).map(|(i, r)| (*r, i)).collect();
    let mut votes: HashMap<i64, usize> = HashMap::new();
    for (i, row) in a.iter().enumerate() {
        if in_a.get(row) == Some(&1)
            && let Some(j) = at_b.get(row)
        {
            *votes.entry(i as i64 - *j as i64).or_default() += 1;
        }
    }
    votes
}

/// The one distance most rows agree on, other than `skip` (a fixed region
/// such as Claude's prompt and status rows votes 0): it needs MIN_VOTES and
/// more votes than every other moving distance together.
fn winner(votes: &HashMap<i64, usize>, skip: Option<i64>) -> Option<Option<i64>> {
    let moving: Vec<(i64, usize)> = votes.iter().filter(|(d, _)| Some(**d) != skip).map(|(d, n)| (*d, *n)).collect();
    let Some(&(best, n)) = moving.iter().max_by_key(|(d, n)| (*n, -d.abs())) else {
        return Some(None);
    };
    let others: usize = moving.iter().filter(|(d, _)| *d != best).map(|(_, n)| n).sum();
    (n >= MIN_VOTES && n > others).then_some(Some(best))
}

/// How `next` moved from `previous` (two screens of the same program).
pub(crate) fn moved(previous: &[&str], next: &[&str]) -> Moved {
    if previous == next {
        return Moved::Still;
    }
    let votes = votes(previous, next);
    match winner(&votes, Some(0)) {
        None => Moved::Lost,
        Some(Some(d)) if d > 0 => Moved::Up(d as usize),
        Some(Some(d)) => Moved::Down(d.unsigned_abs() as usize),
        Some(None) => {
            // Nothing moved by the rows' votes; but if most of the rows that
            // stood out are gone, the screen was replaced (more output at once
            // than it holds, or a redraw): rows may have been missed.
            let stood_out = previous.iter().filter(|r| anchor(r)).count();
            let next_rows: std::collections::HashSet<&str> = next.iter().copied().collect();
            let gone = previous.iter().filter(|r| anchor(r) && !next_rows.contains(*r)).count();
            if gone >= 4 && gone * 2 > stood_out { Moved::Lost } else { Moved::Still }
        }
    }
}

/// One full-screen pane's history on the phone.
#[derive(Debug, Default)]
pub(crate) struct Transcript {
    /// Oldest first: rows above the screen.
    history: VecDeque<Row>,
    bytes: usize,
    /// The screen last looked at, which the next one is aligned with.
    screen: Vec<Row>,
    /// Rows the program's own view sits below its newest (it moved down
    /// that far since): the next move up gives these rows back first, and
    /// the newest `back` history rows are on the screen right now.
    back: usize,
    /// Rows before this index may not join the rows after it (something was
    /// missed): a seed replaces them.
    gap: Option<usize>,
    /// The history reaches the program's first row (a complete seed, never trimmed since).
    complete: bool,
    /// A seed was merged since the pane was first seen.
    seeded: bool,
    /// The screen's width when last looked at: a resize redraws everything.
    cols: usize,
}

impl Transcript {
    /// A transcript read back from a saved copy: `rows` is its history then its screen.
    pub(crate) fn restored(mut rows: Vec<Row>, screen: usize, cols: usize, complete: bool) -> Self {
        let screen = rows.split_off(rows.len().saturating_sub(screen));
        let mut out = Self { screen, cols, complete, ..Self::default() };
        for row in rows {
            out.push(row);
        }
        out
    }

    pub(crate) fn rows(&self) -> usize {
        self.history.len()
    }

    /// Whether a seed is still owed: never seeded, or rows were missed since.
    pub(crate) fn wants_seed(&self) -> bool {
        !self.seeded || self.gap.is_some()
    }

    pub(crate) fn complete(&self) -> bool {
        self.complete && self.gap.is_none()
    }

    fn push(&mut self, row: Row) {
        self.bytes += row.bytes();
        self.history.push_back(row);
        while self.bytes > MAX_BYTES {
            let Some(old) = self.history.pop_front() else { break };
            self.bytes -= old.bytes();
            self.complete = false;
            self.gap = self.gap.and_then(|at| at.checked_sub(1)).filter(|at| *at > 0);
        }
    }

    fn lose(&mut self) {
        self.gap = Some(self.history.len());
        self.back = 0;
    }

    /// A new look at the program's screen; returns how it moved.
    pub(crate) fn observe(&mut self, screen: Vec<Row>, cols: usize) -> Moved {
        if self.screen.is_empty() || cols != self.cols {
            // The first look, or a resize: the program redraws at the new width.
            let first = self.screen.is_empty() && self.history.is_empty();
            self.cols = cols;
            self.screen = screen;
            if !first {
                self.lose();
                return Moved::Lost;
            }
            return Moved::Still;
        }
        let previous: Vec<&str> = self.screen.iter().map(|r| r.text.as_str()).collect();
        let next: Vec<&str> = screen.iter().map(|r| r.text.as_str()).collect();
        let how = moved(&previous, &next);
        match how {
            Moved::Up(d) => {
                // A header that stayed put (the same row at the same place above
                // the moved rows) is not part of the history.
                let header = previous.iter().zip(&next).take_while(|(a, b)| a == b && anchor(a)).count();
                let again = d.min(self.back);
                self.back -= again;
                let end = (header + d).min(self.screen.len());
                let left: Vec<Row> = self.screen[(header + again).min(end)..end].to_vec();
                for row in left {
                    self.push(row);
                }
            }
            Moved::Down(d) => self.back = (self.back + d).min(self.history.len()),
            Moved::Lost => self.lose(),
            Moved::Still => {}
        }
        self.screen = screen;
        how
    }

    /// Merges the core's stitched read (`text`: history then screen, plain;
    /// `complete`: it reached the top). Rows captured live are kept where
    /// both have them; the core's copy fills what is older or was missed.
    /// False when the read could not be placed against this history and screen.
    pub(crate) fn seed(&mut self, text: &str, complete: bool) -> bool {
        let seed: Vec<Row> = text.split('\n').map(Row::plain).collect();
        // Only what is known to be continuous joins the seed.
        let from = self.gap.unwrap_or(0).min(self.history.len());
        let ours: Vec<&str> = self
            .history
            .iter()
            .skip(from)
            .take(self.history.len() - from - self.back.min(self.history.len() - from))
            .chain(self.screen.iter())
            .map(|r| r.text.as_str())
            .collect();
        let theirs: Vec<&str> = seed.iter().map(|r| r.text.as_str()).collect();
        // seed[i] == ours[i - d]: ours starts at seed row d.
        let Some(Some(d)) = winner(&votes(&theirs, &ours), None) else {
            return false;
        };
        let kept: Vec<Row> = self.history.drain(from..).collect();
        let older: Vec<Row> = self.history.drain(..).collect();
        self.bytes = 0;
        self.gap = None;
        if d > 0 {
            // The core holds rows older than ours: they lead.
            for row in seed.into_iter().take(d as usize) {
                self.push(row);
            }
            self.complete = complete;
        } else {
            // Ours reach further back than the core's read: keep them all.
            for row in older {
                self.push(row);
            }
        }
        for row in kept {
            self.push(row);
        }
        self.seeded = true;
        true
    }

    /// What the phone scrolls through: the history, then `screen` (the
    /// program's screen now), each row once.
    pub(crate) fn reader_rows<'a>(&'a self, screen: &'a [Row]) -> impl Iterator<Item = &'a Row> {
        let shown = self.history.len() - self.back.min(self.history.len());
        self.history.iter().take(shown).chain(screen.iter())
    }

    /// The screen last looked at.
    #[cfg(test)]
    pub(crate) fn screen(&self) -> &[Row] {
        &self.screen
    }
}

#[cfg(test)]
mod tests {
    #![allow(clippy::unwrap_used)]
    use super::*;

    /// A Claude-like full screen: `transcript` rows of the conversation
    /// ending at `newest`, then a fixed prompt and status area.
    fn claude(newest: usize, transcript: usize, status: &str) -> Vec<Row> {
        let first = (newest + 1).saturating_sub(transcript);
        let mut rows: Vec<Row> = (first..=newest).map(|n| Row::plain(&line(n))).collect();
        while rows.len() < transcript {
            rows.insert(0, Row::plain(""));
        }
        for footer in ["────────────────────────────", "❯ ", "────────────────────────────", status] {
            rows.push(Row::plain(footer));
        }
        rows
    }

    fn line(n: usize) -> String {
        if n.is_multiple_of(5) { String::new() } else { format!("● conversation line {n}") }
    }

    fn texts<'a>(rows: impl Iterator<Item = &'a Row>) -> Vec<String> {
        rows.map(|r| r.text.clone()).collect()
    }

    fn expected(newest: usize, transcript: usize, status: &str) -> Vec<String> {
        let mut rows: Vec<String> = (0..=newest).map(line).collect();
        rows.extend(texts(claude(newest, transcript, status)[transcript..].iter()));
        // Rows that were never on any screen are not expected.
        rows
    }

    #[test]
    fn ask2_rows_leaving_the_top_of_claudes_full_screen_are_kept_in_order_once() {
        let mut t = Transcript::default();
        let mut newest = 29;
        t.observe(claude(newest, 30, "· working"), 80);
        // Claude writes 1 to 7 rows at a time while its spinner changes.
        for (step, more) in [1usize, 3, 7, 2, 1, 5, 4].into_iter().enumerate() {
            newest += more;
            assert_eq!(t.observe(claude(newest, 30, &format!("· working {step}")), 80), Moved::Up(more));
            // A spinner tick in place moves nothing.
            assert_eq!(t.observe(claude(newest, 30, &format!("✻ working {step}")), 80), Moved::Still);
        }
        let screen = claude(newest, 30, "done");
        t.observe(screen.clone(), 80);
        let all = texts(t.reader_rows(&screen));
        assert_eq!(all, expected(newest, 30, "done"));
        assert_eq!(t.rows(), newest + 1 - 30);
    }

    #[test]
    fn ask2_a_view_scrolled_back_and_forth_elsewhere_adds_no_row_twice() {
        let mut t = Transcript::default();
        t.observe(claude(59, 30, "done"), 80);
        t.observe(claude(69, 30, "done"), 80);
        // The desktop scrolls Claude back 6, then forward 6, then output comes.
        assert_eq!(t.observe(claude(63, 30, "done"), 80), Moved::Down(6));
        assert_eq!(t.observe(claude(69, 30, "done"), 80), Moved::Up(6));
        t.observe(claude(72, 30, "done"), 80);
        let screen = claude(72, 30, "done");
        let all = texts(t.reader_rows(&screen));
        let mut want: Vec<String> = (30..=72).map(line).collect();
        want.extend(texts(screen[30..].iter()));
        assert_eq!(all, want);
    }

    #[test]
    fn ask2_while_scrolled_back_elsewhere_the_reader_shows_each_row_once() {
        let mut t = Transcript::default();
        t.observe(claude(59, 30, "done"), 80);
        t.observe(claude(69, 30, "done"), 80);
        t.observe(claude(65, 30, "done"), 80);
        let screen = claude(65, 30, "done");
        let all = texts(t.reader_rows(&screen));
        let mut want: Vec<String> = (30..=65).map(line).collect();
        want.extend(texts(screen[30..].iter()));
        assert_eq!(all, want);
    }

    #[test]
    fn ask2_too_much_output_at_once_is_a_gap_and_a_seed_fills_it() {
        let mut t = Transcript::default();
        t.observe(claude(29, 30, "· working"), 80);
        t.observe(claude(31, 30, "· working"), 80);
        // 80 rows at once: nothing on the new screen was on the old one.
        assert_eq!(t.observe(claude(111, 30, "· working"), 80), Moved::Lost);
        assert!(t.wants_seed());
        t.observe(claude(113, 30, "done"), 80);
        // The core's stitched read of everything (history then screen).
        let mut text: Vec<String> = (0..=113).map(line).collect();
        text.extend(texts(claude(113, 30, "done")[30..].iter()));
        assert!(t.seed(&text.join("\n"), true));
        assert!(!t.wants_seed() && t.complete());
        let screen = claude(113, 30, "done");
        assert_eq!(texts(t.reader_rows(&screen)), expected(113, 30, "done"));
    }

    #[test]
    fn ask2_a_seed_keeps_the_rows_captured_live_with_their_colours() {
        // Live rows carry colours (their bytes differ from the plain text).
        let coloured = |newest| {
            let mut rows = claude(newest, 30, "· working");
            for row in &mut rows {
                row.vt = format!("\x1b[32m{}\x1b[0m", row.text);
            }
            rows
        };
        let mut t = Transcript::default();
        // Attached late: the first screen starts at row 30.
        t.observe(coloured(59), 80);
        t.observe(coloured(63), 80);
        let kept: Vec<String> = t.history.iter().map(|r| r.vt.clone()).collect();
        assert_eq!(kept.len(), 4);
        let mut text: Vec<String> = (0..=63).map(line).collect();
        text.extend(texts(claude(63, 30, "done")[30..].iter()));
        assert!(t.seed(&text.join("\n"), false));
        assert_eq!(t.rows(), 34);
        let screen = claude(63, 30, "done");
        assert_eq!(texts(t.reader_rows(&screen)), expected(63, 30, "done"));
        // The four rows captured live are the coloured ones, after the plain seed.
        let now: Vec<String> = t.history.iter().map(|r| r.vt.clone()).collect();
        assert_eq!(now[now.len() - 4..], kept[..]);
        assert!(!now[0].contains('\x1b'));
    }

    #[test]
    fn ask2_a_seed_that_cannot_be_placed_changes_nothing() {
        let mut t = Transcript::default();
        t.observe(claude(29, 30, "done"), 80);
        assert!(!t.seed("something else\nentirely different rows\nnothing alike", true));
        assert!(t.wants_seed());
        assert_eq!(t.rows(), 0);
    }

    #[test]
    fn ask2_a_resize_is_a_gap_never_a_false_move() {
        let mut t = Transcript::default();
        t.observe(claude(29, 30, "done"), 80);
        assert_eq!(t.observe(claude(29, 30, "done"), 120), Moved::Lost);
        assert!(t.wants_seed());
    }

    #[test]
    fn ask2_the_history_is_bounded_and_the_oldest_rows_go() {
        let mut t = Transcript::default();
        let big = "x".repeat(4000);
        for n in 0..400 {
            t.push(Row::plain(&format!("{n} {big}")));
        }
        assert!(t.bytes <= MAX_BYTES);
        assert!(t.history.back().unwrap().text.starts_with("399 "));
        assert!(!t.history.front().unwrap().text.starts_with("0 "));
    }

    #[test]
    fn rows_that_repeat_never_prove_a_move() {
        // Only blank and short rows changed: nothing to align by.
        let a = vec!["", "│", "", "  ⎿", "❯"];
        let b = vec!["│", "", "  ⎿", "", "❯"];
        assert_eq!(moved(&a, &b), Moved::Still);
    }
}
