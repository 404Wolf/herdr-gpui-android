package dev.herdr.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.List;
import org.junit.Test;

/** Touch targets over the words drawn in the prompt's rule. */
public final class ControlTargetsTest {
    // Wolf's cover screen: 84 columns of 5.9 dp, rows of 11.9 dp; the prompt's
    // caret on row 40, "type ─ talk ─ send ──" in the rule on row 41.
    private static final float CW = 5.9f, CH = 11.9f;

    private static List<ControlTargets.Rect> targets() {
        return ControlTargets.layout(Arrays.asList(
                new ControlTargets.Cells("type", 41, 64, 68),
                new ControlTargets.Cells("talk", 41, 71, 75),
                new ControlTargets.Cells("send", 41, 78, 82)),
                0, 0, CW, CH, 40, 43 * CH, 84 * CW, false);
    }

    private static ControlTargets.Rect find(List<ControlTargets.Rect> rects, String id) {
        for (ControlTargets.Rect r : rects) if (r.id.equals(id)) return r;
        throw new AssertionError(id);
    }

    @Test public void talkIsTheWidestAndEveryTargetIsAtLeastATouchSquare() {
        List<ControlTargets.Rect> rects = targets();
        ControlTargets.Rect talk = find(rects, "talk");
        for (ControlTargets.Rect r : rects) {
            assertTrue(r.id + " height " + r.height(), r.height() >= ControlTargets.MIN_DP - 0.01f);
            if (r != talk) assertTrue(r.id + " narrower than talk", r.width() < talk.width());
        }
        // On these narrow cells "talk" would want 88 dp, but only the gaps up to
        // its neighbours' words are free: it takes all of them and no more.
        assertEquals(68 * CW, talk.left, 0.01f);
        assertEquals(78 * CW, talk.right, 0.01f);
    }

    @Test public void targetsNeverOverlapAndNeverReachThePromptsText() {
        List<ControlTargets.Rect> rects = targets();
        for (int i = 1; i < rects.size(); i++) {
            assertTrue(rects.get(i - 1).right <= rects.get(i).left + 0.01f);
        }
        // The highest a target goes is the prompt's own rule (row 39): the
        // program's text above stays free to scroll and select.
        for (ControlTargets.Rect r : rects) assertTrue(r.id, r.top >= 39 * CH - 0.01f);
        // Nothing past the grid's right edge.
        for (ControlTargets.Rect r : rects) assertTrue(r.right <= 84 * CW + 0.01f);
        assertEquals(43 * CH, find(rects, "send").bottom, 0.01f);
    }

    /** While recording the host draws " type ─ ● rec 0:05 · tap to stop ──". */
    private static List<ControlTargets.Rect> recording() {
        return ControlTargets.layout(Arrays.asList(
                new ControlTargets.Cells("type", 41, 53, 57),
                new ControlTargets.Cells("talk", 41, 60, 81)),
                0, 0, CW, CH, 40, 43 * CH, 84 * CW, true);
    }

    @Test public void whileRecordingTheStopTakesTheWholeBottomRightBlock() {
        List<ControlTargets.Rect> rects = recording();
        ControlTargets.Rect stop = find(rects, "talk");
        // To the grid's right edge and down to the surface's bottom.
        assertEquals(84 * CW, stop.right, 0.01f);
        assertEquals(43 * CH, stop.bottom, 0.01f);
        assertTrue(stop.width() >= ControlTargets.STOP_MIN_WIDTH_DP - 0.01f);
        assertTrue(stop.height() >= ControlTargets.STOP_MIN_HEIGHT_DP - 0.01f);
        // Far bigger than "talk" is at rest.
        ControlTargets.Rect talk = find(targets(), "talk");
        assertTrue(stop.width() * stop.height() > 3 * talk.width() * talk.height());
        // "type" is still there, left of it, never under it.
        ControlTargets.Rect type = find(rects, "type");
        assertTrue(type.right <= stop.left + 0.01f);
        assertTrue(type.width() > 0);
    }

    @Test public void theStopIsBigEvenWhenItsWordsAreShort() {
        // " ● stop ──" on a tiny grid: the words are 6 cells, the stop is not.
        List<ControlTargets.Rect> rects = ControlTargets.layout(Arrays.asList(
                new ControlTargets.Cells("talk", 41, 75, 81)),
                0, 0, CW, CH, 40, 43 * CH, 90 * CW, true);
        ControlTargets.Rect stop = find(rects, "talk");
        assertEquals(90 * CW - ControlTargets.STOP_MIN_WIDTH_DP, stop.left, 0.01f);
        assertEquals(90 * CW, stop.right, 0.01f);
        assertEquals(43 * CH - ControlTargets.STOP_MIN_HEIGHT_DP, stop.top, 0.01f);
    }

    @Test public void theStopGrowsOnlyIntoFreeSpaceNeverOverAnotherControlsWords() {
        // A stale "send" to the stop's right keeps its words; the stop stops at them.
        List<ControlTargets.Rect> rects = ControlTargets.layout(Arrays.asList(
                new ControlTargets.Cells("talk", 41, 75, 81),
                new ControlTargets.Cells("send", 41, 84, 88)),
                0, 0, CW, CH, 40, 43 * CH, 90 * CW, true);
        ControlTargets.Rect stop = find(rects, "talk"), send = find(rects, "send");
        assertEquals(84 * CW, stop.right, 0.01f);
        assertEquals(84 * CW, send.left, 0.01f);
        assertTrue(send.right >= 88 * CW - 0.01f);
    }

    /**
     * The cells the renderer gives {@code controls} on a {@code cols}-wide row:
     * the line " a ─ b ─ c ──" set against the right edge (gpui-rio ghost.rs).
     */
    private static List<ControlTargets.Cells> drawn(List<GhostText.Control> controls, int cols, int row) {
        List<ControlTargets.Cells> cells = new java.util.ArrayList<>();
        int col = cols - ComposerBar.lineWidth(controls);
        for (int i = 0; i < controls.size(); i++) {
            col += i == 0 ? 1 : 3;
            int start = col;
            GhostText.Control c = controls.get(i);
            for (int s = 0; s + 1 < c.spans.length; s += 2) col += c.spans[s].codePointCount(0, c.spans[s].length());
            cells.add(new ControlTargets.Cells(c.id, row, start, col));
        }
        return cells;
    }

    private static List<GhostText.Control> words(String... talks) {
        List<GhostText.Control> out = new java.util.ArrayList<>();
        for (String t : talks) out.add(new GhostText.Control("talk", t, "info"));
        return out;
    }

    @Test public void everyTargetCoversItsWordsAndNoOtherControlsInEveryStateAndSize() {
        List<List<GhostText.Control>> states = Arrays.asList(
                words("talk"),
                words("connecting…", "connecting…", "…"),
                words("● rec 0:05 · tap to stop", "● 0:05 stop", "● stop"),
                words("desktop offline · rec 0:05 · tap to stop", "● offline 0:05 stop", "● stop"),
                words("transcribing…", "transcribing…", "…"),
                java.util.Collections.<GhostText.Control>emptyList());
        Outbox.Message queued = new Outbox.Message("a", "h", "t1", "b", "x", 1, Outbox.State.QUEUED, false);
        // Cell widths from compact text to pinch zoom; grids from the cover screen at
        // its largest text to the inner screen.
        for (float cw : new float[] {5.9f, 8f, 10f, 13.2f, 18.6f}) {
            for (int cols = 20; cols <= 110; cols++) {
                float maxRight = cols * cw;
                for (List<GhostText.Control> talks : states) {
                    for (boolean typing : new boolean[] {false, true}) {
                        for (boolean recording : new boolean[] {false, true}) {
                            List<GhostText.Control> line = ComposerBar.controls(typing, talks,
                                    Arrays.asList(queued, queued), cols, recording);
                            if (ComposerBar.lineWidth(line) > cols) continue; // the renderer's own fallback
                            List<ControlTargets.Cells> cells = drawn(line, cols, 41);
                            List<ControlTargets.Rect> rects = ControlTargets.layout(cells, 0, 0, cw, 26f,
                                    40, 43 * 26f, maxRight, recording);
                            String where = cw + "dp " + cols + " cols " + talks + " typing=" + typing
                                    + " recording=" + recording;
                            assertEquals(where, cells.size(), rects.size());
                            for (ControlTargets.Cells c : cells) {
                                ControlTargets.Rect r = find(rects, c.id);
                                // Its own words, whole.
                                assertTrue(where + " " + c.id, r.left <= c.start * cw + 0.01f);
                                assertTrue(where + " " + c.id, r.right >= c.end * cw - 0.01f);
                                assertTrue(where + " " + c.id, r.top <= 41 * 26f && r.bottom >= 42 * 26f);
                                assertTrue(where + " " + c.id, r.left >= -0.01f && r.right <= maxRight + 0.01f);
                                // Nobody else's.
                                for (ControlTargets.Cells o : cells) {
                                    if (o == c) continue;
                                    boolean overlaps = r.left < o.end * cw - 0.01f && r.right > o.start * cw + 0.01f;
                                    assertTrue(where + " " + c.id + " covers " + o.id, !overlaps);
                                }
                            }
                            for (int i = 1; i < rects.size(); i++) {
                                assertTrue(where, rects.get(i - 1).right <= rects.get(i).left + 0.01f);
                            }
                        }
                    }
                }
            }
        }
    }

    @Test public void hideStaysTappableBesideTheConnectingStopAtPinchZoom() {
        // The review's case: typing while connecting, 31 columns of 13.2 dp
        // (the cover screen at 22 pt): " ↓ hide ─ connecting… ──".
        int cols = 31;
        float cw = 13.2f;
        List<GhostText.Control> line = ComposerBar.controls(true, words("connecting…", "connecting…", "…"),
                java.util.Collections.emptyList(), cols, true);
        List<ControlTargets.Cells> cells = drawn(line, cols, 17);
        List<ControlTargets.Rect> rects = ControlTargets.layout(cells, 0, 0, cw, 26f, 17, 20 * 26f, cols * cw, true);
        ControlTargets.Cells hideCells = cells.get(0);
        assertEquals("hide", hideCells.id);
        ControlTargets.Rect hide = find(rects, "hide"), stop = find(rects, "talk");
        // The whole of "↓ hide" (x 237.6 to 316.8) hides the keyboard; the stop starts after it.
        assertEquals(237.6f, hideCells.start * cw, 0.01f);
        assertEquals(316.8f, hideCells.end * cw, 0.01f);
        assertTrue(hide.right >= 316.8f - 0.01f);
        assertTrue(stop.left >= 316.8f - 0.01f);
        assertEquals(cols * cw, stop.right, 0.01f);
    }

    @Test public void theStopNeverLeavesTheGrid() {
        // A grid narrower and shorter than the stop's least size.
        List<ControlTargets.Rect> rects = ControlTargets.layout(Arrays.asList(
                new ControlTargets.Cells("talk", 3, 2, 8)),
                10, 20, CW, CH, 3, 20 + 5 * CH, 10 + 12 * CW, true);
        ControlTargets.Rect stop = find(rects, "talk");
        assertEquals(10, stop.left, 0.01f);
        assertEquals(20, stop.top, 0.01f);
        assertEquals(10 + 12 * CW, stop.right, 0.01f);
    }
}
