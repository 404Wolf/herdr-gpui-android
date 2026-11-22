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
import java.util.Set;

/**
 * ⋮ › Usage: a full-height bottom sheet of the connected machine's aswap
 * accounts (factory-setup.32), drawn from {@link UsageModel}. Read-only: no
 * switching, login or refresh. It draws what {@link UsageFeed} kept for the
 * machine the moment it opens, with its age, and the feed's answers as they
 * come (it asks while the sheet shows, see its pacing); relative times tick
 * each {@link #TICK_MS}; a switch to another machine closes it. Back, a tap
 * outside, a tap on the handle or a swipe down closes it. The raw reply is
 * never logged.
 */
final class UsageView {
    static final long TICK_MS = 30_000;
    /** The accounts' column, centred: wide enough for long bars, never the whole unfolded screen. */
    static final int COLUMN_DP = 560;
    /** The sheet's inset at its sides, top and foot (Wolf: a little more air than the 16dp of the rows' own). */
    static final int SIDE_DP = 18, TOP_DP = 10, FOOT_DP = 14;

    private final Activity activity;
    private final GpuiChrome chrome;
    private final String hostId, machine;
    private final UsageFeed feed;
    private final java.util.function.Consumer<UsageFeed.Answer> heard = this::heard;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Set<String> opened = new HashSet<>();
    private final Dialog dialog;
    private final TextView updated, note, footer;
    private final LinearLayout rows;
    private final UsageModel.Sheet state;
    private boolean closed;
    /** When the sheet opened (elapsed clock), and whether its first fresh rows were logged. */
    private long openedAt;
    private boolean drewFresh;

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (closed) return;
            render();
            main.postDelayed(this, TICK_MS);
        }
    };

    /** Opens the sheet for the machine {@code machine} (saved host {@code hostId}, the feed's selected one). */
    static void show(Activity activity, GpuiChrome chrome, String hostId, String machine, UsageFeed feed) {
        if (activity.isFinishing()) return;
        new UsageView(activity, chrome, hostId, machine, feed).open();
    }

    private UsageView(Activity activity, GpuiChrome chrome, String hostId, String machine, UsageFeed feed) {
        this.activity = activity;
        this.chrome = chrome;
        this.hostId = hostId;
        this.machine = machine;
        this.feed = feed;
        dialog = new Dialog(activity);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout sheet = new LinearLayout(activity);
        sheet.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(chrome.panel);
        float r = dp(16);
        shape.setCornerRadii(new float[] {r, r, r, r, 0, 0, 0, 0});
        sheet.setBackground(shape);

        LinearLayout top = column();
        top.setOrientation(LinearLayout.VERTICAL);
        top.setPadding(dp(SIDE_DP), dp(TOP_DP), dp(SIDE_DP), dp(4));
        // The handle: a 48dp tap target that closes, with the usual grip drawn in it.
        android.widget.FrameLayout handle = new android.widget.FrameLayout(activity);
        View grip = new View(activity);
        GradientDrawable gripShape = new GradientDrawable();
        gripShape.setColor(chrome.raised);
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
        sheet.addView(top, centred());

        rows = column();
        rows.setOrientation(LinearLayout.VERTICAL);
        rows.setPadding(dp(SIDE_DP), 0, dp(SIDE_DP), dp(8));
        ScrollView scroll = new ScrollView(activity);
        scroll.addView(rows, new android.widget.FrameLayout.LayoutParams(-1, -2, Gravity.CENTER_HORIZONTAL));
        sheet.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        footer = text("", 12, chrome.onSurfaceVariant, false);
        footer.setPadding(dp(SIDE_DP), dp(6), dp(SIDE_DP), dp(FOOT_DP));
        footer.setVisibility(View.GONE);
        LinearLayout foot = column();
        foot.addView(footer, new LinearLayout.LayoutParams(-1, -2));
        sheet.addView(foot, centred());

        dialog.setContentView(sheet);
        state = new UsageModel.Sheet(machine);
        dialog.setOnDismissListener(d -> {
            closed = true;
            main.removeCallbacksAndMessages(null);
            feed.unlisten(heard);
            feed.watch(UsageFeed.Watch.SHEET, false);
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
        openedAt = android.os.SystemClock.elapsedRealtime();
        UsageFeed.Answer kept = feed.kept(hostId);
        if (kept != null) showKept(kept);
        // Kept data the rail read moments ago is this open's own: its rows are fresh at once.
        drewFresh = kept != null && state.fresh;
        UsageTiming.log(UsageTiming.drawn(kept == null ? "no" : drewFresh ? "fresh kept" : "kept", 0));
        render();
        dialog.show();
        feed.listen(heard);
        // Watching asks at once unless a look is out or the last one is under the feed's cadence old.
        feed.watch(UsageFeed.Watch.SHEET, true);
        main.postDelayed(tick, TICK_MS);
    }

    /** What the feed kept: fresh when the rail asked moments ago, else shown greyed until this open's answer. */
    private void showKept(UsageFeed.Answer kept) {
        if (UsageModel.Sheet.recent(kept.atMs, System.currentTimeMillis())) state.answered(kept.look, false, kept.atMs);
        else state.opened(kept.look, kept.atMs);
    }

    private void heard(UsageFeed.Answer answer) {
        if (closed || !hostId.equals(answer.hostId)) return;
        long heardAt = android.os.SystemClock.elapsedRealtime();
        switch (answer.outcome) {
            // The phone is on another machine now: this sheet's machine is no longer the selected one.
            case SWITCHED: dialog.dismiss(); return;
            case KEPT: if (state.shown == null) showKept(answer); break;
            case BUSY: state.busy(); break;
            case UNREACHABLE: state.answered(null, true, answer.atMs); break;
            default: state.answered(answer.look, false, answer.atMs); break;
        }
        render();
        if (!drewFresh && answer.outcome == UsageFeed.Outcome.DATA) {
            drewFresh = true;
            long drawn = android.os.SystemClock.elapsedRealtime();
            UsageTiming.log(UsageTiming.drawn("fresh", drawn - openedAt) + UsageTiming.onMain(
                    answer.postedMs > 0 ? heardAt - answer.postedMs : -1, drawn - heardAt));
        }
    }

    /** Redraws every row from the state; the focused row (by its key) keeps focus. */
    private void render() {
        long now = System.currentTimeMillis() / 1000;
        ZoneId zone = ZoneId.systemDefault();
        View focused = rows.findFocus();
        Object focusKey = focused == null ? null : focused.getTag();
        rows.removeAllViews();
        boolean old = state.old();
        updated.setText(state.updated(System.currentTimeMillis()));
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
            row.addView(window(w, resets));
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

    /**
     * The sheet's parts share one centred column, at most {@link #COLUMN_DP}
     * wide: on the unfolded screen the bars stay readable, not stretched.
     */
    private LinearLayout column() {
        LinearLayout column = new LinearLayout(activity) {
            @Override protected void onMeasure(int width, int height) {
                int most = dp(COLUMN_DP);
                if (MeasureSpec.getSize(width) > most) width = MeasureSpec.makeMeasureSpec(most, MeasureSpec.EXACTLY);
                super.onMeasure(width, height);
            }
        };
        column.setOrientation(LinearLayout.VERTICAL);
        return column;
    }

    private static LinearLayout.LayoutParams centred() {
        LinearLayout.LayoutParams at = new LinearLayout.LayoutParams(-1, -2);
        at.gravity = Gravity.CENTER_HORIZONTAL;
        return at;
    }

    /**
     * One window: its short name, a bar across the row's width in its level's
     * colour, its used%, and when it resets under the bar. TalkBack hears the
     * row's own description instead.
     */
    private View window(UsageModel.Window w, String resets) {
        int level = color(UsageModel.level(w.used));
        LinearLayout block = new LinearLayout(activity);
        block.setOrientation(LinearLayout.VERTICAL);
        block.setPadding(0, dp(4), 0, 0);
        block.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        LinearLayout line = new LinearLayout(activity);
        line.setGravity(Gravity.CENTER_VERTICAL);
        TextView name = mono(UsageModel.windowName(w), chrome.onSurfaceVariant);
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        line.addView(name, new LinearLayout.LayoutParams(dp(WINDOW_NAME_DP), -2));
        line.addView(new Bar(UsageModel.fill(w.used), level), new LinearLayout.LayoutParams(0, dp(8), 1));
        TextView used = mono(w.used == null ? "–" : UsageModel.percent(w.used), level);
        used.setGravity(Gravity.END);
        line.addView(used, new LinearLayout.LayoutParams(dp(48), -2));
        block.addView(line);
        if (!resets.isEmpty()) {
            TextView when = mono(resets, chrome.onSurfaceVariant);
            when.setPadding(dp(WINDOW_NAME_DP), 0, 0, 0);
            block.addView(when);
        }
        return block;
    }

    /** A window's name column ("7d sonnet" fits); the bar starts after it. */
    static final int WINDOW_NAME_DP = 72;

    /** A rounded bar: the track dim, the used part in its level's colour. */
    private final class Bar extends View {
        private final float fill;
        private final android.graphics.Paint track = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.Paint used = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.RectF box = new android.graphics.RectF();

        Bar(float fill, int color) {
            super(activity);
            this.fill = fill;
            track.setColor(chrome.raised);
            used.setColor(color);
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        }

        @Override protected void onDraw(android.graphics.Canvas canvas) {
            float r = getHeight() / 2f;
            box.set(0, 0, getWidth(), getHeight());
            canvas.drawRoundRect(box, r, r, track);
            if (fill <= 0) return;
            // At least a dot, so 1% still shows.
            box.right = Math.max(getHeight(), getWidth() * fill);
            canvas.drawRoundRect(box, r, r, used);
        }
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
