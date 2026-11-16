//! The terminal state machine on a caller's byte stream. One thread parses the
//! remote program's output into the grid; one sends input. Replies a terminal
//! owes the program (device and cursor reports, colour and size queries) are
//! answered as they are parsed and leave through the same input queue.

use crate::{
    Error, KeyPress, Result, TerminalTheme, key_bytes,
    mouse::{self, Gesture},
};
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
        atomic::{AtomicBool, AtomicU64, Ordering},
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
    /// Input sent so far (keys, typed and pasted text), counted so a view
    /// can tell that the user typed since it last looked.
    inputs: AtomicU64,
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
            inputs: AtomicU64::new(0),
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
        let gesture = if up {
            Gesture::WheelUp
        } else {
            Gesture::WheelDown
        };
        // Reported at the middle of the screen: the pane under the finger is
        // the whole terminal here.
        let report = mouse::report(mode, gesture, columns / 2 + 1, rows / 2 + 1);
        let bytes = if let Some(one) = report {
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

    /// A tap or click on the screen cell (`column`, `row`), counted from 0 at
    /// the screen's top-left, reported to the program as a left-button press
    /// and release when it asked for mouse events. Returns false, sending
    /// nothing, when it asked for none, or the cell is off the screen.
    /// Like the wheel, it leaves the view where it is.
    pub fn click(&self, column: usize, row: usize) -> bool {
        let (mode, columns, rows) = {
            let grid = self.grid.lock();
            (grid.mode(), grid.columns(), grid.screen_lines())
        };
        if column >= columns || row >= rows {
            return false;
        }
        mouse::report(mode, Gesture::Click, column + 1, row + 1)
            .is_some_and(|bytes| self.write(bytes))
    }

    /// How many inputs (keys, typed or pasted text) were sent so far.
    pub fn inputs(&self) -> u64 {
        self.inputs.load(Ordering::Acquire)
    }

    fn follow_input(&self) -> Mode {
        self.inputs.fetch_add(1, Ordering::AcqRel);
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
/// Which part of a grid larger than the pane is drawn: the top-left cell
/// (row, column) of the pane's window into the grid. Policy: the window starts
/// at the top-left and moves only as far as needed to keep the caret on
/// screen (it follows the caret); a grid that fits is drawn whole from (0, 0),
/// with the rest of the pane left empty (letterboxed).
pub fn viewport_origin(grid: (u16, u16), pane: (u16, u16), cursor: (i32, usize)) -> (i32, usize) {
    let (grid_cols, grid_rows) = (usize::from(grid.0), i32::from(grid.1));
    let (pane_cols, pane_rows) = (usize::from(pane.0.max(1)), i32::from(pane.1.max(1)));
    let row = if grid_rows > pane_rows {
        (cursor.0 - pane_rows + 1).clamp(0, grid_rows - pane_rows)
    } else {
        0
    };
    let col = if grid_cols > pane_cols {
        (cursor.1 + 1)
            .saturating_sub(pane_cols)
            .min(grid_cols - pane_cols)
    } else {
        0
    };
    (row, col)
}

#[doc(hidden)]
/// The cells (columns, rows) a pane of `size` pixels holds at `cell` pixels
/// a cell.
pub fn pane_cells(size: (f32, f32), cell: (f32, f32)) -> (u16, u16) {
    let fit = |length: f32, cell: f32, least: f32| {
        (length / cell.max(1.)).floor().clamp(least, 1000.) as u16
    };
    (fit(size.0, cell.0, 2.), fit(size.1, cell.1, 1.))
}

#[doc(hidden)]
/// The type size to draw `grid` with in a pane of `size` pixels whose type
/// size is `base` (a cell of `cell` pixels there). A grid with fewer columns
/// than the pane holds at `base` (another viewer sized it) grows the type
/// until it fills the pane's width, but never past its height, nor `max`.
/// Any other grid is drawn at `base`: type is never shrunk here, and a pane
/// the grid follows (its columns are the pane's own) is left as it is.
pub fn fitted_font_size(
    base: f32,
    cell: (f32, f32),
    size: (f32, f32),
    grid: (u16, u16),
    max: f32,
) -> f32 {
    let (columns, _) = pane_cells(size, cell);
    if !(base > 0.) || grid.0 == 0 || grid.0 >= columns {
        return base;
    }
    // Cells widen in proportion to the type. Rows round up to whole pixels:
    // leave each a pixel, so every grid row still fits.
    let width = size.0 / (f32::from(grid.0) * cell.0) * 0.999;
    let rows = f32::from(grid.1.max(1));
    let height = (size.1 / rows - 1.) / cell.1;
    (base * width.min(height)).clamp(base, max.max(base))
}

#[doc(hidden)]
/// The window actually drawn: [`viewport_origin`], except that a column the
/// reader panned to (`pan`) wins over following the caret, and a view scrolled
/// back into history starts at the top row and, unpanned, the left column.
pub fn view_origin(
    grid: (u16, u16),
    pane: (u16, u16),
    cursor: (i32, usize),
    scrolled: bool,
    pan: Option<usize>,
) -> (i32, usize) {
    let (row, caret_col) = if scrolled {
        (0, 0)
    } else {
        viewport_origin(grid, pane, cursor)
    };
    let last = usize::from(grid.0).saturating_sub(usize::from(pane.0.max(1)));
    (row, pan.map_or(caret_col, |col| col.min(last)))
}

#[doc(hidden)]
/// A column offset the reader chose by panning a grid wider than the pane
/// (one the remote side drew wider than this screen). Client-local: nothing
/// is sent. Once panned, every column is a real position, the left edge too;
/// the window follows the caret again only after the grid's width changes
/// (its rows were drawn again) or the user types (their input is at the
/// caret). A pane switch shows another session's view with its own offset.
#[derive(Clone, Copy, Debug, Default, PartialEq)]
pub struct ColumnPan {
    /// The grid column drawn at the pane's left; `None` follows the caret.
    col: Option<usize>,
    /// Columns of drag not yet a whole one, so slow drags still move.
    rest: f32,
    /// The grid width and the session's input count it was chosen at.
    basis: (u16, u64),
}

impl ColumnPan {
    /// Moves the window `columns` to the right (negative: left) from `drawn`,
    /// the column the last frame started at, stopping at either edge.
    /// `inputs` is the session's input count ([`Session::inputs`]). True when
    /// the column to draw from changed.
    pub fn pan(
        &mut self,
        columns: f32,
        drawn: usize,
        (grid_cols, pane_cols): (u16, u16),
        inputs: u64,
    ) -> bool {
        self.rebase(grid_cols, inputs);
        let last = usize::from(grid_cols).saturating_sub(usize::from(pane_cols.max(1)));
        if last == 0 || !columns.is_finite() {
            self.rest = 0.;
            return false;
        }
        // A reversed drag starts from no remainder, so it moves at once.
        if (self.rest > 0.) != (columns > 0.) {
            self.rest = 0.;
        }
        self.rest += columns;
        let whole = self.rest.trunc();
        self.rest -= whole;
        if whole == 0. {
            return false;
        }
        let from = self.col.unwrap_or(drawn).min(last);
        let next = (from as f32 + whole).clamp(0., last as f32) as usize;
        let moved = self.col != Some(next);
        self.col = Some(next);
        moved
    }

    /// The column to draw from (within the grid), or `None` to follow the
    /// caret: never panned, or dropped by a new grid width or new input.
    pub fn column(&mut self, (grid_cols, pane_cols): (u16, u16), inputs: u64) -> Option<usize> {
        self.rebase(grid_cols, inputs);
        let last = usize::from(grid_cols).saturating_sub(usize::from(pane_cols.max(1)));
        self.col.map(|col| col.min(last))
    }

    fn rebase(&mut self, grid_cols: u16, inputs: u64) {
        if self.basis != (grid_cols, inputs) {
            *self = Self {
                basis: (grid_cols, inputs),
                ..Self::default()
            };
        }
    }
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
    fn a_click_reaches_only_a_program_that_asked_for_mouse_events() {
        use rio_vt::crosswords::Mode;
        let (session, mut remote, _) = session();
        let mode = |session: &Session| session.grid().lock().mode();
        // No mouse reporting: nothing is sent, and the caller is told so.
        assert!(!session.click(4, 2));
        // 1000 then 1006: clicks are reported, as SGR, at 1-based cells.
        remote.write_all(b"\x1b[?1000h\x1b[?1006h").unwrap();
        until(|| mode(&session).contains(Mode::MOUSE_REPORT_CLICK | Mode::SGR_MOUSE));
        assert!(session.click(4, 2));
        reply(&mut remote, b"\x1b[<0;5;3M\x1b[<0;5;3m");
        // Off the 20x4 screen: nothing.
        assert!(!session.click(20, 0));
        assert!(!session.click(0, 4));
        // The program turned reporting off again: taps are its own no more.
        remote.write_all(b"\x1b[?1000l").unwrap();
        until(|| !mode(&session).intersects(Mode::MOUSE_MODE));
        assert!(!session.click(4, 2));
        // The wheel, sharing the encoder, still scrolls by reports when asked.
        remote.write_all(b"\x1b[?1002h").unwrap();
        until(|| mode(&session).contains(Mode::MOUSE_DRAG));
        assert!(session.wheel(2));
        reply(&mut remote, b"\x1b[<64;11;3M\x1b[<64;11;3M");
    }

    /// Nothing more reaches the program within a moment.
    fn silent(remote: &mut UnixStream) {
        remote
            .set_read_timeout(Some(Duration::from_millis(200)))
            .unwrap();
        let mut byte = [0; 1];
        let read = remote.read(&mut byte);
        assert!(read.is_err(), "the program was sent {read:?}");
        remote
            .set_read_timeout(Some(Duration::from_secs(2)))
            .unwrap();
    }

    fn with_mouse_reporting() -> (Arc<Session>, UnixStream) {
        use rio_vt::crosswords::Mode;
        let (session, mut remote, _) = session();
        remote.write_all(b"\x1b[?1000h\x1b[?1006h").unwrap();
        until(|| {
            session
                .grid()
                .lock()
                .mode()
                .contains(Mode::MOUSE_REPORT_CLICK | Mode::SGR_MOUSE)
        });
        (session, remote)
    }

    /// The host's side: each down queued with its pane's stamp, then handled.
    fn handled(downs: &mut crate::tap::DownStamps, stamp: u64) -> crate::tap::Gesture {
        downs.queued(stamp);
        downs.handled();
        downs.now()
    }

    #[test]
    fn an_owned_tap_reaches_the_program_as_a_click() {
        use crate::tap::{DownStamps, Lifted, Press, Touch, lift_on, owns};
        let (session, mut remote) = with_mouse_reporting();
        let mut downs = DownStamps::default();
        let mut touch = Touch::default();
        touch.began(handled(&mut downs, 7));
        let owned = owns(true, touch.lifted(), downs.now());
        assert_eq!(
            lift_on(&session, Press::default(), owned, Some((2, 1))),
            Lifted::Clicked
        );
        reply(&mut remote, b"\x1b[<0;3;2M\x1b[<0;3;2m");
    }

    #[test]
    fn a_shell_view_that_replaced_the_panes_own_view_mid_touch_sends_nothing() {
        use crate::tap::{DownStamps, Lifted, Press, Touch, lift_on, owns};
        // The touch began on the pane's own view (it took the down's stamp);
        // the shell's view replaced it before the lift. GPUI's tap lands on the
        // new view, which saw no touch begin: no click reaches the program.
        let (session, mut remote) = with_mouse_reporting();
        let mut downs = DownStamps::default();
        let mut own_view = Touch::default();
        own_view.began(handled(&mut downs, 7));
        let mut replacing = Touch::default();
        let owned = owns(true, replacing.lifted(), downs.now());
        assert!(!owned);
        assert_eq!(
            lift_on(&session, Press::default(), owned, Some((2, 1))),
            Lifted::Ignored
        );
        silent(&mut remote);
    }

    #[test]
    fn a_shell_view_that_replaced_another_mid_touch_sends_nothing() {
        use crate::tap::{DownStamps, Lifted, NO_STAMP, Press, Touch, lift_on, owns};
        // Shell view A saw the touch begin; B replaced A before the lift.
        let (session, mut remote) = with_mouse_reporting();
        let mut downs = DownStamps::default();
        let mut a = Touch::default();
        a.began(handled(&mut downs, 7));
        let mut b = Touch::default();
        let owned = owns(true, b.lifted(), downs.now());
        assert_eq!(
            lift_on(&session, Press::default(), owned, Some((2, 1))),
            Lifted::Ignored
        );
        // Nor does a link open: lift_on says Ignored for a press on one too.
        let on_link = Press {
            on_link: true,
            ..Press::default()
        };
        assert_eq!(
            lift_on(&session, on_link, owned, Some((2, 1))),
            Lifted::Ignored
        );
        silent(&mut remote);
        // A touch the host had no stamp for is no view's either.
        let mut stampless = Touch::default();
        stampless.began(handled(&mut downs, NO_STAMP));
        let owned = owns(true, stampless.lifted(), downs.now());
        assert_eq!(
            lift_on(&session, Press::default(), owned, Some((2, 1))),
            Lifted::Ignored
        );
        silent(&mut remote);
    }

    #[test]
    fn a_cancelled_touch_then_a_tap_begun_off_the_view_sends_nothing() {
        use crate::tap::{DownStamps, Lifted, Press, Touch, lift_on, owns};
        // A down on the terminal, then the host's CANCEL (a pinch, a scroll):
        // the view still holds that touch. The next down starts off the view
        // (the header) and lifts just inside it, within GPUI's slop, so GPUI's
        // tap lands on the view, which saw no new start.
        let (session, mut remote) = with_mouse_reporting();
        let mut downs = DownStamps::default();
        let mut view = Touch::default();
        view.began(handled(&mut downs, 7));
        handled(&mut downs, 7); // off the view: it sees no start
        let owned = owns(true, view.lifted(), downs.now());
        assert_eq!(
            lift_on(&session, Press::default(), owned, Some((2, 1))),
            Lifted::Ignored
        );
        let on_link = Press {
            on_link: true,
            ..Press::default()
        };
        assert_eq!(
            lift_on(&session, on_link, owned, Some((2, 1))),
            Lifted::Ignored
        );
        silent(&mut remote);
    }

    #[test]
    fn a_view_shown_again_after_a_swap_sends_nothing_for_its_old_touch() {
        use crate::tap::{DownStamps, Lifted, Press, Touch, lift_on, owns};
        // The view saw a touch begin, was swapped out (shell <-> pane) before
        // the lift, and is shown again; the next tap begins elsewhere.
        let (session, mut remote) = with_mouse_reporting();
        let mut downs = DownStamps::default();
        let mut view = Touch::default();
        view.began(handled(&mut downs, 7));
        handled(&mut downs, 8); // the swap's taps, on another view
        let owned = owns(true, view.lifted(), downs.now());
        assert_eq!(
            lift_on(&session, Press::default(), owned, Some((2, 1))),
            Lifted::Ignored
        );
        silent(&mut remote);
        // A swap also clears the touch outright.
        view.began(handled(&mut downs, 9));
        view.clear();
        assert_eq!(view.lifted(), None);
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

    /// The emulator's unfolded pane at 11 px type: 707 x 571 dp, a 6.6 x 13 cell.
    const PANE: (f32, f32) = (707., 571.);
    const CELL: (f32, f32) = (6.601, 13.);

    #[test]
    fn a_narrower_grid_grows_the_type_within_the_pane() {
        use super::{fitted_font_size, pane_cells};
        assert_eq!(pane_cells(PANE, CELL), (107, 43));
        // Another viewer holds 60 x 30: the type grows until the grid fills
        // the pane's width, or its height, whichever comes first.
        let size = fitted_font_size(11., CELL, PANE, (60, 20), 36.);
        let scale = size / 11.;
        let drawn = pane_cells(PANE, (CELL.0 * scale, (CELL.1 * scale).ceil()));
        assert_eq!(drawn.0, 60, "{size}");
        assert!(drawn.1 >= 20);
        let tall = fitted_font_size(11., CELL, PANE, (60, 40), 36.);
        let scale = tall / 11.;
        let drawn = pane_cells(PANE, (CELL.0 * scale, (CELL.1 * scale).ceil()));
        assert!(drawn.0 >= 60 && drawn.1 >= 40, "{tall}: {drawn:?}");
        assert!(tall > 11. && tall < size);
        // Never past the cap, never below the pane's own size.
        assert_eq!(fitted_font_size(11., CELL, PANE, (10, 5), 36.), 36.);
        assert_eq!(fitted_font_size(11., CELL, PANE, (60, 400), 36.), 11.);
        assert_eq!(fitted_font_size(16., CELL, PANE, (60, 30), 12.), 16.);
    }

    #[test]
    fn a_grid_as_wide_as_the_pane_or_wider_keeps_the_pane_s_type() {
        use super::{fitted_font_size, pane_cells};
        let (columns, rows) = pane_cells(PANE, CELL);
        // The phone leads: the grid is its own offer. Nothing changes.
        assert_eq!(fitted_font_size(11., CELL, PANE, (columns, rows), 36.), 11.);
        // Fewer rows but the same columns: no growth either.
        assert_eq!(fitted_font_size(11., CELL, PANE, (columns, 10), 36.), 11.);
        // Wider (item 72's held 200 columns): panned, never shrunk.
        assert_eq!(fitted_font_size(11., CELL, PANE, (200, 120), 36.), 11.);
        assert!(fitted_font_size(f32::NAN, CELL, PANE, (60, 30), 36.).is_nan());
    }

    #[test]
    fn the_offer_never_follows_the_grown_type() {
        use super::{fitted_font_size, pane_cells};
        // The pane is offered at its own size, whatever the grid makes the
        // type: no offer, no resize, no loop.
        let offer = pane_cells(PANE, CELL);
        // The phone leads, another viewer narrows the grid, the phone leads
        // again, a desktop holds it wider, the phone leads again.
        for grid in [offer, (60, 30), (80, 43), offer, (200, 120), offer] {
            let size = fitted_font_size(11., CELL, PANE, grid, 36.);
            assert_eq!(
                pane_cells(PANE, CELL),
                offer,
                "offer moved with {grid:?} at {size}"
            );
            // The grid back at the pane's own size restores its own type.
            if grid == offer {
                assert_eq!(size, 11.);
            }
        }
    }

    #[test]
    fn a_grid_larger_than_the_pane_follows_the_caret() {
        use super::viewport_origin;
        // Fits: drawn whole from the top-left (the rest is letterboxed).
        assert_eq!(viewport_origin((40, 10), (80, 24), (9, 39)), (0, 0));
        // Larger, caret in view: no movement.
        assert_eq!(viewport_origin((120, 40), (80, 24), (5, 10)), (0, 0));
        // Caret below and right of the pane: just far enough to show it.
        assert_eq!(viewport_origin((120, 40), (80, 24), (30, 100)), (7, 21));
        // Never past the grid's end.
        assert_eq!(viewport_origin((120, 40), (80, 24), (39, 119)), (16, 40));
        assert_eq!(viewport_origin((120, 40), (80, 24), (60, 500)), (16, 40));
    }

    #[test]
    fn a_panned_column_wins_over_the_caret_and_holds_in_history() {
        use super::view_origin;
        // Unpanned: the caret is followed live; history starts top-left.
        assert_eq!(
            view_origin((120, 40), (80, 24), (30, 100), false, None),
            (7, 21)
        );
        assert_eq!(
            view_origin((120, 40), (80, 24), (30, 100), true, None),
            (0, 0)
        );
        // Panned: that column, live or scrolled back; rows as before.
        assert_eq!(
            view_origin((120, 40), (80, 24), (30, 100), false, Some(5)),
            (7, 5)
        );
        assert_eq!(
            view_origin((120, 40), (80, 24), (30, 100), true, Some(33)),
            (0, 33)
        );
        // Never past the grid's last full window, nor beside a grid that fits.
        assert_eq!(
            view_origin((120, 40), (80, 24), (0, 0), false, Some(90)),
            (0, 40)
        );
        assert_eq!(
            view_origin((40, 10), (80, 24), (0, 0), true, Some(7)),
            (0, 0)
        );
    }

    #[test]
    fn a_column_pan_moves_by_whole_columns_within_the_grid() {
        use super::ColumnPan;
        const WIDE: (u16, u16) = (200, 50);
        let mut pan = ColumnPan::default();
        // Nothing to pan when the grid fits the pane.
        assert!(!pan.pan(10., 0, (40, 80), 0));
        assert_eq!(pan.column((40, 80), 0), None);
        // A wider grid: 200 columns on a 50-column pane, 150 to pan.
        assert!(pan.pan(12., 0, WIDE, 0));
        assert_eq!(pan.column(WIDE, 0), Some(12));
        // Fractions add up, so a slow drag still moves.
        assert!(!pan.pan(0.6, 12, WIDE, 0));
        assert!(pan.pan(0.6, 12, WIDE, 0));
        assert_eq!(pan.column(WIDE, 0), Some(13));
        // Clamped at the right edge; the next step left moves at once.
        pan.pan(1000., 13, WIDE, 0);
        assert_eq!(pan.column(WIDE, 0), Some(150));
        assert!(!pan.pan(5., 150, WIDE, 0));
        assert!(pan.pan(-1., 150, WIDE, 0));
        assert_eq!(pan.column(WIDE, 0), Some(149));
        // A wider pane (unfolded, smaller type) clamps without forgetting.
        assert_eq!(pan.column((200, 120), 0), Some(80));
        assert_eq!(pan.column(WIDE, 0), Some(149));
        assert!(!pan.pan(f32::NAN, 149, WIDE, 0));
        assert_eq!(pan.column(WIDE, 0), Some(149));
    }

    #[test]
    fn the_left_edge_is_a_real_position_once_panned() {
        use super::ColumnPan;
        const WIDE: (u16, u16) = (200, 50);
        let mut pan = ColumnPan::default();
        pan.pan(-1000., 120, WIDE, 0);
        // Panned to column 0 with the caret out at column 120: it stays at 0,
        // never snapping back to the caret.
        assert_eq!(pan.column(WIDE, 0), Some(0));
        let caret = (49, 170);
        let origin = super::view_origin((200, 50), (50, 50), caret, false, pan.column(WIDE, 0));
        assert_eq!(origin, (0, 0));
        // Further left changes nothing; right moves from there.
        assert!(!pan.pan(-3., 0, WIDE, 0));
        assert!(pan.pan(2., 0, WIDE, 0));
        assert_eq!(pan.column(WIDE, 0), Some(2));
    }

    #[test]
    fn a_column_pan_starts_from_the_window_drawn() {
        use super::ColumnPan;
        let mut pan = ColumnPan::default();
        // The caret had the window at column 30: a pan moves from there.
        assert!(pan.pan(-4., 30, (200, 50), 0));
        assert_eq!(pan.column((200, 50), 0), Some(26));
        // A drag under a whole column does not pin the window yet.
        let mut pan = ColumnPan::default();
        assert!(!pan.pan(0.4, 30, (200, 50), 0));
        assert_eq!(pan.column((200, 50), 0), None);
    }

    #[test]
    fn a_new_grid_width_or_typing_drops_the_pan_but_new_rows_keep_it() {
        use super::ColumnPan;
        let mut pan = ColumnPan::default();
        pan.pan(20., 0, (200, 50), 3);
        pan.pan(0.5, 20, (200, 50), 3);
        // Rows are not part of it: a keyboard opening never moves the window.
        assert_eq!(pan.column((200, 50), 3), Some(20));
        // Another width: the program drew its rows again; follow the caret.
        assert_eq!(pan.column((118, 50), 3), None);
        // The dropped half column does not come back either.
        assert!(!pan.pan(0.6, 0, (118, 50), 3));
        assert_eq!(pan.column((200, 50), 3), None);
        // Typing (the session counted another input): back to the caret.
        pan.pan(20., 0, (200, 50), 3);
        assert_eq!(pan.column((200, 50), 3), Some(20));
        assert_eq!(pan.column((200, 50), 4), None);
    }

    #[test]
    fn keys_typed_and_pasted_text_count_as_input_and_wheel_does_not() {
        let (session, _remote, _) = session();
        assert_eq!(session.inputs(), 0);
        assert!(session.type_text("a"));
        assert!(session.paste("b"));
        assert!(session.key(&KeyPress {
            key: "enter".into(),
            control: false,
            alt: false,
            shift: false,
            text: None,
        }));
        assert_eq!(session.inputs(), 3);
        session.wheel(3);
        assert_eq!(session.inputs(), 3);
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
