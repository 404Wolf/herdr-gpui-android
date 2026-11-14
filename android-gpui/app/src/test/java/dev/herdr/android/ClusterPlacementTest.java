package dev.herdr.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** The renderer's layout hint, as the composer reads it. */
public final class ClusterPlacementTest {
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
}
