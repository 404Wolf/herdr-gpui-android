package dev.herdr.android;

import android.content.Context;
import android.graphics.Paint;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * The composer's buttons as real app buttons (factory-9zz.3, Wolf 11-22): one compact row at the
 * very bottom right of the whole app, under the terminal and the work panel. Only the draft stays
 * at the harness's own prompt. The row shows the widest of {@link ControlLine}'s lines that fits
 * its width: the same controls, ids and taps as the words it replaces, so the composer's state
 * machine is unchanged. A status control ("rec 0:05", "offline") is a quiet label at the row's
 * left, never a button; reserved gaps are dropped (the buttons are anchored at the right edge, so
 * nothing slides under a finger).
 */
final class ActionBar {
    static final int HEIGHT_DP = 44, BUTTON_DP = 34, GAP_DP = 6, PAD_DP = 8, TEXT_SP = 13;

    /** One button or label as the row draws it: pure, for tests. */
    static final class Item {
        final String id, label, style;
        final boolean button;

        Item(String id, String label, String style, boolean button) {
            this.id = id;
            this.label = label;
            this.style = style;
            this.button = button;
        }

        @Override public String toString() {
            return (button ? "[" + label + "]" : label);
        }
    }

    /** A line of controls as the row's items: gaps dropped, the status words a label, words trimmed. */
    static List<Item> items(List<GhostText.Control> line) {
        List<Item> out = new ArrayList<>();
        for (GhostText.Control c : line) {
            if (c.id.startsWith("gap-")) continue;
            StringBuilder text = new StringBuilder();
            String style = "";
            for (int i = 0; i + 1 < c.spans.length; i += 2) {
                text.append(c.spans[i]);
                if (style.isEmpty() && !c.spans[i].trim().isEmpty()) style = c.spans[i + 1];
            }
            String label = text.toString().trim();
            if (label.isEmpty()) continue;
            if ("clear".equals(c.id) && "✕".equals(label)) label = "clear";
            if (ControlLine.ATTACH.equals(c.id)) label = "img";
            out.add(new Item(c.id, label, style, ControlLine.tappable(c.id)));
        }
        return out;
    }

    /** The first line of {@code ladder} (widest first) whose items fit {@code width}, else the last. */
    static int pick(List<List<GhostText.Control>> ladder, int width, java.util.function.ToIntFunction<List<Item>> measure) {
        for (int i = 0; i < ladder.size(); i++) {
            if (measure.applyAsInt(items(ladder.get(i))) <= width) return i;
        }
        return ladder.size() - 1;
    }

    interface Act {
        void tap(String id);
        /** A long press; true when it was taken. */
        boolean hold(String id);
        String describe(String id);
    }

    private final Context context;
    private final GpuiChrome chrome;
    private final Act act;
    private final LinearLayout row;
    private final float density;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private List<List<GhostText.Control>> ladder = java.util.Collections.emptyList();
    private String drawn = "";

    ActionBar(Context context, GpuiChrome chrome, Act act) {
        this.context = context;
        this.chrome = chrome;
        this.act = act;
        density = context.getResources().getDisplayMetrics().density;
        paint.setTextSize(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, TEXT_SP,
                context.getResources().getDisplayMetrics()));
        row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL | Gravity.END);
        row.setPadding(dp(PAD_DP), 0, dp(PAD_DP), 0);
        row.setBackgroundColor(chrome.background);
        row.setVisibility(View.GONE);
        row.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            if (r - l != or - ol) v.post(this::redraw);
        });
    }

    View view() {
        return row;
    }

    /** The composer's lines for now (widest first); empty hides the row (a shell, no agent). */
    void bind(List<List<GhostText.Control>> lines) {
        ladder = lines == null ? java.util.Collections.emptyList() : lines;
        row.setVisibility(ladder.isEmpty() ? View.GONE : View.VISIBLE);
        redraw();
    }

    private void redraw() {
        if (ladder.isEmpty()) {
            if (!drawn.isEmpty()) { row.removeAllViews(); drawn = ""; }
            return;
        }
        int width = row.getWidth() - row.getPaddingLeft() - row.getPaddingRight();
        if (width <= 0) width = Integer.MAX_VALUE;
        List<Item> items = items(ladder.get(pick(ladder, width, this::measure)));
        StringBuilder key0 = new StringBuilder();
        for (Item i : items) key0.append(i.id).append('\u0001').append(i.label).append('\u0001').append(i.style).append('\u0002');
        String key = key0.toString();
        if (key.equals(drawn)) return;
        drawn = key;
        row.removeAllViews();
        for (int n = 0; n < items.size(); n++) {
            Item item = items.get(n);
            TextView view = item.button ? button(item) : label(item);
            LinearLayout.LayoutParams at = new LinearLayout.LayoutParams(-2, item.button ? dp(BUTTON_DP) : -2);
            if (n > 0) at.setMarginStart(dp(GAP_DP));
            if (!item.button) at.weight = 1;
            row.addView(view, at);
        }
    }

    /** Its width in pixels, as {@link #redraw} would lay it out. */
    private int measure(List<Item> items) {
        int total = 0;
        for (int n = 0; n < items.size(); n++) {
            Item item = items.get(n);
            total += Math.round(paint.measureText(item.label)) + (item.button ? 2 * dp(12) : 0);
            if (n > 0) total += dp(GAP_DP);
        }
        return total;
    }

    private TextView button(Item item) {
        TextView b = text(item.label, color(item));
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(12), 0, dp(12), 0);
        b.setMinWidth(dp(48));
        GradientDrawable shape = new GradientDrawable();
        shape.setCornerRadius(dp(8));
        shape.setStroke(Math.max(1, dp(1)), "send".equals(item.id) ? chrome.onSurfaceVariant : chrome.outline);
        shape.setColor(0x00000000);
        b.setBackground(new android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(GpuiChrome.withAlpha(chrome.onSurface, 0x33)), shape, shape));
        b.setContentDescription(act.describe(item.id));
        b.setOnClickListener(v -> act.tap(item.id));
        if ("send".equals(item.id) || "clear".equals(item.id)) b.setOnLongClickListener(v -> act.hold(item.id));
        return b;
    }

    private TextView label(Item item) {
        TextView t = text(item.label, color(item));
        t.setEllipsize(TextUtils.TruncateAt.END);
        t.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        return t;
    }

    private TextView text(String value, int color) {
        TextView t = new TextView(context);
        t.setText(value);
        t.setTextSize(TEXT_SP);
        t.setSingleLine(true);
        t.setTextColor(color);
        t.setIncludeFontPadding(false);
        return t;
    }

    /** The word's colour from its style: Send bright, warnings and recording in their colours. */
    private int color(Item item) {
        switch (item.style) {
            case "danger": return chrome.danger;
            case "warn": return chrome.warning;
            case "info": return chrome.info;
            case "bright": return chrome.onSurface;
            default: return item.button ? chrome.onSurface : chrome.onSurfaceVariant;
        }
    }

    private int dp(int value) {
        return Math.round(value * density);
    }
}
