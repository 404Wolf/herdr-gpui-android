package dev.herdr.android;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/** What the queued strip says about each message. */
public final class QueuedStripTest {
    private static Outbox.Message message(String pane, Outbox.State state, boolean blocked) {
        return new Outbox.Message("id", "h", pane, "b", "text", 1, state, blocked);
    }

    @Test public void eachStateSaysWhatIsHappeningInAFewWords() {
        assertEquals("queued", QueuedStrip.label(message("t1", Outbox.State.QUEUED, false), "t1"));
        assertEquals("waits for its dialog", QueuedStrip.label(message("t1", Outbox.State.QUEUED, true), "t1"));
        assertEquals("sending…", QueuedStrip.label(message("t1", Outbox.State.SENDING, false), "t1"));
        assertEquals("may have been sent", QueuedStrip.label(message("t1", Outbox.State.UNSURE, false), "t1"));
        assertEquals("not sent", QueuedStrip.label(message("t1", Outbox.State.GONE, false), "t1"));
        // A gone message shown on another pane names the pane it was for.
        assertEquals("not sent · t4 gone", QueuedStrip.label(message("t4", Outbox.State.GONE, false), "t1"));
    }

    @Test public void aMultiLineMessageReadsOnOneLineWithItsBreaksMarked() {
        assertEquals("first ⏎ second ⏎ third", QueuedStrip.oneLine("  first\n  second\n\nthird \n"));
        assertEquals("a b", QueuedStrip.oneLine("a\tb"));
        assertEquals("+2 more queued", QueuedStrip.more(2));
    }
}
