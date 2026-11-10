package dev.herdr.android;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * One computer's Fleet tree (Deacon › projects/PM › main and worktree rows ›
 * workers), per the desktop's Fleet tree contract v1/v1.1 (spaces-wolf). It is
 * built per core connection, so agents of two computers never share a Deacon.
 * Plain data in, plain data out: tested without Android.
 */
final class FleetTree {
    private FleetTree() { }

    /** One terminal of this computer, as Home knows it. */
    static class Member {
        String id = "", title = "", status = "", space = "", spaceName = "", tab = "";
        String role = "", project = "", parent = "", kind = "", cwd = "", msg = "", waitKind = "";
        long statusMs, createdMs;
        int spaceIndex = Integer.MAX_VALUE, tabIndex = Integer.MAX_VALUE;

        /** Agents report a status or carry a harness or role; a bare shell does not. */
        boolean isAgent() {
            return !status.isEmpty() || !kind.isEmpty() || !role.isEmpty();
        }

        boolean hasSpace() {
            return !space.isEmpty() && !"loose".equals(space);
        }
    }

    /** What git says of one folder: its repo (common dir), checkout top and branch. */
    static final class Place {
        final String common, top, branch;
        Place(String common, String top, String branch) {
            this.common = trimSlash(common);
            this.top = trimSlash(top);
            this.branch = branch == null ? "" : branch;
        }
    }

    /** One `git worktree list --porcelain` entry. */
    static final class GitWorktree {
        final String path, branch;
        final boolean bare, prunable;
        GitWorktree(String path, String branch, boolean bare, boolean prunable) {
            this.path = trimSlash(path);
            this.branch = branch == null ? "" : branch;
            this.bare = bare;
            this.prunable = prunable;
        }
    }

    /** One machine's git answers: folder → place, repo common dir → its worktrees. */
    static final class GitLook {
        final Map<String, Place> places = new HashMap<>();
        final Map<String, List<GitWorktree>> repos = new HashMap<>();
    }

    /** An agent and the agents that name it as parent within the same project. */
    static final class AgentNode {
        final Member member;
        final List<AgentNode> children = new ArrayList<>();
        AgentNode(Member member) { this.member = member; }
    }

    /**
     * A main checkout or worktree row: every space pointing at the same
     * checkout merges into one row, with all of their agents.
     */
    static final class SpaceNode {
        final String key;
        String name = "", branch = "";
        boolean worktree;
        final List<String> spaces = new ArrayList<>();
        final List<Member> members = new ArrayList<>();
        final List<AgentNode> agents = new ArrayList<>();
        int order = Integer.MAX_VALUE;
        SpaceNode(String key) { this.key = key; }
    }

    static final class Project {
        final String name;
        Member pm;
        /** True when no agent is labelled pm and the lead was inferred from space/tab order. */
        boolean pmInferred;
        final List<SpaceNode> entries = new ArrayList<>();
        /** The repo's worktrees no space has open, at most CLOSED_CAP. */
        final List<GitWorktree> closed = new ArrayList<>();
        int closedMore;
        /** The repo (git common dir) whose worktrees are listed, or "". */
        String repo = "";
        /** blocked, done, working, idle over the project's agents. */
        final int[] tally = new int[4];
        Project(String name) { this.name = name; }

        int agents() {
            int n = pm == null ? 0 : 1;
            for (SpaceNode entry : entries) n += entry.members.size();
            return n;
        }
    }

    static final class Fleet {
        /** The one canonical Deacon, or null: then Home offers "Start Deacon". */
        Member deacon;
        /** Unlabelled agents of the deacon's own space: its helpers. */
        final List<Member> helpers = new ArrayList<>();
        final List<Project> projects = new ArrayList<>();
        /** Spaces with no project: shown under "Spaces", never dropped. */
        final List<SpaceNode> loose = new ArrayList<>();
        /** Extra deacon candidates and agents with no space: "Other agents". */
        final List<Member> others = new ArrayList<>();
    }

    static final int CLOSED_CAP = 30;

    private static long created(Member m) {
        return m.createdMs <= 0 ? Long.MAX_VALUE : m.createdMs;
    }

    private static final Comparator<Member> PLACE = (a, b) -> {
        int c = Integer.compare(a.spaceIndex, b.spaceIndex);
        if (c == 0) c = Integer.compare(a.tabIndex, b.tabIndex);
        if (c == 0) c = Long.compare(created(a), created(b));
        return c != 0 ? c : a.id.compareTo(b.id);
    };

    /** Deacon tie-break: oldest created_ms, then space/tab order, then address. */
    private static final Comparator<Member> AGE = (a, b) -> {
        int c = Long.compare(created(a), created(b));
        return c != 0 ? c : PLACE.compare(a, b);
    };

    static boolean deaconSpace(Member m) {
        return "deacon".equals(m.spaceName.trim().toLowerCase(Locale.ROOT));
    }

    /**
     * The canonical Deacon: role=deacon (oldest, then order), else the first
     * unlabelled agent in a space named "deacon". The fallback applies only here.
     */
    static Member canonicalDeacon(List<? extends Member> members) {
        List<Member> labelled = new ArrayList<>();
        for (Member m : members) if ("deacon".equals(m.role)) labelled.add(m);
        if (!labelled.isEmpty()) return Collections.min(labelled, AGE);
        List<Member> fallback = new ArrayList<>();
        for (Member m : members) if (m.role.isEmpty() && m.isAgent() && deaconSpace(m)) fallback.add(m);
        return fallback.isEmpty() ? null : Collections.min(fallback, PLACE);
    }

    /** The folder git is asked about for a space: its first agent's, else any member's. */
    static String probe(List<? extends Member> spaceMembers) {
        for (Member m : spaceMembers) if (m.isAgent() && !m.cwd.isEmpty()) return trimSlash(m.cwd);
        for (Member m : spaceMembers) if (!m.cwd.isEmpty()) return trimSlash(m.cwd);
        return "";
    }

    static Fleet build(List<? extends Member> members, GitLook git) {
        if (git == null) git = new GitLook();
        Fleet fleet = new Fleet();
        List<Member> sorted = new ArrayList<>(members);
        Collections.sort(sorted, PLACE);
        fleet.deacon = canonicalDeacon(sorted);

        // A sub-agent without a place of its own takes its parent's (parent links are sparse).
        Map<String, Member> byId = new HashMap<>();
        for (Member m : sorted) byId.put(m.id, m);
        Map<String, String> adopted = new HashMap<>();
        for (Member m : sorted) {
            Member up = m;
            for (int hops = 0; hops < 8 && !up.hasSpace() && !up.parent.isEmpty(); hops++) up = byId.get(up.parent);
            if (!m.hasSpace() && up != null && up.hasSpace()) adopted.put(m.id, up.space);
        }
        // Spaces in order, with their members; agents without a space go to Other agents.
        Map<String, List<Member>> spaces = new LinkedHashMap<>();
        for (Member m : sorted) {
            if (m == fleet.deacon) continue;
            if ("deacon".equals(m.role)) { fleet.others.add(m); continue; }
            String space = m.hasSpace() ? m.space : adopted.get(m.id);
            if (space == null) {
                if (m.isAgent()) fleet.others.add(m);
                continue;
            }
            if (m.project.isEmpty() && m.role.isEmpty() && deaconSpace(m) && m.isAgent()) {
                if (fleet.deacon != null && fleet.deacon.space.equals(m.space)) fleet.helpers.add(m);
                else if (fleet.deacon != null && deaconSpace(fleet.deacon)) fleet.helpers.add(m);
                else fleet.others.add(m);
                continue;
            }
            spaces.computeIfAbsent(space, k -> new ArrayList<>()).add(m);
        }

        // Each space's project: its PM's label, else its agents' label;
        // else its repo's (a linked worktree joins its main checkout); else its own name.
        Map<String, String> labelOf = new HashMap<>();
        Map<String, Place> placeOf = new HashMap<>();
        Map<String, String> repoProject = new HashMap<>();
        for (Map.Entry<String, List<Member>> entry : spaces.entrySet()) {
            String label = "";
            for (Member m : entry.getValue()) if ("pm".equals(m.role) && !m.project.isEmpty()) { label = m.project; break; }
            if (label.isEmpty()) for (Member m : entry.getValue()) if (!m.project.isEmpty()) { label = m.project; break; }
            labelOf.put(entry.getKey(), label);
            Place place = git.places.get(probe(entry.getValue()));
            if (place != null) placeOf.put(entry.getKey(), place);
        }
        // A linked worktree joins the project of its main checkout's space
        // (that space's label, else its name); with no such space, the repo's name.
        for (Map.Entry<String, List<Member>> entry : spaces.entrySet()) {
            Place place = placeOf.get(entry.getKey());
            if (place == null || place.common.isEmpty() || repoProject.containsKey(place.common)) continue;
            if (!place.top.equals(mainTop(place, git))) continue;
            String label = labelOf.get(entry.getKey());
            repoProject.put(place.common, label.isEmpty() ? spaceTitle(entry.getValue()) : label);
        }

        Map<String, Project> projects = new LinkedHashMap<>();
        Map<String, Map<String, SpaceNode>> entries = new HashMap<>();
        int order = 0;
        for (Map.Entry<String, List<Member>> entry : spaces.entrySet()) {
            List<Member> list = entry.getValue();
            Place place = placeOf.get(entry.getKey());
            String name = labelOf.get(entry.getKey());
            if (name.isEmpty() && place != null && repoProject.containsKey(place.common)) name = repoProject.get(place.common);
            if (name.isEmpty() && place != null && !place.common.isEmpty()) name = repoName(place.common);
            if (name.isEmpty()) name = conventionRepo(list);
            boolean agents = false;
            for (Member m : list) agents |= m.isAgent();
            if (name.isEmpty() && agents) name = spaceTitle(list);
            String rowKey = place != null && !place.top.isEmpty() ? place.top
                    : conventionTop(list) != null ? conventionTop(list) : "space:" + entry.getKey();
            if (name.isEmpty() || !agents) {
                SpaceNode node = new SpaceNode(rowKey);
                fill(node, entry.getKey(), list, place, order++, git);
                fleet.loose.add(node);
                continue;
            }
            Project project = projects.get(name);
            if (project == null) projects.put(name, project = new Project(name));
            Map<String, SpaceNode> rows = entries.computeIfAbsent(name, k -> new LinkedHashMap<>());
            SpaceNode node = rows.get(rowKey);
            if (node == null) rows.put(rowKey, node = new SpaceNode(rowKey));
            fill(node, entry.getKey(), list, place, order++, git);
            if (project.repo.isEmpty() && place != null) project.repo = place.common;
        }

        for (Project project : projects.values()) {
            List<SpaceNode> rows = new ArrayList<>(entries.get(project.name).values());
            List<Member> all = new ArrayList<>();
            for (SpaceNode row : rows) all.addAll(row.members);
            Collections.sort(all, PLACE);
            boolean explicitWorker = false;
            for (Member m : all) {
                if ("pm".equals(m.role) && project.pm == null) project.pm = m;
                if ("worker".equals(m.role)) explicitWorker = true;
            }
            // Unlabelled projects infer their lead from space/tab order; an
            // explicit worker is never promoted, a missing PM shows as missing.
            if (project.pm == null && !explicitWorker) {
                for (Member m : all) if (m.role.isEmpty() && m.isAgent()) { project.pm = m; project.pmInferred = true; break; }
            }
            // The repo listed is the PM's checkout's, else the first open one's.
            for (SpaceNode row : rows) if (project.pm != null && row.members.contains(project.pm)) {
                Place place = git.places.get(probe(row.members));
                if (place != null && !place.common.isEmpty()) project.repo = place.common;
            }
            // Main checkout first, then worktrees, in space order.
            Collections.sort(rows, (a, b) -> a.worktree != b.worktree ? (a.worktree ? 1 : 0) - (b.worktree ? 1 : 0)
                    : Integer.compare(a.order, b.order));
            for (SpaceNode row : rows) {
                row.members.remove(project.pm);
                nest(row, project.pm);
                for (Member m : row.members) count(project.tally, m.status);
            }
            if (project.pm != null) count(project.tally, project.pm.status);
            project.entries.addAll(rows);
            closedWorktrees(project, git);
            fleet.projects.add(project);
        }
        for (SpaceNode node : fleet.loose) nest(node, null);
        return fleet;
    }

    private static void fill(SpaceNode node, String space, List<Member> list, Place place, int order, GitLook git) {
        node.spaces.add(space);
        node.members.addAll(list);
        node.order = Math.min(node.order, order);
        if (node.name.isEmpty()) node.name = spaceTitle(list);
        if (place != null) {
            if (node.branch.isEmpty()) node.branch = place.branch;
            node.worktree = !place.top.isEmpty() && !place.top.equals(mainTop(place, git));
        } else if (conventionTop(list) != null) {
            node.worktree = true;
        }
    }

    /** The repo's main checkout: git's first worktree entry, else the common dir's parent. */
    private static String mainTop(Place place, GitLook git) {
        List<GitWorktree> list = git.repos.get(place.common);
        if (list != null && !list.isEmpty() && !list.get(0).bare) return list.get(0).path;
        return place.common.endsWith("/.git") ? place.common.substring(0, place.common.length() - 5) : "";
    }

    /** Agents nest under their parent when it is shown in the same row; the PM is the project row. */
    private static void nest(SpaceNode row, Member pm) {
        Collections.sort(row.members, PLACE);
        Map<String, AgentNode> nodes = new LinkedHashMap<>();
        for (Member m : row.members) nodes.put(m.id, new AgentNode(m));
        Set<String> placed = new HashSet<>();
        for (AgentNode node : nodes.values()) {
            String parent = node.member.parent;
            AgentNode up = parent.isEmpty() || parent.equals(node.member.id) || pm != null && parent.equals(pm.id)
                    ? null : nodes.get(parent);
            // A parent cycle would hide both; only an ancestor chain that ends nests.
            if (up != null && !cycles(node.member, nodes)) { up.children.add(node); placed.add(node.member.id); }
        }
        for (AgentNode node : nodes.values()) if (!placed.contains(node.member.id)) row.agents.add(node);
    }

    private static boolean cycles(Member start, Map<String, AgentNode> nodes) {
        Set<String> seen = new HashSet<>();
        Member at = start;
        while (at != null && !at.parent.isEmpty()) {
            if (!seen.add(at.id)) return true;
            AgentNode up = nodes.get(at.parent);
            at = up == null ? null : up.member;
        }
        return false;
    }

    private static void closedWorktrees(Project project, GitLook git) {
        List<GitWorktree> list = git.repos.get(project.repo);
        if (list == null) return;
        Set<String> open = new HashSet<>();
        for (SpaceNode row : project.entries) open.add(row.key);
        int shown = 0;
        for (GitWorktree wt : list) {
            if (wt.bare || wt.prunable || open.contains(wt.path)) continue;
            if (shown < CLOSED_CAP) { project.closed.add(wt); shown++; }
            else project.closedMore++;
        }
    }

    private static void count(int[] tally, String status) {
        switch (status) {
            case "blocked": case "error": tally[0]++; break;
            case "done": tally[1]++; break;
            case "working": tally[2]++; break;
            case "idle": tally[3]++; break;
            default: break;
        }
    }

    private static String spaceTitle(List<Member> list) {
        for (Member m : list) if (!m.spaceName.trim().isEmpty()) return m.spaceName.trim();
        String probe = probe(list);
        return probe.isEmpty() ? "" : lastSegment(probe);
    }

    /** "/w/kache/.git" → "kache"; a bare "kache.git" → "kache". */
    static String repoName(String common) {
        String p = trimSlash(common);
        if (p.endsWith("/.git")) p = p.substring(0, p.length() - 5);
        String name = lastSegment(p);
        return name.endsWith(".git") ? name.substring(0, name.length() - 4) : name;
    }

    /** Without git: ~/.herdr/worktrees/<repo>/<name> is the fallback convention. */
    private static String conventionRepo(List<Member> list) {
        for (Member m : list) {
            String repo = RailTree.repoOfPath(m.cwd);
            if (repo != null) return repo;
        }
        return "";
    }

    private static String conventionTop(List<Member> list) {
        for (Member m : list) {
            String name = RailTree.worktreeOfPath(m.cwd);
            if (name == null) continue;
            int at = m.cwd.indexOf("/.herdr/worktrees/");
            return m.cwd.substring(0, at) + "/.herdr/worktrees/" + RailTree.repoOfPath(m.cwd) + "/" + name;
        }
        return null;
    }

    /**
     * The machine's git look, as the core's "git.look" exec prints it:
     * "P\t<folder>\t<common>\t<top>\t<branch>" per folder (just "P\t<folder>"
     * outside git), then per repo "R\t<common>", its porcelain and "E".
     */
    static GitLook parseGitLook(String text, int perRepo) {
        GitLook look = new GitLook();
        String repo = null;
        StringBuilder porcelain = new StringBuilder();
        for (String line : (text == null ? "" : text).split("\n", -1)) {
            if (repo != null) {
                if (line.equals("E")) {
                    look.repos.put(repo, parsePorcelain(porcelain.toString(), perRepo));
                    repo = null;
                } else porcelain.append(line).append('\n');
                continue;
            }
            String[] f = line.split("\t", -1);
            if (f.length >= 4 && f[0].equals("P")) {
                look.places.put(trimSlash(f[1]), new Place(f[2], f[3], f.length > 4 ? f[4] : ""));
            } else if (f.length >= 2 && f[0].equals("R")) {
                repo = trimSlash(f[1]);
                porcelain.setLength(0);
            }
        }
        return look;
    }

    /** `git worktree list --porcelain` → entries, main checkout first. */
    static List<GitWorktree> parsePorcelain(String text, int limit) {
        List<GitWorktree> out = new ArrayList<>();
        String path = null, branch = "";
        boolean bare = false, prunable = false;
        for (String line : (text == null ? "" : text).split("\n", -1)) {
            if (line.startsWith("worktree ")) {
                path = line.substring(9);
                branch = "";
                bare = prunable = false;
            } else if (line.startsWith("branch ")) {
                branch = line.substring(7).replaceFirst("^refs/heads/", "");
            } else if (line.equals("bare")) {
                bare = true;
            } else if (line.startsWith("prunable")) {
                prunable = true;
            } else if (line.isEmpty() && path != null) {
                if (out.size() < limit) out.add(new GitWorktree(path, branch, bare, prunable));
                path = null;
            }
        }
        if (path != null && out.size() < limit) out.add(new GitWorktree(path, branch, bare, prunable));
        return out;
    }

    /** What a machine row offers for its Deacon. */
    enum DeaconAction { OPEN, START, STARTING, NONE }

    /**
     * Open the one Deacon; Start only on a connected machine with none and
     * nothing in flight (so taps never launch twice); offline offers nothing.
     */
    static DeaconAction deaconAction(boolean connected, Member deacon, boolean inFlight) {
        if (deacon != null) return DeaconAction.OPEN;
        if (!connected) return DeaconAction.NONE;
        return inFlight ? DeaconAction.STARTING : DeaconAction.START;
    }

    /**
     * Whether to ask a machine's git now: only connected, unfolded machines
     * with no look in flight; at most every {@code everyMs}, or {@code newMs}
     * once a folder it has not asked about appears.
     */
    static boolean gitDue(boolean connected, boolean folded, boolean inFlight, boolean newFolder,
            long now, long lastAt, long everyMs, long newMs) {
        if (!connected || folded || inFlight) return false;
        return now - lastAt >= (newFolder ? newMs : everyMs);
    }

    /** Who "New project" talks to: the computer's Deacon, never an automatic PM. */
    static Member newProjectTarget(Fleet fleet) {
        return fleet.deacon;
    }

    /** The one message "New project" sends to the machine's Deacon (same words as the desktop). */
    static String newProjectPrompt(String computer) {
        return "I want to start a new project on " + computer.replaceAll("\\p{Cntrl}", "").trim()
                + ". Help me choose its goal and folder, then set up its project manager and worktree.";
    }

    static String lastSegment(String path) {
        String p = trimSlash(path);
        return p.substring(p.lastIndexOf('/') + 1);
    }

    static String trimSlash(String path) {
        String p = path == null ? "" : path;
        while (p.length() > 1 && p.endsWith("/")) p = p.substring(0, p.length() - 1);
        return p;
    }
}
