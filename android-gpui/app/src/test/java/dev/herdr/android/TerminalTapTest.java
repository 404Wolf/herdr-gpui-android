package dev.herdr.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** A tap the program took as a mouse click opens no typing; any other tap types as before. */
public final class TerminalTapTest {
    @Test public void aTapTheProgramClickedOpensNothingOnAnyPane() {
        assertEquals(TerminalTap.Action.NOTHING, TerminalTap.after(TerminalTap.Outcome.CLICKED, true));
        assertEquals(TerminalTap.Action.NOTHING, TerminalTap.after(TerminalTap.Outcome.CLICKED, false));
    }

    @Test public void withoutAClickTapToTypeIsAsBefore() {
        // Mouse reporting off, or a tap on a link: an agent types into the draft,
        // any other pane gets the terminal's keyboard.
        assertEquals(TerminalTap.Action.TYPE_INTO_DRAFT, TerminalTap.after(TerminalTap.Outcome.PLAIN, true));
        assertEquals(TerminalTap.Action.SHOW_KEYBOARD, TerminalTap.after(TerminalTap.Outcome.PLAIN, false));
    }

    @Test public void aPressThatWasNoTapOpensNothing() {
        // It travelled into a scroll, or the finger was held.
        assertEquals(TerminalTap.Action.NOTHING, TerminalTap.after(TerminalTap.Outcome.IGNORED, true));
        assertEquals(TerminalTap.Action.NOTHING, TerminalTap.after(TerminalTap.Outcome.IGNORED, false));
    }

    @Test public void outcomesDecodeAsTheRendererCodesThem() {
        assertEquals(TerminalTap.Outcome.CLICKED, TerminalTap.Outcome.of(0));
        assertEquals(TerminalTap.Outcome.PLAIN, TerminalTap.Outcome.of(1));
        assertEquals(TerminalTap.Outcome.IGNORED, TerminalTap.Outcome.of(2));
        // Anything else is no tap: never a reason to open the keyboard.
        assertEquals(TerminalTap.Outcome.IGNORED, TerminalTap.Outcome.of(7));
    }

    @Test public void eachReportIsActedOnOnce() {
        TerminalTap taps = new TerminalTap();
        long stamp = taps.show("1:p1");
        assertTrue(taps.accept(stamp, 10));
        // The same report again (a duplicate), or an older one arriving late.
        assertFalse(taps.accept(stamp, 10));
        assertFalse(taps.accept(stamp, 9));
        // The next tap's report.
        assertTrue(taps.accept(stamp, 11));
    }

    @Test public void aLateReportForAnOlderTapNeverSettlesANewerOne() {
        TerminalTap taps = new TerminalTap();
        long stamp = taps.show("1:p1");
        // Two taps; the newer one's report comes first.
        assertTrue(taps.accept(stamp, 21));
        assertFalse(taps.accept(stamp, 20));
    }

    @Test public void aReportForAPaneLeftSinceIsDropped() {
        TerminalTap taps = new TerminalTap();
        long first = taps.show("1:p1");
        long second = taps.show("1:p2");
        assertTrue(second > first);
        // A tap made on p1 reports after p2 is shown: no typing on p2 for it.
        assertFalse(taps.accept(first, 30));
        assertTrue(taps.accept(second, 31));
        // Back to p1: a new stamp, so p1's old reports stay dropped too.
        long third = taps.show("1:p1");
        assertFalse(taps.accept(first, 32));
        assertTrue(taps.accept(third, 33));
    }

    @Test public void showingTheSamePaneAgainKeepsItsStamp() {
        TerminalTap taps = new TerminalTap();
        long stamp = taps.show("1:p1");
        assertEquals(-1, taps.show("1:p1"));
        assertTrue(taps.accept(stamp, 1));
    }

    @Test public void aReconnectIsANewPane() {
        TerminalTap taps = new TerminalTap();
        long before = taps.show("1:p1");
        long after = taps.show("2:p1");
        assertTrue(after > before);
        assertFalse(taps.accept(before, 5));
    }
}
