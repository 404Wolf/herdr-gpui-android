package dev.herdr.android;

import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;

/**
 * A press that only counts as a tap when the finger stayed put: a control
 * whose tap makes something at once (the toolbar's ">_" opens a tab) must not
 * fire for a press that began a scroll or drag across the toolbar. It watches
 * the touches without consuming them, so the view's own click, long-press and
 * ripple behave as usual; the click asks {@link #still()} first.
 */
final class StillPress implements View.OnTouchListener {
    private final int slop;
    private float downX, downY;
    private boolean moved;

    private StillPress(int slop) {
        this.slop = slop;
    }

    /** Watches {@code view}'s touches; the returned guard says whether the last press stayed put. */
    static StillPress on(View view) {
        StillPress guard = new StillPress(ViewConfiguration.get(view.getContext()).getScaledTouchSlop());
        view.setOnTouchListener(guard);
        return guard;
    }

    @Override public boolean onTouch(View view, MotionEvent event) {
        track(event.getActionMasked(), event.getX(), event.getY(), event.getPointerCount());
        return false;
    }

    /** One touch event; pure apart from the fields, so it can be tested without a window. */
    void track(int action, float x, float y, int pointers) {
        if (action == MotionEvent.ACTION_DOWN) {
            downX = x;
            downY = y;
            moved = false;
        } else if (action == MotionEvent.ACTION_CANCEL || action == MotionEvent.ACTION_POINTER_DOWN || pointers > 1) {
            moved = true;
        } else if (action == MotionEvent.ACTION_MOVE || action == MotionEvent.ACTION_UP) {
            if (Math.hypot(x - downX, y - downY) > slop) moved = true;
        }
    }

    /** True when the press that just ended did not travel past the touch slop. */
    boolean still() {
        return !moved;
    }

    /** For tests: a guard with a fixed slop and no view. */
    static StillPress forTest(int slop) {
        return new StillPress(slop);
    }
}
