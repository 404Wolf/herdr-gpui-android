package dev.herdr.android;

/**
 * Where a vertical drag or fling on the terminal goes (Wolf's ask: scrolling
 * keeps working offline). A held frame, the pane kept on screen while the
 * link is down or a reconnect has not attached yet, and a cached pane read
 * back from the phone's copy, scroll their own history on the phone: nothing
 * is sent. A live Herdr pane scrolls through its session. A live pane whose
 * link is in doubt (the phone just lost its network, or the link went quiet
 * after a change) scrolls the phone's own rows only: a full-screen agent would
 * otherwise get wheel input over a dead link, and nothing would move.
 *
 * <p>Pure, so the routing and the edge rule are tested without a device.
 */
final class HeldScroll {
    private HeldScroll() { }

    /** The ways a scroll can go. */
    interface Sink {
        /** Scrolls the held frame's own rows; true when the step was taken. */
        boolean held(int lines);

        /** Scrolls the live pane through its session; true when the step was taken. */
        boolean live(int lines);

        /** Scrolls the live pane's rows on the phone, sending nothing; true when the step was taken. */
        boolean local(int lines);
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

    /**
     * Routes one scroll step; false when nothing could take it (a fling stops there).
     * {@code suspect}: the link of a live pane is in doubt, so nothing may be sent.
     */
    static boolean scroll(boolean shell, boolean present, boolean closed, long remoteHandle, boolean ready,
            boolean suspect, int lines, Sink sink) {
        if (held(shell, present, closed, remoteHandle, ready)) return sink.held(lines);
        if (remoteHandle <= 0) return false;
        // A raw shell has no Herdr link to doubt: its own terminal scrolls it.
        return suspect && !shell ? sink.local(lines) : sink.live(lines);
    }

    /**
     * Whether the pane on screen should offer its saved transcript: not live, its link plainly
     * down, closed or not attached yet, or a live pane whose link is in doubt ({@code doubt}: the
     * phone just lost its network). In doubt nothing is sent, so a drag back must find the saved
     * history then too, not only once the link is given up (Wolf 11-23: swiping up offline did
     * nothing but "connecting").
     */
    static boolean savedTranscriptWanted(boolean live, boolean offline, boolean closed, boolean ready,
            boolean doubt) {
        return doubt || !live && (offline || closed || !ready);
    }

    /** What a step on the phone's own rows should add, once per gesture, when it did not move. */
    enum Edge {
        /** It moved, went down, or was already said this gesture: nothing more. */
        NONE,
        /** At the top of the screen's rows, with a saved history of more than the screen: open it. */
        OPEN_HISTORY,
        /** No older rows at all, as on a full-screen agent: say only the last screen was saved. */
        SCREEN_ONLY,
        /** At the oldest saved row of a history: say there is nothing older. */
        NO_OLDER
    }

    /** The note for {@link Edge#SCREEN_ONLY}. */
    static final String SCREEN_ONLY_NOTE = "offline · only the last screen was saved";
    /** The note for {@link Edge#NO_OLDER}. */
    static final String NO_OLDER_NOTE = "no older lines saved";
    /** A live pane whose link is in doubt: a full-screen agent scrolls itself, so it waits for the link. */
    static final String DOUBT_NOTE = "connecting · the agent scrolls when the link is back";

    /**
     * The edge rule (no drag offline is ever answered by nothing): a step back in
     * time ({@code lines > 0}) that did not move the view either opens the saved
     * history, or says why there is nothing older. {@code history}: rows the grid
     * keeps above its screen; {@code savedHistory}: a proven saved history of more
     * than the screen exists for this pane; {@code saidThisGesture}: already answered.
     */
    static Edge edge(int lines, boolean moved, long history, boolean savedHistory, boolean saidThisGesture) {
        if (lines <= 0 || moved || saidThisGesture) return Edge.NONE;
        if (savedHistory) return Edge.OPEN_HISTORY;
        return history == 0 ? Edge.SCREEN_ONLY : Edge.NO_OLDER;
    }
}
