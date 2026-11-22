package dev.herdr.android;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import org.junit.Test;

/** The OLED look: panels, sheets, raised fills and pressed rows read the palette's roles, never a tint of their own. */
public final class PaletteRolesTest {
    private static String read(String name) throws IOException {
        return new String(Files.readAllBytes(Paths.get("src/main/java/dev/herdr/android/" + name + ".java")), StandardCharsets.UTF_8);
    }

    @Test public void panelsAndSheetsAreThePanelRaisedFillsAreRaisedAndAPressIsHover() throws IOException {
        String panel = read("WorkPanel"), usage = read("UsageView"), sheet = read("ActionSheet");
        assertTrue(panel.contains("root.setBackgroundColor(chrome.panel);"));
        assertTrue(panel.contains("int dim = chrome.raised;"));
        assertTrue(usage.contains("shape.setColor(chrome.panel);"));
        assertTrue(usage.contains("gripShape.setColor(chrome.raised);") && usage.contains("track.setColor(chrome.raised);"));
        assertTrue(sheet.contains("shape.setColor(chrome.panel);"));
        assertTrue(sheet.contains("ColorStateList.valueOf(chrome.hover)"));
        assertTrue(read("PinnedSection").contains("row.setBackgroundColor(chrome.selected);"));
        // The window chip on screen: the selected container with the surface's text, not the accent.
        String gta = read("GpuiTerminalActivity");
        assertTrue(gta.contains("shape.setColor(active ? chrome.selected : chrome.background);"));
        assertTrue(gta.contains("chip.setTextColor(idle && !active ? chrome.onSurfaceVariant : chrome.onSurface);"));
        // No hand-mixed tints left for these surfaces.
        assertFalse(usage.contains("withAlpha(chrome.onSurfaceVariant, 0x33)"));
        assertFalse(panel.contains("withAlpha(chrome.onSurfaceVariant, 0x33)"));
    }
}
