package dev.herdr.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.TimeZone;
import org.junit.Test;

/** t2kg 11-23: a path that is a folder opens as its listing in the file view; Back returns to it. */
public final class FolderListingTest {
    private static final String REPLY = "{\"queued\":true,\"path\":\"/home/wolf/beads/drive-backups\",\"folder\":true,"
            + "\"entries\":[{\"name\":\".beads\",\"dir\":true,\"size\":12,\"mtime\":1795384357},"
            + "{\"name\":\".gitignore\",\"dir\":false,\"size\":106,\"mtime\":1795383736},"
            + "{\"name\":\"../escape\",\"dir\":false,\"size\":1,\"mtime\":1}],\"more\":false}";

    @Test public void aFolderReplyIsAListingWhoseRowsOpenTheirEntries() {
        FileDocument doc = FileDocument.parse("/home/wolf/beads/drive-backups", 0, REPLY);
        assertTrue(doc.folder());
        assertNull(doc.error);
        assertEquals("a name with a slash is never a row", 2, doc.entries.size());
        assertTrue(doc.lines.get(0).startsWith(".beads/"));
        assertTrue(doc.lines.get(1).startsWith(".gitignore "));
        assertTrue(doc.lines.get(1).contains("106 B"));
        assertEquals("/home/wolf/beads/drive-backups/.beads", doc.child(0));
        assertEquals("/home/wolf/beads/drive-backups/.gitignore", doc.child(1));
        assertNull(doc.child(2));
        assertFalse(FileDocument.parse("/a.md", 0, "{\"queued\":true,\"path\":\"/a.md\",\"text\":\"x\"}").folder());
    }

    @Test public void sizesAndTimesReadLikeAListing() {
        assertEquals("0 B", FileDocument.size(0));
        assertEquals("4.0 KB", FileDocument.size(4096));
        assertEquals("12 MB", FileDocument.size(12L * 1024 * 1024));
        TimeZone utc = TimeZone.getTimeZone("UTC");
        assertEquals("Nov 22 18:19", FileDocument.time(1795371540L, utc, 1795371600L));
        assertEquals("Nov 22 2026", FileDocument.time(1795371540L, utc, 1795371540L + 400L * 24 * 3600));
        FileDocument.Entry namesOnly = new FileDocument.Entry("sub", true, -1, -1);
        assertEquals("sub/", FileDocument.row(namesOnly, 10, utc, 0));
    }

    @Test public void backReturnsToTheFolderAndAFreshOpenForgetsTheWalk() throws Exception {
        String pager = new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(
                "src/main/java/dev/herdr/android/FilePager.java")), java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(pager.contains("trail.push(new Step(openTerminal, openThrough, doc, list.getFirstVisiblePosition()));"));
        assertTrue(pager.contains("Step step = trail.poll();\n        if (step == null) return hide();"));
        assertTrue(pager.contains("trail.clear();\n        read(terminal, path, line, through);"));
        String gta = new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(
                "src/main/java/dev/herdr/android/GpuiTerminalActivity.java")), java.nio.charset.StandardCharsets.UTF_8);
        assertTrue("system Back steps out of a folder's entry first", gta.contains("!filePager.back()) leaveByBack();"));
        String refs = new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(
                "src/main/java/dev/herdr/android/RefFiles.java")), java.nio.charset.StandardCharsets.UTF_8);
        assertTrue("a listing is never saved as an empty file", refs.contains("if (reply.optBoolean(\"folder\", false)) return false;"));
    }
}
