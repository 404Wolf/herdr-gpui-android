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
        @Override public boolean local(int lines) {
            went.append("local ").append(lines).append(';');
            return true;
        }
    }

    @Test public void wolfAskOfflineScrolling_aClosedPaneScrollsItsHeldFrame() {
        Recorder sink = new Recorder();
        assertTrue(HeldScroll.scroll(false, true, true, 7, false, false, 3, sink));
        assertTrue(HeldScroll.scroll(false, true, true, 0, false, false, -2, sink));
        assertEquals("held 3;held -2;", sink.went.toString());
    }

    @Test public void wolfAskOfflineScrolling_aReconnectNotYetAttachedScrollsTheHeldFrame() {
        Recorder sink = new Recorder();
        assertTrue(HeldScroll.scroll(false, true, false, 7, false, false, 1, sink));
        assertEquals("held 1;", sink.went.toString());
    }

    @Test public void wolfAskOfflineScrolling_noPaneAtAllScrollsTheCachedCopy() {
        Recorder sink = new Recorder();
        assertTrue(HeldScroll.scroll(false, false, false, 0, false, false, 5, sink));
        assertEquals("held 5;", sink.went.toString());
    }

    @Test public void wolfAskOfflineScrolling_aFlingStopsOnlyWhereTheHeldFrameDoes() {
        HeldScroll.Sink atEnd = new HeldScroll.Sink() {
            @Override public boolean held(int lines) { return false; }
            @Override public boolean live(int lines) { throw new AssertionError("no link"); }
            @Override public boolean local(int lines) { throw new AssertionError("no link"); }
        };
        assertFalse(HeldScroll.scroll(false, true, true, 7, false, false, 4, atEnd));
    }

    @Test public void wolfAskOfflineScrolling_aLivePaneScrollsThroughItsSession() {
        Recorder sink = new Recorder();
        assertTrue(HeldScroll.scroll(false, true, false, 7, true, false, 2, sink));
        assertEquals("live 2;", sink.went.toString());
        assertFalse(HeldScroll.held(false, true, false, 7, true));
    }

    @Test public void wolfAskOfflineScrolling_aRawShellIsNeverAHeldFrame() {
        Recorder sink = new Recorder();
        // Its own terminal scrolls it; the Herdr held frame is never touched.
        assertFalse(HeldScroll.scroll(true, true, true, 0, false, false, 2, sink));
        assertFalse(HeldScroll.scroll(true, false, false, 0, false, false, 2, sink));
        assertEquals("", sink.went.toString());
    }

    @Test public void offlineRedesign_aLivePaneInDoubtScrollsOnThePhoneAndSendsNothing() {
        // factory-9zz.1: in the seconds after airplane mode, before the link is given up, a
        // drag on a full-screen agent went out as wheel input over the dead link.
        Recorder sink = new Recorder();
        assertTrue(HeldScroll.scroll(false, true, false, 7, true, true, 3, sink));
        assertTrue(HeldScroll.scroll(false, true, false, 7, true, true, -1, sink));
        assertEquals("local 3;local -1;", sink.went.toString());
        // Doubt changes nothing for a held frame, a pane with no session, or a raw shell.
        Recorder held = new Recorder();
        assertTrue(HeldScroll.scroll(false, true, true, 7, false, true, 2, held));
        assertFalse(HeldScroll.scroll(false, true, false, 0, false, true, 2, held));
        assertTrue(HeldScroll.scroll(true, true, false, 7, true, true, 2, held));
        assertEquals("held 2;live 2;", held.went.toString());
    }

    @Test public void offlineRedesign_aDragBackThatMovesNothingIsAlwaysAnswered() {
        // A full-screen agent's held frame keeps no rows above its screen.
        assertEquals(HeldScroll.Edge.SCREEN_ONLY, HeldScroll.edge(3, false, 0, false, false));
        // A saved history of more than the screen opens instead.
        assertEquals(HeldScroll.Edge.OPEN_HISTORY, HeldScroll.edge(3, false, 0, true, false));
        assertEquals(HeldScroll.Edge.OPEN_HISTORY, HeldScroll.edge(1, false, 500, true, false));
        // The oldest saved row of a pane with history.
        assertEquals(HeldScroll.Edge.NO_OLDER, HeldScroll.edge(1, false, 500, false, false));
    }

    @Test public void offlineRedesign_theEdgeIsAnsweredOncePerGestureAndOnlyGoingBack() {
        assertEquals(HeldScroll.Edge.NONE, HeldScroll.edge(3, true, 0, false, false));
        assertEquals(HeldScroll.Edge.NONE, HeldScroll.edge(-3, false, 0, true, false));
        assertEquals(HeldScroll.Edge.NONE, HeldScroll.edge(0, false, 0, true, false));
        assertEquals(HeldScroll.Edge.NONE, HeldScroll.edge(3, false, 0, true, true));
        assertEquals(HeldScroll.Edge.NONE, HeldScroll.edge(3, false, 0, false, true));
    }

    @Test public void wolf1124OfflineHistory_aLinkInDoubtLooksUpTheSavedTranscript() {
        // Just offline: the link is still "ready" but in doubt; its saved history must be found.
        assertTrue(HeldScroll.savedTranscriptWanted(true, false, false, true, true));
        // Plainly offline, closed, or not attached yet: as before.
        assertTrue(HeldScroll.savedTranscriptWanted(false, true, false, true, false));
        assertTrue(HeldScroll.savedTranscriptWanted(false, false, true, true, false));
        assertTrue(HeldScroll.savedTranscriptWanted(false, false, false, false, false));
        // A live pane whose link is sound: no saved copy.
        assertFalse(HeldScroll.savedTranscriptWanted(true, false, false, true, false));
    }
}
