package dev.herdr.android;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Where a new session to a host lands (audit PR 12, from GpuiTerminalActivity.awaitHostPane): it
 * waits for the host's list, picks the pane (an explicit pick, then on a machine switch that
 * computer's live Deacon, then the pane this phone last used there, then one doing something),
 * focuses it and waits until it is attached, saying why when herdr-core refuses it. The native
 * session, the remembered panes and time come in from outside, so every path is tested on the
 * JVM (HostLandingTest). Runs on the connector thread, never the UI thread.
 */
final class HostLanding {
    /** After its list, the most a switch waits for its pane (and a Deacon instead) to land. */
    static final long LANDING_MS = 30_000;
    /** Each landing's own wait, within {@link #LANDING_MS}. */
    static final long FOCUS_MS = 20_000;
    /** A machine switch's extra wait for the spaces, which name a Deacon known only by its space. */
    static final long SPACES_MS = 1_500;
    static final long STEP_MS = 150;

    /** The session being opened: its view, and asking it to attach a pane. */
    interface Link {
        JSONObject poll() throws Exception;

        void focus(String pane) throws Exception;
    }

    /** The pane this phone last used on a host, per host. */
    interface Memory {
        String remembered(String hostId);

        void remember(String hostId, String pane);
    }

    /** Waiting between polls; a test's clock advances instead. */
    interface Pause {
        void sleep(long ms) throws InterruptedException;
    }

    /** herdr-core refused to attach the pane a switch was for, and there was nothing to land on instead. */
    static final class Refused extends Exception {
        final String pane, reason;

        Refused(String pane, String reason) {
            super("refused " + pane + ": " + reason);
            this.pane = pane;
            this.reason = reason;
        }
    }

    private final Link link;
    private final Memory memory;
    private final java.util.function.LongSupplier clock;
    private final Pause pause;

    HostLanding(Link link, Memory memory, java.util.function.LongSupplier clock, Pause pause) {
        this.link = link;
        this.memory = memory;
        this.clock = clock;
        this.pause = pause;
    }

    /**
     * Waits for the new session, focuses its pane and returns that id. {@code machineSwitch}:
     * another computer was picked, so it lands on that computer's live Deacon
     * (FleetTree.switchLanding) unless a live terminal there was named. Throws when the session
     * closed, never listed within {@code readyWithinMs}, or refused the pane with nothing else
     * to land on ({@link Refused}).
     */
    String await(String hostId, String forcedPane, boolean machineSwitch, long readyWithinMs) throws Exception {
        long until = clock.getAsLong() + readyWithinMs;
        // Everything this waits for, together: the list, the spaces and every landing (audit A1).
        final long overall = until + LANDING_MS;
        JSONObject state = null;
        boolean listed = false;
        while (clock.getAsLong() < until) {
            state = link.poll();
            String phase = state.optString("state");
            // Why it closed (a timeout, a refusal, a host key), not only that it did.
            if ("closed".equals(phase)) throw new IllegalStateException("closed: " + state.optString("status"));
            // The pane list arrives with the first snapshot; an empty array is not readiness.
            JSONArray panes = state.optJSONArray("panes");
            listed = "ready".equals(phase) || panes != null && panes.length() > 0;
            if (listed) break;
            pause.sleep(STEP_MS);
        }
        // Past its time a dead path holds up nothing: the caller closes the handle.
        if (!listed) throw new IllegalStateException("no answer within " + readyWithinMs / 1000 + " s");
        for (long spaces = clock.getAsLong() + SPACES_MS; machineSwitch && !FleetPanes.hasSpaces(state)
                && clock.getAsLong() < spaces; ) {
            pause.sleep(STEP_MS);
            state = link.poll();
        }
        JSONArray panes = state == null ? null : state.optJSONArray("panes");
        if (panes == null || panes.length() == 0) throw new IllegalStateException("no panes");
        String pane = choose(state, panes, forcedPane, machineSwitch, memory.remembered(hostId));
        memory.remember(hostId, pane);
        if (pane.isEmpty()) throw new IllegalStateException("no pane id");
        // A pane picked offline while this connected (item 77) is where it lands.
        for (int pick = 0; pick < 3; pick++) {
            link.focus(pane);
            until = Math.min(clock.getAsLong() + FOCUS_MS, overall);
            boolean landed = false;
            while (!landed && clock.getAsLong() < until) {
                state = link.poll();
                landed = pane.equals(state.optString("pane_id")) && "ready".equals(state.optString("state"));
                // herdr-core refused it (audit A3): say why now, never wait out the 20 s.
                String refused = refusedReason(state, pane);
                if (refused != null) {
                    // Switching computers: its Deacon instead, once; else the reason.
                    String deacon = machineSwitch ? FleetPanes.switchLanding(state, null) : "";
                    if (deacon.isEmpty() || deacon.equals(pane) || refusedReason(state, deacon) != null) {
                        throw new Refused(pane, refused);
                    }
                    pane = deacon;
                    link.focus(pane);
                    until = Math.min(clock.getAsLong() + FOCUS_MS, overall);
                    continue;
                }
                if (!landed) pause.sleep(STEP_MS);
            }
            if (!landed) break;
            String picked = memory.remembered(hostId);
            if (machineSwitch || picked.isEmpty() || picked.equals(pane) || !hasPane(panes, picked)) return pane;
            pane = picked;
        }
        return pane;
    }

    /**
     * The pane to land on: an explicit pick wins; then (switching computers) its live Deacon;
     * then the pane this phone last used on that host; then one doing something (an idle shell
     * reads as a blank terminal); then any known one; then the daemon's focus; then the first.
     */
    static String choose(JSONObject state, JSONArray panes, String forcedPane, boolean machineSwitch, String remembered) {
        String landing = machineSwitch ? FleetPanes.switchLanding(state, forcedPane) : "";
        String pane = !landing.isEmpty() ? landing
                : forcedPane != null && !forcedPane.isEmpty() ? forcedPane : remembered == null ? "" : remembered;
        if (pane.isEmpty() || !hasPane(panes, pane)) {
            for (int i = 0; i < panes.length() && pane.isEmpty(); i++) {
                String status = panes.optJSONObject(i) == null ? "" : panes.optJSONObject(i).optString("status", "");
                if (!"unknown".equals(status) && !"idle".equals(status)) pane = idAt(panes, i);
            }
        }
        if (pane.isEmpty() || !hasPane(panes, pane)) {
            for (int i = 0; i < panes.length() && pane.isEmpty(); i++) {
                String status = panes.optJSONObject(i) == null ? "" : panes.optJSONObject(i).optString("status", "");
                if (!"unknown".equals(status)) pane = idAt(panes, i);
            }
        }
        if (pane.isEmpty() || !hasPane(panes, pane)) pane = state.optString("focused_pane", "");
        if (pane.isEmpty() || !hasPane(panes, pane)) pane = idAt(panes, 0);
        return pane;
    }

    /** Why herdr-core refused to attach {@code pane} on this session, or null (core_session `refused`). */
    static String refusedReason(JSONObject state, String pane) {
        JSONObject refused = state == null ? null : state.optJSONObject("refused");
        if (refused == null || pane == null || !pane.equals(refused.optString("terminal", ""))) return null;
        String reason = refused.optString("reason", "").trim();
        return reason.isEmpty() ? "it gave no reason" : reason;
    }

    static boolean hasPane(JSONArray panes, String paneId) {
        for (int i = 0; panes != null && i < panes.length(); i++) {
            if (paneId.equals(idAt(panes, i))) return true;
        }
        return false;
    }

    private static String idAt(JSONArray panes, int i) {
        JSONObject pane = panes.optJSONObject(i);
        return pane == null ? "" : pane.optString("id", "");
    }
}
