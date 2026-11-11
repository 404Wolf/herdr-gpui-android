package dev.herdr.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** Saved panes: stable private names, and the newest kept within the bounds. */
public final class PaneSnapshotsTest {
    @Rule public final TemporaryFolder dir = new TemporaryFolder();

    @Test public void namesAreFixedLengthAndDistinguishComputerFromPane() {
        String a = PaneSnapshots.name("host-1", "t3");
        assertEquals(a, PaneSnapshots.name("host-1", "t3"));
        assertNotEquals(a, PaneSnapshots.name("host-2", "t3"));
        // "a" + "b/c" and "a/b" + "c" must not collide.
        assertNotEquals(PaneSnapshots.name("a", "b/c"), PaneSnapshots.name("a/b", "c"));
        assertTrue(PaneSnapshots.name("h", "server/t7").matches("[0-9a-f]{32}\\.snap"));
    }

    @Test public void theOldestGoFirstBeyondTheCountOrTheTotalSize() throws Exception {
        List<File> files = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            File file = dir.newFile("p" + i + ".snap");
            Files.write(file.toPath(), new byte[100]);
            assertTrue(file.setLastModified(1_000_000L + i * 1000));
            files.add(file);
        }
        assertEquals(Arrays.asList(files.get(1), files.get(0)), PaneSnapshots.stale(files, 3, 10_000));
        assertEquals(Arrays.asList(files.get(2), files.get(1), files.get(0)), PaneSnapshots.stale(files, 16, 250));
        assertEquals(0, PaneSnapshots.stale(files, 16, 10_000).size());
    }
}
