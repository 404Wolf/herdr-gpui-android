package dev.herdr.android;


import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import org.junit.Test;

/** Wolf 11-22: a switch to another computer shows its pane from cache at once, live behind it. */
public final class CachedSwitchTest {
    @Test public void theCachedPaneIsShownBeforeTheConnectionIsOpened() throws Exception {
        String gta = new String(Files.readAllBytes(Paths.get("src/main/java/dev/herdr/android/GpuiTerminalActivity.java")),
                StandardCharsets.UTF_8);
        int sw = gta.indexOf("    void switchHost(SavedRemoteHost host, String pane, boolean picked) {");
        int cached = gta.indexOf("String cached = cachedLanding(host.id, pane);", sw);
        int shown = gta.indexOf("showSaved(host.id, cached);", sw);
        int connect = gta.indexOf("connector.execute(() -> {", sw);
        int offlinePath = gta.indexOf("switchHostOffline(host, pane, picked);", sw);
        assertTrue(sw > 0 && offlinePath > sw && offlinePath < cached, "no network: the offline path as before");
        assertTrue(cached > 0 && shown > cached && connect > shown, "cache first, then connect");
        // The stand-in is the target host's own: installOffline names it, and adoptHost keeps
        // the saved frame of that same host until the live pane paints.
        assertTrue(gta.contains("installOffline(host.id, host.name, cached);"));
        assertTrue(gta.contains("if (next.resumed || host.id.equals(savedShownHost)) {"));
        // Both moments are timed from the tap (herdr-switch): cached, then live.
        assertTrue(gta.contains("\"cached \" + pane + \" shown \""));
        assertTrue(gta.contains("android.util.Log.w(\"herdr-switch\", \"pane \" + pane + \" shown \""));
    }

    private static void assertTrue(boolean condition, String why) {
        org.junit.Assert.assertTrue(why, condition);
    }

    private static void assertTrue(boolean condition) {
        org.junit.Assert.assertTrue(condition);
    }
}
