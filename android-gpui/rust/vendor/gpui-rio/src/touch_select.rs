//! Touch selection on the terminal (factory-9zz.4): a long press selects the word under the
//! finger, two handles move either end, and the host shows Copy. The host owns the gesture
//! (nothing of it reaches the program); the view owns the grid and its geometry, so the cells
//! are found and drawn exactly where taps and links find them.

/// Which end of the selection a handle moves.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Handle {
    Start,
    End,
}

/// What the host is told after a selection change: where to draw its two handles (logical
/// pixels in the view's window, the bottom-left of the first cell and the bottom-right of the
/// last; None when that end is scrolled out of view) and the selected text.
#[derive(Clone, Debug, Default, PartialEq)]
pub struct Report {
    pub start: Option<(f32, f32)>,
    pub end: Option<(f32, f32)>,
    pub line_height: f32,
    pub text: String,
}

/// The most text a report carries (a whole screen and more of history is still well under it).
pub const MAX_TEXT_BYTES: usize = 256 * 1024;

/// `text` cut to [`MAX_TEXT_BYTES`] on a character boundary.
pub fn bounded(mut text: String) -> String {
    if text.len() > MAX_TEXT_BYTES {
        let mut cut = MAX_TEXT_BYTES;
        while !text.is_char_boundary(cut) {
            cut -= 1;
        }
        text.truncate(cut);
    }
    text
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn text_is_cut_on_a_character_boundary() {
        let long = "é".repeat(MAX_TEXT_BYTES);
        let cut = bounded(long);
        assert!(cut.len() <= MAX_TEXT_BYTES && cut.chars().all(|c| c == 'é'));
        assert_eq!(bounded("ab".into()), "ab");
    }
}
