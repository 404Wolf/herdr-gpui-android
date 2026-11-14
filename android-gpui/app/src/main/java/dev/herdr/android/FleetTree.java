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
 * The sidebar draws {@link #pmTree}: the Deacon and one row per PM, with each
 * checkout reduced to a terminal's second line. Plain data in, plain data
 * out: tested without Android.
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
        /** Its checkout, as the row's second line ({@link #where}); "" when unknown. */
        String where = "";
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
        /** The git repo (common dir) this checkout belongs to, or "" outside git. */
        String repo = "";
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
        /** The PM's own checkout row (kept even when it held only the PM), for its second line. */
        SpaceNode pmRow;
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
        /** Extra deacon candidates and terminals (agents or bare shells) with no space. */
        final List<Member> others = new ArrayList<>();
        /** Every terminal of this computer, in space/tab order. */
        final List<Member> all = new ArrayList<>();
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
        fleet.all.addAll(sorted);

        // A sub-agent without a place of its own takes its parent's (parent links are sparse).
        Map<String, Member> byId = new HashMap<>();
        for (Member m : sorted) byId.put(m.id, m);
        Map<String, String> adopted = new HashMap<>();
        for (Member m : sorted) {
            Member up = m;
            // A parent that is not listed ends the walk (null): the agent stays under Other agents.
            for (int hops = 0; hops < 8 && up != null && !up.hasSpace() && !up.parent.isEmpty(); hops++) {
                up = byId.get(up.parent);
            }
            if (!m.hasSpace() && up != null && up.hasSpace()) adopted.put(m.id, up.space);
        }
        // Spaces in order, with their members; terminals without a space go to Other terminals.
        Map<String, List<Member>> spaces = new LinkedHashMap<>();
        for (Member m : sorted) {
            if (m == fleet.deacon) continue;
            if ("deacon".equals(m.role)) { fleet.others.add(m); continue; }
            String space = m.hasSpace() ? m.space : adopted.get(m.id);
            // No space (nor a parent's to adopt): kept, shells too, so every terminal is listed once.
            if (space == null) {
                fleet.others.add(m);
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
                if (project.pm != null && row.members.contains(project.pm)) project.pmRow = row;
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
            if (node.repo.isEmpty()) node.repo = place.common;
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
        row.agents.addAll(nestAll(row.members, pm, Collections.emptyMap()));
    }

    /**
     * {@code members} in place order, each under its parent when that parent
     * is among them; the rest (the PM's own children among them) are roots.
     */
    private static List<AgentNode> nestAll(List<Member> members, Member pm, Map<Member, String> where) {
        List<Member> sorted = new ArrayList<>(members);
        Collections.sort(sorted, PLACE);
        Map<String, AgentNode> nodes = new LinkedHashMap<>();
        for (Member m : sorted) {
            AgentNode node = new AgentNode(m);
            node.where = where.getOrDefault(m, "");
            nodes.putIfAbsent(m.id, node);
        }
        Set<String> placed = new HashSet<>();
        for (AgentNode node : nodes.values()) {
            String parent = node.member.parent;
            AgentNode up = parent.isEmpty() || parent.equals(node.member.id) || pm != null && parent.equals(pm.id)
                    ? null : nodes.get(parent);
            // A parent cycle would hide both; only an ancestor chain that ends nests.
            if (up != null && !cycles(node.member, nodes)) { up.children.add(node); placed.add(node.member.id); }
        }
        List<AgentNode> roots = new ArrayList<>();
        for (AgentNode node : nodes.values()) if (!placed.contains(node.member.id)) roots.add(node);
        return roots;
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
        // Offline: what is shown is a cached view; nothing to open, nothing to start.
        if (!connected) return DeaconAction.NONE;
        if (deacon != null) return DeaconAction.OPEN;
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

    /**
     * Project folds as the desktop sidebar keeps them: every project starts
     * folded (a repo shows only its PM rows; opening one shows its worktrees
     * and workers). {@code opened} holds the projects Wolf opened. Nothing is
     * revealed at startup; a later focus change opens the focused agent's
     * project once, and a fold made after that is kept.
     */
    static final class Folds {
        final Set<String> opened;
        private String focus;
        Folds(Set<String> opened) { this.opened = opened; }

        boolean folded(String projectKey) { return !opened.contains(projectKey); }

        void toggle(String projectKey) { if (!opened.remove(projectKey)) opened.add(projectKey); }

        /** The focused terminal, seen on a refresh: true when this reveals (opens) {@code keys}. */
        boolean focusSeen(String now, java.util.Collection<String> keys) {
            if (now == null || now.isEmpty()) return false;
            if (focus == null) { focus = now; return false; } // startup: record, reveal nothing
            if (now.equals(focus)) return false;
            focus = now;
            return opened.addAll(keys);
        }
    }

    /** The rows of the sidebar that can fold, for {@link #foldedByDefault}. */
    enum Fold {
        /** The Deacon's own helpers, under the Deacon row. */
        DEACON,
        /** A PM row: its workers, nested by parent. */
        PROJECT,
        /** "Other terminals": everything no Deacon or PM leads. */
        OTHERS,
        /** An agent's own sub-agents. */
        AGENT
    }

    /**
     * The sidebar at rest is Deacon › PMs, as the desktop Fleet: everything
     * under the Deacon or a PM (helpers, workers, leftovers) waits behind its
     * chevron; once a PM is opened, its workers show their own sub-agents.
     */
    static boolean foldedByDefault(Fold fold) {
        return fold != Fold.AGENT;
    }

    /** One PM row of the sidebar: a project's manager, where it works, and its workers. */
    static final class PmRow {
        final Project project;
        /** The project's labelled PM, or null when its labelled workers have none ("no PM"). */
        final Member pm;
        /** The PM's checkout, as the row's second line ({@link #where}). */
        String where = "";
        /** Every other terminal of the project, across its checkouts, nested by parent. */
        final List<AgentNode> workers = new ArrayList<>();
        /** Projects whose unopened-worktree catalog this row's menu offers ("Worktrees…"). */
        final List<Project> catalogs = new ArrayList<>();
        PmRow(Project project) {
            this.project = project;
            this.pm = project.pm;
        }

        String name() { return project.name; }

        /** The PM or any of its workers is blocked: the row says so even while folded. */
        boolean blocked() { return project.tally[0] > 0; }
    }

    /** A project's main tap opens its PM, or its first worker when the PM is gone. */
    static Member projectTarget(PmRow row) {
        return row.pm != null ? row.pm : row.workers.isEmpty() ? null : row.workers.get(0).member;
    }

    /** A visible tap target for the full catalog, including entries beyond the first page. */
    static String unopenedLabel(Project project) {
        int count = project.closed.size();
        return count + (count == 1 ? " unopened worktree" : " unopened worktrees")
                + (project.closedIncomplete ? " (incomplete)" : "");
    }

    /**
     * What the sidebar (the terminal's rail and Home) draws for one computer:
     * the Deacon, one row per PM, and one folded row for everything else.
     * There are no repo, worktree or space rows; a terminal's checkout is its
     * second line instead.
     */
    static final class PmTree {
        Member deacon;
        /** Unlabelled agents of the Deacon's space, behind the Deacon's chevron. */
        final List<Member> helpers = new ArrayList<>();
        /** One per project a labelled PM (or labelled workers) leads, by project name. */
        final List<PmRow> pms = new ArrayList<>();
        /** Unlabelled projects, loose spaces, extra deacons and agents with no space, nested by parent. */
        final List<AgentNode> others = new ArrayList<>();
        /** The same terminals flat, in place order. */
        final List<Member> otherMembers = new ArrayList<>();
        /** Catalogs of unopened worktrees no PM row can offer: on Other terminals' menu. */
        final List<Project> otherCatalogs = new ArrayList<>();

        /** Anything under Other terminals is blocked. */
        boolean othersBlocked() {
            for (Member m : otherMembers) if (blocked(m)) return true;
            return false;
        }

        /** The Deacon or one of its helpers is blocked. */
        boolean deaconBlocked() {
            if (deacon != null && blocked(deacon)) return true;
            for (Member m : helpers) if (blocked(m)) return true;
            return false;
        }
    }

    static boolean blocked(Member m) {
        return "blocked".equals(m.status) || "error".equals(m.status);
    }

    /**
     * A project leads its own sidebar row when an agent in it is labelled
     * pm, or (its PM gone) when its agents are labelled workers. A project
     * inferred from folders and space order alone is not a PM.
     */
    static boolean managed(Project project) {
        if (project.pmInferred) return false;
        if (project.pm != null) return true;
        for (SpaceNode row : project.entries) for (Member m : row.members) if ("worker".equals(m.role)) return true;
        return false;
    }

    /**
     * A checkout as one short line: "⑂ branch" for a linked worktree (its
     * folder when detached), else "repo · branch", leaving out the repo when
     * it is the project's own name.
     */
    static String where(SpaceNode node, String project) {
        if (node == null) return "";
        if (node.worktree) return "⑂ " + (node.branch.isEmpty() ? node.name : node.branch);
        String name = node.repo.isEmpty() ? node.name : repoName(node.repo);
        if (name.equals(project)) name = "";
        String branch = node.branch.equals(name) ? "" : node.branch;
        if (name.isEmpty()) return branch;
        return branch.isEmpty() ? name : name + " · " + branch;
    }

    /**
     * The sidebar for {@code fleet}: the Deacon; one row per PM, ordered by
     * project name, its workers (every other terminal of its project, by role,
     * project and parent labels) nested under it by parent; and everything
     * else in Other terminals, so no terminal is ever dropped.
     */
    static PmTree pmTree(Fleet fleet) {
        PmTree tree = new PmTree();
        tree.deacon = fleet.deacon;
        tree.helpers.addAll(fleet.helpers);
        Map<Member, String> where = new HashMap<>();
        List<Member> rest = new ArrayList<>(fleet.others);
        for (Project project : fleet.projects) {
            boolean managed = managed(project);
            String own = managed ? project.name : "";
            List<Member> workers = new ArrayList<>();
            for (SpaceNode row : project.entries) {
                for (Member m : row.members) {
                    workers.add(m);
                    where.put(m, where(row, own));
                }
            }
            if (!managed) {
                if (project.pm != null) {
                    rest.add(project.pm);
                    where.put(project.pm, where(project.pmRow, ""));
                }
                rest.addAll(workers);
                continue;
            }
            PmRow row = new PmRow(project);
            row.where = where(project.pmRow, project.name);
            row.workers.addAll(nestAll(workers, project.pm, where));
            tree.pms.add(row);
        }
        for (SpaceNode node : fleet.loose) {
            for (Member m : node.members) {
                rest.add(m);
                where.put(m, where(node, ""));
            }
        }
        Collections.sort(tree.pms, (a, b) -> {
            int c = a.name().compareToIgnoreCase(b.name());
            return c != 0 ? c : a.name().compareTo(b.name());
        });
        // Each repo's unopened worktrees stay reachable from exactly one menu.
        for (Project project : fleet.projects) {
            if (project.closed.isEmpty()) continue;
            PmRow home = catalogRow(tree.pms, project);
            (home == null ? tree.otherCatalogs : home.catalogs).add(project);
        }
        Collections.sort(rest, PLACE);
        tree.otherMembers.addAll(rest);
        tree.others.addAll(nestAll(rest, null, where));
        return tree;
    }

    /**
     * The PM row whose menu offers {@code owner}'s catalog: its own row when
     * it has one; else (an owner inferred from folders) the first PM row, by
     * name, working in the same repo; else null (Other terminals' menu).
     */
    private static PmRow catalogRow(List<PmRow> pms, Project owner) {
        for (PmRow row : pms) if (row.project == owner) return row;
        if (owner.repo.isEmpty()) return null;
        for (PmRow row : pms) if (owner.repo.equals(row.project.repo)) return row;
        return null;
    }

    /** Every terminal under {@code nodes}, depth first. */
    static List<Member> flatten(List<AgentNode> nodes) {
        List<Member> out = new ArrayList<>();
        for (AgentNode node : nodes) {
            out.add(node.member);
            out.addAll(flatten(node.children));
        }
        return out;
    }

    /**
     * The agents the sidebar shows before anything is opened: the Deacon,
     * then each PM by project name. Built on {@link #foldedByDefault} so Home,
     * the rail and this list cannot disagree.
     */
    static List<Member> shownByDefault(Fleet fleet) {
        PmTree tree = pmTree(fleet);
        List<Member> out = new ArrayList<>();
        if (tree.deacon != null) {
            out.add(tree.deacon);
            if (!foldedByDefault(Fold.DEACON)) out.addAll(tree.helpers);
        }
        for (PmRow row : tree.pms) {
            if (row.pm != null) out.add(row.pm);
            if (!foldedByDefault(Fold.PROJECT)) out.addAll(flatten(row.workers));
        }
        if (!foldedByDefault(Fold.OTHERS)) out.addAll(tree.otherMembers);
        return out;
    }

    /** The project holding terminal {@code id} (as its PM or in one of its rows), or null. */
    static Project projectOf(Fleet fleet, String id) {
        for (Project p : fleet.projects) {
            if (p.pm != null && p.pm.id.equals(id)) return p;
            for (SpaceNode row : p.entries) for (Member m : row.members) if (m.id.equals(id)) return p;
        }
        return null;
    }

    private static final Set<String> GENERIC_NAMES = new HashSet<>(Arrays.asList(
            "general", "pm", "deacon", "worker", "main", "shell", "zsh", "bash", "claude", "claude code", "codex"));

    /** A tab or title that names no task: a role, a shell, a harness, a number, a terminal id. */
    static boolean genericName(String name) {
        String n = name == null ? "" : name.trim();
        return n.isEmpty() || GENERIC_NAMES.contains(n.toLowerCase(Locale.ROOT)) || n.matches("\\d+")
                || RailTree.isTerminalId(n);
    }

    /** A worker's task for its badge: its own title, else a tab name that says something, else its id. */
    static String workerTask(String title, String tabName, String id) {
        if (!genericName(title)) return title.trim();
        if (!genericName(tabName)) return tabName.trim();
        return id;
    }

    /** The pane header's role badge: "PM·t19", "worker·<task>", "Deacon·t6k"; "" without a role. */
    static String roleBadge(String role, String id, String task) {
        switch (role == null ? "" : role) {
            case "deacon": return "Deacon·" + id;
            case "pm": return "PM·" + id;
            case "worker": return "worker·" + (task == null || task.trim().isEmpty() ? id : task.trim());
            default: return "";
        }
    }

    /**
     * What picking a computer in the Fleet picker focuses: the exact
     * terminal of its one canonical live Deacon, or null, when the picker
     * shows Start Deacon instead (nothing is ever started by a pick).
     */
    static Member pickTarget(List<? extends Member> members) {
        return canonicalDeacon(members);
    }

    /**
     * Where switching to a computer lands, on every path (a computer row, the
     * rail's computer header or picker, falling back to another host): an
     * explicit terminal that is live there wins; else that computer's
     * canonical live Deacon; else "" and the caller keeps its own fallback
     * (the remembered or a busy terminal). {@code liveMembers} must be the new
     * session's own list, so a cached or vanished Deacon is never focused.
     */
    static String switchLanding(String explicit, List<? extends Member> liveMembers) {
        String wanted = explicit == null ? "" : explicit;
        if (!wanted.isEmpty()) for (Member m : liveMembers) if (m.id.equals(wanted)) return wanted;
        Member deacon = pickTarget(liveMembers);
        return deacon == null ? "" : deacon.id;
    }

    /** What a pick does: focus the Deacon, offer Start Deacon, or nothing (offline). */
    enum Pick { FOCUS, START, OFFLINE }

    /**
     * A pick on a computer that is offline or disconnected is unavailable:
     * its list is a cached view, so its Deacon is never focused and nothing
     * is offered. Online, {@code members} are the core's live terminals right
     * now; a cached Deacon missing from them gets Start Deacon, never a spawn.
     */
    static Pick pick(boolean connected, List<? extends Member> liveMembers) {
        if (!connected) return Pick.OFFLINE;
        return pickTarget(liveMembers) != null ? Pick.FOCUS : Pick.START;
    }

    /** One item of the role-aware top strip: an exact terminal to focus. */
    static final class StripItem {
        final String id, role, label, status;
        StripItem(String id, String role, String label, String status) {
            this.id = id; this.role = role; this.label = label; this.status = status;
        }
    }

    /** The top strip for a role context: "deacon" or "pm" mode, its title and items. */
    static final class Strip {
        final String mode, name;
        final List<StripItem> items = new ArrayList<>();
        Strip(String mode, String name) { this.mode = mode; this.name = name; }
    }

    private static StripItem stripItem(Member m, String role) {
        String label = "worker".equals(role) ? workerTask(m.title, "", m.id) : m.id;
        return new StripItem(m.id, role, label, m.status);
    }

    /**
     * The terminal's top strip for what is focused, as the desktop's stripFor:
     * the canonical Deacon focused shows the Deacon, then this computer's
     * labelled PMs; a labelled PM or one of its workers focused shows the PM,
     * then the project's live workers depth first by parent, across spaces;
     * anything else is null (the space's own tabs). Built from the live
     * fleet each time, so it can never show a stale group.
     */
    static Strip stripFor(Fleet fleet, String focusId) {
        return stripFor(fleet, focusId, true);
    }

    /**
     * The strip from this computer's terminals: anything another machine
     * reports through federation ("machine/tN") is dropped BEFORE the Deacon
     * or project is resolved, so a remote PM can never lead a local group or
     * join the Deacon's list, and a same-named remote project stays apart.
     * (The Fleet tree itself is unchanged.)
     */
    static Strip stripFor(List<? extends Member> members, GitLook git, String focusId, boolean connected) {
        List<Member> local = new ArrayList<>();
        for (Member m : members) if (!m.id.contains("/")) local.add(m);
        return stripFor(build(local, git), focusId, connected);
    }

    /**
     * As {@link #stripFor(Fleet, String)}, for a computer that may be offline:
     * then its list is a cached view and no group is offered. Only this
     * computer's own terminals ever join a group (never "machine/tN").
     */
    static Strip stripFor(Fleet fleet, String focusId, boolean connected) {
        if (!connected || focusId == null || focusId.isEmpty() || focusId.contains("/")) return null;
        if (fleet.deacon != null && fleet.deacon.id.equals(focusId)) {
            Strip strip = new Strip("deacon", "Deacon");
            strip.items.add(stripItem(fleet.deacon, "deacon"));
            for (Project p : fleet.projects) {
                if (p.pm != null && "pm".equals(p.pm.role) && !p.pm.id.contains("/")) strip.items.add(stripItem(p.pm, "pm"));
            }
            return strip;
        }
        Project project = projectOf(fleet, focusId);
        if (project == null || project.pm == null || !"pm".equals(project.pm.role) || project.pm.id.contains("/")) return null;
        List<Member> members = new ArrayList<>();
        for (SpaceNode row : project.entries) {
            for (Member m : row.members) if (m != project.pm && !m.id.contains("/")) members.add(m);
        }
        Collections.sort(members, PLACE);
        Set<String> inProject = new HashSet<>();
        for (Member m : members) inProject.add(m.id);
        Map<String, List<Member>> kids = new HashMap<>();
        for (Member m : members) {
            String up = !m.parent.isEmpty() && inProject.contains(m.parent) ? m.parent : project.pm.id;
            kids.computeIfAbsent(up, k -> new ArrayList<>()).add(m);
        }
        Strip strip = new Strip("pm", project.name);
        strip.items.add(stripItem(project.pm, "pm"));
        Set<String> seen = new HashSet<>();
        seen.add(project.pm.id);
        walkStrip(project.pm.id, kids, seen, strip);
        // A parent loop never hides anyone: whatever the walk missed comes last.
        for (Member m : members) if (seen.add(m.id)) strip.items.add(stripItem(m, "worker"));
        return strip;
    }

    /**
     * Per physical space, whether Wolf switched the top strip from the role
     * group to the space's own tabs (where +, close, move and rename live).
     * Backed by a persisted set, so the choice survives refreshes and
     * relaunches; it never changes which terminal is focused.
     */
    static final class StripChoice {
        final Set<String> physical;
        StripChoice(Set<String> physical) { this.physical = physical; }

        /** Keyed by computer and space: two machines' spaces with one id never share a choice. */
        static String key(String host, String space) {
            return (host == null ? "" : host) + "\n" + space;
        }

        boolean showsGroup(String host, String space, Strip strip) {
            return strip != null && !physical.contains(key(host, space));
        }

        void toggle(String host, String space) {
            String key = key(host, space);
            if (!physical.remove(key)) physical.add(key);
        }
    }

    private static void walkStrip(String id, Map<String, List<Member>> kids, Set<String> seen, Strip strip) {
        for (Member m : kids.getOrDefault(id, Collections.emptyList())) {
            if (!seen.add(m.id)) continue;
            strip.items.add(stripItem(m, "worker"));
            walkStrip(m.id, kids, seen, strip);
        }
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
