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

    /** The timer posted when they opened (at {@code at}) ran out. True when that closed them. */
    boolean timeout(int at) {
        return at == generation && collapse();
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

    /** The first region holding the point, or -1: a touch elsewhere. */
    static int pick(int[][] regions, float x, float y) {
        for (int i = 0; i < regions.length; i++) {
            int[] r = regions[i];
            if (r != null && x >= r[0] && x < r[2] && y >= r[1] && y < r[3]) return i;
        }
        return -1;
    }
}
