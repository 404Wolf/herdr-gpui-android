package dev.herdr.android;

/**
 * How much of the phone the offline cache may use (the offline redesign's §7.2, Wolf's steer: be
 * generous, keep a lot for new and recent sessions, let very old ones phase out). One budget for
 * the three stores, on disk only: transcripts, pane copies and referenced files.
 *
 * <ul>
 *   <li>In all: {@link #TOTAL_BYTES} (4 GiB), or {@link #FREE_SHARE} of the free space measured
 *       when the budget is made, whichever is smaller.
 *   <li>Split 5 : 1 : 2 between transcripts, pane copies and referenced files (2.5, 0.5 and 1 GiB
 *       of a full budget).
 *   <li>Nothing is written while the phone has under {@link #FREE_FLOOR_BYTES} free; reads go on.
 * </ul>
 *
 * <p>A transcript's depth tapers with its age: how long since it last grew (a read gave new
 * lines) or was opened. Its newest lines are kept; past {@link #GONE_MS} it goes. Eviction only
 * ever touches the phone's copies, never a session or a file on a host.
 *
 * <p>Pure: the free space is passed in, so the numbers are unit-tested.
 */
final class OfflineBudget {
    static final long MIB = 1024L * 1024;
    static final long GIB = 1024 * MIB;
    static final long TOTAL_BYTES = 4 * GIB;
    /** At most this share of the free space at the time the budget is made. */
    static final double FREE_SHARE = 0.02;
    static final long FREE_FLOOR_BYTES = 8 * GIB;

    static final long DAY_MS = 24L * 60 * 60 * 1000;
    /** A transcript this old (since it last grew or was opened) is evicted. */
    static final long GONE_MS = 90 * DAY_MS;

    /** One step of the age taper: up to {@code maxAgeMs}, keep {@code depthBytes} of the newest lines. */
    static final class Tier {
        final String name;
        final long maxAgeMs;
        final long depthBytes;

        Tier(String name, long maxAgeMs, long depthBytes) {
            this.name = name;
            this.maxAgeMs = maxAgeMs;
            this.depthBytes = depthBytes;
        }
    }

    /** Hot (open or used in the last day), warm, cool, cold; past cold, gone. */
    static final Tier[] TIERS = {
        new Tier("hot", DAY_MS, 32 * MIB),
        new Tier("warm", 7 * DAY_MS, 8 * MIB),
        new Tier("cool", 30 * DAY_MS, 2 * MIB),
        new Tier("cold", GONE_MS, 512 * 1024),
    };

    final long totalBytes;
    final long transcriptBytes;
    final long paneBytes;
    final long refBytes;
    final long freeFloorBytes;

    private OfflineBudget(long totalBytes, long freeFloorBytes) {
        this.totalBytes = totalBytes;
        this.transcriptBytes = totalBytes / 8 * 5;
        this.paneBytes = totalBytes / 8;
        this.refBytes = totalBytes / 8 * 2;
        this.freeFloorBytes = freeFloorBytes;
    }

    /** The budget for a phone with {@code freeBytes} free now. */
    static OfflineBudget forFree(long freeBytes) {
        long share = (long) (Math.max(0, freeBytes) * FREE_SHARE);
        return new OfflineBudget(Math.min(TOTAL_BYTES, share), FREE_FLOOR_BYTES);
    }

    /** A budget of exactly {@code totalBytes}, for tests. */
    static OfflineBudget of(long totalBytes, long freeFloorBytes) {
        return new OfflineBudget(totalBytes, freeFloorBytes);
    }

    /** The tier of something last used {@code ageMs} ago, or null when it is past cold (gone). */
    static Tier tier(long ageMs) {
        for (Tier t : TIERS) if (ageMs < t.maxAgeMs) return t;
        return null;
    }

    /** How deep a transcript last used {@code ageMs} ago is kept; 0 when it goes. */
    static long depth(long ageMs) {
        Tier t = tier(Math.max(0, ageMs));
        return t == null ? 0 : t.depthBytes;
    }
}
