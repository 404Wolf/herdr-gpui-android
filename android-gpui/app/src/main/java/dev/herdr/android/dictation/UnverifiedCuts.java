package dev.herdr.android.dictation;

import java.util.Arrays;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * The desktop's {@code "unverified_cuts"} on a partial or final: where in its text it cut the
 * audio without a verified seam (only after about 90 s of speech with no pause it could check),
 * so a word there may be split. The composer draws a dim mark there; the text is never changed.
 */
public final class UnverifiedCuts {
    /** At most this many are kept from one message; more would crowd the draft. */
    public static final int MAX = 16;
    public static final int[] NONE = new int[0];

    private UnverifiedCuts() {}

    /**
     * The cuts in {@code message} as offsets into {@code text} (Java chars), in order. The
     * desktop counts code points; offsets at either end, past the text, repeated or not whole
     * numbers are dropped. None when the field is absent or not a list.
     */
    public static int[] of(JSONObject message, String text) {
        JSONArray in = message == null ? null : message.optJSONArray("unverified_cuts");
        if (in == null || text == null || text.isEmpty()) return NONE;
        int points = text.codePointCount(0, text.length());
        int[] out = new int[Math.min(in.length(), MAX)];
        int n = 0;
        for (int i = 0; i < in.length() && n < out.length; i++) {
            Object v = in.opt(i);
            if (!(v instanceof Integer || v instanceof Long)) continue;
            long at = ((Number) v).longValue();
            if (at <= 0 || at >= points) continue;
            out[n++] = text.offsetByCodePoints(0, (int) at);
        }
        out = Arrays.copyOf(out, n);
        Arrays.sort(out);
        int m = 0;
        for (int i = 0; i < out.length; i++) {
            if (m == 0 || out[m - 1] != out[i]) out[m++] = out[i];
        }
        return Arrays.copyOf(out, m);
    }
}
