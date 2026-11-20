package dev.herdr.android;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import org.junit.Test;

public final class OfflineRevisionWiringTest {
    private static String section(String start, String end) throws Exception {
        String source = new String(Files.readAllBytes(Paths.get(
                "src/main/java/dev/herdr/android/GpuiTerminalActivity.java")), StandardCharsets.UTF_8);
        int at = source.indexOf(start);
        return source.substring(at, source.indexOf(end, at));
    }

    @Test public void ask2TheOfflineStandInWaitsForTheStoredRevision() throws Exception {
        String resume = section("protected void onResume()", "protected void onPause()");
        assertFalse("resume published the stand-in with A's old revision before reading B's",
                resume.contains("installOffline(currentHostId, currentHostKey"));
    }

    @Test public void ask2APreviousRevisionsListsCannotFillTheOfflineStandIn() throws Exception {
        String fill = section("private void fillFromCache(", "private void pinPanes(");
        assertFalse("the stand-in asked for a list by profile ID alone",
                fill.contains("lastLists(offline.hostId)"));
    }

    @Test public void ask2TheDelayedOpenOrMissWithUnknownRevisionCannotReplaceTheHeldFrame() throws Exception {
        String draw = section("private void drawSaved(", "private static String paneTitle(");
        int install = draw.indexOf("RemoteNative.showOpened(renderId)");
        assertTrue("the callback must reject an unknown revision before replacing the frame",
                install > 0 && draw.substring(0, install).contains("if (key == null ||"));
    }
}
