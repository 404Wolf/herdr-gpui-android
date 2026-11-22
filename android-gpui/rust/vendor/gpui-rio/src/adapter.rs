/// Defines a terminal entity using the caller's GPUI crate or module.
/// Invoke once in a shared module: `gpui_rio::bind_gpui!(gpui);`.
#[macro_export]
macro_rules! bind_gpui {
    ($gpui:path) => {
        $crate::__bind_gpui!($gpui,);
    };
}

#[doc(hidden)]
#[macro_export]
macro_rules! __bind_gpui {
    ($gpui:path, $($extra:item)*) => {
        pub use self::__gpui_rio_adapter::Terminal;
        use $gpui as __gpui_rio;
        mod __gpui_rio_adapter {
            use super::__gpui_rio as gpui;
            use gpui::*;
            use $crate::{KeyPress, Session, TerminalStyle};
            use $crate::__private::{resolve_color, rio_vt};
            use rio_vt::{
                ansi::CursorShape,
                config::colors::ColorRgb,
                crosswords::{
                    Crosswords, Mode,
                    grid::Scroll,
                    pos::{Column, Pos, Side},
                    square::Wide,
                    style::StyleFlags,
                },
                event::EventListener,
                selection::{Selection, SelectionType},
            };
            use std::{ops::Range, sync::Arc, time::Duration};

            struct PressTarget {
                point: Point<Pixels>,
                position: Pos,
                /// Unclamped hit, including the draft and scrollback.
                drawn: Option<Pos>,
                cell: Option<(usize, usize)>,
                link: Option<$crate::links::GridLink>,
            }

            pub struct Terminal {
                session: Arc<Session>,
                focus: FocusHandle,
                style: TerminalStyle,
                visible: bool,
                bounds: Bounds<Pixels>,
                /// The first terminal column drawn in the pane.
                first_column: usize,
                rows: $crate::footer::Rows,
                alt_screen: bool,
                /// The attached pane's agent, when its own footer should be collapsed.
                footer_kind: $crate::ghost::PromptKind,
                /// A column the reader panned a wider grid to (see `ColumnPan`).
                pan: $crate::ColumnPan,
                /// The pane's size in cells at the last paint.
                pane: (u16, u16),
                cell: Size<Pixels>,
                selecting: bool,
                scroll_delta: f32,
                marked: String,
                host_input: bool,
                /// Opens a tapped link and copies a held one; without one, links are plain text.
                link_handler: Option<std::rc::Rc<dyn Fn($crate::links::Link, $crate::links::LinkAction)>>,
                /// Told what each lifted press did, so the host can act on taps.
                /// Presses come from touch: one is acted on only when this view saw
                /// its touch begin (see `tap::owns`).
                touch_active: Option<fn() -> bool>,
                tap_listener: Option<(
                    std::rc::Rc<dyn Fn() -> $crate::tap::Gesture>,
                    std::rc::Rc<dyn Fn($crate::tap::Report)>,
                )>,
                press: Option<PressTarget>,
                /// The target of the physical down, before GPUI synthesizes a click.
                touch_target: Option<PressTarget>,
                /// The finger on the screen, so a held one is not taken for a tap.
                touch: $crate::tap::Touch,
                /// Unsent words drawn at the program's caret (see `ghost`).
                ghost: Option<Arc<$crate::ghost::Ghost>>,
                /// The prompt kind used to read the screen when no ghost is set.
                prompt_kind: $crate::ghost::PromptKind,
                /// Told where the screen is busy after each paint that changed it.
                layout_listener: Option<std::rc::Rc<dyn Fn(&$crate::ghost::LayoutHint)>>,
                last_hint: Option<$crate::ghost::LayoutHint>,
                /// Where each row was drawn vertically at the last paint (a multi-line draft spreads its rows).
                geometry: $crate::rows::RowGeometry,
                _poll: Task<()>,
            }

            impl Terminal {
                /// Shows `session` in a new entity. The session's owner keeps its
                /// transport; dropping the entity only stops drawing it.
                pub fn attach(
                    session: Arc<Session>,
                    style: TerminalStyle,
                    cx: &mut App,
                ) -> Entity<Self> {
                    session.set_theme(style.theme);
                    cx.new(|cx: &mut Context<Self>| {
                        let poll = cx.spawn(async move |this, cx| {
                            let mut alive = true;
                            loop {
                                // An ended session's final screen stays drawn and
                                // can still be scrolled (a held frame while the
                                // link is down), so it is still watched, slower.
                                let wait = if alive { 16 } else { 100 };
                                cx.background_executor()
                                    .timer(Duration::from_millis(wait))
                                    .await;
                                let Ok(now) = this.update(cx, |this, cx| {
                                    let now = this.session.is_alive();
                                    let dirty = this.session.take_dirty();
                                    let finish_projection = this.rows.deferred() && !this.touch_active();
                                    if (this.visible && (dirty || finish_projection)) || now != alive {
                                        cx.notify();
                                    }
                                    now
                                }) else {
                                    break;
                                };
                                alive = now;
                            }
                        });
                        Self {
                            session,
                            focus: cx.focus_handle(),
                            style,
                            visible: true,
                            bounds: Bounds::default(),
                            first_column: 0,
                            rows: $crate::footer::Rows::default(),
                            alt_screen: false,
                            footer_kind: $crate::ghost::PromptKind::Other,
                            pan: $crate::ColumnPan::default(),
                            pane: (0, 0),
                            cell: size(px(8.), px(18.)),
                            selecting: false,
                            scroll_delta: 0.,
                            marked: String::new(),
                            host_input: false,
                            link_handler: None,
                            touch_active: None,
                            tap_listener: None,
                            press: None,
                            touch_target: None,
                            touch: $crate::tap::Touch::default(),
                            ghost: None,
                            prompt_kind: $crate::ghost::PromptKind::Other,
                            layout_listener: None,
                            last_hint: None,
                            geometry: $crate::rows::RowGeometry::uniform(1.),
                            _poll: poll,
                        }
                    })
                }

                pub fn session(&self) -> &Arc<Session> {
                    &self.session
                }
                pub fn focus(&self, window: &mut Window, cx: &mut App) {
                    window.focus(&self.focus, cx);
                }
                pub fn is_alive(&self) -> bool {
                    self.session.is_alive()
                }
                pub fn set_visible(&mut self, visible: bool) {
                    self.visible = visible;
                    if !visible {
                        // A view put away holds no touch to act on when it is back.
                        self.touch.clear();
                        self.touch_target = None;
                    }
                }
                /// For hosts that deliver keys and text to the session themselves, as on
                /// Android, where one IME bridge commits input. The entity then registers
                /// no input handler, ignores key events, never takes focus, and draws
                /// its cursor without focus.
                pub fn set_host_input(&mut self, host_input: bool) {
                    self.host_input = host_input;
                }
                /// Tapping a link (OSC 8, an http(s) URL, a markdown `[text](url)` or a
                /// file path in the text, wrapped or not) calls `handler` to open it;
                /// a finger held on one calls it to copy it.
                pub fn set_link_handler(
                    &mut self,
                    handler: impl Fn($crate::links::Link, $crate::links::LinkAction) + 'static,
                ) {
                    self.link_handler = Some(std::rc::Rc::new(handler));
                }
                /// Told what every lifted press did, once it was done: `Clicked` only
                /// when the program was sent a click. A host that opens a keyboard on
                /// a tap leaves it shut for a click. `stamp` is asked when a press
                /// begins and comes back in its report: a host that queues a stamp
                /// with each touch-down (`tap::DownStamps`) answers with the gesture
                /// being handled, so it learns which view the press began on, and
                /// this view acts only on the gesture it saw begin.
                pub fn set_tap_listener(
                    &mut self,
                    stamp: impl Fn() -> $crate::tap::Gesture + 'static,
                    listener: impl Fn($crate::tap::Report) + 'static,
                ) {
                    self.tap_listener = Some((std::rc::Rc::new(stamp), std::rc::Rc::new(listener)));
                }
                /// For touch hosts, report whether a gesture is in progress,
                /// including one the host handles itself or GPUI has yet to drain.
                pub fn set_touch_presses(&mut self, active: fn() -> bool) {
                    self.touch_active = Some(active);
                }
                fn touch_active(&self) -> bool {
                    self.touch_active.is_some_and(|active| active())
                }
                /// Pans a grid wider than the pane `dx` to the right (negative:
                /// left), as the host's pan buttons do. Client-local: the program
                /// sees nothing. Holds until the grid's width changes or the user
                /// types (see `ColumnPan`). True when the window moved; the host
                /// then notifies this view.
                pub fn pan(&mut self, dx: Pixels) -> bool {
                    let columns = f32::from(dx) / f32::from(self.cell.width);
                    let (grid_cols, _) = self.session.grid_size();
                    self.pan.pan(columns, self.first_column, (grid_cols, self.pane.0), self.session.inputs())
                }
                fn tap_stamp(&self) -> $crate::tap::Gesture {
                    self.tap_listener.as_ref().map_or($crate::tap::Gesture::NONE, |(stamp, _)| stamp())
                }
                /// Draws `ghost` (or nothing) over the screen from the next frame on.
                pub fn set_ghost(&mut self, ghost: Option<Arc<$crate::ghost::Ghost>>) {
                    self.ghost = ghost;
                }
                /// Whose prompt to look for when reporting busy cells without a ghost.
                pub fn set_prompt_kind(&mut self, kind: $crate::ghost::PromptKind) {
                    self.prompt_kind = kind;
                }
                pub fn set_footer_kind(&mut self, kind: $crate::ghost::PromptKind) -> bool {
                    if self.footer_kind == kind {
                        return false;
                    }
                    self.footer_kind = kind;
                    true
                }
                /// Called after a paint whose layout hint differs from the last one.
                pub fn set_layout_listener(&mut self, listener: impl Fn(&$crate::ghost::LayoutHint) + 'static) {
                    self.layout_listener = Some(std::rc::Rc::new(listener));
                    self.last_hint = None;
                }
                /// Whether `point` is on the draft as last drawn (at Claude's prompt or the host's
                /// own): its words and its row from the prompt glyph on. Such a tap is the host's
                /// (the caret goes there): it never reaches the program as a click, nor a link.
                fn on_draft(&self, point: Point<Pixels>) -> bool {
                    if !self.bounds.contains(&point) {
                        return false;
                    }
                    let Some(hint) = self.last_hint.as_ref() else { return false };
                    let x = f32::from(point.x - self.bounds.left()) / f32::from(self.cell.width);
                    let y = f32::from(point.y - self.bounds.top());
                    if x < 0. || y < 0. {
                        return false;
                    }
                    let Some(row) = self.geometry.draft_row_at(y) else { return false };
                    let col = x as usize;
                    let from = hint
                        .draft_runs
                        .iter()
                        .filter(|r| r.row == row)
                        .map(|r| r.col)
                        .min();
                    from.is_some_and(|from| col + 2 >= from)
                }

                fn target_at(&self, point: Point<Pixels>) -> PressTarget {
                    let grid = self.session.grid().lock();
                    let position = self.position(point, &grid);
                    let drawn = self.drawn_position(point, &grid);
                    let on_draft = self.on_draft(point);
                    // Selection can clamp to an edge. A tap in space left by
                    // a collapsed grid has no link or program cell.
                    let link = if !on_draft && self.link_handler.is_some()
                        && (!self.rows.collapsed() || drawn.is_some())
                    {
                        $crate::links::grid_link_at(&*grid, position)
                    } else {
                        None
                    };
                    PressTarget {
                        point,
                        position,
                        drawn,
                        cell: drawn
                            .filter(|_| !on_draft && !self.rows.scrolled() && grid.display_offset() == 0)
                            .map(|pos| (pos.col.0, pos.row.0 as usize)),
                        link,
                    }
                }

                fn drawn_position(
                    &self,
                    point: Point<Pixels>,
                    grid: &Crosswords<impl EventListener>,
                ) -> Option<Pos> {
                    if !self.bounds.contains(&point) {
                        return None;
                    }
                    let col = self.first_column
                        + (f32::from(point.x - self.bounds.left()) / f32::from(self.cell.width)) as usize;
                    let line = self.rows.source_at(&self.geometry, f32::from(point.y - self.bounds.top()))?;
                    (col < grid.columns()).then_some(Pos::new(line, Column(col)))
                }
                fn cancel_press(&mut self) -> bool {
                    self.press.take().is_some_and(|press| press.link.is_some())
                }
                /// Applies colors and font settings to the running session.
                /// The host should notify its view after changing the style.
                pub fn update_style(&mut self, style: TerminalStyle) {
                    self.session.set_theme(style.theme);
                    self.style = style;
                }

                fn key_down(&mut self, event: &KeyDownEvent, _: &mut Window, cx: &mut Context<Self>) {
                    if self.host_input {
                        return;
                    }
                    let key = &event.keystroke;
                    let clipboard = if cfg!(target_os = "macos") {
                        key.modifiers.platform
                    } else {
                        key.modifiers.control && key.modifiers.shift
                    };
                    if clipboard && key.key == "v" {
                        if let Some(text) = cx.read_from_clipboard().and_then(|item| item.text()) {
                            self.session.paste(&text);
                        }
                        cx.stop_propagation();
                        return;
                    }
                    if clipboard && key.key == "c" {
                        if let Some(text) = self.session.grid().lock().selection_to_string() {
                            cx.write_to_clipboard(ClipboardItem::new_string(text));
                        }
                        cx.stop_propagation();
                        return;
                    }
                    if key.modifiers.platform {
                        return;
                    }
                    let press = KeyPress {
                        key: key.key.clone(),
                        control: key.modifiers.control,
                        alt: key.modifiers.alt,
                        shift: key.modifiers.shift,
                        text: key.key_char.clone(),
                    };
                    if self.session.key(&press) {
                        cx.stop_propagation();
                        cx.notify();
                    }
                }

                fn position(&self, point: Point<Pixels>, grid: &Crosswords<impl EventListener>) -> Pos {
                    let row = self.geometry.grid_row_at(f32::from(point.y - self.bounds.top())).unwrap_or_default();
                    let col =
                        (f32::from(point.x - self.bounds.left()) / f32::from(self.cell.width)).floor() as usize;
                    let row = row.min(self.rows.view_len().saturating_sub(1));
                    Pos::new(
                        self.rows.source(row).unwrap_or_default(),
                        Column((col + self.first_column).min(grid.columns() - 1)),
                    )
                }

                /// Paints placed ghost runs over the grid on the program's background,
                /// with a thin caret in the draft, at Claude's prompt or the host's own.
                #[allow(clippy::too_many_arguments)]
                fn paint_ghost(
                    &self,
                    ghost: &$crate::ghost::Ghost,
                    placed: &[$crate::ghost::Placed],
                    // The program's own background under each run, where it tinted it.
                    tints: &[Option<Hsla>],
                    origin: Point<Pixels>,
                    font: &Font,
                    font_size: Pixels,
                    window: &mut Window,
                    cx: &mut App,
                ) {
                    use $crate::ghost::GhostStyle;
                    let hex = |color: u32| -> Hsla { rgb(color & 0x00ff_ffff).into() };
                    let colors = ghost.colors;
                    let mut caret: Option<Point<Pixels>> = None;
                    // The keyboard's cursor, when the host says where it is in the draft.
                    let mut explicit: Option<Point<Pixels>> = None;
                    for (i, run) in placed.iter().enumerate() {
                        // Cells, as the grid draws them: wide and combined characters too.
                        let len = $crate::ghost::cells(&run.text);
                        // The draft's lines are spread; everything else (controls, queue rows) is on its grid row.
                        let y = if run.source.is_some() {
                            self.geometry.draft_top(run.row)
                        } else {
                            self.geometry.grid_top(run.row)
                        };
                        let at = origin + point(self.cell.width * run.col, px(y));
                        // A run of the draft (at Claude's prompt or the host's own) carries its source.
                        if run.style == GhostStyle::Caret {
                            if run.source.is_some() {
                                explicit = Some(at);
                            }
                            continue;
                        }
                        if len == 0 {
                            continue;
                        }
                        let width = self.cell.width * len;
                        let tint = tints.get(i).copied().flatten();
                        let back = if run.style == GhostStyle::Selected { hex(colors.info) } else { tint.unwrap_or(hex(colors.background)) };
                        // The gap above a spread line is covered too, so none of the grid's own
                        // cells under the draft show through between its lines.
                        let above = if run.source.is_some() && run.row > self.geometry.first {
                            px(self.geometry.gap)
                        } else {
                            px(0.)
                        };
                        window.paint_quad(fill(
                            Bounds::new(at - point(px(0.), above), size(width, self.cell.height + above)),
                            back,
                        ));
                        let color = match run.style {
                            GhostStyle::Text | GhostStyle::Partial => colors.text,
                            GhostStyle::Selected | GhostStyle::Caret => colors.background,
                            GhostStyle::Echo | GhostStyle::Tag | GhostStyle::Dim => colors.dim,
                            GhostStyle::Chip | GhostStyle::Warn => colors.warn,
                            GhostStyle::Bright => colors.bright,
                            GhostStyle::Danger => colors.danger,
                            GhostStyle::Info => colors.info,
                        };
                        // One glyph per cluster, in the terminal's own face (it has no
                        // italic, and a fallback face would not keep the grid), each over
                        // the cells the grid gives it: two for a wide character.
                        let mut cell = 0usize;
                        for g in $crate::ghost::graphemes(&run.text) {
                            let i = cell;
                            cell += $crate::ghost::grapheme_cells(g);
                            if g == " " {
                                continue;
                            }
                            let mut one = g.chars();
                            let glyph = match (one.next(), one.next()) {
                                (Some(c), None) => $crate::glyphs::stand_in(c).to_string(),
                                _ => g.to_owned(),
                            };
                            let text_run = TextRun {
                                len: glyph.len(),
                                font: font.clone(),
                                color: hex(color),
                                background_color: None,
                                underline: None,
                                strikethrough: None,
                            };
                            let line = window.text_system().shape_line(glyph.into(), font_size, &[text_run], None);
                            let cell_at = at + point(self.cell.width * i, px(0.));
                            let _ = line.paint(cell_at, self.cell.height, TextAlign::Left, None, window, cx);
                        }
                        if run.style == GhostStyle::Partial {
                            // Dotted: the words an engine may still revise.
                            let mut x = px(0.);
                            while x < width {
                                window.paint_quad(fill(
                                    Bounds::new(at + point(x, self.cell.height - px(2.)), size(px(2.), px(1.))),
                                    hex(colors.dim),
                                ));
                                x += px(4.);
                            }
                        }
                        if run.source.is_some()
                            && matches!(
                                run.style,
                                GhostStyle::Text | GhostStyle::Partial | GhostStyle::Chip | GhostStyle::Selected
                            )
                        {
                            caret = Some(at + point(width, px(0.)));
                        }
                    }
                    if let Some(at) = explicit.or(caret) {
                        window.paint_quad(fill(Bounds::new(at, size(px(1.5), self.cell.height)), hex(colors.text)));
                    }
                }

                fn paint(&mut self, bounds: Bounds<Pixels>, window: &mut Window, cx: &mut Context<Self>) {
                    self.bounds = bounds;
                    let base_size = if self.style.font_size.is_finite() {
                        self.style.font_size.clamp(1., 256.)
                    } else { 13. };
                    let font = font(terminal_font_family(self.style.font_family.clone().into(), cx));
                    // The font's own line spacing, as other terminals use: tall
                    // glyphs and box drawing meet row to row, and no added leading
                    // takes rows from a small screen.
                    let measure = |font_size: f32, window: &mut Window| {
                        let run = TextRun {
                            len: 1,
                            font: font.clone(),
                            color: rgb_color(self.style.theme.foreground),
                            background_color: None,
                            underline: None,
                            strikethrough: None,
                        };
                        let m = window.text_system().shape_line("M".into(), px(font_size), &[run], None);
                        size(m.width.max(px(1.)), (m.ascent + m.descent).ceil().max(px(1.)))
                    };
                    let base_cell = measure(base_size, window);
                    let pixels = (f32::from(bounds.size.width), f32::from(bounds.size.height));
                    // The pane is measured, and offered, at the style's own size
                    // only: what the type grows to below never reaches the remote
                    // side, so it can never feed back into the grid.
                    let (cols, rows) = $crate::pane_cells(
                        pixels,
                        (f32::from(base_cell.width), f32::from(base_cell.height)),
                    );
                    // Resize before locking: the session locks the grid itself.
                    self.session.resize(cols, rows, pixels.0 as u16, pixels.1 as u16);
                    // The grid may be the remote side's negotiated one. Fewer
                    // columns than the pane: drawn with type grown to fill it
                    // (when the host asks), the rest left empty (letterboxed).
                    // Larger: a pane-sized window into it that follows the caret.
                    let (grid_cols, grid_rows) = self.session.grid_size();
                    let drawn_size = self.style.grow_to_grid.map_or(base_size, |max| {
                        $crate::fitted_font_size(
                            base_size,
                            (f32::from(base_cell.width), f32::from(base_cell.height)),
                            pixels,
                            (grid_cols, grid_rows),
                            max,
                        )
                    });
                    self.cell = if drawn_size == base_size { base_cell } else { measure(drawn_size, window) };
                    let font_size = px(drawn_size);
                    let pane = if drawn_size == base_size {
                        (cols, rows)
                    } else {
                        $crate::pane_cells(pixels, (f32::from(self.cell.width), f32::from(self.cell.height)))
                    };
                    let theme = self.style.theme;
                    let mut grid = self.session.grid().lock();
                    let grid_cols = grid.columns() as u16;
                    let grid_rows = grid.screen_lines();
                    let selection = grid
                        .selection
                        .as_ref()
                        .and_then(|selection| selection.to_range(&*grid));
                    let cursor = grid.cursor();
                    let offset = grid.display_offset();
                    let alt_screen = grid.mode().contains(Mode::ALT_SCREEN);
                    if self.alt_screen != alt_screen {
                        // A main/alternate screen switch replaces the surface
                        // under the finger. Cancel its target and show the new
                        // screen in full, including every alternate-screen row.
                        self.alt_screen = alt_screen;
                        self.touch.clear();
                        self.touch_target = None;
                        self.press = None;
                    }
                    let hidden = $crate::footer::hidden_on_screen(&grid, self.footer_kind);
                    let defer = !alt_screen && self.touch_active();
                    let revealed = self.rows.update(grid_rows, defer, hidden);
                    let cols = usize::from(pane.0.min(grid_cols));
                    // A column the reader panned to wins over the caret, in
                    // history too, so wide rows can be read to their end.
                    self.pane = pane;
                    let (row_off, col_off) = $crate::view_origin(
                        (grid_cols, self.rows.count() as u16),
                        pane,
                        // The cursor is above any detected footer.
                        (cursor.pos.row.0, cursor.pos.col.0),
                        offset != 0,
                        self.pan.column((grid_cols, pane.0), self.session.inputs()),
                    );
                    self.first_column = col_off;
                    self.rows.set_viewport(row_off as usize, usize::from(pane.1), offset);
                    let rows = self.rows.view_len();
                    // The rows as drawn, for finding the program's prompt.
                    let screen = self.rows.screen(&grid, cols, col_off);
                    // The caret as drawn, only while visible and the view is at the bottom.
                    let caret = (cursor.content != CursorShape::Hidden && offset == 0)
                        .then(|| {
                            let row = self.rows.projected(cursor.pos.row)?;
                            let col = cursor.pos.col.0.checked_sub(col_off)?;
                            (col <= cols).then_some((row, col))
                        })
                        .flatten();
                    let kind = self.ghost.as_ref().map_or(self.prompt_kind, |ghost| ghost.kind);
                    let anchor = $crate::ghost::locate(&screen, caret, kind);
                    // Without a caret the controls keep the row they last had.
                    let previous = self
                        .last_hint
                        .as_ref()
                        .and_then(|hint| hint.controls.first())
                        .map(|control| $crate::ghost::Previous {
                            row: control.row,
                            scrolled: offset != 0,
                        });
                    let layout = self
                        .ghost
                        .as_ref()
                        .map(|ghost| $crate::ghost::place_all(ghost, anchor, &screen, previous))
                        .unwrap_or_default();
                    let placed = &layout.placed;
                    // A run that is not the draft (the controls, their blanks, queue rows)
                    // keeps the program's own tint under it: Codex's composer and Gemini's
                    // input box are tinted, and a quad of the default background there
                    // would read as a hole in their box (factory-setup.23/.36).
                    let tints: Vec<Option<Hsla>> = placed
                        .iter()
                        .map(|run| {
                            if run.source.is_some() || run.col >= cols {
                                return None;
                            }
                            let line = self.rows.source(run.row)?;
                            let square = grid.grid[Pos::new(line, Column(run.col + col_off))];
                            let style = grid.grid.style_of(&square);
                            let bg = if style.flags.contains(StyleFlags::INVERSE) {
                                resolve_color(&grid, theme, style.fg)
                            } else {
                                resolve_color(&grid, theme, style.bg)
                            };
                            (bg != theme.background).then(|| rgb_color(bg))
                        })
                        .collect();
                    // Spread only at Claude's prompt (the program's own box), and only when the
                    // controls sit below the draft's first line (on the box's lower rule): the
                    // controls' rows, and Claude's status line below, never move.
                    let geometry = $crate::rows::RowGeometry::for_layout(
                        f32::from(self.cell.height), &layout, anchor, &screen,
                    );
                    self.geometry = geometry;
                    if revealed
                        && self.touch_target.as_ref().is_some_and(|target| {
                            self.drawn_position(target.point, &grid) != target.drawn
                        })
                    {
                        // The final geometry includes both the revealed row and the draft.
                        self.touch.clear();
                        self.touch_target = None;
                    }
                    let cursor_shown = self.host_input || self.focus.is_focused(window);
                    window.paint_quad(fill(bounds, rgb_color(theme.background)));
                    for row in 0..rows {
                        let Some(line) = self.rows.source(row).filter(|_| geometry.visible(row)) else {
                            continue;
                        };
                        let row_top = px(geometry.grid_top(row));
                        for col in 0..cols {
                            let pos = Pos::new(line, Column(col + col_off));
                            let square = grid.grid[pos];
                            if square.wide() == Wide::Spacer {
                                continue;
                            }
                            let style = grid.grid.style_of(&square);
                            let mut fg = resolve_color(&grid, theme, style.fg);
                            let mut bg = resolve_color(&grid, theme, style.bg);
                            if style.flags.contains(StyleFlags::INVERSE) {
                                std::mem::swap(&mut fg, &mut bg);
                            }
                            if style.flags.contains(StyleFlags::DIM) {
                                fg = fg * 0.66;
                            }
                            let selected = selection
                                .as_ref()
                                .is_some_and(|selection| selection.contains(pos));
                            let cursor_here = cursor_shown
                                && cursor.content != CursorShape::Hidden
                                && cursor.pos == pos
                                && offset == 0;
                            if selected || (cursor_here && cursor.content == CursorShape::Block) {
                                std::mem::swap(&mut fg, &mut bg);
                            }
                            let origin = bounds.origin + point(self.cell.width * col, row_top);
                            let width = if square.wide() == Wide::Wide {
                                self.cell.width * 2.
                            } else {
                                self.cell.width
                            };
                            // The canvas already has the default background. Painting each
                            // empty cell again bloats every frame, especially in a full pane.
                            if bg != theme.background {
                                window.paint_quad(fill(
                                    Bounds::new(origin, size(width, self.cell.height)),
                                    rgb_color(bg),
                                ));
                            }
                            if let Some((spans, _)) = self.press.as_ref().and_then(|press| press.link.as_ref())
                                && $crate::links::pressed(spans, &pos.row, col, col_off)
                            {
                                // Pressed link: a soft wash and a rule under it.
                                let mut wash = rgb_color(theme.foreground);
                                wash.a = 0.16;
                                window.paint_quad(fill(Bounds::new(origin, size(width, self.cell.height)), wash));
                                window.paint_quad(fill(
                                    Bounds::new(
                                        origin + point(px(0.), self.cell.height - px(1.5)),
                                        size(width, px(1.5)),
                                    ),
                                    rgb_color(fg),
                                ));
                            }
                            // Drawn with stand-ins for what the font lacks (see `glyphs`).
                            let text: String = grid
                                .grid
                                .cell_text(pos)
                                .map(|c| if c == '\0' { ' ' } else { $crate::glyphs::stand_in(c) })
                                .collect();
                            if (text != " "
                                || style
                                    .flags
                                    .intersects(StyleFlags::ALL_UNDERLINES | StyleFlags::STRIKEOUT)
                                || square.has_hyperlink())
                                && !style.flags.contains(StyleFlags::HIDDEN)
                            {
                                let mut font = font.clone();
                                if style.flags.contains(StyleFlags::BOLD) {
                                    font.weight = FontWeight::BOLD;
                                }
                                if style.flags.contains(StyleFlags::ITALIC) {
                                    font.style = FontStyle::Italic;
                                }
                                let run = TextRun {
                                    len: text.len(),
                                    font,
                                    color: rgb_color(fg),
                                    background_color: None,
                                    // OSC 8 links are underlined, so they read as tappable.
                                    underline: (style.flags.intersects(StyleFlags::ALL_UNDERLINES)
                                        || (self.link_handler.is_some() && square.has_hyperlink()))
                                        .then_some(UnderlineStyle {
                                            color: Some(rgb_color(fg)),
                                            thickness: px(1.),
                                            wavy: style.flags.contains(StyleFlags::UNDERCURL),
                                        }),
                                    strikethrough: style.flags.contains(StyleFlags::STRIKEOUT).then_some(
                                        StrikethroughStyle {
                                            color: Some(rgb_color(fg)),
                                            thickness: px(1.),
                                        },
                                    ),
                                };
                                let line =
                                    window
                                        .text_system()
                                        .shape_line(text.into(), font_size, &[run], None);
                                let _ = line.paint(origin, self.cell.height, TextAlign::Left, None, window, cx);
                            }
                            if cursor_here
                                && matches!(cursor.content, CursorShape::Beam | CursorShape::Underline)
                            {
                                let rect = if cursor.content == CursorShape::Beam {
                                    Bounds::new(origin, size(px(2.), self.cell.height))
                                } else {
                                    Bounds::new(
                                        origin + point(px(0.), self.cell.height - px(2.)),
                                        size(width, px(2.)),
                                    )
                                };
                                window.paint_quad(fill(rect, rgb_color(theme.foreground)));
                            }
                        }
                    }
                    grid.reset_damage();
                    drop(grid);
                    // More of a wider grid off either side: a faint rule on that
                    // edge, so a cut row is not taken for the whole row.
                    let mut edge = rgb_color(theme.foreground);
                    edge.a = 0.35;
                    let drawn_width = self.cell.width * cols;
                    let drawn_height = self.cell.height * rows as f32;
                    for (shown, x) in [
                        (col_off > 0, px(0.)),
                        (col_off + cols < usize::from(grid_cols), drawn_width - px(2.)),
                    ] {
                        if shown {
                            window.paint_quad(fill(
                                Bounds::new(bounds.origin + point(x, px(0.)), size(px(2.), drawn_height)),
                                edge,
                            ));
                        }
                    }
                    if let Some(ghost) = self.ghost.clone() {
                        self.paint_ghost(&ghost, placed, &tints, bounds.origin, &font, font_size, window, cx);
                    }
                    if let Some(listener) = self.layout_listener.clone() {
                        let hint = $crate::ghost::LayoutHint {
                            left: f32::from(bounds.origin.x),
                            top: f32::from(bounds.origin.y),
                            cell_width: f32::from(self.cell.width),
                            cell_height: f32::from(self.cell.height),
                            cols,
                            rows,
                            anchored: anchor.is_some(),
                            guard: 0,
                            busy: Vec::new(),
                            busy_from: rows.saturating_sub($crate::ghost::HINT_ROWS),
                            controls: layout.controls.clone(),
                            echoes: layout.echoes.clone(),
                            row_actions: layout.row_actions.clone(),
                            targets_top: layout.targets_top,
                            draft_shown: layout.draft_shown,
                            draft_runs: $crate::ghost::draft_runs(placed),
                            draft_spans: $crate::ghost::draft_spans(placed),
                            rows_geometry: geometry,
                        };
                        let mut busy = $crate::ghost::busy_columns(&screen, anchor, placed);
                        busy.drain(..hint.busy_from.min(busy.len()));
                        let hint = $crate::ghost::LayoutHint {
                            guard: $crate::ghost::guard_row(&screen, anchor, placed),
                            busy,
                            ..hint
                        };
                        if self.last_hint.as_ref() != Some(&hint) {
                            listener(&hint);
                            self.last_hint = Some(hint);
                        }
                    }
                    if !self.host_input && !self.marked.is_empty() && self.focus.is_focused(window)
                        && let Some(row) = self.rows.projected(cursor.pos.row)
                    {
                        let text = self.marked.replace(['\n', '\r'], " ");
                        let run = TextRun {
                            len: text.len(),
                            font,
                            color: rgb_color(theme.foreground),
                            background_color: Some(rgb_color(theme.background)),
                            underline: Some(UnderlineStyle {
                                color: None,
                                thickness: px(1.),
                                wavy: false,
                            }),
                            strikethrough: None,
                        };
                        let line = window
                            .text_system()
                            .shape_line(text.into(), font_size, &[run], None);
                        let origin = bounds.origin
                            + point(
                                self.cell.width * cursor.pos.col.0.saturating_sub(col_off),
                                px(geometry.grid_top(row)),
                            );
                        let _ = line.paint(origin, self.cell.height, TextAlign::Left, None, window, cx);
                    }
                }
            }

            // Generic aliases and missing theme fonts can silently become proportional
            // GPUI fallbacks. Only accept an installed family before trying known monospace fonts.
            fn terminal_font_family(family: SharedString, cx: &App) -> SharedString {
                static INSTALLED: std::sync::OnceLock<Vec<String>> = std::sync::OnceLock::new();
                let installed = INSTALLED.get_or_init(|| cx.text_system().all_font_names());
                if !family.eq_ignore_ascii_case("monospace")
                    && family != ".SystemUIFont"
                    && installed.iter().any(|name| name == family.as_ref())
                {
                    return family;
                }
                static MONO: std::sync::OnceLock<SharedString> = std::sync::OnceLock::new();
                MONO.get_or_init(|| {
                    [
                        "Menlo",
                        "DejaVu Sans Mono",
                        "Liberation Mono",
                        "Noto Sans Mono",
                        "Ubuntu Mono",
                        "Monaco",
                        "Courier New",
                    ]
                    .into_iter()
                    .find(|candidate| installed.iter().any(|name| name == candidate))
                    .map(SharedString::from)
                    .unwrap_or(family)
                })
                .clone()
            }

            fn rgb_color(color: ColorRgb) -> Hsla {
                rgb((u32::from(color.r) << 16) | (u32::from(color.g) << 8) | u32::from(color.b)).into()
            }

            impl Render for Terminal {
                fn render(&mut self, _: &mut Window, cx: &mut Context<Self>) -> impl IntoElement {
                    let entity = cx.entity();
                    let paint_entity = entity.clone();
                    div()
                        .id("rio-terminal")
                        .size_full()
                        .overflow_hidden()
                        .track_focus(&self.focus)
                        .on_key_down(cx.listener(Self::key_down))
                        .on_mouse_down(
                            MouseButton::Left,
                            cx.listener(|this, event: &MouseDownEvent, window, cx| {
                                if !this.host_input {
                                    this.focus(window, cx);
                                }
                                let target = if this.touch_active.is_some() {
                                    // GPUI synthesizes this pair at lift, using
                                    // lift coordinates. Reuse the real down's target.
                                    if !this.touch.owned_now(this.tap_stamp()) {
                                        return;
                                    }
                                    let Some(target) = this.touch_target.take() else { return };
                                    target
                                } else {
                                    this.target_at(event.position)
                                };
                                let mut grid = this.session.grid().lock();
                                if this.touch_active.is_some()
                                    && this.alt_screen != grid.mode().contains(Mode::ALT_SCREEN)
                                {
                                    this.touch.clear();
                                    return;
                                }
                                grid.selection =
                                    Some(Selection::new(SelectionType::Simple, target.position, Side::Left));
                                this.press = Some(target);
                                this.selecting = true;
                                cx.notify();
                            }),
                        )
                        .on_mouse_move(cx.listener(|this, event: &MouseMoveEvent, _, cx| {
                            // A finger that travels is a scroll or a selection, not a tap.
                            if let Some(press) = &this.press {
                                let moved = event.position - press.point;
                                let slop = this.cell.width.max(px(8.));
                                if moved.x.abs() > slop || moved.y.abs() > slop {
                                    if this.cancel_press() {
                                        cx.notify();
                                    }
                                }
                            }
                            if this.selecting && event.pressed_button == Some(MouseButton::Left) {
                                let mut grid = this.session.grid().lock();
                                let pos = this.position(event.position, &grid);
                                if let Some(selection) = &mut grid.selection {
                                    selection.update(pos, Side::Right);
                                }
                                cx.notify();
                            } else {
                                this.selecting = false;
                            }
                        }))
                        .on_mouse_up(
                            MouseButton::Left,
                            cx.listener(|this, _: &MouseUpEvent, _, cx| {
                                this.selecting = false;
                                let target = this.press.take();
                                let touch = this.touch.lifted();
                                // The gesture lifting now: a touch this view holds from an
                                // earlier one (cancelled, or begun elsewhere) is not it.
                                let now = this.tap_stamp();
                                let press = $crate::tap::Press {
                                    on_link: target.as_ref().is_some_and(|target| target.link.is_some()),
                                    travelled: target.is_none(),
                                    held: touch.is_some_and(|(held, _)| held),
                                };
                                let stamp = touch.map_or(now.stamp, |(_, began)| began.stamp);
                                let owned = $crate::tap::owns(this.touch_active.is_some(), touch, now);
                                let cell = target.as_ref().and_then(|target| target.cell);
                                let link = target.and_then(|target| target.link);
                                let tapped = match $crate::tap::lift_on(&this.session, press, owned, cell) {
                                    $crate::tap::Lifted::OpenLink => {
                                        // A tap on a link opens it and selects nothing.
                                        this.session.grid().lock().selection = None;
                                        if let (Some((_, url)), Some(handler)) = (link, this.link_handler.clone()) {
                                            handler(url, $crate::links::LinkAction::Open);
                                        }
                                        cx.notify();
                                        $crate::tap::Tapped::Plain
                                    }
                                    $crate::tap::Lifted::CopyLink => {
                                        // A finger held on a link copies it; no keyboard opens.
                                        this.session.grid().lock().selection = None;
                                        if let (Some((_, url)), Some(handler)) = (link, this.link_handler.clone()) {
                                            handler(url, $crate::links::LinkAction::Copy);
                                        }
                                        cx.notify();
                                        $crate::tap::Tapped::Ignored
                                    }
                                    // The program, not a local selection, owns the cell.
                                    $crate::tap::Lifted::Clicked => {
                                        this.session.grid().lock().selection = None;
                                        cx.notify();
                                        $crate::tap::Tapped::Clicked
                                    }
                                    $crate::tap::Lifted::Plain => $crate::tap::Tapped::Plain,
                                    $crate::tap::Lifted::Ignored => $crate::tap::Tapped::Ignored,
                                };
                                if let Some((_, listener)) = this.tap_listener.clone() {
                                    listener($crate::tap::Report { id: $crate::tap::next_id(), stamp, tapped });
                                }
                            }),
                        )
                        .on_scroll_wheel(cx.listener(|this, event: &ScrollWheelEvent, _, cx| {
                            this.cancel_press();
                            this.touch_target = None;
                            let delta = event.delta.pixel_delta(this.cell.height);
                            // A sideways drag (GPUI locks a touch pan to one axis)
                            // pans a grid wider than the pane; the content follows
                            // the finger. Otherwise it scrolls history as before.
                            if delta.x.abs() > delta.y.abs() {
                                if this.pan(-delta.x) {
                                    cx.notify();
                                }
                                cx.stop_propagation();
                                return;
                            }
                            let delta = delta.y;
                            this.scroll_delta += f32::from(delta) / f32::from(this.cell.height);
                            let lines = this.scroll_delta.trunc() as i32;
                            this.scroll_delta -= lines as f32;
                            this.session.grid().lock().scroll_display(Scroll::Delta(lines));
                            cx.stop_propagation();
                            cx.notify();
                        }))
                        .child(
                            canvas(
                                |_, _, _| {},
                                move |bounds, _, window, cx| {
                                    // GPUI offers every touch as a touch drag when it
                                    // starts and as a long press once held. Watched, never
                                    // claimed, so a held finger's lift is not taken for a
                                    // tap (see `tap`) and both still behave as before.
                                    window.on_mouse_event({
                                        let entity = paint_entity.clone();
                                        move |event: &TouchDragEvent, phase, _, cx| {
                                            if phase == DispatchPhase::Bubble
                                                && event.phase == TouchPhase::Started
                                                && bounds.contains(&event.start_position)
                                            {
                                                entity.update(cx, |this, _| {
                                                    let stamp = this.tap_stamp();
                                                    this.touch.began(stamp);
                                                    this.touch_target = this.touch.owned_now(stamp)
                                                        .then(|| this.target_at(event.start_position));
                                                });
                                            }
                                        }
                                    });
                                    window.on_mouse_event({
                                        let entity = paint_entity.clone();
                                        move |event: &LongPressEvent, phase, _, cx| {
                                            if phase == DispatchPhase::Bubble && event.phase == TouchPhase::Started {
                                                entity.update(cx, |this, _| this.touch.held());
                                            }
                                        }
                                    });
                                    let this = paint_entity.read(cx);
                                    if !this.host_input {
                                        let focus = this.focus.clone();
                                        window.handle_input(
                                            &focus,
                                            ElementInputHandler::new(bounds, entity.clone()),
                                            cx,
                                        );
                                    }
                                    paint_entity.update(cx, |this, cx| this.paint(bounds, window, cx));
                                },
                            )
                            .size_full(),
                        )
                }
            }

            impl EntityInputHandler for Terminal {
                fn text_for_range(
                    &mut self,
                    range: Range<usize>,
                    actual: &mut Option<Range<usize>>,
                    _: &mut Window,
                    _: &mut Context<Self>,
                ) -> Option<String> {
                    let utf16: Vec<_> = self.marked.encode_utf16().collect();
                    let slice = utf16.get(range.clone())?;
                    *actual = Some(range);
                    Some(String::from_utf16_lossy(slice))
                }
                fn selected_text_range(
                    &mut self,
                    _: bool,
                    _: &mut Window,
                    _: &mut Context<Self>,
                ) -> Option<UTF16Selection> {
                    let end = self.marked.encode_utf16().count();
                    Some(UTF16Selection {
                        range: end..end,
                        reversed: false,
                    })
                }
                fn marked_text_range(&self, _: &mut Window, _: &mut Context<Self>) -> Option<Range<usize>> {
                    (!self.marked.is_empty()).then(|| 0..self.marked.encode_utf16().count())
                }
                fn unmark_text(&mut self, _: &mut Window, cx: &mut Context<Self>) {
                    self.marked.clear();
                    cx.notify();
                }
                fn replace_text_in_range(
                    &mut self,
                    _: Option<Range<usize>>,
                    text: &str,
                    _: &mut Window,
                    cx: &mut Context<Self>,
                ) {
                    self.marked.clear();
                    self.session.type_text(text);
                    cx.notify();
                }
                fn replace_and_mark_text_in_range(
                    &mut self,
                    _: Option<Range<usize>>,
                    text: &str,
                    _: Option<Range<usize>>,
                    _: &mut Window,
                    cx: &mut Context<Self>,
                ) {
                    self.marked = text.into();
                    cx.notify();
                }
                fn bounds_for_range(
                    &mut self,
                    _: Range<usize>,
                    _: Bounds<Pixels>,
                    _: &mut Window,
                    _: &mut Context<Self>,
                ) -> Option<Bounds<Pixels>> {
                    let cursor = self.session.grid().lock().cursor();
                    // The IME sits at the caret as drawn (the window may follow it).
                    let row = self.rows.projected(cursor.pos.row)?;
                    Some(Bounds::new(
                        self.bounds.origin
                            + point(
                                self.cell.width * cursor.pos.col.0.saturating_sub(self.first_column),
                                px(self.geometry.grid_top(row)),
                            ),
                        self.cell,
                    ))
                }
                fn character_index_for_point(
                    &mut self,
                    _: Point<Pixels>,
                    _: &mut Window,
                    _: &mut Context<Self>,
                ) -> Option<usize> {
                    None
                }
            }

            $($extra)*
        }
    };
}
