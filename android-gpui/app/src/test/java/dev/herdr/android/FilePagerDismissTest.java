package dev.herdr.android;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Wolf 11-22: the file viewer comes down from the top and an upward swipe sends it back. */
public final class FilePagerDismissTest {
    private static final float SLOP = 16;

    @Test public void shownAnUpwardDragFromTheHeaderOrPastTheEndDismisses() {
        assertTrue("from the header", FilePager.Dismiss.claims(true, -80, 5, SLOP, true, false, false));
        assertTrue("past the end of the file", FilePager.Dismiss.claims(true, -80, 5, SLOP, false, true, false));
        assertFalse("inside the text it scrolls, never dismisses", FilePager.Dismiss.claims(true, -80, 5, SLOP, false, false, false));
        assertFalse("downward never dismisses", FilePager.Dismiss.claims(true, 80, 5, SLOP, true, true, false));
        assertFalse("a sideways drag (a selection handle) is not it", FilePager.Dismiss.claims(true, -40, 60, SLOP, true, true, false));
        assertFalse("within the slop", FilePager.Dismiss.claims(true, -10, 0, SLOP, true, true, false));
    }

    @Test public void hiddenOnlyADownwardPullOnTheTopTabBringsItBack() {
        assertTrue(FilePager.Dismiss.claims(false, 80, 5, SLOP, false, false, true));
        assertFalse("the terminal's own scroll", FilePager.Dismiss.claims(false, 80, 5, SLOP, false, false, false));
        assertFalse(FilePager.Dismiss.claims(false, -80, 5, SLOP, false, false, true));
    }

    @Test public void itMovesVerticallyAndAnotherMachinesFileReadsThroughThatMachine() throws Exception {
        String pager = new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(
                "src/main/java/dev/herdr/android/FilePager.java")), java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(pager.contains("page.animate().translationY(open ? 0 : -getHeight())"));
        assertFalse("no slide from the right any more", pager.contains("translationX(open ? 0 : getWidth())"));
        String gta = new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(
                "src/main/java/dev/herdr/android/GpuiTerminalActivity.java")), java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(gta.contains("String reply = readThrough(handle, t, p);"));
        assertTrue("the cover's panel comes back as it was", gta.contains("toggleWorkPanel(true);") && gta.contains("reopenPanelAfterFile = true;"));
    }

    @Test public void anotherMachinesDocIsKeptWithItsMachineAndOpensFromThatCopyOffline() throws Exception {
        String gta = new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(
                "src/main/java/dev/herdr/android/GpuiTerminalActivity.java")), java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(gta.contains("refFiles.save(docMachine, DOC_FILES, p, reply, System.currentTimeMillis());"));
        assertTrue(gta.contains("refFiles.open(docMachine, DOC_FILES, path, System.currentTimeMillis())"));
        assertTrue("the machine is the doc's own, by its binding", gta.contains("bindings.machine(source.id, source.connectionRevision)"));
    }
}
