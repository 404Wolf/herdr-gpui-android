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
                    grid::Scroll,
                    pos::{Column, Line, Pos, Side},
                    square::Wide,
                    style::StyleFlags,
                },
                selection::{Selection, SelectionType},
            };
            use std::{ops::Range, sync::Arc, time::Duration};

            pub struct Terminal {
                session: Arc<Session>,
                focus: FocusHandle,
                style: TerminalStyle,
                visible: bool,
                bounds: Bounds<Pixels>,
                /// The grid cell (row, column) drawn at the pane's top-left.
                view_origin: (i32, usize),
                cell: Size<Pixels>,
                selecting: bool,
                scroll_delta: f32,
                marked: String,
                host_input: bool,
                /// Opens a tapped link; without one, links are plain text.
                link_handler: Option<std::rc::Rc<dyn Fn(String)>>,
                /// Where the finger went down, while it may still be a tap.
                press: Option<Point<Pixels>>,
                /// The link under that finger: its row, columns and target.
                pressed_link: Option<(Line, Range<usize>, String)>,
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
                            loop {
                                cx.background_executor()
                                    .timer(Duration::from_millis(16))
                                    .await;
                                let alive = this
                                    .update(cx, |this, cx| {
                                        if this.session.take_dirty() && this.visible {
                                            cx.notify();
                                        }
                                        this.session.is_alive()
                                    })
                                    .unwrap_or(false);
                                if !alive {
                                    // The final screen of an ended session stays drawn.
                                    let _ = this.update(cx, |_, cx| cx.notify());
                                    break;
                                }
                            }
                        });
                        Self {
                            session,
                            focus: cx.focus_handle(),
                            style,
                            visible: true,
                            bounds: Bounds::default(),
                            view_origin: (0, 0),
                            cell: size(px(8.), px(18.)),
                            selecting: false,
                            scroll_delta: 0.,
                            marked: String::new(),
                            host_input: false,
                            link_handler: None,
                            press: None,
                            pressed_link: None,
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
                }
                /// For hosts that deliver keys and text to the session themselves, as on
                /// Android, where one IME bridge commits input. The entity then registers
                /// no input handler, ignores key events, never takes focus, and draws
                /// its cursor without focus.
                pub fn set_host_input(&mut self, host_input: bool) {
                    self.host_input = host_input;
                }
                /// Tapping a link (OSC 8, or a plain http(s) URL in the text) calls `handler`.
                pub fn set_link_handler(&mut self, handler: impl Fn(String) + 'static) {
                    self.link_handler = Some(std::rc::Rc::new(handler));
                }
                /// The link at `pos` on screen, with its columns.
                fn link_at(&self, pos: Pos) -> Option<(Line, Range<usize>, String)> {
                    let grid = self.session.grid().lock();
                    let line = pos.row;
                    let columns = grid.columns();
                    if let Some(link) = grid.cell_hyperlink(line, pos.col) {
                        let id = grid.cell_hyperlink_id(line, pos.col);
                        let mut start = pos.col.0;
                        while start > 0 && grid.cell_hyperlink_id(line, Column(start - 1)) == id {
                            start -= 1;
                        }
                        let mut end = pos.col.0 + 1;
                        while end < columns && grid.cell_hyperlink_id(line, Column(end)) == id {
                            end += 1;
                        }
                        return Some((line, start..end, link.uri().to_owned()));
                    }
                    let text: Vec<char> = (0..columns)
                        .map(|col| grid.grid[line][Column(col)].c())
                        .map(|c| if c == '\0' { ' ' } else { c })
                        .collect();
                    let (range, url) = $crate::links::url_at(&text, pos.col.0)?;
                    Some((line, range, url))
                }
                fn cancel_press(&mut self) -> bool {
                    self.press = None;
                    self.pressed_link.take().is_some()
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

                fn position(&self, point: Point<Pixels>) -> Pos {
                    let grid = self.session.grid().lock();
                    let row =
                        (f32::from(point.y - self.bounds.top()) / f32::from(self.cell.height)).floor() as i32;
                    let col =
                        (f32::from(point.x - self.bounds.left()) / f32::from(self.cell.width)).floor() as usize;
                    // Through the window drawn (it may follow the caret on a larger grid).
                    let (row_off, col_off) = self.view_origin;
                    Pos::new(
                        Line((row + row_off).clamp(0, grid.screen_lines() as i32 - 1) - grid.display_offset() as i32),
                        Column((col + col_off).min(grid.columns() - 1)),
                    )
                }

                fn paint(&mut self, bounds: Bounds<Pixels>, window: &mut Window, cx: &mut Context<Self>) {
                    self.bounds = bounds;
                    let font_size = px(if self.style.font_size.is_finite() {
                        self.style.font_size.clamp(1., 256.)
                    } else { 13. });
                    let font = font(terminal_font_family(self.style.font_family.clone().into(), cx));
                    let run = TextRun {
                        len: 1,
                        font: font.clone(),
                        color: rgb_color(self.style.theme.foreground),
                        background_color: None,
                        underline: None,
                        strikethrough: None,
                    };
                    let measure = window
                        .text_system()
                        .shape_line("M".into(), font_size, &[run], None);
                    // The font's own line spacing, as other terminals use: tall
                    // glyphs and box drawing meet row to row, and no added leading
                    // takes rows from a small screen.
                    self.cell = size(
                        measure.width.max(px(1.)),
                        (measure.ascent + measure.descent).ceil().max(px(1.)),
                    );
                    let cols = (f32::from(bounds.size.width) / f32::from(self.cell.width))
                        .floor()
                        .clamp(2., 1000.) as u16;
                    let rows = (f32::from(bounds.size.height) / f32::from(self.cell.height))
                        .floor()
                        .clamp(1., 1000.) as u16;
                    // Resize before locking: the session locks the grid itself.
                    self.session.resize(
                        cols,
                        rows,
                        f32::from(bounds.size.width) as u16,
                        f32::from(bounds.size.height) as u16,
                    );
                    // The grid may be the remote side's negotiated one. Smaller than
                    // the pane: drawn whole, the rest left empty (letterboxed).
                    // Larger: a pane-sized window into it that follows the caret.
                    let (grid_cols, grid_rows) = self.session.grid_size();
                    let pane = (cols, rows);
                    let (cols, rows) = (
                        usize::from(cols.min(grid_cols)),
                        usize::from(rows.min(grid_rows)),
                    );
                    let theme = self.style.theme;
                    let mut grid = self.session.grid().lock();
                    let selection = grid
                        .selection
                        .as_ref()
                        .and_then(|selection| selection.to_range(&*grid));
                    let cursor = grid.cursor();
                    let offset = grid.display_offset() as i32;
                    let (row_off, col_off) = if offset == 0 {
                        $crate::viewport_origin((grid_cols, grid_rows), pane, (cursor.pos.row.0, cursor.pos.col.0))
                    } else {
                        (0, 0)
                    };
                    self.view_origin = (row_off, col_off);
                    let cursor_shown = self.host_input || self.focus.is_focused(window);
                    window.paint_quad(fill(bounds, rgb_color(theme.background)));
                    for row in 0..rows {
                        for col in 0..cols {
                            let pos = Pos::new(Line(row as i32 + row_off - offset), Column(col + col_off));
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
                                && cursor.pos == Pos::new(Line(row as i32 + row_off), Column(col + col_off))
                                && offset == 0;
                            if selected || (cursor_here && cursor.content == CursorShape::Block) {
                                std::mem::swap(&mut fg, &mut bg);
                            }
                            let origin = bounds.origin + point(self.cell.width * col, self.cell.height * row);
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
                            if let Some((line, range, _)) = &self.pressed_link
                                && *line == pos.row
                                && range.contains(&col)
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
                            let text: String = grid
                                .grid
                                .cell_text(pos)
                                .map(|c| if c == '\0' { ' ' } else { c })
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
                    if !self.host_input && !self.marked.is_empty() && self.focus.is_focused(window) {
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
                                self.cell.height * (cursor.pos.row.0 - row_off) as f32,
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
                                let pos = this.position(event.position);
                                this.press = Some(event.position);
                                this.pressed_link = if this.link_handler.is_some() {
                                    this.link_at(pos)
                                } else {
                                    None
                                };
                                this.session.grid().lock().selection =
                                    Some(Selection::new(SelectionType::Simple, pos, Side::Left));
                                this.selecting = true;
                                cx.notify();
                            }),
                        )
                        .on_mouse_move(cx.listener(|this, event: &MouseMoveEvent, _, cx| {
                            // A finger that travels is a scroll or a selection, not a tap.
                            if let Some(start) = this.press {
                                let moved = event.position - start;
                                let slop = this.cell.width.max(px(8.));
                                if moved.x.abs() > slop || moved.y.abs() > slop {
                                    if this.cancel_press() {
                                        cx.notify();
                                    }
                                }
                            }
                            if this.selecting && event.pressed_button == Some(MouseButton::Left) {
                                let pos = this.position(event.position);
                                if let Some(selection) = &mut this.session.grid().lock().selection {
                                    selection.update(pos, Side::Right);
                                }
                                cx.notify();
                            } else {
                                this.selecting = false;
                            }
                        }))
                        .on_mouse_up(
                            MouseButton::Left,
                            cx.listener(|this, _, _, cx| {
                                this.selecting = false;
                                this.press = None;
                                if let Some((_, _, url)) = this.pressed_link.take() {
                                    // A tap on a link opens it and selects nothing.
                                    this.session.grid().lock().selection = None;
                                    if let Some(handler) = this.link_handler.clone() {
                                        handler(url);
                                    }
                                    cx.notify();
                                }
                            }),
                        )
                        .on_scroll_wheel(cx.listener(|this, event: &ScrollWheelEvent, _, cx| {
                            this.cancel_press();
                            let delta = event.delta.pixel_delta(this.cell.height).y;
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
                    let (row_off, col_off) = self.view_origin;
                    Some(Bounds::new(
                        self.bounds.origin
                            + point(
                                self.cell.width * cursor.pos.col.0.saturating_sub(col_off),
                                self.cell.height * (cursor.pos.row.0 - row_off) as f32,
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
