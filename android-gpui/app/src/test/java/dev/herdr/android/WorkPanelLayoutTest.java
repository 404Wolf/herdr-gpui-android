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
        // Any width is kept; only too narrow a terminal is clamped.
        assertEquals(0.1f, WorkPanelLayout.of(FOLD, false, false, new WorkPanelLayout.Choice(0.1f, false)).share, 1e-6f);
        assertEquals(1 - 320f / FOLD, WorkPanelLayout.of(FOLD, false, false, new WorkPanelLayout.Choice(0.9f, false)).share, 1e-6f);
        // The cover is untouched by the divider.
        assertFalse(WorkPanelLayout.of(412, false, false, new WorkPanelLayout.Choice(0.4f, true)).divider);
    }

    @Test public void draggingAllTheWayRightHidesItAndAnInwardEdgeSwipeBringsItBack() {
        WorkPanelLayout.Choice kept = WorkPanelLayout.released(FOLD - 400, FOLD, 0.5f);
        assertFalse(kept.hidden);
        assertEquals(400f / FOLD, kept.share, 1e-6f);
        // Only at zero (within about 12 px) is it hidden: 150 dp or 10 dp are kept as they are.
        assertEquals(150f / FOLD, WorkPanelLayout.released(FOLD - 150, FOLD, 0.42f).share, 1e-6f);
        assertFalse(WorkPanelLayout.released(FOLD - 10, FOLD, 0.42f).hidden);
        WorkPanelLayout.Choice hidden = WorkPanelLayout.released(FOLD - 4, FOLD, 0.42f);
        assertTrue(hidden.hidden);
        // It comes back at the share it had.
        assertEquals(0.42f, hidden.share, 0f);
        WorkPanelLayout gone = WorkPanelLayout.of(FOLD, false, false, hidden);
        assertFalse(gone.shown);
        assertTrue(gone.hidden && gone.divider);
        // Hidden, the rail can dock again; shown, the panel takes its room.
        assertFalse(WorkPanelLayout.splitsWithRail(FOLD, false, true));
        assertTrue(WorkPanelLayout.splitsWithRail(FOLD, false, false));
        assertTrue(WorkPanelLayout.restores(-12, 2));
        assertFalse(WorkPanelLayout.restores(-4, 0));
        assertFalse(WorkPanelLayout.restores(-60, 80));
        // A reveal keeps exactly the width it was let go at, from zero up; none at all stays hidden.
        assertEquals(37f / FOLD, WorkPanelLayout.revealed(FOLD - 37, FOLD, 0.42f).share, 1e-6f);
        assertFalse(WorkPanelLayout.revealed(FOLD - 37, FOLD, 0.42f).hidden);
        assertTrue(WorkPanelLayout.revealed(FOLD - 2, FOLD, 0.42f).hidden);
        // While the finger is down the panel is exactly there, zero included.
        assertEquals(0f, WorkPanelLayout.live(FOLD, 0).share, 0f);
        assertTrue(WorkPanelLayout.live(FOLD, 0).shown);
        assertEquals(1 - 320f / FOLD, WorkPanelLayout.live(FOLD, 0.95f).share, 1e-6f);
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

    @Test public void theGripIsAVisiblePillAndTheEdgeDrawsNoHandle() throws IOException {
        String divider = new String(Files.readAllBytes(Paths.get("src/main/java/dev/herdr/android/PanelDivider.java")), StandardCharsets.UTF_8);
        assertEquals(4, PanelDivider.GRIP_W_DP);
        assertEquals(36, PanelDivider.GRIP_H_DP);
        // No handle at the edge any more (Wolf: edge swipes only): the strip draws nothing.
        assertFalse(divider.contains("edge.addView("));
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
        // The end of the text scrolls clear of the floating composer icons (review A6).
        assertEquals(64, WorkPanel.BOTTOM_DP);
        assertTrue(panel.contains("scroll.setPadding(0, 0, 0, dp(BOTTOM_DP));") && panel.contains("scroll.setClipToPadding(false);"));
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

    @Test public void thePanelFollowsTheFingerAndOnlyTheReleaseIsKept() throws IOException {
        String divider = new String(Files.readAllBytes(Paths.get("src/main/java/dev/herdr/android/PanelDivider.java")), StandardCharsets.UTF_8);
        int move = divider.indexOf("case MotionEvent.ACTION_MOVE:"), up = divider.indexOf("case MotionEvent.ACTION_UP:");
        // A move hands the live share on (once a frame); the choice waits for the lift.
        assertTrue(divider.substring(move, up).contains("liveShare(WorkPanelLayout.dragged("));
        assertFalse(divider.substring(move, up).contains("finish("));
        assertTrue(divider.substring(up, divider.indexOf("case MotionEvent.ACTION_CANCEL:")).contains("finish(WorkPanelLayout.released("));
        assertTrue(divider.contains("finish(WorkPanelLayout.revealed("));
        assertTrue(divider.contains("divider.postOnAnimation("));
        assertFalse(divider.contains("preview"));
        // The right edge's strip is excluded from Android's Back gesture.
        assertTrue(divider.contains("setSystemGestureExclusionRects"));
        String gta = TerminalSource.text();
        assertTrue(gta.contains("\"work_panel_\" + (getResources().getConfiguration().orientation"));
        assertTrue(gta.contains("root().announceForAccessibility(next.hidden ? \"Work panel hidden\" : \"Work panel restored\");"));
        assertTrue(gta.contains("@Override public void live(float share) { dragPanel(share); }"));
        assertTrue(gta.contains(": WorkPanelLayout.live(widthDp, panelLive);"));
    }
}
