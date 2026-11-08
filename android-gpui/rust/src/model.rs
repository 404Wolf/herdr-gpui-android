//! Platform-independent boundary validation, latest-state mailbox, and input FIFO.
use crate::{Error, Result};
use serde::{Deserialize, Serialize};
use std::{collections::VecDeque, sync::Arc};

#[cfg(feature = "android-ui")]
use std::sync::{Mutex, MutexGuard, OnceLock};

pub const MAX_COMMANDS: usize = 64;
pub const MAX_COMMAND_JSON: usize = 65_536 * 6 + 128;
pub const MAX_TEXT_BYTES: usize = 65_536;
pub const MAX_SUBMIT_BYTES: usize = 4_096;
pub const MAX_SNAPSHOT_BYTES: usize = 524_288;
pub const MAX_SHORTCUT_JSON: usize = 2_097_152;
pub const MAX_LINES: usize = 4_000;
pub const MAX_LINE_CHARS: usize = 4_096;
/// Styled rows carry at most this many spans; extra spans merge into the last.
pub const MAX_RUNS_PER_LINE: usize = 256;
/// Cell attribute bits shared with the desktop painter.
pub const BOLD: u16 = 1;
pub const DIM: u16 = 1 << 1;
pub const ITALIC: u16 = 1 << 2;
pub const UNDERLINE: u16 = 1 << 3;
pub const REVERSED: u16 = 1 << 6;
pub const HIDDEN: u16 = 1 << 7;
pub const STRIKETHROUGH: u16 = 1 << 8;
/// Renderer-only bit: paint this cell with the palette accent (focused pane border).
pub const FOCUS_RING: u16 = 1 << 15;
/// Renderer-only bit: a cell of the daemon's frame - a border or scrollbar,
/// outside every pane's content. On the default background it shows the
/// surround, so a filled letterbox continues through it.
pub const FRAME: u16 = 1 << 14;

/// A terminal palette: the 16 ANSI colors plus the default pair and chrome.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct Palette {
    pub name: &'static str,
    pub ansi: [u32; 16],
    pub foreground: u32,
    pub background: u32,
    /// Header/footer surface and its border.
    pub chrome: u32,
    pub border: u32,
    pub muted: u32,
    pub accent: u32,
}

pub const PALETTES: [Palette; 4] = [
    Palette {
        name: "Default",
        ansi: [
            0x1c1f26, 0xcc6b6b, 0x8fc98a, 0xd8b06a, 0x7aa2d6, 0xb08ad6, 0x6fc0c6, 0xc9d1dc,
            0x566274, 0xe08a8a, 0xa6dc9f, 0xeed092, 0x94b8e8, 0xc9a6e8, 0x8ad6dc, 0xf2f5f9,
        ],
        foreground: 0xdce5ef,
        background: 0x10151d,
        chrome: 0x1e222a,
        border: 0x2b3747,
        muted: 0x9fb0c6,
        accent: 0x76d3b2,
    },
    Palette {
        name: "Nord",
        ansi: [
            0x3b4252, 0xbf616a, 0xa3be8c, 0xebcb8b, 0x81a1c1, 0xb48ead, 0x88c0d0, 0xe5e9f0,
            0x4c566a, 0xbf616a, 0xa3be8c, 0xebcb8b, 0x81a1c1, 0xb48ead, 0x8fbcbb, 0xeceff4,
        ],
        foreground: 0xd8dee9,
        background: 0x2e3440,
        chrome: 0x3b4252,
        border: 0x4c566a,
        muted: 0xaebacd,
        accent: 0x8fbcbb,
    },
    Palette {
        name: "Mocha",
        ansi: [
            0x45475a, 0xf38ba8, 0xa6e3a1, 0xf9e2af, 0x89b4fa, 0xcba6f7, 0x94e2d5, 0xbac2de,
            0x585b70, 0xf38ba8, 0xa6e3a1, 0xf9e2af, 0x89b4fa, 0xcba6f7, 0x94e2d5, 0xa6adc8,
        ],
        foreground: 0xcdd6f4,
        background: 0x1e1e2e,
        chrome: 0x181825,
        border: 0x313244,
        muted: 0xa6adc8,
        accent: 0xa6e3a1,
    },
    Palette {
        name: "Latte",
        ansi: [
            0x5c5f77, 0xd20f39, 0x40a02b, 0xdf8e1d, 0x1e66f5, 0x8839ef, 0x179299, 0xacb0be,
            0x6c6f85, 0xd20f39, 0x40a02b, 0xdf8e1d, 0x1e66f5, 0x8839ef, 0x179299, 0xbcc0cc,
        ],
        foreground: 0x4c4f69,
        background: 0xeff1f5,
        chrome: 0xe6e9ef,
        border: 0xccd0da,
        muted: 0x6c6f85,
        accent: 0x1e66f5,
    },
];

static PALETTE_INDEX: std::sync::atomic::AtomicU8 = std::sync::atomic::AtomicU8::new(0);

pub fn palette() -> Palette {
    PALETTES[PALETTE_INDEX.load(std::sync::atomic::Ordering::Relaxed) as usize % PALETTES.len()]
}

/// Selects a built-in palette. Returns whether the choice changed.
/// Applies a palette and repaints the current window.
#[cfg(feature = "android-ui")]
pub fn apply_palette(index: u8) -> bool {
    if !set_palette(index) {
        return false;
    }
    let mut mailbox = mailbox();
    mailbox.revision = mailbox.revision.wrapping_add(1);
    #[cfg(feature = "android-ui")]
    signal_change();
    true
}

pub fn set_palette(index: u8) -> bool {
    if usize::from(index) >= PALETTES.len() {
        return false;
    }
    PALETTE_INDEX.swap(index, std::sync::atomic::Ordering::Relaxed) != index
}

/// Wire value for the terminal's default color, which the palette supplies.
const DEFAULT_COLOR: u32 = 0;

/// Resolves a wire color (named 1..=16, indexed 0x01xxxxxx, RGB 0x02RRGGBB).
pub fn resolve_color(value: u32, default: u32, palette: &Palette) -> u32 {
    match value >> 24 {
        0 => match value & 0xff {
            1..=16 => palette.ansi[((value & 0xff) - 1) as usize],
            _ => default,
        },
        1 => {
            let index = (value & 0xff) as usize;
            match index {
                0..=15 => palette.ansi[index],
                16..=231 => {
                    let cube = index - 16;
                    let step = |value: usize| {
                        let level = (value % 6) as u32;
                        if level == 0 { 0 } else { 55 + level * 40 }
                    };
                    (step(cube / 36) << 16) | (step(cube / 6 % 6) << 8) | step(cube % 6)
                }
                _ => {
                    let level = 8 + (index as u32 - 232) * 10;
                    (level << 16) | (level << 8) | level
                }
            }
        }
        2 => value & 0xff_ffff,
        _ => default,
    }
}

/// Final foreground and background for one styled run under a palette.
pub fn styled_colors(run: &StyledRun, palette: &Palette) -> (u32, u32) {
    let mut fg = if run.modifier & FOCUS_RING != 0 {
        palette.accent
    } else {
        resolve_color(run.fg, palette.foreground, palette)
    };
    let mut bg = resolve_color(run.bg, palette.background, palette);
    if run.modifier & REVERSED != 0 {
        std::mem::swap(&mut fg, &mut bg);
    }
    if run.modifier & DIM != 0 {
        fg = ((fg & 0xfe_fefe) >> 1) + ((bg & 0xfe_fefe) >> 1);
    }
    if run.modifier & HIDDEN != 0 {
        fg = bg;
    }
    (fg, bg)
}

/// WCAG AA contrast for normal-size text.
#[cfg(any(feature = "android-ui", test))]
pub(crate) const TEXT_CONTRAST: f32 = 4.5;

/// WCAG 2 relative luminance of an opaque 0xRRGGBB color.
#[cfg(any(feature = "android-ui", test))]
fn relative_luminance(color: u32) -> f32 {
    let channel = |shift: u32| {
        let value = ((color >> shift) & 0xff) as f32 / 255.;
        if value <= 0.039_28 {
            value / 12.92
        } else {
            ((value + 0.055) / 1.055).powf(2.4)
        }
    };
    0.2126 * channel(16) + 0.7152 * channel(8) + 0.0722 * channel(0)
}

/// WCAG 2 contrast ratio between two opaque 0xRRGGBB colors.
#[cfg(any(feature = "android-ui", test))]
pub(crate) fn contrast(a: u32, b: u32) -> f32 {
    let (a, b) = (relative_luminance(a), relative_luminance(b));
    (a.max(b) + 0.05) / (a.min(b) + 0.05)
}

/// `amount` of `over` mixed into `base`, per channel.
#[cfg(any(feature = "android-ui", test))]
fn mix(base: u32, over: u32, amount: f32) -> u32 {
    let channel = |shift: u32| {
        let from = ((base >> shift) & 0xff) as f32;
        let to = ((over >> shift) & 0xff) as f32;
        ((from + (to - from) * amount).round().clamp(0., 255.) as u32) << shift
    };
    channel(16) | channel(8) | channel(0)
}

/// The palette color itself when it is legible on `background`, else the least
/// blend toward `fallback` that reaches `minimum` contrast.
#[cfg(any(feature = "android-ui", test))]
pub(crate) fn readable(color: u32, background: u32, fallback: u32, minimum: f32) -> u32 {
    (0..=20)
        .map(|step| mix(color, fallback, step as f32 / 20.))
        .find(|candidate| contrast(*candidate, background) >= minimum)
        .unwrap_or(fallback)
}

/// Colors for everything outside the terminal grid: the GPUI header and the
/// Android system bars, toolbar, rail and sheets. One palette drives them all,
/// so a light theme gets light chrome, and every text role meets WCAG AA on
/// the surface it is drawn on.
#[cfg(any(feature = "android-ui", test))]
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) struct ChromeRoles {
    pub background: u32,
    pub surface: u32,
    pub on_surface: u32,
    pub on_surface_variant: u32,
    pub outline: u32,
    pub primary: u32,
    /// Tonal container for keycaps and secondary buttons.
    pub control: u32,
    /// Container for the selected rail row or toggled control.
    pub selected: u32,
    pub danger: u32,
    pub warning: u32,
    /// Status dots: paired with a word, so they carry no text contrast duty.
    pub success: u32,
    pub info: u32,
    pub working: u32,
    /// Black text reads better than white on this surface.
    pub light: bool,
}

#[cfg(any(feature = "android-ui", test))]
impl ChromeRoles {
    pub(crate) fn of(palette: &Palette) -> Self {
        let surface = palette.chrome;
        let text = palette.foreground;
        // Above this luminance black contrasts more than white does.
        let light = relative_luminance(surface) > 0.179;
        let strongest = if light { 0 } else { 0xff_ffff };
        let on_surface = readable(text, surface, strongest, TEXT_CONTRAST);
        let container = |over: u32, amount: f32| {
            let fill = mix(surface, over, amount);
            // Keep the label readable on the container, not just on the bar.
            if contrast(on_surface, fill) >= TEXT_CONTRAST {
                fill
            } else {
                mix(surface, over, amount / 2.)
            }
        };
        Self {
            background: palette.background,
            surface,
            on_surface,
            on_surface_variant: readable(palette.muted, surface, on_surface, TEXT_CONTRAST),
            outline: palette.border,
            primary: readable(palette.accent, surface, on_surface, TEXT_CONTRAST),
            control: container(text, 0.12),
            selected: container(palette.accent, 0.22),
            danger: readable(palette.ansi[1], surface, on_surface, TEXT_CONTRAST),
            warning: readable(palette.ansi[3], surface, on_surface, TEXT_CONTRAST),
            success: palette.ansi[2],
            info: palette.ansi[4],
            working: palette.ansi[3],
            light,
        }
    }

    /// ARGB ints in the order `GpuiChrome` reads them; the final slot is 1 for
    /// a light surface.
    #[cfg(any(target_os = "android", test))]
    pub(crate) fn to_argb(self) -> [i32; 14] {
        let argb = |color: u32| (0xff00_0000 | color) as i32;
        [
            argb(self.background),
            argb(self.surface),
            argb(self.on_surface),
            argb(self.on_surface_variant),
            argb(self.outline),
            argb(self.primary),
            argb(self.control),
            argb(self.selected),
            argb(self.danger),
            argb(self.warning),
            argb(self.success),
            argb(self.info),
            argb(self.working),
            i32::from(self.light),
        ]
    }
}

/// One contiguous span of cells that share fg, bg and attribute bits.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct StyledRun {
    /// Byte offset into the row text.
    pub start: usize,
    /// Length in bytes.
    pub len: usize,
    /// Wire-encoded color; the renderer resolves it against the palette.
    pub fg: u32,
    pub bg: u32,
    pub modifier: u16,
    /// Index into `Snapshot::links` when this run is clickable.
    pub link: Option<u16>,
}

#[derive(Clone, Debug, PartialEq, Eq, Default)]
pub struct StyledRow {
    pub text: String,
    pub runs: Vec<StyledRun>,
}

/// Local display settings only: no terminal generation or remote resize is
/// implied. Java applies the user's 11–18 dp choice and bounded system font
/// scale before crossing JNI, so the effective size can reach 36 logical px.
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct DisplayPreferences {
    pub(crate) font_size: f32,
    pub(crate) compact: bool,
    /// Android's system font scale. Auto type multiplies the fit by it so an
    /// accessibility setting still enlarges a terminal that sizes itself.
    pub(crate) scale: f32,
}

impl Default for DisplayPreferences {
    fn default() -> Self {
        Self {
            // Zero means "auto": the largest size at which the pane's widest
            // line still fits the surface width.
            font_size: 0.,
            compact: false,
            scale: 1.,
        }
    }
}

impl DisplayPreferences {
    pub fn new(font_size: f32, compact: bool) -> Result<Self> {
        Self::scaled(font_size, compact, 1.)
    }

    pub fn scaled(font_size: f32, compact: bool, scale: f32) -> Result<Self> {
        // 0 is the auto sentinel; everything else is a deliberate user choice.
        if !font_size.is_finite() || (font_size != 0. && !(11.0..=36.0).contains(&font_size)) {
            return Err(Error::InvalidDisplayPreferences);
        }
        if !scale.is_finite() || !(1.0..=3.0).contains(&scale) {
            return Err(Error::InvalidDisplayPreferences);
        }
        Ok(Self {
            font_size,
            compact,
            scale,
        })
    }
}

/// Keep terminal painting, IME placement and responsive chrome on the same
/// logical-pixel geometry. Device physical resolution never chooses a profile.
#[cfg(any(feature = "android-ui", test))]
#[derive(Clone, Copy, Debug)]
pub(crate) struct DisplayMetrics {
    pub font_size: f32,
    pub row_height: f32,
    pub header_height: f32,
    /// Kept for the layout tests that pinned the removed footer row.
    #[allow(dead_code)]
    pub touch_height: f32,
    pub padding: f32,
    pub gap: f32,
    #[allow(dead_code)]
    pub stacked_controls: bool,
}

/// Advance of every glyph in the bundled Noto Mono: 1229 of 2048 units per em.
/// Rows are painted on cells of exactly this width, so glyphs that fall back to
/// a proportional system font can no longer widen a row.
#[cfg(any(feature = "android-ui", test))]
pub(crate) const MONO_ADVANCE_EM: f32 = 1229. / 2048.;

/// The default terminal type size in logical pixels: what the client grid
/// assumes and what chrome around an empty pane is measured with.
#[cfg(any(feature = "android-ui", test))]
const DEFAULT_FONT: f32 = 13.;

/// A classic terminal width, which a raw shell keeps on narrow panels.
#[cfg(any(feature = "android-ui", test))]
const SHELL_COLUMNS: f32 = 80.;

/// Type size for a raw shell, whose grid follows the surface rather than a
/// daemon's pane. A chosen size wins. Auto uses the default size, so a large
/// panel shows more of the shell instead of larger text; where 80 columns do
/// not fit at that size it shrinks toward them, down to the readable floor.
#[cfg(any(feature = "android-ui", test))]
pub(crate) fn shell_font_size(preferences: DisplayPreferences, viewport_width: f32) -> f32 {
    if preferences.font_size > 0. {
        return preferences.font_size;
    }
    let classic = viewport_width.max(0.) / (SHELL_COLUMNS * MONO_ADVANCE_EM);
    (classic.min(DEFAULT_FONT) * preferences.scale).clamp(11., 36.)
}

/// Most a pane's row pitch may be spread. Past this, rows read as islands and
/// the remaining height is better left as even margins.
#[cfg(any(feature = "android-ui", test))]
const MAX_SPREAD: f32 = 2.;

/// Row pitch for a pane whose row count Herdr fixes: a pane shorter than the
/// surface spreads its rows over it, never closer than the natural pitch and
/// never past twice it. The type size and columns are not changed.
#[cfg(any(feature = "android-ui", test))]
pub(crate) fn spread_row_height(natural: f32, rows: usize, surface_height: f32) -> f32 {
    if rows == 0 || surface_height.is_nan() || surface_height <= 0. {
        return natural;
    }
    (surface_height / rows as f32).clamp(natural, natural * MAX_SPREAD)
}

#[cfg(any(feature = "android-ui", test))]
impl DisplayMetrics {
    #[cfg(test)]
    pub fn new(preferences: DisplayPreferences, viewport_width: f32) -> Self {
        Self::fit(preferences, viewport_width, 0)
    }

    /// `widest_line_chars` is the pane's own wrapping width in cells. Auto type
    /// takes the largest size whose line fits the surface, so a narrow pane on a
    /// wide panel grows instead of leaving the right half empty.
    pub fn fit(
        preferences: DisplayPreferences,
        viewport_width: f32,
        widest_line_chars: usize,
    ) -> Self {
        let padding = if preferences.compact { 4. } else { 8. };
        let inset = padding + ROW_GUTTER;
        let font_size = if preferences.font_size > 0. {
            preferences.font_size
        } else if widest_line_chars > 0 && viewport_width > 0. {
            // The widest row fills the surface between both gutters. An 80-cell
            // pane on the 443 dp cover would need about 8.7 px, which is
            // unreadable, so the floor stays at 11 and panning covers the tail.
            ((viewport_width - 2. * inset).max(0.) / (widest_line_chars as f32 * MONO_ADVANCE_EM)
                * preferences.scale)
                .clamp(11., 36.)
        } else {
            DEFAULT_FONT
        };
        let gap = padding;
        // The longest control label is Keyboard. At larger accessibility sizes
        // it gets its own row; Follow and Back share a second row. Labels never
        // force the whole surface wider than a 320 dp window.
        let control_width = 8. * font_size * 0.65 + 6.;
        Self {
            font_size,
            row_height: (font_size * if preferences.compact { 1.4 } else { 1.5 }).ceil(),
            header_height: (font_size * 1.5 + 2. * padding).ceil(),
            touch_height: (font_size * 1.5 + 8.).ceil().max(48.),
            padding,
            gap,
            stacked_controls: 3. * control_width + 2. * (gap + padding) > viewport_width,
        }
    }

    /// Width of one terminal cell in logical pixels.
    pub fn cell_width(self) -> f32 {
        self.font_size * MONO_ADVANCE_EM
    }

    /// Horizontal inset on each side of a row, shared with the header.
    pub fn row_inset(self) -> f32 {
        self.padding + ROW_GUTTER
    }

    pub fn row_width(self, widest_line_chars: usize) -> f32 {
        // Exactly the grid plus both gutters, so panning stops at the last cell.
        (widest_line_chars.min(MAX_LINE_CHARS) as f32 * self.cell_width() + 2. * self.row_inset())
            .max(200.)
    }
}

/// Extra space between a row's edge and its first cell, beyond the padding.
#[cfg(any(feature = "android-ui", test))]
const ROW_GUTTER: f32 = 4.;

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(tag = "type", rename_all = "lowercase", deny_unknown_fields)]
pub enum Command {
    Text {
        text: String,
    },
    Keys {
        keys: Vec<String>,
    },
    Close,
    /// One FIFO slot; the Java pump acknowledges text before sending Enter.
    Submit {
        text: String,
    },
}

impl Command {
    pub fn validate(&self) -> Result<()> {
        match self {
            Self::Text { text } if text.is_empty() || text.len() > MAX_TEXT_BYTES => {
                Err(Error::InvalidText)
            }
            Self::Submit { text }
                if text.is_empty()
                    || text.len() > MAX_SUBMIT_BYTES
                    || text
                        .chars()
                        .any(|ch| ch.is_control() || matches!(ch, '\u{2028}' | '\u{2029}')) =>
            {
                Err(Error::InvalidCommand)
            }
            Self::Keys { keys }
                if keys.is_empty() || keys.len() > 16 || !keys.iter().all(|key| valid_key(key)) =>
            {
                Err(Error::InvalidKeys)
            }
            _ => Ok(()),
        }
    }
}

/// Native Android extra keys/macros join physical and IME input synchronously
/// on Java's UI thread. Reject unknown fields and non-input commands at JNI.
pub fn parse_command(json: &str) -> Result<Command> {
    if json.len() > MAX_COMMAND_JSON {
        return Err(Error::InvalidCommand);
    }
    let command: Command = serde_json::from_str(json)?;
    if matches!(command, Command::Close) {
        return Err(Error::InvalidCommand);
    }
    command.validate()?;
    Ok(command)
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Shortcut {
    pub label: String,
    pub command: Command,
}

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
struct WireShortcut {
    label: String,
    keys: Option<Vec<String>>,
    text: Option<String>,
}

pub fn parse_shortcuts(json: &str) -> Result<Vec<Shortcut>> {
    if json.len() > MAX_SHORTCUT_JSON {
        return Err(Error::InvalidShortcuts);
    }
    let wire: Vec<WireShortcut> = serde_json::from_str(json)?;
    if wire.len() > 24 {
        return Err(Error::InvalidShortcuts);
    }
    let mut result = Vec::with_capacity(wire.len());
    for item in wire {
        if item.label.is_empty()
            || item.label.chars().count() > 40
            || item.label.chars().any(char::is_control)
            || result.iter().any(|s: &Shortcut| s.label == item.label)
        {
            return Err(Error::InvalidShortcuts);
        }
        let command = match (item.keys, item.text) {
            (Some(keys), None) => Command::Keys { keys },
            (None, Some(text)) => Command::Text { text },
            _ => return Err(Error::InvalidShortcuts),
        };
        command.validate()?;
        result.push(Shortcut {
            label: item.label,
            command,
        });
    }
    Ok(result)
}

pub fn valid_key(key: &str) -> bool {
    matches!(
        key,
        "enter"
            | "esc"
            | "tab"
            | "space"
            | "backspace"
            | "up"
            | "down"
            | "left"
            | "right"
            | "shift+tab"
            | "y"
            | "n"
            | "1"
            | "2"
            | "3"
            | "4"
            | "5"
            | "6"
            | "7"
            | "8"
            | "9"
            | "f1"
            | "f2"
            | "f3"
            | "f4"
            | "f5"
            | "f6"
            | "f7"
            | "f8"
            | "f9"
            | "f10"
            | "f11"
            | "f12"
    ) || key
        .strip_prefix("ctrl+")
        .or_else(|| key.strip_prefix("alt+"))
        .is_some_and(|letter| letter.len() == 1 && letter.as_bytes()[0].is_ascii_lowercase())
}

/// Only the exact companion grammar can cross the input boundary. Printable
/// characters use the OS text commit path, which preserves layout and IME.
pub fn physical_key(
    key: &str,
    control: bool,
    alt: bool,
    shift: bool,
    platform: bool,
) -> Option<Command> {
    if platform || (control && alt) || ((control || alt) && shift) {
        return None;
    }
    let key = match key {
        "escape" => "esc",
        "return" => "enter",
        other => other,
    };
    let key = if control || alt {
        format!("{}+{key}", if control { "ctrl" } else { "alt" })
    } else if shift {
        if key != "tab" {
            return None;
        }
        "shift+tab".into()
    } else {
        // Digits and y/n are printable, even though they are accepted by the API.
        if key.chars().count() == 1 {
            return None;
        }
        key.into()
    };
    valid_key(&key).then_some(Command::Keys { keys: vec![key] })
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Snapshot {
    pub title: String,
    pub status: String,
    pub lines: Vec<Arc<str>>,
    /// Present when the daemon surface styles tile the plain lines exactly.
    pub styled: Option<Arc<Vec<StyledRow>>>,
    /// Bounded, browser-openable targets referenced by styled runs.
    pub links: Arc<Vec<String>>,
    pub shortcuts: Vec<Shortcut>,
    pub truncated: bool,
    pub widest_line_chars: usize,
    /// Background cells on the pane's outer ring, by wire colour, from which
    /// the renderer decides what fills the area around the pane.
    pub edge_colors: Vec<(u32, usize)>,
}

impl Default for Snapshot {
    fn default() -> Self {
        Self {
            title: "Herdr · GPUI".into(),
            status: "Connecting…".into(),
            lines: Vec::new(),
            styled: None,
            links: Arc::default(),
            shortcuts: Vec::new(),
            truncated: false,
            widest_line_chars: 1,
            edge_colors: Vec::new(),
        }
    }
}

impl Snapshot {
    pub fn new(title: &str, text: &str, status: &str, shortcuts: &str) -> Result<Self> {
        let title = plain_label(title, 256);
        let status = plain_label(status, 1024);
        let shortcuts = parse_shortcuts(shortcuts)?;
        let (plain, mut truncated) = display_text(text);
        let mut lines: VecDeque<Arc<str>> = VecDeque::new();
        for line in plain.split('\n') {
            let mut chars = line.chars();
            let line: String = chars.by_ref().take(MAX_LINE_CHARS).collect();
            truncated |= chars.next().is_some();
            if lines.len() == MAX_LINES {
                lines.pop_front();
                truncated = true;
            }
            lines.push_back(Arc::from(line));
        }
        let widest_line_chars = lines
            .iter()
            .map(|line| line.chars().count())
            .max()
            .unwrap_or(1)
            .max(1);
        Ok(Self {
            title,
            status,
            lines: lines.into(),
            styled: None,
            links: Arc::default(),
            shortcuts,
            truncated,
            widest_line_chars,
            edge_colors: Vec::new(),
        })
    }

    /// Parses the optional styled surface. A surface whose rows do not tile the
    /// plain text exactly is dropped rather than misaligned; the renderer then
    /// falls back to plain lines.
    pub fn with_surface(
        title: &str,
        text: &str,
        status: &str,
        shortcuts: &str,
        surface: Option<&str>,
    ) -> Result<Self> {
        let mut snapshot = Self::new(title, text, status, shortcuts)?;
        if let Some(raw) = surface.filter(|raw| !raw.is_empty()) {
            let (rows, links) = parse_surface(raw)?;
            if rows.len() == snapshot.lines.len() {
                // Styled rows keep a placeholder for each wide glyph's second
                // cell, so their length is the pane's width in cells. Plain
                // lines omit it to keep Copy faithful.
                snapshot.widest_line_chars = rows
                    .iter()
                    .map(|row| row.text.chars().count())
                    .max()
                    .unwrap_or(1)
                    .max(snapshot.widest_line_chars);
                snapshot.edge_colors = edge_colors(&rows, snapshot.widest_line_chars);
                snapshot.styled = Some(Arc::new(rows));
                snapshot.links = Arc::new(links);
            } else {
                snapshot.truncated = true;
            }
        }
        Ok(snapshot)
    }
}

/// Background cells on the pane's outer ring of cells, by wire colour. Cells
/// past a row's end show the default background. Reversed cells show their
/// foreground there and frame cells belong to the daemon, not the program, so
/// neither votes.
fn edge_colors(rows: &[StyledRow], width: usize) -> Vec<(u32, usize)> {
    let mut tally: Vec<(u32, usize)> = Vec::new();
    let Some(last) = rows.len().checked_sub(1) else {
        return tally;
    };
    let mut add = |background: u32, cells: usize| {
        if cells == 0 {
            return;
        }
        match tally.iter_mut().find(|(color, _)| *color == background) {
            Some((_, count)) => *count += cells,
            None => tally.push((background, cells)),
        }
    };
    let run_cells = |row: &StyledRow, run: &StyledRun| {
        row.text
            .get(run.start..run.start + run.len)
            .map_or(0, |text| text.chars().count())
    };
    for (index, row) in rows.iter().enumerate() {
        if index == 0 || index == last {
            let mut covered = 0;
            for run in &row.runs {
                let cells = run_cells(row, run);
                covered += cells;
                if run.modifier & (REVERSED | FRAME) == 0 {
                    add(run.bg, cells);
                }
            }
            add(DEFAULT_COLOR, width.saturating_sub(covered));
            continue;
        }
        let cells = row.text.chars().count();
        let first = row.runs.first().filter(|run| run.start == 0);
        let end = row
            .runs
            .last()
            .filter(|run| run.start + run.len == row.text.len() && cells >= width);
        for edge in [first, end] {
            match edge {
                Some(run) if run.modifier & (REVERSED | FRAME) != 0 => {}
                Some(run) => add(run.bg, 1),
                None => add(DEFAULT_COLOR, 1),
            }
        }
    }
    tally
}

/// What fills the area around the pane: `Some` colour when most of its edge is
/// painted visibly unlike the palette background - a program drawing its own
/// background - so the pane is not a dark card in a light frame, or the
/// reverse. The commonest such colour wins, as tool panes mix close shades.
/// It takes three quarters of the edge to start filling and half to keep
/// going, so content scrolling past the edge cannot make the surround flicker.
pub fn letterbox(edge: &[(u32, usize)], palette: &Palette, filled: bool) -> Option<u32> {
    let total: usize = edge.iter().map(|&(_, cells)| cells).sum();
    let mut painted: Vec<(u32, usize)> = Vec::new();
    for &(wire, cells) in edge {
        let color = resolve_color(wire, palette.background, palette);
        if !visibly_different(color, palette.background) {
            continue;
        }
        match painted.iter_mut().find(|(seen, _)| *seen == color) {
            Some((_, count)) => *count += cells,
            None => painted.push((color, cells)),
        }
    }
    let share: usize = painted.iter().map(|&(_, cells)| cells).sum();
    let (part, whole) = if filled { (1, 2) } else { (3, 4) };
    if total == 0 || share * whole < total * part {
        return None;
    }
    painted
        .into_iter()
        .max_by_key(|&(_, cells)| cells)
        .map(|(color, _)| color)
}

/// Any channel more than 32 levels apart: near shades read as one background.
fn visibly_different(a: u32, b: u32) -> bool {
    [16, 8, 0]
        .into_iter()
        .any(|shift| ((a >> shift) & 0xff).abs_diff((b >> shift) & 0xff) > 32)
}

/// Shared row format for native projection, history and the renderer boundary.
#[derive(Serialize, Deserialize)]
pub(crate) struct WireSurfaceRow {
    #[serde(rename = "t")]
    pub text: String,
    #[serde(rename = "r")]
    pub runs: Vec<[u32; 6]>,
}

#[derive(Deserialize)]
pub(crate) struct WireSurface {
    // Preserve the optional link table's decoding policy without building a
    // generic JSON tree for every row and run in the much larger surface.
    #[serde(default)]
    pub links: serde_json::Value,
    pub rows: Vec<WireSurfaceRow>,
}

/// Parses `{"rows":[{"t":text,"r":[[start,len,fg,bg,modifier,link],…]},…]}`. Runs must
/// tile every row exactly, so the renderer can index bytes without guessing.
pub fn parse_surface(raw: &str) -> Result<(Vec<StyledRow>, Vec<String>)> {
    if raw.len() > MAX_SNAPSHOT_BYTES {
        return Err(Error::InvalidText);
    }
    let value: WireSurface = serde_json::from_str(raw).map_err(|_| Error::InvalidText)?;
    let mut links = Vec::new();
    if let Some(values) = value.links.as_array() {
        if values.len() > 256 {
            return Err(Error::InvalidText);
        }
        for value in values {
            let url = value.as_str().ok_or(Error::InvalidText)?;
            if url.len() > 2048
                || !(url.starts_with("https://") || url.starts_with("http://"))
                || url.chars().any(char::is_control)
            {
                return Err(Error::InvalidText);
            }
            links.push(url.to_owned());
        }
    }
    let rows = value.rows;
    if rows.len() > MAX_LINES {
        return Err(Error::InvalidText);
    }
    let mut styled = Vec::with_capacity(rows.len());
    let mut bytes = 0usize;
    for row in rows {
        let text = row.text;
        if text.chars().count() > MAX_LINE_CHARS {
            return Err(Error::InvalidText);
        }
        bytes += text.len();
        if bytes > MAX_SNAPSHOT_BYTES {
            return Err(Error::InvalidText);
        }
        let spans = row.runs;
        if spans.len() > MAX_RUNS_PER_LINE {
            return Err(Error::InvalidText);
        }
        let mut runs = Vec::with_capacity(spans.len());
        let mut offset = 0usize;
        for [start, len, fg, bg, modifier, link] in spans {
            let start = start as usize;
            let len = len as usize;
            if start != offset || len == 0 {
                return Err(Error::InvalidText);
            }
            let end = start.checked_add(len).ok_or(Error::InvalidText)?;
            if end > text.len() || !text.is_char_boundary(start) || !text.is_char_boundary(end) {
                return Err(Error::InvalidText);
            }
            if modifier > u16::MAX.into() {
                return Err(Error::InvalidText);
            }
            let link = match link {
                u32::MAX => None,
                index => Some(u16::try_from(index).map_err(|_| Error::InvalidText)?),
            };
            if link.is_some_and(|index| usize::from(index) >= links.len()) {
                return Err(Error::InvalidText);
            }
            runs.push(StyledRun {
                start,
                len,
                fg,
                bg,
                modifier: modifier as u16,
                link,
            });
            offset = end;
        }
        if offset != text.len() {
            return Err(Error::InvalidText);
        }
        styled.push(StyledRow { text, runs });
    }
    Ok((styled, links))
}

fn plain_label(value: &str, max: usize) -> String {
    value
        .chars()
        .filter(|c| !c.is_control())
        .take(max)
        .collect()
}

/// Strip all escape/control sequences rather than interpreting terminal output.
/// OSC clipboard/links, DCS graphics and device queries have no side effects.
fn display_text(text: &str) -> (String, bool) {
    enum Escape {
        None,
        Start,
        Csi,
        String,
        StringTerminator,
    }
    let mut escape = Escape::None;
    let mut result = String::new();
    let mut truncated = false;
    for ch in text.chars() {
        match escape {
            Escape::Start => {
                escape = match ch {
                    '[' => Escape::Csi,
                    ']' | 'P' | '^' | '_' => Escape::String,
                    _ => Escape::None,
                };
                continue;
            }
            Escape::Csi => {
                if ('\u{40}'..='\u{7e}').contains(&ch) {
                    escape = Escape::None;
                }
                continue;
            }
            Escape::String => {
                if ch == '\u{7}' || ch == '\u{9c}' {
                    escape = Escape::None;
                } else if ch == '\u{1b}' {
                    escape = Escape::StringTerminator;
                }
                continue;
            }
            Escape::StringTerminator => {
                escape = if ch == '\\' {
                    Escape::None
                } else {
                    Escape::String
                };
                continue;
            }
            Escape::None => {}
        }
        match ch {
            '\u{1b}' => {
                escape = Escape::Start;
                continue;
            }
            '\u{9b}' => {
                escape = Escape::Csi;
                continue;
            }
            '\u{9d}' | '\u{90}' | '\u{98}' | '\u{9e}' | '\u{9f}' => {
                escape = Escape::String;
                continue;
            }
            '\r' => continue,
            '\n' | '\t' => {}
            c if c.is_control() => continue,
            _ => {}
        }
        if result.len() + ch.len_utf8() > MAX_SNAPSHOT_BYTES {
            truncated = true;
            break;
        }
        result.push(ch);
    }
    (result, truncated)
}

pub struct Mailbox {
    generation: i64,
    active: bool,
    revision: u64,
    snapshot: Arc<Snapshot>,
    commands: VecDeque<Command>,
    ime_session: i64,
    preedit: Arc<str>,
    display_preferences: DisplayPreferences,
    pan: f32,
    /// Furthest useful pan: the painted row width beyond the surface.
    pan_limit: f32,
    pub follow: bool,
    /// Raw SSH shell drawn instead of a Herdr pane, or 0.
    shell: u64,
}

/// Horizontal pan requested by a wheel or drag delta, if the gesture is
/// horizontal. Vertical deltas stay with the list so scrolling is untouched.
pub fn pan_from_wheel(dx: f32, dy: f32) -> Option<f32> {
    if !dx.is_finite() || !dy.is_finite() {
        return None;
    }
    let horizontal = dx.abs() > 2. && dx.abs() > dy.abs();
    horizontal.then(|| -dx)
}

/// Enough for the widest supported line without letting a stray gesture hide
/// the whole pane.
pub const MAX_PAN: f32 = 2400.;

#[derive(Clone)]
pub struct ViewState {
    pub generation: i64,
    pub active: bool,
    pub revision: u64,
    pub snapshot: Arc<Snapshot>,
    pub preedit: Arc<str>,
    pub display_preferences: DisplayPreferences,
    /// Whether new output should pull the viewport to the newest line.
    pub follow: bool,
    /// Client-local horizontal offset in logical pixels; the daemon owns the
    /// wrap width, so panning is how a wide pane stays readable.
    pub pan: f32,
    /// Handle of the raw SSH shell this generation draws, or 0 for Herdr's pane.
    pub shell: u64,
}

impl Default for Mailbox {
    fn default() -> Self {
        Self {
            generation: 0,
            active: false,
            revision: 0,
            snapshot: Arc::default(),
            commands: VecDeque::new(),
            ime_session: 0,
            preedit: Arc::from(""),
            display_preferences: DisplayPreferences::default(),
            pan: 0.,
            pan_limit: MAX_PAN,
            // A fresh session tails its pane until the user scrolls back.
            follow: true,
            shell: 0,
        }
    }
}

impl Mailbox {
    pub fn set_active(&mut self, generation: i64, active: bool) -> bool {
        if generation <= 0 || generation < self.generation {
            return false;
        }
        if generation == self.generation && active == self.active {
            return false;
        }
        if generation > self.generation {
            self.generation = generation;
            self.snapshot = Arc::default();
            self.commands.clear();
            self.ime_session = 0;
            self.preedit = Arc::from("");
            self.pan = 0.;
            self.shell = 0;
        }
        if !active {
            self.commands.clear();
            self.ime_session = 0;
            self.preedit = Arc::from("");
        }
        self.active = active;
        self.revision = self.revision.wrapping_add(1);
        #[cfg(feature = "android-ui")]
        signal_change();
        true
    }

    pub fn set_display_preferences(&mut self, font_size: f32, compact: bool) -> Result<bool> {
        let scale = self.display_preferences.scale;
        self.set_display_preferences_scaled(font_size, compact, scale)
    }

    pub fn set_display_preferences_scaled(
        &mut self,
        font_size: f32,
        compact: bool,
        scale: f32,
    ) -> Result<bool> {
        let preferences = DisplayPreferences::scaled(font_size, compact, scale)?;
        if self.display_preferences == preferences {
            return Ok(false);
        }
        self.display_preferences = preferences;
        // Insets reapply unchanged preferences. Only new typography resets the
        // reading position, atomically with its single render notification.
        self.pan = 0.;
        self.revision = self.revision.wrapping_add(1);
        #[cfg(feature = "android-ui")]
        signal_change();
        Ok(true)
    }

    /// The Android side owns whether the pane is tailing or reading scrollback.
    pub fn set_follow(&mut self, generation: i64, follow: bool) -> bool {
        if generation <= 0 || generation < self.generation || follow == self.follow {
            return false;
        }
        self.follow = follow;
        self.revision = self.revision.wrapping_add(1);
        #[cfg(feature = "android-ui")]
        signal_change();
        true
    }

    /// Draws `shell` (0 for none) for the current generation only.
    pub fn set_shell(&mut self, generation: i64, shell: u64) -> bool {
        if generation <= 0 || generation != self.generation || shell == self.shell {
            return false;
        }
        self.shell = shell;
        self.revision = self.revision.wrapping_add(1);
        #[cfg(feature = "android-ui")]
        signal_change();
        true
    }

    pub fn set_snapshot(&mut self, generation: i64, snapshot: Snapshot) -> bool {
        if generation <= 0 || generation != self.generation {
            return false;
        }
        if *self.snapshot != snapshot {
            self.snapshot = Arc::new(snapshot);
            self.revision = self.revision.wrapping_add(1);
            #[cfg(feature = "android-ui")]
            signal_change();
        }
        true
    }

    /// Updates pane chrome without replacing or copying its terminal body.
    /// Stale generations are ignored before parsing caller-provided metadata.
    pub fn set_snapshot_chrome(
        &mut self,
        generation: i64,
        title: &str,
        status: &str,
        shortcuts: &str,
    ) -> Result<bool> {
        if generation <= 0 || generation != self.generation {
            return Ok(false);
        }
        let title = plain_label(title, 256);
        let status = plain_label(status, 1024);
        let shortcuts = parse_shortcuts(shortcuts)?;
        if self.snapshot.title == title
            && self.snapshot.status == status
            && self.snapshot.shortcuts == shortcuts
        {
            return Ok(false);
        }
        let mut snapshot = (*self.snapshot).clone();
        snapshot.title = title;
        snapshot.status = status;
        snapshot.shortcuts = shortcuts;
        self.snapshot = Arc::new(snapshot);
        self.revision = self.revision.wrapping_add(1);
        #[cfg(feature = "android-ui")]
        signal_change();
        Ok(true)
    }

    /// Horizontal pan in logical pixels, bounded. Returns whether it moved, so
    /// callers only repaint on a real change.
    pub fn pan(&mut self, delta: f32) -> bool {
        if !delta.is_finite() {
            return false;
        }
        let next = (self.pan + delta).clamp(0., self.pan_limit);
        if (next - self.pan).abs() < 0.5 {
            return false;
        }
        self.pan = next;
        self.revision = self.revision.wrapping_add(1);
        #[cfg(feature = "android-ui")]
        signal_change();
        true
    }

    /// Records how far the painted rows extend beyond the surface, so a pan
    /// stops at the last cell and the next opposite step moves immediately.
    /// The renderer already draws the clamped offset, so this never wakes it.
    pub fn set_pan_limit(&mut self, limit: f32) {
        if !limit.is_finite() {
            return;
        }
        self.pan_limit = limit.clamp(0., MAX_PAN);
        self.pan = self.pan.min(self.pan_limit);
    }

    /// Drops the horizontal offset, e.g. when the pane or the type size changes,
    /// so a new pane never opens scrolled into the middle of itself.
    pub fn reset_pan(&mut self) {
        if self.pan == 0. {
            return;
        }
        self.pan = 0.;
        self.revision = self.revision.wrapping_add(1);
        #[cfg(feature = "android-ui")]
        signal_change();
    }

    /// Capture the held body without requiring an active input connection.
    /// The caller joins its lines after releasing the mailbox lock.
    #[cfg(any(all(target_os = "android", feature = "android-ui"), test))]
    pub(crate) fn snapshot_for_copy(&self, generation: i64) -> Result<Arc<Snapshot>> {
        if generation <= 0 || generation != self.generation {
            return Err(Error::StaleGeneration);
        }
        Ok(self.snapshot.clone())
    }

    pub fn state(&self) -> ViewState {
        ViewState {
            generation: self.generation,
            active: self.active,
            revision: self.revision,
            pan: self.pan,
            snapshot: self.snapshot.clone(),
            preedit: self.preedit.clone(),
            display_preferences: self.display_preferences,
            follow: self.follow,
            shell: self.shell,
        }
    }

    fn check_input(&self, generation: i64) -> Result<()> {
        if generation != self.generation {
            return Err(Error::StaleGeneration);
        }
        if !self.active {
            return Err(Error::Inactive);
        }
        Ok(())
    }

    pub fn enqueue(&mut self, generation: i64, command: Command) -> Result<()> {
        self.check_input(generation)?;
        if !self.preedit.is_empty() {
            return Err(Error::Composing);
        }
        self.push_command(command)
    }

    fn push_command(&mut self, command: Command) -> Result<()> {
        command.validate()?;
        if self.commands.len() >= MAX_COMMANDS {
            return Err(Error::QueueFull);
        }
        self.commands.push_back(command);
        Ok(())
    }

    pub fn set_ime_session(&mut self, generation: i64, session: i64) -> bool {
        if generation <= 0
            || generation != self.generation
            || session <= 0
            || session < self.ime_session
        {
            return false;
        }
        self.ime_session = session;
        self.set_preedit("");
        true
    }

    fn set_preedit(&mut self, text: &str) {
        if &*self.preedit == text {
            return;
        }
        self.preedit = Arc::from(text);
        self.revision = self.revision.wrapping_add(1);
        #[cfg(feature = "android-ui")]
        signal_change();
    }

    /// Java's InputConnection callback serializes all commits, surrounding
    /// deletion and physical keys into this same bounded FIFO immediately.
    /// Preedit remains local; it can never race ahead of a terminal Enter key.
    pub fn ime_event(
        &mut self,
        generation: i64,
        session: i64,
        kind: i32,
        text: &str,
        before: usize,
        after: usize,
    ) -> Result<()> {
        self.check_input(generation)?;
        if session <= 0 || session != self.ime_session || text.len() > MAX_TEXT_BYTES {
            return Err(Error::InvalidIme);
        }
        match kind {
            0 => {
                self.set_preedit(text);
                Ok(())
            }
            1 => {
                let result = if text.is_empty() {
                    Ok(())
                } else {
                    let command = if matches!(text, "\n" | "\r" | "\r\n") {
                        Command::Keys {
                            keys: vec!["enter".into()],
                        }
                    } else {
                        Command::Text { text: text.into() }
                    };
                    self.push_command(command)
                };
                // The proxy has committed and cleared its buffer even if our
                // FIFO is full. Report the rejection; never replay it later.
                self.set_preedit("");
                result
            }
            2 | 3 => {
                if !self.preedit.is_empty() {
                    return Err(Error::Composing);
                }
                if after != 0 || before > 16 {
                    return Err(Error::InvalidIme);
                }
                if before == 0 {
                    return Ok(());
                }
                self.push_command(Command::Keys {
                    keys: vec!["backspace".into(); before],
                })
            }
            4 => {
                self.set_preedit("");
                Ok(())
            }
            _ => Err(Error::InvalidIme),
        }
    }

    pub fn take_command(&mut self, generation: i64) -> Option<Command> {
        if !self.active || generation != self.generation {
            return None;
        }
        self.commands.pop_front()
    }
}

// One process-lived TerminalView consumes this single coalesced wake. The actual
// snapshot stays in the mailbox, so slow rendering never builds a state queue.
#[cfg(feature = "android-ui")]
static CHANGES: OnceLock<(async_channel::Sender<()>, async_channel::Receiver<()>)> =
    OnceLock::new();

#[cfg(feature = "android-ui")]
fn signal_change() {
    let (sender, _) = CHANGES.get_or_init(|| async_channel::bounded(1));
    let _ = sender.try_send(());
}

#[cfg(feature = "android-ui")]
pub(crate) fn snapshot_changes() -> async_channel::Receiver<()> {
    CHANGES.get_or_init(|| async_channel::bounded(1)).1.clone()
}

#[cfg(feature = "android-ui")]
static MAILBOX: OnceLock<Mutex<Mailbox>> = OnceLock::new();
#[cfg(feature = "android-ui")]
pub(crate) fn mailbox() -> MutexGuard<'static, Mailbox> {
    // Fail closed after a panicking owner: never replay a possibly partial queue.
    match MAILBOX.get_or_init(Mutex::default).lock() {
        Ok(guard) => guard,
        Err(poisoned) => {
            let mut guard = poisoned.into_inner();
            guard.active = false;
            guard.commands.clear();
            guard
        }
    }
}

#[cfg(test)]
mod tests {
    #![allow(clippy::unwrap_used)]
    use super::*;
    fn text(value: &str) -> Command {
        Command::Text { text: value.into() }
    }

    #[test]
    fn shell_type_fills_wide_panels_and_keeps_classic_width_on_narrow_ones() {
        let auto = DisplayPreferences::default();
        // The inner panel shows more of the shell, not 80 columns of larger text.
        assert_eq!(shell_font_size(auto, 852.), DEFAULT_FONT);
        // Between: exactly 80 columns.
        let mid = 600.;
        assert!((shell_font_size(auto, mid) * MONO_ADVANCE_EM * SHELL_COLUMNS - mid).abs() < 0.01);
        // The cover: the readable floor, as close to 80 columns as it allows.
        assert_eq!(shell_font_size(auto, 443.), 11.);
        // Before the first layout there is no width; the floor holds.
        assert_eq!(shell_font_size(auto, 0.), 11.);
        // Android's font scale enlarges auto type; a chosen size is used as given.
        let scaled = DisplayPreferences::scaled(0., false, 1.5).unwrap();
        assert_eq!(shell_font_size(scaled, 852.), DEFAULT_FONT * 1.5);
        let chosen = DisplayPreferences::new(16., false).unwrap();
        assert_eq!(shell_font_size(chosen, 852.), 16.);
    }

    #[test]
    fn short_panes_spread_their_rows_up_to_twice_their_pitch() {
        // Herdr's 24 rows fill the inner panel's surface exactly.
        assert_eq!(spread_row_height(16., 24, 696.), 29.);
        // A pane taller than the surface keeps its pitch and scrolls.
        assert_eq!(spread_row_height(16., 60, 696.), 16.);
        // A few rows stop at twice their pitch; even margins hold the rest.
        assert_eq!(spread_row_height(16., 5, 696.), 32.);
        // Nothing to lay out, or no surface yet: the natural pitch.
        assert_eq!(spread_row_height(16., 0, 696.), 16.);
        assert_eq!(spread_row_height(16., 24, 0.), 16.);
        assert_eq!(spread_row_height(16., 24, f32::NAN), 16.);
    }
    fn submit(value: &str) -> Command {
        Command::Submit { text: value.into() }
    }

    #[test]
    fn surface_runs_preserve_unicode_styles_and_links() {
        let raw = serde_json::json!({
            "links": ["https://example.com/terminal"],
            "rows": [{
                "t": "a\"\\界😀",
                "r": [
                    [0, 3, 0x0100_0001u32, 0, BOLD, u32::MAX],
                    [3, 3, 0, 0x0200_0010u32, UNDERLINE, 0],
                    [6, 4, 0, 0, REVERSED, u32::MAX]
                ]
            }]
        })
        .to_string();
        let (rows, links) = parse_surface(&raw).unwrap();
        assert_eq!(links, ["https://example.com/terminal"]);
        assert_eq!(
            rows,
            [StyledRow {
                text: "a\"\\界😀".into(),
                runs: vec![
                    StyledRun {
                        start: 0,
                        len: 3,
                        fg: 0x0100_0001,
                        bg: 0,
                        modifier: BOLD,
                        link: None
                    },
                    StyledRun {
                        start: 3,
                        len: 3,
                        fg: 0,
                        bg: 0x0200_0010,
                        modifier: UNDERLINE,
                        link: Some(0)
                    },
                    StyledRun {
                        start: 6,
                        len: 4,
                        fg: 0,
                        bg: 0,
                        modifier: REVERSED,
                        link: None
                    },
                ],
            }]
        );
    }

    #[test]
    fn surface_rejects_invalid_spans_links_and_bounds() {
        for run in [
            "[0,0,0,0,0,4294967295]",
            "[1,3,0,0,0,4294967295]",
            "[0,2,0,0,0,4294967295]",
            "[0,4,0,0,0,4294967295]",
            "[0,3,0,0,65536,4294967295]",
            "[0,3,0,0,0,0]",
            "[0,3,0,0,0,65536]",
            "[0,3,4294967296,0,0,4294967295]",
            "[0,3,-1,0,0,4294967295]",
            "[0,3,0.0,0,0,4294967295]",
            "[0,3,false,0,0,4294967295]",
            "[0,3,0,0,0]",
            "[0,3,0,0,0,4294967295,0]",
        ] {
            let raw = format!("{{\"rows\":[{{\"t\":\"界\",\"r\":[{run}]}}]}}");
            assert!(
                matches!(parse_surface(&raw), Err(Error::InvalidText)),
                "{run}"
            );
        }
        for value in [
            serde_json::json!({"links": ["file:///private"], "rows": []}),
            serde_json::json!({"links": ["https://example.com/\n"], "rows": []}),
            serde_json::json!({"links": [format!("https://{}", "x".repeat(2048))], "rows": []}),
            serde_json::json!({"links": vec!["https://example.com"; 257], "rows": []}),
            serde_json::json!({"rows": vec![serde_json::json!({"t":"", "r": []}); MAX_LINES + 1]}),
            serde_json::json!({"rows": [{"t": "x".repeat(MAX_LINE_CHARS + 1), "r": []}]}),
            serde_json::json!({"rows": [{"t": "x".repeat(MAX_RUNS_PER_LINE + 1), "r": vec![[0,1,0,0,0,u32::MAX]; MAX_RUNS_PER_LINE + 1]}]}),
        ] {
            assert!(matches!(
                parse_surface(&value.to_string()),
                Err(Error::InvalidText)
            ));
        }
        assert!(matches!(
            parse_surface(&" ".repeat(MAX_SNAPSHOT_BYTES + 1)),
            Err(Error::InvalidText)
        ));
    }

    #[test]
    fn lifecycle_and_delayed_generations_are_fenced() {
        let mut box_ = Mailbox::default();
        box_.set_active(10, true);
        box_.enqueue(10, text("old")).unwrap();
        box_.set_active(11, false);
        assert!(box_.take_command(10).is_none());
        assert!(!box_.set_active(10, true));
        assert!(!box_.set_snapshot(10, Snapshot::default()));
        assert!(matches!(
            box_.enqueue(10, text("stale")),
            Err(Error::StaleGeneration)
        ));
        assert!(matches!(
            box_.enqueue(11, text("paused")),
            Err(Error::Inactive)
        ));
        box_.set_active(11, true);
        assert!(box_.take_command(11).is_none());
        box_.enqueue(11, text("new")).unwrap();
        box_.set_active(11, false);
        box_.set_active(11, true);
        assert!(box_.take_command(11).is_none());
    }
    #[test]
    fn repeated_state_and_snapshot_updates_preserve_revision_and_fifo() {
        let mut box_ = Mailbox::default();
        assert!(box_.set_active(10, true));
        let snapshot = Snapshot::new("title", "unchanged\noutput", "Live", "[]").unwrap();
        assert!(box_.set_snapshot(10, snapshot.clone()));
        box_.enqueue(10, text("queued")).unwrap();
        let before = box_.state();
        assert!(!box_.set_active(10, true));
        assert!(box_.set_snapshot(10, snapshot));
        let after = box_.state();
        assert_eq!(before.revision, after.revision);
        assert!(Arc::ptr_eq(&before.snapshot, &after.snapshot));
        assert_eq!(box_.take_command(10), Some(text("queued")));
        assert!(box_.set_active(10, false));
        let revision = box_.state().revision;
        assert!(!box_.set_active(10, false));
        assert_eq!(revision, box_.state().revision);
        // An identical inactive flag with a NEW generation still fences state.
        assert!(box_.set_active(11, false));
        assert_eq!(box_.state().generation, 11);
        assert!(box_.state().snapshot.lines.is_empty());
    }

    #[test]
    fn copy_snapshot_keeps_visible_unicode_and_rejects_stale_generations() {
        let mut box_ = Mailbox::default();
        assert!(matches!(
            box_.snapshot_for_copy(0),
            Err(Error::StaleGeneration)
        ));
        box_.set_active(10, true);
        box_.set_snapshot(
            10,
            Snapshot::new("pane", "é\n\n界😀", "Live", "[]").unwrap(),
        );
        box_.enqueue(10, text("queued")).unwrap();
        let revision = box_.state().revision;
        let copy = box_.snapshot_for_copy(10).unwrap();
        assert_eq!(copy.lines.join("\n"), "é\n\n界😀");
        assert_eq!(box_.state().revision, revision);
        assert_eq!(box_.take_command(10), Some(text("queued")));
        box_.set_active(10, false);
        assert_eq!(
            box_.snapshot_for_copy(10).unwrap().lines.join("\n"),
            "é\n\n界😀"
        );
        box_.set_active(11, true);
        assert!(matches!(
            box_.snapshot_for_copy(10),
            Err(Error::StaleGeneration)
        ));
        assert_eq!(box_.snapshot_for_copy(11).unwrap().lines.join("\n"), "");
        assert_eq!(copy.lines.join("\n"), "é\n\n界😀");
    }

    #[test]
    fn chrome_updates_preserve_colored_linked_unicode_body() {
        let mut box_ = Mailbox::default();
        box_.set_active(10, true);
        let surface = serde_json::json!({
            "links": ["https://example.com/界"],
            "rows": [{
                "t": "界😀",
                "r": [[0, 7, 0x0100_0001u32, 0, BOLD, 0]]
            }]
        })
        .to_string();
        let snapshot =
            Snapshot::with_surface("old", "界😀", "old status", "[]", Some(&surface)).unwrap();
        assert!(box_.set_snapshot(10, snapshot));
        assert!(box_.set_follow(10, false));
        assert!(box_.pan(80.));
        let original = box_.state().snapshot;
        let original_row = original.styled.as_ref().unwrap()[0].clone();
        assert!(
            box_.set_snapshot_chrome(
                10,
                "new title",
                "new status",
                r#"[{"label":"C-c","keys":["ctrl+c"]}]"#,
            )
            .unwrap()
        );
        let state = box_.state();
        assert_eq!(state.snapshot.title, "new title");
        assert_eq!(state.snapshot.status, "new status");
        assert_eq!(state.snapshot.shortcuts.len(), 1);
        assert_eq!(state.snapshot.lines, original.lines);
        assert_eq!(state.snapshot.styled.as_ref().unwrap()[0], original_row);
        assert_eq!(state.snapshot.links, original.links);
        assert_eq!(state.snapshot.truncated, original.truncated);
        assert_eq!(state.snapshot.widest_line_chars, original.widest_line_chars);
        assert!(!state.follow);
        assert_eq!(state.pan, 80.);
    }

    #[test]
    fn a_shell_belongs_to_one_generation() {
        let mut box_ = Mailbox::default();
        box_.set_active(10, true);
        // A delayed attach from a replaced terminal is ignored.
        assert!(!box_.set_shell(9, 7));
        assert!(box_.set_shell(10, 7));
        assert!(
            !box_.set_shell(10, 7),
            "unchanged attach does not wake the view"
        );
        let attached = box_.state();
        assert_eq!(attached.shell, 7);
        // Pausing keeps the last screen; only a new generation drops the shell.
        box_.set_active(10, false);
        assert_eq!(box_.state().shell, 7);
        box_.set_active(11, true);
        assert_eq!(box_.state().shell, 0);
        assert!(box_.state().revision != attached.revision);
    }

    #[test]
    fn chrome_update_rejects_stale_generation_without_mutation() {
        let mut box_ = Mailbox::default();
        box_.set_active(10, true);
        let before = box_.state();
        assert!(
            !box_
                .set_snapshot_chrome(9, "stale", "stale", "invalid")
                .unwrap()
        );
        let after = box_.state();
        assert_eq!(after.revision, before.revision);
        assert!(Arc::ptr_eq(&after.snapshot, &before.snapshot));
    }

    #[test]
    fn unchanged_chrome_does_not_revise_or_replace_snapshot() {
        let mut box_ = Mailbox::default();
        box_.set_active(10, true);
        let snapshot = Snapshot::new("title", "body", "status", "[]").unwrap();
        assert!(box_.set_snapshot(10, snapshot));
        let before = box_.state();
        assert!(
            !box_
                .set_snapshot_chrome(10, "title", "status", "[]")
                .unwrap()
        );
        let after = box_.state();
        assert_eq!(after.revision, before.revision);
        assert!(Arc::ptr_eq(&after.snapshot, &before.snapshot));
    }

    #[test]
    fn invalid_chrome_shortcuts_fail_atomically() {
        let mut box_ = Mailbox::default();
        box_.set_active(10, true);
        assert!(box_.set_snapshot(10, Snapshot::new("title", "body", "status", "[]").unwrap()));
        let before = box_.state();
        assert!(
            box_.set_snapshot_chrome(10, "changed", "changed", r#"[{"label":"bad","keys":[]}]"#)
                .is_err()
        );
        let after = box_.state();
        assert_eq!(after.revision, before.revision);
        assert!(Arc::ptr_eq(&after.snapshot, &before.snapshot));
        assert_eq!(after.snapshot.title, "title");
        assert_eq!(after.snapshot.status, "status");
    }

    #[test]
    fn horizontal_wheel_pans_and_vertical_wheel_does_not() {
        assert_eq!(pan_from_wheel(-300., 0.), Some(300.));
        assert_eq!(pan_from_wheel(0., -300.), None);
        // Diagonal gestures follow the dominant axis only.
        assert_eq!(pan_from_wheel(120., 40.), Some(-120.));
        assert_eq!(pan_from_wheel(40., 120.), None);
        // Jitter under the threshold is never a pan.
        assert_eq!(pan_from_wheel(1.5, 0.), None);
        assert_eq!(pan_from_wheel(f32::NAN, 0.), None);
    }

    #[test]
    fn pan_is_bounded_and_only_reports_real_movement() {
        let mut box_ = Mailbox::default();
        assert_eq!(box_.state().pan, 0.);
        assert!(!box_.pan(-96.));
        assert!(box_.pan(96.));
        assert_eq!(box_.state().pan, 96.);
        for _ in 0..100 {
            box_.pan(96.);
        }
        assert_eq!(box_.state().pan, MAX_PAN);
        box_.pan(-MAX_PAN * 2.);
        assert_eq!(box_.state().pan, 0.);
        assert!(!box_.pan(f32::NAN));
        assert_eq!(box_.state().pan, 0.);
    }

    #[test]
    fn pan_survives_unchanged_layout_but_not_new_type_or_session() {
        let mut box_ = Mailbox::default();
        box_.set_active(10, true);
        box_.pan(96.);
        let before = box_.state();
        assert!(!box_.set_display_preferences_scaled(0., false, 1.).unwrap());
        assert_eq!(box_.state().pan, 96.);
        assert_eq!(box_.state().revision, before.revision);
        assert!(
            box_.set_display_preferences_scaled(f32::NAN, false, 1.)
                .is_err()
        );
        assert_eq!(box_.state().pan, 96.);

        assert!(box_.set_display_preferences_scaled(16., false, 1.).unwrap());
        assert_eq!(box_.state().pan, 0.);
        assert_eq!(box_.state().revision, before.revision + 1);
        box_.pan(96.);
        assert!(
            box_.set_display_preferences_scaled(16., false, 1.3)
                .unwrap()
        );
        assert_eq!(box_.state().pan, 0.);
        box_.pan(96.);
        assert!(!box_.set_active(9, true));
        assert_eq!(box_.state().pan, 96.);
        assert!(box_.set_active(10, false));
        assert_eq!(box_.state().pan, 96.);
        assert!(box_.set_active(11, false));
        assert_eq!(box_.state().pan, 0.);
    }

    #[test]
    fn auto_type_fills_a_narrow_pane_and_stays_on_screen_for_a_wide_one() {
        let auto = DisplayPreferences::default();
        // A 70-cell pane on a 798 dp panel grows instead of leaving the row half empty.
        let roomy = DisplayMetrics::fit(auto, 798., 70);
        assert!(roomy.font_size > 13., "{}", roomy.font_size);
        assert!(roomy.font_size <= 20., "{}", roomy.font_size);
        // A 150-cell pane clamps down so the last cell still fits.
        let dense = DisplayMetrics::fit(auto, 798., 150);
        assert!(
            (11.0..=13.0).contains(&dense.font_size),
            "{}",
            dense.font_size
        );
        // An explicit choice always wins over the fit.
        let chosen = DisplayPreferences::new(17., false).unwrap();
        assert_eq!(DisplayMetrics::fit(chosen, 798., 70).font_size, 17.);
        // Before a pane arrives the long-standing default stands.
        assert_eq!(DisplayMetrics::fit(auto, 798., 0).font_size, 13.);
        // Android's system font scale must still enlarge auto type.
        let scaled = DisplayPreferences::scaled(0., false, 1.3).unwrap();
        let big = DisplayMetrics::fit(scaled, 798., 70);
        assert!(big.font_size > roomy.font_size, "{}", big.font_size);
        assert!(big.font_size <= 36., "{}", big.font_size);
        // Unclamped auto type fills the surface exactly: the widest row plus
        // its gutters never needs a pan, and leaves no reserved slack either.
        for (width, cells) in [(852., 80), (798., 70), (600., 48)] {
            for compact in [false, true] {
                let preferences = DisplayPreferences::new(0., compact).unwrap();
                let metrics = DisplayMetrics::fit(preferences, width, cells);
                let painted = metrics.row_width(cells);
                assert!(painted <= width + 0.01, "{width}/{cells}: {painted}");
                assert!(painted >= width - 0.5, "{width}/{cells}: {painted}");
            }
        }
    }

    #[test]
    fn chrome_roles_follow_the_palette_and_keep_text_legible() {
        for palette in PALETTES {
            let roles = ChromeRoles::of(&palette);
            assert_eq!(roles.surface, palette.chrome, "{}", palette.name);
            assert_eq!(roles.light, palette.name == "Latte", "{}", palette.name);
            for (role, color, under) in [
                ("text", roles.on_surface, roles.surface),
                ("secondary", roles.on_surface_variant, roles.surface),
                ("primary", roles.primary, roles.surface),
                ("danger", roles.danger, roles.surface),
                ("warning", roles.warning, roles.surface),
                ("keycap", roles.on_surface, roles.control),
                ("selection", roles.on_surface, roles.selected),
            ] {
                assert!(
                    contrast(color, under) >= TEXT_CONTRAST,
                    "{} {role}: {:.2}",
                    palette.name,
                    contrast(color, under)
                );
            }
            // Containers stay distinguishable from the bar they sit on.
            assert_ne!(roles.control, roles.surface, "{}", palette.name);
            assert_ne!(roles.selected, roles.surface, "{}", palette.name);
            let exported = roles.to_argb();
            assert_eq!(exported[1], (0xff00_0000 | palette.chrome) as i32);
            assert_eq!(exported[13], i32::from(roles.light));
        }
        // A color that already passes keeps its hue; only failures are blended.
        let default = PALETTES[0];
        assert_eq!(ChromeRoles::of(&default).primary, default.accent);
        let latte = PALETTES[3];
        assert_ne!(ChromeRoles::of(&latte).warning, latte.ansi[3]);
    }

    #[test]
    fn pan_stops_at_the_painted_edge_and_reverses_immediately() {
        let mut box_ = Mailbox::default();
        box_.set_pan_limit(150.);
        assert!(box_.pan(96.));
        assert!(box_.pan(96.));
        assert_eq!(box_.state().pan, 150.);
        assert!(!box_.pan(96.), "a step past the last cell is not a move");
        let revision = box_.state().revision;
        assert!(box_.pan(-96.), "the first step back moves at once");
        assert_eq!(box_.state().pan, 54.);
        assert_eq!(box_.state().revision, revision + 1);
        // A narrower layout clamps the offset without waking the renderer.
        let revision = box_.state().revision;
        box_.set_pan_limit(20.);
        assert_eq!(box_.state().pan, 20.);
        assert_eq!(box_.state().revision, revision);
        for invalid in [f32::NAN, f32::INFINITY] {
            box_.set_pan_limit(invalid);
            assert_eq!(box_.state().pan, 20.);
        }
        box_.set_pan_limit(MAX_PAN * 2.);
        assert!(box_.pan(MAX_PAN * 2.));
        assert_eq!(box_.state().pan, MAX_PAN);
    }

    /// One fixture row: its text and `(bytes, background, modifier)` runs.
    type PaintedRow<'a> = (&'a str, &'a [(u32, u32, u16)]);

    fn painted_pane(rows: &[PaintedRow<'_>]) -> Snapshot {
        let wire: Vec<_> = rows
            .iter()
            .map(|(text, runs)| {
                let mut start = 0;
                let runs: Vec<_> = runs
                    .iter()
                    .map(|&(len, bg, modifier)| {
                        let run = [start, len, 0x02cd_d6f4, bg, u32::from(modifier), u32::MAX];
                        start += len;
                        run
                    })
                    .collect();
                serde_json::json!({ "t": text, "r": runs })
            })
            .collect();
        let surface = serde_json::json!({ "rows": wire }).to_string();
        let text: Vec<&str> = rows.iter().map(|(text, _)| *text).collect();
        let snapshot =
            Snapshot::with_surface("pane", &text.join("\n"), "", "[]", Some(&surface)).unwrap();
        assert!(snapshot.styled.is_some(), "fixture must be a valid surface");
        snapshot
    }

    const LATTE: usize = 3;
    const MOCHA: usize = 2;
    /// The two backgrounds an agent pane paints: its own and its tool boxes.
    const BASE: u32 = 0x021e_1e2e;
    const BOX: u32 = 0x020f_1117;

    #[test]
    fn a_program_painting_its_own_background_fills_the_letterbox() {
        // Tool boxes and the base in close shades, an inner accent, a reversed
        // cursor and the daemon's default-background scrollbar on the edge:
        // the commonest painted shade fills.
        let agent = painted_pane(&[
            ("abcd│", &[(4, BOX, 0), (3, DEFAULT_COLOR, FRAME)]),
            (
                "a  d│",
                &[
                    (1, BOX, 0),
                    (2, 0x02f3_8ba8, 0),
                    (1, BOX, 0),
                    (3, DEFAULT_COLOR, FRAME),
                ],
            ),
            ("a  d│", &[(4, BASE, 0), (3, DEFAULT_COLOR, FRAME)]),
            (
                "abcd┃",
                &[(1, BASE, REVERSED), (3, BASE, 0), (3, DEFAULT_COLOR, FRAME)],
            ),
        ]);
        let latte = PALETTES[LATTE];
        assert_eq!(letterbox(&agent.edge_colors, &latte, false), Some(0x0f1117));
        // On a palette already that dark the pane needs no frame of its own.
        assert_eq!(letterbox(&agent.edge_colors, &PALETTES[MOCHA], false), None);
        // A shell on the palette's own background never fills, in any theme.
        let shell = painted_pane(&[
            ("abcd", &[(4, DEFAULT_COLOR, 0)]),
            ("a  d", &[(4, DEFAULT_COLOR, 0)]),
        ]);
        for palette in PALETTES {
            assert_eq!(
                letterbox(&shell.edge_colors, &palette, false),
                None,
                "{}",
                palette.name
            );
        }
        // A chrome-only update keeps the body, and with it the edge.
        let mut box_ = Mailbox::default();
        box_.set_active(3, true);
        let edge = agent.edge_colors.clone();
        assert!(box_.set_snapshot(3, agent));
        assert!(box_.set_snapshot_chrome(3, "renamed", "", "[]").unwrap());
        assert_eq!(box_.state().snapshot.edge_colors, edge);
    }

    #[test]
    fn the_letterbox_starts_at_three_quarters_and_holds_until_half() {
        let latte = PALETTES[LATTE];
        // Cells past a short row's end show the default background.
        let full: PaintedRow<'_> = ("abcdef", &[(6, BASE, 0)]);
        let short: PaintedRow<'_> = ("ab", &[(2, BASE, 0)]);
        let mostly = painted_pane(&[full, short, short, short, short, full]);
        assert_eq!(
            letterbox(&mostly.edge_colors, &latte, false),
            Some(0x1e1e2e)
        );
        // 20 painted cells of 28: too few to start filling, enough to keep it.
        let half = painted_pane(&[
            full, short, short, short, short, short, short, short, short, full,
        ]);
        assert_eq!(letterbox(&half.edge_colors, &latte, false), None);
        assert_eq!(letterbox(&half.edge_colors, &latte, true), Some(0x1e1e2e));
        // Below half (12 painted cells of 28), a filled letterbox lets go.
        let blank: PaintedRow<'_> = ("ab", &[(2, DEFAULT_COLOR, 0)]);
        let sparse = painted_pane(&[
            full, blank, blank, blank, blank, blank, blank, blank, blank, full,
        ]);
        assert_eq!(letterbox(&sparse.edge_colors, &latte, true), None);
    }

    #[test]
    fn display_preferences_are_bounded_local_and_generation_independent() {
        let mut box_ = Mailbox::default();
        // A fresh session is auto-sized, so 0 changes nothing and 13 is a choice.
        assert!(!box_.set_display_preferences(0., false).unwrap());
        assert!(box_.set_display_preferences(13., false).unwrap());
        assert!(box_.set_display_preferences(12., true).unwrap());
        assert_eq!(box_.state().generation, 0);
        assert!(!box_.state().active);
        box_.set_active(10, true);
        box_.enqueue(10, text("queued")).unwrap();
        box_.set_ime_session(10, 2);
        box_.ime_event(10, 2, 0, "composing", 0, 0).unwrap();
        let before = box_.state();
        assert!(!box_.set_display_preferences(12., true).unwrap());
        assert_eq!(before.revision, box_.state().revision);
        for invalid in [f32::NAN, f32::INFINITY, f32::NEG_INFINITY, 10.9, 36.1] {
            assert!(matches!(
                box_.set_display_preferences(invalid, false),
                Err(Error::InvalidDisplayPreferences)
            ));
        }
        assert_eq!(before.revision, box_.state().revision);
        assert!(box_.set_display_preferences(36., false).unwrap());
        let after = box_.state();
        assert_eq!(after.revision, before.revision + 1);
        assert_eq!(after.generation, 10);
        assert!(after.active);
        assert!(Arc::ptr_eq(&before.snapshot, &after.snapshot));
        assert!(Arc::ptr_eq(&before.preedit, &after.preedit));
        assert_eq!(box_.take_command(10), Some(text("queued")));
        box_.ime_event(10, 2, 1, "committed", 0, 0).unwrap();
        assert_eq!(box_.take_command(10), Some(text("committed")));
        box_.set_active(11, false);
        assert_eq!(box_.state().display_preferences, after.display_preferences);
        assert!(box_.set_display_preferences(11., true).unwrap());
    }
    #[test]
    fn unchanged_preedit_does_not_allocate_or_wake_but_commit_stays_ordered() {
        let mut box_ = Mailbox::default();
        box_.set_active(1, true);
        let revision = box_.state().revision;
        box_.set_ime_session(1, 10);
        assert_eq!(revision, box_.state().revision);
        box_.ime_event(1, 10, 0, "ni", 0, 2).unwrap();
        let before = box_.state();
        box_.ime_event(1, 10, 0, "ni", 0, 2).unwrap();
        assert_eq!(before.revision, box_.state().revision);
        assert!(Arc::ptr_eq(&before.preedit, &box_.state().preedit));
        box_.ime_event(1, 10, 1, "你", 0, 0).unwrap();
        let committed_revision = box_.state().revision;
        box_.ime_event(1, 10, 4, "", 0, 0).unwrap();
        assert_eq!(committed_revision, box_.state().revision);
        assert_eq!(box_.take_command(1), Some(text("你")));
        assert!(box_.take_command(1).is_none());
    }
    #[test]
    fn compact_geometry_adds_terminal_rows_without_reducing_touch_targets() {
        let compact = DisplayMetrics::new(DisplayPreferences::new(12., true).unwrap(), 320.);
        let standard = DisplayMetrics::new(DisplayPreferences::default(), 320.);
        let visible_rows = |metrics: DisplayMetrics, height: f32| {
            let footer_height = metrics.touch_height + 2. * metrics.padding + 1.;
            ((height - metrics.header_height - footer_height) / metrics.row_height).floor()
        };
        assert!(!compact.stacked_controls);
        assert!(!standard.stacked_controls);
        assert_eq!(compact.touch_height, 48.);
        assert_eq!(standard.touch_height, 48.);
        for height in [320., 432., 480., 500.] {
            assert!(visible_rows(compact, height) > visible_rows(standard, height));
            assert!(visible_rows(compact, height) >= 13.);
        }
    }
    #[test]
    fn accessibility_geometry_scales_text_and_keeps_controls_inside_narrow_windows() {
        for compact in [false, true] {
            let baseline =
                DisplayMetrics::new(DisplayPreferences::new(12., compact).unwrap(), 320.);
            for font_size in [11., 12., 13., 18., 24., 36.] {
                for width in [320., 360., 432., 480., 600.] {
                    let metrics = DisplayMetrics::new(
                        DisplayPreferences::new(font_size, compact).unwrap(),
                        width,
                    );
                    assert_eq!(metrics.font_size, font_size);
                    assert!(metrics.row_height >= font_size * 1.4);
                    assert!(metrics.header_height >= font_size * 1.5);
                    assert!(metrics.touch_height >= 48.);
                    let available = width - 2. * metrics.padding;
                    let keyboard_width = if metrics.stacked_controls {
                        available
                    } else {
                        (available - 2. * metrics.gap) / 3.
                    };
                    assert!(keyboard_width >= 8. * font_size * 0.65 + 6.);
                    if metrics.stacked_controls {
                        let other_width = (available - metrics.gap) / 2.;
                        assert!(other_width >= 6. * font_size * 0.65 + 6.);
                    }
                    assert!(metrics.row_width(1) >= 200.);
                    assert_eq!(
                        metrics.row_width(usize::MAX),
                        metrics.row_width(MAX_LINE_CHARS)
                    );
                    if font_size > 12. {
                        assert!(metrics.row_width(100) > baseline.row_width(100));
                    }
                }
            }
        }
        let largest = DisplayMetrics::new(DisplayPreferences::new(36., true).unwrap(), 320.);
        assert!(largest.stacked_controls);
    }
    #[test]
    fn inactive_status_updates_are_visible_without_enabling_input() {
        let mut box_ = Mailbox::default();
        box_.set_active(5, false);
        assert!(box_.set_snapshot(5, Snapshot::new("a", "live", "Offline", "[]").unwrap()));
        assert_eq!(box_.state().snapshot.status, "Offline");
        assert!(!box_.state().active);
    }
    #[test]
    fn fifo_is_bounded_and_never_reorders_or_drops_old_commands() {
        let mut box_ = Mailbox::default();
        box_.set_active(1, true);
        for i in 0..MAX_COMMANDS {
            box_.enqueue(1, text(&i.to_string())).unwrap();
        }
        assert!(matches!(
            box_.enqueue(1, text("overflow")),
            Err(Error::QueueFull)
        ));
        for i in 0..MAX_COMMANDS {
            assert_eq!(box_.take_command(1), Some(text(&i.to_string())));
        }
        assert!(box_.take_command(1).is_none());
    }
    #[test]
    fn shortcut_schema_is_strict_and_uses_actual_companion_keys() {
        assert_eq!(
            parse_shortcuts(
                r#"[{"label":"C-c","keys":["ctrl+c"]},{"label":"Status","text":"git status"}]"#
            )
            .unwrap()
            .len(),
            2
        );
        for bad in [
            r#"[{"label":"bad","keys":["C-c"]}]"#,
            r#"[{"label":"both","keys":["esc"],"text":"oops"}]"#,
            r#"[{"label":"bad","text":"ok","submit":true}]"#,
            r#"[{"label":"bad","keys":[]}]"#,
        ] {
            assert!(parse_shortcuts(bad).is_err());
        }
    }
    #[test]
    fn text_and_keys_bounds_are_enforced() {
        assert!(text(&"a".repeat(MAX_TEXT_BYTES)).validate().is_ok());
        assert!(
            text(&"😀".repeat(MAX_TEXT_BYTES / 4 + 1))
                .validate()
                .is_err()
        );
        assert!(text("").validate().is_err());
        assert!(
            Command::Keys {
                keys: vec!["enter".into(); 17]
            }
            .validate()
            .is_err()
        );
    }
    #[test]
    fn submit_accepts_only_bounded_single_line_unicode() {
        for valid in [
            "x".into(),
            " git status ".into(),
            "a".repeat(MAX_SUBMIT_BYTES),
            "é".repeat(MAX_SUBMIT_BYTES / 2),
            "😀".repeat(MAX_SUBMIT_BYTES / 4),
        ] {
            assert!(submit(&valid).validate().is_ok());
        }
        for invalid in [
            String::new(),
            "a".repeat(MAX_SUBMIT_BYTES + 1),
            "é".repeat(MAX_SUBMIT_BYTES / 2 + 1),
            "😀".repeat(MAX_SUBMIT_BYTES / 4 + 1),
        ] {
            assert!(matches!(
                submit(&invalid).validate(),
                Err(Error::InvalidCommand)
            ));
        }
        for ch in ('\0'..='\u{1f}')
            .chain('\u{7f}'..='\u{9f}')
            .chain(['\u{2028}', '\u{2029}'])
        {
            let invalid = format!("before{ch}after");
            assert!(matches!(
                submit(&invalid).validate(),
                Err(Error::InvalidCommand)
            ));
            // Explicit text input keeps its original multiline/control policy.
            assert!(text(&invalid).validate().is_ok());
        }
    }
    #[test]
    fn modifier_chords_are_never_silently_stripped() {
        assert_eq!(
            physical_key("c", true, false, false, false),
            Some(Command::Keys {
                keys: vec!["ctrl+c".into()]
            })
        );
        for flags in [
            (true, true, false, false),
            (true, false, true, false),
            (false, false, false, true),
        ] {
            assert!(physical_key("c", flags.0, flags.1, flags.2, flags.3).is_none());
        }
        assert!(physical_key("y", false, false, false, false).is_none());
        assert_eq!(
            physical_key("tab", false, false, true, false),
            Some(Command::Keys {
                keys: vec!["shift+tab".into()]
            })
        );
    }
    #[test]
    fn terminal_sequences_are_never_executed_or_displayed_as_commands() {
        let (plain, _) = display_text(
            "a\u{1b}[31mred\u{1b}[0m\u{1b}]52;c;secret\u{7}b\u{1b}Pgraphics\u{1b}\\c\u{9d}link\u{9c}d\0\n",
        );
        assert_eq!(plain, "aredbcd\n");
    }
    #[test]
    fn snapshot_unicode_and_line_work_are_bounded() {
        let snapshot =
            Snapshot::new("title", &"😀".repeat(MAX_LINE_CHARS + 1), "ok", "[]").unwrap();
        assert_eq!(snapshot.lines[0].chars().count(), MAX_LINE_CHARS);
        assert!(snapshot.truncated);
        let snapshot = Snapshot::new("title", &"x\n".repeat(MAX_LINES + 1), "ok", "[]").unwrap();
        assert_eq!(snapshot.lines.len(), MAX_LINES);
        assert!(snapshot.truncated);
        let (plain, truncated) = display_text(&"😀".repeat(MAX_SNAPSHOT_BYTES));
        assert!(plain.len() <= MAX_SNAPSHOT_BYTES);
        assert!(truncated);
    }
    #[test]
    fn serialization_keeps_text_and_submission_explicit() {
        assert_eq!(
            serde_json::to_string(&text("x\n")).unwrap(),
            r#"{"type":"text","text":"x\n"}"#
        );
        assert_eq!(
            serde_json::to_string(&Command::Close).unwrap(),
            r#"{"type":"close"}"#
        );
        assert_eq!(
            serde_json::to_string(&submit("git status")).unwrap(),
            r#"{"type":"submit","text":"git status"}"#
        );
    }
    #[test]
    fn ime_commits_and_physical_enter_share_exact_fifo_order() {
        let mut box_ = Mailbox::default();
        box_.set_active(1, true);
        box_.set_ime_session(1, 10);
        box_.ime_event(1, 10, 1, "你", 0, 0).unwrap();
        box_.enqueue(
            1,
            Command::Keys {
                keys: vec!["enter".into()],
            },
        )
        .unwrap();
        assert_eq!(box_.take_command(1), Some(text("你")));
        assert_eq!(
            box_.take_command(1),
            Some(Command::Keys {
                keys: vec!["enter".into()]
            })
        );
        assert!(box_.take_command(1).is_none());
    }
    #[test]
    fn preedit_blocks_keys_and_only_commit_sends_text() {
        let mut box_ = Mailbox::default();
        box_.set_active(1, true);
        box_.set_ime_session(1, 10);
        box_.ime_event(1, 10, 0, "ni", 0, 2).unwrap();
        assert_eq!(&*box_.state().preedit, "ni");
        assert!(matches!(
            box_.enqueue(
                1,
                Command::Keys {
                    keys: vec!["enter".into()]
                }
            ),
            Err(Error::Composing)
        ));
        assert!(box_.take_command(1).is_none());
        box_.ime_event(1, 10, 1, "你", 0, 0).unwrap();
        assert_eq!(box_.take_command(1), Some(text("你")));
        assert!(box_.state().preedit.is_empty());
    }
    #[test]
    fn old_ime_sessions_and_generations_cannot_replay_input() {
        let mut box_ = Mailbox::default();
        box_.set_active(1, true);
        box_.set_ime_session(1, 10);
        box_.ime_event(1, 10, 0, "old", 0, 0).unwrap();
        box_.set_ime_session(1, 11);
        assert!(matches!(
            box_.ime_event(1, 10, 1, "late", 0, 0),
            Err(Error::InvalidIme)
        ));
        box_.set_active(2, true);
        box_.set_ime_session(2, 12);
        assert!(matches!(
            box_.ime_event(1, 11, 1, "late", 0, 0),
            Err(Error::StaleGeneration)
        ));
        assert!(box_.state().preedit.is_empty());
        assert!(box_.take_command(2).is_none());
    }
    #[test]
    fn ime_deletion_bounds_and_done_are_explicit() {
        let mut box_ = Mailbox::default();
        box_.set_active(1, true);
        box_.set_ime_session(1, 10);
        assert!(box_.ime_event(1, 10, 2, "", 17, 0).is_err());
        assert!(box_.ime_event(1, 10, 3, "", 1, 1).is_err());
        box_.ime_event(1, 10, 3, "", 2, 0).unwrap();
        assert_eq!(
            box_.take_command(1),
            Some(Command::Keys {
                keys: vec!["backspace".into(); 2]
            })
        );
        box_.ime_event(1, 10, 4, "", 0, 0).unwrap();
        assert!(box_.take_command(1).is_none());
    }
    #[test]
    fn native_command_json_is_strict_and_rejects_close() {
        assert_eq!(
            parse_command(r#"{"type":"text","text":"git status"}"#).unwrap(),
            text("git status")
        );
        assert_eq!(
            parse_command(r#"{"type":"submit","text":"echo \ud83d\ude00"}"#).unwrap(),
            submit("echo 😀")
        );
        assert!(matches!(
            parse_command(r#"{"type":"close"}"#),
            Err(Error::InvalidCommand)
        ));
        for bad in [
            r#"{"type":"text","text":"x","submit":true}"#,
            r#"{"type":"keys","keys":["ctrl+c"],"text":"x"}"#,
            r#"{"type":"keys","keys":["C-c"]}"#,
            r#"{"type":"submit"}"#,
            r#"{"type":"submit","text":null}"#,
            r#"{"type":"submit","text":1}"#,
            r#"{"type":"submit","text":"x","keys":[]}"#,
            r#"{"type":"submit","text":"x","submit":true}"#,
            r#"{"type":"submit","type":"text","text":"x"}"#,
            r#"{"type":"submit","text":"x","text":"y"}"#,
            r#"{"type":"submit","text":"x"}{}"#,
            r#"{"type":"submit","text":"\ud800"}"#,
            r#"{"type":"submit","text":"\udc00"}"#,
            r#"{"type":"submit","text":"\ud800x"}"#,
        ] {
            assert!(parse_command(bad).is_err(), "accepted {bad}");
        }
        for invalid in ["", "x\ny", "x\ry", "x\ty", "x\u{2028}y", "x\u{2029}y"] {
            let json = serde_json::to_string(&submit(invalid)).unwrap();
            assert!(matches!(parse_command(&json), Err(Error::InvalidCommand)));
        }
    }
    #[test]
    fn submission_uses_one_bounded_fifo_slot_without_splitting_enter() {
        let mut box_ = Mailbox::default();
        box_.set_active(1, true);
        for i in 0..MAX_COMMANDS - 1 {
            box_.enqueue(1, text(&i.to_string())).unwrap();
        }
        box_.enqueue(1, submit("git status")).unwrap();
        assert!(matches!(
            box_.enqueue(1, submit("overflow")),
            Err(Error::QueueFull)
        ));
        for i in 0..MAX_COMMANDS - 1 {
            assert_eq!(box_.take_command(1), Some(text(&i.to_string())));
        }
        assert_eq!(box_.take_command(1), Some(submit("git status")));
        assert!(box_.take_command(1).is_none());
    }
    #[test]
    fn submission_shares_fifo_order_with_ime_commits_and_physical_keys() {
        let mut box_ = Mailbox::default();
        box_.set_active(1, true);
        box_.set_ime_session(1, 10);
        box_.ime_event(1, 10, 1, "first", 0, 0).unwrap();
        box_.enqueue(
            1,
            parse_command(r#"{"type":"submit","text":"git status"}"#).unwrap(),
        )
        .unwrap();
        let key = physical_key("c", true, false, false, false).unwrap();
        box_.enqueue(1, key.clone()).unwrap();
        box_.ime_event(1, 10, 1, "last", 0, 0).unwrap();
        assert_eq!(box_.take_command(1), Some(text("first")));
        assert_eq!(box_.take_command(1), Some(submit("git status")));
        assert_eq!(box_.take_command(1), Some(key));
        assert_eq!(box_.take_command(1), Some(text("last")));
        assert!(box_.take_command(1).is_none());
    }
    #[test]
    fn submission_is_composition_and_generation_fenced_without_replay() {
        let mut box_ = Mailbox::default();
        box_.set_active(1, true);
        box_.set_ime_session(1, 10);
        box_.ime_event(1, 10, 0, "composing", 0, 0).unwrap();
        assert!(matches!(
            box_.enqueue(1, submit("git status")),
            Err(Error::Composing)
        ));
        box_.ime_event(1, 10, 4, "", 0, 0).unwrap();
        assert!(box_.take_command(1).is_none());
        assert!(matches!(
            box_.enqueue(1, submit("invalid\ncommand")),
            Err(Error::InvalidCommand)
        ));
        assert!(box_.take_command(1).is_none());
        box_.enqueue(1, submit("queued")).unwrap();
        box_.set_active(2, true);
        assert!(box_.take_command(1).is_none());
        assert!(box_.take_command(2).is_none());
        assert!(matches!(
            box_.enqueue(1, submit("stale")),
            Err(Error::StaleGeneration)
        ));
        box_.enqueue(2, submit("paused")).unwrap();
        box_.set_active(2, false);
        assert!(matches!(
            box_.enqueue(2, submit("inactive")),
            Err(Error::Inactive)
        ));
        box_.set_active(2, true);
        assert!(box_.take_command(2).is_none());
    }
    #[test]
    fn native_extra_keys_share_fifo_with_ime_and_physical_keys() {
        let mut box_ = Mailbox::default();
        box_.set_active(1, true);
        box_.set_ime_session(1, 10);
        box_.ime_event(1, 10, 1, "first", 0, 0).unwrap();
        box_.enqueue(
            1,
            parse_command(r#"{"type":"text","text":"macro"}"#).unwrap(),
        )
        .unwrap();
        box_.enqueue(
            1,
            Command::Keys {
                keys: vec!["enter".into()],
            },
        )
        .unwrap();
        assert_eq!(box_.take_command(1), Some(text("first")));
        assert_eq!(box_.take_command(1), Some(text("macro")));
        assert_eq!(
            box_.take_command(1),
            Some(Command::Keys {
                keys: vec!["enter".into()]
            })
        );
    }
}
