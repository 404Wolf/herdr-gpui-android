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
     * What the window's rail is now: open (rows built), floating as a drawer
     * over the terminal ({@code overlay}: opened by a gesture on a wide
     * window, transient), and whether a closed docked rail shows the strip.
     * Every change of the activity's rail goes through one of these, so a
     * transient drawer never decides what the docked rail is.
     */
    static final class Shown {
        final boolean open, overlay, minimized;

        Shown(boolean open, boolean overlay, boolean minimized) {
            this.open = open;
            this.overlay = overlay;
            this.minimized = minimized;
        }

        /**
         * The window's configuration applied (start, fold, unfold): the
         * stored choice as this panel shows it. A drawer open across the fold
         * is gone: it was never the docked choice.
         */
        static Shown configured(RailPanel stored, boolean docks, boolean shell) {
            Mode m = stored.shown(docks, shell);
            return new Shown(m == Mode.OPEN, false, m == Mode.MINIMIZED);
        }

        /** The buttons and keys: the docked rail as {@code next} is (it is also stored). */
        static Shown docked(RailPanel next) {
            return new Shown(next.mode == Mode.OPEN, false, next.mode == Mode.MINIMIZED);
        }

        /** Floats over the terminal (narrow window, or a gesture's drawer): nothing docked changes. */
        boolean drawer(boolean compact) {
            return compact || overlay;
        }

        /**
         * The toolbar's toggle, a drawer's Back or tap outside: open or shut.
         * A drawer shuts to what was docked (the strip stays); pinned open
         * or closed beside the terminal, the strip is gone.
         */
        Shown toggled(boolean open, boolean compact) {
            boolean transientDrawer = drawer(compact);
            return new Shown(open, open && overlay, transientDrawer && minimized);
        }

        /** A gesture opens the rail as a drawer over a wide window's terminal. */
        Shown floated(boolean compact) {
            return new Shown(true, !compact, minimized);
        }

        /** The docked strip shows: closed, minimized, and not a drawer. */
        boolean strip(boolean compact) {
            return !open && minimized && !drawer(compact);
        }

        /** The rail's width in dp: 0 closed, the strip's, or open ({@code openDp}). */
        int widthDp(boolean compact, int openDp) {
            return open ? openDp : strip(compact) ? STRIP_DP : 0;
        }
    }

    /**
     * The badge's text, from how many agents on the machine shown wait on Wolf
     * (the bar's "! N need you", ProjectBar.needYou): empty when nobody waits.
     */
    static String badge(int needYou) {
        return needYou <= 0 ? "" : needYou > 99 ? "99+" : String.valueOf(needYou);
    }
}
