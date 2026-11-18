package dev.herdr.android;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Wolf's ask: offline is ONLY a small corner indicator, never a dim, a scrim
 * or an overlay. Its Reconnect and Hosts open in place beside it and close on
 * use, after a few seconds or at a touch elsewhere (which still reaches the
 * terminal), and each is touched in a 48 dp region though drawn small.
 */
public final class LinkActionsTest {
    @Test public void wolfAskOfflineInPlaceActions_aTapOpensThemBesideTheIndicatorAndASecondClosesThem() {
        LinkActions actions = new LinkActions();
        assertFalse(actions.expanded());
        assertTrue(actions.tap(true));
        assertTrue(actions.expanded());
        assertFalse(actions.tap(true));
        assertFalse(actions.expanded());
    }

    @Test public void wolfAskOfflineInPlaceActions_aHiddenIndicatorHasNone() {
        LinkActions actions = new LinkActions();
        assertFalse(actions.tap(false));
        assertFalse(actions.expanded());
        // Open, then the link comes back: the indicator hides and takes them with it.
        actions.tap(true);
        assertFalse(actions.tap(false));
        assertFalse(actions.expanded());
    }

    @Test public void wolfAskOfflineInPlaceActions_useOrATouchElsewhereClosesThem() {
        LinkActions actions = new LinkActions();
        actions.tap(true);
        assertTrue("used, or touched elsewhere", actions.collapse());
        assertFalse(actions.expanded());
        assertFalse("nothing to close twice", actions.collapse());
    }

    @Test public void wolfAskOfflineInPlaceActions_closeByThemselvesButAnOldTimerNeverClosesNewOnes() {
        LinkActions actions = new LinkActions();
        actions.tap(true);
        int first = actions.generation();
        assertTrue(actions.timeout(first));
        assertFalse(actions.expanded());
        // Opened again before the first timer ran: only the newest timer closes them.
        actions.tap(true);
        int second = actions.generation();
        actions.collapse();
        actions.tap(true);
        assertFalse(actions.timeout(first));
        assertFalse(actions.timeout(second));
        assertTrue(actions.expanded());
        assertTrue(actions.timeout(actions.generation()));
        assertTrue(LinkActions.COLLAPSE_MS >= 3_000 && LinkActions.COLLAPSE_MS <= 10_000);
    }

    @Test public void wolfAskOfflineInPlaceActions_eachIsTouchedInAtLeast48dp() {
        assertEquals(48, LinkActions.TOUCH_DP);
        // A 30 px tall, 120 px wide indicator at the screen's top right grows to 48 tall, centred.
        assertArrayEquals(new int[] {900, 91, 1020, 139}, LinkActions.grow(900, 100, 1020, 130, 48, 1080, 2400));
        // A narrow control grows sideways too.
        assertArrayEquals(new int[] {976, 91, 1024, 139}, LinkActions.grow(990, 100, 1010, 130, 48, 1080, 2400));
        // At an edge it moves inside the screen rather than shrinking.
        assertArrayEquals(new int[] {1032, 0, 1080, 48}, LinkActions.grow(1060, 0, 1080, 30, 48, 1080, 2400));
        // A control already big enough keeps its bounds.
        assertArrayEquals(new int[] {10, 10, 110, 70}, LinkActions.grow(10, 10, 110, 70, 48, 1080, 2400));
    }

    @Test public void wolfAskOfflineInPlaceActions_aTouchOutsideEveryRegionIsElsewhere() {
        int[][] regions = {{900, 91, 1020, 139}, null, {1024, 91, 1072, 139}};
        assertEquals(0, LinkActions.pick(regions, 950, 95));
        // Below the drawn row but inside the 48 dp region: still the indicator.
        assertEquals(0, LinkActions.pick(regions, 950, 135));
        assertEquals(2, LinkActions.pick(regions, 1030, 120));
        // The terminal below, and a hidden control (null): elsewhere.
        assertEquals(-1, LinkActions.pick(regions, 500, 1200));
        assertEquals(-1, LinkActions.pick(regions, 950, 139));
    }
}
