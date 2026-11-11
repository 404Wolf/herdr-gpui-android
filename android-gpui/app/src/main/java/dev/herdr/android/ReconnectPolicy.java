package dev.herdr.android;

/**
 * When to try the next reconnect, and when a live link counts as dead.
 * Pure, so the schedule is unit-tested; ConnectionMonitor owns the clock.
 *
 * A phone on a train loses the path for seconds at a time. Waiting for TCP to
 * notice takes far longer than the gap, so the session is replaced instead: the
 * first attempt is immediate, then quick steps capped at two seconds, forever
 * while the terminal is on screen. A network change starts the ladder again.
 */
final class ReconnectPolicy {
    /** Delays before attempts 1, 2, 3, ...; the last repeats. */
    static final long[] STEPS_MS = {0, 250, 500, 1000, 2000};
    /**
     * herdr-core is refreshed every two seconds, so this much silence on a
     * session that should be talking means the path is gone. Long enough that
     * a slow exec (bounded at ten seconds by the session) is not cut short.
     */
    static final long DEAD_QUIET_MS = 12_000;
    /** Just after a network change, a link that missed one refresh is not trusted. */
    static final long SUSPECT_QUIET_MS = 3_500;

    private int attempt;

    /** Delay before the next attempt, advancing the ladder. */
    long next() {
        long delay = STEPS_MS[Math.min(attempt, STEPS_MS.length - 1)];
        if (attempt < Integer.MAX_VALUE) attempt++;
        return delay;
    }

    /** The link works again, or a new network appeared: start from zero. */
    void reset() {
        attempt = 0;
    }

    int attempts() {
        return attempt;
    }

    /**
     * Whether a session quiet for {@code quietMs} should be replaced. After a
     * network change the old socket is usually bound to a path that no longer
     * exists, so a shorter silence is enough.
     */
    static boolean dead(long quietMs, boolean networkJustChanged) {
        return quietMs >= (networkJustChanged ? SUSPECT_QUIET_MS : DEAD_QUIET_MS);
    }
}
