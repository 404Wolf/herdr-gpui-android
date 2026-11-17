package dev.herdr.android;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Review #22 finding 1: a reconnect begun for A must not adopt A after B was picked offline. */
public final class HostEpochTest {
    @Test public void anAttemptBegunBeforeAnotherPickIsStaleAndOneBegunAfterIsNot() {
        HostEpoch picks = new HostEpoch();
        int reconnectA = picks.now();
        assertFalse(picks.stale(reconnectA));
        // Wolf picks B offline while A is still connecting.
        picks.change();
        assertTrue("A's late success must be ignored", picks.stale(reconnectA));
        // The next attempt is for B, and it is current.
        int reconnectB = picks.now();
        assertFalse(picks.stale(reconnectB));
        // Each pick invalidates every attempt before it, however many.
        picks.change();
        picks.change();
        assertTrue(picks.stale(reconnectB));
    }
}
