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
    private final java.util.concurrent.ExecutorService actionWorker =
            java.util.concurrent.Executors.newSingleThreadExecutor(task -> {
                Thread thread = new Thread(task, "herdr-action");
                thread.setDaemon(true);
                return thread;
            });
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
    /** The bar's composer, mic-only mode and folded keys; see {@link ComposerBar}. */
    private ComposerBar composer;
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
    /** The terminal and, swiped to, a file an agent or a tapped path opened. */
    private FilePager filePager;
    private volatile boolean sidebarOpen;
    /** When the last window switch was asked for, to log how long it took. */
    private volatile long switchAskedAt;
    private final BackOwner backOwner = new BackOwner(this, this::hideSidebar);
    /**
     * Android 14+: while the rail is closed, a Back swipe from the left edge
     * opens it, like a navigation drawer; the right edge and the buttons still
     * go back. Null below Android 14 or while the rail is open.
     */
    private Object edgeBack;
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
            float rowPx = scrollRowPx();
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
        outbox = OutboxCourier.shared(this);
        outbox.listen(outboxChanged);
        snapshots = PaneSnapshots.shared(this);
        worker.setRemoveOnCancelPolicy(true);
        worker.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        if (launch == null) {
            android.util.Log.w("herdr-gpui", "Reopen the terminal from the host list");
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
            android.util.Log.w("herdr-gpui", "Check this host's shortcut settings");
            finish();
        } catch (LinkageError unavailable) {
            android.util.Log.w("herdr-gpui", "The GPUI renderer could not be started");
            finish();
        } catch (IllegalStateException unavailable) {
            android.util.Log.w("herdr-gpui", "The GPUI renderer could not be started");
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
        if (filePager != null) filePager.applyChrome(chrome);
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
                if (composer != null) composer.imeVisible(insets.isVisible(WindowInsets.Type.ime()));
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
        // Android's Back gesture owns the screen edges and ignores exclusion
        // requests, so a left-edge Back swipe opens the rail (setEdgeBack) and
        // a right swipe inside the left half does too (forwardTouch).
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        terminalContent = content;
        // The current space's tabs across the top, as the desktop's tab bar
        // shows them; the rail keeps only machines, spaces and agents.
        tabScroll = new android.widget.HorizontalScrollView(this);
        tabScroll.setHorizontalScrollBarEnabled(false);
        tabScroll.setFillViewport(false);
        tabStrip = new LinearLayout(this);
        tabStrip.setOrientation(LinearLayout.HORIZONTAL);
        tabStrip.setGravity(android.view.Gravity.CENTER_VERTICAL);
        tabStrip.setPadding(dp(6), 0, dp(6), 0);
        tabScroll.addView(tabStrip, new FrameLayout.LayoutParams(-2, -1));
        // An agent's pane has no bottom bar: the strip's end holds what the bar's
        // buttons did (panes, hosts, view, session, close).
        tabRow = new PullDownRow(this);
        tabRow.setOrientation(LinearLayout.HORIZONTAL);
        tabRow.setOnPull(this::showMachines);
        tabRow.addView(tabScroll, new LinearLayout.LayoutParams(0, -1, 1));
        ImageButton tabMenu = iconButton(R.drawable.ic_bar_more, "More", "Panes, hosts and terminal actions",
                view -> showMoreActions(view, true));
        tabRow.addView(tabMenu, new LinearLayout.LayoutParams(dp(36), -1));
        content.addView(tabRow, new LinearLayout.LayoutParams(-1, dp(30)));
        // Where this window is: machine › repo › worktree › window, and why
        // it wants Wolf. Tapping it shows the window's row in the rail.
        crumb = new TextView(this);
        crumb.setTextSize(11);
        crumb.setSingleLine(true);
        crumb.setEllipsize(android.text.TextUtils.TruncateAt.START);
        crumb.setGravity(android.view.Gravity.CENTER_VERTICAL);
        crumb.setPadding(dp(10), 0, dp(10), 0);
        crumb.setVisibility(View.GONE);
        crumb.setOnClickListener(v -> { showRailOverlay(); revealSelectedRow(true); });
        content.addView(crumb, new LinearLayout.LayoutParams(-1, dp(20)));
        filePager = new FilePager(this, surface, chrome, this::readFile, actionWorker);
        content.addView(filePager, new LinearLayout.LayoutParams(-1, 0, 1));
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
        composer = new ComposerBar(this, chrome, Dictation.create(this), composerHost());
        Dictation.pending(this).setListener(pendingDictations);
        composer.setWaiting(Dictation.pending(this).count());
        shortcutBar = createShortcutBar();
        content.addView(shortcutBar, new LinearLayout.LayoutParams(-1, dp(48)));
        // The composer floats over this column, so a growing draft never resizes the pane.
        FrameLayout column = new FrameLayout(this);
        terminalColumn = column;
        column.addView(content, new FrameLayout.LayoutParams(-1, -1));
        // On an agent's pane: the keys above its prompt, the touch targets over
        // the controls drawn in its prompt box, and the keyboard's invisible
        // field. The composer places each from the renderer's layout hints.
        column.addView(composer.keysOverlay(), new FrameLayout.LayoutParams(-1, dp(48)));
        column.addView(composer.targets(), new FrameLayout.LayoutParams(0, 0));
        filePager.setOnShownChanged(() -> composer.setCovered(filePager.showingFile()));
        column.addView(composer.field(), new FrameLayout.LayoutParams(-1, ComposerBar.FIELD_HEIGHT_PX,
                android.view.Gravity.BOTTOM));
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
        railColumns.addView(column, new LinearLayout.LayoutParams(0, -1, 1));
        root.addView(railColumns, new FrameLayout.LayoutParams(-1, -1));
        // Below 600 dp the rail is a modal drawer over this scrim, instead of
        // squeezing an already narrow terminal; Back or a tap outside closes it.
        railScrim = new View(this);
        railScrim.setBackgroundColor(0x52000000);
        railScrim.setVisibility(View.GONE);
        railScrim.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        railScrim.setOnClickListener(view -> hideSidebar());
        // A swipe across the scrim closes the drawer as a tap does; any lift
        // outside the drawer is "away from the rail".
        railScrim.setOnTouchListener((view, event) -> {
            if (event.getActionMasked() == MotionEvent.ACTION_UP) view.performClick();
            return true;
        });
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

    /**
     * Opened by a gesture (edge swipe, edge Back, the breadcrumb) on a wide
     * window, the rail floats over the terminal like the cover's drawer: the
     * terminal keeps its width, so the agent's pty is never resized (a resize
     * makes Claude Code's screen reflow into fragments, on every viewer of a
     * shared terminal). The toolbar's toggle still pins it beside the terminal.
     */
    private boolean railOverlay;

    private boolean railAsDrawer() {
        return compactWindow() || railOverlay;
    }

    /** Opens the rail as a drawer over the terminal, never narrowing it. */
    private void showRailOverlay() {
        if (sidebarOpen) return;
        railOverlay = !compactWindow();
        setSidebarOpen(true);
    }

    private void styleRail() {
        if (sidebar == null) return;
        boolean drawer = railAsDrawer();
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
        boolean drawer = railAsDrawer();
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
        setEdgeBack(!sidebarOpen);
    }

    /**
     * The system takes every left-edge swipe for Back and ignores exclusion
     * requests (d62d747), so the drawer gesture rides on Back itself: Android
     * 14's BackEvent says which edge the swipe came from. Left edge opens the
     * rail; anything else leaves the terminal as Back always did.
     */
    private void setEdgeBack(boolean wanted) {
        if (Build.VERSION.SDK_INT < 34 || wanted == (edgeBack != null)) return;
        if (wanted) {
            edgeBack = EdgeBack.register(this, () -> {
                if (composer != null && composer.hideKeyboard()) return;
                if (shellMode()) leaveByBack(); else showRailOverlay();
            }, () -> {
                if (composer != null && composer.hideKeyboard()) return;
                if (filePager == null || !filePager.hide()) leaveByBack();
            });
        } else {
            EdgeBack.unregister(this, edgeBack);
            edgeBack = null;
        }
    }

    /** What Back did before the edge callback: finish, or send a root task back. */
    @SuppressWarnings("deprecation")
    private void leaveByBack() {
        super.onBackPressed();
    }

    @android.annotation.TargetApi(34)
    private static final class EdgeBack {
        static Object register(android.app.Activity activity, Runnable leftEdge, Runnable otherwise) {
            android.window.OnBackAnimationCallback callback = new android.window.OnBackAnimationCallback() {
                private boolean fromLeft;
                @Override public void onBackStarted(android.window.BackEvent event) {
                    fromLeft = event.getSwipeEdge() == android.window.BackEvent.EDGE_LEFT;
                }
                @Override public void onBackCancelled() { fromLeft = false; }
                @Override public void onBackInvoked() {
                    boolean left = fromLeft;
                    fromLeft = false;
                    (left ? leftEdge : otherwise).run();
                }
            };
            activity.getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, callback);
            return callback;
        }

        static void unregister(android.app.Activity activity, Object callback) {
            activity.getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(
                    (android.window.OnBackInvokedCallback) callback);
        }
    }

    /** Choosing a destination in the drawer closes it, as Android's drawers do. */
    private void closeRailDrawer() {
        if (railAsDrawer()) hideSidebar();
    }

    @SuppressWarnings("deprecation")
    @Override public void onBackPressed() {
        // Back puts the composer's keyboard away first.
        if (composer != null && composer.hideKeyboard()) return;
        if (sidebarOpen && railAsDrawer()) {
            hideSidebar();
            return;
        }
        if (filePager != null && filePager.hide()) return;
        super.onBackPressed();
    }


    /**
     * Real terminal grid for the daemon: the monospace cell box the renderer
     * uses, so Herdr sizes the pane to this display instead of 80x24.
     */
    private String terminalGrid() {
        return TerminalGrid.estimate(this);
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
        if (reconnectRunning) return;
        reconnectRunning = true;
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
                runOnUiThread(() -> {
                    reconnectRunning = false;
                    if (monitor != null) monitor.succeeded();
                    showLinkUp();
                    showReconnectBanner("Open the host list to reconnect");
                });
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
                handle = RemoteNative.connectGrid(host.connectionJson(), credential, terminalGrid(), false);
                if (handle <= 0) throw new IllegalStateException("connection refused");
                reconnectHandle = handle;
                // Paused before the handle was published: attach nothing.
                if (!hostResumed) throw new IllegalStateException("left the screen");
                // The same computer again: back to its remembered terminal, not its Deacon.
                String chosen = awaitHostPane(handle, host.id, null, false);
                reconnectHandle = 0;
                final long readyHandle = handle;
                final String readyPane = chosen;
                final SavedRemoteHost readyHost = host;
                runOnUiThread(() -> {
                    reconnectRunning = false;
                    if (!hostResumed) {
                        // Left the screen while connecting: onResume opens it again.
                        RemoteNative.close(readyHandle);
                        return;
                    }
                    hideReconnectBanner();
                    adoptHost(readyHost, readyHandle, readyPane);
                });
                handle = 0;
            } catch (Exception | LinkageError failure) {
                reconnectHandle = 0;
                if (handle != 0) RemoteNative.close(handle);
                android.util.Log.i("herdr-reconnect", "attempt failed: " + failure);
                runOnUiThread(() -> {
                    reconnectRunning = false;
                    // Left the screen meanwhile: onResume reconnects; say nothing.
                    if (!hostResumed) return;
                    if (monitor != null) {
                        // Keep trying, quickly, while the terminal is on screen.
                        monitor.failed();
                        showLinkDown();
                    } else showReconnectBanner("Reconnect failed. Check the host and network.");
                });
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
            return;
        }
        android.content.ClipboardManager clipboard =
                (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (clipboard == null) return;
        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Herdr pane", text));
    }

    private void publishCurrent(String status) {
        Connection target;
        synchronized (stateLock) { target = connection; }
        if (target != null && !target.isClosed()) publish(target, status);
    }


    private View createShortcutBar() {
        boolean compact = compactWindow();
        toolbarCompact = compact;
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
                "Show or hide the on-screen keyboard", view -> toggleKeyboard());
        // Input controls resolve on touch-up, in order with the keys they sit by.
        synchronousTouch(keyboard);
        pinned.addView(keyboard);
        if (compact) {
            // A compact bar keeps frequent controls and the keys in view; the
            // rest live in one overflow menu, as platform toolbars do.
            pinned.addView(iconButton(R.drawable.ic_bar_more, "More",
                    "More terminal actions", view -> showMoreActions(view, false)));
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
        LinearLayout keys = new LinearLayout(this);
        keys.setOrientation(LinearLayout.HORIZONTAL);
        keys.setGravity(android.view.Gravity.CENTER_VERTICAL);
        keys.setPadding(dp(4), 0, dp(8), 0);
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
        View bar = composer.bar(chrome, pinned, scrolling);
        updateControlButton();
        updateRailToggle();
        return bar;
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
            // Enter has its own place at the end of the bar.
            if (GpuiTerminalProtocol.isPlainEnter(shortcut)) continue;
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
        // The surface sits inside the file pager now; the pager is the column's child.
        View pane = filePager != null ? filePager : terminalSurface;
        if (!(pane.getLayoutParams() instanceof LinearLayout.LayoutParams)) return;
        LinearLayout.LayoutParams surfaceParams = (LinearLayout.LayoutParams) pane.getLayoutParams();
        if (surfaceParams.height != height || surfaceParams.weight != weight) {
            pane.setLayoutParams(new LinearLayout.LayoutParams(-1, height, weight));
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
    /** The overflow menu; {@code navigation} adds panes and hosts, for an agent's barless pane. */
    private void showMoreActions(View anchor, boolean navigation) {
        android.content.Context themed = new android.view.ContextThemeWrapper(this, chrome.popupTheme());
        android.widget.PopupMenu menu = new android.widget.PopupMenu(themed, anchor);
        ColorStateList tint = ColorStateList.valueOf(themeColor(themed, android.R.attr.textColorPrimary));
        menu.getMenu().add(0, 1, 0, "View options").setIcon(R.drawable.ic_bar_view).setIconTintList(tint);
        menu.getMenu().add(0, 2, 1, "Session actions").setIcon(R.drawable.ic_bar_session).setIconTintList(tint);
        menu.getMenu().add(0, 3, 2, "Close terminal").setIcon(R.drawable.ic_bar_close).setIconTintList(tint);
        if (navigation && !shellMode()) {
            menu.getMenu().add(0, 4, -2, "Panes").setIcon(R.drawable.ic_bar_panes).setIconTintList(tint);
            menu.getMenu().add(0, 5, -1, "Hosts and agents").setIcon(R.drawable.ic_bar_hosts).setIconTintList(tint);
            menu.getMenu().add(0, 6, 3, "Gestures");
        }
        menu.setForceShowIcon(true);
        menu.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == 1) showDisplayOptions();
            else if (item.getItemId() == 2) showSessionActions();
            else if (item.getItemId() == 4) showPanes();
            else if (item.getItemId() == 5) toggleSidebar();
            else if (item.getItemId() == 6 && terminalColumn != null) {
                GestureHint.show(this, terminalColumn, chrome);
            }
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

    /**
     * Pixels of finger travel per scrolled line: a row's height divided by the
     * provisioned scroll speed (default 1.4, a little quicker than one row per
     * row of travel). Drags and the fling that follows them use the same rate.
     */
    private float scrollRowPx() {
        return Math.max(1f, rowHeightPx() / ProvisionedSettings.get(this).scrollSpeed);
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
        if (heldFrame(target)) return scrollHeld(lines, false);
        if (target.remoteHandle <= 0) return false;
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

    /**
     * Whether the terminal shows a held frame (the link is down or a reconnect
     * has not attached yet) rather than a live Herdr pane. Null and closed
     * count as held: there is nothing live to scroll.
     */
    private boolean heldFrame(Connection target) {
        if (shellMode()) return false;
        return target == null || target.isClosed() || (target.remoteHandle > 0 && !target.ready);
    }

    /**
     * Scrolls the held frame's own history on the phone: nothing is sent, so
     * it works with no link at all. The "Latest" pill follows its offset.
     */
    private boolean scrollHeld(int lines, boolean latest) {
        try {
            scrollWorker.execute(() -> {
                try {
                    long offset = new JSONObject(RemoteNative.scrollHeld(lines, latest)).optLong("local_offset", 0);
                    main.post(() -> showLatest(offset > 0));
                } catch (Exception | LinkageError ignored) {
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException busy) {
            return false;
        }
        return true;
    }

    /** Nothing pends: local scrolling never waits on a round trip. */
    private void flushScroll() { }

    /**
     * The toolbar's keyboard button toggles. It asks the window whether the IME
     * is really showing (its insets), never a remembered flag, so a Back-gesture
     * dismissal cannot leave the button out of step.
     */
    private void toggleKeyboard() {
        boolean shown = false;
        View root = getWindow().getDecorView();
        if (Build.VERSION.SDK_INT >= 30 && root.getRootWindowInsets() != null) {
            shown = root.getRootWindowInsets().isVisible(WindowInsets.Type.ime());
        }
        // Typing in the composer: this button means raw terminal input instead.
        if (composer != null && composer.ownsKeys()) shown = false;
        if (shown) {
            allowNextKeyboardShow = false;
            super.gpuiHideKeyboard(imeSession);
            if (Build.VERSION.SDK_INT >= 30 && getWindow().getInsetsController() != null) {
                getWindow().getInsetsController().hide(WindowInsets.Type.ime());
            }
            return;
        }
        Connection target;
        synchronized (stateLock) { target = connection; }
        if (target != null) {
            if (terminalSurface != null) terminalSurface.requestFocus();
            nativeShowKeyboard(target.generation);
        }
    }

    private TextView latestButton;
    /** The pane last checked for a waiting agent; touched on the poll thread only. */
    private volatile String autoKeyboardPane = "";

    /**
     * "↓ Latest" floats over the terminal while its history is scrolled back, so
     * reading old output never loses the way back to the live end.
     */
    /** What the poll last reported, read off the UI thread to skip redundant posts. */
    private volatile boolean shownScrolledBack;

    private void showLatest(boolean scrolledBack) {
        shownScrolledBack = scrolledBack;
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
        if (heldFrame(target)) {
            scrollHeld(0, true);
            return;
        }
        if (target.remoteHandle <= 0) return;
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
        options.addView(optionLabel(context, "Agent alerts"));
        Spinner alerts = optionSpinner(context, new String[]{"Off", "Notify when an agent needs me"},
                agentAlertsOn() ? 1 : 0);
        options.addView(alerts);
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
                            .putInt("alt", displayOptions.altMode).putInt("theme", themeIndex)
                            .putBoolean(AGENT_ALERTS, alerts.getSelectedItemPosition() == 1).apply();
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
            note("Trust " + host.name + " from Remote hosts first");
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
                handle = RemoteNative.connectGrid(host.connectionJson(), credential, terminalGrid(), false);
                if (handle <= 0) throw new IllegalStateException("connection refused");
                // Another computer: its live Deacon unless a terminal there was picked.
                String chosen = awaitHostPane(handle, host.id, forcedPane, true);
                final long readyHandle = handle;
                final String readyPane = chosen;
                runOnUiThread(() -> adoptHost(host, readyHandle, readyPane));
                handle = 0;
            } catch (Exception | LinkageError failure) {
                if (handle != 0) RemoteNative.close(handle);
                runOnUiThread(() -> {
                    switchingHost = false;
                    switchingTo = null;
                    note("Could not switch to " + host.name);
                });
            }
        });
    }

    /**
     * Waits for the new session, focuses its first pane and returns that id.
     * {@code machineSwitch}: another computer was picked, so it lands on that
     * computer's live Deacon (FleetTree.switchLanding) unless a live terminal
     * there was named; with no Deacon, the usual choice below applies.
     */
    private String awaitHostPane(long handle, String hostId, String forcedPane, boolean machineSwitch) throws Exception {
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
        // A Deacon may be known only by its space's name: give the spaces a moment.
        for (long spaces = SystemClock.uptimeMillis() + 1_500; machineSwitch && !FleetPanes.hasSpaces(state)
                && SystemClock.uptimeMillis() < spaces; ) {
            Thread.sleep(150);
            state = new JSONObject(RemoteNative.poll(handle, 0, false));
        }
        JSONArray panes = state == null ? null : state.optJSONArray("panes");
        if (panes == null || panes.length() == 0) throw new IllegalStateException("no panes");
        // An explicit pick wins; then (switching computers) its live Deacon;
        // then the pane this phone last used on that host; the daemon's own
        // focus is often an idle shell.
        String landing = machineSwitch ? FleetPanes.switchLanding(state, forcedPane) : "";
        String pane = !landing.isEmpty() ? landing
                : forcedPane != null && !forcedPane.isEmpty() ? forcedPane : rememberedHostPane(hostId);
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
            // Resuming the same host keeps the last frame, the strip and the
            // keyboard until the new session paints; another host starts clean.
            boolean resume = previous != null && previous.remoteHandle > 0 && host.id.equals(currentHostId);
            if (!resume) pendingInput.discardAll();
            next = new Connection(GENERATIONS.incrementAndGet(), handle);
            if (resume) {
                next.workspaces = previous.workspaces;
                next.tabs = previous.tabs;
                next.panes = previous.panes;
            }
            next.resumed = resume;
            next.hostId = host.id;
            next.paneId = pane;
            next.hostName = host.name;
            scrollSeeking = false;
            lastFollowSent = true;
            next.title = host.name;
            next.remoteBoot = null;
            connection = next;
            currentHostId = host.id;
            lastSnapshotTitle = lastSnapshotText = lastSnapshotStatus = lastSnapshotShortcuts = null;
            // A saved pane on screen stays, like a resumed frame, until this one paints.
            if (next.resumed || host.id.equals(savedShownHost)) {
                nativeHold(next.generation);
                if (imeSession > 0) nativeSetImeSession(next.generation, imeSession);
            } else nativeSetActive(next.generation, false);
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
                note("Window copy failed: " + result);
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
        // The stored choice is for a pinned panel with room; a drawer (the
        // cover's, or one opened by a gesture) is transient.
        boolean transientDrawer = railAsDrawer();
        if (!open) railOverlay = false;
        if (!transientDrawer) {
            getSharedPreferences("terminal-display", MODE_PRIVATE).edit()
                    .putBoolean("list", sidebarOpen).apply();
        }
        applySidebarWidth();
        updateHostSampling();
        if (sidebarOpen) {
            revealedPane = "";
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
        } else if (railAsDrawer()) {
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
        sidebarButton.setContentDescription(sidebarOpen
                ? "Hide the host and workspace sidebar"
                : "Show the host and workspace sidebar");
    }

    /** Rebuilds the rail only when the daemon's chrome actually changed. */
    private android.widget.HorizontalScrollView tabScroll;
    private TextView crumb;
    private String crumbText = "";
    /** Replaces the crumb while the link is down: the one status line, no overlay. */
    private String linkStatus;
    /** Reconnects a dropped remote session while this terminal is on screen. */
    private ConnectionMonitor monitor;
    /** Input that never left the phone, sent once the same pane is ready again. Under stateLock. */
    private final PendingInput pendingInput = new PendingInput();
    /** Composer messages not yet delivered, on disk; shared by every terminal activity. */
    private OutboxCourier outbox;
    /** Each pane's last grid on disk, drawn when the terminal opens with no link. */
    private PaneSnapshots snapshots;
    /** The computer whose saved pane is on screen (until its live pane attaches), and when it was saved. */
    private String savedShownHost;
    private long savedShownAt;
    /** The terminal has been on screen before (later resumes reopen the pane last shown). */
    private boolean openedOnce;
    /** One reconnect at a time. Main thread. */
    private boolean reconnectRunning;
    /** The session a reconnect is opening; closed if the terminal leaves the screen first. */
    private volatile long reconnectHandle;
    /** The pane the rail last scrolled into view. */
    private String revealedPane = "";
    private LinearLayout tabStrip;
    /** The tab strip and its menu button; a pull down on it switches machine. */
    private PullDownRow tabRow;
    /** The frame over the terminal column, where the composer and hints float. */
    private FrameLayout terminalColumn;
    private String tabSignature = "";
    /** The tab the strip last scrolled to, so a refresh does not scroll it again. */
    private String tabActive = "";

    /** Rebuilds the top tab strip when the space's tabs, their states or focus change. */
    private void refreshTabStrip() {
        if (tabStrip == null || destroyed) return;
        Connection current;
        synchronized (stateLock) { current = connection; }
        if (composer != null) {
            String kind = current == null || shellMode() ? "" : agentKind(current);
            composer.setPane(current == null ? "" : current.paneId, kind);
            // Agent and shell panes differ in whether the keyboard keeps its mic.
            refreshTerminalImeOptions();
            refreshQueued();
        }
        boolean show = current != null && !shellMode() && current.tabs != null;
        tabScroll.setVisibility(show ? View.VISIBLE : View.GONE);
        // The menu stays wherever the bar is gone, tabs or not.
        if (tabRow != null) tabRow.setVisibility(current != null && !shellMode() ? View.VISIBLE : View.GONE);
        if (!show) return;
        String space = "", paneTab = "";
        JSONArray panes = current.panes;
        for (int i = 0; panes != null && i < panes.length(); i++) {
            JSONObject pane = panes.optJSONObject(i);
            if (pane != null && pane.optString("id").equals(current.paneId)) {
                space = pane.optString("workspace_id");
                paneTab = pane.optString("tab_id");
            }
        }
        updateCrumb(current, space);
        java.util.List<FleetTree.Member> fleetMembers = FleetPanes.members(current.workspaces, current.tabs,
                current.panes, FleetTree.Member::new);
        FleetTree.Strip roleStrip = FleetTree.stripFor(fleetMembers, FleetPanes.LOOKS.get(currentHostId),
                current.paneId, current.ready);
        if (stripChoice().showsGroup(currentHostId, space, roleStrip)) {
            showRoleStrip(current, roleStrip, space);
            return;
        }
        // Rebuild only when what the strip shows changes. Agents retitle their
        // panes many times a second (spinners), and rebuilding then dropped
        // taps and yanked the strip back to the active tab mid-swipe.
        JSONArray tabs = current.tabs;
        java.util.List<String[]> shown = new java.util.ArrayList<>();
        StringBuilder signature = new StringBuilder(space).append(roleStrip != null ? "\tgroup" : "").append('\n');
        String activeId = "";
        for (int i = 0; i < tabs.length(); i++) {
            JSONObject tab = tabs.optJSONObject(i);
            if (tab == null || !space.equals(tab.optString("workspace_id"))) continue;
            String id = tab.optString("id");
            String status = "";
            JSONObject tabPane = null;
            for (int j = 0; panes != null && j < panes.length(); j++) {
                JSONObject pane = panes.optJSONObject(j);
                if (pane != null && id.equals(pane.optString("tab_id"))
                        && space.equals(pane.optString("workspace_id"))) {
                    status = pane.optString("status");
                    tabPane = pane;
                }
            }
            // The pane being shown, which a tap moves at once, not the daemon's
            // confirmation a round trip later.
            boolean focused = id.equals(paneTab);
            String label = HomeActivity.cleanTitle(tab.optString("label", id), id);
            if (tabPane != null && RailTree.isTerminalId(label)) {
                label = RailTree.fallbackTitle(HomeActivity.cleanTitle(
                        chromeTitle(tabPane.optString("title", ""), id), id), tabPane);
            }
            shown.add(new String[] {id, label, status, focused ? "1" : ""});
            signature.append(id).append('\t').append(label).append('\t').append(status)
                    .append('\t').append(focused).append('\n');
            if (focused) activeId = id;
        }
        if (signature.toString().equals(tabSignature)) return;
        boolean activeMoved = !activeId.equals(tabActive);
        tabSignature = signature.toString();
        tabActive = activeId;
        tabStrip.removeAllViews();
        View active = null;
        for (String[] item : shown) {
            String id = item[0], status = item[2];
            boolean focused = !item[3].isEmpty();
            TextView pill = tabPill(item[1], focused, status);
            pill.setTag(focused ? PANE_PILL_ACTIVE : PANE_PILL);
            final Connection target = current;
            final String tabSpace = space, tabLabel = item[1];
            pill.setOnClickListener(v -> { if (!focused) focusChrome(target, "tab", id); });
            pill.setOnTouchListener(new TabDrag(pill, () -> showTabActions(target, tabSpace, id, tabLabel),
                    to -> runEdit(target, "Move " + tabLabel, "op", "tab.move", "space", tabSpace,
                            "tab", id, "index", to)));
            pill.setContentDescription("Tab " + pill.getText() + (status.isEmpty() ? "" : ", " + status)
                    + (focused ? ", current" : ""));
            tabStrip.addView(pill);
            if (focused) active = pill;
        }
        TextView add = tabPill("+", false, "");
        add.setContentDescription("New Claude, Codex or terminal tab in this space");
        add.setOnClickListener(this::showNewMenu);
        tabStrip.addView(add);
        // Back to the role group (as the desktop's chip), when there is one.
        if (roleStrip != null) {
            final String chipSpace = space;
            TextView group = tabPill(roleStrip.mode.equals("pm") ? "▤ " + roleStrip.name : "▤ Deacon", false, "");
            group.setContentDescription("Show the " + (roleStrip.mode.equals("pm") ? roleStrip.name + " project" : "Deacon")
                    + " group instead of this space's tabs");
            group.setOnClickListener(v -> toggleStrip(chipSpace));
            tabStrip.addView(group);
        }
        if (active != null && activeMoved) {
            final View activePill = active;
            tabScroll.post(() -> tabScroll.smoothScrollTo(Math.max(0, activePill.getLeft() - dp(24)), 0));
        }
    }

    /**
     * With a Deacon, a PM or a PM's worker focused, the strip shows that role
     * context instead of the space's tabs (FleetTree.stripFor): each item is
     * an exact terminal, possibly from another space, so a tap focuses that
     * terminal and there is no close, move or rename. It is rebuilt from the
     * live view on every refresh, so it never shows a stale group (what the
     * desktop's projection handshake guards against). False: no role context.
     */
    private void showRoleStrip(Connection current, FleetTree.Strip strip, String space) {
        StringBuilder signature = new StringBuilder("role:").append(strip.mode).append('\t').append(space).append('\n');
        for (FleetTree.StripItem item : strip.items) {
            signature.append(item.id).append('\t').append(item.label).append('\t').append(item.status)
                    .append('\t').append(item.id.equals(current.paneId)).append('\n');
        }
        if (signature.toString().equals(tabSignature)) return;
        boolean activeMoved = !current.paneId.equals(tabActive);
        tabSignature = signature.toString();
        tabActive = current.paneId;
        tabStrip.removeAllViews();
        View active = null;
        for (FleetTree.StripItem item : strip.items) {
            boolean focused = item.id.equals(current.paneId);
            String label = ("worker".equals(item.role) ? "" : "pm".equals(item.role) ? "PM·" : "Deacon·") + item.label;
            TextView pill = tabPill(label, focused, item.status);
            pill.setTag(focused ? PANE_PILL_ACTIVE : PANE_PILL);
            final Connection target = current;
            pill.setOnClickListener(v -> { if (!focused) focusRemotePane(target, item.id); });
            pill.setContentDescription((strip.mode.equals("pm") ? strip.name + ": " : "") + label
                    + (item.status.isEmpty() ? "" : ", " + item.status) + (focused ? ", current" : ""));
            tabStrip.addView(pill);
            if (focused) active = pill;
        }
        // To this space's own tabs (where +, close, move and rename are).
        TextView tabsChip = tabPill("⊞ Tabs", false, "");
        tabsChip.setContentDescription("Show this space's own tabs");
        tabsChip.setOnClickListener(v -> toggleStrip(space));
        tabStrip.addView(tabsChip);
        if (active != null && activeMoved) {
            final View activePill = active;
            tabScroll.post(() -> tabScroll.smoothScrollTo(Math.max(0, activePill.getLeft() - dp(24)), 0));
        }
    }

    private FleetTree.StripChoice stripChoiceState;

    /** The group/tabs choice per physical space, kept across refreshes and launches. */
    private FleetTree.StripChoice stripChoice() {
        if (stripChoiceState == null) {
            java.util.Set<String> saved = new java.util.HashSet<>(getSharedPreferences("terminal-display", MODE_PRIVATE)
                    .getStringSet("strip_tabs", java.util.Collections.emptySet()));
            stripChoiceState = new FleetTree.StripChoice(saved);
        }
        return stripChoiceState;
    }

    /** Switches this space's strip between the role group and its own tabs; focus is untouched. */
    private void toggleStrip(String space) {
        stripChoice().toggle(currentHostId, space);
        getSharedPreferences("terminal-display", MODE_PRIVATE).edit()
                .putStringSet("strip_tabs", new java.util.HashSet<>(stripChoice().physical)).apply();
        tabSignature = "";
        refreshTabStrip();
    }

    private void updateCrumb(Connection current, String space) {
        if (crumb == null || linkStatus != null || crumbNote != null) return;
        String title = "", status = "", cwd = "", spaceLabel = "", role = "", tabId = "", ownTitle = "";
        JSONArray panes = current.panes;
        for (int i = 0; panes != null && i < panes.length(); i++) {
            JSONObject pane = panes.optJSONObject(i);
            if (pane == null || !current.paneId.equals(pane.optString("id"))) continue;
            title = RailTree.fallbackTitle(HomeActivity.cleanTitle(
                    chromeTitle(pane.optString("title", ""), current.paneId), current.paneId), pane);
            status = pane.optString("status", "");
            cwd = pane.optString("cwd", "");
            role = FleetTree.role(pane.optString("role", ""));
            tabId = pane.optString("tab_id", "");
            ownTitle = HomeActivity.cleanTitle(chromeTitle(pane.optString("title", ""), current.paneId), current.paneId);
        }
        // The pane's role badge, as the desktop's pane header: PM·t19, worker·<task>, Deacon·t6k.
        // A worker's task is its own title, else a tab name that is not generic
        // (several workers can share a tab like "general"), else its id.
        String tabName = "";
        JSONArray tabs = current.tabs;
        for (int i = 0; tabs != null && i < tabs.length(); i++) {
            JSONObject tab = tabs.optJSONObject(i);
            if (tab != null && tabId.equals(tab.optString("id")) && space.equals(tab.optString("workspace_id"))) {
                tabName = tab.optString("label", "");
            }
        }
        String badge = FleetTree.roleBadge(role, current.paneId, FleetTree.workerTask(ownTitle, tabName, current.paneId));
        String named = RailTree.labels(current.workspaces, current.tabs, panes).get(space);
        if (named != null) spaceLabel = chromeTitle(named, space);
        String repo = RailTree.repoOfPath(cwd);
        java.util.List<String> parts = new java.util.ArrayList<>();
        parts.add(current.hostName);
        if (repo != null && !repo.equals(spaceLabel)) parts.add(repo);
        if (!spaceLabel.isEmpty()) parts.add(spaceLabel);
        if (!title.isEmpty()) parts.add(RailTree.withoutSpace(title, spaceLabel));
        android.text.SpannableStringBuilder text = new android.text.SpannableStringBuilder();
        if (!badge.isEmpty()) {
            text.append(badge, new android.text.style.StyleSpan(android.graphics.Typeface.BOLD), 0);
            text.setSpan(new android.text.style.ForegroundColorSpan(chrome.primary), 0, text.length(), 0);
            text.append("   ");
        }
        text.append(android.text.TextUtils.join("  ›  ", parts));
        String why = "blocked".equals(status) ? "needs you: waiting for your answer"
                : "error".equals(status) ? "stopped with an error" : "";
        if (!why.isEmpty()) {
            text.append("   ");
            int start = text.length();
            text.append("● ").append(why);
            text.setSpan(new android.text.style.ForegroundColorSpan(
                    "error".equals(status) ? chrome.danger : chrome.warning), start, text.length(), 0);
        }
        crumb.setVisibility(View.VISIBLE);
        crumb.setTextColor(chrome.onSurfaceVariant);
        if (!text.toString().equals(crumbText)) {
            crumbText = text.toString();
            crumb.setText(text);
            crumb.setContentDescription("Showing " + crumbText + ". Show it in the rail");
        }
    }

    /** Scrolls the rail so the window on screen is in view (once per window, or when asked). */
    private void revealSelectedRow(boolean always) {
        Connection current;
        synchronized (stateLock) { current = connection; }
        if (current == null || sidebarScroll == null || !sidebarOpen) return;
        String pane = current.paneId;
        if (!always && pane.equals(revealedPane)) return;
        RailPaneRow row = railPanes.get("pane:" + currentHostId + "/" + pane);
        if (row == null || row.row.getParent() == null) return;
        revealedPane = pane;
        sidebarScroll.post(() -> {
            int top = row.row.getTop();
            int view = sidebarScroll.getHeight();
            int scroll = sidebarScroll.getScrollY();
            if (always || top < scroll || top + row.row.getHeight() > scroll + view) {
                sidebarScroll.smoothScrollTo(0, Math.max(0, top - view / 3));
            }
        });
    }

    /** One tab: the active one raised with an outline, the rest quiet on black. */
    /** The tab strip's pull: every saved machine, the current one marked; a tap switches to it. */
    private void showMachines() {
        if (destroyed || isFinishing() || savedHosts.isEmpty()) return;
        ActionSheet sheet = new ActionSheet(this, chrome, "Switch machine");
        for (SavedRemoteHost host : savedHosts) {
            boolean here = host.id.equals(currentHostId);
            sheet.add(here ? host.name + "  · here" : host.name, () -> { if (!here) switchHost(host); });
        }
        sheet.show();
    }

    /** Tags on the strip's pane pills, so a swipe on the chip steps through them. */
    private static final String PANE_PILL = "pane-pill", PANE_PILL_ACTIVE = "pane-pill-active";

    /**
     * Shows the pill {@code step} places from the current one in the tab strip
     * (the chip's sideways swipe). It stops at either end: other machines are
     * in the rail.
     */
    private void switchPane(int step) {
        if (tabStrip == null || step == 0) return;
        int count = tabStrip.getChildCount();
        int active = -1;
        for (int i = 0; i < count; i++) {
            if (PANE_PILL_ACTIVE.equals(tabStrip.getChildAt(i).getTag())) active = i;
        }
        if (active < 0) return;
        for (int i = active + Integer.signum(step); i >= 0 && i < count; i += Integer.signum(step)) {
            View pill = tabStrip.getChildAt(i);
            if (PANE_PILL.equals(pill.getTag())) {
                pill.callOnClick();
                return;
            }
        }
    }

    private TextView tabPill(String label, boolean active, String status) {
        TextView pill = new TextView(this);
        android.text.SpannableStringBuilder text = new android.text.SpannableStringBuilder();
        if ("working".equals(status) || "blocked".equals(status) || "done".equals(status) || "error".equals(status)) {
            int color = "error".equals(status) ? chrome.danger : chrome.status(status);
            text.append("● ", new android.text.style.ForegroundColorSpan(color), 0);
        }
        text.append(label.length() > 28 ? label.substring(0, 27) + "…" : label);
        pill.setText(text);
        pill.setTextSize(12);
        pill.setSingleLine(true);
        pill.setTextColor(active ? chrome.onSurface : chrome.onSurfaceVariant);
        pill.setGravity(android.view.Gravity.CENTER_VERTICAL);
        pill.setPadding(dp(9), 0, dp(9), 0);
        android.graphics.drawable.GradientDrawable shape = new android.graphics.drawable.GradientDrawable();
        shape.setCornerRadius(dp(6));
        shape.setColor(active ? chrome.control : chrome.background);
        if (active) shape.setStroke(dp(1), chrome.outline);
        pill.setBackground(new android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(chrome.selected), shape,
                new android.graphics.drawable.ColorDrawable(0xffffffff)));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-2, dp(24));
        params.setMarginEnd(dp(3));
        pill.setLayoutParams(params);
        return pill;
    }

    private void showTabActions(Connection target, String space, String tab, String label) {
        java.util.List<String[]> spaces = new java.util.ArrayList<>();
        JSONArray all = target.workspaces;
        for (int i = 0; all != null && i < all.length(); i++) {
            JSONObject item = all.optJSONObject(i);
            if (item != null && !space.equals(item.optString("id"))) {
                spaces.add(new String[] {item.optString("id"), chromeTitle(item.optString("label"), item.optString("id"))});
            }
        }
        new ActionSheet(this, chrome, label)
                .add("Rename…", () -> ActionSheet.prompt(this, chrome, "Rename window", label,
                        name -> runEdit(target, "Rename", "op", "tab.rename", "space", space, "tab", tab, "name", name)))
                .add("Split: new shell beside it", () -> runEdit(target, "Split", "op", "tab.split",
                        "space", space, "tab", tab))
                .add("Move to space…", () -> {
                    ActionSheet pick = new ActionSheet(this, chrome, "Move " + label + " to");
                    for (String[] other : spaces) {
                        pick.add(other[1], () -> runEdit(target, "Move", "op", "tab.space", "space", space,
                                "tab", tab, "to", other[0]));
                    }
                    pick.show();
                })
                .danger("Close window", () -> runEdit(target, "Close " + label, "op", "tab.close",
                        "space", space, "tab", tab))
                .show();
    }

    /**
     * A tab pill's touch: a tap switches (the click listener), a sideways swipe
     * scrolls the strip, a hold opens the actions, and a hold then drag lifts
     * the pill and drops it at a new position.
     */
    private final class TabDrag implements View.OnTouchListener {
        private final View pill;
        private final Runnable menu;
        private final java.util.function.IntConsumer moved;
        private final int slop = android.view.ViewConfiguration.get(GpuiTerminalActivity.this).getScaledTouchSlop();
        private float downX, downY;
        private boolean held, dragging, cancelled;
        private final Runnable hold = new Runnable() {
            @Override public void run() {
                held = true;
                pill.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
                pill.animate().scaleX(1.08f).scaleY(1.08f).setDuration(90).start();
                pill.setElevation(dp(6));
                pill.getParent().requestDisallowInterceptTouchEvent(true);
            }
        };

        TabDrag(View pill, Runnable menu, java.util.function.IntConsumer moved) {
            this.pill = pill;
            this.menu = menu;
            this.moved = moved;
        }

        @Override public boolean onTouch(View view, MotionEvent event) {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX = event.getRawX();
                    downY = event.getRawY();
                    held = dragging = cancelled = false;
                    pill.postDelayed(hold, android.view.ViewConfiguration.getLongPressTimeout());
                    return false; // the pressed state and click stay the view's own
                case MotionEvent.ACTION_MOVE: {
                    float dx = event.getRawX() - downX;
                    if (!held) {
                        if (Math.abs(dx) > slop || Math.abs(event.getRawY() - downY) > slop) {
                            pill.removeCallbacks(hold);
                            cancelled = true;
                        }
                        return false;
                    }
                    if (!dragging && Math.abs(dx) > slop) dragging = true;
                    if (dragging) pill.setTranslationX(dx);
                    return true;
                }
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL: {
                    pill.removeCallbacks(hold);
                    if (!held) return false;
                    boolean up = event.getActionMasked() == MotionEvent.ACTION_UP;
                    int target = dropIndex();
                    pill.animate().scaleX(1f).scaleY(1f).translationX(0).setDuration(120).start();
                    pill.setElevation(0);
                    pill.setPressed(false);
                    if (up && dragging) {
                        int from = tabStrip.indexOfChild(pill);
                        if (target != from && target >= 0) {
                            // Show the new order at once; herdr-core's answer confirms it.
                            tabStrip.removeView(pill);
                            tabStrip.addView(pill, target);
                            tabSignature = "";
                            moved.accept(target);
                        }
                    } else if (up) {
                        menu.run();
                    }
                    held = dragging = false;
                    return true;
                }
                default:
                    return false;
            }
        }

        /** Where the lifted pill's centre now falls among the other tabs ("+" excluded). */
        private int dropIndex() {
            float centre = pill.getLeft() + pill.getTranslationX() + pill.getWidth() / 2f;
            int tabs = tabStrip.getChildCount() - 1;
            int index = 0;
            for (int i = 0; i < tabs; i++) {
                View other = tabStrip.getChildAt(i);
                if (other == pill) continue;
                if (centre > other.getLeft() + other.getWidth() / 2f) index++;
            }
            return Math.min(index, Math.max(0, tabs - 1));
        }
    }

    /** "+": what a new tab in this space should run. */
    private void showNewMenu(View anchor) {
        Connection target;
        synchronized (stateLock) { target = connection; }
        if (target == null) return;
        android.widget.PopupMenu menu = new android.widget.PopupMenu(
                new android.view.ContextThemeWrapper(this, chrome.popupTheme()), anchor);
        menu.getMenu().add(0, 1, 0, "Claude");
        menu.getMenu().add(0, 2, 1, "Codex");
        menu.getMenu().add(0, 3, 2, "Shell");
        menu.setOnMenuItemClickListener(item -> {
            String action = item.getItemId() == 1 ? "agent.claude" : item.getItemId() == 2 ? "agent.codex" : "tab.new";
            runSessionAction(target, action, "New " + item.getTitle());
            return true;
        });
        menu.show();
    }

    private void refreshSidebar(boolean force) {
        refreshTabStrip();
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
        // One machine at a time, as the mockup's machine picker has it: the
        // rail is the machine on screen; its header (name ▾) picks another.
        boolean picker = currentHostId != null && current != null && hosts.size() > 1
                && hosts.get(0).id.equals(currentHostId);
        if (picker) hosts = hosts.subList(0, 1);
        for (int h = 0; h < hosts.size(); h++) {
            SavedRemoteHost host = hosts.get(h);
            boolean live = host.id.equals(currentHostId);
            HostSample sample = hostSamples.get(host.id);
            JSONArray workspaces = live && current != null ? current.workspaces
                    : sample == null ? null : sample.workspaces;
            JSONArray panes = live && current != null ? current.panes
                    : sample == null ? null : sample.panes;
            JSONArray tabs = live && current != null ? current.tabs
                    : sample == null ? null : sample.tabs;
            addHostRows(host, live, sample, workspaces, tabs, panes, current, order, keep);
            if (h + 1 < hosts.size()) order.add(railRule(host.id, keep));
        }
        pruneRailRows(keep);
        commitRailOrder(order);
        // The window on screen (opened from Home, a notification or the rail)
        // is always visible in the rail, selected.
        revealSelectedRow(false);
    }

    private void addHostRows(SavedRemoteHost host, boolean live, HostSample sample, JSONArray workspaces,
            JSONArray tabs,            JSONArray panes, Connection current, java.util.List<View> order, java.util.Set<String> keep) {
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
        // The same tree as Home (FleetTree via FleetPanes): this machine's
        // Deacon, each project once under its PM, every terminal in the row of
        // its own checkout, unopened worktrees once per repo, leftovers kept.
        java.util.List<FleetTree.Member> members = FleetPanes.members(workspaces, tabs, panes, FleetTree.Member::new);
        railSpaceOf.clear();
        for (FleetTree.Member m : members) railSpaceOf.put(m.id, m.space);
        if (live && current != null) maybeRailGitLook(host, current, members);
        FleetTree.Fleet fleet = FleetTree.build(members, FleetPanes.LOOKS.get(host.id));
        FleetTree.PmTree tree = FleetTree.pmTree(fleet);
        String selectedPane = live && current != null ? current.paneId : "";
        // PM rows start folded. Nothing opens at startup; moving to another
        // window later opens its PM row once, and a fold after that is kept.
        FleetTree.Project focusedProject = FleetTree.projectOf(fleet, selectedPane);
        java.util.List<String> reveal = new java.util.ArrayList<>();
        if (focusedProject != null) reveal.add(railProjectKey(host, focusedProject));
        if (railFolds().focusSeen(selectedPane, reveal)) {
            getSharedPreferences("terminal-display", MODE_PRIVATE).edit()
                    .putStringSet("rail_collapsed", new java.util.HashSet<>(collapsedRepos())).apply();
        }
        updateRailFleetButtons(header, host, live, current, fleet);
        FleetTree.Member deacon = fleet.deacon;
        // The sidebar is the Deacon and one row per PM (FleetTree.pmTree, as
        // Home): workers wait behind their PM's chevron with their checkout as
        // a second line; there are no repo, worktree or space rows.
        if (deacon != null) {
            String hKey = railFold(railKey(host, "helpers"), FleetTree.Fold.DEACON);
            boolean hFolded = railFolded(hKey) && !containsMember(tree.helpers, selectedPane);
            String said = FleetPanes.displayTitle(deacon);
            String sub = deacon.id + (FleetTree.genericName(said) || said.equals(deacon.id) ? "" : " · " + said);
            railAgent(host, live, current, deacon, "Deacon", sub, 0, tree.helpers.isEmpty() ? null : hKey, hFolded,
                    hFolded && tree.deaconBlocked(), selectedPane, order, keep);
            String pending = railDeaconPending.getOrDefault(host.id, "");
            if (deacon.id.equals(pending)) {
                railNote(host, "deacon-state", "Starting the Deacon…", chrome.onSurfaceVariant, 1, null, order, keep);
            } else if (!FleetTree.running(deacon)) {
                railNote(host, "deacon-state", "Claude isn't running in the Deacon's terminal. Tap to open it.",
                        chrome.danger, 1, () -> openRailPane(host, live, current, deacon.id), order, keep);
            }
            if (!hFolded) {
                for (FleetTree.Member helper : tree.helpers) {
                    railAgent(host, live, current, helper, null, "", 1, null, false, false, selectedPane, order, keep);
                }
            }
            // New project is the computer row's +; while it is asking, say so here.
            if (railProjectBusy.contains(host.id)) {
                railNote(host, "new-project", "Asking the Deacon…", chrome.onSurfaceVariant, 1, null, order, keep);
            }
        } else {
            String why = !live ? "No Deacon. Switch to " + host.name + " to start one."
                    : railDeaconBusy.contains(host.id) ? "Starting the Deacon…"
                    : "◆ No Deacon on this computer. Tap to start one.";
            railNote(host, "deacon-state", why, chrome.onSurfaceVariant, 0,
                    live && !railDeaconBusy.contains(host.id) ? () -> railStartDeacon(host, current) : null,
                    order, keep);
        }
        for (FleetTree.PmRow pm : tree.pms) railPm(host, live, current, pm, selectedPane, order, keep);
        if (!tree.others.isEmpty() || !tree.otherCatalogs.isEmpty()) {
            String key = railFold(railKey(host, "others"), FleetTree.Fold.OTHERS);
            // The focused terminal is never hidden behind a fold it starts in.
            boolean folded = railFolded(key) && !containsMember(tree.otherMembers, selectedPane);
            String meta = (tree.othersBlocked() ? "! " : "") + tree.otherMembers.size();
            RailWorkspaceRow othersRow = railHeader(host, live, current, "group:others", "Other terminals", meta, key,
                    folded, 0, "", order, keep);
            if (live && !tree.otherCatalogs.isEmpty()) {
                othersRow.worktrees = () -> showRailCatalogs(host, current, tree.otherCatalogs);
            }
            if (!folded) {
                for (FleetTree.AgentNode node : tree.others) railAgentTree(host, live, current, node, 1, selectedPane, order, keep);
                railCatalogRows(host, live, current, tree.otherCatalogs, order, keep);
            }
        }
    }

    /** One PM row (its project's name, the PM's checkout below), then its workers when unfolded. */
    private void railPm(SavedRemoteHost host, boolean live, Connection current,
            FleetTree.PmRow pm, String selectedPane, java.util.List<View> order, java.util.Set<String> keep) {
        String key = railProjectKey(host, pm.project);
        boolean folded = railFolds().folded(key);
        String foldKey = pm.workers.isEmpty() && pm.catalogs.isEmpty() ? null : key;
        if (pm.pm == null) {
            // Labelled workers whose PM is gone: the project still has its row.
            java.util.List<FleetTree.Member> workers = FleetTree.flatten(pm.workers);
            FleetTree.Member target = FleetTree.projectTarget(pm);
            String meta = (pm.blocked() ? "! " : "") + workers.size();
            RailWorkspaceRow header = railHeader(host, live, current, "proj:" + pm.name(), pm.name() + " · no PM", meta,
                    foldKey, folded, 0, target == null ? "" : target.id, order, keep);
            if (live && !pm.catalogs.isEmpty()) header.worktrees = () -> showRailCatalogs(host, current, pm.catalogs);
        } else {
            String sub = pm.pm.id + (pm.where.isEmpty() ? "" : " · " + pm.where);
            RailPaneRow row = railAgent(host, live, current, pm.pm, pm.name(), sub, 0, foldKey, folded,
                    folded && pm.blocked(), selectedPane, order, keep);
            if (row != null && live && !pm.catalogs.isEmpty()) {
                row.worktrees = () -> showRailCatalogs(host, current, pm.catalogs);
            }
        }
        if (folded) return;
        for (FleetTree.AgentNode node : pm.workers) railAgentTree(host, live, current, node, 1, selectedPane, order, keep);
        railCatalogRows(host, live, current, pm.catalogs, order, keep);
    }

    /** Visible catalogs, behind the same fold as the group's terminals. */
    private void railCatalogRows(SavedRemoteHost host, boolean live, Connection current,
            java.util.List<FleetTree.Project> catalogs, java.util.List<View> order, java.util.Set<String> keep) {
        for (FleetTree.Project project : catalogs) {
            RailWorkspaceRow row = railHeader(host, live, current, "catalog:" + project.repo,
                    FleetTree.repoName(project.repo), FleetTree.unopenedLabel(project), null, false, 1, "", order, keep);
            row.action = () -> showRailWorktrees(host, live ? current : null, project, 0);
            row.row.setContentDescription("Show " + FleetTree.unopenedLabel(project) + " in " + row.label.getText());
        }
    }

    /** A row's "Worktrees…": straight to the one repo's catalog, else a choice of repo first. */
    private void showRailCatalogs(SavedRemoteHost host, Connection current,
            java.util.List<FleetTree.Project> catalogs) {
        if (catalogs.size() == 1) { showRailWorktrees(host, current, catalogs.get(0), 0); return; }
        ActionSheet sheet = new ActionSheet(this, chrome, "Worktrees with no space");
        for (FleetTree.Project project : catalogs) {
            sheet.add(FleetTree.repoName(project.repo) + " · " + project.closed.size() + "…",
                    () -> showRailWorktrees(host, current, project, 0));
        }
        sheet.show();
    }

    /** "Worktrees…" for one repo: its worktrees no terminal has open, a page at a time. */
    private void showRailWorktrees(SavedRemoteHost host, Connection current,
            FleetTree.Project project, int pages) {
        int total = project.closed.size();
        ActionSheet sheet = new ActionSheet(this, chrome, project.name + " · " + total
                + (total == 1 ? " worktree" : " worktrees") + " with no space"
                + (project.closedIncomplete ? " (git's list was cut short)" : ""));
        int shown = FleetTree.shownClosed(project, pages);
        for (int i = 0; i < shown; i++) {
            FleetTree.GitWorktree wt = project.closed.get(i);
            String name = wt.branch.isEmpty() ? FleetTree.lastSegment(wt.path) + " (detached)" : wt.branch;
            sheet.add("○ " + name, () -> openRailWorktree(host, current, wt));
        }
        if (total > shown) {
            sheet.add("+" + (total - shown) + " more", () -> showRailWorktrees(host, current, project, pages + 1));
        }
        sheet.show();
    }

    private FleetTree.Folds railFoldState;

    /**
     * A rail row's stored fold key under FleetTree's one policy (shared with
     * Home): a row folded by default is stored as "<key>:open" while opened.
     */
    private static String railFold(String key, FleetTree.Fold fold) {
        return FleetTree.foldedByDefault(fold) ? key + ":open" : key;
    }

    private boolean railFolded(String foldKey) {
        return foldKey.endsWith(":open") != collapsedRepos().contains(foldKey);
    }

    /** Project folds (opened projects live in the rail's fold set as ":open" keys). */
    private FleetTree.Folds railFolds() {
        if (railFoldState == null) railFoldState = new FleetTree.Folds(collapsedRepos());
        return railFoldState;
    }

    private static String railProjectKey(SavedRemoteHost host, FleetTree.Project project) {
        return railKey(host, "p/" + project.name) + ":open";
    }

    private static boolean containsMember(java.util.List<FleetTree.Member> list, String id) {
        for (FleetTree.Member m : list) if (m.id.equals(id)) return true;
        return false;
    }

    /** Rail fold keys: per machine, apart from the old repo/space keys. */
    private static String railKey(SavedRemoteHost host, String what) {
        return host.id + "/fleet/" + what;
    }

    private void railAgentTree(SavedRemoteHost host, boolean live, Connection current, FleetTree.AgentNode node,
            int depth, String selectedPane, java.util.List<View> order, java.util.Set<String> keep) {
        railAgent(host, live, current, node.member, null, node.where, depth, null, false, false, selectedPane, order, keep);
        for (FleetTree.AgentNode kid : node.children) railAgentTree(host, live, current, kid, depth + 1, selectedPane, order, keep);
    }

    /**
     * One agent row: tapping focuses that exact terminal. {@code lead} names a
     * Deacon or PM row (its title, in medium weight, with a chevron slot that
     * folds {@code foldKey}); {@code sub} is the second line, usually the
     * checkout; {@code bubbled} marks blocked work folded under it. Returns
     * null when this terminal already has a row in this pass.
     */
    private RailPaneRow railAgent(SavedRemoteHost host, boolean live, Connection current, FleetTree.Member m,
            String lead, String sub, int depth, String foldKey, boolean folded, boolean bubbled, String selectedPane,
            java.util.List<View> order, java.util.Set<String> keep) {
        String paneKey = "pane:" + host.id + "/" + m.id;
        if (!keep.add(paneKey)) return null; // each terminal once
        RailPaneRow row = railPanes.get(paneKey);
        if (row == null) {
            row = createRailPaneRow();
            railPanes.put(paneKey, row);
        }
        String title = lead != null ? lead : chromeTitle(FleetPanes.displayTitle(m), m.id);
        row.foldKey = foldKey;
        row.worktrees = null;
        String mark = lead == null ? null : foldKey == null ? "" : folded ? "▸" : "▾";
        row.mark.setVisibility(mark == null ? View.GONE : View.VISIBLE);
        if (mark != null && !mark.contentEquals(row.mark.getText())) row.mark.setText(mark);
        // The chevron is the row's only fold control, so a screen reader gets it as its own button.
        String under = "deacon".equals(m.role) && "Deacon".equals(lead) ? "the Deacon's helpers" : "workers under " + lead;
        String foldSays = foldKey == null ? null : (folded ? "Show " : "Hide ") + under;
        row.mark.setImportantForAccessibility(foldSays == null ? View.IMPORTANT_FOR_ACCESSIBILITY_NO
                : View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        row.mark.setFocusable(foldSays != null);
        row.mark.setContentDescription(foldSays);
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            row.mark.setStateDescription(foldSays == null ? null : folded ? "collapsed" : "expanded");
        }
        if (!sub.contentEquals(row.sub.getText())) row.sub.setText(sub);
        row.sub.setVisibility(sub.isEmpty() ? View.GONE : View.VISIBLE);
        if (row.lead != (lead != null)) {
            row.lead = lead != null;
            row.title.setTypeface(railMono(row.lead));
        }
        updateRailPaneRow(row, host, live, current, m.id, title, m.status.isEmpty() ? "unknown" : m.status,
                m.id.equals(selectedPane), bubbled);
        // One step in is one chevron slot, so a worker's mark sits under its PM's.
        int pad = dp(4 + 18 * depth);
        if (row.row.getPaddingLeft() != pad) row.row.setPadding(pad, dp(2), dp(6), dp(2));
        order.add(row.row);
        return row;
    }

    /** A project opens its terminal; groups fold; catalogs open their list. The chevron only folds. */
    private RailWorkspaceRow railHeader(SavedRemoteHost host, boolean live, Connection current, String id, String label,
            String meta, String foldKey, boolean folded, int depth, String firstPane,
            java.util.List<View> order, java.util.Set<String> keep) {
        String key = "fleet:" + host.id + "/" + id;
        keep.add(key);
        RailWorkspaceRow row = railWorkspaces.get(key);
        if (row == null) {
            row = createRailWorkspaceRow();
            railWorkspaces.put(key, row);
        }
        row.host = host;
        row.live = live;
        row.connection = current;
        row.firstPane = firstPane;
        // Virtual by default: the scoped menu (open its terminal, copy details).
        row.physicalSpace = null;
        row.worktrees = null;
        row.action = null;
        row.workspaceId = railSpaceOf.getOrDefault(firstPane, "");
        row.foldKey = foldKey;
        row.folded = folded;
        if (!label.contentEquals(row.label.getText())) row.label.setText(label);
        if (!meta.contentEquals(row.meta.getText())) row.meta.setText(meta);
        row.meta.setTextColor(meta.startsWith("!") ? chrome.danger : chrome.onSurfaceVariant);
        String mark = foldKey == null ? "▪" : folded ? "▸" : "▾";
        if (!mark.contentEquals(row.mark.getText())) row.mark.setText(mark);
        int pad = dp(2 + 12 * depth);
        if (row.row.getPaddingLeft() != pad) {
            row.row.setPadding(pad, dp(2), dp(4), dp(2));
            boolean top = depth == 0;
            row.label.setTextSize(top ? 13 : 12);
            row.label.setTypeface(railMono(top));
            row.label.setTextColor(top ? chrome.onSurface : chrome.primary);
        }
        String described = (!firstPane.isEmpty() || foldKey == null ? "Open " : folded ? "Unfold " : "Fold ")
                + label + ", " + meta;
        if (!described.contentEquals(row.row.getContentDescription() == null ? "" : row.row.getContentDescription())) {
            row.row.setContentDescription(described);
        }
        order.add(row.row);
        return row;
    }

    /** A one-line rail action or state, reused by key. */
    private void railNote(SavedRemoteHost host, String id, String text, int color, int depth, Runnable action,
            java.util.List<View> order, java.util.Set<String> keep) {
        String key = "fleetnote:" + host.id + "/" + id;
        keep.add(key);
        TextView note = railNotes.get(key);
        if (note == null) {
            note = railText("", 12, color);
            note.setSingleLine(true);
            note.setEllipsize(android.text.TextUtils.TruncateAt.END);
            note.setMinHeight(dp(28));
            note.setGravity(android.view.Gravity.CENTER_VERTICAL);
            note.setBackground(railBackground(false));
            railNotes.put(key, note);
        }
        if (!text.contentEquals(note.getText())) {
            note.setText(text);
            note.setContentDescription(text);
        }
        note.setTextColor(color);
        note.setPadding(dp(10 + 12 * depth), dp(1), dp(6), dp(1));
        note.setOnClickListener(action == null ? null : v -> action.run());
        note.setClickable(action != null);
        order.add(note);
    }

    // ── Rail Fleet actions (explicit taps only) ─────────────────────────

    /** Machines with a New project (or other Deacon message) in flight: one send per tap. */
    private final java.util.Set<String> railProjectBusy = java.util.concurrent.ConcurrentHashMap.newKeySet();
    /** A worktree open already queued by a tap, scoped to its computer. */
    private final java.util.Set<String> railWorktreeOpening = new java.util.HashSet<>();
    /** Each terminal's space, for the rail's header long-press menus. */
    private final java.util.Map<String, String> railSpaceOf = new java.util.HashMap<>();
    private final java.util.Map<String, String> railDeaconPending = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.Map<String, Long> railDeaconPendingAt = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.Set<String> railDeaconBusy = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final java.util.Set<String> railGitBusy = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final java.util.Map<String, java.util.Set<String>> railGitAsked = new java.util.concurrent.ConcurrentHashMap<>();

    private void openRailPane(SavedRemoteHost host, boolean live, Connection current, String paneId) {
        if (live && current != null) focusRemotePane(current, paneId);
        else switchHost(host, paneId);
        closeRailDrawer();
    }

    /** The machine header's ◆ (its Deacon) and + (New project, or Start Deacon first). */
    private void updateRailFleetButtons(RailHostRow header, SavedRemoteHost host, boolean live, Connection current,
            FleetTree.Fleet fleet) {
        int agents = 0, blocked = 0;
        for (FleetTree.Member m : fleet.all) {
            if (!m.isAgent()) continue;
            agents++;
            if ("blocked".equals(m.status)) blocked++;
        }
        String counts = agents + (blocked > 0 ? " · " + blocked + "!" : "");
        String word = header.hostState == null || header.hostState.isEmpty() ? counts : header.hostState + " · " + counts;
        if (!word.contentEquals(header.state.getText())) header.state.setText(word);
        header.state.setTextColor(blocked > 0 ? chrome.danger : header.live ? chrome.onSurface : chrome.onSurfaceVariant);
        FleetTree.Member deacon = fleet.deacon;
        boolean busy = railDeaconBusy.contains(host.id) || railDeaconPending.containsKey(host.id);
        FleetTree.DeaconAction action = FleetTree.deaconAction(live, deacon, busy);
        String deaconText = action == FleetTree.DeaconAction.OPEN ? "◆" : action == FleetTree.DeaconAction.START
                ? "Start Deacon" : action == FleetTree.DeaconAction.STARTING ? "Starting…" : "";
        if (!deaconText.contentEquals(header.deacon.getText())) header.deacon.setText(deaconText);
        header.deacon.setVisibility(deaconText.isEmpty() ? View.GONE : View.VISIBLE);
        header.deacon.setEnabled(action == FleetTree.DeaconAction.OPEN || action == FleetTree.DeaconAction.START);
        header.deacon.setContentDescription(deacon != null ? "Open the Deacon on " + host.name : "Start the Deacon on " + host.name);
        header.deacon.setOnClickListener(v -> {
            if (deacon != null) openRailPane(host, live, current, deacon.id);
            else if (live && !busy) railStartDeacon(host, current);
        });
        header.plus.setVisibility(live || deacon != null ? View.VISIBLE : View.GONE);
        boolean asking = railProjectBusy.contains(host.id);
        boolean canAsk = !asking && (deacon != null || live && !busy);
        header.plus.setEnabled(canAsk);
        header.plus.setAlpha(canAsk ? 1f : 0.4f);
        header.plus.setContentDescription(deacon != null ? "New project on " + host.name + ": ask its Deacon"
                : "New project on " + host.name + ": start its Deacon first");
        header.plus.setOnClickListener(v -> {
            if (railProjectBusy.contains(host.id)) return;
            if (deacon != null) railNewProject(host, live, current, deacon);
            else if (live && !busy) railStartDeacon(host, current);
        });
    }

    /** A Fleet header's menu: only its own terminal and details, never the shared space's edits. */
    private void showFleetHeaderActions(RailWorkspaceRow holder) {
        String label = holder.label.getText().toString();
        ActionSheet sheet = new ActionSheet(this, chrome, label);
        String pane = holder.firstPane;
        if (pane != null && !pane.isEmpty()) {
            sheet.add("Open", () -> {
                if (holder.live && holder.connection != null) focusRemotePane(holder.connection, pane);
                else switchHost(holder.host, pane);
                closeRailDrawer();
            });
            String cwd = holder.live ? paneCwd(holder.connection, pane) : "";
            if (!cwd.isEmpty()) sheet.add("Copy path", () -> ActionSheet.copy(this, "Path", cwd));
            sheet.add("Copy terminal id", () -> ActionSheet.copy(this, "Terminal id", pane));
        }
        Runnable worktrees = holder.worktrees;
        if (worktrees != null) sheet.add("Worktrees…", worktrees);
        sheet.add("Copy name", () -> ActionSheet.copy(this, "Name", label));
        sheet.show();
    }

    /** Runs one session action off the main thread; ok gets the reply on the main thread. */
    private void railAction(Connection target, String json, String label,
            java.util.function.Consumer<JSONObject> ok, Runnable failed) {
        actionWorker.execute(() -> {
            JSONObject result = null;
            String failure = null;
            try {
                result = new JSONObject(RemoteNative.sessionAction(target.remoteHandle, json));
                if (!result.optBoolean("queued", false)) failure = result.optString("error", "it did not answer");
            } catch (Exception | LinkageError error) {
                failure = "the computer did not answer";
            }
            final JSONObject reply = result;
            final String reason = failure;
            main.post(() -> {
                if (destroyed) return;
                if (reason == null) { ok.accept(reply); return; }
                if (failed != null) failed.run();
                note("Could not " + label + ": " + reason);
            });
        });
    }

    private void railStartDeacon(SavedRemoteHost host, Connection current) {
        if (current == null || !railDeaconBusy.add(host.id)) return;
        refreshSidebar(true);
        String json;
        try {
            json = new JSONObject().put("op", "deacon.start").put("name", host.name).toString();
        } catch (JSONException impossible) { return; }
        railAction(current, json, "start the Deacon", reply -> {
            railDeaconBusy.remove(host.id);
            String terminal = reply.optString("terminal", "");
            if (!reply.optString("layout_error", "").isEmpty()) {
                note("The Deacon started, but " + reply.optString("layout_error"));
            }
            if (reply.optBoolean("started", false) && !terminal.isEmpty()) {
                // Starting… until claude is seen running there (checkRailDeacon).
                railDeaconPending.put(host.id, terminal);
                railDeaconPendingAt.put(host.id, System.currentTimeMillis());
            } else if (!terminal.isEmpty()) {
                focusRemotePane(current, terminal);
            }
            refreshSidebar(true);
        }, () -> { railDeaconBusy.remove(host.id); refreshSidebar(true); });
    }

    /** Confirms a launched Deacon from the live view, as Home does; never relaunches. */
    private void checkRailDeacon(SavedRemoteHost host, Connection current, java.util.List<FleetTree.Member> members) {
        String pending = railDeaconPending.get(host.id);
        if (pending == null) return;
        FleetTree.Member launched = null;
        for (FleetTree.Member m : members) if (m.id.equals(pending)) launched = m;
        long elapsed = System.currentTimeMillis() - railDeaconPendingAt.getOrDefault(host.id, 0L);
        if (launched == null && elapsed < 5_000) return;
        FleetTree.StartHealth health = FleetTree.startHealth(launched, elapsed, 45_000);
        if (health == FleetTree.StartHealth.WAITING) return;
        railDeaconPending.remove(host.id);
        if (health == FleetTree.StartHealth.FAILED) {
            note(launched == null
                    ? "The Deacon on " + host.name + " stopped right away. Check that claude is installed and signed in there."
                    : "The Deacon's terminal opened on " + host.name + ", but claude didn't start in it.");
        }
        if (launched != null) main.post(() -> focusRemotePane(current, pending));
    }

    private void railNewProject(SavedRemoteHost host, boolean live, Connection current, FleetTree.Member deacon) {
        if (!live || current == null) { switchHost(host, deacon.id); closeRailDrawer(); return; }
        railSendToDeacon(host, current, deacon, FleetTree.newProjectPrompt(host.name));
    }

    private void railSendToDeacon(SavedRemoteHost host, Connection current, FleetTree.Member deacon, String text) {
        String json;
        try {
            json = new JSONObject().put("op", "pane.send").put("terminal", deacon.id).put("text", text).toString();
        } catch (JSONException impossible) { return; }
        // One send per tap: a second tap while this one is in flight does nothing.
        if (!railProjectBusy.add(host.id)) return;
        refreshSidebar(true);
        railAction(current, json, "reach the Deacon", reply -> {
            railProjectBusy.remove(host.id);
            refreshSidebar(true);
            focusRemotePane(current, deacon.id);
            closeRailDrawer();
        }, () -> { railProjectBusy.remove(host.id); refreshSidebar(true); });
    }

    /** One tap reuses a live terminal in the worktree, or opens a plain shell there. */
    private void openRailWorktree(SavedRemoteHost host, Connection current, FleetTree.GitWorktree wt) {
        synchronized (stateLock) {
            if (current == null || connection != current || !host.id.equals(currentHostId)) {
                note("Connect to " + host.name + " before opening a worktree.");
                return;
            }
        }
        java.util.List<FleetTree.Member> now = FleetPanes.members(current.workspaces, current.tabs, current.panes,
                FleetTree.Member::new);
        FleetTree.Member there = FleetTree.memberAt(now, wt.path);
        if (there != null) { focusRemotePane(current, there.id); closeRailDrawer(); return; }
        String key = host.id + "\n" + wt.path;
        if (!railWorktreeOpening.add(key)) {
            note("Opening " + FleetTree.lastSegment(wt.path) + "…");
            return;
        }
        String json;
        try {
            json = new JSONObject().put("op", "space.open").put("folder", wt.path).toString();
        } catch (JSONException impossible) { railWorktreeOpening.remove(key); return; }
        note("Opening " + FleetTree.lastSegment(wt.path) + "…");
        railAction(current, json, "open " + FleetTree.lastSegment(wt.path), reply -> {
            railWorktreeOpening.remove(key);
            synchronized (stateLock) { if (connection != current) return; }
            String terminal = reply.optString("terminal", "");
            if (terminal.isEmpty()) { note("The computer did not return a terminal for this worktree."); return; }
            focusRemotePane(current, terminal);
            closeRailDrawer();
        }, () -> railWorktreeOpening.remove(key));
    }

    /** The live machine's git look, shared with Home; same pacing rules. */
    private void maybeRailGitLook(SavedRemoteHost host, Connection current, java.util.List<FleetTree.Member> members) {
        checkRailDeacon(host, current, members);
        java.util.Set<String> paths = FleetPanes.gitPaths(members);
        if (paths.isEmpty()) return;
        long now = System.currentTimeMillis();
        java.util.Set<String> asked = railGitAsked.getOrDefault(host.id, java.util.Collections.emptySet());
        boolean fresh = !asked.containsAll(paths);
        long at = FleetPanes.LOOKED_AT.getOrDefault(host.id, 0L);
        if (!FleetTree.gitDue(true, false, railGitBusy.contains(host.id), fresh, now, at, 300_000, 15_000)) return;
        if (!railGitBusy.add(host.id)) return;
        FleetPanes.LOOKED_AT.put(host.id, now);
        String json;
        try {
            json = new JSONObject().put("op", "git.look").put("paths", new JSONArray(paths)).toString();
        } catch (JSONException impossible) { railGitBusy.remove(host.id); return; }
        actionWorker.execute(() -> {
            FleetTree.GitLook look = null;
            try {
                JSONObject result = new JSONObject(RemoteNative.sessionAction(current.remoteHandle, json));
                if (result.optBoolean("queued", false)) {
                    look = FleetTree.parseGitLook(result.optString("text", ""), FleetTree.PER_REPO);
                    look.allTruncated = result.optBoolean("truncated", false);
                }
            } catch (Exception | LinkageError ignored) {
                look = null;
            }
            final FleetTree.GitLook answer = look;
            main.post(() -> {
                railGitBusy.remove(host.id);
                if (destroyed || answer == null) return;
                FleetPanes.LOOKS.put(host.id, answer);
                railGitAsked.put(host.id, paths);
                refreshSidebar(true);
            });
        });
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
        prune(railWorkspaces, keep, holder -> holder.row);
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
        holder.row.setBackground(railBackground(false));
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
        holder.deacon = railText("", 11, chrome.primary);
        holder.plus = railText("+", 15, chrome.primary);
        for (TextView button : new TextView[] {holder.deacon, holder.plus}) {
            button.setGravity(android.view.Gravity.CENTER);
            button.setMinWidth(dp(28));
            button.setMinHeight(dp(28));
            button.setPadding(dp(6), 0, dp(6), 0);
            button.setBackground(railBackground(false));
            LinearLayout.LayoutParams buttonParams = new LinearLayout.LayoutParams(-2, -2);
            buttonParams.leftMargin = dp(4);
            holder.row.addView(button, buttonParams);
        }
        holder.host = host;
        holder.row.setContentDescription("Connect to " + host.name);
        holder.row.setOnClickListener(view -> {
            if (holder.live && savedHosts.size() > 1) {
                showMachinePicker();
                return;
            }
            switchHost(holder.host);
            closeRailDrawer();
        });
        holder.row.setOnLongClickListener(view -> {
            new ActionSheet(this, chrome, holder.host.name)
                    .add(holder.live ? "Reconnect" : "Connect", () -> {
                        if (holder.live) reconnectCurrentHost(); else switchHost(holder.host);
                    })
                    .add("Hosts and keys…", () -> startActivity(new android.content.Intent(this,
                            RemoteHostsActivity.class).putExtra("skip_reopen", true)))
                    .show();
            return true;
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
            holder.row.setBackground(railBackground(live));
            holder.row.setSelected(live);
            // Secondary text is only guaranteed on the bare surface.
            holder.state.setTextColor(live ? chrome.onSurface : chrome.onSurfaceVariant);
        }
        String shown = live && savedHosts.size() > 1 ? host.name + "  ▾" : host.name;
        if (!shown.contentEquals(holder.name.getText())) holder.name.setText(shown);
        int count = workspaces == null ? 0 : workspaces.length();
        if (count != holder.counted) {
            // Workspaces are enumerated below; keep the count for screen readers.
            holder.counted = count;
            holder.row.setContentDescription(hostDescription(holder));
        }
    }

    /**
     * A space header: a full-width button (pressed ripple, chevron, window
     * count) that opens the space, its windows as the tabs on top.
     */
    private RailWorkspaceRow createRailWorkspaceRow() {
        RailWorkspaceRow holder = new RailWorkspaceRow();
        holder.row = new LinearLayout(this);
        holder.row.setOrientation(LinearLayout.HORIZONTAL);
        holder.row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        holder.row.setMinimumHeight(dp(28));
        holder.row.setSaveFromParentEnabled(false);
        holder.row.setFocusable(true);
        holder.row.setClickable(true);
        holder.mark = railText("", 12, chrome.onSurfaceVariant);
        // A separate fold target, even when the row opens a project terminal.
        holder.mark.setGravity(android.view.Gravity.CENTER);
        holder.mark.setMinWidth(dp(22));
        holder.mark.setMinHeight(dp(28));
        holder.mark.setBackground(railBackground(false));
        holder.mark.setOnClickListener(v -> {
            if (holder.foldKey != null) toggleRepo(holder.foldKey);
            else holder.row.performClick();
        });
        LinearLayout.LayoutParams markParams = new LinearLayout.LayoutParams(-2, -2);
        markParams.setMarginEnd(dp(3));
        holder.row.addView(holder.mark, markParams);
        holder.label = railText("", 12, chrome.primary);
        holder.label.setTypeface(railMono(false));
        holder.row.addView(holder.label, new LinearLayout.LayoutParams(0, -2, 1));
        holder.meta = railText("", 11, chrome.onSurfaceVariant);
        holder.meta.setTypeface(railMono(false));
        holder.meta.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        LinearLayout.LayoutParams metaParams = new LinearLayout.LayoutParams(-2, -2);
        metaParams.leftMargin = dp(6);
        holder.row.addView(holder.meta, metaParams);
        TextView chevron = railText("›", 15, chrome.onSurfaceVariant);
        chevron.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        LinearLayout.LayoutParams chevronParams = new LinearLayout.LayoutParams(-2, -2);
        chevronParams.leftMargin = dp(4);
        holder.row.addView(chevron, chevronParams);
        holder.row.setBackground(railBackground(false));
        holder.row.setOnLongClickListener(view -> {
            // A Fleet header (project, checkout slice, group) is not a physical
            // space: its menu never renames, moves or adds to the shared space.
            if (holder.physicalSpace == null) showFleetHeaderActions(holder);
            else showSpaceActions(holder);
            return true;
        });
        holder.row.setOnClickListener(view -> {
            if (holder.action != null) holder.action.run();
            else if (!holder.firstPane.isEmpty()) openRailPane(holder.host, holder.live, holder.connection, holder.firstPane);
            else if (holder.foldKey != null) toggleRepo(holder.foldKey);
        });
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = dp(2);
        holder.row.setLayoutParams(params);
        return holder;
    }

    private void updateRailWorkspaceRow(RailWorkspaceRow holder, SavedRemoteHost host, String label,
            boolean live, Connection connection, String workspaceId, String firstPane,
            boolean holdsSelection, boolean nested, int windows, int waiting, int working) {
        holder.host = host;
        holder.live = live;
        holder.connection = connection;
        holder.workspaceId = workspaceId;
        // Opening a space lands on the pane already selected in it, if any.
        holder.firstPane = holdsSelection && connection != null ? connection.paneId : firstPane;
        if (!label.contentEquals(holder.label.getText())) holder.label.setText(label);
        if (holder.nested != Boolean.valueOf(nested)) {
            holder.nested = nested;
            // Worktrees sit one step in, under their repo.
            // Repo rows: bold, bright; worktrees: lighter and one step in.
            holder.row.setPadding(dp(nested ? 16 : 2), dp(2), dp(4), dp(2));
            holder.label.setTextSize(nested ? 12 : 13);
            holder.label.setTypeface(android.graphics.Typeface.create(
                    nested ? "sans-serif" : "sans-serif-medium",
                    nested ? android.graphics.Typeface.NORMAL : android.graphics.Typeface.BOLD));
            holder.label.setTextColor(Boolean.TRUE.equals(holder.selected) || !nested
                    ? chrome.onSurface : chrome.primary);
        }
        StringBuilder meta = new StringBuilder();
        if (waiting > 0) meta.append(waiting).append("! ");
        if (working > 0) meta.append(working).append("▶ ");
        meta.append(windows).append(windows == 1 ? " win" : " wins");
        if (!meta.toString().contentEquals(holder.meta.getText())) holder.meta.setText(meta);
        if (holder.selected != Boolean.valueOf(holdsSelection)) {
            holder.selected = holdsSelection;
            holder.row.setBackground(railBackground(holdsSelection));
            holder.row.setSelected(holdsSelection);
            holder.label.setTextColor(holdsSelection || !Boolean.TRUE.equals(holder.nested)
                    ? chrome.onSurface : chrome.primary);
            holder.mark.setTextColor(holdsSelection ? chrome.primary : chrome.onSurfaceVariant);
        }
        String verb = holder.foldKey == null ? "Open " : holder.folded ? "Unfold " : "Fold ";
        String described = verb + (nested ? "worktree " : "space ") + label + ", " + windows
                + (windows == 1 ? " window" : " windows") + (holdsSelection ? ", current" : "");
        if (!described.contentEquals(holder.row.getContentDescription() == null
                ? "" : holder.row.getContentDescription())) {
            holder.row.setContentDescription(described);
        }
    }

    /** Machines, each with how many of its agents wait on Wolf; picking one switches to it. */
    private void showMachinePicker() {
        ActionSheet sheet = new ActionSheet(this, chrome, "Machine");
        for (SavedRemoteHost host : savedHosts) {
            boolean live = host.id.equals(currentHostId);
            JSONArray panes, workspaces, tabs;
            Connection current;
            synchronized (stateLock) { current = connection; }
            boolean connected;
            if (live) {
                panes = current == null ? null : current.panes;
                workspaces = current == null ? null : current.workspaces;
                tabs = current == null ? null : current.tabs;
                connected = current != null && current.ready;
            } else {
                HostSample sample = hostSamples.get(host.id);
                panes = sample == null ? null : sample.panes;
                workspaces = sample == null ? null : sample.workspaces;
                tabs = sample == null ? null : sample.tabs;
                connected = sample != null && sample.connected;
            }
            // A pick focuses that computer's canonical live Deacon, in its exact
            // terminal; with none, the rail shows Start Deacon; offline (a cached
            // list), nothing is focused or offered. Nothing ever starts.
            java.util.List<FleetTree.Member> listed = FleetPanes.members(workspaces, tabs, panes, FleetTree.Member::new);
            FleetTree.Pick pick = FleetTree.pick(connected, listed);
            FleetTree.Member deacon = pick == FleetTree.Pick.FOCUS ? FleetTree.pickTarget(listed) : null;
            int waiting = 0;
            for (int i = 0; panes != null && i < panes.length(); i++) {
                JSONObject pane = panes.optJSONObject(i);
                String status = pane == null ? "" : pane.optString("status");
                if ("blocked".equals(status) || "error".equals(status)) waiting++;
            }
            String label = (live ? "● " : "○ ") + host.name
                    + (waiting > 0 ? "   " + waiting + " need" + (waiting == 1 ? "s" : "") + " you" : "")
                    + (live ? "   (here)" : "") + (pick == FleetTree.Pick.OFFLINE ? "   offline" : "");
            sheet.add(label, () -> {
                if (pick == FleetTree.Pick.OFFLINE) {
                    // Unavailable: no focus attempt, no Start Deacon; just show it.
                    if (!live) switchHost(host);
                } else if (deacon != null) {
                    if (live && current != null) focusRemotePane(current, deacon.id);
                    else switchHost(host, deacon.id);
                    closeRailDrawer();
                } else if (!live) {
                    // The rail then shows that computer with its Start Deacon row.
                    switchHost(host);
                }
            });
        }
        sheet.show();
    }

    private java.util.Set<String> railCollapsed;

    /** Collapsed repos, per machine and repo, kept across launches. */
    private java.util.Set<String> collapsedRepos() {
        if (railCollapsed == null) {
            railCollapsed = new java.util.HashSet<>(getSharedPreferences("terminal-display", MODE_PRIVATE)
                    .getStringSet("rail_collapsed", java.util.Collections.emptySet()));
        }
        return railCollapsed;
    }

    private void toggleRepo(String key) {
        java.util.Set<String> collapsed = collapsedRepos();
        if (!collapsed.remove(key)) collapsed.add(key);
        getSharedPreferences("terminal-display", MODE_PRIVATE).edit()
                .putStringSet("rail_collapsed", new java.util.HashSet<>(collapsed)).apply();
        refreshSidebar(true);
    }

    private String paneCwd(Connection target, String paneId) {
        JSONArray panes = target == null ? null : target.panes;
        for (int i = 0; panes != null && i < panes.length(); i++) {
            JSONObject pane = panes.optJSONObject(i);
            if (pane != null && paneId.equals(pane.optString("id"))) return pane.optString("cwd", "");
        }
        return "";
    }

    private void showSpaceActions(RailWorkspaceRow holder) {
        String label = holder.label.getText().toString();
        if (!holder.live) {
            new ActionSheet(this, chrome, label)
                    .add("Open on " + holder.host.name, () -> switchHost(holder.host, holder.firstPane))
                    .show();
            return;
        }
        showSpaceSheet(holder.connection, holder.workspaceId, label, holder.firstPane);
    }

    /** A space's display name from the session's workspace list, or "" when it is not listed. */
    private static String spaceName(Connection target, String space) {
        JSONArray all = target == null ? null : target.workspaces;
        for (int i = 0; all != null && i < all.length(); i++) {
            JSONObject item = all.optJSONObject(i);
            if (item != null && space.equals(item.optString("id"))) return chromeTitle(item.optString("label"), space);
        }
        return "";
    }

    /**
     * One physical space's own menu (new terminal, rename, move): reached
     * from a terminal's long press, since the sidebar has no space rows.
     */
    private void showSpaceSheet(Connection target, String space, String label, String firstPane) {
        int index = -1;
        JSONArray all = target.workspaces;
        for (int i = 0; all != null && i < all.length(); i++) {
            JSONObject item = all.optJSONObject(i);
            if (item != null && space.equals(item.optString("id"))) index = i;
        }
        final int at = index;
        ActionSheet sheet = new ActionSheet(this, chrome, label)
                .add("Open", () -> { focusRemotePane(target, firstPane); closeRailDrawer(); })
                .add("New Claude here", () -> runEdit(target, "New Claude", "op", "space.new", "space", space, "kind", "claude"))
                .add("New Codex here", () -> runEdit(target, "New Codex", "op", "space.new", "space", space, "kind", "codex"))
                .add("New shell here", () -> runEdit(target, "New shell", "op", "space.new", "space", space, "kind", "shell"))
                .add("Rename…", () -> ActionSheet.prompt(this, chrome, "Rename space", label,
                        name -> runEdit(target, "Rename", "op", "space.rename", "space", space, "name", name)));
        if (at > 0) sheet.add("Move up", () -> runEdit(target, "Move", "op", "space.move", "space", space, "index", at - 1));
        if (at >= 0 && all != null && at < all.length() - 1) {
            sheet.add("Move down", () -> runEdit(target, "Move", "op", "space.move", "space", space, "index", at + 1));
        }
        String cwd = paneCwd(target, firstPane);
        if (!cwd.isEmpty()) sheet.add("Copy path", () -> ActionSheet.copy(this, "Path", cwd));
        sheet.show();
    }

    private void showPaneActions(RailPaneRow holder) {
        String title = holder.title.getText().toString();
        if (!holder.live) {
            new ActionSheet(this, chrome, title)
                    .add("Open on " + holder.host.name, () -> switchHost(holder.host, holder.paneId))
                    .show();
            return;
        }
        Connection target = holder.connection;
        String pane = holder.paneId;
        String cwd = paneCwd(target, pane);
        ActionSheet sheet = new ActionSheet(this, chrome, title)
                .add("Open", () -> { focusRemotePane(target, pane); closeRailDrawer(); })
                .add("Interrupt (stop key)", () -> runEdit(target, "Interrupt", "op", "pane.interrupt", "terminal", pane));
        Runnable worktrees = holder.worktrees;
        if (worktrees != null) sheet.add("Worktrees…", worktrees);
        String space = railSpaceOf.getOrDefault(pane, "");
        String spaceLabel = space.isEmpty() ? "" : spaceName(target, space);
        if (!spaceLabel.isEmpty()) {
            sheet.add("Space “" + spaceLabel + "”…", () -> showSpaceSheet(target, space, spaceLabel, pane));
        }
        if (!cwd.isEmpty()) sheet.add("Copy path", () -> ActionSheet.copy(this, "Path", cwd));
        sheet.add("Copy terminal id", () -> ActionSheet.copy(this, "Terminal id", pane))
                .danger("Close terminal", () -> runEdit(target, "Close " + title, "op", "pane.kill", "terminal", pane))
                .show();
    }

    /**
     * Rail rows, boxy as the desktop sidebar: a square ripple for the press;
     * selected, a square fill with a 2 dp accent bar at the row's start.
     */
    private android.graphics.drawable.Drawable railBackground(boolean selected) {
        android.graphics.drawable.ColorDrawable mask = new android.graphics.drawable.ColorDrawable(0xffffffff);
        int pressed = selected ? chrome.outline : chrome.selected;
        android.graphics.drawable.LayerDrawable fill = null;
        if (selected) {
            fill = new android.graphics.drawable.LayerDrawable(new android.graphics.drawable.Drawable[] {
                    new android.graphics.drawable.ColorDrawable(chrome.selected),
                    new android.graphics.drawable.ColorDrawable(chrome.primary)});
            fill.setLayerGravity(1, android.view.Gravity.START | android.view.Gravity.FILL_VERTICAL);
            fill.setLayerWidth(1, dp(2));
            fill.setLayerInset(1, 0, dp(4), 0, dp(4));
        }
        return new android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(pressed), fill, mask);
    }

    /** The rail's chrome face: monospace, medium for a Deacon or PM row's title. */
    private static android.graphics.Typeface railMono(boolean medium) {
        return android.graphics.Typeface.create(android.graphics.Typeface.MONOSPACE, medium ? 500 : 400, false);
    }

    private RailPaneRow createRailPaneRow() {
        RailPaneRow holder = new RailPaneRow();
        holder.row = new LinearLayout(this);
        holder.row.setOrientation(LinearLayout.HORIZONTAL);
        holder.row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        holder.row.setMinimumHeight(dp(28));
        holder.row.setPadding(dp(12), dp(2), dp(6), dp(2));
        holder.row.setSaveFromParentEnabled(false);
        holder.row.setBackground(railBackground(false));
        // A Deacon or PM row's chevron: it folds, the rest of the row focuses the agent.
        holder.mark = railText("", 12, chrome.onSurfaceVariant);
        holder.mark.setTypeface(railMono(false));
        holder.mark.setGravity(android.view.Gravity.CENTER);
        holder.mark.setMinHeight(dp(28));
        holder.mark.setBackground(railBackground(false));
        holder.mark.setVisibility(View.GONE);
        holder.mark.setOnClickListener(v -> {
            if (holder.foldKey != null) toggleRepo(holder.foldKey);
            else holder.row.performClick();
        });
        holder.row.addView(holder.mark, new LinearLayout.LayoutParams(dp(18), -2));
        // Status as a small square mark (no ovals in the boxy rail).
        holder.dot = new View(this);
        holder.dot.setBackgroundColor(chrome.onSurfaceVariant);
        LinearLayout.LayoutParams dotParams = new LinearLayout.LayoutParams(dp(6), dp(6));
        dotParams.setMarginEnd(dp(8));
        holder.row.addView(holder.dot, dotParams);
        LinearLayout lines = new LinearLayout(this);
        lines.setOrientation(LinearLayout.VERTICAL);
        holder.title = railText("", 13, chrome.onSurface);
        holder.title.setTypeface(railMono(false));
        lines.addView(holder.title);
        // The second line: a terminal's checkout (worktree or branch), never a row of its own.
        holder.sub = railText("", 11, chrome.onSurfaceVariant);
        holder.sub.setTypeface(railMono(false));
        holder.sub.setVisibility(View.GONE);
        lines.addView(holder.sub);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, -2, 1);
        params.leftMargin = dp(1);
        holder.row.addView(lines, params);
        // A colour alone cannot say what an agent is doing; name the state.
        holder.state = railText("", 11, chrome.onSurfaceVariant);
        holder.state.setTypeface(railMono(false));
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
        holder.row.setOnLongClickListener(view -> { showPaneActions(holder); return true; });
        return holder;
    }

    private void updateRailPaneRow(RailPaneRow holder, SavedRemoteHost host, boolean live,
            Connection connection, String paneId, String title, String status, boolean selected,
            boolean bubbled) {
        holder.host = host;
        holder.live = live;
        holder.connection = connection;
        holder.paneId = paneId;
        if (!status.equals(holder.status)) {
            boolean pulse = holder.status != null;
            holder.status = status;
            holder.dot.setBackgroundColor(chrome.status(status));
            if (pulse) pulseRailDot(holder.dot);
        }
        if (holder.selected != selected) {
            holder.selected = selected;
            holder.row.setBackground(railBackground(selected));
            holder.row.setSelected(selected);
        }
        if (!title.contentEquals(holder.title.getText())) holder.title.setText(title);
        String word = status == null || status.isEmpty() || "unknown".equals(status) ? "" : status;
        // Blocked is a red "!" on the agent's own row, and on its PM's or the
        // Deacon's row while the blocked work is folded under it.
        boolean alarm = "blocked".equals(status) || "error".equals(status) || bubbled;
        String state = alarm ? (word.isEmpty() ? "!" : "! " + word) : word;
        if (!state.contentEquals(holder.state.getText())) holder.state.setText(state);
        holder.state.setTextColor(alarm ? chrome.danger : selected ? chrome.onSurface : chrome.onSurfaceVariant);
        CharSequence sub = holder.sub.getVisibility() == View.VISIBLE ? holder.sub.getText() : "";
        // A row description overrides its children, so the state has to be said here.
        String described = "Focus pane " + title + (sub.length() == 0 ? "" : ", " + sub)
                + (word.isEmpty() ? "" : ", " + word)
                + (bubbled ? ", something under it needs you" : "")
                + (holder.foldKey == null ? "" : ", long press for more");
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
        params.topMargin = dp(6);
        params.bottomMargin = dp(4);
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
        /** The machine's Deacon (◆, or Start Deacon) and New project (+). */
        TextView deacon, plus;
        String hostState = "";
        SavedRemoteHost host;
        String status;
        boolean live;
        int counted = -1;
    }

    private static final class RailWorkspaceRow {
        LinearLayout row;
        TextView mark, label, meta;
        SavedRemoteHost host;
        Connection connection;
        String workspaceId = "";
        String firstPane = "";
        boolean live;
        Boolean nested, selected;
        /** What this header folds (a repo's worktrees, or its own windows), or null. */
        String foldKey;
        boolean folded;
        /** The one physical space this row wholly is, or null for a Fleet header (scoped menu). */
        String physicalSpace = "";
        /** "Worktrees…" on its menu (a no-PM project, Other terminals), or null. */
        Runnable worktrees;
        /** Primary tap for a catalog row; project and fold rows resolve from their own fields. */
        Runnable action;
    }

    private static final class RailPaneRow {
        LinearLayout row;
        View dot;
        TextView mark, title, sub;
        TextView state;
        /** What a Deacon or PM row's chevron folds, or null. */
        String foldKey;
        /** A PM row's long-press "Worktrees…", or null. */
        Runnable worktrees;
        boolean lead;
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
            note("Switch panes from Remote hosts for this connection");
            return;
        }
        try {
            switchAskedAt = SystemClock.uptimeMillis();
            JSONObject result = new JSONObject(RemoteNative.focus(target.remoteHandle, paneId));
            if (!result.optBoolean("queued", false)) throw new IllegalStateException("Pane focus was not queued");
            rememberHostPane(paneId);
            synchronized (stateLock) {
                if (!current(target)) return;
                target.paneId = paneId;
                target.pendingPane = paneId;
                target.pendingUntil = SystemClock.uptimeMillis() + 4000;
                target.fastUntil = SystemClock.uptimeMillis() + 1500;
                target.nextRemotePollAt = 0;
                publish(target, "Switching pane…");
            }
            refreshSidebar(false);
        } catch (Exception | LinkageError failure) {
            // The only failure left is a session that has ended (the switch
            // itself is a slot that cannot overflow): reconnect, landing on
            // the asked-for window, instead of telling Wolf to reopen.
            android.util.Log.w("herdr-switch", "switch to " + paneId + " failed: " + failure);
            rememberHostPane(paneId);
            reconnectCurrentHost();
        }
    }

    /** Tabs and workspaces use the daemon's own switch requests. */
    private void focusChrome(Connection target, String kind, String id) {
        if (id.isEmpty() || id.length() > 128
                || !id.matches("[A-Za-z0-9_./:-]+")) return;
        // Name the pane here, as the daemon would pick it, so the switch shows
        // at once instead of after the daemon's answer.
        String pane = "";
        String space = "";
        JSONArray panes = target.panes;
        for (int i = 0; "tab".equals(kind) && panes != null && i < panes.length(); i++) {
            JSONObject item = panes.optJSONObject(i);
            if (item != null && item.optString("id").equals(target.paneId)) space = item.optString("workspace_id");
        }
        for (int i = 0; panes != null && i < panes.length() && pane.isEmpty(); i++) {
            JSONObject item = panes.optJSONObject(i);
            if (item == null) continue;
            boolean match = "tab".equals(kind)
                    ? id.equals(item.optString("tab_id")) && space.equals(item.optString("workspace_id"))
                    : id.equals(item.optString("workspace_id"));
            if (match) pane = item.optString("id");
        }
        if (!pane.isEmpty()) {
            focusRemotePane(target, pane);
            return;
        }
        if (target.remoteHandle <= 0) {
            note("Switch workspaces from Remote hosts for this connection");
            return;
        }
        try {
            switchAskedAt = SystemClock.uptimeMillis();
            JSONObject result = new JSONObject("tab".equals(kind)
                    ? RemoteNative.focusTab(target.remoteHandle, id)
                    : RemoteNative.focusWorkspace(target.remoteHandle, id));
            if (!result.optBoolean("queued", false)) throw new IllegalStateException("Switch was not queued");
            synchronized (stateLock) {
                if (current(target)) publish(target, "Switching " + kind + "…");
            }
        } catch (Exception | LinkageError failure) {
            android.util.Log.w("herdr-switch", "switch to " + kind + " " + id + " failed: " + failure);
            reconnectCurrentHost();
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

    /** The composer's way in: the same queue and checks as the toolbar keys. */
    private ComposerBar.Host composerHost() {
        return new ComposerBar.Host() {
            @Override public boolean queue(String commandJson) { return queueComposerInput(commandJson); }

            @Override public boolean send(String text) { return sendComposerMessage(text); }

            @Override public void enterKey() {
                queueComposerInput("{\"type\":\"keys\",\"keys\":[\"enter\"]}");
            }

            @Override public void hideKeyboard() {
                allowNextKeyboardShow = false;
                GpuiTerminalActivity.super.gpuiHideKeyboard(imeSession);
                if (Build.VERSION.SDK_INT >= 30 && getWindow().getInsetsController() != null) {
                    getWindow().getInsetsController().hide(WindowInsets.Type.ime());
                }
            }

            @Override public void notice(String message) {
                note(message);
            }

            @Override public void showQueue(java.util.List<Outbox.Message> messages) { queueSheet(messages); }

            @Override public void setGhost(String json) {
                if (!hostStarted) return;
                try {
                    nativeSetGhost(json);
                } catch (IllegalArgumentException refused) {
                    // A ghost the renderer refuses is not drawn; the draft itself is kept.
                }
            }

            @Override public void switchPane(int step) { GpuiTerminalActivity.this.switchPane(step); }

            @Override public View surface() { return terminalSurface; }

            @Override public void rawKeyboard() {
                Connection target;
                synchronized (stateLock) { target = connection; }
                if (target == null || terminalSurface == null) return;
                terminalSurface.requestFocus();
                nativeShowKeyboard(target.generation);
            }

            @Override public void withMicrophone(Runnable granted) {
                if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
                        == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    granted.run();
                    return;
                }
                microphoneGranted = granted;
                requestPermissions(new String[]{android.Manifest.permission.RECORD_AUDIO}, MICROPHONE_REQUEST);
            }
        };
    }

    private static final int MICROPHONE_REQUEST = 41;
    /** What the composer asked to do once the microphone is allowed; one request at a time. */
    private Runnable microphoneGranted;

    @Override public void onRequestPermissionsResult(int request, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(request, permissions, results);
        if (request != MICROPHONE_REQUEST) return;
        Runnable granted = microphoneGranted;
        microphoneGranted = null;
        if (destroyed || granted == null) return;
        if (results.length > 0 && results[0] == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            granted.run();
        } else {
            note("Dictation needs the microphone");
        }
    }

    /**
     * A composer message: typed into the pane at once when the link is up and
     * nothing of the pane's is still waiting, otherwise kept in the outbox,
     * which sends it in order when the link returns. False keeps the draft.
     */
    private boolean sendComposerMessage(String text) {
        String host;
        String pane;
        boolean live;
        synchronized (stateLock) {
            Connection target = connection;
            host = target == null ? currentHostId : target.hostId;
            pane = target == null ? "" : target.paneId;
            live = target != null && target.ready && !target.isClosed();
        }
        boolean queueable = host != null && !pane.isEmpty();
        // A message waiting on a dictation goes through the outbox, which holds it until filled.
        boolean awaits = DictationToken.in(text);
        if (live && !awaits && !(queueable && outbox.outbox().holds(host, pane))) {
            String command;
            try {
                command = GpuiTerminalProtocol.composeCommand(text);
            } catch (JSONException | IllegalArgumentException invalid) {
                note("Message is too long to send in one piece");
                return false;
            }
            return queueComposerInput(command);
        }
        if (!queueable) {
            note("Not connected; the draft is kept");
            return false;
        }
        if (outbox.add(host, pane, text) == null) {
            note(Outbox.sendable(text) ? "Too many messages wait already; the draft is kept"
                    : "This message cannot be sent in one piece; the draft is kept");
            return false;
        }
        return true;
    }

    /**
     * Dictations the desktop transcribed after the fact: into the draft that
     * holds their place, else the queued message that does (it then goes
     * out), else the end of the pane's draft. Never sent unless the user sent
     * the message that holds them.
     */
    private final dev.herdr.android.dictation.PendingDictations.Listener pendingDictations =
            new dev.herdr.android.dictation.PendingDictations.Listener() {
                @Override public void onTranscribed(dev.herdr.android.dictation.PendingClip clip, String text) {
                    if (composer == null) return;
                    if (!composer.placeDictation(clip.id, text) && !outbox.fillDictation(clip.id, text)) {
                        composer.appendDictation(clip.target, text);
                    }
                    Dictation.pending(GpuiTerminalActivity.this).delivered(clip.id);
                }

                @Override public void onChanged() {
                    if (composer != null) composer.setWaiting(Dictation.pending(GpuiTerminalActivity.this).count());
                }
            };

    /** Pane ids in a poll's pane list. */
    private static java.util.Set<String> paneIds(JSONArray panes) {
        java.util.Set<String> ids = new java.util.HashSet<>();
        for (int i = 0; panes != null && i < panes.length(); i++) {
            JSONObject pane = panes.optJSONObject(i);
            String id = pane == null ? "" : pane.optString("id", "");
            if (!id.isEmpty()) ids.add(id);
        }
        return ids;
    }

    /** The outbox changed (any thread): the strip and the status line follow. */
    private final Runnable outboxChanged = () -> main.post(() -> {
        if (destroyed) return;
        refreshQueued();
        showLinkDown();
    });

    /** Counts the outbox's messages for the pane on screen on Enter's badge. Main thread. */
    private void refreshQueued() {
        if (composer == null || outbox == null) return;
        String host;
        String pane;
        synchronized (stateLock) {
            host = connection == null ? currentHostId : connection.hostId;
            pane = connection == null ? "" : connection.paneId;
        }
        composer.showQueued(host == null ? java.util.Collections.emptyList()
                : outbox.outbox().shown(host, pane, System.currentTimeMillis()));
    }

    /** The pane's queued messages, one row each; a row opens its actions. */
    private void queueSheet(java.util.List<Outbox.Message> messages) {
        if (messages.isEmpty()) return;
        String pane;
        synchronized (stateLock) {
            pane = connection == null ? "" : connection.paneId;
        }
        ActionSheet sheet = new ActionSheet(this, chrome, QueuedMessages.title(messages.size()));
        for (Outbox.Message message : messages) {
            sheet.add(QueuedMessages.row(message, pane), () -> queuedActions(message));
        }
        sheet.show();
    }

    /** What can be done with one queued message; an unsure one is resent only from here. */
    private void queuedActions(Outbox.Message message) {
        if (message.state == Outbox.State.SENDING) {
            return;
        }
        ActionSheet sheet = new ActionSheet(this, chrome, QueuedMessages.PROMPT + QueuedMessages.oneLine(message.text));
        if (message.state == Outbox.State.UNSURE) {
            // The core may have typed it: look at the pane before sending it twice.
            sheet.add("Send again (check the pane first)", () -> outbox.resend(message.id));
        }
        sheet.add("Edit as draft", () -> composer.takeBack(outbox.take(message.id)));
        sheet.add("Copy", () -> ActionSheet.copy(this, "Message", message.text));
        sheet.danger("Drop", () -> outbox.take(message.id));
        sheet.show();
    }

    /** False leaves the composer's draft in place: unlike a key, it is never lost. */
    private boolean queueComposerInput(String commandJson) {
        if (displayDialog != null && displayDialog.isShowing()) return false;
        clearControl();
        synchronized (stateLock) {
            Connection target = connection;
            if (target != null && !target.ready && monitor != null && monitor.reconnecting()
                    && !target.paneId.isEmpty()) {
                // Sent once, in order, when the same pane is back.
                if (!pendingInput.add(target.paneId, commandJson)) return false;
                main.post(this::showLinkDown);
                return true;
            }
            if (target == null || target.isClosed() || !target.ready) {
                note("Not connected; the draft is kept");
                return false;
            }
            if (compositionBlocksInput(target)) return false;
            if (nativeSendCommand(target.generation, commandJson)) return true;
            publish(target, "Input queue is full; the draft is kept");
            return false;
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
                // From the edge strip (where Back does not take it: buttons
                // navigation, a swipe just inside the edge) a short pull opens it.
                float needed = touchDownX < dp(40) ? dp(24) : dp(60);
                if (dx > needed && dx > dy * 1.5f) {
                    edgeClaimed = true;
                    showRailOverlay();
                    return true;
                }
            }
            if (edgeClaimed) return true;
            if (!scrolling && Math.abs(event.getY() - touchDownY) > dp(8)) {
                scrolling = true;
                // Dragging the conversation down while typing puts the keyboard away.
                if (event.getY() > touchDownY && composer != null) composer.hideKeyboard();
                // The drag is ours from here: the renderer's press (a link's
                // highlight, a selection) ends now, not at a lift it never sees.
                nativeTouch(MotionEvent.ACTION_CANCEL, 0, new int[] {event.getPointerId(0)},
                        new float[] {touchDownX}, new float[] {touchDownY});
            }
            if (scrolling) {
                long now = SystemClock.uptimeMillis();
                float dy = event.getY() - lastTouchY;
                float dt = Math.max(1, now - lastMoveAt);
                // Smoothed drag velocity in pixels per millisecond.
                scrollVelocity = 0.6f * scrollVelocity + 0.4f * (dy / dt);
                lastMoveAt = now;
                float rowPx = scrollRowPx();
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
                // An agent's pane types into the composer's draft instead.
                boolean composing = composer != null && composer.terminalTapped();
                if (!composing && target != null && target.ready && terminalSurface != null) {
                    final long generation = target.generation;
                    terminalSurface.postDelayed(() -> {
                        // A tapped file link opened the file page instead.
                        if (!destroyed && (filePager == null || !filePager.showingFile())) {
                            nativeShowKeyboard(generation);
                        }
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

    /**
     * Home opened over this terminal (a launcher or notification start) and
     * an agent was picked there: show it here instead of refusing to open a
     * second terminal.
     */
    @Override protected void onNewIntent(android.content.Intent intent) {
        super.onNewIntent(intent);
        String hostId = intent.getStringExtra(EXTRA_FOCUS_HOST);
        String pane = intent.getStringExtra(EXTRA_FOCUS_PANE);
        if (hostId == null || pane == null || destroyed) return;
        Connection target;
        synchronized (stateLock) { target = connection; }
        if (hostId.equals(currentHostId) && target != null && target.remoteHandle > 0) {
            focusRemotePane(target, pane);
            return;
        }
        for (SavedRemoteHost host : savedHosts) {
            if (host.id.equals(hostId)) { switchHost(host, pane); return; }
        }
    }

    static final String EXTRA_FOCUS_HOST = "focus_host", EXTRA_FOCUS_PANE = "focus_pane";

    @Override protected void onResume() {
        super.onResume();
        if (isFinishing() || launch == null || shortcutJson == null || !hostStarted) return;
        hostResumed = true;
        boolean firstOpen = !openedOnce;
        openedOnce = true;
        nativeResumed();
        if (monitor == null && !launch.shell) {
            monitor = new ConnectionMonitor(this, main, this::autoReconnect, this::showLinkDown);
        }
        if (monitor != null) monitor.start();
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
                    // Pausing (a tapped link opening the browser, the app
                    // switcher) closed the session; this terminal used to give
                    // up and close. Reconnect to the same host and pane instead.
                    if (currentHostId != null) {
                        // Opened with no session (Home offline): this launch's pane is the one to
                        // show and to land on; later, the pane last shown.
                        if (firstOpen && launch.paneId != null && !launch.paneId.isEmpty()) {
                            rememberHostPane(currentHostId, launch.paneId);
                        }
                        showSaved(currentHostId, rememberedHostPane(currentHostId));
                        main.post(this::reconnectCurrentHost);
                        return;
                    }
                    android.util.Log.w("herdr-gpui", "Remote session was paused. Reconnect from Remote hosts");
                    finish();
                    return;
                }
                next = new Connection(GENERATIONS.incrementAndGet(), remoteHandle);
                next.hostId = currentHostId;
            } else next = new Connection(GENERATIONS.incrementAndGet(), new CompanionClient(launch.host.profile));
            connection = next;
            nativeSetActive(next.generation, false);
            publish(next, "Connecting…");
            next.poll = worker.scheduleWithFixedDelay(() -> pump(next), 0, COMMAND_POLL_MS, TimeUnit.MILLISECONDS);
        }
    }

    @Override protected void onPause() {
        hostResumed = false;
        // Off screen nothing reconnects; onResume opens the session again.
        if (monitor != null) {
            monitor.stop();
            monitor.succeeded();
        }
        // A reconnect still opening must not attach (and size) a pane while
        // the terminal is off screen: closing it releases any claim, and the
        // attempt then fails quietly; onResume reconnects.
        long opening = reconnectHandle;
        reconnectHandle = 0;
        if (opening > 0) RemoteNative.close(opening);
        // Mirrors are only useful on screen; three SSH sessions polling in the
        // background would spend battery for a rail nobody can see.
        stopHostSampling();
        allowNextKeyboardShow = false;
        oneShotKey = KeyEvent.KEYCODE_UNKNOWN;
        clearControl();
        if (displayDialog != null) displayDialog.dismiss();
        capturedKeys.clear();
        // Coming back reconnects to the pane on screen now, not an older one.
        synchronized (stateLock) {
            if (connection != null && connection.remoteHandle > 0 && currentHostId != null
                    && !connection.paneId.isEmpty()) {
                rememberHostPane(connection.paneId);
            }
        }
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
        if (composer != null) composer.persist();
        if (foldWatcher != null) {
            foldWatcher.stop();
            foldWatcher = null;
        }
        stopConnection();
        super.onStop();
    }

    @Override protected void onDestroy() {
        destroyed = true;
        if (outbox != null) outbox.unlisten(outboxChanged);
        if (composer != null) composer.destroy();
        Dictation.pending(this).setListener(null);
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
            note("Session controls need the SSH workflow");
            return;
        }
        // A raw shell has no Herdr panes or tabs to change.
        String[] labels = target.shell
                ? new String[]{"Copy screen text", "Remote hosts…", "Save screenshot"}
                : new String[]{"New Claude", "New Codex", "New shell", "Close tab", "Scroll to bottom",
                        "Copy screen", "Hosts…", "Save screenshot"};
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
            Runnable choose = () -> {
                sheet.dismiss();
                runSessionChoice(target, shownLabels[index], shownActions[index]);
            };
            // A close ends processes: it asks on this same row, never in a second pop-up.
            if (destructive) {
                String warning = "pane.close".equals(shownActions[i])
                        ? "Tap again: its processes end, no undo"
                        : "Tap again: its panes and processes end, no undo";
                ConfirmInPlace.bind(row, shownLabels[i], warning, choose);
            } else row.setOnClickListener(view -> choose.run());
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
            // Confirmed on its own row (showSessionActions).
            runSessionAction(target, action, label);
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

    /**
     * Off the UI thread: an action waits for herdr-core (a new agent starts,
     * the layout is rewritten), which froze the app for up to 20 seconds.
     */
    private void runSessionAction(Connection target, String action, String label) {
        synchronized (stateLock) {
            if (current(target)) publish(target, label + "…");
        }
        actionWorker.execute(() -> {
            String failure = null;
            try {
                JSONObject result = new JSONObject(RemoteNative.sessionAction(target.remoteHandle, action));
                if (!result.optBoolean("queued", false)) failure = result.optString("error", "not applied");
            } catch (Exception | LinkageError error) {
                failure = "reopen and check the session";
            }
            if (failure == null) return;
            final String reason = failure;
            main.post(() -> {
                if (!destroyed) note("Could not apply " + label + ": " + reason);
            });
        });
    }

    /** A layout edit (see core_session's TabEdit), applied like any session action. */
    private void runEdit(Connection target, String label, Object... fields) {
        try {
            JSONObject edit = new JSONObject();
            for (int i = 0; i + 1 < fields.length; i += 2) edit.put((String) fields[i], fields[i + 1]);
            runSessionAction(target, edit.toString(), label);
        } catch (JSONException impossible) { }
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

    /** Off unless turned on in View options: Herdr posts no notifications by default. */
    static final String AGENT_ALERTS = "agent_alerts";

    /**
     * Typing straight into an agent's terminal: the keyboard leaves out its
     * microphone key, as in the composer, since Herdr draws its own "talk".
     */
    @Override protected String terminalImeOptions() {
        return ComposerBar.privateImeOptions(composer != null && composer.drawsTalk());
    }

    private boolean agentAlertsOn() {
        return getSharedPreferences("terminal-display", MODE_PRIVATE).getBoolean(AGENT_ALERTS, false);
    }

    /** Agent alerts from the daemon, posted on one bounded channel, only when turned on. */
    private void postNotice(String title, String body, String paneId) {
        if (destroyed || isFinishing() || !agentAlertsOn()) return;
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
        } catch (RuntimeException rejected) {
            // A locked device may refuse clipboard writes; the session stays usable.
        }
    }

    /**
     * Rust, after a paint that changed it: where the drawn screen is busy
     * (JSON, see ghost_bridge.rs), so the floating controls stay clear of the
     * caret, ghost text and dialogs. Any thread.
     */
    public void gpuiLayoutHint(long expectedGeneration, String json) {
        // Posted, never run inline: Rust calls this from inside a paint, and the
        // composer answers with ghost text, which must not re-enter that frame.
        main.post(() -> {
            synchronized (stateLock) {
                if (destroyed || connection == null || connection.generation != expectedGeneration) return;
            }
            if (composer != null) composer.layoutHint(json);
        });
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
                note("No app can open this link");
            }
        });
    }

    /** A tapped file path (or OSC 8 file link): shown in the file page, read from the pane's machine. */
    public void gpuiOpenFile(long expectedGeneration, String path, int line) {
        runOnUiThread(() -> {
            String pane;
            synchronized (stateLock) {
                if (destroyed || isFinishing() || connection == null
                        || connection.generation != expectedGeneration || connection.remoteHandle == 0) return;
                pane = connection.paneId;
            }
            if (filePager == null) return;
            filePager.open(pane, path, Math.max(0, line));
            // The file is read, not typed into: the keyboard would only cover it.
            android.view.inputmethod.InputMethodManager input =
                    (android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (input != null && terminalSurface != null) {
                input.hideSoftInputFromWindow(terminalSurface.getWindowToken(), 0);
            }
        });
    }

    /** Blocking, on a worker: core_session's file.read for the file page. */
    private String readFile(String terminal, String path) {
        Connection target;
        synchronized (stateLock) { target = connection; }
        if (target == null || target.remoteHandle == 0) {
            return "{\"queued\":false,\"error\":\"Not connected\"}";
        }
        try {
            JSONObject read = new JSONObject().put("op", "file.read").put("terminal", terminal).put("path", path);
            return RemoteNative.sessionAction(target.remoteHandle, read.toString());
        } catch (JSONException impossible) {
            return "{\"queued\":false}";
        }
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
        // Enter while typing into the composer is its send, whichever view has
        // focus: it must never reach the terminal as a bare Enter instead.
        if (composer != null && composer.typing() && composer.enterKey(event)) return true;
        if (composer != null && composer.ownsKeys()) return super.dispatchKeyEvent(event);
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
                if ("submit".equals(command.type) || "compose".equals(command.type)) {
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

    /** When Enter last went to this pane alone, for the double-Enter "send now". */
    private long lastEnterAt;
    private String lastEnterPane = "";

    /**
     * Enter twice in quick succession, with nothing typed between, sends a
     * message Claude Code has queued while it works right away: its Ctrl+Enter
     * ("ctrl+enter to send now", checked on Claude Code 2.1.319). Codex needs
     * nothing: its Enter already sends while it works. Shells are untouched.
     * The window is the "double_enter_ms" display preference (0 turns it off).
     */
    private String doubleEnter(Connection current, GpuiTerminalProtocol.Command command, String raw) {
        boolean enter = "keys".equals(command.type) && command.keys.size() == 1
                && "enter".equals(command.keys.get(0));
        long now = SystemClock.uptimeMillis();
        if (!enter) {
            lastEnterAt = 0;
            return raw;
        }
        long window = getSharedPreferences("terminal-display", MODE_PRIVATE).getInt("double_enter_ms", 700);
        boolean second = window > 0 && lastEnterAt > 0 && now - lastEnterAt <= window
                && current.paneId.equals(lastEnterPane) && "claude".equals(agentKind(current))
                && "working".equals(paneStatus(current));
        lastEnterPane = current.paneId;
        if (!second) {
            lastEnterAt = now;
            return raw;
        }
        lastEnterAt = 0;
        return "{\"type\":\"keys\",\"keys\":[\"ctrl+enter\"]}";
    }

    private static String paneStatus(Connection current) {
        JSONArray panes = current.panes;
        for (int i = 0; panes != null && i < panes.length(); i++) {
            JSONObject pane = panes.optJSONObject(i);
            if (pane != null && current.paneId.equals(pane.optString("id"))) return pane.optString("status", "");
        }
        return "";
    }

    /** "claude" or "codex" for the pane on screen, from its agent.kind label or title. */
    private static String agentKind(Connection current) {
        JSONArray panes = current.panes;
        for (int i = 0; panes != null && i < panes.length(); i++) {
            JSONObject pane = panes.optJSONObject(i);
            if (pane == null || !current.paneId.equals(pane.optString("id"))) continue;
            String kind = pane.optString("kind", "").toLowerCase(java.util.Locale.ROOT);
            if (kind.contains("claude")) return "claude";
            if (kind.contains("codex")) return "codex";
            return kind;
        }
        return "";
    }

    private void pumpRemote(Connection current) {
        boolean mutation = false;
        try {
            // Input held across a reconnect goes first, in order, once.
            String held = null;
            synchronized (stateLock) {
                if (current(current) && current.ready) {
                    held = pendingInput.next(current.paneId);
                    int discarded = pendingInput.takeDiscarded();
                    if (discarded > 0) {
                        main.post(() -> note(discarded + (discarded == 1 ? " input" : " inputs")
                                + " for another pane not sent"));
                    }
                }
            }
            if (held != null) {
                mutation = true;
                String result = RemoteNative.input(current.remoteHandle, current.paneId, held);
                if (!new JSONObject(result).optBoolean("queued", false)) throw new IOException("Held input was not queued");
                synchronized (stateLock) {
                    pendingInput.sent();
                    if (current(current)) publish(current, "Input queued over SSH");
                }
                current.fastUntil = SystemClock.uptimeMillis() + 1500;
                mutation = false;
            }
            String raw = held != null ? null : nativeTakeCommand(current.generation);
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
                if (!current(current)) return;
                if (!current.ready) {
                    // Taken in the moment the pane stopped being ready: hold it
                    // for the same pane rather than drop it.
                    synchronized (stateLock) {
                        if (!pendingInput.add(current.paneId, raw)) {
                            main.post(() -> note("Input not sent: the terminal is reconnecting"));
                        }
                    }
                    return;
                }
                mutation = true;
                raw = doubleEnter(current, command, raw);
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
                // herdr-core answers a refresh every two seconds; silence well
                // past that is a dead path (TCP would take far longer to say so).
                if (current.ready && monitor != null && monitor.dead(state.optLong("quiet_ms", 0))) {
                    fail(current, "The host stopped answering");
                    return;
                }
                if (state.optBoolean("unchanged", false)) {
                    String quietPhase = state.optString("state", "");
                    String quietPane = state.optString("pane_id", "");
                    boolean smallChange = !quietPhase.equals(current.remotePhase)
                            || !quietPane.equals(current.chromePane)
                            || !state.optString("boot_id", "").equals(current.remoteBoot == null ? "" : current.remoteBoot);
                    if (!smallChange) {
                        final boolean quietBack = state.optLong("local_offset", 0) > 0;
                        if (quietBack != shownScrolledBack) main.post(() -> showLatest(quietBack));
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
                // A switch just asked for wins over the daemon's older answer
                // until the daemon reaches it, so the selection never flips back.
                boolean pending = !current.pendingPane.isEmpty()
                        && SystemClock.uptimeMillis() < current.pendingUntil;
                if (pending && pane.equals(current.pendingPane)) current.pendingPane = "";
                else if (!pending && !pane.isEmpty()) current.paneId = pane;
                boolean selected = "ready".equals(phase) && !pane.isEmpty() && !boot.isEmpty();
                boolean chromeChanged;
                if (current.remoteBoot != null && !current.remoteBoot.equals(boot)) {
                    // Held input was meant for programs that no longer exist.
                    pendingInput.discardAll();
                    fail(current, "Herdr restarted. Reopen the remote session before sending input");
                    return;
                }
                if (selected) current.remoteBoot = boot;
                current.remotePhase = phase;
                current.remoteRevision = state.optLong("token", state.optLong("revision", 0));
                current.ready = selected;
                if (selected && !current.announced) {
                    current.announced = true;
                    main.post(() -> { if (current(current)) showLinkUp(); });
                }
                // An agent's `hc open-file` for the pane on screen (core_session's open_file).
                final JSONObject openFile = state.optJSONObject("open_file");
                final String shownPane = current.paneId;
                main.post(() -> { if (filePager != null) filePager.offer(openFile, shownPane); });
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
                    if (switchAskedAt > 0) {
                        android.util.Log.w("herdr-switch", "pane " + pane + " shown "
                                + (SystemClock.uptimeMillis() - switchAskedAt) + " ms after the tap");
                        switchAskedAt = 0;
                    }
                    nativeResetPan();
                    // The native side starts the new pane at its live output.
                    synchronized (current) { current.fetchedOffset = -1; }
                    if (!lastFollowSent) {
                        lastFollowSent = true;
                        nativeSetFollow(current.generation, true);
                    }
                }
                current.chromePane = pane;
                if (selected && current.hostId != null) {
                    outbox.ready(current.hostId, current.remoteHandle, boot, paneIds(current.panes));
                }
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
            if (resumable(failed)) {
                // The frame stays, dimmed, with the keyboard up; input not yet
                // sent waits for the same pane and the monitor reconnects.
                failed.ready = false;
                holdUnsent(failed);
                nativeHold(failed.generation);
                publish(failed, message);
                android.util.Log.i("herdr-reconnect", "link lost: " + message);
                main.post(() -> {
                    if (monitor == null || !hostResumed) return;
                    monitor.lost();
                    showLinkDown();
                });
            } else {
                nativeSetActive(failed.generation, false);
                publish(failed, message);
                showReconnectBanner(message);
            }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            Thread cleanup = new Thread(failed::close, "herdr-gpui-disconnect");
            cleanup.setDaemon(true);
            cleanup.start();
        } else failed.close();
    }

    /**
     * Draws {@code pane}'s saved grid, dimmed and scrollable, while there is no
     * live pane: the terminal opened with no link, or came back to the screen
     * before reconnecting. The live pane replaces it as soon as it attaches.
     */
    private void showSaved(String host, String pane) {
        if (snapshots == null || host == null || pane == null || pane.isEmpty()) return;
        snapshots.open(host, pane, shown -> main.post(() -> {
            synchronized (stateLock) {
                Connection now = connection;
                if (destroyed || !host.equals(currentHostId) || now != null && now.ready) return;
                long generation = now != null ? now.generation : GENERATIONS.incrementAndGet();
                nativeHold(generation);
                nativeAttachShell(generation, shown.renderId);
                savedShownHost = host;
                savedShownAt = shown.savedAt;
            }
            showLinkDown();
        }));
    }

    /** A Herdr session on a saved host: the pane outlives the link, so it can be resumed. */
    private boolean resumable(Connection failed) {
        return failed.remoteHandle > 0 && !shellMode() && currentHostId != null && monitor != null;
    }

    /** Under stateLock, while the failed generation still owns the mailbox. */
    private void holdUnsent(Connection failed) {
        if (!failed.paneId.isEmpty()) rememberHostPane(failed.paneId);
        for (int i = 0; i < PendingInput.MAX_COMMANDS; i++) {
            String raw = nativeTakeCommand(failed.generation);
            if (raw == null) break;
            if (!holdable(raw)) continue;
            if (!pendingInput.add(failed.paneId, raw)) break;
        }
    }

    /** Input worth holding; a close means leave, not something to replay later. */
    private static boolean holdable(String raw) {
        try {
            return !"close".equals(GpuiTerminalProtocol.command(raw).type);
        } catch (IOException | RuntimeException invalid) {
            return false;
        }
    }

    /** The monitor's attempt. Main thread. */
    private void autoReconnect() {
        if (destroyed || isFinishing() || !hostResumed || currentHostId == null) return;
        showLinkDown();
        reconnectCurrentHost();
    }

    /** The status line while the link is down; dense, in the crumb's own slot. Main thread. */
    private void showLinkDown() {
        if (monitor == null || !monitor.reconnecting() || crumb == null) return;
        String host;
        int held;
        synchronized (stateLock) {
            host = connection == null ? "" : connection.hostName;
            held = pendingInput.size();
            String hostId = connection == null ? currentHostId : connection.hostId;
            if (hostId != null && outbox != null) held += outbox.outbox().waiting(hostId);
        }
        StringBuilder text = new StringBuilder(monitor.offline() ? "No network · reconnecting" : "Reconnecting");
        if (host != null && !host.isEmpty()) text.append(" to ").append(host);
        text.append('…');
        if (held > 0) text.append(" · ").append(held).append(held == 1 ? " input waits" : " inputs wait");
        if (savedShownAt > 0) {
            text.append(" · showing ").append(android.text.format.DateFormat.getTimeFormat(this)
                    .format(new java.util.Date(savedShownAt)));
        }
        linkStatus = text.toString();
        // A note on screen keeps its moment; the link status follows it.
        if (crumbNote != null) return;
        crumb.setVisibility(View.VISIBLE);
        crumb.setTextColor(chrome.warning);
        crumb.setText(linkStatus);
        crumb.setContentDescription(linkStatus);
    }

    /** A short note in the crumb's own slot, in place of any pop-up; null while none shows. */
    private String crumbNote;
    private final Runnable clearNote = () -> {
        crumbNote = null;
        crumbText = null;
        if (crumb == null) return;
        if (linkStatus != null) {
            crumb.setTextColor(chrome.warning);
            crumb.setText(linkStatus);
            crumb.setContentDescription(linkStatus);
        } else refreshTabStrip();
    };

    /**
     * Says something worth knowing (a failed action, a refused send) quietly,
     * in the status line where the crumb sits, for a few seconds; Herdr shows
     * no toasts, snackbars or pop-ups. Any thread.
     */
    private void note(String text) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post(() -> note(text));
            return;
        }
        if (destroyed || crumb == null || text == null || text.isEmpty()) return;
        crumbNote = text;
        crumb.setVisibility(View.VISIBLE);
        crumb.setTextColor(chrome.warning);
        crumb.setText(text);
        crumb.setContentDescription(text);
        main.removeCallbacks(clearNote);
        main.postDelayed(clearNote, NOTE_MS);
    }

    private static final long NOTE_MS = 5_000;

    /** The link is back: the crumb returns to where this window is. Main thread. */
    private void showLinkUp() {
        savedShownHost = null;
        savedShownAt = 0;
        if (monitor != null) monitor.succeeded();
        if (linkStatus == null) return;
        linkStatus = null;
        crumbText = null;
        refreshTabStrip();
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
        /** Replaces a dropped session on the same host; its first ready ends the reconnect. */
        boolean resumed;
        /** The link-up was announced once for this session. */
        boolean announced;
        final long generation;
        final CompanionClient client;
        final long remoteHandle;
        /** A raw SSH shell; {@link #shellHandle} is 0 until it is open. */
        final boolean shell;
        volatile long shellHandle;
        volatile boolean closed;
        String remoteBoot;
        /** The saved host this session belongs to, for the outbox; null for a companion or shell. */
        volatile String hostId;
        volatile String paneId = launch.paneId;
        /** A pane switch asked for and not yet confirmed by the daemon, and until when to wait. */
        volatile String pendingPane = "";
        volatile long pendingUntil;
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
            else closeRemote();
        }
        void close() {
            closed = true;
            if (client != null) client.close();
            else if (shell) closeShell();
            else closeRemote();
        }
        private final java.util.concurrent.atomic.AtomicBoolean remoteClosed =
                new java.util.concurrent.atomic.AtomicBoolean();
        /** Never blocks: the grid is saved on the snapshots' own thread. */
        private void closeRemote() {
            RemoteNative.close(remoteHandle);
            // Its grid, for reading this conversation later with no link.
            if (remoteHandle > 0 && hostId != null && remoteClosed.compareAndSet(false, true)) {
                snapshots.saveClosed(hostId, paneId);
            }
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
    /** Unsent words drawn at the program's caret (JSON, see ghost_bridge.rs); "" clears them. */
    static native void nativeSetGhost(String json);
    private static native void nativeSetSnapshot(long generation, String title, String text, String status,
            String shortcutsJson);
    private static native void nativeSetSnapshotChrome(long generation, String title, String status,
            String shortcutsJson);
    private static native String nativeCopyText(long generation);
    private static native void nativeSetActive(long generation, boolean active);
    private static native void nativeHold(long generation);
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
