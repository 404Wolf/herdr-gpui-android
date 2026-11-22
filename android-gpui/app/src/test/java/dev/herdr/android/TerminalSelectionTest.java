package dev.herdr.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Who owns a terminal touch while selecting (factory-9zz.4): pure, so tested here. */
public final class TerminalSelectionTest {
    private static TerminalSelection.Gesture gesture() {
        return new TerminalSelection.Gesture(500, 10);
    }

    @Test public void aStillFingerHeldLongEnoughSelectsAndAnythingElseStaysTheRenderers() {
        TerminalSelection.Gesture g = gesture();
        g.down(100, 100, 0, TerminalSelection.Gesture.NONE);
        assertFalse("before the long press the renderer has it", g.owned);
        assertFalse(g.longPress(499));
        assertTrue(g.longPress(500));
        TerminalSelection.Gesture scroll = gesture();
        scroll.down(100, 100, 0, TerminalSelection.Gesture.NONE);
        assertTrue(scroll.move(100, 140));
        assertFalse("a finger that moved is a scroll", scroll.longPress(600));
        TerminalSelection.Gesture pinch = gesture();
        pinch.down(100, 100, 0, TerminalSelection.Gesture.NONE);
        pinch.secondFinger();
        assertFalse(pinch.longPress(600));
        TerminalSelection.Gesture jitter = gesture();
        jitter.down(100, 100, 0, TerminalSelection.Gesture.NONE);
        assertFalse("within the slop it is still a press", jitter.move(105, 104));
        assertTrue(jitter.longPress(600));
    }

    @Test public void whileShownEveryTouchIsOursAndOnlyATapOutsideClears() {
        TerminalSelection.Gesture g = gesture();
        g.shown = true;
        g.down(10, 10, 0, TerminalSelection.Gesture.HANDLE_END);
        assertTrue("a handle drag never reaches the program", g.owned);
        assertEquals(TerminalSelection.Gesture.HANDLE_END, g.dragging);
        g.move(80, 10);
        assertFalse("a handle drag keeps the selection", g.liftClears());
        g.down(300, 300, 0, TerminalSelection.Gesture.NONE);
        assertTrue(g.owned);
        assertTrue("a tap outside clears", g.liftClears());
        g.down(300, 300, 0, TerminalSelection.Gesture.NONE);
        g.move(300, 400);
        assertFalse("a drag outside keeps it", g.liftClears());
        assertFalse("no second long press while one shows", g.longPress(10_000));
    }

    @Test public void theRenderersReportIsReadAndBadOrEmptyMeansNone() {
        TerminalSelection.Places p = TerminalSelection.Places.parse(
                "{\"start\":{\"x\":10,\"y\":40},\"end\":{\"x\":90.5,\"y\":60},\"lineHeight\":20,\"text\":\"hello\\nworld\"}");
        assertTrue(p.startShown && p.endShown);
        assertEquals(90.5f, p.endX, 0.001f);
        assertEquals("hello\nworld", p.text);
        TerminalSelection.Places scrolledOff = TerminalSelection.Places.parse("{\"start\":null,\"end\":{\"x\":1,\"y\":2},\"lineHeight\":20,\"text\":\"x\"}");
        assertFalse(scrolledOff.startShown);
        assertNull(TerminalSelection.Places.parse("{}"));
        assertNull(TerminalSelection.Places.parse("not json"));
    }

    @Test public void theActivityAsksSelectionFirstAndNeverForwardsItsTouches() throws Exception {
        String gta = new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(
                "src/main/java/dev/herdr/android/GpuiTerminalActivity.java")), java.nio.charset.StandardCharsets.UTF_8);
        int dispatch = gta.indexOf("private boolean dispatchTerminalTouch(MotionEvent event, int masked) {");
        int first = gta.indexOf("selection.onTouch(event)", dispatch);
        int forward = gta.indexOf("return nativeTouch(", dispatch);
        assertTrue(dispatch > 0 && first > dispatch && first < forward);
        assertTrue(gta.contains("@Override public void claimTouch() { claimRendererTouch(); }"));
    }
}
