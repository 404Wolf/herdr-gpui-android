package dev.herdr.android;

import static org.junit.Assert.assertEquals;

import java.util.Arrays;
import java.util.Collections;
import org.junit.Test;

/** What the Enter badge and the queue sheet say about the outbox. */
public final class QueuedMessagesTest {
    private static Outbox.Message message(String pane, Outbox.State state, boolean blocked) {
        return new Outbox.Message("id", "h", pane, "b", "text", 1, state, blocked);
    }

    @Test public void eachStateSaysWhatIsHappeningInAFewWords() {
        assertEquals("queued", QueuedMessages.label(message("t1", Outbox.State.QUEUED, false), "t1"));
        assertEquals("waits for its dialog", QueuedMessages.label(message("t1", Outbox.State.QUEUED, true), "t1"));
        assertEquals("sending…", QueuedMessages.label(message("t1", Outbox.State.SENDING, false), "t1"));
        assertEquals("may have been sent", QueuedMessages.label(message("t1", Outbox.State.UNSURE, false), "t1"));
        assertEquals("not sent", QueuedMessages.label(message("t1", Outbox.State.GONE, false), "t1"));
        // A gone message shown on another pane names the pane it was for.
        assertEquals("not sent · t4 gone", QueuedMessages.label(message("t4", Outbox.State.GONE, false), "t1"));
    }

    @Test public void aMultiLineMessageReadsOnOneLineWithItsBreaksMarked() {
        assertEquals("first ⏎ second ⏎ third", QueuedMessages.oneLine("  first\n  second\n\nthird \n"));
        assertEquals("a b", QueuedMessages.oneLine("a\tb"));
        assertEquals("❯ text  · queued", QueuedMessages.row(message("t1", Outbox.State.QUEUED, false), "t1"));
    }

    @Test public void theBadgeCountsAndHidesWhenEmpty() {
        assertEquals("", QueuedMessages.badge(0));
        assertEquals("2", QueuedMessages.badge(2));
        assertEquals("9", QueuedMessages.badge(9));
        assertEquals("9+", QueuedMessages.badge(10));
        assertEquals("1 queued message, sent in order", QueuedMessages.title(1));
        assertEquals("3 queued messages, sent in order", QueuedMessages.title(3));
    }

    @Test public void theBadgeTakesTheMostPressingMessagesColour() {
        assertEquals(QueuedMessages.Urgency.NONE, QueuedMessages.urgency(Collections.emptyList()));
        assertEquals(QueuedMessages.Urgency.QUEUED, QueuedMessages.urgency(Arrays.asList(
                message("t1", Outbox.State.QUEUED, false))));
        assertEquals(QueuedMessages.Urgency.WAITING, QueuedMessages.urgency(Arrays.asList(
                message("t1", Outbox.State.QUEUED, false), message("t1", Outbox.State.QUEUED, true),
                message("t1", Outbox.State.SENDING, false))));
        assertEquals(QueuedMessages.Urgency.GONE, QueuedMessages.urgency(Arrays.asList(
                message("t1", Outbox.State.UNSURE, false), message("t1", Outbox.State.GONE, false))));
    }
}
