package dev.herdr.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collections;
import org.junit.Test;

/** Optional refs-only counts remain distinct from unknown data and agent state. */
public final class FleetGitCountsTest {
    private static FleetTree.GitLook parse(String text) { return FleetTree.parseGitLook(text, 200); }

    private static FleetTree.Member member(int id, String role, String project, String cwd) {
        FleetTree.Member member = new FleetTree.Member();
        member.id = "t" + id;
        member.role = role;
        member.project = project;
        member.cwd = cwd;
        member.status = "idle";
        member.space = "s" + id;
        member.spaceName = project;
        member.spaceIndex = id;
        member.tabIndex = id;
        return member;
    }

    private static FleetTree.PmRow project(FleetTree.PmTree tree, String name) {
        for (FleetTree.PmRow row : tree.allProjects) if (row.name().equals(name)) return row;
        throw new AssertionError(name);
    }

    @Test public void oldRecordsRemainReadableWithUnknownCounts() {
        FleetTree.GitLook git = parse("P\t/repo\t/repo/.git\t/repo\tmain\nP\t/older\t/repo/.git\t/repo\n");
        assertEquals("main", git.places.get("/repo").branch);
        assertEquals("", git.places.get("/older").branch);
        assertNull(git.places.get("/repo").ahead);
        assertNull(git.places.get("/repo").behind);
        assertEquals("", FleetTree.changes(git, "/repo"));
        FleetTree.PmTree tree = FleetTree.pmTree(FleetTree.build(
                Collections.singletonList(member(1, "pm", "app", "/repo")), git));
        assertNull(tree.pms.get(0).ahead);
        assertFalse(tree.repos.get(0).label(true).contains("↑"));
    }

    @Test public void newRecordsDistinguishPositiveKnownZeroAndUnknownFields() {
        FleetTree.GitLook git = parse("P\t/a\t/repo/.git\t/a\tfix\t3\t2\n"
                + "P\t/zero\t/repo/.git\t/zero\tmain\t0\t0\n"
                + "P\t/unknown\t/repo/.git\t/unknown\t\t?\t?\n"
                + "P\t/partial\t/repo/.git\t/partial\tfix\t?\t2\n");
        assertEquals(Integer.valueOf(3), git.places.get("/a").ahead);
        assertEquals(Integer.valueOf(2), git.places.get("/a").behind);
        assertEquals("↑3 ↓2", FleetTree.changes(git, "/a/"));
        assertEquals(Integer.valueOf(0), git.places.get("/zero").ahead);
        assertEquals("", FleetTree.changes(git, "/zero"));
        assertNull(git.places.get("/unknown").ahead);
        assertNull(git.places.get("/unknown").behind);
        assertEquals("", FleetTree.changes(git, "/unknown"));
        assertEquals("↓2", FleetTree.changes(git, "/partial"));
    }

    @Test public void malformedOutOfRangeAndUnterminatedCountsStayUnknown() {
        for (String value : Arrays.asList("", "?", "-1", "+1", "1.0", " 1", "1x", "2147483648", "99999999999999999999")) {
            FleetTree.GitLook git = parse("P\t/repo\t/repo/.git\t/repo\tmain\t" + value + "\t" + value + "\n");
            assertNull(value, git.places.get("/repo").ahead);
            assertNull(value, git.places.get("/repo").behind);
        }
        FleetTree.GitLook cut = parse("P\t/repo\t/repo/.git\t/repo\tmain\t123\t4");
        assertNull(cut.places.get("/repo").ahead);
        assertNull(cut.places.get("/repo").behind);
        assertTrue(parse("P\t/not-git\n").outside.contains("/not-git"));
        assertFalse(parse("P\t/not-git").outside.contains("/not-git"));
    }

    @Test public void thePmCheckoutAndNestedAgentFoldersCountEachTopOnce() {
        FleetTree.GitLook git = parse("P\t/main\t/r/.git\t/main\tmain\t3\t0\n"
                + "P\t/a\t/r/.git\t/a\tfix\t2\t1\n"
                + "P\t/a/src\t/r/.git\t/a\tfix\t2\t1\n"
                + "P\t/b\t/r/.git\t/b\tfeature\t1\t0\n"
                + "P\t/main/src\t/r/.git\t/main\tmain\t3\t0\n");
        FleetTree.Member pm = member(1, "pm", "app", "/main");
        FleetTree.Member a = member(2, "worker", "app", "/a");
        FleetTree.Member sub = member(3, "", "app", "/a/src");
        sub.parent = a.id;
        FleetTree.Member b = member(4, "worker", "app", "/b");
        FleetTree.PmTree tree = FleetTree.pmTree(FleetTree.build(Arrays.asList(pm, a, sub, b), git));
        FleetTree.PmRow row = project(tree, "app");
        assertEquals(Long.valueOf(6), row.ahead);
        assertEquals("≡3 ↑6", row.countsLabel());
        assertEquals("1 project  ≡3 ↑6", tree.repos.get(0).label(true));
        assertFalse(tree.repos.get(0).label(false).contains("↑"));
        assertEquals("↑2 ↓1", FleetTree.changes(git, sub.cwd));
        assertEquals("↑3", FleetTree.changes(git, "/main/src"));
    }

    @Test public void anUnknownCheckoutCannotBorrowAnotherAgentsCountThroughItsSpace() {
        FleetTree.GitLook git = parse("P\t/main\t/r/.git\t/main\tmain\t3\t0\n");
        FleetTree.Member pm = member(1, "pm", "app", "/main");
        FleetTree.Member worker = member(2, "worker", "app", "/unknown");
        worker.space = pm.space;
        FleetTree.PmTree unknown = FleetTree.pmTree(FleetTree.build(Arrays.asList(pm, worker), git));
        assertNull(project(unknown, "app").ahead);
        assertFalse(unknown.repos.get(0).label(true).contains("↑"));
        // A completed outside-git answer is distinct from an unanswered folder.
        git.outside.add(worker.cwd);
        FleetTree.PmTree outside = FleetTree.pmTree(FleetTree.build(Arrays.asList(pm, worker), git));
        assertEquals(Long.valueOf(3), project(outside, "app").ahead);
    }

    @Test public void foldedRepoTotalsSumShownProjectsAndExcludeIdleProjects() {
        FleetTree.GitLook git = parse("P\t/main\t/r/.git\t/main\tmain\t3\t0\n"
                + "P\t/quiet\t/r/.git\t/quiet\tquiet\t100\t0\n");
        FleetTree.Member a = member(1, "pm", "alpha", "/main");
        FleetTree.Member b = member(2, "pm", "beta", "/main");
        FleetTree.Member idle = member(3, "worker", "quiet", "/quiet");
        idle.program = "zsh";
        FleetTree.PmTree tree = FleetTree.pmTree(FleetTree.build(Arrays.asList(a, b, idle), git));
        assertEquals("2 projects  ↑6", tree.repos.get(0).label(true));
        assertEquals(1, tree.repos.get(0).idle.size());
        FleetTree.Member missing = member(4, "pm", "missing", "/missing");
        git.places.put(missing.cwd, new FleetTree.Place("/r/.git", "/missing", "missing"));
        FleetTree.PmTree partial = FleetTree.pmTree(FleetTree.build(Arrays.asList(a, b, missing), git));
        assertFalse(partial.repos.get(0).label(true).contains("↑"));
    }

    @Test public void noUpstreamIsKnownZeroAndSumsDoNotOverflowAnInteger() {
        FleetTree.Member pm = member(1, "pm", "app", "/main");
        FleetTree.GitLook zero = parse("P\t/main\t/r/.git\t/main\tmain\t0\t0\n");
        FleetTree.PmRow row = FleetTree.pmTree(FleetTree.build(Collections.singletonList(pm), zero)).pms.get(0);
        assertEquals(Long.valueOf(0), row.ahead);
        assertEquals("", row.countsLabel());
        FleetTree.Member worker = member(2, "worker", "app", "/a");
        FleetTree.GitLook large = parse("P\t/main\t/r/.git\t/main\tmain\t2147483647\t0\n"
                + "P\t/a\t/r/.git\t/a\tfix\t1\t0\n");
        row = FleetTree.pmTree(FleetTree.build(Arrays.asList(pm, worker), large)).pms.get(0);
        assertEquals(Long.valueOf(2147483648L), row.ahead);
        assertTrue(row.countsLabel().contains("↑2147483648"));
    }

    @Test public void bothWorkerRenderersReadTheirOwnFolderNumbers() throws IOException {
        String home = source("HomeActivity");
        String rail = source("GpuiTerminalActivity");
        assertTrue(home.contains("FleetTree.changes(agent.machine.git, agent.cwd)"));
        assertTrue(rail.contains("FleetTree.changes(FleetPanes.LOOKS.get(host.id), m.cwd)"));
        assertTrue(rail.contains("String shownSub = sub +"));
    }

    private static String source(String name) throws IOException {
        return new String(Files.readAllBytes(Paths.get("src/main/java/dev/herdr/android/" + name + ".java")),
                StandardCharsets.UTF_8);
    }
}
