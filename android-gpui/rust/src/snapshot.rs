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
//! File: one JSON header line (`{"v":1,"cols":..,"rows":..,"pane":".."}`),
//! then the bytes. Written to a temporary file and renamed over the old one.
use crate::remote::{Error, Result};
use rio_vt::crosswords::Crosswords;
use rio_vt::crosswords::formatter::write_sgr;
use rio_vt::crosswords::pos::{Column, Line};
use rio_vt::crosswords::style::Style;
use rio_vt::event::EventListener;
use serde_json::{Value, json};
use std::{
    fmt::Write as _,
    fs,
    io::{self, Read},
    path::Path,
    sync::Arc,
};

/// The most a saved pane may take; the oldest history rows give way first.
pub(crate) const MAX_BYTES: usize = 4 * 1024 * 1024;
const MAX_HEADER: usize = 4096;
const VERSION: u64 = 1;

/// The grid's history and screen as VT bytes, at most `max_bytes` (whole
/// oldest rows are dropped to fit). Every screen row is written, blank or
/// not, so replayed rows land on the same screen lines.
pub(crate) fn encode<U: EventListener>(grid: &Crosswords<U>, max_bytes: usize) -> Vec<u8> {
    let (rows, cols) = (grid.screen_lines(), grid.columns());
    if rows == 0 || cols == 0 {
        return Vec::new();
    }
    let history = grid.history_size() as i32;
    let mut lines: Vec<String> = (-history..rows as i32).map(|line| row(grid, line, cols)).collect();
    let mut tail = String::from("\x1b[0m");
    let cursor = grid.grid.cursor.pos;
    let _ = write!(tail, "\x1b[{};{}H", cursor.row.0.max(0) + 1, cursor.col.0 + 1);
    // \r\n between rows, a reset and the cursor at the end.
    const START: &str = "\x1b[0m";
    let mut total = START.len() + tail.len() + lines.iter().map(|l| l.len() + 2).sum::<usize>();
    // Only history may go: the screen rows place everything else.
    let mut dropped = 0;
    while total > max_bytes && dropped < history as usize {
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
    out.push_str(&tail);
    out.into_bytes()
}

/// One row, self-contained: it starts and ends in the default style, so any
/// prefix of history can be dropped. Trailing blanks that draw nothing are left out.
fn row<U: EventListener>(grid: &Crosswords<U>, line: i32, cols: usize) -> String {
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
    let mut col = 0;
    while col <= last {
        let square = &cells[Column(col)];
        if square.is_spacer() {
            col += 1;
            continue;
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
    if current != Style::default() {
        out.push_str("\x1b[0m");
    }
    out
}

/// A saved pane read back: its grid size and the bytes that redraw it.
#[derive(Debug, PartialEq)]
pub(crate) struct Saved {
    pub cols: u16,
    pub rows: u16,
    pub pane: String,
    pub bytes: Vec<u8>,
}

pub(crate) fn header(cols: u16, rows: u16, pane: &str) -> String {
    format!("{}\n", json!({"v":VERSION,"cols":cols,"rows":rows,"pane":pane}))
}

/// Writes `pane`'s grid to `path`, atomically.
pub(crate) fn save<U: EventListener>(grid: &Crosswords<U>, pane: &str, path: &Path) -> Result<()> {
    let cols = u16::try_from(grid.columns()).map_err(|_| Error::Capacity)?;
    let rows = u16::try_from(grid.screen_lines()).map_err(|_| Error::Capacity)?;
    let mut file = header(cols, rows, pane).into_bytes();
    file.extend(encode(grid, MAX_BYTES));
    let tmp = path.with_extension("tmp");
    {
        let mut out = fs::File::create(&tmp)?;
        io::Write::write_all(&mut out, &file)?;
        out.sync_all()?;
    }
    fs::rename(&tmp, path)?;
    Ok(())
}

/// Reads a saved pane, refusing anything malformed or larger than a save makes.
pub(crate) fn load(path: &Path) -> Result<Saved> {
    let mut bytes = Vec::new();
    fs::File::open(path)?
        .take((MAX_BYTES + MAX_HEADER + 1) as u64)
        .read_to_end(&mut bytes)?;
    parse(bytes)
}

pub(crate) fn parse(mut bytes: Vec<u8>) -> Result<Saved> {
    let invalid = || Error::Snapshot;
    if bytes.len() > MAX_BYTES + MAX_HEADER {
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
    let (Some(cols), Some(rows), true) = (size("cols"), size("rows"), header["v"] == VERSION) else {
        return Err(invalid());
    };
    let pane = header["pane"].as_str().ok_or_else(invalid)?.to_owned();
    bytes.drain(..=end);
    Ok(Saved { cols, rows, pane, bytes })
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
    use super::*;
    use std::time::{Duration, Instant};

    fn parsed(text: &[u8], cols: u16, rows: u16) -> Arc<gpui_rio::Session> {
        let session = restore(Saved { cols, rows, pane: "t1".into(), bytes: text.to_vec() }).unwrap();
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
