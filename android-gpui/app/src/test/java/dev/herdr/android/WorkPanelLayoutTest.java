package dev.herdr.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import org.junit.Test;

/** The terminal | panel divider's choices (factory-9zz.9), in dp, without a window. */
public final class WorkPanelLayoutTest {
    private static final int FOLD = 949;

    @Test public void theSplitIsWolfsChoiceWithEachSideAtLeastItsMinimum() {
        assertEquals(0.5f, WorkPanelLayout.of(FOLD, false, false).share, 0f);
        assertEquals(0.4f, WorkPanelLayout.of(FOLD, false, false, new WorkPanelLayout.Choice(0.4f, false)).share, 1e-6f);
        // Too narrow a panel or too narrow a terminal is clamped.
        assertEquals(280f / FOLD, WorkPanelLayout.of(FOLD, false, false, new WorkPanelLayout.Choice(0.1f, false)).share, 1e-6f);
        assertEquals(1 - 320f / FOLD, WorkPanelLayout.of(FOLD, false, false, new WorkPanelLayout.Choice(0.9f, false)).share, 1e-6f);
        // The cover is untouched by the divider.
        assertFalse(WorkPanelLayout.of(412, false, false, new WorkPanelLayout.Choice(0.4f, true)).divider);
    }

    @Test public void draggingAllTheWayRightHidesItAndAnInwardEdgeSwipeBringsItBack() {
        WorkPanelLayout.Choice kept = WorkPanelLayout.released(FOLD - 400, FOLD, 0.5f);
        assertFalse(kept.hidden);
        assertEquals(400f / FOLD, kept.share, 1e-6f);
        WorkPanelLayout.Choice hidden = WorkPanelLayout.released(FOLD - 150, FOLD, 0.42f);
        assertTrue(hidden.hidden);
        // It comes back at the share it had.
        assertEquals(0.42f, hidden.share, 0f);
        WorkPanelLayout gone = WorkPanelLayout.of(FOLD, false, false, hidden);
        assertFalse(gone.shown);
        assertTrue(gone.hidden && gone.divider);
        // Hidden, the rail can dock again; shown, the panel takes its room.
        assertFalse(WorkPanelLayout.splitsWithRail(FOLD, false, true));
        assertTrue(WorkPanelLayout.splitsWithRail(FOLD, false, false));
        assertTrue(WorkPanelLayout.restores(-60, 10));
        assertFalse(WorkPanelLayout.restores(-30, 0));
        assertFalse(WorkPanelLayout.restores(-60, 80));
        assertFalse(WorkPanelLayout.shownAgain(hidden).hidden);
        assertEquals(0.42f, WorkPanelLayout.shownAgain(hidden).share, 0f);
        // The drag never squeezes the terminal under its minimum.
        assertEquals(1 - 320f / FOLD, WorkPanelLayout.dragged(10, FOLD), 1e-6f);
    }

    @Test public void theChoiceIsKeptAsTextAndAnythingElseIsTheDefault() {
        WorkPanelLayout.Choice c = WorkPanelLayout.Choice.parse(new WorkPanelLayout.Choice(0.42f, true).stored());
        assertTrue(c.hidden);
        assertEquals(0.42f, c.share, 1e-3f);
        assertEquals(0f, WorkPanelLayout.Choice.parse("garbage").share, 0f);
        assertFalse(WorkPanelLayout.Choice.parse("hidden:2").hidden);
        assertEquals(0f, WorkPanelLayout.Choice.parse(null).share, 0f);
    }

    @Test public void theTerminalResizesOnceOnTheReleaseNeverWhileDragging() throws IOException {
        String divider = new String(Files.readAllBytes(Paths.get("src/main/java/dev/herdr/android/PanelDivider.java")), StandardCharsets.UTF_8);
        int move = divider.indexOf("case MotionEvent.ACTION_MOVE:"), up = divider.indexOf("case MotionEvent.ACTION_UP:");
        // A move only moves the preview line; the choice (and the resize) waits for the lift.
        assertFalse(divider.substring(move, up).contains("chose.accept"));
        assertTrue(divider.substring(up, divider.indexOf("case MotionEvent.ACTION_CANCEL:")).contains("chose.accept(WorkPanelLayout.released("));
        // The right edge's strip is excluded from Android's Back gesture.
        assertTrue(divider.contains("setSystemGestureExclusionRects"));
        String gta = new String(Files.readAllBytes(Paths.get("src/main/java/dev/herdr/android/GpuiTerminalActivity.java")), StandardCharsets.UTF_8);
        assertTrue(gta.contains("\"work_panel_\" + (getResources().getConfiguration().orientation"));
        assertTrue(gta.contains("root().announceForAccessibility(next.hidden ? \"Work panel hidden\" : \"Work panel restored\");"));
    }
}
