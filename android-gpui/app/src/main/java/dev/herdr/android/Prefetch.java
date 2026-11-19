package dev.herdr.android;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Which panes the phone reads into its offline cache while the app is open
 * and connected (item 77, ask 2), so a switch with no link has
 * something to show. Pure: the terminal activity runs one read at a time,
 * only after the person stopped touching the screen for {@link #QUIET_MS}.
 *
 * <ul>
 *   <li>Only panes whose status marker (status, since when, turn) changed
 *       since their copy was made, never the one on screen (it is saved as
 *       it is shown), never one that works or waits on a dialog.</li>
 *   <li>The project on screen first, then the Deacon and PMs.</li>
 *   <li>A pane asked for is not asked again until its marker moves, read or
 *       refused: a refused read leaves the copy the phone has.</li>
 *   <li>Mobile data: at most {@link #METERED_DAY_BYTES} a day; Wi-Fi: no limit.</li>
 * </ul>
 */
final class Prefetch {
    private Prefetch() { }

    static final long METERED_DAY_BYTES = 20L * 1024 * 1024;
    static final long QUIET_MS = 2_000;
    static final long EVERY_MS = 3_000;

    /** A pane's status marker as the list has it: when it changes, its copy is stale. */
    static String marker(JSONObject pane) {
        return pane.optString("status", "") + "/" + pane.optLong("status_ms", 0) + "/" + pane.optLong("turn", 0);
    }

    static boolean busy(JSONObject pane) {
        String status = pane.optString("status", "");
        return status.equals("working") || status.equals("blocked") || status.equals("error");
    }

    /** The project a pane belongs to: its label, else its space. */
    private static String project(JSONObject pane) {
        String project = pane.optString("project", "");
        return project.isEmpty() ? "space:" + pane.optString("workspace_id", "") : project;
    }

    /**
     * Pane ids to read next, best first. {@code seen}: pane id → the marker
     * its copy (or its last read) had.
     */
    static List<String> plan(JSONArray panes, String onScreen, Map<String, String> seen) {
        if (panes == null) return Collections.emptyList();
        String here = null;
        for (int i = 0; i < panes.length(); i++) {
            JSONObject pane = panes.optJSONObject(i);
            if (pane != null && pane.optString("id", "").equals(onScreen)) here = project(pane);
        }
        List<String> first = new ArrayList<>();
        List<String> then = new ArrayList<>();
        for (int i = 0; i < panes.length(); i++) {
            JSONObject pane = panes.optJSONObject(i);
            if (pane == null) continue;
            String id = pane.optString("id", "");
            if (id.isEmpty() || id.equals(onScreen) || busy(pane) || marker(pane).equals(seen.get(id))) continue;
            String role = FleetTree.role(pane.optString("role", ""));
            if (here != null && here.equals(project(pane))) first.add(id);
            else if (role.equals("deacon") || role.equals("pm")) then.add(id);
        }
        first.addAll(then);
        return first;
    }

    /**
     * The most one read may take on the link (a stitched read of 1000 lines,
     * up to a 1 MiB copy, plus the reply's framing and the small questions
     * before it): reserved before a read on mobile data, so a read never
     * starts when it could pass the cap.
     */
    static final long RESERVE_BYTES = 2L * 1024 * 1024;

    /**
     * The day's data spent on reads, against the mobile-data budget. Only a
     * later day starts a new budget: a clock or time zone moving back keeps
     * what was spent.
     */
    static final class Budget {
        long day;
        long used;

        private long spent(long day) {
            return day > this.day ? 0 : used;
        }

        private void roll(long day) {
            if (day > this.day) {
                this.day = day;
                used = 0;
            }
        }

        /** Whether a read may start on {@code day} (local epoch day): one more reservation fits. */
        boolean allows(long day, boolean metered) {
            return !metered || spent(day) + RESERVE_BYTES <= METERED_DAY_BYTES;
        }

        /** {@code bytes} taken on {@code day}; only mobile data counts. */
        void spend(long day, long bytes, boolean metered) {
            roll(day);
            if (metered) used += Math.max(0, bytes);
        }

        /** Starts a read: false when it could pass the cap; else, on mobile data, its reservation is spent. */
        boolean reserve(long day, boolean metered) {
            if (!allows(day, metered)) return false;
            spend(day, metered ? RESERVE_BYTES : 0, metered);
            return true;
        }

        /**
         * A read that ended with {@code received} bytes on the link: they
         * replace its reservation. Mobile data at its start or at its end
         * counts. A read that failed or timed out never comes here: its
         * reservation stays spent.
         */
        void settle(long received, boolean meteredAtStart, boolean meteredAtEnd) {
            if (meteredAtStart) used += Math.max(0, received) - RESERVE_BYTES;
            else if (meteredAtEnd) used += Math.max(0, received);
            used = Math.max(0, used);
        }
    }

    /** What the native read of one pane gave (see RemoteNative.prefetchPane). */
    static final class Read {
        final boolean refused;
        final long received;
        /** The copy (unsealed), or null. */
        final byte[] copy;

        Read(boolean refused, long received, byte[] copy) {
            this.refused = refused;
            this.received = received;
            this.copy = copy;
        }

        /** One byte (1: refused), the bytes received (8, big-endian), then the copy. */
        static Read parse(byte[] bytes) {
            if (bytes == null || bytes.length < 9) throw new IllegalArgumentException("not a prefetch result");
            long received = java.nio.ByteBuffer.wrap(bytes, 1, 8).getLong();
            byte[] copy = bytes.length > 9 ? java.util.Arrays.copyOfRange(bytes, 9, bytes.length) : null;
            return new Read(bytes[0] == 1, received, copy);
        }
    }

    interface Reader {
        /** Reads one pane; throws when the read failed or timed out. */
        Read read(String pane) throws Exception;
    }

    /**
     * One prefetch read of {@code pane} (its cache file {@code key}) within {@code budget}: reserved
     * before (none when it could pass the cap), charged what it received
     * after, the reservation kept when it failed. A pane read, or refused by
     * the pane or the core, is marked {@code seen} at {@code marker} (not
     * asked again until it moves); a passing failure leaves it to the next
     * tick. Returns the copy to store, or null.
     */
    static byte[] step(Budget budget, Map<String, String> seen, String key, String pane, String marker, long day,
            java.util.function.BooleanSupplier metered, Reader reader) {
        boolean atStart = metered.getAsBoolean();
        if (!budget.reserve(day, atStart)) return null;
        Read read;
        try {
            read = reader.read(pane);
        } catch (Exception | LinkageError failed) {
            return null;
        }
        budget.settle(read.received, atStart, metered.getAsBoolean());
        if (read.copy != null || read.refused) seen.put(key, marker);
        return read.copy;
    }

    /** What the budget and the panes read are kept in across restarts. */
    interface Store {
        String seen();
        long day();
        long used();
        void save(String seen, long day, long used);
    }

    static Budget load(Store store, Budget into) {
        into.day = store.day();
        into.used = store.used();
        return into;
    }

    static void save(Store store, Map<String, String> seen, Budget budget) {
        store.save(encode(bounded(seen, MAX_SEEN)), budget.day, budget.used);
    }

    /** At most this many panes are remembered as read. */
    static final int MAX_SEEN = 4 * PaneSnapshots.MAX_FILES;

    /** {@code seen} cut to {@code max} entries, never wiped: the ones beyond are read again. */
    static Map<String, String> bounded(Map<String, String> seen, int max) {
        if (seen.size() <= max) return seen;
        java.util.Iterator<String> it = seen.keySet().iterator();
        while (seen.size() > max && it.hasNext()) {
            it.next();
            it.remove();
        }
        return seen;
    }

    /** {@code seen} as stored: a JSON object of file name → marker. */
    static String encode(Map<String, String> seen) {
        return new JSONObject(seen).toString();
    }

    static Map<String, String> decode(String text) {
        Map<String, String> out = new HashMap<>();
        try {
            JSONObject root = new JSONObject(text == null || text.isEmpty() ? "{}" : text);
            for (java.util.Iterator<String> it = root.keys(); it.hasNext(); ) {
                String key = it.next();
                out.put(key, root.optString(key, ""));
            }
        } catch (JSONException unreadable) {
            out.clear();
        }
        return out;
    }
}
