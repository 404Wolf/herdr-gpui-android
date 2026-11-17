package dev.herdr.android;

/**
 * When to save the pane on screen while it is live (item 77), so a pane read
 * offline is recent even if the link never dropped cleanly: when its agent's
 * turn ends (working, then done, blocked or idle), and at most every
 * {@link #MAX_INTERVAL_MS} while its output keeps changing. A quiet pane is
 * never rewritten. Leaving the screen and switching away save it elsewhere
 * (the session's close, and the switch). Pure and single-threaded: the
 * terminal's poll worker owns one.
 */
final class PaneCheckpoint {
    static final long MAX_INTERVAL_MS = 120_000;

    private String pane = "";
    private String status = "";
    private long output;
    private long savedAt;
    private boolean changed;

    /**
     * One poll of the pane on screen: its id, its agent status from the list,
     * the session's pane-output byte count and the time now (any monotonic
     * clock). True means save it now.
     */
    boolean observe(String pane, String status, long output, long now) {
        if (pane == null || pane.isEmpty()) return false;
        if (status == null) status = "";
        if (!pane.equals(this.pane)) {
            // A pane just shown starts from its attach: nothing new to keep yet.
            this.pane = pane;
            this.status = status;
            this.output = output;
            this.savedAt = now;
            this.changed = false;
            return false;
        }
        if (output != this.output) {
            this.output = output;
            changed = true;
        }
        boolean turnEnded = "working".equals(this.status) && turnOver(status);
        this.status = status;
        if (!turnEnded && !(changed && now - savedAt >= MAX_INTERVAL_MS)) return false;
        savedAt = now;
        changed = false;
        return true;
    }

    /** Forget the pane, so the next poll starts over (a new session, or no pane). */
    void reset() {
        pane = "";
        status = "";
        changed = false;
    }

    private static boolean turnOver(String status) {
        return "done".equals(status) || "blocked".equals(status) || "idle".equals(status);
    }
}
