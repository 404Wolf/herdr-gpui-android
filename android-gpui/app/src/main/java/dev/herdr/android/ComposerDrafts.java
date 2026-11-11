package dev.herdr.android;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The composer's local text, one draft per pane, so switching panes never loses
 * what was typed. Pure state with no views: editing never waits on the link.
 *
 * <p>Dictation joins the draft without owning it: {@link #dictationPartial}
 * replaces only the utterance in progress (engines send the whole utterance so
 * far, not a delta), and {@link #dictationFinal} commits it.
 */
final class ComposerDrafts {
    /** Drafts kept for panes not on screen; the oldest is dropped beyond this. */
    static final int MAX_PANES = 32;
    /** A draft longer than this is not saved (it still lives while the app does). */
    static final int MAX_SAVED_CHARS = 64 * 1024;

    private final Map<String, String> drafts = new LinkedHashMap<String, String>(16, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
            return size() > MAX_PANES;
        }
    };
    private String pane = "";
    /** The draft before the utterance in progress, or null when not dictating. */
    private String dictationBase;

    /** Shows {@code next}'s draft, keeping the current one for later. */
    String switchTo(String next) {
        String target = next == null ? "" : next;
        if (target.equals(pane)) return text();
        dictationBase = null;
        pane = target;
        return text();
    }

    String pane() { return pane; }

    String text() {
        String text = drafts.get(pane);
        return text == null ? "" : text;
    }

    /** The field changed under the user's fingers. */
    void edit(String text) {
        if (text == null || text.isEmpty()) drafts.remove(pane);
        else drafts.put(pane, text);
    }

    void dictationStarted() { dictationBase = text(); }

    boolean dictating() { return dictationBase != null; }

    /** The whole utterance so far; it replaces the previous partial. */
    String dictationPartial(String utterance) {
        if (dictationBase == null) dictationBase = text();
        edit(join(dictationBase, utterance));
        return text();
    }

    String dictationFinal(String utterance) {
        String result = dictationPartial(utterance);
        dictationBase = null;
        return result;
    }

    /**
     * The utterance ended without a final (an error, or cancelled by a mode or
     * pane change). The last partial stays in the draft: spoken words are never
     * thrown away, and the next utterance builds on them.
     */
    String dictationEnded() {
        dictationBase = null;
        return text();
    }

    /**
     * What Enter sends: the draft without surrounding blank lines, or null when
     * there is nothing to send and Enter should reach the program as a key.
     * The draft is not cleared until {@link #sent} confirms it was queued.
     */
    String outgoing() {
        String text = text().replaceAll("^\\s+|\\s+$", "");
        return text.isEmpty() ? null : text;
    }

    void sent() {
        drafts.remove(pane);
        dictationBase = null;
    }

    /** Each pane's draft, oldest first, for saving; at most {@link #MAX_PANES}. */
    String encode() {
        org.json.JSONObject out = new org.json.JSONObject();
        try {
            for (Map.Entry<String, String> draft : drafts.entrySet()) {
                if (draft.getValue().length() <= MAX_SAVED_CHARS) out.put(draft.getKey(), draft.getValue());
            }
        } catch (org.json.JSONException impossible) {
            return "{}";
        }
        return out.toString();
    }

    /** Restores what {@link #encode} saved, under drafts already typed since; a damaged value restores nothing. */
    void restore(String saved) {
        if (saved == null || saved.isEmpty()) return;
        Map<String, String> typed = new LinkedHashMap<>(drafts);
        try {
            org.json.JSONObject in = new org.json.JSONObject(saved);
            for (java.util.Iterator<String> keys = in.keys(); keys.hasNext(); ) {
                String key = keys.next();
                String text = in.optString(key, "");
                if (!key.isEmpty() && !text.isEmpty() && text.length() <= MAX_SAVED_CHARS) drafts.put(key, text);
            }
        } catch (org.json.JSONException damaged) {
            return;
        }
        drafts.putAll(typed);
    }

    static String join(String base, String utterance) {
        String spoken = utterance == null ? "" : utterance.trim();
        if (base == null || base.isEmpty()) return spoken;
        if (spoken.isEmpty()) return base;
        return Character.isWhitespace(base.charAt(base.length() - 1)) ? base + spoken : base + " " + spoken;
    }
}
