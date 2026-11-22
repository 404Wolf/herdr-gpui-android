package dev.herdr.android;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * Where one usage look's time went (factory-setup.32), logged to herdr-ops:
 * waiting for its worker thread, the native call (the core session's queue
 * plus the RPC), the RPC alone, and the core's own run of aswap, as the
 * reply's {@code timing} says. Pure; the times come in as arguments.
 */
final class UsageTiming {
    final long queuedMs, callMs, rpcMs, execMs;

    UsageTiming(long queuedMs, long callMs, long rpcMs, long execMs) {
        this.queuedMs = queuedMs;
        this.callMs = callMs;
        this.rpcMs = rpcMs;
        this.execMs = execMs;
    }

    /** The reply's {@code timing}; -1 for what it does not say. */
    static UsageTiming of(long queuedMs, long callMs, String reply) {
        long rpc = -1, exec = -1;
        try {
            JSONObject timing = new JSONObject(reply == null ? "" : reply).optJSONObject("timing");
            if (timing != null) {
                rpc = timing.optLong("rpc_ms", -1);
                exec = timing.isNull("exec_ms") ? -1 : timing.optLong("exec_ms", -1);
            }
        } catch (JSONException e) {
            // No timing in an unreadable reply: the parse says what is wrong with it.
        }
        return new UsageTiming(queuedMs, callMs, rpc, exec);
    }

    /** "usage.look 912 ms: queued 3 ms, session 840 ms, rpc 72 ms, aswap 60 ms"; -1s left out. */
    String line() {
        StringBuilder s = new StringBuilder("usage.look ").append(queuedMs + callMs).append(" ms: queued ")
                .append(queuedMs).append(" ms");
        if (rpcMs >= 0) {
            s.append(", session ").append(Math.max(0, callMs - rpcMs)).append(" ms, rpc ").append(rpcMs).append(" ms");
        } else {
            s.append(", call ").append(callMs).append(" ms");
        }
        if (execMs >= 0) s.append(", aswap ").append(execMs).append(" ms");
        return s.toString();
    }

    /** "usage.sheet fresh rows 4123 ms after open"; at open, "kept rows" or "no rows" (asking). */
    static String drawn(String what, long sinceOpenMs) {
        return "usage.sheet " + what + " rows " + sinceOpenMs + " ms after open";
    }

    /** ": the main thread took 600 ms to run it, drawing 40 ms"; the wait left out when unknown. */
    static String onMain(long waitedMs, long drawingMs) {
        return waitedMs >= 0 ? ": the main thread took " + waitedMs + " ms to run it, drawing " + drawingMs + " ms"
                : ": drawing " + drawingMs + " ms";
    }

    static void log(String line) {
        android.util.Log.i("herdr-ops", line);
    }
}
