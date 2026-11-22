package dev.herdr.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import org.junit.Test;

/** The cover's Work panel (Wolf, 11-22): a full page pulled in from the right edge, settled full or gone. */
public final class CoverPageTest {
    private static final int COVER = 412;

    private static String source(String name) throws IOException {
        return new String(Files.readAllBytes(Paths.get("src/main/java/dev/herdr/android/" + name + ".java")), StandardCharsets.UTF_8);
    }

    @Test public void thePageFollowsTheFingerAndSettlesFullOrGone() {
        // Pulled in from the right edge: the page's left edge is under the finger.
        assertEquals(0f, WorkPanelLayout.coverRevealed(COVER, COVER), 0f);
        assertEquals(0.25f, WorkPanelLayout.coverRevealed(COVER * 0.75f, COVER), 1e-6f);
        assertEquals(1f, WorkPanelLayout.coverRevealed(-20, COVER), 0f);
        // Dragged back toward the right.
        assertEquals(0.75f, WorkPanelLayout.coverDismissing(COVER * 0.25f, COVER), 1e-6f);
        assertEquals(1f, WorkPanelLayout.coverDismissing(-30, COVER), 0f);
        // Released slowly: the nearer side; flung: the fling's side, wherever it is.
        assertTrue(WorkPanelLayout.coverOpens(0.6f, 0));
        assertFalse(WorkPanelLayout.coverOpens(0.4f, 0));
        assertTrue(WorkPanelLayout.coverOpens(0.2f, -800));
        assertFalse(WorkPanelLayout.coverOpens(0.9f, 800));
        // Its offset while partly in, in px.
        assertEquals(750f, WorkPanelLayout.coverOffset(0.25f, 1000), 1e-3f);
        assertEquals(0f, WorkPanelLayout.coverOffset(1.4f, 1000), 0f);
        // The unfolded split is untouched: still finger-tracking, no full-page snap.
        assertEquals(0.3f, WorkPanelLayout.live(949, 0.3f).share, 1e-6f);
        assertFalse(WorkPanelLayout.of(949, false, false).overlay);
    }

    @Test public void theEdgeOpensItOnTheCoverAndThePageTakesOnlyClearlySidewaysDrags() throws IOException {
        String divider = source("PanelDivider");
        // On the cover the strip is there while the page is away; the pull reports how much shows.
        assertTrue(divider.contains("layout.overlay && (!layout.shown || revealing)"));
        assertTrue(divider.contains("liveShare(cover ? WorkPanelLayout.coverRevealed(xDp, windowDp)"));
        assertTrue(divider.contains("WorkPanelLayout.coverOpens(WorkPanelLayout.coverRevealed(xDp, windowDp), vx)"));
        assertTrue(divider.contains("setSystemGestureExclusionRects"));
        String panel = source("WorkPanel");
        // The page's own dismiss: cover only, rightward past the slop, never over a selection.
        assertTrue(panel.contains("if (!onCover || !shown) return false;"));
        assertTrue(panel.contains("dx > slop && dx > Math.abs(dy) * 1.5f && !SelectableText.anySelected(body)"));
        assertTrue(panel.contains("getScaledTouchSlop()"));
        assertTrue(panel.contains("host.coverSettled(WorkPanelLayout.coverOpens(visible, speed.getXVelocity() / density()));"));
        // Folding keeps the tab's scroll.
        assertTrue(panel.contains("if (!on) scrollOf[tab.ordinal()] = scroll.getScrollY();"));
        String gta = source("GpuiTerminalActivity");
        // The viewer's dismiss owns the screen while it shows.
        assertTrue(gta.contains("panelDivider.place(filePager == null || !filePager.showingFile(), layout, choice,"));
        assertTrue(gta.contains("float offset = coverMoving ? WorkPanelLayout.coverOffset(coverLive, rootWidth) : 0;"));
        assertTrue(gta.contains("page.animate().translationX(open ? 0 : root().getWidth())"));
    }
}
