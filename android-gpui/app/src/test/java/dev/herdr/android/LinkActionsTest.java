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
        assertTrue(actions.timeout(first, false));
        assertFalse(actions.expanded());
        // Opened again before the first timer ran: only the newest timer closes them.
        actions.tap(true);
        int second = actions.generation();
        actions.collapse();
        actions.tap(true);
        assertFalse(actions.timeout(first, false));
        assertFalse(actions.timeout(second, false));
        assertTrue(actions.expanded());
        assertTrue(actions.timeout(actions.generation(), false));
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

    @Test public void wolfAskOfflineInPlaceActions_stayAsLongAsTheUserGivesControlsAndWhileTalkBackIsOnThem() {
        // The user's "Time to take action" when longer than six seconds; never shorter.
        assertEquals(6_000, LinkActions.collapseAfter(0));
        assertEquals(6_000, LinkActions.collapseAfter(6_000));
        assertEquals(20_000, LinkActions.collapseAfter(20_000));
        LinkActions actions = new LinkActions();
        actions.tap(true);
        int at = actions.generation();
        // TalkBack is on Reconnect when the time runs out: they stay, and the timer waits again.
        assertFalse(actions.timeout(at, true));
        assertTrue(actions.expanded());
        assertTrue(actions.waiting(at));
        // Focus moved on: the next run of the same timer closes them.
        assertTrue(actions.timeout(at, false));
        assertFalse(actions.waiting(at));
        // Closed some other way: a stale timer neither closes nor waits.
        actions.tap(true);
        int again = actions.generation();
        actions.collapse();
        assertFalse(actions.timeout(again, false));
        assertFalse(actions.waiting(again));
    }

    // The recorded geometry (1080 x 2400, 420 dpi, 48 dp = 126 px), actions open: the row
    // ends at y=207 and the terminal begins there; Reconnect's region reaches y=230.
    private static final int ROW_BOTTOM = 207;
    private static final float SLOP = 21;

    private static int[][] openRegions() {
        return new int[][] {
            LinkActions.grow(416, 128, 663, 207, 126, 1080, 2400),
            LinkActions.grow(674, 136, 848, 199, 126, 1080, 2400),
            LinkActions.grow(859, 136, 985, 199, 126, 1080, 2400),
        };
    }

    @Test public void wolfAskOfflineInPlaceActions_aDragFromTheTerminalsTopEdgeIsTheTerminalsAndNeverAClick() {
        int[][] regions = openRegions();
        assertTrue("the region reaches into the terminal", regions[1][3] > ROW_BOTTOM);
        LinkActions.Press press = new LinkActions.Press();
        // Down in the overlap, a long drag down the terminal, up there: all the terminal's.
        assertEquals(LinkActions.Press.PASS, press.down(regions, ROW_BOTTOM, 760, 215));
        assertEquals(LinkActions.Press.PASS, press.move(760, 400, 1, SLOP));
        assertEquals(LinkActions.Press.PASS, press.move(760, 900, 1, SLOP));
        assertEquals(LinkActions.Press.PASS, press.up(760, 900, true, SLOP));
        assertEquals(-1, press.takeClick());
        // The same drag back to where it began is still a drag, not Reconnect.
        assertEquals(LinkActions.Press.PASS, press.down(regions, ROW_BOTTOM, 760, 215));
        assertEquals(LinkActions.Press.PASS, press.move(760, 900, 1, SLOP));
        assertEquals(LinkActions.Press.PASS, press.move(760, 215, 1, SLOP));
        assertEquals(LinkActions.Press.PASS, press.up(760, 215, true, SLOP));
        assertEquals(-1, press.takeClick());
    }

    @Test public void wolfAskOfflineInPlaceActions_aTapInTheOverlapIsTheControlsAndTheTerminalIsCancelled() {
        LinkActions.Press press = new LinkActions.Press();
        assertEquals(LinkActions.Press.PASS, press.down(openRegions(), ROW_BOTTOM, 760, 215));
        assertEquals(LinkActions.Press.PASS, press.move(762, 218, 1, SLOP));
        assertEquals(LinkActions.Press.CANCEL_THEN_KEEP, press.up(762, 218, true, SLOP));
        assertEquals(1, press.takeClick());
        assertEquals("one click", -1, press.takeClick());
    }

    @Test public void wolfAskOfflineInPlaceActions_aPressInTheRowClicksOnlyAsATap() {
        int[][] regions = openRegions();
        LinkActions.Press press = new LinkActions.Press();
        // A tap on Reconnect, drawn or just outside it in the row.
        assertEquals(LinkActions.Press.KEEP, press.down(regions, ROW_BOTTOM, 760, 160));
        assertEquals(LinkActions.Press.KEEP, press.up(765, 163, true, SLOP));
        assertEquals(1, press.takeClick());
        // Pressed, then dragged away and back: kept (the row's pull never sees half a gesture), no click.
        assertEquals(LinkActions.Press.KEEP, press.down(regions, ROW_BOTTOM, 760, 160));
        assertEquals(LinkActions.Press.KEEP, press.move(760, 600, 1, SLOP));
        assertEquals(LinkActions.Press.KEEP, press.move(760, 160, 1, SLOP));
        assertEquals(LinkActions.Press.KEEP, press.up(760, 160, true, SLOP));
        assertEquals(-1, press.takeClick());
        // A second finger.
        assertEquals(LinkActions.Press.KEEP, press.down(regions, ROW_BOTTOM, 760, 160));
        assertEquals(LinkActions.Press.KEEP, press.move(760, 160, 2, SLOP));
        assertEquals(LinkActions.Press.KEEP, press.up(760, 160, true, SLOP));
        assertEquals(-1, press.takeClick());
        // The system cancels it.
        assertEquals(LinkActions.Press.KEEP, press.down(regions, ROW_BOTTOM, 760, 160));
        assertEquals(LinkActions.Press.KEEP, press.cancel());
        assertEquals(LinkActions.Press.PASS, press.up(760, 160, true, SLOP));
        assertEquals(-1, press.takeClick());
        // Lifted far from where it went down, with no move reported between: no click.
        assertEquals(LinkActions.Press.KEEP, press.down(regions, ROW_BOTTOM, 760, 160));
        assertEquals(LinkActions.Press.KEEP, press.up(760, 900, true, SLOP));
        assertEquals(-1, press.takeClick());
        assertEquals(LinkActions.Press.PASS, press.down(regions, ROW_BOTTOM, 760, 215));
        assertEquals(LinkActions.Press.PASS, press.up(760, 900, true, SLOP));
        assertEquals(-1, press.takeClick());
        // The actions closed by themselves while it was held: the control is gone, no click.
        assertEquals(LinkActions.Press.KEEP, press.down(regions, ROW_BOTTOM, 760, 160));
        assertEquals(LinkActions.Press.KEEP, press.up(760, 160, false, SLOP));
        assertEquals(-1, press.takeClick());
    }

    @Test public void wolfAskOfflineInPlaceActions_aTouchOutsideTheRegionsIsUntouched() {
        LinkActions.Press press = new LinkActions.Press();
        assertEquals(LinkActions.Press.PASS, press.down(openRegions(), ROW_BOTTOM, 500, 800));
        assertEquals(-1, press.target());
        assertEquals(LinkActions.Press.PASS, press.move(500, 1200, 1, SLOP));
        assertEquals(LinkActions.Press.PASS, press.move(500, 1200, 2, SLOP));
        assertEquals(LinkActions.Press.PASS, press.up(500, 1200, true, SLOP));
        assertEquals(LinkActions.Press.PASS, press.cancel());
        assertEquals(-1, press.takeClick());
    }
}
