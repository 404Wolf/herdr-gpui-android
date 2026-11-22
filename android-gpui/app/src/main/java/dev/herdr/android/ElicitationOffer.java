package dev.herdr.android;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONException;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * Runs {@link ElicitationPolicy}'s decisions: reads the pane on {@link #worker}, never the UI
 * thread, every {@link #CHECK_MILLIS} while the policy wants it, and shows or closes
 * {@link ElicitationSheet}. Opening the sheet sends nothing. Main thread only.
 */
final class ElicitationOffer {
    static final long CHECK_MILLIS = 400;
    static final String TAG = "herdr-elicit";

    private final Activity activity;
    private final Handler main = new Handler(Looper.getMainLooper());
    /** Reads panes' grids for the offer, the sheet and its runs: one at a time, off the UI thread. */
    private final ExecutorService worker = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "elicitation");
        thread.setDaemon(true);
        return thread;
    });
    private final ElicitationPolicy policy = new ElicitationPolicy();
    private final Runnable check = this::check;
    /** The activity's theme, null until the renderer's first frame. */
    private final Supplier<GpuiChrome> chrome;
    /** Why the user is busy (typing, a draft, dictating), or null: the sheet waits while busy. */
    private final Supplier<String> busy;
    /** The last busy reason logged, so a wait is logged once. */
    private String loggedBusy;
    /** Whether screenshots may show the sheet (the app's capturable setting). */
    private final BooleanSupplier capturable;
    private ElicitationSheet sheet;
    /** The last sheet's answers and whose they were ({@link ElicitationPolicy#answersKey}). */
    private Elicitation answers = new Elicitation();
    private String answersKey = "";
    private ElicitationSheet.Pane target;
    /** Reads logged for the pane on screen: the first few, to show the offer is alive. */
    private int traced;

    /** Run as the sheet opens: puts away a keyboard the app raised by itself. */
    private final Runnable opening;

    ElicitationOffer(Activity activity, Supplier<GpuiChrome> chrome, Supplier<String> busy, BooleanSupplier capturable,
            Runnable opening) {
        this.opening = opening;
        this.activity = activity;
        this.chrome = chrome;
        this.busy = busy;
        this.capturable = capturable;
    }

    /**
     * Main thread, when anything here changed. {@code where} names the connection, pane and
     * attach {@code target} reads and types into; {@code status} is null while the pane cannot
     * take input (not ready, or not the pane on screen).
     */
    void observe(String where, String status, String agentKind, ElicitationSheet.Pane target) {
        // Logged without the screen: which pane and its state, never what it shows.
        android.util.Log.i(TAG, "pane " + where + " status " + (status == null ? "(not live)" : status) + " kind " + agentKind);
        if (!where.equals(policy.where())) traced = 0;
        if (policy.observe(where, status, agentKind)) {
            android.util.Log.i(TAG, "pane changed: sheet closed");
            close();
        }
        this.target = target;
        check();
    }

    private boolean ready() {
        return chrome.get() != null && target != null && !activity.isFinishing();
    }

    private boolean busy() {
        String why = busy.get();
        if (why != null && !why.equals(loggedBusy)) android.util.Log.i(TAG, "waiting: " + why);
        loggedBusy = why;
        return why != null;
    }

    private void check() {
        main.removeCallbacks(check);
        switch (policy.check(ready(), busy())) {
            case READ:
                read();
                return;
            case LATER:
                main.postDelayed(check, CHECK_MILLIS);
                return;
            case IDLE:
            default:
        }
    }

    private void read() {
        String at = policy.where();
        ElicitationSheet.Pane pane = target;
        boolean trace = traced++ < 3;
        try {
            worker.execute(() -> {
                String json;
                long started = android.os.SystemClock.uptimeMillis();
                try {
                    if (!pane.attached()) {
                        main.post(() -> {
                            android.util.Log.i(TAG, "no renderer for " + at + ": waiting for the next attach");
                            if (policy.detached(at) == ElicitationPolicy.Landed.CHECK) check();
                        });
                        return;
                    }
                    json = pane.parse();
                } catch (RuntimeException | LinkageError error) {
                    android.util.Log.w(TAG, "reading " + at + " failed: " + error);
                    json = null;
                }
                if (trace) {
                    android.util.Log.i(TAG, "read " + at + " in " + (android.os.SystemClock.uptimeMillis() - started)
                            + " ms: " + (json == null ? "no dialog" : "a dialog"));
                }
                String read = json;
                main.post(() -> landed(at, read));
            });
        } catch (RejectedExecutionException closed) {
            policy.readFailed();   // destroyed: no more reads
        }
    }

    private void landed(String at, String json) {
        Elicitation.Dialog dialog = null;
        try {
            dialog = Elicitation.parse(json);
        } catch (JSONException | RuntimeException error) {
            android.util.Log.w(TAG, "unreadable dialog on " + at + ": " + error.getMessage());
        }
        boolean typing = busy();
        switch (policy.landed(at, dialog, ready(), typing)) {
            case OPEN:
                android.util.Log.i(TAG, "offered " + dialog.harness + " " + dialog.kind + " on " + at);
                String key = ElicitationPolicy.answersKey(at, dialog);
                if (!key.equals(answersKey)) {
                    answers = new Elicitation();
                    answersKey = key;
                }
                sheet = new ElicitationSheet(activity, chrome.get(), target, worker, capturable.getAsBoolean(), answers);
                sheet.setOnClosed(() -> {
                    policy.sheetClosed();
                    main.postDelayed(check, CHECK_MILLIS);
                });
                opening.run();
                sheet.show(dialog);
                return;
            case CHECK:
                check();
                return;
            case LATER:
            default:
                main.postDelayed(check, CHECK_MILLIS);
        }
    }

    /** Closes the sheet; a run in progress stops before its next key. */
    void close() {
        if (sheet != null) sheet.dismiss();
        sheet = null;
    }

    /** The activity is going away: nothing more is read. */
    void destroy() {
        main.removeCallbacks(check);
        close();
        target = null;
        worker.shutdownNow();
    }
}
