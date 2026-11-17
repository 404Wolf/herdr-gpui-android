package dev.herdr.android;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import dev.herdr.android.dictation.DictationEngine;
import java.util.Collections;
import java.util.HashMap;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

/**
 * The desktop's unverified cuts (a long dictation cut without a verified seam): a dim mark in
 * the draft at the prompt, never in the text that is sent, kept only while the words before it
 * are unchanged.
 */
public final class CutMarksTest {
    private static final GhostText.Colors COLORS = new GhostText.Colors(
            0xffB2CCD6, 0xff4A4A4A, 0xffFFCB6B, 0xff000000, 0xffEEFFFF, 0xffF07178, 0xff82AAFF);

    /** The draft's spans as "text|style" pieces joined by " + ". */
    private static String drawn(String draft, GhostText.Marks marks, int[] cuts) throws Exception {
        JSONArray d = new JSONObject(GhostText.json("claude", draft, marks, cuts, new HashMap<>(),
                Collections.emptySet(), Collections.emptyList(), false, null, Integer.MAX_VALUE,
                Collections.emptyList(), COLORS)).getJSONArray("draft");
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < d.length(); i++) {
            JSONObject s = d.getJSONObject(i);
            out.append(i == 0 ? "" : " + ").append(s.getString("t")).append('|').append(s.getString("s"));
        }
        return out.toString();
    }

    @Test public void aCutIsADimMarkBeforeTheWordItSplitsNeverText() throws Exception {
        assertEquals("make the stop bu|text + ⁞|dim + tton|text",
                drawn("make the stop button", GhostText.Marks.NONE, new int[] {16}));
        // No cuts: the draft as before.
        assertEquals("make it|text", drawn("make it", GhostText.Marks.NONE, null));
    }

    @Test public void theMarkKeepsTheCaretSelectionAndRevisableWordsAroundIt() throws Exception {
        // The caret at the cut: the mark first, then the caret.
        assertEquals("ab|text + ⁞|dim + |caret + cd|text",
                drawn("abcd", GhostText.Marks.of(2, 2, -1, -1), new int[] {2}));
        // An engine's revisable words split by a cut stay revisable on both sides.
        assertEquals("go |text + ab|partial + ⁞|dim + cd|partial",
                drawn("go abcd", new GhostText.Marks(-1, -1, -1, 3, 7), new int[] {5}));
        // Inside a selection.
        assertEquals("a|text + b|selected + ⁞|dim + c|selected + d|text",
                drawn("abcd", GhostText.Marks.of(1, 3, -1, -1), new int[] {2}));
    }

    @Test public void aMarkNeverCutsAChipOrSitsAtTheEnds() throws Exception {
        String token = DictationToken.of("c-1");
        String draft = "see " + token + " now";
        int inside = draft.indexOf(token) + 3;
        String out = drawn(draft, GhostText.Marks.NONE, new int[] {0, inside, draft.length()});
        assertFalse(out, out.contains("⁞"));
    }

    @Test public void aTapOnTheMarkOrAfterItLandsOnTheRightCharacter() {
        // Drawn: "a b ⁞ c d" -> the mark is drawn character 2 and stands for offset 2.
        int[] offsets = GhostText.fieldOffsets("abcd", new int[] {2}, new HashMap<>(), Collections.emptySet());
        assertArrayEquals(new int[] {0, 1, 2, 2, 3, 4}, offsets);
        assertEquals(3, DraftTaps.fieldIndex(offsets, 4)); // "d" is drawn 5th, after the mark
        // Without cuts, as before.
        assertArrayEquals(new int[] {0, 1, 2, 3, 4},
                GhostText.fieldOffsets("abcd", new HashMap<>(), Collections.emptySet()));
    }

    @Test public void aLongDraftsMarkMovesWithItsElidedStart() throws Exception {
        String draft = "x".repeat(GhostText.MAX_CHARS + 10) + "ab";
        int cut = draft.length() - 1; // between "a" and "b"
        String out = drawn(draft, GhostText.Marks.NONE, new int[] {cut, 5});
        assertTrue(out, out.endsWith("a|text + ⁞|dim + b|text"));
        assertEquals("one mark: the elided one is dropped", 1, out.split("⁞", -1).length - 1);
        int[] offsets = GhostText.fieldOffsets(draft, new int[] {cut}, new HashMap<>(), Collections.emptySet());
        // "…", the shown characters, the mark before "b", the end.
        assertEquals(1 + GhostText.MAX_CHARS + 1 + 1, offsets.length);
        assertEquals(cut, offsets[offsets.length - 3]);
        assertEquals(cut, offsets[offsets.length - 2]);
    }

    // ---- the drafts keep the marks beside the text ------------------------------------------

    @Test public void aDictationsCutsLandWhereItsWordsLand() {
        ComposerDrafts drafts = new ComposerDrafts();
        drafts.switchTo("t1");
        drafts.edit("note:");
        drafts.dictationStarted();
        // "  make the stop bu|tton": two blanks trimmed, then a space after "note:".
        drafts.dictationPartial("  make the stop button", new int[] {18});
        assertEquals("note: make the stop button", drafts.text());
        assertArrayEquals(new int[] {6 + 16}, drafts.cuts());
        drafts.dictationFinal("  make the stop button bigger", new int[] {18});
        assertArrayEquals(new int[] {22}, drafts.cuts());
        // Never in what is sent.
        assertEquals("note: make the stop button bigger", drafts.outgoing());
    }

    @Test public void aMarkStaysWhileTheWordsBeforeItDoAndGoesWithThem() {
        ComposerDrafts drafts = new ComposerDrafts();
        drafts.switchTo("t1");
        drafts.dictationStarted();
        drafts.dictationFinal("abcdef", new int[] {3});
        drafts.edit("abcdef and more");
        assertArrayEquals("typing after it keeps it", new int[] {3}, drafts.cuts());
        drafts.edit("ab");
        assertArrayEquals("deleting back over it removes it", new int[0], drafts.cuts());
        drafts.edit("abXdef");
        assertArrayEquals("changing the words before it removes it", new int[0], drafts.cuts());
        drafts.edit("abcdef");
        assertArrayEquals("the same words again (an undo): back", new int[] {3}, drafts.cuts());
        drafts.sent();
        drafts.edit("abcdef");
        assertArrayEquals("sent: gone", new int[0], drafts.cuts());
    }

    @Test public void eachPaneKeepsItsOwnMarksAndACancelledDictationLeavesNone() {
        ComposerDrafts drafts = new ComposerDrafts();
        drafts.switchTo("t1");
        drafts.dictationStarted();
        drafts.dictationFinal("abcdef", new int[] {3});
        drafts.switchTo("t2");
        assertArrayEquals(new int[0], drafts.cuts());
        drafts.dictationStarted();
        drafts.dictationPartial("ghijkl", new int[] {2});
        assertArrayEquals(new int[] {2}, drafts.cuts());
        drafts.dictationCancelled();
        assertArrayEquals(new int[0], drafts.cuts());
        drafts.switchTo("t1");
        assertArrayEquals(new int[] {3}, drafts.cuts());
    }

    @Test public void aFinalForAnotherPaneCarriesItsCuts() {
        ComposerDrafts drafts = new ComposerDrafts();
        drafts.switchTo("t2");
        drafts.edit("hi");
        drafts.switchTo("t1");
        drafts.append("t2", "abcdef", new int[] {3});
        drafts.switchTo("t2");
        assertEquals("hi abcdef", drafts.text());
        assertArrayEquals(new int[] {6}, drafts.cuts());
    }

    @Test public void aChipFilledInEarlierMovesTheMarksAfterIt() {
        ComposerDrafts drafts = new ComposerDrafts();
        drafts.switchTo("t1");
        drafts.dictationStarted();
        drafts.dictationPending("c1");
        drafts.dictationStarted();
        drafts.dictationFinal("abcdef", new int[] {3});
        String token = DictationToken.of("c1");
        assertEquals(token + " abcdef", drafts.text());
        int before = drafts.cuts()[0];
        drafts.placeDictation("c1", "hello");
        assertEquals("hello abcdef", drafts.text());
        assertArrayEquals(new int[] {before - token.length() + "hello".length()}, drafts.cuts());
    }

    @Test public void aRolledBackChangeBringsBackTheMarksAsTheyWere() {
        ComposerDrafts drafts = new ComposerDrafts();
        drafts.switchTo("t1");
        drafts.dictationStarted();
        drafts.dictationPending("c1");
        drafts.dictationStarted();
        drafts.dictationFinal("abcdef", new int[] {3});
        int[] was = drafts.cuts();
        Object snapshot = drafts.snapshot();
        drafts.placeDictation("c1", "a much longer text");
        drafts.rollback(snapshot);
        assertArrayEquals(was, drafts.cuts());
    }

    // ---- the routing hands them on ----------------------------------------------------------

    @Test public void theRoutingPutsTheDesktopsCutsInTheDraftsAndNotTheText() {
        ComposerDrafts drafts = new ComposerDrafts();
        drafts.switchTo("t1");
        final String[] shown = {null};
        DictationRouting routing = new DictationRouting(drafts, new DictationRouting.Bar() {
            @Override public void showText(String text) { shown[0] = text; }
            @Override public void setDictationState(DictationEngine.State state) { }
            @Override public void enter() { }
            @Override public void notice(String text) { }
            @Override public void appendDictation(String pane, String text) { drafts.append(pane, text); }
            @Override public void clipLength(String clipId, long durationMs) { }
            @Override public void transcribedOnPhone() { }
        });
        drafts.dictationStarted();
        DictationEngine.Listener listener = routing.listener("t1");
        listener.onPartial("make the stop bu", new int[] {9});
        assertArrayEquals(new int[] {9}, drafts.cuts());
        listener.onFinal("make the stop button", false, new int[] {9});
        assertEquals("make the stop button", shown[0]);
        assertArrayEquals(new int[] {9}, drafts.cuts());
        // The phone's own recognizer sends no cuts: its partial replaces the desktop's, marks too.
        drafts.dictationStarted();
        listener = routing.listener("t1");
        listener.onPartial("make the stop button and more", new int[] {25});
        listener.onPartial("make the stop button and more");
        assertArrayEquals("the settled mark stays, the live one goes", new int[] {9}, drafts.cuts());
    }
}
