package dev.herdr.android;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Wolf's ask (11-18): a link on a held frame opens, but only on the selected pane's own frame.
 * The traces are the review of #39 (round 2) LifecycleProbe's, as the activity records them:
 * {@code attached} where a frame is drawn, the selection being the connection's host and pane.
 */
public final class LinkFrameTest {
    @Test public void wolfAskLinksReturningToTheAppOpensTheSamePanesFrameBeforeTheCacheLands() {
        LinkFrame frame = new LinkFrame();
        frame.attached("A", "p1");          // live A/p1 painted
        // onPause stops the connection; onResume installs A/p1 offline; the saved copy is pending.
        assertTrue(frame.opens("A", "p1", false));
    }

    @Test public void wolfAskLinksAnOfflinePanePickOpensNothingUntilThatPanesFrameIsDrawn() {
        LinkFrame frame = new LinkFrame();
        frame.attached("A", "p1");
        // focusOffline(p2): the selection moves, p1's frame is still on screen.
        assertFalse(frame.opens("A", "p2", false));
        // A reconnect adopts A/p2 before the saved frame: still p1 on screen.
        assertFalse(frame.opens("A", "p2", false));
        frame.attached("A", "p2");          // drawSaved(A, p2)
        assertTrue(frame.opens("A", "p2", false));
    }

    @Test public void wolfAskLinksAnotherHostsFrameNeverOpensEvenAfterAReconnect() {
        LinkFrame frame = new LinkFrame();
        frame.attached("A", "p1");
        // Offline switch to B/p2 (installOffline), then adoptHost(B, p2) before B's saved frame.
        assertFalse(frame.opens("B", "p2", false));
        assertFalse(frame.opens("B", "p1", false));
        frame.attached("B", "p2");
        assertTrue(frame.opens("B", "p2", false));
        assertFalse("and A's own selection no longer matches B's frame", frame.opens("A", "p1", false));
    }

    @Test public void wolfAskLinksASavedFrameThenAReconnectToItsPaneOpens() {
        LinkFrame frame = new LinkFrame();
        frame.attached("A", "p1");          // drawSaved(A, p1)
        assertTrue(frame.opens("A", "p1", false));   // adoptHost(A, p1) holding it
        assertFalse(frame.opens("A", "p2", false));  // adoptHost(A, p2): another pane
    }

    @Test public void wolfAskLinksALiveSessionOpensAndNothingShownOpensNothing() {
        LinkFrame frame = new LinkFrame();
        assertFalse(frame.opens("A", "p1", false));
        assertTrue("live, as before", frame.opens("A", "p1", true));
        frame.attached("A", null);          // a raw shell has no pane
        assertTrue(frame.opens("A", "", false));
        frame.cleared();
        assertFalse(frame.opens("A", "", false));
    }
}
