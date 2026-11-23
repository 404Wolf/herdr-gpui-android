package dev.herdr.android;

/**
 * Where the right panel goes (the settled preview's layouts): on the cover
 * (under 640 dp) it covers the terminal when its Work button opens it; on the
 * unfolded screen (640 to 1100 dp) it takes half the width beside the
 * terminal and the rail becomes a ☰ drawer; wider, it docks at the right with
 * the rail still docked at the left. A raw shell has no panel.
 *
 * <p>Docked, Wolf moves the divider (factory-9zz.9): the panel's share of the
 * width is his {@link Choice}, kept per orientation. The panel follows the
 * finger the whole way ({@link #live}), down to zero; released under
 * {@link #HIDE_BELOW_DP} it is hidden, else it stays exactly where it was
 * let go. Hidden, a drag in from the right edge grows it from zero with the
 * finger and keeps the width it is let go at. On the cover there is no
 * split: a pull in from the right edge brings the page in under the finger
 * and it settles full or gone ({@link #coverOpens}); a drag or fling back
 * toward the right sends it away. Pure: sizes in dp in, a layout out.
 */
final class WorkPanelLayout {
    /** The app's top bar: the terminal's tab row and the panel's header are this tall, on one line (Wolf 11-22). */
    static final int TOP_BAR_DP = 30;
    static final int COVER_DP = 640, SPLIT_DP = 1100, MIN_DP = 340;
    /**
     * Hidden only at zero: a release this close to the edge (about 12 px on
     * the Fold, for an imprecise finger) hides it, and a reveal let go there
     * stays hidden; any wider width is kept exactly (Wolf: no threshold, no
     * preset, no minimum). The terminal beside it keeps {@link #TERMINAL_MIN_DP}.
     */
    static final int HIDE_BELOW_DP = 6, PANEL_MIN_DP = HIDE_BELOW_DP, TERMINAL_MIN_DP = 320;
    /** A tap on the hidden panel's handle, or this long an inward swipe, is a reveal, not a stray touch. */
    static final int RESTORE_DP = 8;
    /** The right-edge zone that takes the restoring swipe: narrow, and short enough to exclude from Back. */
    static final int EDGE_DP = 20, EDGE_TALL_DP = 200;
    /** The cluster's room at the bottom right: the strip stays above it, so its buttons keep their taps. */
    static final int EDGE_CLEAR_DP = 72;

    /**
     * The restoring strip's distance from the window's bottom, dp: its
     * {@link #EDGE_TALL_DP} (all of Android's Back exclusion for one edge)
     * centred in the lower half, where the thumb is (the missing panel of
     * 11-23: mid-height was out of reach), never over the icon cluster.
     */
    static int edgeBottomDp(int heightDp) {
        if (heightDp <= 0) return EDGE_CLEAR_DP;
        int centred = heightDp / 4 - EDGE_TALL_DP / 2;
        return Math.max(EDGE_CLEAR_DP, Math.min(centred, Math.max(EDGE_CLEAR_DP, heightDp - EDGE_TALL_DP)));
    }

    /**
     * The ⋮ menu's "Overview and tasks" on the unfolded screen (the missing
     * panel of 11-23): a hidden panel comes back at the share it had, a shown
     * one is hidden, the same as the divider's own hide; no width is invented.
     */
    static Choice toggled(Choice c) {
        Choice now = c == null ? Choice.DEFAULT : c;
        return now.hidden ? shownAgain(now) : new Choice(now.share, true);
    }

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

    /**
     * The cover's full page, or the split: the display decides, not the
     * window (fold regressions of 11-23). An unfolded window narrowed by
     * split screen or a freeform window still splits; only the cover display
     * itself (under {@link #COVER_DP}) gets the page. {@code displayWidthDp}
     * 0 (unknown, before API 30): the window's width decides, as before.
     */
    static boolean onCover(int windowWidthDp, int displayWidthDp) {
        return (displayWidthDp > 0 ? displayWidthDp : windowWidthDp) < COVER_DP;
    }

    /**
     * A Back swipe from the right edge starting {@code yDp} down a window
     * {@code heightDp} tall brings the hidden panel in: the lower half, where
     * the thumb is, above the icon cluster. Anywhere else it stays Back.
     */
    static boolean takesEdgeBack(float yDp, int heightDp) {
        return heightDp > 0 && yDp >= heightDp / 2f && yDp <= heightDp - EDGE_CLEAR_DP;
    }

    /** At {@code widthDp}; {@code open}: the cover's panel was opened; {@code choice}: the divider's. */
    static WorkPanelLayout of(int widthDp, boolean shell, boolean open, Choice choice) {
        return of(widthDp, widthDp < COVER_DP, shell, open, choice);
    }

    /** As {@link #of(int, boolean, boolean, Choice)}, with {@code cover} from {@link #onCover}. */
    static WorkPanelLayout of(int widthDp, boolean cover, boolean shell, boolean open, Choice choice) {
        if (shell) return new WorkPanelLayout(false, false, false, 0, false, false);
        if (cover) return new WorkPanelLayout(open, true, true, 1, false, false);
        Choice c = choice == null ? Choice.DEFAULT : choice;
        float fallback = widthDp <= SPLIT_DP ? 0.5f : 0.3f;
        float share = clamp(c.share > 0 ? c.share : fallback, widthDp);
        return new WorkPanelLayout(!c.hidden, false, false, share, true, c.hidden);
    }

    /**
     * The panel while a finger moves the divider or reveals it: exactly at
     * {@code share} (zero included), only the terminal's minimum holding.
     * Nothing is kept until the release.
     */
    static WorkPanelLayout live(int widthDp, float share) {
        float most = widthDp <= 0 ? 1 : Math.max(0, 1 - (float) TERMINAL_MIN_DP / widthDp);
        return new WorkPanelLayout(true, false, false, Math.max(0, Math.min(most, share)), true, false);
    }

    static WorkPanelLayout of(int widthDp, boolean shell, boolean open) {
        return of(widthDp, shell, open, Choice.DEFAULT);
    }

    /**
     * The divider's left edge, px: centred on the panel's edge, but never past
     * the window's right side, so a panel left a few dp wide still has a
     * whole divider to grab (factory-9zz.9, the missing panel of 11-23).
     */
    static int dividerLeft(int panelLeftPx, int windowPx, int touchPx) {
        return Math.max(0, Math.min(panelLeftPx - touchPx / 2, windowPx - touchPx));
    }

    /** The divider is within Back's edge zone ({@code backPx} of the right side): it needs an exclusion. */
    static boolean dividerAtEdge(int dividerLeftPx, int windowPx, int touchPx, int backPx) {
        return windowPx > 0 && dividerLeftPx + touchPx > windowPx - backPx;
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

    /**
     * A reveal from the right edge let go with the divider at {@code dividerXDp}:
     * shown at that width, or still hidden (at {@code before}) when it is
     * narrower than {@link #HIDE_BELOW_DP}. Never a preset width.
     */
    static Choice revealed(float dividerXDp, int widthDp, float before) {
        return released(dividerXDp, widthDp, before);
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

    // ---- The cover (Wolf, 11-22): no split, a full page that follows the finger ----

    /**
     * A fling at least this fast (dp/s) decides on its own: toward the left
     * opens the page, toward the right sends it away. Slower, the page settles
     * on the side it is more than half on.
     */
    static final int COVER_FLING_DP_S = 600;
    /** How long the page takes to settle full or away after the release. */
    static final int COVER_SETTLE_MS = 180;

    /** How much of the cover's page shows while a finger pulls it in from the right edge to {@code fingerXDp}. */
    static float coverRevealed(float fingerXDp, int widthDp) {
        if (widthDp <= 0) return 0;
        return Math.max(0, Math.min(1, (widthDp - fingerXDp) / widthDp));
    }

    /** How much of the open page shows while a finger drags it {@code dxDp} toward the right. */
    static float coverDismissing(float dxDp, int widthDp) {
        if (widthDp <= 0) return 1;
        return Math.max(0, Math.min(1, 1 - dxDp / widthDp));
    }

    /**
     * Where a release leaves the cover's page: open (the full page) or off
     * screen, from how much of it shows and the finger's speed ({@code vxDpS},
     * positive toward the right). Never a partial page.
     */
    static boolean coverOpens(float visible, float vxDpS) {
        if (vxDpS <= -COVER_FLING_DP_S) return true;
        if (vxDpS >= COVER_FLING_DP_S) return false;
        return visible >= 0.5f;
    }

    /** The page's offset from its open place, in px, while {@code visible} of it shows. */
    static float coverOffset(float visible, int widthPx) {
        return (1 - Math.max(0, Math.min(1, visible))) * widthPx;
    }

    /** The unfolded split: the panel takes the rail's room, so the rail is a drawer; a hidden panel gives it back. */
    static boolean splitsWithRail(int widthDp, boolean shell, boolean hidden) {
        return !shell && !hidden && widthDp >= COVER_DP && widthDp <= SPLIT_DP;
    }

    static boolean splitsWithRail(int widthDp, boolean shell) {
        return splitsWithRail(widthDp, shell, false);
    }
}
