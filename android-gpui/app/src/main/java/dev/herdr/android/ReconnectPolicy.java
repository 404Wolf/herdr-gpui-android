package dev.herdr.android;

/**
 * When to try the next reconnect, and when a live link counts as dead.
 * Pure, so the schedule is unit-tested; ConnectionMonitor owns the clock.
 *
 * A phone on a train loses the path for seconds at a time. Waiting for TCP to
 * notice takes far longer than the gap, so the session is replaced instead: the
 * first attempt is immediate, then steps that double up to eight seconds. The
 * attempts of one network (an epoch) are bounded: at most {@link #MAX_ATTEMPTS},
 * within {@link #MAX_EPOCH_MS}; then nothing runs until a network arrives or
 * validates, or the user asks, each of which starts a new epoch.
 */
final class ReconnectPolicy {
    /** Delays before attempts 1, 2, 3, ...; the last repeats. */
    static final long[] STEPS_MS = {0, 500, 1000, 2000, 4000, 8000};
    /** Attempts in one epoch before it is spent. */
    static final int MAX_ATTEMPTS = 12;
    /** How long one epoch may keep trying, from its first attempt. */
    static final long MAX_EPOCH_MS = 90_000;
    /**
     * How long one attempt may take to reach the host's list of panes. The SSH connect has its
     * own fifteen seconds; an attempt past this is closed, so a path that died mid-attempt does
     * not hold up the next one.
     */
    static final long ATTEMPT_MS = 10_000;
    /** {@link #next(long)}'s answer when the epoch is spent. */
    static final long EXHAUSTED = -1;


    /**
     * herdr-core is refreshed every two seconds, so this much silence on a
     * session that should be talking means the path is gone. Long enough that
     * a slow exec (bounded at ten seconds by the session) is not cut short.
     */
    static final long DEAD_QUIET_MS = 12_000;
    /** Just after a network change, a link that missed one refresh is not trusted. */
    static final long SUSPECT_QUIET_MS = 3_500;
    /**
     * Just after a network change, a link this quiet is in doubt: it has missed a refresh (the
     * core sends one every two seconds), so nothing is sent over it until it talks again or is
     * replaced. Losing every network says so sooner (ConnectionMonitor's no-network grace).
     */
    static final long DOUBT_QUIET_MS = 2_500;

    private int attempt;
    /** When the epoch's first attempt was asked for (uptime ms), or -1 before it. */
    private long startedAt = -1;

    /**
     * Delay before the next attempt, advancing the ladder; {@link #EXHAUSTED} once the epoch
     * has made {@link #MAX_ATTEMPTS} or run for {@link #MAX_EPOCH_MS} at {@code nowMs}.
     */
    long next(long nowMs) {
        if (startedAt < 0) startedAt = nowMs;
        if (exhausted(nowMs)) {
            spent = true;
            return EXHAUSTED;
        }
        long delay = STEPS_MS[Math.min(attempt, STEPS_MS.length - 1)];
        attempt++;
        return delay;
    }

    /**
     * A network arrived or validated at {@code nowMs}: the next attempt is immediate. Only a
     * network the epoch did not have ({@code newNetwork}) starts a new epoch; the same network
     * validating again (it flaps, or a check runs again) is tried at once but counts against the
     * epoch, so it can never lift the bound. {@link #EXHAUSTED} when it is spent.
     */
    long arrived(long nowMs, boolean newNetwork) {
        if (newNetwork) reset();
        if (next(nowMs) == EXHAUSTED) return EXHAUSTED;
        return 0;
    }

    /** Whether the epoch is spent, at {@code nowMs}. */
    boolean exhausted(long nowMs) {
        return attempt >= MAX_ATTEMPTS || startedAt >= 0 && nowMs - startedAt >= MAX_EPOCH_MS;
    }

    /** Whether the epoch was found spent: its attempts are all made (time is checked by {@link #next}). */
    private boolean spent;

    /** Whether the last {@link #next(long)} found the epoch spent. */
    boolean exhausted() {
        return spent;
    }

    /** The link works again, a network arrived, or the user asked: a new epoch from zero. */
    void reset() {
        attempt = 0;
        startedAt = -1;
        spent = false;
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

    /** Whether a live link quiet for {@code quietMs} is in doubt: only just after a network change. */
    static boolean suspect(long quietMs, boolean networkJustChanged) {
        return networkJustChanged && quietMs >= DOUBT_QUIET_MS;
    }
}
