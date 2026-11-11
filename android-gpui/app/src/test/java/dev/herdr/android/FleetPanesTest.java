package dev.herdr.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

/**
 * Switching computers lands on that computer's live Deacon, read from the
 * new session's own poll. Every switch path goes through switchLanding: the
 * terminal's switchHost (rail computer header, its picker, Connect on an
 * offline header, a picked agent on another computer, Home's agent picks
 * via onNewIntent) and the host list falling back to another host.
 */
public final class FleetPanesTest {
    private static JSONObject pane(String id, String space, String role, String status) throws Exception {
        return new JSONObject().put("id", id).put("workspace_id", space).put("tab_id", space + "-tab")
                .put("title", id).put("role", role).put("status", status).put("kind", "claude");
    }

    private static JSONObject poll(JSONArray workspaces, JSONObject... panes) throws Exception {
        JSONArray list = new JSONArray();
        for (JSONObject p : panes) list.put(p);
        JSONObject state = new JSONObject().put("state", "ready").put("panes", list).put("tabs", new JSONArray());
        if (workspaces != null) state.put("workspaces", workspaces);
        return state;
    }

    private static JSONArray spaces(String... idLabel) throws Exception {
        JSONArray out = new JSONArray();
        for (int i = 0; i < idLabel.length; i += 2) out.put(new JSONObject().put("id", idLabel[i]).put("label", idLabel[i + 1]));
        return out;
    }

    @Test public void aComputerHeaderOrPickerSwitchLandsOnTheLabelledDeacon() throws Exception {
        JSONObject state = poll(spaces("w1", "home-manager", "w2", "misc"),
                pane("t5", "w1", "pm", "working"), pane("t9", "w2", "deacon", "idle"));
        assertEquals("t9", FleetPanes.switchLanding(state, null));
        assertEquals("t9", FleetPanes.switchLanding(state, ""));
    }

    @Test public void aDeaconKnownOnlyByItsSpaceNeedsTheSpaces() throws Exception {
        JSONObject named = poll(spaces("w1", "deacon"), pane("t3", "w1", "", "idle"));
        assertEquals("t3", FleetPanes.switchLanding(named, null));
        // Before the spaces arrive it is not known; the switch waits briefly for them.
        JSONObject early = poll(null, pane("t3", "w1", "", "idle"));
        assertFalse(FleetPanes.hasSpaces(early));
        assertTrue(FleetPanes.hasSpaces(named));
        assertEquals("", FleetPanes.switchLanding(early, null));
    }

    @Test public void aPickedLiveTerminalOnAnotherComputerWins() throws Exception {
        JSONObject state = poll(spaces("w1", "deacon", "w2", "app"),
                pane("t1", "w1", "deacon", "idle"), pane("t7", "w2", "worker", "working"));
        assertEquals("t7", FleetPanes.switchLanding(state, "t7"));
    }

    @Test public void aCachedTerminalOrDeaconThatIsGoneLandsOnTheLiveDeacon() throws Exception {
        // The rail's Deacon row on an offline computer names its cached Deacon (t2);
        // the live session has a new one (t8): that is the one focused.
        JSONObject state = poll(spaces("w1", "deacon"), pane("t8", "w1", "deacon", "idle"));
        assertEquals("t8", FleetPanes.switchLanding(state, "t2"));
    }

    @Test public void noDeaconKeepsTheCallersOwnChoice() throws Exception {
        JSONObject state = poll(spaces("w1", "app"), pane("t4", "w1", "pm", "working"));
        assertEquals("", FleetPanes.switchLanding(state, null));
        assertEquals("", FleetPanes.switchLanding(null, null));
    }

    @Test public void anotherComputersFederatedDeaconIsNeverTheLanding() throws Exception {
        JSONObject state = poll(spaces("w1", "app"), pane("laptop/t1", "w1", "deacon", "idle"),
                pane("t4", "w1", "pm", "working"));
        assertEquals("", FleetPanes.switchLanding(state, null));
        assertEquals("", FleetPanes.switchLanding(state, "laptop/t1"));
    }

    @Test public void homesComputerPickAgreesWithEverySwitchPath() throws Exception {
        // Home's pickMachine focuses FleetTree.pickTarget when pick says FOCUS; the
        // same live list gives the same terminal through switchLanding.
        JSONObject state = poll(spaces("w1", "deacon", "w2", "misc"),
                pane("t1", "w1", "", "idle"), pane("t6", "w2", "deacon", "working"));
        java.util.List<FleetTree.Member> live = FleetPanes.members(state.optJSONArray("workspaces"),
                state.optJSONArray("tabs"), state.optJSONArray("panes"), FleetTree.Member::new);
        assertEquals(FleetTree.Pick.FOCUS, FleetTree.pick(true, live));
        assertEquals(FleetTree.pickTarget(live).id, FleetPanes.switchLanding(state, null));
        // Offline Home never focuses (its list is cached); the switch paths only see live polls.
        assertEquals(FleetTree.Pick.OFFLINE, FleetTree.pick(false, live));
    }
}
