package dev.herdr.android;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Where a dictation waiting for the desktop goes in a draft or a queued message:
 * {@code ⟦dictation <clip id>⟧}. The composer shows it as a chip ("0:12 waiting for desktop");
 * a message holding one is not sent until the clip's text replaces it.
 */
final class DictationToken {
    private DictationToken() {}

    static final Pattern PATTERN = Pattern.compile("⟦dictation ([0-9A-Za-z-]{1,64})⟧");

    static String of(String clipId) {
        return "⟦dictation " + clipId + "⟧";
    }

    static boolean in(String text) {
        return text != null && text.indexOf('⟦') >= 0 && PATTERN.matcher(text).find();
    }

    /** The clip ids in {@code text}, in order. */
    static List<String> ids(String text) {
        List<String> out = new ArrayList<>();
        if (text == null) return out;
        Matcher m = PATTERN.matcher(text);
        while (m.find()) out.add(m.group(1));
        return out;
    }

    /**
     * {@code text} with {@code clipId}'s token replaced by {@code spoken}, or null when it has no
     * such token. An empty dictation removes the token and the space it leaves.
     */
    static String fill(String text, String clipId, String spoken) {
        if (text == null) return null;
        String token = of(clipId);
        int at = text.indexOf(token);
        if (at < 0) return null;
        String before = text.substring(0, at);
        String after = text.substring(at + token.length());
        String words = spoken == null ? "" : spoken.trim();
        if (words.isEmpty()) {
            if (before.endsWith(" ") && (after.isEmpty() || after.startsWith(" "))) {
                before = before.substring(0, before.length() - 1);
            }
            return before + after;
        }
        boolean gapBefore = !before.isEmpty() && !Character.isWhitespace(before.charAt(before.length() - 1));
        boolean gapAfter = !after.isEmpty() && !Character.isWhitespace(after.charAt(0))
                && Character.isLetterOrDigit(after.codePointAt(0));
        return before + (gapBefore ? " " : "") + words + (gapAfter ? " " : "") + after;
    }
}
