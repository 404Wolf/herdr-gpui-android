package dev.herdr.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
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
        // Offline: a cached Deacon is neither opened nor replaced.
        assertEquals(FleetTree.DeaconAction.NONE, FleetTree.deaconAction(false, deacon, false));
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

    @Test public void rawRoleValuesAreNoRoleAndAKeptShellIsNotARunningDeacon() {
        assertEquals("", FleetTree.role("kache"));
        assertEquals("pm", FleetTree.role(" pm "));
        FleetTree.Member deacon = label(m("t1", "s", "deacon", 0, 0, "/h", "idle"), "deacon", "");
        deacon.program = "aswap";
        assertTrue(FleetTree.running(deacon));
        deacon.program = "-zsh";
        assertFalse(FleetTree.running(deacon));
        deacon.program = "/run/current-system/sw/bin/bash";
        assertFalse(FleetTree.running(deacon));
        deacon.program = "";
        assertTrue(FleetTree.running(deacon));
        // A kept shell still holds the role: it is shown, and Start is not offered.
        assertEquals(FleetTree.DeaconAction.OPEN, FleetTree.deaconAction(true, deacon, false));
    }

    @Test public void aDeaconWithClaudeMissingFailsVisiblyAndARetryLaunchesNothing() {
        FleetTree.Member launched = label(m("t9", "spX", "deacon", 0, 0, "/h/Config", ""), "deacon", "");
        launched.kind = "claude"; // labelled at birth with the harness
        // The login shell is still starting claude: wait.
        launched.program = "zsh";
        assertEquals(FleetTree.StartHealth.WAITING, FleetTree.startHealth(launched, 3_000, 45_000));
        // claude: command not found (127); the kept terminal stays a plain shell: failed, not "started".
        assertEquals(FleetTree.StartHealth.FAILED, FleetTree.startHealth(launched, 45_000, 45_000));
        // The terminal is gone (exited): failed.
        assertEquals(FleetTree.StartHealth.FAILED, FleetTree.startHealth(null, 6_000, 45_000));
        // Retry: the kept shell is still the canonical Deacon, so Start is not offered and nothing launches.
        FleetTree.Fleet fleet = FleetTree.build(Arrays.asList(launched), null);
        assertSame(launched, fleet.deacon);
        assertFalse(FleetTree.running(launched));
        assertEquals(FleetTree.DeaconAction.OPEN, FleetTree.deaconAction(true, fleet.deacon, false));
        // A healthy start: claude (through aswap) in the foreground, or a status report.
        launched.program = "aswap";
        assertEquals(FleetTree.StartHealth.RUNNING, FleetTree.startHealth(launched, 4_000, 45_000));
        launched.program = "zsh";
        launched.status = "idle";
        assertEquals(FleetTree.StartHealth.RUNNING, FleetTree.startHealth(launched, 4_000, 45_000));
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
        // The PM is the project row; its main checkout holds no one else, so no extra row.
        assertEquals(2, p.entries.size());
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
        // ONE row for the five lanes, with every agent (the main checkout holds only the lead).
        assertEquals(1, kache.entries.size());
        FleetTree.SpaceNode lanes = kache.entries.get(0);
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

    @Test public void everyUnopenedWorktreeIsKeptAndPagedSoTheLastCanBeChosen() {
        StringBuilder porcelain = new StringBuilder("worktree /r\nbare\n\n");
        for (int i = 0; i < 75; i++) porcelain.append("worktree /r/wt").append(i).append("\nbranch refs/heads/b").append(i).append("\n\n");
        porcelain.append("worktree /r/stale\nbranch refs/heads/s\nprunable gitdir file points to non-existent location\n\n");
        FleetTree.GitLook git = FleetTree.parseGitLook("P\t/r/wt0\t/r\t/r/wt0\tb0\nR\t/r\n" + porcelain + "E\n",
                FleetTree.PER_REPO);
        FleetTree.Project p = FleetTree.build(Arrays.asList(m("t1", "s", "r", 0, 0, "/r/wt0", "idle")), git).projects.get(0);
        // The full catalog: 74 unopened (wt0 is open; bare and prunable skipped), nothing cut.
        assertEquals(74, p.closed.size());
        assertFalse(p.closedIncomplete);
        for (FleetTree.GitWorktree wt : p.closed) assertFalse(wt.path.equals("/r/stale") || wt.path.equals("/r/wt0"));
        // Home and the rail draw shownClosed() rows; each "+N more" tap adds a page.
        assertEquals(30, FleetTree.shownClosed(p, 0));
        assertEquals(60, FleetTree.shownClosed(p, 1));
        assertEquals(74, FleetTree.shownClosed(p, 2));
        assertEquals("/r/wt74", p.closed.get(FleetTree.shownClosed(p, 2) - 1).path);
    }

    @Test public void aCutShortGitAnswerIsNeverShownAsComplete() {
        String body = "worktree /r\nbranch refs/heads/main\n\nworktree /r/a\nbranch refs/heads/a\n\n";
        // The core marks a list it had to cut ("T").
        FleetTree.GitLook marked = FleetTree.parseGitLook("P\t/r\t/r/.git\t/r\tmain\nR\t/r/.git\n" + body + "T\nE\n", 100);
        assertTrue(FleetTree.build(Arrays.asList(m("t1", "s", "r", 0, 0, "/r", "idle")), marked).projects.get(0).closedIncomplete);
        // An answer that stops inside a list.
        FleetTree.GitLook cut = FleetTree.parseGitLook("P\t/r\t/r/.git\t/r\tmain\nR\t/r/.git\n" + body, 100);
        assertTrue(cut.truncated.contains("/r/.git"));
        // More entries than the phone reads per repo.
        FleetTree.GitLook many = FleetTree.parseGitLook("P\t/r\t/r/.git\t/r\tmain\nR\t/r/.git\n" + body + "E\n", 1);
        assertTrue(many.truncated.contains("/r/.git"));
        // The whole exec output was truncated.
        FleetTree.GitLook all = FleetTree.parseGitLook("P\t/r\t/r/.git\t/r\tmain\nR\t/r/.git\n" + body + "E\n", 100);
        assertFalse(FleetTree.build(Arrays.asList(m("t1", "s", "r", 0, 0, "/r", "idle")), all).projects.get(0).closedIncomplete);
        all.allTruncated = true;
        assertTrue(FleetTree.build(Arrays.asList(m("t1", "s", "r", 0, 0, "/r", "idle")), all).projects.get(0).closedIncomplete);
    }

    @Test public void aChildInAnotherCheckoutStaysInItsOwnWorktree() {
        FleetTree.Member pm = label(m("t1", "s", "app", 0, 0, "/r/app", "idle"), "pm", "app");
        FleetTree.Member worker = label(m("t2", "s", "app", 0, 1, "/r/wt-a", "working"), "worker", "app");
        worker.parent = "t1";
        FleetTree.Member sub = label(m("t3", "s", "app", 0, 2, "/r/wt-b/src", "working"), "worker", "app");
        sub.parent = "t2"; // its parent works in another checkout
        FleetTree.GitLook git = FleetTree.parseGitLook(look(new String[][] {
                {"/r/app", "/r/app/.git", "/r/app", "main"},
                {"/r/wt-a", "/r/app/.git", "/r/wt-a", "a"},
                {"/r/wt-b/src", "/r/app/.git", "/r/wt-b", "b"}},
                "/r/app/.git", "/r/app=main", "/r/wt-a=a", "/r/wt-b=b"), 200);
        FleetTree.Project p = FleetTree.build(Arrays.asList(pm, worker, sub), git).projects.get(0);
        FleetTree.SpaceNode a = null, b = null;
        for (FleetTree.SpaceNode n : p.entries) {
            if (n.key.equals("/r/wt-a")) a = n;
            if (n.key.equals("/r/wt-b")) b = n;
        }
        assertEquals(Arrays.asList("t2"), agentIds(a));
        assertTrue(a.agents.get(0).children.isEmpty());
        assertEquals(Arrays.asList("t3"), agentIds(b));
        assertSame(sub, b.members.get(0));
        assertTrue(p.closed.isEmpty());
    }

    @Test public void reopeningAWorktreeFindsTheTerminalAlreadyThere() {
        FleetTree.Member there = m("t5", "s9", "", 3, 0, "/r/wt-c/src", "");
        List<FleetTree.Member> all = Arrays.asList(m("t1", "s", "app", 0, 0, "/r/wt-cc", "idle"), there);
        assertSame(there, FleetTree.memberAt(all, "/r/wt-c/"));
        assertNull(FleetTree.memberAt(all, "/r/wt-d"));
    }

    @Test public void newProjectGoesToEachComputersOwnDeacon() {
        String[] computers = {"laptop", "server", "Macro (Google)"};
        Set<String> targets = new HashSet<>();
        for (String computer : computers) {
            FleetTree.Member d = label(m("t1", "s", "deacon", 0, 0, "/h", "idle"), "deacon", "");
            d.title = computer;
            FleetTree.Fleet fleet = FleetTree.build(Arrays.asList(d, m("t2", "x", "app", 1, 0, "/w/app", "idle")), null);
            assertSame(d, FleetTree.newProjectTarget(fleet));
            targets.add(FleetTree.newProjectTarget(fleet).title);
            assertTrue(FleetTree.newProjectPrompt(computer).startsWith("I want to start a new project on " + computer + ". "));
        }
        assertEquals(3, targets.size());
        // An offline computer offers neither Start nor launches.
        assertEquals(FleetTree.DeaconAction.NONE, FleetTree.deaconAction(false, null, false));
    }

    @Test public void oneRepoUnderSeveralProjectLabelsListsItsWorktreesOnce() {
        // Wolf's desktop: home-manager, hypr2d and motion-cues all live in the home-manager repo.
        FleetTree.Member hm = label(m("t1", "s1", "home-manager", 0, 0, "/c/home-manager", "idle"), "pm", "home-manager");
        FleetTree.Member hypr = label(m("t2", "s2", "hypr2d", 1, 0, "/wt/hypr2d", "working"), "pm", "hypr2d");
        FleetTree.Member cues = label(m("t3", "s3", "motion-cues", 2, 0, "/wt/cues", "idle"), "pm", "motion-cues");
        FleetTree.GitLook git = FleetTree.parseGitLook(look(new String[][] {
                {"/c/home-manager", "/c/home-manager/.git", "/c/home-manager", "main"},
                {"/wt/hypr2d", "/c/home-manager/.git", "/wt/hypr2d", "hypr2d"},
                {"/wt/cues", "/c/home-manager/.git", "/wt/cues", "cues"}},
                "/c/home-manager/.git", "/c/home-manager=main", "/wt/hypr2d=hypr2d", "/wt/cues=cues",
                "/wt/idle-a=a", "/wt/idle-b=b"), 200);
        // Order must not matter: the owner is the project named after the repo.
        FleetTree.Fleet fleet = FleetTree.build(Arrays.asList(cues, hypr, hm), git);
        assertEquals(3, fleet.projects.size());
        Set<String> seen = new HashSet<>();
        int lists = 0;
        for (FleetTree.Project p : fleet.projects) {
            if (!p.closed.isEmpty()) lists++;
            for (FleetTree.GitWorktree wt : p.closed) assertTrue("listed twice: " + wt.path, seen.add(wt.path));
            for (FleetTree.SpaceNode row : p.entries) assertTrue("shown twice: " + row.key, seen.add(row.key));
        }
        assertEquals(1, lists);
        assertEquals(Arrays.asList("/wt/idle-a", "/wt/idle-b"), closedPaths(project(fleet, "home-manager")));
        // Without a project named after the repo, the PM on the main checkout owns the list.
        hm.project = "dotfiles";
        FleetTree.Fleet renamed = FleetTree.build(Arrays.asList(cues, hypr, hm), git);
        assertEquals(Arrays.asList("/wt/idle-a", "/wt/idle-b"), closedPaths(project(renamed, "dotfiles")));
        assertTrue(project(renamed, "hypr2d").closed.isEmpty() && project(renamed, "motion-cues").closed.isEmpty());
    }

    private static List<String> closedPaths(FleetTree.Project p) {
        List<String> out = new ArrayList<>();
        for (FleetTree.GitWorktree wt : p.closed) out.add(wt.path);
        return out;
    }

    @Test public void twoManagersInOneSpaceEachLeadTheirOwnProject() {
        // The laptop: t67 (PM home-manager) and t5t (PM botd) are tabs of one space;
        // t5t works from ~/Config (no git); botd's worker is in another space and repo.
        FleetTree.Member t67 = label(m("t67", "hm", "home-manager", 0, 0, "/c/home-manager", "working"), "pm", "home-manager");
        FleetTree.Member t5t = label(m("t5t", "hm", "home-manager", 0, 5, "/c", "done"), "pm", "botd");
        FleetTree.Member hmWorker = label(m("t6m", "hm", "home-manager", 0, 3, "/wt/fleet", "working"), "worker", "home-manager");
        hmWorker.parent = "t67";
        FleetTree.Member botdWorker = label(m("t5h", "b", "botd", 1, 0, "/wt/botd-w5", "working"), "worker", "");
        botdWorker.parent = "t5t"; // no project label: inherits its parent's
        FleetTree.Member shell = m("t4h", "hm", "home-manager", 0, 6, "/c/home-manager", ""); // a shell in the shared space
        FleetTree.GitLook git = FleetTree.parseGitLook(look(new String[][] {
                {"/c/home-manager", "/c/home-manager/.git", "/c/home-manager", "main"},
                {"/wt/fleet", "/c/home-manager/.git", "/wt/fleet", "fleet"},
                {"/wt/botd-w5", "/p/botd/.git", "/wt/botd-w5", "w5"},
                {"/c"}}, "/c/home-manager/.git", "/c/home-manager=main", "/wt/fleet=fleet", "/wt/hm-idle=idle")
                + "R\t/p/botd/.git\nworktree /p/botd\nbranch refs/heads/main\n\nworktree /wt/botd-w5\nbranch refs/heads/w5\n\n"
                + "worktree /wt/botd-idle\nbranch refs/heads/bi\n\nE\n", 200);
        FleetTree.Fleet fleet = FleetTree.build(Arrays.asList(t5t, shell, hmWorker, botdWorker, t67), git);
        FleetTree.Project hm = project(fleet, "home-manager");
        FleetTree.Project botd = project(fleet, "botd");
        assertSame(t67, hm.pm);
        assertSame(t5t, botd.pm);
        // botd: its worker's row only, its own repo's worktrees; no ghost home-manager row.
        assertEquals("/p/botd/.git", botd.repo);
        assertEquals(1, botd.entries.size());
        assertEquals("/wt/botd-w5", botd.entries.get(0).key);
        // Its main checkout is not open anywhere, so it is listed too.
        assertEquals(Arrays.asList("/p/botd", "/wt/botd-idle"), closedPaths(botd));
        for (FleetTree.SpaceNode row : botd.entries) assertFalse(row.key.startsWith("/c/home-manager"));
        // home-manager keeps its worker and its own worktree list.
        assertEquals("/c/home-manager/.git", hm.repo);
        assertEquals(Arrays.asList("/wt/hm-idle"), closedPaths(hm));
        // Every terminal exactly once; the shell of the shared space stays loose.
        List<String> all = new ArrayList<>();
        for (FleetTree.Project p : fleet.projects) {
            if (p.pm != null) all.add(p.pm.id);
            for (FleetTree.SpaceNode n : p.entries) all.addAll(ids(n.members));
        }
        for (FleetTree.SpaceNode n : fleet.loose) all.addAll(ids(n.members));
        all.addAll(ids(fleet.others));
        Collections.sort(all);
        assertEquals(Arrays.asList("t4h", "t5h", "t5t", "t67", "t6m"), all);
    }

    @Test public void aPmOutsideGitWithNoWorkersHasNoBorrowedCheckout() {
        FleetTree.Member a = label(m("t1", "s", "home-manager", 0, 0, "/c/home-manager", "idle"), "pm", "home-manager");
        FleetTree.Member b = label(m("t2", "s", "home-manager", 0, 1, "/c", "idle"), "pm", "botd");
        FleetTree.GitLook git = FleetTree.parseGitLook(look(new String[][] {
                {"/c/home-manager", "/c/home-manager/.git", "/c/home-manager", "main"}, {"/c"}},
                "/c/home-manager/.git", "/c/home-manager=main", "/wt/x=x"), 200);
        FleetTree.Fleet fleet = FleetTree.build(Arrays.asList(a, b), git);
        FleetTree.Project botd = project(fleet, "botd");
        assertSame(b, botd.pm);
        assertTrue(botd.entries.isEmpty());
        assertEquals("", botd.repo);
        assertTrue(botd.closed.isEmpty());
        assertEquals(Arrays.asList("/wt/x"), closedPaths(project(fleet, "home-manager")));
    }

    @Test public void anOrphanWithNoSpaceAndAMissingParentIsKeptUnderOtherAgents() {
        FleetTree.Member orphan = m("t9", "loose", "", Integer.MAX_VALUE, Integer.MAX_VALUE, "/h", "working");
        orphan.parent = "t404"; // not listed on this computer
        FleetTree.Member grandchild = m("t10", "", "", Integer.MAX_VALUE, Integer.MAX_VALUE, "/h", "idle");
        grandchild.parent = "t9";
        FleetTree.Fleet fleet = FleetTree.build(Arrays.asList(orphan, grandchild), null);
        assertEquals(new HashSet<>(Arrays.asList("t9", "t10")), new HashSet<>(ids(fleet.others)));
    }

    @Test public void aWorkerInANewLinkedWorktreeOfThePmSpaceKeepsItsWorktreeRow() {
        // The herdr-flow-smoke fixture: PM t6w in the main checkout, worker t6x a tab in the same
        // space, in a linked worktree created after the last git look (so git does not know it yet).
        FleetTree.Member pm = label(m("t6w", "sp9", "herdr-flow-smoke", 0, 0, "/tmp/smoke", "idle"), "pm", "herdr-flow-smoke");
        FleetTree.Member worker = label(m("t6x", "sp9", "herdr-flow-smoke", 0, 1,
                "/h/.herdr/worktrees/smoke/smoke-marker", "working"), "worker", "herdr-flow-smoke");
        worker.parent = "t6w";
        FleetTree.GitLook stale = FleetTree.parseGitLook(look(new String[][] {
                {"/tmp/smoke", "/tmp/smoke/.git", "/tmp/smoke", "main"}}, "/tmp/smoke/.git", "/tmp/smoke=main"), 200);
        FleetTree.GitLook fresh = FleetTree.parseGitLook(look(new String[][] {
                {"/tmp/smoke", "/tmp/smoke/.git", "/tmp/smoke", "main"},
                {"/h/.herdr/worktrees/smoke/smoke-marker", "/tmp/smoke/.git", "/h/.herdr/worktrees/smoke/smoke-marker", "smoke-marker"}},
                "/tmp/smoke/.git", "/tmp/smoke=main", "/h/.herdr/worktrees/smoke/smoke-marker=smoke-marker"), 200);
        for (FleetTree.GitLook git : new FleetTree.GitLook[] {stale, fresh, null}) {
            FleetTree.Project p = FleetTree.build(Arrays.asList(pm, worker), git).projects.get(0);
            assertSame(pm, p.pm);
            assertEquals(1, p.entries.size());
            FleetTree.SpaceNode row = p.entries.get(0);
            assertTrue(row.worktree);
            assertEquals("smoke-marker", row.name);
            assertEquals(Arrays.asList("t6x"), agentIds(row));
            // Not flattened under the PM: the "⑂ smoke-marker" row is drawn.
            assertFalse(FleetTree.flat(p));
            assertTrue(p.closed.isEmpty() || !closedPaths(p).contains(row.key));
        }
        // A worker in the PM's own checkout is still drawn flat under the PM.
        FleetTree.Member beside = label(m("t7", "sp9", "herdr-flow-smoke", 0, 2, "/tmp/smoke", "idle"), "worker", "herdr-flow-smoke");
        assertTrue(FleetTree.flat(FleetTree.build(Arrays.asList(pm, beside), fresh).projects.get(0)));
    }

    @Test public void reposGroupTheirProjectsAndOutsideGitComesLast() {
        FleetTree.Member deacon = label(m("t3", "d", "deacon", 0, 0, "/h", "idle"), "deacon", "");
        FleetTree.Member hm = label(m("t67", "hm", "home-manager", 1, 0, "/c/hm", "working"), "pm", "home-manager");
        FleetTree.Member hypr = label(m("t4m", "hy", "hypr2d", 2, 0, "/wt/hypr", "idle"), "pm", "hypr2d");
        FleetTree.Member botd = label(m("t5t", "hm", "home-manager", 1, 4, "/c", "blocked"), "pm", "botd"); // outside git
        FleetTree.Member stray = m("t9", "x", "scratch", 3, 0, "/tmp/x", ""); // a shell outside git
        FleetTree.Member kept = m("t8", "k", "", 4, 0, "/c/hm/sub", ""); // a shell in the repo
        FleetTree.GitLook git = FleetTree.parseGitLook(look(new String[][] {
                {"/c/hm", "/c/hm/.git", "/c/hm", "main"},
                {"/wt/hypr", "/c/hm/.git", "/wt/hypr", "hypr"},
                {"/c/hm/sub", "/c/hm/.git", "/c/hm", "main"}, {"/c"}, {"/tmp/x"}},
                "/c/hm/.git", "/c/hm=main", "/wt/hypr=hypr", "/wt/idle=idle"), 200);
        FleetTree.Fleet fleet = FleetTree.build(Arrays.asList(deacon, hm, hypr, botd, stray, kept), git);
        List<FleetTree.RepoGroup> groups = FleetTree.repoGroups(fleet);
        assertEquals(3, groups.size());
        FleetTree.RepoGroup repo = groups.get(0);
        assertEquals("hm", repo.name);
        assertEquals(2, repo.pms());
        assertEquals(Arrays.asList("home-manager", "hypr2d"), Arrays.asList(repo.projects.get(0).name, repo.projects.get(1).name));
        // Unopened worktrees: ONE owner per repo.
        assertEquals(Arrays.asList("/wt/idle"), closedPaths(repo.owner));
        // botd's PM is explicitly labelled but outside git: its own heading, not Outside git.
        FleetTree.RepoGroup noGit = groups.get(1);
        assertTrue(noGit.noGit());
        assertEquals("botd · no git", noGit.name);
        assertEquals("botd", noGit.projects.get(0).name);
        FleetTree.RepoGroup outside = groups.get(2);
        assertTrue(outside.outside());
        assertTrue(outside.projects.isEmpty());
        assertEquals(1, outside.loose.size());
        assertEquals("t9", outside.loose.get(0).members.get(0).id);
        // Needs you: the blocked PM, by its own status.
        assertEquals(Arrays.asList("t5t"), ids(FleetTree.needsYou(fleet)));
    }

    @Test public void needsYouListsTheDeaconFirstThenPmsThenTheRest() {
        FleetTree.Member deacon = label(m("t3", "d", "deacon", 0, 0, "/h", "blocked"), "deacon", "");
        FleetTree.Member worker = label(m("t1", "a", "app", 1, 1, "/w/app", "blocked"), "worker", "app");
        FleetTree.Member pm = label(m("t2", "a", "app", 1, 0, "/w/app", "blocked"), "pm", "app");
        FleetTree.Member idle = label(m("t4", "a", "app", 1, 2, "/w/app", "idle"), "worker", "app");
        FleetTree.Fleet fleet = FleetTree.build(Arrays.asList(worker, idle, pm, deacon), null);
        assertEquals(Arrays.asList("t3", "t2", "t1"), ids(FleetTree.needsYou(fleet)));
    }

    @Test public void projectsStartFoldedNothingOpensAtStartupAndAManualFoldIsKept() {
        FleetTree.Folds folds = new FleetTree.Folds(new HashSet<>());
        assertTrue(folds.folded("hm"));
        assertTrue(folds.folded("botd"));
        // Startup: the active worker's project is not opened.
        assertFalse(folds.focusSeen("t6m", Arrays.asList("hm")));
        assertTrue(folds.folded("hm"));
        // Wolf opens and folds by hand.
        folds.toggle("botd");
        assertFalse(folds.folded("botd"));
        // Moving to another window later opens its project once...
        assertTrue(folds.focusSeen("t5h", Arrays.asList("hm")));
        assertFalse(folds.folded("hm"));
        // ...and a fold made after that is kept while the focus stays.
        folds.toggle("hm");
        assertFalse(folds.focusSeen("t5h", Arrays.asList("hm")));
        assertTrue(folds.folded("hm"));
        // The focused agent's project is found by PM or worker.
        FleetTree.Member pm = label(m("t1", "s", "app", 0, 0, "/w/app", "idle"), "pm", "app");
        FleetTree.Member w = label(m("t2", "s", "app", 0, 1, "/w/app-wt", "working"), "worker", "app");
        FleetTree.Fleet fleet = FleetTree.build(Arrays.asList(pm, w), null);
        assertEquals("app", FleetTree.projectOf(fleet, "t2").name);
        assertEquals("app", FleetTree.projectOf(fleet, "t1").name);
        assertNull(FleetTree.projectOf(fleet, "t9"));
    }

    @Test public void aLabelledPmOutsideGitHasItsOwnHeadingAnUnlabelledOneDoesNot() {
        // project "phone", PM t2x in ~/Config (not a git repo).
        FleetTree.Member phone = label(m("t2x", "hm", "home-manager", 0, 3, "/c", "working"), "pm", "phone");
        FleetTree.Member loose = m("t9", "n", "notes", 1, 0, "/notes", "idle"); // unlabelled agent, no git
        FleetTree.GitLook git = FleetTree.parseGitLook("P\t/c\nP\t/notes\n", 200);
        List<FleetTree.RepoGroup> groups = FleetTree.repoGroups(FleetTree.build(Arrays.asList(phone, loose), git));
        assertEquals(2, groups.size());
        assertEquals("phone · no git", groups.get(0).name);
        assertSame(phone, groups.get(0).projects.get(0).pm);
        assertNull(groups.get(0).owner);
        assertTrue(groups.get(1).outside());
        assertEquals("notes", groups.get(1).projects.get(0).name);
    }

    @Test public void aWorkerBadgeShowsItsTaskNotAGenericTabName() {
        // t6z in the shared "general" tab: its task title, not "general".
        assertEquals("worker·Desktop Fleet sidebar", FleetTree.roleBadge("worker", "t6z",
                FleetTree.workerTask("Desktop Fleet sidebar", "general", "t6z")));
        // No title of its own: a specific tab name still says something.
        assertEquals("smoke-marker", FleetTree.workerTask("", "smoke-marker", "t6x"));
        // No title, a generic tab: its terminal id.
        assertEquals("worker·t6z", FleetTree.roleBadge("worker", "t6z", FleetTree.workerTask("", "general", "t6z")));
        for (String generic : new String[] {"General", "pm", "Deacon", "worker", "MAIN", "shell", "zsh", "bash",
                "claude", "Claude Code", "codex", "12", "t6z"}) {
            assertEquals(generic, "t6z", FleetTree.workerTask(generic, generic, "t6z"));
        }
    }

    @Test public void pickingAComputerResolvesToItsExactDeaconOrToStart() {
        // One computer: two deacon candidates; the canonical (oldest) one is focused.
        FleetTree.Member older = label(m("t6k", "d", "deacon", 0, 0, "/h", "idle"), "deacon", "");
        older.createdMs = 10;
        FleetTree.Member newer = label(m("t9", "d", "deacon", 0, 1, "/h", "working"), "deacon", "");
        newer.createdMs = 20;
        FleetTree.Member pm = label(m("t67", "hm", "home-manager", 1, 0, "/c", "working"), "pm", "home-manager");
        assertSame(older, FleetTree.pickTarget(Arrays.asList(newer, pm, older)));
        // Another computer with no Deacon: no target, the picker shows Start Deacon.
        List<FleetTree.Member> none = Arrays.asList(pm);
        assertNull(FleetTree.pickTarget(none));
        assertEquals(FleetTree.DeaconAction.START,
                FleetTree.deaconAction(true, FleetTree.pickTarget(none), false));
    }

    @Test public void anOfflineComputerOrAStaleDeaconIsNeverFocused() {
        FleetTree.Member cached = label(m("t6k", "d", "deacon", 0, 0, "/h", "idle"), "deacon", "");
        FleetTree.Member pm = label(m("t67", "hm", "home-manager", 1, 0, "/c", "working"), "pm", "home-manager");
        // Offline or disconnected: the cached list still holds a Deacon, but it is not focused or offered.
        assertEquals(FleetTree.Pick.OFFLINE, FleetTree.pick(false, Arrays.asList(cached, pm)));
        assertEquals(FleetTree.DeaconAction.NONE, FleetTree.deaconAction(false, cached, false));
        // Online, but the cached Deacon is not among the live terminals now: Start Deacon, no focus.
        assertEquals(FleetTree.Pick.START, FleetTree.pick(true, Arrays.asList(pm)));
        // Online and live: focus exactly it.
        assertEquals(FleetTree.Pick.FOCUS, FleetTree.pick(true, Arrays.asList(cached, pm)));
        assertSame(cached, FleetTree.pickTarget(Arrays.asList(cached, pm)));
    }

    @Test public void theTopStripFollowsTheRoleInFocus() {
        FleetTree.Member deacon = label(m("t6k", "d", "deacon", 0, 0, "/h", "idle"), "deacon", "");
        FleetTree.Member hmPm = label(m("t67", "hm", "home-manager", 1, 0, "/c/hm", "working"), "pm", "home-manager");
        FleetTree.Member w1 = label(m("t6m", "hm", "home-manager", 1, 3, "/wt/fleet", "working"), "worker", "home-manager");
        w1.parent = "t67";
        w1.title = "Desktop Fleet sidebar";
        FleetTree.Member sub = label(m("t7a", "other", "scratch", 3, 0, "/wt/fleet", "idle"), "worker", "home-manager");
        sub.parent = "t6m"; // another space: still in the project's strip, under its parent
        FleetTree.Member w2 = label(m("t6n", "hm", "home-manager", 1, 4, "/wt/deploy", "done"), "worker", "home-manager");
        w2.parent = "t67";
        FleetTree.Member botdPm = label(m("t5t", "hm", "home-manager", 1, 5, "/c", "idle"), "pm", "botd");
        FleetTree.Member inferred = m("t9", "z", "zulip", 2, 0, "/w/zulip", "idle"); // unlabelled lead
        FleetTree.Member shell = m("t2", "x", "x", 4, 0, "/tmp", "");
        FleetTree.Fleet fleet = FleetTree.build(Arrays.asList(deacon, hmPm, w1, sub, w2, botdPm, inferred, shell), null);
        // A PM focused: the PM first, then its live workers depth first by parent, across spaces.
        FleetTree.Strip pmStrip = FleetTree.stripFor(fleet, "t67");
        assertEquals("pm", pmStrip.mode);
        assertEquals("home-manager", pmStrip.name);
        assertEquals(Arrays.asList("t67", "t6m", "t7a", "t6n"), stripIds(pmStrip));
        assertEquals("Desktop Fleet sidebar", pmStrip.items.get(1).label);
        // One of its workers focused: the same project strip.
        assertEquals(stripIds(pmStrip), stripIds(FleetTree.stripFor(fleet, "t7a")));
        // The Deacon focused: the Deacon, then the labelled PMs (not an inferred lead).
        FleetTree.Strip deaconStrip = FleetTree.stripFor(fleet, "t6k");
        assertEquals("deacon", deaconStrip.mode);
        assertEquals(Arrays.asList("t6k", "t67", "t5t"), stripIds(deaconStrip));
        // No role context: null, so the space's own tabs show.
        assertNull(FleetTree.stripFor(fleet, "t9"));
        assertNull(FleetTree.stripFor(fleet, "t2"));
        assertNull(FleetTree.stripFor(fleet, ""));
        // Every item is an exact terminal of this computer: a tap focuses that id.
        for (FleetTree.StripItem item : pmStrip.items) assertTrue(ids(fleet.all).contains(item.id));
    }

    @Test public void theStripTogglesToPhysicalTabsPerSpaceAndBackWithoutMovingFocus() {
        FleetTree.Member pm = label(m("t67", "hm", "home-manager", 0, 0, "/c/hm", "working"), "pm", "home-manager");
        FleetTree.Member w = label(m("t6m", "hm", "home-manager", 0, 1, "/wt/f", "working"), "worker", "home-manager");
        w.parent = "t67";
        FleetTree.Member other = label(m("t5t", "hm", "home-manager", 0, 2, "/c", "idle"), "pm", "botd");
        FleetTree.Member otherWorker = label(m("t5h", "hm", "home-manager", 0, 3, "/w/botd", "working"), "worker", "botd");
        otherWorker.parent = "t5t";
        FleetTree.Member remote = label(m("server/t4", "hm", "home-manager", 0, 4, "/c/hm", "working"), "worker", "home-manager");
        remote.parent = "t67";
        FleetTree.Fleet fleet = FleetTree.build(Arrays.asList(pm, w, other, otherWorker, remote), null);
        String focus = "t6m";
        FleetTree.Strip strip = FleetTree.stripFor(fleet, focus, true);
        // A group never holds another project's or another machine's terminals.
        assertEquals(Arrays.asList("t67", "t6m"), stripIds(strip));
        // Toggle to the space's own tabs and back: the focused terminal is untouched.
        Set<String> saved = new HashSet<>();
        FleetTree.StripChoice choice = new FleetTree.StripChoice(saved);
        assertTrue(choice.showsGroup("laptop", "hm", strip));
        choice.toggle("laptop", "hm");
        assertFalse(choice.showsGroup("laptop", "hm", strip));
        assertTrue("another space keeps its own choice", choice.showsGroup("laptop", "other", strip));
        // The same space id on another machine keeps its own, independent choice.
        assertTrue(choice.showsGroup("server", "hm", strip));
        choice.toggle("server", "hm");
        choice.toggle("server", "hm");
        assertTrue(choice.showsGroup("server", "hm", strip));
        assertFalse(choice.showsGroup("laptop", "hm", strip));
        // A refresh (a new strip, the persisted set read again) keeps the choice.
        FleetTree.StripChoice reread = new FleetTree.StripChoice(new HashSet<>(saved));
        assertFalse(reread.showsGroup("laptop", "hm", FleetTree.stripFor(fleet, focus, true)));
        reread.toggle("laptop", "hm");
        assertTrue(reread.showsGroup("laptop", "hm", FleetTree.stripFor(fleet, focus, true)));
        assertEquals("t6m", focus);
        assertEquals(Arrays.asList("t67", "t6m"), stripIds(FleetTree.stripFor(fleet, focus, true)));
        // No role context: the space's tabs, whatever the choice.
        assertFalse(reread.showsGroup("laptop", "hm", null));
    }

    @Test public void theStripNeverLetsARemoteTerminalInOrLead() {
        FleetTree.GitLook none = null;
        FleetTree.Member deacon = label(m("t6k", "d", "deacon", 0, 0, "/h", "idle"), "deacon", "");
        FleetTree.Member localPm = label(m("t67", "hm", "home-manager", 1, 1, "/c/hm", "working"), "pm", "home-manager");
        FleetTree.Member remotePm = label(m("server/t5t", "b", "botd", 1, 0, "/c", "idle"), "pm", "botd");
        // A local Deacon plus a remote PM: the remote PM is never listed.
        assertEquals(Arrays.asList("t6k", "t67"),
                stripIds(FleetTree.stripFor(Arrays.asList(deacon, localPm, remotePm), none, "t6k", true)));
        // A local worker whose parent is a remote PM cannot take it as its lead.
        FleetTree.Member worker = label(m("t5h", "b", "botd", 1, 2, "/w/botd", "working"), "worker", "botd");
        worker.parent = "server/t5t";
        assertNull(FleetTree.stripFor(Arrays.asList(deacon, remotePm, worker), none, "t5h", true));
        // A same-named project on a remote machine stays apart: the local PM leads, alone with its own.
        FleetTree.Member remoteSame = label(m("server/t70", "hm", "home-manager", 0, 0, "/c/hm", "idle"), "pm", "home-manager");
        remoteSame.createdMs = 1;
        FleetTree.Member remoteWorker = label(m("server/t71", "hm", "home-manager", 0, 1, "/c/hm", "working"), "worker", "home-manager");
        FleetTree.Member localWorker = label(m("t6m", "hm", "home-manager", 1, 2, "/wt/f", "working"), "worker", "home-manager");
        localWorker.parent = "t67";
        assertEquals(Arrays.asList("t67", "t6m"), stripIds(FleetTree.stripFor(
                Arrays.asList(remoteSame, remoteWorker, localPm, localWorker), none, "t6m", true)));
    }

    @Test public void anOfflineComputersCachedGroupIsUnavailable() {
        FleetTree.Member pm = label(m("t67", "hm", "home-manager", 0, 0, "/c/hm", "working"), "pm", "home-manager");
        FleetTree.Member deacon = label(m("t6k", "d", "deacon", 1, 0, "/h", "idle"), "deacon", "");
        FleetTree.Fleet fleet = FleetTree.build(Arrays.asList(pm, deacon), null);
        assertNull(FleetTree.stripFor(fleet, "t67", false));
        assertNull(FleetTree.stripFor(fleet, "t6k", false));
        assertNotNull(FleetTree.stripFor(fleet, "t67", true));
    }

    private static List<String> stripIds(FleetTree.Strip strip) {
        List<String> out = new ArrayList<>();
        for (FleetTree.StripItem item : strip.items) out.add(item.id);
        return out;
    }

    @Test public void thePaneHeaderBadgeNamesTheRole() {
        assertEquals("PM·t19", FleetTree.roleBadge("pm", "t19", "ignored"));
        assertEquals("Deacon·t6k", FleetTree.roleBadge("deacon", "t6k", ""));
        assertEquals("worker·smoke-marker", FleetTree.roleBadge("worker", "t6x", " smoke-marker "));
        assertEquals("worker·t6x", FleetTree.roleBadge("worker", "t6x", ""));
        assertEquals("", FleetTree.roleBadge("", "t1", "x"));
    }

    @Test public void onlyAWholeSpaceOffersTheSpacesOwnMenu() {
        // The shared laptop space: t67's and t5t's projects each see a slice of it.
        FleetTree.Member t67 = label(m("t67", "hm", "home-manager", 0, 0, "/c/hm", "idle"), "pm", "home-manager");
        FleetTree.Member w = label(m("t2", "hm", "home-manager", 0, 1, "/c/hm", "working"), "worker", "home-manager");
        FleetTree.Member t5t = label(m("t5t", "hm", "home-manager", 0, 2, "/c", "idle"), "pm", "botd");
        FleetTree.Member bw = label(m("t5h", "b", "botd", 1, 0, "/w/botd", "working"), "worker", "botd");
        List<FleetTree.Member> all = Arrays.asList(t67, w, t5t, bw);
        FleetTree.Fleet fleet = FleetTree.build(all, null);
        // home-manager's row is a slice of the shared space (t67 and t5t are elsewhere): scoped.
        for (FleetTree.SpaceNode row : project(fleet, "home-manager").entries) assertNull(FleetTree.wholeSpace(row, all));
        // botd's worker row is its whole space: the space's own menu is fine there.
        assertEquals("b", FleetTree.wholeSpace(project(fleet, "botd").entries.get(0), all));
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
