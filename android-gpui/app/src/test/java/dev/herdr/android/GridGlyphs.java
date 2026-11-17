package dev.herdr.android;

/**
 * The symbols the phone's terminal grid draws: Noto Mono's, plus the renderer's
 * stand-ins (gpui-rio's glyphs.rs draws ✓ as √ and ✕/✗ as ×). Checked on the
 * x86_64 emulator; "▴", "▸", "▾" and "↻" showed as empty boxes there. Words the
 * composer draws on Claude's line may use only these and printable ASCII.
 */
final class GridGlyphs {
    static final String DRAWN = "·…↓≡─◌●▲✓✕✗";

    private GridGlyphs() {}

    /** The characters of {@code text} the grid would draw as boxes, or "" when it draws all. */
    static String boxes(String text) {
        StringBuilder out = new StringBuilder();
        text.codePoints().forEach(c -> {
            if ((c >= 0x20 && c < 0x7f) || DRAWN.indexOf(c) >= 0) return;
            out.appendCodePoint(c);
        });
        return out.toString();
    }
}
