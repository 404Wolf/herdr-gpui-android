package dev.herdr.android;

import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * How long a blocking phone op (usage.look, beads.stores, beads.detail) held
 * the core session. Until those ops pump the session's commands while they
 * wait (t3jk, after PR #55), a slow machine.exec stalls typing, held sends and
 * focus for its whole run, so each one's duration is logged ("herdr-ops") and
 * a slow one says so. Only the op's name and milliseconds are logged, never
 * its reply.
 */
final class OpTiming {
    private OpTiming() { }

    /** Past this, an op is long enough for typing to feel it. */
    static final long SLOW_MS = 500;

    /** The log line for one op: "beads.stores 812 ms (slow: input waited)". */
    static String line(String op, long millis) {
        return op + " " + millis + " ms" + (millis >= SLOW_MS ? " (slow: input waited)" : "");
    }

    /** Runs {@code call}, then reports its line to {@code log}, even when it throws. */
    static <T> T timed(String op, Supplier<T> call, LongSupplier clock, java.util.function.Consumer<String> log) {
        long start = clock.getAsLong();
        try {
            return call.get();
        } finally {
            log.accept(line(op, clock.getAsLong() - start));
        }
    }

    /** {@link #timed} on the device clock, to logcat. */
    static <T> T timed(String op, Supplier<T> call) {
        return timed(op, call, android.os.SystemClock::elapsedRealtime, OpTiming::log);
    }

    private static void log(String line) {
        if (line.endsWith(")")) android.util.Log.w("herdr-ops", line);
        else android.util.Log.i("herdr-ops", line);
    }

    /** Words for the view at most this long. */
    static final int REASON_MAX = 120;

    /**
     * The daemon's words made safe to show: untrusted text, so one line with
     * no control characters, paths hidden ("…"), at most {@link #REASON_MAX}.
     */
    static String clean(String text) {
        String line = text.replaceAll("\u001b\\[[0-9;?]*[A-Za-z]", "").replaceAll("\\p{Cntrl}+", " ").replaceAll("(~|\\.{0,2})?/[^\\s\"']*", "…")
                .replaceAll("\\s+", " ").trim();
        return line.length() > REASON_MAX ? line.substring(0, REASON_MAX - 1) + "…" : line;
    }

    /**
     * Why a look failed, in words for the view: the core session's own error
     * (the native layer throws it as an IllegalStateException with its
     * display text, written for people), bounded; anything else is that the
     * machine could not be asked. Logged under "herdr-ops" for diagnosis.
     */
    static String reason(String op, RuntimeException failure) {
        String said = failure instanceof IllegalStateException ? failure.getMessage() : null;
        String reason = said == null ? "" : clean(said);
        if (reason.isEmpty()) reason = "can't reach the machine";
        try {
            android.util.Log.w("herdr-ops", op + " failed: " + reason);
        } catch (RuntimeException notOnAndroid) {
            // JVM unit tests have no logcat.
        }
        return reason;
    }

    /** Retries of a busy machine: the first wait, doubling up to the last. */
    static final long BUSY_FIRST_MS = 5_000, BUSY_MAX_MS = 60_000;

    /** The wait before retry {@code attempt} (0 first) of a busy machine: 5 s, 10 s, 20 s… at most 60 s. */
    static long busyBackoff(int attempt) {
        long wait = BUSY_FIRST_MS;
        for (int i = 0; i < attempt && wait < BUSY_MAX_MS; i++) wait *= 2;
        return Math.min(wait, BUSY_MAX_MS);
    }
}
