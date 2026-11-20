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
//!   not added twice when it moves up again; scrolled back further than the
//!   history reaches (the program scrolling its own view), the older rows
//!   lead the history once it moves up again.
//! - **Gaps.** When the screen changed by more than it holds between two
//!   looks (or was resized), rows were missed: a mark stands there.
//!
//! Only what the phone watched on one attach stream: nothing is read from
//! the core (its stitched read freezes the pane on every screen), from a
//! saved copy, or from another stream of the same pane (the phone cannot
//! prove a reattached pane is the same terminal). The phone scrolls this history itself
//! ([`Transcript::reader_rows`]) and saves it, so nothing waits on the link.
use rio_vt::crosswords::Crosswords;
use rio_vt::crosswords::pos::{Column, Line, Pos};
use rio_vt::crosswords::square::Wide;
use rio_vt::event::EventListener;
use std::collections::{HashMap, VecDeque};
use std::sync::Arc;

/// The most one pane's history may take (Wolf's budget: 1 MiB a pane); the
/// oldest rows go first.
pub(crate) const MAX_BYTES: usize = 1024 * 1024;
/// A row counts as evidence of where the screen moved only with this many
/// characters that are not spaces: short rows ("", "│", "  ⎿") repeat everywhere.
const MIN_ANCHOR_CHARS: usize = 3;
/// At least this many such rows must agree on a move before it is trusted.
const MIN_VOTES: usize = 2;
/// Drawn in the reader (and in a saved copy) where rows were never seen: the
/// screen changed by more than it holds between two looks.
pub(crate) const GAP_TEXT: &str = "· rows not seen ·";
/// Drawn above the oldest row of the copy being read: a drag stops there,
/// and a new one from there scrolls the program's own view.
pub(crate) const TOP_TEXT: &str = "· top of the phone's copy ·";

fn gap_row() -> &'static Arc<Row> {
    static ROW: std::sync::OnceLock<Arc<Row>> = std::sync::OnceLock::new();
    ROW.get_or_init(|| Arc::new(Row { text: GAP_TEXT.to_owned(), vt: format!("\x1b[2m{GAP_TEXT}\x1b[0m") }))
}

/// One screen row: its text (trailing blanks trimmed), what it is compared
/// by, and the bytes that draw it again with its colours.
#[derive(Clone, Debug, PartialEq, Eq)]
pub(crate) struct Row {
    pub text: String,
    pub vt: String,
}

impl Row {
    /// A row as plain text, drawn in the default colours.
    #[cfg(test)]
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
    /// Oldest first: rows above the screen. Shared, so a copy of them (a
    /// reader, a save) is taken without copying the rows themselves.
    history: VecDeque<Arc<Row>>,
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
    /// Where the next of those rows go: after the ones a move up already gave
    /// back (they come oldest first, a few each move), before the rest.
    beyond_at: usize,
    /// Where rows were missed, ascending: the rows before each index never
    /// join the rows after it. 0: rows before the oldest kept one (nothing to mark).
    gaps: Vec<usize>,
    /// The screen's width when last looked at: a resize redraws everything.
    cols: usize,
}

impl Transcript {
    /// A transcript of `rows`: its history then its screen (tests).
    #[cfg(test)]
    pub(crate) fn restored(mut rows: Vec<Row>, screen: usize, cols: usize) -> Self {
        let screen = rows.split_off(rows.len().saturating_sub(screen));
        let mut out = Self { screen, cols, ..Self::default() };
        for row in rows {
            out.push(row);
        }
        out
    }

    /// Rows were missed right after the newest history row.
    fn mark_gap(&mut self) {
        let at = self.history.len();
        if self.gaps.last() != Some(&at) {
            self.gaps.push(at);
        }
    }

    /// Every gap index shifted by `by` rows (oldest dropped: negative), those
    /// that fall off the front gone.
    fn shift_gaps(&mut self, by: isize) {
        self.gaps = self.gaps.iter().filter_map(|g| g.checked_add_signed(by)).filter(|g| *g > 0 || by > 0).collect();
    }

    pub(crate) fn rows(&self) -> usize {
        self.history.len()
    }

    /// Rows older than the history the view moved up past, oldest first:
    /// they go after the ones given back before them, before the rest.
    fn prepend(&mut self, rows: Vec<Row>) {
        let n = rows.len();
        let at = self.beyond_at.min(self.history.len());
        for (i, row) in rows.into_iter().enumerate() {
            self.bytes += row.bytes();
            self.history.insert(at + i, Arc::new(row));
        }
        self.beyond_at = at + n;
        self.gaps = self.gaps.iter().map(|g| if *g >= at { g + n } else { *g }).collect();
        while self.bytes > MAX_BYTES {
            let Some(old) = self.history.pop_front() else { break };
            self.bytes -= old.bytes();
            self.beyond_at = self.beyond_at.saturating_sub(1);
            self.shift_gaps(-1);
        }
    }

    fn push(&mut self, row: Row) {
        self.bytes += row.bytes();
        self.history.push_back(Arc::new(row));
        while self.bytes > MAX_BYTES {
            let Some(old) = self.history.pop_front() else { break };
            self.bytes -= old.bytes();
            self.beyond_at = self.beyond_at.saturating_sub(1);
            self.shift_gaps(-1);
        }
    }

    fn lose(&mut self) {
        // Each place rows were missed stays apart from the rows around it.
        self.mark_gap();
        self.back = 0;
        self.beyond = 0;
        self.beyond_at = 0;
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
                // Rows beyond the history now: older than every row kept.
                self.beyond_at = 0;
            }
            Moved::Lost => self.lose(),
            Moved::Still => {}
        }
        self.screen = screen;
        how
    }

    /// What the phone scrolls through: the history, then `screen` (the
    /// program's screen now), each row once, with a mark where rows were
    /// never seen ([`GAP_TEXT`]).
    #[cfg(test)]
    pub(crate) fn reader_rows<'a>(&'a self, screen: &'a [Row]) -> impl Iterator<Item = &'a Row> {
        self.shown().into_iter().map(|row| &**row).chain(screen.iter())
    }

    /// [`Transcript::reader_rows`], shared: the history rows themselves are
    /// not copied (taken while the transcript is locked, written out after).
    pub(crate) fn reader_shared(&self, screen: &[Row]) -> Vec<Arc<Row>> {
        let mut out: Vec<Arc<Row>> = self.shown().into_iter().cloned().collect();
        out.extend(screen.iter().cloned().map(Arc::new));
        out
    }

    /// The history rows above the program's view, with a mark at each gap.
    fn shown(&self) -> Vec<&Arc<Row>> {
        let shown = self.history.len() - self.back.min(self.history.len());
        let mut gaps = self.gaps.iter().copied().filter(|g| *g > 0 && *g <= shown).peekable();
        let mut out: Vec<&Arc<Row>> = Vec::with_capacity(shown + self.gaps.len());
        for (i, row) in self.history.iter().take(shown).enumerate() {
            while gaps.next_if(|g| *g <= i).is_some() {
                out.push(gap_row());
            }
            out.push(row);
        }
        if gaps.next().is_some() {
            out.push(gap_row());
        }
        out
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
    fn ask2_too_much_output_at_once_is_a_marked_gap() {
        let mut t = Transcript::default();
        t.observe(claude(29, 30, "· working"), 80);
        t.observe(claude(31, 30, "· working"), 80);
        // 80 rows at once: nothing on the new screen was on the old one.
        assert_eq!(t.observe(claude(111, 30, "· working"), 80), Moved::Lost);
        t.observe(claude(113, 30, "done"), 80);
        let screen = claude(113, 30, "done");
        let all = texts(t.reader_rows(&screen));
        let mut want: Vec<String> = vec![line(0), line(1), GAP_TEXT.to_owned()];
        want.extend((82..=113).map(line));
        want.extend(texts(screen[30..].iter()));
        assert_eq!(all, want);
    }

    #[test]
    fn ask2_a_resize_is_a_gap_never_a_false_move() {
        let mut t = Transcript::default();
        t.observe(claude(29, 30, "done"), 80);
        assert_eq!(t.observe(claude(29, 30, "done"), 120), Moved::Lost);
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

    /// Round 3 (emulator): the program's own view scrolled back far beyond
    /// the phone's history, then forward a few rows at a time: the rows it
    /// gives back join in order, once, before the history.
    #[test]
    fn ask2_a_view_scrolled_far_back_then_forward_in_steps_keeps_the_order() {
        let mut t = Transcript::default();
        t.observe(claude(59, 30, "done"), 80);
        t.observe(claude(64, 30, "done"), 80);
        // Back 3 rows at a time (a wheel), 13 rows beyond the history.
        for newest in (44..=61).rev().step_by(3) {
            assert!(matches!(t.observe(claude(newest, 30, "done"), 80), Moved::Down(3)));
        }
        // Forward again, 3 at a time, to the newest.
        for newest in (49..=64).step_by(3) {
            assert_eq!(t.observe(claude(newest, 30, "done"), 80), Moved::Up(3));
        }
        let screen = claude(64, 30, "done");
        let all: Vec<String> = texts(t.reader_rows(&screen)).into_iter().filter(|r| !r.is_empty()).collect();
        let mut want: Vec<String> = (17..=64).map(line).filter(|r| !r.is_empty()).collect();
        want.extend(texts(screen[30..].iter()).into_iter().filter(|r| !r.is_empty()));
        assert_eq!(all, want);
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
        assert_eq!(texts(t.history.iter().map(|r| &**r)), vec![rule.to_owned(), "row 001".into(), "row 002".into(), "row 003".into()]);
    }

    /// Review round 2 P2-4: two separate bursts are two gaps, both marked.
    #[test]
    fn ask2_two_bursts_are_two_marked_gaps() {
        let mut t = Transcript::default();
        t.observe(claude(29, 30, "· working"), 80);
        t.observe(claude(40, 30, "· working"), 80);
        assert_eq!(t.observe(claude(140, 30, "· working"), 80), Moved::Lost);
        t.observe(claude(150, 30, "· working"), 80);
        assert_eq!(t.observe(claude(250, 30, "· working"), 80), Moved::Lost);
        t.observe(claude(255, 30, "· working"), 80);
        let screen = claude(255, 30, "· working");
        let marks = t.reader_rows(&screen).filter(|r| r.text == GAP_TEXT).count();
        assert_eq!(marks, 2);
    }

    #[test]
    fn rows_that_repeat_never_prove_a_move() {
        // Only blank and short rows changed: nothing to align by.
        let a = vec!["", "│", "", "  ⎿", "❯"];
        let b = vec!["│", "", "  ⎿", "", "❯"];
        assert_eq!(moved(&a, &b), Moved::Still);
    }
}
