package dev.herdr.android;

import static org.junit.Assert.assertEquals;

import java.util.Arrays;
import java.util.Collections;
import org.junit.Test;

/** What the Enter badge and the queue sheet say about the outbox. */
public final class QueuedMessagesTest {
    private static Outbox.Message message(String pane, Outbox.State state) {
        return new Outbox.Message("id", "h", pane, "text", state);
    }

    private static Outbox.Message gone(String pane) {
        return new Outbox.Message("id", "h", pane, "text", Outbox.State.NEEDS_YOU, Outbox.Reason.GONE);
    }

    @Test public void eachStateSaysWhatIsHappeningInAFewWords() {
        assertEquals("held · destination not verified", QueuedMessages.label(message("t1", Outbox.State.PENDING), "t1"));
        assertEquals("sending…", QueuedMessages.label(message("t1", Outbox.State.SENDING), "t1"));
        assertEquals("sent · check it arrived", QueuedMessages.label(message("t1", Outbox.State.SENT), "t1"));
        assertEquals("may have been sent", QueuedMessages.label(message("t1", Outbox.State.NEEDS_YOU), "t1"));
        // A gone message names the pane it was for.
        assertEquals("not sent · t4 gone", QueuedMessages.label(gone("t4"), "t1"));
    }

    @Test public void aMultiLineMessageReadsOnOneLineWithItsBreaksMarked() {
        assertEquals("first ⏎ second ⏎ third", QueuedMessages.oneLine("  first\n  second\n\nthird \n"));
        assertEquals("a b", QueuedMessages.oneLine("a\tb"));
        assertEquals("❯ text  · sending…", QueuedMessages.row(message("t1", Outbox.State.SENDING), "t1"));
    }

    @Test public void theBadgeCountsAndHidesWhenEmpty() {
        assertEquals("", QueuedMessages.badge(0));
        assertEquals("2", QueuedMessages.badge(2));
        assertEquals("9", QueuedMessages.badge(9));
        assertEquals("9+", QueuedMessages.badge(10));
        assertEquals("1 held message, each sent by your tap", QueuedMessages.title(1));
        assertEquals("3 held messages, each sent by your tap", QueuedMessages.title(3));
    }

    @Test public void theBadgeTakesTheMostPressingMessagesColour() {
        assertEquals(QueuedMessages.Urgency.NONE, QueuedMessages.urgency(Collections.emptyList()));
        assertEquals(QueuedMessages.Urgency.NONE, QueuedMessages.urgency(Arrays.asList(message("t1", Outbox.State.SENT))));
        assertEquals(QueuedMessages.Urgency.QUEUED, QueuedMessages.urgency(Arrays.asList(
                message("t1", Outbox.State.PENDING))));
        assertEquals(QueuedMessages.Urgency.WAITING, QueuedMessages.urgency(Arrays.asList(
                message("t1", Outbox.State.PENDING), message("t1", Outbox.State.NEEDS_YOU),
                message("t1", Outbox.State.SENDING))));
        assertEquals(QueuedMessages.Urgency.GONE, QueuedMessages.urgency(Arrays.asList(
                message("t1", Outbox.State.NEEDS_YOU), gone("t1"))));
    }
}
