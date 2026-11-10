package dev.herdr.android;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/** The deacon view's words for a status and for how long it has held. */
public final class HomeAgentTextTest {
    @Test public void aBlockedAgentSaysWhatItNeeds() {
        assertEquals("needs you", HomeActivity.statusWord("blocked", ""));
        assertEquals("needs you: permission", HomeActivity.statusWord("blocked", "permission"));
        assertEquals("working", HomeActivity.statusWord("working", "question"));
        assertEquals("no status", HomeActivity.statusWord("", ""));
    }

    @Test public void ageRoundsDownAndHidesWhenUnknown() {
        long now = 10L * 24 * 60 * 60_000;
        assertEquals("", HomeActivity.age(0, now));
        assertEquals("now", HomeActivity.age(now - 30_000, now));
        // A core clock a little ahead of the phone's is still "now".
        assertEquals("now", HomeActivity.age(now + 5_000, now));
        assertEquals("12m", HomeActivity.age(now - 12 * 60_000, now));
        assertEquals("47h", HomeActivity.age(now - 47 * 60 * 60_000, now));
        assertEquals("3d", HomeActivity.age(now - 3 * 24 * 60 * 60_000, now));
    }
}
