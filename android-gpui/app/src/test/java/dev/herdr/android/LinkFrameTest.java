package dev.herdr.android;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Wolf's ask (11-18): a link opens on the frame it was found on, held or live, but only while that
 * frame is the selected pane's. Every link carries the host and pane its renderer was installed
 * for (recorded natively with the renderer, taken at the press). The traces are the reviews of
 * #39 (rounds 2 and 3): what the link carries, against what is selected when Java acts on it.
 */
public final class LinkFrameTest {
    @Test public void wolfAskLinksAQueuedLinkFromThePaneLeftOnScreenOpensNothing() {
        // A/p1 held; focusOffline(p2); p1's link is queued behind drawSaved(A, p2), which runs first.
        assertFalse(LinkFrame.selected("A", "p1", "A", "p2"));
    }

    @Test public void wolfAskLinksAQueuedLinkFromTheHostLeftOnScreenOpensNothing() {
        // Offline switch to B/p2; A's link is queued behind B's saved frame.
        assertFalse(LinkFrame.selected("A", "p1", "B", "p2"));
        assertFalse(LinkFrame.selected("A", "p2", "B", "p2"));
    }

    @Test public void wolfAskLinksARendererInstalledByAPollThatThenFailedCarriesItsOwnPane() {
        // pollTerminal installed p2's renderer (recorded as A/p2 with it), then Java took the
        // closed/fail path with p1 still selected: p2's links do not pass as p1's.
        assertFalse(LinkFrame.selected("A", "p2", "A", "p1"));
    }

    @Test public void wolfAskLinksTheSamePanesFrameOpensBackInTheAppAndOnAReconnect() {
        // Live A/p1 painted; onPause, then onResume installs A/p1 offline before its cache lands.
        assertTrue(LinkFrame.selected("A", "p1", "A", "p1"));
        // A saved A/p1 frame, then adoptHost(A, p1) holding it.
        assertTrue(LinkFrame.selected("A", "p1", "A", "p1"));
        // Its pane's own frame, once drawn, after a pick.
        assertTrue(LinkFrame.selected("A", "p2", "A", "p2"));
    }

    @Test public void wolfAskLinksARawShellOpensAndALinkWithNoFrameDoesNot() {
        // A raw shell's connection has no host or pane id: recorded as "".
        assertTrue(LinkFrame.selected(LinkFrame.part(null), LinkFrame.part(null), null, null));
        assertFalse(LinkFrame.selected(null, null, "A", "p1"));
        assertFalse(LinkFrame.selected("", "", "A", "p1"));
    }
}
