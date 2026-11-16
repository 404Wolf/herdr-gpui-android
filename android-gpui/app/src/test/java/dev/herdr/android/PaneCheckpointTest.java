package dev.herdr.android;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Item 77: the pane on screen is saved at its turn's end and every two minutes at most while it prints. */
public final class PaneCheckpointTest {
    private static final long MIN = 60_000;

    @Test public void aPaneJustShownIsNotSavedAgainAtOnce() {
        PaneCheckpoint c = new PaneCheckpoint();
        assertFalse(c.observe("t1", "working", 100, 0));
        assertFalse(c.observe("t1", "working", 100, 10));
    }

    @Test public void theEndOfATurnSavesIt() {
        for (String end : new String[] {"done", "blocked", "idle"}) {
            PaneCheckpoint c = new PaneCheckpoint();
            c.observe("t1", "working", 100, 0);
            assertFalse(c.observe("t1", "working", 200, 1000));
            assertTrue(end, c.observe("t1", end, 200, 2000));
            // Once.
            assertFalse(c.observe("t1", end, 200, 3000));
        }
    }

    @Test public void aStatusThatNeverWorkedIsNoTurnEnd() {
        PaneCheckpoint c = new PaneCheckpoint();
        c.observe("t1", "unknown", 0, 0);
        assertFalse(c.observe("t1", "idle", 0, 1000));
        assertFalse(c.observe("t1", "done", 0, 2000));
    }

    @Test public void printingSavesEveryTwoMinutesAtMost() {
        PaneCheckpoint c = new PaneCheckpoint();
        c.observe("t1", "working", 0, 0);
        assertFalse(c.observe("t1", "working", 10, MIN));
        assertFalse(c.observe("t1", "working", 20, 2 * MIN - 1));
        assertTrue(c.observe("t1", "working", 30, 2 * MIN));
        assertFalse(c.observe("t1", "working", 40, 3 * MIN));
        assertTrue(c.observe("t1", "working", 50, 4 * MIN));
    }

    @Test public void aQuietPaneIsNeverRewritten() {
        PaneCheckpoint c = new PaneCheckpoint();
        c.observe("t1", "idle", 500, 0);
        assertFalse(c.observe("t1", "idle", 500, 10 * MIN));
        assertFalse(c.observe("t1", "idle", 500, 60 * MIN));
        // Output long after the last save is saved at once.
        assertTrue(c.observe("t1", "idle", 501, 61 * MIN));
    }

    @Test public void anotherPaneStartsOverAndAResetForgetsTheLastOne() {
        PaneCheckpoint c = new PaneCheckpoint();
        c.observe("t1", "working", 0, 0);
        c.observe("t1", "working", 5, MIN);
        assertFalse(c.observe("t2", "done", 9, 3 * MIN));
        assertFalse(c.observe("t2", "done", 9, 4 * MIN));
        c.reset();
        assertFalse(c.observe("t2", "working", 9, 5 * MIN));
        assertFalse(c.observe("", "done", 9, 6 * MIN));
        assertFalse(c.observe(null, "done", 9, 6 * MIN));
    }
}
