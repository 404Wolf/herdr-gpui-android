package dev.herdr.android;

/**
 * Where the right panel goes (the settled preview's layouts): on the cover
 * (under 640 dp) it covers the terminal when its Work button opens it; on the
 * unfolded screen (640 to 1100 dp) it takes half the width beside the
 * terminal and the rail becomes a ☰ drawer; wider, it docks at the right with
 * the rail still docked at the left. A raw shell has no panel.
 *
 * <p>Docked, Wolf moves the divider (factory-9zz.9): the panel's share of the
 * width is his {@link Choice}, kept per orientation, with each side at least
 * its minimum; dragged all the way right the panel is hidden, and an inward
 * swipe from the right edge brings it back. Pure: sizes in dp in, a layout out.
 */
final class WorkPanelLayout {
    static final int COVER_DP = 640, SPLIT_DP = 1100, MIN_DP = 340;
    /** The least a docked panel and the terminal beside it may be, while dragging. */
    static final int PANEL_MIN_DP = 280, TERMINAL_MIN_DP = 320;
    /** A release with the panel narrower than this hides it; an inward edge swipe this long brings it back. */
    static final int HIDE_BELOW_DP = 200, RESTORE_DP = 48;
    /** The right-edge zone that takes the restoring swipe: narrow, and short enough to exclude from Back. */
    static final int EDGE_DP = 20, EDGE_TALL_DP = 200;

    /** Wolf's own split, as the divider left it: the panel's share, and whether it is hidden. */
    static final class Choice {
        /** The panel's share of the width; 0 for the default (half unfolded, 30% wider). */
        final float share;
        final boolean hidden;

        Choice(float share, boolean hidden) {
            this.share = share;
            this.hidden = hidden;
        }

        static final Choice DEFAULT = new Choice(0, false);

        /** How it is kept: "0.420" or "hidden:0.420". */
        String stored() {
            return (hidden ? "hidden:" : "") + String.format(java.util.Locale.ROOT, "%.3f", share);
        }

        /** A kept choice, or the default for anything else. */
        static Choice parse(String stored) {
            if (stored == null || stored.isEmpty()) return DEFAULT;
            boolean hidden = stored.startsWith("hidden:");
            try {
                float share = Float.parseFloat(hidden ? stored.substring("hidden:".length()) : stored);
                if (!(share >= 0 && share < 1)) return DEFAULT;
                return new Choice(share, hidden);
            } catch (NumberFormatException e) {
                return DEFAULT;
            }
        }
    }

    final boolean shown, overlay, toggle;
    /** Its share of the window's width when docked. */
    final float share;
    /** Docked and movable: the divider shows (and, hidden, the right edge brings it back). */
    final boolean divider, hidden;

    private WorkPanelLayout(boolean shown, boolean overlay, boolean toggle, float share, boolean divider, boolean hidden) {
        this.shown = shown;
        this.overlay = overlay;
        this.toggle = toggle;
        this.share = share;
        this.divider = divider;
        this.hidden = hidden;
    }

    /** At {@code widthDp}; {@code open}: the cover's panel was opened; {@code choice}: the divider's. */
    static WorkPanelLayout of(int widthDp, boolean shell, boolean open, Choice choice) {
        if (shell) return new WorkPanelLayout(false, false, false, 0, false, false);
        if (widthDp < COVER_DP) return new WorkPanelLayout(open, true, true, 1, false, false);
        Choice c = choice == null ? Choice.DEFAULT : choice;
        float fallback = widthDp <= SPLIT_DP ? 0.5f : 0.3f;
        float share = clamp(c.share > 0 ? c.share : fallback, widthDp);
        return new WorkPanelLayout(!c.hidden, false, false, share, true, c.hidden);
    }

    static WorkPanelLayout of(int widthDp, boolean shell, boolean open) {
        return of(widthDp, shell, open, Choice.DEFAULT);
    }

    /** Each side keeps its minimum; a window too narrow for both splits it evenly. */
    static float clamp(float share, int widthDp) {
        if (widthDp <= 0) return share;
        float least = (float) PANEL_MIN_DP / widthDp, most = 1 - (float) TERMINAL_MIN_DP / widthDp;
        if (least > most) return 0.5f;
        return Math.max(least, Math.min(most, share));
    }

    /**
     * The panel's share while the divider is dragged to {@code dividerXDp}
     * (from the window's left): unclamped past the panel's minimum toward the
     * right, so the drag can carry it off the edge, but never under the
     * terminal's minimum.
     */
    static float dragged(float dividerXDp, int widthDp) {
        if (widthDp <= 0) return 0.5f;
        float share = (widthDp - dividerXDp) / widthDp;
        return Math.max(0, Math.min(1 - (float) TERMINAL_MIN_DP / widthDp, share));
    }

    /**
     * Where a drag released at {@code dividerXDp} leaves the panel: hidden
     * when it is narrower than {@link #HIDE_BELOW_DP} (it comes back at
     * {@code before}, the share it had), else at the dragged share, clamped.
     */
    static Choice released(float dividerXDp, int widthDp, float before) {
        if (widthDp - dividerXDp < HIDE_BELOW_DP) return new Choice(before, true);
        return new Choice(clamp(dragged(dividerXDp, widthDp), widthDp), false);
    }

    /** The choice with the panel shown again, at the share it had. */
    static Choice shownAgain(Choice c) {
        return new Choice(c == null ? 0 : c.share, false);
    }

    /** A release this near the middle (the Fold's hinge) lands on it. */
    static final int SNAP_DP = 16;

    /** The divider's x, snapped to the middle of the window (the hinge) within {@link #SNAP_DP}. */
    static float snapped(float dividerXDp, int widthDp) {
        float middle = widthDp / 2f;
        return Math.abs(dividerXDp - middle) <= SNAP_DP ? middle : dividerXDp;
    }

    /** An inward swipe from the right edge of {@code dxDp} (negative: leftward) brings a hidden panel back. */
    static boolean restores(float dxDp, float dyDp) {
        return dxDp <= -RESTORE_DP && Math.abs(dxDp) > Math.abs(dyDp) * 1.5f;
    }

    /** The unfolded split: the panel takes the rail's room, so the rail is a drawer; a hidden panel gives it back. */
    static boolean splitsWithRail(int widthDp, boolean shell, boolean hidden) {
        return !shell && !hidden && widthDp >= COVER_DP && widthDp <= SPLIT_DP;
    }

    static boolean splitsWithRail(int widthDp, boolean shell) {
        return splitsWithRail(widthDp, shell, false);
    }
}
