package dev.herdr.android;

/**
 * Where the right panel goes (the settled preview's layouts): on the cover
 * (under 640 dp) it covers the terminal when its Work button opens it; on the
 * unfolded screen (640 to 1100 dp) it takes half the width beside the
 * terminal and the rail becomes a ☰ drawer; wider, it docks at the right with
 * the rail still docked at the left. A raw shell has no panel. Pure.
 */
final class WorkPanelLayout {
    static final int COVER_DP = 640, SPLIT_DP = 1100, MIN_DP = 340;

    final boolean shown, overlay, toggle;
    /** Its share of the window's width when docked. */
    final float share;

    private WorkPanelLayout(boolean shown, boolean overlay, boolean toggle, float share) {
        this.shown = shown;
        this.overlay = overlay;
        this.toggle = toggle;
        this.share = share;
    }

    /** At {@code widthDp}; {@code open}: the cover's panel was opened. */
    static WorkPanelLayout of(int widthDp, boolean shell, boolean open) {
        if (shell) return new WorkPanelLayout(false, false, false, 0);
        if (widthDp < COVER_DP) return new WorkPanelLayout(open, true, true, 1);
        return new WorkPanelLayout(true, false, false, widthDp <= SPLIT_DP ? 0.5f : 0.3f);
    }

    /** The unfolded split: the panel takes the rail's room, so the rail is a drawer. */
    static boolean splitsWithRail(int widthDp, boolean shell) {
        return !shell && widthDp >= COVER_DP && widthDp <= SPLIT_DP;
    }
}
