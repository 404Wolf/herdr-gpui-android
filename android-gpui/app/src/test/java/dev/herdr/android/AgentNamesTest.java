package dev.herdr.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;
import org.json.JSONArray;
import org.junit.Test;

/**
 * factory-jmc. Wolf: "i never need to see t3xz or the code since i do not use
 * that", and "the dot is enough for state no need for working or done".
 */
public final class AgentNamesTest {
    private static FleetTree.Member agent(String id, String name, String task, String helper, String title) {
        FleetTree.Member m = new FleetTree.Member();
        m.id = id;
        m.name = name;
        m.task = task;
        m.helper = helper;
        m.title = title;
        m.kind = "claude";
        return m;
    }

    @Test public void aRowIsItsShortNameNeverItsId() {
        assertEquals("phone-rail", AgentNames.of(agent("t3qx", "phone-rail", "phone-rail", "", "Phone-rail lane B")));
        // No name: the task label, as written.
        assertEquals("usage-view", AgentNames.of(agent("t3qx", "", "usage-view", "", "")));
        // A task that is a bead id names the bead, not the agent: the title is next.
        assertEquals("Fix the totals", AgentNames.of(agent("t3qx", "", "factory-setup.21", "", "Fix the totals")));
        // Nothing but ids: never the id, an honest "no task label" (drawn dim).
        assertEquals(AgentNames.UNNAMED, AgentNames.of(agent("t3qx", "t3qx", "", "", "t3qx")));
        assertEquals(AgentNames.UNNAMED, AgentNames.of(agent("server/t1z", "server/t1z", "", "", "")));
        assertTrue(AgentNames.unnamed(agent("t5", "", "", "", "")));
    }

    @Test public void aHelperShowsItsOwnPurposeNotItsWorkersTitle() {
        assertEquals("design-review: right-panel",
                AgentNames.of(agent("t4a", "phone-rail-review", "right-panel", "design-review", "Phone-rail lane B")));
        // Without a usable task, its role alone; still never the worker's title or an id.
        assertEquals("audit", AgentNames.of(agent("t4a", "", "factory-setup.2", "audit", "Phone-rail lane B")));
    }

    @Test public void aliasesAndBeadIdsAreRecognised() {
        for (String alias : new String[] {"t3xz", "T1B6", "server/t1z", "ab12", "x", ""}) assertTrue(alias, AgentNames.alias(alias));
        for (String name : new String[] {"phone-rail", "factory-pm", "Phone-rail lane B", "deacon"}) assertFalse(name, AgentNames.alias(name));
        assertTrue(AgentNames.beadId("factory-setup.21"));
        assertTrue(AgentNames.beadId("bd-a1b2.3.1"));
        assertFalse(AgentNames.beadId("phone-rail"));
    }

    @Test public void twoAgentsWithTheSameNameAreToldApartByFolderElseOrder() {
        FleetTree.Member a = agent("t1", "worker", "", "", ""), b = agent("t2", "worker", "", "", "");
        a.cwd = "/w/factory-phone-rail";
        b.cwd = "/w/factory-phone-usage";
        AgentNames.disambiguate(Arrays.asList(a, b));
        assertEquals("worker · factory-phone-rail", FleetPanes.displayTitle(a));
        assertEquals("worker · factory-phone-usage", FleetPanes.displayTitle(b));
        b.cwd = "/x/factory-phone-rail";
        AgentNames.disambiguate(Arrays.asList(a, b));
        assertEquals("worker", FleetPanes.displayTitle(a));
        assertEquals("worker · 2", FleetPanes.displayTitle(b));
        FleetTree.Member alone = agent("t3", "solo-agent", "", "", "");
        AgentNames.disambiguate(Arrays.asList(alone));
        assertEquals("solo-agent", FleetPanes.displayTitle(alone));
    }

    @Test public void theStateIsSaidToAScreenReaderNotDrawnAsWords() {
        assertEquals("needs you", AgentNames.stateWords("blocked", ""));
        assertEquals("needs you", AgentNames.stateWords("idle", "question"));
        assertEquals("working", AgentNames.stateWords("working", ""));
        assertEquals("state unknown", AgentNames.stateWords("", ""));
    }

    @Test public void membersCarryTheirNameTaskAndHelperFromTheView() throws Exception {
        JSONArray panes = new JSONArray("[{\"id\":\"t3qx\",\"title\":\"Phone-rail lane B\",\"name\":\"phone-rail\",\"task\":\"phone-rail\","
                + "\"kind\":\"claude\",\"workspace_id\":\"s1\",\"tab_id\":\"a\"},"
                + "{\"id\":\"t4\",\"labels\":{\"helper\":\"review\",\"task\":\"usage-view\"},\"kind\":\"claude\",\"workspace_id\":\"s1\",\"tab_id\":\"b\"},"
                + "{\"id\":\"t5\",\"name\":null,\"kind\":\"claude\",\"workspace_id\":\"s1\",\"tab_id\":\"c\"}]");
        List<FleetTree.Member> m = FleetPanes.members(null, null, panes, FleetTree.Member::new);
        assertEquals("phone-rail", FleetPanes.displayTitle(m.get(0)));
        assertEquals("review: usage-view", FleetPanes.displayTitle(m.get(1)));
        assertEquals(AgentNames.UNNAMED, FleetPanes.displayTitle(m.get(2)));
    }

    @Test public void noIdIsDrawnInTheRailOrHome() throws IOException {
        String rail = source("GpuiTerminalActivity"), home = source("HomeActivity");
        assertFalse(rail.contains("\"Copy terminal id\""));
        assertFalse(home.contains("\"Copy terminal id\""));
        assertFalse(rail.contains("chromeTitle(FleetPanes.displayTitle(m), m.id)"));
        assertFalse(rail.contains("pm.pm.id + "));
        assertFalse(rail.contains("String sub = deacon.id"));
        assertFalse(home.contains("\"Deacon \" + deacon.id"));
        assertFalse(home.contains("second.add(agent.id)"));
        assertFalse(rail.contains("\" (\" + w.terminal + \" only)\""));
        // The pane's crumb: the agent's name, no "PM·t19" / "worker·…" badge.
        assertFalse(rail.contains("FleetTree.roleBadge("));
        String crumb = section(rail, "private void updateCrumb(", "crumb.setVisibility(View.VISIBLE);");
        assertTrue(crumb.contains("FleetPanes.displayTitle(m)"));
        // The state is the dot; only a folded "!" is drawn, and TalkBack still hears the state.
        String row = section(rail, "private void updateRailPaneRow(", "private View railRule(");
        assertTrue(row.contains("String state = bubbled ? \"!\" : \"\";"));
        assertTrue(row.contains("AgentNames.stateWords("));
    }

    private static String source(String name) throws IOException {
        return new String(Files.readAllBytes(Paths.get("src/main/java/dev/herdr/android/" + name + ".java")), StandardCharsets.UTF_8);
    }

    private static String section(String source, String start, String end) {
        int first = source.indexOf(start);
        assertTrue(start, first >= 0);
        int last = source.indexOf(end, first);
        assertTrue(end, last > first);
        return source.substring(first, last);
    }
}
