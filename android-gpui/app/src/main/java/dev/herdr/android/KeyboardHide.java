package dev.herdr.android;

import android.app.Activity;
import android.view.MotionEvent;
import android.view.View;
import android.view.inputmethod.InputMethodManager;

/**
 * Putting the on-screen keyboard away, the same on every screen: Back does it before anything else,
 * and so does a downward drag. Pure geometry plus one system call.
 */
final class KeyboardHide {
    private KeyboardHide() { }

    /** A drag that went mostly down, far enough: the dock-style "put it away" gesture. */
    static boolean isDownSwipe(float x0, float y0, float x1, float y1, float slop) {
        float dy = y1 - y0, dx = x1 - x0;
        return dy > 3 * slop && dy > 2 * Math.abs(dx);
    }

    /** Hides the keyboard if it is up; true when it was (so Back has done its job). */
    static boolean hideIfShown(Activity activity) {
        View decor = activity.getWindow().getDecorView();
        InputMethodManager input = activity.getSystemService(InputMethodManager.class);
        return input != null && input.hideSoftInputFromWindow(decor.getWindowToken(), 0);
    }

    /** Watches an activity's touches and hides the keyboard on a downward drag; never consumes one. */
    static final class Swipe {
        private float startX, startY;
        private boolean done;

        void onTouch(Activity activity, MotionEvent event, float slop) {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    startX = event.getX();
                    startY = event.getY();
                    done = false;
                    break;
                case MotionEvent.ACTION_MOVE:
                    if (!done && isDownSwipe(startX, startY, event.getX(), event.getY(), slop)) {
                        done = true;
                        hideIfShown(activity);
                    }
                    break;
                default:
            }
        }
    }
}
