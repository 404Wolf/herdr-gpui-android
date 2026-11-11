package dev.herdr.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ReconnectPolicyTest {
    @Test
    public void the_first_attempt_is_immediate_then_quick_steps_capped_at_two_seconds() {
        ReconnectPolicy policy = new ReconnectPolicy();
        long[] seen = new long[8];
        for (int i = 0; i < seen.length; i++) seen[i] = policy.next();
        assertEquals(0, seen[0]);
        assertEquals(250, seen[1]);
        assertEquals(500, seen[2]);
        assertEquals(1000, seen[3]);
        for (int i = 4; i < seen.length; i++) assertEquals("never slower than 2 s", 2000, seen[i]);
    }

    @Test
    public void a_network_change_or_success_starts_the_ladder_again() {
        ReconnectPolicy policy = new ReconnectPolicy();
        for (int i = 0; i < 6; i++) policy.next();
        policy.reset();
        assertEquals(0, policy.attempts());
        assertEquals(0, policy.next());
    }

    @Test
    public void a_quiet_link_is_dead_sooner_just_after_a_network_change() {
        assertFalse(ReconnectPolicy.dead(2_100, false));
        assertFalse("one refresh interval is normal", ReconnectPolicy.dead(2_100, true));
        assertTrue(ReconnectPolicy.dead(ReconnectPolicy.SUSPECT_QUIET_MS, true));
        assertFalse(ReconnectPolicy.dead(ReconnectPolicy.SUSPECT_QUIET_MS, false));
        assertTrue(ReconnectPolicy.dead(ReconnectPolicy.DEAD_QUIET_MS, false));
    }
}
