package dev.herdr.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Wolf's ask: scrolling keeps working offline. A drag or fling on a held
 * frame (the link is down, a reconnect has not attached) or a cached pane
 * scrolls the phone's own rows; it is never refused for want of a link.
 */
public final class HeldScrollTest {
    /** Records which way each step went. */
    private static final class Recorder implements HeldScroll.Sink {
        final StringBuilder went = new StringBuilder();
        @Override public boolean held(int lines) {
            went.append("held ").append(lines).append(';');
            return true;
        }
        @Override public boolean live(int lines) {
            went.append("live ").append(lines).append(';');
            return true;
        }
    }

    @Test public void wolfAskOfflineScrolling_aClosedPaneScrollsItsHeldFrame() {
        Recorder sink = new Recorder();
        assertTrue(HeldScroll.scroll(false, true, true, 7, false, 3, sink));
        assertTrue(HeldScroll.scroll(false, true, true, 0, false, -2, sink));
        assertEquals("held 3;held -2;", sink.went.toString());
    }

    @Test public void wolfAskOfflineScrolling_aReconnectNotYetAttachedScrollsTheHeldFrame() {
        Recorder sink = new Recorder();
        assertTrue(HeldScroll.scroll(false, true, false, 7, false, 1, sink));
        assertEquals("held 1;", sink.went.toString());
    }

    @Test public void wolfAskOfflineScrolling_noPaneAtAllScrollsTheCachedCopy() {
        Recorder sink = new Recorder();
        assertTrue(HeldScroll.scroll(false, false, false, 0, false, 5, sink));
        assertEquals("held 5;", sink.went.toString());
    }

    @Test public void wolfAskOfflineScrolling_aFlingStopsOnlyWhereTheHeldFrameDoes() {
        HeldScroll.Sink atEnd = new HeldScroll.Sink() {
            @Override public boolean held(int lines) { return false; }
            @Override public boolean live(int lines) { throw new AssertionError("no link"); }
        };
        assertFalse(HeldScroll.scroll(false, true, true, 7, false, 4, atEnd));
    }

    @Test public void wolfAskOfflineScrolling_aLivePaneScrollsThroughItsSession() {
        Recorder sink = new Recorder();
        assertTrue(HeldScroll.scroll(false, true, false, 7, true, 2, sink));
        assertEquals("live 2;", sink.went.toString());
        assertFalse(HeldScroll.held(false, true, false, 7, true));
    }

    @Test public void wolfAskOfflineScrolling_aRawShellIsNeverAHeldFrame() {
        Recorder sink = new Recorder();
        // Its own terminal scrolls it; the Herdr held frame is never touched.
        assertFalse(HeldScroll.scroll(true, true, true, 0, false, 2, sink));
        assertFalse(HeldScroll.scroll(true, false, false, 0, false, 2, sink));
        assertEquals("", sink.went.toString());
    }
}
