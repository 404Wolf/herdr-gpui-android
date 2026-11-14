package dev.herdr.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
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
import java.util.List;
import java.util.Set;
import org.junit.Test;

/** Repo headings organize the same PM rows; they never add or promote terminals. */
public final class FleetReposTest {
    private static FleetTree.Member member(String id, String role, String project, String cwd) {
        FleetTree.Member m = new FleetTree.Member();
        m.id = id;
        m.role = role;
        m.project = project;
        m.cwd = cwd;
        m.space = id + "-space";
        m.spaceName = project;
        m.spaceIndex = Integer.parseInt(id.substring(1));
        m.tabIndex = 0;
        return m;
    }

    private static void place(FleetTree.GitLook git, String cwd, String common, String top) {
        git.places.put(cwd, new FleetTree.Place(common, top, "main"));
    }

    private static List<String> names(FleetTree.RepoRow repo) {
        List<String> out = new ArrayList<>();
        for (FleetTree.PmRow pm : repo.projects) out.add(pm.name());
        return out;
    }

    @Test public void mainAndArbitraryLinkedCheckoutsShareOneHeaderWithAProjectCount() {
        FleetTree.Member main = member("t1", "pm", "Zeta", "/repos/monopoly/src");
        FleetTree.Member linked = member("t2", "pm", "alpha", "/tmp/linked/src");
        FleetTree.GitLook git = new FleetTree.GitLook();
        place(git, main.cwd, "/repos/monopoly/.git", "/repos/monopoly");
        place(git, linked.cwd, "/repos/monopoly/.git", "/tmp/linked");
        FleetTree.PmTree tree = FleetTree.pmTree(FleetTree.build(Arrays.asList(linked, main), git));
        assertEquals(1, tree.repos.size());
        FleetTree.RepoRow repo = tree.repos.get(0);
        assertEquals("monopoly", repo.key);
        assertEquals("monopoly", repo.name);
        assertEquals("2 projects", repo.countLabel());
        assertEquals(Arrays.asList("Zeta", "alpha"), names(repo));
        assertSame(tree.pms.get(1), repo.projects.get(0));
        assertSame(tree.pms.get(0), repo.projects.get(1));
    }

    @Test public void beforeGitArrivesTheConventionalWorktreePathGroupsDifferentProjectLabels() {
        FleetTree.Member a = member("t1", "pm", "phone", "/h/.herdr/worktrees/herdr-android/phone/src");
        FleetTree.Member b = member("t2", "pm", "desktop", "/h/.herdr/worktrees/herdr-android/desktop");
        FleetTree.RepoRow repo = FleetTree.pmTree(FleetTree.build(Arrays.asList(a, b), null)).repos.get(0);
        assertEquals("herdr-android", repo.name);
        assertEquals(Arrays.asList("phone", "desktop"), names(repo));
        assertEquals("2 projects", repo.countLabel());
    }

    @Test public void aPmOutsideKnownGitFallsBackToItsProjectLabel() {
        FleetTree.Member pm = member("t1", "pm", "home-manager-conf", "/unknown/src");
        FleetTree.RepoRow repo = FleetTree.pmTree(FleetTree.build(Collections.singletonList(pm), null)).repos.get(0);
        assertEquals("home-manager-conf", repo.name);
        assertEquals("1 project", repo.countLabel());
        assertSame(pm, repo.projects.get(0).pm);
    }

    @Test public void theProjectsOtherCheckoutsBackfillUntilThePmsOwnGitArrives() {
        FleetTree.Member pm = member("t1", "pm", "phone", "/h/.herdr/worktrees/herdr-android/phone");
        FleetTree.Member worker = member("t2", "worker", "phone", "/repos/zulip");
        worker.parent = pm.id;
        FleetTree.GitLook git = new FleetTree.GitLook();
        place(git, worker.cwd, "/repos/zulip/.git", "/repos/zulip");
        FleetTree.PmTree early = FleetTree.pmTree(FleetTree.build(Arrays.asList(pm, worker), git));
        assertEquals("zulip", early.repos.get(0).name);
        assertSame(worker, early.repos.get(0).projects.get(0).workers.get(0).member);
        // Once the PM's own git answer arrives it wins over both worker repo and path convention.
        place(git, pm.cwd, "/repos/android-renamed/.git", pm.cwd);
        FleetTree.PmTree fresh = FleetTree.pmTree(FleetTree.build(Arrays.asList(worker, pm), git));
        assertEquals("android-renamed", fresh.repos.get(0).name);
    }

    @Test public void twoRepositoriesWithTheSameBasenameShareOneHeader() {
        FleetTree.Member a = member("t1", "pm", "beta", "/a/repo");
        FleetTree.Member b = member("t2", "pm", "alpha", "/b/repo");
        FleetTree.GitLook git = new FleetTree.GitLook();
        place(git, a.cwd, "/a/repo/.git", a.cwd);
        place(git, b.cwd, "/b/repo/.git", b.cwd);
        FleetTree.PmTree tree = FleetTree.pmTree(FleetTree.build(Arrays.asList(b, a), git));
        assertEquals(1, tree.repos.size());
        assertEquals("repo", tree.repos.get(0).key);
        assertEquals(Arrays.asList("beta", "alpha"), names(tree.repos.get(0)));
    }

    @Test public void missingPmProjectsStayClickableAndBlockedWorkBubblesToTheRepo() {
        FleetTree.Member orphan = member("t1", "worker", "fixes", "/repos/zulip");
        orphan.status = "blocked";
        FleetTree.GitLook git = new FleetTree.GitLook();
        place(git, orphan.cwd, "/repos/zulip/.git", orphan.cwd);
        FleetTree.RepoRow repo = FleetTree.pmTree(FleetTree.build(Collections.singletonList(orphan), git)).repos.get(0);
        assertEquals("zulip", repo.name);
        assertEquals("1 project", repo.countLabel());
        assertTrue(repo.blocked());
        assertSame(orphan, FleetTree.projectTarget(repo.projects.get(0)));
    }

    @Test public void headersKeepProjectOrderAndEveryProjectAppearsOnce() {
        FleetTree.Member a = member("t1", "pm", "A project", "/h/.herdr/worktrees/Zulip/feature");
        FleetTree.Member b = member("t2", "pm", "B project", "/h/.herdr/worktrees/gitea/feature");
        FleetTree.Member c = member("t3", "pm", "C project", "/h/.herdr/worktrees/Zulip/another");
        FleetTree.PmTree tree = FleetTree.pmTree(FleetTree.build(Arrays.asList(c, b, a), null));
        assertEquals(2, tree.repos.size());
        assertEquals("Zulip", tree.repos.get(0).name);
        assertEquals(Arrays.asList("A project", "C project"), names(tree.repos.get(0)));
        assertEquals("gitea", tree.repos.get(1).name);
        assertEquals(Collections.singletonList("B project"), names(tree.repos.get(1)));
        List<FleetTree.PmRow> shown = new ArrayList<>();
        for (FleetTree.RepoRow repo : tree.repos) shown.addAll(repo.projects);
        assertEquals(tree.pms.size(), shown.size());
        assertEquals(new HashSet<>(tree.pms), new HashSet<>(shown));
    }

    @Test public void repoCountsExcludeTheDeaconHelpersAndUnlabelledTerminals() {
        FleetTree.Member deacon = member("t1", "deacon", "", "/h");
        deacon.spaceName = "deacon";
        FleetTree.Member helper = member("t2", "", "", "/h");
        helper.space = deacon.space;
        helper.spaceName = "deacon";
        helper.status = "working";
        FleetTree.Member plain = member("t3", "", "", "/tmp");
        FleetTree.Member pm = member("t4", "pm", "gitea", "/repos/gitea");
        FleetTree.PmTree tree = FleetTree.pmTree(FleetTree.build(Arrays.asList(deacon, helper, plain, pm), null));
        assertSame(deacon, tree.deacon);
        assertTrue(tree.helpers.contains(helper));
        assertEquals(1, tree.repos.size());
        assertEquals("1 project", tree.repos.get(0).countLabel());
        assertSame(pm, tree.repos.get(0).projects.get(0).pm);
        assertTrue(tree.outsideLoose.stream().anyMatch(node -> node.members.contains(plain)));
    }

    @Test public void reposStartOpenAndSelectionRevealsOnlyOnARealFocusChange() {
        assertFalse(FleetTree.foldedByDefault(FleetTree.Fold.REPO));
        assertTrue(FleetTree.foldedByDefault(FleetTree.Fold.PROJECT));
        String repoA = "host-a/fleet/repo:/repo/.git", repoB = "host-b/fleet/repo:/repo/.git";
        Set<String> stored = new HashSet<>(Arrays.asList(repoA, repoB));
        FleetTree.Folds folds = new FleetTree.Folds(stored);
        assertFalse(folds.focusSeen("host-a/t1", Collections.singletonList("project-a:open"),
                Collections.singletonList(repoA)));
        assertTrue(stored.contains(repoA)); // Startup does not unfold anything.
        assertTrue(folds.focusSeen("host-a/t2", Collections.singletonList("project-a:open"),
                Collections.singletonList(repoA)));
        assertFalse(stored.contains(repoA));
        assertTrue(stored.contains(repoB));
        assertFalse(folds.folded("project-a:open"));
        stored.add(repoA); // A manual fold while this terminal stays focused.
        assertFalse(folds.focusSeen("host-a/t2", Collections.singletonList("project-a:open"),
                Collections.singletonList(repoA)));
        assertTrue(stored.contains(repoA));
        // Even with its project already open, a new selection reveals the folded repo.
        assertTrue(folds.focusSeen("host-a/t3", Collections.singletonList("project-a:open"),
                Collections.singletonList(repoA)));
        assertFalse(stored.contains(repoA));
        stored.add(repoA);
        // The same terminal id on another computer is a different focus.
        assertTrue(folds.focusSeen("host-b/t2", Collections.singletonList("project-b:open"),
                Collections.singletonList(repoB)));
        assertTrue(stored.contains(repoA));
        assertFalse(stored.contains(repoB));
    }

    @Test public void homeAndRailUseTheSameRepoProjectionAndCounts() throws IOException {
        for (String activity : Arrays.asList("HomeActivity", "GpuiTerminalActivity")) {
            String source = new String(Files.readAllBytes(Paths.get("src/main/java/dev/herdr/android/" + activity + ".java")),
                    StandardCharsets.UTF_8);
            assertTrue(activity, source.contains("FleetTree.RepoRow repo : tree.repos"));
            assertTrue(activity, source.contains("FleetTree.PmRow pm : repo.projects"));
            assertTrue(activity, source.contains("repo.label("));
            assertTrue(activity, source.contains("FleetTree.Fold.REPO"));
        }
    }
}
