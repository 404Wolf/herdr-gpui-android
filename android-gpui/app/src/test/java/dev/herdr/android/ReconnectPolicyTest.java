package dev.herdr.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ReconnectPolicyTest {
    @Test
    public void the_first_attempt_is_immediate_then_steps_double_up_to_eight_seconds() {
        ReconnectPolicy policy = new ReconnectPolicy();
        long[] seen = new long[8];
        for (int i = 0; i < seen.length; i++) seen[i] = policy.next(0);
        assertEquals(0, seen[0]);
        assertEquals(500, seen[1]);
        assertEquals(1000, seen[2]);
        assertEquals(2000, seen[3]);
        assertEquals(4000, seen[4]);
        for (int i = 5; i < seen.length; i++) assertEquals("never slower than 8 s", 8000, seen[i]);
    }

    @Test
    public void a_network_change_or_success_starts_the_ladder_again() {
        ReconnectPolicy policy = new ReconnectPolicy();
        for (int i = 0; i < 6; i++) policy.next(0);
        policy.reset();
        assertEquals(0, policy.attempts());
        assertEquals(0, policy.next(0));
    }

    @Test
    public void offlineRedesign_oneEpochMakesAtMostTwelveAttempts() {
        ReconnectPolicy policy = new ReconnectPolicy();
        long now = 0;
        for (int i = 0; i < ReconnectPolicy.MAX_ATTEMPTS; i++) {
            long delay = policy.next(now);
            assertTrue("attempt " + (i + 1) + " runs", delay >= 0);
            now += delay;
        }
        assertFalse(policy.exhausted());
        assertEquals(ReconnectPolicy.EXHAUSTED, policy.next(now));
        assertTrue(policy.exhausted());
        assertEquals("spent stays spent", ReconnectPolicy.EXHAUSTED, policy.next(now + 1));
        // A network arriving, or the user's tap, starts a new epoch at once.
        policy.reset();
        assertFalse(policy.exhausted());
        assertEquals(0, policy.next(now + 2));
    }

    @Test
    public void offlineRedesign_oneEpochTriesForAtMostNinetySeconds() {
        ReconnectPolicy policy = new ReconnectPolicy();
        assertEquals(0, policy.next(1_000));
        // Attempts that each hang until their deadline use the epoch up in time, not count.
        assertTrue(policy.next(1_000 + ReconnectPolicy.MAX_EPOCH_MS - 1) >= 0);
        assertEquals(ReconnectPolicy.EXHAUSTED, policy.next(1_000 + ReconnectPolicy.MAX_EPOCH_MS));
        assertTrue(policy.exhausted());
    }

    @Test
    public void offlineRedesign_anAttemptIsCutWellInsideTheEpoch() {
        assertTrue(ReconnectPolicy.ATTEMPT_MS * 3 < ReconnectPolicy.MAX_EPOCH_MS);
        assertEquals(10_000, ReconnectPolicy.ATTEMPT_MS);
    }

    @Test
    public void a_quiet_link_is_dead_sooner_just_after_a_network_change() {
        assertFalse(ReconnectPolicy.dead(2_100, false));
        assertFalse("one refresh interval is normal", ReconnectPolicy.dead(2_100, true));
        assertTrue(ReconnectPolicy.dead(ReconnectPolicy.SUSPECT_QUIET_MS, true));
        assertFalse(ReconnectPolicy.dead(ReconnectPolicy.SUSPECT_QUIET_MS, false));
        assertTrue(ReconnectPolicy.dead(ReconnectPolicy.DEAD_QUIET_MS, false));
    }

    @Test
    public void offlineRedesign_aLinkIsInDoubtOnlyWhenQuietPastARefreshJustAfterAChange() {
        // Quiet past one missed refresh, just after a network change: nothing is sent over it.
        assertTrue(ReconnectPolicy.suspect(ReconnectPolicy.DOUBT_QUIET_MS, true));
        assertFalse(ReconnectPolicy.suspect(ReconnectPolicy.DOUBT_QUIET_MS - 1, true));
        // Without a change, a quiet link is trusted until it is dead.
        assertFalse(ReconnectPolicy.suspect(ReconnectPolicy.DEAD_QUIET_MS - 1, false));
        // In doubt before it is given up: drags stay on the phone in between.
        assertTrue(ReconnectPolicy.DOUBT_QUIET_MS < ReconnectPolicy.SUSPECT_QUIET_MS);
        // Longer than one two-second refresh, so a healthy link is not doubted.
        assertTrue(ReconnectPolicy.DOUBT_QUIET_MS > 2_000);
    }

    @Test
    public void offlineRedesign_theSameNetworkValidatingAgainNeverLiftsTheBound() {
        // The same network validates again and again, however far apart (Slurm 11177: every
        // ~50 s on the emulator): each is an immediate attempt, counted, never a new epoch.
        ReconnectPolicy bounded = new ReconnectPolicy();
        assertEquals(0, bounded.next(0));
        for (int i = 1; i < ReconnectPolicy.MAX_ATTEMPTS; i++) assertEquals(0, bounded.arrived(i * 7_000L, false));
        assertEquals(ReconnectPolicy.EXHAUSTED, bounded.arrived(85_000, false));
        assertTrue(bounded.exhausted());
        // A network the epoch did not have starts a new one.
        assertEquals(0, bounded.arrived(86_000, true));
        assertFalse(bounded.exhausted());
        assertEquals(1, bounded.attempts());
    }
}
