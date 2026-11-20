package dev.herdr.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Test;

/** A multi-line draft's rows are spread by the renderer; the host's targets follow them. */
public final class MultilineSpacingTest {
    private static ClusterPlacement.Hint hint(String extra) {
        return ClusterPlacement.Hint.parse(
                "{\"left\":0,\"top\":30,\"cellWidth\":6.5,\"cellHeight\":16,\"cols\":72,\"rows\":40,"
                        + "\"anchored\":true,\"guard\":38,\"busyFrom\":38,\"busy\":[0,0]" + extra + "}");
    }

    @Test public void rowsStayUniformWithoutASpreadBlock() {
        ControlTargets.Rows rows = hint("").rows();
        assertEquals(30 + 5 * 16f, rows.top(5), 0.001f);
        assertEquals(5, rows.rowAt(30 + 5 * 16f + 1));
    }

    @Test public void theBlockSpreadsAndTheRowsBelowAndItsLastRowStayPut() {
        ClusterPlacement.Hint h = hint(",\"blockFirst\":36,\"blockRows\":3,\"rowGap\":4");
        assertNotNull(h);
        ControlTargets.Rows rows = h.rows();
        assertEquals(30 + 38 * 16f, rows.top(38), 0.001f);
        assertEquals(30 + 39 * 16f, rows.top(39), 0.001f);
        assertEquals(20f, rows.top(37) - rows.top(36), 0.001f);
        assertEquals(30 + 10 * 16f - 8f, rows.top(10), 0.001f);
        // A tap in the gap under a draft line is that line's.
        assertEquals(36, rows.rowAt(rows.top(37) - 1));
        assertEquals(37, rows.rowAt(rows.top(37) + 1));
    }

    @Test public void aBlockOutsideTheGridIsIgnored() {
        assertEquals(0, hint(",\"blockFirst\":39,\"blockRows\":3,\"rowGap\":4").blockRows);
        assertEquals(0, hint(",\"blockFirst\":36,\"blockRows\":3,\"rowGap\":-4").blockRows);
    }

    @Test public void targetsAvoidTheSpreadDraftRows() {
        // The draft's two rows, spread; the controls' row below them.
        ControlTargets.Rows rows = new ControlTargets.Rows(0, 16, 36, 2, 4);
        List<ControlTargets.Cells> controls =
                Collections.singletonList(new ControlTargets.Cells("send", 38, 60, 66));
        int[][] spans = {{36, 2, 70}, {37, 2, 70}};
        List<ControlTargets.Rect> out = ControlTargets.layout(controls, rows, 0, 6.5f, 36, 16 * 40f, 6.5f * 72,
                false, spans, Collections.<ControlTargets.Cells>emptyList());
        assertEquals(1, out.size());
        // Never above the last draft row's drawn bottom.
        assertTrue(out.get(0).top >= rows.bottom(37) - 0.001f);
    }
}
