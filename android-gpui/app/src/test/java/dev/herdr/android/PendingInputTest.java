package dev.herdr.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class PendingInputTest {
    @Test
    public void held_input_is_sent_once_in_order() {
        PendingInput pending = new PendingInput();
        assertTrue(pending.add("t1", "a"));
        assertTrue(pending.add("t1", "b"));
        assertEquals("a", pending.next("t1"));
        // Not yet accepted (the send failed): it is offered again, not lost.
        assertEquals("a", pending.next("t1"));
        pending.sent();
        assertEquals("b", pending.next("t1"));
        pending.sent();
        assertNull(pending.next("t1"));
        assertTrue(pending.isEmpty());
    }

    @Test
    public void input_for_another_pane_is_discarded_and_counted_never_redirected() {
        PendingInput pending = new PendingInput();
        pending.add("t1", "a");
        pending.add("t1", "b");
        pending.add("t2", "c");
        assertEquals("c", pending.next("t2"));
        assertEquals(2, pending.takeDiscarded());
        assertEquals(0, pending.takeDiscarded());
    }

    @Test
    public void the_hold_is_bounded_and_needs_a_pane() {
        PendingInput pending = new PendingInput();
        assertFalse(pending.add("", "a"));
        for (int i = 0; i < PendingInput.MAX_COMMANDS; i++) assertTrue(pending.add("t1", "x"));
        assertFalse(pending.add("t1", "x"));
        PendingInput large = new PendingInput();
        assertFalse(large.add("t1", new String(new char[PendingInput.MAX_CHARS + 1])));
    }

    @Test
    public void a_restarted_herdr_drops_everything_held() {
        PendingInput pending = new PendingInput();
        pending.add("t1", "a");
        pending.add("t1", "b");
        pending.discardAll();
        assertTrue(pending.isEmpty());
        assertEquals(2, pending.takeDiscarded());
    }
}
