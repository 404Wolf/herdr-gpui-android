package dev.herdr.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.Collections;
import java.util.List;
import org.junit.Test;

/** A multi-line draft is spread by the renderer; the host's targets follow the drawn rows. */
public final class MultilineSpacingTest {
    private static ClusterPlacement.Hint hint(String extra) {
        return ClusterPlacement.Hint.parse(
                "{\"left\":0,\"top\":30,\"cellWidth\":6.5,\"cellHeight\":16,\"cols\":72,\"rows\":40,"
                        + "\"anchored\":true,\"guard\":38,\"busyFrom\":38,\"busy\":[0,0]" + extra + "}");
    }

    @Test public void rowsStayUniformWithoutASpreadDraft() {
        ControlTargets.Rows rows = hint("").rows();
        assertEquals(30 + 5 * 16f, rows.top(5), 0.001f);
        assertEquals(5, rows.rowAt(30 + 5 * 16f + 1));
    }

    @Test public void rowsBelowTheDraftsFirstRowNeverMoveWhateverTheScroll() {
        ClusterPlacement.Hint h = hint(",\"blockFirst\":36,\"blockRows\":3,\"rowGap\":4,\"rowShift\":16");
        assertNotNull(h);
        ControlTargets.Rows rows = h.rows();
        for (int row = 37; row < 40; row++) assertEquals(30 + row * 16f, rows.top(row), 0.001f);
        // The rows above it are scrolled up by whole rows.
        assertEquals(30 + 10 * 16f - 16f, rows.top(10), 0.001f);
        assertEquals(30 + 36 * 16f - 16f, rows.top(36), 0.001f);
    }

    @Test public void theDraftsLinesAreSpacedAndTapsFindTheirOwnRow() {
        ControlTargets.Rows rows = hint(",\"blockFirst\":36,\"blockRows\":3,\"rowGap\":4,\"rowShift\":16").rows();
        assertEquals(20f, rows.draftTop(37) - rows.draftTop(36), 0.001f);
        assertEquals(36, rows.draftRowAt(rows.draftTop(36) + 1));
        assertEquals(37, rows.draftRowAt(rows.draftTop(37) + 1));
        assertEquals(37, rows.draftRowAt(rows.draftTop(38) - 1));
        assertEquals(-1, rows.draftRowAt(rows.draftTop(36) - 1));
        // A grid-row tap below the box is the grid's own row.
        assertEquals(39, rows.rowAt(30 + 39 * 16f + 1));
    }

    @Test public void aSpreadOutsideTheGridIsIgnored() {
        assertEquals(0, hint(",\"blockFirst\":39,\"blockRows\":3,\"rowGap\":4").blockRows);
        assertEquals(0, hint(",\"blockFirst\":36,\"blockRows\":3,\"rowGap\":-4").blockRows);
        assertEquals(0, hint(",\"blockFirst\":36,\"blockRows\":3,\"rowGap\":4,\"rowShift\":-1").blockRows);
    }

    @Test public void theControlsRowKeepsItsTargetWhateverTheDraftsLength() {
        List<ControlTargets.Cells> controls =
                Collections.singletonList(new ControlTargets.Cells("send", 38, 60, 66));
        float[] tops = new float[4];
        for (int lines = 1; lines <= 4; lines++) {
            ControlTargets.Rows rows = lines == 1 ? ControlTargets.Rows.uniform(0, 16)
                    : new ControlTargets.Rows(0, 16, 36, lines, 4, lines > 2 ? 16 : 0);
            int[][] spans = new int[lines][];
            for (int i = 0; i < lines; i++) spans[i] = new int[] {36 + i, 2, 40};
            List<ControlTargets.Rect> out = ControlTargets.layout(controls, rows, 0, 6.5f, 36, 16 * 40f,
                    6.5f * 72, false, spans, Collections.<ControlTargets.Cells>emptyList());
            assertEquals(1, out.size());
            tops[lines - 1] = out.get(0).bottom;
        }
        for (int i = 1; i < 4; i++) assertEquals(tops[0], tops[i], 0.001f);
    }

    @Test public void targetsAvoidTheSpreadDraftLines() {
        ControlTargets.Rows rows = new ControlTargets.Rows(0, 16, 30, 3, 4, 0);
        List<ControlTargets.Cells> controls =
                Collections.singletonList(new ControlTargets.Cells("send", 33, 60, 66));
        int[][] spans = {{30, 2, 70}, {31, 2, 70}, {32, 2, 70}};
        List<ControlTargets.Rect> out = ControlTargets.layout(controls, rows, 0, 6.5f, 30, 16 * 40f, 6.5f * 72,
                false, spans, Collections.<ControlTargets.Cells>emptyList());
        assertEquals(1, out.size());
        assertTrue(out.get(0).top >= rows.draftTop(32) + 16 - 0.001f);
    }

    @Test public void aDraftLineTouchingOnlyAControlsPaddingLeavesItsWordsTappable() {
        // 52 columns of 6 px, 40 px rows: Clear's words at columns 23-27 on the lower rule (row 21),
        // the third draft line (row 22) ending at column 22, just short of them.
        ControlTargets.Rows rows = new ControlTargets.Rows(0, 40, 20, 3, 10, 0);
        List<ControlTargets.Cells> controls = java.util.Arrays.asList(
                new ControlTargets.Cells("clear", 21, 23, 28), new ControlTargets.Cells("send", 21, 45, 49));
        int[][] spans = {{20, 2, 50}, {21, 2, 12}, {22, 2, 22}};
        List<ControlTargets.Rect> out = ControlTargets.layout(controls, rows, 0, 6f, 20, 40 * 40f, 6f * 52, false,
                spans, Collections.<ControlTargets.Cells>emptyList());
        for (ControlTargets.Rect r : out) {
            float cx = (r.labelLeft + r.labelRight) / 2, cy = rows.top(21) + 20;
            assertTrue(r.id + " center", r.left <= cx && cx <= r.right && r.top <= cy && cy <= r.bottom);
            assertTrue(r.id + " words", r.left <= r.labelLeft && r.right >= r.labelRight);
            // And never over the draft's third line.
            assertTrue(r.id, r.left >= 22 * 6f - 0.001f || r.bottom <= rows.draftTop(22) + 0.001f
                    || r.top >= rows.draftTop(22) + 40);
        }
    }
}
