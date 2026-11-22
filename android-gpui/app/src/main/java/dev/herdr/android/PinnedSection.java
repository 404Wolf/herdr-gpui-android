package dev.herdr.android;

import android.app.Activity;
import android.graphics.Typeface;
import android.os.Build;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

/**
 * The compact "Pinned" section at the top of the rail (factory-9zz.2, P3): one
 * 40dp row per pinned agent, the one-tap way to an agent on any machine. A row
 * is the state dot, the agent's name (never an id), a compact dim machine tag,
 * always shown, and a dim state word when it is not live ("offline · 14:02");
 * live is the dot alone. A tap opens the pin; a long-press offers Unpin. No
 * pins, no section. The rows ({@link #rows}) are pure; the view only draws them.
 */
final class PinnedSection {
    /** The machine tag's length: a compact name, cut with "…". */
    static final int MACHINE_CHARS = 12;
    /** As tight as the rail's lead rows (Wolf's OLED preview); the whole row is the tap target. */
    static final int ROW_DP = FleetTree.LEAD_ROW_DP;

    /** One drawn row. */
    static final class Row {
        final Pins.Pin pin;
        final String name, machine, words, status;
        final boolean unnamed, selected, live;

        Row(Pins.Pin pin, String name, boolean unnamed, String machine, String words, String status,
                boolean selected, boolean live) {
            this.pin = pin;
            this.name = name;
            this.unnamed = unnamed;
            this.machine = machine;
            this.words = words;
            this.status = status;
            this.selected = selected;
            this.live = live;
        }

        /** What a screen reader hears: "phone-rail on desktop, offline, last seen 14:02". */
        String described() {
            String state = live ? AgentNames.stateWords(status, "") : words;
            return name + " on " + machine + (state.isEmpty() ? "" : ", " + state);
        }
    }

    /**
     * The rows for {@code pins}, oldest first. {@code onScreen} names the live
     * agents of the machine on screen by address (their names come from there
     * first); {@code selected} is the pin key of the agent on screen, if pinned.
     */
    static List<Row> rows(List<Pins.Pin> pins, Pins.Context now, Map<String, FleetTree.Member> onScreen,
            Pins.Key selected, ZoneId zone) {
        List<Row> out = new ArrayList<>();
        for (Pins.Pin pin : pins) {
            Pins.State state = Pins.state(pin, now);
            boolean here = pin.key.machine.equals(now.onScreen);
            FleetTree.Member member = here ? onScreen.get(pin.key.address) : null;
            String name = member != null && !AgentNames.unnamed(member) ? FleetPanes.displayTitle(member)
                    : !AgentNames.alias(pin.label) ? pin.label : AgentNames.UNNAMED;
            boolean live = state == Pins.State.LIVE;
            String status = member != null ? member.status : pin.status;
            out.add(new Row(pin, name, AgentNames.UNNAMED.equals(name), machineTag(pin.machineName),
                    words(state, pin, zone), status, pin.key.equals(selected), live));
        }
        return out;
    }

    /** A machine's name, compact: at most {@link #MACHINE_CHARS}, "…" when cut. */
    static String machineTag(String machine) {
        String m = machine == null ? "" : machine.trim();
        if (m.isEmpty()) return "?";
        return m.codePointCount(0, m.length()) <= MACHINE_CHARS ? m
                : m.substring(0, m.offsetByCodePoints(0, MACHINE_CHARS - 1)) + "…";
    }

    /**
     * The dim state word: none when live (the dot says it), else "ended",
     * "unavailable", "reconnecting" or "offline · 14:02" (when its machine was
     * last seen); "restarted" when another program started in it since it was
     * last opened.
     */
    static String words(Pins.State state, Pins.Pin pin, ZoneId zone) {
        String said;
        switch (state) {
            case ENDED: return "ended";
            case UNAVAILABLE: said = "unavailable"; break;
            case RECONNECTING: said = "reconnecting"; break;
            case OFFLINE: said = "offline" + (pin.asOfMs > 0 ? " · " + clock(pin.asOfMs, zone) : ""); break;
            default: said = ""; break;
        }
        if (pin.restarted()) said = said.isEmpty() ? "restarted" : said + " · restarted";
        return said;
    }

    static String clock(long millis, ZoneId zone) {
        return Instant.ofEpochMilli(millis).atZone(zone).format(DateTimeFormatter.ofPattern("HH:mm", Locale.US));
    }

    /** What the drawn rows are made of: the same signature draws nothing new (no rebuild under a finger). */
    static String signature(List<Row> rows) {
        StringBuilder s = new StringBuilder();
        for (Row r : rows) {
            s.append(r.pin.key.address).append('|').append(r.pin.key.createdMs).append('|').append(r.name).append('|')
                    .append(r.machine).append('|').append(r.words).append('|').append(r.status).append('|')
                    .append(r.selected).append('|').append(r.live).append('\n');
        }
        return s.toString();
    }

    private final Activity activity;
    private final GpuiChrome chrome;
    private final Consumer<Pins.Pin> open, unpin;
    private final LinearLayout root;
    private String drawn = null;

    /** {@code open} runs a row's tap; {@code unpin} its long-press Unpin. */
    PinnedSection(Activity activity, GpuiChrome chrome, Consumer<Pins.Pin> open, Consumer<Pins.Pin> unpin) {
        this.activity = activity;
        this.chrome = chrome;
        this.open = open;
        this.unpin = unpin;
        root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setVisibility(View.GONE);
        root.setSaveFromParentEnabled(false);
    }

    View view() {
        return root;
    }

    /** Draws {@code rows}; no rows, no section. Unchanged rows are left alone. */
    void show(List<Row> rows) {
        String sig = signature(rows);
        if (sig.equals(drawn)) return;
        drawn = sig;
        root.removeAllViews();
        root.setVisibility(rows.isEmpty() ? View.GONE : View.VISIBLE);
        if (rows.isEmpty()) return;
        // A quiet caps label, 12dp above and 4dp below (review D3).
        TextView header = text("PINNED", 11, chrome.onSurfaceVariant, true);
        header.setContentDescription("Pinned");
        header.setPadding(dp(10), dp(12), dp(10), dp(4));
        if (Build.VERSION.SDK_INT >= 28) header.setAccessibilityHeading(true);
        root.addView(header);
        for (Row r : rows) root.addView(row(r));
    }

    private View row(Row r) {
        LinearLayout row = new LinearLayout(activity);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(ROW_DP));
        row.setPadding(dp(10), 0, dp(8), 0);
        row.setBackground(ActionSheet.ripple(chrome));
        row.setSelected(r.selected);
        // The rail's own mark for the row on screen.
        if (r.selected) row.setBackgroundColor(chrome.selected);
        row.setFocusable(true);
        // The rail's square status mark: the agent's state when live, dim when not.
        View dot = new View(activity);
        dot.setBackgroundColor(r.live ? chrome.status(r.status.isEmpty() ? "unknown" : r.status)
                : GpuiChrome.withAlpha(chrome.onSurfaceVariant, 0x66));
        LinearLayout.LayoutParams dotAt = new LinearLayout.LayoutParams(dp(6), dp(6));
        dotAt.setMarginEnd(dp(8));
        row.addView(dot, dotAt);
        TextView name = text(r.name, 13, r.unnamed || !r.live ? chrome.onSurfaceVariant : chrome.onSurface, false);
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        row.addView(name, new LinearLayout.LayoutParams(0, -2, 1));
        if (!r.words.isEmpty()) {
            TextView words = text(r.words, 11, chrome.onSurfaceVariant, false);
            words.setSingleLine(true);
            LinearLayout.LayoutParams at = new LinearLayout.LayoutParams(-2, -2);
            at.setMarginStart(dp(6));
            row.addView(words, at);
        }
        TextView machine = text(r.machine, 11, GpuiChrome.withAlpha(chrome.onSurfaceVariant, 0xB0), false);
        machine.setSingleLine(true);
        LinearLayout.LayoutParams at = new LinearLayout.LayoutParams(-2, -2);
        at.setMarginStart(dp(6));
        row.addView(machine, at);
        row.setContentDescription(r.described());
        row.setOnClickListener(v -> open.accept(r.pin));
        row.setOnLongClickListener(v -> { askUnpin(r); return true; });
        // TalkBack gets Unpin as an action of its own, beside the tap.
        row.setAccessibilityDelegate(new View.AccessibilityDelegate() {
            @Override public void onInitializeAccessibilityNodeInfo(View host, AccessibilityNodeInfo info) {
                super.onInitializeAccessibilityNodeInfo(host, info);
                info.addAction(new AccessibilityNodeInfo.AccessibilityAction(R.id.pin_unpin_action, "Unpin"));
            }

            @Override public boolean performAccessibilityAction(View host, int action, android.os.Bundle args) {
                if (action == R.id.pin_unpin_action) {
                    unpin.accept(r.pin);
                    return true;
                }
                return super.performAccessibilityAction(host, action, args);
            }
        });
        return row;
    }

    private void askUnpin(Row r) {
        new ActionSheet(activity, chrome, r.name + " · " + r.machine).add("Unpin", () -> unpin.accept(r.pin)).show();
    }

    private TextView text(String value, float size, int color, boolean medium) {
        TextView view = ActionSheet.label(activity, value, size, color);
        view.setTypeface(Typeface.create(Typeface.MONOSPACE, medium ? 500 : 400, false));
        return view;
    }

    private int dp(float value) {
        return ActionSheet.dp(activity, value);
    }
}
