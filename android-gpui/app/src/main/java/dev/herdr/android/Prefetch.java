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
 * and connected (item 77, Wolf's ask 2), so a switch with no link has
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

    /** The day's data spent on reads, against the mobile-data budget. */
    static final class Budget {
        long day;
        long used;

        /** Whether another read may start on {@code day} (local epoch day). */
        boolean allows(long day, boolean metered) {
            if (!metered) return true;
            return (day != this.day ? 0 : used) < METERED_DAY_BYTES;
        }

        /** A read of {@code bytes} on {@code day}; only mobile data counts. */
        void spend(long day, long bytes, boolean metered) {
            if (day != this.day) {
                this.day = day;
                used = 0;
            }
            if (metered) used += Math.max(0, bytes);
        }
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
