package dev.herdr.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
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
import org.json.JSONArray;
import org.junit.Test;

/** The right panel's choices, without a window (synthetic agents and beads). */
public final class PanelModelTest {
    private static List<BeadsModel.Node> tree() {
        return BeadsModel.beadTree(BeadsModel.parseBeads("[{\"id\":\"demo-1\",\"title\":\"Epic\",\"status\":\"open\",\"issue_type\":\"epic\"},"
                + "{\"id\":\"demo-1.1\",\"title\":\"Done one\",\"status\":\"closed\",\"parent\":\"demo-1\"},"
                + "{\"id\":\"demo-1.2\",\"title\":\"Working one\",\"status\":\"in_progress\",\"parent\":\"demo-1\"},"
                + "{\"id\":\"demo-1.2.1\",\"title\":\"Sub task\",\"status\":\"open\",\"parent\":\"demo-1.2\"},"
                + "{\"id\":\"demo-9\",\"title\":\"Loose\",\"status\":\"open\"}]").beads);
    }

    @Test public void theAgentOnScreenIsKnownByItsRoleProjectNameAndBead() throws Exception {
        JSONArray panes = new JSONArray("[{\"id\":\"t2\",\"role\":\"pm\",\"project\":\"demo\",\"name\":\"demo-pm\",\"workspace_id\":\"s1\",\"kind\":\"claude\"},"
                + "{\"id\":\"t3\",\"role\":\"worker\",\"project\":\"demo\",\"name\":\"agent-a\",\"issue\":\"demo-1.2\",\"parent\":\"t2\",\"workspace_id\":\"s1\",\"kind\":\"claude\"}]");
        List<? extends FleetTree.Member> members = BeadsModel.terms(null, null, panes);
        PanelModel.Agent pm = PanelModel.agentOf(members, "t2");
        assertEquals("demo", pm.doc());
        assertTrue(pm.hasTasks());
        PanelModel.Agent worker = PanelModel.agentOf(members, "t3");
        assertEquals("demo/agent-a", worker.doc());
        assertEquals("demo-1.2", worker.bead);
        assertEquals("agent-a", worker.label);
        assertNull(PanelModel.agentOf(members, "t9"));
        PanelModel.Agent deacon = new PanelModel.Agent("t1", "deacon", "", "deacon", null, "Deacon");
        assertEquals("_deacon", deacon.doc());
        // The deacon's Tasks: its own machine's tasks (Wolf, 11-23).
        assertTrue(deacon.hasTasks());
        assertEquals(PanelModel.Tab.TASKS, PanelModel.tab(PanelModel.Tab.TASKS, deacon));
        assertEquals(PanelModel.Tab.TASKS, PanelModel.tab(PanelModel.Tab.TASKS, pm));
    }

    @Test public void tasksRowsFoldAndOpenAndSayAnEpicsProgress() {
        List<BeadsModel.Node> roots = tree();
        List<PanelModel.Row> rows = PanelModel.rows(roots, Collections.emptySet(), new HashSet<>(Collections.singleton("demo-1.2")));
        List<String> ids = new ArrayList<>();
        for (PanelModel.Row r : rows) ids.add(r.depth + ":" + r.node.bead.id + (r.open ? "+" : ""));
        assertTrue(ids.contains("1:demo-1.2+"));
        assertTrue(ids.contains("2:demo-1.2.1"));
        assertTrue(ids.contains("0:demo-9"));
        PanelModel.Row epic = rows.get(0);
        assertTrue(epic.epic);
        assertTrue(epic.progress, epic.progress.matches("1/3 done · 1 in progress|1/2 done · 1 in progress"));
        Set<String> folded = new HashSet<>(Collections.singleton("demo-1"));
        List<PanelModel.Row> shut = PanelModel.rows(roots, folded, Collections.emptySet());
        assertEquals(Arrays.asList("demo-1", "demo-9"), Arrays.asList(shut.get(0).node.bead.id, shut.get(1).node.bead.id));
        assertEquals(Arrays.asList("demo-1.2", "demo-1"), PanelModel.parentsOf(roots, "demo-1.2.1"));
    }

    @Test public void aBeadsPlanIsOneDocumentInTheFableOrder() {
        BeadsModel.Detail d = new BeadsModel.Detail();
        d.title = "Fix it";
        d.status = "closed";
        d.closeReason = "Shipped in #70.";
        d.sections.add(new String[] {"Description", "# Fix it\n\nWhy and what."});
        d.sections.add(new String[] {"Design", "One column."});
        d.sections.add(new String[] {"Acceptance", "## Done when\n\nIt works."});
        assertEquals("Why and what.\n\n### Design\n\nOne column.\n\n## Done when\n\nIt works.\n\n### Outcome\n\nShipped in #70.",
                PanelModel.plan(d));
        d.status = "open";
        assertFalse(PanelModel.plan(d).contains("Outcome"));
        BeadsModel.Detail empty = new BeadsModel.Detail();
        empty.title = "x";
        assertEquals("", PanelModel.plan(empty));
        assertEquals("No plan written yet · 2 beads below.", PanelModel.noPlan(tree().get(0)));
        assertEquals("wolf · 11-22 10:00", PanelModel.byLine(new BeadsModel.Comment("wolf", "t", "2026-11-22T10:00:00Z")));
    }

    @Test public void thePanelSplitsTheUnfoldedScreenAndCoversTheCover() {
        WorkPanelLayout cover = WorkPanelLayout.of(412, false, false);
        assertFalse(cover.shown);
        assertTrue(cover.toggle && cover.overlay);
        assertTrue(WorkPanelLayout.of(412, false, true).shown);
        WorkPanelLayout fold = WorkPanelLayout.of(840, false, false);
        assertTrue(fold.shown && !fold.overlay && !fold.toggle);
        assertEquals(0.5f, fold.share, 0f);
        assertTrue(WorkPanelLayout.splitsWithRail(840, false));
        assertFalse(WorkPanelLayout.splitsWithRail(1280, false));
        assertEquals(0.3f, WorkPanelLayout.of(1280, false, false).share, 0f);
        assertFalse(WorkPanelLayout.of(840, true, true).shown);
        assertFalse(WorkPanelLayout.splitsWithRail(840, true));
    }

    @Test public void theActivityPlacesThePanelAndRoutesBackTypingAndQuotes() throws IOException {
        String gta = TerminalSource.text();
        assertTrue(gta.contains("return railShown().drawer(railCompact());"));
        assertTrue(gta.contains("WorkPanelLayout.splitsWithRail(getResources().getConfiguration().screenWidthDp, shellMode(),"));
        assertTrue(gta.contains("if (workPanelBack()) return;"));
        assertTrue(gta.contains("@Override public boolean typing() { return imeShown || (composer != null && composer.busy() != null); }"));
        assertTrue(gta.contains("private void quoteToDraft(String header, String text)"));
        String panel = new String(Files.readAllBytes(Paths.get("src/main/java/dev/herdr/android/WorkPanel.java")), StandardCharsets.UTF_8);
        // The engine's reads first (it gates them itself), then a bead read alone, one at a time and
        // never while Wolf types; never a redraw under a selection.
        assertTrue(panel.indexOf("syncReads();") < panel.indexOf("if (inFlight || paused || host.typing()) return;"));
        assertTrue(panel.contains("if (!force && SelectableText.anySelected(body)) { pending = true; return; }"));
        assertTrue(panel.contains("new Thread(task, \"herdr-panel\")"));
    }
}
