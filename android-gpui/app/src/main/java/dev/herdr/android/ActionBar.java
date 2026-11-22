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
 * The composer's controls as app icon buttons (factory-9zz.3, Wolf 11-22): one cluster of equal
 * icon buttons at the very bottom right of the whole app, overlaying the work panel (or, with no
 * panel, the terminal), never a strip that takes height. Only the draft stays at the harness's own
 * prompt. Five fixed slots, Clear Img Type Talk Send, whose icons change in place (Talk becomes
 * Stop; Clear becomes Cancel while dictating or Undo after a clear); the queue and the status words
 * ("rec 0:05") are small labels left of them. The ids and taps are ControlLine's, so the composer's
 * state machine is unchanged. Icons: Material Symbols vectors (res/drawable/ic_act_*).
 */
final class ActionBar {
    static final int BUTTON_DP = 40, TARGET_DP = 48, GAP_DP = 2, MARGIN_DP = 8, TEXT_SP = 12;
    /** The cluster's width: five equal slots. */
    static final int SLOTS_WIDTH_DP = 5 * TARGET_DP + 4 * GAP_DP;

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

    /** The fixed slots, left to right, at the row's right edge (t42y A1b): nothing moves between states. */
    static final String[] SLOTS = {"clear", ControlLine.ATTACH, "type", "talk", "send"};

    /**
     * A line of controls as the row's items: first what comes and goes (the queue button, the
     * status words), then the five fixed slots {@link #SLOTS}, whose labels change in place:
     * Clear becomes Cancel while dictating (one discard button, never two) or Undo after a
     * clear; Talk becomes Stop. A slot that does not apply is there, disabled.
     */
    static List<Item> items(List<GhostText.Control> line) {
        java.util.Map<String, Item> byId = new java.util.LinkedHashMap<>();
        Item status = null;
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
            if (ControlLine.STATUS.equals(c.id)) {
                if (status == null) status = new Item(c.id, label, style, false);
                continue;
            }
            byId.putIfAbsent(c.id, new Item(c.id, label, style, true));
        }
        List<Item> out = new ArrayList<>();
        Item queue = byId.get("queue");
        if (queue != null) out.add(queue);
        if (status != null) out.add(status);
        Item cancel = byId.get(ControlLine.CANCEL), undo = byId.get("undo"), clear = byId.get("clear");
        out.add(cancel != null ? new Item(cancel.id, "Cancel", "warn", true)
                : undo != null ? new Item(undo.id, capital(undo.label), undo.style, true)
                : clear != null ? new Item("clear", "Clear", "warn", true)
                : new Item("clear", "Clear", DISABLED, true));
        Item img = byId.get(ControlLine.ATTACH);
        out.add(new Item(ControlLine.ATTACH, "Img", img != null ? img.style : DISABLED, true));
        Item type = byId.get("type");
        out.add(new Item("type", type == null ? "Type" : capital(type.label), type == null ? DISABLED : type.style, true));
        Item talk = byId.get("talk");
        if (talk != null) out.add(new Item("talk", capital(talk.label), talk.style, true));
        else out.add(new Item("talk", "Talk", DISABLED, true));
        out.add(new Item("send", "Send", "bright", true));
        return out;
    }

    private static String capital(String label) {
        return label.isEmpty() ? label : label.substring(0, 1).toUpperCase(java.util.Locale.ROOT) + label.substring(1);
    }

    /** The style of a reserved, disabled button. */
    static final String DISABLED = "disabled";

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
        row.setPadding(0, 0, 0, 0);
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
        View parent = (View) row.getParent();
        int width = parent == null ? 0 : parent.getWidth() - 2 * dp(MARGIN_DP);
        if (width <= 0) width = Integer.MAX_VALUE;
        List<Item> items = items(ladder.get(pick(ladder, width, this::measure)));
        StringBuilder key0 = new StringBuilder();
        for (Item i : items) key0.append(i.id).append('\u0001').append(i.label).append('\u0001').append(i.style).append('\u0002');
        String key = key0.toString();
        if (key.equals(drawn)) return;
        drawn = key;
        row.removeAllViews();
        // One compact group at the bottom right in every state: the labels, then the five slots.
        for (int n = 0; n < items.size(); n++) {
            Item item = items.get(n);
            boolean slot = item.button && isSlot(item.id);
            LinearLayout.LayoutParams at = slot ? new LinearLayout.LayoutParams(dp(TARGET_DP), dp(TARGET_DP))
                    : new LinearLayout.LayoutParams(-2, -2);
            if (n > 0) at.setMarginStart(dp(GAP_DP));
            if (!slot) at.setMarginEnd(dp(4));
            row.addView(slot ? button(item) : pill(item), at);
        }
    }

    /** Its width in pixels, as {@link #redraw} would lay it out: the labels, then the slots. */
    private int measure(List<Item> items) {
        int total = dp(SLOTS_WIDTH_DP);
        for (Item item : items) {
            if (isSlot(item.id)) continue;
            total += Math.round(paint.measureText(item.label)) + 2 * dp(10) + dp(GAP_DP);
        }
        return total;
    }

    static boolean isSlot(String id) {
        return "clear".equals(id) || "undo".equals(id) || ControlLine.CANCEL.equals(id) || ControlLine.ATTACH.equals(id)
                || "type".equals(id) || "talk".equals(id) || "send".equals(id);
    }

    /** The icon for a slot in its state. */
    static int icon(Item item) {
        switch (item.id) {
            case "undo": return R.drawable.ic_act_undo;
            case "clear":
            case ControlLine.CANCEL: return R.drawable.ic_act_close;
            case ControlLine.ATTACH: return R.drawable.ic_act_image;
            case "type": return item.label.equalsIgnoreCase("hide") ? R.drawable.ic_act_keyboard_hide : R.drawable.ic_act_keyboard;
            case "talk":
                if (item.label.equalsIgnoreCase("stop") || "danger".equals(item.style) || "warn".equals(item.style)) {
                    return R.drawable.ic_act_stop;
                }
                return item.label.equalsIgnoreCase("talk") ? R.drawable.ic_act_mic : R.drawable.ic_act_busy;
            default: return R.drawable.ic_act_send;
        }
    }

    private View button(Item item) {
        android.widget.ImageButton b = new android.widget.ImageButton(context);
        b.setImageResource(icon(item));
        b.setScaleType(android.widget.ImageView.ScaleType.CENTER);
        boolean recording = "talk".equals(item.id) && icon(item) == R.drawable.ic_act_stop;
        int tint = recording ? 0xFFFFFFFF : "send".equals(item.id) ? chrome.onSurface
                : ControlLine.CANCEL.equals(item.id) || "warn".equals(item.style) ? chrome.warning : chrome.onSurface;
        b.setImageTintList(android.content.res.ColorStateList.valueOf(tint));
        // A 40 dp visual inside the 48 dp target; opaque, so it reads over the panel's text.
        GradientDrawable shape = new GradientDrawable();
        shape.setCornerRadius(dp(10));
        // Talk is the one filled slot (the palette's selected role); Stop is filled red.
        shape.setColor(recording ? chrome.danger : "talk".equals(item.id) ? chrome.selected : chrome.raised);
        shape.setStroke(Math.max(1, dp(1)), chrome.outline);
        int inset = dp((TARGET_DP - BUTTON_DP) / 2);
        android.graphics.drawable.InsetDrawable visual = new android.graphics.drawable.InsetDrawable(shape, inset);
        b.setBackground(new android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(GpuiChrome.withAlpha(chrome.onSurface, 0x33)), visual, null));
        if (DISABLED.equals(item.style)) {
            b.setEnabled(false);
            b.setAlpha(0.38f);
        }
        b.setContentDescription(act.describe(item.id));
        if (android.os.Build.VERSION.SDK_INT >= 26) b.setTooltipText(item.label);
        b.setOnClickListener(v -> act.tap(item.id));
        if ("send".equals(item.id) || "clear".equals(item.id)) b.setOnLongClickListener(v -> act.hold(item.id));
        return b;
    }

    /** A small opaque pill for a label (the queue's count, the status words). */
    private View pill(Item item) {
        TextView t = label(item);
        t.setPadding(dp(10), dp(4), dp(10), dp(4));
        GradientDrawable shape = new GradientDrawable();
        shape.setCornerRadius(dp(10));
        shape.setColor(chrome.raised);
        t.setBackground(shape);
        if (item.button) {
            t.setContentDescription(act.describe(item.id));
            t.setOnClickListener(v -> act.tap(item.id));
        }
        return t;
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
