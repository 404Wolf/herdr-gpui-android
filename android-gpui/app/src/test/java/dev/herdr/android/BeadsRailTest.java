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

    @Test public void itOnlyReads() throws IOException {
        String beads = source("BeadsRail");
        assertTrue(beads.contains("\"beads.stores\"") && beads.contains("\"beads.detail\""));
        for (String write : new String[] {"bd update", "bd close", "\"tab.", "pane.kill"}) {
            assertTrue(write, !beads.contains(write));
        }
    }
}
