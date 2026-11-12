//! Stand-ins for symbols the bundled terminal font cannot draw, so they never
//! show as empty boxes. Android offers no fallback font for these, and agents
//! draw them constantly: Claude Code's prompt `❯` and its spinner's dingbat
//! stars. Only painting uses a stand-in; the screen's text is unchanged, so
//! copying, links and prompt detection still see the program's own characters.

/// What to draw for `c`: itself, or a lookalike the font has.
pub fn stand_in(c: char) -> char {
    match c {
        '❯' | '❱' => '›',
        '❮' | '❰' => '‹',
        // Dingbat stars, asterisks and sparkles (Claude's spinner: ✢ ✳ ✶ ✻ ✽).
        '\u{2722}'..='\u{274B}' => '*',
        _ => c,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn agents_prompt_and_spinner_glyphs_get_drawable_stand_ins() {
        assert_eq!(stand_in('❯'), '›');
        assert_eq!(stand_in('❮'), '‹');
        for star in ['✢', '✳', '✶', '✻', '✽'] {
            assert_eq!(stand_in(star), '*', "{star}");
        }
        // What the font draws is left alone.
        for kept in ['›', '●', '─', 'a', '·', '⏵'] {
            assert_eq!(stand_in(kept), kept);
        }
    }
}
