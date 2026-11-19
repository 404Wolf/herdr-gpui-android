package dev.herdr.android;

/**
 * The corner indicator's actions, in place (Wolf's ask: offline is ONLY a
 * small indicator; never a dimmed screen, a scrim or an overlay). A tap on
 * the indicator opens Reconnect and Hosts as two small controls beside it, in
 * the same top row; using one, a few seconds passing or a touch anywhere else
 * closes them again. Nothing covers the terminal, nothing dims it, and a
 * touch elsewhere still reaches what it touched (the terminal keeps
 * scrolling): there is no dialog to dismiss first.
 *
 * <p>Pure, so the open and close rules and the touch regions are tested
 * without a device. The activity draws what {@link #expanded()} says.
 */
final class LinkActions {
    /** The actions close by themselves this long after they open. */
    static final long COLLAPSE_MS = 6_000;
    /** Each action's touch region is at least this square, however small it is drawn. */
    static final int TOUCH_DP = 48;

    private boolean expanded;
    private int generation;

    /** Whether the actions show beside the indicator. */
    boolean expanded() {
        return expanded;
    }

    /** Changes whenever the actions open or close, so a stale timeout does nothing. */
    int generation() {
        return generation;
    }

    /**
     * The indicator was tapped: opens its actions, or closes them when open.
     * A hidden indicator has none. Returns whether they show now.
     */
    boolean tap(boolean shown) {
        if (!shown) {
            collapse();
            return false;
        }
        expanded = !expanded;
        generation++;
        return expanded;
    }

    /** Closes them: one was used, the indicator hid, or a touch landed elsewhere. True when it changed. */
    boolean collapse() {
        if (!expanded) return false;
        expanded = false;
        generation++;
        return true;
    }

    /**
     * The timer posted when they opened (at {@code at}) ran out. True when that
     * closed them. While a control has accessibility focus (TalkBack is on it)
     * they stay: see {@link #waiting}.
     */
    boolean timeout(int at, boolean accessibilityFocused) {
        if (accessibilityFocused) return false;
        return at == generation && collapse();
    }

    /** True while the timer posted at {@code at} still owns open actions: it waits again. */
    boolean waiting(int at) {
        return expanded && at == generation;
    }

    /**
     * How long they stay open: {@link #COLLAPSE_MS}, or the longer time the
     * user chose for controls to stay ("Time to take action";
     * AccessibilityManager.getRecommendedTimeoutMillis).
     */
    static long collapseAfter(long recommended) {
        return Math.max(COLLAPSE_MS, recommended);
    }

    /**
     * A control's touch region: its bounds {left, top, right, bottom} grown
     * to at least {@code min} on each side about their centre, then moved,
     * not shrunk, to lie inside the {@code width} × {@code height} window.
     */
    static int[] grow(int left, int top, int right, int bottom, int min, int width, int height) {
        int[] x = span(left, right, min, width);
        int[] y = span(top, bottom, min, height);
        return new int[] {x[0], y[0], x[1], y[1]};
    }

    private static int[] span(int from, int to, int min, int limit) {
        int size = to - from;
        if (size < min) {
            int grow = min - size;
            from -= grow / 2;
            to += grow - grow / 2;
        }
        if (from < 0) {
            to = Math.min(limit, to - from);
            from = 0;
        }
        if (to > limit) {
            from = Math.max(0, from - (to - limit));
            to = limit;
        }
        return new int[] {from, to};
    }

    /**
     * One touch sequence against the indicator and its controls, decided
     * before the terminal sees it (the activity's dispatchTouchEvent passes
     * every event here first and does what the answer says). A control's
     * touch region is larger than the row it is drawn in, so it can reach a
     * few dp into the terminal (or the crumb) under the row:
     *
     * <ul>
     * <li>A press that starts inside the row is the control's ({@link #KEEP}).
     * <li>A press that starts below the row, in that overlap, goes on to the
     * terminal as well ({@link #PASS}); only if it stays a tap does the control
     * take it, and the terminal is then told its gesture was cancelled
     * ({@link #CANCEL_THEN_KEEP}). A drag that starts there is the terminal's
     * scroll, never a click.
     * <li>Movement past the touch slop, a second finger or a cancel ends the
     * press without a click, wherever it started.
     * <li>A touch outside every region is the terminal's, untouched
     * ({@link #PASS}).
     * </ul>
     */
    static final class Press {
        /** The event goes on to the window (the terminal, the tabs) as usual. */
        static final int PASS = 0;
        /** The indicator keeps the event; nothing else sees it. */
        static final int KEEP = 1;
        /** The terminal had this press: send it a cancel, then the indicator keeps the event. */
        static final int CANCEL_THEN_KEEP = 2;

        private int target = -1;
        private boolean shared;
        private boolean swallow;
        private float downX;
        private float downY;
        private int click = -1;

        /** The control the press is on, or -1. */
        int target() {
            return target;
        }

        /** The control to click now (once), or -1. */
        int takeClick() {
            int now = click;
            click = -1;
            return now;
        }

        /**
         * A first finger down at ({@code x}, {@code y}). {@code rowBottom} is
         * the screen y where the indicator's row ends and the content under it
         * begins.
         */
        int down(int[][] regions, int rowBottom, float x, float y) {
            click = -1;
            swallow = false;
            target = pick(regions, x, y);
            if (target < 0) return PASS;
            shared = y >= rowBottom;
            downX = x;
            downY = y;
            return shared ? PASS : KEEP;
        }

        /** A move with {@code pointers} fingers down; {@code slop} is the system touch slop in px. */
        int move(float x, float y, int pointers, float slop) {
            if (target < 0) return swallow ? KEEP : PASS;
            if (pointers > 1 || Math.hypot(x - downX, y - downY) > slop) return end();
            return shared ? PASS : KEEP;
        }

        /** The last finger up. {@code shown} is whether the pressed control is still on screen. */
        int up(float x, float y, boolean shown, float slop) {
            if (target < 0) {
                boolean kept = swallow;
                swallow = false;
                return kept ? KEEP : PASS;
            }
            boolean tap = shown && Math.hypot(x - downX, y - downY) <= slop;
            boolean wasShared = shared;
            int pressed = target;
            target = -1;
            if (!tap) return wasShared ? PASS : KEEP;
            click = pressed;
            return wasShared ? CANCEL_THEN_KEEP : KEEP;
        }

        /** The system cancelled the gesture. */
        int cancel() {
            if (target < 0) {
                boolean kept = swallow;
                swallow = false;
                return kept ? KEEP : PASS;
            }
            boolean wasShared = shared;
            target = -1;
            return wasShared ? PASS : KEEP;
        }

        /** No click any more: a press begun in the row keeps the rest of its gesture; one begun below gives it back. */
        private int end() {
            boolean wasShared = shared;
            target = -1;
            swallow = !wasShared;
            return wasShared ? PASS : KEEP;
        }
    }

    /** The first region holding the point, or -1: a touch elsewhere. */
    static int pick(int[][] regions, float x, float y) {
        for (int i = 0; i < regions.length; i++) {
            int[] r = regions[i];
            if (r != null && x >= r[0] && x < r[2] && y >= r[1] && y < r[3]) return i;
        }
        return -1;
    }
}
