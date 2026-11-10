package dev.herdr.android;

import java.util.ArrayList;
import java.util.Arrays;
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
        /** The foreground program's name, when the core says ("" when unknown). */
        String program = "";
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
        /** Repos whose worktree list was cut short; all of them when the whole answer was. */
        final Set<String> truncated = new HashSet<>();
        boolean allTruncated;
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
        /** Every one of the repo's worktrees no space has open (the full catalog). */
        final List<GitWorktree> closed = new ArrayList<>();
        /** True when git's answer was cut short, so the list may miss some. */
        boolean closedIncomplete;
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

    /** Unopened worktrees shown before "+N more"; a tap shows a page more. */
    static final int CLOSED_CAP = 30;
    /** Worktrees read per repo; past it the list says it is incomplete. */
    static final int PER_REPO = 5000;

    /** How many of a project's unopened worktrees to draw after {@code pages} taps on "+N more". */
    static int shownClosed(Project project, int pages) {
        long n = (long) CLOSED_CAP * (Math.max(0, pages) + 1);
        return (int) Math.min(project.closed.size(), n);
    }

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

    /** deacon, pm or worker; any other label value is treated as no role. */
    static String role(String value) {
        String v = value == null ? "" : value.trim();
        return v.equals("deacon") || v.equals("pm") || v.equals("worker") ? v : "";
    }

    private static final Set<String> SHELLS = new HashSet<>(Arrays.asList(
            "sh", "bash", "zsh", "fish", "dash", "ksh", "nu", "tcsh", "csh", "login"));

    /** False when a shell is in the foreground: the agent ended and its terminal was kept. */
    static boolean running(Member m) {
        String p = m.program.trim();
        if (p.startsWith("-")) p = p.substring(1);
        return p.isEmpty() || !SHELLS.contains(lastSegment(p));
    }

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

        // Each AGENT's project, not each space's: a PM's workers are tabs in
        // its space, but two managers can share one space too (t67 for
        // home-manager and t5t for botd). In order: its own project label;
        // its parent's project (same computer); its space's label when that
        // space carries exactly one; its own repo (a linked worktree joins
        // its main checkout's project); the folder convention; its space's name.
        Map<String, String> spaceOfMember = new HashMap<>();
        Map<String, Set<String>> spaceLabels = new HashMap<>();
        List<Member> placed = new ArrayList<>();
        for (Map.Entry<String, List<Member>> entry : spaces.entrySet()) {
            Set<String> labels = new java.util.TreeSet<>();
            for (Member m : entry.getValue()) {
                spaceOfMember.put(m.id, entry.getKey());
                placed.add(m);
                if (!m.project.isEmpty()) labels.add(m.project);
            }
            spaceLabels.put(entry.getKey(), labels);
        }
        Collections.sort(placed, PLACE);
        Map<String, String> projectOf = new HashMap<>();
        for (Member m : placed) if (m.isAgent()) {
            String p = labelledProject(m, byId, spaceOfMember, spaceLabels, new HashSet<>());
            if (!p.isEmpty()) projectOf.put(m.id, p);
        }
        // Repos: the project of the agents on each repo's main checkout.
        Map<String, String> repoProject = new HashMap<>();
        for (Member m : placed) {
            if (!m.isAgent()) continue;
            Place own = git.places.get(trimSlash(m.cwd));
            if (own == null || own.common.isEmpty() || !own.top.equals(mainTop(own, git))) continue;
            if (!projectOf.containsKey(m.id)) projectOf.put(m.id, spaceTitle(spaces.get(spaceOfMember.get(m.id))));
            repoProject.putIfAbsent(own.common, projectOf.get(m.id));
        }
        for (Member m : placed) {
            if (!m.isAgent() || projectOf.containsKey(m.id)) continue;
            Place own = git.places.get(trimSlash(m.cwd));
            String name = "";
            if (own != null && !own.common.isEmpty()) {
                name = repoProject.containsKey(own.common) ? repoProject.get(own.common) : repoName(own.common);
            }
            if (name.isEmpty()) name = conventionRepo(Collections.singletonList(m));
            if (name.isEmpty()) name = spaceTitle(spaces.get(spaceOfMember.get(m.id)));
            if (!name.isEmpty()) projectOf.put(m.id, name);
        }
        // Shells follow their space when all its agents are in one project; else they stay loose.
        Map<String, Set<String>> spaceProjects = new HashMap<>();
        for (Member m : placed) {
            if (projectOf.containsKey(m.id)) {
                spaceProjects.computeIfAbsent(spaceOfMember.get(m.id), k -> new HashSet<>()).add(projectOf.get(m.id));
            }
        }
        for (Member m : placed) {
            if (projectOf.containsKey(m.id)) continue;
            Set<String> ps = spaceProjects.get(spaceOfMember.get(m.id));
            if (ps != null && ps.size() == 1) projectOf.put(m.id, ps.iterator().next());
        }

        // Groups: each project holds its own members only, so one physical
        // space can take part in several projects; every terminal is placed once.
        Map<String, Project> projects = new LinkedHashMap<>();
        Map<String, Map<String, SpaceNode>> entries = new HashMap<>();
        Map<String, SpaceNode> looseBySpace = new LinkedHashMap<>();
        // Every checkout some terminal (a PM too) is in: never listed as unopened.
        Set<String> open = new HashSet<>();
        Map<String, Integer> spaceOrder = new HashMap<>();
        for (String space : spaces.keySet()) spaceOrder.put(space, spaceOrder.size());
        for (Member m : placed) {
            String space = spaceOfMember.get(m.id);
            int at = spaceOrder.get(space);
            Place spacePlace = git.places.get(probe(spaces.get(space)));
            String name = projectOf.get(m.id);
            if (name == null) {
                SpaceNode node = looseBySpace.get(space);
                if (node == null) {
                    String key = spacePlace != null && !spacePlace.top.isEmpty() ? spacePlace.top : "space:" + space;
                    looseBySpace.put(space, node = new SpaceNode(key));
                }
                open.add(node.key);
                fill(node, space, m, spacePlace, at, git);
                continue;
            }
            Project project = projects.get(name);
            if (project == null) projects.put(name, project = new Project(name));
            Map<String, SpaceNode> rows = entries.computeIfAbsent(name, k -> new LinkedHashMap<>());
            // Its OWN checkout first; the space's only when that space is this project's alone.
            Place own = git.places.get(trimSlash(m.cwd));
            Set<String> inSpace = spaceProjects.getOrDefault(space, Collections.emptySet());
            boolean spaceIsOurs = inSpace.size() <= 1;
            Place use = own != null ? own : spaceIsOurs ? spacePlace : null;
            String convention = conventionTop(Collections.singletonList(m));
            String key = own != null && !own.top.isEmpty() ? own.top
                    : convention != null ? convention
                    : use != null && !use.top.isEmpty() ? use.top : "space:" + space;
            SpaceNode node = rows.get(key);
            if (node == null) rows.put(key, node = new SpaceNode(key));
            fill(node, space, m, use, at, git);
            open.add(key);
        }
        fleet.loose.addAll(looseBySpace.values());

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
            // The repo listed: the PM's own; a PM outside git uses its own
            // members' repo, never another project's checkout.
            Place pmPlace = project.pm == null ? null : git.places.get(trimSlash(project.pm.cwd));
            if (pmPlace != null && !pmPlace.common.isEmpty()) project.repo = pmPlace.common;
            else {
                for (Member m : all) {
                    Place own = git.places.get(trimSlash(m.cwd));
                    if (own != null && !own.common.isEmpty()) { project.repo = own.common; break; }
                }
            }
            // Main checkout first, then worktrees, in space order.
            Collections.sort(rows, (a, b) -> a.worktree != b.worktree ? (a.worktree ? 1 : 0) - (b.worktree ? 1 : 0)
                    : Integer.compare(a.order, b.order));
            for (SpaceNode row : rows) {
                row.members.remove(project.pm);
                // A row that only held the PM is the PM row itself: no ghost checkout row.
                if (row.members.isEmpty()) continue;
                nest(row, project.pm);
                for (Member m : row.members) count(project.tally, m.status);
                project.entries.add(row);
            }
            if (project.pm != null) count(project.tally, project.pm.status);
            fleet.projects.add(project);
        }
        for (SpaceNode node : fleet.loose) nest(node, null);
        // Each repo's unopened worktrees are listed once, under one owner
        // project, minus every checkout open anywhere on this computer.
        Set<String> listed = new HashSet<>();
        for (Project project : fleet.projects) {
            if (project.repo.isEmpty() || !listed.add(project.repo)) continue;
            closedWorktrees(repoOwner(fleet.projects, project.repo, git), git, open);
        }
        return fleet;
    }

    /** An agent's project from labels: its own, its parent's, its space's when unambiguous. */
    private static String labelledProject(Member m, Map<String, Member> byId, Map<String, String> spaceOf,
            Map<String, Set<String>> spaceLabels, Set<String> seen) {
        if (!m.project.isEmpty()) return m.project;
        if (!seen.add(m.id)) return "";
        Member parent = m.parent.isEmpty() ? null : byId.get(m.parent);
        if (parent != null && parent != m && !"deacon".equals(parent.role)) {
            String up = labelledProject(parent, byId, spaceOf, spaceLabels, seen);
            if (!up.isEmpty()) return up;
        }
        Set<String> labels = spaceLabels.get(spaceOf.get(m.id));
        return labels != null && labels.size() == 1 ? labels.iterator().next() : "";
    }

    private static void fill(SpaceNode node, String space, Member m, Place place, int order, GitLook git) {
        if (!node.spaces.contains(space)) node.spaces.add(space);
        node.members.add(m);
        node.order = Math.min(node.order, order);
        List<Member> one = Collections.singletonList(m);
        if (place != null && place.top.equals(node.key)) {
            if (node.branch.isEmpty()) node.branch = place.branch;
            node.worktree = !place.top.equals(mainTop(place, git));
        } else if (conventionTop(one) != null && conventionTop(one).equals(node.key)) {
            node.worktree = true;
        }
        // A worktree row is named after its checkout; a main or plain row after its space.
        if (node.worktree) {
            node.name = lastSegment(node.key);
        } else if (node.name.isEmpty() && node.key.startsWith("space:") && !m.cwd.isEmpty()) {
            // Outside git in a space shared by projects: named by its folder, not the space.
            node.name = lastSegment(m.cwd);
        } else if (node.name.isEmpty()) {
            String title = spaceTitle(one);
            node.name = title.isEmpty() ? lastSegment(node.key) : title;
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

    /**
     * The one project that lists a repo's unopened worktrees: the one named
     * after the repo, else the one whose PM works in the main checkout, else
     * the first in order. Other projects labelled onto that repo keep their
     * agents but never repeat the list.
     */
    static Project repoOwner(List<Project> projects, String repo, GitLook git) {
        Project first = null, onMain = null;
        String name = repoName(repo);
        for (Project p : projects) {
            if (!repo.equals(p.repo)) continue;
            if (p.name.equals(name)) return p;
            if (first == null) first = p;
            if (onMain == null && p.pm != null) {
                Place place = git.places.get(trimSlash(p.pm.cwd));
                if (place != null && place.top.equals(mainTop(place, git))) onMain = p;
            }
        }
        return onMain != null ? onMain : first;
    }

    private static void closedWorktrees(Project project, GitLook git, Set<String> open) {
        List<GitWorktree> list = git.repos.get(project.repo);
        if (list == null) return;
        for (GitWorktree wt : list) {
            if (wt.bare || wt.prunable || open.contains(wt.path)) continue;
            project.closed.add(wt);
        }
        project.closedIncomplete = git.allTruncated || git.truncated.contains(project.repo);
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
                    List<GitWorktree> list = parsePorcelain(porcelain.toString(), perRepo + 1);
                    if (list.size() > perRepo) {
                        list = new ArrayList<>(list.subList(0, perRepo));
                        look.truncated.add(repo);
                    }
                    look.repos.put(repo, list);
                    repo = null;
                } else if (line.equals("T")) {
                    look.truncated.add(repo); // the core cut this repo's list short
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
        // An answer that ends inside a repo's list was cut short too.
        if (repo != null) {
            look.repos.put(repo, parsePorcelain(porcelain.toString(), perRepo));
            look.truncated.add(repo);
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

    enum StartHealth { WAITING, RUNNING, FAILED }

    /**
     * A just-launched agent: RUNNING once it reports a status or a non-shell
     * program runs in it; FAILED when its terminal is gone, or still a plain
     * shell after {@code limitMs} (claude missing, exit 127, kept as a shell).
     */
    static StartHealth startHealth(Member launched, long elapsedMs, long limitMs) {
        if (launched == null) return StartHealth.FAILED;
        if (!launched.status.isEmpty()) return StartHealth.RUNNING;
        if (!launched.program.trim().isEmpty() && running(launched)) return StartHealth.RUNNING;
        return elapsedMs >= limitMs ? StartHealth.FAILED : StartHealth.WAITING;
    }

    /** A terminal already working in {@code folder} (fresh view, not the git cache), or null. */
    static Member memberAt(List<? extends Member> members, String folder) {
        String root = trimSlash(folder);
        for (Member m : members) if (within(trimSlash(m.cwd), root)) return m;
        return null;
    }

    static boolean within(String path, String root) {
        return !root.isEmpty() && (path.equals(root) || path.startsWith(root.endsWith("/") ? root : root + "/"));
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
