package dev.herdr.android;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What the right panel (Overview | Tasks) shows, pure so it can be tested:
 * whose it is, which tabs it has, the Tasks rows as the tree folds, and a
 * bead's plan as one Markdown document (the Fable spec: description, then
 * Design, Acceptance, Notes, and the Outcome of a closed bead). The settled
 * preview (t3fp, 8787) and the desktop panel are the reference.
 */
final class PanelModel {
    private PanelModel() { }

    /** The agent the panel is for: the one on screen. */
    static final class Agent {
        final String id, role, project, name, bead, label;

        Agent(String id, String role, String project, String name, String bead, String label) {
            this.id = id;
            this.role = role;
            this.project = project;
            this.name = name;
            this.bead = bead;
            this.label = label;
        }

        /** Its status doc's name (DocModel), or null when it can't have one. */
        String doc() {
            return DocModel.nameFor(role, project, name);
        }

        /** The deacon has no project, so no Tasks. */
        boolean hasTasks() {
            return !"deacon".equals(role) && project != null && !project.isEmpty();
        }

        /** The same agent, doc and bead: nothing to read again. */
        String key() {
            return id + "\n" + role + "\n" + project + "\n" + name + "\n" + bead;
        }
    }

    /**
     * The agent {@code paneId} is, from the live machine's members: its role,
     * its project (its own label, else its PM's, as the rail places it), its
     * hc name, its bead (BeadsModel.beadOf), and what to call it.
     */
    static Agent agentOf(List<? extends FleetTree.Member> members, String paneId) {
        FleetTree.Member m = null;
        for (FleetTree.Member x : members) if (x.id.equals(paneId)) m = x;
        if (m == null) return null;
        String project = m.project;
        if (project.isEmpty()) {
            FleetTree.Project p = FleetTree.projectOf(FleetTree.build(members, null), paneId);
            project = p == null ? "" : p.name;
        }
        String role = m.role.isEmpty() && FleetTree.deaconSpace(m) && m.project.isEmpty() ? "" : m.role;
        String label = AgentNames.unnamed(m) ? AgentNames.UNNAMED : FleetPanes.displayTitle(m);
        return new Agent(m.id, role, project, m.name, BeadsModel.beadOf(m.id, members), label);
    }

    enum Tab { OVERVIEW, TASKS }

    /** The tab shown: Tasks only where there are tasks. */
    static Tab tab(Tab wanted, Agent agent) {
        return wanted == Tab.TASKS && agent != null && agent.hasTasks() ? Tab.TASKS : Tab.OVERVIEW;
    }

    /** One row of the Tasks tree as it folds. */
    static final class Row {
        final BeadsModel.Node node;
        final int depth;
        final boolean epic, folded, open;
        /** "3/7 done · 2 in progress" for an epic, else "". */
        final String progress;
        /** The row after an unfolded node's children: "4 closed". */
        final int closedAfter;

        Row(BeadsModel.Node node, int depth, boolean epic, boolean folded, boolean open, String progress, int closedAfter) {
            this.node = node;
            this.depth = depth;
            this.epic = epic;
            this.folded = folded;
            this.open = open;
            this.progress = progress;
            this.closedAfter = closedAfter;
        }
    }

    /** The tree as rows: epics first at depth 0, children under an unfolded parent, {@code open} beads expanded. */
    static List<Row> rows(List<BeadsModel.Node> roots, Set<String> folded, Set<String> open) {
        List<Row> out = new ArrayList<>();
        for (BeadsModel.Node n : roots) add(out, n, 0, folded, open);
        return out;
    }

    private static void add(List<Row> out, BeadsModel.Node n, int depth, Set<String> folded, Set<String> open) {
        boolean epic = depth == 0 && BeadsModel.isEpic(n.bead);
        boolean shut = folded.contains(n.bead.id);
        String progress = "";
        if (epic) {
            int working = 0;
            for (BeadsModel.Node k : n.children) if ("in_progress".equals(k.bead.status)) working++;
            BeadsModel.Epic e = BeadsModel.progress(n);
            progress = e.closed + "/" + e.total + " done" + (working > 0 ? " · " + working + " in progress" : "");
        }
        out.add(new Row(n, depth, epic, shut, open.contains(n.bead.id), progress, shut ? 0 : n.closed));
        if (shut) return;
        for (BeadsModel.Node k : n.children) add(out, k, depth + 1, folded, open);
    }

    /**
     * A tap on a bead's title (no disclosure triangles): open, it closes, with
     * its beads folded away; closed, it opens its plan and unfolds its beads.
     * True when it is now open.
     */
    static boolean toggle(String id, boolean kids, Set<String> open, Set<String> folded) {
        if (open.remove(id)) {
            if (kids) folded.add(id);
            return false;
        }
        open.add(id);
        folded.remove(id);
        return true;
    }

    /** The ids above {@code id} in the tree, nearest first: unfolded so the agent's own bead shows. */
    static List<String> parentsOf(List<BeadsModel.Node> roots, String id) {
        Map<String, String> up = new HashMap<>();
        for (BeadsModel.Node n : roots) parents(n, null, up);
        List<String> out = new ArrayList<>();
        for (String p = up.get(id); p != null && !out.contains(p); p = up.get(p)) out.add(p);
        return out;
    }

    private static void parents(BeadsModel.Node n, String parent, Map<String, String> up) {
        if (parent != null) up.put(n.bead.id, parent);
        for (BeadsModel.Node k : n.children) parents(k, n.bead.id, up);
    }

    /**
     * A bead's plan as one document (the Fable spec, the preview's plan()):
     * its description (without a first heading that only repeats its title),
     * then "### Design", "### Acceptance", "### Notes", and "### Outcome" when
     * it is closed; a section that already starts with a heading keeps it.
     * Empty when nothing is written.
     */
    static String plan(BeadsModel.Detail d) {
        String title = d.title == null ? "" : d.title.trim().toLowerCase(java.util.Locale.ROOT);
        List<String> parts = new ArrayList<>();
        for (String[] s : d.sections) {
            String name = s[0].equals("Description") ? "" : s[0];
            String text = section(name, s[1], title);
            if (!text.isEmpty()) parts.add(text);
        }
        if ("closed".equals(d.status) && d.closeReason != null) {
            String outcome = section("Outcome", d.closeReason, title);
            if (!outcome.isEmpty()) parts.add(outcome);
        }
        return String.join("\n\n", parts);
    }

    private static String section(String label, String text, String title) {
        String t = text == null ? "" : text.trim();
        if (t.isEmpty()) return "";
        if (label.isEmpty()) {
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("^#\\s+(.*)\\n?").matcher(t);
            if (m.find() && m.group(1).trim().toLowerCase(java.util.Locale.ROOT).equals(title)) t = t.substring(m.end()).trim();
            return t;
        }
        return t.matches("(?s)^#{1,6}\\s.*") ? t : "### " + label + "\n\n" + t;
    }

    /** "No plan written yet · 3 beads below" when a bead has no plan. */
    static String noPlan(BeadsModel.Node node) {
        int kids = node == null ? 0 : node.children.size() + node.closed;
        return "No plan written yet" + (kids > 0 ? " · " + kids + (kids == 1 ? " bead below" : " beads below") : "") + ".";
    }

    /** "wolf · 11-22 10:00": a comment's author and minute. */
    static String byLine(BeadsModel.Comment c) {
        String at = c.at == null ? "" : c.at.replace('T', ' ');
        if (at.length() >= 16) at = at.substring(5, 16);
        return (c.author == null ? "someone" : c.author) + (at.isEmpty() ? "" : " · " + at);
    }

    /**
     * Who is clearly working on bead {@code id}: the live agents on this
     * machine whose issue label is that bead and whose project (its own label,
     * or its parent's) is {@code project}. Never a guess from titles or names.
     */
    static List<FleetTree.Member> assignees(List<? extends FleetTree.Member> members, String id, String project) {
        Map<String, FleetTree.Member> byId = new HashMap<>();
        for (FleetTree.Member m : members) byId.put(m.id, m);
        List<FleetTree.Member> out = new ArrayList<>();
        for (FleetTree.Member m : members) {
            if (!(m instanceof BeadsModel.Term) || !id.equals(((BeadsModel.Term) m).issue)) continue;
            if (!m.isAgent() || !FleetTree.running(m)) continue;
            FleetTree.Member parent = byId.get(m.parent);
            String own = !m.project.isEmpty() ? m.project : parent == null ? "" : parent.project;
            if (own.equals(project)) out.add(m);
        }
        return out;
    }
}
