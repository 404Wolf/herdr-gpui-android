package dev.herdr.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import org.json.JSONException;
import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * PR-F of the offline redesign (factory-9zz.1): copies of the files agents reference, kept per
 * machine and resolved path for reading offline, honest about what they are, and bounded.
 */
public final class RefFilesTest {
    @Rule public final TemporaryFolder dir = new TemporaryFolder();

    private static final MachineKey A = MachineKey.of("core-a", "SHA256:host-a");
    private static final MachineKey B = MachineKey.of("core-b", "SHA256:host-b");
    private static final long DAY = 24L * 60 * 60 * 1000;
    private long free = Long.MAX_VALUE;

    private RefFiles store(RefFiles.Budget budget) {
        RefFiles store = new RefFiles(dir.getRoot(), budget, () -> free);
        store.start();
        return store;
    }

    private RefFiles store() {
        return store(RefFiles.Budget.PHONE);
    }

    private static String read(String path, String text, int lines, boolean last) throws JSONException {
        return new JSONObject().put("queued", true).put("path", path).put("text", text).put("lines", lines)
                .put("last", last).toString();
    }

    private static String failed(String error) throws JSONException {
        return new JSONObject().put("queued", false).put("error", error).toString();
    }

    private static JSONObject reply(RefFiles.Copy copy) throws JSONException {
        return new JSONObject(copy.reply());
    }

    @Test public void aReadIsKeptUnderWhereItResolvedAndOpensAsASavedCopy() throws JSONException {
        RefFiles store = store();
        assertTrue(store.save(A, "t5", "docs/PLAN.md", read("/w/p/docs/PLAN.md", "# Plan\nstep\n", 2, true), 1_000));
        RefFiles.Copy copy = store.open(A, "t5", "docs/PLAN.md", 2_000);
        assertNotNull(copy);
        JSONObject shown = reply(copy);
        assertEquals("/w/p/docs/PLAN.md", shown.getString("path"));
        assertEquals("# Plan\nstep\n", shown.getString("text"));
        assertEquals(1_000, shown.getLong("saved_as_of_ms"));
        // The absolute path opens it too, from any terminal of that machine: one copy.
        assertNotNull(store.open(A, "t9", "/w/p/docs/PLAN.md", 2_000));
        // Parsed by the file view as a saved copy, with its age and size said.
        FileDocument doc = FileDocument.parse("docs/PLAN.md", 0, copy.reply());
        assertEquals(1_000, doc.savedAtMs);
        assertEquals("saved copy · as of 14:02 · may be outdated · 2 lines", FileDocument.savedNote("14:02", doc));
    }

    @Test public void theSamePathOnTwoMachinesIsTwoCopiesAndTwoAgentsShareOne() throws JSONException {
        RefFiles store = store();
        assertTrue(store.save(A, "t5", "/etc/motd.txt", read("/etc/motd.txt", "A's", 1, true), 1_000));
        assertTrue(store.save(B, "t5", "/etc/motd.txt", read("/etc/motd.txt", "B's", 1, true), 1_000));
        assertEquals("A's", reply(store.open(A, "t5", "/etc/motd.txt", 2_000)).getString("text"));
        assertEquals("B's", reply(store.open(B, "t5", "/etc/motd.txt", 2_000)).getString("text"));
        assertTrue(store.save(A, "t6", "../motd.txt", read("/etc/motd.txt", "A's again", 1, true), 3_000));
        assertEquals("A's again", reply(store.open(A, "t5", "/etc/motd.txt", 4_000)).getString("text"));
        // A relative name is its terminal's: another terminal's same name is not resolved by it.
        assertNull(store.open(A, "t5", "../motd.txt", 4_000));
    }

    @Test public void aFileThatCouldNotBeReadSaysWhyAndALinkFailureKeepsNothing() throws JSONException {
        RefFiles store = store();
        assertTrue(store.save(A, "t5", "gone.md", failed("not_found: gone.md"), 1_000));
        FileDocument doc = FileDocument.parse("gone.md", 0, store.open(A, "t5", "gone.md", 2_000).reply());
        assertEquals("No such file", doc.error);
        assertEquals("saved copy · as of 14:02 · may be outdated · no such file", FileDocument.savedNote("14:02", doc));
        assertTrue(store.save(A, "t5", "/bin/sh", failed("not_text"), 1_000));
        assertEquals("Not a text file", FileDocument.parse("/bin/sh", 0, store.open(A, "t5", "/bin/sh", 2_000).reply()).error);
        // The session's own refusal says nothing of the file: no copy, and an older one stays.
        assertTrue(store.save(A, "t5", "/a.md", read("/a.md", "kept", 1, true), 1_000));
        assertFalse(store.save(A, "t5", "/a.md", failed("Not connected"), 2_000));
        assertEquals("kept", reply(store.open(A, "t5", "/a.md", 3_000)).getString("text"));
        // Never seen: nothing, so the view says it was not saved.
        assertNull(store.open(A, "t5", "/never.md", 3_000));
        assertEquals(FileDocument.NOT_SAVED, FileDocument.reason("not_saved"));
    }

    @Test public void aPartReadOrAnOversizeFileSaysHowMuchWasKept() throws JSONException {
        RefFiles store = store(new RefFiles.Budget(1 << 20, 100, 30 * DAY, 0, 16));
        // The host cut it (a prefetch reads the first lines only).
        assertTrue(store.save(A, "t5", "/big.md", read("/big.md", "1\n2\n", 9_000, false), 1_000));
        FileDocument doc = FileDocument.parse("/big.md", 0, store.open(A, "t5", "/big.md", 2_000).reply());
        assertEquals("saved copy · as of 14:02 · may be outdated · first 2 of 9000 lines", FileDocument.savedNote("14:02", doc));
        // Past the per-file cap: whole lines up to it.
        assertTrue(store.save(A, "t5", "/wide.md", read("/wide.md", "0123456789\nabcdefghij\n", 2, true), 1_000));
        JSONObject wide = reply(store.open(A, "t5", "/wide.md", 2_000));
        assertEquals("0123456789\n", wide.getString("text"));
        assertFalse(wide.getBoolean("last"));
    }

    @Test public void anOlderReadNeverReplacesANewerCopy() throws JSONException {
        RefFiles store = store();
        assertTrue(store.save(A, "t5", "/a.md", read("/a.md", "new", 1, true), 2_000));
        assertFalse(store.save(A, "t5", "/a.md", read("/a.md", "old", 1, true), 1_000));
        assertEquals("new", reply(store.open(A, "t5", "/a.md", 3_000)).getString("text"));
    }

    @Test public void leastRecentlyUsedGoesFirstAndIdleCopiesExpire() throws JSONException {
        // Room for two copies.
        RefFiles store = store(new RefFiles.Budget(1 << 20, 2, 30 * DAY, 0, 1 << 20));
        assertTrue(store.save(A, "t5", "/1.md", read("/1.md", "one", 1, true), 1_000));
        assertTrue(store.save(A, "t5", "/2.md", read("/2.md", "two", 1, true), 2_000));
        // /1 is opened (used) after /2 was read: /2 is now the least recently used.
        assertNotNull(store.open(A, "t5", "/1.md", 3_000));
        assertTrue(store.save(A, "t5", "/3.md", read("/3.md", "three", 1, true), 4_000));
        assertNotNull(store.open(A, "t5", "/1.md", 5_000));
        assertNull("the least recently used went", store.open(A, "t5", "/2.md", 5_000));
        assertNotNull(store.open(A, "t5", "/3.md", 5_000));
        // Referenced again counts as use, so a file an agent keeps naming stays.
        assertTrue(store.fresh(A, "t5", "/3.md", 0, 6_000));
        // Not used for 30 days: gone at the next write.
        long later = 6_000 + 31 * DAY;
        assertTrue(store.save(A, "t5", "/4.md", read("/4.md", "four", 1, true), later));
        assertNull(store.open(A, "t5", "/1.md", later));
        assertNull(store.open(A, "t5", "/3.md", later));
    }

    @Test public void theTotalBudgetHoldsAndTheFreeSpaceFloorStopsWritesButNotReads() throws JSONException {
        RefFiles store = store(new RefFiles.Budget(400, 100, 30 * DAY, 1_000, 1 << 20));
        String text = new String(new char[100]).replace('\0', 'x');
        for (int i = 0; i < 20; i++) {
            assertTrue(store.save(A, "t5", "/f" + i + ".md", read("/f" + i + ".md", text, 1, true), 1_000 + i));
            long total = 0;
            for (File machine : dir.getRoot().listFiles(File::isDirectory)) {
                for (File f : machine.listFiles((d, n) -> n.endsWith(".ref"))) total += f.length();
            }
            assertTrue("within the budget after write " + i + ": " + total, total <= 400);
        }
        assertNotNull(store.open(A, "t5", "/f19.md", 9_000));
        free = 999;
        assertFalse("under the floor nothing is written", store.save(A, "t5", "/new.md", read("/new.md", "n", 1, true), 9_000));
        assertNotNull("reads still work", store.open(A, "t5", "/f19.md", 9_000));
    }

    @Test public void theDefaultBudgetIsWolfsGenerousOne() {
        assertEquals(8 * 1024 * 1024, RefFiles.MAX_FILE_BYTES);
        assertEquals(1024L * 1024 * 1024, RefFiles.MAX_TOTAL_BYTES);
        assertEquals(20_000, RefFiles.MAX_FILES);
        assertEquals(30L * DAY, RefFiles.MAX_IDLE_MS);
        assertEquals(8L * 1024 * 1024 * 1024, RefFiles.FREE_FLOOR_BYTES);
    }
}
