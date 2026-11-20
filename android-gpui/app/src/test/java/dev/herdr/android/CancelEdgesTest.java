package dev.herdr.android;

import static org.junit.Assert.assertEquals;

import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

/**
 * Cancel against every single insert and delete around a dictated span: it never removes a typed
 * character, and removes every dictated one (and the separator that joined them) unless the
 * dictated words themselves were edited, in which case nothing is destroyed.
 */
public final class CancelEdgesTest {
    private static final char TYPED = 'T', SEPARATOR = 'S', DICTATED = 'D';

    /** The draft "seed fixture partial": labels per character. */
    private static List<Character> labels(String base, String spoken) {
        List<Character> out = new ArrayList<>();
        for (int i = 0; i < base.length(); i++) out.add(TYPED);
        out.add(SEPARATOR);
        for (int i = 0; i < spoken.length(); i++) out.add(DICTATED);
        return out;
    }

    private static String expected(String text, List<Character> after, boolean tainted) {
        if (tainted) return text;
        StringBuilder out = new StringBuilder();
        int firstD = after.indexOf(DICTATED);
        for (int i = 0; i < text.length(); i++) {
            char label = after.get(i);
            if (label == DICTATED) continue;
            if (label == SEPARATOR && firstD == i + 1) continue;
            out.append(text.charAt(i));
        }
        return out.toString();
    }

    private static ComposerDrafts dictating(String base, String spoken) {
        ComposerDrafts drafts = new ComposerDrafts();
        drafts.switchTo("t1");
        drafts.edit(base);
        drafts.dictationStarted();
        drafts.dictationPartial(spoken);
        return drafts;
    }

    @Test public void cancelAfterAnySingleEditAroundTheSpanTouchesNoTypedCharacter() {
        String base = "seed", spoken = "fixture partial";
        String text = base + " " + spoken;
        List<Character> start = labels(base, spoken);
        int firstD = start.indexOf(DICTATED), lastD = start.lastIndexOf(DICTATED);
        for (int i = 0; i <= text.length(); i++) {
            // Insert 'z' at i.
            ComposerDrafts drafts = dictating(base, spoken);
            String inserted = text.substring(0, i) + "z" + text.substring(i);
            drafts.edit(inserted);
            List<Character> after = new ArrayList<>(start);
            after.add(i, TYPED);
            boolean interior = i > firstD && i <= lastD;
            assertEquals("insert at " + i + " in " + inserted, expected(inserted, after, interior),
                    drafts.dictationCancelled());
        }
        for (int i = 0; i < text.length(); i++) {
            // Delete the character at i.
            ComposerDrafts drafts = dictating(base, spoken);
            String deleted = text.substring(0, i) + text.substring(i + 1);
            drafts.edit(deleted);
            List<Character> after = new ArrayList<>(start);
            char gone = after.remove(i);
            boolean hitDictated = gone == DICTATED;
            assertEquals("delete at " + i + " in " + deleted, expected(deleted, after, hitDictated),
                    drafts.dictationCancelled());
        }
    }

    @Test public void theReviewersCase_zTypedRightBeforeThePartialStaysAfterCancel() {
        ComposerDrafts drafts = dictating("seed", "fixture partial");
        drafts.edit("seed zfixture partial");
        assertEquals("seed z", drafts.dictationCancelled());
    }
}
