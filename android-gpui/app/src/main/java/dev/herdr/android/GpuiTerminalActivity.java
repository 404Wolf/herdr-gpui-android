package dev.herdr.android;

import android.annotation.SuppressLint;

import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.content.res.ColorStateList;
import android.os.Bundle;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.inputmethod.BaseInputConnection;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.KeyCharacterMap;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.FrameLayout;
import android.widget.ScrollView;
import android.widget.Toast;
import android.widget.TextView;
import android.widget.Spinner;
import android.widget.ArrayAdapter;
import dev.gpui.mobile.GpuiInputActivity;
import dev.herdr.android.core.ClientException;
import dev.herdr.android.core.CompanionClient;
import dev.herdr.android.core.KeyChord;
import dev.herdr.android.core.Shortcut;
import java.io.IOException;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** GPUI paints and routes input; each connection owns either HTTPS or embedded SSH transport. */
public final class GpuiTerminalActivity extends GpuiInputActivity implements SurfaceHolder.Callback {
    @Override protected void attachBaseContext(android.content.Context base) {
        super.attachBaseContext(ProvisionedSettings.scaled(base));
    }

    private static final AtomicLong GENERATIONS = new AtomicLong();
    private static final java.util.regex.Pattern CHROME_CONTROLS =
            java.util.regex.Pattern.compile("[\\p{Cntrl}]");
    /** Loop period for input commands; the poll gate below decides frame cadence. */
    private static final long COMMAND_POLL_MS = 16;
    private static final long SCREEN_POLL_MS = 1000;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Object stateLock = new Object();
    private final Set<Integer> capturedKeys = new HashSet<>();
    /** Scroll requests ride their own thread so a poll parse never delays them. */
    private final java.util.concurrent.ExecutorService scrollWorker =
            java.util.concurrent.Executors.newSingleThreadExecutor(task -> {
                Thread thread = new Thread(task, "herdr-scroll");
                thread.setDaemon(true);
                return thread;
            });
    private final ScheduledThreadPoolExecutor worker = new ScheduledThreadPoolExecutor(1, task -> {
        Thread thread = new Thread(task, "herdr-gpui-https");
        thread.setDaemon(true);
        return thread;
    });
    private GpuiLaunch.Session launch;
    private Connection connection;
    private String shortcutJson;
    private List<Shortcut> shortcuts = Collections.emptyList();
    private boolean hostStarted;
    private volatile boolean hostResumed;
    private boolean destroyed;
    private long imeSession;
    private boolean imeComposing;
    private boolean initialImeHidden;
    private boolean keyboardRequested;
    private boolean allowNextKeyboardShow;
    private GpuiDisplayOptions displayOptions;
    private boolean compactDisplay;
    private FrameLayout terminalRoot;
    private LinearLayout sidebar, sidebarRows;
    /** Reused rail rows, so a sampler refresh never replaces the view under a finger. */
    private final java.util.Map<String, RailHostRow> railHostRows = new java.util.HashMap<>();
    private final java.util.Map<String, RailWorkspaceRow> railWorkspaces = new java.util.HashMap<>();
    private final java.util.Map<String, RailPaneRow> railPanes = new java.util.HashMap<>();
    private final java.util.Map<String, TextView> railNotes = new java.util.HashMap<>();
    private final java.util.Map<String, View> railRules = new java.util.HashMap<>();

    private ScrollView sidebarScroll;
    private ImageButton sidebarButton;
    /** Rail parent when it sits beside the terminal; compact windows float it instead. */
    private LinearLayout railColumns;
    private View railScrim, railEdge, railFade;
    private View shortcutBar;
    /** Size class the toolbar was built for, so a fold rebuilds it only on change. */
    private boolean toolbarCompact;
    /** Tabletop posture from the device's window extensions, while started. */
    private FoldPosture.Watcher foldWatcher;
    private FoldPosture posture = FoldPosture.FLAT;
    /** In tabletop the keys leave the toolbar strip for this deck under the fold. */
    private android.widget.ScrollView keyDeck;
    private android.widget.GridLayout keyGrid;
    private boolean keysOnDeck;
    /** Colors for every surface outside the painted grid, from the current palette. */
    private GpuiChrome chrome;
    private volatile boolean sidebarOpen;
    private final BackOwner backOwner = new BackOwner(this, this::hideSidebar);
    private String sidebarSignature = "";
    private volatile java.util.List<SavedRemoteHost> savedHosts = java.util.Collections.emptyList();
    private volatile String currentHostId;
    private volatile boolean switchingHost;
    /** Host currently being adopted; its sampler stays closed meanwhile. */
    private volatile String switchingTo;
    /** One background SSH session per saved host, used only to mirror its tree. */
    private static final class HostSample {
        volatile long handle;
        volatile org.json.JSONArray workspaces, tabs, panes;
        volatile boolean connected;
        volatile long revision = -1;
        volatile long nextAttemptAt;
    }
    private final java.util.Map<String, HostSample> hostSamples = new java.util.concurrent.ConcurrentHashMap<>();
    private java.util.concurrent.ScheduledExecutorService sampler;
    private AlertDialog displayDialog;
    private Button controlButton;
    /** Vertical swipe state: a short tap still reaches the terminal. */
    private float touchDownX, edgeStartX;
    private boolean tapCandidate;
    private long touchDownAt;
    private boolean edgeClaimed;
    private float touchDownY, lastTouchY, scrollFraction;
    private float scrollVelocity, flingRemainder;
    private long lastMoveAt;
    private boolean flinging;
    private SurfaceView terminalSurface;
    private final Runnable flingStep = new Runnable() {
        @Override public void run() {
            if (!flinging || terminalSurface == null) return;
            long now = SystemClock.uptimeMillis();
            long dt = Math.max(1, Math.min(48, now - lastMoveAt));
            lastMoveAt = now;
            float rowPx = rowHeightPx();
            float lines = scrollVelocity * dt / rowPx + flingRemainder;
            int step = (int) lines;
            flingRemainder = lines - step;
            // Sub-row frames still count: they carry into the next one.
            boolean atEdge = step != 0 && !requestScroll(step);
            scrollVelocity *= (float) Math.pow(0.90, dt / 16.0);
            if (!atEdge && Math.abs(scrollVelocity) > 0.05f && rowPx > 0f) {
                terminalSurface.postOnAnimation(this);
                return;
            }
            flinging = false;
            scrollVelocity = 0f;
            flingRemainder = 0f;
            flushScroll();
        }
    };

    private void stopFling() {
        flinging = false;
        scrollVelocity = 0f;
        flingRemainder = 0f;
    }
    private boolean scrolling;
    /** Locally accumulated scrollback target; polls only resync it while idle. */
    private long desiredScroll, lastSentScroll = -1;
    /** A fetch whose frame never arrived is asked for again after this long. */
    private static final long FETCH_RETRY_MS = 1000;
    /** True while the finger, a fling, or an in-flight request owns the offset. */
    private volatile boolean scrollSeeking;
    private boolean lastFollowSent = true;
    private boolean controlLatched;
    private int oneShotKey = KeyEvent.KEYCODE_UNKNOWN;
    /** The saved theme choice: a palette index, or {@link GpuiDisplayOptions#THEME_SYSTEM}. */
    private int themeIndex;
    /** The palette last painted, so a configuration change repaints only on a real change. */
    private int appliedPalette = -1;
    private long lastSnapshotGeneration = Long.MIN_VALUE;
    private long lastSnapshotRevision = Long.MIN_VALUE;
    private String lastSnapshotTitle, lastSnapshotText, lastSnapshotStatus, lastSnapshotShortcuts;

    @Override protected void onCreate(Bundle state) {
        // A restored activity cannot recover authority from Intent extras or framework saved state.
        launch = state == null ? GpuiLaunch.consume(System.nanoTime()) : null;
        super.onCreate(null);
        // Terminal content is private. Only a debuggable build that opts in through
        // the global switch may be captured, so a field report can be screenshotted
        // without weakening a release build.
        boolean capturable = ProvisionedSettings.capturable(this);
        if (!capturable) getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        getWindow().getDecorView().setSaveFromParentEnabled(false);
        getWindow().getDecorView().setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
        worker.setRemoveOnCancelPolicy(true);
        worker.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        if (launch == null) {
            Toast.makeText(this, "Reopen the terminal from the host list", Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        try {
            shortcuts = Shortcut.parseConfiguration(launch.shortcuts());
            currentHostId = launch.remoteHost == null ? null : launch.remoteHost.id;
            // Remote-mutating controls live on the Java UI thread, never a deferred GPUI click.
            shortcutJson = "[]";
            loadDisplayOptions();
            themeIndex = getSharedPreferences("terminal-display", MODE_PRIVATE).getInt("theme", 0);
            if (themeIndex < 0 || themeIndex > GpuiDisplayOptions.THEME_SYSTEM) themeIndex = 0;
            nativeStart(this);
            appliedPalette = currentPalette();
            nativeSetTheme(appliedPalette);
            chrome = GpuiChrome.load(appliedPalette);
            // Host switching needs the saved hosts before the first rail paint.
            worker.execute(() -> {
                try {
                    java.util.List<SavedRemoteHost> hosts = new RemoteHostStore(this).read();
                    runOnUiThread(() -> { savedHosts = hosts; updateHostSampling(); refreshSidebar(true); });
                } catch (Exception ignored) {
                    // An unreadable store leaves the rail without host rows.
                }
            });
            hostStarted = true;
            createSurface();
        } catch (IllegalArgumentException invalid) {
            Toast.makeText(this, "Check this host's shortcut settings", Toast.LENGTH_LONG).show();
            finish();
        } catch (LinkageError unavailable) {
            Toast.makeText(this, "The GPUI renderer could not be started", Toast.LENGTH_LONG).show();
            finish();
        } catch (IllegalStateException unavailable) {
            Toast.makeText(this, "The GPUI renderer could not be started", Toast.LENGTH_LONG).show();
            finish();
        }
    }

    private int currentPalette() {
        boolean dark = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
        return GpuiDisplayOptions.palette(themeIndex, dark);
    }

    /** Paints the terminal and every Android surface with the palette the theme now means. */
    private void applyTheme() {
        int palette = currentPalette();
        if (palette == appliedPalette) return;
        appliedPalette = palette;
        nativeSetTheme(palette);
        chrome = GpuiChrome.load(palette);
        applyChrome();
    }

    /** Repaints every Android surface for a newly chosen theme. */
    private void applyChrome() {
        applySystemBars();
        styleRail();
        rebuildShortcutBar();
        if (banner != null) styleBanner();
        // Reused rail rows carry the old colors; rebuild them once for the change.
        if (sidebarRows != null) {
            pruneRailRows(java.util.Collections.emptySet());
            sidebarRows.removeAllViews();
            sidebarSignature = null;
            refreshSidebar(true);
        }
    }

    // The painted grid is a gesture surface, not a click target: a screen reader
    // reaches this app through the toolbar, the rail and the Keys keyboard.
    @SuppressLint("ClickableViewAccessibility")
    private void createSurface() {
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                | WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
        if (Build.VERSION.SDK_INT >= 30) getWindow().setDecorFitsSystemWindows(false);
        else getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        FrameLayout root = new FrameLayout(this);
        terminalRoot = root;
        root.addOnLayoutChangeListener((view, l, t, r, b, ol, ot, or, ob) -> {
            applyDisplayOptions();
            // The rail is sized from this width, which a fold or resize changes
            // only after the configuration callback has already run.
            if (r - l != or - ol) applySidebarWidth();
        });
        root.setSaveFromParentEnabled(false);
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets bounds = insets.getInsets(WindowInsets.Type.systemBars()
                        | WindowInsets.Type.displayCutout() | WindowInsets.Type.ime());
                view.setPadding(bounds.left, bounds.top, bounds.right, bounds.bottom);
            } else {
                // Pre-R edge-to-edge uses adjustResize for IME and stable bars for our padding.
                view.setPadding(insets.getStableInsetLeft(), insets.getStableInsetTop(),
                        insets.getStableInsetRight(), insets.getStableInsetBottom());
            }
            root.post(this::applyDisplayOptions);
            return insets;
        });
        SurfaceView surface = new SurfaceView(this);
        terminalSurface = surface;
        surface.setFocusable(true);
        surface.setFocusableInTouchMode(true);
        surface.setSaveEnabled(false);
        surface.getHolder().addCallback(this);
        surface.setOnTouchListener((view, event) -> forwardTouch(event));
        // Android's Back gesture owns the screen edges. Excluding a narrow left
        // strip lets an edge swipe reach the terminal, where it opens the rail.
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        terminalContent = content;
        content.addView(surface, new LinearLayout.LayoutParams(-1, 0, 1));
        keyGrid = new android.widget.GridLayout(this);
        keyGrid.setPadding(dp(8), dp(8), dp(8), dp(8));
        keyDeck = new android.widget.ScrollView(this);
        keyDeck.setVerticalScrollBarEnabled(false);
        keyDeck.setVisibility(View.GONE);
        keyDeck.addView(keyGrid, new FrameLayout.LayoutParams(-1, -2));
        content.addView(keyDeck, new LinearLayout.LayoutParams(-1, 0, 1));
        // A keyboard, a rotation or the rail changes how much sits under a fold.
        content.addOnLayoutChangeListener((view, l, t, r, b, ol, ot, or, ob) -> {
            if (l != ol || t != ot || r != or || b != ob) view.post(this::applyPosture);
        });
        shortcutBar = createShortcutBar();
        content.addView(shortcutBar, new LinearLayout.LayoutParams(-1, dp(48)));
        // The sidebar rail mirrors the desktop's list of workspaces and agents.
        sidebar = new LinearLayout(this);
        sidebar.setOrientation(LinearLayout.HORIZONTAL);
        sidebar.setSaveFromParentEnabled(false);
        sidebarScroll = new RailScrollView(this, this::hideSidebar);
        sidebarScroll.setVerticalScrollBarEnabled(false);
        sidebarRows = new LinearLayout(this);
        sidebarRows.setOrientation(LinearLayout.VERTICAL);
        sidebarRows.setPadding(dp(6), dp(4), dp(6), dp(12));
        sidebarScroll.addView(sidebarRows, new android.widget.FrameLayout.LayoutParams(-1, -2));
        // Hosts and workspaces exceed one screen; the fade says the list keeps going.
        android.widget.FrameLayout railFrame = new android.widget.FrameLayout(this);
        railFrame.addView(sidebarScroll, new android.widget.FrameLayout.LayoutParams(-1, -1));
        railFade = new View(this);
        railFade.setLayoutParams(new android.widget.FrameLayout.LayoutParams(
                -1, dp(24), android.view.Gravity.BOTTOM));
        railFrame.addView(railFade);
        sidebar.addView(railFrame, new LinearLayout.LayoutParams(0, -1, 1));
        railEdge = new View(this);
        sidebar.addView(railEdge, new LinearLayout.LayoutParams(dp(1), -1));
        railColumns = new LinearLayout(this);
        railColumns.setOrientation(LinearLayout.HORIZONTAL);
        railColumns.addView(sidebar, new LinearLayout.LayoutParams(0, -1));
        railColumns.addView(content, new LinearLayout.LayoutParams(0, -1, 1));
        root.addView(railColumns, new FrameLayout.LayoutParams(-1, -1));
        // Below 600 dp the rail is a modal drawer over this scrim, instead of
        // squeezing an already narrow terminal; Back or a tap outside closes it.
        railScrim = new View(this);
        railScrim.setBackgroundColor(0x52000000);
        railScrim.setVisibility(View.GONE);
        railScrim.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        railScrim.setOnClickListener(view -> hideSidebar());
        root.addView(railScrim, new FrameLayout.LayoutParams(-1, -1));
        styleRail();
        applySystemBars();
        setContentView(root);
        root.requestApplyInsets();
        surface.requestFocus();
        root.post(this::hideInitialKeyboard);
        root.post(() -> { applySidebarDefault(); refreshSidebar(true); });
    }

    /**
     * Edge to edge, the bars show the root, so they continue the header and the
     * toolbar; their icons flip with the theme's lightness to stay visible.
     */
    private void applySystemBars() {
        getWindow().setStatusBarColor(chrome.surface);
        getWindow().setNavigationBarColor(chrome.surface);
        if (terminalRoot != null) terminalRoot.setBackgroundColor(chrome.surface);
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

    /** A compact window has no room to show the rail beside the terminal. */
    private boolean compactWindow() {
        return getResources().getConfiguration().screenWidthDp < 600;
    }

    private void styleRail() {
        if (sidebar == null) return;
        boolean drawer = compactWindow();
        if (drawer) {
            // A modal drawer: rounded on its open edge, raised over the scrim.
            float radius = dp(16);
            boolean rtl = sidebar.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;
            float start = rtl ? radius : 0, end = rtl ? 0 : radius;
            android.graphics.drawable.GradientDrawable panel = new android.graphics.drawable.GradientDrawable();
            panel.setColor(chrome.surface);
            panel.setCornerRadii(new float[]{start, start, end, end, end, end, start, start});
            sidebar.setBackground(panel);
            sidebar.setClipToOutline(true);
            sidebar.setElevation(dp(3));
        } else {
            sidebar.setBackgroundColor(chrome.surface);
            sidebar.setClipToOutline(false);
            sidebar.setElevation(0);
        }
        railEdge.setVisibility(drawer ? View.GONE : View.VISIBLE);
        railEdge.setBackgroundColor(chrome.outline);
        railFade.setBackground(new android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
                new int[] {GpuiChrome.withAlpha(chrome.surface, 0), chrome.surface}));
    }

    /** Moves the rail between the drawer layer and the column beside the terminal. */
    private void placeRail() {
        if (sidebar == null || railColumns == null || terminalRoot == null) return;
        boolean drawer = compactWindow();
        android.view.ViewGroup parent = (android.view.ViewGroup) sidebar.getParent();
        android.view.ViewGroup wanted = drawer ? terminalRoot : railColumns;
        if (parent != wanted) {
            if (parent != null) parent.removeView(sidebar);
            if (drawer) {
                terminalRoot.addView(sidebar, new FrameLayout.LayoutParams(0, -1, android.view.Gravity.START));
            } else {
                railColumns.addView(sidebar, 0, new LinearLayout.LayoutParams(0, -1));
            }
            styleRail();
        }
        railScrim.setVisibility(drawer && sidebarOpen ? View.VISIBLE : View.GONE);
        // An open drawer is the only thing Back closes here; otherwise Back leaves the terminal.
        backOwner.setOwned(drawer && sidebarOpen);
    }

    /** Choosing a destination in the drawer closes it, as Android's drawers do. */
    private void closeRailDrawer() {
        if (compactWindow()) hideSidebar();
    }

    @SuppressWarnings("deprecation")
    @Override public void onBackPressed() {
        if (sidebarOpen && compactWindow()) {
            hideSidebar();
            return;
        }
        super.onBackPressed();
    }


    /**
     * Real terminal grid for the daemon: the monospace cell box the renderer
     * uses, so Herdr sizes the pane to this display instead of 80x24.
     */
    private String terminalGrid() {
        android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
        int stored = getSharedPreferences("terminal-display", MODE_PRIVATE).getInt("font", 13);
        float fontPx = Math.max(8f, (stored > 0 ? stored : 13) * getResources()
                .getConfiguration().fontScale * dm.density / dm.scaledDensity * dm.density);
        int cellW = Math.max(1, Math.round(fontPx * 0.6f));
        int cellH = Math.max(1, Math.round(fontPx * 1.25f));
        int cols = Math.min(400, Math.max(20, dm.widthPixels / cellW));
        int rows = Math.min(200, Math.max(5, (dm.heightPixels - dp(48) * 3) / cellH));
        return cols + "," + rows + "," + cellW + "," + cellH;
    }


    private LinearLayout terminalContent;
    private LinearLayout banner;
    private TextView bannerText;

    /** Explains a dead connection and offers the one action that can fix it. */
    private void showReconnectBanner(String message) {
        // fail() runs on the poll worker; Android views may only be touched on
        // the main thread, so marshal first instead of throwing silently there.
        if (android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) {
            runOnUiThread(() -> showReconnectBanner(message));
            return;
        }
        if (terminalRoot == null) return;
        if (banner == null) {
            banner = new LinearLayout(this);
            banner.setOrientation(LinearLayout.HORIZONTAL);
            banner.setGravity(android.view.Gravity.CENTER_VERTICAL);
            banner.setPadding(dp(16), dp(4), dp(8), dp(4));
            bannerText = new TextView(this);
            bannerText.setTextSize(13);
            LinearLayout.LayoutParams textParams =
                    new LinearLayout.LayoutParams(0, -2, 1f);
            banner.addView(bannerText, textParams);
            Button hosts = keycap("Hosts");
            hosts.setOnClickListener(view -> startActivity(new android.content.Intent(this,
                    RemoteHostsActivity.class).putExtra("skip_reopen", true)));
            banner.addView(hosts, keycapParams());
            Button retry = keycap("Reconnect");
            retry.setOnClickListener(view -> reconnectCurrentHost());
            banner.addView(retry, keycapParams());
            banner.setVisibility(View.GONE);
            // Directly above the toolbar: closest to the surface it explains.
            int barIndex = terminalContent.getChildCount() - 1;
            terminalContent.addView(banner, barIndex);
            styleBanner();
        }
        bannerText.setText(BannerCopy.human(message));
        banner.setVisibility(View.VISIBLE);
    }

    /** The failure is the danger color on the bar's own surface, where it stays legible. */
    private void styleBanner() {
        banner.setBackgroundColor(chrome.surface);
        bannerText.setTextColor(chrome.danger);
        for (int i = 0; i < banner.getChildCount(); i++) {
            View child = banner.getChildAt(i);
            if (!(child instanceof Button)) continue;
            ((Button) child).setTextColor(chrome.onSurface);
            child.setBackground(controlBackground(false, chrome.control, 10, dp(4)));
        }
    }


    /** Transport wording is for logs; the banner says what happened and what to do. */

    /** One description for a host row: name, workspace count, then its state. */
    private String hostDescription(RailHostRow holder) {
        StringBuilder text = new StringBuilder("Connect to ").append(holder.host.name);
        if (holder.counted > 0) text.append(", ").append(holder.counted).append(" workspaces");
        if (holder.hostState != null && !holder.hostState.isEmpty()) {
            text.append(", ").append(holder.hostState);
        }
        return text.toString();
    }


    private void hideReconnectBanner() {
        if (banner != null) banner.setVisibility(View.GONE);
    }

    /** Reuses the saved host for this session; no credential is kept in memory. */
    private void reconnectCurrentHost() {
        if (shellMode()) {
            // A shell has no state to resume: reconnecting opens a fresh one.
            hideReconnectBanner();
            stopConnection();
            startShell();
            return;
        }
        final String id = currentHostId;
        hideReconnectBanner();
        publishCurrent("Reconnecting…");
        worker.execute(() -> {
            SavedRemoteHost host = null;
            try {
                // The rail's cached list is often empty here; the store is truth.
                for (SavedRemoteHost candidate : new RemoteHostStore(this).read()) {
                    if (candidate.id.equals(id)) host = candidate;
                }
            } catch (Exception ignored) {
                host = null;
            }
            if (host == null) {
                runOnUiThread(() -> showReconnectBanner("Open the host list to reconnect"));
                return;
            }
            long handle = 0;
            try {
                char[] key = new RemoteIdentityStore(this).read();
                String credential;
                try {
                    credential = new JSONObject().put("private_key", new String(key)).toString();
                } finally {
                    java.util.Arrays.fill(key, '\0');
                }
                handle = RemoteNative.connectGrid(host.connectionJson(), credential, terminalGrid(), true);
                if (handle <= 0) throw new IllegalStateException("connection refused");
                String chosen = awaitHostPane(handle, host.id, null);
                final long readyHandle = handle;
                final String readyPane = chosen;
                final SavedRemoteHost readyHost = host;
                runOnUiThread(() -> { hideReconnectBanner(); adoptHost(readyHost, readyHandle, readyPane); });
                handle = 0;
            } catch (Exception | LinkageError failure) {
                if (handle != 0) RemoteNative.close(handle);
                runOnUiThread(() -> showReconnectBanner("Reconnect failed. Check the host and network."));
            }
        });
    }


    /** The pane text this client already holds: no daemon round trip, no selection UI. */
    private void copyPaneText() {
        final Connection target;
        final String pane;
        final String heldText;
        synchronized (stateLock) {
            target = connection;
            pane = target == null ? "" : target.paneId;
            heldText = target == null ? null : target.text;
        }
        if (target != null && target.shell) {
            final long handle = target.shellHandle;
            worker.execute(() -> {
                final String text = handle == 0 ? null : RemoteNative.shellText(handle);
                main.post(() -> {
                    synchronized (stateLock) {
                        if (connection != target || !hostResumed || destroyed) return;
                    }
                    writePaneTextToClipboard(text);
                });
            });
            return;
        }
        if (target == null || target.remoteHandle == 0) {
            writePaneTextToClipboard(heldText);
            return;
        }
        worker.execute(() -> {
            final String text = nativeCopyText(target.generation);
            main.post(() -> {
                synchronized (stateLock) {
                    if (connection != target || !target.paneId.equals(pane)
                            || !hostResumed || destroyed) return;
                }
                writePaneTextToClipboard(text);
            });
        });
    }

    private void writePaneTextToClipboard(String text) {
        if (text == null || text.isEmpty()) {
            Toast.makeText(this, "No pane text to copy yet", Toast.LENGTH_SHORT).show();
            return;
        }
        android.content.ClipboardManager clipboard =
                (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (clipboard == null) return;
        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Herdr pane", text));
        Toast.makeText(this, "Copied " + text.length() + " characters", Toast.LENGTH_SHORT).show();
    }

    private void publishCurrent(String status) {
        Connection target;
        synchronized (stateLock) { target = connection; }
        if (target != null && !target.isClosed()) publish(target, status);
    }


    private View createShortcutBar() {
        boolean compact = compactWindow();
        toolbarCompact = compact;
        LinearLayout shell = new LinearLayout(this);
        shell.setOrientation(LinearLayout.HORIZONTAL);
        shell.setGravity(android.view.Gravity.CENTER_VERTICAL);
        shell.setBackgroundColor(chrome.surface);
        // Navigation and actions stay put; only the key strip scrolls, so no
        // control can be scrolled out of reach on a narrow panel.
        LinearLayout pinned = new LinearLayout(this);
        pinned.setOrientation(LinearLayout.HORIZONTAL);
        pinned.setPadding(dp(4), 0, 0, 0);
        // A raw shell has no Herdr workspaces, panes or host rail to show.
        if (!shellMode()) {
            sidebarButton = iconButton(R.drawable.ic_bar_hosts, "Hosts",
                    "Show the host and workspace sidebar", view -> toggleSidebar());
            pinned.addView(sidebarButton);
            pinned.addView(iconButton(R.drawable.ic_bar_panes, "Panes",
                    "Workspaces, tabs and panes for this host", view -> showPanes()));
        }
        ImageButton keyboard = iconButton(R.drawable.ic_bar_keyboard, "Keyboard",
                "Show the on-screen keyboard", view -> {
                    Connection target;
                    synchronized (stateLock) { target = connection; }
                    if (target != null) nativeShowKeyboard(target.generation);
                });
        // Input controls resolve on touch-up, in order with the keys they sit by.
        synchronousTouch(keyboard);
        pinned.addView(keyboard);
        if (compact) {
            // A compact bar keeps frequent controls and the keys in view; the
            // rest live in one overflow menu, as platform toolbars do.
            pinned.addView(iconButton(R.drawable.ic_bar_more, "More",
                    "More terminal actions", this::showMoreActions));
        } else {
            pinned.addView(iconButton(R.drawable.ic_bar_view, "View",
                    "Terminal display and keyboard options", view -> showDisplayOptions()));
            pinned.addView(iconButton(R.drawable.ic_bar_session, "Session",
                    shellMode() ? (debuggable() ? "Copy the screen, save a screenshot or return to the hosts"
                            : "Copy the screen or return to the hosts")
                            : "New tab, split, zoom or close this session's pane", view -> showSessionActions()));
            pinned.addView(iconButton(R.drawable.ic_bar_close, "Close",
                    "Close this terminal and return to the host list", view -> finish()));
        }
        View divider = new View(this);
        divider.setBackgroundColor(chrome.outline);
        divider.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        LinearLayout.LayoutParams dividerParams = new LinearLayout.LayoutParams(dp(1), dp(24));
        dividerParams.setMargins(dp(4), 0, dp(4), 0);
        LinearLayout keys = new LinearLayout(this);
        keys.setOrientation(LinearLayout.HORIZONTAL);
        keys.setGravity(android.view.Gravity.CENTER_VERTICAL);
        keys.setPadding(0, 0, dp(16), 0);
        // Panning is client-side: it is how a wide pane stays readable at a type
        // size that actually fits this display. A shell fits its own width.
        for (float panStep : shellMode() ? new float[0] : new float[]{-96f, 96f}) {
            final float step = panStep;
            ImageButton pan = iconButton(panStep < 0 ? R.drawable.ic_bar_pan_left : R.drawable.ic_bar_pan_right,
                    panStep < 0 ? "Pan left" : "Pan right",
                    panStep < 0 ? "Pan the pane left" : "Pan the pane right", view -> nativePan(step));
            synchronousTouch(pan);
            keys.addView(pan);
        }
        java.util.List<Button> keycaps = createKeycaps();
        if (keyGrid != null) keyGrid.removeAllViews();
        if (keysOnDeck && keyGrid != null) {
            // Tabletop: the same keys, larger, on the half where the hands are.
            fillKeyDeck(keycaps);
        } else {
            for (Button keycap : keycaps) keys.addView(keycap, keycapParams());
        }
        HorizontalScrollView outer = new HorizontalScrollView(this);
        outer.setHorizontalScrollBarEnabled(false);
        outer.addView(keys, new FrameLayout.LayoutParams(-2, dp(48)));
        // The scroller cuts its last key in half; the fade marks the boundary
        // as "swipe for more" instead of a rendering fault.
        FrameLayout scrolling = new FrameLayout(this);
        scrolling.addView(outer, new FrameLayout.LayoutParams(-1, dp(48)));
        View fade = new View(this);
        fade.setBackground(new android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.RIGHT_LEFT,
                new int[]{chrome.surface, GpuiChrome.withAlpha(chrome.surface, 0)}));
        fade.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        FrameLayout.LayoutParams fadeParams = new FrameLayout.LayoutParams(dp(20), dp(48));
        fadeParams.gravity = android.view.Gravity.END;
        scrolling.addView(fade, fadeParams);
        shell.addView(pinned, new LinearLayout.LayoutParams(-2, dp(48)));
        shell.addView(divider, dividerParams);
        shell.addView(scrolling, new LinearLayout.LayoutParams(0, dp(48), 1));
        updateControlButton();
        updateRailToggle();
        return shell;
    }

    /** The Ctrl latch and the host's shortcut keys, wherever this layout puts them. */
    private java.util.List<Button> createKeycaps() {
        java.util.List<Button> keycaps = new java.util.ArrayList<>();
        controlButton = keycap("Ctrl");
        controlButton.setOnClickListener(view -> {
            synchronized (stateLock) {
                if (connection == null || connection.isClosed() || !connection.ready
                        || compositionBlocksInput(connection)) return;
                controlLatched = !controlLatched;
                updateControlButton();
            }
        });
        synchronousTouch(controlButton);
        keycaps.add(controlButton);
        for (Shortcut shortcut : shortcuts) {
            Button button = keycap(shortcut.getLabel());
            button.setOnClickListener(view -> sendShortcut(shortcut));
            synchronousTouch(button);
            keycaps.add(button);
        }
        return keycaps;
    }

    /** Lays the keys out as a grid of 56 dp keys, as many columns as fit. */
    private void fillKeyDeck(java.util.List<Button> keycaps) {
        keyDeck.setBackgroundColor(chrome.surface);
        int width = terminalContent == null ? 0 : terminalContent.getWidth();
        keyGrid.setColumnCount(Math.max(3, Math.min(8, width / dp(112))));
        for (Button keycap : keycaps) {
            keycap.setTextSize(15);
            keycap.setMinHeight(dp(56));
            keycap.setMinimumHeight(dp(56));
            android.widget.GridLayout.LayoutParams cell = new android.widget.GridLayout.LayoutParams(
                    android.widget.GridLayout.spec(android.widget.GridLayout.UNDEFINED),
                    android.widget.GridLayout.spec(android.widget.GridLayout.UNDEFINED, 1f));
            cell.width = 0;
            cell.height = dp(56);
            cell.setMargins(dp(4), 0, dp(4), 0);
            keyGrid.addView(keycap, cell);
        }
    }

    /**
     * Tabletop: the terminal keeps the half above the fold and the keys move to
     * a deck below it, over the toolbar, so no row sits on the bend. Without
     * room under the fold - an open keyboard, say - the normal layout returns.
     */
    private void applyPosture() {
        if (terminalContent == null || terminalSurface == null || keyDeck == null) return;
        int[] origin = new int[2];
        terminalContent.getLocationInWindow(origin);
        int above = posture.heightAbove(origin[1], origin[1] + terminalContent.getHeight(),
                dp(200), dp(48 + 2 * 56 + 16));
        boolean deck = above >= 0;
        int height = deck ? above : 0;
        float weight = deck ? 0f : 1f;
        LinearLayout.LayoutParams surfaceParams = (LinearLayout.LayoutParams) terminalSurface.getLayoutParams();
        if (surfaceParams.height != height || surfaceParams.weight != weight) {
            terminalSurface.setLayoutParams(new LinearLayout.LayoutParams(-1, height, weight));
        }
        LinearLayout.LayoutParams deckParams = (LinearLayout.LayoutParams) keyDeck.getLayoutParams();
        int hinge = deck ? posture.foldBottom - posture.foldTop : 0;
        if (deckParams.topMargin != hinge) {
            deckParams.topMargin = hinge;
            keyDeck.setLayoutParams(deckParams);
        }
        if (deck != keysOnDeck) {
            keysOnDeck = deck;
            keyDeck.setVisibility(deck ? View.VISIBLE : View.GONE);
            rebuildShortcutBar();
        }
    }

    /** Rebuilds the toolbar for a new theme or window size class, in place. */
    private void rebuildShortcutBar() {
        if (terminalContent == null || shortcutBar == null) return;
        int index = terminalContent.indexOfChild(shortcutBar);
        terminalContent.removeView(shortcutBar);
        shortcutBar = createShortcutBar();
        terminalContent.addView(shortcutBar, index, new LinearLayout.LayoutParams(-1, dp(48)));
    }

    /** Actions a compact toolbar keeps out of view, with the labels the wide one tooltips. */
    private void showMoreActions(View anchor) {
        android.content.Context themed = new android.view.ContextThemeWrapper(this, chrome.popupTheme());
        android.widget.PopupMenu menu = new android.widget.PopupMenu(themed, anchor);
        ColorStateList tint = ColorStateList.valueOf(themeColor(themed, android.R.attr.textColorPrimary));
        menu.getMenu().add(0, 1, 0, "View options").setIcon(R.drawable.ic_bar_view).setIconTintList(tint);
        menu.getMenu().add(0, 2, 1, "Session actions").setIcon(R.drawable.ic_bar_session).setIconTintList(tint);
        menu.getMenu().add(0, 3, 2, "Close terminal").setIcon(R.drawable.ic_bar_close).setIconTintList(tint);
        menu.setForceShowIcon(true);
        menu.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == 1) showDisplayOptions();
            else if (item.getItemId() == 2) showSessionActions();
            else finish();
            return true;
        });
        menu.show();
    }

    /** A 48 dp icon button; its label is a tooltip and the overflow menu's text. */
    private ImageButton iconButton(int icon, String label, String description, View.OnClickListener action) {
        ImageButton button = new ImageButton(this);
        button.setImageResource(icon);
        button.setImageTintList(ColorStateList.valueOf(chrome.onSurface));
        button.setBackground(controlBackground(false, 0x00000000, 12, dp(4)));
        button.setScaleType(android.widget.ImageView.ScaleType.CENTER);
        button.setContentDescription(description);
        button.setTooltipText(label);
        button.setFocusable(false);
        button.setSaveEnabled(false);
        button.setOnClickListener(action);
        button.setLayoutParams(new LinearLayout.LayoutParams(dp(48), dp(48)));
        return button;
    }

    /** A terminal key: text on a tonal keycap, 40 dp tall inside a 48 dp target. */
    private Button keycap(String title) {
        Button button = new Button(this);
        button.setText(title);
        button.setTextSize(13);
        button.setAllCaps(false);
        button.setMinWidth(dp(48));
        button.setMinimumWidth(dp(48));
        button.setMinHeight(dp(48));
        button.setMinimumHeight(dp(48));
        button.setPadding(dp(12), 0, dp(12), 0);
        button.setTextColor(chrome.onSurface);
        button.setBackground(controlBackground(false, chrome.control, 10, dp(4)));
        button.setStateListAnimator(null);
        button.setFocusable(false);
        button.setSaveEnabled(false);
        return button;
    }

    private LinearLayout.LayoutParams keycapParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-2, dp(48));
        params.setMargins(dp(2), 0, dp(2), 0);
        return params;
    }

    /**
     * Rounded fill inset to its visual size, with a press ripple. The view stays
     * a full 48 dp touch target; {@code selected} swaps in the selection fill.
     */
    private android.graphics.drawable.Drawable controlBackground(boolean selected, int fill, int radiusDp, int inset) {
        android.graphics.drawable.GradientDrawable shape = roundedDrawable(selected ? chrome.selected : fill, radiusDp);
        android.graphics.drawable.GradientDrawable mask = roundedDrawable(0xffffffff, radiusDp);
        return new android.graphics.drawable.RippleDrawable(
                ColorStateList.valueOf(GpuiChrome.withAlpha(chrome.onSurface, 0x29)),
                new android.graphics.drawable.InsetDrawable(shape, 0, inset, 0, inset),
                new android.graphics.drawable.InsetDrawable(mask, 0, inset, 0, inset));
    }

    private static int themeColor(android.content.Context context, int attribute) {
        android.content.res.TypedArray values = context.obtainStyledAttributes(new int[]{attribute});
        try {
            return values.getColor(0, 0xff000000);
        } finally {
            values.recycle();
        }
    }

    // performClick is dispatched from the touch lambda below; lint cannot see it.
    @SuppressLint("ClickableViewAccessibility")
    private void synchronousTouch(View button) {
        GpuiShortcutTouch tap = new GpuiShortcutTouch();
        button.setOnTouchListener((view, event) -> {
            int action = event.getActionMasked();
            if (action != MotionEvent.ACTION_DOWN && action != MotionEvent.ACTION_UP
                    && action != MotionEvent.ACTION_MOVE && action != MotionEvent.ACTION_CANCEL
                    && action != MotionEvent.ACTION_POINTER_DOWN && action != MotionEvent.ACTION_POINTER_UP) return false;
            boolean inside = event.getX() >= 0 && event.getX() < view.getWidth()
                    && event.getY() >= 0 && event.getY() < view.getHeight();
            boolean clicked = tap.event(action, inside, event.getPointerCount() == 1);
            view.setPressed(tap.isPressed());
            // Resolve before the next physical event, rather than Button's deferred PerformClick.
            if (clicked) view.performClick();
            return true;
        });
    }

    private float rowHeightPx() {
        long rows;
        synchronized (stateLock) { rows = connection == null ? 0 : connection.scrollRows; }
        if (rows > 0 && terminalRoot != null) {
            int height = terminalRoot.getHeight() - terminalRoot.getPaddingTop() - terminalRoot.getPaddingBottom();
            if (height > 0) return Math.max(1f, (float) height / rows);
        }
        float font = displayOptions == null ? 13f
                : displayOptions.effectiveFont(compactDisplay, getResources().getConfiguration().fontScale);
        float lineHeight = compactDisplay ? 1.4f : 1.5f;
        return Math.max(1f, font * lineHeight * getResources().getDisplayMetrics().density);
    }

    /** Drag down reveals older lines: the client scrolls its own buffered rows. */
    private boolean requestScroll(int lines) {
        final Connection target;
        synchronized (stateLock) { target = connection; }
        if (target == null || target.isClosed() || target.remoteHandle <= 0) return false;
        if (lines > 0 && lastFollowSent) {
            lastFollowSent = false;
            nativeSetFollow(target.generation, false);
        }
        target.fastUntil = SystemClock.uptimeMillis() + 1200;
        try {
            scrollWorker.execute(() -> {
                try {
                    JSONObject answer = new JSONObject(RemoteNative.scrollLocal(target.remoteHandle, lines,
                            target.generation, target.title, target.status, shortcutJson));
                    if (answer.optBoolean("following", false) && !lastFollowSent) {
                        lastFollowSent = true;
                        nativeSetFollow(target.generation, true);
                    }
                    fetchRows(target, answer);
                } catch (Exception | LinkageError ignored) {
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException busy) {
            return false;
        }
        return true;
    }

    /**
     * Asks the daemon to draw the offset the native view needs next: a gap in
     * the buffer, the screen ahead of the finger, or live output once back at
     * the tail. Each frame fills a whole screen of rows, so one request per
     * offset is enough; it is repeated only when its frame never arrives.
     */
    private void fetchRows(Connection target, JSONObject answer) {
        if (answer.isNull("fetch")) return;
        long offset = answer.optLong("fetch", -1);
        if (offset < 0) return;
        long now = SystemClock.uptimeMillis();
        synchronized (target) {
            if (offset == target.fetchedOffset && now - target.fetchedAt < FETCH_RETRY_MS) return;
            target.fetchedOffset = offset;
            target.fetchedAt = now;
        }
        sendScroll(target, offset);
    }

    /** Asks the daemon to render at `offset`, which backfills the local buffer. */
    private void sendScroll(Connection target, long offset) {
        try {
            scrollWorker.execute(() -> {
                try { RemoteNative.scrollTo(target.remoteHandle, offset); }
                catch (RuntimeException | LinkageError ignored) { }
            });
        } catch (java.util.concurrent.RejectedExecutionException busy) { }
    }

    /** Nothing pends: local scrolling never waits on a round trip. */
    private void flushScroll() { }

    private TextView latestButton;
    /** The pane last checked for a waiting agent; touched on the poll thread only. */
    private volatile String autoKeyboardPane = "";

    /**
     * "↓ Latest" floats over the terminal while its history is scrolled back, so
     * reading old output never loses the way back to the live end.
     */
    private void showLatest(boolean scrolledBack) {
        if (destroyed || terminalContent == null) return;
        if (latestButton == null) {
            if (!scrolledBack) return;
            latestButton = new TextView(this);
            latestButton.setText("↓ Latest");
            latestButton.setTextSize(12);
            latestButton.setTextColor(chrome.onSurface);
            latestButton.setPadding(dp(10), dp(5), dp(10), dp(5));
            android.graphics.drawable.GradientDrawable pill = new android.graphics.drawable.GradientDrawable();
            pill.setColor(chrome.control);
            pill.setStroke(dp(1), chrome.outline);
            pill.setCornerRadius(dp(14));
            latestButton.setBackground(pill);
            latestButton.setContentDescription("Jump to the latest output");
            latestButton.setOnClickListener(v -> { jumpToLive(); showLatest(false); });
            FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(-2, -2,
                    android.view.Gravity.BOTTOM | android.view.Gravity.END);
            params.setMargins(0, 0, dp(12), dp(60));
            terminalRoot.addView(latestButton, params);
        }
        latestButton.setVisibility(scrolledBack ? View.VISIBLE : View.GONE);
    }

    private void jumpToLive() {
        final Connection target;
        synchronized (stateLock) { target = connection; }
        if (target == null || target.isClosed() || target.remoteHandle <= 0) return;
        desiredScroll = 0;
        lastSentScroll = -1;
        scrollSeeking = true;
        synchronized (target) { target.fetchedOffset = -1; }
        if (!lastFollowSent) {
            lastFollowSent = true;
            nativeSetFollow(target.generation, true);
        }
        try {
            worker.execute(() -> {
                try { RemoteNative.scrollTo(target.remoteHandle, 0); }
                catch (RuntimeException | LinkageError ignored) { }
            });
        } catch (java.util.concurrent.RejectedExecutionException busy) { }
    }

    private int dp(float value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    private void clearControl() {
        if (!controlLatched) return;
        controlLatched = false;
        updateControlButton();
    }

    private void updateControlButton() {
        if (controlButton == null) return;
        controlButton.setText(controlLatched ? "Ctrl •" : "Ctrl");
        controlButton.setSelected(controlLatched);
        controlButton.setBackground(controlBackground(controlLatched, chrome.control, 10, dp(4)));
        controlButton.setContentDescription(controlLatched
                ? "Control armed for next letter. Tap to cancel" : "Control modifier for the next letter");
    }

    private void loadDisplayOptions() {
        SharedPreferences preferences = getSharedPreferences("terminal-display", MODE_PRIVATE);
        try {
            displayOptions = new GpuiDisplayOptions(preferences.getInt("mode", 0),
                    preferences.getInt("font", 0), preferences.getInt("alt", 0));
        } catch (ClassCastException invalid) {
            displayOptions = new GpuiDisplayOptions(0, 0, 0);
        }
    }

    private void applyDisplayOptions() {
        if (!hostStarted || destroyed || terminalRoot == null || displayOptions == null) return;
        // Configuration dimensions follow the app window through fold/orientation changes,
        // but unlike the padded root they are not reduced by transient IME insets.
        Configuration configuration = getResources().getConfiguration();
        float width = configuration.screenWidthDp;
        float height = configuration.screenHeightDp;
        if (width <= 0 || height <= 0) return;
        compactDisplay = displayOptions.compact(width, height);
        // Zero hands type sizing to the renderer, which fits the pane's own
        // wrapping width; a first run on a fold should not be caption-sized.
        float systemScale = getResources().getConfiguration().fontScale;
        if (!Float.isFinite(systemScale) || systemScale <= 0) systemScale = 1;
        // A pinch for this posture wins, then a size chosen in the sheet, then
        // the provisioned default; zero still means the renderer's own fit.
        float pinched = pinchedFont();
        float provisioned = ProvisionedSettings.get(this).fontSize;
        float chosenFont = pinched > 0 ? pinched * systemScale
                : displayOptions.fontSize != 0
                        ? displayOptions.effectiveFont(compactDisplay, systemScale)
                        : provisioned > 0 ? provisioned * systemScale : 0f;
        appliedFont = chosenFont > 0 ? chosenFont / systemScale : compactDisplay ? 12 : 13;
        nativeSetDisplayPreferences(chosenFont, compactDisplay,
                getResources().getConfiguration().fontScale);
    }

    /** Terminal text in points (before the system scale) last sent to the renderer. */
    private float appliedFont = 13;

    /** Folded and unfolded keep their own pinch, so each screen stays as set. */
    private String pinchKey() { return compactDisplay ? "pinch_compact" : "pinch_standard"; }

    private float pinchedFont() {
        return getSharedPreferences("terminal-display", MODE_PRIVATE).getFloat(pinchKey(), 0f);
    }

    private android.view.ScaleGestureDetector pinch;
    private float pinchFont;
    private boolean pinching;

    /** Two fingers resize the terminal text, 9 to 24 points, kept per posture. */
    private android.view.ScaleGestureDetector pinchDetector() {
        if (pinch != null) return pinch;
        pinch = new android.view.ScaleGestureDetector(this,
                new android.view.ScaleGestureDetector.SimpleOnScaleGestureListener() {
                    @Override public boolean onScaleBegin(android.view.ScaleGestureDetector detector) {
                        pinching = true;
                        pinchFont = appliedFont;
                        return true;
                    }
                    @Override public boolean onScale(android.view.ScaleGestureDetector detector) {
                        float next = Math.max(9f, Math.min(24f, pinchFont * detector.getScaleFactor()));
                        // Whole half-points only: every step reflows the grid.
                        next = Math.round(next * 2f) / 2f;
                        if (next != pinchFont) {
                            pinchFont = next;
                            getSharedPreferences("terminal-display", MODE_PRIVATE).edit()
                                    .putFloat(pinchKey(), next).apply();
                            applyDisplayOptions();
                        }
                        return true;
                    }
                    @Override public void onScaleEnd(android.view.ScaleGestureDetector detector) {
                        pinching = false;
                    }
                });
        return pinch;
    }

    private void hideInitialKeyboard() {
        if (keyboardRequested || initialImeHidden || terminalRoot == null
                || terminalRoot.getWindowToken() == null || !hasWindowFocus()) return;
        initialImeHidden = true;
        ((android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE))
                .hideSoftInputFromWindow(terminalRoot.getWindowToken(), 0);
        if (Build.VERSION.SDK_INT >= 30) {
            WindowInsetsController controller = getWindow().getInsetsController();
            if (controller != null) controller.hide(WindowInsets.Type.ime());
        }
    }

    @Override public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (!hasFocus) clearControl();
        else hideInitialKeyboard();
    }

    @Override public void onConfigurationChanged(Configuration configuration) {
        super.onConfigurationChanged(configuration);
        clearControl();
        if (terminalRoot != null) terminalRoot.post(() -> {
            // Folding to the cover crosses 600 dp: the bar swaps inline actions
            // for its overflow menu and the rail becomes a drawer, or back.
            if (compactWindow() != toolbarCompact) rebuildShortcutBar();
            applySidebarDefault();
            applyDisplayOptions();
            // Follow system tracks the dark theme switch without a restart.
            applyTheme();
        });
    }

    /** A platform alert with this theme's lightness; build its views from {@code getContext()}. */
    private AlertDialog.Builder sheetBuilder() {
        return new AlertDialog.Builder(this, chrome.dialogTheme());
    }

    private TextView optionLabel(android.content.Context context, String text) {
        TextView label = new TextView(context);
        label.setText(text);
        // Caption weight: the spinner below carries the actual value.
        label.setTextSize(13);
        label.setTextColor(themeColor(context, android.R.attr.textColorSecondary));
        label.setPadding(0, dp(8), 0, dp(4));
        label.setSaveEnabled(false);
        return label;
    }

    private Spinner optionSpinner(android.content.Context context, String[] choices, int selection) {
        Spinner spinner = new Spinner(context);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(context, android.R.layout.simple_spinner_item, choices);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter);
        spinner.setSelection(selection);
        spinner.setMinimumHeight(dp(48));
        spinner.setSaveEnabled(false);
        return spinner;
    }

    private void showDisplayOptions() {
        if (displayDialog != null && displayDialog.isShowing()) return;
        clearControl();
        allowNextKeyboardShow = false;
        synchronized (stateLock) {
            imeComposing = false;
            if (connection != null && connection.ready && !connection.isClosed())
                nativeImeEvent(connection.generation, imeSession, 4, "", 0, 0);
        }
        // Reset also fences existing InputConnection wrappers, including same-session ones.
        super.gpuiHideKeyboard(imeSession);
        AlertDialog.Builder builder = sheetBuilder();
        android.content.Context context = builder.getContext();
        LinearLayout options = new LinearLayout(context);
        options.setOrientation(LinearLayout.VERTICAL);
        options.setPadding(dp(24), 0, dp(24), dp(12));
        options.addView(optionLabel(context, "Layout"));
        Spinner mode = optionSpinner(context, new String[]{"Automatic (available space)", "Compact / Titan 2 Elite", "Standard"}, displayOptions.mode);
        options.addView(mode);
        options.addView(optionLabel(context, "Text size (respects system text scale)"));
        String[] sizes = {"Automatic", "11", "12", "13", "14", "15", "16", "17", "18"};
        Spinner font = optionSpinner(context, sizes, displayOptions.fontSize == 0 ? 0 : displayOptions.fontSize - 10);
        options.addView(font);
        options.addView(optionLabel(context, "Theme"));
        Spinner theme = optionSpinner(context, new String[]{"Default", "Nord", "Mocha", "Latte",
                "Follow system (Latte or Mocha)"}, themeIndex);
        options.addView(theme);
        options.addView(optionLabel(context, "Physical Alt key"));
        Spinner alt = optionSpinner(context, new String[]{"Default (device symbols)", "Device symbols", "Terminal Alt shortcuts"}, displayOptions.altMode);
        options.addView(alt);
        // One line of guidance; the host and status it used to repeat are already
        // in the header this sheet opens over.
        TextView help = optionLabel(context, "Compact keeps more rows visible. Opening this sheet cancels an unsent draft.");
        options.addView(help);
        android.widget.ScrollView scroll = new android.widget.ScrollView(context);
        scroll.addView(options);
        displayDialog = builder.setTitle("Terminal view")
                .setView(scroll).setNegativeButton("Cancel", null)
                .setPositiveButton("Apply", (dialog, which) -> {
                    displayOptions = new GpuiDisplayOptions(mode.getSelectedItemPosition(),
                            font.getSelectedItemPosition() == 0 ? 0 : font.getSelectedItemPosition() + 10,
                            alt.getSelectedItemPosition());
                    themeIndex = theme.getSelectedItemPosition();
                    getSharedPreferences("terminal-display", MODE_PRIVATE).edit()
                            .putInt("mode", displayOptions.mode).putInt("font", displayOptions.fontSize)
                            .putInt("alt", displayOptions.altMode).putInt("theme", themeIndex).apply();
                    applyTheme();
            // Host switching needs the saved hosts before the first rail paint.
            worker.execute(() -> {
                try {
                    java.util.List<SavedRemoteHost> hosts = new RemoteHostStore(this).read();
                    runOnUiThread(() -> { savedHosts = hosts; refreshSidebar(true); });
                } catch (Exception ignored) {
                    // An unreadable store leaves the rail without host rows.
                }
            });
                    applyDisplayOptions();
                }).create();
        showLocalDialog(displayDialog);
    }


    /** Same opt-in the activity used at create time. */
    private boolean capturableSession() {
        return ProvisionedSettings.capturable(this);
    }

    private void showLocalDialog(AlertDialog dialog) {
        if (destroyed || isFinishing()) return;
        AlertDialog previous = displayDialog;
        if (previous != null && previous != dialog) previous.dismiss();
        displayDialog = dialog;
        dialog.setOnDismissListener(closedDialog -> {
            if (displayDialog == closedDialog) displayDialog = null;
            clearControl();
        });
        // Dialogs carry the same private content as the terminal, so they stay
        // secure unless the same debuggable opt-in applies; otherwise no sheet in
        // this app could ever be captured or reviewed.
        if (!capturableSession()) dialog.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
        dialog.show();
    }

    /** Mirrors belong only to the resumed activity's visible sidebar. */
    private void updateHostSampling() {
        if (!hostResumed || !sidebarOpen || destroyed || shellMode()) {
            stopHostSampling();
            return;
        }
        if (sampler == null) {
            sampler = java.util.concurrent.Executors.newScheduledThreadPool(3, runnable -> {
                Thread thread = new Thread(runnable, "herdr-host-sample");
                thread.setDaemon(true);
                return thread;
            });
        }
        for (SavedRemoteHost host : savedHosts) {
            final String hostId = host.id;
            // Hosts loaded after the sampler started still need a mirror; the
            // old early return left them showing "no session" forever.
            HostSample sample = new HostSample();
            if (hostSamples.putIfAbsent(hostId, sample) != null) continue;
            sampler.scheduleWithFixedDelay(() -> sampleHost(hostId, sample), 0, 3, TimeUnit.SECONDS);
        }
    }

    private void stopHostSampling() {
        if (sampler != null) {
            sampler.shutdownNow();
            sampler = null;
        }
        for (HostSample sample : hostSamples.values()) {
            closeSample(sample);
        }
        hostSamples.clear();
    }

    private boolean sampleCurrent(String hostId, HostSample sample) {
        return hostResumed && sidebarOpen && !destroyed
                && !hostId.equals(currentHostId) && !hostId.equals(switchingTo)
                && hostSamples.get(hostId) == sample;
    }

    /** A stopped sampler can never acquire a replacement sampler's host row. */
    private void sampleHost(String hostId, HostSample sample) {
        if (!sampleCurrent(hostId, sample)) {
            closeSample(sample);
            return;
        }
        SavedRemoteHost host = null;
        for (SavedRemoteHost candidate : savedHosts) {
            if (candidate.id.equals(hostId)) host = candidate;
        }
        if (host == null || host.expectedFingerprint.isEmpty()) return;
        long now = SystemClock.uptimeMillis();
        if (sample.handle == 0) {
            if (now < sample.nextAttemptAt) return;
            try {
                RemoteNative.ensureLoaded(this);
                char[] key = new RemoteIdentityStore(this).read();
                String credential;
                try {
                    credential = new JSONObject().put("private_key", new String(key)).toString();
                } finally {
                    java.util.Arrays.fill(key, '\0');
                }
                // The rail needs snapshots, not a terminal surface for every
                // unseen host. Negotiate that interest before the first frame.
                long handle = RemoteNative.connectGrid(host.connectionJson(), credential, terminalGrid(), false);
                if (handle <= 0) throw new IllegalStateException("connect refused");
                boolean retained;
                synchronized (sample) {
                    retained = sampleCurrent(hostId, sample);
                    if (retained) {
                        sample.handle = handle;
                        sample.connected = false;
                    }
                }
                if (!retained) {
                    RemoteNative.close(handle);
                    return;
                }
            } catch (Exception | LinkageError failure) {
                sample.nextAttemptAt = now + 30_000;
                return;
            }
        }
        try {
            JSONObject state = new JSONObject(RemoteNative.poll(sample.handle, sample.revision, true));
            if (state.optBoolean("unchanged", false)) {
                if (sample.connected) sample.connected = true;
                return;
            }
            if ("closed".equals(state.optString("state"))) throw new IllegalStateException("closed");
            JSONArray panes = state.optJSONArray("panes");
            if (panes != null && panes.length() > 0) {
                sample.panes = panes;
                sample.workspaces = state.optJSONArray("workspaces");
                sample.tabs = state.optJSONArray("tabs");
                sample.revision = state.optLong("token", state.optLong("revision", 0));
                sample.connected = true;
                sidebarSignature = "";
                if (!destroyed) runOnUiThread(() -> refreshSidebar(false));
            }
        } catch (Exception | LinkageError failure) {
            closeSample(sample);
            sample.nextAttemptAt = now + 30_000;
            if (!destroyed) runOnUiThread(() -> refreshSidebar(true));
        }
    }

    private void closeSample(HostSample sample) {
        long handle;
        synchronized (sample) {
            handle = sample.handle;
            sample.handle = 0;
            sample.connected = false;
        }
        if (handle != 0) {
            try { RemoteNative.close(handle); } catch (RuntimeException | LinkageError ignored) { }
        }
    }

    private void rememberHostPane(String paneId) {
        rememberHostPane(currentHostId, paneId);
    }

    private void rememberHostPane(String hostId, String paneId) {
        if (hostId == null || paneId == null || paneId.isEmpty()) return;
        getSharedPreferences("terminal-host-panes", MODE_PRIVATE).edit()
                .putString(hostId, paneId).apply();
        String preference = "remote-startup";
        getSharedPreferences(preference, MODE_PRIVATE).edit()
                .putString("last_host", hostId)
                .putString("last_pane", paneId)
                .apply();
    }

    private String rememberedHostPane(String hostId) {
        if (hostId == null) return "";
        return getSharedPreferences("terminal-host-panes", MODE_PRIVATE).getString(hostId, "");
    }

    private static boolean hasPane(JSONArray panes, String paneId) {
        for (int i = 0; i < panes.length(); i++) {
            JSONObject pane = panes.optJSONObject(i);
            if (pane != null && paneId.equals(pane.optString("id", ""))) return true;
        }
        return false;
    }

    /**
     * Connects to another saved host with the stored device key and keeps the
     * GPUI surface: only the connection and its generation change.
     */
    private void switchHost(SavedRemoteHost host) { switchHost(host, null); }

    private void switchHost(SavedRemoteHost host, String pane) {
        if (host == null || switchingHost || host.id.equals(currentHostId)) return;
        if (host.expectedFingerprint.isEmpty()) {
            Toast.makeText(this, "Trust " + host.name + " from Remote hosts first", Toast.LENGTH_LONG).show();
            return;
        }
        switchingHost = true;
        switchingTo = host.id;
        // The mirror for this host is released before the real session opens;
        // two connections to one host would waste the handle budget.
        HostSample target = hostSamples.get(host.id);
        if (target != null) closeSample(target);
        final String forcedPane = pane;
        final Connection previous;
        synchronized (stateLock) { previous = connection; }
        if (previous != null) {
            synchronized (stateLock) { publish(previous, "Switching to " + host.name + "…"); }
        }
        worker.execute(() -> {
            long handle = 0;
            try {
                char[] key = new RemoteIdentityStore(this).read();
                String credential;
                try {
                    credential = new JSONObject().put("private_key", new String(key)).toString();
                } finally {
                    java.util.Arrays.fill(key, '\0');
                }
                handle = RemoteNative.connectGrid(host.connectionJson(), credential, terminalGrid(), true);
                if (handle <= 0) throw new IllegalStateException("connection refused");
                String chosen = awaitHostPane(handle, host.id, forcedPane);
                final long readyHandle = handle;
                final String readyPane = chosen;
                runOnUiThread(() -> adoptHost(host, readyHandle, readyPane));
                handle = 0;
            } catch (Exception | LinkageError failure) {
                if (handle != 0) RemoteNative.close(handle);
                runOnUiThread(() -> {
                    switchingHost = false;
                    switchingTo = null;
                    Toast.makeText(this, "Could not switch to " + host.name, Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    /** Waits for the new session, focuses its first pane and returns that id. */
    private String awaitHostPane(long handle, String hostId, String forcedPane) throws Exception {
        long until = SystemClock.uptimeMillis() + 45_000;
        JSONObject state = null;
        while (SystemClock.uptimeMillis() < until) {
            state = new JSONObject(RemoteNative.poll(handle, 0, false));
            String phase = state.optString("state");
            if ("closed".equals(phase)) throw new IllegalStateException("closed");
            // The pane list arrives with the first snapshot; an empty array is
            // not readiness.
            JSONArray listed = state.optJSONArray("panes");
            if ("ready".equals(phase) || listed != null && listed.length() > 0) break;
            Thread.sleep(150);
        }
        JSONArray panes = state == null ? null : state.optJSONArray("panes");
        if (panes == null || panes.length() == 0) throw new IllegalStateException("no panes");
        // An explicit pick wins; then the pane this phone last used on that
        // host; the daemon's own focus is often an idle shell.
        String pane = forcedPane != null && !forcedPane.isEmpty() ? forcedPane : rememberedHostPane(hostId);
        // Then a pane that is actually doing something: an idle shell reads as
        // a blank terminal even though it is rendering.
        if (pane.isEmpty() || !hasPane(panes, pane)) {
            for (int i = 0; i < panes.length() && pane.isEmpty(); i++) {
                String status = panes.getJSONObject(i).optString("status", "");
                if (!"unknown".equals(status) && !"idle".equals(status)) {
                    pane = panes.getJSONObject(i).optString("id", "");
                }
            }
        }
        if (pane.isEmpty() || !hasPane(panes, pane)) {
            for (int i = 0; i < panes.length() && pane.isEmpty(); i++) {
                if (!"unknown".equals(panes.getJSONObject(i).optString("status", ""))) {
                    pane = panes.getJSONObject(i).optString("id", "");
                }
            }
        }
        if (pane.isEmpty() || !hasPane(panes, pane)) {
            pane = state.optString("focused_pane", "");
        }
        if (pane.isEmpty() || !hasPane(panes, pane)) pane = panes.getJSONObject(0).optString("id", "");
        rememberHostPane(hostId, pane);
        if (pane.isEmpty()) throw new IllegalStateException("no pane id");
        RemoteNative.focus(handle, pane);
        until = SystemClock.uptimeMillis() + 20_000;
        while (SystemClock.uptimeMillis() < until) {
            state = new JSONObject(RemoteNative.poll(handle, 0, false));
            if (pane.equals(state.optString("pane_id"))
                    && "ready".equals(state.optString("state"))) return pane;
            Thread.sleep(150);
        }
        return pane;
    }

    private void adoptHost(SavedRemoteHost host, long handle, String pane) {
        if (destroyed || isFinishing()) {
            RemoteNative.close(handle);
            return;
        }
        Connection next;
        synchronized (stateLock) {
            Connection previous = connection;
            connection = null;
            if (previous != null) previous.close();
            next = new Connection(GENERATIONS.incrementAndGet(), handle);
            next.paneId = pane;
            next.hostName = host.name;
            scrollSeeking = false;
            lastFollowSent = true;
            next.title = host.name;
            next.remoteBoot = null;
            connection = next;
            currentHostId = host.id;
            lastSnapshotTitle = lastSnapshotText = lastSnapshotStatus = lastSnapshotShortcuts = null;
            nativeSetActive(next.generation, false);
            publish(next, "Connecting to " + host.name + "…");
            next.poll = worker.scheduleWithFixedDelay(() -> pump(next), 0, COMMAND_POLL_MS, TimeUnit.MILLISECONDS);
        }
        switchingHost = false;
        switchingTo = null;
        sidebarSignature = "";
        refreshSidebar(true);
    }

    /**
     * The rail opens by default only where it leaves a usable terminal: the
     * inner panel (798 dp here) has room, the cover panel (about 494 dp) does not,
     * and opening it there would squeeze the terminal to two thirds of a phone.
     * An explicit choice always wins.
     */
    private void applySidebarDefault() {
        // The stored value is the preference for a panel with room. Folding to the
        // cover must not keep half the terminal covered by the rail, and unfolding
        // restores what the user asked for.
        int widthDp = getResources().getConfiguration().screenWidthDp;
        boolean wantsRail = getSharedPreferences("terminal-display", MODE_PRIVATE)
                .getBoolean("list", true);
        boolean fits = widthDp >= 600;
        boolean open = wantsRail && fits && !shellMode();
        if (sidebarOpen != open) sidebarSignature = null;
        sidebarOpen = open;
        applySidebarWidth();
        updateHostSampling();
    }

    private static SurfaceView findSurface(View view) {
        if (view instanceof SurfaceView) return (SurfaceView) view;
        if (view instanceof android.view.ViewGroup) {
            android.view.ViewGroup group = (android.view.ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                SurfaceView found = findSurface(group.getChildAt(i));
                if (found != null) return found;
            }
        }
        return null;
    }

    private void captureScreen() {
        View decor = getWindow().getDecorView();
        final android.graphics.Bitmap image = android.graphics.Bitmap.createBitmap(
                decor.getWidth(), decor.getHeight(), android.graphics.Bitmap.Config.ARGB_8888);
        // A window PixelCopy shows exactly what the compositor presents, which a
        // software draw of the hierarchy does not reproduce for scrolled content.
        android.view.PixelCopy.request(getWindow(), image, result -> {
            if (result != android.view.PixelCopy.SUCCESS) {
                decor.draw(new android.graphics.Canvas(image));
                Toast.makeText(this, "Window copy failed: " + result, Toast.LENGTH_SHORT).show();
            }
            saveScreenshot(image);
        }, new Handler(Looper.getMainLooper()));
    }

    /** Writes the composite off the UI thread; the file stays app-private. */
    private void saveScreenshot(android.graphics.Bitmap image) {
        Thread writer = new Thread(() -> {
            try (java.io.FileOutputStream out =
                         new java.io.FileOutputStream(new java.io.File(getFilesDir(), "screen.png"))) {
                image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out);
            } catch (Exception ignored) {
            } finally {
                image.recycle();
            }
        }, "herdr-screenshot");
        writer.setDaemon(true);
        writer.start();
        Toast.makeText(this, "Screenshot saved", Toast.LENGTH_SHORT).show();
    }

    private void hideSidebar() {
        if (!sidebarOpen) return;
        setSidebarOpen(false);
    }

    /**
     * Debugging aids, such as the app-private screenshot that would bypass
     * FLAG_SECURE's intent, exist only in a debuggable build.
     */
    private boolean debuggable() {
        return (getApplicationInfo().flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0;
    }

    private void showSidebar() {
        if (sidebarOpen) return;
        setSidebarOpen(true);
    }

    private void toggleSidebar() {
        setSidebarOpen(!sidebarOpen);
    }

    private void setSidebarOpen(boolean open) {
        sidebarOpen = open;
        // The stored choice is for a panel with room; a drawer opened on the
        // cover is transient and must not reopen a rail after unfolding.
        if (!compactWindow()) {
            getSharedPreferences("terminal-display", MODE_PRIVATE).edit()
                    .putBoolean("list", sidebarOpen).apply();
        }
        applySidebarWidth();
        updateHostSampling();
        if (sidebarOpen) {
            refreshSidebar(true);
        }
    }

    private void applySidebarWidth() {
        if (sidebar == null) return;
        placeRail();
        int available = terminalRoot == null ? 0
                : terminalRoot.getWidth() - terminalRoot.getPaddingLeft() - terminalRoot.getPaddingRight();
        int width;
        if (!sidebarOpen) {
            width = 0;
        } else if (compactWindow()) {
            // Modal drawer: wide enough to read titles, never the whole window.
            width = available > 0 ? Math.min(dp(320), Math.round(available * 0.86f)) : dp(300);
        } else {
            width = Math.min(dp(240), Math.max(dp(140),
                    terminalRoot == null ? dp(180) : terminalRoot.getWidth() / 3));
        }
        android.view.ViewGroup.LayoutParams layout = sidebar.getLayoutParams();
        if (layout != null && layout.width != width) {
            layout.width = width;
            sidebar.setLayoutParams(layout);
        }
        updateRailToggle();
    }

    /** The toggle shows its state as a selected container, not a changing word. */
    private void updateRailToggle() {
        if (sidebarButton == null) return;
        sidebarButton.setSelected(sidebarOpen);
        sidebarButton.setBackground(controlBackground(sidebarOpen, 0x00000000, 12, dp(4)));
        sidebarButton.setTooltipText(sidebarOpen ? "Hide hosts" : "Hosts");
        sidebarButton.setContentDescription(sidebarOpen
                ? "Hide the host and workspace sidebar"
                : "Show the host and workspace sidebar");
    }

    /** Rebuilds the rail only when the daemon's chrome actually changed. */
    private void refreshSidebar(boolean force) {
        if (!sidebarOpen || sidebarRows == null) return;
        Connection current;
        synchronized (stateLock) { current = connection; }
        StringBuilder signature = new StringBuilder();
        for (SavedRemoteHost host : savedHosts) {
            HostSample sample = hostSamples.get(host.id);
            signature.append(host.id).append(':');
            if (host.id.equals(currentHostId)) {
                signature.append("live:").append(current == null ? 0 : current.chromeRevision);
            } else if (sample == null) {
                signature.append("none");
            } else if (!sample.connected) {
                signature.append("down");
            } else {
                // The daemon's revision moves whenever the tree does, so the rail
                // never has to stringify both arrays to notice a change.
                signature.append('r').append(sample.revision);
            }
            signature.append(';');
        }
        if (current != null) signature.append(current.paneId);
        signature.append(currentHostId);
        if (!force && signature.toString().equals(sidebarSignature)) return;
        sidebarSignature = signature.toString();
        if (savedHosts.isEmpty()) {
            pruneRailRows(java.util.Collections.emptySet());
            sidebarRows.removeAllViews();
            sidebarRows.addView(railText("No saved hosts yet", 13, chrome.onSurfaceVariant));
            return;
        }
        // Rows are reused, never rebuilt: the sampler refreshes every three
        // seconds and replacing a view under a finger cancels its tap.
        java.util.List<View> order = new java.util.ArrayList<>();
        java.util.Set<String> keep = new java.util.HashSet<>();
        // The machine this terminal is on leads the rail; the others follow in
        // saved order, so the top of the rail always names where you are.
        java.util.List<SavedRemoteHost> hosts = new java.util.ArrayList<>(savedHosts);
        for (int h = 0; h < hosts.size(); h++) {
            if (hosts.get(h).id.equals(currentHostId)) { hosts.add(0, hosts.remove(h)); break; }
        }
        for (int h = 0; h < hosts.size(); h++) {
            SavedRemoteHost host = hosts.get(h);
            boolean live = host.id.equals(currentHostId);
            HostSample sample = hostSamples.get(host.id);
            JSONArray workspaces = live && current != null ? current.workspaces
                    : sample == null ? null : sample.workspaces;
            JSONArray panes = live && current != null ? current.panes
                    : sample == null ? null : sample.panes;
            addHostRows(host, live, sample, workspaces, panes, current, order, keep);
            if (h + 1 < hosts.size()) order.add(railRule(host.id, keep));
        }
        pruneRailRows(keep);
        commitRailOrder(order);
    }

    private void addHostRows(SavedRemoteHost host, boolean live, HostSample sample, JSONArray workspaces,
            JSONArray panes, Connection current, java.util.List<View> order, java.util.Set<String> keep) {
        String hostKey = "host:" + host.id;
        keep.add(hostKey);
        RailHostRow header = railHostRows.get(hostKey);
        if (header == null) {
            header = createRailHostRow(host);
            railHostRows.put(hostKey, header);
        }
        updateRailHostRow(header, host, live, sample, workspaces);
        order.add(header.row);
        if (workspaces == null || panes == null) {
            String noteKey = "note:" + host.id;
            keep.add(noteKey);
            TextView note = railNotes.get(noteKey);
            if (note == null) {
                note = new TextView(this);
                note.setTextSize(11);
                note.setTextColor(chrome.onSurfaceVariant);
                note.setPadding(dp(10), dp(2), dp(6), dp(6));
                note.setTypeface(android.graphics.Typeface.create("sans-serif-italic",
                        android.graphics.Typeface.NORMAL));
                railNotes.put(noteKey, note);
            }
            String text = sample != null && !sample.connected ? "connecting…" : "no session";
            if (!text.contentEquals(note.getText())) note.setText(text);
            order.add(note);
            return;
        }
        for (int w = 0; w < workspaces.length(); w++) {
            JSONObject workspace = workspaces.optJSONObject(w);
            if (workspace == null) continue;
            String workspaceId = workspace.optString("id", "");
            String workspaceLabel = chromeTitle(workspace.optString("label", ""), workspaceId);
            String workspaceKey = "ws:" + host.id + "/" + workspaceId;
            keep.add(workspaceKey);
            RailWorkspaceRow workspaceRow = railWorkspaces.get(workspaceKey);
            if (workspaceRow == null) {
                workspaceRow = createRailWorkspaceRow();
                railWorkspaces.put(workspaceKey, workspaceRow);
            }
            // Orientation on a long rail: mark the workspace that holds the
            // focused pane, not just the pane itself.
            boolean holdsSelection = false;
            if (live && current != null) {
                for (int q = 0; q < panes.length(); q++) {
                    JSONObject pane = panes.optJSONObject(q);
                    if (pane == null || !workspaceId.equals(pane.optString("workspace_id", ""))) continue;
                    if (current.paneId.equals(pane.optString("id", ""))) { holdsSelection = true; break; }
                }
            }
            updateRailWorkspaceRow(workspaceRow, workspaceLabel, live, current, workspaceId,
                    holdsSelection);
            order.add(workspaceRow.view);
            for (int p = 0; p < panes.length(); p++) {
                JSONObject pane = panes.optJSONObject(p);
                if (pane == null || !workspaceId.equals(pane.optString("workspace_id", ""))) continue;
                String paneId = pane.optString("id", "");
                String title = chromeTitle(pane.optString("title", ""), paneId);
                boolean selected = live && current != null && paneId.equals(current.paneId);
                String paneKey = "pane:" + host.id + "/" + paneId;
                keep.add(paneKey);
                RailPaneRow row = railPanes.get(paneKey);
                if (row == null) {
                    row = createRailPaneRow();
                    railPanes.put(paneKey, row);
                }
                updateRailPaneRow(row, host, live, current, paneId, title,
                        pane.optString("status", "unknown"), selected);
                order.add(row.row);
            }
        }
    }

    /** Moves reused rows into their new positions without touching rows that already sit there. */
    private void commitRailOrder(java.util.List<View> order) {
        for (int i = 0; i < order.size(); i++) {
            View view = order.get(i);
            if (view.getParent() != sidebarRows) {
                sidebarRows.addView(view, Math.min(i, sidebarRows.getChildCount()));
            } else if (sidebarRows.indexOfChild(view) != i) {
                sidebarRows.removeView(view);
                sidebarRows.addView(view, i);
            }
        }
        while (sidebarRows.getChildCount() > order.size()) {
            sidebarRows.removeViewAt(order.size());
        }
    }

    private void pruneRailRows(java.util.Set<String> keep) {
        prune(railHostRows, keep, holder -> holder.row);
        prune(railWorkspaces, keep, holder -> holder.view);
        prune(railPanes, keep, holder -> holder.row);
        prune(railNotes, keep, view -> view);
        prune(railRules, keep, view -> view);
    }

    private <T> void prune(java.util.Map<String, T> rows, java.util.Set<String> keep, java.util.function.Function<T, View> view) {
        java.util.Iterator<java.util.Map.Entry<String, T>> iterator = rows.entrySet().iterator();
        while (iterator.hasNext()) {
            java.util.Map.Entry<String, T> entry = iterator.next();
            if (keep.contains(entry.getKey())) continue;
            View stale = view.apply(entry.getValue());
            if (stale.getParent() == sidebarRows) sidebarRows.removeView(stale);
            iterator.remove();
        }
    }

    private RailHostRow createRailHostRow(SavedRemoteHost host) {
        RailHostRow holder = new RailHostRow();
        holder.row = new LinearLayout(this);
        holder.row.setOrientation(LinearLayout.HORIZONTAL);
        holder.row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        // Dense rail: Wolf scans many agents at once, so rows trade the 48 dp
        // touch guideline for more of them on screen.
        holder.row.setMinimumHeight(dp(36));
        holder.row.setPadding(dp(6), dp(4), dp(6), dp(4));
        holder.row.setSaveFromParentEnabled(false);
        holder.dot = new View(this);
        holder.dot.setBackground(roundedDrawable(chrome.onSurfaceVariant, 4));
        LinearLayout.LayoutParams dotParams = new LinearLayout.LayoutParams(dp(8), dp(8));
        dotParams.setMarginEnd(dp(8));
        holder.row.addView(holder.dot, dotParams);
        holder.name = railText(host.name, 13, chrome.onSurface);
        holder.name.setTypeface(android.graphics.Typeface.create("sans-serif-medium",
                android.graphics.Typeface.NORMAL));
        LinearLayout.LayoutParams nameParams = new LinearLayout.LayoutParams(0, -2, 1);
        nameParams.leftMargin = dp(1);
        holder.row.addView(holder.name, nameParams);
        holder.state = railText("", 10, chrome.onSurfaceVariant);
        holder.state.setSingleLine(true);
        holder.state.setGravity(android.view.Gravity.END);
        LinearLayout.LayoutParams hostStateParams = new LinearLayout.LayoutParams(-2, -2);
        hostStateParams.leftMargin = dp(6);
        holder.row.addView(holder.state, hostStateParams);
        holder.host = host;
        holder.row.setContentDescription("Connect to " + host.name);
        holder.row.setOnClickListener(view -> {
            switchHost(holder.host);
            closeRailDrawer();
        });
        return holder;
    }

    private void updateRailHostRow(RailHostRow holder, SavedRemoteHost host, boolean live,
            HostSample sample, JSONArray workspaces) {
        holder.host = host;
        // Connection state is the thing a glance should answer, so it gets its
        // own dot shape: solid when connected, ring while dialling, hollow when down.
        String status = live || (sample != null && sample.connected) ? "connected"
                : sample != null ? "connecting" : "offline";
        if (!status.equals(holder.status)) {
            boolean pulse = holder.status != null;
            holder.status = status;
            // Colour alone cannot say it, so the dot is paired with its word.
            String word = "connected".equals(status) ? "live"
                    : "connecting".equals(status) ? "dialling" : "";
            holder.state.setText(word);
            holder.hostState = word;
            holder.row.setContentDescription(hostDescription(holder));
            holder.dot.setBackground("connected".equals(status)
                    ? roundedDrawable(chrome.success, 4)
                    : ringDrawable("connecting".equals(status) ? chrome.working : chrome.onSurfaceVariant));
            if (pulse) pulseRailDot(holder.dot);
        }
        if (holder.live != live) {
            holder.live = live;
            // The connected host reads as the current destination, like a
            // selected navigation item, and says so to assistive technology.
            holder.row.setBackground(live ? roundedDrawable(chrome.selected, 12) : null);
            holder.row.setSelected(live);
            // Secondary text is only guaranteed on the bare surface.
            holder.state.setTextColor(live ? chrome.onSurface : chrome.onSurfaceVariant);
        }
        if (!host.name.contentEquals(holder.name.getText())) holder.name.setText(host.name);
        int count = workspaces == null ? 0 : workspaces.length();
        if (count != holder.counted) {
            // Workspaces are enumerated below; keep the count for screen readers.
            holder.counted = count;
            holder.row.setContentDescription(hostDescription(holder));
        }
    }

    private RailWorkspaceRow createRailWorkspaceRow() {
        RailWorkspaceRow holder = new RailWorkspaceRow();
        // The desktop lists workspaces in sentence case with the pane title below,
        // not as shouted section headings; match that reading order.
        TextView row = railText("", 12, chrome.onSurfaceVariant);
        row.setTypeface(android.graphics.Typeface.create("sans-serif-medium",
                android.graphics.Typeface.NORMAL));
        row.setMinimumHeight(dp(26));
        row.setPadding(dp(10), dp(7), dp(6), dp(2));
        row.setOnClickListener(view -> {
            if (!holder.live) return;
            focusChrome(holder.connection, "workspace", holder.workspaceId);
            closeRailDrawer();
        });
        holder.view = row;
        return holder;
    }

    private void updateRailWorkspaceRow(RailWorkspaceRow holder, String label, boolean live,
            Connection connection, String workspaceId, boolean holdsSelection) {
        holder.live = live;
        holder.connection = connection;
        holder.workspaceId = workspaceId;
        TextView row = holder.view;
        if (!label.contentEquals(row.getText())) {
            row.setText(label);
            row.setContentDescription("Focus workspace " + label);
        }
        int color = holdsSelection ? chrome.primary : chrome.onSurfaceVariant;
        if (row.getCurrentTextColor() != color) row.setTextColor(color);
    }

    private RailPaneRow createRailPaneRow() {
        RailPaneRow holder = new RailPaneRow();
        holder.row = new LinearLayout(this);
        holder.row.setOrientation(LinearLayout.HORIZONTAL);
        holder.row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        holder.row.setMinimumHeight(dp(34));
        holder.row.setPadding(dp(10), dp(3), dp(6), dp(3));
        holder.row.setSaveFromParentEnabled(false);
        holder.dot = new View(this);
        holder.dot.setBackground(roundedDrawable(chrome.onSurfaceVariant, 4));
        LinearLayout.LayoutParams dotParams = new LinearLayout.LayoutParams(dp(8), dp(8));
        dotParams.setMarginEnd(dp(8));
        holder.row.addView(holder.dot, dotParams);
        holder.title = railText("", 12, chrome.onSurface);
        holder.title.setSingleLine(false);
        holder.title.setMaxLines(2);
        holder.title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        holder.title.setLineSpacing(0, 1.05f);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, -2, 1);
        params.leftMargin = dp(1);
        holder.row.addView(holder.title, params);
        // A colour alone cannot say what an agent is doing; name the state.
        holder.state = railText("", 10, chrome.onSurfaceVariant);
        holder.state.setSingleLine(true);
        holder.state.setEllipsize(android.text.TextUtils.TruncateAt.END);
        holder.state.setGravity(android.view.Gravity.END);
        LinearLayout.LayoutParams stateParams = new LinearLayout.LayoutParams(-2, -2);
        stateParams.leftMargin = dp(6);
        holder.row.addView(holder.state, stateParams);
        holder.row.setOnClickListener(view -> {
            if (holder.live) {
                focusRemotePane(holder.connection, holder.paneId);
            } else {
                switchHost(holder.host, holder.paneId);
            }
            closeRailDrawer();
        });
        return holder;
    }

    private void updateRailPaneRow(RailPaneRow holder, SavedRemoteHost host, boolean live,
            Connection connection, String paneId, String title, String status, boolean selected) {
        holder.host = host;
        holder.live = live;
        holder.connection = connection;
        holder.paneId = paneId;
        if (!status.equals(holder.status)) {
            boolean pulse = holder.status != null;
            holder.status = status;
            holder.dot.setBackground(roundedDrawable(chrome.status(status), 4));
            if (pulse) {
                pulseRailDot(holder.dot);
                            }
        }
        if (holder.selected != selected) {
            holder.selected = selected;
            holder.row.setBackground(selected ? roundedDrawable(chrome.selected, 12) : null);
            holder.row.setSelected(selected);
            holder.state.setTextColor(selected ? chrome.onSurface : chrome.onSurfaceVariant);
        }
        if (!title.contentEquals(holder.title.getText())) {
            holder.title.setText(title);
            holder.row.setContentDescription("Focus pane " + title);
        }
        String state = status == null || status.isEmpty() || "unknown".equals(status)
                ? "" : status;
        if (!state.contentEquals(holder.state.getText())) holder.state.setText(state);
        // A row description overrides its children, so the state has to be said here.
        String described = state.isEmpty() ? "Focus pane " + title
                : "Focus pane " + title + ", " + state;
        if (!described.contentEquals(holder.row.getContentDescription() == null
                ? "" : holder.row.getContentDescription())) {
            holder.row.setContentDescription(described);
        }
    }

    private View railRule(String hostId, java.util.Set<String> keep) {
        String key = "rule:" + hostId;
        keep.add(key);
        View rule = railRules.get(key);
        if (rule != null) return rule;
        rule = new View(this);
        rule.setBackgroundColor(chrome.outline);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, dp(1));
        params.topMargin = dp(12);
        params.bottomMargin = dp(6);
        params.leftMargin = dp(6);
        params.rightMargin = dp(6);
        rule.setLayoutParams(params);
        railRules.put(key, rule);
        return rule;
    }

    private static final class RailHostRow {
        LinearLayout row;
        View dot;
        TextView name;
        TextView state;
        String hostState = "";
        SavedRemoteHost host;
        String status;
        boolean live;
        int counted = -1;
    }

    private static final class RailWorkspaceRow {
        TextView view;
        Connection connection;
        String workspaceId = "";
        boolean live;
    }

    private static final class RailPaneRow {
        LinearLayout row;
        View dot;
        TextView title;
        TextView state;
        SavedRemoteHost host;
        Connection connection;
        String paneId = "";
        boolean live;
        boolean selected;
        String status;
    }

    private android.graphics.drawable.GradientDrawable ringDrawable(int color) {
        android.graphics.drawable.GradientDrawable ring = new android.graphics.drawable.GradientDrawable();
        ring.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        ring.setColor(0x00000000);
        ring.setStroke(dp(2), color);
        return ring;
    }

    /** A single scale pulse: enough to catch the eye, short enough to stay quiet. */
    private void pulseRailDot(View dot) {
        dot.animate().cancel();
        dot.setScaleX(1f);
        dot.setScaleY(1f);
        dot.animate().scaleX(1.8f).scaleY(1.8f).setDuration(140)
                .withEndAction(() -> dot.animate().scaleX(1f).scaleY(1f).setDuration(180).start())
                .start();
    }

    private TextView railText(String text, int size, int color) {
        TextView label = new TextView(this);
        label.setText(text);
        label.setTextSize(size);
        label.setTextColor(color);
        label.setSingleLine(true);
        label.setEllipsize(android.text.TextUtils.TruncateAt.END);
        label.setSaveEnabled(false);
        return label;
    }

    private android.graphics.drawable.GradientDrawable roundedDrawable(int color, int radiusDp) {
        android.graphics.drawable.GradientDrawable shape = new android.graphics.drawable.GradientDrawable();
        shape.setColor(color);
        shape.setCornerRadius(dp(radiusDp));
        return shape;
    }

    private void showPanes() {
        if (destroyed || isFinishing() || displayDialog != null && displayDialog.isShowing()) return;
        final Connection target;
        synchronized (stateLock) { target = connection; }
        if (target == null || target.isClosed()) return;
        clearControl();
        allowNextKeyboardShow = false;
        super.gpuiHideKeyboard(imeSession);
        AlertDialog.Builder builder = sheetBuilder();
        android.content.Context context = builder.getContext();
        int primary = themeColor(context, android.R.attr.textColorPrimary);
        int secondary = themeColor(context, android.R.attr.textColorSecondary);
        LinearLayout list = new LinearLayout(context);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(dp(16), dp(4), dp(16), dp(12));
        JSONArray workspaces = target.workspaces, tabs = target.tabs, panes = target.panes;
        int rows = 0;
        if (workspaces != null && panes != null) {
            for (int w = 0; w < workspaces.length(); w++) {
                JSONObject workspace = workspaces.optJSONObject(w);
                if (workspace == null) continue;
                String workspaceId = workspace.optString("id", "");
                TextView header = chromeLabel(context, chromeTitle(workspace.optString("label", ""), workspaceId),
                        14, secondary, true);
                header.setMinimumHeight(dp(48));
                header.setTypeface(android.graphics.Typeface.create("sans-serif-medium",
                        android.graphics.Typeface.NORMAL));
                header.setContentDescription("Focus workspace " + chromeTitle(workspace.optString("label", ""), workspaceId));
                header.setOnClickListener(view -> {
                    if (displayDialog != null) displayDialog.dismiss();
                    focusChrome(target, "workspace", workspaceId);
                });
                list.addView(header);
                int workspaceTabs = 0;
                if (tabs != null) for (int t = 0; t < tabs.length(); t++) {
                    JSONObject tab = tabs.optJSONObject(t);
                    if (tab != null && workspaceId.equals(tab.optString("workspace_id", ""))) workspaceTabs++;
                }
                // A lone tab is the workspace itself: listing it only adds a level.
                boolean tabRows = workspaceTabs > 1;
                if (tabRows) for (int t = 0; t < tabs.length(); t++) {
                    JSONObject tab = tabs.optJSONObject(t);
                    if (tab == null || !workspaceId.equals(tab.optString("workspace_id", ""))) continue;
                    boolean focused = tab.optBoolean("focused");
                    String tabTitle = chromeTitle(tab.optString("label", ""), tab.optString("id", ""));
                    // A bare number reads as a stray count; name what it counts.
                    if (tabTitle.matches("[0-9]+")) tabTitle = "Tab " + tabTitle;
                    LinearLayout row = new LinearLayout(context);
                    row.setOrientation(LinearLayout.HORIZONTAL);
                    row.setGravity(android.view.Gravity.CENTER_VERTICAL);
                    row.addView(chromeDot(context, tab.optString("status", "unknown")));
                    row.addView(chromeLabel(context, tabTitle, 13, focused ? primary : secondary, false));
                    // 48 dp is Android's minimum touch target; 40 was below it.
                    row.setMinimumHeight(dp(48));
                    row.setPadding(dp(16), 0, dp(8), 0);
                    row.setSelected(focused);
                    row.setContentDescription("Focus tab " + chromeTitle(tab.optString("label", ""), tab.optString("id", "")));
                    String tabId = tab.optString("id", "");
                    row.setOnClickListener(view -> {
                        if (displayDialog != null) displayDialog.dismiss();
                        focusChrome(target, "tab", tabId);
                    });
                    list.addView(row);
                }
                for (int p = 0; p < panes.length(); p++) {
                    JSONObject pane = panes.optJSONObject(p);
                    if (pane == null || !workspaceId.equals(pane.optString("workspace_id", ""))) continue;
                    rows++;
                    list.addView(paneRow(context, target, pane, primary, tabRows ? dp(28) : dp(16)));
                }
            }
        }
        if (rows == 0) list.addView(chromeLabel(context, "No panes reported by this session yet", 14, secondary, false));
        ScrollView scroll = new ScrollView(context);
        scroll.addView(list);
        AlertDialog sheet = builder.setTitle("Panes")
                .setView(scroll)
                .setNegativeButton("Close", null)
                .create();
        showLocalDialog(sheet);
    }

    /** {@code indent} nests the row under its tab, or directly under its workspace. */
    private View paneRow(android.content.Context context, Connection target, JSONObject pane, int textColor,
            int indent) {
        String id = pane.optString("id", "");
        boolean current = id.equals(target.paneId);
        String title = chromeTitle(pane.optString("title", ""), id);
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setMinimumHeight(dp(48));
        row.setPadding(indent, 0, dp(12), 0);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        row.setFocusable(true);
        row.setSaveEnabled(false);
        row.addView(chromeDot(context, pane.optString("status", "unknown")));
        TextView label = chromeLabel(context, title, 15, textColor, false);
        row.addView(label, new LinearLayout.LayoutParams(0, -2, 1));
        if (current) {
            // The pane on screen reads as the selected item, not just a tint.
            row.setBackground(roundedDrawable(GpuiChrome.withAlpha(
                    themeColor(context, android.R.attr.colorAccent), 0x29), 12));
            label.setTypeface(android.graphics.Typeface.create("sans-serif-medium",
                    android.graphics.Typeface.NORMAL));
            row.setSelected(true);
        }
        row.setContentDescription((current ? "Current pane " : "Focus pane ") + title);
        row.setOnClickListener(view -> {
            if (displayDialog != null) displayDialog.dismiss();
            focusRemotePane(target, id);
        });
        return row;
    }

    private void focusRemotePane(Connection target, String paneId) {
        if (paneId.isEmpty() || paneId.length() > 128
                || !paneId.matches("[A-Za-z0-9_./:-]+")) return;
        if (target.remoteHandle <= 0) {
            Toast.makeText(this, "Switch panes from Remote hosts for this connection", Toast.LENGTH_LONG).show();
            return;
        }
        try {
            JSONObject result = new JSONObject(RemoteNative.focus(target.remoteHandle, paneId));
            if (!result.optBoolean("queued", false)) throw new IllegalStateException("Pane focus was not queued");
            rememberHostPane(paneId);
            synchronized (stateLock) {
                if (!current(target)) return;
                target.paneId = paneId;
                publish(target, "Switching pane…");
            }
        } catch (Exception | LinkageError failure) {
            Toast.makeText(this, "Could not switch pane; reopen and check the session", Toast.LENGTH_LONG).show();
        }
    }

    /** Tabs and workspaces use the daemon's own switch requests. */
    private void focusChrome(Connection target, String kind, String id) {
        if (id.isEmpty() || id.length() > 128
                || !id.matches("[A-Za-z0-9_./:-]+")) return;
        if (target.remoteHandle <= 0) {
            Toast.makeText(this, "Switch workspaces from Remote hosts for this connection", Toast.LENGTH_LONG).show();
            return;
        }
        try {
            JSONObject result = new JSONObject("tab".equals(kind)
                    ? RemoteNative.focusTab(target.remoteHandle, id)
                    : RemoteNative.focusWorkspace(target.remoteHandle, id));
            if (!result.optBoolean("queued", false)) throw new IllegalStateException("Switch was not queued");
            synchronized (stateLock) {
                if (current(target)) publish(target, "Switching " + kind + "…");
            }
        } catch (Exception | LinkageError failure) {
            Toast.makeText(this, "Could not switch; reopen and check the session", Toast.LENGTH_LONG).show();
        }
    }

    private static String chromeTitle(String value, String fallback) {
        String title = CHROME_CONTROLS.matcher(value).replaceAll(" ").trim();
        if (title.isEmpty()) title = fallback;
        return title.length() > 60 ? title.substring(0, 60) + "…" : title;
    }

    private View chromeDot(android.content.Context context, String status) {
        View dot = new View(context);
        dot.setBackground(roundedDrawable(chrome.status(status), 4));
        LinearLayout.LayoutParams layout = new LinearLayout.LayoutParams(dp(8), dp(8));
        layout.setMarginEnd(dp(12));
        dot.setLayoutParams(layout);
        return dot;
    }

    private TextView chromeLabel(android.content.Context context, String text, int size, int color, boolean header) {
        TextView label = new TextView(context);
        label.setText(text);
        label.setTextSize(size);
        label.setTextColor(color);
        label.setSingleLine(true);
        label.setEllipsize(android.text.TextUtils.TruncateAt.END);
        label.setSaveEnabled(false);
        if (header) label.setPadding(0, dp(14), 0, dp(4));
        return label;
    }

    private void sendShortcut(Shortcut shortcut) {
        if (displayDialog != null && displayDialog.isShowing()) return;
        clearControl();
        synchronized (stateLock) {
            Connection target = connection;
            if (target == null || target.isClosed()) return;
            if (!target.ready) {
                publish(target, "Wait for the terminal connection before typing");
                return;
            }
            if (compositionBlocksInput(target)) return;
            try {
                if (!nativeSendCommand(target.generation, GpuiTerminalProtocol.shortcutCommand(shortcut))) {
                    fail(target, "Shortcut was not queued; reopen and check the terminal before retrying");
                }
            } catch (JSONException | IllegalArgumentException invalid) {
                fail(target, "Invalid shortcut; check the host's shortcut settings");
            }
        }
    }

    @Override public void surfaceCreated(SurfaceHolder holder) {
        if (hostStarted) nativeSurfaceCreated(holder.getSurface(), getResources().getDisplayMetrics().density);
    }

    @Override public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        if (hostStarted) nativeSurfaceCreated(holder.getSurface(), getResources().getDisplayMetrics().density);
    }

    @Override public void surfaceDestroyed(SurfaceHolder holder) {
        // The supported host API acknowledges surface release before Android disposes it.
        if (hostStarted) nativeSurfaceDestroyed();
    }

    private boolean forwardTouch(MotionEvent event) {
        int count = event.getPointerCount();
        if (!hostStarted || count < 1 || count > 16) return false;
        int masked = event.getActionMasked();
        pinchDetector().onTouchEvent(event);
        if (pinching || count > 1 && masked != MotionEvent.ACTION_DOWN) {
            // A pinch is ours: the program never sees half of a two-finger gesture.
            scrolling = false;
            tapCandidate = false;
            return true;
        }
        if (masked == MotionEvent.ACTION_DOWN) {
            tapCandidate = true;
            touchDownAt = SystemClock.uptimeMillis();
            stopFling();
            touchDownX = edgeStartX = event.getX();
            edgeClaimed = false;
            touchDownY = lastTouchY = event.getY();
            lastMoveAt = SystemClock.uptimeMillis();
            scrollVelocity = 0f;
            scrolling = false;
            scrollFraction = 0f;
        } else if (masked == MotionEvent.ACTION_MOVE && count == 1 && !shellMode()) {
            // A shell has no rail, and GPUI turns its pans into Rio's own scrollback.
            // The system owns the screen edges for Back, so the open gesture starts
            // inside the left third rather than at the very edge.
            int width = terminalSurface == null ? 0 : terminalSurface.getWidth();
            if (!edgeClaimed && !sidebarOpen && width > 0 && touchDownX < width / 2f) {
                float dx = event.getX() - edgeStartX;
                float dy = Math.abs(event.getY() - touchDownY);
                if (dx > dp(60) && dx > dy * 1.5f) {
                    edgeClaimed = true;
                    showSidebar();
                    return true;
                }
            }
            if (edgeClaimed) return true;
            if (!scrolling && Math.abs(event.getY() - touchDownY) > dp(8)) scrolling = true;
            if (scrolling) {
                long now = SystemClock.uptimeMillis();
                float dy = event.getY() - lastTouchY;
                float dt = Math.max(1, now - lastMoveAt);
                // Smoothed drag velocity in pixels per millisecond.
                scrollVelocity = 0.6f * scrollVelocity + 0.4f * (dy / dt);
                lastMoveAt = now;
                float rowPx = rowHeightPx();
                scrollFraction += dy / rowPx;
                lastTouchY = event.getY();
                int lines = (int) scrollFraction;
                if (lines != 0) {
                    scrollFraction -= lines;
                    requestScroll(lines);
                }
                return true;
            }
        } else if (masked == MotionEvent.ACTION_UP || masked == MotionEvent.ACTION_CANCEL) {
            // A plain tap on the terminal opens the keyboard: typing into an agent
            // should not need the toolbar. The tap still reaches the program.
            if (masked == MotionEvent.ACTION_UP && tapCandidate && !scrolling && !edgeClaimed
                    && SystemClock.uptimeMillis() - touchDownAt < 350
                    && Math.abs(event.getX() - touchDownX) < dp(10)
                    && Math.abs(event.getY() - touchDownY) < dp(10)) {
                Connection target;
                synchronized (stateLock) { target = connection; }
                // After the tap itself reaches the renderer: its focus handling
                // would otherwise hide the keyboard this just asked for.
                if (target != null && target.ready && terminalSurface != null) {
                    final long generation = target.generation;
                    terminalSurface.postDelayed(() -> {
                        if (!destroyed) nativeShowKeyboard(generation);
                    }, 150);
                }
            }
            tapCandidate = false;
            if (edgeClaimed) {
                edgeClaimed = false;
                return true;
            }
            if (scrolling) {
                scrolling = false;
                flushScroll();
                if (masked == MotionEvent.ACTION_UP && Math.abs(scrollVelocity) > 0.12f) {
                    flinging = true;
                    lastMoveAt = SystemClock.uptimeMillis();
                    flingRemainder = 0f;
                    if (terminalSurface != null) terminalSurface.postOnAnimation(flingStep);
                }
                return true;
            }
            stopFling();
        }
        int[] ids = new int[count];
        float[] xs = new float[count], ys = new float[count];
        for (int i = 0; i < count; i++) {
            ids[i] = event.getPointerId(i);
            xs[i] = event.getX(i);
            ys[i] = event.getY(i);
        }
        return nativeTouch(event.getActionMasked(), event.getActionIndex(), ids, xs, ys);
    }

    @Override protected void onResume() {
        super.onResume();
        if (isFinishing() || launch == null || shortcutJson == null || !hostStarted) return;
        hostResumed = true;
        nativeResumed();
        updateHostSampling();
        if (launch.shell) {
            synchronized (stateLock) {
                if (connection != null) return;
            }
            startShell();
            return;
        }
        synchronized (stateLock) {
            if (connection != null) return;
            Connection next;
            if (launch.remoteHost != null) {
                long remoteHandle = launch.takeRemoteHandle();
                if (remoteHandle <= 0) {
                    Toast.makeText(this, "Remote session was paused. Reconnect from Remote hosts", Toast.LENGTH_LONG).show();
                    finish();
                    return;
                }
                next = new Connection(GENERATIONS.incrementAndGet(), remoteHandle);
            } else next = new Connection(GENERATIONS.incrementAndGet(), new CompanionClient(launch.host.profile));
            connection = next;
            nativeSetActive(next.generation, false);
            publish(next, "Connecting…");
            next.poll = worker.scheduleWithFixedDelay(() -> pump(next), 0, COMMAND_POLL_MS, TimeUnit.MILLISECONDS);
        }
    }

    @Override protected void onPause() {
        hostResumed = false;
        // Mirrors are only useful on screen; three SSH sessions polling in the
        // background would spend battery for a rail nobody can see.
        stopHostSampling();
        allowNextKeyboardShow = false;
        oneShotKey = KeyEvent.KEYCODE_UNKNOWN;
        clearControl();
        if (displayDialog != null) displayDialog.dismiss();
        capturedKeys.clear();
        stopConnection();
        if (hostStarted) nativePaused();
        super.onPause();
    }

    @Override protected void onStart() {
        super.onStart();
        // Postures arrive while the activity is visible, as Jetpack delivers them.
        if (foldWatcher == null) {
            foldWatcher = FoldPosture.Watcher.start(this, update -> main.post(() -> {
                if (update.equals(posture)) return;
                posture = update;
                applyPosture();
            }));
        }
    }

    @Override protected void onStop() {
        if (foldWatcher != null) {
            foldWatcher.stop();
            foldWatcher = null;
        }
        stopConnection();
        super.onStop();
    }

    @Override protected void onDestroy() {
        destroyed = true;
        stopHostSampling();
        stopConnection();
        worker.shutdownNow();
        GpuiLaunch.release(launch);
        launch = null;
        shortcutJson = null;
        shortcuts = Collections.emptyList();
        lastSnapshotTitle = lastSnapshotText = lastSnapshotStatus = lastSnapshotShortcuts = null;
        terminalRoot = null;
        super.onDestroy();
    }

    /** Desktop-style session control for the focused tab and pane. */
    private void showSessionActions() {
        final Connection target;
        synchronized (stateLock) { target = connection; }
        if (target == null || target.isClosed()) return;
        if (!target.shell && target.remoteHandle <= 0) {
            Toast.makeText(this, "Session controls need the SSH workflow", Toast.LENGTH_LONG).show();
            return;
        }
        // A raw shell has no Herdr panes or tabs to change.
        String[] labels = target.shell
                ? new String[]{"Copy screen text", "Remote hosts…", "Save screenshot"}
                : new String[]{"New Claude", "New Codex", "New terminal tab", "Close terminal", "Jump to live output",
                        "Copy terminal text", "Remote hosts…", "Save screenshot"};
        String[] actions = target.shell
                ? new String[]{"copy.text", "hosts.list", "capture.screen"}
                : new String[]{"agent.claude", "agent.codex", "tab.new", "pane.close", "scroll.live", "copy.text",
                        "hosts.list", "capture.screen"};
        if (!debuggable()) {
            // The screenshot is a debugging aid listed last; a release build drops it.
            labels = java.util.Arrays.copyOf(labels, labels.length - 1);
            actions = java.util.Arrays.copyOf(actions, actions.length - 1);
        }
        final String[] shownLabels = labels;
        final String[] shownActions = actions;
        AlertDialog.Builder builder = sheetBuilder();
        android.content.Context context = builder.getContext();
        int text = themeColor(context, android.R.attr.textColorPrimary);
        int destructiveText = themeColor(context, android.R.attr.colorError);
        int divider = GpuiChrome.withAlpha(themeColor(context, android.R.attr.textColorSecondary), 0x40);
        LinearLayout list = new LinearLayout(context);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(0, dp(4), 0, 0);
        AlertDialog sheet = builder.setTitle("Session")
                .setView(list)
                .setNegativeButton("Cancel", null)
                .create();
        for (int i = 0; i < shownLabels.length; i++) {
            // Group create/layout from view/utilities, and mark the closes as
            // destructive instead of styling them like "New tab".
            if (!target.shell && (i == 4 || i == 6 || i == 8)) list.addView(sheetDivider(context, divider));
            boolean destructive = shownActions[i].endsWith(".close");
            TextView row = new TextView(context);
            row.setText(shownLabels[i]);
            row.setTextSize(16);
            row.setTextColor(destructive ? destructiveText : text);
            row.setGravity(android.view.Gravity.CENTER_VERTICAL);
            row.setMinimumHeight(dp(48));
            row.setPadding(dp(24), 0, dp(24), 0);
            row.setBackground(controlBackground(false, 0x00000000, 0, 0));
            row.setSaveEnabled(false);
            final int index = i;
            row.setOnClickListener(view -> {
                sheet.dismiss();
                runSessionChoice(target, shownLabels[index], shownActions[index]);
            });
            list.addView(row);
        }
        showLocalDialog(sheet);
    }

    private View sheetDivider(android.content.Context context, int color) {
        View line = new View(context);
        line.setBackgroundColor(color);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, dp(1));
        params.setMargins(dp(24), dp(4), dp(24), dp(4));
        line.setLayoutParams(params);
        return line;
    }

    private void runSessionChoice(Connection target, String label, String action) {
        if ("scroll.live".equals(action)) {
            jumpToLive();
            return;
        }
        if ("copy.text".equals(action)) {
            copyPaneText();
            return;
        }
        if ("capture.screen".equals(action) && debuggable()) {
            captureScreen();
            return;
        }
        if ("hosts.list".equals(action)) {
            startActivity(new android.content.Intent(this, RemoteHostsActivity.class)
                    .putExtra("skip_reopen", true));
            return;
        }
        if ("pane.close".equals(action) || "tab.close".equals(action)) {
            confirmSessionAction(target, action, label);
        } else if ("pane.zoom.on".equals(action)) {
            // One control toggles: the daemon reports the current zoom state.
            runSessionAction(target, zoomed(target) ? "pane.zoom.off" : "pane.zoom.on", "Zoom");
        } else {
            runSessionAction(target, action, label);
        }
    }

    private boolean zoomed(Connection target) {
        JSONArray tabs = target.tabs;
        if (tabs == null) return false;
        for (int i = 0; i < tabs.length(); i++) {
            JSONObject tab = tabs.optJSONObject(i);
            if (tab != null && tab.optBoolean("focused") && tab.optBoolean("zoomed")) return true;
        }
        return false;
    }

    private void confirmSessionAction(Connection target, String action, String label) {
        sheetBuilder()
                .setTitle(label + "?")
                .setMessage("pane.close".equals(action)
                        ? "Closing the pane terminates its processes. This cannot be undone."
                        : "Closing the tab terminates its panes and processes. This cannot be undone.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton(label, (dialog, which) -> runSessionAction(target, action, label))
                .show();
    }

    private void runSessionAction(Connection target, String action, String label) {
        try {
            JSONObject result = new JSONObject(RemoteNative.sessionAction(target.remoteHandle, action));
            if (!result.optBoolean("queued", false)) throw new IllegalStateException("not queued");
            synchronized (stateLock) {
                if (current(target)) publish(target, label + "…");
            }
        } catch (Exception | LinkageError failure) {
            Toast.makeText(this, "Could not apply " + label + "; reopen and check the session", Toast.LENGTH_LONG).show();
        }
    }

    /** A terminal bell: a short vibration, never a sound that overrides the ring mode. */
    private void ringBell(int count) {
        if (destroyed || isFinishing()) return;
        android.os.Vibrator vibrator = (android.os.Vibrator) getSystemService(VIBRATOR_SERVICE);
        if (vibrator == null || !vibrator.hasVibrator()) return;
        try {
            long millis = Math.min(600L, 40L * Math.max(1, count));
            vibrator.vibrate(android.os.VibrationEffect.createOneShot(millis,
                    android.os.VibrationEffect.DEFAULT_AMPLITUDE));
        } catch (RuntimeException ignored) {
            // A device policy may refuse vibration; the terminal stays usable.
        }
    }

    /** Agent alerts from the daemon, posted on one bounded channel. */
    private void postNotice(String title, String body, String paneId) {
        if (destroyed || isFinishing()) return;
        android.app.NotificationManager manager =
                (android.app.NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (manager == null) return;
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission("android.permission.POST_NOTIFICATIONS")
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            return;
        }
        android.app.NotificationChannel channel = new android.app.NotificationChannel(
                "herdr-agent-alerts", "Agent alerts", android.app.NotificationManager.IMPORTANCE_DEFAULT);
        channel.setDescription("A Herdr agent is waiting on you");
        manager.createNotificationChannel(channel);
        android.app.Notification notification = new android.app.Notification.Builder(this, "herdr-agent-alerts")
                .setSmallIcon(android.R.drawable.stat_notify_chat)
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(new android.app.Notification.BigTextStyle().bigText(body))
                .setAutoCancel(true)
                .build();
        try {
            manager.notify(paneId.isEmpty() ? title.hashCode() : paneId.hashCode(), notification);
        } catch (RuntimeException ignored) {
            // Posting can be refused while the user restricts notifications.
        }
    }

    /** OSC 52 text the daemon forwarded: plain text only, never a URI or bytes. */
    private void applyRemoteClipboard(Connection target, String text) {
        synchronized (stateLock) {
            if (!current(target)) return;
        }
        android.content.ClipboardManager manager =
                (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (manager == null) return;
        try {
            manager.setPrimaryClip(android.content.ClipData.newPlainText("Herdr", text));
            Toast.makeText(this, "Copied from terminal", Toast.LENGTH_SHORT).show();
        } catch (RuntimeException rejected) {
            // A locked device may refuse clipboard writes; the session stays usable.
        }
    }

    /** Opens a terminal link through the platform browser; never a file or custom scheme. */
    public void gpuiOpenLink(long expectedGeneration, String url) {
        runOnUiThread(() -> {
            synchronized (stateLock) {
                if (destroyed || isFinishing() || connection == null
                        || connection.generation != expectedGeneration) return;
            }
            if (url == null || url.length() > 2048) return;
            String lower = url.toLowerCase(java.util.Locale.ROOT);
            if (!lower.startsWith("http://") && !lower.startsWith("https://")) return;
            try {
                startActivity(new android.content.Intent(android.content.Intent.ACTION_VIEW,
                        android.net.Uri.parse(url)).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (RuntimeException noBrowser) {
                Toast.makeText(this, "No app can open this link", Toast.LENGTH_SHORT).show();
            }
        });
    }

    /** Called by GPUI directly, independent of the HTTP command queue and connection health. */
    public void gpuiClose(long expectedGeneration) {
        runOnUiThread(() -> {
            synchronized (stateLock) {
                if (connection != null && connection.generation == expectedGeneration) finish();
            }
        });
    }

    /** Only the explicit GPUI Keyboard control grants a show request. Focus alone does not. */
    public void gpuiRequestKeyboard(long expectedGeneration) {
        runOnUiThread(() -> {
            synchronized (stateLock) {
                if (destroyed || isFinishing() || connection == null || !connection.ready
                        || connection.isClosed() || connection.generation != expectedGeneration
                        || displayDialog != null && displayDialog.isShowing()) return;
                keyboardRequested = true;
                allowNextKeyboardShow = true;
            }
        });
    }

    @Override public void gpuiShowKeyboard(int keyboardType, long session) {
        runOnUiThread(() -> {
            if (!allowNextKeyboardShow || session < imeSession
                    || displayDialog != null && displayDialog.isShowing()) return;
            allowNextKeyboardShow = false;
            clearControl();
            setImeSession(session);
            synchronized (stateLock) {
                if (!destroyed && !isFinishing() && connection != null
                        && !connection.isClosed() && connection.ready) {
                    super.gpuiShowKeyboard(keyboardType, session);
                }
            }
        });
    }

    @Override public void gpuiHideKeyboard(long session) {
        runOnUiThread(() -> {
            if (session < imeSession) return;
            setImeSession(session);
            if (!destroyed) super.gpuiHideKeyboard(session);
        });
    }

    @Override public void gpuiResetComposition(long session) {
        runOnUiThread(() -> {
            if (session < imeSession) return;
            setImeSession(session);
            if (!destroyed) super.gpuiResetComposition(session);
        });
    }

    private void setImeSession(long session) {
        imeSession = session;
        imeComposing = false;
        synchronized (stateLock) {
            if (!destroyed && connection != null) nativeSetImeSession(connection.generation, session);
        }
    }

    @Override protected void gpuiIme(long session, int kind, String text, int start, int end) {
        clearControl();
        if (displayDialog != null && displayDialog.isShowing()) return;
        synchronized (stateLock) {
            Connection target = connection;
            if (destroyed || session != imeSession || target == null || target.isClosed() || !target.ready) return;
            if (kind == 0) imeComposing = !text.isEmpty();
            else if (kind == 1 || kind == 4) imeComposing = false;
            // Commit is synchronously queued before this callback returns. Never also forward
            // through upstream's deferred nativeIme, which could reorder or duplicate remote input.
            if (!nativeImeEvent(target.generation, session, kind, text, start, end)) {
                fail(target, "Input was not queued; reopen and check the terminal before retrying");
            }
        }
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        // Never persist host credentials, terminal text, IME drafts, or a reusable launch grant.
        // Also skip the framework view-state traversal of the invisible IME proxy.
    }

    /** True while a terminal key from the on-screen keyboard takes the physical path. */
    private boolean imeKeyInFlight;

    @Override protected boolean gpuiImeKey(KeyEvent event) {
        imeKeyInFlight = true;
        try {
            dispatchKeyEvent(event);
            return true;
        } finally {
            imeKeyInFlight = false;
        }
    }

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        if (displayDialog != null && displayDialog.isShowing()) return super.dispatchKeyEvent(event);
        boolean physical = imeKeyInFlight || event.isFromSource(InputDevice.SOURCE_KEYBOARD)
                && event.getDevice() != null && !event.getDevice().isVirtual()
                && (event.getFlags() & KeyEvent.FLAG_SOFT_KEYBOARD) == 0;
        int code = event.getKeyCode();
        if (!physical || code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_VOLUME_UP
                || code == KeyEvent.KEYCODE_VOLUME_DOWN || event.isSystem()) {
            return super.dispatchKeyEvent(event);
        }
        if (event.getAction() == KeyEvent.ACTION_UP) {
            capturedKeys.remove(code);
            if (oneShotKey == code) oneShotKey = KeyEvent.KEYCODE_UNKNOWN;
            return true;
        }
        // A one-shot Ctrl press must not turn into plain text when the same key repeats.
        if (event.getAction() == KeyEvent.ACTION_DOWN && oneShotKey == code) {
            if (event.getRepeatCount() > 0) return true;
            oneShotKey = KeyEvent.KEYCODE_UNKNOWN;
        }
        if (KeyEvent.isModifierKey(code) || code == KeyEvent.KEYCODE_CAPS_LOCK
                || code == KeyEvent.KEYCODE_NUM_LOCK || code == KeyEvent.KEYCODE_SCROLL_LOCK
                || event.getAction() != KeyEvent.ACTION_DOWN) return true;
        capturedKeys.add(code);
        synchronized (stateLock) {
            Connection target = connection;
            if (target == null || target.isClosed()) { clearControl(); return true; }
            if (!target.ready) {
                publish(target, "Wait for the terminal connection before typing");
                return true;
            }
            if (compositionBlocksInput(target)) { clearControl(); return true; }
            try {
                if (event.isMetaPressed() || event.isSymPressed() || event.isFunctionPressed()) {
                    clearControl();
                    throw new IllegalArgumentException("Unsupported modifier");
                }
                String named = physicalKeyName(code);
                boolean oneShot = controlLatched;
                clearControl();
                if (oneShot) oneShotKey = code;
                boolean control = event.isCtrlPressed() || oneShot;
                boolean terminalAlt = event.isAltPressed() && !displayOptions.altUsesSymbols();
                // OEM Alt symbols come from this event's KeyCharacterMap. Never guess digits,
                // scancodes, Fn meanings, or modify the user's system keyboard settings.
                if (event.isAltPressed() && (control || named != null))
                    throw new IllegalArgumentException("Unsupported modifiers");
                boolean accepted;
                if (named != null || control || terminalAlt) {
                    String key = named == null ? String.valueOf((char) event.getUnicodeChar(0)) : named;
                    String canonical = KeyChord.fromPhysical(key, control, terminalAlt, event.isShiftPressed());
                    accepted = nativeSendKey(target.generation, canonical);
                } else {
                    int unicode = event.getUnicodeChar(event.getMetaState());
                    if ((unicode & KeyCharacterMap.COMBINING_ACCENT) != 0) {
                        fail(target, "Physical dead-key composition is unsupported; reopen and use the on-screen keyboard");
                        return true;
                    }
                    String value = GpuiTerminalProtocol.physicalText(unicode);
                    accepted = event.isAltPressed()
                            ? nativeSendCommand(target.generation, new org.json.JSONObject()
                                    .put("type", "text").put("text", value).toString())
                            : nativeKey(target.generation, code, event.getAction(), event.getMetaState(), unicode);
                }
                // Every physical key bypasses the IME proxy and enters this one native FIFO.
                // Otherwise an immediate Enter could overtake text deferred to the render thread.
                if (!accepted) fail(target, "Input was not queued; reopen and check the terminal before retrying");
            } catch (IllegalArgumentException | JSONException unsupported) {
                publish(target, "This key combination is not supported; use the on-screen keyboard for composed characters");
            }
        }
        return true;
    }

    private boolean compositionBlocksInput(Connection target) {
        View focus = getCurrentFocus();
        boolean marked = focus instanceof EditText
                && BaseInputConnection.getComposingSpanStart(((EditText) focus).getText()) >= 0;
        if (!imeComposing && !marked) return false;
        publish(target, "Finish or cancel on-screen composition before sending other keys");
        return true;
    }

    private static String physicalKeyName(int key) {
        switch (key) {
            case KeyEvent.KEYCODE_ENTER: case KeyEvent.KEYCODE_NUMPAD_ENTER: return "enter";
            case KeyEvent.KEYCODE_ESCAPE: return "esc";
            case KeyEvent.KEYCODE_TAB: return "tab";
            case KeyEvent.KEYCODE_DEL: return "backspace";
            case KeyEvent.KEYCODE_DPAD_UP: return "up";
            case KeyEvent.KEYCODE_DPAD_DOWN: return "down";
            case KeyEvent.KEYCODE_DPAD_LEFT: return "left";
            case KeyEvent.KEYCODE_DPAD_RIGHT: return "right";
            default: return key >= KeyEvent.KEYCODE_F1 && key <= KeyEvent.KEYCODE_F12
                    ? "f" + (key - KeyEvent.KEYCODE_F1 + 1) : null;
        }
    }

    private void stopConnection() {
        final Connection old;
        synchronized (stateLock) {
            old = connection;
            connection = null;
            if (old == null) return;
            old.cancel();
            if (old.poll != null) old.poll.cancel(false);
            nativeSetActive(old.generation, false);
            old.text = "";
            old.remotePainted = false;
            publish(old, "Disconnected; unsent input discarded");
        }
        // disconnect() can involve platform I/O. Never join or perform it on the UI thread.
        Thread cleanup = new Thread(old::close, "herdr-gpui-disconnect");
        cleanup.setDaemon(true);
        cleanup.start();
    }

    private boolean current(Connection candidate) {
        synchronized (stateLock) {
            return connection == candidate && !candidate.isClosed();
        }
    }

    private void pump(Connection current) {
        if (!current(current)) return;
        if (current.shell) { pumpShell(current); return; }
        if (current.remoteHandle != 0) { pumpRemote(current); return; }
        boolean mutation = false;
        try {
            String raw = nativeTakeCommand(current.generation);
            if (raw != null) {
                GpuiTerminalProtocol.Command command = GpuiTerminalProtocol.command(raw);
                if ("close".equals(command.type)) {
                    synchronized (stateLock) {
                        if (!current(current)) return;
                        nativeSetActive(current.generation, false);
                        if (current.poll != null) current.poll.cancel(false);
                    }
                    main.post(() -> { if (current(current)) finish(); });
                    return;
                }
                if (!current(current)) return;
                mutation = true;
                if ("submit".equals(command.type)) {
                    GpuiTerminalProtocol.requireAck(current.client.sendText(current.paneId, command.text));
                    // One FIFO item owns both requests. Never submit after an uncertain text
                    // acknowledgment or after this connection was paused/replaced.
                    if (!current(current)) return;
                    GpuiTerminalProtocol.requireAck(current.client.sendKeys(current.paneId,
                            Collections.singletonList("enter")));
                } else {
                    String response = "text".equals(command.type)
                            ? current.client.sendText(current.paneId, command.text)
                            : current.client.sendKeys(current.paneId, command.keys);
                    GpuiTerminalProtocol.requireAck(response);
                }
                synchronized (stateLock) {
                    if (!current(current)) return;
                    publish(current, "Input accepted by companion");
                }
                mutation = false;
            }
            long now = SystemClock.elapsedRealtime();
            if (now < current.nextScreenAt || !current(current)) return;
            String text = GpuiTerminalProtocol.screen(current.client.getScreen(current.paneId));
            synchronized (stateLock) {
                if (!current(current)) return;
                current.text = text;
                current.ready = true;
                current.nextScreenAt = SystemClock.elapsedRealtime() + SCREEN_POLL_MS;
                publish(current, "Connected · " + launch.name());
                nativeSetActive(current.generation, true);
            }
        } catch (ClientException failure) {
            fail(current, failure.getMessage());
        } catch (IOException | IllegalArgumentException invalid) {
            fail(current, mutation
                    ? "Host acknowledgment was invalid. Input may have reached the host; reopen and check before retrying"
                    : "Invalid or oversized terminal data; reopen to connect again");
        } catch (RuntimeException failure) {
            fail(current, mutation
                    ? "Input failed and may have reached the host; reopen and check before retrying"
                    : "Terminal connection stopped; reopen to connect again");
        }
    }

    private void pumpRemote(Connection current) {
        boolean mutation = false;
        try {
            String raw = nativeTakeCommand(current.generation);
            if (raw != null) {
                GpuiTerminalProtocol.Command command = GpuiTerminalProtocol.command(raw);
                if ("close".equals(command.type)) {
                    synchronized (stateLock) {
                        if (!current(current)) return;
                        current.ready = false;
                        nativeSetActive(current.generation, false);
                        if (current.poll != null) current.poll.cancel(false);
                    }
                    main.post(() -> { if (current(current)) finish(); });
                    return;
                }
                if (!current(current) || !current.ready) return;
                mutation = true;
                String result = RemoteNative.input(current.remoteHandle, current.paneId, raw);
                if (!new JSONObject(result).optBoolean("queued", false)) throw new IOException("Input was not queued");
                // Keep full-speed polling briefly after typing so echo stays immediate.
                current.fastUntil = SystemClock.uptimeMillis() + 1500;
                // Binary input has no daemon receipt. Submit is one TextCommit+Enter
                // batch; this status deliberately means queued, never acknowledged.
                synchronized (stateLock) {
                    if (current(current)) publish(current, "Input queued over SSH");
                }
                mutation = false;
            }
            if (!current(current)) return;
            long nowMs = SystemClock.uptimeMillis();
            if (nowMs < current.nextRemotePollAt) return;
            // A live scroll wants frames, not just state: poll near 30 Hz until
            // the gesture settles, then fall back.
            long cadence = nowMs < current.fastUntil ? (scrollSeeking ? 33 : 50) : 100;
            current.nextRemotePollAt = nowMs + cadence;
            synchronized (stateLock) {
                if (!current(current)) return;
                String rawState = RemoteNative.pollTerminal(current.remoteHandle, current.remoteRevision,
                        current.workspaces == null ? 0 : current.chromeRevision,
                        current.generation, current.hostName, shortcutJson);
                if (rawState == null || rawState.length() > 1024 * 1024) throw new IOException("Oversized remote state");
                JSONObject state = new JSONObject(rawState);
                if (state.optBoolean("unchanged", false)) {
                    String quietPhase = state.optString("state", "");
                    String quietPane = state.optString("pane_id", "");
                    boolean smallChange = !quietPhase.equals(current.remotePhase)
                            || !quietPane.equals(current.paneId)
                            || !state.optString("boot_id", "").equals(current.remoteBoot == null ? "" : current.remoteBoot);
                    if (!smallChange) {
                        JSONObject quietScroll = state.optJSONObject("scroll");
                        if (quietScroll != null) {
                            current.scrollOffset = quietScroll.optLong("offset", 0);
                            current.scrollMax = quietScroll.optLong("max", 0);
                            current.scrollRows = quietScroll.optLong("rows", 0);
                        }
                        fetchRows(current, state);
                        return;
                    }
                    // The tree did not move but the connection did: fetch the full view once.
                    rawState = RemoteNative.pollTerminal(current.remoteHandle, 0, 0, current.generation,
                            current.hostName, shortcutJson);
                    if (rawState == null || rawState.length() > 1024 * 1024)
                        throw new IOException("Oversized remote state");
                    state = new JSONObject(rawState);
                }
                String phase = state.getString("state");
                if ("closed".equals(phase)) {
                    fail(current, GpuiTerminalProtocol.title(state.optString("status", "Remote session closed")));
                    return;
                }
                // A transport that stops answering leaves the session "connecting"
                // forever; bound that state so the user gets a reason and an action.
                String health = state.optString("status", "");
                boolean degraded = phase.equals("connecting") || health.startsWith("endpoint health check");
                if (degraded) {
                    if (current.degradedSince == 0) current.degradedSince = SystemClock.uptimeMillis();
                    else if (SystemClock.uptimeMillis() - current.degradedSince > 15_000) {
                        fail(current, health.isEmpty() ? "The host stopped responding" : health);
                        return;
                    }
                } else current.degradedSince = 0;
                if (!"ready".equals(phase) && !"connecting".equals(phase)) throw new IOException("Invalid remote state");
                String pane = state.optString("pane_id", "");
                String boot = state.optString("boot_id", "");
                // The daemon owns the focused pane: a tab, workspace or pane switch
                // moves it, and the terminal follows instead of waiting forever.
                if (!pane.isEmpty()) current.paneId = pane;
                boolean selected = "ready".equals(phase) && !pane.isEmpty() && !boot.isEmpty();
                boolean chromeChanged;
                if (current.remoteBoot != null && !current.remoteBoot.equals(boot)) {
                    fail(current, "Herdr restarted. Reopen the remote session before sending input");
                    return;
                }
                if (selected) current.remoteBoot = boot;
                current.remotePhase = phase;
                current.remoteRevision = state.optLong("token", state.optLong("revision", 0));
                current.ready = selected;
                if (selected && !pane.equals(autoKeyboardPane)) {
                    // Opening an agent that is waiting on Wolf (a question or a
                    // permission) raises the keyboard once, ready for the answer.
                    autoKeyboardPane = pane;
                    JSONArray listed = state.optJSONArray("panes");
                    for (int i = 0; listed != null && i < listed.length(); i++) {
                        JSONObject item = listed.optJSONObject(i);
                        if (item != null && pane.equals(item.optString("id"))
                                && "blocked".equals(item.optString("status"))) {
                            final long generation = current.generation;
                            main.post(() -> nativeShowKeyboard(generation));
                            break;
                        }
                    }
                }
                long chromeRevision = state.getLong("chrome_token");
                chromeChanged = current.workspaces == null || current.chromeRevision != chromeRevision;
                if (chromeChanged) {
                    current.chromeRevision = chromeRevision;
                    current.workspaces = state.optJSONArray("workspaces");
                    current.tabs = state.optJSONArray("tabs");
                    current.panes = state.optJSONArray("panes");
                    current.title = state.optString("title", current.hostName);
                }
                // Reset for the daemon-confirmed pane, not a queued focus or
                // a title update on the pane the user is already reading.
                if (selected && !pane.equals(current.chromePane)) {
                    nativeResetPan();
                    // The native side starts the new pane at its live output.
                    synchronized (current) { current.fetchedOffset = -1; }
                    if (!lastFollowSent) {
                        lastFollowSent = true;
                        nativeSetFollow(current.generation, true);
                    }
                }
                current.chromePane = pane;
                final boolean scrolledBack = state.optLong("local_offset", 0) > 0;
                main.post(() -> showLatest(scrolledBack));
                current.renderedScroll = state.optLong("rendered_scroll", current.renderedScroll);
                JSONObject scroll = state.optJSONObject("scroll");
                if (scroll != null) {
                    current.scrollOffset = scroll.optLong("offset", 0);
                    current.scrollMax = scroll.optLong("max", 0);
                    current.scrollRows = scroll.optLong("rows", 0);
                    if (scrollSeeking) {
                        // The daemon answers with the last offset it applied; do not
                        // drag the user back while a newer target is still in flight.
                        // Convergence is what the daemon has drawn, not what we asked for.
                        if (current.renderedScroll == desiredScroll) scrollSeeking = false;
                    } else {
                        desiredScroll = current.scrollOffset;
                        lastSentScroll = current.scrollOffset;
                    }
                }
                fetchRows(current, state);
                int bell = state.optInt("bell", 0);
                if (bell > 0) {
                    final int rings = bell;
                    main.post(() -> ringBell(rings));
                }
                JSONArray notices = state.optJSONArray("notifications");
                if (notices != null) {
                    for (int i = 0; i < Math.min(notices.length(), 8); i++) {
                        JSONObject notice = notices.optJSONObject(i);
                        if (notice == null) continue;
                        String noticeTitle = notice.optString("title", "");
                        if (noticeTitle.isEmpty()) continue;
                        String noticeBody = notice.optString("body", "");
                        String noticePane = notice.optString("pane_id", "");
                        main.post(() -> postNotice(noticeTitle, noticeBody, noticePane));
                    }
                }
                long serial = state.optLong("clipboard_serial", 0);
                String copy = state.optString("clipboard", "");
                if (serial > current.clipboardSerial) {
                    current.clipboardSerial = serial;
                    // Only bounded UTF-8 text decoded by the native side reaches the pasteboard.
                    if (!copy.isEmpty()) {
                        final Connection owner = current;
                        main.post(() -> applyRemoteClipboard(owner, copy));
                    }
                }
                String status = selected ? "SSH connected" : "Waiting for selected remote pane…";
                current.status = status;
                current.remotePainted = true;
                rememberSnapshot(current, status);
                nativeSetActive(current.generation, selected);
                if (chromeChanged) main.post(() -> refreshSidebar(false));
            }
        } catch (IOException | JSONException | RuntimeException failure) {
            fail(current, mutation
                    ? "SSH input stopped; it may have reached the host. Reconnect and inspect before retrying"
                    : "Remote session stopped. Reconnect from Remote hosts");
        }
    }

    private void fail(Connection failed, String message) {
        synchronized (stateLock) {
            if (!current(failed)) return;
            failed.cancel();
            if (failed.poll != null) failed.poll.cancel(false);
            nativeSetActive(failed.generation, false);
            publish(failed, message);
            showReconnectBanner(message);
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            Thread cleanup = new Thread(failed::close, "herdr-gpui-disconnect");
            cleanup.setDaemon(true);
            cleanup.start();
        } else failed.close();
    }

    /** A raw SSH shell instead of a Herdr pane: no rail, panes or session changes. */
    private boolean shellMode() {
        return launch != null && launch.shell;
    }

    /** Opens a new shell on the launch host under a new generation. UI thread. */
    private void startShell() {
        final SavedRemoteHost host = launch.remoteHost;
        final String grid = terminalGrid();
        synchronized (stateLock) {
            Connection next = new Connection(GENERATIONS.incrementAndGet());
            connection = next;
            nativeSetActive(next.generation, false);
            publish(next, "Opening a shell…");
            // Connecting holds the worker; the pump below runs once it is done.
            worker.execute(() -> connectShell(next, host, grid));
            next.poll = worker.scheduleWithFixedDelay(() -> pump(next), 0, COMMAND_POLL_MS, TimeUnit.MILLISECONDS);
        }
    }

    /** Worker thread: SSH handshake, PTY and login shell, then the shell is drawn. */
    private void connectShell(Connection next, SavedRemoteHost host, String grid) {
        long handle = 0;
        try {
            char[] key = new RemoteIdentityStore(this).read();
            String credential;
            try {
                credential = new JSONObject().put("private_key", new String(key)).toString();
            } finally {
                java.util.Arrays.fill(key, '\0');
            }
            // The first size is an estimate; the terminal resizes the PTY when it lays out.
            String[] size = grid.split(",");
            handle = RemoteNative.openShell(host.connectionJson(), credential,
                    Integer.parseInt(size[0]), Integer.parseInt(size[1]));
            if (handle <= 0) throw new IllegalStateException("The host refused the shell");
            synchronized (stateLock) {
                if (!current(next)) return;
                next.shellHandle = handle;
                handle = 0;
                next.ready = true;
                nativeAttachShell(next.generation, next.shellHandle);
                nativeSetActive(next.generation, true);
                publish(next, "Shell");
            }
            runOnUiThread(this::hideReconnectBanner);
        } catch (Exception | LinkageError failure) {
            String reason = failure.getMessage();
            fail(next, "Could not open a shell on " + host.name
                    + (reason == null || reason.isEmpty() ? "" : ": " + reason));
        } finally {
            if (handle != 0) RemoteNative.closeShell(handle);
        }
    }

    /** The mailbox's commands as bytes on the shell's PTY, in order. */
    private void pumpShell(Connection current) {
        long handle = current.shellHandle;
        if (handle == 0 || !current.ready) return;
        try {
            if (!RemoteNative.shellAlive(handle)) {
                synchronized (stateLock) {
                    if (!current(current)) return;
                    // The last screen stays readable and copyable until the user leaves.
                    current.ready = false;
                    nativeSetActive(current.generation, false);
                    if (current.poll != null) current.poll.cancel(false);
                    publish(current, BannerCopy.SHELL_EXITED);
                }
                showReconnectBanner(BannerCopy.SHELL_EXITED);
                return;
            }
            for (int i = 0; i < 16; i++) {
                String raw = nativeTakeCommand(current.generation);
                if (raw == null) return;
                GpuiTerminalProtocol.Command command = GpuiTerminalProtocol.command(raw);
                if ("close".equals(command.type)) {
                    synchronized (stateLock) {
                        if (!current(current)) return;
                        current.ready = false;
                        nativeSetActive(current.generation, false);
                        if (current.poll != null) current.poll.cancel(false);
                    }
                    main.post(() -> { if (current(current)) finish(); });
                    return;
                }
                if (!current(current)) return;
                if (!RemoteNative.shellInput(handle, raw)) throw new IOException("Shell input queue is full");
            }
        } catch (IOException | RuntimeException failure) {
            fail(current, "Shell input failed and may have partly reached the host; reconnect to continue");
        }
    }

    /** Called only under stateLock, preventing a paused session from reactivating after a read. */
    private void publish(Connection target, String status) {
        if (target.generation == lastSnapshotGeneration && target.title.equals(lastSnapshotTitle)
                && target.text.equals(lastSnapshotText) && status.equals(lastSnapshotStatus)
                && shortcutJson.equals(lastSnapshotShortcuts)
                && target.remoteRevision == lastSnapshotRevision) return;
        target.status = status;
        if (target.remoteHandle != 0 && target.remotePainted && target.ready) {
            nativeSetSnapshotChrome(target.generation, target.title, status, shortcutJson);
        } else {
            nativeSetSnapshot(target.generation, target.title, target.text, status, shortcutJson);
        }
        rememberSnapshot(target, status);
    }

    /** Records a snapshot already painted by pollTerminal without sending its body again. */
    private void rememberSnapshot(Connection target, String status) {
        lastSnapshotGeneration = target.generation;
        lastSnapshotRevision = target.remoteRevision;
        lastSnapshotTitle = target.title;
        lastSnapshotText = target.text;
        lastSnapshotStatus = status;
        lastSnapshotShortcuts = shortcutJson;
    }

    private final class Connection {
        long degradedSince;
        final long generation;
        final CompanionClient client;
        final long remoteHandle;
        /** A raw SSH shell; {@link #shellHandle} is 0 until it is open. */
        final boolean shell;
        volatile long shellHandle;
        volatile boolean closed;
        String remoteBoot;
        volatile String paneId = launch.paneId;
        volatile long remoteRevision;
        volatile long chromeRevision;
        volatile String remotePhase = "";
        volatile String status = "Connecting…";
        volatile long fastUntil;
        volatile long renderedScroll;
        /** Last offset {@link #fetchRows} asked for and when, guarded by the connection. */
        long fetchedOffset = -1, fetchedAt;
        long nextRemotePollAt;
        volatile String title = launch.title;
        volatile String hostName = launch.name();
        String text = "";
        boolean remotePainted;
        JSONArray workspaces, tabs, panes;
        volatile long clipboardSerial;
        volatile long scrollOffset, scrollMax, scrollRows;
        String chromePane = "";
        long nextScreenAt;
        boolean ready;
        ScheduledFuture<?> poll;

        Connection(long generation, CompanionClient client) {
            this.generation = generation;
            this.client = client;
            this.remoteHandle = 0;
            this.shell = false;
        }
        Connection(long generation, long remoteHandle) {
            this.generation = generation;
            this.client = null;
            this.remoteHandle = remoteHandle;
            this.shell = false;
        }
        Connection(long generation) {
            this.generation = generation;
            this.client = null;
            this.remoteHandle = 0;
            this.shell = true;
        }
        boolean isClosed() { return closed || (client != null && client.isClosed()); }
        void cancel() {
            closed = true;
            if (client != null) client.cancel();
            else if (shell) closeShell();
            else RemoteNative.close(remoteHandle);
        }
        void close() {
            closed = true;
            if (client != null) client.close();
            else if (shell) closeShell();
            else RemoteNative.close(remoteHandle);
        }
        /** Never blocks, so it is safe under stateLock on the UI thread. */
        private void closeShell() {
            long handle = shellHandle;
            shellHandle = 0;
            if (handle != 0) RemoteNative.closeShell(handle);
        }
    }

    private static native void nativeSetDisplayPreferences(float fontSize, boolean compact,
            float systemFontScale);
    /** Draws the raw shell {@code handle} (0 for Herdr's pane) for this generation only. */
    private static native boolean nativeAttachShell(long generation, long handle);

    /** Moves the terminal horizontally by this many logical pixels. */
    private static native void nativePan(float delta);

    /** Drops the horizontal offset when a different pane is confirmed. */
    private static native void nativeResetPan();
    private static native void nativeSetTheme(int index);
    private static native void nativeSetSnapshot(long generation, String title, String text, String status,
            String shortcutsJson);
    private static native void nativeSetSnapshotChrome(long generation, String title, String status,
            String shortcutsJson);
    private static native String nativeCopyText(long generation);
    private static native void nativeSetActive(long generation, boolean active);
    private static native void nativeSetFollow(long generation, boolean follow);
    private static native void nativeShowKeyboard(long generation);
    private static native String nativeTakeCommand(long generation);
    private static native boolean nativeSendCommand(long generation, String commandJson);
    private static native boolean nativeSendKey(long generation, String key);
    private static native void nativeStart(android.app.Activity activity);
    private static native void nativeSurfaceCreated(Surface surface, float scale);
    private static native void nativeSurfaceDestroyed();
    private static native void nativeResumed();
    private static native void nativePaused();
    private static native boolean nativeTouch(int action, int actionIndex, int[] ids, float[] xs, float[] ys);
    private static native boolean nativeKey(long generation, int keyCode, int action, int metaState, int unicodeCodepoint);
    private static native void nativeSetImeSession(long generation, long session);
    private static native boolean nativeImeEvent(long generation, long session, int kind, String text, int start, int end);
}
