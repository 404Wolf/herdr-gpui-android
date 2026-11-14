//! The terminal state machine on a caller's byte stream. One thread parses the
//! remote program's output into the grid; one sends input. Replies a terminal
//! owes the program (device and cursor reports, colour and size queries) are
//! answered as they are parsed and leave through the same input queue.

use crate::{Error, KeyPress, Result, TerminalTheme, key_bytes};
use rio_vt::{
    ansi::CursorShape,
    config::colors::{AnsiColor, ColorRgb},
    crosswords::{
        Crosswords, CrosswordsSize, Mode,
        grid::Scroll,
        pos::{Column, Line, Pos},
        square::Wide,
    },
    event::{EventListener, RioEvent, WindowId, WindowSize, sync::FairMutex},
    performer::handler::Processor,
};
use std::{
    io::{ErrorKind, Read, Write},
    sync::{
        Arc, Mutex, MutexGuard, PoisonError,
        atomic::{AtomicBool, Ordering},
        mpsc,
    },
    thread,
    time::Instant,
};

/// Input chunks waiting for the writer. A full queue refuses more input
/// rather than blocking the caller, which is usually the UI thread.
const INPUT_QUEUE: usize = 256;
const READ_BUFFER: usize = 64 * 1024;
const SCROLLBACK: usize = 10_000;

#[doc(hidden)]
#[derive(Clone)]
pub struct Listener {
    dirty: Arc<AtomicBool>,
    events: mpsc::Sender<RioEvent>,
}

impl EventListener for Listener {
    fn send_event(&self, event: RioEvent, _: WindowId) {
        if matches!(
            event,
            RioEvent::PtyWrite(..)
                | RioEvent::ColorRequest(..)
                | RioEvent::TextAreaSizeRequest(..)
                | RioEvent::Title(..)
        ) {
            let _ = self.events.send(event);
        }
        self.dirty.store(true, Ordering::Release);
    }

    fn send_event_with_high_priority(&self, event: RioEvent, id: WindowId) {
        self.send_event(event, id);
    }

    fn send_redraw(&self, _: WindowId) {
        self.dirty.store(true, Ordering::Release);
    }
}

/// State the reader thread needs to answer queries, kept by the view.
struct Shared {
    theme: Mutex<TerminalTheme>,
    size: Mutex<WindowSize>,
    title: Mutex<Option<String>>,
    /// The pane's last measured grid: an offer to the remote side.
    offer: Mutex<(u16, u16)>,
    /// The remote side's authoritative grid, once it negotiated one: the
    /// program wraps at this width, so the grid follows it, not the pane.
    negotiated: Mutex<Option<(u16, u16)>>,
    /// Grid changes queued in stream order, for the reader to apply at its
    /// parser boundary (after flushing a pending synchronized update).
    grid_controls: Mutex<std::collections::VecDeque<(u16, u16)>>,
}

fn lock<T>(mutex: &Mutex<T>) -> MutexGuard<'_, T> {
    mutex.lock().unwrap_or_else(PoisonError::into_inner)
}

/// A terminal fed by a byte stream. Dropping the last handle stops the writer;
/// the reader stops when the stream reaches its end, which the stream's owner
/// controls by closing its transport.
pub struct Session {
    grid: Arc<FairMutex<Crosswords<Listener>>>,
    input: mpsc::SyncSender<Vec<u8>>,
    dirty: Arc<AtomicBool>,
    alive: Arc<AtomicBool>,
    shared: Arc<Shared>,
    // Type-erased so one session type serves every transport.
    resize: Box<dyn Fn(u16, u16) + Send + Sync>,
}

impl Session {
    /// Parses `output` into a new `columns` × `lines` grid and writes input to
    /// `input`. Give `output` a short read timeout: an idle stream then still
    /// flushes a synchronized update the program never ended. `resize` is told
    /// each new grid size, for the remote side's window size.
    pub fn start<R, W, F>(
        output: R,
        input: W,
        resize: F,
        theme: TerminalTheme,
        columns: u16,
        lines: u16,
    ) -> Result<Arc<Self>>
    where
        R: Read + Send + 'static,
        W: Write + Send + 'static,
        F: Fn(u16, u16) + Send + Sync + 'static,
    {
        let (columns, lines) = (columns.max(2), lines.max(1));
        let (events, replies) = mpsc::channel();
        let dirty = Arc::new(AtomicBool::new(true));
        let listener = Listener {
            dirty: dirty.clone(),
            events,
        };
        let grid = Arc::new(FairMutex::new(Crosswords::new(
            CrosswordsSize::new(usize::from(columns), usize::from(lines)),
            CursorShape::Block,
            listener,
            WindowId::from(0),
            0,
            SCROLLBACK,
        )));
        let shared = Arc::new(Shared {
            theme: Mutex::new(theme),
            size: Mutex::new(WindowSize {
                rows: lines,
                cols: columns,
                width: 0,
                height: 0,
            }),
            title: Mutex::new(None),
            offer: Mutex::new((columns, lines)),
            negotiated: Mutex::new(None),
            grid_controls: Mutex::new(std::collections::VecDeque::new()),
        });
        let (input_queue, queued) = mpsc::sync_channel::<Vec<u8>>(INPUT_QUEUE);
        thread::Builder::new()
            .name("rio-input".into())
            .spawn(move || {
                let mut input = input;
                for bytes in queued {
                    if input
                        .write_all(&bytes)
                        .and_then(|()| input.flush())
                        .is_err()
                    {
                        break;
                    }
                }
            })
            .map_err(|source| Error::Thread {
                role: "input",
                source,
            })?;
        let alive = Arc::new(AtomicBool::new(true));
        let reader = Reader {
            grid: grid.clone(),
            replies,
            shared: shared.clone(),
            input: input_queue.clone(),
            dirty: dirty.clone(),
            alive: alive.clone(),
        };
        thread::Builder::new()
            .name("rio-output".into())
            .spawn(move || reader.run(output))
            .map_err(|source| Error::Thread {
                role: "output",
                source,
            })?;
        Ok(Arc::new(Self {
            grid,
            input: input_queue,
            dirty,
            alive,
            shared,
            resize: Box::new(resize),
        }))
    }

    #[doc(hidden)]
    pub fn grid(&self) -> &Arc<FairMutex<Crosswords<Listener>>> {
        &self.grid
    }

    /// False once the stream has ended; the last screen stays readable.
    pub fn is_alive(&self) -> bool {
        self.alive.load(Ordering::Acquire)
    }

    /// Whether anything changed since the last call.
    pub fn take_dirty(&self) -> bool {
        self.dirty.swap(false, Ordering::AcqRel)
    }

    /// The title the remote program last set (OSC 0 or 2), if any.
    pub fn title(&self) -> Option<String> {
        lock(&self.shared.title).clone()
    }

    /// The rows on screen as text: trailing blanks and blank last rows trimmed.
    pub fn screen_text(&self) -> String {
        let grid = self.grid.lock();
        let offset = grid.display_offset() as i32;
        let mut text = String::new();
        for row in 0..grid.screen_lines() as i32 {
            let start = text.len();
            for column in 0..grid.columns() {
                let pos = Pos::new(Line(row - offset), Column(column));
                if grid.grid[pos].wide() == Wide::Spacer {
                    continue;
                }
                text.extend(
                    grid.grid
                        .cell_text(pos)
                        .map(|c| if c == '\0' { ' ' } else { c }),
                );
            }
            text.truncate(start + text[start..].trim_end().len());
            text.push('\n');
        }
        text.truncate(text.trim_end().len());
        text
    }

    /// The palette colour queries are answered with.
    pub fn set_theme(&self, theme: TerminalTheme) {
        *lock(&self.shared.theme) = theme;
    }

    /// The pane measured `columns` × `lines` cells: offers that size to the
    /// remote side (only when it changes) and, unless the remote side
    /// negotiated its own grid, resizes the grid to it.
    pub fn resize(&self, columns: u16, lines: u16, width: u16, height: u16) {
        let (columns, lines) = (columns.max(2), lines.max(1));
        let offered = {
            let mut offer = lock(&self.shared.offer);
            let changed = *offer != (columns, lines);
            *offer = (columns, lines);
            changed
        };
        let target = lock(&self.shared.negotiated).unwrap_or((columns, lines));
        let regrid = self.apply_grid(target, Some((width, height)));
        if offered {
            (self.resize)(columns, lines);
        }
        if offered || regrid {
            self.dirty.store(true, Ordering::Release);
        }
    }

    /// The remote side's authoritative grid (it sends one before repainting at
    /// that size). The grid follows it; nothing is offered back. False for an
    /// empty size.
    pub fn set_grid(&self, columns: u16, lines: u16) -> bool {
        if columns == 0 || lines == 0 {
            return false;
        }
        *lock(&self.shared.negotiated) = Some((columns, lines));
        self.apply_grid((columns, lines), None);
        self.dirty.store(true, Ordering::Release);
        true
    }

    /// The remote side's grid, in stream order: the transport calls this
    /// where the grid change sits between stream bytes, then returns from
    /// `read` without more bytes. The reader then ends any synchronized
    /// update pending under the old grid (its buffered bytes are parsed at
    /// that size) and only then applies the new grid, before reading on.
    pub fn queue_grid(&self, columns: u16, lines: u16) -> bool {
        if columns == 0 || lines == 0 {
            return false;
        }
        lock(&self.shared.grid_controls).push_back((columns, lines));
        true
    }

    /// Back to the pane's own geometry (a path that does not negotiate).
    pub fn clear_grid(&self) {
        *lock(&self.shared.negotiated) = None;
        let offer = *lock(&self.shared.offer);
        self.apply_grid(offer, None);
        self.dirty.store(true, Ordering::Release);
    }

    /// The grid's size now: the negotiated one, else the pane's.
    pub fn grid_size(&self) -> (u16, u16) {
        let size = lock(&self.shared.size);
        (size.cols, size.rows)
    }

    /// Whether the remote side negotiated the grid.
    pub fn negotiated(&self) -> bool {
        lock(&self.shared.negotiated).is_some()
    }

    /// Resizes the cell grid to `target` when it differs; true when it did.
    fn apply_grid(&self, target: (u16, u16), pixels: Option<(u16, u16)>) -> bool {
        apply_grid(&self.grid, &self.shared, target, pixels)
    }
}

/// Resizes `grid` to `target` (and records it as the window size); true when it changed.
fn apply_grid(
    grid: &FairMutex<Crosswords<Listener>>,
    shared: &Shared,
    target: (u16, u16),
    pixels: Option<(u16, u16)>,
) -> bool {
    {
        let (columns, lines) = (target.0.max(2), target.1.max(1));
        let changed = {
            let mut size = lock(&shared.size);
            let changed = size.cols != columns || size.rows != lines;
            size.cols = columns;
            size.rows = lines;
            if let Some((width, height)) = pixels {
                size.width = width;
                size.height = height;
            }
            changed
        };
        if changed {
            grid.lock().resize(CrosswordsSize::new(
                usize::from(columns),
                usize::from(lines),
            ));
        }
        changed
    }
}

impl Session {

    /// Typed text, sent as is. Returns false when the input queue is full.
    pub fn type_text(&self, text: &str) -> bool {
        self.follow_input();
        self.write(text.as_bytes().to_vec())
    }

    /// Pasted text: escape characters removed, line breaks as carriage returns,
    /// bracketed when the program asked for bracketed paste.
    pub fn paste(&self, text: &str) -> bool {
        let bracketed = self.follow_input().contains(Mode::BRACKETED_PASTE);
        let text = text
            .replace('\x1b', "")
            .replace("\r\n", "\r")
            .replace('\n', "\r");
        self.write(if bracketed {
            format!("\x1b[200~{text}\x1b[201~").into_bytes()
        } else {
            text.into_bytes()
        })
    }

    /// A non-text key. Returns false when it is not a terminal key or the
    /// input queue is full.
    pub fn key(&self, key: &KeyPress) -> bool {
        let mode = self.grid.lock().mode();
        // Shift+Enter is a newline that does not submit, as desktop terminals
        // send it: the kitty keyboard protocol's CSI 13;2u when the program
        // turned the protocol on, else ESC CR (Alt+Enter), which Claude Code
        // and Codex read as a newline.
        // Ctrl+Enter is Claude Code's "send now" for a message queued while
        // it works: CSI 13;5u under the kitty protocol, else xterm's
        // modifyOtherKeys form, which it reads the same.
        let bytes = if key.key == "enter" && key.control && !key.shift && !key.alt {
            Some(if mode.intersects(Mode::KITTY_KEYBOARD_PROTOCOL) {
                b"\x1b[13;5u".to_vec()
            } else {
                b"\x1b[27;5;13~".to_vec()
            })
        } else if key.key == "enter" && key.shift && !key.control && !key.alt {
            Some(if mode.intersects(Mode::KITTY_KEYBOARD_PROTOCOL) {
                b"\x1b[13;2u".to_vec()
            } else {
                b"\x1b\r".to_vec()
            })
        } else {
            key_bytes(key, mode.contains(Mode::APP_CURSOR))
        };
        let Some(bytes) = bytes else {
            return false;
        };
        self.follow_input();
        self.write(bytes)
    }

    /// Input returns the view to the live screen and drops any selection.
    /// A touch scroll of `lines` (positive = back in history) as the program
    /// wants it: wheel reports when it asked for mouse events (Claude Code,
    /// Codex and other full-screen TUIs), arrow keys in the alternate screen
    /// without mouse reporting, which is what xterm's alternate-scroll does.
    /// Returns false when the program wants neither, so the caller scrolls
    /// the local scrollback instead. Never follows the output to the bottom.
    pub fn wheel(&self, lines: i32) -> bool {
        if lines == 0 {
            return true;
        }
        let (mode, columns, rows) = {
            let grid = self.grid.lock();
            (grid.mode(), grid.columns(), grid.screen_lines())
        };
        let count = lines.unsigned_abs().min(30) as usize;
        let up = lines > 0;
        let bytes = if mode.intersects(Mode::MOUSE_MODE) {
            // Report at the middle of the screen: the pane under the finger is
            // the whole terminal here.
            let (column, row) = (columns / 2 + 1, rows / 2 + 1);
            let button = if up { 64 } else { 65 };
            let one = if mode.contains(Mode::SGR_MOUSE) {
                format!("\x1b[<{button};{column};{row}M").into_bytes()
            } else {
                let encode = |value: usize| u8::try_from(32 + value.min(223)).unwrap_or(255);
                vec![0x1b, b'[', b'M', 32 + button, encode(column), encode(row)]
            };
            one.repeat(count)
        } else if mode.contains(Mode::ALT_SCREEN) {
            let one: &[u8] = match (up, mode.contains(Mode::APP_CURSOR)) {
                (true, true) => b"\x1bOA",
                (true, false) => b"\x1b[A",
                (false, true) => b"\x1bOB",
                (false, false) => b"\x1b[B",
            };
            one.repeat(count)
        } else {
            return false;
        };
        self.write(bytes)
    }

    fn follow_input(&self) -> Mode {
        let mut grid = self.grid.lock();
        grid.scroll_display(Scroll::Bottom);
        grid.selection = None;
        self.dirty.store(true, Ordering::Release);
        grid.mode()
    }

    fn write(&self, bytes: Vec<u8>) -> bool {
        bytes.is_empty() || self.input.try_send(bytes).is_ok()
    }
}

struct Reader {
    grid: Arc<FairMutex<Crosswords<Listener>>>,
    replies: mpsc::Receiver<RioEvent>,
    shared: Arc<Shared>,
    input: mpsc::SyncSender<Vec<u8>>,
    dirty: Arc<AtomicBool>,
    alive: Arc<AtomicBool>,
}

impl Reader {
    fn run(self, mut output: impl Read) {
        let mut processor = Processor::default();
        let mut buffer = vec![0; READ_BUFFER];
        loop {
            let read = match output.read(&mut buffer) {
                Ok(0) => break,
                Ok(read) => read,
                Err(error) if error.kind() == ErrorKind::Interrupted => continue,
                Err(error)
                    if matches!(error.kind(), ErrorKind::WouldBlock | ErrorKind::TimedOut) =>
                {
                    0
                }
                Err(_) => break,
            };
            // Grid changes the transport queued at this point in the stream:
            // end a synchronized update still pending under the old grid (its
            // bytes are parsed at that size), then switch grids, before any
            // later byte is read.
            let controls: Vec<(u16, u16)> = lock(&self.shared.grid_controls).drain(..).collect();
            for (columns, lines) in controls {
                let mut grid = self.grid.lock();
                if processor.sync_timeout().sync_timeout().is_some() {
                    processor.stop_sync(&mut *grid);
                    self.answer(&grid);
                }
                drop(grid);
                *lock(&self.shared.negotiated) = Some((columns, lines));
                apply_grid(&self.grid, &self.shared, (columns, lines), None);
                self.dirty.store(true, Ordering::Release);
            }
            let flush = processor
                .sync_timeout()
                .sync_timeout()
                .is_some_and(|deadline| deadline <= Instant::now());
            if read == 0 && !flush {
                continue;
            }
            let mut grid = self.grid.lock();
            processor.advance(&mut *grid, &buffer[..read]);
            self.answer(&grid);
            drop(grid);
            self.dirty.store(true, Ordering::Release);
        }
        self.alive.store(false, Ordering::Release);
        self.dirty.store(true, Ordering::Release);
    }

    fn answer(&self, grid: &Crosswords<Listener>) {
        for event in self.replies.try_iter() {
            let reply = match event {
                RioEvent::PtyWrite(_, text) => text,
                RioEvent::ColorRequest(_, index, format) => {
                    format(resolve_index(grid, *lock(&self.shared.theme), index))
                }
                RioEvent::TextAreaSizeRequest(_, format) => format(*lock(&self.shared.size)),
                RioEvent::Title(_, title) => {
                    *lock(&self.shared.title) = Some(title);
                    continue;
                }
                _ => continue,
            };
            // A program that floods queries cannot grow memory without bound.
            let _ = self.input.try_send(reply.into_bytes());
        }
    }
}

#[doc(hidden)]
/// Which part of the grid is drawn: the grid cell (row, column) at the pane's
/// top-left. A grid larger than the pane gets a pane-sized window that starts
/// at the top-left and moves only as far as needed to keep the caret on
/// screen (it follows the caret). A grid shorter than the pane is drawn whole
/// against the pane's bottom: the row is negative, the rows above it are left
/// empty, so the program's prompt stays at the bottom by the host's controls
/// (the remote side's grid can be shorter, e.g. kept at its keyboard-up size
/// while the link is down). A narrower grid is drawn from column 0, the rest
/// of each row left empty.
pub fn viewport_origin(grid: (u16, u16), pane: (u16, u16), cursor: (i32, usize)) -> (i32, usize) {
    let (grid_cols, grid_rows) = (usize::from(grid.0), i32::from(grid.1));
    let (pane_cols, pane_rows) = (usize::from(pane.0.max(1)), i32::from(pane.1.max(1)));
    let row = if grid_rows > pane_rows {
        (cursor.0 - pane_rows + 1).clamp(0, grid_rows - pane_rows)
    } else {
        grid_rows - pane_rows
    };
    let col = if grid_cols > pane_cols {
        (cursor.1 + 1).saturating_sub(pane_cols).min(grid_cols - pane_cols)
    } else {
        0
    };
    (row, col)
}

#[doc(hidden)]
/// The grid cell (column, row) painted under the point `(x, y)` in the pane,
/// with `cell` the cell's width and height and `origin` the grid cell drawn
/// at the pane's top-left (see [`viewport_origin`]). The row counts the
/// grid's screen rows from 0, as drawn: a view scrolled into history shifts
/// them, which the caller applies. `None` where no grid cell is painted: the
/// empty rows above a grid drawn against the pane's bottom and the empty
/// columns right of a narrower grid, so a touch there reaches nothing (no
/// link, no selection, no click) instead of the nearest edge cell.
pub fn cell_at(
    (x, y): (f32, f32),
    (width, height): (f32, f32),
    (row_off, col_off): (i32, usize),
    (columns, rows): (usize, usize),
) -> Option<(usize, usize)> {
    if !(x >= 0. && y >= 0. && width > 0. && height > 0.) {
        return None;
    }
    let row = (y / height).floor() as i64 + i64::from(row_off);
    let col = (x / width).floor() as i64 + i64::try_from(col_off).ok()?;
    let (row, col) = (usize::try_from(row).ok()?, usize::try_from(col).ok()?);
    (row < rows && col < columns).then_some((col, row))
}

pub fn resolve_index(grid: &Crosswords<Listener>, theme: TerminalTheme, index: usize) -> ColorRgb {
    if index < 269
        && let Some(color) = grid.colors()[index]
    {
        ColorRgb::from_color_arr(color)
    } else {
        theme.color(index)
    }
}

#[doc(hidden)]
pub fn resolve_color(
    grid: &Crosswords<Listener>,
    theme: TerminalTheme,
    color: AnsiColor,
) -> ColorRgb {
    match color {
        AnsiColor::Spec(color) => color,
        AnsiColor::Indexed(index) => resolve_index(grid, theme, usize::from(index)),
        AnsiColor::Named(color) => resolve_index(grid, theme, color as usize),
    }
}

#[cfg(test)]
mod tests {
    #![allow(clippy::unwrap_used)]
    use super::Session;
    use crate::{KeyPress, TerminalColor, TerminalTheme};
    use rio_vt::crosswords::pos::{Column, Line, Pos};
    use std::{
        io::{Read, Write},
        os::unix::net::UnixStream,
        sync::{Arc, Mutex},
        time::{Duration, Instant},
    };

    fn theme() -> TerminalTheme {
        let fg = TerminalColor {
            r: 230,
            g: 230,
            b: 230,
        };
        TerminalTheme::new(
            TerminalColor {
                r: 20,
                g: 20,
                b: 20,
            },
            fg,
            [fg; 16],
        )
    }

    /// Every size the remote side was told, in order.
    type Resizes = Arc<Mutex<Vec<(u16, u16)>>>;

    /// A session on one end of a socket pair; the other end plays the remote program.
    fn session() -> (Arc<Session>, UnixStream, Resizes) {
        let (local, remote) = UnixStream::pair().unwrap();
        local
            .set_read_timeout(Some(Duration::from_millis(20)))
            .unwrap();
        remote
            .set_read_timeout(Some(Duration::from_secs(2)))
            .unwrap();
        let resizes = Arc::new(Mutex::new(Vec::new()));
        let seen = resizes.clone();
        let session = Session::start(
            local.try_clone().unwrap(),
            local,
            move |columns, lines| seen.lock().unwrap().push((columns, lines)),
            theme(),
            20,
            4,
        )
        .unwrap();
        (session, remote, resizes)
    }

    fn until(mut ready: impl FnMut() -> bool) {
        let deadline = Instant::now() + Duration::from_secs(2);
        while !ready() {
            assert!(Instant::now() < deadline, "condition not reached in time");
            std::thread::sleep(Duration::from_millis(5));
        }
    }

    fn text_at(session: &Session, line: i32, columns: usize) -> String {
        let grid = session.grid().lock();
        (0..columns)
            .flat_map(|column| grid.grid.cell_text(Pos::new(Line(line), Column(column))))
            .collect()
    }

    fn reply(remote: &mut UnixStream, expected: &[u8]) {
        let mut received = vec![0; expected.len()];
        remote.read_exact(&mut received).unwrap();
        assert_eq!(received, expected);
    }

    #[test]
    fn output_is_parsed_into_the_grid_and_reports_go_back_to_the_program() {
        let (session, mut remote, _) = session();
        remote.write_all(b"hi\r\nthere").unwrap();
        until(|| text_at(&session, 1, 5) == "there");
        assert_eq!(text_at(&session, 0, 2), "hi");
        // A cursor position report is the terminal's answer, not screen text.
        remote.write_all(b"\x1b[6n").unwrap();
        reply(&mut remote, b"\x1b[2;6R");
        remote.write_all(b"\x1b]2;build log\x07").unwrap();
        until(|| session.title().as_deref() == Some("build log"));
    }

    #[test]
    fn keys_and_paste_follow_the_modes_the_program_set() {
        let (session, mut remote, _) = session();
        let up = KeyPress {
            key: "up".into(),
            ..KeyPress::default()
        };
        assert!(session.key(&up));
        reply(&mut remote, b"\x1b[A");
        remote.write_all(b"\x1b[?1h\x1b[?2004h").unwrap();
        until(|| {
            session
                .grid()
                .lock()
                .mode()
                .contains(rio_vt::crosswords::Mode::BRACKETED_PASTE)
        });
        assert!(session.key(&up));
        reply(&mut remote, b"\x1bOA");
        assert!(session.paste("a\nb\x1b"));
        reply(&mut remote, b"\x1b[200~a\rb\x1b[201~");
        assert!(session.type_text("ls"));
        reply(&mut remote, b"ls");
        assert!(!session.key(&KeyPress {
            key: "a".into(),
            ..KeyPress::default()
        }));
    }

    #[test]
    fn the_negotiated_grid_lays_out_the_first_replay() {
        // Attach: the core's grid (80 wide) is set before any replay byte is
        // parsed, so a 60-character line never wraps at the pane's 20 columns.
        let (session, mut remote, _) = session();
        assert!(session.set_grid(80, 24));
        let line = "x".repeat(60);
        remote.write_all(line.as_bytes()).unwrap();
        until(|| text_at(&session, 0, 60) == line);
        // Nothing spilled onto the next row.
        assert!(text_at(&session, 1, 1).trim_matches(['\0', ' ']).is_empty());
    }

    #[test]
    fn a_core_resize_never_echoes_back_and_pane_offers_are_deduplicated() {
        let (session, _remote, resizes) = session();
        // Core notifications, repeated and changing: nothing goes back.
        for (cols, rows) in [(80, 24), (80, 24), (100, 30), (60, 20)] {
            assert!(session.set_grid(cols, rows));
        }
        assert!(resizes.lock().unwrap().is_empty());
        // The pane's own size goes out once per change, never twice for the same size.
        session.resize(30, 8, 300, 160);
        session.resize(30, 8, 300, 160);
        assert!(session.set_grid(90, 25));
        session.resize(30, 8, 310, 170);
        assert_eq!(*resizes.lock().unwrap(), vec![(30, 8)]);
        assert_eq!(session.grid_size(), (90, 25));
    }

    #[test]
    fn a_grid_larger_than_the_pane_follows_the_caret() {
        use super::viewport_origin;
        // Fills the pane exactly: drawn whole from the top-left.
        assert_eq!(viewport_origin((80, 24), (80, 24), (23, 79)), (0, 0));
        // Narrower, as tall: from column 0, the rest of each row empty.
        assert_eq!(viewport_origin((40, 24), (80, 24), (9, 39)), (0, 0));
        // Larger, caret in view: no movement.
        assert_eq!(viewport_origin((120, 40), (80, 24), (5, 10)), (0, 0));
        // Caret below and right of the pane: just far enough to show it.
        assert_eq!(viewport_origin((120, 40), (80, 24), (30, 100)), (7, 21));
        // Never past the grid's end.
        assert_eq!(viewport_origin((120, 40), (80, 24), (39, 119)), (16, 40));
        assert_eq!(viewport_origin((120, 40), (80, 24), (60, 500)), (16, 40));
    }

    #[test]
    fn a_grid_shorter_than_the_pane_sits_at_its_bottom() {
        use super::viewport_origin;
        // The Fold's cover screen: the host's grid from when the keyboard was up
        // (24 rows) in a pane of 52 rows once it hid with the link down. The
        // grid's row 0 is drawn on pane row 28, its last row on the pane's last.
        assert_eq!(viewport_origin((50, 24), (50, 52), (22, 2)), (-28, 0));
        // Unfolded: a wider, taller pane; the same rule.
        assert_eq!(viewport_origin((110, 30), (110, 61), (28, 2)), (-31, 0));
        // Shorter and narrower: bottom, from column 0.
        assert_eq!(viewport_origin((40, 10), (80, 24), (9, 39)), (-14, 0));
        // One row short: one empty row on top.
        assert_eq!(viewport_origin((80, 23), (80, 24), (22, 2)), (-1, 0));
    }

    #[test]
    fn a_touch_above_a_bottom_anchored_grid_reaches_no_cell() {
        use super::{cell_at, viewport_origin};
        // The cover screen offline: a 50x24 grid at the bottom of a 52-row pane.
        let origin = viewport_origin((50, 24), (50, 52), (22, 2));
        assert_eq!(origin, (-28, 0));
        let at = |x, y| cell_at((x, y), (10., 20.), origin, (50, 24));
        // The grid's first row is drawn on pane row 28, its last on row 51.
        assert_eq!(at(5., 28. * 20.), Some((0, 0)));
        assert_eq!(at(495., 51. * 20. + 19.), Some((49, 23)));
        // The empty rows above it: nothing, not row 0 (where a link could be).
        assert_eq!(at(5., 27. * 20. + 19.), None);
        assert_eq!(at(5., 0.), None);
    }

    #[test]
    fn a_touch_outside_the_drawn_grid_reaches_no_cell() {
        use super::cell_at;
        let at = |x, y, origin, grid| cell_at((x, y), (10., 20.), origin, grid);
        // A grid that fills the pane: every cell is its own.
        assert_eq!(at(0., 0., (0, 0), (80, 24)), Some((0, 0)));
        assert_eq!(at(799.9, 479.9, (0, 0), (80, 24)), Some((79, 23)));
        // Right of a narrower grid, and below or left of the pane: nothing.
        assert_eq!(at(400., 5., (0, 0), (40, 24)), None);
        assert_eq!(at(5., 480., (0, 0), (80, 24)), None);
        assert_eq!(at(-0.5, 5., (0, 0), (80, 24)), None);
        // A window into a larger grid that follows the caret is offset by it.
        assert_eq!(at(5., 5., (16, 40), (120, 40)), Some((40, 16)));
        assert_eq!(at(795., 465., (16, 40), (120, 40)), Some((119, 39)));
        // A degenerate cell size maps nothing.
        assert_eq!(cell_at((5., 5.), (0., 20.), (0, 0), (80, 24)), None);
    }

    #[test]
    fn a_negotiated_grid_is_followed_never_offered_back_and_falls_back() {
        let (session, _remote, resizes) = session();
        session.resize(40, 10, 400, 200);
        // The remote side's grid: the grid follows it, nothing is offered back.
        assert!(session.set_grid(80, 24));
        assert_eq!(session.grid_size(), (80, 24));
        assert_eq!(session.grid().lock().columns(), 80);
        assert!(session.negotiated());
        // The pane still offers its own size when it changes, but the grid stays.
        session.resize(50, 12, 500, 240);
        assert_eq!(session.grid_size(), (80, 24));
        assert_eq!(*resizes.lock().unwrap(), vec![(40, 10), (50, 12)]);
        assert!(!session.set_grid(0, 0));
        // An old path: back to the pane's geometry.
        session.clear_grid();
        assert!(!session.negotiated());
        assert_eq!(session.grid_size(), (50, 12));
        assert_eq!(session.grid().lock().columns(), 50);
    }

    #[test]
    fn resize_reaches_the_remote_side_once_per_new_size() {
        let (session, _remote, resizes) = session();
        session.resize(30, 8, 300, 160);
        session.resize(30, 8, 310, 170);
        session.resize(31, 8, 310, 170);
        assert_eq!(*resizes.lock().unwrap(), vec![(30, 8), (31, 8)]);
        assert_eq!(session.grid().lock().columns(), 31);
    }

    #[test]
    fn the_session_ends_with_its_stream_and_keeps_the_last_screen() {
        let (session, mut remote, _) = session();
        remote.write_all(b"bye").unwrap();
        until(|| text_at(&session, 0, 3) == "bye");
        drop(remote);
        until(|| !session.is_alive());
        assert_eq!(text_at(&session, 0, 3), "bye");
    }

    #[test]
    fn screen_text_keeps_rows_and_trims_blank_space() {
        let (session, mut remote, _) = session();
        remote.write_all("a  \r\n\r\n世b".as_bytes()).unwrap();
        until(|| session.screen_text() == "a\n\n世b");
    }

    fn base64(bytes: &[u8]) -> String {
        const TABLE: &[u8; 64] =
            b"ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
        let mut text = String::new();
        for chunk in bytes.chunks(3) {
            let bits = chunk.iter().enumerate().fold(0u32, |bits, (i, byte)| {
                bits | u32::from(*byte) << (16 - 8 * i)
            });
            for i in 0..4 {
                text.push(if i <= chunk.len() {
                    char::from(TABLE[(bits >> (18 - 6 * i) & 63) as usize])
                } else {
                    '='
                });
            }
        }
        text
    }

    #[test]
    fn graphics_never_touch_a_local_file_the_program_names() {
        let (session, mut remote, _) = session();
        // Upstream reads such a temporary file, then deletes it.
        let path = std::env::temp_dir().join(format!(
            "tty-graphics-protocol-gpui-rio-{}.rgba",
            std::process::id()
        ));
        std::fs::write(&path, [255, 0, 0, 255]).unwrap();
        let name = base64(path.to_str().unwrap().as_bytes());
        remote
            .write_all(format!("\x1b_Ga=t,t=t,f=32,s=1,v=1,i=7;{name}\x1b\\done").as_bytes())
            .unwrap();
        until(|| session.screen_text().contains("done"));
        let exists = path.exists();
        let _ = std::fs::remove_file(&path);
        assert!(exists, "a remote program deleted a local file");
        reply(&mut remote, b"\x1b_Gi=7;EINVAL: unsupported medium\x1b\\");
    }
}
