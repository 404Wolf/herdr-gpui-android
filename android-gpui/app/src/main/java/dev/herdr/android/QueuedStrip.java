package dev.herdr.android;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.List;

/**
 * The outbox's messages for the pane on screen, one dense line each, floating
 * directly above the composer field over the terminal (never resizing it).
 * A line reads like Claude Code's own echo of a prompt, {@code ❯ text}, with
 * what is happening to it on the right: queued, waiting for a dialog,
 * sending, may have been sent, or not sent because its pane is gone.
 * A tap hands the message to {@link Tap} for its actions.
 */
final class QueuedStrip {
    interface Tap {
        void tapped(Outbox.Message message);
    }

    /** Lines shown; the rest are counted on one more line. */
    static final int MAX_ROWS = 3;
    private static final String PROMPT = "❯ ";

    private final Context context;
    private final Tap tap;
    private final float density;
    private final LinearLayout view;
    private GpuiChrome chrome;
    /** What the rows show, so an unchanged list is not rebuilt on every outbox change. */
    private String shown = "";

    QueuedStrip(Context context, GpuiChrome chrome, Tap tap) {
        this.context = context;
        this.chrome = chrome;
        this.tap = tap;
        this.density = context.getResources().getDisplayMetrics().density;
        view = new LinearLayout(context);
        view.setOrientation(LinearLayout.VERTICAL);
        view.setVisibility(View.GONE);
        style(chrome);
    }

    View view() { return view; }

    boolean isEmpty() { return view.getVisibility() != View.VISIBLE; }

    void style(GpuiChrome next) {
        chrome = next;
        GradientDrawable box = new GradientDrawable();
        box.setColor(chrome.control);
        box.setStroke(Math.max(1, dp(1)), chrome.outline);
        box.setCornerRadius(dp(10));
        view.setBackground(box);
        view.setPadding(0, dp(2), 0, dp(2));
        shown = "";
    }

    /** Shows {@code messages} (from {@link Outbox#shown}); an empty list hides the strip. */
    void show(List<Outbox.Message> messages, String pane) {
        String signature = signature(messages, pane);
        if (signature.equals(shown)) return;
        shown = signature;
        view.removeAllViews();
        if (messages.isEmpty()) {
            view.setVisibility(View.GONE);
            return;
        }
        int rows = Math.min(messages.size(), MAX_ROWS);
        for (int i = 0; i < rows; i++) view.addView(row(messages.get(i), pane));
        if (messages.size() > rows) {
            TextView more = text(more(messages.size() - rows), chrome.onSurfaceVariant);
            more.setPadding(dp(10), 0, dp(10), 0);
            view.addView(more, new LinearLayout.LayoutParams(-1, dp(22)));
        }
        view.setVisibility(View.VISIBLE);
    }

    private View row(Outbox.Message message, String pane) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(10), 0, dp(8), 0);
        row.setMinimumHeight(dp(28));
        row.setBackground(new android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(GpuiChrome.withAlpha(chrome.onSurface, 0x29)), null,
                new android.graphics.drawable.ColorDrawable(0xffffffff)));
        TextView line = text(PROMPT + oneLine(message.text), chrome.onSurface);
        row.addView(line, new LinearLayout.LayoutParams(0, -2, 1));
        TextView state = text(label(message, pane), color(message));
        state.setPadding(dp(8), 0, 0, 0);
        row.addView(state, new LinearLayout.LayoutParams(-2, -2));
        row.setContentDescription("Message " + label(message, pane) + ": " + message.text);
        row.setOnClickListener(v -> tap.tapped(message));
        return row;
    }

    private TextView text(String value, int color) {
        TextView text = new TextView(context);
        text.setText(value);
        text.setTextSize(12);
        text.setTypeface(Typeface.MONOSPACE);
        text.setTextColor(color);
        text.setSingleLine(true);
        text.setEllipsize(TextUtils.TruncateAt.END);
        text.setGravity(Gravity.CENTER_VERTICAL);
        return text;
    }

    private int color(Outbox.Message message) {
        switch (message.state) {
            case UNSURE: return chrome.warning;
            case GONE: return chrome.danger;
            case SENDING: return chrome.info;
            default: return message.blocked ? chrome.warning : chrome.onSurfaceVariant;
        }
    }

    /** What is happening to the message, in a few words. */
    static String label(Outbox.Message message, String pane) {
        switch (message.state) {
            case SENDING: return "sending…";
            case UNSURE: return "may have been sent";
            case GONE: return message.pane.equals(pane) ? "not sent" : "not sent · " + message.pane + " gone";
            default: return message.blocked ? "waits for its dialog" : "queued";
        }
    }

    static String more(int count) {
        return "+" + count + " more queued";
    }

    /** A message's lines on one line: a break shows as ⏎ so nothing looks cut off. */
    static String oneLine(String text) {
        return text.trim().replaceAll("\\s*\\n\\s*", " ⏎ ").replace('\t', ' ');
    }

    private static String signature(List<Outbox.Message> messages, String pane) {
        StringBuilder out = new StringBuilder(pane == null ? "" : pane);
        for (Outbox.Message m : messages) {
            out.append('\u0000').append(m.id).append(m.state).append(m.blocked);
        }
        return out.toString();
    }

    private int dp(float value) { return Math.round(value * density); }
}
