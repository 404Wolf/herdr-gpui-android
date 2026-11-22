package dev.herdr.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Wolf 11-22: edge swipes replace the Work button; the left edge opens the sidebar. */
public final class EdgeStripTest {
    @Test public void theStripIsCentredAndNeverTallerThanAndroidHonours() {
        int[] r = EdgeStrip.left(1000, 2000, 50, 400);
        assertEquals(0, r[0]);
        assertEquals(50, r[2]);
        assertEquals(400, r[3] - r[1]);
        assertEquals(800, r[1]);
        int[] small = EdgeStrip.left(40, 100, 50, 400);
        assertEquals(40, small[2]);
        assertEquals(100, small[3] - small[1]);
    }

    @Test public void noWorkButtonAndTheEdgeYieldsToAGestureWithAnOwner() throws Exception {
        String gta = new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(
                "src/main/java/dev/herdr/android/GpuiTerminalActivity.java")), java.nio.charset.StandardCharsets.UTF_8);
        assertFalse(gta.contains("\"Work\""));
        assertTrue(gta.contains("!edgeClaimed && !sidebarOpen && !scrolling && !panning"));
        assertTrue(gta.contains("EdgeStrip.left(r - l, b - t, dp(EdgeStrip.WIDTH_DP), dp(EdgeStrip.TALL_DP))"));
        int dispatch = gta.indexOf("private boolean dispatchTerminalTouch(MotionEvent event, int masked) {");
        assertTrue("a selection takes its touches before the edge looks", gta.indexOf("selection.onTouch(event)", dispatch)
                < gta.indexOf("!edgeClaimed && !sidebarOpen", dispatch));
    }
}
