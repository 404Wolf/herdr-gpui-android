package dev.herdr.android;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What the phone's terminal grid draws, for the words the composer puts on Claude's line: not a
 * font's coverage, but a hand-checked list. {@link #SEEN} are the symbols seen drawn on the
 * x86_64 emulator ("▲" and "◌" come from Android's own fallback fonts, Noto Sans CJK, not the
 * bundled Noto Mono, which has neither); "▴", "▸", "▾" and "↻" showed as empty boxes there.
 * The renderer's stand-ins (gpui-rio glyphs.rs: "❯" drawn as "›", "✓" as "√", "✕" as "×"...)
 * are read from glyphs.rs itself, so they cannot drift. A check against the bundled fonts' cmap
 * would refuse most of what draws, so it is not one.
 */
final class GridGlyphs {
    static final String SEEN = "·…↓≡─◌●▲";
    /** gpui-rio's glyph stand-ins, relative to the app module (Gradle's and the plain run's directory). */
    static final Path GLYPHS_RS = Paths.get("../rust/vendor/gpui-rio/src/glyphs.rs");
    static final Set<Integer> STAND_INS = standIns();

    private GridGlyphs() {}

    /** The characters of {@code text} the grid would draw as boxes, or "" when it draws all. */
    static String boxes(String text) {
        StringBuilder out = new StringBuilder();
        text.codePoints().forEach(c -> {
            if ((c >= 0x20 && c < 0x7f) || SEEN.indexOf(c) >= 0 || STAND_INS.contains(c)) return;
            out.appendCodePoint(c);
        });
        return out.toString();
    }

    /** Every character {@code stand_in} maps to something else: the arms of its match. */
    private static Set<Integer> standIns() {
        String rs;
        try {
            rs = new String(Files.readAllBytes(GLYPHS_RS), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("gpui-rio's glyphs.rs is not at " + GLYPHS_RS.toAbsolutePath(), e);
        }
        String body = rs.substring(rs.indexOf("pub fn stand_in"), rs.indexOf("_ => c"));
        Set<Integer> out = new HashSet<>();
        Matcher range = Pattern.compile("'\\\\u\\{([0-9A-Fa-f]+)\\}'\\.\\.='\\\\u\\{([0-9A-Fa-f]+)\\}'\\s*=>").matcher(body);
        while (range.find()) {
            for (int c = Integer.parseInt(range.group(1), 16); c <= Integer.parseInt(range.group(2), 16); c++) out.add(c);
        }
        for (String arm : body.split("\n")) {
            int arrow = arm.indexOf("=>");
            if (arrow < 0 || arm.contains("..=")) continue;
            Matcher one = Pattern.compile("'([^'\\\\])'").matcher(arm.substring(0, arrow));
            while (one.find()) out.add(one.group(1).codePointAt(0));
        }
        if (out.isEmpty()) throw new IllegalStateException("no stand-ins read from " + GLYPHS_RS);
        return out;
    }
}
