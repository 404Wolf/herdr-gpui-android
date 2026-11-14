package dev.herdr.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

/**
 * Which events of a gesture reach the terminal renderer. GPUI makes a tap
 * of a finger it saw go down and come up, so a gesture the activity takes
 * over must end in the renderer with one CANCEL and nothing after it.
 */
public final class RendererTouchTest {
    /** What the renderer is sent for each step, as the activity routes it. */
    private static final class Renderer {
        final RendererTouch touch = new RendererTouch();
        final List<String> sent = new ArrayList<>();

        void down(int pointer) {
            touch.down(pointer);
            sent.add("down " + pointer);
        }

        /** The activity takes the gesture over (a scroll, the rail, a second finger). */
        void claim() {
            int pointer = touch.claim();
            if (pointer != RendererTouch.NONE) sent.add("cancel " + pointer);
        }

        void event(String name) {
            if (touch.forwards()) sent.add(name);
        }

        void up() {
            if (touch.forwards()) sent.add("up");
            touch.end();
        }

        /** GPUI synthesizes a tap only for a finger it saw go down and come up. */
        boolean tapped() {
            return sent.size() >= 2 && sent.get(0).startsWith("down") && sent.get(sent.size() - 1).equals("up");
        }
    }

    @Test public void aTapReachesTheRendererWhole() {
        Renderer renderer = new Renderer();
        renderer.down(0);
        renderer.event("move");
        renderer.up();
        assertEquals(java.util.Arrays.asList("down 0", "move", "up"), renderer.sent);
        assertTrue(renderer.tapped());
    }

    @Test public void aTwoFingerTapIsCancelledAndNeverClicks() {
        Renderer renderer = new Renderer();
        renderer.down(0);
        // The second finger: the activity's (POINTER_DOWN), then its lift, then the first's.
        renderer.claim();
        renderer.event("pointer down 1");
        renderer.event("pointer up 1");
        renderer.up();
        assertEquals(java.util.Arrays.asList("down 0", "cancel 0"), renderer.sent);
        assertFalse(renderer.tapped());
    }

    @Test public void aPinchIsCancelledOnceAndItsLastLiftNeverArrives() {
        Renderer renderer = new Renderer();
        renderer.down(0);
        renderer.claim();
        // Every multi-pointer step claims again; the renderer hears one cancel.
        for (int i = 0; i < 5; i++) {
            renderer.claim();
            renderer.event("move");
        }
        // The pinch ends with one finger still down, moving, then lifting.
        renderer.event("move");
        renderer.up();
        assertEquals(java.util.Arrays.asList("down 0", "cancel 0"), renderer.sent);
        assertFalse(renderer.tapped());
    }

    @Test public void aScrollAndAnEdgeSwipeEndTheRenderersTouch() {
        for (int moves : new int[] {1, 3}) {
            Renderer renderer = new Renderer();
            renderer.down(4);
            for (int i = 0; i < moves; i++) renderer.event("move");
            // Past the slop: the scroll (or the rail) is the activity's.
            renderer.claim();
            renderer.event("move");
            renderer.up();
            assertEquals("cancel 4", renderer.sent.get(renderer.sent.size() - 1));
            assertFalse(renderer.tapped());
        }
    }

    @Test public void theNextGestureReachesTheRendererAgain() {
        // A claimed gesture used to leave GPUI's recognizer holding its finger,
        // so every tap after it was ignored.
        Renderer renderer = new Renderer();
        renderer.down(0);
        renderer.claim();
        renderer.up();
        renderer.sent.clear();
        renderer.down(0);
        renderer.up();
        assertEquals(java.util.Arrays.asList("down 0", "up"), renderer.sent);
        assertTrue(renderer.tapped());
    }

    @Test public void theScrollStartsWhereTheRendererStopsTakingATap() {
        // GPUI's GestureTuning default touch slop is 8 logical px, and this
        // window's logical pixel is a dp.
        assertEquals(8, RendererTouch.SLOP_DP);
    }
}
