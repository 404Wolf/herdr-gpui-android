package dev.herdr.android;

import java.nio.charset.StandardCharsets;

/**
 * What a finished text selection puts on the clipboard and says
 * (factory-9zz.4), shared by the terminal's selection and the panels'
 * {@link SelectableText}: at most {@link #CAP_BYTES} of UTF-8, cut at a
 * character boundary; "Copied 3 lines" or "Copied 12 characters"; nothing said
 * for the same text again. Pure: no clipboard, no clock.
 */
final class SelectionCopy {
    private SelectionCopy() { }

    /** Binder's transaction limit is about 1 MB: a clip stays well under it. */
    static final int CAP_BYTES = 512 * 1024;
    /** The clipboard label every Herdr copy uses. */
    static final String LABEL = "Herdr";

    /** One copy: the text for the clipboard, and whether it was cut to the cap. */
    static final class Clip {
        final String text;
        final boolean cut;

        Clip(String text, boolean cut) {
            this.text = text;
            this.cut = cut;
        }

        /** "Copied 3 lines", "Copied 12 characters", "Copied (cut to 512 KiB)". */
        String said() {
            if (cut) return "Copied (cut to " + (CAP_BYTES / 1024) + " KiB)";
            int lines = lines(text);
            if (lines > 1) return "Copied " + lines + " lines";
            int chars = text.codePointCount(0, text.length());
            return "Copied " + chars + (chars == 1 ? " character" : " characters");
        }
    }

    /** {@code text} as a clip: whole when it fits, else its longest prefix within the cap (never half a character). */
    static Clip of(String text) {
        String t = text == null ? "" : text;
        if (t.length() * 3L <= CAP_BYTES || t.getBytes(StandardCharsets.UTF_8).length <= CAP_BYTES) return new Clip(t, false);
        int bytes = 0, end = 0;
        while (end < t.length()) {
            int cp = t.codePointAt(end);
            int size = cp < 0x80 ? 1 : cp < 0x800 ? 2 : cp < 0x10000 ? 3 : 4;
            if (bytes + size > CAP_BYTES) break;
            bytes += size;
            end += Character.charCount(cp);
        }
        return new Clip(t.substring(0, end), true);
    }

    /** Its lines: a final line break ends the last line, it does not start another. */
    static int lines(String text) {
        if (text.isEmpty()) return 0;
        int n = 1;
        for (int i = 0; i < text.length(); i++) if (text.charAt(i) == '\n' && i < text.length() - 1) n++;
        return n;
    }

    /**
     * When a selection that keeps changing has settled: the copy waits
     * {@link #SETTLE_MS} after the last change (handle drags run in the
     * Editor's own windows, so the text view never sees their lift).
     */
    static final class Settle {
        static final long SETTLE_MS = 600;
        private String pending;
        private long dueMs;
        private String last;

        /** The selection now ({@code selected} empty: none) at {@code nowMs}. */
        void changed(String selected, long nowMs) {
            // Blank (a long press on spaces) is no selection: nothing is copied.
            pending = selected == null || selected.trim().isEmpty() ? null : selected;
            dueMs = nowMs + SETTLE_MS;
        }

        /** The text to copy now, or null: nothing settled yet, nothing selected, or the same as the last copy. */
        String due(long nowMs) {
            if (pending == null || nowMs < dueMs) return null;
            return take();
        }

        /** The selection ends (its toolbar closed): copy what was selected at once, unless just copied. */
        String ended() {
            return pending == null ? null : take();
        }

        private String take() {
            String text = pending;
            pending = null;
            if (text.equals(last)) return null;
            last = text;
            return text;
        }
    }
}
