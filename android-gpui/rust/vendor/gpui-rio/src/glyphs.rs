//! Stand-ins for symbols the bundled terminal font cannot draw, so they never
//! show as empty boxes. Agents draw them constantly. Two groups show as boxes
//! on the phone: the Dingbats block (Claude's prompt `❯`, its spinner's stars,
//! check and cross marks), which the system's symbol font has but does not
//! draw for us, and the media and connector symbols no system font has at all
//! (Claude's `⏵⏵` mode marker, `⏺` tool bullet and `⎿` output connector).
//! Each stand-in is a single-cell glyph Noto Mono has, or box drawing, which
//! draws. Only painting uses a stand-in; the screen's text is unchanged, so
//! copying, links and prompt detection still see the program's own characters.

/// What to draw for `c`: itself, or a lookalike the font has.
pub fn stand_in(c: char) -> char {
    match c {
        '❯' | '❱' | '⏵' => '›',
        '❮' | '❰' | '⏴' => '‹',
        '⏶' => '^',
        '⏷' => 'v',
        '⏺' => '•',
        '⏸' => '¦',
        '⏹' => '#',
        '⎿' => '└',
        '✓' | '✔' => '√',
        '✕' | '✖' | '✗' | '✘' => '×',
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
        // Claude's mode marker, tool bullet and output connector.
        assert_eq!("⏵⏵".chars().map(stand_in).collect::<String>(), "››");
        assert_eq!(stand_in('⏺'), '•');
        assert_eq!(stand_in('⎿'), '└');
        assert_eq!(stand_in('✓'), '√');
        assert_eq!(stand_in('✗'), '×');
        // What draws is left alone, box drawing included.
        for kept in ['›', '●', '─', '└', 'a', '·', '←', '⚠'] {
            assert_eq!(stand_in(kept), kept);
        }
    }
}
