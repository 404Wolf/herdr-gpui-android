package dev.herdr.android;

/**
 * Wolf's item 83 on the phone: the rail docked beside the terminal (the
 * unfolded panel's side panel) is open, minimized to a strip that keeps the
 * needs-you badge, or closed, as the desktop's Work panel is. The choice is
 * this device's, kept across restarts; ctrl-alt-w closes the rail and brings
 * back what was there (a strip stays a strip), ctrl-alt-b minimizes it and
 * brings it back. The cover's rail is a drawer over the terminal: nothing
 * there is docked, so nothing there is minimized or kept. Pure: the activity
 * draws it.
 */
final class RailPanel {
    enum Mode { OPEN, MINIMIZED, CLOSED }

    /** The strip's width, as the desktop's (44 px there). */
    static final int STRIP_DP = 44;

    /** Where it is kept: the activity's "terminal-display" preferences. */
    static final String MODE_KEY = "rail", LAST_KEY = "rail_last", LEGACY_KEY = "list";

    /** What the docked rail is now. */
    final Mode mode;
    /** What ctrl-alt-w brings back from closed: open or minimized, never closed. */
    final Mode last;

    private RailPanel(Mode mode, Mode last) {
        this.mode = mode;
        this.last = last == Mode.CLOSED ? Mode.OPEN : last;
    }

    static final RailPanel DEFAULT = new RailPanel(Mode.OPEN, Mode.OPEN);

    /**
     * The stored choice: {@code mode} and {@code last} as {@link #MODE_KEY} and
     * {@link #LAST_KEY} keep them; with neither, the older open/closed
     * boolean ({@code legacy}, null when unset); with nothing, open, the
     * unfolded panel's default.
     */
    static RailPanel read(String mode, String last, Boolean legacy) {
        Mode m = parse(mode);
        if (m == null) m = legacy == null || legacy ? Mode.OPEN : Mode.CLOSED;
        Mode l = parse(last);
        return new RailPanel(m, l == null ? (m == Mode.CLOSED ? Mode.OPEN : m) : l);
    }

    private static Mode parse(String name) {
        if (name == null) return null;
        switch (name) {
            case "open": return Mode.OPEN;
            case "minimized": return Mode.MINIMIZED;
            case "closed": return Mode.CLOSED;
            default: return null;
        }
    }

    static String name(Mode mode) {
        return mode == Mode.MINIMIZED ? "minimized" : mode == Mode.CLOSED ? "closed" : "open";
    }

    /** The toolbar toggle and the header's buttons: shown as asked. */
    RailPanel with(Mode next) {
        return new RailPanel(next, next == Mode.CLOSED ? (mode == Mode.CLOSED ? last : mode) : next);
    }

    /** ctrl-alt-w: closed, or back to what was there before. */
    RailPanel toggle() {
        return mode == Mode.CLOSED ? with(last) : with(Mode.CLOSED);
    }

    /** ctrl-alt-b and the header's –: to the strip, and back open. */
    RailPanel minimize() {
        return with(mode == Mode.MINIMIZED ? Mode.OPEN : Mode.MINIMIZED);
    }

    /**
     * What a window shows of the stored choice: a panel too narrow to dock
     * the rail (the cover, under 600 dp) or a raw shell (no rail) shows none,
     * whatever was chosen on the inner panel; unfolding brings it back.
     */
    Mode shown(boolean docks, boolean shell) {
        return !docks || shell ? Mode.CLOSED : mode;
    }

    /**
     * The badge's text, from how many agents on the machine shown wait on Wolf
     * (the bar's "! N need you", ProjectBar.needYou): empty when nobody waits.
     */
    static String badge(int needYou) {
        return needYou <= 0 ? "" : needYou > 99 ? "99+" : String.valueOf(needYou);
    }
}
