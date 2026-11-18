package dev.herdr.android;

import java.text.BreakIterator;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Text as the terminal grid shows it: in grapheme clusters (what one glyph is drawn for: a
 * letter and its combining accents, a flag, an emoji with its variation selector or a ZWJ
 * family), each one or two cells wide, as the renderer counts them (gpui-rio ghost.rs
 * {@code grapheme_cells}). Pure.
 */
final class TextCells {
    private TextCells() { }

    /** Where each cluster of {@code text} starts, in chars, then its length: never inside one. */
    static int[] boundaries(String text) {
        List<Integer> out = new ArrayList<>();
        BreakIterator it = BreakIterator.getCharacterInstance(Locale.ROOT);
        it.setText(text);
        out.add(0);
        for (int b = it.next(); b != BreakIterator.DONE; b = it.next()) {
            if (b >= text.length()) break;
            int before = text.codePointBefore(b), at = text.codePointAt(b);
            // A cluster an older break table splits: a ZWJ sequence, a variation selector, a skin
            // tone, a keycap, a combining mark, the second half of a flag.
            boolean joined = before == 0x200D || at == 0x200D || at == 0xFE0F || at == 0x20E3
                    || (at >= 0x1F3FB && at <= 0x1F3FF) || mark(at);
            if (regional(before) && regional(at)) {
                // Count the regional indicators since the last boundary kept.
                int riRun = 0;
                for (int i = out.get(out.size() - 1); i < b; i += Character.charCount(text.codePointAt(i))) {
                    if (regional(text.codePointAt(i))) riRun++;
                }
                if (riRun % 2 == 1) joined = true;
            }
            if (!joined) out.add(b);
        }
        out.add(text.length());
        int[] result = new int[out.size()];
        for (int i = 0; i < result.length; i++) result[i] = out.get(i);
        return result;
    }

    /** The first cluster boundary of {@code text} at or after {@code at}. */
    static int clusterEnd(String text, int at) {
        for (int b : boundaries(text)) if (b >= at) return b;
        return text.length();
    }

    /** {@code text}'s clusters, in order. */
    static List<String> clusters(String text) {
        int[] b = boundaries(text);
        List<String> out = new ArrayList<>();
        for (int i = 0; i + 1 < b.length; i++) if (b[i + 1] > b[i]) out.add(text.substring(b[i], b[i + 1]));
        return out;
    }

    /** The cells {@code text} takes in the grid. */
    static int cells(String text) {
        int n = 0;
        for (String c : clusters(text)) n += clusterCells(c);
        return n;
    }

    /** One cluster's cells: two for an emoji or an East Asian wide character, else one. */
    static int clusterCells(String cluster) {
        if (cluster.isEmpty()) return 0;
        if (cluster.indexOf('️') >= 0) return 2;
        int cp = cluster.codePointAt(0);
        if (regional(cp)) return 2;
        return wide(cp) ? 2 : 1;
    }

    private static boolean regional(int cp) {
        return cp >= 0x1F1E6 && cp <= 0x1F1FF;
    }

    private static boolean mark(int cp) {
        int type = Character.getType(cp);
        return type == Character.NON_SPACING_MARK || type == Character.ENCLOSING_MARK
                || type == Character.COMBINING_SPACING_MARK;
    }

    private static boolean wide(int cp) {
        return (cp >= 0x1100 && cp <= 0x115F) || (cp >= 0x231A && cp <= 0x231B) || (cp >= 0x23E9 && cp <= 0x23EC)
                || (cp >= 0x2E80 && cp <= 0x303E) || (cp >= 0x3041 && cp <= 0x33FF) || (cp >= 0x3400 && cp <= 0x4DBF)
                || (cp >= 0x4E00 && cp <= 0x9FFF) || (cp >= 0xA000 && cp <= 0xA4CF) || (cp >= 0xAC00 && cp <= 0xD7A3)
                || (cp >= 0xF900 && cp <= 0xFAFF) || (cp >= 0xFE30 && cp <= 0xFE4F) || (cp >= 0xFF00 && cp <= 0xFF60)
                || (cp >= 0xFFE0 && cp <= 0xFFE6) || (cp >= 0x1F300 && cp <= 0x1F64F)
                || (cp >= 0x1F680 && cp <= 0x1F6FF) || (cp >= 0x1F900 && cp <= 0x1F9FF)
                || (cp >= 0x1FA70 && cp <= 0x1FAFF) || (cp >= 0x20000 && cp <= 0x3FFFD);
    }
}
