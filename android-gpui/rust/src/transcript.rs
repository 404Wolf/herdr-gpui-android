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
/// Drawn in the reader (and in a saved copy) where rows were never seen: the
/// screen changed by more than it holds between two looks, and no read from
/// the core filled the gap yet. Read back from a saved copy as the gap again.
pub(crate) const GAP_TEXT: &str = "· rows not seen ·";

fn gap_row() -> &'static Row {
    static ROW: std::sync::OnceLock<Row> = std::sync::OnceLock::new();
    ROW.get_or_init(|| Row { text: GAP_TEXT.to_owned(), vt: format!("\x1b[2m{GAP_TEXT}\x1b[0m") })
}

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
    /// How far the view moved down beyond all of the history: the top of the
    /// screen holds this many rows older than the oldest history row. A move
    /// up gives these back first; they lead the history then.
    beyond: usize,
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
            // The mark a saved copy carries where rows were missed is the gap again.
            if row.text == GAP_TEXT {
                if !out.history.is_empty() {
                    out.gap = Some(out.history.len());
                }
                continue;
            }
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

    /// Rows older than every history row, oldest first, put before them.
    fn prepend(&mut self, rows: Vec<Row>) {
        if self.complete {
            // The history reached the program's first row: these were known.
            return;
        }
        let n = rows.len();
        for row in rows.into_iter().rev() {
            self.bytes += row.bytes();
            self.history.push_front(row);
        }
        self.gap = self.gap.map(|at| at + n);
        while self.bytes > MAX_BYTES {
            let Some(old) = self.history.pop_front() else { break };
            self.bytes -= old.bytes();
            self.gap = self.gap.and_then(|at| at.checked_sub(1)).filter(|at| *at > 0);
        }
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
        // Rows before an older gap are kept apart from what follows it too.
        self.gap = Some(self.gap.map_or(self.history.len(), |at| at.min(self.history.len())));
        self.back = 0;
        self.beyond = 0;
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
                // the moved rows) is not part of the history; a row that repeats
                // with the move itself (a rule every `d` rows) is no header.
                let header = (0..previous.len().min(next.len()))
                    .take_while(|&i| previous[i] == next[i] && anchor(previous[i]) && previous.get(i + d) != Some(&previous[i]))
                    .count();
                let end = (header + d).min(self.screen.len());
                let start = header.min(end);
                // First the rows older than the history (the view sat above
                // it): they lead the history, in order.
                let older = (d.min(self.beyond)).min(end - start);
                self.beyond -= older;
                if older > 0 {
                    self.prepend(self.screen[start..start + older].to_vec());
                }
                // Then the history rows that were on the screen: known already.
                let again = (d - older).min(self.back).min(end - start - older);
                self.back -= again;
                let left: Vec<Row> = self.screen[start + older + again..end].to_vec();
                for row in left {
                    self.push(row);
                }
            }
            Moved::Down(d) => {
                let total = self.back + self.beyond + d;
                self.back = total.min(self.history.len());
                self.beyond = total - self.back;
            }
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
        if d <= 0 && from > 0 {
            // The read starts after the gap: it fills none of it, and the
            // rows on both sides must not be joined.
            return false;
        }
        let kept: Vec<Row> = self.history.drain(from..).collect();
        let older: Vec<Row> = self.history.drain(..).collect();
        self.bytes = 0;
        self.gap = None;
        self.beyond = 0;
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
    /// program's screen now), each row once, with a mark where rows were
    /// never seen ([`GAP_TEXT`]).
    pub(crate) fn reader_rows<'a>(&'a self, screen: &'a [Row]) -> impl Iterator<Item = &'a Row> {
        let shown = self.history.len() - self.back.min(self.history.len());
        let at = self.gap.filter(|at| *at > 0).map_or(shown, |at| at.min(shown));
        let mark = self.gap.filter(|g| *g > 0 && *g <= shown).map(|_| gap_row());
        self.history.iter().take(at).chain(mark).chain(self.history.iter().skip(at).take(shown - at)).chain(screen.iter())
    }

    /// The screen last looked at.
    #[cfg(test)]
    pub(crate) fn screen(&self) -> &[Row] {
        &self.screen
    }
}

#[cfg(test)]
mod tests {
    #![allow(clippy::unwrap_used, clippy::expect_used)]
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

    /// Review P2-2: the desktop scrolls Claude back further than the phone holds.
    #[test]
    fn ask2_a_view_scrolled_back_past_the_phones_history_adds_no_row_twice() {
        let mut t = Transcript::default();
        t.observe(claude(59, 30, "done"), 80);
        t.observe(claude(64, 30, "done"), 80);
        assert_eq!(t.rows(), 5);
        assert_eq!(t.observe(claude(54, 30, "done"), 80), Moved::Down(10));
        assert_eq!(t.observe(claude(64, 30, "done"), 80), Moved::Up(10));
        let screen = claude(64, 30, "done");
        let all: Vec<String> = texts(t.reader_rows(&screen)).into_iter().filter(|r| !r.is_empty()).collect();
        let mut want: Vec<String> = (25..=64).map(line).filter(|r| !r.is_empty()).collect();
        want.extend(texts(screen[30..].iter()).into_iter().filter(|r| !r.is_empty()));
        assert_eq!(all, want);
    }

    /// Review P2-3: a seed that starts after the gap must not clear it.
    #[test]
    fn ask2_a_seed_that_starts_after_a_gap_leaves_it_owed() {
        let mut t = Transcript::default();
        t.observe(claude(29, 30, "· working"), 80);
        t.observe(claude(40, 30, "· working"), 80);
        assert_eq!(t.observe(claude(140, 30, "· working"), 80), Moved::Lost);
        for n in (141..=199).step_by(2) {
            t.observe(claude(n, 30, "· working"), 80);
        }
        // The core's read reaches back only to row 150, after the gap.
        let mut text: Vec<String> = (150..=200).map(line).collect();
        text.extend(texts(claude(200, 30, "done")[30..].iter()));
        let placed = t.seed(&text.join("\n"), false);
        assert!(!placed || t.wants_seed(), "the gap was cleared without being filled");
    }

    /// Review P2-4: rows never seen are shown as such, never silently joined.
    #[test]
    fn ask2_rows_lost_to_a_gap_are_marked_in_the_reader() {
        let mut t = Transcript::default();
        t.observe(claude(29, 30, "· working"), 80);
        t.observe(claude(40, 30, "· working"), 80);
        assert_eq!(t.observe(claude(140, 30, "· working"), 80), Moved::Lost);
        t.observe(claude(143, 30, "· working"), 80);
        let screen = claude(143, 30, "· working");
        let all = texts(t.reader_rows(&screen));
        let at = all.iter().position(|r| r == "· rows not seen ·").expect("a marker where rows were missed");
        // Everything before it was captured before the gap, everything after it since.
        assert_eq!(all[at - 2..at], [line(9), line(10)]);
        assert_eq!(all[at + 1], line(111));
    }

    /// Review P3-1: a rule that repeats every `d` rows is no fixed header.
    #[test]
    fn ask2_a_rule_repeating_with_the_move_is_kept_in_its_place() {
        let rule = "────────────────";
        let rows = |first: usize| -> Vec<Row> {
            (first..first + 20).map(|n| Row::plain(&if n % 4 == 0 { rule.to_owned() } else { format!("row {n:03}") })).collect()
        };
        let mut t = Transcript::default();
        t.observe(rows(0), 80);
        assert_eq!(t.observe(rows(4), 80), Moved::Up(4));
        assert_eq!(texts(t.history.iter()), vec![rule.to_owned(), "row 001".into(), "row 002".into(), "row 003".into()]);
    }

    #[test]
    fn rows_that_repeat_never_prove_a_move() {
        // Only blank and short rows changed: nothing to align by.
        let a = vec!["", "│", "", "  ⎿", "❯"];
        let b = vec!["│", "", "  ⎿", "", "❯"];
        assert_eq!(moved(&a, &b), Moved::Still);
    }
}
