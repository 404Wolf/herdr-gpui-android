package dev.herdr.android;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * The live pane's offer, over the top of the terminal: a held message typed for this pane (its
 * target as it was when typed, its age and its text) with one Send that types it here, or a
 * draft from before a restart that one tap loads. Nothing is sent or loaded without that tap,
 * and the tap is the user's confirmation that this pane is the right one.
 */
final class HeldOfferCard {
    private final LinearLayout card;
    private final TextView title;
    private final TextView body;
    private final TextView primary;
    private final TextView secondary;
    private final TextView dismiss;
    /** What the card shows now, so a refresh that changes nothing touches no view. */
    private String shown = "";

    HeldOfferCard(Context context, GpuiChrome chrome) {
        card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(context, 10);
        card.setPadding(pad, dp(context, 6), pad, dp(context, 4));
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(chrome.surface);
        shape.setStroke(dp(context, 1), chrome.primary);
        shape.setCornerRadius(dp(context, 8));
        card.setBackground(shape);
        card.setVisibility(View.GONE);
        card.setClickable(true);
        title = ActionSheet.label(context, "", 12, chrome.onSurfaceVariant);
        title.setMaxLines(2);
        title.setEllipsize(TextUtils.TruncateAt.END);
        card.addView(title);
        body = ActionSheet.label(context, "", 14, chrome.onSurface);
        body.setMaxLines(4);
        body.setEllipsize(TextUtils.TruncateAt.END);
        card.addView(body);
        LinearLayout buttons = new LinearLayout(context);
        buttons.setGravity(Gravity.END);
        dismiss = ActionSheet.button(context, chrome, "", chrome.onSurfaceVariant);
        secondary = ActionSheet.button(context, chrome, "", chrome.onSurfaceVariant);
        primary = ActionSheet.button(context, chrome, "", chrome.primary);
        buttons.addView(dismiss);
        buttons.addView(secondary);
        buttons.addView(primary);
        card.addView(buttons, new LinearLayout.LayoutParams(-1, -2));
    }

    View view() {
        return card;
    }

    /**
     * Shows an offer. {@code key} names it: the same key again changes nothing. Null
     * {@code secondaryLabel} or {@code dismissLabel} hide those buttons.
     */
    void show(String key, String heading, String text, String primaryLabel, Runnable onPrimary,
            String secondaryLabel, Runnable onSecondary, String dismissLabel, Runnable onDismiss) {
        if (key.equals(shown) && card.getVisibility() == View.VISIBLE) return;
        shown = key;
        title.setText(heading);
        body.setText(text);
        body.setVisibility(text == null || text.isEmpty() ? View.GONE : View.VISIBLE);
        bind(primary, primaryLabel, onPrimary);
        bind(secondary, secondaryLabel, onSecondary);
        bind(dismiss, dismissLabel, onDismiss);
        card.setContentDescription(heading + ". " + (text == null ? "" : text));
        card.setVisibility(View.VISIBLE);
    }

    private void bind(TextView button, String label, Runnable action) {
        if (label == null) {
            button.setVisibility(View.GONE);
            button.setOnClickListener(null);
            return;
        }
        button.setText(label);
        button.setVisibility(View.VISIBLE);
        button.setOnClickListener(v -> {
            hide();
            if (action != null) action.run();
        });
    }

    void hide() {
        shown = "";
        card.setVisibility(View.GONE);
    }

    private static int dp(Context context, float value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }
}
