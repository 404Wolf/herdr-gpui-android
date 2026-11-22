package dev.herdr.android;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * A rail row drawn from a machine's cached list (its last list, or Home's
 * cache: no live mirror yet) is read-only until that machine answers
 * (factory audit, PR 7). A tap switches to the machine without forcing the
 * cached terminal id, then opens that terminal only if the machine's own
 * first list still has it with the same creation time, as pins are checked:
 * an id a restarted core gave to another terminal never opens. Pure.
 */
final class RailArrival {
    enum Verdict { GO, ENDED, NOT_LISTED }

    final String hostId, paneId, name;
    /** The cached terminal's created_ms, or 0 when the cache did not say (then only its id is checked). */
    final long createdMs;

    RailArrival(String hostId, String paneId, long createdMs, String name) {
        this.hostId = hostId;
        this.paneId = paneId;
        this.createdMs = createdMs;
        this.name = name == null ? "" : name;
    }

    /** {@code paneId}'s created_ms in {@code panes}, or 0. */
    static long createdOf(JSONArray panes, String paneId) {
        JSONObject p = pane(panes, paneId);
        return p == null ? 0 : p.optLong("created_ms", 0);
    }

    /** The machine's own list now: the same terminal (id and creation time), ended, or not listed. */
    Verdict check(JSONArray panes) {
        JSONObject p = pane(panes, paneId);
        if (p == null) return Verdict.NOT_LISTED;
        long now = p.optLong("created_ms", 0);
        return createdMs > 0 && now > 0 && now != createdMs ? Verdict.ENDED : Verdict.GO;
    }

    /** What the screen says when it does not open it. */
    String refusal(String machine) {
        String who = name.isEmpty() ? "That terminal" : name;
        return who + " isn't on " + (machine == null || machine.isEmpty() ? "that computer" : machine) + " any more";
    }

    private static JSONObject pane(JSONArray panes, String paneId) {
        for (int i = 0; panes != null && paneId != null && i < panes.length(); i++) {
            JSONObject p = panes.optJSONObject(i);
            if (p != null && paneId.equals(p.optString("id"))) return p;
        }
        return null;
    }
}
