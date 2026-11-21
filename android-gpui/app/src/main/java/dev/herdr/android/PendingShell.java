package dev.herdr.android;

import java.util.HashSet;
import java.util.Set;

/**
 * The quick shell's own tab while it starts (factory-setup.29): a "shell ◌"
 * pill at the end of the tab strip from the tap until a new terminal shows
 * up in the view, then nothing (the real tab takes its place). The core's
 * refusal, or {@link #TIMEOUT_MS} without a new terminal, turns it into a
 * red "shell ✕" that says why and offers Retry or Close; nothing is left
 * running by it. One per machine connection; pure, the caller gives the clock.
 */
final class PendingShell {
    static final long TIMEOUT_MS = 10_000;

    enum State { NONE, STARTING, FAILED }

    private State state = State.NONE;
    private String hostId = "";
    private long startedAt;
    private String reason = "";
    private final Set<String> before = new HashSet<>();

    /** A shell was asked for on {@code hostId}; {@code panes} are the terminals there now. */
    void start(String hostId, Set<String> panes, long now) {
        this.hostId = hostId == null ? "" : hostId;
        state = State.STARTING;
        startedAt = now;
        reason = "";
        before.clear();
        before.addAll(panes);
    }

    /**
     * The view's terminals on {@code hostId} at {@code now}: a new one ends the
     * wait, a long wait fails it. Another machine's view changes nothing.
     * True when the state changed (the strip redraws).
     */
    boolean observe(String hostId, Set<String> panes, long now) {
        if (state != State.STARTING || !this.hostId.equals(hostId)) return false;
        for (String pane : panes) {
            if (!before.contains(pane)) {
                state = State.NONE;
                return true;
            }
        }
        if (now - startedAt >= TIMEOUT_MS) {
            state = State.FAILED;
            reason = "no terminal after " + TIMEOUT_MS / 1000 + " s";
            return true;
        }
        return false;
    }

    /** The core refused the start (its words). */
    void failed(String why) {
        if (state != State.STARTING) return;
        state = State.FAILED;
        reason = why == null || why.isEmpty() ? "not started" : why;
    }

    /** Close on the failed pill, or a machine switch. */
    void clear() {
        state = State.NONE;
        reason = "";
        before.clear();
    }

    State state() {
        return state;
    }

    String hostId() {
        return hostId;
    }

    String reason() {
        return reason;
    }

    /** When the wait times out, for the caller's one delayed check. */
    long deadline() {
        return startedAt + TIMEOUT_MS;
    }

    /** The pill's words, or null when there is none. */
    String pill() {
        switch (state) {
            case STARTING: return "shell ◌";
            case FAILED: return "shell ✕";
            default: return null;
        }
    }

    /** What the pill says to a screen reader, with the machine's name. */
    String described(String machine) {
        switch (state) {
            case STARTING: return "Starting a shell on " + machine;
            case FAILED: return "Couldn't start a shell on " + machine + ": " + reason + ". Tap to retry or close";
            default: return "";
        }
    }
}
