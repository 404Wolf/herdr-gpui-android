package dev.herdr.android;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** One engine for the process: only the window that started a session owns it. */
public final class DictationOwnerTest {
    @Test public void onlyTheStartingWindowOwnsTheSession() {
        Object first = new Object(), second = new Object();
        Dictation.own(first);
        try {
            assertTrue(Dictation.owns(first));
            assertTrue(Dictation.busyElsewhere(second));
            assertFalse(Dictation.busyElsewhere(first));
            // Another window ending (or closing) leaves the first's session alone.
            Dictation.release(second);
            assertTrue(Dictation.owns(first));
            Dictation.release(first);
            assertFalse(Dictation.busyElsewhere(second));
            assertFalse(Dictation.owns(first));
        } finally {
            Dictation.release(first);
        }
    }
}
