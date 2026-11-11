package dev.herdr.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import org.json.JSONObject;
import org.junit.Test;

public final class ComposeCommandTest {
    @Test
    public void composeIsOneTextSlotKeepingLineBreaks() throws Exception {
        JSONObject command = new JSONObject(GpuiTerminalProtocol.composeCommand("two\nlines"));
        assertEquals("compose", command.getString("type"));
        assertEquals("two\nlines", command.getString("text"));
        assertEquals(2, command.length());
    }

    @Test
    public void blankOrOversizedTextIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> GpuiTerminalProtocol.composeCommand(""));
        assertThrows(IllegalArgumentException.class, () -> GpuiTerminalProtocol.composeCommand(" \n "));
        StringBuilder big = new StringBuilder();
        for (int i = 0; i <= dev.herdr.android.core.Shortcut.MAX_TEXT_BYTES; i++) big.append('a');
        assertThrows(IllegalArgumentException.class, () -> GpuiTerminalProtocol.composeCommand(big.toString()));
    }
}
