package dev.herdr.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** A tap the program took as a mouse click opens no typing; any other tap types as before. */
public final class TerminalTapTest {
    @Test public void aTapTheProgramClickedOpensNothingOnAnyPane() {
        assertEquals(TerminalTap.Action.NOTHING, TerminalTap.after(true, true));
        assertEquals(TerminalTap.Action.NOTHING, TerminalTap.after(true, false));
    }

    @Test public void withoutAClickTapToTypeIsAsBefore() {
        // Mouse reporting off (or a tap on a link): an agent types into the draft,
        // any other pane gets the terminal's keyboard.
        assertEquals(TerminalTap.Action.TYPE_INTO_DRAFT, TerminalTap.after(false, true));
        assertEquals(TerminalTap.Action.SHOW_KEYBOARD, TerminalTap.after(false, false));
    }

    @Test public void aCountedTapSettlesOnceOnTheReportOrTheTimeout() {
        TerminalTap tap = new TerminalTap();
        tap.tapped();
        // The renderer's report settles it; the timeout that follows finds nothing.
        assertTrue(tap.settle());
        assertFalse(tap.settle());
        // No report: the timeout settles it instead, and a late report is ignored.
        tap.tapped();
        assertTrue(tap.settle());
        assertFalse(tap.settle());
    }

    @Test public void aReportForAPressTheActivityDidNotCountIsIgnored() {
        // A drag or a hold: the renderer may still report its lift.
        assertFalse(new TerminalTap().settle());
    }

    @Test public void theWaitIsShortButLongerThanAFrame() {
        assertTrue(TerminalTap.REPORT_WAIT_MS > 50 && TerminalTap.REPORT_WAIT_MS <= 500);
    }
}
