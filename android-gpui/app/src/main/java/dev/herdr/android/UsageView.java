package dev.herdr.android;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.time.ZoneId;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.function.Supplier;

/**
 * ⋮ › Usage: a full-height bottom sheet of the connected machine's aswap
 * accounts (factory-setup.32), drawn from {@link UsageModel}. Read-only: no
 * switching, login or refresh. It asks the machine on open and every
 * {@link #POLL_MS} while it is showing; relative times tick each
 * {@link #TICK_MS}; nothing runs once it is closed or while the app is paused,
 * and a switch to another machine closes it. Back, a tap outside, a tap on
 * the handle or a swipe down closes it. The raw reply is never logged.
 */
final class UsageView {
    static final long POLL_MS = 60_000, TICK_MS = 30_000;

    /** The last good answer per saved host, with when it came: shown greyed when a later look fails. */
    private static final Map<String, Object[]> LAST_GOOD = new ConcurrentHashMap<>();

    private final Activity activity;
    private final GpuiChrome chrome;
    private final String hostId, machine;
    private final Supplier<String> look;
    private final Executor worker;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Set<String> opened = new HashSet<>();
    private final Dialog dialog;
    private final TextView updated, note, footer;
    private final LinearLayout rows;
    private final UsageModel.Sheet state;
    private final PauseWatch pauses;
    private boolean asking, closed, paused;

    private final Runnable poll = this::ask;
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (closed) return;
            render();
            main.postDelayed(this, TICK_MS);
        }
    };

    /**
     * Opens the sheet for the machine {@code machine} (saved host {@code hostId}).
     * {@code look} sends {@code {"op":"usage.look"}} to that machine's core and
     * returns its reply; it blocks, so it only ever runs on {@code worker}.
     */
    static void show(Activity activity, GpuiChrome chrome, String hostId, String machine,
            Supplier<String> look, Executor worker) {
        if (activity.isFinishing()) return;
        new UsageView(activity, chrome, hostId, machine, look, worker).open();
    }

    private UsageView(Activity activity, GpuiChrome chrome, String hostId, String machine,
            Supplier<String> look, Executor worker) {
        this.activity = activity;
        this.chrome = chrome;
        this.hostId = hostId;
        this.machine = machine;
        this.look = look;
        this.worker = worker;
        dialog = new Dialog(activity);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout sheet = new LinearLayout(activity);
        sheet.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(chrome.surface);
        float r = dp(16);
        shape.setCornerRadii(new float[] {r, r, r, r, 0, 0, 0, 0});
        sheet.setBackground(shape);

        LinearLayout top = new LinearLayout(activity);
        top.setOrientation(LinearLayout.VERTICAL);
        top.setPadding(dp(16), dp(8), dp(16), dp(4));
        // The handle: a 48dp tap target that closes, with the usual grip drawn in it.
        android.widget.FrameLayout handle = new android.widget.FrameLayout(activity);
        View grip = new View(activity);
        GradientDrawable gripShape = new GradientDrawable();
        gripShape.setColor(GpuiChrome.withAlpha(chrome.onSurfaceVariant, 0x66));
        gripShape.setCornerRadius(dp(2));
        grip.setBackground(gripShape);
        handle.addView(grip, new android.widget.FrameLayout.LayoutParams(dp(36), dp(4), Gravity.CENTER));
        handle.setBackground(ActionSheet.ripple(chrome));
        handle.setContentDescription("Close usage");
        handle.setOnClickListener(v -> dialog.dismiss());
        LinearLayout.LayoutParams handleParams = new LinearLayout.LayoutParams(dp(96), dp(48));
        handleParams.gravity = Gravity.CENTER_HORIZONTAL;
        top.addView(handle, handleParams);
        LinearLayout titleRow = new LinearLayout(activity);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = text("Usage · " + machine, 16, chrome.onSurface, true);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        titleRow.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        updated = text("", 12, chrome.onSurfaceVariant, false);
        titleRow.addView(updated);
        titleRow.setMinimumHeight(dp(32));
        top.addView(titleRow, new LinearLayout.LayoutParams(-1, -2));
        note = text("", 13, chrome.warning, false);
        note.setVisibility(View.GONE);
        // Said when it changes ("Can't reach …", "aswap isn't installed …"), not only when found.
        note.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        top.addView(note);
        swipeToClose(top);
        sheet.addView(top, new LinearLayout.LayoutParams(-1, -2));

        rows = new LinearLayout(activity);
        rows.setOrientation(LinearLayout.VERTICAL);
        rows.setPadding(dp(16), 0, dp(16), dp(8));
        ScrollView scroll = new ScrollView(activity);
        scroll.addView(rows);
        sheet.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        footer = text("", 12, chrome.onSurfaceVariant, false);
        footer.setPadding(dp(16), dp(6), dp(16), dp(12));
        footer.setVisibility(View.GONE);
        sheet.addView(footer);

        dialog.setContentView(sheet);
        state = new UsageModel.Sheet(machine);
        // Nothing asks while the app is paused; it asks again on resume.
        pauses = PauseWatch.on(activity, () -> {
            paused = true;
            main.removeCallbacks(poll);
        }, () -> {
            paused = false;
            if (!closed) ask();
        });
        dialog.setOnDismissListener(d -> {
            closed = true;
            main.removeCallbacksAndMessages(null);
            pauses.stop(activity);
        });
    }

    private void open() {
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(0));
            window.setGravity(Gravity.BOTTOM);
            int height = Math.round(activity.getResources().getDisplayMetrics().heightPixels * 0.88f);
            window.setLayout(WindowManager.LayoutParams.MATCH_PARENT, height);
        }
        Object[] cached = LAST_GOOD.get(hostId);
        if (cached != null) state.opened((UsageModel.Look) cached[0], (Long) cached[1]);
        render();
        dialog.show();
        ask();
        main.postDelayed(tick, TICK_MS);
    }

    private void ask() {
        if (closed || asking || paused) return;
        asking = true;
        worker.execute(() -> {
            UsageModel.Look answer = null;
            boolean unreachable = false, switched = false;
            try {
                answer = UsageModel.parse(OpTiming.timed("usage.look", look));
            } catch (HostSwitched gone) {
                switched = true;
            } catch (RuntimeException | LinkageError e) {
                // The cause is never shown: only that the machine could not be asked.
                unreachable = true;
            }
            UsageModel.Look result = answer;
            boolean lost = unreachable, left = switched;
            main.post(() -> {
                // The phone is on another machine now: this sheet's machine is no longer the selected one.
                if (left) { asking = false; dialog.dismiss(); return; }
                answered(result, lost);
            });
        });
    }

    private void answered(UsageModel.Look answer, boolean unreachable) {
        asking = false;
        if (closed) return;
        long now = System.currentTimeMillis();
        state.answered(answer, unreachable, now);
        if (answer != null && answer.kind == UsageModel.Kind.ACCOUNTS) LAST_GOOD.put(hostId, new Object[] {answer, now});
        render();
        main.removeCallbacks(poll);
        if (!paused) main.postDelayed(poll, POLL_MS);
    }

    /** Redraws every row from the state; the focused row (by its key) keeps focus. */
    private void render() {
        long now = System.currentTimeMillis() / 1000;
        ZoneId zone = ZoneId.systemDefault();
        View focused = rows.findFocus();
        Object focusKey = focused == null ? null : focused.getTag();
        rows.removeAllViews();
        boolean old = state.old();
        updated.setText(state.updated(zone));
        String noteText = state.note(now, zone);
        if (!noteText.contentEquals(note.getText())) note.setText(noteText);
        note.setVisibility(noteText.isEmpty() ? View.GONE : View.VISIBLE);
        UsageModel.Look shown = state.shown;
        String foot = shown == null ? "" : shown.footer;
        footer.setText(foot);
        footer.setVisibility(foot.isEmpty() ? View.GONE : View.VISIBLE);
        if (shown == null || shown.kind != UsageModel.Kind.ACCOUNTS) return;
        for (UsageModel.Account a : shown.accounts) rows.addView(row(a, now, zone, old));
        if (focusKey != null) {
            View again = rows.findViewWithTag(focusKey);
            if (again != null) again.requestFocus();
        }
    }

    private View row(UsageModel.Account a, long now, ZoneId zone, boolean old) {
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(0, dp(8), 0, dp(8));
        row.setMinimumHeight(dp(44));
        row.setBackground(ActionSheet.ripple(chrome));
        row.setAlpha(old ? 0.55f : 1f);
        String key = a.provider + "/" + a.label;
        row.setTag(key);

        LinearLayout first = new LinearLayout(activity);
        first.setGravity(Gravity.CENTER_VERTICAL);
        TextView mark = text(a.active ? "●" : "○", 13, a.active ? chrome.primary : chrome.onSurfaceVariant, false);
        mark.setPadding(0, 0, dp(8), 0);
        first.addView(mark);
        TextView label = text(a.label, 15, chrome.onSurface, a.active);
        label.setSingleLine(true);
        label.setEllipsize(TextUtils.TruncateAt.END);
        first.addView(label, new LinearLayout.LayoutParams(0, -2, 1));
        String tags = (a.active ? "active  " : "") + (a.reserved ? "reserved  " : "") + a.provider;
        first.addView(text(tags, 12, chrome.onSurfaceVariant, false));
        Double tight = a.tightest();
        TextView most = text(tight == null ? "" : UsageModel.percent(tight), 15, color(UsageModel.level(tight)), true);
        most.setMinWidth(dp(48));
        most.setGravity(Gravity.END);
        first.addView(most);
        row.addView(first);

        StringBuilder said = new StringBuilder(a.label).append(", ").append(a.provider)
                .append(a.active ? ", active" : "");
        if (tight != null) said.append(", ").append(UsageModel.percent(tight)).append(" used");
        for (UsageModel.Window w : a.windows) {
            String resets = UsageModel.resets(w, now, zone);
            String line = UsageModel.windowName(w) + "  " + UsageModel.bar(w.used) + "  "
                    + (w.used == null ? "–" : UsageModel.percent(w.used));
            row.addView(mono(line, color(UsageModel.level(w.used))));
            if (!resets.isEmpty()) row.addView(mono("    " + resets, chrome.onSurfaceVariant));
            said.append(", ").append(UsageModel.windowName(w)).append(' ')
                    .append(w.used == null ? "unknown" : UsageModel.percent(w.used))
                    .append(resets.isEmpty() ? "" : ", " + resets);
        }
        if (UsageModel.noData(a)) row.addView(mono("no quota data", chrome.onSurfaceVariant));
        String problem = UsageModel.problem(a, now);
        if (!problem.isEmpty()) {
            row.addView(mono("! " + problem, a.hasError ? chrome.danger : chrome.warning));
            said.append(", ").append(problem);
        }
        String stale = UsageModel.staleWords(a, now);
        if (!stale.isEmpty()) {
            row.addView(mono(stale, chrome.warning));
            said.append(", ").append(stale);
        }
        if (!a.duplicates.isEmpty()) {
            String same = "same login as " + String.join(", ", a.duplicates);
            row.addView(mono(same, chrome.onSurfaceVariant));
            said.append(", ").append(same);
        }
        if (opened.contains(key)) {
            String details = "fetched " + when(a.fetchedAt, now) + " · tried " + when(a.lastAttempt, now)
                    + " · next " + when(a.nextPoll, now) + (a.httpStatus > 0 ? " · http " + a.httpStatus : "");
            row.addView(mono(details, chrome.onSurfaceVariant));
            said.append(". ").append(details);
        }
        row.setContentDescription(said + (opened.contains(key) ? ". Tap to hide details" : ". Tap for details"));
        row.setOnClickListener(v -> {
            if (!opened.remove(key)) opened.add(key);
            render();
        });
        return row;
    }

    /** "4m ago", "in 2m", "never". */
    private static String when(long at, long now) {
        if (at <= 0) return "never";
        return at > now ? "in " + UsageModel.span(at - now) : UsageModel.span(now - at) + " ago";
    }

    private int color(UsageModel.Level level) {
        switch (level) {
            case DANGER: return chrome.danger;
            case WARN: return chrome.warning;
            default: return chrome.onSurface;
        }
    }

    /** A swipe down on the sheet's top closes it; a tap there does nothing (only the handle closes). */
    private void swipeToClose(View top) {
        float[] start = new float[1];
        top.setOnTouchListener((v, event) -> {
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) start[0] = event.getRawY();
            if (event.getActionMasked() == MotionEvent.ACTION_UP && event.getRawY() - start[0] > dp(64)) dialog.dismiss();
            // Kept for the whole gesture, so the lift is seen; the title is not a button.
            return true;
        });
    }

    private TextView text(String value, float size, int color, boolean bold) {
        TextView view = ActionSheet.label(activity, value, size, color);
        if (bold) view.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        return view;
    }

    private TextView mono(String value, int color) {
        TextView view = text(value, 12, color, false);
        view.setTypeface(Typeface.MONOSPACE);
        view.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        return view;
    }

    private int dp(float value) {
        return ActionSheet.dp(activity, value);
    }
}
