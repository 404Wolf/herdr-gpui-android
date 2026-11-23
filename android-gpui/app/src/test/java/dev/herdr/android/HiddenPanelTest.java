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
 * The missing panel of 11-23: unfolded, a divider drag let go at the edge
 * left it hidden ('hidden:S'), and the only way back was a 20x200 dp strip
 * at mid-height that Wolf couldn't find. Hiding at zero stays (his ask);
 * the way back is now always there.
 */
public final class HiddenPanelTest {
    private static String source(String name) throws IOException {
        return new String(Files.readAllBytes(Paths.get("src/main/java/dev/herdr/android/" + name + ".java")), StandardCharsets.UTF_8);
    }

    @Test public void theMenuBringsAHiddenPanelBackAtItsWidthAndHidesAShownOne() {
        WorkPanelLayout.Choice hidden = new WorkPanelLayout.Choice(0.38f, true);
        WorkPanelLayout.Choice back = WorkPanelLayout.toggled(hidden);
        assertFalse(back.hidden);
        assertEquals(0.38f, back.share, 0f);
        WorkPanelLayout.Choice again = WorkPanelLayout.toggled(back);
        assertTrue(again.hidden);
        assertEquals(0.38f, again.share, 0f);
        // The default shown panel hides and comes back as the default (the hinge).
        assertTrue(WorkPanelLayout.toggled(null).hidden);
        assertEquals(0.5f, WorkPanelLayout.of(949, false, false, WorkPanelLayout.toggled(WorkPanelLayout.toggled(null))).share, 0f);
    }

    @Test public void theMenuOffersItUnfoldedToo() throws IOException {
        String gta = source("GpuiTerminalActivity");
        int at = gta.indexOf("menu.getMenu().add(0, 9, MoreMenu.USAGE,");
        String offer = gta.substring(gta.lastIndexOf("if (", at), at);
        assertFalse("not cover-only any more", offer.contains("compactWindow()"));
        assertTrue(gta.contains("else choosePanel(WorkPanelLayout.toggled(panelChoice()));"));
        assertTrue(gta.contains("boolean panelShown = cover ? workPanelOpen : !panelChoice().hidden;"));
    }

    @Test public void theRestoringStripSitsInTheLowerHalfAboveTheCluster() throws IOException {
        // Fold unfolded: 984 dp tall; the 200 dp strip centred at three quarters down.
        assertEquals(146, WorkPanelLayout.edgeBottomDp(984));
        int top = 984 - 146 - WorkPanelLayout.EDGE_TALL_DP;
        assertTrue("in the lower half", top >= 984 / 2);
        // Keyboard up (a short window) or unknown: just above the icon cluster.
        assertEquals(WorkPanelLayout.EDGE_CLEAR_DP, WorkPanelLayout.edgeBottomDp(400));
        assertEquals(WorkPanelLayout.EDGE_CLEAR_DP, WorkPanelLayout.edgeBottomDp(0));
        assertEquals(200, WorkPanelLayout.EDGE_TALL_DP);
        assertTrue(source("PanelDivider").contains("at.gravity = Gravity.END | Gravity.BOTTOM;"));
    }

    @Test public void aPanelLeftAFewDpWideStillHasAWholeDividerOutOfBacksEdge() {
        int window = 2076, touch = 53;
        // 6 dp of panel: the divider is pulled back on screen, whole.
        int left = WorkPanelLayout.dividerLeft(window - 13, window, touch);
        assertEquals(window - touch, left);
        assertTrue(WorkPanelLayout.dividerAtEdge(left, window, touch, 70));
        // At the hinge it is centred on the edge and needs no exclusion.
        int mid = WorkPanelLayout.dividerLeft(window / 2, window, touch);
        assertEquals(window / 2 - touch / 2, mid);
        assertFalse(WorkPanelLayout.dividerAtEdge(mid, window, touch, 70));
    }
}
