package dev.herdr.android;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Starting a machine's Deacon and asking it for a new project, for Home and
 * the rail alike (factory audit, PR 10): one state per saved host, one set of
 * transitions and one set of words, so a Deacon started on Home shows
 * "Starting…" in the rail too, and neither screen launches a second. Pure:
 * times come in as arguments; the screens send the core requests and draw.
 */
final class DeaconFlow {
    /** How long a launched Deacon has to show claude running, and how long a fresh launch may be unlisted. */
    static final long START_MS = 45_000, LIST_GRACE_MS = 5_000;

    /** One machine's Deacon and New project, as the screens see them. */
    static final class State {
        /** A deacon.start is out. */
        boolean starting;
        /** Launched and not yet confirmed running: its terminal and when. */
        String pending = "";
        long pendingAt;
        /** Why the last start failed ("" none). */
        String error = "";
        /** A New project message is out, and why the last one failed. */
        boolean asking;
        String askError = "";

        /** Busy: a start is out or not yet confirmed (no second launch). */
        boolean busy() {
            return starting || !pending.isEmpty();
        }
    }

    private static final DeaconFlow SHARED = new DeaconFlow();

    /** The app's one flow: Home and the rail share it. */
    static DeaconFlow shared() {
        return SHARED;
    }

    private final Map<String, State> byHost = new ConcurrentHashMap<>();

    State of(String hostId) {
        return byHost.computeIfAbsent(hostId == null ? "" : hostId, k -> new State());
    }

    /** A start begins; false when one is already out or pending (the tap does nothing). */
    boolean begin(String hostId) {
        State s = of(hostId);
        synchronized (s) {
            if (s.busy()) return false;
            s.starting = true;
            s.error = "";
            return true;
        }
    }

    /** What a deacon.start's answer means for the screen. */
    enum Started { PENDING, EXISTING, FAILED }

    /**
     * The core answered a start: {@code terminal} is the Deacon's ("" none),
     * {@code fresh} whether it launched one now, {@code failure} why not
     * (null none). A fresh one is pending until it shows claude running.
     */
    Started started(String hostId, String terminal, boolean fresh, String failure, long nowMs) {
        State s = of(hostId);
        synchronized (s) {
            s.starting = false;
            if (terminal == null || terminal.isEmpty()) {
                s.error = failure == null || failure.isEmpty() ? "it did not answer" : failure;
                return Started.FAILED;
            }
            if (failure != null) s.error = failure;
            if (fresh) {
                s.pending = terminal;
                s.pendingAt = nowMs;
                return Started.PENDING;
            }
            return Started.EXISTING;
        }
    }

    /** The start could not be sent at all. */
    void failed(String hostId, String why) {
        State s = of(hostId);
        synchronized (s) {
            s.starting = false;
            s.error = why == null ? "" : why;
        }
    }

    /** A pending Deacon checked against the machine's live list. */
    enum Check { NONE, WAITING, RUNNING, FAILED }

    /**
     * Confirms a launched Deacon from {@code members} (never relaunches):
     * running once it reports a status or a non-shell program; failed when
     * it is gone, or still a shell after {@link #START_MS}. Ends the pending
     * state on RUNNING and FAILED (the failure's words are in {@link State#error}).
     */
    Check check(String hostId, String machine, List<? extends FleetTree.Member> members, long nowMs) {
        State s = of(hostId);
        synchronized (s) {
            if (s.pending.isEmpty()) return Check.NONE;
            FleetTree.Member launched = null;
            for (FleetTree.Member m : members) if (m.id.equals(s.pending)) launched = m;
            long elapsed = nowMs - s.pendingAt;
            if (launched == null && elapsed < LIST_GRACE_MS) return Check.WAITING;
            FleetTree.StartHealth health = FleetTree.startHealth(launched, elapsed, START_MS);
            if (health == FleetTree.StartHealth.WAITING) return Check.WAITING;
            s.pending = "";
            if (health == FleetTree.StartHealth.RUNNING) {
                s.error = "";
                return Check.RUNNING;
            }
            s.error = launched == null ? stoppedRightAway(machine) : didNotStart(machine);
            return Check.FAILED;
        }
    }

    /** The Deacon's terminal pending confirmation ("" none). */
    String pending(String hostId) {
        return of(hostId).pending;
    }

    /** Claude runs in the Deacon again (Wolf started it there): an old failure no longer applies. */
    void running(String hostId) {
        State s = of(hostId);
        synchronized (s) {
            if (s.pending.isEmpty()) s.error = "";
        }
    }

    /** A New project send begins; false while one is out (one send per tap). */
    boolean ask(String hostId) {
        State s = of(hostId);
        synchronized (s) {
            if (s.asking) return false;
            s.asking = true;
            s.askError = "";
            return true;
        }
    }

    void asked(String hostId, String failure) {
        State s = of(hostId);
        synchronized (s) {
            s.asking = false;
            s.askError = failure == null ? "" : failure;
        }
    }

    // ---- the words, the same on both screens ----

    static String starting(String machine) {
        return "Starting the Deacon on " + machine + "…";
    }

    static String asking() {
        return "Asking the Deacon…";
    }

    static String none(boolean live, String machine) {
        return live ? "No Deacon on this computer. Tap to start one." : "No Deacon. Connect " + machine + " to start one.";
    }

    static String couldNotStart(String why) {
        return "Could not start the Deacon: " + why + ". Tap to try again.";
    }

    static String couldNotAsk(String why) {
        return "Could not reach the Deacon: " + why + ". Tap to try again.";
    }

    static String notRunning(String machine) {
        return "The Deacon's terminal on " + machine + " is open, but claude isn't running in it."
                + " Start claude there, or check that it is installed";
    }

    static String stoppedRightAway(String machine) {
        return "The Deacon on " + machine + " stopped right away. Check that claude is installed and signed in there";
    }

    static String didNotStart(String machine) {
        return "The Deacon's terminal opened on " + machine + ", but claude didn't start in it."
                + " Check that claude is installed there";
    }

    static String started(String machine) {
        return "Started the Deacon on " + machine;
    }
}
