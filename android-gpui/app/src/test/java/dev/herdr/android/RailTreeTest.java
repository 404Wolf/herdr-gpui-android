package dev.herdr.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

public final class RailTreeTest {
    @Test public void aWorktreeFolderNamesItsRepo() {
        assertEquals("monopoly", RailTree.repoOfPath("/home/w/.herdr/worktrees/monopoly/wolf-x"));
        assertEquals("monopoly", RailTree.repoOfPath("/home/w/.herdr/worktrees/monopoly/wolf-x/src"));
        assertNull(RailTree.repoOfPath("/home/w/monopoly"));
        assertNull(RailTree.repoOfPath("/home/w/.herdr/worktrees/monopoly/"));
        assertNull(RailTree.repoOfPath(""));
    }

    @Test public void worktreesFollowTheirRepoAndOthersKeepTheirPlace() throws Exception {
        JSONArray spaces = new JSONArray()
                .put(space("a", "wolf-x"))
                .put(space("b", "zulip"))
                .put(space("c", "monopoly"))
                .put(space("d", "wolf-y"))
                .put(space("e", "kache-wt"));
        JSONArray panes = new JSONArray()
                .put(pane("a", "/h/.herdr/worktrees/monopoly/wolf-x"))
                .put(pane("b", "/h/zulip"))
                .put(pane("c", "/h/monopoly"))
                .put(pane("d", "/h/.herdr/worktrees/monopoly/wolf-y/sub"))
                .put(pane("e", "/h/.herdr/worktrees/kache/kache-wt"));
        Map<String, String> repos = RailTree.repos(panes);
        assertEquals("monopoly", repos.get("a"));
        assertNull(repos.get("c"));
        List<String> labels = new ArrayList<>();
        for (JSONObject s : RailTree.order(spaces, repos)) labels.add(s.getString("label"));
        assertEquals(List.of("monopoly", "wolf-x", "wolf-y", "zulip", "kache-wt"), labels);
    }

    @Test public void aTitleDropsTheSpaceItRepeats() {
        assertEquals("Optimize chart loading", RailTree.withoutSpace(
                "Optimize chart loading | wolf-ins1584-study-fr....", "wolf-ins1584-study-frames"));
        assertEquals("Implement LOD", RailTree.withoutSpace("Implement LOD | wolf-lod", "wolf-lod"));
        assertEquals("a | b", RailTree.withoutSpace("a | b", "zulip"));
        assertEquals("plain", RailTree.withoutSpace("plain", "zulip"));
    }

    @Test public void spacesAreNamedForTheirWorktreeAndRepeatsToldApart() throws Exception {
        JSONArray spaces = new JSONArray()
                .put(space("a", "macro")).put(space("b", "macro")).put(space("c", "macro"));
        JSONArray panes = new JSONArray()
                .put(pane("a", "/h/.herdr/worktrees/macro/wolf-connector-cards"))
                .put(pane("b", "/h/macro")).put(pane("c", "/h/macro"));
        JSONArray tabs = new JSONArray()
                .put(new JSONObject().put("id", "x").put("workspace_id", "b").put("label", "Onboarding"))
                .put(new JSONObject().put("id", "y").put("workspace_id", "c").put("label", "Research"));
        Map<String, String> labels = RailTree.labels(spaces, tabs, panes);
        assertEquals("wolf-connector-cards", labels.get("a"));
        assertEquals("macro · Onboarding", labels.get("b"));
        assertEquals("macro · Research", labels.get("c"));
    }

    @Test public void terminalIdsAreNeverTitles() throws Exception {
        JSONObject pane = new JSONObject().put("kind", "claude").put("cwd", "/h/macro/");
        assertEquals("claude · macro", RailTree.fallbackTitle("t5s", pane));
        assertEquals("shell · macro", RailTree.fallbackTitle("server/t1z", new JSONObject().put("cwd", "/h/macro")));
        assertEquals("Fix chart", RailTree.fallbackTitle("Fix chart", pane));
    }

    private static JSONObject space(String id, String label) throws Exception {
        return new JSONObject().put("id", id).put("label", label);
    }

    private static JSONObject pane(String space, String cwd) throws Exception {
        return new JSONObject().put("id", "t" + space).put("workspace_id", space).put("cwd", cwd);
    }
}
