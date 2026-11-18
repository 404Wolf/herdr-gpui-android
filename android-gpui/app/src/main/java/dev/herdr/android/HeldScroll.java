package dev.herdr.android;

/**
 * Where a vertical drag or fling on the terminal goes (Wolf's ask: scrolling
 * keeps working offline). A held frame, the pane kept on screen while the
 * link is down or a reconnect has not attached yet, and a cached pane read
 * back from the phone's copy, scroll their own history on the phone: nothing
 * is sent. A live Herdr pane scrolls through its session. Pure, so the
 * routing is tested without a device.
 */
final class HeldScroll {
    private HeldScroll() { }

    /** The two ways a scroll can go. */
    interface Sink {
        /** Scrolls the held frame's own rows; true when the step was taken. */
        boolean held(int lines);

        /** Scrolls the live pane through its session; true when the step was taken. */
        boolean live(int lines);
    }

    /**
     * Whether the terminal shows a held frame rather than a live Herdr pane.
     * No pane and a closed one count as held: there is nothing live to scroll.
     * A raw shell is never held: its own terminal scrolls it.
     */
    static boolean held(boolean shell, boolean present, boolean closed, long remoteHandle, boolean ready) {
        if (shell) return false;
        return !present || closed || (remoteHandle > 0 && !ready);
    }

    /** Routes one scroll step; false when nothing could take it (a fling stops there). */
    static boolean scroll(boolean shell, boolean present, boolean closed, long remoteHandle, boolean ready,
            int lines, Sink sink) {
        if (held(shell, present, closed, remoteHandle, ready)) return sink.held(lines);
        if (remoteHandle <= 0) return false;
        return sink.live(lines);
    }
}
