package dev.herdr.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.Test;

/** The same desktop placement and prepared counts feed Home and the terminal rail. */
public final class FleetSidebarTest {
    private static FleetTree.Member member(int id, String role, String project, String cwd, String status) {
        FleetTree.Member member = new FleetTree.Member();
        member.id = "t" + id;
        member.role = role;
        member.project = project;
        member.cwd = cwd;
        member.status = status;
        member.space = "s" + id;
        member.spaceName = project;
        member.spaceIndex = id;
        member.tabIndex = id;
        return member;
    }

    private static void place(FleetTree.GitLook git, String cwd, String repo, String top) {
        git.places.put(cwd, new FleetTree.Place(repo, top, "main"));
    }

    private static FleetTree.GitWorktree wt(String path) {
        return new FleetTree.GitWorktree(path, FleetTree.lastSegment(path), false, false);
    }

    private static FleetTree.PmRow project(FleetTree.PmTree tree, String name) {
        for (FleetTree.PmRow row : tree.allProjects) if (row.name().equals(name)) return row;
        throw new AssertionError(name);
    }

    @Test public void countsIncludeNestedAgentsButExcludeThePmShellsAndKeptShellLabels() {
        FleetTree.Member pm = member(1, "pm", "app", "/repo", "working");
        FleetTree.Member a = member(2, "worker", "app", "/wt/a", "working");
        FleetTree.Member b = member(3, "worker", "app", "/wt/b", "blocked");
        FleetTree.Member sub = member(4, "", "app", "/wt/a/src", "done");
        sub.parent = a.id;
        sub.space = a.space;
        FleetTree.Member shell = member(5, "", "app", "/repo", "");
        shell.space = pm.space;
        FleetTree.Member kept = member(6, "worker", "app", "/repo", "done");
        kept.kind = "claude";
        kept.program = "/usr/bin/zsh";
        FleetTree.GitLook git = new FleetTree.GitLook();
        place(git, pm.cwd, "/repo/.git", "/repo");
        FleetTree.PmTree tree = FleetTree.pmTree(FleetTree.build(Arrays.asList(sub, kept, shell, b, a, pm), git));
        FleetTree.PmRow row = project(tree, "app");
        assertEquals(3, row.subAgents);
        assertEquals("≡3 1! 2~ 1✓", row.countsLabel());
        assertTrue(row.labelledPm());
        assertEquals(5, FleetTree.flatten(row.workers).size());
        assertEquals("1 project  ≡3 1! 2~ 1✓", tree.repos.get(0).label(true));
        assertEquals("1 project  !", tree.repos.get(0).label(false));
        assertFalse(row.countsLabel().contains("↑")); // Legacy place records do not invent a zero or a count.
    }

    @Test public void aNoPmProjectSubtractsOnlyItsActualInferredLead() {
        FleetTree.Member lead = member(1, "", "repo", "/repo", "idle");
        FleetTree.Member worker = member(2, "worker", "repo", "/repo", "working");
        worker.parent = lead.id;
        FleetTree.GitLook git = new FleetTree.GitLook();
        place(git, lead.cwd, "/repo/.git", "/repo");
        FleetTree.PmTree tree = FleetTree.pmTree(FleetTree.build(Arrays.asList(worker, lead), git));
        FleetTree.PmRow row = project(tree, "repo");
        assertTrue(row.project.pmInferred);
        assertFalse(row.labelledPm());
        assertSame(lead, row.pm);
        assertSame(lead, FleetTree.projectTarget(row));
        assertEquals(1, row.subAgents);
        assertEquals("≡1 1~", row.countsLabel());
        assertSame(row, tree.repos.get(0).projects.get(0));
        assertTrue(tree.outside.isEmpty());
        assertEquals(Collections.singletonList(lead), FleetTree.shownByDefault(FleetTree.build(Arrays.asList(worker, lead), git)));
    }

    @Test public void aNoPmProjectWithoutAnInferredLeadCountsEveryWorker() {
        FleetTree.Member a = member(1, "worker", "repo", "/repo", "working");
        FleetTree.Member b = member(2, "worker", "repo", "/repo", "working");
        b.parent = a.id;
        FleetTree.GitLook git = new FleetTree.GitLook();
        place(git, a.cwd, "/repo/.git", "/repo");
        FleetTree.PmRow row = project(FleetTree.pmTree(FleetTree.build(Arrays.asList(a, b), git)), "repo");
        assertNull(row.pm);
        assertSame(a, FleetTree.projectTarget(row));
        assertEquals("≡2 2~", row.countsLabel());
    }

    @Test public void projectsCheckoutsCatalogsAndFootGroupsHaveAPlaceWithoutAMoreSection() {
        FleetTree.Member pm = member(1, "pm", "managed", "/repo", "idle");
        FleetTree.Member live = member(2, "", "live", "/repo/live", "working");
        FleetTree.Member idle = member(3, "worker", "quiet", "/repo/quiet", "");
        idle.program = "zsh";
        FleetTree.Member stray = member(4, "", "", "/stray", "");
        FleetTree.Member outside = member(5, "", "notes", "/notes", "working");
        FleetTree.Member outsideShell = member(6, "", "", "/tmp", "");
        FleetTree.Member other = member(7, "worker", "", "/elsewhere", "idle");
        other.space = "";
        FleetTree.Member bare = member(8, "", "", "/scratch", "");
        bare.space = "";
        FleetTree.GitLook git = new FleetTree.GitLook();
        place(git, pm.cwd, "/repo/.git", "/repo");
        place(git, live.cwd, "/repo/.git", "/repo/live");
        place(git, idle.cwd, "/repo/.git", "/repo/quiet");
        place(git, stray.cwd, "/repo/.git", "/stray");
        git.repos.put("/repo/.git", Arrays.asList(wt("/repo"), wt("/repo/live"), wt("/repo/quiet"), wt("/stray"), wt("/unopened")));
        List<FleetTree.Member> all = Arrays.asList(bare, other, outsideShell, outside, stray, idle, live, pm);
        FleetTree.PmTree tree = FleetTree.pmTree(FleetTree.build(all, git));
        assertEquals(1, tree.repos.size());
        FleetTree.RepoRow repo = tree.repos.get(0);
        assertEquals(Arrays.asList("managed", "live"), projectNames(repo.projects));
        assertEquals(Collections.singletonList("quiet"), projectNames(repo.idle));
        assertSame(stray, FleetTree.checkoutTarget(repo.loose.get(0)));
        assertEquals(1, repo.catalogs.size());
        assertEquals("/unopened", repo.catalogs.get(0).closed.get(0).path);
        assertEquals(1, tree.outside.size());
        assertEquals(2, tree.outsideLoose.size());
        assertEquals(3, tree.outsideCount());
        assertEquals(Collections.singletonList(other), tree.otherMembers);
        assertEquals(ids(all), reachable(tree));
        assertTrue(FleetTree.foldedByDefault(FleetTree.Fold.IDLE));
        assertTrue(FleetTree.foldedByDefault(FleetTree.Fold.OUTSIDE));
        assertTrue(FleetTree.foldedByDefault(FleetTree.Fold.OTHERS));
    }

    @Test public void aKnownRepoNameFromALaterCheckoutPlacesAnEarlierGitlessProject() {
        FleetTree.Member lead = member(1, "", "britton", "/unknown", "working");
        FleetTree.Member shell = member(2, "", "", "/repos/britton", "");
        FleetTree.GitLook git = new FleetTree.GitLook();
        place(git, shell.cwd, "/repos/britton/.git", "/repos/britton");
        for (List<FleetTree.Member> order : Arrays.asList(Arrays.asList(lead, shell), Arrays.asList(shell, lead))) {
            FleetTree.PmTree tree = FleetTree.pmTree(FleetTree.build(order, git));
            assertTrue(tree.outside.isEmpty());
            assertEquals("britton", tree.repos.get(0).name);
            assertSame(lead, tree.repos.get(0).projects.get(0).pm);
            assertEquals(1, tree.repos.get(0).loose.size());
        }
    }

    @Test public void duplicateRepoNamesShareAHeaderAndKeepBothCanonicalCatalogsOnce() {
        FleetTree.Member a = member(1, "pm", "alpha", "/client/repo", "idle");
        FleetTree.Member b = member(2, "pm", "beta", "/server/repo", "idle");
        FleetTree.GitLook git = new FleetTree.GitLook();
        place(git, a.cwd, "/client/repo/.git", a.cwd);
        place(git, b.cwd, "/server/repo/.git", b.cwd);
        git.repos.put("/client/repo/.git", Arrays.asList(wt(a.cwd), wt("/client/fix")));
        git.repos.put("/server/repo/.git", Arrays.asList(wt(b.cwd), wt("/server/fix")));
        FleetTree.PmTree tree = FleetTree.pmTree(FleetTree.build(Arrays.asList(a, b), git));
        assertEquals(1, tree.repos.size());
        FleetTree.RepoRow repo = tree.repos.get(0);
        assertEquals("2 projects", repo.countLabel());
        assertEquals(2, repo.catalogs.size());
        assertEquals("client/repo", repo.catalogName(repo.catalogs.get(0)));
        assertEquals("server/repo", repo.catalogName(repo.catalogs.get(1)));
        Set<String> paths = new HashSet<>();
        for (FleetTree.Project catalog : repo.catalogs) for (FleetTree.GitWorktree worktree : catalog.closed) {
            assertTrue(paths.add(worktree.path));
        }
        assertEquals(new HashSet<>(Arrays.asList("/client/fix", "/server/fix")), paths);
    }

    @Test public void aRepoWithOnlyAStrayShellStillGetsItsUnopenedCatalog() {
        FleetTree.Member shell = member(1, "", "", "/repo", "");
        FleetTree.GitLook git = new FleetTree.GitLook();
        place(git, shell.cwd, "/repo/.git", "/repo");
        git.repos.put("/repo/.git", Arrays.asList(wt("/repo"), wt("/fix")));
        FleetTree.PmTree tree = FleetTree.pmTree(FleetTree.build(Collections.singletonList(shell), git));
        FleetTree.RepoRow repo = tree.repos.get(0);
        assertTrue(repo.projects.isEmpty());
        assertEquals(1, repo.loose.size());
        assertEquals("/fix", repo.catalogs.get(0).closed.get(0).path);
        assertEquals(Collections.singleton(shell.id), reachable(tree));
    }

    @Test public void aShellOnlyProjectIsIdleAndDoesNotInflateFoldedRepoTotals() {
        FleetTree.Member shell = member(1, "", "quiet", "/repo", "");
        FleetTree.GitLook git = new FleetTree.GitLook();
        place(git, shell.cwd, "/repo/.git", "/repo");
        FleetTree.PmTree tree = FleetTree.pmTree(FleetTree.build(Collections.singletonList(shell), git));
        FleetTree.RepoRow repo = tree.repos.get(0);
        assertTrue(repo.projects.isEmpty());
        assertEquals(1, repo.idle.size());
        assertEquals("0 projects", repo.label(true));
        assertEquals("", repo.idle.get(0).countsLabel());
        assertSame(shell, FleetTree.projectTarget(repo.idle.get(0)));
    }

    @Test public void shellsInTwoCheckoutsOfOneSpaceStayInTheirOwnRepoLists() {
        FleetTree.Member a = member(1, "", "", "/a", "");
        FleetTree.Member b = member(2, "", "", "/b", "");
        b.space = a.space;
        FleetTree.GitLook git = new FleetTree.GitLook();
        place(git, a.cwd, "/a/.git", "/a");
        place(git, b.cwd, "/b/.git", "/b");
        FleetTree.PmTree tree = FleetTree.pmTree(FleetTree.build(Arrays.asList(a, b), git));
        assertEquals(2, tree.repos.size());
        assertEquals(Collections.singletonList(a), tree.repos.get(0).loose.get(0).members);
        assertEquals(Collections.singletonList(b), tree.repos.get(1).loose.get(0).members);
    }

    @Test public void spacedAndUnplacedShellsMergeOnceAndRemainTargetsAfterALateGitAnswer() {
        FleetTree.Member spaced = member(1, "", "", "/repo", "");
        FleetTree.Member loose = member(2, "", "", "/repo", "");
        loose.space = "loose";
        FleetTree.Member unplaced = member(3, "", "", "/repo", "");
        unplaced.space = "";
        List<FleetTree.Member> members = Arrays.asList(spaced, loose, unplaced);
        FleetTree.PmTree early = FleetTree.pmTree(FleetTree.build(members, null));
        assertEquals(ids(members), reachable(early));
        assertEquals(3, early.outsideLoose.size());

        FleetTree.GitLook git = new FleetTree.GitLook();
        place(git, "/repo", "/repo/.git", "/repo");
        FleetTree.Fleet fleet = FleetTree.build(members, git);
        FleetTree.PmTree first = FleetTree.pmTree(fleet);
        assertEquals(1, first.repos.get(0).loose.size());
        FleetTree.SpaceNode checkout = first.repos.get(0).loose.get(0);
        assertEquals("/repo", checkout.key);
        assertEquals(3, checkout.members.size());
        assertEquals(3, FleetTree.flatten(checkout.agents).size());
        Map<String, String> rows = actualRows(first);
        assertEquals(1L, rows.keySet().stream().filter(key -> key.startsWith("fleet:checkout:")).count());
        assertTrue(rows.containsKey("pane:t1"));
        assertTrue(rows.containsKey("pane:t2"));
        assertTrue(rows.containsKey("pane:t3"));
        assertEquals(ids(members), reachable(first));

        FleetTree.PmTree again = FleetTree.pmTree(fleet);
        assertEquals(new ArrayList<>(rows.entrySet()), new ArrayList<>(actualRows(again).entrySet()));
        assertEquals(3, FleetTree.flatten(again.repos.get(0).loose.get(0).agents).size());
        assertEquals(1, fleet.loose.get(0).members.size());
        assertEquals(1, fleet.loose.get(0).agents.size()); // The source forest was not appended to or mutated.
    }

    @Test public void projectsSharingASpaceAndDifferentComputersNeverShareCounts() {
        FleetTree.Member pmA = member(1, "pm", "alpha", "/repo", "idle");
        FleetTree.Member pmB = member(2, "pm", "beta", "/repo", "idle");
        FleetTree.Member worker = member(3, "worker", "beta", "/repo", "working");
        pmB.space = pmA.space;
        worker.space = pmA.space;
        FleetTree.GitLook git = new FleetTree.GitLook();
        place(git, "/repo", "/repo/.git", "/repo");
        FleetTree.PmTree first = FleetTree.pmTree(FleetTree.build(Arrays.asList(pmA, pmB, worker), git));
        assertEquals("", project(first, "alpha").countsLabel());
        assertEquals("≡1 1~", project(first, "beta").countsLabel());
        assertEquals("2 projects  ≡1 1~", first.repos.get(0).label(true));
        FleetTree.PmTree otherComputer = FleetTree.pmTree(FleetTree.build(Collections.singletonList(pmB), git));
        assertEquals("", project(otherComputer, "beta").countsLabel());
        assertEquals("≡1 1~", project(first, "beta").countsLabel());
    }

    @Test public void bothViewsConsumeTheSharedPlacementAndCountsWithoutAPmOverflow() throws IOException {
        for (String name : Arrays.asList("HomeActivity", "GpuiTerminalActivity")) {
            String source = new String(Files.readAllBytes(Paths.get("src/main/java/dev/herdr/android/" + name + ".java")),
                    StandardCharsets.UTF_8);
            assertTrue(name, source.contains("pm.countsLabel()"));
            assertTrue(name, source.contains("repo.label("));
            assertTrue(name, source.contains("repo.idle"));
            assertTrue(name, source.contains("repo.loose"));
            assertTrue(name, source.contains("repo.catalogs"));
            assertTrue(name, source.contains("\"Outside git\""));
            assertTrue(name, source.contains("\"Other agents\""));
            assertFalse(name, source.contains("group:others"));
            assertFalse(name, source.contains("tree.otherCatalogs"));
        }
    }

    private static List<String> projectNames(List<FleetTree.PmRow> rows) {
        List<String> out = new ArrayList<>();
        for (FleetTree.PmRow row : rows) out.add(row.name());
        return out;
    }

    private static Set<String> ids(List<FleetTree.Member> members) {
        Set<String> out = new HashSet<>();
        for (FleetTree.Member member : members) out.add(member.id);
        return out;
    }

    private static Set<String> reachable(FleetTree.PmTree tree) {
        Set<String> targets = new HashSet<>(actualRows(tree).values());
        targets.remove("");
        return targets;
    }

    /** Actual rail cache-key shapes with the constant host prefix omitted; every fold expanded. */
    private static Map<String, String> actualRows(FleetTree.PmTree tree) {
        Map<String, String> rows = new LinkedHashMap<>();
        if (tree.deacon != null) row(rows, "pane:" + tree.deacon.id, tree.deacon);
        for (FleetTree.Member helper : tree.helpers) row(rows, "pane:" + helper.id, helper);
        for (FleetTree.RepoRow repo : tree.repos) {
            row(rows, "fleet:repo:" + repo.key, null);
            for (FleetTree.PmRow project : repo.projects) projectRows(rows, project);
            if (!repo.idle.isEmpty()) {
                row(rows, "fleet:idle:" + repo.key, null);
                for (FleetTree.PmRow project : repo.idle) projectRows(rows, project);
            }
            for (FleetTree.SpaceNode checkout : repo.loose) checkoutRows(rows, checkout);
            for (FleetTree.Project catalog : repo.catalogs) row(rows, "fleet:catalog:" + catalog.repo, null);
        }
        if (tree.outsideCount() > 0) {
            row(rows, "fleet:group:outside", null);
            for (FleetTree.PmRow project : tree.outside) projectRows(rows, project);
            for (FleetTree.SpaceNode checkout : tree.outsideLoose) checkoutRows(rows, checkout);
        }
        if (!tree.others.isEmpty()) {
            row(rows, "fleet:group:other-agents", null);
            agentRows(rows, tree.others);
        }
        return rows;
    }

    private static void projectRows(Map<String, String> rows, FleetTree.PmRow project) {
        row(rows, project.labelledPm() ? "pane:" + project.pm.id : "fleet:proj:" + project.name(),
                FleetTree.projectTarget(project));
        agentRows(rows, project.workers);
    }

    private static void checkoutRows(Map<String, String> rows, FleetTree.SpaceNode checkout) {
        row(rows, "fleet:checkout:" + checkout.key, FleetTree.checkoutTarget(checkout));
        if (checkout.members.size() > 1) agentRows(rows, checkout.agents);
    }

    private static void agentRows(Map<String, String> rows, List<FleetTree.AgentNode> agents) {
        for (FleetTree.AgentNode agent : agents) {
            row(rows, "pane:" + agent.member.id, agent.member);
            agentRows(rows, agent.children);
        }
    }

    private static void row(Map<String, String> rows, String key, FleetTree.Member target) {
        assertFalse("duplicate actual row key " + key, rows.containsKey(key));
        rows.put(key, target == null ? "" : target.id);
    }
}
