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
 * Wolf's fold-transition spec (11-23, via t2kg): folding closes Overview and
 * the cover shows the terminal; on the cover Overview is a full page only
 * after an explicit reveal, else hidden; unfolding with the cover's Overview
 * open makes it the inner split at once, at the saved inner width; on the
 * inner display only the split width applies.
 */
public final class FoldTransitionTest {
    private static final int UNFOLDED = 1054, COVER = 548;

    @Test public void foldingAnOpenSplitClosesItAndTheCoverShowsTheTerminal() {
        WorkPanelLayout.Choice inner = new WorkPanelLayout.Choice(0.4f, false);
        // The cover's page had been opened before an earlier unfold: folding never brings it back.
        WorkPanelLayout.Fold folded = WorkPanelLayout.crossed(false, true, true, inner);
        assertFalse(folded.coverOpen);
        assertFalse(WorkPanelLayout.of(COVER, true, false, folded.coverOpen, folded.inner).shown);
        // The inner split is kept for the next unfold, as it was.
        assertEquals(0.4f, folded.inner.share, 0f);
        assertFalse(folded.inner.hidden);
    }

    @Test public void unfoldingTheCoversOpenPageGivesTheSplitAtTheSavedInnerWidth() {
        // Hidden on the inner display before: the open page still unfolds into the split.
        WorkPanelLayout.Choice inner = new WorkPanelLayout.Choice(0.37f, true);
        WorkPanelLayout.Fold unfolded = WorkPanelLayout.crossed(true, false, true, inner);
        assertFalse("the cover's page is closed behind it", unfolded.coverOpen);
        WorkPanelLayout split = WorkPanelLayout.of(UNFOLDED, false, false, unfolded.coverOpen, unfolded.inner);
        assertTrue(split.shown && split.divider);
        assertFalse("never full-screen on the inner display", split.overlay);
        assertEquals(0.37f, split.share, 1e-6f);
        // The cover's page closed: the inner display keeps what it had.
        assertTrue(WorkPanelLayout.crossed(true, false, false, inner).inner.hidden);
    }

    @Test public void foldAndUnfoldCyclesWithoutARestart() {
        WorkPanelLayout.Choice inner = new WorkPanelLayout.Choice(0.42f, false);
        boolean coverOpen = false;
        boolean cover = false;
        for (int i = 0; i < 4; i++) {
            // Fold: closed on the cover.
            WorkPanelLayout.Fold f = WorkPanelLayout.crossed(cover, true, coverOpen, inner);
            cover = true; coverOpen = f.coverOpen; inner = f.inner;
            assertFalse(WorkPanelLayout.of(COVER, true, false, coverOpen, inner).shown);
            // Wolf pulls the page in on the cover every other round.
            if (i % 2 == 0) coverOpen = true;
            // Unfold: the split, at the inner width, never the page.
            f = WorkPanelLayout.crossed(cover, false, coverOpen, inner);
            cover = false; coverOpen = f.coverOpen; inner = f.inner;
            WorkPanelLayout split = WorkPanelLayout.of(UNFOLDED, false, false, coverOpen, inner);
            assertFalse(split.overlay);
            assertTrue(split.shown);
            assertEquals(0.42f, split.share, 1e-6f);
        }
        // No posture change: nothing changes.
        WorkPanelLayout.Fold same = WorkPanelLayout.crossed(true, true, true, inner);
        assertTrue(same.coverOpen);
    }

    @Test public void theActivityAppliesTheTransitionOnEveryPostureChange() throws IOException {
        String gta = new String(Files.readAllBytes(Paths.get("src/main/java/dev/herdr/android/GpuiTerminalActivity.java")),
                StandardCharsets.UTF_8);
        int at = gta.indexOf("private void applyWorkPanel()");
        String body = gta.substring(at, gta.indexOf("\n    }\n", at));
        assertTrue(body.contains("WorkPanelLayout.crossed("));
    }
}
