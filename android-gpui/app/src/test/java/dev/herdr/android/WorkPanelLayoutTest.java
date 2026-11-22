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

    @Test public void aReleaseNearTheHingeLandsOnIt() {
        assertEquals(FOLD / 2f, WorkPanelLayout.snapped(FOLD / 2f + 12, FOLD), 0f);
        assertEquals(FOLD / 2f + 30, WorkPanelLayout.snapped(FOLD / 2f + 30, FOLD), 0f);
        assertEquals(0.5f, WorkPanelLayout.released(WorkPanelLayout.snapped(FOLD / 2f - 9, FOLD), FOLD, 0.4f).share, 1e-6f);
    }

    @Test public void theGripIsAVisiblePillAndTheHiddenPanelLeavesAHandle() throws IOException {
        String divider = new String(Files.readAllBytes(Paths.get("src/main/java/dev/herdr/android/PanelDivider.java")), StandardCharsets.UTF_8);
        assertEquals(4, PanelDivider.GRIP_W_DP);
        assertEquals(36, PanelDivider.GRIP_H_DP);
        assertEquals(48, PanelDivider.EDGE_GRIP_H_DP);
        assertTrue(divider.contains("edge.addView(pill(gripColor)"));
        assertTrue(divider.contains("dragged(true);") && divider.contains("dragged(false);"));
    }

    @Test public void thePanelsTypeIsReadable() throws IOException {
        assertEquals(14f, WorkPanel.BODY_SP, 0f);
        assertEquals(20, WorkPanel.LINE_DP);
        assertEquals(16, WorkPanel.SIDE_DP);
        String panel = new String(Files.readAllBytes(Paths.get("src/main/java/dev/herdr/android/WorkPanel.java")), StandardCharsets.UTF_8);
        // The age moved into the tab row; bullets hang at their text.
        assertTrue(panel.contains("showAge(f.text"));
        assertTrue(panel.contains("first + Math.round(bodyPaint().measureText(marker))"));
        // Text clears the bar below by 16 dp, mid-scroll too (review A6).
        assertEquals(16, WorkPanel.BOTTOM_DP);
        assertTrue(panel.contains("scroll.setPadding(0, 0, 0, dp(BOTTOM_DP));") && panel.contains("scroll.setClipToPadding(true);"));
    }

    @Test public void withNoChoiceTheUnfoldedSplitIsOnTheHinge() {
        // The review's Fold is 949 dp wide: the default leaves the divider at 474.5 dp (A3).
        WorkPanelLayout l = WorkPanelLayout.of(949, false, false, WorkPanelLayout.Choice.parse(""));
        assertEquals(474.5f, 949 * (1 - l.share), 1e-3f);
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
