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

    /**
     * Where the bar scrolls so the shown chip ({@code left}..{@code right},
     * bar coordinates) is whole in a window {@code width} wide now at
     * {@code from}: the least scroll that shows it, with up to {@code margin}
     * beside it where there is room; -1 when it is already whole (a scroll
     * Wolf made stays). A chip wider than the window shows its start.
     */
    static int revealScroll(int left, int right, int from, int width, int margin) {
        if (left >= from && right <= from + width) return -1;
        int chip = right - left;
        if (chip >= width) return Math.max(0, left);
        int room = Math.min(margin, (width - chip) / 2);
        int to = left < from ? left - room : right - width + room;
        return Math.max(0, to);
    }

    /** Whether a window chip of this status carries a status dot after its name. */
    static boolean dotted(String status) {
        return "working".equals(status) || "blocked".equals(status) || "done".equals(status) || "error".equals(status);
    }

    /** The project chip as drawn: "[name]", then " !N" when N of its agents wait on Wolf. */
    static String projectChipText(ProjectModel.Project p) {
        return projectText(p) + (p != null && p.waiting > 0 ? " !" + p.waiting : "");
    }

    /** The repo crumb as drawn after the project chip, or null: none. */
    static String crumbText(String repo) {
        return repo == null ? null : repo + " ›";
    }

    /** A window chip as drawn: its text, and its status dot. */
    static String chipText(Chip c) {
        return c.text + (dotted(c.window.status) ? " ●" : "");
    }

    /**
     * The bar's text as drawn, from the start up to chip {@code upTo}
     * (inclusive; chips.size() - 1 for all): the project chip, the repo
     * crumb, the window chips. Whatever changes in it can move the shown chip
     * (the activity reveals it again) or change the bar (it is rebuilt).
     */
    static String drawn(ProjectModel.Project p, String repo, List<Chip> chips, int upTo) {
        StringBuilder out = new StringBuilder(projectChipText(p)).append('\n');
        if (repo != null) out.append(crumbText(repo));
        out.append('\n');
        for (int i = 0; i <= upTo && i < chips.size(); i++) out.append(chipText(chips.get(i))).append('\n');
        return out.toString();
    }

    /**
     * What the bar is rebuilt on (the activity's cache key): the exact text
     * drawn (drawn), and what is drawn or acted on but is not text: each
     * window's status (the dot's colour, what the chip says it is to a screen
     * reader), its terminal and tab, which is shown, whether its tab is its
     * project's alone (its menu's scope); and the need-you count, the fold.
     */
    static String signature(List<ProjectModel.Project> all, ProjectModel.Project p, String repo, List<Chip> chips,
            int needYou, boolean compact) {
        StringBuilder out = new StringBuilder(p == null ? "" : p.key).append('\t').append(needYou).append('\t')
                .append(compact).append('\n').append(drawn(p, repo, chips, chips.size() - 1));
        for (Chip c : chips) {
            out.append(c.window.terminal).append('\t').append(c.window.tab).append('\t').append(c.window.status)
                    .append('\t').append(c.active).append('\t').append(ProjectModel.ownsTab(all, p, c.window)).append('\n');
        }
        return out.toString();
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
