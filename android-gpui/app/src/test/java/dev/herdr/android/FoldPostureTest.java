package dev.herdr.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class FoldPostureTest {
    private static final int FLAT_STATE = 1;

    @Test public void onlyAHalfOpenFoldAcrossTheWindowIsTabletop() {
        // The inner panel turned landscape: the fold runs across at y=1038.
        FoldPosture tabletop = FoldPosture.of(FoldPosture.STATE_HALF_OPENED, 0, 1038, 2152, 1038);
        assertTrue(tabletop.tabletop);
        assertEquals(1038, tabletop.foldTop);
        assertEquals(1038, tabletop.foldBottom);
        // The same fold opened flat, or held like a book, splits nothing.
        assertEquals(FoldPosture.FLAT, FoldPosture.of(FLAT_STATE, 0, 1038, 2152, 1038));
        assertFalse(FoldPosture.of(FoldPosture.STATE_HALF_OPENED, 1038, 0, 1038, 2152).tabletop);
        // A hinge with its own height keeps both edges.
        FoldPosture hinge = FoldPosture.of(FoldPosture.STATE_HALF_OPENED, 0, 1000, 2152, 1040);
        assertEquals(1000, hinge.foldTop);
        assertEquals(1040, hinge.foldBottom);
    }

    @Test public void aPostureChangeIsAUseButTheWatchersFirstReportIsNot() {
        // Item 71 (t217 on PR #29): a fold into tabletop can keep the window's size.
        FoldPosture tabletop = FoldPosture.of(FoldPosture.STATE_HALF_OPENED, 0, 1038, 2152, 1038);
        FoldPosture.Uses uses = new FoldPosture.Uses();
        // Started while already in tabletop: how the phone was, not a fold.
        assertFalse(uses.isUse(FoldPosture.FLAT, tabletop));
        assertTrue("opened flat", uses.isUse(tabletop, FoldPosture.FLAT));
        assertTrue("half-closed again", uses.isUse(FoldPosture.FLAT, tabletop));
        assertFalse("the same posture again", uses.isUse(tabletop, FoldPosture.of(FoldPosture.STATE_HALF_OPENED, 0, 1038, 2152, 1038)));
        // A watcher that first reports the posture already known.
        FoldPosture.Uses fresh = new FoldPosture.Uses();
        assertFalse(fresh.isUse(FoldPosture.FLAT, FoldPosture.FLAT));
        assertTrue(fresh.isUse(FoldPosture.FLAT, tabletop));
    }

    @Test public void theTerminalKeepsTheHalfAboveTheFoldOnlyWhenBothSidesFit() {
        FoldPosture tabletop = FoldPosture.of(FoldPosture.STATE_HALF_OPENED, 0, 1038, 2152, 1038);
        // Status bar above, nothing below: the terminal ends exactly at the fold.
        assertEquals(921, tabletop.heightAbove(117, 2076, 500, 300));
        // An open keyboard leaves 208 px under the fold, too little for the keys.
        assertEquals(-1, tabletop.heightAbove(117, 1246, 500, 300));
        // A fold above the content, or a sliver of terminal above it, does not split.
        assertEquals(-1, tabletop.heightAbove(1100, 2076, 500, 300));
        assertEquals(-1, tabletop.heightAbove(700, 2076, 500, 300));
        assertEquals(-1, FoldPosture.FLAT.heightAbove(117, 2076, 500, 300));
    }
}
