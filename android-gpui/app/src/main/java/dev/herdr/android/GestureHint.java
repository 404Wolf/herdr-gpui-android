package dev.herdr.android;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;

/**
 * A card over an agent's terminal that names its gestures, since the bar's
 * buttons are gone: the rail from the left, the file from the right, panes and
 * keys on the chip, machines from the tab strip. Shown only from ⋯ > Gestures,
 * never on its own (Herdr shows no pop-ups it was not asked for). Any tap
 * dismisses it.
 */
final class GestureHint {
    static final String TEXT = "Swipe → from the left half: machines, spaces and agents\n"
            + "Swipe ← from the right half: the open file\n"
            + "Swipe ↔ on the mic and ↵ chip: previous or next pane\n"
            + "Swipe ↑ on the chip, or hold ↵: terminal keys\n"
            + "Pull ↓ on the tabs: switch machine\n"
            + "Tap the terminal or ⌨ to type";

    private GestureHint() { }

    /** Shows the card over {@code frame}; the user asked for it. */
    static void show(Context context, FrameLayout frame, GpuiChrome chrome) {
        float density = context.getResources().getDisplayMetrics().density;
        FrameLayout scrim = new FrameLayout(context);
        scrim.setBackgroundColor(GpuiChrome.withAlpha(chrome.background, 0xb8));
        scrim.setClickable(true);
        TextView card = new TextView(context);
        card.setText(TEXT);
        card.setTextSize(13);
        card.setLineSpacing(0, 1.35f);
        card.setTextColor(chrome.onSurface);
        int pad = Math.round(14 * density);
        card.setPadding(pad, pad, pad, pad);
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(chrome.surface);
        shape.setStroke(Math.max(1, Math.round(density)), chrome.primary);
        shape.setCornerRadius(10 * density);
        card.setBackground(shape);
        FrameLayout.LayoutParams at = new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER);
        at.setMargins(pad, pad, pad, pad);
        scrim.addView(card, at);
        scrim.setOnClickListener(v -> {
            if (scrim.getParent() instanceof ViewGroup) ((ViewGroup) scrim.getParent()).removeView(scrim);
        });
        scrim.setContentDescription("Gestures. " + TEXT.replace('\n', ' ') + ". Tap to close.");
        frame.addView(scrim, new FrameLayout.LayoutParams(-1, -1));
        scrim.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
    }
}
