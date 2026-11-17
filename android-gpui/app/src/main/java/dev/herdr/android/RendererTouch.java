package dev.herdr.android;

/**
 * The touch the terminal renderer is following. A gesture's first finger
 * goes to the renderer, which recognizes taps (and sends a program its mouse
 * clicks) on its own. When the activity takes the gesture over (a scroll,
 * the rail's edge swipe, a pinch or a second finger), the renderer must be
 * told once, with a CANCEL for that finger, and then sees nothing more of
 * the gesture, its last lift included. Otherwise it would take the final
 * lift for a tap (a pinch that ended in a click), or keep the finger
 * pending and ignore every touch after it.
 */
final class RendererTouch {
    /**
     * How far a finger moves before it is no longer a tap: the 8 dp touch
     * slop of GPUI's own recognizer (GestureTuning), so the activity starts
     * a scroll exactly where the renderer stops taking the finger for a tap.
     */
    static final int SLOP_DP = 8;

    /** A drag still within the slop: no axis yet. */
    static final int AXIS_NONE = 0;
    /** A sideways drag: the renderer pans a grid wider than the pane. */
    static final int AXIS_X = 1;
    /** An up-or-down drag: the activity scrolls the conversation. */
    static final int AXIS_Y = 2;

    /**
     * The axis a drag locks to once it leaves the slop, as GPUI's recognizer
     * decides it (the movement's length past the slop, then its dominant
     * axis, vertical on a tie). The activity and the renderer must agree:
     * a drag GPUI locked sideways is a pan for the rest of the gesture, so
     * the activity must not take it for a scroll when it later drifts
     * vertically. {@code slopPx} is {@link #SLOP_DP} in pixels.
     */
    static int axis(float dx, float dy, float slopPx) {
        if (Float.isNaN(dx) || Float.isNaN(dy) || Math.hypot(dx, dy) <= slopPx) return AXIS_NONE;
        return Math.abs(dx) <= Math.abs(dy) ? AXIS_Y : AXIS_X;
    }

    /** No finger: the renderer follows none, or was told the gesture is gone. */
    static final int NONE = -1;

    /** The pointer the renderer follows, or {@link #NONE}. */
    private int pointer = NONE;
    /** The activity owns the rest of the current gesture. */
    private boolean claimed;

    /** A gesture begins: its first finger goes to the renderer. */
    void down(int pointerId) {
        pointer = pointerId;
        claimed = false;
    }

    /**
     * The activity takes the gesture over. Returns the pointer to cancel in
     * the renderer, the first time only; {@link #NONE} after that.
     */
    int claim() {
        claimed = true;
        int cancel = pointer;
        pointer = NONE;
        return cancel;
    }

    /** Whether an event of the current gesture still goes to the renderer. */
    boolean forwards() {
        return !claimed;
    }

    /** The gesture's last finger lifted, or the system cancelled it. */
    void end() {
        pointer = NONE;
    }
}
