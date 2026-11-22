package dev.herdr.android;

/**
 * How often the rail's mirrors of other machines read their lists (factory
 * audit, PR 8, the Java side). With a machine connected and more than one
 * saved, the rail shows only that machine (its picker names the others), so
 * the others are hidden: read every {@link #HIDDEN_MS}, enough for the
 * picker's counts, not every {@link #SHOWN_MS}; and a hidden machine's change
 * never rebuilds the rail. Pure.
 */
final class HostSampling {
    private HostSampling() { }

    static final long SHOWN_MS = 3_000, HIDDEN_MS = 20_000;

    /** The rail shows only the machine on screen (the picker names the rest). */
    static boolean onlyCurrent(String currentHostId, boolean connected, int savedHosts) {
        return currentHostId != null && connected && savedHosts > 1;
    }

    /** Whether {@code hostId}'s rows are on the rail now. */
    static boolean shown(String hostId, String currentHostId, boolean connected, int savedHosts) {
        return !onlyCurrent(currentHostId, connected, savedHosts) || hostId.equals(currentHostId);
    }

    /** The wait before a mirror's next read. */
    static long every(boolean shown) {
        return shown ? SHOWN_MS : HIDDEN_MS;
    }
}
