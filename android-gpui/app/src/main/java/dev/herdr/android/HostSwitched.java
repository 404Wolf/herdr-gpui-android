package dev.herdr.android;

/**
 * A look (usage.look, beads.*) refused because the phone is no longer on the
 * machine it was asked for, or has no connection while paused. Not a failure
 * of that machine: the views drop the answer instead of calling it unreachable.
 */
final class HostSwitched extends IllegalStateException {
    HostSwitched() {
        super("the phone is not on that machine now");
    }
}
