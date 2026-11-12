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
                    MicButton.mix(chrome.background, chrome.onSurfaceVariant, 0.55f),
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
            if (text.length() > MAX_CHARS) text = "…" + text.substring(text.length() - MAX_CHARS);
            JSONArray spans = new JSONArray();
            if (partialFrom >= 0 && partialFrom <= text.length()) {
                spans(spans, text.substring(0, partialFrom), "text", lengths);
                spans(spans, text.substring(partialFrom), "partial", lengths);
            } else {
                spans(spans, text, "text", lengths);
            }
            o.put("draft", spans);
            JSONArray echoes = new JSONArray();
            int from = Math.max(0, queued.size() - MAX_ECHOES);
            for (Outbox.Message message : queued.subList(from, queued.size())) {
                JSONArray echo = new JSONArray();
                spans(echo, QueuedMessages.PROMPT + QueuedMessages.oneLine(message.text), "echo", lengths);
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

    /** {@code text} as spans of {@code style}, each dictation token a chip. */
    private static void spans(JSONArray out, String text, String style, Map<String, Long> lengths)
            throws JSONException {
        Matcher m = DictationToken.PATTERN.matcher(text);
        int at = 0;
        while (m.find()) {
            if (m.start() > at) out.put(span(text.substring(at, m.start()), style));
            // Brackets, in the terminal's own font: it has no clock glyph.
            out.put(span("[" + DraftChips.label(lengths.get(m.group(1))) + "]", "chip"));
            at = m.end();
        }
        if (at < text.length()) out.put(span(text.substring(at), style));
    }

    private static JSONObject span(String text, String style) throws JSONException {
        return new JSONObject().put("t", text).put("s", style);
    }
}
