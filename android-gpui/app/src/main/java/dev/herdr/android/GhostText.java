package dev.herdr.android;

import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * The JSON the renderer draws as ghost text (ghost_bridge.rs): the pane's
 * unsent draft at the program's caret, a dictation's revisable tail
 * underlined, recordings waiting for the desktop as chips, and the pane's
 * queued messages above the prompt. Pure: no views.
 */
final class GhostText {
    /** Queued messages shown above the prompt; the renderer keeps the newest that fit. */
    static final int MAX_ECHOES = 6;
    /** The renderer refuses more; the composer's own bound is far lower. */
    static final int MAX_CHARS = 16_000;

    /** Colours as 0xRRGGBB, from the chrome. */
    static final class Colors {
        final int text, dim, warn, background, bright, danger, info;

        Colors(int text, int dim, int warn, int background, int bright, int danger, int info) {
            this.text = text & 0xffffff;
            this.dim = dim & 0xffffff;
            this.warn = warn & 0xffffff;
            this.background = background & 0xffffff;
            this.bright = bright & 0xffffff;
            this.danger = danger & 0xffffff;
            this.info = info & 0xffffff;
        }

        static Colors of(GpuiChrome chrome) {
            return new Colors(chrome.onSurfaceVariant,
                    GpuiChrome.mix(chrome.background, chrome.onSurfaceVariant, 0.55f),
                    chrome.warning, chrome.background, chrome.onSurface, chrome.danger, chrome.info);
        }
    }

    /** One of the controls drawn in the prompt's frame: an id and its words. */
    static final class Control {
        final String id;
        /** Pairs of (text, style): "dim", "bright", "danger", "info" or "warn". */
        final String[] spans;

        Control(String id, String... spans) {
            this.id = id;
            this.spans = spans;
        }
    }

    private GhostText() { }

    /** The prompt kind the renderer looks for: "claude", "codex" or "other". */
    static String kind(String agent) {
        if ("claude".equals(agent)) return "claude";
        if ("codex".equals(agent)) return "codex";
        return "other";
    }

    /**
     * @param partialFrom where an engine's revisable words start in {@code draft}, or -1
     * @param lengths recording lengths by clip id, for chips
     * @param reserve columns kept clear on rows from {@code reserveFrom} down
     */
    static String json(String kind, String draft, int partialFrom, Map<String, Long> lengths,
            List<Outbox.Message> queued, String pane, List<Control> controls, Colors colors) {
        return json(kind, draft, partialFrom, lengths, java.util.Collections.emptySet(), queued, pane, controls, colors);
    }

    /** @param parked recordings the desktop gave up on: their chips read "not transcribed yet · retry" */
    static String json(String kind, String draft, int partialFrom, Map<String, Long> lengths,
            java.util.Set<String> parked, List<Outbox.Message> queued, String pane, List<Control> controls,
            Colors colors) {
        String text = draft == null ? "" : draft;
        Marks marks = partialFrom >= 0 && partialFrom <= text.length()
                ? new Marks(-1, -1, -1, partialFrom, text.length()) : Marks.NONE;
        return json(kind, draft, marks, lengths, parked, queued, pane, controls, colors);
    }

    /**
     * Where the keyboard is in the draft, in its characters: the cursor, the
     * selection and the composing (underlined) word. Each is drawn in the
     * draft at the prompt, as a text field draws them. -1 for none.
     */
    static final class Marks {
        static final Marks NONE = new Marks(-1, -1, -1, -1, -1);
        final int caret, selectionStart, selectionEnd, composingStart, composingEnd;

        Marks(int caret, int selectionStart, int selectionEnd, int composingStart, int composingEnd) {
            this.caret = caret;
            this.selectionStart = Math.min(selectionStart, selectionEnd);
            this.selectionEnd = Math.max(selectionStart, selectionEnd);
            this.composingStart = Math.min(composingStart, composingEnd);
            this.composingEnd = Math.max(composingStart, composingEnd);
        }

        /** A text field's marks: the bar caret only while nothing is selected. */
        static Marks of(int selectionStart, int selectionEnd, int composingStart, int composingEnd) {
            boolean selected = selectionStart >= 0 && selectionStart != selectionEnd;
            return new Marks(selected || selectionEnd < 0 ? -1 : selectionEnd,
                    selected ? selectionStart : -1, selected ? selectionEnd : -1,
                    composingStart >= 0 && composingStart != composingEnd ? composingStart : -1,
                    composingStart >= 0 && composingStart != composingEnd ? composingEnd : -1);
        }

        /** The marks after the draft's first {@code dropped} characters became one "…". */
        Marks shifted(int dropped) {
            if (dropped <= 0) return this;
            return new Marks(shift(caret, dropped), shift(selectionStart, dropped), shift(selectionEnd, dropped),
                    shift(composingStart, dropped), shift(composingEnd, dropped));
        }

        private static int shift(int at, int dropped) {
            return at < 0 ? -1 : Math.max(1, at - dropped + 1);
        }
    }

    /**
     * The draft's runs: (text, style) pairs in order, "selected" over
     * "partial" over "text", and an empty "caret" run where the cursor is.
     * A dictation token is never cut by a mark: marks inside one move to its end.
     */
    static List<String[]> runs(String text, Marks marks) {
        int length = text.length();
        java.util.TreeSet<Integer> cuts = new java.util.TreeSet<>();
        cuts.add(0);
        cuts.add(length);
        int caret = snap(text, marks.caret);
        int ss = snap(text, marks.selectionStart), se = snap(text, marks.selectionEnd);
        int cs = snap(text, marks.composingStart), ce = snap(text, marks.composingEnd);
        for (int cut : new int[] {caret, ss, se, cs, ce}) if (cut >= 0) cuts.add(cut);
        List<String[]> out = new java.util.ArrayList<>();
        Integer[] at = cuts.toArray(new Integer[0]);
        for (int i = 0; i < at.length; i++) {
            if (at[i] == caret) out.add(new String[] {"", "caret"});
            if (i + 1 == at.length) break;
            int from = at[i], to = at[i + 1];
            if (from == to) continue;
            String style = ss >= 0 && from >= ss && to <= se ? "selected"
                    : cs >= 0 && from >= cs && to <= ce ? "partial" : "text";
            out.add(new String[] {text.substring(from, to), style});
        }
        return out;
    }

    /** {@code at} clamped to the text, moved out of a dictation token it falls in. */
    private static int snap(String text, int at) {
        if (at < 0) return -1;
        at = Math.min(at, text.length());
        Matcher m = DictationToken.PATTERN.matcher(text);
        while (m.find()) {
            if (at > m.start() && at < m.end()) return m.end();
        }
        return at;
    }

    static String json(String kind, String draft, Marks marks, Map<String, Long> lengths,
            java.util.Set<String> parked, List<Outbox.Message> queued, String pane, List<Control> controls,
            Colors colors) {
        try {
            JSONObject o = new JSONObject();
            o.put("kind", kind);
            o.put("colors", new JSONObject()
                    .put("text", colors.text).put("dim", colors.dim)
                    .put("warn", colors.warn).put("background", colors.background)
                    .put("bright", colors.bright).put("danger", colors.danger).put("info", colors.info));
            JSONArray drawn = new JSONArray();
            for (Control control : controls) {
                JSONArray spans = new JSONArray();
                for (int i = 0; i + 1 < control.spans.length; i += 2) {
                    if (!control.spans[i].isEmpty()) spans.put(span(control.spans[i], control.spans[i + 1]));
                }
                drawn.put(new JSONObject().put("id", control.id).put("spans", spans));
            }
            o.put("controls", drawn);
            String text = draft == null ? "" : draft;
            if (text.length() > MAX_CHARS) {
                marks = marks.shifted(text.length() - MAX_CHARS);
                text = "…" + text.substring(text.length() - MAX_CHARS);
            }
            JSONArray spans = new JSONArray();
            for (String[] run : runs(text, marks)) {
                if (run[1].equals("caret")) spans.put(span("", "caret"));
                else spans(spans, run[0], run[1], lengths, parked);
            }
            o.put("draft", spans);
            JSONArray echoes = new JSONArray();
            int from = Math.max(0, queued.size() - MAX_ECHOES);
            for (Outbox.Message message : queued.subList(from, queued.size())) {
                JSONArray echo = new JSONArray();
                spans(echo, QueuedMessages.PROMPT + QueuedMessages.oneLine(message.text), "echo", lengths, parked);
                boolean unsure = message.state == Outbox.State.UNSURE || message.state == Outbox.State.GONE;
                echo.put(span("  " + QueuedMessages.label(message, pane), unsure ? "warn" : "tag"));
                echoes.put(echo);
            }
            o.put("echoes", echoes);
            return o.toString();
        } catch (JSONException impossible) {
            return "";
        }
    }

    /**
     * For each character of the draft as drawn ({@link #json}: a cut draft's
     * "…", a chip's label, counted in code points as the renderer counts
     * cells), where it is in the typed text {@code draft}; one more entry for
     * the end. A tap on a chip puts the cursor after it.
     */
    static int[] fieldOffsets(String draft, Map<String, Long> lengths, java.util.Set<String> parked) {
        String text = draft == null ? "" : draft;
        int base = 0;
        java.util.ArrayList<Integer> out = new java.util.ArrayList<>();
        if (text.length() > MAX_CHARS) {
            base = text.length() - MAX_CHARS;
            out.add(base);
            text = text.substring(base);
        }
        Matcher m = DictationToken.PATTERN.matcher(text);
        int at = 0;
        while (true) {
            boolean found = m.find();
            int plainEnd = found ? m.start() : text.length();
            for (int i = at; i < plainEnd; i = text.offsetByCodePoints(i, 1)) out.add(base + i);
            if (!found) break;
            String label = "[" + DraftChips.label(lengths.get(m.group(1)), parked.contains(m.group(1))) + "]";
            for (int i = 0, n = label.codePointCount(0, label.length()); i < n; i++) out.add(base + m.end());
            at = m.end();
        }
        out.add(base + text.length());
        int[] offsets = new int[out.size()];
        for (int i = 0; i < offsets.length; i++) offsets[i] = out.get(i);
        return offsets;
    }

    /** {@code text} as spans of {@code style}, each dictation token a chip. */
    private static void spans(JSONArray out, String text, String style, Map<String, Long> lengths,
            java.util.Set<String> parked) throws JSONException {
        Matcher m = DictationToken.PATTERN.matcher(text);
        int at = 0;
        while (m.find()) {
            if (m.start() > at) out.put(span(text.substring(at, m.start()), style));
            // Brackets, in the terminal's own font: it has no clock glyph.
            out.put(span("[" + DraftChips.label(lengths.get(m.group(1)), parked.contains(m.group(1))) + "]", "chip"));
            at = m.end();
        }
        if (at < text.length()) out.put(span(text.substring(at), style));
    }

    private static JSONObject span(String text, String style) throws JSONException {
        return new JSONObject().put("t", text).put("s", style);
    }
}
