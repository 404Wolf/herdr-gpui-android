package dev.herdr.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.json.JSONObject;
import org.junit.Test;

/** Audit A3: a switch whose attach herdr-core refused stops waiting and says why. */
public final class RefusedAttachTest {
    @Test public void aRefusalIsReadOnlyForThePaneItNames() throws Exception {
        JSONObject state = new JSONObject().put("state", "ready").put("pane_id", "")
                .put("refused", new JSONObject().put("terminal", "t1b6").put("reason", "no terminal \"t1b6\""));
        assertEquals("no terminal \"t1b6\"", GpuiTerminalActivity.refusedReason(state, "t1b6"));
        assertNull("another pane's refusal is not this one's", GpuiTerminalActivity.refusedReason(state, "t16b"));
        assertNull(GpuiTerminalActivity.refusedReason(new JSONObject().put("state", "connecting"), "t1b6"));
        assertNull(GpuiTerminalActivity.refusedReason(null, "t1b6"));
        JSONObject bare = new JSONObject().put("refused", new JSONObject().put("terminal", "t1"));
        assertEquals("it gave no reason", GpuiTerminalActivity.refusedReason(bare, "t1"));
    }
}
