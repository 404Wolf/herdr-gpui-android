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
import java.util.Arrays;
import java.util.Collections;
import org.junit.Test;

/** Tap destinations plus source guards for Android listeners, without starting a window or JNI. */
public final class RailClicksTest {
    private static FleetTree.Member member(String id, String role, String parent, int order) {
        FleetTree.Member m = new FleetTree.Member();
        m.id = id;
        m.role = role;
        m.parent = parent;
        m.project = "app";
        m.space = "app";
        m.spaceName = "app";
        m.spaceIndex = 0;
        m.tabIndex = order;
        m.cwd = "/work/app";
        return m;
    }

    @Test public void aProjectTapOpensItsPmEvenWhenWorkersCameFirstInThePoll() {
        FleetTree.Member worker = member("t2", "worker", "t1", 0);
        FleetTree.Member pm = member("t1", "pm", "", 1);
        FleetTree.PmTree tree = FleetTree.pmTree(FleetTree.build(Arrays.asList(worker, pm), null));
        assertSame(pm, FleetTree.projectTarget(tree.pms.get(0)));
    }

    @Test public void aProjectWithoutAPmOpensItsFirstRootWorkerAndKeepsChildrenReachable() {
        FleetTree.Member root = member("t2", "worker", "t-gone", 1);
        FleetTree.Member child = member("t3", "worker", "t2", 0);
        FleetTree.PmRow row = FleetTree.pmTree(FleetTree.build(Arrays.asList(child, root), null)).pms.get(0);
        assertNull(row.pm);
        assertSame(root, FleetTree.projectTarget(row));
        assertSame(child, row.workers.get(0).children.get(0).member);
        assertEquals(2, FleetTree.flatten(row.workers).size());
    }

    @Test public void anEmptyProjectHasNoInventedTerminal() {
        assertNull(FleetTree.projectTarget(new FleetTree.PmRow(new FleetTree.Project("empty"))));
    }

    @Test public void aCatalogRowCountsTheFullListAndSaysWhenItIsIncomplete() {
        FleetTree.Project project = new FleetTree.Project("app");
        project.closed.add(new FleetTree.GitWorktree("/work/wt0", "", false, false));
        assertEquals("1 unopened worktree", FleetTree.unopenedLabel(project));
        for (int i = 1; i < 75; i++) {
            project.closed.add(new FleetTree.GitWorktree("/work/wt" + i, "fix" + i, false, false));
        }
        assertEquals("75 unopened worktrees", FleetTree.unopenedLabel(project));
        assertEquals(30, FleetTree.shownClosed(project, 0));
        assertEquals(60, FleetTree.shownClosed(project, 1));
        assertEquals(75, FleetTree.shownClosed(project, 2));
        project.closedIncomplete = true;
        assertEquals("75 unopened worktrees (incomplete)", FleetTree.unopenedLabel(project));
    }

    @Test public void worktreeTapsUseTheLatestTerminalsAndNeverANeighboringPath() {
        FleetTree.Member neighbor = member("t1", "", "", 0);
        neighbor.cwd = "/work/fix-other";
        FleetTree.Member shell = member("t2", "", "", 1);
        shell.cwd = "/work/fix/src";
        assertNull(FleetTree.memberAt(Collections.singletonList(neighbor), "/work/fix/"));
        assertSame(shell, FleetTree.memberAt(Arrays.asList(neighbor, shell), "/work/fix/"));
        // A terminal that vanished between drawing the catalog and tapping it is never reused.
        assertNull(FleetTree.memberAt(Collections.emptyList(), "/work/fix"));
    }

    @Test public void homeAndTheRailWireTheCatalogAndWorktreeToPrimaryTaps() throws IOException {
        String home = source("HomeActivity");
        String rail = source("GpuiTerminalActivity");
        assertTrue(home.contains("FleetTree.projectTarget(pm)"));
        assertTrue(rail.contains("FleetTree.projectTarget(pm)"));
        assertTrue(home.contains("addCatalogRows(row, machine, pm.catalogs)"));
        assertTrue(home.contains("addCatalogRows(others, machine, tree.otherCatalogs)"));
        assertTrue(rail.contains("railCatalogRows(host, live, current, pm.catalogs, order, keep)"));
        assertTrue(rail.contains("railCatalogRows(host, live, current, tree.otherCatalogs, order, keep)"));
        assertTrue(home.contains("() -> openWorktree(machine, wt.path)"));
        assertTrue(rail.contains("() -> openRailWorktree(host, current, wt)"));
        assertFalse(home.contains("closedWorktreeActions("));
        assertFalse(rail.contains("railClosedWorktree("));
        // Opening shells uses the endpoint; it never types a cd command into an existing agent.
        assertTrue(home.contains("put(\"op\", \"space.open\").put(\"folder\", path)"));
        assertTrue(rail.contains("put(\"op\", \"space.open\").put(\"folder\", wt.path)"));
    }

    @Test public void worktreeOpensAreDeduplicatedAndLateResultsCannotStealAnotherSession() throws IOException {
        String home = section(source("HomeActivity"), "private void openWorktree(", "private void openTerminal(");
        String rail = section(source("GpuiTerminalActivity"), "private void openRailWorktree(", "private void maybeRailGitLook(");
        assertTrue(home.indexOf("FleetTree.memberAt(") < home.indexOf("machine.opening.add(path)"));
        assertTrue(home.contains("machine.opening.remove(path)"));
        assertTrue(home.contains("if (machine.handle != handle) return;"));
        assertTrue(rail.indexOf("FleetTree.memberAt(") < rail.indexOf("railWorktreeOpening.add(key)"));
        assertTrue(rail.contains("railWorktreeOpening.remove(key)"));
        assertTrue(rail.contains("if (connection != current) return;"));
    }

    @Test public void fleetMachineRowsAndIndependentPressTargetsHaveFeedbackAndActions() throws IOException {
        String home = source("HomeActivity");
        String rail = source("GpuiTerminalActivity");
        String overview = section(home, "private void fleetOverview(", "private void openAgent(");
        assertTrue(overview.contains("row.setOnClickListener(v -> pickMachine(machine))"));
        String tree = section(home, "private LinearLayout treeLine(", "private TextView chevronSlot(");
        assertTrue(tree.contains("toggle.setBackground(ripple())"));
        String chip = section(home, "private TextView chip(", "private static boolean hasRole(");
        assertTrue(chip.contains("RippleDrawable("));
        for (String[] constructor : new String[][] {
                {"private RailWorkspaceRow createRailWorkspaceRow(", "private void updateRailWorkspaceRow("},
                {"private RailPaneRow createRailPaneRow(", "private void updateRailPaneRow("}}) {
            String row = section(rail, constructor[0], constructor[1]);
            assertTrue(constructor[0], row.contains("holder.mark.setBackground(railBackground(false))"));
            assertTrue(constructor[0], row.contains("holder.mark.setOnClickListener("));
        }
        String pill = section(rail, "private TextView tabPill(", "private void showTabActions(");
        assertTrue(pill.contains("RippleDrawable("));
    }

    private static String source(String name) throws IOException {
        return new String(Files.readAllBytes(Paths.get("src/main/java/dev/herdr/android/" + name + ".java")),
                StandardCharsets.UTF_8);
    }

    private static String section(String source, String start, String end) {
        int first = source.indexOf(start);
        assertTrue(start, first >= 0);
        int last = source.indexOf(end, first);
        assertTrue(end, last > first);
        return source.substring(first, last);
    }
}
