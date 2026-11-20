package dev.herdr.android;

import android.app.Activity;
import android.app.Dialog;
import android.os.SystemClock;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.Window;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.List;
import java.util.TimeZone;

/**
 * The one queue (§3.7 of the offline design): every held message on every computer, grouped by
 * computer and then by pane, each with its text, its target, when it was typed and how long ago,
 * and its state in words. It opens from the corner badge's "N queued". A row opens its actions;
 * a pending message is sent only from its own live pane, which "Open its pane" shows.
 */
final class QueueScreen {
    /** What the screen asks of the terminal. */
    interface Actions {
        /** Whether {@code m}'s pane is the one on screen (so it can be edited into the draft). */
        boolean onScreen(Outbox.Message m);
        /** Shows {@code m}'s computer and pane; the offer to send appears there once it is live. */
        void open(Outbox.Message m);
        /** Takes {@code m} back into the composer's draft of the pane on screen. */
        void edit(Outbox.Message m);
        /** Whether a boot count can be read: ages need it. */
        int boot();
    }

    private QueueScreen() { }

    static void show(Activity activity, GpuiChrome chrome, OutboxCourier courier, Actions actions) {
        if (activity.isFinishing()) return;
        List<Outbox.Message> all = courier.outbox().all();
        Dialog dialog = new Dialog(activity);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout list = ActionSheet.card(activity, chrome);
        int waiting = courier.outbox().waiting();
        TextView heading = ActionSheet.label(activity, waiting == 0 ? "Nothing waits to be sent"
                : waiting == 1 ? "1 message waits for you" : waiting + " messages wait for you", 13, chrome.onSurface);
        heading.setPadding(dp(activity, 16), dp(activity, 12), dp(activity, 16), dp(activity, 2));
        list.addView(heading);
        TextView rule = ActionSheet.label(activity,
                "Nothing here is sent by itself. Open a message's pane: once it is live, tap Send there.", 12,
                chrome.onSurfaceVariant);
        rule.setPadding(dp(activity, 16), 0, dp(activity, 16), dp(activity, 8));
        list.addView(rule);
        List<Outbox.Target> targets = new ArrayList<>();
        for (Outbox.Message m : all) targets.add(m.target);
        TimeZone zone = TimeZone.getDefault();
        boolean h24 = android.text.format.DateFormat.is24HourFormat(activity);
        long wall = System.currentTimeMillis();
        long elapsed = SystemClock.elapsedRealtime();
        int boot = actions.boot();
        String lastComputer = null;
        for (List<Outbox.Message> group : HeldWords.grouped(all)) {
            Outbox.Message first = group.get(0);
            String computer = first.unverified() ? "destination not verified" : HeldWords.computer(first.target, targets);
            if (!computer.equals(lastComputer)) {
                TextView header = ActionSheet.label(activity, computer, 12, chrome.primary);
                header.setPadding(dp(activity, 16), dp(activity, 10), dp(activity, 16), dp(activity, 2));
                list.addView(header);
                lastComputer = computer;
            }
            TextView pane = ActionSheet.label(activity, HeldWords.pane(first.target), 12, chrome.onSurfaceVariant);
            pane.setPadding(dp(activity, 24), dp(activity, 2), dp(activity, 16), dp(activity, 2));
            list.addView(pane);
            for (Outbox.Message m : group) {
                LinearLayout row = new LinearLayout(activity);
                row.setOrientation(LinearLayout.VERTICAL);
                row.setPadding(dp(activity, 24), dp(activity, 6), dp(activity, 16), dp(activity, 6));
                row.setBackground(ActionSheet.ripple(chrome));
                row.setContentDescription("Held message: " + m.text);
                TextView text = ActionSheet.label(activity, QueuedMessages.PROMPT + QueuedMessages.oneLine(m.text), 15,
                        chrome.onSurface);
                text.setMaxLines(3);
                text.setEllipsize(TextUtils.TruncateAt.END);
                row.addView(text);
                row.addView(ActionSheet.label(activity, HeldWords.target(m.target, targets), 12, chrome.onSurfaceVariant));
                row.addView(ActionSheet.label(activity, HeldWords.typed(m, wall, elapsed, boot, zone, h24), 12,
                        chrome.onSurfaceVariant));
                row.addView(ActionSheet.label(activity, HeldWords.state(m, false, zone, h24), 12, color(chrome, m)));
                row.setOnClickListener(v -> {
                    dialog.dismiss();
                    rowActions(activity, chrome, courier, actions, m);
                });
                list.addView(row, new LinearLayout.LayoutParams(-1, -2));
            }
        }
        ScrollView scroll = new ScrollView(activity);
        scroll.addView(list);
        dialog.setContentView(scroll);
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0));
            int width = Math.min(dp(activity, 420), activity.getResources().getDisplayMetrics().widthPixels
                    - dp(activity, 24));
            window.setLayout(width, WindowManager.LayoutParams.WRAP_CONTENT);
            window.setGravity(Gravity.CENTER);
        }
        dialog.show();
    }

    private static int color(GpuiChrome chrome, Outbox.Message m) {
        switch (m.state) {
            case NEEDS_YOU: return chrome.warning;
            case SENT: return chrome.info;
            case SENDING: return chrome.info;
            default: return chrome.onSurfaceVariant;
        }
    }

    /** One message's actions, by its state (§3.7). */
    static void rowActions(Activity activity, GpuiChrome chrome, OutboxCourier courier, Actions actions,
            Outbox.Message m) {
        ActionSheet sheet = new ActionSheet(activity, chrome, QueuedMessages.oneLine(m.text));
        boolean here = actions.onScreen(m);
        switch (m.state) {
            case SENDING:
                return;
            case PENDING:
                if (!here) sheet.add("Open its pane to send", () -> actions.open(m));
                if (here) sheet.add("Edit as draft", () -> actions.edit(m));
                sheet.add("Copy", () -> ActionSheet.copy(activity, "Message", m.text));
                sheet.danger("Cancel (not sent)", () -> courier.take(m.id));
                break;
            case NEEDS_YOU:
                if (!here) sheet.add("Open its pane to check", () -> actions.open(m));
                sheet.add("It arrived", () -> courier.take(m.id));
                if (m.reason != Outbox.Reason.GONE) sheet.add("Send again (check the pane first)", () -> courier.resend(m.id));
                if (here) sheet.add("Edit as draft", () -> actions.edit(m));
                sheet.add("Copy", () -> ActionSheet.copy(activity, "Message", m.text));
                sheet.danger("Drop", () -> courier.take(m.id));
                break;
            case SENT:
                if (!here) sheet.add("Open its pane to check", () -> actions.open(m));
                sheet.add("Copy", () -> ActionSheet.copy(activity, "Message", m.text));
                sheet.add("Send again", () -> courier.resend(m.id));
                break;
            default:
                return;
        }
        sheet.show();
    }

    private static int dp(Activity activity, float value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }
}
