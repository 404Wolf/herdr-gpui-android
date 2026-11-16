use rio_vt::config::colors::ColorRgb;

pub type TerminalColor = ColorRgb;

#[derive(Clone, Copy, PartialEq)]
pub struct TerminalTheme {
    pub background: ColorRgb,
    pub foreground: ColorRgb,
    ansi: [ColorRgb; 16],
}

impl TerminalTheme {
    pub fn new(background: ColorRgb, foreground: ColorRgb, ansi: [ColorRgb; 16]) -> Self {
        Self {
            background,
            foreground,
            ansi,
        }
    }

    #[doc(hidden)]
    pub fn color(self, index: usize) -> ColorRgb {
        match index {
            0..=15 => self.ansi[index],
            16..=231 => {
                let n = index - 16;
                let channel = |n: usize| if n == 0 { 0 } else { (55 + n * 40) as u8 };
                ColorRgb {
                    r: channel(n / 36),
                    g: channel(n / 6 % 6),
                    b: channel(n % 6),
                }
            }
            232..=255 => {
                let v = (8 + (index - 232) * 10) as u8;
                ColorRgb { r: v, g: v, b: v }
            }
            257 => self.background,
            259..=266 => self.ansi[index - 259] * 0.66,
            268 => self.foreground * 0.66,
            _ => self.foreground,
        }
    }
}

/// Appearance supplied by the host application, in logical pixels.
#[derive(Clone, PartialEq)]
pub struct TerminalStyle {
    pub theme: TerminalTheme,
    /// An installed monospace family, or "monospace" for platform fallback.
    pub font_family: String,
    /// The type size the pane is measured, and offered to the remote side, at.
    pub font_size: f32,
    /// Draw a grid narrower than the pane (another viewer sized it) with type
    /// grown to fill the pane, up to this size; `None` letterboxes it. The
    /// pane is still measured and offered at `font_size`.
    pub grow_to_grid: Option<f32>,
}
