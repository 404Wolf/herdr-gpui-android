package dev.herdr.android;

import android.content.Context;
import android.view.MotionEvent;
import android.view.ViewConfiguration;
import android.widget.LinearLayout;

/**
 * A row whose downward pull runs an action (the tab strip's: switch machine),
 * while sideways drags still scroll what it holds and taps reach its children.
 */
final class PullDownRow extends LinearLayout {
    /** How far the finger must travel down, in dp. */
    static final int PULL_DP = 28;

    private final int slop;
    private final float density;
    private Runnable onPull;
    private float downX, downY;

    PullDownRow(Context context) {
        super(context);
        slop = ViewConfiguration.get(context).getScaledTouchSlop();
        density = context.getResources().getDisplayMetrics().density;
    }

    void setOnPull(Runnable action) { onPull = action; }

    /** A drag from {@code (dx, dy)} away that counts as a pull: mostly down, past the slop. */
    static boolean pulling(float dx, float dy, float slop) {
        return dy > slop * 2 && dy > Math.abs(dx) * 2;
    }

    @Override public boolean onInterceptTouchEvent(MotionEvent event) {
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
            downX = event.getX();
            downY = event.getY();
            return false;
        }
        return event.getActionMasked() == MotionEvent.ACTION_MOVE
                && pulling(event.getX() - downX, event.getY() - downY, slop);
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        if (event.getActionMasked() == MotionEvent.ACTION_UP
                && event.getY() - downY > PULL_DP * density && onPull != null) {
            onPull.run();
        }
        return true;
    }
}
