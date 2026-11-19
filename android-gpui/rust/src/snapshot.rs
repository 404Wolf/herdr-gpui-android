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
//! copy is the phone's own transcript of it (`transcript`) and then its
//! screen, written as plain rows: read back, it scrolls like any history.
//!
//! File (version 2): one JSON header line (`{"v":2,"cols":..,"rows":..,
//! "pane":"..","history":..,"complete":..,"full_screen":..}`), then the bytes.
//! The phone seals the whole file before it reaches the disk (PaneSnapshots);
//! version 1 files (no history count) are still read once, to migrate them.
use crate::remote::{Error, Result};
use rio_vt::crosswords::Crosswords;
use rio_vt::crosswords::formatter::write_sgr;
use rio_vt::crosswords::pos::{Column, Line};
use rio_vt::crosswords::style::Style;
use rio_vt::event::EventListener;
use serde_json::{Value, json};
use std::{fmt::Write as _, io, sync::Arc};
#[cfg(test)]
use std::{fs, io::Read, path::Path};

/// The most a saved pane may take (Wolf's budget: 1 MiB a pane); the oldest
/// history rows give way first.
pub(crate) const MAX_BYTES: usize = crate::transcript::MAX_BYTES;
/// The most a copy read back may be: version 1 copies were up to 4 MiB.
const MAX_READ: usize = 4 * 1024 * 1024;
const MAX_HEADER: usize = 4096;
const VERSION: u64 = 2;

/// The grid's history and screen as VT bytes, at most `max_bytes` (whole
/// oldest rows are dropped to fit). Every screen row is written, blank or
/// not, so replayed rows land on the same screen lines.
#[cfg(test)]
pub(crate) fn encode<U: EventListener>(grid: &Crosswords<U>, max_bytes: usize) -> Vec<u8> {
    encode_grid(grid, max_bytes).0
}

/// [`encode`], and how many history rows it kept.
fn encode_grid<U: EventListener>(grid: &Crosswords<U>, max_bytes: usize) -> (Vec<u8>, usize) {
    let (rows, cols) = (grid.screen_lines(), grid.columns());
    if rows == 0 || cols == 0 {
        return (Vec::new(), 0);
    }
    let history = grid.history_size();
    let lines: Vec<String> = (-(history as i32)..rows as i32).map(|line| row(grid, line, cols)).collect();
    assemble(lines, history, &tail(grid), max_bytes)
}

/// A full-screen program's copy: the phone's `transcript` of it, then its
/// screen now, as rows (oldest dropped to fit `max_bytes`); how many history
/// rows were kept; and which of them mark rows that were missed.
pub(crate) fn encode_transcript<U: EventListener>(
    grid: &Crosswords<U>,
    transcript: &crate::transcript::Transcript,
    max_bytes: usize,
) -> (Vec<u8>, usize, Vec<usize>) {
    let screen = crate::transcript::screen_rows(grid);
    let rows: Vec<&crate::transcript::Row> = transcript.reader_rows(&screen).collect();
    let marks: Vec<usize> =
        rows.iter().enumerate().filter(|(_, row)| crate::transcript::is_mark(row)).map(|(i, _)| i).collect();
    let lines: Vec<String> = rows.iter().map(|r| r.vt.clone()).collect();
    let history = lines.len() - screen.len();
    let (bytes, kept) = assemble(lines, history, &tail(grid), max_bytes);
    let dropped = history - kept;
    let marks = marks.into_iter().filter_map(|m| m.checked_sub(dropped)).collect();
    (bytes, kept, marks)
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
fn assemble(mut lines: Vec<String>, history: usize, tail: &str, max_bytes: usize) -> (Vec<u8>, usize) {
    let mut total = START.len() + tail.len() + lines.iter().map(|l| l.len() + 2).sum::<usize>();
    // Only history may go: the screen rows place everything else.
    let mut dropped = 0;
    while total > max_bytes && dropped < history {
        total -= lines[dropped].len() + 2;
        dropped += 1;
    }
    lines.drain(..dropped);
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

/// The rows of a saved copy's bytes (as [`assemble`] wrote them), each with
/// its text: a full-screen pane's transcript read back.
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
    let cells = &grid.grid[Line(line)];
    let last = (0..cols).rev().find(|&col| {
        let square = &cells[Column(col)];
        let c = square.c();
        (c != ' ' && c != '\0') || grid.grid.style_of(square).renders_on_blank()
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
        let link = grid.cell_hyperlink(Line(line), Column(col)).map(|l| (clean_osc(l.id()), clean_osc(l.uri())));
        if link != linked {
            match &link {
                Some((id, uri)) => {
                    let _ = write!(out, "\x1b]8;{};{}\x1b\\", if id.is_empty() { String::new() } else { format!("id={id}") }, uri);
                }
                None => out.push_str("\x1b]8;;\x1b\\"),
            }
            linked = link;
        }
        let style = grid.grid.style_of(square);
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

/// A link's id or target as it may stand in an OSC 8: no control characters
/// (they would end or break the sequence), and no `;` in the id.
fn clean_osc(text: &str) -> String {
    text.chars().filter(|c| !c.is_control()).take(2048).collect()
}

/// A saved pane read back: its grid size and the bytes that redraw it.
#[derive(Debug, PartialEq)]
pub(crate) struct Saved {
    pub cols: u16,
    pub rows: u16,
    pub pane: String,
    pub bytes: Vec<u8>,
    /// How many of its rows are history (above the screen); None from a version 1 file.
    pub history: Option<usize>,
    /// The history reaches the program's first row.
    pub complete: bool,
    /// It is a full-screen program's transcript and screen.
    pub full_screen: bool,
    /// The core it was copied from (its boot id), "" when unknown.
    pub boot: String,
    /// The terminal's incarnation (its `created_ms`) and the program it ran
    /// when copied: with the core and pane, which terminal this is.
    pub born: u64,
    pub program: String,
    /// History rows that mark rows missed (see [`crate::transcript::GAP_TEXT`]).
    pub gaps: Vec<usize>,
}

impl Saved {
    /// Bytes to draw on a `cols`×`rows` grid, with no history count (a note, a test).
    pub(crate) fn of(cols: u16, rows: u16, pane: &str, bytes: Vec<u8>) -> Self {
        Self {
            cols,
            rows,
            pane: pane.into(),
            bytes,
            history: None,
            complete: false,
            full_screen: false,
            boot: String::new(),
            born: 0,
            program: String::new(),
            gaps: Vec::new(),
        }
    }

    /// Which terminal this copy is of.
    pub(crate) fn ident(&self) -> crate::remote::Ident {
        crate::remote::Ident { boot: self.boot.clone(), pane: self.pane.clone(), born: self.born, program: self.program.clone() }
    }
}

/// What a copy's header says besides its size.
#[derive(Debug, Default, Clone, PartialEq, Eq)]
pub(crate) struct Meta {
    pub history: usize,
    pub complete: bool,
    pub full_screen: bool,
    pub gaps: Vec<usize>,
}

pub(crate) fn header(cols: u16, rows: u16, ident: &crate::remote::Ident, meta: Meta) -> String {
    format!(
        "{}\n",
        json!({"v":VERSION,"cols":cols,"rows":rows,"pane":ident.pane,"boot":ident.boot,"born":ident.born,
               "program":ident.program,"history":meta.history,"complete":meta.complete,
               "full_screen":meta.full_screen,"gaps":meta.gaps})
    )
}

/// A saved pane's whole file, built from the grid: copied while the grid is
/// locked, sealed and written by the phone after the lock is released. A
/// full-screen program's copy is `transcript` and its screen; anything else
/// is the grid's own history and screen.
#[cfg(test)]
pub(crate) fn copy<U: EventListener>(
    grid: &Crosswords<U>,
    pane: &str,
    boot: &str,
    transcript: Option<&crate::transcript::Transcript>,
) -> Result<Vec<u8>> {
    let ident = crate::remote::Ident { boot: boot.into(), pane: pane.into(), ..Default::default() };
    copy_of(grid, &ident, transcript)
}

/// [`copy`] of the terminal `ident` names.
pub(crate) fn copy_of<U: EventListener>(
    grid: &Crosswords<U>,
    ident: &crate::remote::Ident,
    transcript: Option<&crate::transcript::Transcript>,
) -> Result<Vec<u8>> {
    let cols = u16::try_from(grid.columns()).map_err(|_| Error::Capacity)?;
    let rows = u16::try_from(grid.screen_lines()).map_err(|_| Error::Capacity)?;
    let full_screen = grid.mode().contains(rio_vt::crosswords::Mode::ALT_SCREEN);
    let (body, meta) = match transcript.filter(|_| full_screen) {
        Some(transcript) => {
            let (body, history, gaps) = encode_transcript(grid, transcript, MAX_BYTES);
            let complete = transcript.complete() && history == transcript.rows();
            (body, Meta { history, complete, full_screen, gaps })
        }
        None => {
            let (body, history) = encode_grid(grid, MAX_BYTES);
            (body, Meta { history, complete: false, full_screen, gaps: Vec::new() })
        }
    };
    let mut file = header(cols, rows, ident, meta).into_bytes();
    file.extend(body);
    Ok(file)
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
    write(&copy(grid, pane, "", None)?, path)
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
    let history = if version == Some(VERSION) {
        Some(header["history"].as_u64().and_then(|n| usize::try_from(n).ok()).ok_or_else(invalid)?)
    } else {
        None
    };
    // Where the copy marks rows missed: history rows, ascending, bounded.
    let gaps: Vec<usize> = match (history, header["gaps"].as_array()) {
        (Some(history), Some(gaps)) if gaps.len() <= 4096 => {
            let gaps: Vec<usize> =
                gaps.iter().filter_map(|g| g.as_u64().and_then(|n| usize::try_from(n).ok())).filter(|g| *g < history).collect();
            if gaps.windows(2).any(|w| w[0] >= w[1]) {
                return Err(invalid());
            }
            gaps
        }
        _ => Vec::new(),
    };
    bytes.drain(..=end);
    Ok(Saved {
        cols,
        rows,
        pane,
        bytes,
        history,
        complete: header["complete"] == true,
        full_screen: header["full_screen"] == true,
        boot: header["boot"].as_str().unwrap_or("").chars().take(128).collect(),
        born: header["born"].as_u64().unwrap_or(0),
        program: header["program"].as_str().unwrap_or("").chars().take(64).collect(),
        gaps,
    })
}

/// A full-screen pane's transcript, read back from its saved copy.
pub(crate) fn transcript_of(saved: &Saved) -> Option<crate::transcript::Transcript> {
    if !saved.full_screen {
        return None;
    }
    let mut rows = saved_rows(&saved.bytes);
    let history = saved.history?;
    let screen = rows.len().checked_sub(history)?;
    // The mark rows go; where they stood, rows were missed.
    let mut gaps = Vec::with_capacity(saved.gaps.len());
    for (taken, mark) in saved.gaps.iter().enumerate() {
        gaps.push(mark - taken);
    }
    for mark in saved.gaps.iter().rev() {
        rows.remove(*mark);
    }
    Some(crate::transcript::Transcript::restored_with_gaps(rows, screen, usize::from(saved.cols), saved.complete, &gaps))
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
        let bytes = copy(&session.grid().lock(), "t1", "boot", None).unwrap();
        let back = restore(parse(bytes).unwrap()).unwrap();
        wait_quiet(&back);
        let grid = back.grid().lock();
        let line = (0..grid.screen_lines() as i32)
            .map(Line)
            .find(|line| (0..grid.columns()).any(|c| grid.cell_hyperlink(*line, Column(c)).is_some()));
        let link = line.and_then(|line| (0..grid.columns()).find_map(|c| grid.cell_hyperlink(line, Column(c))));
        assert_eq!(link.map(|l| l.uri().to_owned()).as_deref(), Some("https://example.test/notes"));
        // The row's text is still the label.
        assert!(saved_rows(&copy(&session.grid().lock(), "t1", "boot", None).unwrap()).iter().any(|r| r.text.contains("see release notes here")));
    }

    /// Review mutant M5: only a full-screen grid's copy is its transcript.
    #[test]
    fn ask2_a_plain_pane_is_saved_from_its_grid_never_from_a_transcript() {
        let session = parsed(b"$ ls\r\nREADME.md\r\n$ ", 30, 5);
        let transcript = crate::transcript::Transcript::restored(
            vec![crate::transcript::Row::plain("a transcript row from elsewhere")],
            0,
            30,
            false,
        );
        let bytes = copy(&session.grid().lock(), "t1", "boot", Some(&transcript)).unwrap();
        let text = String::from_utf8_lossy(&bytes);
        assert!(text.contains("README.md"));
        assert!(!text.contains("a transcript row from elsewhere"));
        assert!(!parse(bytes).unwrap().full_screen);
    }

    /// Review P2-4: a saved copy carries the gap mark, and reads it back as the gap.
    #[test]
    fn ask2_a_saved_copy_keeps_the_mark_where_rows_were_missed() {
        let rows = |texts: &[&str]| texts.iter().map(|t| crate::transcript::Row::plain(t)).collect::<Vec<_>>();
        let restored = crate::transcript::Transcript::restored_with_gaps(
            rows(&["before the gap", "after the gap", "on screen"]),
            1,
            30,
            false,
            &[1],
        );
        assert!(restored.wants_seed());
        let screen = rows(&["on screen"]);
        let all: Vec<String> = restored.reader_rows(&screen).map(|r| r.text.clone()).collect();
        assert_eq!(all, ["before the gap", crate::transcript::GAP_TEXT, "after the gap", "on screen"]);
        // Output that happens to read like the mark is output, never a gap.
        let plain = crate::transcript::Transcript::restored(rows(&["a row", crate::transcript::GAP_TEXT, "on screen"]), 1, 30, false);
        assert_eq!(plain.rows(), 2);
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
