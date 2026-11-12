package dev.herdr.android;

import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.drawable.GradientDrawable;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import dev.herdr.android.dictation.DictationEngine;
import java.util.Locale;

/**
 * The one dictation control: a round mic floating over the terminal's bottom
 * right, straight above Enter. Tap to start, tap again to stop. While a session
 * runs it grows leftward into a pill that says what is happening, in the
 * chrome's roles: primary while connecting, danger while recording, info while
 * transcribing, warning while recording offline for the desktop to transcribe
 * later. A warning badge counts the recordings still waiting for the desktop.
 *
 * <p>It floats, so no state changes the terminal's size; the composer places it.
 */
final class MicButton {
    /** How long a one-line note above the pill stays. */
    static final long NOTE_MS = 4_000;
    private static final long TICK_MS = 500;

    private final Context context;
    private final float density;
    private final LinearLayout view;
    private final TextView note;
    private final LinearLayout pill;
    private final ImageView icon;
    private final ProgressBar spinner;
    private final View dot;
    private final TextView label;
    private final ImageView stop;
    private final TextView badge;
    private final ObjectAnimator pulse;
    private GpuiChrome chrome;

    private DictationEngine.State state = DictationEngine.State.IDLE;
    /** When the microphone opened, for the clock; 0 while not recording. */
    private long recordingSince;
    /** -1 until the first count, which is shown without a note. */
    private int waiting = -1;

    MicButton(Context context, GpuiChrome chrome, View.OnClickListener tap) {
        this.context = context;
        this.chrome = chrome;
        density = context.getResources().getDisplayMetrics().density;
        view = new LinearLayout(context);
        view.setOrientation(LinearLayout.VERTICAL);
        view.setGravity(Gravity.END);
        view.setClipChildren(false);
        view.setClipToPadding(false);

        note = new TextView(context);
        note.setTextSize(11);
        note.setSingleLine(true);
        note.setPadding(dp(7), dp(3), dp(7), dp(3));
        note.setVisibility(View.GONE);
        LinearLayout.LayoutParams noteParams = new LinearLayout.LayoutParams(-2, -2);
        noteParams.bottomMargin = dp(4);
        view.addView(note, noteParams);

        FrameLayout frame = new FrameLayout(context);
        frame.setClipChildren(false);
        frame.setClipToPadding(false);
        pill = new LinearLayout(context);
        pill.setOrientation(LinearLayout.HORIZONTAL);
        pill.setGravity(Gravity.CENTER);
        pill.setMinimumWidth(dp(44));
        pill.setOnClickListener(tap);
        pill.setFocusable(false);
        icon = new ImageView(context);
        icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        pill.addView(icon, square(22));
        spinner = new ProgressBar(context);
        spinner.setIndeterminate(true);
        pill.addView(spinner, square(18));
        dot = new View(context);
        pill.addView(dot, square(10));
        label = new TextView(context);
        label.setTextSize(12.5f);
        label.setSingleLine(true);
        label.setIncludeFontPadding(false);
        label.setGravity(Gravity.CENTER_VERTICAL);
        label.setPadding(dp(8), 0, dp(8), 0);
        pill.addView(label, new LinearLayout.LayoutParams(-2, -2));
        stop = new ImageView(context);
        stop.setImageResource(R.drawable.ic_bar_stop);
        stop.setScaleType(ImageView.ScaleType.FIT_CENTER);
        stop.setPadding(dp(4), dp(4), dp(4), dp(4));
        pill.addView(stop, square(26));
        frame.addView(pill, new FrameLayout.LayoutParams(-2, dp(44), Gravity.END));
        badge = new TextView(context);
        badge.setTextSize(10);
        badge.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        badge.setGravity(Gravity.CENTER);
        badge.setIncludeFontPadding(false);
        badge.setMinWidth(dp(16));
        badge.setPadding(dp(4), 0, dp(4), 0);
        badge.setTranslationX(dp(4));
        badge.setTranslationY(-dp(4));
        badge.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        frame.addView(badge, new FrameLayout.LayoutParams(-2, dp(16), Gravity.TOP | Gravity.END));
        view.addView(frame, new LinearLayout.LayoutParams(-2, -2));

        pulse = ObjectAnimator.ofFloat(dot, View.ALPHA, 1f, 0.35f);
        pulse.setDuration(700);
        pulse.setRepeatMode(ValueAnimator.REVERSE);
        pulse.setRepeatCount(ValueAnimator.INFINITE);
        style(chrome);
    }

    View view() { return view; }

    DictationEngine.State state() { return state; }

    void style(GpuiChrome next) {
        chrome = next;
        render();
    }

    void setState(DictationEngine.State next) {
        if (next == state) return;
        boolean wasRecording = recording(state);
        state = next;
        if (recording(next) && !wasRecording) recordingSince = SystemClock.elapsedRealtime();
        if (!recording(next)) recordingSince = 0;
        if (next == DictationEngine.State.OFFLINE_RECORDING) {
            showNote("Saved on the phone; transcribed when the desktop is back");
        }
        render();
    }

    /** Recordings waiting for the desktop, from PendingDictations. */
    void setWaiting(int count) {
        boolean grew = waiting >= 0 && count > waiting;
        waiting = Math.max(0, count);
        if (grew && !recording(state)) showNote(waitingText(waiting));
        render();
    }

    /** A one-line note above the pill, in the warning colour, gone after a few seconds. */
    void showNote(String text) {
        note.setText(text);
        note.setVisibility(View.VISIBLE);
        note.removeCallbacks(hideNote);
        note.postDelayed(hideNote, NOTE_MS);
    }

    void destroy() {
        pulse.cancel();
        label.removeCallbacks(tick);
        note.removeCallbacks(hideNote);
    }

    private final Runnable hideNote = this::hideNote;

    private void hideNote() { note.setVisibility(View.GONE); }

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (recordingSince == 0) return;
            label.setText(text(state, SystemClock.elapsedRealtime() - recordingSince));
            label.postDelayed(this, TICK_MS);
        }
    };

    private void render() {
        DictationEngine.State s = state;
        int accent = accent(s);
        GradientDrawable shape = new GradientDrawable();
        shape.setCornerRadius(dp(22));
        shape.setColor(s == DictationEngine.State.IDLE || s == DictationEngine.State.PENDING
                ? chrome.control : mix(chrome.surface, accent, 0.16f));
        shape.setStroke(Math.max(1, dp(1)), s == DictationEngine.State.IDLE || s == DictationEngine.State.PENDING
                ? chrome.outline : accent);
        pill.setBackground(shape);
        boolean idle = s == DictationEngine.State.IDLE || s == DictationEngine.State.PENDING;
        pill.setPadding(idle ? 0 : dp(10), 0, idle ? 0 : dp(9), 0);

        boolean offline = s == DictationEngine.State.OFFLINE_RECORDING;
        icon.setVisibility(idle || offline ? View.VISIBLE : View.GONE);
        icon.setImageResource(offline ? R.drawable.ic_bar_cloud_off : R.drawable.ic_bar_mic);
        icon.setImageTintList(ColorStateList.valueOf(offline ? chrome.warning : chrome.onSurfaceVariant));

        boolean busy = s == DictationEngine.State.CONNECTING || s == DictationEngine.State.TRANSCRIBING;
        spinner.setVisibility(busy ? View.VISIBLE : View.GONE);
        spinner.setIndeterminateTintList(ColorStateList.valueOf(accent));

        boolean listening = s == DictationEngine.State.LISTENING;
        dot.setVisibility(listening ? View.VISIBLE : View.GONE);
        GradientDrawable round = new GradientDrawable();
        round.setShape(GradientDrawable.OVAL);
        round.setColor(chrome.danger);
        dot.setBackground(round);
        if (listening) {
            if (!pulse.isStarted()) pulse.start();
        } else {
            pulse.cancel();
            dot.setAlpha(1f);
        }

        String words = text(s, recordingSince == 0 ? 0 : SystemClock.elapsedRealtime() - recordingSince);
        label.setVisibility(words.isEmpty() ? View.GONE : View.VISIBLE);
        label.setText(words);
        label.setTextColor(busy ? accent : chrome.onSurface);
        label.removeCallbacks(tick);
        if (recordingSince != 0) label.postDelayed(tick, TICK_MS);

        boolean recording = listening || offline;
        stop.setVisibility(recording ? View.VISIBLE : View.GONE);
        GradientDrawable stopShape = new GradientDrawable();
        stopShape.setShape(GradientDrawable.OVAL);
        stopShape.setColor(accent);
        stop.setBackground(stopShape);
        stop.setImageTintList(ColorStateList.valueOf(chrome.light ? 0xffffffff : chrome.background));

        String count = QueuedMessages.badge(Math.max(0, waiting));
        badge.setVisibility(count.isEmpty() ? View.GONE : View.VISIBLE);
        badge.setText(count);
        GradientDrawable badgeShape = new GradientDrawable();
        badgeShape.setColor(chrome.warning);
        badgeShape.setCornerRadius(dp(8));
        badgeShape.setStroke(dp(2), chrome.background);
        badge.setBackground(badgeShape);
        badge.setTextColor(chrome.light ? 0xffffffff : chrome.background);

        GradientDrawable noteShape = new GradientDrawable();
        noteShape.setColor(chrome.background);
        noteShape.setStroke(Math.max(1, dp(1)), chrome.outline);
        noteShape.setCornerRadius(dp(6));
        note.setBackground(noteShape);
        note.setTextColor(chrome.warning);

        String description = description(s, Math.max(0, waiting));
        pill.setContentDescription(description);
        pill.setTooltipText(description);
    }

    private int accent(DictationEngine.State s) {
        switch (s) {
            case CONNECTING: return chrome.primary;
            case LISTENING: return chrome.danger;
            case TRANSCRIBING: return chrome.info;
            case OFFLINE_RECORDING: return chrome.warning;
            default: return chrome.onSurfaceVariant;
        }
    }

    /** The microphone is open: the clock runs. */
    static boolean recording(DictationEngine.State s) {
        return s == DictationEngine.State.CONNECTING || s == DictationEngine.State.LISTENING
                || s == DictationEngine.State.OFFLINE_RECORDING;
    }

    /** The pill's words for a state; empty for the bare mic. */
    static String text(DictationEngine.State s, long elapsedMs) {
        switch (s) {
            case CONNECTING: return "connecting";
            case LISTENING: return clock(elapsedMs);
            case TRANSCRIBING: return "transcribing";
            case OFFLINE_RECORDING: return "Desktop offline, recording " + clock(elapsedMs);
            default: return "";
        }
    }

    /** What a tap does now, for TalkBack and the tooltip. */
    static String description(DictationEngine.State s, int waiting) {
        String action;
        switch (s) {
            case CONNECTING: action = "Connecting to the desktop; tap to stop"; break;
            case LISTENING: action = "Listening; tap to stop"; break;
            case TRANSCRIBING: action = "Transcribing"; break;
            case OFFLINE_RECORDING: action = "Desktop offline, recording for later; tap to stop"; break;
            default: action = "Dictate into the draft";
        }
        return waiting > 0 ? action + ". " + waitingText(waiting) : action;
    }

    static String waitingText(int count) {
        return count + " waiting for desktop";
    }

    /** m:ss, the clock a recording shows. */
    static String clock(long elapsedMs) {
        long seconds = Math.max(0, elapsedMs) / 1000;
        return String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60);
    }

    /** {@code over} laid on {@code base} at {@code amount}: an opaque tint of the surface. */
    static int mix(int base, int over, float amount) {
        int r = Math.round(((base >> 16) & 0xff) * (1 - amount) + ((over >> 16) & 0xff) * amount);
        int g = Math.round(((base >> 8) & 0xff) * (1 - amount) + ((over >> 8) & 0xff) * amount);
        int b = Math.round((base & 0xff) * (1 - amount) + (over & 0xff) * amount);
        return 0xff000000 | (r << 16) | (g << 8) | b;
    }

    private LinearLayout.LayoutParams square(int sizeDp) {
        return new LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp));
    }

    private int dp(float value) { return Math.round(value * density); }
}
