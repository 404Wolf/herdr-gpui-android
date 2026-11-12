package dev.herdr.android;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** A pull on the tab strip is mostly down; sideways drags stay the strip's scroll. */
public final class PullDownRowTest {
    @Test public void onlyAMostlyDownwardDragPastTheSlopIsAPull() {
        assertTrue(PullDownRow.pulling(4, 40, 8));
        assertFalse(PullDownRow.pulling(4, 12, 8)); // within the slop
        assertFalse(PullDownRow.pulling(30, 40, 8)); // too sideways: a scroll
        assertFalse(PullDownRow.pulling(0, -40, 8)); // upward
    }
}
