package dev.herdr.android;

import java.util.ArrayList;
import java.util.List;

/**
 * What the terminal's top bar says (Wolf's items 60, 70, 78): the project
 * focus is in and its windows only, window 1 its lead, the shown one filled;
 * as the desktop's status line, `[project] 1 lead 2 traces ● 3 review`. Its
 * chip only names the project (and how many of its agents wait on Wolf); it
 * never changes which windows are listed. Pure: the activity draws it.
 */
final class ProjectBar {
    private ProjectBar() { }

    /** Name characters a window chip shows: the shown one, others wide, others on the cover. */
    static final int ACTIVE = 28, WIDE = 24, COMPACT = 10;

    /** One window chip: its window, what it says, whether it is the one shown. */
    static final class Chip {
        final ProjectModel.Window window;
        final String text;
        final boolean active;
        Chip(ProjectModel.Window window, String text, boolean active) {
            this.window = window;
            this.text = text;
            this.active = active;
        }
    }

    /** "n name", the name cut to {@code max} characters with "…". */
    static String text(ProjectModel.Window w, int max) {
        String name = w.name == null ? "" : w.name;
        if (name.codePointCount(0, name.length()) > max) {
            name = name.substring(0, name.offsetByCodePoints(0, max - 1)) + "…";
        }
        return w.n + " " + name;
    }

    /**
     * The window chips of {@code p}, in its order, {@code shown} (its index,
     * -1 none) filled and whole; on the cover ({@code compact}) the others
     * short, so the row stays a row.
     */
    static List<Chip> chips(ProjectModel.Project p, int shown, boolean compact) {
        List<Chip> out = new ArrayList<>();
        if (p == null) return out;
        for (int i = 0; i < p.windows.size(); i++) {
            boolean active = i == shown;
            ProjectModel.Window w = p.windows.get(i);
            out.add(new Chip(w, text(w, active ? ACTIVE : compact ? COMPACT : WIDE), active));
        }
        return out;
    }

    /**
     * Window {@code from} let go with its centre at {@code centre}, the
     * window chips' centres being {@code centres} (bar coordinates): it lands
     * before or after the nearest other window, never before the lead.
     * null: nowhere to go, or where it already is.
     */
    static ProjectModel.WindowMove dropMove(ProjectModel.Project p, int from, float centre, float[] centres) {
        if (p == null || centres == null || centres.length != p.windows.size()) return null;
        int first = !p.windows.isEmpty() && p.windows.get(0) == p.lead ? 1 : 0;
        int to = -1;
        for (int i = first; i < centres.length; i++) {
            if (i == from) continue;
            if (to < 0 || Math.abs(centres[i] - centre) < Math.abs(centres[to] - centre)) to = i;
        }
        if (to < 0) return null;
        return ProjectModel.windowMove(p, from, to, centre > centres[to]);
    }

    /** The project chip: "[name]"; the waiting count is drawn beside it. */
    static String projectText(ProjectModel.Project p) {
        return "[" + (p == null ? "-" : p.name) + "]";
    }

    /** How many agents on this computer wait on Wolf (blocked), every project's. */
    static int needYou(List<ProjectModel.Project> all) {
        int n = 0;
        for (ProjectModel.Project p : all) n += p.waiting;
        return n;
    }

    /**
     * Where a project's new window goes (model.js newWindowPlace, one
     * computer): its lead's space; its folder the shown window's when that is
     * the project's, else the lead's. null: a project nobody leads (a space's
     * own): then the space's plain new tab.
     */
    static String[] newWindowPlace(ProjectModel.Project p, int shown) {
        if (p == null || !p.led() || p.lead == null || p.lead.terminal == null) return null;
        String folder = shown >= 0 && !p.windows.get(shown).cwd.isEmpty() ? p.windows.get(shown).cwd : p.lead.cwd;
        return new String[] {p.lead.space, p.lead.terminal, folder};
    }
}
