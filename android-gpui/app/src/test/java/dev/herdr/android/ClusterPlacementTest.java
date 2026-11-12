package dev.herdr.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Where the floating cluster goes, from the renderer's busy-columns report. */
public final class ClusterPlacementTest {
    /** 40 cols of 6 px, rows of 15 px, at (0, 30). */
    private static ClusterPlacement.Hint hint(int... busy) {
        return new ClusterPlacement.Hint(0, 30, 6, 15, 40, busy.length, true, busy, busy.length);
    }

    @Test public void theRenderersJsonParsesAndBadJsonIsRefused() {
        ClusterPlacement.Hint h = ClusterPlacement.Hint.parse(
                "{\"left\":0,\"top\":30,\"cellWidth\":6.5,\"cellHeight\":15,\"cols\":72,\"rows\":2,"
                        + "\"anchored\":true,\"guard\":1,\"busy\":[0,12]}");
        assertNotNull(h);
        assertEquals(12, h.busy[1]);
        assertEquals(1, h.guard);
        assertTrue(h.anchored);
        // Only the bottom rows reported: the rest count as taken.
        ClusterPlacement.Hint bottom = ClusterPlacement.Hint.parse(
                "{\"left\":0,\"top\":0,\"cellWidth\":6,\"cellHeight\":15,\"cols\":40,\"rows\":4,"
                        + "\"busyFrom\":2,\"busy\":[0,5]}");
        assertNotNull(bottom);
        assertEquals(40, bottom.busy[1]);
        assertEquals(5, bottom.busy[3]);
        assertNull(ClusterPlacement.Hint.parse(
                "{\"left\":0,\"top\":0,\"cellWidth\":6,\"cellHeight\":15,\"cols\":40,\"rows\":4,"
                        + "\"busyFrom\":1,\"busy\":[0,5]}"));
        assertNull(ClusterPlacement.Hint.parse("{\"rows\":3,\"busy\":[1]}"));
        assertNull(ClusterPlacement.Hint.parse("not json"));
    }

    @Test public void theClusterTakesTheLowestRowsWhoseRightEndIsFree() {
        // 60x30 px = 10 cols x 2 rows. The last two rows are free: it sits there.
        ClusterPlacement.Spot spot = ClusterPlacement.place(hint(40, 40, 12, 0, 0), 60, 30, null);
        assertEquals(3, spot.topRow);
        assertEquals(4, spot.bottomRow);
        assertFalse(spot.crowded);
        assertEquals(240f, spot.right, 0f);
        assertEquals(30 + 5 * 15f, spot.bottom, 0f);
    }

    @Test public void itClimbsAboveWhatIsBusyAndNeverCoversIt() {
        // The bottom row is busy to col 35 (a dialog edge): the spot goes up,
        // over a row busy only to col 10, clear of the cluster's 10 columns.
        ClusterPlacement.Spot spot = ClusterPlacement.place(hint(0, 0, 10, 0, 35), 60, 30, null);
        assertEquals(2, spot.topRow);
        assertEquals(3, spot.bottomRow);
        assertTrue(ClusterPlacement.fits(hint(0, 0, 30, 0, 35), 2, 2, 10));
        assertFalse(ClusterPlacement.fits(hint(0, 0, 31, 0, 35), 2, 2, 10));
    }

    @Test public void itStaysPutWhileItStillFitsSoItDoesNotHop() {
        ClusterPlacement.Spot first = ClusterPlacement.place(hint(0, 0, 0, 0, 40), 60, 30, null);
        assertEquals(3, first.bottomRow);
        // The bottom row frees up: the cluster keeps its place.
        assertEquals(first, ClusterPlacement.place(hint(0, 0, 0, 0, 0), 60, 30, first));
        // Its place fills: it moves to the lowest spot that fits.
        assertEquals(2, ClusterPlacement.place(hint(0, 0, 0, 40, 0), 60, 30, first).bottomRow);
    }

    @Test public void withNoFreeSpotItIsCrowdedInTheCorner() {
        ClusterPlacement.Spot spot = ClusterPlacement.place(hint(40, 40, 40, 40), 60, 30, null);
        assertTrue(spot.crowded);
        assertEquals(3, spot.bottomRow);
    }
}
