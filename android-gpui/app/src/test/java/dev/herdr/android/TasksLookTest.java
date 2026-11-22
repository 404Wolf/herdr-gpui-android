package dev.herdr.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.HashSet;
import java.util.Set;
import org.junit.Test;

/** Wolf's Tasks and loading refinements (11-22), without a window. */
public final class TasksLookTest {
    private static String source(String name) throws IOException {
        return new String(Files.readAllBytes(Paths.get("src/main/java/dev/herdr/android/" + name + ".java")), StandardCharsets.UTF_8);
    }

    @Test public void aTitleTapOpensAndTheSameTapClosesWithItsBeads() {
        Set<String> open = new HashSet<>(), folded = new HashSet<>();
        assertTrue(PanelModel.toggle("e1", true, open, folded));
        assertTrue(open.contains("e1") && !folded.contains("e1"));
        assertFalse(PanelModel.toggle("e1", true, open, folded));
        assertFalse(open.contains("e1"));
        assertTrue(folded.contains("e1"));
        // Opened again, its beads come back with its plan.
        assertTrue(PanelModel.toggle("e1", true, open, folded));
        assertFalse(folded.contains("e1"));
        // A leaf has nothing to fold.
        PanelModel.toggle("b1", false, open, folded);
        PanelModel.toggle("b1", false, open, folded);
        assertFalse(folded.contains("b1"));
    }

    @Test public void tasksHaveRoundMarkersNoTrianglesAndSmallEqualSides() throws IOException {
        String panel = source("WorkPanel");
        String row = panel.substring(panel.indexOf("private View row(PanelModel.Row r) {"), panel.indexOf("private View plan(PanelModel.Row r) {"));
        assertFalse(panel.contains("RailDisclosure"));
        assertFalse(row.contains("\"▴\""));
        assertTrue(row.contains("dot.setShape(android.graphics.drawable.GradientDrawable.OVAL);"));
        assertTrue(row.contains("PanelModel.toggle(id, kids, open, folded)"));
        assertEquals(12, WorkPanel.TASKS_SIDE_DP);
        assertTrue(panel.contains("body.setPadding(side, body.getPaddingTop(), side, body.getPaddingBottom());"));
        // Dense: a 36 dp row with 4 dp above and below; the plan and "N closed" start at the title.
        assertEquals(36, WorkPanel.TASK_ROW_DP);
        assertEquals(4, WorkPanel.TASK_PAD_DP);
        assertEquals(WorkPanel.MARK_DP + WorkPanel.MARK_GAP_DP, WorkPanel.TITLE_AT_DP);
        assertTrue(panel.contains("box.setPadding(dp(INDENT_DP * r.depth + TITLE_AT_DP)"));
    }

    @Test public void loadingIsOneSkeletonNeverDots() throws IOException {
        for (String name : new String[] {"WorkPanel", "UsageView", "UsageModel"}) {
            String s = source(name);
            for (String dots : new String[] {"Reading its plan…", "'s beads…", "refreshing…", "\"asking…\"", "· asking…"}) {
                assertFalse(name + " " + dots, s.contains(dots));
            }
        }
        String panel = source("WorkPanel");
        assertTrue(panel.contains("new Skeleton(activity, chrome, 6, TASK_SHAPE)"));
        assertTrue(panel.contains("new Skeleton(activity, chrome, 8, TEXT_SHAPE)"));
        assertTrue(panel.contains("new Skeleton(activity, chrome, 3, TEXT_SHAPE)"));
        assertTrue(source("UsageView").contains("new Skeleton(activity, chrome, 3, LOADING_SHAPE)"));
        // Sized like what comes: a Tasks row's height and its title's start.
        assertEquals(WorkPanel.TASK_ROW_DP + 2 * WorkPanel.TASK_PAD_DP, WorkPanel.TASK_SHAPE.rowDp);
        assertEquals(WorkPanel.TITLE_AT_DP, WorkPanel.TASK_SHAPE.leadDp);
        assertEquals(WorkPanel.LINE_DP, WorkPanel.TEXT_SHAPE.rowDp);
        // Dark bars on the palette's raised fill, a slow pulse, and TalkBack hears "Loading".
        String skeleton = source("Skeleton");
        assertTrue(skeleton.contains("shape.setColor(chrome.raised);"));
        assertTrue(Skeleton.PULSE_MS >= 1000);
        assertTrue(skeleton.contains("setContentDescription(\"Loading\");"));
        assertTrue(skeleton.contains("pulse.cancel();"));
    }
}
