package dev.herdr.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import dev.herdr.android.core.Shortcut;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

public final class PinnedEnterTest {
    @Test
    public void onlyAPlainEnterKeyIsDroppedFromTheRow() {
        assertTrue(GpuiTerminalProtocol.isPlainEnter(Shortcut.keys("Enter", "enter")));
        assertTrue(GpuiTerminalProtocol.isPlainEnter(Shortcut.keys("Return", "enter")));
        // A sequence that only starts with Enter is a macro, not the pinned key.
        assertFalse(GpuiTerminalProtocol.isPlainEnter(Shortcut.keys("Twice", "enter enter")));
        assertFalse(GpuiTerminalProtocol.isPlainEnter(Shortcut.keys("Ctrl+C", "ctrl+c")));
        assertFalse(GpuiTerminalProtocol.isPlainEnter(Shortcut.keys("Esc", "esc")));
        assertFalse(GpuiTerminalProtocol.isPlainEnter(Shortcut.text("enter", "enter")));
    }

    @Test
    public void theDefaultRowKeepsEverythingButEnter() {
        List<String> kept = new ArrayList<>();
        for (Shortcut shortcut : Shortcut.parseConfiguration(Shortcut.DEFAULT_CONFIGURATION)) {
            if (!GpuiTerminalProtocol.isPlainEnter(shortcut)) kept.add(shortcut.getLabel());
        }
        assertEquals(java.util.Arrays.asList("Esc", "Tab", "Ctrl+C", "Up", "Down", "Left", "Right"), kept);
    }
}
