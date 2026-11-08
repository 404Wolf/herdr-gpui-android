//! GPUI elements rendered by the mobile wgpu/Vulkan backend, with virtual rows.
use crate::{
    cell_shapes,
    model::{self, DisplayMetrics, ViewState},
};
use gpui::{prelude::*, *};
use std::{cell::RefCell, ops::Range, rc::Rc, sync::Arc};

/// Live companion terminal view, embeddable in a GPUI window.
pub struct TerminalView {
    state: ViewState,
    focus: FocusHandle,
    scroll: UniformListScrollHandle,
    follow: bool,
    marked: String,
    input_bounds: Bounds<Pixels>,
    viewport: Size<Pixels>,
    error: Option<String>,
    /// Whether the last frame filled the letterbox; it holds a filled one longer.
    letterbox_filled: bool,
    /// The raw SSH shell drawn instead of Herdr's pane, by its handle.
    #[cfg(unix)]
    shell: Option<(u64, Entity<crate::rio::Terminal>)>,
    /// The style last given to the shell, so unchanged frames do not repaint it.
    #[cfg(unix)]
    shell_style: Option<gpui_rio::TerminalStyle>,
    _updates: Task<()>,
}

impl TerminalView {
    pub fn new(window: &mut Window, cx: &mut Context<Self>) -> Self {
        let focus = cx.focus_handle();
        window.focus(&focus, cx);
        let changes = model::snapshot_changes();
        let updates = cx.spawn(async move |this, cx| {
            while changes.recv().await.is_ok() {
                if this
                    .update(cx, |this, cx| {
                        if this.refresh_snapshot(cx) {
                            cx.notify();
                        }
                    })
                    .is_err()
                {
                    break;
                }
            }
        });
        let mut view = Self {
            state: model::mailbox().state(),
            focus,
            scroll: UniformListScrollHandle::new(),
            follow: true,
            marked: String::new(),
            input_bounds: Bounds::default(),
            viewport: Size::default(),
            error: None,
            letterbox_filled: false,
            #[cfg(unix)]
            shell: None,
            #[cfg(unix)]
            shell_style: None,
            _updates: updates,
        };
        // The window can open after a shell was attached, with no later change
        // to wake the view: show what the mailbox already names.
        #[cfg(unix)]
        view.attach_shell(view.state.shell, cx);
        view
    }

    fn refresh_snapshot(&mut self, cx: &mut Context<Self>) -> bool {
        let state = model::mailbox().state();
        if self.state.revision == state.revision {
            return false;
        }
        #[cfg(unix)]
        self.attach_shell(state.shell, cx);
        #[cfg(not(unix))]
        let _ = cx;
        let new_generation = self.state.generation != state.generation;
        if new_generation || !state.active {
            self.marked.clear();
            self.error = None;
        }
        let output_changed = !Arc::ptr_eq(&self.state.snapshot, &state.snapshot)
            && self.state.snapshot.lines != state.snapshot.lines;
        // A scrolled window keeps its line count; only an append moves the tail.
        let appended = self.state.snapshot.lines.len() != state.snapshot.lines.len();
        let geometry_changed = self.state.display_preferences != state.display_preferences
            || self.state.preedit.is_empty() != state.preedit.is_empty()
            || self.state.snapshot.truncated != state.snapshot.truncated;
        if self.follow != state.follow {
            self.follow = state.follow;
        }
        if self.follow
            && (new_generation || (output_changed && appended) || geometry_changed)
            && !state.snapshot.lines.is_empty()
        {
            self.scroll
                .scroll_to_item(state.snapshot.lines.len() - 1, ScrollStrategy::Bottom);
        }
        if self.marked != *state.preedit {
            self.marked = state.preedit.to_string();
        }
        self.state = state;
        true
    }

    /// Shows the shell `handle` names, or Herdr's pane again for 0. Input
    /// stays with this view: Java's IME bridge commits it through the mailbox.
    #[cfg(unix)]
    fn attach_shell(&mut self, handle: u64, cx: &mut Context<Self>) {
        if self.shell.as_ref().map_or(0, |(current, _)| *current) == handle {
            return;
        }
        self.shell_style = None;
        self.shell = crate::shell::session(handle)
            .or_else(|| crate::remote::renderer(handle))
            .map(|session| {
                let style = self.shell_style(self.viewport.width);
                let terminal = crate::rio::Terminal::attach(session, style, cx);
                terminal.update(cx, |terminal, _| terminal.set_host_input(true));
                (handle, terminal)
            });
    }

    #[cfg(unix)]
    fn shell_style(&self, width: Pixels) -> gpui_rio::TerminalStyle {
        gpui_rio::TerminalStyle {
            theme: crate::shell::theme(&model::palette()),
            font_family: crate::fonts::MONO_FAMILY.into(),
            font_size: model::shell_font_size(self.state.display_preferences, width.into()),
        }
    }

    /// The attached shell's element, after giving it the current theme and size.
    #[cfg(unix)]
    fn shell_element(&mut self, width: Pixels, cx: &mut Context<Self>) -> Option<AnyElement> {
        let terminal = self.shell.as_ref()?.1.clone();
        let style = self.shell_style(width);
        if self.shell_style.as_ref() != Some(&style) {
            let next = style.clone();
            terminal.update(cx, |terminal, cx| {
                terminal.update_style(next);
                cx.notify();
            });
            self.shell_style = Some(style);
        }
        Some(terminal.into_any_element())
    }

    #[allow(dead_code)]
    fn close(&mut self, generation: i64, cx: &mut Context<Self>) {
        #[cfg(target_os = "android")]
        if crate::android::close_activity(generation) {
            return;
        }
        let _ = generation;
        self.error = Some("Use Android Back to return to your host".into());
        cx.notify();
    }

    #[allow(dead_code)]
    fn keyboard(&mut self, generation: i64, window: &mut Window, cx: &mut Context<Self>) {
        let state = model::mailbox().state();
        if !state.active || state.generation != generation {
            self.error = Some("Terminal input is inactive".into());
            cx.notify();
            return;
        }
        window.focus(&self.focus, cx);
        #[cfg(target_os = "android")]
        if crate::android::request_keyboard(generation) {
            gpui_mobile::show_keyboard();
        }
    }

    fn ime_buffer(&self) -> &str {
        // A single virtual preceding cell lets an empty Android EditText request
        // backspace without pretending the rendered output is editable text.
        if self.marked.is_empty() {
            " "
        } else {
            &self.marked
        }
    }
}

#[allow(dead_code)]
fn button(
    id: impl Into<ElementId>,
    label: impl Into<SharedString>,
    metrics: DisplayMetrics,
) -> Stateful<Div> {
    div()
        .id(id)
        .flex_1()
        .min_w_0()
        .h(px(metrics.touch_height))
        .min_h(px(metrics.touch_height))
        .flex()
        .items_center()
        .justify_center()
        .px(px(3.))
        .rounded_md()
        .bg(rgb(model::palette().chrome))
        .text_color(rgb(model::palette().foreground))
        .text_size(px(metrics.font_size))
        .cursor_pointer()
        .child(div().min_w_0().truncate().child(label.into()))
}

/// Text, style runs, tappable byte ranges and drawn cells for one painted row.
struct GridRow {
    text: SharedString,
    runs: Vec<TextRun>,
    links: Vec<(Range<usize>, String)>,
    drawn: Vec<DrawnCell>,
}

/// A character painted as shapes over its hidden glyph; see [`cell_shapes`].
#[derive(Clone, Copy, Debug, PartialEq)]
struct DrawnCell {
    /// Byte offset of the character in the row text.
    byte: usize,
    ch: char,
    /// The colour its glyph would have been painted in.
    color: Hsla,
}

/// The layout a linked row painted, kept so a tap resolves against it.
type PaintedLine = Rc<RefCell<Option<(Point<Pixels>, ShapedLine)>>>;

impl GridRow {
    /// `fill` is the colour behind the row; runs on that colour paint nothing.
    fn styled(
        row: &model::StyledRow,
        links: &[String],
        palette: &model::Palette,
        fill: u32,
    ) -> Self {
        let runs = row
            .runs
            .iter()
            .map(|run| text_run(run, palette, fill))
            .collect();
        let (runs, drawn) = hide_drawn_glyphs(&row.text, runs);
        Self {
            text: row.text.clone().into(),
            runs,
            links: row
                .runs
                .iter()
                .filter_map(|run| {
                    let url = links.get(usize::from(run.link?))?.clone();
                    Some((run.start..run.start + run.len, url))
                })
                .collect(),
            drawn,
        }
    }

    fn plain(line: &Arc<str>, palette: &model::Palette) -> Self {
        let run = TextRun {
            len: line.len(),
            font: mono_font(),
            color: rgb(palette.foreground).into(),
            background_color: None,
            underline: None,
            strikethrough: None,
        };
        let (runs, drawn) = hide_drawn_glyphs(line, vec![run]);
        Self {
            text: SharedString::from(line.clone()),
            runs,
            links: Vec::new(),
            drawn,
        }
    }

    /// Paints the row on the cell grid: every glyph starts at its own column,
    /// including glyphs a proportional system font supplies, so box borders
    /// and columns line up across rows. Box-drawing and block characters are
    /// shapes filling their cells, so borders and bars join the rows above and
    /// below however far apart the rows are spread. Linked rows resolve a tap
    /// against the same glyph positions they painted.
    fn into_element(
        self,
        generation: i64,
        row_index: usize,
        font_size: Pixels,
        cell_width: Pixels,
    ) -> AnyElement {
        if self.text.is_empty() {
            return div().into_any_element();
        }
        let Self {
            text,
            runs,
            links,
            drawn,
        } = self;
        let painted: PaintedLine = Rc::default();
        let keep = (!links.is_empty()).then(|| painted.clone());
        let line = canvas(
            move |_, window, _| {
                window
                    .text_system()
                    .shape_line(text, font_size, &runs, Some(cell_width))
            },
            move |bounds, line, window, cx| {
                let height = bounds.size.height;
                let _ =
                    line.paint_background(bounds.origin, height, TextAlign::Left, None, window, cx);
                let _ = line.paint(bounds.origin, height, TextAlign::Left, None, window, cx);
                paint_drawn(&drawn, &line, bounds, cell_width, window);
                if let Some(keep) = keep {
                    *keep.borrow_mut() = Some((bounds.origin, line));
                }
            },
        )
        .size_full();
        if links.is_empty() {
            return line.into_any_element();
        }
        div()
            .id(("terminal-row", row_index))
            .size_full()
            .cursor_pointer()
            .child(line)
            .on_click(move |event, _, _cx| {
                let url = painted.borrow().as_ref().and_then(|(origin, line)| {
                    link_at(line, &links, event.position().x - origin.x).map(str::to_owned)
                });
                let Some(url) = url else {
                    return;
                };
                #[cfg(target_os = "android")]
                crate::android::open_link(generation, &url);
                #[cfg(not(target_os = "android"))]
                {
                    let state = model::mailbox().state();
                    if state.active && state.generation == generation {
                        _cx.open_url(&url);
                    }
                }
            })
            .into_any_element()
    }
}

/// The link under a tap `x` pixels into a painted row, if any.
fn link_at<'a>(
    line: &ShapedLine,
    links: &'a [(Range<usize>, String)],
    x: Pixels,
) -> Option<&'a str> {
    let index = line.index_for_x(x)?;
    links
        .iter()
        .find(|(range, _)| range.contains(&index))
        .map(|(_, url)| url.as_str())
}

/// Hides the glyph of every character painted as shapes. Its run keeps its
/// font, background and decorations, so the row's bytes, links and layout are
/// unchanged, but its text is transparent. Returns the new runs and the cells
/// to paint, each in the colour its glyph would have had.
fn hide_drawn_glyphs(text: &str, runs: Vec<TextRun>) -> (Vec<TextRun>, Vec<DrawnCell>) {
    if !text.chars().any(cell_shapes::drawn) {
        return (runs, Vec::new());
    }
    let mut split = Vec::with_capacity(runs.len() * 2);
    let mut drawn = Vec::new();
    let mut start = 0;
    for run in runs {
        let end = start + run.len;
        // Runs tile the text on character boundaries; keep any other run whole.
        let Some(slice) = text.get(start..end) else {
            start = end;
            split.push(run);
            continue;
        };
        let mut next = start;
        for (offset, ch) in slice.char_indices() {
            if !cell_shapes::drawn(ch) {
                continue;
            }
            let byte = start + offset;
            if byte > next {
                split.push(TextRun {
                    len: byte - next,
                    ..run.clone()
                });
            }
            split.push(TextRun {
                len: ch.len_utf8(),
                color: transparent_black(),
                ..run.clone()
            });
            drawn.push(DrawnCell {
                byte,
                ch,
                color: run.color,
            });
            next = byte + ch.len_utf8();
        }
        if end > next {
            split.push(TextRun {
                len: end - next,
                ..run
            });
        }
        start = end;
    }
    (split, drawn)
}

/// Each drawn cell with the grid column of its glyph in `line`. A glyph sits on
/// its column to within a pixel, and after a combining mark a column is no
/// longer a character count, so the shaped position decides.
fn drawn_columns<'a>(
    line: &'a ShapedLine,
    cells: &'a [DrawnCell],
    cell_width: f32,
) -> impl Iterator<Item = (&'a DrawnCell, f32)> + 'a {
    line.runs
        .iter()
        .flat_map(|run| run.glyphs.iter())
        .filter_map(move |glyph| {
            let found = cells
                .binary_search_by_key(&glyph.index, |cell| cell.byte)
                .ok()?;
            let cell = cells.get(found)?;
            Some((cell, (f32::from(glyph.position.x) / cell_width).round()))
        })
}

/// `logical` in whole device pixels, rounded the way GPUI rounds quad edges,
/// so drawn cells share their edges with the row backgrounds.
fn device_pixel(logical: f32, scale: f32) -> i32 {
    let scaled = logical * scale;
    (scaled.abs() - 0.5).ceil().copysign(scaled) as i32
}

/// Paints `cells` of the row at `bounds` as shapes filling their whole cells:
/// the row's full height, so a border meets the rows above and below however
/// far apart the rows are spread.
fn paint_drawn(
    cells: &[DrawnCell],
    line: &ShapedLine,
    bounds: Bounds<Pixels>,
    cell_width: Pixels,
    window: &mut Window,
) {
    let width = f32::from(cell_width);
    if cells.is_empty() || width <= 0. {
        return;
    }
    let scale = window.scale_factor();
    let light = cell_shapes::light_stroke(device_pixel(width, scale));
    let top = device_pixel(f32::from(bounds.top()), scale);
    let bottom = device_pixel(f32::from(bounds.bottom()), scale);
    let left = f32::from(bounds.left());
    let logical = |value: i32| px(value as f32 / scale);
    let to_bounds = |rect: cell_shapes::Rect| {
        Bounds::from_corners(
            point(logical(rect.left), logical(rect.top)),
            point(logical(rect.right), logical(rect.bottom)),
        )
    };
    for (cell, column) in drawn_columns(line, cells, width) {
        let rect = cell_shapes::Rect {
            left: device_pixel(left + column * width, scale),
            top,
            right: device_pixel(left + (column + 1.) * width, scale),
            bottom,
        };
        cell_shapes::shapes(cell.ch, rect, light, |shape| match shape {
            cell_shapes::Shape::Fill(rect) => window.paint_quad(fill(to_bounds(rect), cell.color)),
            cell_shapes::Shape::Shade(rect, alpha) => {
                window.paint_quad(fill(to_bounds(rect), cell.color.opacity(alpha)));
            }
            cell_shapes::Shape::Arc {
                outline,
                corner,
                radius,
                width,
                clip,
            } => {
                let (radius, width, none) = (logical(radius), logical(width), px(0.));
                let (top_left, top_right, bottom_right, bottom_left, sides) = match corner {
                    cell_shapes::Corner::TopLeft => {
                        (radius, none, none, none, (width, none, none, width))
                    }
                    cell_shapes::Corner::TopRight => {
                        (none, radius, none, none, (width, width, none, none))
                    }
                    cell_shapes::Corner::BottomRight => {
                        (none, none, radius, none, (none, width, width, none))
                    }
                    cell_shapes::Corner::BottomLeft => {
                        (none, none, none, radius, (none, none, width, width))
                    }
                };
                let corners = Corners {
                    top_left,
                    top_right,
                    bottom_right,
                    bottom_left,
                };
                let (top, right, bottom, left) = sides;
                let edges = Edges {
                    top,
                    right,
                    bottom,
                    left,
                };
                window.with_content_mask(
                    Some(ContentMask {
                        bounds: to_bounds(clip),
                    }),
                    |window| {
                        window.paint_quad(quad(
                            to_bounds(outline),
                            corners,
                            transparent_black(),
                            edges,
                            cell.color,
                            BorderStyle::Solid,
                        ));
                    },
                );
            }
        });
    }
}

fn mono_font() -> Font {
    Font {
        family: crate::fonts::MONO_FAMILY.into(),
        features: Default::default(),
        fallbacks: None,
        weight: FontWeight::NORMAL,
        style: FontStyle::Normal,
    }
}

/// One terminal run painted with the palette's colors and its attributes. Its
/// background is left to the surface only when the surface already shows it.
fn text_run(run: &model::StyledRun, palette: &model::Palette, fill: u32) -> TextRun {
    let mut font = mono_font();
    if run.modifier & model::BOLD != 0 {
        font.weight = FontWeight::BOLD;
    }
    if run.modifier & model::ITALIC != 0 {
        font.style = FontStyle::Italic;
    }
    let (fg, bg) = model::styled_colors(run, palette);
    let color = rgb(fg).into();
    // The daemon's frame on the default background is part of the surround.
    let surround = run.modifier & model::FRAME != 0 && bg == palette.background;
    TextRun {
        len: run.len,
        font,
        color,
        background_color: (bg != fill && !surround).then(|| rgb(bg).into()),
        underline: (run.modifier & model::UNDERLINE != 0).then(|| UnderlineStyle {
            thickness: px(1.),
            color: Some(color),
            wavy: false,
        }),
        strikethrough: (run.modifier & model::STRIKETHROUGH != 0).then(|| StrikethroughStyle {
            thickness: px(1.),
            color: Some(color),
        }),
    }
}

impl Render for TerminalView {
    fn render(&mut self, window: &mut Window, cx: &mut Context<Self>) -> impl IntoElement {
        let snapshot = self.state.snapshot.clone();
        let rows = snapshot.clone();
        let entity = cx.entity();
        let active = self.state.active;
        let generation = self.state.generation;
        let status = self
            .error
            .clone()
            .unwrap_or_else(|| snapshot.status.clone());
        let viewport = window.viewport_size();
        #[cfg(unix)]
        let shell = self.shell_element(viewport.width, cx);
        #[cfg(not(unix))]
        let shell: Option<AnyElement> = None;
        let shell_mode = shell.is_some();
        let metrics = DisplayMetrics::fit(
            self.state.display_preferences,
            viewport.width.into(),
            snapshot.widest_line_chars,
        );
        if viewport != self.viewport {
            self.viewport = viewport;
            if self.follow && !snapshot.lines.is_empty() {
                self.scroll
                    .scroll_to_item(snapshot.lines.len() - 1, ScrollStrategy::Bottom);
            }
        }
        let row_width = px(metrics.row_width(snapshot.widest_line_chars));
        // Panning ends at the last cell; a step past it would only show blank.
        let overflow =
            (metrics.row_width(snapshot.widest_line_chars) - f32::from(viewport.width)).max(0.);
        model::mailbox().set_pan_limit(overflow);
        let pan = self.state.pan.min(overflow);
        // The bars under the body have one fixed height, so the body's height is
        // known before layout: the viewport less the header and visible bars.
        let notice_height = (metrics.font_size * 1.5 + 8.).ceil();
        let notices = usize::from(snapshot.truncated) + usize::from(!self.marked.is_empty());
        let surface_height = f32::from(viewport.height)
            - if shell_mode {
                0.
            } else {
                metrics.header_height
            }
            - notices as f32 * notice_height;
        // Herdr fixes a pane's rows, usually fewer than a phone panel shows.
        // Their pitch grows until they fill the body, up to twice the natural
        // pitch, so the type size and every column stay as they are.
        let pitch =
            model::spread_row_height(metrics.row_height, snapshot.lines.len(), surface_height);
        let row_height = px(pitch);
        let font_size = px(metrics.font_size);
        let cell_width = px(metrics.cell_width());
        let row_inset = px(metrics.row_inset());
        let palette = model::palette();
        let chrome = model::ChromeRoles::of(&palette);
        // A program that paints its own background fills the letterbox with it,
        // so its pane is not a dark card in a light frame, or the reverse.
        let letterbox = model::letterbox(&snapshot.edge_colors, &palette, self.letterbox_filled);
        self.letterbox_filled = letterbox.is_some();
        let fill = letterbox.unwrap_or(palette.background);
        // Past the spread cap a pane still falls short; it keeps even margins.
        let content_height = pitch * snapshot.lines.len() as f32;
        let short_pane = content_height < surface_height - 1.;
        // An empty pane must say why it is empty: a bare surface reads as a crash.
        let empty_card = div()
            .flex()
            .flex_col()
            .items_center()
            .gap(px(metrics.gap * 2.))
            .px(px(metrics.padding * 4.))
            .child(
                div()
                    .text_size(px(metrics.font_size * 1.3))
                    .font_weight(FontWeight::SEMIBOLD)
                    .child(if status.is_empty() {
                        SharedString::from("Connecting to the pane…")
                    } else {
                        SharedString::from(status.clone())
                    }),
            )
            .child(
                div()
                    .text_size(px(metrics.font_size))
                    .text_color(rgb(model::palette().muted))
                    .text_center()
                    .child(
                        "No terminal surface yet. Press Back to choose a host, \
                         or wait if the connection is still opening.",
                    ),
            );
        div()
            .size_full()
            .flex()
            .flex_col()
            .overflow_hidden()
            .bg(rgb(model::palette().background))
            .text_color(rgb(model::palette().foreground))
            .when(false, |root| root)
            .font_family(crate::fonts::SANS_FAMILY)
            .track_focus(&self.focus)
            // A shell has no pane title or agent state to show: its rows take
            // the header's height, and its exit or failure shows in the banner.
            .when(!shell_mode, |root| {
                root.child(
                    div()
                        .flex_none()
                        .min_w_0()
                        .h(px(metrics.header_height))
                        // The Java toolbar, rail and system bars take the same roles,
                        // so a light theme gets light chrome around its light page.
                        .bg(rgb(chrome.surface))
                        .text_color(rgb(chrome.on_surface))
                        .flex()
                        .items_center()
                        .gap(px(metrics.gap))
                        .px(row_inset)
                        .border_b_1()
                        .border_color(rgb(chrome.outline))
                        // Chrome keeps its own scale: the pane title must stay legible
                        // on the cover, where the fitted terminal type drops to 11 px.
                        .text_size(px(metrics.font_size.clamp(13., 16.)))
                        .child(
                            div()
                                .flex_1()
                                .min_w_0()
                                .truncate()
                                .font_weight(FontWeight::SEMIBOLD)
                                .child(snapshot.title.clone()),
                        )
                        .child(
                            // The status is a short state word, not a second title: give
                            // the pane identity the rest of the row instead of half of it.
                            div()
                                .flex_none()
                                .max_w(px(metrics.font_size * 14.))
                                .min_w_0()
                                .truncate()
                                .text_right()
                                .text_color(rgb(if self.error.is_some() {
                                    chrome.danger
                                } else if active {
                                    chrome.primary
                                } else {
                                    chrome.warning
                                }))
                                .child(status),
                        ),
                )
            })
            .child({
                let mut body = div()
                    .flex_1()
                    .min_h_0()
                    .min_w_0()
                    .relative()
                    .overflow_hidden()
                    .bg(rgb(fill))
                    .flex()
                    .flex_col();
                // A horizontal drag pans the pane; vertical deltas stay with the
                // list so scrolling keeps working exactly as before.
                // A shell fits its own width and scrolls its own history.
                if !shell_mode {
                    body = body.on_scroll_wheel(move |event, _, _| {
                        let (dx, dy) = match event.delta {
                            ScrollDelta::Pixels(point) => (f32::from(point.x), f32::from(point.y)),
                            ScrollDelta::Lines(point) => (point.x, point.y),
                        };
                        if let Some(delta) = model::pan_from_wheel(dx, dy) {
                            model::mailbox().pan(delta);
                        }
                    });
                }
                body.when(snapshot.lines.is_empty() && !shell_mode, |body| {
                    body.items_center().justify_center()
                })
                .when_some(shell, |body, shell| body.child(shell))
                .when(pan > 0. && !shell_mode, |body| {
                    // A panned pane has no visible left edge; mark it so a cut
                    // line is never mistaken for a broken render.
                    body.child(
                        div()
                            .absolute()
                            .left_0()
                            .top_0()
                            .bottom_0()
                            .w(px(2.))
                            .bg(rgb(model::palette().accent)),
                    )
                })
                .when(!snapshot.lines.is_empty() && !shell_mode, |body| {
                    // A pane shorter than the surface gets even margins above
                    // and below; one tall band over the text reads as a fault.
                    body.when(short_pane, |body| body.justify_center()).child(
                        div()
                            .min_w_0()
                            .when(short_pane, |box_| box_.h(px(content_height.max(0.))))
                            .when(!short_pane, |box_| box_.size_full())
                            .child(
                                div().h_full().w(row_width).ml(px(-pan)).child(
                                    uniform_list(
                                        "terminal-lines",
                                        snapshot.lines.len(),
                                        move |range, _, _| {
                                            range
                                                .map(|index| {
                                                    let line = div()
                                                        .h(row_height)
                                                        .w(row_width)
                                                        // Match the header's inset so the terminal and
                                                        // the pane title share one left edge.
                                                        .px(row_inset)
                                                        .font_family(crate::fonts::MONO_FAMILY)
                                                        .text_size(font_size)
                                                        .line_height(row_height)
                                                        .whitespace_nowrap();
                                                    // Painted cells win when their runs tile the
                                                    // plain line; otherwise keep the plain text.
                                                    let row = match rows
                                                        .styled
                                                        .as_ref()
                                                        .and_then(|styled| styled.get(index))
                                                    {
                                                        Some(styled) => GridRow::styled(
                                                            styled,
                                                            &rows.links,
                                                            &palette,
                                                            fill,
                                                        ),
                                                        None => GridRow::plain(
                                                            &rows.lines[index],
                                                            &palette,
                                                        ),
                                                    };
                                                    line.child(row.into_element(
                                                        generation, index, font_size, cell_width,
                                                    ))
                                                })
                                                .collect::<Vec<_>>()
                                        },
                                    )
                                    .size_full()
                                    .with_horizontal_sizing_behavior(
                                        ListHorizontalSizingBehavior::Unconstrained,
                                    )
                                    .track_scroll(&self.scroll),
                                ),
                            ),
                    )
                })
                .when(snapshot.lines.is_empty() && !shell_mode, |body| {
                    body.child(empty_card)
                })
                .child(
                    canvas(
                        |_, _, _| (),
                        move |bounds, _, window, cx| {
                            entity.update(cx, |this, cx| {
                                let height = row_height.min(bounds.size.height);
                                this.input_bounds = Bounds::new(
                                    point(bounds.left(), bounds.bottom() - height),
                                    size(px(2.).min(bounds.size.width), height),
                                );
                                window.handle_input(
                                    &this.focus,
                                    ElementInputHandler::new(this.input_bounds, cx.entity()),
                                    cx,
                                );
                            });
                        },
                    )
                    .absolute()
                    .size_full(),
                )
                .into_any_element()
            })
            .when(snapshot.truncated, |root| {
                root.child(
                    div()
                        .flex_none()
                        .px(px(metrics.padding + 4.))
                        .h(px(notice_height))
                        .line_height(px(notice_height))
                        .text_size(px(metrics.font_size))
                        .truncate()
                        .text_color(rgb(model::readable(
                            palette.ansi[3],
                            palette.background,
                            palette.foreground,
                            model::TEXT_CONTRAST,
                        )))
                        .child(if self.state.display_preferences.compact {
                            "Display limit reached"
                        } else {
                            "Display capped at 4,000 lines / 4,096 characters per line"
                        }),
                )
            })
            .when(!self.marked.is_empty(), |root| {
                root.child(
                    div()
                        .flex_none()
                        .px(px(metrics.padding + 4.))
                        .h(px(notice_height))
                        .line_height(px(notice_height))
                        .text_size(px(metrics.font_size))
                        .truncate()
                        .bg(rgb(model::palette().border))
                        .child(format!("Composing: {}", self.marked)),
                )
            })
    }
}

impl EntityInputHandler for TerminalView {
    fn text_for_range(
        &mut self,
        range: Range<usize>,
        adjusted: &mut Option<Range<usize>>,
        _: &mut Window,
        _: &mut Context<Self>,
    ) -> Option<String> {
        let text: Vec<u16> = self.ime_buffer().encode_utf16().collect();
        let start = range.start.min(text.len());
        let end = range.end.min(text.len()).max(start);
        *adjusted = Some(start..end);
        Some(String::from_utf16_lossy(&text[start..end]))
    }
    fn selected_text_range(
        &mut self,
        _: bool,
        _: &mut Window,
        _: &mut Context<Self>,
    ) -> Option<UTF16Selection> {
        let end = self.ime_buffer().encode_utf16().count();
        Some(UTF16Selection {
            range: end..end,
            reversed: false,
        })
    }
    fn marked_text_range(&self, _: &mut Window, _: &mut Context<Self>) -> Option<Range<usize>> {
        (!self.marked.is_empty()).then(|| 0..self.marked.encode_utf16().count())
    }
    fn unmark_text(&mut self, _: &mut Window, cx: &mut Context<Self>) {
        if !self.marked.is_empty() {
            self.marked.clear();
            cx.notify();
        }
    }
    fn replace_text_in_range(
        &mut self,
        _: Option<Range<usize>>,
        _: &str,
        _: &mut Window,
        cx: &mut Context<Self>,
    ) {
        // Java's ordered nativeImeEvent bridge is the sole remote commit path.
        // The platform handler only presents local composition/configuration.
        if !self.marked.is_empty() {
            self.marked.clear();
            cx.notify();
        }
    }
    fn replace_and_mark_text_in_range(
        &mut self,
        _: Option<Range<usize>>,
        text: &str,
        _: Option<Range<usize>>,
        _: &mut Window,
        cx: &mut Context<Self>,
    ) {
        let state = model::mailbox().state();
        if state.active
            && state.generation == self.state.generation
            && text.len() <= model::MAX_TEXT_BYTES
            && self.marked != text
        {
            self.marked = text.into();
            cx.notify();
        }
    }
    fn bounds_for_range(
        &mut self,
        _: Range<usize>,
        _: Bounds<Pixels>,
        _: &mut Window,
        _: &mut Context<Self>,
    ) -> Option<Bounds<Pixels>> {
        Some(self.input_bounds)
    }
    fn character_index_for_point(
        &mut self,
        _: Point<Pixels>,
        _: &mut Window,
        _: &mut Context<Self>,
    ) -> Option<usize> {
        None
    }
    fn accepts_text_input(&self, _: &mut Window, _: &mut Context<Self>) -> bool {
        let state = model::mailbox().state();
        state.active && state.generation == self.state.generation
    }
    fn text_input_configuration(
        &mut self,
        _: &mut Window,
        _: &mut Context<Self>,
    ) -> TextInputConfiguration {
        TextInputConfiguration {
            input_action: TextInputAction::Enter,
            ..TextInputConfiguration::default()
        }
    }
}

#[cfg(test)]
mod tests {
    #![allow(clippy::unwrap_used)]
    use super::{DisplayMetrics, DrawnCell, GridRow, drawn_columns, link_at, text_run};
    use crate::model;
    use gpui::{TextRun, TextSystem, WindowTextSystem, font, px, transparent_black};
    use std::sync::Arc;

    #[test]
    fn grid_rows_keep_every_cell_in_its_column_across_styles_and_fallbacks() {
        let text_system = Arc::new(TextSystem::new(Arc::new(
            gpui_wgpu::CosmicTextSystem::new_without_system_fonts("Roboto"),
        )));
        crate::fonts::install(&text_system).unwrap();
        let window_text = WindowTextSystem::new(text_system);
        // A wide glyph plus its placeholder, a box-drawing glyph the bundled
        // font lacks, and a decorated cell: columns 0, 1, 2, 3 and 4.
        let (rows, _) = model::parse_surface(
            r#"{"rows":[{"t":"a界 ─Z","r":[[0,1,0,0,0,4294967295],[1,4,16777217,0,8,4294967295],[5,3,0,0,0,4294967295],[8,1,0,33554448,256,4294967295]]}]}"#,
        ).unwrap();
        let row = &rows[0];
        let palette = model::PALETTES[0];
        let runs: Vec<_> = row
            .runs
            .iter()
            .map(|run| text_run(run, &palette, palette.background))
            .collect();
        for font_size in [11., 14., 18., 36.] {
            let cell = font_size * model::MONO_ADVANCE_EM;
            let line = window_text.shape_line(
                row.text.clone().into(),
                px(font_size),
                &runs,
                Some(px(cell)),
            );
            for (byte, column) in [(0, 0.), (1, 1.), (4, 2.), (5, 3.), (8, 4.)] {
                let x = f32::from(line.x_for_index(byte));
                assert!(
                    (x - column * cell).abs() < 0.01,
                    "{font_size}: byte {byte} at {x}"
                );
                assert_eq!(line.index_for_x(px(column * cell + 0.1)), Some(byte));
            }
        }
        // The cell is the bundled font's real advance: forcing the grid moves no
        // ASCII glyph, so ordinary text looks exactly as it did unforced.
        let ascii = "Terminal 0123 ~|".repeat(5);
        let run = [TextRun {
            len: ascii.len(),
            font: font(crate::fonts::MONO_FAMILY),
            ..TextRun::default()
        }];
        let natural = window_text.shape_line(ascii.clone().into(), px(14.), &run, None);
        let forced = window_text.shape_line(
            ascii.into(),
            px(14.),
            &run,
            Some(px(14. * model::MONO_ADVANCE_EM)),
        );
        for byte in [1, 40, 79] {
            let drift = f32::from(natural.x_for_index(byte)) - f32::from(forced.x_for_index(byte));
            assert!(drift.abs() < 0.5, "byte {byte} drifted {drift}");
        }
    }

    #[test]
    fn linked_cells_resolve_taps_on_the_grid_and_nothing_else() {
        let text_system = Arc::new(TextSystem::new(Arc::new(
            gpui_wgpu::CosmicTextSystem::new_without_system_fonts("Roboto"),
        )));
        crate::fonts::install(&text_system).unwrap();
        let window_text = WindowTextSystem::new(text_system);
        let (rows, links) = model::parse_surface(
            r#"{"links":["https://example.com/界"],"rows":[{"t":"see ─docs here","r":[[0,4,0,0,0,4294967295],[4,7,0,0,8,0],[11,5,0,0,0,4294967295]]}]}"#,
        ).unwrap();
        let latte = model::PALETTES[3];
        let row = GridRow::styled(&rows[0], &links, &latte, latte.background);
        assert_eq!(
            row.links,
            vec![(4..11, "https://example.com/界".to_owned())]
        );
        let cell = 14. * model::MONO_ADVANCE_EM;
        let line = window_text.shape_line(row.text.clone(), px(14.), &row.runs, Some(px(cell)));
        // Columns 4 to 8 are "─docs"; the fallback glyph does not shift them.
        for column in [4., 5., 8.] {
            assert_eq!(
                link_at(&line, &row.links, px(column * cell + 1.)),
                Some("https://example.com/界")
            );
        }
        for column in [0., 3., 9., 12.] {
            assert_eq!(link_at(&line, &row.links, px(column * cell + 1.)), None);
        }
    }

    #[test]
    fn drawn_cells_hide_only_their_own_glyphs() {
        // A frame border, then text with a block inside one coloured run. Only
        // `│` and `█` lose their glyphs; each keeps its run's colour for its
        // shapes, and every byte and other glyph is painted as before.
        let (rows, _) = model::parse_surface(
            r#"{"rows":[{"t":"│ab█c","r":[[0,3,0,0,16384,4294967295],[3,6,16777217,0,0,4294967295]]}]}"#,
        )
        .unwrap();
        let palette = model::PALETTES[0];
        let border = text_run(&rows[0].runs[0], &palette, palette.background).color;
        let red = text_run(&rows[0].runs[1], &palette, palette.background).color;
        let row = GridRow::styled(&rows[0], &[], &palette, palette.background);
        let runs: Vec<_> = row.runs.iter().map(|run| (run.len, run.color)).collect();
        assert_eq!(
            runs,
            [
                (3, transparent_black()),
                (2, red),
                (3, transparent_black()),
                (1, red)
            ]
        );
        assert_eq!(
            row.drawn,
            [
                DrawnCell {
                    byte: 0,
                    ch: '│',
                    color: border
                },
                DrawnCell {
                    byte: 5,
                    ch: '█',
                    color: red
                },
            ]
        );
        let plain = GridRow::plain(&Arc::from("─x"), &palette);
        let lengths: Vec<_> = plain.runs.iter().map(|run| run.len).collect();
        assert_eq!(lengths, [3, 1]);
        assert_eq!(plain.drawn.len(), 1);
        // ASCII lookalikes keep their glyphs and their single run.
        let ascii = GridRow::plain(&Arc::from("| ascii -+"), &palette);
        assert_eq!(ascii.runs.len(), 1);
        assert!(ascii.drawn.is_empty());
    }

    #[test]
    fn drawn_cells_are_painted_in_the_columns_their_glyphs_occupy() {
        // After a wide glyph and its placeholder, and after a combining mark
        // that adds a character but no column, a drawn cell's column comes from
        // where its glyph was shaped, not from counting characters.
        let text_system = Arc::new(TextSystem::new(Arc::new(
            gpui_wgpu::CosmicTextSystem::new_without_system_fonts("Roboto"),
        )));
        crate::fonts::install(&text_system).unwrap();
        let window_text = WindowTextSystem::new(text_system);
        let (rows, _) = model::parse_surface(
            r#"{"rows":[{"t":"界 ─e\u0301│","r":[[0,13,0,0,0,4294967295]]}]}"#,
        )
        .unwrap();
        let palette = model::PALETTES[0];
        let row = GridRow::styled(&rows[0], &[], &palette, palette.background);
        for font_size in [11., 14., 18., 36.] {
            let cell = font_size * model::MONO_ADVANCE_EM;
            let line =
                window_text.shape_line(row.text.clone(), px(font_size), &row.runs, Some(px(cell)));
            let columns: Vec<_> = drawn_columns(&line, &row.drawn, cell)
                .map(|(drawn, column)| (drawn.ch, column))
                .collect();
            assert_eq!(columns, [('─', 2.), ('│', 4.)], "{font_size}");
        }
    }

    #[test]
    fn cells_keep_their_own_background_over_a_filled_letterbox() {
        // A painted cell, a default content cell and a default scrollbar cell
        // of the daemon's frame. The content cell keeps the palette's light
        // background over a dark fill; the frame cell shows whatever is behind.
        let (rows, _) = model::parse_surface(
            r#"{"rows":[{"t":"ab│","r":[[0,1,0,35528238,0,4294967295],[1,1,0,0,0,4294967295],[2,3,0,0,16384,4294967295]]}]}"#,
        )
        .unwrap();
        let latte = model::PALETTES[3];
        let dark = 0x1e1e2e;
        let backgrounds = |fill: u32| -> Vec<_> {
            GridRow::styled(&rows[0], &[], &latte, fill)
                .runs
                .iter()
                .map(|run| run.background_color)
                .collect()
        };
        assert_eq!(
            backgrounds(dark),
            vec![None, Some(gpui::rgb(latte.background).into()), None]
        );
        assert_eq!(
            backgrounds(latte.background),
            vec![Some(gpui::rgb(dark).into()), None, None]
        );
    }

    #[test]
    fn bundled_control_labels_fit_at_all_supported_sizes_in_320_dp() {
        // Exercise actual bundled glyph advances, not just an estimated
        // character width. No host/OEM font fallback may mask clipping.
        let text_system = Arc::new(TextSystem::new(Arc::new(
            gpui_wgpu::CosmicTextSystem::new_without_system_fonts("Roboto"),
        )));
        crate::fonts::install(&text_system).unwrap();
        let window_text = WindowTextSystem::new(text_system);
        for compact in [false, true] {
            for font_size in [11., 12., 13., 18., 24., 36.] {
                let metrics = DisplayMetrics::new(
                    model::DisplayPreferences::new(font_size, compact).unwrap(),
                    320.,
                );
                let available = 320. - 2. * metrics.padding;
                for label in ["Keyboard", "Follow", "Paused", "Back"] {
                    let width = if metrics.stacked_controls {
                        if label == "Keyboard" {
                            available
                        } else {
                            (available - metrics.gap) / 2.
                        }
                    } else {
                        (available - 2. * metrics.gap) / 3.
                    };
                    let line = window_text.shape_line(
                        label.into(),
                        px(font_size),
                        &[TextRun {
                            len: label.len(),
                            font: font(crate::fonts::SANS_FAMILY),
                            ..TextRun::default()
                        }],
                        None,
                    );
                    assert!(line.width() <= px(width - 6.));
                }
            }
        }
    }
}
