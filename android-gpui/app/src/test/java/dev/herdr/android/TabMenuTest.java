package dev.herdr.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;
import org.json.JSONArray;
import org.junit.Test;

/** A tab's long-press sheet: closing a tab never silently ends an agent (factory-setup.29). */
public final class TabMenuTest {
    private static TabMenu.Pane shell(String program) {
        return new TabMenu.Pane("t1", "shell", "", program, "", "");
    }

    private static TabMenu.Pane agent(String kind, String program) {
        return new TabMenu.Pane("t2", "factory-pm", kind, program, "working", "Fixing the rail");
    }

    @Test public void idleShellsCloseWithoutAskingAndProgramsAreNamed() {
        List<TabMenu.Pane> idle = Arrays.asList(shell("zsh"), shell(""));
        assertEquals(TabMenu.Close.SHELLS, TabMenu.close(idle));
        assertEquals("Close tab", TabMenu.closeLabel(idle));
        assertFalse(TabMenu.closeConfirms(idle));
        assertEquals("build · shell", TabMenu.title("build", idle));

        List<TabMenu.Pane> busy = Arrays.asList(shell("zsh"), shell("vim"), shell("vim"));
        assertEquals("Close tab and stop vim", TabMenu.closeLabel(busy));
        assertTrue(TabMenu.closeConfirms(busy));
        assertEquals("build · vim", TabMenu.title("build", busy));
    }

    @Test public void anAgentTabOffersNoCloseOnlyAnExplicitEnd() {
        List<TabMenu.Pane> tab = Arrays.asList(agent("claude", "claude"), shell("zsh"));
        assertEquals(TabMenu.Close.AGENTS, TabMenu.close(tab));
        assertNull(TabMenu.closeLabel(tab));
        assertEquals("factory-pm · Claude · working", TabMenu.title("factory-pm", tab));
        assertEquals("End factory-pm…", TabMenu.endLabel(tab.get(0)));
        String says = TabMenu.endMessage(tab.get(0));
        assertTrue(says.startsWith("Claude in factory-pm is working."));
        assertTrue(says.contains("Fixing the rail"));
        assertTrue(says.contains("stops the agent"));
    }

    @Test public void anAgentThatExitedIsItsKeptShellAgain() {
        TabMenu.Pane ended = agent("codex", "zsh");
        assertFalse(ended.agent());
        assertFalse(ended.busyShell());
        assertEquals("Close tab", TabMenu.closeLabel(Arrays.asList(ended)));
        // The program is unknown (an older core): still an agent, never closed by surprise.
        assertTrue(agent("claude", "").agent());
        assertTrue(agent("claude", "aswap").agent());
    }

    @Test public void panesComeFromTheViewForThatTabOnly() throws Exception {
        JSONArray view = new JSONArray("[{\"id\":\"t1\",\"workspace_id\":\"s1\",\"tab_id\":\"a\",\"kind\":\"claude\",\"program\":\"claude\"},"
                + "{\"id\":\"t2\",\"workspace_id\":\"s1\",\"tab_id\":\"b\",\"program\":\"zsh\"},"
                + "{\"id\":\"t3\",\"workspace_id\":\"s2\",\"tab_id\":\"a\",\"program\":\"zsh\"}]");
        List<TabMenu.Pane> panes = TabMenu.panes(view, "s1", "a");
        assertEquals(1, panes.size());
        assertEquals("t1", panes.get(0).id);
        assertTrue(TabMenu.panes(null, "s1", "a").isEmpty());
    }

    @Test public void theSheetAndToolbarUseIt() throws IOException {
        String rail = new String(Files.readAllBytes(Paths.get("src/main/java/dev/herdr/android/GpuiTerminalActivity.java")),
                StandardCharsets.UTF_8);
        int start = rail.indexOf("private void showTabActions(");
        String sheet = rail.substring(start, rail.indexOf("private boolean stillOwned(", start));
        assertTrue(sheet.contains("TabMenu.closeLabel(inTab)"));
        assertTrue(sheet.contains("\"End agent\""));
        assertFalse(sheet.contains("\"Close window\""));
        assertTrue(rail.contains("if (!shellMode()) pinned.addView(quickShellButton());"));
        int quick = rail.indexOf("private ImageButton quickShellButton(");
        String button = rail.substring(quick, rail.indexOf("\n    }\n", quick));
        assertTrue(button.contains("newWindow(target, newWindowPlace(target), \"shell\")"));
        assertTrue(button.contains("showNewMenu(view)"));
        assertFalse(rail.contains("\"Close terminal\").setIcon"));
        assertTrue(button.contains("!still[0].still()"));
        assertTrue(button.contains("StillPress.on(shell)"));
        // A host switch between the sheet and its dialog ends nothing on the new host.
        String end = sheet.substring(sheet.indexOf("\"End agent\""));
        assertTrue(end.indexOf("stillOwned(owned, label)") < end.indexOf("pane.kill"));
    }

    @Test public void aPressThatTravelledIsNotATap() {
        StillPress press = StillPress.forTest(8);
        press.track(android.view.MotionEvent.ACTION_DOWN, 10, 10, 1);
        press.track(android.view.MotionEvent.ACTION_MOVE, 14, 12, 1);
        press.track(android.view.MotionEvent.ACTION_UP, 15, 12, 1);
        assertTrue(press.still());
        press.track(android.view.MotionEvent.ACTION_DOWN, 10, 10, 1);
        press.track(android.view.MotionEvent.ACTION_MOVE, 40, 10, 1);
        press.track(android.view.MotionEvent.ACTION_UP, 12, 10, 1);
        assertFalse(press.still());
        press.track(android.view.MotionEvent.ACTION_DOWN, 10, 10, 1);
        press.track(android.view.MotionEvent.ACTION_CANCEL, 10, 10, 1);
        assertFalse(press.still());
        press.track(android.view.MotionEvent.ACTION_DOWN, 10, 10, 1);
        assertTrue(press.still());
    }
}
