package dev.herdr.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import org.junit.Test;

/**
 * The fold regressions of 11-23 17:24Z. (1) A right-to-left swipe from the
 * right edge was Android's Back, not the panel: the Pixel Fold keeps its edges
 * for Back whatever a view excludes (d62d747), and the activity's edge
 * callback was off whenever the rail was docked, the very state a hidden
 * panel leaves unfolded. (2) Unfolded, the panel came in as the cover's full
 * page: cover or split was decided by the window's width, so an unfolded
 * window narrower than 640 dp (split screen, a freeform window) got the
 * cover's page. Cover or split is the display's now, not the window's.
 */
public final class FoldEdgeTest {
    /** The Fold at Wolf's display size (the 11-23 dump: a 20 dp strip is 39 px, so about 1.97 px/dp). */
    private static final int UNFOLDED = 1054, COVER = 548, HALF = 527;

    private static String source(String name) throws IOException {
        return new String(Files.readAllBytes(Paths.get("src/main/java/dev/herdr/android/" + name + ".java")), StandardCharsets.UTF_8);
    }

    @Test public void anUnfoldedWindowNarrowerThanTheCoverStillSplits() {
        // The unfolded display, its window halved by split screen: still the split, never the page.
        assertFalse(WorkPanelLayout.onCover(HALF, UNFOLDED));
        WorkPanelLayout hidden = WorkPanelLayout.of(HALF, false, false, false, new WorkPanelLayout.Choice(0.3f, true));
        assertFalse(hidden.overlay);
        assertTrue("a hidden split, revealed from the edge", hidden.divider && hidden.hidden);
        WorkPanelLayout shown = WorkPanelLayout.of(HALF, false, false, false, new WorkPanelLayout.Choice(0.3f, false));
        assertFalse(shown.overlay);
        assertEquals(0.3f, shown.share, 1e-6f);
        // Revealing grows it from zero with the finger.
        assertEquals(0f, WorkPanelLayout.live(HALF, WorkPanelLayout.dragged(HALF, HALF)).share, 0f);
        assertEquals(0.1f, WorkPanelLayout.live(HALF, 0.1f).share, 1e-6f);
        // The whole unfolded window splits too.
        assertFalse(WorkPanelLayout.onCover(UNFOLDED, UNFOLDED));
    }

    @Test public void theCoverIsTheCoverDisplayAlone() {
        assertTrue(WorkPanelLayout.onCover(COVER, COVER));
        assertTrue(WorkPanelLayout.of(COVER, true, false, true, null).overlay);
        // The display unknown (before API 30): the window's width decides, as before.
        assertTrue(WorkPanelLayout.onCover(COVER, 0));
        assertFalse(WorkPanelLayout.onCover(UNFOLDED, 0));
    }

    @Test public void theActivityAsksTheDisplayNotTheWindow() throws IOException {
        String gta = source("GpuiTerminalActivity");
        assertFalse("no cover check by window width", gta.contains("screenWidthDp < WorkPanelLayout.COVER_DP"));
        assertTrue(gta.contains("getMaximumWindowMetrics()"));
        assertTrue(gta.contains("WorkPanelLayout.onCover("));
    }

    @Test public void theRightEdgesBackSwipeInTheLowerHalfRevealsThePanel() {
        int tall = 984;
        assertTrue("lower half", WorkPanelLayout.takesEdgeBack(700, tall));
        assertTrue(WorkPanelLayout.takesEdgeBack(tall / 2f, tall));
        assertFalse("upper half: Back", WorkPanelLayout.takesEdgeBack(300, tall));
        assertFalse("over the icon cluster: Back", WorkPanelLayout.takesEdgeBack(tall - 40, tall));
        assertFalse(WorkPanelLayout.takesEdgeBack(700, 0));
    }

    @Test public void theEdgeCallbackListensForTheRightEdgeEvenWithTheRailDocked() throws IOException {
        String gta = source("GpuiTerminalActivity");
        assertTrue(gta.contains("BackEvent.EDGE_RIGHT"));
        assertTrue("follows the finger", gta.contains("onBackProgressed("));
        // Off only while the drawer is open (BackOwner closes it); a docked rail keeps it on.
        assertFalse(gta.contains("setEdgeBack(!sidebarOpen);"));
        assertTrue(gta.contains("setEdgeBack(!(drawer && sidebarOpen));"));
    }

    @Test public void aDividerDraggedOffTheEdgeComesBackAtTheWidthItHadBefore() throws IOException {
        // The share the release keeps is the one at the touch's start, not the live sliver.
        String divider = source("PanelDivider");
        assertTrue(divider.contains("WorkPanelLayout.released(WorkPanelLayout.snapped(xDp, windowDp), windowDp, before[0])"));
        assertTrue(divider.contains("WorkPanelLayout.revealed(WorkPanelLayout.snapped(xDp, windowDp), windowDp, before[0])"));
        WorkPanelLayout.Choice hidden = WorkPanelLayout.released(1052, UNFOLDED, 0.42f);
        assertTrue(hidden.hidden);
        assertEquals(0.42f, WorkPanelLayout.toggled(hidden).share, 0f);
    }
    @Test public void foldingAndUnfoldingDerivesTheModeAgainEveryTime() {
        // One session: cover, unfolded, cover, unfolded, the panel open and hidden. The layout
        // keeps no mode of its own: each posture gets its own answer from the same inputs.
        for (boolean hidden : new boolean[] {false, true}) {
            WorkPanelLayout.Choice choice = new WorkPanelLayout.Choice(0.4f, hidden);
            for (int round = 0; round < 2; round++) {
                WorkPanelLayout cover = WorkPanelLayout.of(COVER, WorkPanelLayout.onCover(COVER, COVER), false, true, choice);
                assertTrue(cover.overlay);
                WorkPanelLayout open = WorkPanelLayout.of(UNFOLDED, WorkPanelLayout.onCover(UNFOLDED, UNFOLDED), false, true, choice);
                assertFalse("the cover's open page never carries into the split", open.overlay);
                assertEquals(!hidden, open.shown);
                assertTrue(open.divider);
            }
        }
    }

    @Test public void aPostureChangeDropsTheOldPosturesGestureAndLaysOutAgain() throws IOException {
        String gta = source("GpuiTerminalActivity");
        int at = gta.indexOf("public void onConfigurationChanged(");
        String config = gta.substring(at, gta.indexOf("\n    }\n", at));
        assertTrue("every fold, unfold or turn", config.contains("postureChanged();"));
        int p = gta.indexOf("private void postureChanged()");
        assertTrue(p > 0);
        String body = gta.substring(p, gta.indexOf("\n    }\n", p));
        for (String reset : new String[] {"panelLive = Float.NaN;", "coverLive = Float.NaN;", "edgeRevealing = false;",
                "panelDivider.cancelGesture();", "addOnGlobalLayoutListener"}) {
            assertTrue(reset, body.contains(reset));
        }
        assertTrue(source("PanelDivider").contains("void cancelGesture()"));
        // The emulator's qualification reads each posture's inputs from the log.
        assertTrue(gta.contains("\"work panel: \""));
    }
    @Test public void theCoverAndTheUnfoldedScreenKeepTheirOwnSplit() throws IOException {
        // Cover and unfolded portrait shared 'work_panel_port': a state kept on the cover could
        // reach the unfolded screen. Each posture has its own key now; the old keys stay the
        // unfolded screen's, so Wolf's saved width carries over there and only there.
        assertEquals("work_panel_port", WorkPanelLayout.choiceKey(false, false));
        assertEquals("work_panel_land", WorkPanelLayout.choiceKey(false, true));
        assertEquals("work_panel_cover_port", WorkPanelLayout.choiceKey(true, false));
        assertEquals("work_panel_cover_land", WorkPanelLayout.choiceKey(true, true));
        java.util.Map<String, String> prefs = new java.util.HashMap<>();
        prefs.put("work_panel_port", "0.400");
        prefs.put(WorkPanelLayout.choiceKey(true, false), "hidden:0.000");
        // The full page open on the cover...
        WorkPanelLayout cover = WorkPanelLayout.of(COVER, true, false, true,
                WorkPanelLayout.Choice.parse(prefs.get(WorkPanelLayout.choiceKey(true, false))));
        assertTrue(cover.overlay && cover.shown);
        // ...then unfolded: a normal split at the unfolded screen's own saved width.
        WorkPanelLayout open = WorkPanelLayout.of(UNFOLDED, false, false, true,
                WorkPanelLayout.Choice.parse(prefs.get(WorkPanelLayout.choiceKey(false, false))));
        assertFalse(open.overlay);
        assertTrue(open.shown && open.divider);
        assertEquals(0.4f, open.share, 1e-6f);
        assertTrue(source("GpuiTerminalActivity").contains("WorkPanelLayout.choiceKey(coverPosture(),"));
    }
}
