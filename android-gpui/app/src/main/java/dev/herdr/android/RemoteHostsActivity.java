package dev.herdr.android;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.InsetDrawable;
import android.graphics.drawable.RippleDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.json.JSONArray;
import org.json.JSONObject;

/** SSH login and pane selection. Credentials never enter a profile, Intent or saved state. */
public final class RemoteHostsActivity extends Activity {
    private final Handler main = new Handler();
    private final ThreadPoolExecutor worker = new ThreadPoolExecutor(1, 1, 0L,
            TimeUnit.MILLISECONDS, new ArrayBlockingQueue<Runnable>(8));
    private volatile int generation;
    private volatile boolean visible;
    private RemoteHostStore store;
    private RemoteIdentityStore identityStore;
    private boolean identityPresent;
    /** Colours of the terminal theme, so this list and the terminal look like one app. */
    private GpuiChrome chrome;
    private int appliedPalette = -1;
    private TextView title;
    private TextView subtitle;
    private ImageView logo;
    private ImageButton back;
    private Button addHost;
    /** Where Back and the header arrow go; null on the host list itself. */
    private Runnable backAction;
    private final BackOwner backOwner = new BackOwner(this, () -> {
        if (backAction != null) backAction.run();
    });
    private LinearLayout statusRow;
    private ProgressBar statusProgress;
    private ImageView statusIcon;
    private LinearLayout content;
    private TextView status;
    private Tone statusTone;
    private AlertDialog dialog;
    private EditText password;
    private EditText keyPassphrase;
    private EditText identityInput;
    private char[] pendingSecret;
    private char[] pendingPassphrase;
    private SavedRemoteHost selected;
    private volatile long ownedHandle;
    private boolean openingPane;
    private final LaunchReopen launchReopen = new LaunchReopen();
    /** A message that must survive the asynchronous profile render. */
    private String pendingStatus;
    private Tone pendingTone = Tone.NOTE;
    private String paneSnapshot = "";
    private static final int REQUEST_EXPORT_HOSTS = 41;
    private static final int REQUEST_IMPORT_HOSTS = 42;
    /** A hosts file the system picker returned, handled once this screen resumes. */
    private int pendingFileRequest;
    private Uri pendingFileUri;
    private int savedHostCount;

    /** How a status line reads: work under way, a neutral note, or a failure. */
    private enum Tone { PROGRESS, NOTE, ERROR }

    @Override public void onCreate(Bundle state) {
        super.onCreate(null);
        // Matches the terminal: only a debuggable build that opts in may be captured.
        boolean capturable = (getApplicationInfo().flags
                & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
                && "1".equals(android.provider.Settings.Global.getString(
                        getContentResolver(), "herdr_probe"));
        if (!capturable) getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        store = new RemoteHostStore(this);
        identityStore = new RemoteIdentityStore(this);
        try {
            // The theme's colours come from the renderer, which SSH needs as well.
            RemoteNative.ensureLoaded(this);
        } catch (RuntimeException | LinkageError unavailable) {
            TextView failed = new TextView(this);
            failed.setText("The GPUI renderer could not be loaded. Reinstall the app to connect to a host.");
            failed.setPadding(dp(24), dp(48), dp(24), dp(24));
            failed.setTextSize(16);
            failed.setFitsSystemWindows(true);
            setContentView(failed);
            return;
        }
        shell(currentPalette());
    }

    @Override protected void onResume() {
        super.onResume();
        if (content == null) return;
        visible = true;
        // The theme is chosen in the terminal; coming back must not show the old one.
        int palette = currentPalette();
        if (palette != appliedPalette) shell(palette);
        // Reopening should land in the terminal, not this list; the list is the
        // fallback when the remembered session cannot be restored. Returning
        // from the file picker is not a launch, so it never reopens one.
        int fileRequest = pendingFileRequest;
        Uri fileUri = pendingFileUri;
        pendingFileRequest = 0;
        pendingFileUri = null;
        boolean skip = fileUri != null
                || getIntent() != null && getIntent().getBooleanExtra("skip_reopen", false);
        if (launchReopen.firstResume(() -> !skip && reopenEnabled() && queueReopen())) return;
        showProfiles();
        if (fileUri != null) handleHostFile(fileRequest, fileUri);
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != REQUEST_EXPORT_HOSTS && request != REQUEST_IMPORT_HOSTS) return;
        if (result != RESULT_OK || data == null || data.getData() == null) return;
        // This arrives before onResume, while work is still fenced off.
        pendingFileRequest = request;
        pendingFileUri = data.getData();
    }

    @Override protected void onPause() {
        visible = false;
        if (content == null) {
            super.onPause();
            return;
        }
        cancelConnection();
        dismissDialog();
        content.removeAllViews();
        super.onPause();
    }

    @Override protected void onDestroy() {
        cancelConnection();
        worker.shutdownNow();
        super.onDestroy();
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        // Deliberately omit view state, passwords, edit drafts and remote content.
    }

    @SuppressWarnings("deprecation")
    @Override public void onBackPressed() {
        // A host check, sign-in or pane choice returns to the list, as its arrow does.
        if (backAction != null) {
            backAction.run();
            return;
        }
        super.onBackPressed();
    }

    /** The palette the saved terminal theme paints with now, resolved as the terminal does. */
    private int currentPalette() {
        int theme = getSharedPreferences("terminal-display", MODE_PRIVATE).getInt("theme", 0);
        boolean dark = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
        return GpuiDisplayOptions.palette(theme, dark);
    }

    /**
     * Header, status line and scrolling body in the theme's colours. A wide
     * panel keeps a readable centred column instead of stretching every row.
     */
    private void shell(int palette) {
        appliedPalette = palette;
        chrome = GpuiChrome.load(palette);
        getWindow().setBackgroundDrawable(new ColorDrawable(chrome.background));
        LinearLayout root = column(this);
        root.setBackgroundColor(chrome.background);
        // fitSystemWindows overwrites any padding set on the same view, which left
        // every line flush to the rounded edge; the inset belongs on the content.
        root.setFitsSystemWindows(true);
        LinearLayout page = column(this);
        int gutter = dp(wideWindow() ? 24 : 16);
        page.setPadding(gutter, 0, gutter, 0);
        LinearLayout.LayoutParams pageParams = new LinearLayout.LayoutParams(readableWidth(), 0, 1);
        pageParams.gravity = Gravity.CENTER_HORIZONTAL;
        root.addView(page, pageParams);
        page.addView(header());
        page.addView(statusLine());
        ScrollView scroll = new ScrollView(this);
        scroll.setClipToPadding(false);
        scroll.setVerticalScrollBarEnabled(false);
        privateView(scroll);
        content = column(this);
        content.setPadding(0, dp(4), 0, dp(24));
        scroll.addView(content, new android.widget.FrameLayout.LayoutParams(-1, -2));
        page.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root);
        // The insets controller belongs to the decor view, which exists only now.
        applySystemBars();
        // Reopening the last session shows its progress under the list's header.
        showListHeader();
    }

    /** The whole width up to 720 dp, then a centred column of that width. */
    private int readableWidth() {
        return getResources().getConfiguration().screenWidthDp > 720
                ? dp(720) : LinearLayout.LayoutParams.MATCH_PARENT;
    }

    /** Room for a host's actions beside its name rather than under it. */
    private boolean wideWindow() {
        return getResources().getConfiguration().screenWidthDp >= 600;
    }

    /**
     * Edge to edge, the bars show the page behind them; their icons flip with
     * the theme's lightness to stay visible.
     */
    private void applySystemBars() {
        getWindow().setStatusBarColor(chrome.background);
        getWindow().setNavigationBarColor(chrome.background);
        if (Build.VERSION.SDK_INT >= 30) {
            WindowInsetsController controller = getWindow().getInsetsController();
            int light = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                    | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
            if (controller != null) controller.setSystemBarsAppearance(chrome.light ? light : 0, light);
        } else {
            View decor = getWindow().getDecorView();
            int light = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            decor.setSystemUiVisibility((decor.getSystemUiVisibility() & ~light) | (chrome.light ? light : 0));
        }
    }

    /** The app mark or a back arrow, the screen's title, and Add host on the list. */
    private LinearLayout header() {
        LinearLayout bar = row(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(0, dp(12), 0, dp(12));
        logo = new ImageView(this);
        logo.setImageResource(R.drawable.ic_herdr);
        logo.setBackground(rounded(chrome.surface, 12));
        logo.setClipToOutline(true);
        logo.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        privateView(logo);
        LinearLayout.LayoutParams logoParams = new LinearLayout.LayoutParams(dp(40), dp(40));
        logoParams.setMarginEnd(dp(16));
        bar.addView(logo, logoParams);
        back = iconButton(R.drawable.ic_home_back, chrome.onSurface, "Back", "", view -> {
            if (backAction != null) backAction.run();
        });
        LinearLayout.LayoutParams backParams = new LinearLayout.LayoutParams(dp(48), dp(48));
        backParams.setMarginStart(-dp(12));
        backParams.setMarginEnd(dp(4));
        bar.addView(back, backParams);
        LinearLayout titles = column(this);
        title = text(this, "", wideWindow() ? 28 : 24, chrome.onSurface);
        title.setAccessibilityHeading(true);
        title.setMaxLines(2);
        title.setEllipsize(TextUtils.TruncateAt.END);
        subtitle = text(this, "", 14, chrome.onSurfaceVariant);
        subtitle.setMaxLines(2);
        subtitle.setEllipsize(TextUtils.TruncateAt.END);
        titles.addView(title);
        titles.addView(subtitle);
        bar.addView(titles, new LinearLayout.LayoutParams(0, -2, 1));
        addHost = tonalButton("Add host", () -> editProfile(null));
        withIcon(addHost, R.drawable.ic_home_add, chrome.onSurface);
        LinearLayout.LayoutParams addParams = new LinearLayout.LayoutParams(-2, dp(48));
        addParams.setMarginStart(dp(12));
        bar.addView(addHost, addParams);
        return bar;
    }

    private void showListHeader() {
        showHeader("Remote hosts", "SSH credentials are used once and cleared on close or pause.", null);
    }

    /**
     * The list's header, or a step's: a step has a back arrow that cancels it
     * and returns to the list, described for screen readers by {@code backLabel}.
     */
    private void showHeader(String heading, String detail, String backLabel) {
        boolean list = backLabel == null;
        backAction = list ? null : this::showProfiles;
        backOwner.setOwned(!list);
        logo.setVisibility(list ? View.VISIBLE : View.GONE);
        back.setVisibility(list ? View.GONE : View.VISIBLE);
        back.setContentDescription(backLabel);
        addHost.setVisibility(list ? View.VISIBLE : View.GONE);
        title.setText(heading);
        subtitle.setText(detail);
        subtitle.setVisibility(detail.isEmpty() ? View.GONE : View.VISIBLE);
    }

    /**
     * A progress spinner or an icon beside one line that screen readers
     * announce. The spinner is made on first use; most visits never need one.
     */
    private LinearLayout statusLine() {
        statusRow = row(this);
        statusRow.setGravity(Gravity.CENTER_VERTICAL);
        statusRow.setVisibility(View.GONE);
        statusProgress = null;
        statusIcon = new ImageView(this);
        statusIcon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        statusRow.addView(statusIcon, new LinearLayout.LayoutParams(dp(20), dp(20)));
        status = text(this, "", 14, chrome.onSurfaceVariant);
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        LinearLayout.LayoutParams textParams = new LinearLayout.LayoutParams(0, -2, 1);
        textParams.setMarginStart(dp(12));
        statusRow.addView(status, textParams);
        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(-1, -2);
        rowParams.bottomMargin = dp(12);
        statusRow.setLayoutParams(rowParams);
        return statusRow;
    }

    private static final String STARTUP = "remote-startup";

    static boolean reopenEnabled(android.content.Context context) {
        return context.getSharedPreferences(STARTUP, MODE_PRIVATE).getBoolean("reopen", true);
    }

    private boolean reopenEnabled() { return reopenEnabled(this); }

    /** Remembered host and pane for the next launch, written when a pane opens. */
    static void rememberLastSession(android.content.Context context, SavedRemoteHost host, String paneId) {
        if (host == null || paneId == null || paneId.isEmpty()) return;
        context.getSharedPreferences(STARTUP, MODE_PRIVATE).edit()
                .putString("last_host", host.id)
                .putString("last_pane", paneId)
                .apply();
    }

    /** Starts the remembered session on the worker; returns false when nothing is remembered. */
    private boolean queueReopen() {
        android.content.SharedPreferences prefs = getSharedPreferences(STARTUP, MODE_PRIVATE);
        String hostId = prefs.getString("last_host", "");
        String paneId = prefs.getString("last_pane", "");
        if (hostId.isEmpty() || paneId.isEmpty()) return false;
        setStatus(Tone.PROGRESS, "Reopening the last session…");
        int token = generation;
        submit(() -> {
            if (!current(token)) return;
            SavedRemoteHost host = null;
            try {
                for (SavedRemoteHost candidate : store.read()) {
                    if (candidate.id.equals(hostId)) host = candidate;
                }
            } catch (Exception unreadable) {
                host = null;
            }
            if (host == null || host.expectedFingerprint.isEmpty() || !identityStore.exists()) {
                String reason = host == null ? "The remembered host is gone"
                        : host.expectedFingerprint.isEmpty() ? "The remembered host is no longer trusted"
                        : "No saved SSH identity on this device";
                main.post(() -> {
                    if (!current(token)) return;
                    pendingStatus = reason + "; pick a host to continue";
                    pendingTone = Tone.NOTE;
                    showProfiles();
                });
                return;
            }
            // A remembered host can miss a single network hiccup; retry once, then
            // fall back to another saved host so the app still lands in a terminal.
            java.util.List<SavedRemoteHost> candidates = new java.util.ArrayList<>();
            candidates.add(host);
            candidates.add(host);
            try {
                for (SavedRemoteHost other : store.read()) {
                    if (!other.id.equals(host.id) && !other.expectedFingerprint.isEmpty()) {
                        candidates.add(other);
                    }
                }
            } catch (Exception ignored) { }
            String failureReason = "the host did not answer";
            for (int attempt = 0; attempt < candidates.size(); attempt++) {
                if (!current(token)) return;
                SavedRemoteHost candidate = candidates.get(attempt);
                String wanted = candidate.id.equals(host.id) ? paneId : "";
                if (attempt > 0) {
                    try { Thread.sleep(1200); } catch (InterruptedException stopped) { return; }
                }
                String outcome = openCandidate(candidate, wanted, token);
                if (outcome == null) return;
                failureReason = outcome;
            }
            final String reason = failureReason;
            main.post(() -> {
                if (!current(token)) return;
                pendingStatus = "No session could be opened (" + reason + "); pick a host to continue";
                pendingTone = Tone.ERROR;
                showProfiles();
            });
        });
        return true;
    }

    /** Tries one host. Returns null once the terminal is launched, else the reason. */
    private String openCandidate(SavedRemoteHost host, String wantedPane, int token) {
        long handle = 0;
        try {
            RemoteNative.ensureLoaded(this);
            JSONObject inspection = result(RemoteNative.inspect(host.connectionJson()));
            String key = SavedRemoteHost.fingerprint(inspection.getString("fingerprint"));
            if (!host.expectedFingerprint.equals(key)) throw new IllegalStateException("host key changed");
            char[] secret = identityStore.read();
            String credential;
            try {
                credential = new JSONObject().put("private_key", new String(secret)).toString();
            } finally {
                Arrays.fill(secret, '\0');
            }
            handle = RemoteNative.connect(host.connectionJson(), credential);
            if (handle <= 0) throw new IllegalStateException("connect refused");
            String focused = reopenPane(handle, wantedPane);
            final long readyHandle = handle;
            final String readyPane = focused;
            main.post(() -> {
                if (!current(token)) {
                    RemoteNative.close(readyHandle);
                    return;
                }
                try {
                    GpuiLaunch.launchRemote(this, host, readyHandle, readyPane, readyPane);
                    rememberLastSession(this, host, readyPane);
                    // Stay on the back stack: the terminal's Back button and the
                    // system gesture both promise a return to this host list.
                } catch (Exception | LinkageError failure) {
                    RemoteNative.close(readyHandle);
                }
            });
            handle = 0;
            return null;
        } catch (Exception | LinkageError failure) {
            if (handle != 0) RemoteNative.close(handle);
            String message = failure.getMessage();
            return host.name + ": " + (message == null || message.isEmpty() ? "unreachable" : message);
        }
    }

    /** Waits for the session and focuses the remembered pane, or a live one. */
    private String reopenPane(long handle, String remembered) throws Exception {
        long until = System.currentTimeMillis() + 30_000;
        JSONObject state = null;
        while (System.currentTimeMillis() < until) {
            state = new JSONObject(RemoteNative.poll(handle, 0, false));
            String phase = state.optString("state");
            if ("closed".equals(phase)) throw new IllegalStateException("closed");
            org.json.JSONArray panes = state.optJSONArray("panes");
            if ("ready".equals(phase) || panes != null && panes.length() > 0) break;
            Thread.sleep(150);
        }
        org.json.JSONArray panes = state == null ? null : state.optJSONArray("panes");
        if (panes == null || panes.length() == 0) throw new IllegalStateException("no panes");
        String pane = "";
        for (int i = 0; i < panes.length(); i++) {
            if (remembered.equals(panes.getJSONObject(i).optString("id", ""))) pane = remembered;
        }
        for (int i = 0; i < panes.length() && pane.isEmpty(); i++) {
            String status = panes.getJSONObject(i).optString("status", "");
            if (!"unknown".equals(status) && !"idle".equals(status)) pane = panes.getJSONObject(i).optString("id", "");
        }
        if (pane.isEmpty()) pane = panes.getJSONObject(0).optString("id", "");
        RemoteNative.focus(handle, pane);
        until = System.currentTimeMillis() + 20_000;
        while (System.currentTimeMillis() < until) {
            state = new JSONObject(RemoteNative.poll(handle, 0, false));
            if (pane.equals(state.optString("pane_id")) && "ready".equals(state.optString("state"))) break;
            Thread.sleep(150);
        }
        return pane;
    }

    private void cancelConnection() {
        generation++;
        // Let fenced callbacks run: a late connect callback must close its unclaimed handle.
        worker.getQueue().clear();
        clearSecret();
        long old = ownedHandle;
        ownedHandle = 0;
        selected = null;
        paneSnapshot = "";
        openingPane = false;
        if (old != 0) RemoteNative.close(old);
    }

    private synchronized void clearSecret() {
        if (pendingSecret != null) Arrays.fill(pendingSecret, '\0');
        pendingSecret = null;
        if (pendingPassphrase != null) Arrays.fill(pendingPassphrase, '\0');
        pendingPassphrase = null;
        if (password != null) password.setText("");
        if (keyPassphrase != null) keyPassphrase.setText("");
        if (identityInput != null) identityInput.setText("");
    }

    private void dismissDialog() {
        clearSecret();
        if (dialog != null) {
            dialog.dismiss();
            dialog = null;
        }
        password = null;
        keyPassphrase = null;
    }

    private void showProfiles() {
        cancelConnection();
        dismissDialog();
        content.removeAllViews();
        showListHeader();
        int token = generation;
        // Profiles usually load within a frame; a spinner only for a slow read
        // keeps the list from jumping down and back on every visit.
        clearStatus();
        Runnable slow = () -> { if (current(token)) setStatus(Tone.PROGRESS, "Loading encrypted profiles…"); };
        main.postDelayed(slow, 300);
        run(token, () -> new ProfilesSnapshot(store.read(), identityStore.exists()), snapshot -> {
            main.removeCallbacks(slow);
            identityPresent = snapshot.identityPresent;
            renderProfiles(snapshot.hosts);
        }, failure -> {
            main.removeCallbacks(slow);
            setStatus(Tone.ERROR, failure);
        });
    }

    private void renderProfiles(List<SavedRemoteHost> hosts) {
        content.removeAllViews();
        if (pendingStatus != null) {
            String message = pendingStatus;
            pendingStatus = null;
            setStatus(pendingTone, message);
        } else {
            clearStatus();
        }
        // The hosts are why this screen exists, so they lead; settings follow.
        savedHostCount = hosts.size();
        if (hosts.isEmpty()) content.addView(emptyHosts(), cardParams());
        for (SavedRemoteHost host : hosts) content.addView(hostCard(host), cardParams());
        content.addView(sectionLabel("This device"));
        content.addView(deviceCard(), cardParams());
    }

    private LinearLayout.LayoutParams cardParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.bottomMargin = dp(12);
        return params;
    }

    /** No hosts yet: what one is, and the action that adds it. */
    private LinearLayout emptyHosts() {
        LinearLayout card = card();
        card.setGravity(Gravity.CENTER_HORIZONTAL);
        card.setPadding(dp(24), dp(32), dp(24), dp(24));
        ImageView icon = icon(R.drawable.ic_home_dns, chrome.primary);
        card.addView(icon, new LinearLayout.LayoutParams(dp(40), dp(40)));
        TextView heading = medium(text(this, "No hosts yet", 18, chrome.onSurface));
        heading.setGravity(Gravity.CENTER);
        heading.setPadding(0, dp(16), 0, dp(4));
        card.addView(heading);
        TextView body = text(this, "Add a computer that runs Herdr and accepts SSH from this phone.",
                14, chrome.onSurfaceVariant);
        body.setGravity(Gravity.CENTER);
        card.addView(body);
        Button add = primaryButton("Add host", () -> editProfile(null));
        LinearLayout.LayoutParams addParams = new LinearLayout.LayoutParams(-2, dp(48));
        addParams.topMargin = dp(16);
        card.addView(add, addParams);
        // Moving to this app from another install starts here.
        Button importFile = textButton("Import from a file", chrome.primary, this::importHosts);
        LinearLayout.LayoutParams importParams = new LinearLayout.LayoutParams(-2, dp(48));
        importParams.topMargin = dp(4);
        card.addView(importFile, importParams);
        return card;
    }

    /**
     * One host: who it is, whether its key is pinned, one filled primary action,
     * and everything else in its overflow menu, so "Delete profile" never sits
     * beside "Connect". A narrow panel moves Connect under the name.
     */
    private LinearLayout hostCard(SavedRemoteHost host) {
        boolean wide = wideWindow();
        LinearLayout card = card();
        card.setPadding(dp(16), dp(16), dp(4), dp(16));
        LinearLayout top = row(this);
        top.setGravity(wide ? Gravity.CENTER_VERTICAL : Gravity.TOP);
        top.addView(avatar(host.name), new LinearLayout.LayoutParams(dp(40), dp(40)));
        LinearLayout about = column(this);
        TextView name = medium(text(this, host.name, 18, chrome.onSurface));
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        about.addView(name);
        TextView address = text(this, address(host), 14, chrome.onSurfaceVariant);
        address.setSingleLine(true);
        address.setEllipsize(TextUtils.TruncateAt.END);
        address.setPadding(0, dp(2), 0, dp(6));
        about.addView(address);
        about.addView(trustLine(host));
        LinearLayout.LayoutParams aboutParams = new LinearLayout.LayoutParams(0, -2, 1);
        aboutParams.setMarginStart(dp(16));
        top.addView(about, aboutParams);
        Button connect = primaryButton("Connect", () -> inspectHost(host));
        connect.setContentDescription("Connect to " + host.name);
        ImageButton more = iconButton(R.drawable.ic_bar_more, chrome.onSurfaceVariant, "More",
                "More actions for " + host.name, view -> showHostActions(view, host));
        if (wide) {
            LinearLayout.LayoutParams connectParams = new LinearLayout.LayoutParams(-2, dp(48));
            connectParams.setMarginStart(dp(12));
            top.addView(connect, connectParams);
            top.addView(more, new LinearLayout.LayoutParams(dp(48), dp(48)));
            card.addView(top);
            return card;
        }
        LinearLayout.LayoutParams moreParams = new LinearLayout.LayoutParams(dp(48), dp(48));
        moreParams.topMargin = -dp(8);
        top.addView(more, moreParams);
        card.addView(top);
        LinearLayout.LayoutParams connectParams = new LinearLayout.LayoutParams(-1, dp(48));
        connectParams.setMarginStart(dp(56));
        connectParams.setMarginEnd(dp(12));
        connectParams.topMargin = dp(8);
        card.addView(connect, connectParams);
        return card;
    }

    /** A host in one line: its account and session, or its Iroh endpoint. */
    private static String address(SavedRemoteHost host) {
        return "tcp".equals(host.transport)
                ? host.username + "@" + host.host + ":" + host.port + " · " + host.session
                : "Iroh · " + host.endpointId + " · " + host.session;
    }

    /**
     * The pinned fingerprint in full (ellipsized on screen only), or a warning
     * that the next connection will ask to trust a key.
     */
    private LinearLayout trustLine(SavedRemoteHost host) {
        boolean trusted = !host.expectedFingerprint.isEmpty();
        LinearLayout line = row(this);
        line.setGravity(Gravity.CENTER_VERTICAL);
        int color = trusted ? chrome.onSurfaceVariant : chrome.warning;
        line.addView(icon(trusted ? R.drawable.ic_home_verified : R.drawable.ic_home_warning,
                trusted ? chrome.primary : chrome.warning), new LinearLayout.LayoutParams(dp(16), dp(16)));
        TextView key = text(this, trusted ? host.expectedFingerprint : "Host key not trusted yet",
                trusted ? 12 : 13, color);
        if (trusted) key.setTypeface(Typeface.MONOSPACE);
        key.setSingleLine(true);
        key.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        LinearLayout.LayoutParams keyParams = new LinearLayout.LayoutParams(0, -2, 1);
        keyParams.setMarginStart(dp(6));
        line.addView(key, keyParams);
        return line;
    }

    /** A host's initial on the theme's selection colour. */
    private TextView avatar(String name) {
        String initial = name.isEmpty() ? "?"
                : new String(Character.toChars(Character.toUpperCase(name.codePointAt(0))));
        TextView avatar = medium(text(this, initial, 18, chrome.onSurface));
        avatar.setGravity(Gravity.CENTER);
        GradientDrawable circle = new GradientDrawable();
        circle.setShape(GradientDrawable.OVAL);
        circle.setColor(chrome.selected);
        avatar.setBackground(circle);
        avatar.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        return avatar;
    }

    /** The device's key, start-up behaviour and the other screens, as one group. */
    private LinearLayout deviceCard() {
        LinearLayout card = card();
        card.setPadding(0, dp(4), 0, dp(4));
        card.addView(keyRow());
        card.addView(divider());
        card.addView(reopenRow());
        card.addView(divider());
        card.addView(linkRow(R.drawable.ic_home_import_export, "Hosts file",
                "Export your hosts to a file, or import them from one", this::showHostFile));
        card.addView(divider());
        card.addView(linkRow(R.drawable.ic_home_link, "Companion hosts",
                "The HTTPS workflow with a companion token",
                () -> startActivity(new android.content.Intent(this, MainActivity.class))));
        card.addView(divider());
        card.addView(linkRow(R.drawable.ic_home_diagnostics, "Local device diagnostics",
                "Model, Android version, battery and storage, read on request",
                () -> startActivity(new android.content.Intent(this, DeviceDiagnosticsActivity.class))));
        card.addView(divider());
        card.addView(linkRow(R.drawable.ic_home_info, "About Remote for Herdr",
                "Version, licences and privacy policy",
                () -> startActivity(new android.content.Intent(this, AboutActivity.class))));
        return card;
    }

    private LinearLayout keyRow() {
        LinearLayout row = settingRow(R.drawable.ic_home_key, 2);
        LinearLayout about = column(this);
        about.addView(text(this, "Device SSH key", 16, chrome.onSurface));
        about.addView(text(this, identityPresent
                ? "Saved here, encrypted with Android Keystore"
                : "None saved; connect with a password or a one-use key", 14, chrome.onSurfaceVariant));
        LinearLayout actions = row(this);
        actions.setGravity(Gravity.CENTER_VERTICAL);
        Button primary = tonalButton(identityPresent ? "Replace" : "Save key", this::editIdentity);
        primary.setContentDescription(identityPresent ? "Replace the saved SSH key" : "Save a device SSH key");
        actions.addView(primary, new LinearLayout.LayoutParams(-2, dp(48)));
        if (identityPresent) {
            Button remove = textButton("Remove", chrome.danger, this::removeIdentity);
            remove.setContentDescription("Remove the saved SSH key");
            LinearLayout.LayoutParams removeParams = new LinearLayout.LayoutParams(-2, dp(48));
            removeParams.setMarginStart(dp(4));
            actions.addView(remove, removeParams);
        }
        if (wideWindow()) {
            row.addView(about, new LinearLayout.LayoutParams(0, -2, 1));
            LinearLayout.LayoutParams actionParams = new LinearLayout.LayoutParams(-2, -2);
            actionParams.setMarginStart(dp(12));
            row.addView(actions, actionParams);
            return row;
        }
        actions.setPadding(0, dp(8), 0, 0);
        about.addView(actions);
        row.addView(about, new LinearLayout.LayoutParams(0, -2, 1));
        return row;
    }

    private LinearLayout reopenRow() {
        // The icon centres on the switch's 48 dp line, not on the first text line.
        LinearLayout row = settingRow(R.drawable.ic_home_history, 12);
        LinearLayout about = column(this);
        // No default switch style: inflating its animated thumb and track cost
        // more than the rest of the list, and themeSwitch replaces both.
        android.widget.Switch reopen = new android.widget.Switch(this, null, 0, 0);
        reopen.setFocusable(true);
        reopen.setClickable(true);
        reopen.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        reopen.setText("Reopen the last session at launch");
        reopen.setTextColor(chrome.onSurface);
        reopen.setTextSize(16);
        reopen.setMinHeight(dp(48));
        reopen.setSwitchPadding(dp(16));
        themeSwitch(reopen);
        reopen.setChecked(reopenEnabled());
        privateView(reopen);
        about.addView(reopen);
        TextView reopenHint = text(this, reopenHint(reopen.isChecked()), 14, chrome.onSurfaceVariant);
        about.addView(reopenHint);
        reopen.setOnCheckedChangeListener((view, checked) -> {
            getSharedPreferences(STARTUP, MODE_PRIVATE).edit().putBoolean("reopen", checked).apply();
            reopenHint.setText(reopenHint(checked));
        });
        row.addView(about, new LinearLayout.LayoutParams(0, -2, 1));
        return row;
    }

    private static String reopenHint(boolean reopen) {
        return reopen ? "The app opens the remembered host and pane instead of this list."
                : "The app opens this host list every time.";
    }

    /**
     * The current platform switch shape in the theme's colours: a 52 x 32 dp
     * track, filled when on with a 24 dp thumb, outlined when off with a 16 dp
     * one. Switch spans two thumb slots plus the track's padding, so 2 dp of
     * padding and 24 dp slots keep the thumbs clear of the track's ends.
     */
    private void themeSwitch(android.widget.Switch control) {
        // Without a style, showText defaults to on and would lay out null labels.
        control.setShowText(false);
        control.setBackground(pressable(0x00000000, 12, 0));
        int[] on = {android.R.attr.state_checked};
        android.graphics.drawable.StateListDrawable track = new android.graphics.drawable.StateListDrawable();
        GradientDrawable trackOn = rounded(chrome.primary, 16);
        trackOn.setSize(dp(52), dp(32));
        trackOn.setPadding(dp(2), 0, dp(2), 0);
        GradientDrawable trackOff = rounded(chrome.control, 16);
        trackOff.setStroke(dp(2), chrome.onSurfaceVariant);
        trackOff.setSize(dp(52), dp(32));
        trackOff.setPadding(dp(2), 0, dp(2), 0);
        track.addState(on, trackOn);
        track.addState(new int[0], trackOff);
        android.graphics.drawable.StateListDrawable thumb = new android.graphics.drawable.StateListDrawable();
        thumb.addState(on, thumbDot(chrome.surface, 24));
        thumb.addState(new int[0], thumbDot(chrome.onSurfaceVariant, 16));
        control.setThumbTintList(null);
        control.setTrackTintList(null);
        control.setTrackDrawable(track);
        control.setThumbDrawable(thumb);
        control.setSwitchMinWidth(dp(52));
    }

    /**
     * A round thumb of {@code sizeDp}, centred in a 24 x 32 dp slot. Switch reads
     * a thumb's padding as part of its geometry, so the slot uses layer insets,
     * which add none.
     */
    private Drawable thumbDot(int color, int sizeDp) {
        GradientDrawable dot = new GradientDrawable();
        dot.setShape(GradientDrawable.OVAL);
        dot.setColor(color);
        dot.setSize(dp(sizeDp), dp(sizeDp));
        android.graphics.drawable.LayerDrawable slot =
                new android.graphics.drawable.LayerDrawable(new Drawable[]{dot});
        int horizontal = dp((24 - sizeDp) / 2), vertical = dp((32 - sizeDp) / 2);
        slot.setLayerInset(0, horizontal, vertical, horizontal, vertical);
        return slot;
    }

    /** A whole-row link to another screen, closed by a chevron. */
    private LinearLayout linkRow(int iconRes, String heading, String detail, Runnable action) {
        LinearLayout row = settingRow(iconRes, 0);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackground(pressable(0x00000000, 0, 0));
        row.setClickable(true);
        row.setFocusable(true);
        row.setOnClickListener(view -> action.run());
        LinearLayout about = column(this);
        about.addView(text(this, heading, 16, chrome.onSurface));
        about.addView(text(this, detail, 14, chrome.onSurfaceVariant));
        row.addView(about, new LinearLayout.LayoutParams(0, -2, 1));
        LinearLayout.LayoutParams chevronParams = new LinearLayout.LayoutParams(dp(24), dp(24));
        chevronParams.setMarginStart(dp(12));
        row.addView(icon(R.drawable.ic_home_chevron, chrome.onSurfaceVariant), chevronParams);
        return row;
    }

    /** A settings row with its leading icon; callers add the text and controls. */
    private LinearLayout settingRow(int iconRes, int iconTopDp) {
        LinearLayout row = row(this);
        row.setGravity(Gravity.TOP);
        row.setMinimumHeight(dp(64));
        row.setPadding(dp(16), dp(14), dp(16), dp(14));
        LinearLayout.LayoutParams iconParams = new LinearLayout.LayoutParams(dp(24), dp(24));
        iconParams.setMarginEnd(dp(16));
        iconParams.topMargin = dp(iconTopDp);
        row.addView(icon(iconRes, chrome.onSurfaceVariant), iconParams);
        return row;
    }

    /** Every secondary action keeps its own confirmation; the menu only gathers them. */
    private void showHostActions(View anchor, SavedRemoteHost host) {
        Context themed = new android.view.ContextThemeWrapper(this, chrome.popupTheme());
        android.widget.PopupMenu menu = new android.widget.PopupMenu(themed, anchor);
        // A raw shell needs the same pinned key and saved identity as Connect.
        if (!host.expectedFingerprint.isEmpty() && identityPresent) {
            menu.getMenu().add(0, 5, 0, "Open shell");
        }
        menu.getMenu().add(0, 1, 0, "Edit profile");
        menu.getMenu().add(0, 2, 1, "Edit extra keys");
        if (!host.expectedFingerprint.isEmpty()) menu.getMenu().add(0, 3, 2, destructive("Forget SSH trust"));
        menu.getMenu().add(0, 4, 3, destructive("Delete profile"));
        menu.setOnMenuItemClickListener(item -> {
            switch (item.getItemId()) {
                case 5: openShell(host); break;
                case 1: editProfile(host); break;
                case 2: editShortcuts(host); break;
                case 3: forgetTrust(host); break;
                default: deleteProfile(host); break;
            }
            return true;
        });
        menu.show();
    }

    /** The host's own login shell, without Herdr's panes or agent status. */
    private void openShell(SavedRemoteHost host) {
        try {
            GpuiLaunch.launchShell(this, host);
        } catch (RuntimeException | LinkageError failure) {
            String message = failure.getMessage();
            setStatus(Tone.ERROR, host.name + ": " + (message == null || message.isEmpty() ? "could not open a shell" : message));
        }
    }

    private CharSequence destructive(String title) {
        android.text.SpannableString text = new android.text.SpannableString(title);
        text.setSpan(new android.text.style.ForegroundColorSpan(chrome.danger), 0, title.length(), 0);
        return text;
    }

    private TextView sectionLabel(String text) {
        TextView label = medium(text(this, text, 14, chrome.primary));
        label.setAccessibilityHeading(true);
        label.setPadding(dp(4), dp(20), 0, dp(8));
        return label;
    }

    private static final class ProfilesSnapshot {
        final List<SavedRemoteHost> hosts;
        final boolean identityPresent;
        ProfilesSnapshot(List<SavedRemoteHost> hosts, boolean identityPresent) {
            this.hosts = hosts;
            this.identityPresent = identityPresent;
        }
    }

    private void editIdentity() {
        generation++;
        dismissDialog();
        AlertDialog.Builder builder = sheet();
        Context context = builder.getContext();
        LinearLayout fields = sheetFields(context);
        fields.addView(note(context, "Save one device-wide existing OpenSSH or PEM private key, encrypted with Android Keystore. It is not added to any host profile. A key passphrase is requested only for a connection and is never saved.", 14));
        identityInput = field(context, fields, "Paste existing OpenSSH / PEM private key", "", true);
        identityInput.setSingleLine(false);
        identityInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        identityInput.setMinLines(5);
        identityInput.setMaxLines(10);
        TextView error = note(context, "", 13);
        fields.addView(error);
        ScrollView scroll = new ScrollView(context);
        scroll.addView(fields);
        AlertDialog editing = builder.setTitle(identityPresent ? "Replace saved SSH key" : "Save SSH key")
                .setView(scroll).setNegativeButton("Cancel", null).setPositiveButton("Save key", null).create();
        editing.setOnDismissListener(ignored -> {
            if (identityInput != null) identityInput.setText("");
            identityInput = null;
            if (dialog == editing) dialog = null;
        });
        showDialog(editing);
        editing.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            if (identityInput == null || identityInput.length() == 0) {
                error.setText("Paste a nonempty OpenSSH or PEM private key.");
                return;
            }
            if (!boundedSecret(identityInput.getText(), 64 * 1024)) {
                error.setText("SSH key must be valid text and at most 64 KiB.");
                return;
            }
            final char[] identity = characters(identityInput);
            synchronized (this) { pendingSecret = identity; pendingPassphrase = null; }
            identityInput.setText("");
            identityInput = null;
            editing.dismiss();
            dialog = null;
            int token = generation;
            run(token, () -> {
                try {
                    identityStore.save(identity);
                    return true;
                } finally {
                    Arrays.fill(identity, '\0');
                    synchronized (this) { if (pendingSecret == identity) pendingSecret = null; }
                }
            }, ignored -> {
                identityPresent = true;
                dismissDialog();
                showProfiles();
            }, failure -> {
                setStatus(Tone.ERROR, failure);
            });
        });
    }

    private void removeIdentity() {
        generation++;
        showDialog(sheet().setTitle("Remove saved SSH key?")
                .setMessage("Permanently remove the device-wide encrypted SSH key and its Android Keystore key. Saved SSH profiles and host fingerprints are unchanged.")
                .setNegativeButton("Cancel", null).setPositiveButton("Remove key", (which, index) -> {
                    int token = generation;
                    run(token, () -> { identityStore.delete(); return true; }, ignored -> {
                        identityPresent = false;
                        showProfiles();
                    });
                }).create());
    }

    private void editProfile(SavedRemoteHost old) {
        generation++;
        dismissDialog();
        AlertDialog.Builder builder = sheet();
        Context context = builder.getContext();
        LinearLayout fields = sheetFields(context);
        EditText name = field(context, fields, "Profile name", old == null ? "" : old.name, false);
        Spinner transport = new Spinner(context);
        transport.setAdapter(new ArrayAdapter<String>(context, android.R.layout.simple_spinner_dropdown_item,
                new String[]{"Direct TCP + SSH", "Iroh + SSH"}));
        privateView(transport);
        transport.setSelection(old != null && "iroh".equals(old.transport) ? 1 : 0);
        fields.addView(transport);
        EditText host = field(context, fields, "SSH hostname or IP", old == null ? "" : old.host, false);
        EditText port = field(context, fields, "SSH port", old == null ? "22" : Integer.toString(old.port), false);
        port.setInputType(InputType.TYPE_CLASS_NUMBER);
        EditText endpoint = field(context, fields, "Iroh endpoint ID or ticket (Iroh only)",
                old == null ? "" : old.endpointTicket.isEmpty() ? old.endpointId : old.endpointTicket, false);
        // Added last; paste reports into it as well.
        TextView error = note(context, "Changing the host, Iroh endpoint, user or session clears saved SSH trust. A newer ticket or another relay for the same endpoint keeps it.", 13);
        fields.addView(choice(context, "Paste endpoint or ticket", () -> pasteIrohTarget(endpoint, transport, error)));
        EditText relay = field(context, fields, "Iroh relay URL (optional, Iroh only)", old == null ? "" : old.relayUrl, false);
        relay.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        fields.addView(note(context, "Iroh needs an SSH receiver on the host: iroh-ssh, or dumbpipe listen-tcp with --custom-alpn utf8:/iroh/ssh. A ticket also carries the receiver's relay and addresses. With a relay URL the app uses only that relay, not Iroh's public relays or address lookup. The receiver selects the SSH port; the port above applies only to direct TCP.", 12));
        EditText user = field(context, fields, "SSH username", old == null ? "" : old.username, false);
        EditText session = field(context, fields, "Herdr session", old == null ? "default" : old.session, false);
        fields.addView(error);
        ScrollView scroll = new ScrollView(context);
        scroll.addView(fields);
        AlertDialog editing = builder.setTitle(old == null ? "Add host" : "Edit host")
                .setView(scroll).setNegativeButton("Cancel", null).setPositiveButton("Save", null).create();
        showDialog(editing);
        editing.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            String kind = transport.getSelectedItemPosition() == 0 ? "tcp" : "iroh";
            String endpointId = "", endpointTicket = "", relayUrl = "";
            if ("iroh".equals(kind)) {
                try {
                    JSONObject target = new JSONObject(RemoteNative.irohTarget(value(endpoint), value(relay)));
                    endpointId = target.getString("endpoint_id");
                    endpointTicket = target.getString("endpoint_ticket");
                    relayUrl = target.getString("relay_url");
                } catch (IllegalStateException | org.json.JSONException | LinkageError invalid) {
                    error.setText(safeMessage(invalid.getMessage()));
                    return;
                }
            }
            try {
                SavedRemoteHost draft = SavedRemoteHost.create(value(name), kind, value(host),
                        "tcp".equals(kind) ? Integer.parseInt(value(port)) : 22,
                        endpointId, endpointTicket, relayUrl, value(user), value(session));
                editing.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
                int token = generation;
                run(token, () -> store.saveProfile(old, draft), saved -> {
                    dismissDialog();
                    showProfiles();
                }, failure -> {
                    editing.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(true);
                    error.setText(failure);
                });
            } catch (Exception failure) { error.setText("Check all profile fields and enter an SSH port from 1 to 65535."); }
        });
    }

    /**
     * Fills the Iroh field from the clipboard, e.g. a ticket copied from a QR code scanned by the
     * camera app. Invalid text leaves the field unchanged.
     */
    private void pasteIrohTarget(EditText endpoint, Spinner transport, TextView status) {
        ClipboardManager clipboard = getSystemService(ClipboardManager.class);
        ClipData clip = clipboard == null ? null : clipboard.getPrimaryClip();
        // Plain text only: coercing a URI item would read its content on the UI thread.
        CharSequence text = clip == null || clip.getItemCount() == 0 ? null : clip.getItemAt(0).getText();
        if (text == null || text.toString().trim().isEmpty()) {
            status.setText("The clipboard holds no text to paste.");
            return;
        }
        try {
            JSONObject target = new JSONObject(RemoteNative.irohTarget(text.toString(), ""));
            String id = target.getString("endpoint_id");
            String ticket = target.getString("endpoint_ticket");
            endpoint.setText(ticket.isEmpty() ? id : ticket);
            transport.setSelection(1);
            status.setText((ticket.isEmpty() ? "Pasted endpoint " : "Pasted a ticket for endpoint ") + id + ".");
        } catch (IllegalStateException | org.json.JSONException | LinkageError invalid) {
            status.setText(safeMessage(invalid.getMessage()));
        }
    }

    private void forgetTrust(SavedRemoteHost host) {
        generation++;
        showDialog(sheet().setTitle("Forget SSH trust?")
                .setMessage("Remove the saved fingerprint for " + host.name + "? Verify its next fingerprint independently before trusting it again.")
                .setNegativeButton("Cancel", null).setPositiveButton("Forget trust", (which, index) -> {
                    int token = generation;
                    run(token, () -> store.forgetTrust(host), ignored -> showProfiles());
                }).create());
    }

    private void editShortcuts(SavedRemoteHost host) {
        generation++;
        dismissDialog();
        AlertDialog.Builder builder = sheet();
        Context context = builder.getContext();
        LinearLayout fields = sheetFields(context);
        fields.addView(note(context, "One button per line, up to 24. Text inserts without Enter.\nExamples:\nStop = key:ctrl+c\nStatus = text:git status\nNext = keys:down,enter", 14));
        EditText keys = field(context, fields, "Extra keys", host.shortcuts, false);
        keys.setSingleLine(false);
        keys.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        keys.setMinLines(5);
        TextView error = note(context, "", 13);
        fields.addView(error);
        ScrollView scroll = new ScrollView(context);
        scroll.addView(fields);
        AlertDialog editor = builder.setTitle("Extra keys · " + host.name)
                .setView(scroll).setNegativeButton("Cancel", null).setPositiveButton("Save", null).create();
        showDialog(editor);
        editor.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            String configuration = value(keys);
            try { dev.herdr.android.core.Shortcut.parseConfiguration(configuration); }
            catch (IllegalArgumentException invalid) { error.setText(safeMessage(invalid.getMessage())); return; }
            editor.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
            run(generation, () -> store.updateShortcuts(host, configuration), saved -> {
                dismissDialog(); showProfiles();
            }, failure -> {
                error.setText(failure);
                editor.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(true);
            });
        });
    }

    private void deleteProfile(SavedRemoteHost host) {
        generation++;
        showDialog(sheet().setTitle("Delete remote profile?")
                .setMessage("Remove " + host.name + " and its saved SSH trust and quick commands from this device?")
                .setNegativeButton("Cancel", null).setPositiveButton("Delete", (which, index) -> {
                    int token = generation;
                    run(token, () -> { store.delete(host); return true; }, ignored -> showProfiles());
                }).create());
    }

    /** What a hosts file holds, before either picker opens. */
    private void showHostFile() {
        showDialog(sheet().setTitle("Hosts file")
                .setMessage("Export saves every host's address, user, session, extra keys, quick commands "
                        + "and trusted host key to a file you choose. Passwords and the device SSH key are "
                        + "never in it.\n\nImport adds the hosts from such a file, after showing you which.")
                .setNegativeButton("Cancel", null)
                .setNeutralButton("Import", (which, index) -> importHosts())
                .setPositiveButton("Export", (which, index) -> exportHosts())
                .create());
    }

    private void exportHosts() {
        if (savedHostCount == 0) {
            setStatus(Tone.NOTE, "There are no hosts to export yet");
            return;
        }
        openPicker(new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                .setType(HostFile.MIME_TYPE).putExtra(Intent.EXTRA_TITLE, HostFile.FILE_NAME),
                REQUEST_EXPORT_HOSTS);
    }

    private void importHosts() {
        // Files sent by mail or chat often lose their JSON type.
        openPicker(new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                .setType("*/*").putExtra(Intent.EXTRA_MIME_TYPES,
                        new String[] {HostFile.MIME_TYPE, "text/plain", "application/octet-stream"}),
                REQUEST_IMPORT_HOSTS);
    }

    private void openPicker(Intent intent, int request) {
        try {
            startActivityForResult(intent, request);
        } catch (ActivityNotFoundException missing) {
            setStatus(Tone.ERROR, "This device has no file picker to choose a hosts file");
        }
    }

    /** A picked file, on the worker: export writes it; import plans and then asks first. */
    private void handleHostFile(int request, Uri uri) {
        int token = generation;
        if (request == REQUEST_EXPORT_HOSTS) {
            run(token, () -> {
                List<SavedRemoteHost> hosts = store.read();
                byte[] bytes = HostFile.write(hosts);
                try (OutputStream out = getContentResolver().openOutputStream(uri, "w")) {
                    if (out == null) throw new IOException("The picker returned no file to write");
                    out.write(bytes);
                } catch (IOException | SecurityException unwritable) {
                    throw new IllegalStateException("The hosts file could not be written", unwritable);
                }
                return hosts.size();
            }, count -> setStatus(Tone.NOTE, count == 1 ? "Exported 1 host" : "Exported " + count + " hosts"));
            return;
        }
        run(token, () -> {
            byte[] bytes;
            try (InputStream in = getContentResolver().openInputStream(uri)) {
                if (in == null) throw new IOException("The picker returned no file to read");
                bytes = HostFile.readBounded(in);
            } catch (IOException | SecurityException unreadable) {
                throw new IllegalStateException("The hosts file could not be read", unreadable);
            }
            List<SavedRemoteHost> incoming = HostFile.read(bytes);
            return new ImportPlan(incoming.size(), RemoteHostStore.newProfiles(store.read(), incoming));
        }, this::confirmImport);
    }

    /**
     * Names every host an import adds. A file's trusted host keys come with its
     * hosts, so confirming here is the explicit trust step for those keys.
     */
    private void confirmImport(ImportPlan plan) {
        if (plan.added.isEmpty()) {
            setStatus(Tone.NOTE, plan.inFile == 0 ? "The hosts file has no hosts in it"
                    : "Every host in the file is already saved here");
            return;
        }
        StringBuilder message = new StringBuilder();
        boolean trusted = false;
        for (SavedRemoteHost host : plan.added) {
            message.append("• ").append(host.name).append(" — ").append(address(host)).append('\n');
            trusted |= !host.expectedFingerprint.isEmpty();
        }
        int skipped = plan.inFile - plan.added.size();
        if (skipped == 1) message.append("\n1 host in the file is already saved here and stays as it is.\n");
        else if (skipped > 1) message.append('\n').append(skipped)
                .append(" hosts in the file are already saved here and stay as they are.\n");
        if (trusted) {
            message.append("\nHost keys trusted in the file are trusted here too, so import only a file "
                    + "you exported yourself or got from someone you trust.");
        }
        int count = plan.added.size();
        generation++;
        showDialog(sheet().setTitle(count == 1 ? "Import 1 host?" : "Import " + count + " hosts?")
                .setMessage(message.toString().trim())
                .setNegativeButton("Cancel", null).setPositiveButton("Import", (which, index) -> {
                    int token = generation;
                    run(token, () -> store.importProfiles(plan.added), added -> {
                        pendingStatus = added == 1 ? "Imported 1 host" : "Imported " + added + " hosts";
                        pendingTone = Tone.NOTE;
                        showProfiles();
                    });
                }).create());
    }

    /** What a hosts file would add: how many hosts it held, and those not saved yet. */
    private static final class ImportPlan {
        final int inFile;
        final List<SavedRemoteHost> added;

        ImportPlan(int inFile, List<SavedRemoteHost> added) {
            this.inFile = inFile;
            this.added = added;
        }
    }

    private void inspectHost(SavedRemoteHost host) {
        cancelConnection();
        selected = host;
        content.removeAllViews();
        showHeader(host.name, destination(host).replace('\n', ' '), "Cancel and return to hosts");
        setStatus(Tone.PROGRESS, "Checking the SSH host key for " + host.name + "…");
        int token = generation;
        run(token, () -> {
            SavedRemoteHost current = store.requireCurrent(host);
            RemoteNative.ensureLoaded(this);
            return result(RemoteNative.inspect(current.connectionJson()));
        }, inspection -> {
            try {
                String key = SavedRemoteHost.fingerprint(inspection.getString("fingerprint"));
                if (key.isEmpty()) throw new IllegalArgumentException("Missing SSH host key");
                if (!host.expectedFingerprint.isEmpty()) {
                    if (!host.expectedFingerprint.equals(key)) {
                        setStatus(Tone.ERROR, "SSH HOST KEY CHANGED. Connection blocked. Verify the change independently, then use Forget SSH trust in the host's ⋮ menu.");
                        return;
                    }
                    signIn(host);
                    return;
                }
                setStatus(Tone.NOTE, "Verify this SSH fingerprint before trusting the host");
                showDialog(sheet().setTitle("Trust this SSH host key?")
                        .setMessage(destination(host) + "\n\n" + key
                                + "\n\nCompare this SHA-256 fingerprint with a trusted source. No credentials have been sent.")
                        .setNegativeButton("Cancel", (which, index) -> showProfiles())
                        .setPositiveButton("Trust fingerprint", (which, index) -> run(token,
                                () -> store.trust(host, key), trusted -> { selected = trusted; signIn(trusted); }))
                        .create());
            } catch (Exception failure) { setStatus(Tone.ERROR, "SSH host-key inspection returned an invalid response."); }
        });
    }

    private enum CredentialMode { PASSWORD, ONE_USE_KEY, SAVED_KEY }

    /**
     * Signs in once the host key is verified. A saved device key needs no
     * choice: it is used at once, and the sheet appears only when the key needs
     * its passphrase or that sign-in fails.
     */
    private void signIn(SavedRemoteHost host) {
        if (!identityPresent) {
            showCredentials(host, CredentialMode.PASSWORD);
            return;
        }
        char[] secret = new char[0];
        char[] phrase = new char[0];
        synchronized (this) { pendingSecret = secret; pendingPassphrase = phrase; }
        connect(host, secret, phrase, CredentialMode.SAVED_KEY, true);
    }

    /** Why an automatic sign-in with the saved key handed over to the sheet. */
    private static String savedKeyFailed(String reason) {
        return reason == null || reason.isEmpty()
                ? "Connecting with the saved SSH key failed. Choose another way to sign in."
                : "Connecting with the saved SSH key failed: " + reason;
    }

    private void showCredentials(SavedRemoteHost host, CredentialMode mode) {
        showCredentials(host, mode, null, false);
    }

    /**
     * The sign-in sheet. `notice` says why it opened when the user did not ask
     * for it; `passphraseRequired` means the saved key is encrypted, so Connect
     * waits for its passphrase.
     */
    private void showCredentials(SavedRemoteHost host, CredentialMode mode, String notice,
            boolean passphraseRequired) {
        dismissDialog();
        // The key was just verified or trusted; nothing is in progress until sign-in.
        setStatus(Tone.NOTE, "Host key verified. Sign in to continue.");
        AlertDialog.Builder builder = sheet();
        Context context = builder.getContext();
        LinearLayout fields = sheetFields(context);
        if (notice != null) {
            TextView why = note(context, notice, 14);
            why.setTextColor(themeColor(context, android.R.attr.textColorPrimary));
            fields.addView(why);
        }
        TextView where = note(context, destination(host), 16);
        where.setTextColor(themeColor(context, android.R.attr.textColorPrimary));
        fields.addView(where);
        fields.addView(note(context, "Trusted " + host.expectedFingerprint
                + (mode == CredentialMode.SAVED_KEY
                    ? "\nThe saved private key is decrypted only for this connection. Its passphrase is one-use."
                    : "\nPasswords and passphrases are used once and cleared on close or pause."), 13));
        fields.addView(note(context, "The app checks for a running Herdr session. Herdr’s bridge may start it if it stops while connecting.", 13));
        password = null;
        keyPassphrase = null;
        if (mode != CredentialMode.SAVED_KEY) {
            boolean privateKey = mode == CredentialMode.ONE_USE_KEY;
            password = field(context, fields, privateKey ? "Existing OpenSSH / PEM private key" : "SSH password", "", true);
            if (privateKey) {
                password.setSingleLine(false);
                password.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                        | InputType.TYPE_TEXT_VARIATION_PASSWORD | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
                password.setMinLines(3); password.setMaxLines(6);
            }
        }
        if (mode != CredentialMode.PASSWORD) {
            keyPassphrase = field(context, fields, passphraseRequired ? "Key passphrase" : "Key passphrase (optional)", "", true);
        }
        if (mode == CredentialMode.ONE_USE_KEY) {
            fields.addView(note(context, "This key is used once and is not saved. To save a device-wide encrypted key, return to Remote hosts.", 13));
        }
        if (mode == CredentialMode.SAVED_KEY) {
            fields.addView(note(context, "Uses the device-wide encrypted identity. No automatic password or pasted-key fallback is attempted.", 13));
        }
        if (mode != CredentialMode.PASSWORD) fields.addView(choice(context, "Use password", () -> showCredentials(host, CredentialMode.PASSWORD)));
        if (mode != CredentialMode.ONE_USE_KEY) fields.addView(choice(context, "Use one-use private key", () -> showCredentials(host, CredentialMode.ONE_USE_KEY)));
        if (identityPresent && mode != CredentialMode.SAVED_KEY) {
            fields.addView(choice(context, "Use saved SSH key", () -> showCredentials(host, CredentialMode.SAVED_KEY)));
        }
        ScrollView scrolling = new ScrollView(context); scrolling.addView(fields);
        AlertDialog login = builder.setTitle("Connect to " + host.name).setView(scrolling)
                .setNegativeButton("Cancel", (which, index) -> showProfiles())
                .setPositiveButton("Connect", null).create();
        showDialog(login);
        final int loginGeneration = generation;
        login.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            if (!current(loginGeneration) || dialog != login || !login.isShowing()) return;
            if (mode != CredentialMode.SAVED_KEY) {
                if (password == null || password.length() == 0) return;
                int maximum = mode == CredentialMode.ONE_USE_KEY ? 65536 : 4096;
                if (!boundedSecret(password.getText(), maximum)) {
                    password.setError(mode == CredentialMode.ONE_USE_KEY ? "Key exceeds 64 KiB" : "Password exceeds 4096 bytes"); return;
                }
            }
            if (keyPassphrase != null && !boundedSecret(keyPassphrase.getText(), 4096)) {
                keyPassphrase.setError("Passphrase exceeds 4096 bytes"); return;
            }
            if (passphraseRequired && (keyPassphrase == null || keyPassphrase.length() == 0)) return;
            final char[] secret = mode == CredentialMode.SAVED_KEY ? new char[0] : characters(password);
            final char[] phrase = keyPassphrase == null ? new char[0] : characters(keyPassphrase);
            synchronized (this) { pendingSecret = secret; pendingPassphrase = phrase; }
            if (password != null) password.setText("");
            if (keyPassphrase != null) keyPassphrase.setText("");
            login.dismiss(); dialog = null; password = null; keyPassphrase = null;
            connect(host, secret, phrase, mode, false);
        });
        login.setOnCancelListener(ignored -> showProfiles());
    }

    /**
     * Starts the SSH session. An `automatic` sign-in, with the saved key and no
     * sheet, hands over to the sheet instead of ending at an error: before
     * trying when the key needs its passphrase, and when it fails before the
     * session's panes arrive.
     */
    private void connect(SavedRemoteHost host, char[] secret, char[] phrase, CredentialMode mode,
            boolean automatic) {
        setStatus(Tone.PROGRESS, automatic
                ? "Signing in to " + host.name + " with the saved SSH key…"
                : "Connecting to " + host.name + "…");
        int token = generation;
        submit(() -> {
            long handle = 0;
            char[] credentialSecret = secret;
            try {
                if (!current(token)) return;
                SavedRemoteHost currentHost = store.requireCurrent(host);
                if (mode == CredentialMode.SAVED_KEY) {
                    credentialSecret = identityStore.read();
                    // Ask for the passphrase now rather than after a refused attempt.
                    if (automatic && RemoteIdentityStore.needsPassphrase(credentialSecret)) {
                        main.post(() -> {
                            if (current(token)) showCredentials(currentHost, CredentialMode.SAVED_KEY,
                                    "The saved SSH key is encrypted. Enter its passphrase to connect.", true);
                        });
                        return;
                    }
                }
                String credential;
                synchronized (this) {
                    if (!current(token) || pendingSecret != secret || pendingPassphrase != phrase) return;
                    boolean privateKey = mode != CredentialMode.PASSWORD;
                    JSONObject payload = new JSONObject().put(privateKey ? "private_key" : "password", new String(credentialSecret));
                    if (privateKey && phrase.length > 0) payload.put("passphrase", new String(phrase));
                    credential = payload.toString();
                    Arrays.fill(credentialSecret, '\0');
                    if (credentialSecret != secret) Arrays.fill(secret, '\0');
                    Arrays.fill(phrase, '\0');
                    pendingSecret = null; pendingPassphrase = null;
                }
                handle = RemoteNative.connect(currentHost.connectionJson(), credential);
                if (handle <= 0) throw new IllegalStateException("Remote connection could not be started");
                final long readyHandle = handle;
                main.post(() -> {
                    if (!current(token)) { RemoteNative.close(readyHandle); return; }
                    ownedHandle = readyHandle;
                    selected = currentHost;
                    pollConnection(token, readyHandle, currentHost, automatic);
                });
                handle = 0;
            } catch (Exception | LinkageError failure) {
                String reason = failure instanceof IllegalStateException || failure instanceof IllegalArgumentException
                        ? safeMessage(failure.getMessage()) : null;
                main.post(() -> {
                    if (!current(token)) return;
                    if (automatic) showCredentials(host, CredentialMode.PASSWORD, savedKeyFailed(reason), false);
                    else setStatus(Tone.ERROR, "SSH connection failed. Go back to the host list to try again.");
                });
            } finally {
                if (handle != 0) RemoteNative.close(handle);
                synchronized (this) {
                    Arrays.fill(credentialSecret, '\0');
                    if (credentialSecret != secret) Arrays.fill(secret, '\0');
                    Arrays.fill(phrase, '\0');
                    if (pendingSecret == secret) pendingSecret = null;
                    if (pendingPassphrase == phrase) pendingPassphrase = null;
                }
            }
        });
    }

    private void pollConnection(int token, long handle, SavedRemoteHost host, boolean automatic) {
        if (!current(token) || ownedHandle != handle) return;
        run(token, () -> {
            store.requireCurrent(host);
            return result(RemoteNative.poll(handle, 0, false));
        }, snapshot -> {
            if (ownedHandle != handle) return;
            setStatus("ready".equals(snapshot.optString("state")) ? Tone.NOTE : Tone.PROGRESS,
                    snapshot.optString("status", "Connecting…"));
            if ("closed".equals(snapshot.optString("state"))) {
                String reason = snapshot.optString("status", "SSH connection closed");
                cancelConnection();
                if (automatic) {
                    // The session never reached its panes: most likely the host
                    // refused the saved key, so offer the other ways to sign in.
                    selected = host;
                    showCredentials(host, CredentialMode.PASSWORD, savedKeyFailed(safeMessage(reason)), false);
                    return;
                }
                setStatus(Tone.ERROR, safeMessage(reason) + " Go back to the host list to reconnect.");
                return;
            }
            boolean ready = "ready".equals(snapshot.optString("state"));
            if (ready) {
                JSONArray panes = snapshot.optJSONArray("panes");
                if (panes != null && !panes.toString().equals(paneSnapshot)) {
                    paneSnapshot = panes.toString();
                    renderPanes(host, handle, panes);
                }
            }
            // Once the session is ready, a later close is a lost connection, not a refused key.
            main.postDelayed(() -> pollConnection(token, handle, host, automatic && !ready), 700);
        }, failure -> {
            cancelConnection();
            setStatus(Tone.ERROR, failure + " Go back to the host list to reconnect.");
        });
    }

    /**
     * One card of the session's panes. Each row is the pane's own button, its
     * agent state as a dot that screen readers hear as a word.
     */
    private void renderPanes(SavedRemoteHost host, long handle, JSONArray panes) {
        content.removeAllViews();
        showHeader("Choose a terminal", host.name, "Disconnect and return to hosts");
        LinearLayout card = card();
        card.setPadding(dp(4), dp(4), dp(4), dp(4));
        if (panes.length() == 0) {
            TextView none = text(this, "No terminal panes in this Herdr session", 16, chrome.onSurfaceVariant);
            none.setPadding(dp(16), dp(16), dp(16), dp(16));
            card.addView(none);
        }
        for (int i = 0; i < Math.min(panes.length(), 256); i++) {
            JSONObject pane = panes.optJSONObject(i);
            if (pane == null) continue;
            String id = pane.optString("id", "");
            if (!id.matches("[A-Za-z0-9_./:-]+") || id.length() > 128) continue;
            String title = GpuiTerminalProtocol.title(pane.optString("title", id));
            card.addView(paneRow(title, pane.optString("status", "unknown"), () -> openPane(host, handle, id, title)),
                    new LinearLayout.LayoutParams(-1, -2));
        }
        content.addView(card, cardParams());
    }

    private Button paneRow(String title, String state, Runnable action) {
        // No default button style: its background and elevation animator would be replaced.
        Button row = new Button(this, null, 0, 0);
        row.setFocusable(true);
        row.setText(title);
        row.setAllCaps(false);
        row.setTextSize(16);
        row.setTextColor(chrome.onSurface);
        row.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        row.setSingleLine(true);
        row.setEllipsize(TextUtils.TruncateAt.END);
        row.setMinHeight(dp(56));
        row.setMinimumHeight(dp(56));
        row.setPadding(dp(16), 0, dp(16), 0);
        row.setBackground(pressable(0x00000000, 12, 0));
        row.setStateListAnimator(null);
        GradientDrawable dot = new GradientDrawable();
        dot.setShape(GradientDrawable.OVAL);
        dot.setColor(chrome.status(state));
        dot.setBounds(0, 0, dp(10), dp(10));
        row.setCompoundDrawablesRelative(dot, null, null, null);
        row.setCompoundDrawablePadding(dp(16));
        boolean known = !"unknown".equals(state) && !state.isEmpty();
        row.setContentDescription(known ? title + ", " + state : title);
        privateView(row);
        row.setOnClickListener(view -> action.run());
        return row;
    }

    private void openPane(SavedRemoteHost host, long handle, String id, String title) {
        if (ownedHandle != handle || openingPane) return;
        openingPane = true;
        setStatus(Tone.PROGRESS, "Requesting selected pane…");
        int token = generation;
        run(token, () -> {
            SavedRemoteHost currentHost = store.requireCurrent(host);
            if (!current(token) || ownedHandle != handle) throw new IllegalStateException("Remote selection was cancelled");
            JSONObject response = result(RemoteNative.focus(handle, id));
            if (!Boolean.TRUE.equals(response.opt("queued"))) throw new IllegalStateException("Pane focus was not queued");
            return currentHost;
        }, currentHost -> {
            if (ownedHandle != handle) return;
            try {
                rememberLastSession(this, currentHost, id);
                GpuiLaunch.launchRemote(this, currentHost, handle, id, title);
                // startActivity succeeded: only the launch grant/terminal now owns the handle.
                ownedHandle = 0;
                generation++;
                setStatus(Tone.NOTE, "Terminal opened");
            } catch (Exception | LinkageError failure) {
                openingPane = false;
                setStatus(Tone.ERROR, "The GPUI terminal could not be opened. You can retry or disconnect.");
            }
        }, failure -> { openingPane = false; setStatus(Tone.ERROR, failure); });
    }

    private interface Work<T> { T execute() throws Exception; }
    private interface Done<T> { void apply(T result); }
    private interface Failed { void apply(String message); }

    private <T> void run(int token, Work<T> action, Done<T> done) {
        run(token, action, done, message -> setStatus(Tone.ERROR, message));
    }

    private <T> void run(int token, Work<T> action, Done<T> done, Failed failed) {
        submit(() -> {
            if (!current(token)) return;
            try {
                T result = action.execute();
                main.post(() -> { if (current(token)) done.apply(result); });
            } catch (Exception | LinkageError failure) {
                String message = failure instanceof IllegalStateException || failure instanceof IllegalArgumentException
                        ? safeMessage(failure.getMessage()) : "Remote operation failed; check the profile or encrypted storage.";
                main.post(() -> { if (current(token)) failed.apply(message); });
            }
        });
    }

    private void submit(Runnable task) {
        try { worker.execute(task); }
        catch (RejectedExecutionException busy) { clearSecret(); setStatus(Tone.ERROR, "An operation is still finishing. Please try again."); }
    }

    private boolean current(int token) { return visible && generation == token; }
    private static JSONObject result(String body) throws Exception {
        JSONObject result = new JSONObject(body);
        if (result.has("error") && !result.isNull("error") && !result.optString("error").isEmpty())
            throw new IllegalStateException(safeMessage(result.optString("error")));
        return result;
    }
    private static String safeMessage(String value) {
        if (value == null || value.isEmpty()) return "Remote operation failed";
        return value.replaceAll("[\\p{Cntrl}]", " ").substring(0, Math.min(value.length(), 500));
    }
    private void showDialog(AlertDialog value) {
        if (dialog != null) dialog.dismiss();
        if (value.getWindow() == null) throw new IllegalStateException("Secure dialog window is unavailable");
        // Set before attaching the window; asynchronous readers/rendering can
        // observe isShowing immediately during show().
        value.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        dialog = value;
        value.show();
    }
    /** Shows one status line; repeating the current one changes nothing and announces nothing. */
    private void setStatus(Tone tone, String value) {
        if (tone == statusTone && value.contentEquals(status.getText())
                && statusRow.getVisibility() == View.VISIBLE) return;
        statusTone = tone;
        status.setText(value);
        status.setTextColor(tone == Tone.ERROR ? chrome.onSurface : chrome.onSurfaceVariant);
        if (tone == Tone.PROGRESS && statusProgress == null) {
            statusProgress = new ProgressBar(this, null, android.R.attr.progressBarStyleSmall);
            statusProgress.setIndeterminateTintList(ColorStateList.valueOf(chrome.primary));
            statusProgress.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            statusRow.addView(statusProgress, 0, new LinearLayout.LayoutParams(dp(20), dp(20)));
        }
        if (statusProgress != null) statusProgress.setVisibility(tone == Tone.PROGRESS ? View.VISIBLE : View.GONE);
        statusIcon.setVisibility(tone == Tone.PROGRESS ? View.GONE : View.VISIBLE);
        if (tone == Tone.ERROR) {
            statusIcon.setImageResource(R.drawable.ic_home_error);
            statusIcon.setImageTintList(ColorStateList.valueOf(chrome.danger));
            statusRow.setBackground(rounded(GpuiChrome.withAlpha(chrome.danger, 0x24), 12));
            statusRow.setPadding(dp(12), dp(10), dp(12), dp(10));
        } else {
            statusIcon.setImageResource(R.drawable.ic_home_info);
            statusIcon.setImageTintList(ColorStateList.valueOf(chrome.onSurfaceVariant));
            statusRow.setBackground(null);
            statusRow.setPadding(dp(4), dp(2), dp(4), dp(2));
        }
        statusRow.setVisibility(View.VISIBLE);
    }
    private void clearStatus() {
        statusTone = null;
        status.setText("");
        statusRow.setVisibility(View.GONE);
    }
    private static String destination(SavedRemoteHost host) {
        return "iroh".equals(host.transport)
                ? host.username + " via Iroh\n" + host.endpointId
                : host.username + "@" + host.host + ":" + host.port;
    }
    private static boolean boundedSecret(CharSequence text, int limit) {
        int bytes = 0;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (Character.isHighSurrogate(ch)) {
                if (++i >= text.length() || !Character.isLowSurrogate(text.charAt(i))) return false;
                bytes += 4;
            } else if (Character.isLowSurrogate(ch)) return false;
            else bytes += ch < 0x80 ? 1 : ch < 0x800 ? 2 : 3;
            if (bytes > limit) return false;
        }
        return true;
    }
    private static char[] characters(EditText field) {
        CharSequence text = field.getText();
        char[] result = new char[text.length()];
        for (int i = 0; i < text.length(); i++) result[i] = text.charAt(i);
        return result;
    }
    private static String value(EditText field) { return field.getText().toString(); }
    private void privateView(View view) {
        view.setSaveEnabled(false);
        view.setSaveFromParentEnabled(false);
        view.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
    }

    /** A sheet in the platform dialog style whose lightness matches the theme. */
    private AlertDialog.Builder sheet() {
        return new AlertDialog.Builder(this, chrome.dialogTheme());
    }

    /** A sheet's content column; built from the sheet's context so it takes its colours. */
    private LinearLayout sheetFields(Context context) {
        LinearLayout fields = column(context);
        fields.setPadding(dp(24), dp(4), dp(24), 0);
        return fields;
    }

    /** Sheet text in the sheet theme's secondary colour. */
    private TextView note(Context context, String value, int sizeSp) {
        TextView note = text(context, value, sizeSp, themeColor(context, android.R.attr.textColorSecondary));
        note.setPadding(0, dp(6), 0, dp(6));
        return note;
    }

    private EditText field(Context context, LinearLayout parent, String hint, String value, boolean secret) {
        EditText field = new EditText(context);
        field.setHint(hint);
        field.setText(value);
        field.setSingleLine(true);
        field.setInputType(InputType.TYPE_CLASS_TEXT | (secret ? InputType.TYPE_TEXT_VARIATION_PASSWORD : InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS));
        field.setImeOptions(android.view.inputmethod.EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING);
        privateView(field);
        parent.addView(field);
        return field;
    }

    /** A sheet's text button, in the platform style of the sheet's theme. */
    private Button choice(Context context, String text, Runnable action) {
        Button button = new Button(context, null, android.R.attr.borderlessButtonStyle);
        button.setText(text);
        button.setAllCaps(false);
        button.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        button.setMinHeight(dp(48));
        privateView(button);
        button.setOnClickListener(view -> action.run());
        return button;
    }

    private static int themeColor(Context context, int attribute) {
        android.content.res.TypedArray values = context.obtainStyledAttributes(new int[]{attribute});
        try {
            return values.getColor(0, 0xff000000);
        } finally {
            values.recycle();
        }
    }

    private TextView text(Context context, String value, int sizeSp, int color) {
        TextView text = new TextView(context);
        text.setText(value);
        text.setTextSize(sizeSp);
        text.setTextColor(color);
        privateView(text);
        return text;
    }

    private static TextView medium(TextView text) {
        text.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        return text;
    }

    /** The one filled action of a card: the theme's accent with surface-coloured text. */
    private Button primaryButton(String text, Runnable action) {
        return pill(text, chrome.primary, chrome.surface, action);
    }

    /** A secondary action on a tonal fill; only the primary text colour is legible on it. */
    private Button tonalButton(String text, Runnable action) {
        return pill(text, chrome.control, chrome.onSurface, action);
    }

    private Button textButton(String text, int color, Runnable action) {
        return pill(text, 0x00000000, color, action);
    }

    /** A 40 dp rounded button inside a 48 dp touch target, with a press ripple. */
    private Button pill(String text, int fill, int textColor, Runnable action) {
        // No default button style: its background and elevation animator would be replaced.
        Button button = new Button(this, null, 0, 0);
        button.setFocusable(true);
        button.setGravity(Gravity.CENTER);
        button.setText(text);
        button.setAllCaps(false);
        button.setTextSize(14);
        button.setTextColor(textColor);
        medium(button);
        button.setBackground(pressable(fill, 20, dp(4)));
        button.setMinHeight(dp(48));
        button.setMinimumHeight(dp(48));
        button.setMinWidth(dp(64));
        button.setMinimumWidth(dp(64));
        button.setPadding(dp(20), 0, dp(20), 0);
        button.setStateListAnimator(null);
        privateView(button);
        button.setOnClickListener(view -> action.run());
        return button;
    }

    /** An 18 dp leading icon in the label's colour. */
    private void withIcon(Button button, int iconRes, int color) {
        Drawable icon = getDrawable(iconRes);
        if (icon == null) return;
        icon = icon.mutate();
        icon.setBounds(0, 0, dp(18), dp(18));
        icon.setTint(color);
        button.setCompoundDrawablesRelative(icon, null, null, null);
        button.setCompoundDrawablePadding(dp(8));
        button.setPadding(dp(16), 0, dp(20), 0);
    }

    /** A 48 dp icon button with a round ripple; its label is a tooltip. */
    private ImageButton iconButton(int iconRes, int tint, String label, String description,
            View.OnClickListener action) {
        ImageButton button = new ImageButton(this, null, 0, 0);
        button.setFocusable(true);
        button.setImageResource(iconRes);
        button.setImageTintList(ColorStateList.valueOf(tint));
        button.setBackground(pressable(0x00000000, 24, 0));
        button.setScaleType(ImageView.ScaleType.CENTER);
        button.setContentDescription(description);
        button.setTooltipText(label);
        privateView(button);
        button.setOnClickListener(action);
        return button;
    }

    private ImageView icon(int iconRes, int tint) {
        ImageView icon = new ImageView(this);
        icon.setImageResource(iconRes);
        icon.setImageTintList(ColorStateList.valueOf(tint));
        icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        privateView(icon);
        return icon;
    }

    /** A rounded surface with the theme's outline, lifted off the page. */
    private LinearLayout card() {
        LinearLayout card = column(this);
        GradientDrawable shape = rounded(chrome.surface, 16);
        shape.setStroke(Math.max(1, dp(1) / 2), chrome.outline);
        card.setBackground(shape);
        return card;
    }

    private View divider() {
        View line = new View(this);
        line.setBackgroundColor(chrome.outline);
        line.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, Math.max(1, dp(1) / 2));
        params.setMarginStart(dp(56));
        line.setLayoutParams(params);
        return line;
    }

    private GradientDrawable rounded(int color, int radiusDp) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(color);
        shape.setCornerRadius(dp(radiusDp));
        return shape;
    }

    /**
     * Rounded fill inset to its visual size, with a press ripple, as the
     * terminal's controls draw it; the view keeps its full touch target.
     */
    private Drawable pressable(int fill, int radiusDp, int inset) {
        return new RippleDrawable(ColorStateList.valueOf(GpuiChrome.withAlpha(chrome.onSurface, 0x29)),
                new InsetDrawable(rounded(fill, radiusDp), 0, inset, 0, inset),
                new InsetDrawable(rounded(0xffffffff, radiusDp), 0, inset, 0, inset));
    }

    private LinearLayout row(Context context) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        privateView(row);
        return row;
    }

    private LinearLayout column(Context context) {
        LinearLayout result = new LinearLayout(context);
        result.setOrientation(LinearLayout.VERTICAL); privateView(result);
        return result;
    }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
