package dev.herdr.android;

import android.widget.TextView;

/**
 * A destructive control that confirms where it is, never in a pop-up: the
 * first tap changes its own label to say what a second tap will do; a second
 * tap within {@link #WINDOW_MS} does it; otherwise it quietly goes back.
 */
final class ConfirmInPlace {
    static final long WINDOW_MS = 4_000;

    /** The two-tap rule, apart from any view. */
    static final class Arm {
        private long armedAt = -1;

        /** True when this tap confirms; false when it only armed (or re-armed after the window). */
        boolean tap(long now) {
            if (armedAt >= 0 && now - armedAt <= WINDOW_MS) {
                armedAt = -1;
                return true;
            }
            armedAt = now;
            return false;
        }

        boolean armed(long now) {
            return armedAt >= 0 && now - armedAt <= WINDOW_MS;
        }

        void reset() {
            armedAt = -1;
        }
    }

    private ConfirmInPlace() { }

    /**
     * Makes {@code view} run {@code action} on a second tap; the first shows
     * {@code armedLabel} in place of {@code label} until the window passes.
     */
    static void bind(TextView view, String label, String armedLabel, Runnable action) {
        Arm arm = new Arm();
        Runnable disarm = () -> {
            arm.reset();
            view.setText(label);
        };
        view.setText(label);
        view.setOnClickListener(v -> {
            view.removeCallbacks(disarm);
            if (arm.tap(android.os.SystemClock.uptimeMillis())) {
                view.setText(label);
                action.run();
                return;
            }
            view.setText(armedLabel);
            view.setContentDescription(armedLabel);
            view.postDelayed(disarm, WINDOW_MS);
        });
    }

    /** "Close terminal" becomes "Tap again to close terminal". */
    static String again(String label) {
        if (label == null || label.isEmpty()) return "Tap again";
        return "Tap again to " + Character.toLowerCase(label.charAt(0)) + label.substring(1);
    }
}
