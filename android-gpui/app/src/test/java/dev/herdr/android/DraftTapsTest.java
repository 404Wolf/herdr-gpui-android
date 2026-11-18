package dev.herdr.android;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import org.junit.Test;

/** A tap on the draft drawn at the prompt puts the cursor there. */
public final class DraftTapsTest {
    // "fix the build and rerun" wrapped at the prompt: row 3 from col 2
    // ("fix the build ", 14 cells), row 4 from the indent ("and rerun").
    private static final int[][] RUNS = {{3, 2, 14, 0}, {4, 2, 9, 14}};

    @Test public void aTapOnAWordGoesToTheNearerEdgeOfItsCell() {
        // "fix |the": cell 6 is "t", tapped on its left half.
        assertEquals(4, DraftTaps.drawnAt(RUNS, 3, 6.2f));
        // Its right half: after the "t".
        assertEquals(5, DraftTaps.drawnAt(RUNS, 3, 6.7f));
        // The wrapped row: "and |rerun".
        assertEquals(18, DraftTaps.drawnAt(RUNS, 4, 6.1f));
    }

    @Test public void offTheWordsTheRowsNearestEndAndNoRowNoCursor() {
        // Past "rerun": the end of the draft.
        assertEquals(23, DraftTaps.drawnAt(RUNS, 4, 30f));
        // Left of the prompt's text (on "❯ "): its start.
        assertEquals(0, DraftTaps.drawnAt(RUNS, 3, 0.4f));
        // A row with none of the draft: not a tap on it.
        assertEquals(-1, DraftTaps.drawnAt(RUNS, 5, 4f));
        assertEquals(-1, DraftTaps.drawnAt(new int[0][], 3, 4f));
    }

    @Test public void drawnCharactersMapBackToTheTypedText() {
        String token = DictationToken.of("c-1");
        Map<String, Long> lengths = new HashMap<>();
        lengths.put("c-1", 9_000L);
        String draft = "a " + token + " b";
        int[] offsets = GhostText.fieldOffsets(draft, lengths, Collections.emptySet());
        // "a " then the chip's label, then " b", then the end.
        String label = "[" + DraftChips.label(9_000L, false) + "]";
        assertEquals(2 + label.length() + 2 + 1, offsets.length);
        assertEquals(1, offsets[1]);
        // Anywhere on the chip: after its token.
        int after = draft.indexOf(token) + token.length();
        assertEquals(after, offsets[2]);
        assertEquals(after, offsets[2 + label.length() - 1]);
        assertEquals(draft.length(), offsets[offsets.length - 1]);
        // An emoji is one cell but two UTF-16 units.
        assertArrayEquals(new int[] {0, 2, 3}, GhostText.fieldOffsets("😀x", lengths, Collections.emptySet()));
        assertEquals(3, DraftTaps.fieldIndex(new int[] {0, 2, 3}, 9));
        assertEquals(-1, DraftTaps.fieldIndex(new int[] {0, 2, 3}, -1));
    }

    @Test public void aDraftCutForLengthMapsItsEllipsisToWhereItsWordsStart() {
        String draft = "x".repeat(GhostText.MAX_CHARS + 5);
        int[] offsets = GhostText.fieldOffsets(draft, new HashMap<>(), Collections.emptySet());
        assertEquals(5, offsets[0]);
        assertEquals(5, offsets[1]);
        assertEquals(draft.length(), offsets[offsets.length - 1]);
    }

    @Test public void theHintCarriesTheDraftsRuns() {
        ClusterPlacement.Hint hint = ClusterPlacement.Hint.parse("{\"left\":0,\"top\":0,\"cellWidth\":8,"
                + "\"cellHeight\":16,\"cols\":40,\"rows\":2,\"busy\":[0,0],\"busyFrom\":0,"
                + "\"draft\":[[1,2,5,0]]}");
        // An older renderer's four fields: one character per cell.
        assertArrayEquals(new int[] {1, 2, 5, 0, 5, 0}, hint.draftRuns[0]);
        assertArrayEquals(new int[] {1, 2, 7}, hint.draftSpans[0]);
        ClusterPlacement.Hint wide = ClusterPlacement.Hint.parse("{\"left\":0,\"top\":0,\"cellWidth\":8,"
                + "\"cellHeight\":16,\"cols\":40,\"rows\":2,\"busy\":[0,0],\"busyFrom\":0,"
                + "\"draft\":[[1,2,2,0,1]]}");
        assertArrayEquals(new int[] {1, 2, 2, 0, 1, 1}, wide.draftRuns[0]);
        assertEquals(null, ClusterPlacement.Hint.parse("{\"left\":0,\"top\":0,\"cellWidth\":8,"
                + "\"cellHeight\":16,\"cols\":40,\"rows\":2,\"busy\":[0,0],\"draft\":[[1,2]]}"));
    }
}
