package dev.herdr.android;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import javax.crypto.SecretKey;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** Saved panes (item 77, ask 2): sealed, stable private names, the newest kept within the budget. */
public final class PaneSnapshotsTest {
    @Rule public final TemporaryFolder dir = new TemporaryFolder();

    /** The native side as a test sees it: copies by pane, and what was opened. */
    private static final class FakeNative implements PaneSnapshots.Native {
        final java.util.Map<String, byte[]> copies = new java.util.HashMap<>();
        final List<byte[]> opened = new ArrayList<>();
        final List<byte[]> adopted = new ArrayList<>();
        @Override public byte[] copy(long handle, String pane) { return copies.get(pane); }
        @Override public long open(byte[] copy) { opened.add(copy); return 40 + opened.size(); }
        @Override public void adopt(byte[] copy) { adopted.add(copy); }
    }

    private static final byte[] TRANSCRIPT =
            "{\"v\":2}\n● the deploy key is AKIA-SECRET\r\n● and more of Claude's history".getBytes(StandardCharsets.UTF_8);

    private PaneSnapshots snapshots(File folder, SecretKey key, FakeNative natives) {
        CacheSeal seal = new CacheSeal(key);
        return new PaneSnapshots(folder, Runnable::run, () -> seal, natives);
    }

    @Test public void namesAreFixedLengthAndDistinguishComputerFromPane() {
        String a = PaneSnapshots.name("host-1", "t3");
        assertEquals(a, PaneSnapshots.name("host-1", "t3"));
        assertNotEquals(a, PaneSnapshots.name("host-2", "t3"));
        // "a" + "b/c" and "a/b" + "c" must not collide.
        assertNotEquals(PaneSnapshots.name("a", "b/c"), PaneSnapshots.name("a/b", "c"));
        assertTrue(PaneSnapshots.name("h", "server/t7").matches("[0-9a-f]{32}\\.snap2"));
    }

    @Test public void ask2TheCacheSurvivesARestartSealedAndIsNeverPlainTextOnDisk() throws Exception {
        File folder = dir.newFolder("panes");
        SecretKey key = CacheSeal.newKey();
        FakeNative natives = new FakeNative();
        natives.copies.put("t1", TRANSCRIPT);
        snapshots(folder, key, natives).save("server", "t1", 7);
        File saved = new File(folder, PaneSnapshots.name("server", "t1"));
        assertTrue(saved.isFile());
        String onDisk = new String(Files.readAllBytes(saved.toPath()), StandardCharsets.ISO_8859_1);
        assertFalse(onDisk.contains("AKIA-SECRET"));
        assertFalse(onDisk.contains("Claude"));
        // The app is killed and started again (same Keystore key): the copy reads back whole.
        FakeNative after = new FakeNative();
        List<Long> shown = new ArrayList<>();
        snapshots(folder, key, after).open("server", "t1", s -> shown.add(s.renderId), () -> shown.add(-1L));
        assertEquals(Collections.singletonList(41L), shown);
        assertArrayEquals(TRANSCRIPT, after.opened.get(0));
        // The pane switched to online takes the same transcript, drawing nothing.
        snapshots(folder, key, after).adopt("server", "t1");
        assertArrayEquals(TRANSCRIPT, after.adopted.get(0));
        // The Keystore key is gone (app data cleared): nothing opens, the file goes.
        shown.clear();
        snapshots(folder, CacheSeal.newKey(), after).open("server", "t1", s -> shown.add(s.renderId), () -> shown.add(-1L));
        assertEquals(Collections.singletonList(-1L), shown);
        assertFalse(saved.exists());
    }

    /** Review round 2 P1-1: a saved connection edited to another computer keeps its id, never its copies. */
    @Test public void ask2AnEditedConnectionNeverShowsTheOldComputersCopy() throws Exception {
        File folder = dir.newFolder("panes");
        SecretKey key = CacheSeal.newKey();
        FakeNative natives = new FakeNative();
        natives.copies.put("t1", TRANSCRIPT);
        PaneSnapshots snapshots = snapshots(folder, key, natives);
        java.util.Map<String, String> revisions = new java.util.HashMap<>();
        revisions.put("profile", "revision-a");
        snapshots.revisions(revisions);
        snapshots.save("profile", "t1", 7);
        // The profile now points at computer B: same id, a new connection revision.
        revisions.put("profile", "revision-b");
        snapshots.revisions(revisions);
        List<Long> shown = new ArrayList<>();
        snapshots.open("profile", "t1", s -> shown.add(s.renderId), () -> shown.add(-1L));
        assertEquals(Collections.singletonList(-1L), shown);
        // Back on A's revision (the edit undone), A's copy is A's again.
        revisions.put("profile", "revision-a");
        snapshots.revisions(revisions);
        shown.clear();
        snapshots.open("profile", "t1", s -> shown.add(s.renderId), () -> shown.add(-1L));
        assertEquals(1, shown.size());
        assertTrue(shown.get(0) > 0);
    }

    /** Review P2-6: opening a copy must not change the time it says it was saved. */
    @Test public void ask2AnOfflineCopyKeepsItsSaveTimeOverEveryOpen() throws Exception {
        File folder = dir.newFolder("panes");
        SecretKey key = CacheSeal.newKey();
        FakeNative natives = new FakeNative();
        natives.copies.put("t1", TRANSCRIPT);
        PaneSnapshots snapshots = snapshots(folder, key, natives);
        snapshots.save("server", "t1", 7);
        long t0 = System.currentTimeMillis() - 26L * 60 * 60 * 1000;
        assertTrue(snapshots.file("server", "t1").setLastModified(t0));
        List<Long> savedAt = new ArrayList<>();
        snapshots.open("server", "t1", s -> savedAt.add(s.savedAt));
        snapshots.open("server", "t1", s -> savedAt.add(s.savedAt));
        assertEquals(2, savedAt.size());
        assertEquals(t0 / 1000, savedAt.get(0) / 1000);
        assertEquals(t0 / 1000, savedAt.get(1) / 1000);
    }

    @Test public void ask2TheCapIsEnforcedOldestOutPinnedPanesLastNothingPastTwoWeeks() throws Exception {
        long now = 100L * 24 * 60 * 60 * 1000;
        List<File> files = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            File file = dir.newFile("p" + i + ".snap2");
            Files.write(file.toPath(), new byte[100]);
            // p0 oldest ... p5 newest, an hour apart.
            assertTrue(file.setLastModified(now - (6 - i) * 3_600_000L));
            files.add(file);
        }
        // Beyond the count: the oldest go.
        assertEquals(Arrays.asList(files.get(2), files.get(1), files.get(0)),
                PaneSnapshots.stale(files, Collections.emptySet(), 3, 10_000, now, PaneSnapshots.MAX_AGE_MS));
        // Past the bytes: the oldest go; a pinned pane (the Deacon, oldest) stays and counts first.
        HashSet<String> pinned = new HashSet<>(Collections.singletonList("p0.snap2"));
        assertEquals(Arrays.asList(files.get(2), files.get(1)),
                PaneSnapshots.stale(files, pinned, 48, 450, now, PaneSnapshots.MAX_AGE_MS));
        // Older than two weeks: gone unless pinned.
        assertTrue(files.get(3).setLastModified(now - PaneSnapshots.MAX_AGE_MS - 1));
        assertTrue(files.get(0).setLastModified(now - PaneSnapshots.MAX_AGE_MS - 1));
        assertEquals(Collections.singletonList(files.get(3)),
                PaneSnapshots.stale(files, pinned, 48, 10_000, now, PaneSnapshots.MAX_AGE_MS));
        // Wolf's budget.
        assertEquals(64L * 1024 * 1024, PaneSnapshots.MAX_TOTAL_BYTES);
        assertEquals(48, PaneSnapshots.MAX_FILES);
    }

    @Test public void ask2EverySaveStaysWithinTheBudget() throws Exception {
        File folder = dir.newFolder("panes");
        FakeNative natives = new FakeNative();
        PaneSnapshots snapshots = snapshots(folder, CacheSeal.newKey(), natives);
        for (int i = 0; i < PaneSnapshots.MAX_FILES + 5; i++) {
            natives.copies.put("t" + i, ("pane " + i).getBytes(StandardCharsets.UTF_8));
            snapshots.save("server", "t" + i, 1);
            new File(folder, PaneSnapshots.name("server", "t" + i)).setLastModified(System.currentTimeMillis() - (100 - i) * 1000L);
        }
        snapshots.save("server", "t0", 1);
        String[] left = folder.list((d, n) -> n.endsWith(PaneSnapshots.SUFFIX));
        assertEquals(PaneSnapshots.MAX_FILES, left.length);
    }

    @Test public void ask2PlainVersion1CopiesAreSealedOnceThenDeleted() throws Exception {
        File old = dir.newFolder("old-panes");
        File folder = new File(dir.getRoot(), "cache-v2/panes");
        String base = PaneSnapshots.name("server", "t4").replace(PaneSnapshots.SUFFIX, "");
        File plain = new File(old, base + ".snap");
        Files.write(plain.toPath(), TRANSCRIPT);
        SecretKey key = CacheSeal.newKey();
        FakeNative natives = new FakeNative();
        PaneSnapshots snapshots = snapshots(folder, key, natives);
        snapshots.migrate(old);
        assertFalse(plain.exists());
        assertFalse(old.exists());
        List<Long> shown = new ArrayList<>();
        snapshots.open("server", "t4", s -> shown.add(s.renderId), () -> shown.add(-1L));
        assertEquals(1, shown.size());
        assertArrayEquals(TRANSCRIPT, natives.opened.get(0));
    }

    @Test public void aPaneWithNoReadableCopyIsReportedMissingAndAnUnreadableOneGoes() throws Exception {
        List<String> heard = new ArrayList<>();
        File folder = dir.newFolder("panes");
        PaneSnapshots snapshots = snapshots(folder, CacheSeal.newKey(), new FakeNative());
        snapshots.open("h", "t1", shown -> heard.add("shown"), () -> heard.add("missing"));
        assertEquals(Collections.singletonList("missing"), heard);
        // A copy that cannot be opened (here: not sealed) is deleted, then missing.
        File bad = snapshots.file("h", "t2");
        Files.write(bad.toPath(), new byte[] {1, 2, 3});
        heard.clear();
        snapshots.open("h", "t2", shown -> heard.add("shown"), () -> heard.add("missing"));
        assertEquals(Collections.singletonList("missing"), heard);
        assertFalse(bad.exists());
        // No pane, nothing asked.
        heard.clear();
        snapshots.open("h", "", shown -> heard.add("shown"), () -> heard.add("missing"));
        assertTrue(heard.isEmpty());
    }
}
