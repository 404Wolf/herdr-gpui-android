package dev.herdr.android;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/** A pinch to 9 or 10 crashed the app (the renderer takes 11 to 36): the pinch stays within it. */
public final class PinchFloorTest {
    @Test public void aPinchStaysWithinWhatTheRendererTakes() {
        assertEquals(11f, GpuiTerminalActivity.pinchClamp(9f), 0f);
        assertEquals(11f, GpuiTerminalActivity.pinchClamp(10.5f), 0f);
        assertEquals(13f, GpuiTerminalActivity.pinchClamp(13f), 0f);
        assertEquals(24f, GpuiTerminalActivity.pinchClamp(40f), 0f);
        assertEquals("not pinched stays not pinched", 0f, GpuiTerminalActivity.pinchClamp(0f), 0f);
        assertEquals(0f, GpuiTerminalActivity.pinchClamp(Float.NaN), 0f);
    }

    @Test public void theHeadersAgeIsCentredWithThePinAndItsTabsNeverWrap() throws Exception {
        String panel = new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(
                "src/main/java/dev/herdr/android/WorkPanel.java")), java.nio.charset.StandardCharsets.UTF_8);
        org.junit.Assert.assertTrue(panel.contains("{overviewTab, tasksTab, close, age}) v.setPadding"));
        org.junit.Assert.assertTrue(panel.contains("view.setSingleLine(true);") && panel.contains("view.setHorizontallyScrolling(true);"));
    }
}
