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
                0, 0, CW, CH, 40, 43 * CH, 84 * CW);
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
        assertTrue(talk.width() >= ControlTargets.TALK_MIN_DP - 0.01f);
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
}
