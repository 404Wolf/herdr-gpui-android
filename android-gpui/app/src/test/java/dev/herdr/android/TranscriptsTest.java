package dev.herdr.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
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
        // A file whose header names something else is a miss, whatever its name.
        File file = store.file(key);
        String body = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
        Files.write(file.toPath(), body.replace("\"t5\"", "\"t9\"").getBytes(StandardCharsets.UTF_8));
        assertNull(store.open(key));
        Files.write(file.toPath(), "not a header\nkept".getBytes(StandardCharsets.UTF_8));
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

    @Test public void theOldestGoFirstWhenTheCountOrTheBytesWouldPassTheirBound() {
        Transcripts store = store();
        for (int i = 0; i < Transcripts.MAX_FILES + 5; i++) {
            assertTrue(store.save(TranscriptKey.of(A, "t" + i, 1L, INC), "text " + i, false, 1_000 + i));
        }
        assertEquals(Transcripts.MAX_FILES, store.count());
        assertNull("the oldest went", store.open(TranscriptKey.of(A, "t0", 1L, INC)));
        assertNotNull(store.open(TranscriptKey.of(A, "t" + (Transcripts.MAX_FILES + 4), 1L, INC)));
        assertTrue(store.bytes() <= Transcripts.MAX_TOTAL_BYTES);
        // The newer of two copies of one terminal wins; an older save never replaces it.
        TranscriptKey key = TranscriptKey.of(A, "t50", 1L, INC);
        assertTrue(store.save(key, "newer", false, 9_000));
        assertFalse(store.save(key, "older", false, 8_000));
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
}
