package dev.herdr.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.view.MotionEvent;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;

/** A tap on a control that just slid under the finger does nothing (the undo window's end). */
public final class TapGuardTest {
    private static ControlTargets.Rect at(String id, float left, float right) {
        return new ControlTargets.Rect(id, left - 8, 600, right + 8, 650, left, right);
    }

    @Test public void talkSlidingIntoUndosPlaceIsNotTappedAtOnce() {
        TapGuard guard = new TapGuard();
        // type ─ talk ─ ✕ ─ cleared · undo ─ send: settled for a while.
        List<ControlTargets.Rect> withUndo = Arrays.asList(at("type", 10, 40), at("talk", 50, 80),
                at("clear", 90, 100), at("undo", 110, 200), at("send", 210, 250));
        guard.laid(withUndo, 0);
        assertTrue(guard.accepts("undo", 5_000));
        // The undo window ends: the line is laid again without it, and nothing else moved yet.
        guard.laid(withUndo, 5_000);
        assertTrue("an unchanged layout is no move", guard.accepts("talk", 5_010));
        // The controls shift: talk now sits where undo was.
        List<ControlTargets.Rect> after = Arrays.asList(at("type", 60, 90), at("talk", 100, 130),
                at("clear", 140, 150), at("send", 160, 200));
        guard.laid(after, 5_050);
        assertFalse("a tap aimed at undo must not start a recording", guard.accepts("talk", 5_100));
        assertFalse(guard.accepts("send", 5_349));
        assertTrue("settled again", guard.accepts("talk", 5_050 + TapGuard.SETTLE_MS));
    }

    /** The renderer's cells for {@code line}, right-aligned on row 17 of a {@code cols} grid. */
    private static List<ControlTargets.Cells> drawn(List<GhostText.Control> line, int cols) {
        List<ControlTargets.Cells> cells = new java.util.ArrayList<>();
        int col = cols - ControlLine.width(line);
        for (int i = 0; i < line.size(); i++) {
            col += i == 0 ? 1 : 3;
            int start = col;
            GhostText.Control c = line.get(i);
            for (int s = 0; s + 1 < c.spans.length; s += 2) col += c.spans[s].codePointCount(0, c.spans[s].length());
            cells.add(new ControlTargets.Cells(c.id, 17, start, col));
        }
        return cells;
    }

    private static List<ControlTargets.Rect> laidOut(ControlLine.State in, int cols) {
        float cw = 13.2f, ch = 26f;
        return ControlTargets.layout(drawn(ControlLine.controls(in, cols), cols), 0, 0, cw, ch, 17, 20 * ch,
                cols * cw, false);
    }

    private static String hit(List<ControlTargets.Rect> rects, float x, float y) {
        for (ControlTargets.Rect r : rects) if (x >= r.left && x < r.right && y >= r.top && y < r.bottom) return r.id;
        return null;
    }

    @Test public void aTapAimedAtUndoAsItsWindowEndsActsOnNothing() {
        // The real line and targets: the cover screen, a draft just cleared, a message still queued.
        int cols = 46;
        ControlLine.State in = new ControlLine.State();
        in.talks = java.util.Collections.singletonList(new GhostText.Control("talk", "talk", "dim"));
        in.clearable = true;
        in.queued = 1;
        in.undoWide = "cleared · undo";
        in.undoNarrow = "undo";
        List<ControlTargets.Rect> withUndo = laidOut(in, cols);
        ControlTargets.Rect undo = null;
        for (ControlTargets.Rect r : withUndo) if (r.id.equals("undo")) undo = r;
        float x = (undo.labelLeft + undo.labelRight) / 2, y = (undo.top + undo.bottom) / 2;
        // The window ends: undo goes, and the right-aligned line slides right under the finger
        // (on the emulator it was talk, starting a recording; here it is send, sending).
        in.undoWide = null;
        in.undoNarrow = null;
        List<ControlTargets.Rect> after = laidOut(in, cols);
        String under = hit(after, x, y);
        assertTrue("the hazard: another control is under the finger now, " + under,
                "talk".equals(under) || "send".equals(under));

        TapGuard guard = new TapGuard();
        guard.laid(withUndo, 0);
        guard.laid(after, 6_000);
        assertFalse(under + " does not act on a tap meant for undo", guard.accepts(under, 6_050));
        assertTrue("a deliberate tap once it settled works", guard.accepts(under, 6_000 + TapGuard.SETTLE_MS));
        // The same for every width the cover screen and the inner screen give.
        for (int c = 30; c <= 120; c++) {
            in.undoWide = "cleared · undo";
            in.undoNarrow = "undo";
            List<ControlTargets.Rect> before = laidOut(in, c);
            ControlTargets.Rect u = null;
            for (ControlTargets.Rect r : before) if (r.id.equals("undo")) u = r;
            if (u == null) continue;
            in.undoWide = null;
            in.undoNarrow = null;
            List<ControlTargets.Rect> later = laidOut(in, c);
            String now = hit(later, (u.labelLeft + u.labelRight) / 2, (u.top + u.bottom) / 2);
            if (now == null) continue;
            TapGuard g = new TapGuard();
            g.laid(before, 0);
            g.laid(later, 6_000);
            assertFalse(c + " cols: " + now, g.accepts(now, 6_000 + TapGuard.SETTLE_MS - 1));
        }
    }

    @Test public void aControlThatJustAppearedWaitsAndOneThatStayedDoesNot() {
        TapGuard guard = new TapGuard();
        guard.laid(Arrays.asList(at("talk", 50, 80), at("send", 210, 250)), 0);
        guard.laid(Arrays.asList(at("talk", 50, 80), at("send", 210, 250), at("undo", 110, 200)), 1_000);
        assertFalse(guard.accepts("undo", 1_100));
        assertTrue(guard.accepts("talk", 1_100));
        assertTrue(guard.accepts("undo", 1_300));
    }

    @Test public void aControlThatLeftAndCameBackIsNew() {
        TapGuard guard = new TapGuard();
        guard.laid(Arrays.asList(at("undo", 110, 200)), 0);
        guard.laid(Arrays.asList(at("talk", 50, 80)), 1_000);
        guard.laid(Arrays.asList(at("undo", 110, 200)), 2_000);
        assertFalse(guard.accepts("undo", 2_100));
    }

    @Test public void controlsShownAgainAfterBeingHiddenAreNew() {
        // ComposerBar hides the strip (a cover, a shell) and shows it again: same ids, same place.
        TapGuard guard = new TapGuard();
        List<ControlTargets.Rect> line = Arrays.asList(at("send", 210, 250));
        guard.laid(line, 0);
        guard.hidden();
        guard.laid(line, 5_000);
        assertFalse("visible again for 10 ms only", guard.accepts("send", 5_010));
        assertTrue(guard.accepts("send", 5_000 + TapGuard.SETTLE_MS));
    }

    // ---- through the composer's own listener (ComposerBar.GuardedTarget) ----------------------

    /** A target whose clicks are recorded, laid out (appeared) at {@code at}. */
    private static ComposerBar.GuardedTarget target(TapGuard guard, String id, List<String> acted) {
        return new ComposerBar.GuardedTarget(id, guard, acted::add);
    }

    /**
     * What Android does with a finger's tap on a view: DOWN and UP to its touch listener, and
     * a click only if the listener took neither.
     */
    private static void fingerTap(ComposerBar.GuardedTarget g, long now) {
        boolean taken = g.touch(MotionEvent.ACTION_DOWN, now);
        taken |= g.touch(MotionEvent.ACTION_UP, now);
        if (!taken) g.onClick(null);
    }

    @Test public void aFingersTapOnAControlThatJustAppearedDoesNothingThroughTheListener() {
        TapGuard guard = new TapGuard();
        guard.laid(Arrays.asList(at("queue", 10, 60)), 1_000);
        List<String> acted = new java.util.ArrayList<>();
        ComposerBar.GuardedTarget queue = target(guard, "queue", acted);
        fingerTap(queue, 1_010);
        assertTrue("taken on the touch path, no click", acted.isEmpty());
        fingerTap(queue, 1_000 + TapGuard.SETTLE_MS);
        assertEquals(Arrays.asList("queue"), acted);
    }

    @Test public void aNamedActivationAlwaysActsThroughTheListener() {
        // TalkBack's double tap or a performClick names its control: no touch, just the click.
        TapGuard guard = new TapGuard();
        guard.laid(Arrays.asList(at("undo", 110, 200)), 1_000);
        List<String> acted = new java.util.ArrayList<>();
        target(guard, "undo", acted).onClick(null);
        assertEquals(Arrays.asList("undo"), acted);
    }

    @Test public void aCancelledOrAbandonedGestureLeavesNothingBehind() {
        TapGuard guard = new TapGuard();
        guard.laid(Arrays.asList(at("queue", 10, 60)), 1_000);
        List<String> acted = new java.util.ArrayList<>();
        ComposerBar.GuardedTarget queue = target(guard, "queue", acted);
        // The strip takes a drag over: the target gets DOWN, then CANCEL.
        assertTrue(queue.touch(MotionEvent.ACTION_DOWN, 1_010));
        assertTrue(queue.touch(MotionEvent.ACTION_CANCEL, 1_015));
        queue.onClick(null); // then a named activation
        assertEquals(Arrays.asList("queue"), acted);
        // An UP that makes no click (a long press, say), then a named activation.
        queue.touch(MotionEvent.ACTION_DOWN, 1_020);
        queue.touch(MotionEvent.ACTION_UP, 1_030);
        queue.onClick(null);
        assertEquals(Arrays.asList("queue", "queue"), acted);
        // And the next finger on the settled control is decided afresh.
        fingerTap(queue, 1_000 + TapGuard.SETTLE_MS);
        assertEquals(3, acted.size());
    }

    @Test public void eachFingerIsDecidedOnItsOwnTarget() {
        // Split multi-touch: each child gets its own DOWN; one must not decide the other.
        TapGuard guard = new TapGuard();
        guard.laid(Arrays.asList(at("type", 300, 340)), 0);
        guard.laid(Arrays.asList(at("type", 300, 340), at("queue", 10, 60)), 1_000);
        List<String> acted = new java.util.ArrayList<>();
        ComposerBar.GuardedTarget queue = target(guard, "queue", acted), type = target(guard, "type", acted);
        boolean queueTaken = queue.touch(MotionEvent.ACTION_DOWN, 1_010);
        boolean typeTaken = type.touch(MotionEvent.ACTION_DOWN, 1_011);
        assertTrue("queue just appeared", queueTaken);
        assertFalse("type had settled", typeTaken);
        type.touch(MotionEvent.ACTION_CANCEL, 1_012); // the other finger's gesture ends
        queueTaken |= queue.touch(MotionEvent.ACTION_UP, 1_020);
        if (!queueTaken) queue.onClick(null);
        assertTrue("the first finger's tap is still guarded", acted.isEmpty());
    }

    @Test public void aTapAimedAtUndoAsItsWindowEndsDoesNothingThroughTheListener() {
        // The real line and targets at every width, through the listener the composer installs.
        for (int c = 30; c <= 120; c++) {
            ControlLine.State in = new ControlLine.State();
            in.talks = java.util.Collections.singletonList(new GhostText.Control("talk", "talk", "dim"));
            in.clearable = true;
            in.queued = 1;
            in.undoWide = "cleared · undo";
            in.undoNarrow = "undo";
            List<ControlTargets.Rect> before = laidOut(in, c);
            ControlTargets.Rect u = null;
            for (ControlTargets.Rect r : before) if (r.id.equals("undo")) u = r;
            if (u == null) continue;
            in.undoWide = null;
            in.undoNarrow = null;
            List<ControlTargets.Rect> later = laidOut(in, c);
            String under = hit(later, (u.labelLeft + u.labelRight) / 2, (u.top + u.bottom) / 2);
            if (under == null) continue;
            TapGuard guard = new TapGuard();
            guard.laid(before, 0);
            guard.laid(later, 6_000);
            List<String> acted = new java.util.ArrayList<>();
            fingerTap(target(guard, under, acted), 6_050);
            assertTrue(c + " cols: " + under + " acted on a tap meant for undo", acted.isEmpty());
        }
    }

    @Test public void anIdNeverLaidOutIsNotBlocked() {
        // Rows and actions the guard was never told about act as before.
        assertTrue(new TapGuard().accepts("row-edit", 0));
    }
}
