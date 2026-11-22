package dev.herdr.android;

/**
 * A pick made while the phone is still switching machines (factory audit,
 * PR 6): the rail's, the machine picker's or a pin's. The latest one is kept
 * (a newer pick replaces an older) and runs once the switch settles, instead
 * of being dropped. Pure: the activity runs it.
 */
final class HostPick {
    /** The saved profile to show, and the terminal there (null: its landing, its Deacon). */
    final String hostId, pane;
    final boolean picked;
    /** A pin, verified again where it opens; null for a rail or picker pick. */
    final Pins.Pin pin;

    HostPick(String hostId, String pane, boolean picked, Pins.Pin pin) {
        this.hostId = hostId;
        this.pane = pane;
        this.picked = picked;
        this.pin = pin;
    }

    /** The one pick waiting for a switch to settle. */
    static final class Queue {
        private HostPick waiting;

        /** Keeps {@code pick}; the one it replaces is dropped (Wolf picked again). */
        void keep(HostPick pick) {
            waiting = pick;
        }

        /** The waiting pick, once: null when there is none. */
        HostPick take() {
            HostPick out = waiting;
            waiting = null;
            return out;
        }

        boolean waiting() {
            return waiting != null;
        }
    }

    /** What the screen says while it waits: "Still switching to server · then laptop". */
    static String note(String switchingToName, String nextName) {
        String to = switchingToName == null || switchingToName.isEmpty() ? "another computer" : switchingToName;
        return "Still switching to " + to + (nextName == null || nextName.isEmpty() || nextName.equals(to) ? "…" : " · then " + nextName);
    }
}
