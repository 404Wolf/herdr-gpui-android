package dev.herdr.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * Stage 2 of the offline design: an agent's transcript, read with the core's bounded read while
 * online, kept per machine, terminal and incarnation, and shown offline only for the incarnation
 * the last direct list proved; bounded on disk, oldest first out.
 */
public final class TranscriptsTest {
    @Rule public final TemporaryFolder dir = new TemporaryFolder();

    private static final MachineKey A = MachineKey.of("core-a", "SHA256:host-a");
    private static final MachineKey B = MachineKey.of("core-b", "SHA256:host-b");
    private static final String INC = "0123456789abcdef";
    private static final String INC2 = "fedcba9876543210";

    private Transcripts store() {
        Transcripts store = new Transcripts(dir.getRoot());
        store.start();
        return store;
    }

    private static Map<String, Transcripts.Seen> seen(Object... addressCreatedIncarnation) {
        Map<String, Transcripts.Seen> out = new HashMap<>();
        for (int i = 0; i < addressCreatedIncarnation.length; i += 3) {
            out.put((String) addressCreatedIncarnation[i], new Transcripts.Seen(
                    (Long) addressCreatedIncarnation[i + 1], (String) addressCreatedIncarnation[i + 2]));
        }
        return out;
    }

    // ---- keys: machine + terminal + incarnation, all proven ----

    @Test public void aKeyNeedsTheMachineTheTerminalAndItsIncarnation() {
        assertNotNull(TranscriptKey.of(A, "t5", 7L, INC));
        assertNull("no machine", TranscriptKey.of(null, "t5", 7L, INC));
        assertNull("an old core: no incarnation", TranscriptKey.of(A, "t5", 7L, null));
        assertNull(TranscriptKey.of(A, "t5", 7L, ""));
        assertNull(TranscriptKey.of(A, "t5", 7L, "../evil"));
        assertNull("a relayed pane is live only", TranscriptKey.of(A, "macro/t7", 7L, INC));
        assertNull("no creation time", TranscriptKey.of(A, "t5", null, INC));
        assertNull(TranscriptKey.of(A, "t5", 0L, INC));
        assertEquals(TranscriptKey.of(A, "t5", 7L, INC), TranscriptKey.of(A, "t5", 7L, INC));
        assertFalse(TranscriptKey.of(A, "t5", 7L, INC).equals(TranscriptKey.of(A, "t5", 7L, INC2)));
        assertFalse(TranscriptKey.of(A, "t5", 7L, INC).equals(TranscriptKey.of(B, "t5", 7L, INC)));
    }

    // ---- shown offline only where identity is proven ----

    @Test public void aTranscriptIsShownOnlyForTheIncarnationTheLastDirectListProved() {
        Transcripts store = store();
        TranscriptKey key = TranscriptKey.of(A, "t5", 7L, INC);
        assertTrue(store.save(key, "line 1\nline 2", false, 1_000));
        // Nothing proven yet: no list of A was seen.
        assertNull(store.proven(A, "t5"));
        store.observe(A, seen("t5", 7L, INC), 1_500);
        Transcripts.Transcript shown = store.proven(A, "t5");
        assertNotNull(shown);
        assertEquals("line 1\nline 2", shown.text);
        assertEquals(1_000, shown.asOfMs);
        // The program was replaced (a kept shell, a restarted agent): the old transcript is not it.
        store.observe(A, seen("t5", 7L, INC2), 2_000);
        assertNull(store.proven(A, "t5"));
        // Same address on another machine: never A's.
        store.observe(B, seen("t5", 7L, INC), 2_000);
        assertNull(store.proven(B, "t5"));
        // The terminal left the list: nothing is shown for it.
        assertTrue(store.save(TranscriptKey.of(A, "t6", 9L, INC), "x", false, 2_100));
        store.observe(A, seen("t6", 9L, INC), 2_200);
        assertNotNull(store.proven(A, "t6"));
        store.observe(A, Collections.emptyMap(), 2_300);
        assertNull(store.proven(A, "t6"));
    }

    @Test public void anOldCoresListWithoutIncarnationsProvesNothing() {
        Transcripts store = store();
        assertTrue(store.save(TranscriptKey.of(A, "t5", 7L, INC), "x", false, 1));
        store.observe(A, seen("t5", 7L, null), 2);
        assertNull(store.proven(A, "t5"));
    }

    @Test public void theFileMustNameExactlyTheKeyAskedFor() throws Exception {
        Transcripts store = store();
        TranscriptKey key = TranscriptKey.of(A, "t5", 7L, INC);
        assertTrue(store.save(key, "kept", true, 5));
        Transcripts.Transcript open = store.open(key);
        assertEquals("kept", open.text);
        assertTrue(open.truncated);
        // A header naming something else is a miss, whatever its name.
        File head = store.head(key);
        String header = new String(Files.readAllBytes(head.toPath()), StandardCharsets.UTF_8);
        Files.write(head.toPath(), header.replace("\"t5\"", "\"t9\"").getBytes(StandardCharsets.UTF_8));
        assertNull(store.open(key));
        Files.write(head.toPath(), "not a header".getBytes(StandardCharsets.UTF_8));
        assertNull(store.open(key));
        // And so is a file kept before depth accrued (one header line, then the text).
        File legacy = store.file(key);
        Files.write(legacy.toPath(), "not a header\nkept".getBytes(StandardCharsets.UTF_8));
        assertNull(store.open(key));
    }

    @Test public void aTranscriptSurvivesARestartAndItsProofToo() {
        Transcripts store = store();
        TranscriptKey key = TranscriptKey.of(A, "t5", 7L, INC);
        assertTrue(store.save(key, "before the restart", false, 10));
        store.observe(A, seen("t5", 7L, INC), 11);
        Transcripts again = store();
        assertEquals("before the restart", again.proven(A, "t5").text);
    }

    // ---- bounds and eviction ----

    @Test public void aTranscriptIsBoundedAndOneTooLargeIsNotKept() {
        Transcripts store = store();
        char[] big = new char[Transcripts.MAX_TEXT_BYTES + 1];
        Arrays.fill(big, 'x');
        assertFalse(store.save(TranscriptKey.of(A, "t1", 1L, INC), new String(big), true, 1));
        assertNull(store.open(TranscriptKey.of(A, "t1", 1L, INC)));
    }

    @Test public void theColdestGoFirstWhenTheCountWouldPassItsBound() {
        Transcripts store = new Transcripts(dir.getRoot(), OfflineBudget.of(1L << 40, 0), () -> Long.MAX_VALUE, 8);
        store.start();
        for (int i = 0; i < 13; i++) {
            assertTrue(store.save(TranscriptKey.of(A, "t" + i, 1L, INC), "text " + i, false, false, 1_000 + i));
        }
        assertEquals(8, store.count());
        assertNull("the one that grew longest ago went", store.open(TranscriptKey.of(A, "t0", 1L, INC)));
        assertNotNull(store.open(TranscriptKey.of(A, "t12", 1L, INC)));
        // The newer of two reads of one terminal wins; an older read never replaces it.
        TranscriptKey key = TranscriptKey.of(A, "t50", 1L, INC);
        assertTrue(store.save(key, "newer", false, true, 9_000));
        assertFalse(store.save(key, "older", false, true, 8_000));
        assertEquals("newer", store.open(key).text);
    }

    @Test public void halfWrittenFilesFromAKillAreSweptAtStart() throws Exception {
        Transcripts store = store();
        File machineDir = store.file(TranscriptKey.of(A, "t1", 1L, INC)).getParentFile();
        assertTrue(machineDir.isDirectory() || machineDir.mkdirs());
        File tmp = new File(machineDir, "t1-1-" + INC + ".txt.tmp");
        Files.write(tmp.toPath(), "half".getBytes(StandardCharsets.UTF_8));
        store();
        assertFalse(tmp.exists());
    }

    // ---- freshness ----

    @Test public void itSaysWhenItWasReadAndThatItMayBeOutdated() {
        assertEquals("transcript as of 14:02 · may be outdated", Transcripts.label("14:02"));
    }

    @Test public void offlineRedesign_aScreenOnlyReadIsKeptAsScreenAndSaysSo() {
        // factory-9zz.1 gap 2: Claude Code and Codex are full-screen, and a read without
        // stitching gives their screen only. It is kept as that, never offered as history.
        Transcripts store = store();
        TranscriptKey key = TranscriptKey.of(A, "t5", 7L, INC);
        assertTrue(store.save(key, "the screen", false, true, 1_000));
        store.observe(A, seen("t5", 7L, INC), 1_500);
        Transcripts.Transcript screen = store.proven(A, "t5");
        assertTrue(screen.screen);
        assertEquals("t5 · transcript as of 14:02 · may be outdated · screen only",
                Transcripts.title("t5", "14:02", screen));
        // A shell's history is not.
        TranscriptKey shell = TranscriptKey.of(A, "t6", 8L, INC);
        assertTrue(store.save(shell, "a\nb", true, false, 1_000));
        store.observe(A, seen("t6", 8L, INC), 1_600);
        Transcripts.Transcript history = store.proven(A, "t6");
        assertFalse(history.screen);
        assertEquals("t6 · transcript as of 14:02 · may be outdated · older lines not kept",
                Transcripts.title("t6", "14:02", history));
    }

    @Test public void offlineRedesign_aFileFromBeforeTheScreenFlagCountsAsTheScreen() throws IOException {
        Transcripts store = store();
        TranscriptKey key = TranscriptKey.of(A, "t5", 7L, INC);
        // A stage-2 file: one header line with no "screen", then the text.
        String header = "{\"v\":1,\"core\":\"core-a\",\"hostkey\":\"SHA256:host-a\",\"address\":\"t5\","
                + "\"created_ms\":7,\"incarnation\":\"" + INC + "\",\"as_of_ms\":1000,\"truncated\":false}";
        File legacy = store.file(key);
        assertTrue(legacy.getParentFile().isDirectory() || legacy.getParentFile().mkdirs());
        Files.write(legacy.toPath(), (header + "\nold").getBytes(StandardCharsets.UTF_8));
        store.observe(A, seen("t5", 7L, INC), 1_500);
        Transcripts.Transcript old = store.proven(A, "t5");
        assertEquals("old", old.text);
        assertTrue("unknown is not claimed as history", old.screen);
        // The next read replaces it with a kept transcript and the old file goes.
        assertTrue(store.save(key, "new", false, false, 2_000));
        assertFalse(legacy.exists());
        assertEquals("new", store.proven(A, "t5").text);
    }

    // ---- what to read, when ----

    private static List<TranscriptPlanner.Pane> panes(TranscriptPlanner.Pane... panes) {
        return Arrays.asList(panes);
    }

    private static TranscriptPlanner.Pane agent(String address) {
        return new TranscriptPlanner.Pane(address, 7L, INC, true);
    }

    @Test public void nothingIsReadFromACoreWithoutTheBoundedReadOrForAnUnprovenMachine() {
        TranscriptPlanner planner = new TranscriptPlanner();
        assertNull(planner.next(false, true, false, "t1", 1, panes(agent("t1")), 100_000));
        assertNull(planner.next(true, false, false, "t1", 1, panes(agent("t1")), 100_000));
        assertEquals("t1", planner.next(true, true, false, "t1", 1, panes(agent("t1")), 100_000));
    }

    @Test public void onlyAgentsWithAProvenIncarnationOnThisMachineAreRead() {
        TranscriptPlanner planner = new TranscriptPlanner();
        List<TranscriptPlanner.Pane> list = panes(
                new TranscriptPlanner.Pane("t1", 7L, INC, false),
                new TranscriptPlanner.Pane("t2", 7L, null, true),
                new TranscriptPlanner.Pane("macro/t3", 7L, INC, true),
                new TranscriptPlanner.Pane("t4", null, INC, true));
        for (TranscriptPlanner.Pane p : list) assertNull(planner.next(true, true, false, p.address, 1, list, 100_000));
    }

    @Test public void thePaneOnScreenIsReadWhileItPrintsAtMostOnceAMinuteOneReadAtATime() {
        TranscriptPlanner planner = new TranscriptPlanner();
        List<TranscriptPlanner.Pane> list = panes(agent("t1"));
        assertEquals("t1", planner.next(true, true, true, "t1", 5, list, 100_000));
        assertNull("one read at a time", planner.next(true, true, true, "t1", 6, list, 100_001));
        planner.finished("t1", 100_100);
        assertNull("not again within the minute", planner.next(true, true, true, "t1", 7, list, 150_000));
        assertEquals("t1", planner.next(true, true, true, "t1", 8, list, 100_100 + TranscriptPlanner.SCREEN_EVERY_MS));
        planner.finished("t1", 200_000);
        assertNull("nothing printed since: nothing to read",
                planner.next(true, true, true, "t1", 8, list, 200_000 + TranscriptPlanner.SCREEN_EVERY_MS));
    }

    @Test public void otherAgentsAreReadOnUnmeteredNetworksSpacedAndRarely() {
        TranscriptPlanner planner = new TranscriptPlanner();
        List<TranscriptPlanner.Pane> list = panes(agent("t1"), agent("t2"), agent("t3"));
        assertNull("metered: only the pane on screen", planner.next(true, true, true, "t9", 0, list, 100_000));
        assertEquals("t1", planner.next(true, true, false, "t9", 0, list, 100_000));
        planner.finished("t1", 100_050);
        assertNull("2 s apart", planner.next(true, true, false, "t9", 0, list, 101_000));
        assertEquals("t2", planner.next(true, true, false, "t9", 0, list, 100_050 + TranscriptPlanner.SPACING_MS));
        planner.finished("t2", 102_100);
        assertEquals("t3", planner.next(true, true, false, "t9", 0, list, 102_100 + TranscriptPlanner.SPACING_MS));
        planner.finished("t3", 104_200);
        assertNull("each at most once in five minutes", planner.next(true, true, false, "t9", 0, list, 110_000));
        assertEquals("t1", planner.next(true, true, false, "t9", 0, list, 100_050 + TranscriptPlanner.OTHERS_EVERY_MS));
    }

    @Test public void aReplyIsKeptOnlyForTheSameLinkAndTheIncarnationListedNow() {
        assertTrue(TranscriptPlanner.keep(INC, INC, 3, 3));
        assertFalse("the link changed since the read began", TranscriptPlanner.keep(INC, INC, 3, 4));
        assertFalse("the program was replaced", TranscriptPlanner.keep(INC, INC2, 3, 3));
        assertFalse("the core could not say", TranscriptPlanner.keep(null, INC, 3, 3));
        assertFalse(TranscriptPlanner.keep(INC, null, 3, 3));
    }

    // ---- review of #55: only a connection's own list proves anything ----

    @Test public void onlyAReadyConnectionsOwnListAfterItsHelloIsPublishedOncePerRevision() {
        assertTrue(TranscriptKeeper.mayPublish(true, true, true, true, -1, 4));
        assertFalse("published already", TranscriptKeeper.mayPublish(true, true, true, true, 4, 4));
        assertTrue("a new revision", TranscriptKeeper.mayPublish(true, true, true, true, 4, 5));
        assertFalse("a resumed connection's inherited list", TranscriptKeeper.mayPublish(true, true, false, true, -1, 4));
        assertFalse("before its hello's features", TranscriptKeeper.mayPublish(true, true, true, false, -1, 4));
        assertFalse("its machine unbound", TranscriptKeeper.mayPublish(true, false, true, true, -1, 4));
        assertFalse("not ready", TranscriptKeeper.mayPublish(false, true, true, true, -1, 4));
    }

    /** Why the inherited list must never be published: it would delete the only valid copy. */
    @Test public void anOlderListDeletesTheCurrentCopySoItMustNotBePublished() {
        Transcripts store = store();
        assertTrue(store.save(TranscriptKey.of(A, "t5", 7L, INC2), "new", false, 10));
        store.observe(A, seen("t5", 7L, INC2), 11);
        assertNotNull(store.proven(A, "t5"));
        store.observe(A, seen("t5", 7L, INC), 12);
        assertNull(store.proven(A, "t5"));
        assertEquals(0, store.count());
    }

    // ---- depth accrues, tapers with age, within the budget (offline redesign §7.2) ----

    private static String rows(int from, int to) {
        StringBuilder out = new StringBuilder();
        for (int i = from; i < to; i++) out.append("row ").append(i).append('\n');
        return out.toString();
    }

    @Test public void offlineRedesign_historyReadsJoinOnTheirOverlapAndGrowPastOneRead() {
        Transcripts store = store();
        TranscriptKey key = TranscriptKey.of(A, "t5", 7L, INC);
        assertTrue(store.save(key, rows(0, 100) + "$ ", false, false, 1_000));
        // The next read overlaps the last 60 rows and has moved on; the prompt line was being typed.
        assertTrue(store.save(key, rows(40, 160) + "$ ls", false, false, 2_000));
        String text = store.open(key).text;
        assertTrue(text.startsWith("row 0\nrow 1\n"));
        assertTrue(text.endsWith("row 159\n$ ls"));
        assertEquals("each row once", 1, text.split("row 99\n", -1).length - 1);
        assertFalse("the old prompt line was replaced", text.contains("$ \nrow 100"));
        // A read with nothing new changes nothing.
        long before = store.body(key).length();
        assertTrue(store.save(key, rows(60, 160) + "$ ls", false, false, 3_000));
        assertEquals(before, store.body(key).length());
    }

    @Test public void offlineRedesign_readsThatDoNotOverlapAreKeptApartByAGapRow() {
        Transcripts store = store();
        TranscriptKey key = TranscriptKey.of(A, "t5", 7L, INC);
        assertTrue(store.save(key, rows(0, 100), false, false, 1_000));
        assertTrue(store.save(key, rows(500, 600), false, false, 2_000));
        String text = store.open(key).text;
        assertTrue(text.contains("row 99\n" + Transcripts.GAP + "\nrow 500"));
    }

    @Test public void offlineRedesign_aScreenOnlyReadReplacesWhatWasKept() {
        Transcripts store = store();
        TranscriptKey key = TranscriptKey.of(A, "t5", 7L, INC);
        assertTrue(store.save(key, rows(0, 10), false, true, 1_000));
        assertTrue(store.save(key, rows(3, 13), false, true, 2_000));
        assertEquals(rows(3, 13).trim(), store.open(key).text);
        assertTrue(store.open(key).screen);
    }

    @Test public void offlineRedesign_openingReadsOnlyTheNewestWindow() {
        Transcripts store = store();
        TranscriptKey key = TranscriptKey.of(A, "t5", 7L, INC);
        // Grow one transcript past the window by joined reads of 2,000 rows.
        for (int start = 0; start < 10_000; start += 1_500) {
            assertTrue(store.save(key, rows(start, start + 2_000), false, false, 1_000 + start));
        }
        Transcripts.Transcript t = store.open(key);
        String[] lines = t.text.split("\n");
        assertEquals(Transcripts.WINDOW_LINES, lines.length);
        assertEquals("row 10999", lines[lines.length - 1]);
        assertTrue("older lines are on disk, not shown", t.truncated);
        assertTrue(store.body(key).length() > t.text.length());
    }

    @Test public void offlineRedesign_depthTapersWithAgeAndVeryOldTranscriptsGo() {
        long day = OfflineBudget.DAY_MS;
        Transcripts store = store();
        TranscriptKey old = TranscriptKey.of(A, "t1", 1L, INC);
        TranscriptKey stale = TranscriptKey.of(A, "t2", 1L, INC);
        // 3 MiB of history each, grown at day 0.
        StringBuilder big = new StringBuilder();
        for (int start = 0; big.length() < 3 * 1024 * 1024; start += 2_000) big.append(rows(start, start + 2_000));
        String text = big.toString();
        for (TranscriptKey key : new TranscriptKey[] {old, stale}) {
            for (int at = 0; at < text.length(); ) {
                int end = Math.min(text.length(), text.lastIndexOf('\n', Math.min(text.length() - 1, at + 200_000)) + 1);
                assertTrue(store.save(key, text.substring(at, end), false, false, 1 + at));
                at = end;
            }
        }
        assertTrue(store.body(old).length() > 2 * 1024 * 1024);
        // Twelve days later (cool: 2 MiB) a write elsewhere tapers both to their newest 2 MiB.
        TranscriptKey hot = TranscriptKey.of(A, "t3", 1L, INC);
        assertTrue(store.save(hot, rows(0, 10), false, false, 12 * day));
        assertTrue(store.body(old).length() <= 2 * 1024 * 1024);
        assertTrue(store.open(old).text.endsWith(text.substring(text.lastIndexOf("row ", text.length() - 2)).trim()));
        assertTrue(store.open(old).truncated);
        // Past 90 days with no growth: gone. The hot one, read since, stays.
        assertTrue(store.save(hot, rows(5, 20), false, false, 100 * day));
        assertNull(store.open(old));
        assertNull(store.open(stale));
        assertNotNull(store.open(hot));
    }

    @Test public void offlineRedesign_theStoreKeepsItsShareOfTheBudgetColdestFirst() {
        // A budget whose transcript share is 5/8 of 64 KiB = 40 KiB.
        Transcripts store = new Transcripts(dir.getRoot(), OfflineBudget.of(64 * 1024, 0), () -> Long.MAX_VALUE);
        store.start();
        for (int i = 0; i < 20; i++) {
            assertTrue(store.save(TranscriptKey.of(A, "t" + i, 1L, INC), rows(0, 400), false, false, 1_000 + i));
            assertTrue("within the share after write " + i + ": " + store.bytes(), store.bytes() <= 40 * 1024);
        }
        assertNotNull("the newest stays", store.open(TranscriptKey.of(A, "t19", 1L, INC)));
        assertNull("the coldest went", store.open(TranscriptKey.of(A, "t0", 1L, INC)));
    }

    @Test public void offlineRedesign_underTheFreeSpaceFloorNothingIsWrittenButReadsWork() {
        long[] free = {Long.MAX_VALUE};
        Transcripts store = new Transcripts(dir.getRoot(), OfflineBudget.of(1L << 30, 1_000), () -> free[0]);
        store.start();
        TranscriptKey key = TranscriptKey.of(A, "t5", 7L, INC);
        assertTrue(store.save(key, "kept", false, false, 1_000));
        free[0] = 999;
        assertFalse(store.save(key, "kept\nmore", false, false, 2_000));
        assertEquals("kept", store.open(key).text);
    }

    @Test public void offlineRedesign_theBudgetIsGenerousButASmallPhoneGetsLess() {
        OfflineBudget phone = OfflineBudget.forFree(432L * 1000 * 1000 * 1000);
        assertEquals(4 * OfflineBudget.GIB, phone.totalBytes);
        assertEquals(2560 * OfflineBudget.MIB, phone.transcriptBytes);
        assertEquals(512 * OfflineBudget.MIB, phone.paneBytes);
        // The sync engine's 64 MiB comes out of the referenced files' 1 GiB (factory-9zz.14); the total is unchanged.
        assertEquals(960 * OfflineBudget.MIB, phone.refBytes);
        assertEquals(64 * OfflineBudget.MIB, phone.syncBytes);
        assertEquals(phone.totalBytes, phone.transcriptBytes + phone.paneBytes + phone.refBytes + phone.syncBytes);
        assertEquals(8 * OfflineBudget.GIB, phone.freeFloorBytes);
        // 20 GB free: 2 % of it.
        assertEquals((long) (20e9 * 0.02), OfflineBudget.forFree(20_000_000_000L).totalBytes);
        // The taper.
        assertEquals(32 * OfflineBudget.MIB, OfflineBudget.depth(0));
        assertEquals(8 * OfflineBudget.MIB, OfflineBudget.depth(2 * OfflineBudget.DAY_MS));
        assertEquals(2 * OfflineBudget.MIB, OfflineBudget.depth(8 * OfflineBudget.DAY_MS));
        assertEquals(512 * 1024, OfflineBudget.depth(31 * OfflineBudget.DAY_MS));
        assertEquals(0, OfflineBudget.depth(90 * OfflineBudget.DAY_MS));
        assertEquals(OfflineBudget.TOTAL_BYTES / 8, PaneSnapshots.MAX_TOTAL_BYTES);
    }
}
