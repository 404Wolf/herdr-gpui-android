//! A pane's last terminal state on disk, so the conversation can be read with
//! no link at all, even after the app was killed.
//!
//! What is saved is the phone's own Rio grid (history, then screen, then the
//! cursor) as VT bytes: written to a fresh grid of the same size, they draw the
//! same rows with the same colours and glyphs the program drew, because they
//! are replayed through the same parser and renderer. Nothing about the
//! program is interpreted, so Claude Code, Codex and shells all look exactly
//! as they did. Colours keep their named/indexed form, so the theme applies.
//!
//! A full-screen program (Claude Code) keeps its history to itself, so its
//! copy is what the phone watched of it (`transcript`) and then its screen,
//! written as rows: read back, it scrolls like any history. A copy is for
//! reading with no link only; nothing in it is joined to a live pane again.
//!
//! File (version 2): one JSON header line (`{"v":2,"cols":..,"rows":..,
//! "pane":"..","history":..,"full_screen":..}`), then the bytes.
//! The phone seals the whole file before it reaches the disk (PaneSnapshots);
//! version 1 files (no history count) are still read once, to migrate them.
use crate::remote::{Error, Result};
use rio_vt::crosswords::Crosswords;
use rio_vt::crosswords::formatter::write_sgr;
use rio_vt::crosswords::grid::Grid;
use rio_vt::crosswords::square::{Hyperlink, Square};
use rio_vt::crosswords::pos::{Column, Line};
use rio_vt::crosswords::style::Style;
use rio_vt::event::EventListener;
use serde_json::{Value, json};
use std::{fmt::Write as _, io, sync::Arc};
#[cfg(test)]
use std::{fs, io::Read, path::Path};

/// The most a saved pane may take, as on phone-feedback (a plain shell keeps
/// 4 MiB of scrollback); the oldest history rows give way first. An agent's
/// transcript is at most transcript::MAX_BYTES.
pub(crate) const MAX_BYTES: usize = 4 * 1024 * 1024;
/// The most a copy read back may be: version 1 copies were up to 4 MiB.
const MAX_READ: usize = 4 * 1024 * 1024;
const MAX_HEADER: usize = 4096;
const VERSION: u64 = 2;

/// The grid's history and screen as VT bytes, at most `max_bytes` (whole
/// oldest rows are dropped to fit). Every screen row is written, blank or
/// not, so replayed rows land on the same screen lines.
#[cfg(test)]
pub(crate) fn encode<U: EventListener>(grid: &Crosswords<U>, max_bytes: usize) -> Vec<u8> {
    Rows::of_grid(grid).encode(max_bytes).0
}

/// A copy's rows, taken while the grid (and the transcript) are locked:
/// building its bytes from them needs no lock, so whatever else waits on
/// those locks (the poll) never waits for a copy to be encoded.
pub(crate) struct Rows {
    pub cols: usize,
    /// Screen lines.
    pub lines: usize,
    /// The rows (their bytes in `vt`): history, then the screen.
    rows: Vec<Arc<crate::transcript::Row>>,
    history: usize,
    tail: String,
    full_screen: bool,
}

impl Rows {
    /// The grid's own history and screen (formatted with the grid locked:
    /// tests and short grids; a save copies [`Cells`] instead).
    #[cfg(test)]
    pub(crate) fn of_grid<U: EventListener>(grid: &Crosswords<U>) -> Self {
        Cells::of(grid).rows()
    }

    /// A full-screen program's: the phone's `transcript` of it, then
    /// `screen` (with a mark where rows were missed).
    pub(crate) fn of_transcript(screen: &Screen, transcript: &crate::transcript::Transcript) -> Self {
        let rows = transcript.reader_shared(&screen.rows);
        let history = rows.len() - screen.rows.len();
        Self { cols: screen.cols, lines: screen.rows.len(), rows, history, tail: screen.tail.clone(), full_screen: true }
    }

    /// A pane's copy: a full-screen program's `transcript` and its screen,
    /// anything else the grid's own history and screen.
    #[cfg(test)]
    pub(crate) fn of<U: EventListener>(grid: &Crosswords<U>, transcript: Option<&crate::transcript::Transcript>) -> Self {
        match (transcript, Screen::of(grid)) {
            (Some(transcript), Some(screen)) => Self::of_transcript(&screen, transcript),
            _ => Self::of_grid(grid),
        }
    }

    /// The bytes that draw these rows, at most `max_bytes` (the oldest
    /// history rows dropped to fit), and how many history rows were kept.
    pub(crate) fn encode(self, max_bytes: usize) -> (Vec<u8>, usize) {
        #[cfg(test)]
        probe::hit("encode");
        if self.cols == 0 || self.lines == 0 {
            return (Vec::new(), 0);
        }
        let lines: Vec<&str> = self.rows.iter().map(|r| r.vt.as_str()).collect();
        assemble(&lines, self.history, &self.tail, max_bytes)
    }

    /// `pane`'s whole file: a header and the bytes (sealed and written by the phone).
    pub(crate) fn file(self, pane: &str) -> Result<Vec<u8>> {
        let cols = u16::try_from(self.cols).map_err(|_| Error::Capacity)?;
        let rows = u16::try_from(self.lines).map_err(|_| Error::Capacity)?;
        let full_screen = self.full_screen;
        let (body, history) = self.encode(MAX_BYTES);
        let mut file = header(cols, rows, pane, history, full_screen).into_bytes();
        file.extend(body);
        Ok(file)
    }
}

/// A full-screen program's screen as a copy needs it, taken while its grid
/// is locked (then the grid's lock goes; the transcript's is taken after).
pub(crate) struct Screen {
    pub rows: Vec<crate::transcript::Row>,
    pub cols: usize,
    tail: String,
}

impl Screen {
    /// None when the grid is not on the alternate screen.
    pub(crate) fn of<U: EventListener>(grid: &Crosswords<U>) -> Option<Self> {
        full_screen(grid).then(|| Self { rows: crate::transcript::screen_rows(grid), cols: grid.columns(), tail: tail(grid) })
    }
}

/// A grid's cells, copied while it is locked (its rows, styles and links
/// as they are), so the rows are formatted with the lock released.
pub(crate) struct Cells {
    grid: Grid<Square>,
    cols: usize,
    lines: usize,
    history: usize,
    tail: String,
    full_screen: bool,
}

impl Cells {
    pub(crate) fn of<U: EventListener>(grid: &Crosswords<U>) -> Self {
        Self {
            grid: grid.grid.clone(),
            cols: grid.columns(),
            lines: grid.screen_lines(),
            history: grid.history_size(),
            tail: tail(grid),
            full_screen: full_screen(grid),
        }
    }

    /// Its history and screen as rows, formatted now.
    pub(crate) fn rows(self) -> Rows {
        let (lines, cols, history) = (self.lines, self.cols, self.history);
        let rows = if lines == 0 || cols == 0 {
            Vec::new()
        } else {
            (-(history as i32)..lines as i32)
                .map(|line| Arc::new(crate::transcript::Row { text: String::new(), vt: row_of(&self.grid, line, cols) }))
                .collect()
        };
        Rows { cols, lines, rows, history, tail: self.tail, full_screen: self.full_screen }
    }
}

fn full_screen<U: EventListener>(grid: &Crosswords<U>) -> bool {
    grid.mode().contains(rio_vt::crosswords::Mode::ALT_SCREEN)
}

/// The cursor, after the rows: a reset, its place, and whether it shows.
fn tail<U: EventListener>(grid: &Crosswords<U>) -> String {
    let mut tail = String::from("\x1b[0m");
    let cursor = grid.grid.cursor.pos;
    let _ = write!(tail, "\x1b[{};{}H", cursor.row.0.max(0) + 1, cursor.col.0 + 1);
    // A hidden cursor stays hidden: the composer reads a visible one as the
    // program's input caret, so a cached dialog must not offer one.
    if !grid.mode().contains(rio_vt::crosswords::Mode::SHOW_CURSOR) {
        tail.push_str("\x1b[?25l");
    }
    tail
}

/// Rows from `\r\n` to `\r\n` between a reset and `tail`, the oldest of the
/// first `history` dropped until it fits `max_bytes`: the bytes and the
/// history rows kept.
fn assemble(lines: &[&str], history: usize, tail: &str, max_bytes: usize) -> (Vec<u8>, usize) {
    let mut total = START.len() + tail.len() + lines.iter().map(|l| l.len() + 2).sum::<usize>();
    // Only history may go: the screen rows place everything else.
    let mut dropped = 0;
    while total > max_bytes && dropped < history {
        total -= lines[dropped].len() + 2;
        dropped += 1;
    }
    let lines = &lines[dropped..];
    let mut out = String::with_capacity(total);
    // Written to a fresh grid: no clear (Rio would push a blank row to history).
    out.push_str(START);
    for (index, line) in lines.iter().enumerate() {
        if index > 0 {
            out.push_str("\r\n");
        }
        out.push_str(line);
    }
    out.push_str(tail);
    (out.into_bytes(), history - dropped)
}

const START: &str = "\x1b[0m";

/// Test probes: a test registers what runs when the code under test reaches
/// a named point ("format": a row is formatted; "encode": a copy's bytes are
/// assembled), on the same thread.
#[cfg(test)]
pub(crate) mod probe {
    use std::cell::RefCell;
    use std::collections::HashMap;
    type Probes = HashMap<&'static str, Box<dyn FnMut()>>;
    thread_local! {
        static PROBES: RefCell<Probes> = RefCell::new(HashMap::new());
    }
    pub(crate) fn set(name: &'static str, f: impl FnMut() + 'static) {
        PROBES.with(|p| p.borrow_mut().insert(name, Box::new(f)));
    }
    pub(crate) fn clear() {
        PROBES.with(|p| p.borrow_mut().clear());
    }
    pub(crate) fn hit(name: &'static str) {
        let f = PROBES.with(|p| p.borrow_mut().remove(name));
        if let Some(mut f) = f {
            f();
            PROBES.with(|p| {
                p.borrow_mut().entry(name).or_insert(f);
            });
        }
    }
}

/// The rows of a saved copy's bytes (as [`assemble`] wrote them), each with its text.
#[cfg(test)]
pub(crate) fn saved_rows(bytes: &[u8]) -> Vec<crate::transcript::Row> {
    let text = String::from_utf8_lossy(bytes);
    let text = text.strip_prefix(START).unwrap_or(&text);
    let mut lines: Vec<&str> = text.split("\r\n").collect();
    if let Some(last) = lines.last_mut()
        && let Some(at) = last.rfind("\x1b[0m\x1b[")
    {
        *last = &last[..at];
    }
    lines
        .into_iter()
        .map(|vt| crate::transcript::Row { text: strip_sgr(vt).trim_end().to_owned(), vt: vt.to_owned() })
        .collect()
}

/// `vt` without its SGR and OSC 8 sequences (the only ones a row holds).
#[cfg(test)]
fn strip_sgr(vt: &str) -> String {
    let mut out = String::with_capacity(vt.len());
    let mut chars = vt.chars().peekable();
    while let Some(c) = chars.next() {
        if c == '\x1b' {
            match chars.next() {
                Some('[') => {
                    for c in chars.by_ref() {
                        if ('@'..='~').contains(&c) {
                            break;
                        }
                    }
                }
                // An OSC (a link), to BEL or ESC \.
                Some(']') => {
                    while let Some(c) = chars.next() {
                        if c == '\x07' || (c == '\x1b' && chars.next_if_eq(&'\\').is_some()) {
                            break;
                        }
                    }
                }
                _ => {}
            }
            continue;
        }
        out.push(c);
    }
    out
}

/// One row, self-contained: it starts and ends in the default style, so any
/// prefix of history can be dropped. Trailing blanks that draw nothing are left out.
pub(crate) fn row<U: EventListener>(grid: &Crosswords<U>, line: i32, cols: usize) -> String {
    row_of(&grid.grid, line, cols)
}

/// The link a cell is in, if any.
fn link_of(grid: &Grid<Square>, line: i32, col: usize) -> Option<Hyperlink> {
    let cell = &grid[Line(line)][Column(col)];
    if !cell.has_hyperlink() {
        return None;
    }
    grid.extras_table.get(cell.extras_id()?).and_then(|extras| extras.hyperlink.clone())
}

/// [`row`] of a grid's cells.
fn row_of(grid: &Grid<Square>, line: i32, cols: usize) -> String {
    #[cfg(test)]
    probe::hit("format");
    let cells = &grid[Line(line)];
    let last = (0..cols).rev().find(|&col| {
        let square = &cells[Column(col)];
        let c = square.c();
        (c != ' ' && c != '\0') || grid.style_of(square).renders_on_blank()
    });
    let mut out = String::new();
    let Some(last) = last else {
        return out;
    };
    let mut current = Style::default();
    // The OSC 8 link the cells written so far are in (its id and target).
    let mut linked: Option<(String, String)> = None;
    let mut col = 0;
    while col <= last {
        let square = &cells[Column(col)];
        if square.is_spacer() {
            col += 1;
            continue;
        }
        let link = link_of(grid, line, col)
            .filter(|l| l.uri().len() <= MAX_LINK && l.id().len() <= MAX_LINK)
            .map(|l| (clean_osc(l.id()), clean_osc(l.uri())));
        if link != linked {
            match &link {
                Some((id, uri)) => {
                    let _ = write!(out, "\x1b]8;{};{}\x1b\\", if id.is_empty() { String::new() } else { format!("id={id}") }, uri);
                }
                None => out.push_str("\x1b]8;;\x1b\\"),
            }
            linked = link;
        }
        let style = grid.style_of(square);
        if style != current {
            write_sgr(&mut out, &style);
            current = style;
        }
        let c = square.c();
        out.push(if c == '\0' { ' ' } else { c });
        col += if square.is_wide() { 2 } else { 1 };
    }
    if linked.is_some() {
        out.push_str("\x1b]8;;\x1b\\");
    }
    if current != Style::default() {
        out.push_str("\x1b[0m");
    }
    out
}

/// The longest link target (or id) a copy keeps: the live parser's own bound
/// (gpui-rio's links), so every link that was live is kept whole. A longer
/// one (no live link is) is left out, never cut to another target.
const MAX_LINK: usize = 4096;

/// A link's id or target as it may stand in an OSC 8: no control characters
/// (they would end or break the sequence).
fn clean_osc(text: &str) -> String {
    text.chars().filter(|c| !c.is_control()).collect()
}

/// A saved pane read back: its grid size and the bytes that redraw it.
#[derive(Debug, PartialEq)]
pub(crate) struct Saved {
    pub cols: u16,
    pub rows: u16,
    pub pane: String,
    pub bytes: Vec<u8>,
}

impl Saved {
    /// Bytes to draw on a `cols`×`rows` grid, with no history count (a note, a test).
    pub(crate) fn of(cols: u16, rows: u16, pane: &str, bytes: Vec<u8>) -> Self {
        Self { cols, rows, pane: pane.into(), bytes }
    }
}

pub(crate) fn header(cols: u16, rows: u16, pane: &str, history: usize, full_screen: bool) -> String {
    format!("{}\n", json!({"v":VERSION,"cols":cols,"rows":rows,"pane":pane,"history":history,"full_screen":full_screen}))
}

/// A saved pane's whole file, built from the grid (a full-screen program's
/// from `transcript` and its screen).
#[cfg(test)]
pub(crate) fn copy<U: EventListener>(
    grid: &Crosswords<U>,
    pane: &str,
    transcript: Option<&crate::transcript::Transcript>,
) -> Result<Vec<u8>> {
    Rows::of(grid, transcript).file(pane)
}

/// Writes a [`copy`] to `path`, atomically: a temporary file, synced, then renamed.
#[cfg(test)]
pub(crate) fn write(file: &[u8], path: &Path) -> Result<()> {
    let tmp = path.with_extension("tmp");
    {
        let mut out = fs::File::create(&tmp)?;
        io::Write::write_all(&mut out, file)?;
        out.sync_all()?;
    }
    fs::rename(&tmp, path)?;
    Ok(())
}

/// Writes `pane`'s grid to `path`, atomically.
#[cfg(test)]
pub(crate) fn save<U: EventListener>(grid: &Crosswords<U>, pane: &str, path: &Path) -> Result<()> {
    write(&copy(grid, pane, None)?, path)
}

/// Reads a saved pane, refusing anything malformed or larger than a save makes.
#[cfg(test)]
pub(crate) fn load(path: &Path) -> Result<Saved> {
    let mut bytes = Vec::new();
    fs::File::open(path)?
        .take((MAX_READ + MAX_HEADER + 1) as u64)
        .read_to_end(&mut bytes)?;
    parse(bytes)
}

/// Reads a copy's file (opened by the phone), version 2 or a version 1 left
/// from before, refusing anything malformed or larger than a save made.
pub(crate) fn parse(mut bytes: Vec<u8>) -> Result<Saved> {
    let invalid = || Error::Snapshot;
    if bytes.len() > MAX_READ + MAX_HEADER {
        return Err(Error::Capacity);
    }
    let end = bytes
        .iter()
        .take(MAX_HEADER)
        .position(|b| *b == b'\n')
        .ok_or_else(invalid)?;
    let header: Value = serde_json::from_slice(&bytes[..end])?;
    let size = |key: &str| {
        header[key]
            .as_u64()
            .and_then(|n| u16::try_from(n).ok())
            .filter(|n| *n > 0)
    };
    let version = header["v"].as_u64();
    let (Some(cols), Some(rows), Some(1 | 2)) = (size("cols"), size("rows"), version) else {
        return Err(invalid());
    };
    let pane = header["pane"].as_str().ok_or_else(invalid)?.to_owned();
    // A version 2 header always says how many rows are history.
    if version == Some(VERSION) && header["history"].as_u64().is_none() {
        return Err(invalid());
    }
    bytes.drain(..=end);
    Ok(Saved { cols, rows, pane, bytes })
}

/// The most characters of a title or note an uncached pane shows.
const MAX_NOTE_CHARS: usize = 200;

/// What a pane with no saved copy draws while there is no link: its title,
/// bold, and one dim line under it, with no cursor (nothing can be typed into
/// it). Text only: control characters in either string are dropped.
pub(crate) fn note(title: &str, line: &str) -> Vec<u8> {
    let clean = |text: &str| -> String {
        text.chars().filter(|c| !c.is_control()).take(MAX_NOTE_CHARS).collect()
    };
    format!("\x1b[0m\x1b[1m{}\x1b[0m\r\n\x1b[2m{}\x1b[0m\x1b[?25l", clean(title), clean(line)).into_bytes()
}

/// A read-only terminal showing `saved`: no transport, input goes nowhere.
pub(crate) fn restore(saved: Saved) -> Result<Arc<gpui_rio::Session>> {
    let session = gpui_rio::Session::start(
        io::Cursor::new(saved.bytes),
        io::sink(),
        |_, _| {},
        crate::shell::theme(&crate::model::palette()),
        saved.cols,
        saved.rows,
    )?;
    // The saved size is the grid: the phone's own size must not reflow it.
    session.set_grid(saved.cols, saved.rows);
    Ok(session)
}

#[cfg(test)]
mod tests {
    #![allow(clippy::unwrap_used)]
    use super::*;
    use std::time::{Duration, Instant};

    #[test]
    fn an_uncached_pane_draws_its_title_and_one_dim_line_and_nothing_else() {
        let bytes = note("lead \x1b[2Jwindow 2", "not cached · opens when the link is back");
        let shown = parsed(&bytes, 60, 6);
        let text = shown.screen_text();
        let lines: Vec<&str> = text.lines().map(str::trim_end).filter(|l| !l.is_empty()).collect();
        // The escape in the title was dropped, never run (no screen clear).
        assert_eq!(lines, vec!["lead [2Jwindow 2", "not cached · opens when the link is back"]);
        let grid = shown.grid().lock();
        assert!(grid.grid.style_of(&grid.grid[Line(0)][Column(0)]).flags.contains(rio_vt::crosswords::style::StyleFlags::BOLD));
        assert!(grid.grid.style_of(&grid.grid[Line(1)][Column(0)]).flags.contains(rio_vt::crosswords::style::StyleFlags::DIM));
        assert!(!grid.mode().contains(rio_vt::crosswords::Mode::SHOW_CURSOR));
        drop(grid);
        let long = note(&"x".repeat(1000), "");
        assert!(long.len() < 300);
    }

    /// Review round 2 P2-7: an OSC 8 link keeps its target in a copy (and so
    /// in the phone's copy read back), not only its label.
    #[test]
    fn ask2_a_labelled_link_keeps_its_target_in_the_copy() {
        let session = parsed(b"see \x1b]8;;https://example.test/notes\x1b\\release notes\x1b]8;;\x1b\\ here\r\n$ ", 40, 5);
        let bytes = copy(&session.grid().lock(), "t1", None).unwrap();
        let back = restore(parse(bytes).unwrap()).unwrap();
        wait_quiet(&back);
        let grid = back.grid().lock();
        let line = (0..grid.screen_lines() as i32)
            .map(Line)
            .find(|line| (0..grid.columns()).any(|c| grid.cell_hyperlink(*line, Column(c)).is_some()));
        let link = line.and_then(|line| (0..grid.columns()).find_map(|c| grid.cell_hyperlink(line, Column(c))));
        assert_eq!(link.map(|l| l.uri().to_owned()).as_deref(), Some("https://example.test/notes"));
        // The row's text is still the label.
        assert!(saved_rows(&copy(&session.grid().lock(), "t1", None).unwrap()).iter().any(|r| r.text.contains("see release notes here")));
    }

    /// Round 3 P3: a link target as long as a live one may be (#39: 4 KiB)
    /// is kept whole in a copy, never cut.
    #[test]
    fn ask2_a_long_link_target_is_kept_whole_in_the_copy() {
        let target = format!("https://example.test/{}", "a".repeat(2500));
        let text = format!("\x1b]8;;{target}\x1b\\notes\x1b]8;;\x1b\\\r\n$ ");
        let session = parsed(text.as_bytes(), 40, 5);
        let bytes = copy(&session.grid().lock(), "t1", None).unwrap();
        let back = restore(parse(bytes).unwrap()).unwrap();
        wait_quiet(&back);
        let grid = back.grid().lock();
        let link = (0..grid.screen_lines() as i32)
            .flat_map(|l| (0..grid.columns()).map(move |c| (l, c)))
            .find_map(|(l, c)| grid.cell_hyperlink(Line(l), Column(c)));
        assert_eq!(link.map(|l| l.uri().len()), Some(target.len()), "the link's target was cut");
    }

    /// Review mutant M5: only a full-screen grid's copy is its transcript.
    #[test]
    fn ask2_a_plain_pane_is_saved_from_its_grid_never_from_a_transcript() {
        let session = parsed(b"$ ls\r\nREADME.md\r\n$ ", 30, 5);
        let transcript = crate::transcript::Transcript::restored(
            vec![crate::transcript::Row::plain("a transcript row from elsewhere")],
            0,
            30,
        );
        let bytes = copy(&session.grid().lock(), "t1", Some(&transcript)).unwrap();
        let text = String::from_utf8_lossy(&bytes);
        assert!(text.contains("README.md"));
        assert!(!text.contains("a transcript row from elsewhere"));
        assert!(text.lines().next().unwrap().contains("\"full_screen\":false"));
    }

    /// Round 4 P2-3: a plain shell's copy keeps 4 MiB of scrollback, as on
    /// phone-feedback: 6,000 rows of 240 characters are all there.
    #[test]
    fn ask2_a_plain_shell_copy_keeps_its_4_mib_of_scrollback() {
        let wide = "x".repeat(232);
        let text: String = (0..6000).map(|n| format!("{n:05} {wide}\r\n")).collect();
        let session = parsed(text.as_bytes(), 240, 24);
        let bytes = copy(&session.grid().lock(), "t1", None).unwrap();
        let text = String::from_utf8_lossy(&bytes);
        assert!(text.contains("00000 x"), "the oldest rows were dropped from a {} byte copy", bytes.len());
    }

    fn parsed(text: &[u8], cols: u16, rows: u16) -> Arc<gpui_rio::Session> {
        let session = restore(Saved::of(cols, rows, "t1", text.to_vec())).unwrap();
        wait_quiet(&session);
        session
    }

    /// Until the reader has parsed everything (the stream ended).
    fn wait_quiet(session: &gpui_rio::Session) {
        let deadline = Instant::now() + Duration::from_secs(5);
        while session.is_alive() {
            assert!(Instant::now() < deadline, "the bytes were never parsed");
            std::thread::sleep(Duration::from_millis(5));
        }
    }

    fn rows_of(session: &gpui_rio::Session) -> Vec<String> {
        let grid = session.grid().lock();
        let history = grid.history_size() as i32;
        (-history..grid.screen_lines() as i32)
            .map(|line| {
                let cells = &grid.grid[Line(line)];
                (0..grid.columns()).map(|c| cells[Column(c)].c()).map(|c| if c == '\0' { ' ' } else { c }).collect::<String>().trim_end().to_owned()
            })
            .collect()
    }

    #[test]
    fn a_hidden_cursor_stays_hidden_and_a_shown_one_shown() {
        use rio_vt::crosswords::Mode;
        let cases = [("❯ draft".as_bytes(), true), ("\x1b[?25l│ 1. Yes".as_bytes(), false)];
        for (program, shown) in cases {
            let original = parsed(program, 24, 6);
            let bytes = encode(&original.grid().lock(), MAX_BYTES);
            let copy = parsed(&bytes, 24, 6);
            assert_eq!(copy.grid().lock().mode().contains(Mode::SHOW_CURSOR), shown);
        }
    }

    #[test]
    fn a_saved_grid_redraws_the_same_history_screen_colours_and_cursor() {
        let mut program = String::new();
        for n in 0..40 {
            program.push_str(&format!("\x1b[3{}mline {n}\x1b[0m wide 漢字\r\n", n % 8));
        }
        program.push_str("\x1b[44m  \x1b[0m❯ draft\x1b[2D");
        let original = parsed(program.as_bytes(), 24, 6);
        let (bytes, cursor) = {
            let grid = original.grid().lock();
            (encode(&grid, MAX_BYTES), grid.grid.cursor.pos)
        };
        let copy = parsed(&bytes, 24, 6);
        assert_eq!(rows_of(&copy), rows_of(&original));
        let (a, b) = (original.grid().lock(), copy.grid().lock());
        assert_eq!(b.history_size(), a.history_size());
        assert_eq!(b.grid.cursor.pos, cursor);
        for line in [-10, 0, 5] {
            for col in 0..24 {
                let (x, y) = (&a.grid[Line(line)][Column(col)], &b.grid[Line(line)][Column(col)]);
                assert_eq!((x.c(), x.is_wide()), (y.c(), y.is_wide()), "line {line} col {col}");
                assert_eq!(a.grid.style_of(x), b.grid.style_of(y), "line {line} col {col}");
            }
        }
    }

    #[test]
    fn the_size_cap_drops_the_oldest_history_rows_and_keeps_the_screen() {
        let program: String = (0..200).map(|n| format!("row {n:03}\r\n")).collect();
        let original = parsed(program.as_bytes(), 20, 4);
        let bytes = encode(&original.grid().lock(), 400);
        assert!(bytes.len() <= 400, "{}", bytes.len());
        let copy = parsed(&bytes, 20, 4);
        let rows = rows_of(&copy);
        // The screen is intact; history kept from the newest end.
        assert_eq!(rows[rows.len() - 4..], rows_of(&original)[rows_of(&original).len() - 4..]);
        assert!(rows.iter().any(|r| r == "row 199"));
        assert!(!rows.iter().any(|r| r == "row 000"));
    }

    #[test]
    fn a_saved_file_reads_back_and_damaged_ones_are_refused() {
        let dir = std::env::temp_dir().join(format!("herdr-snapshot-{}", std::process::id()));
        fs::create_dir_all(&dir).unwrap();
        let path = dir.join("t1.snap");
        let original = parsed(b"hello\r\nworld", 30, 5);
        save(&original.grid().lock(), "server/t1", &path).unwrap();
        let saved = load(&path).unwrap();
        assert_eq!((saved.cols, saved.rows, saved.pane.as_str()), (30, 5, "server/t1"));
        let copy = restore(saved).unwrap();
        wait_quiet(&copy);
        assert_eq!(copy.grid_size(), (30, 5));
        assert!(copy.screen_text().contains("world"));
        assert!(parse(b"no header".to_vec()).is_err());
        assert!(parse(b"{\"v\":2,\"cols\":3,\"rows\":3,\"pane\":\"t\"}\n".to_vec()).is_err());
        assert!(parse(b"{\"v\":1,\"cols\":0,\"rows\":3,\"pane\":\"t\"}\n".to_vec()).is_err());
        assert!(parse(vec![b'x'; MAX_BYTES + MAX_HEADER + 1]).is_err());
        fs::remove_dir_all(&dir).unwrap();
    }
}
