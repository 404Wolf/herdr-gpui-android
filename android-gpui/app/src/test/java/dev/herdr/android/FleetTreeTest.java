package dev.herdr.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.Test;

/** The per-computer Fleet tree, per the desktop's Fleet tree contract. */
public final class FleetTreeTest {
    private static int nextSpace;

    private static FleetTree.Member m(String id, String space, String spaceName, int spaceIndex, int tabIndex,
            String cwd, String status) {
        FleetTree.Member m = new FleetTree.Member();
        m.id = id;
        m.title = id;
        m.space = space;
        m.spaceName = spaceName;
        m.spaceIndex = spaceIndex;
        m.tabIndex = tabIndex;
        m.cwd = cwd;
        m.status = status;
        m.kind = status.isEmpty() ? "" : "claude";
        return m;
    }

    private static FleetTree.Member label(FleetTree.Member m, String role, String project) {
        m.role = role;
        m.project = project;
        return m;
    }

    private static List<String> ids(List<? extends FleetTree.Member> list) {
        List<String> out = new ArrayList<>();
        for (FleetTree.Member m : list) out.add(m.id);
        return out;
    }

    private static List<String> agentIds(FleetTree.SpaceNode node) {
        List<String> out = new ArrayList<>();
        for (FleetTree.AgentNode a : node.agents) out.add(a.member.id);
        return out;
    }

    private static FleetTree.Project project(FleetTree.Fleet fleet, String name) {
        for (FleetTree.Project p : fleet.projects) if (p.name.equals(name)) return p;
        throw new AssertionError("no project " + name + " in " + names(fleet));
    }

    private static List<String> names(FleetTree.Fleet fleet) {
        List<String> out = new ArrayList<>();
        for (FleetTree.Project p : fleet.projects) out.add(p.name);
        return out;
    }

    /** git look text for repos: main checkout first, then worktrees. */
    private static String look(String[][] places, String common, String... worktrees) {
        StringBuilder text = new StringBuilder();
        for (String[] p : places) text.append("P\t").append(String.join("\t", p)).append('\n');
        text.append("R\t").append(common).append('\n');
        for (String wt : worktrees) {
            String[] f = wt.split("=");
            text.append("worktree ").append(f[0]).append("\nHEAD 0\n");
            text.append(f.length > 1 ? "branch refs/heads/" + f[1] : "detached").append("\n\n");
        }
        return text.append("E\n").toString();
    }

    // ── Deacon ───────────────────────────────────────────────────────────

    @Test public void duplicateDeaconLabelsKeepTheOldestAndShowTheRestAsOtherAgents() {
        FleetTree.Member young = label(m("t9", "s1", "deacon", 0, 0, "/h", "idle"), "deacon", "");
        young.createdMs = 500;
        FleetTree.Member old = label(m("t3", "s2", "misc", 1, 0, "/h", "working"), "deacon", "");
        old.createdMs = 100;
        FleetTree.Member untimed = label(m("t1", "s1", "deacon", 0, 1, "/h", "idle"), "deacon", "");
        FleetTree.Fleet fleet = FleetTree.build(Arrays.asList(young, old, untimed), null);
        assertSame(old, fleet.deacon);
        assertEquals(new HashSet<>(Arrays.asList("t9", "t1")), new HashSet<>(ids(fleet.others)));
    }

    @Test public void anUnlabelledAgentInTheDeaconSpaceIsTheFallbackDeaconOnly() {
        FleetTree.Member shell = m("t0", "sd", "Deacon", 0, 0, "/h", "");
        FleetTree.Member first = m("t5", "sd", "Deacon", 0, 1, "/h", "idle");
        FleetTree.Member helper = m("t6", "sd", "Deacon", 0, 2, "/h", "working");
        FleetTree.Fleet fleet = FleetTree.build(Arrays.asList(helper, shell, first), null);
        assertSame(first, fleet.deacon);
        assertEquals(Arrays.asList("t6"), ids(fleet.helpers));
        // The shell-only rest of the deacon space is not a project.
        assertTrue(fleet.projects.isEmpty());
    }

    @Test public void aMissingOrOfflineMachineOffersNoDuplicateStart() {
        FleetTree.Fleet empty = FleetTree.build(new ArrayList<>(), null);
        assertNull(empty.deacon);
        assertEquals(FleetTree.DeaconAction.NONE, FleetTree.deaconAction(false, null, false));
        assertEquals(FleetTree.DeaconAction.START, FleetTree.deaconAction(true, null, false));
        // A start in flight (or within the guard) never offers a second one.
        assertEquals(FleetTree.DeaconAction.STARTING, FleetTree.deaconAction(true, null, true));
        FleetTree.Member deacon = label(m("t1", "s", "deacon", 0, 0, "/h", "idle"), "deacon", "");
        assertEquals(FleetTree.DeaconAction.OPEN, FleetTree.deaconAction(true, deacon, true));
        assertEquals(FleetTree.DeaconAction.OPEN, FleetTree.deaconAction(false, deacon, false));
    }

    @Test public void gitIsAskedOnlyForConnectedUnfoldedMachinesAndBounded() {
        long now = 1_000_000;
        assertFalse(FleetTree.gitDue(false, false, false, true, now, 0, 300_000, 15_000));
        assertFalse(FleetTree.gitDue(true, true, false, true, now, 0, 300_000, 15_000));
        assertFalse(FleetTree.gitDue(true, false, true, true, now, 0, 300_000, 15_000));
        assertTrue(FleetTree.gitDue(true, false, false, true, now, now - 20_000, 300_000, 15_000));
        assertFalse(FleetTree.gitDue(true, false, false, false, now, now - 20_000, 300_000, 15_000));
        assertTrue(FleetTree.gitDue(true, false, false, false, now, now - 300_000, 300_000, 15_000));
    }

    @Test public void newProjectTalksToTheDeaconInPlainWords() {
        FleetTree.Member deacon = label(m("t1", "s", "deacon", 0, 0, "/h", "idle"), "deacon", "");
        FleetTree.Fleet fleet = FleetTree.build(Arrays.asList(deacon), null);
        assertSame(deacon, FleetTree.newProjectTarget(fleet));
        assertEquals("I want to start a new project on Macro (Google). Help me choose its goal and folder,"
                + " then set up its project manager and worktree.", FleetTree.newProjectPrompt("Macro (Google)\n"));
    }

    // ── Projects and managers ───────────────────────────────────────────

    @Test public void anExplicitWorkerIsNeverPromotedAndUnlabelledProjectsInferTheirLead() {
        FleetTree.Member worker = label(m("t2", "s1", "billing", 0, 0, "/w/billing", "working"), "worker", "billing");
        FleetTree.Member other = m("t3", "s1", "billing", 0, 1, "/w/billing", "idle");
        FleetTree.Member lead = m("t7", "s2", "zulip", 1, 0, "/w/zulip", "idle");
        FleetTree.Member second = m("t8", "s2", "zulip", 1, 1, "/w/zulip", "working");
        FleetTree.Fleet fleet = FleetTree.build(Arrays.asList(worker, other, second, lead), null);
        FleetTree.Project billing = project(fleet, "billing");
        assertNull(billing.pm);
        FleetTree.Project zulip = project(fleet, "zulip");
        assertSame(lead, zulip.pm);
        assertTrue(zulip.pmInferred);
        assertEquals(Arrays.asList("t8"), agentIds(zulip.entries.get(0)));
    }

    @Test public void aLabelledPmLeadsItsProjectOnceAndWorkersNestByParent() {
        FleetTree.Member pm = label(m("t1", "s1", "botd", 0, 0, "/w/botd", "idle"), "pm", "botd");
        FleetTree.Member w = label(m("t2", "s1", "botd", 0, 1, "/w/botd", "working"), "worker", "botd");
        w.parent = "t1";
        FleetTree.Member sub = m("t3", "s1", "botd", 0, 2, "/w/botd", "working");
        sub.parent = "t2";
        sub.project = "botd";
        FleetTree.Member sparse = m("t4", "s1", "botd", 0, 3, "/w/botd", "idle");
        sparse.parent = "t99"; // parent links are sparse: an unknown parent is top level
        sparse.project = "botd";
        FleetTree.Fleet fleet = FleetTree.build(Arrays.asList(sub, w, pm, sparse), null);
        FleetTree.Project botd = project(fleet, "botd");
        assertSame(pm, botd.pm);
        assertEquals(1, fleet.projects.size());
        FleetTree.SpaceNode row = botd.entries.get(0);
        assertEquals(Arrays.asList("t2", "t4"), agentIds(row));
        assertEquals("t3", row.agents.get(0).children.get(0).member.id);
        assertEquals(2, botd.tally[2]);
        assertEquals(2, botd.tally[3]);
    }

    @Test public void aSpacelessSubAgentJoinsItsParentsPlace() {
        FleetTree.Member pm = label(m("t19", "s1", "monopoly", 0, 0, "/srv/monopoly", "done"), "pm", "new-world");
        FleetTree.Member sub = label(m("t100", "loose", "", Integer.MAX_VALUE, Integer.MAX_VALUE, "/srv/monopoly", "done"),
                "worker", "new-world");
        sub.parent = "t19";
        FleetTree.Fleet fleet = FleetTree.build(Arrays.asList(pm, sub), null);
        assertTrue(fleet.others.isEmpty());
        assertEquals(Arrays.asList("t100"), agentIds(project(fleet, "new-world").entries.get(0)));
    }

    @Test public void worktreesJoinTheirMainCheckoutsLabelledProject() {
        FleetTree.Member pm = label(m("t19", "s1", "monopoly", 0, 0, "/srv/monopoly", "idle"), "pm", "new-world");
        FleetTree.Member wt = m("t2", "s2", "", 1, 0, "/srv/wt/socket", "working");
        FleetTree.Member stray = label(m("t5", "s3", "elsewhere", 2, 0, "/srv/other", "idle"), "", "new-world");
        FleetTree.GitLook git = FleetTree.parseGitLook(look(new String[][] {
                {"/srv/monopoly", "/srv/monopoly/.git", "/srv/monopoly", "dev"},
                {"/srv/wt/socket", "/srv/monopoly/.git", "/srv/wt/socket", "socket"}},
                "/srv/monopoly/.git", "/srv/monopoly=dev", "/srv/wt/socket=socket", "/srv/wt/closed=c")
                + "P\t/srv/other\t/srv/other/.git\t/srv/other\tmain\nR\t/srv/other/.git\nworktree /srv/other\n\n"
                + "worktree /srv/other-wt\nbranch refs/heads/o\n\nE\n", 200);
        FleetTree.Fleet fleet = FleetTree.build(Arrays.asList(pm, wt, stray), git);
        assertEquals(Arrays.asList("new-world"), names(fleet));
        FleetTree.Project p = project(fleet, "new-world");
        assertEquals(3, p.entries.size());
        assertEquals("/srv/monopoly/.git", p.repo);
        // Only the PM's repo lists closed worktrees; the stray space's repo never does.
        assertEquals(1, p.closed.size());
        assertEquals("/srv/wt/closed", p.closed.get(0).path);
    }

    // ── Worktrees ────────────────────────────────────────────────────────

    @Test public void aWorkerTabInItsOwnWorktreeIsItsOwnOpenRowInsideThePmSpace() {
        // One PM space: the PM's tab in the main checkout, a worker's tab in a custom worktree.
        FleetTree.Member pm = label(m("t67", "hm", "home-manager", 0, 0, "/c/home-manager", "working"), "pm", "home-manager");
        FleetTree.Member worker = label(m("t6m", "hm", "home-manager", 0, 3, "/wt/fleet tree/sub", "working"),
                "worker", "home-manager");
        worker.parent = "t67";
        FleetTree.Member mainWorker = label(m("t6n", "hm", "home-manager", 0, 4, "/c/home-manager", "idle"),
                "worker", "home-manager");
        FleetTree.GitLook git = FleetTree.parseGitLook(look(new String[][] {
                {"/c/home-manager", "/c/home-manager/.git", "/c/home-manager", "main"},
                {"/wt/fleet tree/sub", "/c/home-manager/.git", "/wt/fleet tree", "wolf/fleet-workflow-tree"}},
                "/c/home-manager/.git", "/c/home-manager=main", "/wt/fleet tree=wolf/fleet-workflow-tree",
                "/wt/elsewhere=other"), 200);
        FleetTree.Fleet fleet = FleetTree.build(Arrays.asList(mainWorker, worker, pm), git);
        FleetTree.Project p = project(fleet, "home-manager");
        assertSame(pm, p.pm);
        assertEquals(2, p.entries.size());
        FleetTree.SpaceNode main = p.entries.get(0), wt = p.entries.get(1);
        assertFalse(main.worktree);
        assertEquals(Arrays.asList("t6n"), agentIds(main));
        assertTrue(wt.worktree);
        assertEquals("/wt/fleet tree", wt.key);
        assertEquals("fleet tree", wt.name);
        assertEquals("wolf/fleet-workflow-tree", wt.branch);
        // Tapping the worktree focuses the worker's own tab.
        assertSame(worker, wt.members.get(0));
        // Open in a tab means not "unopened".
        assertEquals(1, p.closed.size());
        assertEquals("/wt/elsewhere", p.closed.get(0).path);
    }

    @Test public void spacesAtOneWorktreeMergeIntoOneRowAndUnrelatedReposStaySeparate() {
        // The server: many unlabelled "Kache lane" spaces at the same worktree.
        List<FleetTree.Member> all = new ArrayList<>();
        all.add(m("t1", "k0", "kache", 0, 0, "/srv/kache", "idle"));
        for (int i = 1; i <= 5; i++) {
            all.add(m("t1" + i, "lane" + i, "Kache lane " + i, i, 0, "/srv/lanes/kache-a/src", "working"));
        }
        all.add(m("t20", "z", "zulip", 9, 0, "/srv/zulip", "idle"));
        all.add(m("t21", "n", "notes", 10, 0, "/srv/notes", "idle"));
        FleetTree.GitLook git = FleetTree.parseGitLook(
                look(new String[][] {
                    {"/srv/kache", "/srv/kache/.git", "/srv/kache", "main"},
                    {"/srv/lanes/kache-a/src", "/srv/kache/.git", "/srv/lanes/kache-a", "lane-a"},
                }, "/srv/kache/.git", "/srv/kache=main", "/srv/lanes/kache-a=lane-a", "/srv/other place/b=b",
                        "/srv/gone=old")
                + "P\t/srv/zulip\t/srv/zulip/.git\t/srv/zulip\tmain\n"
                + "R\t/srv/zulip/.git\nworktree /srv/zulip\nbranch refs/heads/main\n\nE\n"
                + "P\t/srv/notes\n", 200);
        FleetTree.Fleet fleet = FleetTree.build(all, git);
        assertEquals(Arrays.asList("kache", "zulip", "notes"), names(fleet));
        FleetTree.Project kache = project(fleet, "kache");
        assertEquals("t1", kache.pm.id);
        // Main checkout first, then ONE row for the five lanes, with every agent.
        assertEquals(2, kache.entries.size());
        FleetTree.SpaceNode lanes = kache.entries.get(1);
        assertTrue(lanes.worktree);
        assertEquals("/srv/lanes/kache-a", lanes.key);
        assertEquals("lane-a", lanes.branch);
        assertEquals(5, lanes.spaces.size());
        assertEquals(5, lanes.agents.size());
        // Closed worktrees: the custom path is listed; open ones are not.
        List<String> closed = new ArrayList<>();
        for (FleetTree.GitWorktree wt : kache.closed) closed.add(wt.path);
        assertEquals(Arrays.asList("/srv/other place/b", "/srv/gone"), closed);
        assertTrue(project(fleet, "zulip").closed.isEmpty());
    }

    @Test public void withoutGitTheWorktreeFolderConventionGroupsAndArbitraryPathsStayApart() {
        FleetTree.Member a = m("t1", "s1", "", 0, 0, "/h/.herdr/worktrees/macro/wolf-x/src", "working");
        FleetTree.Member b = m("t2", "s2", "", 1, 0, "/h/.herdr/worktrees/macro/wolf-x", "idle");
        FleetTree.Member c = m("t3", "s3", "lab", 2, 0, "/opt/custom/lab-tree", "idle");
        FleetTree.Fleet fleet = FleetTree.build(Arrays.asList(a, b, c), null);
        assertEquals(Arrays.asList("macro", "lab"), names(fleet));
        FleetTree.Project macro = project(fleet, "macro");
        assertEquals(1, macro.entries.size());
        assertEquals(2, macro.entries.get(0).spaces.size());
    }

    @Test public void closedWorktreesAreCappedAndSkipBareAndPrunable() {
        StringBuilder porcelain = new StringBuilder("worktree /r\nbare\n\n");
        for (int i = 0; i < 40; i++) porcelain.append("worktree /r/wt").append(i).append("\nbranch refs/heads/b").append(i).append("\n\n");
        porcelain.append("worktree /r/stale\nbranch refs/heads/s\nprunable gitdir file points to non-existent location\n\n");
        FleetTree.GitLook git = FleetTree.parseGitLook("P\t/r/wt0\t/r\t/r/wt0\tb0\nR\t/r\n" + porcelain + "E\n", 200);
        FleetTree.Fleet fleet = FleetTree.build(Arrays.asList(m("t1", "s", "r", 0, 0, "/r/wt0", "idle")), git);
        FleetTree.Project p = fleet.projects.get(0);
        assertEquals(FleetTree.CLOSED_CAP, p.closed.size());
        assertEquals(39 - FleetTree.CLOSED_CAP, p.closedMore);
        for (FleetTree.GitWorktree wt : p.closed) assertFalse(wt.path.equals("/r/stale") || wt.path.equals("/r/wt0"));
    }

    // ── Leftovers are never dropped ─────────────────────────────────────

    @Test public void shellOnlySpacesAndSpacelessAgentsStayVisible() {
        FleetTree.Member shell = m("t1", "s1", "scratch", 0, 0, "/h", "");
        FleetTree.Member loose = m("t2", "loose", "", 0, 0, "/h", "working");
        FleetTree.Member agent = m("t3", "s2", "app", 1, 0, "/w/app", "idle");
        FleetTree.Fleet fleet = FleetTree.build(Arrays.asList(shell, loose, agent), null);
        assertEquals(1, fleet.loose.size());
        assertEquals(Arrays.asList("t1"), ids(fleet.loose.get(0).members));
        assertEquals(Arrays.asList("t2"), ids(fleet.others));
        Set<String> shown = new HashSet<>(ids(fleet.others));
        for (FleetTree.SpaceNode n : fleet.loose) shown.addAll(ids(n.members));
        for (FleetTree.Project p : fleet.projects) {
            if (p.pm != null) shown.add(p.pm.id);
            for (FleetTree.SpaceNode n : p.entries) shown.addAll(ids(n.members));
        }
        assertEquals(new HashSet<>(Arrays.asList("t1", "t2", "t3")), shown);
    }

    @Test public void porcelainParsesDetachedAndBranches() {
        List<FleetTree.GitWorktree> list = FleetTree.parsePorcelain(
                "worktree /a\nHEAD 1\nbranch refs/heads/main\n\nworktree /b c\nHEAD 2\ndetached\n", 10);
        assertEquals(2, list.size());
        assertEquals("main", list.get(0).branch);
        assertEquals("/b c", list.get(1).path);
        assertEquals("", list.get(1).branch);
    }
}
