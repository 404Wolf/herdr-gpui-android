package dev.herdr.android;

import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Read-only local diagnostics, read only when the user asks and only while this
 * screen is visible. Results are shown here and never stored or sent anywhere.
 */
public final class DeviceDiagnosticsActivity extends Activity {
    @Override protected void attachBaseContext(android.content.Context base) {
        super.attachBaseContext(ProvisionedSettings.scaled(base));
    }

    private static final int BG = Color.rgb(16, 18, 22);
    private static final int TEXT = Color.rgb(231, 234, 240);
    private static final int MUTED = Color.rgb(155, 167, 185);
    private static final long TIMEOUT_MS = 5000;
    // One process-wide slot bounds even platform calls that ignore interruption,
    // so leaving and reopening the screen cannot stack reads.
    private static final ThreadPoolExecutor WORKER = new ThreadPoolExecutor(0, 1, 30,
            TimeUnit.SECONDS, new SynchronousQueue<>(), task -> {
                Thread thread = new Thread(task, "herdr-local-diagnostics");
                thread.setDaemon(true);
                return thread;
            });

    private final Handler main = new Handler(Looper.getMainLooper());
    private Button read;
    private TextView status;
    private TextView output;
    private boolean visible;
    private boolean busy;
    /** Fences results: a pause, a timeout or a newer read makes an older result stale. */
    private int generation;

    @Override public void onCreate(Bundle state) {
        super.onCreate(null);
        // Device data is private: masked except in a debuggable build that opts
        // into capture, matching the terminal, its sheets and the companion.
        boolean capturable = ProvisionedSettings.capturable(this);
        if (!capturable) getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        createScreen();
    }

    @Override protected void onResume() {
        super.onResume();
        visible = true;
        status.setText("Nothing is read until you choose Read diagnostics.");
        updateReadButton();
    }

    @Override protected void onPause() {
        visible = false;
        cancelRead();
        super.onPause();
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        // Diagnostic output is deliberately never persisted.
    }

    private void createScreen() {
        LinearLayout frame = column();
        frame.setBackgroundColor(BG);
        frame.setFitsSystemWindows(true);
        LinearLayout root = column();
        root.setPadding(dp(16), dp(12), dp(16), dp(12));
        frame.addView(root, new LinearLayout.LayoutParams(-1, -1));

        root.addView(label("Local device diagnostics", 24, TEXT));
        root.addView(label("Read-only model, Android version, battery and free app storage, shown only on this screen. Nothing is stored or sent to a host.", 15, MUTED));
        status = label("", 14, MUTED);
        root.addView(status);

        read = new Button(this);
        read.setText("Read diagnostics");
        read.setAllCaps(false);
        // The platform disabled tint is all but invisible on this surface; keep
        // the unavailable action readable so it still explains itself.
        read.setTextColor(read.getTextColors().withAlpha(0x9a));
        read.setMinHeight(dp(48));
        privateView(read);
        root.addView(read);

        output = label("", 14, TEXT);
        // The snapshot is JSON: machine text uses the mono face, as in the terminal.
        output.setTypeface(android.graphics.Typeface.MONOSPACE);
        output.setTextIsSelectable(true);
        ScrollView scroll = new ScrollView(this);
        privateView(scroll);
        scroll.setFillViewport(false);
        scroll.addView(output);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(frame);

        read.setOnClickListener(view -> requestRead());
    }

    private void requestRead() {
        if (!visible || busy) return;
        final int request = ++generation;
        busy = true;
        output.setText("");
        status.setText("Reading local diagnostics…");
        updateReadButton();
        final Context application = getApplicationContext();
        final Runnable timeout = () -> {
            if (generation != request) return;
            generation++;
            busy = false;
            status.setText("The diagnostics read timed out. You can try again.");
            updateReadButton();
        };
        try {
            WORKER.execute(() -> {
                JSONObject snapshot;
                try {
                    snapshot = DeviceDiagnostics.snapshot(application);
                } catch (JSONException | RuntimeException unavailable) {
                    snapshot = null;
                }
                final JSONObject result = snapshot;
                main.post(() -> show(request, timeout, result));
            });
        } catch (RejectedExecutionException occupied) {
            // A read from before this screen paused still holds the only slot.
            generation++;
            busy = false;
            status.setText("A previous read is still finishing. Try again shortly.");
            updateReadButton();
            return;
        }
        main.postDelayed(timeout, TIMEOUT_MS);
    }

    private void show(int request, Runnable timeout, JSONObject snapshot) {
        if (generation != request) return;
        main.removeCallbacks(timeout);
        busy = false;
        if (snapshot == null) {
            status.setText("Local diagnostics are unavailable right now. You can try again.");
        } else {
            try {
                output.setText(snapshot.toString(2));
                status.setText("Read on this device just now. Nothing was stored or sent.");
            } catch (JSONException unavailable) {
                status.setText("Local diagnostics are unavailable right now. You can try again.");
            }
        }
        updateReadButton();
    }

    /** Leaving the screen discards any pending result and clears what was shown. */
    private void cancelRead() {
        generation++;
        busy = false;
        main.removeCallbacksAndMessages(null);
        if (output != null) output.setText("");
        updateReadButton();
    }

    private void updateReadButton() {
        if (read != null) read.setEnabled(visible && !busy);
    }

    private LinearLayout column() {
        LinearLayout result = new LinearLayout(this);
        result.setOrientation(LinearLayout.VERTICAL);
        privateView(result);
        return result;
    }

    private TextView label(String text, int size, int color) {
        TextView result = new TextView(this);
        result.setText(text);
        result.setTextSize(size);
        result.setTextColor(color);
        result.setPadding(0, dp(6), 0, dp(6));
        privateView(result);
        return result;
    }

    private void privateView(View view) {
        view.setSaveEnabled(false);
        view.setSaveFromParentEnabled(false);
        view.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
        if (Build.VERSION.SDK_INT >= 30) {
            view.setImportantForContentCapture(View.IMPORTANT_FOR_CONTENT_CAPTURE_NO_EXCLUDE_DESCENDANTS);
        }
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
