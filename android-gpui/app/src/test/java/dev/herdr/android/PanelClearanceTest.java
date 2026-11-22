package dev.herdr.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import org.junit.Test;

/** The panel's text scrolls clear of the floating composer cluster (review C1), without a window. */
public final class PanelClearanceTest {
    @Test public void theTextClearsTheClusterFromItsTopNotItsHeight() {
        // Fold, 2.19 px/dp: the scroll ends at the screen's bottom (2208 px), under a 24 dp
        // gesture inset; the 43 dp cluster sits 8 dp above that inset, so its top is at 2208 - 165.
        int scrollBottom = 2208, clusterTop = 2208 - 165, gap = 18, least = 140;
        assertEquals(165 + gap, WorkPanel.clearance(scrollBottom, clusterTop, gap, least));
        // The old rule (the cluster's height and margin, 112 px) left about 50 px of text under it.
        assertTrue(WorkPanel.clearance(scrollBottom, clusterTop, gap, least) > 94 + 18);
        // A cluster that doesn't reach far down still gets the default.
        assertEquals(least, WorkPanel.clearance(2208, 2200, gap, least));
        assertEquals(8, WorkPanel.CLEAR_GAP_DP);
    }

    @Test public void theActivityHandsTheClusterItselfToThePanel() throws IOException {
        String gta = new String(Files.readAllBytes(Paths.get("src/main/java/dev/herdr/android/GpuiTerminalActivity.java")),
                StandardCharsets.UTF_8);
        assertTrue(gta.contains("workPanel.clearCluster(overPanel && shows ? cluster : null);"));
        String panel = new String(Files.readAllBytes(Paths.get("src/main/java/dev/herdr/android/WorkPanel.java")),
                StandardCharsets.UTF_8);
        assertFalse(panel.contains("void clearBottom("));
        assertTrue(panel.contains("scroll.setClipToPadding(false);"));
    }
}
