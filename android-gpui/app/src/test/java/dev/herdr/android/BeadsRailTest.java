package dev.herdr.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import org.junit.Test;

/** The rail's beads drill-in is wired as the design says (factory-setup.21), without a window. */
public final class BeadsRailTest {
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

    @Test public void backPopsTheBeadsLevelBeforeTheRailCloses() throws IOException {
        String rail = source("GpuiTerminalActivity");
        String back = section(rail, "public void onBackPressed()", "super.onBackPressed();");
        assertTrue(back.indexOf("beadsRail.back()") < back.indexOf("hideSidebar()"));
        String edge = section(rail, "private void setEdgeBack(", "EdgeBack.unregister(");
        // The second callback: Back from anywhere but the left edge.
        String otherwise = edge.substring(edge.indexOf("}, () -> {"));
        assertTrue(otherwise.indexOf("beadsRail.back()") < otherwise.indexOf("leaveByBack()"));
    }

    @Test public void aMachineSwitchDropsItAndEveryReadAsksTheSameMachine() throws IOException {
        String rail = source("GpuiTerminalActivity");
        String switching = section(rail, "private void switchHost(SavedRemoteHost host, String pane, boolean picked)", "if (host.expectedFingerprint");
        assertTrue(switching.contains("beadsRail.close()"));
        String look = section(rail, "private java.util.function.Function<String, String> beadsLook(", "private void openBeadsAgent(");
        assertTrue(look.contains("!hostId.equals(now.hostId)"));
        // Agents join their beads only with the factory's labels.
        assertTrue(rail.contains("railMembers = BeadsModel.terms(workspaces, tabs, panes)"));
    }

    @Test public void everyBeadsReadIsTimedAndTheRowPollsSlowerThanTheDrillIn() throws IOException {
        String beads = source("BeadsRail");
        assertTrue(beads.contains("OpTiming.timed(\"beads.stores\""));
        assertTrue(beads.contains("OpTiming.timed(\"beads.detail\""));
        assertTrue(BeadsRail.ROW_POLL_MS >= 4 * BeadsRail.POLL_MS);
    }

    @Test public void aFoldedPmCarriesItsBeadsNeedsYou() throws IOException {
        assertTrue(BeadsRail.badge(0).isEmpty());
        assertTrue(BeadsRail.badge(2).equals(" ☰!2"));
        String rail = source("GpuiTerminalActivity");
        String pm = section(rail, "private void railPm(", "private void selectRailHeader(");
        assertTrue(pm.contains("folded && live ? BeadsRail.needsYou(host.id, pm.name(), railMembers)"));
        assertTrue(pm.contains("BeadsRail.badge(beadsNeed)"));
    }

    @Test public void aPmWithoutWorkersIsNeverFoldedSoItsBeadsShow() throws IOException {
        String pm = section(source("GpuiTerminalActivity"), "private void railPm(", "private void selectRailHeader(");
        assertTrue(pm.contains("boolean folded = pm.workerRows > 0 && railFolds().folded(key);"));
        // The open rail reads beads for a folded PM too (its ☰!k), before the fold returns.
        assertTrue(pm.indexOf("beadsRail.want(host.id, beadsLook(host.id))") < pm.indexOf("if (folded) return;"));
    }

    @Test public void nothingIsReadWhileTheRailIsHiddenOrTheAppPaused() throws IOException {
        String beads = source("BeadsRail");
        String tick = section(beads, "private void tick()", "private void showDetail(");
        assertTrue(tick.contains("if (!isOpen() || paused) return;"));
        assertTrue(tick.contains("if (root.isShown())"));
        assertTrue(beads.contains("PauseWatch.on(activity"));
        String want = section(beads, "void want(String hostId", "/** Drills in");
        assertTrue(want.contains("if (paused ||"));
        // A look refused because the phone left that machine is neither news nor an error.
        assertTrue(want.contains("catch (HostSwitched gone)"));
    }

    @Test public void theDetailsShowAllOnlyWhenSomethingIsHiddenAndMarksReadAsWords() {
        assertEquals(0, BeadsRail.lineCount(""));
        assertEquals(3, BeadsRail.lineCount("a\nb\nc"));
        assertEquals("needs you", BeadsRail.markWords("needs-you"));
        assertEquals("working", BeadsRail.markWords("working"));
    }

    @Test public void aWorkIsBuiltOncePerReadAndMembersChange() {
        FleetTree.Member a = new FleetTree.Member();
        a.id = "t1";
        a.status = "idle";
        String before = BeadsRail.membersKey(java.util.Arrays.asList(a));
        a.status = "working";
        assertTrue(!before.equals(BeadsRail.membersKey(java.util.Arrays.asList(a))));
        BeadsModel.Term t = new BeadsModel.Term();
        t.id = "t2";
        t.issue = "x-1";
        assertTrue(BeadsRail.membersKey(java.util.Arrays.asList(t)).contains("x-1"));
        assertNull(BeadsRail.work("no-such-host", "p", java.util.Collections.emptyList()));
    }

    private static BeadsModel.Term term(String id, String role, String parent, String project, String issue) {
        BeadsModel.Term t = new BeadsModel.Term();
        t.id = id;
        t.role = role;
        t.parent = parent;
        t.project = project;
        t.issue = issue;
        t.kind = "claude";
        return t;
    }

    @Test public void aTappedAgentShowsItsOwnBeadAHelperItsWorkersAndNoneIsNeverGuessed() {
        java.util.List<BeadsModel.Term> m = java.util.Arrays.asList(
                term("t1", "pm", "", "factory", "factory-setup"),
                term("t2", "worker", "t1", "factory", "factory-setup.22"),
                term("t3", "", "t2", "", ""),                       // a helper of t2, no issue of its own
                term("t4", "", "t2", "", "factory-setup.40"),       // a helper with its own
                term("t5", "worker", "t1", "factory", ""),          // a legacy worker: no bead linked
                term("t6", "", "t5", "", ""));
        assertEquals("factory-setup.22", BeadsRail.beadOf("t2", m));
        assertEquals("factory-setup.22", BeadsRail.beadOf("t3", m));
        assertEquals("factory-setup.40", BeadsRail.beadOf("t4", m));
        // Not the PM's own bead: the chain stops at the lead.
        assertNull(BeadsRail.beadOf("t5", m));
        assertNull(BeadsRail.beadOf("t6", m));
        assertNull(BeadsRail.beadOf("nobody", m));
        assertEquals("factory", BeadsRail.projectOf("t3", m));
    }

    @Test public void theTapFocusesFirstThenShowsTheBeadFullyOpenAndSelectable() throws IOException {
        String rail = source("GpuiTerminalActivity");
        String tap = section(rail, "holder.row.setOnClickListener(view -> {\n            if (holder.live) {", "holder.row.setOnLongClickListener(view -> { showPaneActions(holder)");
        assertTrue(tap.indexOf("focusRemotePane(holder.connection, holder.paneId)") < tap.indexOf("beadsRail.tapped("));
        // The cover's drawer stays open on the bead (its terminal focused behind it); a drawer that closes
        // over it closes the bead, so reopened it shows the agents; Back from it is one step to the terminal.
        assertTrue(tap.contains("if (!bead) closeRailDrawer();"));
        String hide = section(rail, "private void hideSidebar() {", "setSidebarOpen(false);");
        assertTrue(hide.contains("beadsRail.showingAgentsBead()) beadsRail.close();"));
        String systemBack = section(rail, "public void onBackPressed()", "super.onBackPressed();");
        assertTrue(systemBack.indexOf("beadsRail.showingAgentsBead()") < systemBack.indexOf("beadsRail.back()"));
        String beads = source("BeadsRail");
        String tapped = section(beads, "void tapped(", "/** detailId for an agent");
        assertTrue(tapped.contains("!agent.isAgent()") && tapped.contains("\"pm\".equals(agent.role)"));
        String open = section(beads, "void openFor(", "void tapped(");
        assertTrue(open.contains("if (bead != null) showAll.add(bead);"));
        assertTrue(open.contains("if (bead != null) readDetail(bead);"));
        // Who works on it needs the stores: an agent's bead asks for them too, and says so while it waits.
        assertTrue(open.contains("want(hostId, look);"));
        String detail = section(beads, "private void renderDetail()", "/** Lines a text takes");
        assertTrue(detail.contains("No task linked."));
        assertTrue(detail.contains("Reading who works on it…"));
        // No comments anywhere (Wolf, via t2kg): the detail draws none.
        assertTrue(!detail.contains("COMMENTS") && !detail.contains("detail.comments"));
        assertTrue(detail.contains("if (fromAgent || (!all && !hidden)) return;"));
        assertTrue(detail.contains("title.setTextIsSelectable(true)"));
        assertTrue(beads.contains("view.setTextIsSelectable(true);"));
        // An unreadable store or a host switch mid-load: the error is said, a switch is dropped.
        String read = section(beads, "private void readDetail(", "/** Redraws when");
        assertTrue(read.contains("catch (HostSwitched gone)") && read.contains("Can't read ") == false);
        assertTrue(detail.contains("\"Can't read \" + detailId"));
        // Back from an agent's bead goes back to the agents.
        String back = section(beads, "boolean back()", "/** Leaves the beads view");
        assertTrue(back.contains("if (detailId != null && !fromAgent)"));
    }

    @Test public void itOnlyReads() throws IOException {
        String beads = source("BeadsRail");
        assertTrue(beads.contains("\"beads.stores\"") && beads.contains("\"beads.detail\""));
        for (String write : new String[] {"bd update", "bd close", "\"tab.", "pane.kill"}) {
            assertTrue(write, !beads.contains(write));
        }
    }
}
