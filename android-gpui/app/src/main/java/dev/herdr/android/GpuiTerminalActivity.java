package dev.herdr.android;

import android.annotation.SuppressLint;

import android.app.AlertDialog;
import android.content.ClipData;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.content.res.ColorStateList;
import android.net.Uri;
import android.os.Bundle;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.inputmethod.BaseInputConnection;
import android.view.ContentInfo;
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
import java.util.function.Supplier;
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
    final Handler main = new Handler(Looper.getMainLooper());
    final Object stateLock = new Object();
    private final Set<Integer> capturedKeys = new HashSet<>();
    /** Scroll requests ride their own thread so a poll parse never delays them. */
    /**
     * Reads and keeps the files agents reference, for reading offline (PR-F): one at a time, off
     * every thread the terminal needs, and its disk work with it.
     */
    private final java.util.concurrent.ExecutorService refWorker =
            java.util.concurrent.Executors.newSingleThreadExecutor(task -> {
                Thread thread = new Thread(task, "herdr-refs");
                thread.setDaemon(true);
                thread.setPriority(Thread.MIN_PRIORITY);
                return thread;
            });
    /** Which referenced file to read next, and when. Thread-safe. */
    private final RefPlanner refPlanner = new RefPlanner();
    /** Copies of referenced files; null until created. Disk work on {@link #refWorker} or the file view's worker. */
    private RefFiles refFiles;
    /** When the poll last looked for files the pane on screen names (uptime ms). Poll thread. */
    private long refsLookedAt;
    final java.util.concurrent.ExecutorService actionWorker =
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
    /** Pumps every connection's frames at a fixed delay; nothing that waits on a network runs here. */
    private final ScheduledThreadPoolExecutor worker = new ScheduledThreadPoolExecutor(1, task -> {
        Thread thread = new Thread(task, "herdr-gpui-pump");
        thread.setDaemon(true);
        return thread;
    });
    /**
     * Opens connections (a machine switch, a reconnect) and reads the saved hosts and caches
     * (audit A1): a switch's waits of tens of seconds never hold up the pump of the terminal
     * on screen, so it keeps painting and echoing while another computer connects.
     */
    private final java.util.concurrent.ExecutorService connector = java.util.concurrent.Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "herdr-gpui-connect");
        thread.setDaemon(true);
        return thread;
    });
    private GpuiLaunch.Session launch;
    Connection connection;
    private String shortcutJson;
    private List<Shortcut> shortcuts = Collections.emptyList();
    private boolean hostStarted;
    /** The keyboard was showing at the last insets; its showing is a use (item 71). */
    private boolean imeShown;
    /** The bottom inset the root is laid out with (bars, cutout and keyboard), px: the cluster rides from it. */
    private int laidOutBottom;
    /** The composer's icon cluster (ActionBar) as the root holds it. */
    private View clusterView;
    /** The window's size in dp when last configured: a fold or turn changes it (item 71). */
    private final int[] screenDp = new int[2];
    private volatile boolean hostResumed;
    boolean destroyed;
    private long imeSession;
    private boolean imeComposing;
    private boolean initialImeHidden;
    private boolean keyboardRequested;
    private boolean allowNextKeyboardShow;
    private GpuiDisplayOptions displayOptions;
    private boolean compactDisplay;
    private FrameLayout terminalRoot;
    LinearLayout sidebar, sidebarRows;

    ScrollView sidebarScroll;
    private ImageButton sidebarButton;
    /**
     * Wolf's item 83: the docked rail open, minimized to a strip or closed,
     * this device's choice (RailPanel). The strip is shown while the rail is
     * closed and {@code railMinimized}; it and the toolbar's rail button keep
     * the needs-you badge, the bar's count ({@code barNeedYou}).
     */
    private RailPanel railPanel = RailPanel.DEFAULT;
    private boolean railMinimized;
    private int barNeedYou;
    private LinearLayout railBody, railControls, railStrip;
    private TextView railStripBadge, sidebarBadge;
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
    GpuiChrome chrome;
    /** The keyboard up now is the one autoKeyboardPane raised, untouched since: it waits for the answer. */
    private volatile boolean keyboardRaisedByApp;
    /** factory-setup.33: the agent's question dialog on the pane, answered from a native sheet. */
    private final ElicitationOffer elicitation = new ElicitationOffer(this, () -> chrome,
            () -> ElicitationPolicy.busy(imeShown, keyboardRaisedByApp, composer == null ? null : composer.busy()),
            () -> ProvisionedSettings.capturable(this), this::hideRaisedKeyboard);
    /** What the offer last saw (connection, pane, attach, readiness, status, kind); posted on change only. */
    private String elicitationSignature = "";
    /** The terminal and, swiped to, a file an agent or a tapped path opened. */
    private FilePager filePager;
    volatile boolean sidebarOpen;
    /** When the last window switch was asked for, to log how long it took. */
    private volatile long switchAskedAt;
    private final BackOwner backOwner = new BackOwner(this, this::hideSidebar);
    /**
     * Android 14+: while the rail is closed, a Back swipe from the left edge
     * opens it, like a navigation drawer; the right edge and the buttons still
     * go back. Null below Android 14 or while the rail is open.
     */
    private Object edgeBack;
    String sidebarSignature = "";
    volatile java.util.List<SavedRemoteHost> savedHosts = java.util.Collections.emptyList();
    volatile String currentHostId;
    volatile boolean switchingHost;
    /** Host currently being adopted; its sampler stays closed meanwhile. */
    volatile String switchingTo;
    /** One background SSH session per saved host, used only to mirror its tree. */
    static final class HostSample {
        volatile long handle;
        volatile org.json.JSONArray workspaces, tabs, panes;
        volatile boolean connected;
        volatile long revision = -1;
        volatile long nextAttemptAt;
        /** The next read (HostSampling: slower while the rail doesn't show this machine). */
        volatile long nextPollAt;
    }
    final java.util.Map<String, HostSample> hostSamples = new java.util.concurrent.ConcurrentHashMap<>();
    private java.util.concurrent.ScheduledExecutorService sampler;
    private AlertDialog displayDialog;
    private Button controlButton;
    /** Vertical swipe state: a short tap still reaches the terminal. */
    private float touchDownX, edgeStartX;
    /** Which taps the renderer reports belong to the pane shown now. Main thread. */
    private final TerminalTap terminalTap = new TerminalTap();
    /** The finger the renderer follows, until the activity takes a gesture over. */
    private final RendererTouch rendererTouch = new RendererTouch();
    private boolean edgeClaimed;
    private float touchDownY, lastTouchY, scrollFraction;
    private float scrollVelocity, flingRemainder;
    private long lastMoveAt;
    private volatile boolean flinging;
    private SurfaceView terminalSurface;
    private TerminalSelection selection;
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

    /**
     * Bumped when something takes the viewport (typing jumps to the live bottom): scroll steps
     * already handed to {@link #scrollWorker} for an older one are dropped, not run after it.
     */
    private final java.util.concurrent.atomic.AtomicInteger scrollEpoch = new java.util.concurrent.atomic.AtomicInteger();

    /**
     * Typing takes the viewport (the draft is written at Claude's prompt, at the live bottom):
     * a fling still running stops, its scroll steps not yet run are dropped, and the pane goes
     * back to its live bottom if it is (or was being flung) into history. Also on a held frame.
     */
    private void takeViewportForTyping() {
        boolean wasFlinging = flinging;
        stopFling();
        if (terminalSurface != null) terminalSurface.removeCallbacks(flingStep);
        scrollEpoch.incrementAndGet();
        if (!shownScrolledBack && !wasFlinging) return;
        jumpToLive();
        showLatest(false);
    }
    private volatile boolean scrolling;
    /** The current drag left the slop sideways: the renderer pans it to its end. */
    private boolean panning;
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
        open = true;
        // A killed app's leftover camera capture, or a share's cached copies, are never kept.
        CaptureFiles.clear(getCacheDir());
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
        bindings = MachineBindings.shared(this);
        transcripts = TranscriptKeeper.shared(this);
        refFiles = RefFiles.shared(this);
        snapshots = PaneSnapshots.shared(this);
        worker.setRemoveOnCancelPolicy(true);
        worker.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        if (launch == null) {
            android.content.Intent intent = getIntent();
            if (ACTION_OPEN_NOTICE.equals(intent.getAction())) {
                String host = intent.getStringExtra(EXTRA_FOCUS_HOST);
                String pane = intent.getStringExtra(EXTRA_FOCUS_PANE);
                if (host != null && pane != null) HomeActivity.openNotice(this, host, pane);
            }
            android.util.Log.w("herdr-gpui", "Reopen the terminal from the host list");
            finish();
            return;
        }
        try {
            shortcuts = Shortcut.parseConfiguration(launch.shortcuts());
            currentHostId = launch.remoteHost == null ? null : launch.remoteHost.id;
            if (launch.revealFleetSelection) rail.requestRailReveal(currentHostId, launch.paneId);
            // Remote-mutating controls live on the Java UI thread, never a deferred GPUI click.
            shortcutJson = "[]";
            loadDisplayOptions();
            themeIndex = getSharedPreferences("terminal-display", MODE_PRIVATE).getInt("theme", 0);
            if (themeIndex < 0 || themeIndex > GpuiDisplayOptions.THEME_SYSTEM) themeIndex = 0;
            nativeStart(this);
            nativeShowAgentFooter(showAgentFooter);
            appliedPalette = currentPalette();
            nativeSetTheme(appliedPalette);
            chrome = GpuiChrome.load(appliedPalette);
            // Host switching needs the saved hosts before the first rail paint.
            connector.execute(() -> {
                try {
                    java.util.List<SavedRemoteHost> hosts = new RemoteHostStore(this).read();
                    runOnUiThread(() -> { savedHosts = hosts; updateHostSampling(); rail.refreshSidebar(true); });
                } catch (Exception ignored) {
                    // An unreadable store leaves the rail without host rows.
                }
            });
            hostStarted = true;
            screenDp[0] = getResources().getConfiguration().screenWidthDp;
            screenDp[1] = getResources().getConfiguration().screenHeightDp;
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
        updateLinkBadge();
        // Reused rail rows carry the old colors; rebuild them once for the change.
        if (sidebarRows != null) {
            rail.pruneRailRows(java.util.Collections.emptySet());
            sidebarRows.removeAllViews();
            sidebarSignature = null;
            rail.refreshSidebar(true);
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
                laidOutBottom = bounds.bottom;
                boolean ime = insets.isVisible(WindowInsets.Type.ime());
                if (!ime && imeShown) keyboardRaisedByApp = false;   // put away: the next one is the user's
                if (ime && !imeShown) markUsed();
                imeShown = ime;
                if (composer != null) composer.imeVisible(ime);
                applyKeyboardCluster();
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
        // Focus comes back to the terminal when the keyboard's hidden input
        // proxy lets it go (a shell ending, a link lost after typing), out of
        // touch mode: Android's default focus highlight then washes the whole
        // terminal grey. Wolf's ask: offline is never a grayed screen. Focus
        // and input stay as they are; only that highlight goes.
        surface.setDefaultFocusHighlightEnabled(false);
        surface.setSaveEnabled(false);
        surface.getHolder().addCallback(this);
        surface.setOnTouchListener((view, event) -> forwardTouch(event));
        // The left edge opens the sidebar (Wolf 11-22): the system's Back gesture yields there,
        // in one strip within Android's 200 dp per edge (the right edge's is the panel's).
        surface.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            if (Build.VERSION.SDK_INT < 29) return;
            int[] edge = EdgeStrip.left(r - l, b - t, dp(EdgeStrip.WIDTH_DP), dp(EdgeStrip.TALL_DP));
            v.setSystemGestureExclusionRects(java.util.Collections.singletonList(
                    new android.graphics.Rect(edge[0], edge[1], edge[2], edge[3])));
        });
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
        // The shown window stays whole when the bar is rebuilt (its chips laid
        // out only then) and when its width changes (a fold, a rotation).
        tabStrip.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            if (revealPending) revealActiveChip();
        });
        tabScroll.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            if (r - l != or - ol) v.post(this::revealActiveChip);
        });
        // An agent's pane has no bottom bar: the strip's end holds what the bar's
        // buttons did (panes, hosts, view, session, close).
        tabRow = new PullDownRow(this);
        tabRow.setOrientation(LinearLayout.HORIZONTAL);
        tabRow.setOnPull(this::showMachines);
        tabRow.addView(tabScroll, new LinearLayout.LayoutParams(0, -1, 1));
        tabMenu = iconButton(R.drawable.ic_bar_more, "More", "Panes, hosts and terminal actions",
                view -> showMoreActions(view, true));
        // Offline, reconnecting, a cached pane or a shell's end: one small
        // indicator at the top right (LinkBadge), never a dimmed screen, a
        // banner or a status line. A tap opens Reconnect and Hosts beside it,
        // in this row (LinkActions): no dialog, nothing over the terminal.
        linkBox = new LinearLayout(this);
        linkBox.setOrientation(LinearLayout.HORIZONTAL);
        linkBox.setGravity(android.view.Gravity.CENTER_VERTICAL);
        linkBox.setVisibility(View.GONE);
        linkBadge = new TextView(this);
        linkBadge.setTextSize(LinkBadge.TEXT_SP);
        linkBadge.setSingleLine(true);
        linkBadge.setEllipsize(android.text.TextUtils.TruncateAt.END);
        linkBadge.setMaxWidth(dp(LinkBadge.MAX_WIDTH_DP));
        linkBadge.setGravity(android.view.Gravity.CENTER_VERTICAL);
        linkBadge.setPadding(dp(6), 0, dp(2), 0);
        linkBadge.setOnClickListener(v -> toggleLinkActions());
        linkBox.addView(linkBadge, new LinearLayout.LayoutParams(-2, -1));
        linkPrimary = linkAction(v -> useLinkAction(this::reconnectByHand));
        linkBox.addView(linkPrimary, linkActionParams());
        linkHosts = linkAction(v -> useLinkAction(() -> startActivity(new android.content.Intent(this,
                RemoteHostsActivity.class).putExtra("skip_reopen", true))));
        linkHosts.setText(LinkBadge.HOSTS);
        linkBox.addView(linkHosts, linkActionParams());
        LinearLayout.LayoutParams badgeAt = new LinearLayout.LayoutParams(-2, -1);
        badgeAt.gravity = LinkBadge.GRAVITY;
        tabRow.addView(linkBox, badgeAt);
        // The pin of the agent on screen, top right (factory-9zz.2): PinsRail keeps it and the rail's Pinned section.
        rail.pinsRail = new PinsRail(this, chrome, Pins.shared(this), rail::openPin, () -> rail.refreshSidebar(true), this::note);
        usageFeed = UsageFeed.shared(this).attach(this);
        usageFeed.watch(UsageFeed.Watch.RAIL, sidebarOpen && !railMinimized);
        // Each look holds the core session's one worker, so typing would wait behind the rail's prefetch.
        usageFeed.typing(() -> imeShown || (composer != null && composer.busy() != null));
                tabRow.addView(rail.pinsRail.button().view(), new LinearLayout.LayoutParams(dp(PinButton.WIDTH_DP), -1));
        tabRow.addView(tabMenu, new LinearLayout.LayoutParams(dp(PinButton.WIDTH_DP), -1));
        content.addView(tabRow, new LinearLayout.LayoutParams(-1, dp(WorkPanelLayout.TOP_BAR_DP)));
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
        rail.pinsRail.button().extendTouch(crumb, tabMenu);
        filePager = new FilePager(this, surface, chrome, this::readFileOrCopy, actionWorker);
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
        composer = new ComposerBar(this, chrome, Dictation.shared(this), composerHost());
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
        filePager.setOnShownChanged(() -> {
            if (!filePager.showingFile() && reopenPanelAfterFile) {
                reopenPanelAfterFile = false;
                toggleWorkPanel(true);
            }
            composer.setCovered(filePager.showingFile());
            applyKeyboardCluster();
            refreshOffer();
            // The viewer's own swipe owns the screen while it shows: the panel's edge strip steps aside.
            applyWorkPanel();
        });
        column.addView(composer.field(), new FrameLayout.LayoutParams(-1, ComposerBar.FIELD_HEIGHT_PX,
                android.view.Gravity.BOTTOM));
        // A held message's offer, under the tab row and the crumb: over the pane it is for.
        heldOffer = new HeldOfferCard(this, chrome);
        FrameLayout.LayoutParams offerAt = new FrameLayout.LayoutParams(-1, -2, android.view.Gravity.TOP);
        offerAt.setMargins(dp(8), dp(54), dp(8), 0);
        column.addView(heldOffer.view(), offerAt);
        // The sidebar rail mirrors the desktop's list of workspaces and agents.
        sidebar = new LinearLayout(this);
        sidebar.setOrientation(LinearLayout.HORIZONTAL);
        sidebar.setSaveFromParentEnabled(false);
        sidebarScroll = new RailScrollView(this, this::hideSidebar);
        sidebarScroll.setVerticalScrollBarEnabled(false);
        sidebarRows = new LinearLayout(this);
        sidebarRows.setOrientation(LinearLayout.VERTICAL);
        // A little less from the screen's edge to the fold arrows (Wolf, 9zz.10).
        sidebarRows.setPadding(dp(3), dp(4), dp(6), dp(12));
        sidebarScroll.addView(sidebarRows, new android.widget.FrameLayout.LayoutParams(-1, -2));
        // Hosts and workspaces exceed one screen; the fade says the list keeps going.
        android.widget.FrameLayout railFrame = new android.widget.FrameLayout(this);
        // Docked, the rail's own header minimizes or closes it (item 83); the
        // list scrolls under it.
        railBody = new LinearLayout(this);
        railBody.setOrientation(LinearLayout.VERTICAL);
        railControls = createRailControls();
        railBody.addView(railControls, new LinearLayout.LayoutParams(-1, dp(48)));
        railBody.addView(sidebarScroll, new LinearLayout.LayoutParams(-1, 0, 1));
        railFrame.addView(railBody, new android.widget.FrameLayout.LayoutParams(-1, -1));
        railFade = new View(this);
        railFade.setLayoutParams(new android.widget.FrameLayout.LayoutParams(
                -1, dp(24), android.view.Gravity.BOTTOM));
        railFrame.addView(railFade);
        railStrip = createRailStrip();
        railFrame.addView(railStrip, new android.widget.FrameLayout.LayoutParams(-1, -1));
        sidebar.addView(railFrame, new LinearLayout.LayoutParams(0, -1, 1));
        railEdge = new View(this);
        sidebar.addView(railEdge, new LinearLayout.LayoutParams(dp(1), -1));
        railColumns = new LinearLayout(this);
        railColumns.setOrientation(LinearLayout.HORIZONTAL);
        railColumns.addView(sidebar, new LinearLayout.LayoutParams(0, -1));
        railColumns.addView(column, new LinearLayout.LayoutParams(0, -1, 1));
        root.addView(railColumns, new FrameLayout.LayoutParams(-1, -1));
        // The right panel (Overview | Tasks; WorkPanel): docked at the right from 640 dp, over everything on the cover.
        workPanel = new WorkPanel(this, chrome, workPanelHost());
        root.addView(workPanel.view(), new FrameLayout.LayoutParams(0, -1, android.view.Gravity.END));
        workPanel.attach();
        panelDivider = new PanelDivider(this, chrome, new PanelDivider.Moves() {
            @Override public void chose(WorkPanelLayout.Choice next) { choosePanel(next); }
            @Override public void live(float share) { dragPanel(share); }
            @Override public void cover(float visible) { coverDrag(visible); }
            @Override public void coverSettled(boolean open) { coverSettle(open); }
        });
        root.addView(panelDivider.divider(), new FrameLayout.LayoutParams(dp(PanelDivider.TOUCH_DP), -1, android.view.Gravity.START));
        root.addView(panelDivider.edge(), new FrameLayout.LayoutParams(dp(WorkPanelLayout.EDGE_DP), dp(WorkPanelLayout.EDGE_TALL_DP),
                android.view.Gravity.END | android.view.Gravity.CENTER_VERTICAL));
        // The composer's icons (ActionBar): a cluster at the very bottom right of the whole app,
        // over the panel (or the terminal), never a strip that takes height; the root's padding
        // keeps it above the navigation bar and the keyboard.
        View actionBar = composer.actionBar();
        FrameLayout.LayoutParams clusterAt = new FrameLayout.LayoutParams(-2, -2, android.view.Gravity.BOTTOM | android.view.Gravity.END);
        clusterAt.setMargins(0, 0, dp(ActionBar.MARGIN_DP), dp(ActionBar.MARGIN_DP));
        root.addView(actionBar, clusterAt);
        clusterView = actionBar;
        root.getViewTreeObserver().addOnGlobalLayoutListener(() -> fitAroundCluster(actionBar));
        if (Build.VERSION.SDK_INT >= 30) rideKeyboard(root, actionBar);
        root.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> { if (r - l != or - ol) v.post(this::applyWorkPanel); });
        // Select and copy in the terminal: its handles live over everything else (factory-9zz.4).
        selection = new TerminalSelection(this, root, surface, new TerminalSelection.Host() {
            @Override public boolean select(int kind, float x, float y) {
                Connection now;
                synchronized (stateLock) { now = connection; }
                return now != null && nativeSelect(now.generation, kind, x, y);
            }
            @Override public void claimTouch() { claimRendererTouch(); }
            @Override public void copy(String text) { ActionSheet.copy(GpuiTerminalActivity.this, "Terminal", text); }
        });
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
        root.post(() -> { applySidebarDefault(); rail.refreshSidebar(true); });
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
        return railShown().drawer(railCompact());
    }

    /** No room to dock the rail: the cover, or the unfolded screen while the work panel takes its half. */
    private boolean railCompact() {
        return compactWindow() || WorkPanelLayout.splitsWithRail(getResources().getConfiguration().screenWidthDp, shellMode(),
                panelChoice().hidden);
    }

    /** Opens the rail as a drawer over the terminal, never narrowing it. */
    private void showRailOverlay() {
        if (sidebarOpen) return;
        // Transient: nothing docked (the stored choice, the strip) changes.
        setRailShown(railShown().floated(railCompact()));
        applySidebarWidth();
        updateHostSampling();
        revealedPane = "";
        rail.revealActiveOnOpen();
        rail.refreshSidebar(true);
    }

    /** The rail as it is now (item 83's transitions are RailPanel.Shown's). */
    private RailPanel.Shown railShown() {
        return new RailPanel.Shown(sidebarOpen, railOverlay, railMinimized);
    }

    private void setRailShown(RailPanel.Shown next) {
        if (sidebarOpen != next.open) sidebarSignature = null;
        if (usageFeed != null) usageFeed.watch(UsageFeed.Watch.RAIL, next.open && !next.minimized);
        sidebarOpen = next.open;
        railOverlay = next.overlay;
        railMinimized = next.minimized;
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
        for (android.view.ViewGroup group : new android.view.ViewGroup[] {railControls, railStrip}) {
            for (int i = 0; group != null && i < group.getChildCount(); i++) {
                View child = group.getChildAt(i);
                if (child instanceof TextView && child != railStripBadge) {
                    ((TextView) child).setTextColor(chrome.onSurfaceVariant);
                }
            }
        }
        updateRailBadge();
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
        // On unless the drawer is open (BackOwner closes it): a docked rail keeps the right edge's reveal.
        setEdgeBack(!(drawer && sidebarOpen));
    }

    /**
     * The system takes every left-edge swipe for Back and ignores exclusion
     * requests (d62d747), so the drawer gesture rides on Back itself: Android
     * 14's BackEvent says which edge the swipe came from. Left edge opens the
     * rail (when it is closed); the right edge's lower half brings a hidden
     * work panel in under the finger (panelEdgeStarted); anything else leaves
     * the terminal as Back always did.
     */
    private void setEdgeBack(boolean wanted) {
        if (Build.VERSION.SDK_INT < 34 || wanted == (edgeBack != null)) return;
        if (wanted) {
            Runnable back = () -> {
                if (composer != null && composer.hideKeyboard()) return;
                if (workPanelBack()) return;
                if (filePager == null || !filePager.back()) leaveByBack();
            };
            edgeBack = EdgeBack.register(this, () -> {
                if (sidebarOpen) { back.run(); return; }
                if (composer != null && composer.hideKeyboard()) return;
                if (workPanelBack()) return;
                if (shellMode()) leaveByBack(); else showRailOverlay();
            }, back, new EdgeBack.Right() {
                @Override public boolean started(float x, float y) { return panelEdgeStarted(x, y); }
                @Override public void moved(float x) { panelEdgeMoved(x); }
                @Override public void ended(float x, boolean invoked) { panelEdgeEnded(x, invoked); }
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
        /** The right edge's swipe, when it is the panel's: taken at its start, then followed to its end. */
        interface Right {
            boolean started(float x, float y);
            void moved(float x);
            void ended(float x, boolean invoked);
        }

        static Object register(android.app.Activity activity, Runnable leftEdge, Runnable otherwise, Right right) {
            android.window.OnBackAnimationCallback callback = new android.window.OnBackAnimationCallback() {
                private boolean fromLeft, toPanel;
                private float lastX;
                @Override public void onBackStarted(android.window.BackEvent event) {
                    fromLeft = event.getSwipeEdge() == android.window.BackEvent.EDGE_LEFT;
                    lastX = event.getTouchX();
                    toPanel = event.getSwipeEdge() == android.window.BackEvent.EDGE_RIGHT
                            && right.started(event.getTouchX(), event.getTouchY());
                }
                @Override public void onBackProgressed(android.window.BackEvent event) {
                    if (!toPanel) return;
                    lastX = event.getTouchX();
                    right.moved(lastX);
                }
                @Override public void onBackCancelled() {
                    fromLeft = false;
                    // Let go short of Back: the panel still keeps where it was let go (or hides at the edge).
                    if (toPanel) right.ended(lastX, false);
                    toPanel = false;
                }
                @Override public void onBackInvoked() {
                    boolean left = fromLeft, panel = toPanel;
                    fromLeft = false;
                    toPanel = false;
                    if (panel) right.ended(lastX, true);
                    else (left ? leftEdge : otherwise).run();
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
    @SuppressWarnings("deprecation")
    @Override public void onBackPressed() {
        // Back puts the composer's keyboard away first (and any other keyboard that is up).
        if (composer != null && composer.hideKeyboard()) return;
        if (KeyboardHide.hideIfShown(this)) return;
        if (sidebarOpen && railAsDrawer()) {
            hideSidebar();
            return;
        }
        if (workPanelBack()) return;
        if (filePager != null && filePager.back()) return;
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

    /**
     * A failure the link can not recover from by itself: the top corner's
     * indicator says it, and its in-place actions offer Reconnect and Hosts.
     * Nothing is inserted above the bar or over the terminal. In a raw shell
     * it is a lost connection (an exit is {@link #showShellEnd}). Any thread.
     */
    private void showLinkProblem(String message) {
        // fail() runs on the poll worker; Android views may only be touched on
        // the main thread, so marshal first instead of throwing silently there.
        if (android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) {
            runOnUiThread(() -> showLinkProblem(message));
            return;
        }
        if (terminalRoot == null) return;
        linkProblem = BannerCopy.human(message);
        if (shellMode() && shellEnd == null) shellEnd = LinkBadge.ShellEnd.LOST;
        updateLinkBadge();
    }

    /** A raw shell ended: it exited, or its connection was lost. Any thread. */
    private void showShellEnd(LinkBadge.ShellEnd end, String message) {
        if (android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) {
            runOnUiThread(() -> showShellEnd(end, message));
            return;
        }
        shellEnd = end;
        showLinkProblem(message);
    }

    /** One description for a host row: name, workspace count, then its state. */
    String hostDescription(RailController.RailHostRow holder) {
        StringBuilder text = new StringBuilder("Connect to ").append(holder.host.name);
        if (holder.counted > 0) text.append(", ").append(holder.counted).append(" workspaces");
        if (holder.hostState != null && !holder.hostState.isEmpty()) {
            text.append(", ").append(holder.hostState);
        }
        return text.toString();
    }


    /** The failure is over (a reconnect began, a shell opened). Main thread. */
    private void clearLinkProblem() {
        if (linkProblem != null || shellEnd != null) {
            linkProblem = null;
            shellEnd = null;
            updateLinkBadge();
        }
    }

    /**
     * Another computer was picked: the attempt in flight (a reconnect or a
     * switch) is for the old one. Its opening handle is closed so it fails
     * fast, and whatever it returns later is ignored. Main thread.
     */
    private void pickedAnotherHost() {
        hostEpoch.change();
        long opening = reconnectHandle;
        reconnectHandle = 0;
        if (opening > 0) RemoteNative.close(opening);
    }

    /** The indicator's Reconnect: a new epoch of attempts, the first at once. Main thread. */
    private void reconnectByHand() {
        if (monitor == null || shellMode()) {
            reconnectCurrentHost();
            return;
        }
        monitor.retry();
        showLinkDown();
    }

    /** After a stale attempt ends: try the computer picked now, if one is wanted. Main thread. */
    private void retryCurrentHost() {
        if (destroyed || !hostResumed || monitor == null || !monitor.reconnecting()) return;
        reconnectCurrentHost();
    }

    /** Reuses the saved host for this session; no credential is kept in memory. */
    void reconnectCurrentHost() {
        if (shellMode()) {
            // A shell has no state to resume: reconnecting opens a fresh one.
            clearLinkProblem();
            stopConnection();
            startShell();
            return;
        }
        if (reconnectRunning) {
            // A newer attempt replaces one still running: the network came back while it
            // hung on the dead path (factory-9zz.1). Its handle closes, so it ends at once,
            // and whatever it returns is ignored by its sequence number.
            long hanging = reconnectHandle;
            reconnectHandle = 0;
            if (hanging > 0) RemoteNative.close(hanging);
            android.util.Log.i("herdr-reconnect", "attempt " + reconnectSeq + " replaced");
        }
        reconnectRunning = true;
        final int seq = ++reconnectSeq;
        final long startedAt = SystemClock.uptimeMillis();
        final String id = currentHostId;
        final int epoch = hostEpoch.now();
        clearLinkProblem();
        publishCurrent("Reconnecting…");
        connector.execute(() -> {
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
                    if (seq != reconnectSeq) return;
                    reconnectRunning = false;
                    if (destroyed || !hostResumed) return;
                    // A lookup for a computer no longer picked must not stop the
                    // monitor, label or banner of the one picked since.
                    if (hostEpoch.stale(epoch)) {
                        retryCurrentHost();
                        return;
                    }
                    if (monitor != null) monitor.succeeded();
                    showLinkUp();
                    showLinkProblem("Open the host list to reconnect");
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
                // Replaced while it read the store (its handle was not yet there to close).
                if (seq != reconnectSeq) throw new IllegalStateException("replaced");
                // Paused before the handle was published: attach nothing.
                if (!hostResumed) throw new IllegalStateException("left the screen");
                // Another computer was picked meanwhile (it closed the handle if it saw it).
                if (hostEpoch.stale(epoch)) throw new IllegalStateException("another computer was picked");
                // The same computer again: back to its remembered terminal, not its Deacon.
                String chosen = awaitHostPane(handle, host.id, null, false, ReconnectPolicy.ATTEMPT_MS);
                if (seq == reconnectSeq) reconnectHandle = 0;
                android.util.Log.i("herdr-reconnect", "attempt " + seq + ": " + chosen + " ready "
                        + (SystemClock.uptimeMillis() - startedAt) + " ms after it began" + sinceNetwork());
                final long readyHandle = handle;
                final String readyPane = chosen;
                final SavedRemoteHost readyHost = host;
                runOnUiThread(() -> {
                    if (seq != reconnectSeq) {
                        // Replaced meanwhile: the newer attempt is the one that adopts.
                        RemoteNative.close(readyHandle);
                        return;
                    }
                    reconnectRunning = false;
                    if (!hostResumed) {
                        // Left the screen while connecting: onResume opens it again.
                        RemoteNative.close(readyHandle);
                        return;
                    }
                    if (hostEpoch.stale(epoch) || !readyHost.id.equals(currentHostId)) {
                        // Another computer was picked while this one connected: never adopt it.
                        RemoteNative.close(readyHandle);
                        retryCurrentHost();
                        return;
                    }
                    clearLinkProblem();
                    adoptHost(readyHost, readyHandle, readyPane);
                    // A pane picked offline after the session chose where to land.
                    String picked = rememberedHostPane(readyHost.id);
                    Connection now;
                    synchronized (stateLock) { now = connection; }
                    if (now != null && !picked.isEmpty() && !picked.equals(readyPane) && hasPane(now.panes, picked)) {
                        focusRemotePane(now, picked);
                    }
                });
                handle = 0;
            } catch (Exception | LinkageError failure) {
                if (seq == reconnectSeq) reconnectHandle = 0;
                if (handle != 0) RemoteNative.close(handle);
                android.util.Log.i("herdr-reconnect", "attempt " + seq + " failed after "
                        + (SystemClock.uptimeMillis() - startedAt) + " ms: " + failure);
                runOnUiThread(() -> {
                    // Replaced: the newer attempt reports for itself.
                    if (seq != reconnectSeq) return;
                    reconnectRunning = false;
                    // Left the screen meanwhile: onResume reconnects; say nothing.
                    if (!hostResumed) return;
                    // A stale attempt for a computer no longer picked says nothing either.
                    if (hostEpoch.stale(epoch)) {
                        retryCurrentHost();
                        return;
                    }
                    if (monitor != null) {
                        // Keep trying while the terminal is on screen and the epoch lasts.
                        monitor.failed();
                        showLinkDown();
                    } else showLinkProblem("Reconnect failed. Check the host and network.");
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
            // While the rail is not open, its button keeps the needs-you badge (item 83).
            FrameLayout railButton = new FrameLayout(this);
            sidebarButton.setLayoutParams(new FrameLayout.LayoutParams(dp(48), dp(48)));
            railButton.addView(sidebarButton);
            sidebarBadge = needYouBadge(10);
            railButton.addView(sidebarBadge, new FrameLayout.LayoutParams(-2, -2,
                    android.view.Gravity.TOP | android.view.Gravity.END));
            pinned.addView(railButton, new LinearLayout.LayoutParams(dp(48), dp(48)));
        }
        ImageButton keyboard = iconButton(R.drawable.ic_bar_keyboard, "Keyboard",
                "Show or hide the on-screen keyboard", view -> toggleKeyboard());
        // Input controls resolve on touch-up, in order with the keys they sit by.
        synchronousTouch(keyboard);
        pinned.addView(keyboard);
        if (!shellMode()) pinned.addView(quickShellButton());
        // One bar at every width (factory-setup.29): rail, keyboard and >_. Spaces and panes,
        // view, session, usage and back to hosts are in the top bar's ⋮, the only one; a raw
        // shell has no top bar, so its ⋮ is here.
        if (shellMode()) {
            pinned.addView(iconButton(R.drawable.ic_bar_more, "More", "View, session and back to hosts",
                    view -> showMoreActions(view, false)));
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
        // A rebuilt toolbar (theme, posture) gets the badge now, not at the count's next change.
        updateRailBadge();
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
        applyKeyboardCluster();
    }

    /** Actions a compact toolbar keeps out of view, with the labels the wide one tooltips. */
    /** The overflow menu; {@code navigation} adds panes and hosts, for an agent's barless pane. */
    private void showMoreActions(View anchor, boolean navigation) {
        android.content.Context themed = new android.view.ContextThemeWrapper(this, chrome.popupTheme());
        android.widget.PopupMenu menu = new android.widget.PopupMenu(themed, anchor);
        ColorStateList tint = ColorStateList.valueOf(themeColor(themed, android.R.attr.textColorPrimary));
        menu.getMenu().add(0, 1, MoreMenu.VIEW, "View options").setIcon(R.drawable.ic_bar_view).setIconTintList(tint);
        menu.getMenu().add(0, 2, MoreMenu.SESSION, "Session actions").setIcon(R.drawable.ic_bar_session)
                .setIconTintList(tint);
        menu.getMenu().add(0, 3, MoreMenu.CLOSE, "Back to hosts").setIcon(R.drawable.ic_bar_close).setIconTintList(tint);
        // Panes is ⋮'s at every width now that the bar has no Panes button.
        if (!shellMode()) {
            menu.getMenu().add(0, 4, MoreMenu.PANES, "Spaces & panes").setIcon(R.drawable.ic_bar_panes).setIconTintList(tint);
        }
        if (navigation && !shellMode()) {
            menu.getMenu().add(0, 5, MoreMenu.HOSTS, "Hosts and agents").setIcon(R.drawable.ic_bar_hosts)
                    .setIconTintList(tint);
            menu.getMenu().add(0, 6, MoreMenu.GESTURES, "Gestures");
        }
        if (attachTarget() != null) menu.getMenu().add(0, 7, MoreMenu.ATTACH, "Attach image");
        if (!shellMode()) menu.getMenu().add(0, 8, MoreMenu.USAGE, "Usage").setIcon(R.drawable.ic_bar_usage).setIconTintList(tint);
        // The Work panel opens from the right edge (Wolf 11-22: no Work button); the menu offers
        // it too, for TalkBack and anyone the gesture doesn't suit, on the unfolded screen as on
        // the cover: a panel hidden there always has a visible way back (11-23).
        if (!shellMode()) {
            boolean cover = coverPosture();
            boolean panelShown = cover ? workPanelOpen : !panelChoice().hidden;
            menu.getMenu().add(0, 9, MoreMenu.USAGE, panelShown ? "Hide overview and tasks" : "Overview and tasks");
        }
        menu.setForceShowIcon(true);
        menu.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == 9) {
                if (coverPosture()) toggleWorkPanel(!workPanelOpen);
                else choosePanel(WorkPanelLayout.toggled(panelChoice()));
            }
            else if (item.getItemId() == 8) showUsage();
            else if (item.getItemId() == 7) showAttachSources();
            else if (item.getItemId() == 1) showDisplayOptions();
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

    /** The usage feed of the machine on screen (UsageFeed): kept answers, one look at a time. */
    private UsageFeed usageFeed;

    /** The right panel (WorkPanel), the chrome revision it last saw, and whether the cover's is open. */
    private WorkPanel workPanel;
    private long workPanelRevision = -1;
    private boolean workPanelOpen;
    /** The posture the panel was last laid out in (null: not yet): a change is a fold or unfold. */
    private Boolean panelCover;

    /** Docked at the right from 640 dp (the unfolded split, or beside the rail when wider); over all on the cover. */
    private void applyWorkPanel() {
        if (workPanel == null || terminalRoot == null) return;
        int widthDp = getResources().getConfiguration().screenWidthDp;
        // A fold or unfold since the last layout: Wolf's transitions (WorkPanelLayout.crossed).
        boolean cover = coverPosture();
        if (panelCover != null && panelCover != cover) {
            WorkPanelLayout.Choice inner = cover ? null : panelChoice();
            WorkPanelLayout.Fold crossed = WorkPanelLayout.crossed(panelCover, cover, workPanelOpen, inner);
            workPanelOpen = crossed.coverOpen;
            if (!cover && crossed.inner != inner) {
                getSharedPreferences("terminal-display", MODE_PRIVATE).edit().putString(panelChoiceKey(), crossed.inner.stored()).apply();
            }
        }
        panelCover = cover;
        WorkPanelLayout.Choice choice = panelChoice();
        // A finger on the divider or the edge: the panel is exactly where it is, nothing kept yet.
        boolean coverMoving = cover && !shellMode() && !Float.isNaN(coverLive);
        WorkPanelLayout layout = Float.isNaN(panelLive) || shellMode() || cover
                ? WorkPanelLayout.of(widthDp, cover, shellMode(), workPanelOpen || coverMoving, choice)
                : WorkPanelLayout.live(widthDp, panelLive);
        int rootWidth = root().getWidth();
        if (Float.isNaN(panelLive) && Float.isNaN(coverLive)) {
            String inputs = "cover=" + cover + " windowDp=" + widthDp + " displayDp=" + displayWidthDp()
                    + " rootPx=" + rootWidth + " saved=" + choice.stored() + " coverOpen=" + workPanelOpen
                    + " shown=" + layout.shown + " overlay=" + layout.overlay + " share=" + layout.share;
            if (!inputs.equals(panelLogged)) {
                panelLogged = inputs;
                android.util.Log.i("herdr-gpui", "work panel: " + inputs);
            }
        }
        // The cover's page follows the finger, then settles (coverSettle animates it); docked it never moves.
        if (!coverSettling) {
            float offset = coverMoving ? WorkPanelLayout.coverOffset(coverLive, rootWidth) : 0;
            if (workPanel.view().getTranslationX() != offset) workPanel.view().setTranslationX(offset);
        }
        // Docked, Wolf's divider sets the share (each side keeps its minimum: WorkPanelLayout.clamp).
        int width = layout.overlay ? -1 : Math.round(rootWidth * layout.share);
        FrameLayout.LayoutParams at = (FrameLayout.LayoutParams) workPanel.view().getLayoutParams();
        if (at.width != (layout.shown ? width : 0)) {
            at.width = layout.shown ? width : 0;
            workPanel.view().setLayoutParams(at);
        }
        int right = layout.shown && !layout.overlay ? width : 0;
        if (railColumns.getPaddingRight() != right) railColumns.setPadding(0, 0, right, 0);
        workPanel.show(layout.shown, layout.overlay);
        placeTopRight(layout.shown && !layout.overlay);
        if (panelDivider != null) {
            int[] origin = new int[2];
            root().getLocationOnScreen(origin);
            panelDivider.place(filePager == null || !filePager.showingFile(), layout, choice,
                    rootWidth - (layout.shown ? width : 0), rootWidth, origin[0]);
        }

    }

    private View root() {
        return (View) railColumns.getParent();
    }

    /**
     * The cover's full-page panel, or the split: the display's width decides
     * (WorkPanelLayout.onCover), so an unfolded window narrowed by split
     * screen still splits (fold regressions, 11-23).
     */
    private boolean coverPosture() {
        return WorkPanelLayout.onCover(getResources().getConfiguration().screenWidthDp, displayWidthDp());
    }

    /** The display's width now, dp (0 before API 30): read fresh on every call, never kept. */
    private int displayWidthDp() {
        if (Build.VERSION.SDK_INT < 30) return 0;
        return Math.round(getWindowManager().getMaximumWindowMetrics().getBounds().width()
                / getResources().getDisplayMetrics().density);
    }

    /** What the panel's layout was last derived from, as logged (one line per change, never per frame). */
    private String panelLogged = "";

    /**
     * A fold, unfold or turn (Wolf 11-23: a restart brought the split back, so
     * suspect a mode kept across postures): no gesture of the old posture
     * carries over, and the layout is derived again from the new window, now
     * and once it has its new size. Cover or split is never kept, only
     * derived (coverPosture); workPanelOpen (the cover's page) and the saved
     * split are the only state that crosses, by design.
     */
    private void postureChanged() {
        if (terminalRoot == null || workPanel == null) return;
        panelLive = Float.NaN;
        coverLive = Float.NaN;
        edgeRevealing = false;
        if (panelDivider != null) panelDivider.cancelGesture();
        applyWorkPanel();
        View r = root();
        r.getViewTreeObserver().addOnGlobalLayoutListener(new android.view.ViewTreeObserver.OnGlobalLayoutListener() {
            @Override public void onGlobalLayout() {
                r.getViewTreeObserver().removeOnGlobalLayoutListener(this);
                applyWorkPanel();
            }
        });
        r.requestLayout();
    }

    /** A right-edge Back swipe is bringing the panel in (panelEdgeStarted), and onto the cover's page or the split. */
    private boolean edgeRevealing, edgeOnCover;

    /**
     * The right edge's Back swipe (EdgeBack): the Fold keeps its edges for
     * Back whatever a view excludes (d62d747), so the panel's way in rides on
     * Back itself, as the rail's does on the left. It takes the swipe when the
     * panel is away (hidden unfolded, closed on the cover) and the swipe starts
     * in the lower half above the icon cluster; anywhere else Back stays Back.
     * Coordinates are the screen's.
     */
    private boolean panelEdgeStarted(float x, float y) {
        edgeRevealing = false;
        if (workPanel == null || terminalRoot == null || shellMode()) return false;
        if (filePager != null && filePager.showingFile()) return false;
        boolean cover = coverPosture();
        boolean away = cover ? !workPanelOpen : panelChoice().hidden;
        if (!away) return false;
        int[] origin = new int[2];
        root().getLocationOnScreen(origin);
        float density = getResources().getDisplayMetrics().density;
        if (!WorkPanelLayout.takesEdgeBack((y - origin[1]) / density, Math.round(root().getHeight() / density))) return false;
        edgeRevealing = true;
        edgeOnCover = cover;
        return true;
    }

    /** The finger at {@code x} (the screen's): the panel comes in from zero under it, as the edge strip's does. */
    private void panelEdgeMoved(float x) {
        if (!edgeRevealing) return;
        int[] origin = new int[2];
        root().getLocationOnScreen(origin);
        float density = getResources().getDisplayMetrics().density;
        float xDp = (x - origin[0]) / density;
        int widthDp = Math.round(root().getWidth() / density);
        if (edgeOnCover) coverDrag(WorkPanelLayout.coverRevealed(xDp, widthDp));
        else dragPanel(WorkPanelLayout.dragged(WorkPanelLayout.snapped(xDp, widthDp), widthDp));
    }

    /**
     * The swipe let go at {@code x}: the split keeps the width it was let go at
     * (hidden again within HIDE_BELOW_DP of the edge); the cover's page settles
     * full or gone, full when Back would have gone back ({@code invoked}).
     */
    private void panelEdgeEnded(float x, boolean invoked) {
        if (!edgeRevealing) return;
        edgeRevealing = false;
        int[] origin = new int[2];
        root().getLocationOnScreen(origin);
        float density = getResources().getDisplayMetrics().density;
        float xDp = (x - origin[0]) / density;
        int widthDp = Math.round(root().getWidth() / density);
        if (edgeOnCover) {
            coverSettle(invoked || WorkPanelLayout.coverOpens(WorkPanelLayout.coverRevealed(xDp, widthDp), 0));
        } else {
            choosePanel(WorkPanelLayout.revealed(WorkPanelLayout.snapped(xDp, widthDp), widthDp, panelChoice().share));
        }
    }

    /**
     * Room for the icon cluster where it covers something, and nowhere else (Wolf: no strip
     * across the app): docked, the panel's text scrolls clear of it and the terminal is
     * untouched; over the terminal (the cover, or the panel hidden) the terminal's grid ends
     * above the cluster's top (t2kg 11-22: the prompt row and footer must stay visible), and
     * the black room left of the cluster holds its status words.
     */
    private void fitAroundCluster(View cluster) {
        boolean shows = cluster.getVisibility() == View.VISIBLE;
        boolean overPanel = workPanel != null && workPanel.view().getVisibility() == View.VISIBLE
                && cluster.getLeft() >= workPanel.view().getLeft();
        if (workPanel != null) workPanel.clearCluster(overPanel && shows ? cluster : null);
        boolean overlay = keyboardOverlay();
        int below = shows && !overPanel ? KeyboardCluster.belowTerminal(overlay,
                cluster.getHeight() + dp(ActionBar.MARGIN_DP), overlay ? composer.promptFooterPx() : Float.NaN) : 0;
        View content = terminalColumn == null || terminalColumn.getChildCount() == 0 ? null : terminalColumn.getChildAt(0);
        if (content == null) return;
        FrameLayout.LayoutParams at = (FrameLayout.LayoutParams) content.getLayoutParams();
        if (at.bottomMargin == below) return;
        at.bottomMargin = below;
        content.setLayoutParams(at);
    }

    /** The keyboard is up on an agent's pane: the cluster overlays the session (KeyboardCluster). */
    private boolean keyboardOverlay() {
        return composer != null && KeyboardCluster.overlay(imeShown, composer.agentPane(),
                filePager != null && filePager.showingFile());
    }

    /**
     * With the keyboard up on an agent's pane the bottom toolbar steps aside and the cluster sits
     * over the session just above the keyboard (Wolf 11-23); the terminal is lifted only to keep
     * the prompt's row clear of it. Main thread.
     */
    private void applyKeyboardCluster() {
        if (shortcutBar == null || composer == null) return;
        boolean shown = KeyboardCluster.toolbarShown(imeShown, composer.agentPane(),
                filePager != null && filePager.showingFile());
        int visibility = shown ? View.VISIBLE : View.GONE;
        if (shortcutBar.getVisibility() != visibility) shortcutBar.setVisibility(visibility);
        if (clusterView != null) fitAroundCluster(clusterView);
    }

    /** While the keyboard moves, the cluster stays on its top edge (it is laid out for where it ends). */
    @android.annotation.TargetApi(30)
    private void rideKeyboard(View root, View cluster) {
        root.setWindowInsetsAnimationCallback(new android.view.WindowInsetsAnimation.Callback(
                android.view.WindowInsetsAnimation.Callback.DISPATCH_MODE_CONTINUE_ON_SUBTREE) {
            @Override public WindowInsets onProgress(WindowInsets insets,
                    java.util.List<android.view.WindowInsetsAnimation> running) {
                boolean keyboard = false;
                for (android.view.WindowInsetsAnimation a : running) {
                    if ((a.getTypeMask() & WindowInsets.Type.ime()) != 0) keyboard = true;
                }
                if (!keyboard) return insets;
                float now = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout()
                        | WindowInsets.Type.ime()).bottom;
                cluster.setTranslationY(KeyboardCluster.ride(laidOutBottom, now));
                return insets;
            }

            @Override public void onEnd(android.view.WindowInsetsAnimation animation) {
                cluster.setTranslationY(0);
            }
        });
    }

    /**
     * The pin and ⋮ belong at the far right of the whole screen (Wolf): in the
     * docked panel's header beside the terminal, else at the end of the tab row.
     * Moving the same views keeps their state, listeners and accessibility.
     */
    private void placeTopRight(boolean docked) {
        View pin = rail.pinsRail.button().view();
        android.view.ViewGroup to = docked ? workPanel.trailing() : tabRow;
        if (pin.getParent() == to) return;
        for (View v : new View[] {pin, tabMenu}) {
            android.view.ViewGroup from = (android.view.ViewGroup) v.getParent();
            if (from != null) from.removeView(v);
        }
        // Both bars are TOP_BAR_DP tall; 48 dp targets reach below them (PinButton.extendTouch,
        // WorkPanel's target strip), never by making the bar taller.
        to.addView(pin, new LinearLayout.LayoutParams(dp(PinButton.WIDTH_DP), -1));
        to.addView(tabMenu, new LinearLayout.LayoutParams(dp(PinButton.WIDTH_DP), -1));
        // Docked, the panel's header row is a 48 dp target strip drawn 30 dp tall: the glyphs
        // sit in its top part (WorkPanel); in the tab row they are centred and reach below it.
        int below = docked ? Math.max(0, dp(WorkPanel.TARGET_DP) - dp(WorkPanelLayout.TOP_BAR_DP)) : 0;
        for (View v : new View[] {pin, tabMenu}) v.setPadding(v.getPaddingLeft(), 0, v.getPaddingRight(), below);
    }

    /** The terminal | panel divider (PanelDivider), and Wolf's split per orientation (factory-9zz.9). */
    private PanelDivider panelDivider;

    private String panelChoiceKey() {
        return WorkPanelLayout.choiceKey(coverPosture(),
                getResources().getConfiguration().orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE);
    }

    private WorkPanelLayout.Choice panelChoice() {
        return WorkPanelLayout.Choice.parse(getSharedPreferences("terminal-display", MODE_PRIVATE).getString(panelChoiceKey(), ""));
    }

    /** The cover's page while a finger moves it: how much of it shows; NaN otherwise. */
    private float coverLive = Float.NaN;
    /** The page is animating to where the release left it: the layout leaves its offset alone. */
    private boolean coverSettling;

    /** The cover: the full page follows the finger (in from the right edge, or back toward it). */
    private void coverDrag(float visible) {
        coverLive = visible;
        applyWorkPanel();
    }

    /** The cover: the release settles the page full or off screen, from where it is; no partial page. */
    private void coverSettle(boolean open) {
        if (workPanel == null) return;
        if (Float.isNaN(coverLive)) coverLive = open ? 0 : 1;
        View page = workPanel.view();
        coverSettling = true;
        page.animate().cancel();
        page.animate().translationX(open ? 0 : root().getWidth()).setDuration(WorkPanelLayout.COVER_SETTLE_MS)
                .withEndAction(() -> {
                    coverSettling = false;
                    coverLive = Float.NaN;
                    page.setTranslationX(0);
                    toggleWorkPanel(open);
                }).start();
    }

    /** The panel's share while a finger drags it (PanelDivider, at most once a frame); NaN otherwise. */
    private float panelLive = Float.NaN;

    /** Follows the finger: the panel and the terminal take the live width; NaN puts the kept choice back. */
    private void dragPanel(float share) {
        panelLive = share;
        applyWorkPanel();
    }

    /** A drag's release, the reveal from the edge or TalkBack: kept, then laid out at it. */
    private void choosePanel(WorkPanelLayout.Choice next) {
        panelLive = Float.NaN;
        boolean was = panelChoice().hidden;
        getSharedPreferences("terminal-display", MODE_PRIVATE).edit().putString(panelChoiceKey(), next.stored()).apply();
        // TalkBack hears the change itself, once, on the release (not while dragging).
        if (was != next.hidden) root().announceForAccessibility(next.hidden ? "Work panel hidden" : "Work panel restored");
        applySidebarWidth();
        rail.refreshSidebar(true);
        if (next.hidden) note("Work panel hidden · swipe in from the right edge, or ⋮ › Overview and tasks, to bring it back");
    }

    /** The cover's Work button and the panel's ×. */
    private void toggleWorkPanel(boolean open) {
        workPanelOpen = open;
        applyWorkPanel();
    }

    /** Back: a selection in the panel first, then the cover's open panel. */
    private boolean workPanelBack() {
        if (workPanel == null || !workPanel.shown()) return false;
        if (workPanel.back()) return true;
        if (workPanelOpen && coverPosture()) {
            toggleWorkPanel(false);
            return true;
        }
        return false;
    }

    private WorkPanel.Host workPanelHost() {
        return new WorkPanel.Host() {
            @Override public String op(String json) {
                Connection now;
                synchronized (stateLock) { now = connection; }
                if (now == null || now.isClosed() || now.remoteHandle <= 0) throw new HostSwitched();
                return RemoteNative.sessionAction(now.remoteHandle, json);
            }
            @Override public void note(String text) { GpuiTerminalActivity.this.note(text); }
            @Override public void quote(String header, String text) { quoteToDraft(header, text); }
            @Override public void openFile(String hostId, String path) {
                Connection now;
                synchronized (stateLock) { now = connection; }
                if (now == null || filePager == null || hostId == null) return;
                // On the cover the panel covers the terminal: it steps aside and comes back when
                // the file closes, as it was (WorkPanel keeps its tab and scroll).
                if (compactWindow() && workPanelOpen) {
                    toggleWorkPanel(false);
                    reopenPanelAfterFile = true;
                }
                if (hostId.equals(now.hostId)) {
                    filePager.open(now.paneId, path, 0);
                    return;
                }
                // The doc's own machine (Wolf 11-22): read through that machine's mirror session,
                // never the one on screen; nothing to read through says so.
                HostSample mirror = hostSamples.get(hostId);
                JSONArray panes = mirror == null ? null : mirror.panes;
                String terminal = panes == null || panes.length() == 0 ? "" : panes.optJSONObject(0) == null ? ""
                        : panes.optJSONObject(0).optString("id", "");
                // Each read is kept with its source machine (factory-9zz.12), so it opens offline too.
                SavedRemoteHost source = savedHost(hostId);
                MachineKey docMachine = source == null || bindings == null ? null
                        : bindings.machine(source.id, source.connectionRevision);
                if (mirror == null || !mirror.connected || mirror.handle <= 0 || terminal.isEmpty()) {
                    RefFiles.Copy copy = docMachine == null || refFiles == null ? null
                            : refFiles.open(docMachine, DOC_FILES, path, System.currentTimeMillis());
                    if (copy == null) {
                        note("Can't reach " + hostName(hostId) + " to open that file now");
                        return;
                    }
                    String saved = copy.reply();
                    // A folder's entries open from their own saved copies, when there are any.
                    filePager.open(DOC_FILES, path, 0, (t, p) -> {
                        if (p.equals(path)) return saved;
                        RefFiles.Copy entry = refFiles.open(docMachine, DOC_FILES, p, System.currentTimeMillis());
                        return entry == null ? "{\"queued\":false,\"error\":\"not_saved\"}" : entry.reply();
                    });
                    return;
                }
                final long handle = mirror.handle;
                filePager.open(terminal, path, 0, (t, p) -> {
                    String reply = readThrough(handle, t, p);
                    if (docMachine != null && refFiles != null) refFiles.save(docMachine, DOC_FILES, p, reply, System.currentTimeMillis());
                    return reply;
                });
            }
            @Override public void openLink(String url) {
                String lower = url == null ? "" : url.toLowerCase(java.util.Locale.ROOT);
                if (url == null || url.length() > 2048 || !(lower.startsWith("http://") || lower.startsWith("https://"))) return;
                try {
                    startActivity(new android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url)));
                } catch (RuntimeException e) {
                    note("No app here opens that link");
                }
            }
            @Override public boolean typing() { return imeShown || (composer != null && composer.busy() != null); }
            @Override public MachineKey machine() {
                Connection now;
                synchronized (stateLock) { now = connection; }
                return now == null || now.isClosed() ? null : PanelSync.keyFor(now.machine, now.refusedBy != null, now.hostId);
            }
            @Override public void closed() { toggleWorkPanel(false); }
            @Override public void coverDrag(float visible) { GpuiTerminalActivity.this.coverDrag(visible); }
            @Override public void coverSettled(boolean open) { coverSettle(open); }
            @Override public void focus(String id) {
                Connection now;
                synchronized (stateLock) { now = connection; }
                if (now == null || now.isClosed()) return;
                // The bead stays open in Tasks; on the cover the terminal comes to the front.
                if (compactWindow()) toggleWorkPanel(false);
                focusRemotePane(now, id);
            }
        };
    }

    /**
     * A quote from the panel into the draft of the agent on screen: its
     * header, then each line as "> ", then a blank line; never sent.
     * t3jk's quoteToDraft (factory-9zz.4) replaces this with the composer's
     * own insertion at the cursor; until then it joins the draft like a taken-back message.
     */
    private void quoteToDraft(String header, String text) {
        if (composer == null) return;
        StringBuilder quoted = new StringBuilder(header).append('\n');
        for (String line : text.split("\n", -1)) quoted.append("> ").append(line).append('\n');
        composer.takeBack(quoted.append('\n').toString());
        if (compactWindow()) toggleWorkPanel(false);
        note("Quoted into the draft · not sent");
    }

    /** The connected machine's aswap accounts (UsageView), drawn from the feed of that same machine. */
    private void showUsage() {
        Connection opened;
        synchronized (stateLock) { opened = connection; }
        if (opened == null || opened.isClosed() || opened.remoteHandle <= 0 || opened.hostId == null || usageFeed == null) {
            note("Usage needs a connected machine");
            return;
        }
        UsageView.show(this, chrome, opened.hostId, opened.hostName, usageFeed);
    }

    /** One usage look at {@code hostId}'s core; blocks (the feed's own thread). Another machine on screen: HostSwitched. */
    private String usageLook(String hostId) {
        Connection now;
        synchronized (stateLock) { now = connection; }
        if (hostId == null || now == null || now.isClosed() || !hostId.equals(now.hostId)) throw new HostSwitched();
        return RemoteNative.sessionAction(now.remoteHandle, "{\"op\":\"usage.look\"}");
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
        android.graphics.drawable.GradientDrawable shape = rail.roundedDrawable(selected ? chrome.selected : fill, radiusDp);
        android.graphics.drawable.GradientDrawable mask = rail.roundedDrawable(0xffffffff, radiusDp);
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
        sayRoute(target);
        return HeldScroll.scroll(shellMode(), target != null, target != null && target.isClosed(),
                target == null ? 0 : target.remoteHandle, target != null && target.ready, linkInDoubt(target), lines,
                new HeldScroll.Sink() {
                    @Override public boolean held(int step) { return scrollHeld(step, false); }
                    @Override public boolean live(int step) { return scrollLive(target, step); }
                    @Override public boolean local(int step) { return scrollLocalOnly(target, step); }
                });
    }

    /**
     * Whether a live pane's link is in doubt (no network, or quiet just after a change): then a
     * drag scrolls the phone's own rows and sends nothing (factory-9zz.1: a full-screen agent
     * otherwise gets wheel input over a dead link, and nothing moves). Any thread.
     */
    private boolean linkInDoubt(Connection target) {
        return target != null && monitor != null && monitor.suspect(target.quietMs);
    }

    /** Once per gesture, where its steps go: what the device check reads (factory-9zz.1). Main thread. */
    private void sayRoute(Connection target) {
        if (routeSaid) return;
        routeSaid = true;
        android.util.Log.i("herdr-scroll", "drag: " + (target == null ? "no pane"
                : "closed=" + target.isClosed() + " ready=" + target.ready + " handle=" + target.remoteHandle
                + " doubt=" + linkInDoubt(target)));
    }

    /** The edge rule has answered the gesture now running: once per drag or fling. Main thread. */
    private boolean edgeSaid;
    /** The lookup a drag back in doubt waits on: its history opens when it answers. Main thread. */
    private long openWhenFound;
    /** The gesture now running has logged where its steps go. Main thread. */
    private boolean routeSaid;

    /**
     * After a step on the phone's own rows: a drag back that moved nothing is answered,
     * once per gesture, by the saved history or a note saying why there is nothing older.
     * {@code doubt}: the live pane's link is in doubt (it is not offline). Main thread.
     */
    private void answerEdge(int lines, JSONObject step, boolean doubt) {
        HeldScroll.Edge edge = HeldScroll.edge(lines, step.optBoolean("moved", true),
                step.optLong("history", 0), transcriptOpen != null && transcriptHistory, edgeSaid);
        if (edge == HeldScroll.Edge.NONE) return;
        edgeSaid = true;
        // Once per gesture: what the device check reads (factory-9zz.1).
        android.util.Log.i("herdr-scroll", (doubt ? "in doubt" : "held") + ": " + edge + " history="
                + step.optLong("history", 0));
        stopFling();
        if (edge == HeldScroll.Edge.OPEN_HISTORY) {
            transcriptOpen.run();
            return;
        }
        if (doubt && transcriptOpen == null) {
            // Just offline: the saved history may not have been looked up yet. Look now, and
            // open it when it answers, so this drag is not left at "connecting".
            refreshTranscript(here());
            openWhenFound = transcriptSeq;
        }
        if (doubt && edge == HeldScroll.Edge.SCREEN_ONLY) {
            note(HeldScroll.DOUBT_NOTE);
        } else {
            note(edge == HeldScroll.Edge.SCREEN_ONLY ? HeldScroll.SCREEN_ONLY_NOTE : HeldScroll.NO_OLDER_NOTE);
        }
    }

    /**
     * A live pane whose link is in doubt scrolls its own rows on the phone: nothing is sent,
     * not even a wheel report. Its "Latest" pill follows the offset.
     */
    private boolean scrollLocalOnly(Connection target, int lines) {
        final int epoch = scrollEpoch.get();
        try {
            scrollWorker.execute(() -> {
                if (epoch != scrollEpoch.get()) return;
                JSONObject step;
                try {
                    step = new JSONObject(RemoteNative.scrollLocalOnly(target.remoteHandle, lines));
                } catch (Exception | LinkageError gone) {
                    // The session ended under the step (the link is going): still answered, never silent.
                    step = new JSONObject();
                    try {
                        step.put("moved", false).put("history", 0).put("local_offset", 0);
                    } catch (JSONException impossible) {
                        return;
                    }
                }
                final JSONObject answer = step;
                main.post(() -> {
                    showLatest(answer.optLong("local_offset", 0) > 0);
                    answerEdge(lines, answer, true);
                });
            });
        } catch (java.util.concurrent.RejectedExecutionException busy) {
            return false;
        }
        return true;
    }

    /** A live Herdr pane scrolls its own buffered rows; the daemon backfills gaps. */
    private boolean scrollLive(Connection target, int lines) {
        if (lines > 0 && lastFollowSent) {
            lastFollowSent = false;
            nativeSetFollow(target.generation, false);
        }
        target.fastUntil = SystemClock.uptimeMillis() + 1200;
        final int epoch = scrollEpoch.get();
        try {
            scrollWorker.execute(() -> {
                // The viewport was taken since (typing went to the live bottom): drop it.
                if (epoch != scrollEpoch.get()) return;
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
        return HeldScroll.held(shellMode(), target != null, target != null && target.isClosed(),
                target == null ? 0 : target.remoteHandle, target != null && target.ready);
    }

    /**
     * Scrolls the held frame's own history on the phone: nothing is sent, so
     * it works with no link at all. The "Latest" pill follows its offset.
     */
    private boolean scrollHeld(int lines, boolean latest) {
        try {
            scrollWorker.execute(() -> {
                try {
                    JSONObject step = new JSONObject(RemoteNative.scrollHeld(lines, latest));
                    main.post(() -> {
                        showLatest(step.optLong("local_offset", 0) > 0);
                        if (!latest) answerEdge(lines, step, false);
                    });
                } catch (Exception | LinkageError failed) {
                    android.util.Log.w("herdr-scroll", "held step failed: " + failed);
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
        keyboardRaisedByApp = false;   // the user's own keyboard from here on
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
            // On the scroll worker: after any scroll step already running, never before it.
            scrollWorker.execute(() -> {
                try { RemoteNative.scrollTo(target.remoteHandle, 0); }
                catch (RuntimeException | LinkageError ignored) { }
            });
        } catch (java.util.concurrent.RejectedExecutionException busy) { }
    }

    int dp(float value) { return Math.round(value * getResources().getDisplayMetrics().density); }

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

    private boolean showAgentFooter;

    private void loadDisplayOptions() {
        SharedPreferences preferences = getSharedPreferences("terminal-display", MODE_PRIVATE);
        try {
            displayOptions = new GpuiDisplayOptions(preferences.getInt("mode", 0),
                    preferences.getInt("font", 0), preferences.getInt("alt", 0));
            showAgentFooter = AgentFooterPreference.show(preferences);
        } catch (ClassCastException invalid) {
            displayOptions = new GpuiDisplayOptions(0, 0, 0);
            showAgentFooter = false;
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

    /**
     * A pinched size the renderer takes: 11 to 24 (the renderer refuses below 11, and a pinch to
     * 9 or 10 crashed the app in applyDisplayOptions). 0 stays "not pinched".
     */
    static float pinchClamp(float size) {
        if (!(size > 0) || Float.isNaN(size)) return 0f;
        return Math.max(PINCH_MIN, Math.min(PINCH_MAX, size));
    }

    static final float PINCH_MIN = 11f, PINCH_MAX = 24f;

    private float pinchedFont() {
        // A size kept by an older build (it allowed 9) is read back within the bounds.
        return pinchClamp(getSharedPreferences("terminal-display", MODE_PRIVATE).getFloat(pinchKey(), 0f));
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
                        float next = pinchClamp(pinchFont * detector.getScaleFactor());
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
        // Folded, unfolded or turned: someone has the phone in hand (item 71).
        if (configuration.screenWidthDp != screenDp[0] || configuration.screenHeightDp != screenDp[1]) {
            if (screenDp[0] != 0) markUsed();
            screenDp[0] = configuration.screenWidthDp;
            screenDp[1] = configuration.screenHeightDp;
            postureChanged();
        }
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
        android.widget.Switch footer = new android.widget.Switch(context);
        footer.setText("Show agent footer");
        footer.setChecked(showAgentFooter);
        footer.setMinHeight(dp(48));
        options.addView(footer);
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
                    showAgentFooter = footer.isChecked();
                    SharedPreferences.Editor editor = getSharedPreferences("terminal-display", MODE_PRIVATE).edit();
                    AgentFooterPreference.save(editor, showAgentFooter)
                            .putInt("mode", displayOptions.mode).putInt("font", displayOptions.fontSize)
                            .putInt("alt", displayOptions.altMode).putInt("theme", themeIndex)
                            .putBoolean(AGENT_ALERTS, alerts.getSelectedItemPosition() == 1).apply();
                    nativeShowAgentFooter(showAgentFooter);
                    applyTheme();
            // Host switching needs the saved hosts before the first rail paint.
            connector.execute(() -> {
                try {
                    java.util.List<SavedRemoteHost> hosts = new RemoteHostStore(this).read();
                    runOnUiThread(() -> { savedHosts = hosts; rail.refreshSidebar(true); });
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

    /** Whether the rail shows {@code hostId}'s rows now (HostSampling). */
    boolean railShows(String hostId) {
        Connection current;
        synchronized (stateLock) { current = connection; }
        // The same rule as the rail's picker (refreshSidebar): a connection on screen and more than one machine.
        return HostSampling.shown(hostId, currentHostId, current != null, savedHosts.size());
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
        // A machine the rail doesn't show (the picker names it) is read slower: its counts, not its rows.
        boolean shown = railShows(hostId);
        if (now < sample.nextPollAt) return;
        sample.nextPollAt = now + HostSampling.every(shown) - 500;
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
                sample.connected = true;
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
                // Only a machine the rail shows rebuilds it; a hidden one's new list waits for its picker.
                if (shown) {
                    sidebarSignature = "";
                    if (!destroyed) runOnUiThread(() -> rail.refreshSidebar(false));
                }
            }
        } catch (Exception | LinkageError failure) {
            closeSample(sample);
            sample.nextAttemptAt = now + 30_000;
            if (!destroyed) runOnUiThread(() -> rail.refreshSidebar(true));
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
        return HostLanding.hasPane(panes, paneId);
    }

    /**
     * Connects to another saved host with the stored device key and keeps the
     * GPUI surface: only the connection and its generation change.
     */
    void switchHost(SavedRemoteHost host) { switchHost(host, null); }

    void switchHost(SavedRemoteHost host, String pane) { switchHost(host, pane, true); }

    void switchHost(SavedRemoteHost host, String pane, boolean picked) {
        if (host == null) return;
        if (switchingHost) {
            // Not dropped (factory audit, PR 6): the latest pick runs once this switch settles.
            hostPicks.keep(new HostPick(host.id, pane, picked, null));
            note(HostPick.note(hostName(switchingTo), host.name));
            return;
        }
        if (host.id.equals(currentHostId)) return;
        pendingShell.clear();
        if (host.expectedFingerprint.isEmpty()) {
            note("Trust " + host.name + " from Remote hosts first");
            return;
        }
        if (monitor != null && monitor.offline()) {
            // No network at all: no attempt to wait on, show it from the cache.
            switchHostOffline(host, pane, picked);
            return;
        }
        switchingHost = true;
        switchingTo = host.id;
        pickedAnotherHost();
        final int epoch = hostEpoch.now();
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
        switchAskedAt = SystemClock.uptimeMillis();
        // Cached first (Wolf 11-22): the pane this switch lands on is shown at once from its saved
        // copy ("connecting · HH:MM"), never seconds of blank; the live session replaces it on
        // its first paint (adoptHost holds the saved frame until then). Its stand-in is that
        // host's own, so nothing typed or read crosses to the machine switched away from.
        String cached = cachedLanding(host.id, pane);
        if (!cached.isEmpty()) {
            stopConnection();
            currentHostId = host.id;
            savedShownHost = null;
            savedShownAt = 0;
            installOffline(host.id, host.name, cached);
            showSaved(host.id, cached);
            sidebarSignature = "";
            rail.refreshSidebar(true);
        }
        connector.execute(() -> {
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
                String chosen = awaitHostPane(handle, host.id, forcedPane, true, MACHINE_SWITCH_READY_MS);
                final long readyHandle = handle;
                final String readyPane = chosen;
                runOnUiThread(() -> {
                    if (hostEpoch.stale(epoch)) {
                        // Picked something else meanwhile.
                        RemoteNative.close(readyHandle);
                        switchingHost = false;
                        switchingTo = null;
                        runWaitingPick();
                        return;
                    }
                    if (picked && readyPane.equals(forcedPane)) rail.requestRailReveal(host.id, readyPane);
                    adoptHost(host, readyHandle, readyPane);
                });
                handle = 0;
            } catch (Exception | LinkageError failure) {
                if (handle != 0) RemoteNative.close(handle);
                runOnUiThread(() -> {
                    switchingHost = false;
                    switchingTo = null;
                    if (destroyed || !hostResumed || hostEpoch.stale(epoch)) { runWaitingPick(); return; }
                    if (failure instanceof HostLanding.Refused) {
                        // It answered and said no: not offline, so no cached view; stay here.
                        note(host.name + " refused that terminal: " + ((HostLanding.Refused) failure).reason);
                        runWaitingPick();
                        return;
                    }
                    // Unreachable: show it from the cache; the monitor keeps trying it.
                    switchHostOffline(host, forcedPane, picked);
                    runWaitingPick();
                });
            }
        });
    }

    /**
     * The pane a switch to {@code hostId} shows from cache while it connects: the pick, else
     * that computer's Deacon from its last list, else the pane last used there; "" when none
     * is known (then the switch waits for the live list as before).
     */
    private String cachedLanding(String hostId, String pane) {
        if (pane != null && !pane.isEmpty()) return pane;
        JSONArray[] last = lastLists(hostId);
        if (last != null) {
            try {
                String deacon = FleetPanes.switchLanding(new JSONObject().put("workspaces", last[0])
                        .put("tabs", last[1]).put("panes", last[2]), "");
                if (!deacon.isEmpty()) return deacon;
            } catch (JSONException unexpected) {
                // Fall back to the remembered pane below.
            }
        }
        return rememberedHostPane(hostId);
    }

    /** How long a machine switch waits for the new host's list, as before reconnects were bounded. */
    private static final long MACHINE_SWITCH_READY_MS = 45_000;

    /** " (the network came back N ms ago)", when it did while the terminal was on screen; else "". */
    private String sinceNetwork() {
        long arrived = monitor == null ? 0 : monitor.arrivedAt();
        return arrived <= 0 ? "" : " (network back " + (SystemClock.uptimeMillis() - arrived) + " ms ago)";
    }

    /** Where a new session lands (HostLanding), on this phone's native session and saved panes. */
    private String awaitHostPane(long handle, String hostId, String forcedPane, boolean machineSwitch,
            long readyWithinMs) throws Exception {
        HostLanding.Link link = new HostLanding.Link() {
            @Override public JSONObject poll() throws Exception { return new JSONObject(RemoteNative.poll(handle, 0, false)); }
            @Override public void focus(String pane) { RemoteNative.focus(handle, pane); }
        };
        HostLanding.Memory memory = new HostLanding.Memory() {
            @Override public String remembered(String host) { return rememberedHostPane(host); }
            @Override public void remember(String host, String pane) { rememberHostPane(host, pane); }
        };
        return new HostLanding(link, memory, SystemClock::uptimeMillis, Thread::sleep)
                .await(hostId, forcedPane, machineSwitch, readyWithinMs);
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
            boolean resume = previous != null && (previous.remoteHandle > 0 || previous.offline)
                    && host.id.equals(currentHostId);
            checkpoint.reset();
            next = new Connection(GENERATIONS.incrementAndGet(), handle);
            if (resume) {
                next.workspaces = previous.workspaces;
                next.tabs = previous.tabs;
                next.panes = previous.panes;
            }
            next.resumed = resume;
            next.hostId = host.id;
            next.connectionRevision = host.connectionRevision;
            next.fingerprint = host.expectedFingerprint;
            next.session = host.session;
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
        rail.refreshSidebar(true);
        runWaitingPick();
    }

    /** The pick kept while a switch ran (HostPick.Queue): the latest one. */
    final HostPick.Queue hostPicks = new HostPick.Queue();

    /** Runs the pick kept during a switch, after it (posted: the switch's own state settles first). */
    private void runWaitingPick() {
        if (!hostPicks.waiting()) return;
        main.post(() -> {
            HostPick pick = hostPicks.take();
            if (pick == null || destroyed) return;
            if (pick.pin != null) { rail.openPin(pick.pin); return; }
            SavedRemoteHost host = savedHost(pick.hostId);
            if (host == null) return;
            if (host.id.equals(currentHostId)) {
                Connection now;
                synchronized (stateLock) { now = connection; }
                if (now != null && pick.pane != null && !pick.pane.isEmpty()) focusRemotePane(now, pick.pane);
                return;
            }
            switchHost(host, pick.pane, pick.picked);
        });
    }

    private SavedRemoteHost savedHost(String id) {
        if (id == null) return null;
        for (SavedRemoteHost h : savedHosts) if (h.id.equals(id)) return h;
        return null;
    }

    String hostName(String id) {
        SavedRemoteHost h = savedHost(id);
        return h == null ? "" : h.name;
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
        // Item 83: the stored choice is open, minimized to a strip, or closed.
        int widthDp = getResources().getConfiguration().screenWidthDp;
        // A drawer open across the fold ends here: it never decides the docked choice.
        railPanel = storedRailPanel();
        RailPanel.Shown next = RailPanel.Shown.configured(railPanel, widthDp >= 600 && !railCompact(), shellMode());
        boolean opening = next.open && !sidebarOpen;
        setRailShown(next);
        applySidebarWidth();
        updateHostSampling();
        // A closed rail builds no rows (refreshSidebar), so one docked by
        // unfolding would stay empty until the tree next changed: fill it now.
        if (opening) rail.refreshSidebar(true);
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
        // The stored choice is for a pinned panel with room; a drawer (the
        // cover's, or one opened by a gesture) is transient: it shuts to what
        // was docked, the strip included.
        if (!railAsDrawer()) storeRailPanel(railPanel.with(open ? RailPanel.Mode.OPEN : RailPanel.Mode.CLOSED));
        setRailShown(railShown().toggled(open, railCompact()));
        applySidebarWidth();
        updateHostSampling();
        if (sidebarOpen) {
            revealedPane = "";
            rail.revealActiveOnOpen();
            rail.refreshSidebar(true);
        }
    }

    /** This device's choice for the docked rail (item 83); the older open/closed flag before it. */
    private RailPanel storedRailPanel() {
        SharedPreferences preferences = getSharedPreferences("terminal-display", MODE_PRIVATE);
        return RailPanel.read(preferences.getString(RailPanel.MODE_KEY, null),
                preferences.getString(RailPanel.LAST_KEY, null),
                preferences.contains(RailPanel.LEGACY_KEY) ? preferences.getBoolean(RailPanel.LEGACY_KEY, true) : null);
    }

    private void storeRailPanel(RailPanel next) {
        railPanel = next;
        getSharedPreferences("terminal-display", MODE_PRIVATE).edit()
                .putString(RailPanel.MODE_KEY, RailPanel.name(next.mode))
                .putString(RailPanel.LAST_KEY, RailPanel.name(next.last))
                .putBoolean(RailPanel.LEGACY_KEY, next.mode == RailPanel.Mode.OPEN).apply();
    }

    /**
     * The docked rail to {@code next} (item 83): open beside the terminal, a
     * strip with the needs-you badge, or closed. The terminal takes the width
     * it frees, as the toolbar's toggle has always made it. On the cover there
     * is nothing docked: the rail stays the drawer its button opens.
     */
    private void setRailPanel(RailPanel next) {
        if (shellMode()) return;
        if (compactWindow()) {
            note("Unfold the phone to dock, minimize or close the rail");
            return;
        }
        storeRailPanel(next);
        RailPanel.Shown shown = RailPanel.Shown.docked(next);
        boolean opening = shown.open && !sidebarOpen;
        setRailShown(shown);
        applySidebarWidth();
        updateHostSampling();
        if (opening) {
            revealedPane = "";
            rail.revealActiveOnOpen();
            rail.refreshSidebar(true);
        }
    }

    /** ctrl-alt-w closes the rail and brings back what was there; ctrl-alt-b minimizes it and back. */
    private void railKey(boolean close) {
        if (compactWindow()) {
            toggleSidebar();
            return;
        }
        // What the rail shows now, an open drawer over the terminal included, is what the key acts on.
        RailPanel now = sidebarOpen && railAsDrawer() ? railPanel.with(RailPanel.Mode.OPEN) : railPanel;
        setRailPanel(close ? now.toggle() : now.minimize());
    }

    /** The docked rail's header: minimize to the strip, close (item 83). */
    private LinearLayout createRailControls() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL | android.view.Gravity.END);
        row.setPadding(0, 0, dp(4), 0);
        row.addView(railControl("–", "Minimize the rail to a strip (ctrl-alt-b)",
                () -> setRailPanel(railPanel.with(RailPanel.Mode.MINIMIZED))));
        row.addView(railControl("×", "Close the rail (ctrl-alt-w brings it back)",
                () -> setRailPanel(railPanel.with(RailPanel.Mode.CLOSED))));
        return row;
    }

    private TextView railControl(String glyph, String description, Runnable action) {
        TextView button = new TextView(this);
        button.setText(glyph);
        button.setTextSize(16);
        button.setGravity(android.view.Gravity.CENTER);
        button.setContentDescription(description);
        button.setTooltipText(description);
        button.setOnClickListener(view -> action.run());
        // A 48 dp touch target, as every control here; the glyph stays small.
        button.setLayoutParams(new LinearLayout.LayoutParams(dp(48), dp(48)));
        return button;
    }

    /** The minimized rail: a strip that opens it again and keeps the needs-you badge. */
    private LinearLayout createRailStrip() {
        LinearLayout strip = new LinearLayout(this);
        strip.setOrientation(LinearLayout.VERTICAL);
        strip.setGravity(android.view.Gravity.CENTER_HORIZONTAL);
        strip.setPadding(0, dp(6), 0, 0);
        strip.setVisibility(View.GONE);
        strip.setContentDescription("Open the rail");
        strip.setOnClickListener(view -> setRailPanel(railPanel.with(RailPanel.Mode.OPEN)));
        TextView open = new TextView(this);
        open.setText("»");
        open.setTextSize(18);
        open.setGravity(android.view.Gravity.CENTER);
        open.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        strip.addView(open, new LinearLayout.LayoutParams(-1, dp(40)));
        railStripBadge = needYouBadge(12);
        strip.addView(railStripBadge, new LinearLayout.LayoutParams(-2, -2));
        return strip;
    }

    /** The needs-you count as a small filled badge; hidden while nobody waits. */
    private TextView needYouBadge(int textSp) {
        TextView badge = new TextView(this);
        badge.setTextSize(textSp);
        badge.setGravity(android.view.Gravity.CENTER);
        badge.setSingleLine(true);
        badge.setMinWidth(dp(16));
        badge.setPadding(dp(4), 0, dp(4), 0);
        badge.setVisibility(View.GONE);
        badge.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        return badge;
    }

    /** The strip's and the toolbar button's badge follow the bar's count of agents waiting on Wolf. */
    private void updateRailBadge() {
        String text = RailPanel.badge(barNeedYou);
        String waiting = text.isEmpty() ? "" : ", " + barNeedYou + (barNeedYou == 1 ? " agent needs" : " agents need") + " you";
        for (TextView badge : new TextView[] {railStripBadge, sidebarBadge}) {
            if (badge == null) continue;
            if (!text.contentEquals(badge.getText())) badge.setText(text);
            android.graphics.drawable.GradientDrawable fill = new android.graphics.drawable.GradientDrawable();
            fill.setColor(chrome.status("blocked"));
            fill.setCornerRadius(dp(8));
            badge.setBackground(fill);
            badge.setTextColor(chrome.surface);
            // The toolbar's copy only while the rail is not open: open, the rail's header says it.
            boolean wanted = !text.isEmpty() && (badge == railStripBadge || !sidebarOpen);
            badge.setVisibility(wanted ? View.VISIBLE : View.GONE);
        }
        if (railStrip != null) railStrip.setContentDescription("Open the rail" + waiting);
        if (sidebarButton != null && !sidebarOpen) {
            sidebarButton.setContentDescription("Show the host and workspace sidebar" + waiting);
        }
    }

    private void applySidebarWidth() {
        if (sidebar == null) return;
        applyWorkPanel();
        placeRail();
        int available = terminalRoot == null ? 0
                : terminalRoot.getWidth() - terminalRoot.getPaddingLeft() - terminalRoot.getPaddingRight();
        int width;
        boolean strip = railShown().strip(railCompact());
        if (!sidebarOpen) {
            width = strip ? dp(RailPanel.STRIP_DP) : 0;
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
        if (railStrip != null) {
            railStrip.setVisibility(strip ? View.VISIBLE : View.GONE);
            railBody.setVisibility(strip ? View.GONE : View.VISIBLE);
            railFade.setVisibility(strip ? View.GONE : View.VISIBLE);
            // A drawer closes by Back or a tap outside; only the docked rail has the header.
            railControls.setVisibility(sidebarOpen && !railAsDrawer() ? View.VISIBLE : View.GONE);
        }
        updateRailToggle();
        updateRailBadge();
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
    /** The top corner's link indicator (LinkBadge); the crumb keeps saying where this is. */
    private TextView linkBadge;
    /** The indicator and its in-place actions, in the tab row. */
    private LinearLayout linkBox;
    /** The in-place actions: Reconnect (or New shell) and Hosts. */
    private TextView linkPrimary, linkHosts;
    /** Whether those actions show (LinkActions). Main thread. */
    private final LinkActions linkActions = new LinkActions();
    /** The touch sequence on the indicator's controls (linkTouch), until it ends. Main thread. */
    private final LinkActions.Press linkPress = new LinkActions.Press();
    /** How the raw shell ended, or null while it runs. Main thread. */
    private LinkBadge.ShellEnd shellEnd;
    /** The tab row's More button; a raw shell has none. */
    private ImageButton tabMenu;
    /** What the indicator shows now. Main thread. */
    private LinkBadge.Look linkLook = LinkBadge.HIDDEN;
    /** A failure the indicator explains on tap (the old banner's text), or null. Main thread. */
    private String linkProblem;
    /** Reconnects a dropped remote session while this terminal is on screen. */
    private ConnectionMonitor monitor;
    /** Held composer messages, on disk; shared by every terminal activity. Sent only by a tap. */
    private OutboxCourier outbox;
    /** Which machine each profile revision reaches, proven by a direct hello. */
    MachineBindings bindings;
    /** The live pane's one-tap offer: a held message for it, or a draft from before a restart. */
    private HeldOfferCard heldOffer;
    /** Agents' transcripts: read while a direct link is up, offered offline where proven. */
    private TranscriptKeeper transcripts;
    /** The offline transcript's button, and what it was last looked up for. Main thread. */
    private TextView transcriptButton;
    /** Opens the transcript the button offers, checked again then; null while none is offered. */
    private Runnable transcriptOpen;
    /** The offered transcript holds more than the screen: a drag past the top opens it. */
    private boolean transcriptHistory;
    private String transcriptQuery = "";
    /** Counts lookups: an answer, or a tap, for an older one is stale. Main thread. */
    private long transcriptSeq;
    /** Whether the network is metered, checked at most every few seconds. Poll thread. */
    private volatile boolean meteredNow;
    private long meteredAt;
    /** The pane whose draft offer was put off ("Later"): it stays kept and comes back elsewhere. Main thread. */
    private String draftLater;
    /** What the offer was last refreshed for (pane, attach, paint), so polls repaint nothing. */
    private volatile String offerSignature = "";
    /** Each pane's last grid on disk, drawn when the terminal opens with no link. */
    private PaneSnapshots snapshots;
    /**
     * The computer whose saved pane is on screen (until its live pane attaches),
     * and when it was saved: 0 for a pane with no copy (its title and a note).
     */
    private volatile String savedShownHost;
    private volatile long savedShownAt;
    /** When to checkpoint the pane on screen. Under stateLock. */
    private final PaneCheckpoint checkpoint = new PaneCheckpoint();
    /** Each computer's lists as this terminal last saw them live: newer than Home's cache. */
    private final java.util.Map<String, JSONArray[]> seenLists = new java.util.concurrent.ConcurrentHashMap<>();
    /** Home's last view of each computer, for switching with no link; read once per resume. */
    private volatile java.util.Map<String, HomeCache.Entry> fleetCache = java.util.Collections.emptyMap();
    /** The terminal has been on screen before (later resumes reopen the pane last shown). */
    private boolean openedOnce;
    /** A reconnect is running: the one numbered {@link #reconnectSeq}. Main thread. */
    private boolean reconnectRunning;
    /**
     * The latest reconnect's number. A newer attempt replaces a running one; the older one's
     * result is then ignored. Written on the main thread, read by attempts on the worker.
     */
    private volatile int reconnectSeq;
    /**
     * Which computer was picked last: an attempt (reconnect or switch) that
     * began before another pick is stale and must not adopt its host.
     */
    private final HostEpoch hostEpoch = new HostEpoch();
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
    void refreshTabStrip() {
        if (tabStrip == null || destroyed) return;
        Connection current;
        synchronized (stateLock) { current = connection; }
        if (workPanel != null && current != null && current.chromeRevision != workPanelRevision) {
            workPanelRevision = current.chromeRevision;
            java.util.List<BeadsModel.Term> terms = current.panes == null ? java.util.Collections.emptyList()
                    : BeadsModel.terms(current.workspaces, current.tabs, current.panes);
            workPanel.select(PanelModel.agentOf(terms, current.paneId), current.hostId, current.hostName, terms);
        }
        if (usageFeed != null) {
            String usageHost = current == null ? null : current.hostId;
            usageFeed.select(usageHost, current != null && current.ready && !current.isClosed(), () -> usageLook(usageHost));
        }
        if (rail.pinsRail != null) {
            JSONObject shown = current == null ? null : PinsRail.paneOf(current.panes, current.paneId);
            boolean agent = shown != null && !shellMode()
                    && (!shown.optString("kind", "").isEmpty() || !shown.optString("role", "").isEmpty());
            rail.pinsRail.bindScreen(current == null ? null : current.machine, current != null && current.ready && !current.isClosed(),
                    current != null && !current.ready && !current.isClosed(), current == null ? null : current.hostId,
                    current == null ? null : current.hostName, current == null ? null : current.session,
                    current == null ? null : current.panes, current == null ? "" : current.paneId, agent, rail.railMembers);
        }
        // A touch from now on carries this pane's stamp with its down.
        terminalTap.show(current == null ? "" : current.generation + ":" + current.paneId);
        if (composer != null) {
            String kind = current == null || shellMode() ? "" : agentKind(current);
            composer.setPane(current == null ? "" : current.hostId,
                    current == null ? null : current.connectionRevision,
                    current == null ? "" : current.paneId, kind);
            // Agent and shell panes differ in whether the keyboard keeps its mic.
            refreshTerminalImeOptions();
            refreshQueued();
        }
        boolean show = current != null && !shellMode() && current.tabs != null;
        tabScroll.setVisibility(show ? View.VISIBLE : View.GONE);
        // The menu stays wherever the bar is gone, tabs or not.
        if (tabRow != null) {
            // A raw shell has no tabs or menu here; only its end's indicator shows.
            tabRow.setVisibility((current != null && !shellMode()) || linkLook.shown ? View.VISIBLE : View.GONE);
            tabMenu.setVisibility(shellMode() ? View.GONE : View.VISIBLE);
        }
        if (!show) {
            if (barNeedYou != 0) {
                barNeedYou = 0;
                updateRailBadge();
            }
            return;
        }
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
        // The project focus is in and its windows only (Wolf's items 60, 70, 78; the desktop's
        // status line): window 1 its lead, the shown one filled. Nothing toggles it.
        java.util.List<ProjectModel.Project> projects = ProjectModel.ordered(
                ProjectModel.projects(current.workspaces, current.tabs, current.panes), current.projectsOrder);
        ProjectModel.Project project = ProjectModel.currentOf(projects, current.paneId, paneTab, space);
        int shown = ProjectModel.shownIndex(project, current.paneId, paneTab);
        boolean compact = compactWindow();
        java.util.List<ProjectBar.Chip> chips = ProjectBar.chips(project, shown, compact);
        int needYou = ProjectBar.needYou(projects);
        if (needYou != barNeedYou) {
            barNeedYou = needYou;
            updateRailBadge();
        }
        String repo = compact || project == null || project.deacon ? null
                : ProjectModel.repoOf(project, gitNames(), ProjectModel.repoLabels(current.panes));
        // Rebuild only when what the bar shows changes. Agents retitle their
        // panes many times a second (spinners), and rebuilding then dropped
        // taps and yanked the strip back to the active tab mid-swipe.
        boolean ours = current.hostId != null && current.hostId.equals(pendingShell.hostId());
        // Only a new shell ends the wait: an agent or another client's tab arriving meanwhile is not it.
        boolean shellChanged = ours && pendingShell.observe(current.hostId, paneIds(current, true),
                android.os.SystemClock.elapsedRealtime());
        String pending = ours ? pendingShell.pill() : null;
        if (shellChanged && pendingShell.state() == PendingShell.State.SLOW) {
            tabStrip.announceForAccessibility(pendingShell.described(current.hostName));
        }
        String signature = ProjectBar.signature(projects, project, repo, chips, needYou, compact) + "|" + pending;
        // Everything drawn before the shown chip, and it: a change there moves it.
        String before = ProjectBar.drawn(project, repo, chips, shown);
        String activeId = shown >= 0 ? project.key + "#" + ProjectModel.windowId(project.windows.get(shown)) : "";
        if (signature.equals(tabSignature)) return;
        // A fold (the cover cuts names), or windows before it (or it) renamed,
        // move the shown chip too.
        boolean activeMoved = !activeId.equals(tabActive) || compact != tabCompact
                || !before.equals(tabBefore);
        tabCompact = compact;
        tabBefore = before;
        tabSignature = signature;
        tabActive = activeId;
        tabStrip.removeAllViews();
        final Connection target = current;
        // On the cover the breadcrumb below already names the project and repo (t42y C4/C5).
        if (!compactWindow()) tabStrip.addView(projectChip(project));
        if (repo != null && !compactWindow()) {
            TextView crumbText = new TextView(this);
            crumbText.setText(ProjectBar.crumbText(repo));
            crumbText.setTextSize(11);
            crumbText.setSingleLine(true);
            crumbText.setTextColor(chrome.onSurfaceVariant);
            crumbText.setGravity(android.view.Gravity.CENTER_VERTICAL);
            crumbText.setPadding(0, 0, dp(6), 0);
            tabStrip.addView(crumbText, new LinearLayout.LayoutParams(-2, dp(24)));
        }
        java.util.List<View> windowChips = new java.util.ArrayList<>();
        for (int i = 0; i < chips.size(); i++) {
            ProjectBar.Chip c = chips.get(i);
            ProjectModel.Window w = c.window;
            final int index = i;
            TextView chip = windowChip(c.text, c.active, w.status, w.terminal == null);
            chip.setTag(c.active ? PANE_PILL_ACTIVE : PANE_PILL);
            chip.setOnClickListener(v -> {
                if (c.active) return;
                if (w.terminal != null) focusRemotePane(target, w.terminal);
                else note("Nothing runs in " + w.name + ": open it on the desktop");
            });
            Runnable menu = () -> showWindowActions(target, w);
            if (w == project.lead) {
                // Window 1 stays the lead: it is never dragged (item 79).
                chip.setOnLongClickListener(v -> { menu.run(); return true; });
            } else {
                // Held, then dragged: Wolf's order of the project's windows (item 79), kept in this
                // core's projects.order as the desktop keeps it. Held and let go: the menu.
                chip.setOnTouchListener(new WindowDrag(chip, menu, centre -> {
                    float[] centres = new float[windowChips.size()];
                    for (int k = 0; k < centres.length; k++) {
                        View other = windowChips.get(k);
                        centres[k] = other.getLeft() + other.getWidth() / 2f;
                    }
                    ProjectModel.WindowMove move = ProjectBar.dropMove(project, index, (float) centre, centres);
                    if (move != null) moveWindow(target, move);
                }));
            }
            chip.setContentDescription("Window " + w.n + ", " + w.name + (w.status.isEmpty() ? "" : ", " + w.status)
                    + (c.active ? ", current" : "") + (w == project.lead ? "" : ". Hold and drag to reorder"));
            tabStrip.addView(chip);
            windowChips.add(chip);
        }
        if (pending != null) {
            PendingShell.State shellState = pendingShell.state();
            // Not a window yet: drawn as an inactive chip, one marker in its own words, red when it failed.
            TextView pill = windowChip(pending, false, "", false);
            pill.setTextColor(shellState == PendingShell.State.FAILED ? chrome.danger : chrome.onSurfaceVariant);
            pill.setContentDescription(pendingShell.described(target.hostName));
            // A tap only where there is something to choose: stop waiting, or retry / close.
            if (shellState == PendingShell.State.FAILED) pill.setOnClickListener(v -> showPendingShellFailure(target));
            else if (shellState == PendingShell.State.SLOW) pill.setOnClickListener(v -> showPendingShellWait(target));
            else pill.setClickable(false);
            tabStrip.addView(pill);
        }
        TextView add = windowChip("+", false, "", false);
        add.setContentDescription(project != null && project.led()
                ? "New Claude, Codex or terminal window of " + project.name
                : "New Claude, Codex or terminal tab in this space");
        add.setOnClickListener(this::showNewMenu);
        tabStrip.addView(add);
        if (!compact && needYou > 0) {
            TextView waiting = new TextView(this);
            waiting.setText("! " + needYou + " need you");
            waiting.setTextSize(12);
            waiting.setSingleLine(true);
            waiting.setTextColor(chrome.status("blocked"));
            waiting.setGravity(android.view.Gravity.CENTER_VERTICAL);
            waiting.setPadding(dp(10), 0, dp(6), 0);
            waiting.setContentDescription(needYou + " agents need you; go to the next one");
            waiting.setOnClickListener(v -> focusNextWaiting(target, projects));
            tabStrip.addView(waiting, new LinearLayout.LayoutParams(-2, dp(24)));
        }
        if (activeMoved) {
            revealPending = true;
            tabScroll.post(this::revealActiveChip);
        }
    }

    private boolean tabCompact;
    /** The chips up to the shown one as last drawn: their text moves it. */
    private String tabBefore = "";
    /** The shown chip is to be revealed once the rebuilt bar is laid out. */
    private boolean revealPending;

    /**
     * Scrolls the bar so the shown window's chip is whole, by its measured
     * bounds after layout, and only when it is not: a scroll Wolf made himself
     * stays where he left it.
     */
    private void revealActiveChip() {
        if (tabStrip == null || tabScroll == null) return;
        View active = tabStrip.findViewWithTag(PANE_PILL_ACTIVE);
        int width = tabScroll.getWidth();
        if (active == null || active.getWidth() == 0 || width == 0) return;
        // Never wider than the bar: a name too long for it is cut with "…",
        // so the shown chip can always be whole.
        if (active instanceof TextView && ((TextView) active).getMaxWidth() != width) {
            TextView text = (TextView) active;
            text.setEllipsize(android.text.TextUtils.TruncateAt.END);
            text.setMaxWidth(width);
            if (active.getWidth() > width) {
                revealPending = true; // revealed after this relayout
                return;
            }
        }
        revealPending = false;
        int to = ProjectBar.revealScroll(active.getLeft(), active.getRight(), tabScroll.getScrollX(), width, dp(24));
        if (to >= 0) tabScroll.smoothScrollTo(to, 0);
    }

    /**
     * A window's menu. Its tab holds only this project's windows: the tab's
     * own menu (rename, split, move, close: every terminal in it). A tab it
     * shares with another project's terminal: this window's terminal only,
     * so nothing of the other project is ever ended or moved with it.
     */
    private void showWindowActions(Connection target, ProjectModel.Window w) {
        // Decided from the view as it is now, and again before anything
        // tab-wide is done: another project's terminal may join the tab
        // while the bar (or this menu) is shown.
        java.util.function.BooleanSupplier owned = () -> windowOwnsTab(target, w);
        if (owned.getAsBoolean()) {
            showTabActions(target, w.space, w.tab, w.name, owned);
            return;
        }
        if (w.terminal == null) {
            note(w.name + " shares its tab with another project; change it on the desktop");
            return;
        }
        new ActionSheet(this, chrome, w.name + " · shares its tab with another project")
                .danger("Close this window only", () ->
                        runEdit(target, "Close " + w.name, "op", "pane.kill", "terminal", w.terminal))
                .show();
    }

    /** Saves a dragged window's place in this core's projects.order; the bar follows when it is read back. */
    private void moveWindow(Connection target, ProjectModel.WindowMove move) {
        runEdit(target, "Move window", "op", "windows.move", "project", move.project, "id", move.id,
                "target", move.target, "after", move.after, "shown", new JSONArray(move.shown));
    }

    /**
     * The project chip, `[name]` and how many of its own agents wait on Wolf:
     * a tap lists this computer's projects (the desktop's switcher); it never
     * changes which windows the bar lists (item 78).
     */
    private TextView projectChip(ProjectModel.Project project) {
        TextView chip = new TextView(this);
        // ProjectBar.projectChipText, styled: the name bold, the waiting count in blocked's colour.
        String drawn = ProjectBar.projectChipText(project);
        int name = ProjectBar.projectText(project).length();
        android.text.SpannableStringBuilder text = new android.text.SpannableStringBuilder(drawn);
        text.setSpan(new android.text.style.StyleSpan(android.graphics.Typeface.BOLD), 0, name, 0);
        if (drawn.length() > name) {
            text.setSpan(new android.text.style.ForegroundColorSpan(chrome.status("blocked")), name, drawn.length(), 0);
        }
        chip.setText(text);
        chip.setTextSize(12);
        chip.setSingleLine(true);
        chip.setTextColor(chrome.onSurface);
        chip.setGravity(android.view.Gravity.CENTER_VERTICAL);
        chip.setPadding(dp(4), 0, dp(8), 0);
        chip.setContentDescription("Project " + (project == null ? "none" : project.name)
                + (project != null && project.waiting > 0 ? ", " + project.waiting + " need you" : ""));
        // A label only (Wolf, 11-22): the rail switches projects; the chip opened a redundant list.
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-2, dp(24));
        params.setMarginEnd(dp(2));
        chip.setLayoutParams(params);
        return chip;
    }

    /**
     * A window chip (item 60): a box with a border, the shown one filled; a
     * status dot when an agent works, waits or is done there. {@code idle}:
     * a tab with nothing running in it.
     */
    private TextView windowChip(String label, boolean active, String status, boolean idle) {
        TextView chip = new TextView(this);
        android.text.SpannableStringBuilder text = new android.text.SpannableStringBuilder(label);
        if (ProjectBar.dotted(status)) {
            int color = "error".equals(status) ? chrome.danger : chrome.status(status);
            text.append(" ●", new android.text.style.ForegroundColorSpan(color), 0);
        }
        chip.setText(text);
        chip.setTextSize(12);
        chip.setSingleLine(true);
        chip.setTextColor(idle && !active ? chrome.onSurfaceVariant : chrome.onSurface);
        chip.setGravity(android.view.Gravity.CENTER_VERTICAL);
        chip.setPadding(dp(7), 0, dp(7), 0);
        android.graphics.drawable.GradientDrawable shape = new android.graphics.drawable.GradientDrawable();
        shape.setCornerRadius(dp(3));
        // The window on screen: the palette's quiet selected container (OLED: #17202e), not a bright accent fill.
        shape.setColor(active ? chrome.selected : chrome.background);
        shape.setStroke(dp(1), active ? chrome.selected : chrome.outline);
        chip.setBackground(new android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(chrome.hover), shape,
                new android.graphics.drawable.ColorDrawable(0xffffffff)));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-2, dp(24));
        params.setMarginEnd(dp(3));
        chip.setLayoutParams(params);
        return chip;
    }

    /** What git said of this computer's folders: folder -> its repo's name and whether it is a linked worktree. */
    private java.util.Map<String, ProjectModel.GitName> gitNames() {
        return ProjectModel.gitNames(currentHostId == null ? null : FleetPanes.LOOKS.get(currentHostId));
    }

    /** "! N need you": the next waiting agent after the one shown, round all of them. */
    private void focusNextWaiting(Connection target, java.util.List<ProjectModel.Project> projects) {
        java.util.List<String> waiting = new java.util.ArrayList<>();
        for (ProjectModel.Project p : projects) {
            for (ProjectModel.Window w : p.windows) if ("blocked".equals(w.status) && w.terminal != null) waiting.add(w.terminal);
        }
        if (waiting.isEmpty()) return;
        int at = waiting.indexOf(target.paneId);
        focusRemotePane(target, waiting.get((at + 1) % waiting.size()));
    }

    private void updateCrumb(Connection current, String space) {
        if (crumb == null || crumbNote != null) return;
        String title = "", status = "", cwd = "", spaceLabel = "";
        JSONArray panes = current.panes;
        for (int i = 0; panes != null && i < panes.length(); i++) {
            JSONObject pane = panes.optJSONObject(i);
            if (pane == null || !current.paneId.equals(pane.optString("id"))) continue;
            title = RailTree.fallbackTitle(HomeActivity.cleanTitle(
                    chromeTitle(pane.optString("title", ""), current.paneId), current.paneId), pane);
            status = pane.optString("status", "");
            cwd = pane.optString("cwd", "");
        }
        // The agent's name (AgentNames), as its rail row says it: no role badge, no id (factory-jmc).
        for (FleetTree.Member m : rail.railMembers) {
            if (m.id.equals(current.paneId) && !AgentNames.unnamed(m)) title = FleetPanes.displayTitle(m);
        }
        String named = RailTree.labels(current.workspaces, current.tabs, panes).get(space);
        if (named != null) spaceLabel = chromeTitle(named, space);
        String repo = RailTree.repoOfPath(cwd);
        java.util.List<String> parts = new java.util.ArrayList<>();
        parts.add(current.hostName);
        if (repo != null && !repo.equals(spaceLabel)) parts.add(repo);
        if (!spaceLabel.isEmpty()) parts.add(spaceLabel);
        if (!title.isEmpty()) parts.add(RailTree.withoutSpace(title, spaceLabel));
        android.text.SpannableStringBuilder text = new android.text.SpannableStringBuilder();
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
        long savedAt = savedShownAt;
        if (savedShownHost != null && savedAt > 0 && !current.ready) {
            // A saved screen is never the pane's content now: it says when it was saved.
            text.append("   ");
            int start = text.length();
            text.append("◷ ").append(OfflineSwitch.saved(OfflineSwitch.when(savedAt, System.currentTimeMillis(),
                    java.util.TimeZone.getDefault(), android.text.format.DateFormat.is24HourFormat(this),
                    java.util.Locale.getDefault())));
            text.setSpan(new android.text.style.ForegroundColorSpan(chrome.warning), start, text.length(), 0);
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
    void revealSelectedRow(boolean always) {
        Connection current;
        synchronized (stateLock) { current = connection; }
        if (current == null || sidebarScroll == null || !sidebarOpen) return;
        String pane = current.paneId;
        if (!always && pane.equals(revealedPane)) return;
        View row = selectedRailView(pane);
        if (row == null) return;
        revealedPane = pane;
        sidebarScroll.post(() -> {
            int top = row.getTop();
            int view = sidebarScroll.getHeight();
            int scroll = sidebarScroll.getScrollY();
            if (always || top < scroll || top + row.getHeight() > scroll + view) {
                sidebarScroll.smoothScrollTo(0, Math.max(0, top - view / 3));
            }
        });
    }

    /** A no-PM lead or a folded checkout has a header target rather than a separate pane row. */
    private View selectedRailView(String pane) {
        RailController.RailPaneRow row = rail.railPanes.get("pane:" + currentHostId + "/" + pane);
        if (row != null && row.row.getParent() != null) return row.row;
        for (RailController.RailWorkspaceRow header : rail.railWorkspaces.values()) {
            if (header.host != null && header.host.id.equals(currentHostId) && header.row.getParent() != null
                    && (header.row.isSelected() || header.firstPane.equals(pane))) return header.row;
        }
        return null;
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

    /**
     * Whether {@code w}'s tab is still its project's alone in {@code target}'s
     * view as it is now (ProjectModel.ownsTab); false when the window or its
     * project is gone, so nothing tab-wide is done on a guess.
     */
    private boolean windowOwnsTab(Connection target, ProjectModel.Window w) {
        if (w.tab == null || w.tab.isEmpty()) return false;
        java.util.List<ProjectModel.Project> all = ProjectModel.projects(target.workspaces, target.tabs, target.panes);
        for (ProjectModel.Project p : all) {
            for (ProjectModel.Window o : p.windows) {
                boolean same = w.terminal != null ? w.terminal.equals(o.terminal)
                        : o.terminal == null && w.tab.equals(o.tab) && w.space.equals(o.space);
                if (same) return w.tab.equals(o.tab) && w.space.equals(o.space) && ProjectModel.ownsTab(all, p, o);
            }
        }
        return false;
    }

    /**
     * A tab's menu. {@code owned}: checked again as each tab-wide action is
     * done; when the tab is no longer its project's alone, nothing is done.
     */
    private void showTabActions(Connection target, String space, String tab, String label,
            java.util.function.BooleanSupplier owned) {
        java.util.List<String[]> spaces = new java.util.ArrayList<>();
        JSONArray all = target.workspaces;
        for (int i = 0; all != null && i < all.length(); i++) {
            JSONObject item = all.optJSONObject(i);
            if (item != null && !space.equals(item.optString("id"))) {
                spaces.add(new String[] {item.optString("id"), chromeTitle(item.optString("label"), item.optString("id"))});
            }
        }
        java.util.List<TabMenu.Pane> inTab = TabMenu.panes(target.panes, space, tab);
        ActionSheet sheet = new ActionSheet(this, chrome, TabMenu.title(label, inTab))
                .add("Rename…", () -> ActionSheet.prompt(this, chrome, "Rename window", label,
                        name -> { if (stillOwned(owned, label)) runEdit(target, "Rename", "op", "tab.rename", "space", space, "tab", tab, "name", name); }))
                .add("Split: new shell beside it", () -> { if (stillOwned(owned, label)) runEdit(target, "Split", "op", "tab.split",
                        "space", space, "tab", tab); })
                .add("Move to space…", () -> {
                    ActionSheet pick = new ActionSheet(this, chrome, "Move " + label + " to");
                    for (String[] other : spaces) {
                        pick.add(other[1], () -> { if (stillOwned(owned, label)) runEdit(target, "Move", "op", "tab.space", "space", space,
                                "tab", tab, "to", other[0]); });
                    }
                    pick.show();
                });
        // Closing a tab never ends an agent unasked: an agent tab offers only its explicit End (TabMenu).
        String close = TabMenu.closeLabel(inTab);
        Runnable closeTab = () -> { if (stillOwned(owned, label)) runEdit(target, "Close " + label, "op", "tab.close",
                "space", space, "tab", tab); };
        // A busy shell's close says what it stops, in a dialog (as End does); idle shells just close.
        if (close != null && TabMenu.closeConfirms(inTab)) {
            sheet.add(close, () -> sheetBuilder().setTitle(close.replace("…", "?"))
                    .setMessage(TabMenu.stopMessage(inTab))
                    .setNegativeButton("Cancel", null)
                    .setPositiveButton("Stop and close", (d, w) -> closeTab.run())
                    .show());
        } else if (close != null) {
            sheet.add(close, closeTab);
        }
        String noClose = TabMenu.noCloseReason(inTab);
        if (noClose != null) {
            sheet.add("Close tab…", () -> sheetBuilder().setTitle("Close tab").setMessage(noClose)
                    .setPositiveButton("OK", null).show());
        }
        java.util.List<java.util.List<String>> detach = TabMenu.detach(inTab);
        if (detach != null) {
            // The desktop's Park: the agents leave the tab and stay in the rail under their lead.
            sheet.add(TabMenu.DETACH, () -> { if (stillOwned(owned, label)) runEdit(target, "Close " + label, "op", "tab.detach",
                    "space", space, "tab", tab, "park", new JSONArray(detach.get(0)), "kill", new JSONArray(detach.get(1))); });
        }
        for (TabMenu.Pane agent : inTab) {
            if (!agent.agent()) continue;
            // Confirmed by its own dialog, which names the agent, its state and its task.
            sheet.add(TabMenu.endLabel(agent), () -> sheetBuilder().setTitle(TabMenu.endLabel(agent))
                    .setMessage(TabMenu.endMessage(agent, inTab.size() - 1))
                    .setNegativeButton("Cancel", null)
                    .setPositiveButton("End agent", (d, w) -> {
                        if (stillOwned(owned, label)) runEdit(target, "End " + agent.title, "op", "pane.kill", "terminal", agent.id);
                    })
                    .show());
        }
        sheet.show();
    }

    private boolean stillOwned(java.util.function.BooleanSupplier owned, String label) {
        if (owned.getAsBoolean()) return true;
        note(label + " now shares its tab with another project: nothing was changed. Hold it again for its own menu");
        return false;
    }

    /**
     * A tab pill's touch: a tap switches (the click listener), a sideways swipe
     * scrolls the strip, a hold opens the actions, and a hold then drag lifts
     * the pill and drops it at a new position.
     */
    /**
     * Hold, then drag a window chip along the bar (item 79): it follows the
     * finger and, let go, reports where its centre is (bar coordinates). Held
     * and let go without moving: its menu. A plain tap stays the chip's click.
     */
    private final class WindowDrag implements View.OnTouchListener {
        private final View chip;
        private final Runnable menu;
        private final java.util.function.DoubleConsumer dropped;
        private final int slop = android.view.ViewConfiguration.get(GpuiTerminalActivity.this).getScaledTouchSlop();
        private float downX, downY;
        private boolean held, dragging;
        private final Runnable hold = new Runnable() {
            @Override public void run() {
                held = true;
                chip.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
                chip.animate().scaleX(1.08f).scaleY(1.08f).setDuration(90).start();
                chip.setElevation(dp(6));
                chip.getParent().requestDisallowInterceptTouchEvent(true);
            }
        };

        WindowDrag(View chip, Runnable menu, java.util.function.DoubleConsumer dropped) {
            this.chip = chip;
            this.menu = menu;
            this.dropped = dropped;
        }

        @Override public boolean onTouch(View view, MotionEvent event) {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX = event.getRawX();
                    downY = event.getRawY();
                    held = dragging = false;
                    chip.postDelayed(hold, android.view.ViewConfiguration.getLongPressTimeout());
                    return false; // the pressed state and click stay the view's own
                case MotionEvent.ACTION_MOVE: {
                    float dx = event.getRawX() - downX;
                    if (!held) {
                        if (Math.abs(dx) > slop || Math.abs(event.getRawY() - downY) > slop) chip.removeCallbacks(hold);
                        return false;
                    }
                    if (!dragging && Math.abs(dx) > slop) dragging = true;
                    if (dragging) chip.setTranslationX(dx);
                    return true;
                }
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL: {
                    chip.removeCallbacks(hold);
                    if (!held) return false;
                    boolean up = event.getActionMasked() == MotionEvent.ACTION_UP;
                    float centre = chip.getLeft() + chip.getTranslationX() + chip.getWidth() / 2f;
                    chip.animate().scaleX(1f).scaleY(1f).translationX(0).setDuration(120).start();
                    chip.setElevation(0);
                    chip.setPressed(false);
                    if (up && dragging) dropped.accept(centre);
                    else if (up) menu.run();
                    held = dragging = false;
                    return true;
                }
                default:
                    return false;
            }
        }
    }

    /** Where "+" puts a project's new window now (ProjectBar.newWindowPlace), or null: a plain new tab. */
    private String[] newWindowPlace(Connection target) {
        String space = "", tab = "";
        JSONArray panes = target.panes;
        for (int i = 0; panes != null && i < panes.length(); i++) {
            JSONObject pane = panes.optJSONObject(i);
            if (pane != null && pane.optString("id").equals(target.paneId)) {
                space = pane.optString("workspace_id");
                tab = pane.optString("tab_id");
            }
        }
        java.util.List<ProjectModel.Project> all = ProjectModel.ordered(
                ProjectModel.projects(target.workspaces, target.tabs, target.panes), target.projectsOrder);
        ProjectModel.Project p = ProjectModel.currentOf(all, target.paneId, tab, space);
        return ProjectBar.newWindowPlace(p, ProjectModel.shownIndex(p, target.paneId, tab));
    }

    /**
     * "+": a new Claude, Codex or shell window. Of a project with a lead, it
     * is that project's window, as the desktop's C-a c makes one: a tab in the
     * lead's space, in the shown window's folder (else the lead's), labelled
     * parent=<lead> on this core (window.new). Elsewhere a plain new tab in the
     * space shown.
     */
    private void showNewMenu(View anchor) {
        Connection target;
        synchronized (stateLock) { target = connection; }
        if (target == null) return;
        // Placed from the view as it is now, not as the bar was last drawn: a
        // window that changed folder since makes the new one there.
        String[] place = newWindowPlace(target);
        android.widget.PopupMenu menu = new android.widget.PopupMenu(
                new android.view.ContextThemeWrapper(this, chrome.popupTheme()), anchor);
        menu.getMenu().add(0, 1, 0, "Claude");
        menu.getMenu().add(0, 2, 1, "Codex");
        menu.getMenu().add(0, 3, 2, "Shell");
        menu.setOnMenuItemClickListener(item -> {
            newWindow(target, place, item.getItemId() == 1 ? "claude" : item.getItemId() == 2 ? "codex" : "shell");
            return true;
        });
        menu.show();
    }

    /** One new window of {@code kind} (claude, codex or shell), placed as "+" places it. */
    private void newWindow(Connection target, String[] place, String kind) {
        newWindow(target, place, kind, null);
    }

    /** {@code failed}, when given, hears the core's refusal (the quick shell's pill). */
    private void newWindow(Connection target, String[] place, String kind, java.util.function.Consumer<String> failed) {
        String label = "New " + TabMenu.harness(kind) + (target.hostName == null ? "" : " on " + target.hostName);
        if (place != null) {
            if (place[2] != null && place[2].startsWith("/")) {
                runEditOr(target, label, failed, "op", "window.new", "space", place[0], "lead", place[1], "folder", place[2], "kind", kind);
            } else {
                runEditOr(target, label, failed, "op", "window.new", "space", place[0], "lead", place[1], "kind", kind);
            }
            return;
        }
        runSessionAction(target, "claude".equals(kind) ? "agent.claude" : "codex".equals(kind) ? "agent.codex" : "tab.new", label, failed);
    }

    /** The quick shell's own pill while it starts, then why it didn't (PendingShell). */
    private final PendingShell pendingShell = new PendingShell();

    /** The view's terminal ids; {@code shellsOnly}: those with no agent kind. */
    private static java.util.Set<String> paneIds(Connection target, boolean shellsOnly) {
        java.util.Set<String> ids = new java.util.HashSet<>();
        JSONArray panes = target == null ? null : target.panes;
        for (int i = 0; panes != null && i < panes.length(); i++) {
            JSONObject pane = panes.optJSONObject(i);
            if (pane != null && (!shellsOnly || pane.optString("kind", "").isEmpty())) ids.add(pane.optString("id"));
        }
        return ids;
    }

    /** ">_": a new shell, with a "shell ◌" pill at the strip's end until it shows up or fails. */
    private void startQuickShell(Connection target) {
        pendingShell.start(target.hostId, paneIds(target, false), android.os.SystemClock.elapsedRealtime());
        tabSignature = "";
        refreshTabStrip();
        String host = target.hostId;
        main.postDelayed(() -> {
            // Only for this activity and the machine it was started on.
            if (destroyed || host == null || !host.equals(currentHostId)) return;
            tabSignature = "";
            refreshTabStrip();
        }, PendingShell.TIMEOUT_MS + 100);
        newWindow(target, newWindowPlace(target), "shell", why -> {
            pendingShell.failed(why);
            tabSignature = "";
            refreshTabStrip();
            tabStrip.announceForAccessibility(pendingShell.described(target.hostName));
        });
    }

    /** The slow pill's tap: it may still start, so the choice is only to stop showing the wait. */
    private void showPendingShellWait(Connection target) {
        new ActionSheet(this, chrome, "Still starting a shell on " + target.hostName)
                .add("Keep waiting", () -> { })
                .add("Stop waiting", () -> { pendingShell.clear(); tabSignature = ""; refreshTabStrip(); })
                .show();
    }

    /** The failed pill's tap: why, then Retry or Close. Nothing was left running. */
    private void showPendingShellFailure(Connection target) {
        new ActionSheet(this, chrome, "Couldn't start a shell on " + target.hostName + ": " + pendingShell.reason())
                .add("Retry", () -> { pendingShell.clear(); startQuickShell(target); })
                .add("Close", () -> { pendingShell.clear(); tabSignature = ""; refreshTabStrip(); })
                .show();
    }

    /** The toolbar's ">_": one tap, a new shell where "+" would put it; held, the same pick as "+". */
    private ImageButton quickShellButton() {
        StillPress[] still = new StillPress[1];
        ImageButton shell = iconButton(R.drawable.ic_bar_shell, "New shell",
                "New shell in a new tab. Hold for Claude or Codex", view -> {
                    // A press that began a scroll or drag across the bar makes nothing.
                    if (still[0] != null && !still[0].still()) return;
                    Connection target;
                    synchronized (stateLock) { target = connection; }
                    if (target != null && !target.isClosed()) startQuickShell(target);
                });
        shell.setOnLongClickListener(view -> { showNewMenu(view); return true; });
        still[0] = StillPress.on(shell);
        return shell;
    }

    /** The rail (RailController): its rows, folds, pins section, Deacon and worktree actions. */
    final RailController rail = new RailController(this);

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
            row.setBackground(rail.roundedDrawable(GpuiChrome.withAlpha(
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

    void focusRemotePane(Connection target, String paneId) {
        focusRemotePane(target, paneId, true);
    }

    void focusRemotePane(Connection target, String paneId, boolean picked) {
        if (paneId.isEmpty() || paneId.length() > 128
                || !paneId.matches("[A-Za-z0-9_./:-]+")) return;
        switch (OfflineSwitch.route(target.offline, target.isClosed(), target.remoteHandle)) {
            case REFUSE:
                note("Switch panes from Remote hosts for this connection");
                return;
            case OFFLINE:
                focusOffline(target, paneId, picked);
                return;
            default:
                break;
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
            if (picked) rail.requestRailReveal(target.hostId, paneId);
            rail.refreshSidebar(picked);
        } catch (Exception | LinkageError failure) {
            // The only failure left is a session that has ended (the switch
            // itself is a slot that cannot overflow): show the pane's saved
            // copy now; the reconnect lands on it.
            android.util.Log.w("herdr-switch", "switch to " + paneId + " failed: " + failure);
            focusOffline(target, paneId, picked);
        }
    }

    /**
     * A switch with no session (item 77): the pane is remembered, so the
     * reconnect lands on it; the selection moves at once; its saved copy is
     * drawn (or its title and a note when it has none). Nothing is asked of
     * the core: the live grid replaces this one when the pane attaches.
     */
    private void focusOffline(Connection target, String paneId, boolean picked) {
        String host = target.hostId != null ? target.hostId : currentHostId;
        rememberHostPane(host, paneId);
        synchronized (stateLock) {
            if (connection != target) return;
            target.paneId = paneId;
            target.pendingPane = "";
        }
        nativeResetPan();
        showSaved(host, paneId);
        if (picked) rail.requestRailReveal(host, paneId);
        rail.refreshSidebar(picked);
        // One attempt now unless one is running; the monitor keeps trying.
        reconnectCurrentHost();
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
        if (target.offline || target.remoteHandle > 0 && target.isClosed()) {
            note("That needs the link");
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

    static String chromeTitle(String value, String fallback) {
        String title = CHROME_CONTROLS.matcher(value).replaceAll(" ").trim();
        if (title.isEmpty()) title = fallback;
        return title.length() > 60 ? title.substring(0, 60) + "…" : title;
    }

    private View chromeDot(android.content.Context context, String status) {
        View dot = new View(context);
        dot.setBackground(rail.roundedDrawable(chrome.status(status), 4));
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

            @Override public boolean send(String text) { return sendComposerMessage(text, java.util.UUID.randomUUID().toString()); }

            @Override public boolean send(String text, String id) { return sendComposerMessage(text, id); }

            @Override public void typed() { markUsed(); }

            @Override public void enterKey() {
                queueComposerInput("{\"type\":\"keys\",\"keys\":[\"enter\"]}");
            }

            @Override public void promptMoved() {
                applyKeyboardCluster();
            }
            @Override public boolean flushHeld() {
                return GpuiTerminalActivity.this.flushHeld();
            }

            @Override public void toLive() {
                takeViewportForTyping();
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

            @Override public void showQueue(java.util.List<QueueView.Row> rows, java.util.List<Outbox.Message> messages) {
                queueSheet(rows, messages);
            }

            @Override public String takeQueued(String id) { return outbox == null ? null : outbox.take(id); }

            @Override public void resendQueued(String id) {
                if (outbox != null) outbox.resend(id);
            }

            @Override public String paneName(String pane) {
                Connection current;
                synchronized (stateLock) {
                    current = connection;
                }
                return paneTitle(current, pane);
            }

            @Override public Outbox.Blocked blockedLane() {
                Here at = here();
                return outbox == null || at.host.isEmpty() ? null : outbox.outbox().blocked(at.lane());
            }

            @Override public boolean moveAside(String id) {
                return outbox != null && outbox.moveAside(id);
            }

            @Override public java.util.List<String> clearableQueued() {
                // The rows the pane shows, by the same machine and terminal identity.
                Here at = here();
                if (outbox == null || at.host.isEmpty()) return java.util.Collections.emptyList();
                return outbox.outbox().clearable(at.machine, at.host, at.pane, System.currentTimeMillis());
            }

            @Override public void act(String id) { actOnHeld(id); }

            @Override public boolean queuedKnown(String id) {
                return outbox != null && outbox.outbox().known(id);
            }

            @Override public boolean queuedEditable(String id) {
                Outbox.Message m = outbox == null ? null : outbox.outbox().message(id);
                return m != null && m.state != Outbox.State.SENDING;
            }

            @Override public String queuedText(String id) {
                return outbox == null ? null : outbox.outbox().text(id);
            }

            @Override public boolean clearQueued(java.util.List<String> ids, long until) {
                return outbox != null && outbox.clear(ids, until);
            }

            @Override public boolean unclearQueued(java.util.List<String> ids) {
                return outbox == null || outbox.unclear(ids);
            }

            @Override public void purgeCleared(java.util.List<String> ids) {
                if (outbox != null) outbox.purgeCleared(ids);
            }

            @Override public boolean outboxLoaded() {
                return outbox != null && outbox.outbox().loaded();
            }

            @Override public java.util.List<Outbox.Cleared> clearedQueued(long now) {
                Here at = here();
                if (outbox == null || at.host.isEmpty()) return java.util.Collections.emptyList();
                return outbox.outbox().clearedOn(at.machine, at.host, now);
            }

            @Override public java.util.List<String> liveQueuedTexts() {
                return outbox == null ? java.util.Collections.emptyList() : outbox.outbox().liveTexts();
            }

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

            @Override public boolean canAttach() { return true; }

            @Override public void attachImages() { showAttachSources(); }

            @android.annotation.TargetApi(31)
            @Override public boolean attachPasted(ContentInfo payload, Runnable nativePaste) {
                return queueImagePaste(payload.getClip(), nativePaste);
            }

            @Override public void attachFiles(java.util.List<java.io.File> files) {
                AttachTarget target = attachTarget();
                if (target == null) {
                    for (java.io.File file : files) file.delete();
                    note("Open an agent to attach an image");
                    return;
                }
                java.util.List<ImageAttach.Source> sources = new java.util.ArrayList<>();
                for (java.io.File file : files) sources.add(AttachImages.fileSource(file));
                attach(target, sources, () -> { for (java.io.File file : files) file.delete(); });
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


    // ---- Attaching images: the file lands on the host, its path goes into the composer. ----
    private static volatile boolean open;
    /** Whether a terminal screen exists to take a shared image (the share sheet checks). */
    static boolean isOpen() { return open; }
    static final String EXTRA_ATTACH_SHARED = "attach_shared";
    private final java.util.concurrent.ExecutorService attachWorker =
            java.util.concurrent.Executors.newSingleThreadExecutor(task -> {
                Thread thread = new Thread(task, "herdr-attach");
                thread.setDaemon(true);
                return thread;
            });

    /** The core session and pane an image is for, fixed when it was asked for. */
    private static final class AttachTarget {
        final long handle;
        final String pane;
        final String host;
        final String revision;

        AttachTarget(long handle, String pane, String host, String revision) {
            this.handle = handle;
            this.pane = pane;
            this.host = host;
            this.revision = revision;
        }

        boolean matches(Connection connection) {
            return host.equals(connection.hostId) && revision.equals(connection.connectionRevision)
                    && pane.equals(connection.paneId);
        }
    }

    /** The live core session and the pane on screen, or null with no link (nothing to put an image on). */
    private AttachTarget attachTarget() {
        synchronized (stateLock) {
            Connection c = connection;
            if (c == null || c.offline || c.shell || !c.ready || c.isClosed() || c.remoteHandle <= 0
                    || c.hostId == null || c.connectionRevision == null
                    || c.paneId == null || c.paneId.isEmpty()) return null;
            // An image's path belongs in an agent's composer; a plain shell's Enter bypasses the draft.
            if (!ComposerBar.isAgent(agentKind(c))) return null;
            return new AttachTarget(c.remoteHandle, c.paneId, c.hostId, c.connectionRevision);
        }
    }

    private static boolean profileStillCurrent(AttachTarget target) {
        return target.revision.equals(RemoteHostStore.connectionRevision(target.host));
    }

    /**
     * The live session for {@code target}'s pane right now. Leaving for a picker or the camera can
     * end the session and bring up a new one on return, so the handle from before is never reused:
     * waits up to 15 s for the original profile revision and pane to be live again.
     */
    private AttachTarget liveTarget(AttachTarget target, Supplier<String> pasteCurrent) {
        long until = SystemClock.elapsedRealtime() + 15_000;
        do {
            if (attachmentInterruption(target, pasteCurrent) != null) return null;
            synchronized (stateLock) {
                if (connection != null && target.matches(connection)) {
                    AttachTarget now = attachTarget();
                    if (now != null) return now;
                }
            }
            try {
                Thread.sleep(250);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return null;
            }
        } while (SystemClock.elapsedRealtime() < until);
        return null;
    }

    private AttachTarget cameraFor;

    private void showAttachSources() {
        if (attachTarget() == null) {
            note("Connect to a computer to attach an image");
            return;
        }
        new ActionSheet(this, chrome, "Attach an image")
                .add("Photo library", this::pickImages)
                .add("Take a photo", this::takePhoto)
                .add("Paste image from clipboard", this::pasteImage)
                .show();
    }

    private void pickImages() {
        cameraFor = attachTarget();
        try {
            startActivityForResult(AttachImages.pickIntent(), AttachImages.PICK_REQUEST);
        } catch (android.content.ActivityNotFoundException none) {
            note("No photo picker on this phone");
        }
    }

    private java.io.File cameraFile;

    private void takePhoto() {
        cameraFor = attachTarget();
        if (cameraFor == null) {
            note("Open an agent to attach an image");
            return;
        }
        try {
            cameraFile = CaptureFiles.create(getCacheDir(), System.currentTimeMillis());
            android.net.Uri target = android.net.Uri.parse(
                    "content://" + getPackageName() + ".capture/" + cameraFile.getName());
            android.content.Intent intent = AttachImages.cameraIntent(target, getPackageManager());
            if (intent != null) {
                startActivityForResult(intent, AttachImages.CAMERA_REQUEST);
                return;
            }
        } catch (android.content.ActivityNotFoundException none) {
            // A matching camera can disappear before launch.
        } catch (java.io.IOException | RuntimeException failed) {
            dropCapture();
            note("The camera could not start");
            return;
        }
        dropCapture();
        note("No camera app on this phone");
    }

    /** A capture that never started: only its own file goes (another shot's may still be uploading). */
    private void dropCapture() {
        java.io.File own = cameraFile;
        cameraFile = null;
        cameraFor = null;
        if (own != null) own.delete();
    }

    private void pasteImage() {
        android.content.ClipboardManager clipboard =
                (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        ClipData clip = clipboard == null ? null : clipboard.getPrimaryClip();
        if (clip == null) {
            note("No image on the clipboard");
            return;
        }
        queueImagePaste(clip, null);
    }

    /** The native fallback holds the receive-content payload and its temporary grant. */
    private boolean queueImagePaste(ClipData clip, Runnable nativePaste) {
        AttachTarget target = attachTarget();
        if (composer == null || (target == null && nativePaste == null)) {
            note("Open an agent to attach an image");
            return true;
        }
        Supplier<String> draftInterruption = composer.capturePasteInterruption();
        Supplier<String> pasteCurrent = () -> {
            synchronized (stateLock) {
                if (target != null && connection != null && !target.matches(connection)) {
                    return "Image paste cancelled: you switched panes";
                }
                return draftInterruption.get();
            }
        };
        try {
            attachWorker.execute(() -> {
                try {
                    List<ImageAttach.Source> sources = new java.util.ArrayList<>();
                    int imageCount = 0;
                    for (int i = 0; i < clip.getItemCount(); i++) {
                        if (attachmentInterruption(target, pasteCurrent) != null) break;
                        Uri uri = AttachImages.imageUri(clip.getItemAt(i));
                        if (uri == null || !isImageUri(uri)) continue;
                        imageCount++;
                        if (sources.size() < ImageAttach.MAX_IMAGES) {
                            sources.add(AttachImages.uriSource(getContentResolver(), uri));
                        }
                    }
                    // Classify every item before uploading, including items beyond the attach limit.
                    boolean mixed = imageCount != clip.getItemCount();
                    if (mixed || target == null) {
                        main.post(() -> {
                            if (destroyed) return;
                            String interrupted = attachmentInterruption(target, pasteCurrent);
                            if (interrupted != null) note(interrupted);
                            else if (mixed && nativePaste != null) nativePaste.run();
                            else if (mixed) note("The clipboard contains non-image items");
                            else note("Open an agent to attach an image");
                        });
                    } else {
                        attachOnWorker(target, sources, pasteCurrent);
                    }
                } finally {
                    java.lang.ref.Reference.reachabilityFence(nativePaste);
                }
            });
            return true;
        } catch (java.util.concurrent.RejectedExecutionException closed) {
            return false;
        }
    }

    @Override protected void onActivityResult(int request, int result, android.content.Intent data) {
        super.onActivityResult(request, result, data);
        AttachTarget target = cameraFor;
        if (request == AttachImages.PICK_REQUEST) {
            cameraFor = null;
            if (result == RESULT_OK && target != null) attachTo(target, AttachImages.pickedUris(data));
        } else if (request == AttachImages.CAMERA_REQUEST) {
            java.io.File shot = cameraFile;
            cameraFile = null;
            cameraFor = null;
            if (shot == null) return;
            if (result != RESULT_OK || target == null) {
                shot.delete();
                return;
            }
            if (shot.length() == 0) {
                shot.delete();
                note("The camera returned no picture");
                return;
            }
            attach(target, java.util.Collections.singletonList(AttachImages.fileSource(shot)), shot::delete);
        }
    }

    private void attachTo(AttachTarget target, java.util.List<android.net.Uri> uris) {
        java.util.List<ImageAttach.Source> sources = new java.util.ArrayList<>();
        for (android.net.Uri uri : uris) sources.add(AttachImages.uriSource(getContentResolver(), uri));
        attach(target, sources, null);
    }

    private boolean isImageUri(Uri uri) {
        try {
            return AttachImages.isImageMime(getContentResolver().getType(uri));
        } catch (RuntimeException unavailable) {
            return false;
        }
    }

    /**
     * Puts each image on the pane's computer (off the main thread, over the core connection), then
     * types the paths into that pane's draft, shell-quoted, never sent.
     */
    private void attach(AttachTarget target, java.util.List<ImageAttach.Source> sources, Runnable done) {
        if (sources.isEmpty()) return;
        try {
            attachWorker.execute(() -> {
                try {
                    attachOnWorker(target, sources, null);
                } finally {
                    if (done != null) done.run();
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException closed) {
            if (done != null) done.run();
        }
    }

    private static String attachmentInterruption(AttachTarget target, Supplier<String> pasteCurrent) {
        if (target != null && !profileStillCurrent(target)) {
            return "Image attachment cancelled: the remote profile changed";
        }
        return pasteCurrent == null ? null : pasteCurrent.get();
    }

    private static void requireAttachmentCurrent(AttachTarget target, Supplier<String> pasteCurrent)
            throws ImageAttach.AttachException {
        String interrupted = attachmentInterruption(target, pasteCurrent);
        if (interrupted != null) throw new ImageAttach.AttachException(interrupted);
    }

    private void attachOnWorker(AttachTarget target, List<ImageAttach.Source> sources, Supplier<String> pasteCurrent) {
        if (!sources.isEmpty() && attachmentInterruption(target, pasteCurrent) == null) {
            note(sources.size() == 1 ? "Attaching image…" : "Attaching " + sources.size() + " images…");
        }
        List<ImageAttach.Source> guarded = new java.util.ArrayList<>();
        for (ImageAttach.Source source : sources.subList(0, Math.min(ImageAttach.MAX_IMAGES, sources.size()))) {
            guarded.add(() -> {
                requireAttachmentCurrent(target, pasteCurrent);
                return source.read();
            });
        }
        ImageAttach.Outcome outcome = ImageAttach.attach(guarded, AttachImages.CONVERTER,
                (name, bytes) -> {
                    AttachTarget live = liveTarget(target, pasteCurrent);
                    requireAttachmentCurrent(target, pasteCurrent);
                    if (live == null) {
                        throw new ImageAttach.AttachException("The connection is down; try the image again");
                    }
                    try {
                        return RemoteNative.putFile(live.handle, live.pane, name, bytes);
                    } catch (RuntimeException dropped) {
                        String why = dropped.getMessage();
                        throw new ImageAttach.AttachException("The image may not have arrived (it could still be on the computer)"
                                + (why == null || why.isEmpty() ? "" : ": " + (why.length() > 120 ? why.substring(0, 120) : why)), dropped);
                    }
                },
                () -> java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(System.currentTimeMillis())
                        + System.nanoTime() % 1_000_000L);
        main.post(() -> attached(target, outcome, pasteCurrent));
    }

    private void attached(AttachTarget target, ImageAttach.Outcome outcome, Supplier<String> pasteCurrent) {
        if (destroyed) return;
        String interrupted = attachmentInterruption(target, pasteCurrent);
        if (interrupted != null) {
            note(interrupted);
            return;
        }
        if (!outcome.paths.isEmpty()) {
            boolean kept = composer != null
                    && composer.appendAttachment(target.host, target.revision, target.pane, outcome.paths);
            int n = outcome.paths.size();
            note(!kept ? "The image arrived but the draft could not be saved"
                    : outcome.failures.isEmpty() ? (n == 1 ? "Image attached" : n + " images attached")
                    : n + " attached, " + outcome.failures.size() + " failed: " + outcome.failures.get(0));
        } else if (!outcome.failures.isEmpty()) {
            note(outcome.failures.get(0));
        } else if (pasteCurrent != null) {
            note("No image on the clipboard");
        }
    }

    private int askedShare = -1;

    /**
     * Images from the share sheet wait here until the user chooses: once a live agent is on screen
     * this asks "attach to this agent?" (once per share). Nothing is uploaded before the answer, and
     * an answer takes only the share it was asked about.
     */
    private void takeSharedImages() {
        long now = System.currentTimeMillis();
        if (destroyed) return;
        if (!SharedImages.waiting(now)) {
            // Everything refused and nothing waiting: say why, once, wherever the sender lands.
            String why = SharedImages.takeNotice(now);
            if (why != null) note(why);
            return;
        }
        if (askedShare == SharedImages.generation()) return;
        AttachTarget target = attachTarget();
        if (target == null) {
            main.removeCallbacks(retrySharedImages);
            main.postDelayed(retrySharedImages, 1500);
            return;
        }
        int generation = SharedImages.generation();
        askedShare = generation;
        int count = SharedImages.count(now);
        String where;
        synchronized (stateLock) { where = connection == null || connection.title == null ? "this agent" : connection.title; }
        new ActionSheet(this, chrome, (count == 1 ? "Attach the shared image to " : "Attach " + count + " shared images to ") + where + "?")
                .add("Attach", () -> {
                    AttachTarget live = attachTarget();
                    if (live == null || !live.pane.equals(target.pane) || !live.host.equals(target.host)
                            || !live.revision.equals(target.revision) || !profileStillCurrent(target)) {
                        note("That agent is not on screen any more; open it and share again");
                        return;
                    }
                    // A newer share replaced the one this dialog was about: it takes nothing of it.
                    java.util.List<java.io.File> files = SharedImages.take(System.currentTimeMillis(), generation);
                    if (files.isEmpty()) {
                        note("A newer shared image replaced this one");
                        return;
                    }
                    java.util.List<ImageAttach.Source> sources = new java.util.ArrayList<>();
                    for (java.io.File file : files) sources.add(AttachImages.fileSource(file));
                    attach(live, sources, () -> { for (java.io.File file : files) file.delete(); });
                })
                .add("Not now (pick another agent in Home)", () -> { })
                .show();
    }

    private final Runnable retrySharedImages = this::takeSharedImages;

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

    /** Where the pane on screen is, as the outbox needs it. Under stateLock. */
    private static final class Here {
        final Connection connection;
        final String host, pane;
        /** The machine its profile revision is bound to (live, or proven earlier); null when unbound. */
        final MachineKey machine;
        /** Attached over a direct link, first paint done, its machine proven: a tap may type here. */
        final boolean live;
        /** Seen through another core's relay: live only, nothing is held for it. */
        final boolean relayed;
        final String refusedBy;
        final Long createdMs;
        final String label, status;
        /** Read once, with {@link #live}: the session handle, its attach, and its Herdr session. */
        final long handle, attach;
        final String session;

        Here(Connection c, String host, String pane, MachineKey machine, boolean live, String refusedBy,
                Long createdMs, String label, String status, long handle, long attach, String session) {
            this.handle = handle;
            this.attach = attach;
            this.session = session == null ? "" : session;
            this.connection = c;
            this.host = host == null ? "" : host;
            this.pane = pane == null ? "" : pane;
            this.machine = machine;
            this.live = live;
            this.relayed = this.pane.contains("/");
            this.refusedBy = refusedBy;
            this.createdMs = createdMs;
            this.label = label;
            this.status = status;
        }

        Outbox.Live lane() {
            return new Outbox.Live(machine, host, pane, createdMs, session);
        }

        /** Where a tap now would type: this pane, session and attach, taken together. */
        OfferGate.Tap tap() {
            return new OfferGate.Tap(pane, handle, attach);
        }

        boolean mayType(OfferGate.Tap tap, boolean covered) {
            return OfferGate.mayType(tap, pane, handle, attach, live, covered);
        }
    }

    private Here here() {
        synchronized (stateLock) {
            Connection c = connection;
            String host = c == null ? currentHostId : c.hostId;
            String pane = c == null ? "" : c.paneId;
            MachineKey machine = c == null ? null : c.machine;
            String refused = c == null ? null : c.refusedBy;
            if (machine == null && refused == null && c != null && c.hostId != null && bindings != null) {
                // Offline: the revision's binding from an earlier hello, never a guess.
                machine = bindings.machine(c.hostId, c.connectionRevision);
                MachineBindings.Binding binding = bindings.binding(c.hostId, c.connectionRevision);
                if (binding != null && !binding.bound()) refused = binding.refusedBy;
            }
            long attach = c == null ? 0 : c.attach;
            boolean live = c != null && c.machine != null && c.ready && !c.isClosed() && !c.offline
                    && c.painted && attach > 0 && c.remoteHandle > 0 && pane.equals(c.chromePane);
            Long created = null;
            String label = "", status = "";
            JSONArray panes = c == null ? null : c.panes;
            for (int i = 0; panes != null && i < panes.length(); i++) {
                JSONObject item = panes.optJSONObject(i);
                if (item == null || !pane.equals(item.optString("id"))) continue;
                long ms = item.optLong("created_ms", 0);
                created = ms > 0 ? ms : null;
                label = HomeActivity.cleanTitle(item.optString("title", ""), pane);
                status = item.optString("status", "");
            }
            return new Here(c, host, pane, machine, live, refused, created, label, status,
                    c == null ? 0 : c.remoteHandle, attach, c == null ? "" : c.session);
        }
    }

    /** The phone's boot count, so an age survives no reboot; -1 when it cannot be read. */
    private int bootCount() {
        try {
            return android.provider.Settings.Global.getInt(getContentResolver(),
                    android.provider.Settings.Global.BOOT_COUNT);
        } catch (android.provider.Settings.SettingNotFoundException | SecurityException unknown) {
            return -1;
        }
    }

    /**
     * Step 2 of the Send handoff (SendHandoff): the message is held under submission {@code id}
     * and fsynced before this returns, then acted on: typed at once when its pane is live and
     * nothing older waits in its lane, otherwise kept for a tap in that pane. A profile with no
     * proven machine, a refused one, or a relayed pane works live only and holds nothing. False
     * keeps the draft.
     */
    private boolean sendComposerMessage(String text, String id) {
        Here here = here();
        boolean awaits = DictationToken.in(text);
        if (here.machine == null || here.relayed) {
            boolean liveHere;
            synchronized (stateLock) {
                Connection c = here.connection;
                liveHere = c != null && c.ready && !c.isClosed() && !c.offline;
            }
            if (liveHere && !awaits) return typeLiveOnly(text);
            note(here.relayed ? "Open that computer directly to hold messages for it; the draft is kept"
                    : here.refusedBy != null ? "This computer reports the same Herdr as " + here.refusedBy
                            + "; it works live only. The draft is kept"
                    : "Not connected, and this computer was never reached from this profile; the draft is kept");
            return false;
        }
        if (here.pane.isEmpty()) {
            note("No pane is selected; the draft is kept");
            return false;
        }
        Outbox.Live lane = here.lane();
        int older = outbox.outbox().waitingIn(lane);
        String profileName;
        String session;
        synchronized (stateLock) {
            Connection c = here.connection;
            profileName = c == null ? "" : c.hostName;
            session = c == null ? "" : c.session;
        }
        Outbox.Target target = new Outbox.Target(here.machine, here.host, profileName, session, here.pane,
                here.createdMs, here.label, here.status);
        Outbox.Composed composed = new Outbox.Composed(System.currentTimeMillis(), SystemClock.elapsedRealtime(),
                bootCount());
        if (outbox.add(id, target, text, composed) == null) {
            note(!Outbox.sendable(text) ? "This message cannot be sent in one piece; the draft is kept"
                    : "Too many messages wait already, or the phone could not save it; the draft is kept");
            return false;
        }
        if (!here.live || awaits) {
            if (!here.live) note("Held for " + HeldWords.target(target, java.util.Collections.emptyList())
                    + "; send it from that pane once it is live");
            return true;
        }
        if (older > 0) {
            note(HeldWords.olderWaiting(older));
            return true;
        }
        // Step 4 waits for the draft's clear to reach the disk (actOnHeld): typed at once in the
        // pane on screen, on the attach on screen now, no lead.
        // Pane, session and attach as they were at the tap (here() ran before the outbox saved):
        // a switch while it saved can never redirect the message.
        OfferGate.Tap tap = here.tap();
        immediateId = id;
        immediate = () -> {
            Here now = here();
            if (!now.mayType(tap, covered())) {
                refreshOffer();
                return;
            }
            outbox.send(id, lane, tap.handle, tap.attach, text, true, outcome -> {
                if (outcome == Outbox.Outcome.UNWRITTEN) note("Not sent: the pane was not live; it waits in the queue");
                else if (outcome == Outbox.Outcome.UNSURE) note("It may have been sent, or may sit unsent in the input box");
            });
        };
        return true;
    }

    /** A held message to type at once, waiting for its draft's clear to be on disk. Main thread. */
    private String immediateId;
    private Runnable immediate;

    /** Step 4 of the Send handoff for {@code id}: its draft's clear is on disk. Main thread. */
    private void actOnHeld(String id) {
        Runnable run = id.equals(immediateId) ? immediate : null;
        immediateId = null;
        immediate = null;
        if (run != null) run.run();
    }

    /** Whether the file page covers the terminal: then no held message is offered or typed. */
    private boolean covered() {
        return filePager != null && filePager.showingFile();
    }

    /** A live-only pane: typed at once through the input queue, as before stage 1; nothing held. */
    private boolean typeLiveOnly(String text) {
        String command;
        try {
            command = GpuiTerminalProtocol.composeCommand(text);
        } catch (JSONException | IllegalArgumentException invalid) {
            note("Message is too long to send in one piece");
            return false;
        }
        return queueComposerInput(command);
    }

    /**
     * The live pane's offer (§3.5): its lane's head, with the target as it was when typed and its
     * age, and one Send that types it here. Without one, a draft from before a restart is offered
     * instead. Main thread.
     */
    private void refreshOffer() {
        if (heldOffer == null || destroyed || outbox == null) return;
        refreshTranscript(here());
        if (covered()) {
            heldOffer.hide();
            return;
        }
        Here here = here();
        Outbox.Message offer = here.live ? outbox.outbox().offer(here.lane()) : null;
        if (offer != null) {
            java.util.List<Outbox.Target> targets = new java.util.ArrayList<>();
            for (Outbox.Message m : outbox.outbox().all()) targets.add(m.target);
            java.util.TimeZone zone = java.util.TimeZone.getDefault();
            String heading = HeldWords.offer(offer, targets, zone, android.text.format.DateFormat.is24HourFormat(this),
                    System.currentTimeMillis(), SystemClock.elapsedRealtime(), bootCount());
            OfferGate.Tap tap = here.tap();
            heldOffer.show(offer.id + "@" + tap.handle + "/" + tap.attach, heading, QueuedMessages.PROMPT + offer.text,
                    "Send here", () -> sendHeld(offer.id, tap),
                    "Edit", () -> { if (composer != null) composer.editHeld(offer.id); },
                    "Queue", this::showQueueScreen);
            return;
        }
        ComposerDrafts.Draft draft = composer == null ? null : composer.offeredDraft();
        String draftKey = "draft:" + here.host + "/" + here.pane;
        if (draft != null && here.machine != null && !draftKey.equals(draftLater)) {
            String when = draft.at > 0 ? OfflineSwitch.when(draft.at, System.currentTimeMillis(),
                    java.util.TimeZone.getDefault(), android.text.format.DateFormat.is24HourFormat(this),
                    java.util.Locale.getDefault()) : "before the app restarted";
            heldOffer.show(draftKey, "draft from " + when + " · not loaded",
                    QueuedMessages.oneLine(draft.text), "Restore", () -> {
                        if (composer != null) composer.acceptOfferedDraft();
                        refreshOffer();
                    }, null, null, "Later", () -> draftLater = draftKey);
            return;
        }
        heldOffer.hide();
    }

    /**
     * The tap on a held message's Send: typed into the attach that was on screen when the offer
     * was drawn, with the late lead for a program that reports status. Anything changed since
     * (another pane, another attach, the link) and nothing is typed. Main thread.
     */
    private void sendHeld(String id, OfferGate.Tap tap) {
        Here here = here();
        if (!here.mayType(tap, covered())) {
            note("The pane changed before the tap; nothing was sent");
            refreshOffer();
            return;
        }
        Outbox.Message m = outbox.outbox().offer(here.lane());
        if (m == null || !m.id.equals(id)) {
            refreshOffer();
            return;
        }
        String wire = HeldWords.wire(m.text, m.composed.wallMs, java.util.TimeZone.getDefault(),
                !here.status.isEmpty(), false);
        outbox.send(id, here.lane(), tap.handle, tap.attach, wire, false, outcome -> {
            if (outcome == Outbox.Outcome.WRITTEN) note("Sent · check it arrived");
            else if (outcome == Outbox.Outcome.UNWRITTEN) note("Not sent: the pane was not live; it still waits");
            else if (outcome == Outbox.Outcome.UNSURE) note("It may have been sent, or may sit unsent in the input box");
            main.post(this::refreshOffer);
        });
    }

    /**
     * Send with an empty draft (Wolf 11-22): this pane's held messages are typed now, the lane's
     * head first and the next only once the one before was written, so the order holds and none
     * goes twice; then, on Claude Code, its send-now key (Enter on the empty prompt, "Press up
     * to edit queued messages, Enter to send them immediately") so a busy turn takes them at
     * once. Codex steers each typed message by itself. False when nothing is held here.
     */
    private boolean flushHeld() {
        Here here = here();
        if (outbox == null || !here.live || covered()) return false;
        Outbox.Message head = outbox.outbox().offer(here.lane());
        if (head == null) return false;
        flushNext(here.tap(), 0);
        return true;
    }

    private void flushNext(OfferGate.Tap tap, int sent) {
        Here here = here();
        Outbox.Message m = outbox == null ? null : outbox.outbox().offer(here.lane());
        if (m == null || sent >= HeldFlush.MAX || !here.mayType(tap, covered())) {
            if (sent > 0 && HeldFlush.sendNowKey(paneKind(here))) {
                // The pane is still the one tapped: Claude's own send-now for what it queued.
                if (here.mayType(tap, covered())) queueComposerInput("{\"type\":\"keys\",\"keys\":[\"enter\"]}");
            }
            refreshOffer();
            return;
        }
        String wire = HeldWords.wire(m.text, m.composed.wallMs, java.util.TimeZone.getDefault(),
                !here.status.isEmpty(), false);
        outbox.send(m.id, here.lane(), tap.handle, tap.attach, wire, false, outcome -> main.post(() -> {
            if (outcome == Outbox.Outcome.WRITTEN) {
                flushNext(tap, sent + 1);
                return;
            }
            if (outcome == Outbox.Outcome.UNWRITTEN) note("Not sent: the pane was not live; it still waits");
            else if (outcome == Outbox.Outcome.UNSURE) note("It may have been sent, or may sit unsent in the input box");
            refreshOffer();
        }));
    }

    /** The harness of the pane on screen ("claude", "codex", ...), "" when not an agent's. */
    private String paneKind(Here here) {
        Connection c = here.connection;
        JSONArray panes = c == null ? null : c.panes;
        for (int i = 0; panes != null && i < panes.length(); i++) {
            JSONObject p = panes.optJSONObject(i);
            if (p != null && here.pane.equals(p.optString("id"))) return p.optString("kind", "");
        }
        return "";
    }

    /** The one queue, every computer and pane. Main thread. */
    private void showQueueScreen() {
        if (outbox == null || destroyed) return;
        outbox.purgeSent();
        QueueScreen.show(this, chrome, outbox, queueActions);
    }

    private final QueueScreen.Actions queueActions = new QueueScreen.Actions() {
        @Override public boolean onScreen(Outbox.Message m) {
            Here here = here();
            return here.host.equals(m.host) && here.pane.equals(m.pane)
                    && (m.target.machine == null || m.target.machine.equals(here.machine));
        }

        @Override public void open(Outbox.Message m) {
            focusHostPane(m.host, m.pane, true);
        }

        @Override public void edit(Outbox.Message m) {
            if (composer != null) composer.editHeld(m.id);
        }

        @Override public int boot() {
            return bootCount();
        }
    };

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
                    // The recording goes only once its text is durably stored: in a draft, a queued
                    // message, or the end of its pane's draft. A failed write leaves all as it was,
                    // and the text is offered again shortly.
                    int placed = composer.placeDictation(clip.id, text);
                    if (placed == 0) placed = outbox.fillDictation(clip.id, text);
                    if (placed == 0) placed = composer.appendDictation(clip.target, text) ? 1 : -1;
                    dev.herdr.android.dictation.PendingDictations pending = Dictation.pending(GpuiTerminalActivity.this);
                    if (placed > 0) {
                        pending.delivered(clip.id);
                    } else {
                        main.postDelayed(() -> pending.offerAgain(clip.id), 5_000);
                    }
                }

                @Override public void onChanged() {
                    if (composer != null) composer.setWaiting(Dictation.pending(GpuiTerminalActivity.this).count());
                }

                @Override public void onNothing(dev.herdr.android.dictation.PendingClip clip,
                        dev.herdr.android.dictation.DictationException.Reason reason) {
                    if (composer != null) composer.nothingHeard(reason);
                }

                @Override public void onTranscribedHere(dev.herdr.android.dictation.PendingClip clip, String text,
                        String error) {
                    note(text != null
                            ? (text.isEmpty() ? "This phone heard no speech in that recording; it is kept"
                                    : "Heard on this phone (English only): use it or keep waiting, on its row")
                            : "This phone could not transcribe it (" + error + "); the recording is kept");
                    if (composer != null) composer.setWaiting(Dictation.pending(GpuiTerminalActivity.this).count());
                }
            };

    /** A poll's live terminals: address to {@code created_ms} (null when the list has none). */
    private static java.util.Map<String, Long> terminals(JSONArray panes) {
        java.util.Map<String, Long> out = new java.util.HashMap<>();
        for (int i = 0; panes != null && i < panes.length(); i++) {
            JSONObject pane = panes.optJSONObject(i);
            String id = pane == null ? "" : pane.optString("id", "");
            long created = pane == null ? 0 : pane.optLong("created_ms", 0);
            if (!id.isEmpty()) out.put(id, created > 0 ? created : null);
        }
        return out;
    }

    /**
     * Binds this connection's profile revision to the machine its hello proved (core id plus the
     * pinned host key it authenticated), once. A collision is refused: the profile works live
     * only, and says with which computer. Poll thread, under stateLock.
     */
    private void bindMachine(Connection current, String coreId) {
        if (current.machine != null || current.refusedBy != null || current.hostId == null || bindings == null) return;
        // Not before the held messages are read: their sessions own their machines.
        if (outbox == null || !MachineBindings.mayBind(outbox.outbox())) return;
        MachineKey key = MachineKey.of(coreId, current.fingerprint);
        if (key == null) return;
        MachineBindings.Binding binding = bindings.bind(current.hostId, current.connectionRevision, current.session,
                current.hostName, key, outbox == null ? java.util.Collections.emptyList() : outbox.outbox().all());
        if (binding == null) return;
        if (binding.bound()) {
            current.machine = key;
            Pins.Pin waiting = rail.pinAwaitingHello;
            if (waiting != null) {
                rail.pinAwaitingHello = null;
                main.post(() -> { if (!destroyed) rail.openPin(waiting); });
            }
            // No transcript proof here: the panes may still be a resumed connection's. The poll
            // publishes this connection's own list once it has one (TranscriptKeeper.mayPublish).
        } else {
            current.refusedBy = binding.refusedBy;
            String other = binding.refusedBy;
            main.post(() -> note("This computer reports the same Herdr as " + other
                    + "; it works live only, and nothing is held for it"));
        }
        main.post(this::refreshQueued);
    }

    /**
     * A direct link's list is also what proves a transcript offline (an old core's proves none):
     * published once per list revision, and only this connection's own, after its hello. Poll
     * thread.
     */
    private void publishTranscriptProof(Connection current, boolean ready) {
        if (transcripts == null || !TranscriptKeeper.mayPublish(ready, current.machine != null, current.ownList,
                current.featuresKnown, current.transcriptsListed, current.chromeRevision)) {
            return;
        }
        current.transcriptsListed = current.chromeRevision;
        transcripts.listed(current.machine, current.generation, current.panes);
    }

    /** Whether the phone's network is metered (other agents' transcripts are not read then). */
    private boolean metered() {
        long now = SystemClock.elapsedRealtime();
        if (now - meteredAt > 5_000) {
            meteredAt = now;
            android.net.ConnectivityManager connectivity = getSystemService(android.net.ConnectivityManager.class);
            meteredNow = connectivity == null || connectivity.isActiveNetworkMetered();
        }
        return meteredNow;
    }

    /**
     * Offline, the pane on screen's transcript, when the last direct list of its machine proves
     * it is of the program that list showed there: one button, "transcript · as of 14:02". Live,
     * or unproven, nothing. Main thread.
     */
    private void refreshTranscript(Here here) {
        if (transcripts == null) return;
        Connection c = here.connection;
        boolean offline = c != null && HeldScroll.savedTranscriptWanted(here.live, c.offline, c.isClosed(), c.ready,
                linkInDoubt(c));
        // The connection is part of it: a reconnect to the same pane looks again.
        String query = offline && here.machine != null && !here.pane.isEmpty()
                ? c.generation + "/" + here.machine + "/" + here.pane : "";
        if (query.equals(transcriptQuery)) return;
        transcriptQuery = query;
        // Any change hides the button at once: its listener is the old pane's.
        long seq = ++transcriptSeq;
        hideTranscriptButton();
        if (query.isEmpty()) return;
        MachineKey machine = here.machine;
        String pane = here.pane;
        String label = here.label.isEmpty() ? pane : here.label;
        transcripts.offline(machine, pane, t -> main.post(() -> {
            if (destroyed || seq != transcriptSeq) return;
            showTranscriptButton(seq, machine, pane, label, t);
        }));
    }

    private void hideTranscriptButton() {
        transcriptOpen = null;
        transcriptHistory = false;
        if (transcriptButton == null) return;
        transcriptButton.setVisibility(View.GONE);
        transcriptButton.setOnClickListener(null);
    }

    /**
     * Whether {@code t}, found by lookup {@code seq} for {@code machine}'s {@code pane}, is still
     * the pane on screen's: no lookup since (any change of connection, machine or pane starts
     * one), and the transcript is of that machine and pane. Main thread.
     */
    private boolean transcriptCurrent(long seq, MachineKey machine, String pane, Transcripts.Transcript t) {
        return !destroyed && seq == transcriptSeq && t.key.machine.equals(machine) && t.key.address.equals(pane);
    }

    private void showTranscriptButton(long seq, MachineKey machine, String address, String pane,
            Transcripts.Transcript t) {
        if (terminalRoot == null) return;
        if (t == null || !transcriptCurrent(seq, machine, address, t)) {
            hideTranscriptButton();
            return;
        }
        if (transcriptButton == null) {
            transcriptButton = new TextView(this);
            transcriptButton.setTextSize(12);
            transcriptButton.setTextColor(chrome.onSurface);
            transcriptButton.setPadding(dp(10), dp(5), dp(10), dp(5));
            android.graphics.drawable.GradientDrawable pill = new android.graphics.drawable.GradientDrawable();
            pill.setColor(chrome.control);
            pill.setStroke(dp(1), chrome.outline);
            pill.setCornerRadius(dp(14));
            transcriptButton.setBackground(pill);
            FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(-2, -2,
                    android.view.Gravity.TOP | android.view.Gravity.END);
            params.setMargins(0, dp(58), dp(12), 0);
            terminalRoot.addView(transcriptButton, params);
        }
        String when = OfflineSwitch.when(t.asOfMs, System.currentTimeMillis(), java.util.TimeZone.getDefault(),
                android.text.format.DateFormat.is24HourFormat(this), java.util.Locale.getDefault());
        transcriptButton.setText("≡ transcript · as of " + when);
        transcriptButton.setContentDescription("Read " + pane + "'s " + Transcripts.label(when));
        transcriptOpen = () -> {
            // Checked again on the tap: the selection may have moved since it was shown.
            refreshTranscript(here());
            if (!transcriptCurrent(seq, machine, address, t)) {
                hideTranscriptButton();
                return;
            }
            TranscriptScreen.show(this, chrome, pane, when, t);
        };
        transcriptHistory = !t.screen;
        transcriptButton.setOnClickListener(v -> { if (transcriptOpen != null) transcriptOpen.run(); });
        transcriptButton.setVisibility(View.VISIBLE);
        if (openWhenFound == seq) {
            openWhenFound = 0;
            if (transcriptHistory) transcriptOpen.run();
        }
    }

    /** The poll's attach on screen and its first paint; the offer follows a change. Under stateLock. */
    private void noteAttach(Connection current, JSONObject state) {
        long attach = state.optLong("attach", 0);
        boolean painted = state.optBoolean("painted", false);
        String pane = state.optString("pane_id", "");
        String signature = current.generation + "/" + pane + "/" + attach + "/" + painted + "/" + current.ready;
        current.attach = attach;
        current.painted = painted;
        if (painted && current.ready && !current.paintLogged) {
            // How long the way back took, for the device check (factory-9zz.1).
            current.paintLogged = true;
            android.util.Log.i("herdr-reconnect", "live: first paint of " + pane + sinceNetwork());
        }
        if (transcripts != null && current.ready && painted && current.machine != null && current.transcripts) {
            // One bounded read at a time, off this thread: it returns at once.
            transcripts.tick(new TranscriptKeeper.Link(current.remoteHandle, current.generation, current.machine,
                    true, metered(), current.chromePane, state.optLong("output", 0), current.panes));
        }
        // A hello the outbox was not yet read for is bound on a later poll.
        if (current.machine == null && current.ready && current.remoteBoot != null) bindMachine(current, current.remoteBoot);
        if (current.ready && painted) prefetchRefs(current, current.machine);
        publishTranscriptProof(current, current.ready);
        boolean live = current.ready && pane.equals(current.paneId);
        String where = current.generation + "/" + pane + "/" + attach + "/" + live;
        String status = live ? paneStatus(current) : null, kind = agentKind(current);
        if (!(where + "/" + status + "/" + kind).equals(elicitationSignature)) {
            elicitationSignature = where + "/" + status + "/" + kind;
            main.post(() -> offerElicitation(current, where, pane, attach, status, kind));
        }
        if (signature.equals(offerSignature)) return;
        offerSignature = signature;
        main.post(this::refreshOffer);
    }

    /** The sheet opens: a keyboard the app raised for the question goes away (the user's own stays). */
    private void hideRaisedKeyboard() {
        if (!ElicitationPolicy.hidesKeyboard(imeShown, keyboardRaisedByApp)) return;
        keyboardRaisedByApp = false;
        allowNextKeyboardShow = false;
        super.gpuiHideKeyboard(imeSession);
        if (Build.VERSION.SDK_INT >= 30 && getWindow().getInsetsController() != null) {
            getWindow().getInsetsController().hide(WindowInsets.Type.ime());
        }
    }

    /** Hands the offer this pane; its keys go only to this pane at this attach, through its input queue. Main thread. */
    private void offerElicitation(Connection current, String where, String pane, long attach, String status, String kind) {
        if (destroyed) return;
        String nativeKind = ElicitationPolicy.kind(kind);
        elicitation.observe(where, current(current) ? status : null, kind, new ElicitationSheet.Pane() {
            @Override public String parse() { return ElicitationNative.parse(attach, nativeKind); }
            @Override public boolean attached() { return ElicitationNative.attached(attach); }
            @Override public String step(String goal, String progress) { return ElicitationNative.step(attach, nativeKind, goal, progress); }
            @Override public boolean send(String json) {
                return current(current) && current.ready && pane.equals(current.paneId) && attach == current.attach
                        && nativeSendCommand(current.generation, json);
            }
            // The sheet sits in (and dims) the terminal that asked, not the work panel beside it.
            @Override public android.graphics.Rect area() {
                if (terminalColumn == null || !terminalColumn.isLaidOut()) return null;
                int[] at = new int[2];
                terminalColumn.getLocationOnScreen(at);
                return new android.graphics.Rect(at[0], at[1], at[0] + terminalColumn.getWidth(), at[1] + terminalColumn.getHeight());
            }
        });
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
        Here here = here();
        composer.showQueued(here.host.isEmpty() ? java.util.Collections.emptyList()
                : outbox.outbox().shown(here.machine, here.host, here.pane));
        refreshOffer();
    }

    /** The pane's queued messages, one row each; a row opens its actions. */
    /**
     * The full list ("+N more"): every row of the pane's queue, messages and recordings alike,
     * each with its actions; they go through the composer, so a drop is undoable as on the line.
     */
    private void queueSheet(java.util.List<QueueView.Row> rows, java.util.List<Outbox.Message> messages) {
        if (rows.isEmpty()) return;
        int waiting = 0;
        for (QueueView.Row row : rows) if (row.kind != QueueView.Kind.SENT) waiting++;
        ActionSheet sheet = new ActionSheet(this, chrome, QueuedMessages.title(waiting));
        for (QueueView.Row row : rows) {
            String line = row.state.isEmpty() ? row.text : row.text + "  " + row.state;
            if (!row.actionable) {
                sheet.add(line, () -> { });
                continue;
            }
            Outbox.Message message = null;
            for (Outbox.Message m : messages) if (m.id.equals(row.id)) message = m;
            Outbox.Message m = message;
            sheet.add(line, () -> rowActions(row, m));
        }
        sheet.show();
    }

    /** What can be done with one row of the full list; an unsure message is resent only here. */
    private void rowActions(QueueView.Row row, Outbox.Message message) {
        ActionSheet sheet = new ActionSheet(this, chrome, row.text);
        switch (row.kind) {
            case MESSAGE:
                if (message == null || message.state == Outbox.State.SENDING) return;
                Here opened = here();
                if (message.state == Outbox.State.PENDING && message.id.equals(offeredId()) && opened.live) {
                    // Bound to the pane and attach on screen now: a reattach before the tap types nothing.
                    OfferGate.Tap tap = opened.tap();
                    sheet.add("Send here", () -> sendHeld(message.id, tap));
                }
                if (message.state == Outbox.State.NEEDS_YOU) {
                    sheet.add("It arrived", () -> composer.queueCommand(row.id, "drop"));
                }
                if (message.state == Outbox.State.SENT
                        || (message.state == Outbox.State.NEEDS_YOU && message.reason != Outbox.Reason.GONE)) {
                    // It may have been typed: look at the pane before sending it twice.
                    sheet.add("Send again (check the pane first)", () -> composer.queueCommand(row.id, "again"));
                }
                if (message.state != Outbox.State.SENT) {
                    sheet.add("Edit as draft", () -> composer.queueCommand(row.id, "edit"));
                }
                sheet.add("Copy", () -> ActionSheet.copy(this, "Message", message.text));
                sheet.danger(message.state == Outbox.State.PENDING ? "Cancel" : "Drop",
                        () -> composer.queueCommand(row.id, "drop"));
                break;
            case PARKED:
                sheet.add("Retry", () -> composer.queueCommand(row.id, "retry"));
                sheet.danger("Drop", () -> composer.queueCommand(row.id, "drop"));
                break;
            case CLIP:
                sheet.danger("Drop", () -> composer.queueCommand(row.id, "drop"));
                break;
            default:
                return;
        }
        sheet.show();
    }

    /** The held message the live pane offers now, or null. */
    private String offeredId() {
        Here here = here();
        Outbox.Message m = here.live ? outbox.outbox().offer(here.lane()) : null;
        return m == null ? null : m.id;
    }

    /** False leaves the composer's draft in place: unlike a key, it is never lost. */
    private boolean queueComposerInput(String commandJson) {
        if (displayDialog != null && displayDialog.isShowing()) return false;
        clearControl();
        synchronized (stateLock) {
            Connection target = connection;
            if (target != null && OfflineSwitch.refusesKeys(target.offline, target.isClosed(), target.remoteHandle)) {
                // What a key means depends on the screen at that moment: with
                // no link it is refused, never queued (messages go to the Outbox).
                main.post(() -> note(OfflineSwitch.KEYS_REFUSED));
                return false;
            }
            if (target != null && !target.ready && monitor != null && monitor.reconnecting()) {
                // Never replayed on a later attach: a key means what the screen showed.
                main.post(() -> note(OfflineSwitch.KEYS_REFUSED));
                return false;
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

    /** Where the last completed touch began (surface pixels), for taps on the draft. */
    private float tapX = Float.NaN, tapY = Float.NaN;

    private boolean forwardTouch(MotionEvent event) {
        if (!hostStarted) return false;
        int masked = event.getActionMasked();
        if (masked == MotionEvent.ACTION_DOWN) nativeSetTerminalTouchActive(true);
        try {
            return dispatchTerminalTouch(event, masked);
        } finally {
            // Local scrolls and pinches cancel GPUI's touch before the fingers lift.
            if (masked == MotionEvent.ACTION_UP || masked == MotionEvent.ACTION_CANCEL) {
                nativeSetTerminalTouchActive(false);
            }
        }
    }

    private boolean dispatchTerminalTouch(MotionEvent event, int masked) {
        int count = event.getPointerCount();
        if (count < 1 || count > 16) return false;
        // Selection first (factory-9zz.4): a long press, a handle drag or a tap that clears a
        // shown selection is the activity's; none of it reaches the program, nor scrolls.
        if (selection != null && selection.onTouch(event)) {
            if (masked == MotionEvent.ACTION_UP || masked == MotionEvent.ACTION_CANCEL) rendererTouch.end();
            return true;
        }
        if (masked == MotionEvent.ACTION_UP) {
            tapX = touchDownX;
            tapY = touchDownY;
        }
        if (masked == MotionEvent.ACTION_DOWN) rendererTouch.down(event.getPointerId(0));
        pinchDetector().onTouchEvent(event);
        if (pinching || count > 1 && masked != MotionEvent.ACTION_DOWN) {
            // A pinch is ours: the program never sees half of a two-finger
            // gesture, nor its last finger's lift as a tap.
            scrolling = false;
            claimRendererTouch();
            if (masked == MotionEvent.ACTION_UP || masked == MotionEvent.ACTION_CANCEL) rendererTouch.end();
            return true;
        }
        if (masked == MotionEvent.ACTION_DOWN) {
            stopFling();
            edgeSaid = false;
            routeSaid = false;
            touchDownX = edgeStartX = event.getX();
            edgeClaimed = false;
            touchDownY = lastTouchY = event.getY();
            lastMoveAt = SystemClock.uptimeMillis();
            scrollVelocity = 0f;
            scrolling = false;
            panning = false;
            scrollFraction = 0f;
        } else if (masked == MotionEvent.ACTION_MOVE && count == 1 && !shellMode()) {
            // A shell has no rail, and GPUI turns its pans into Rio's own scrollback.
            // The system owns the screen edges for Back, so the open gesture starts
            // inside the left third rather than at the very edge.
            int width = terminalSurface == null ? 0 : terminalSurface.getWidth();
            // Never over a gesture that already has an owner: a scroll in progress (a text
            // selection and the file viewer take their touches before this).
            if (!edgeClaimed && !sidebarOpen && !scrolling && !panning && width > 0 && touchDownX < width / 2f) {
                float dx = event.getX() - edgeStartX;
                float dy = Math.abs(event.getY() - touchDownY);
                // From the edge strip (where Back does not take it: buttons
                // navigation, a swipe just inside the edge) a short pull opens it.
                float needed = touchDownX < dp(40) ? dp(24) : dp(60);
                if (dx > needed && dx > dy * 1.5f) {
                    edgeClaimed = true;
                    claimRendererTouch();
                    showRailOverlay();
                    return true;
                }
            }
            if (edgeClaimed) return true;
            // The axis is decided once, where GPUI decides it: a drag it locked
            // sideways stays the renderer's pan however it drifts after.
            int axis = scrolling || panning ? RendererTouch.AXIS_NONE : RendererTouch.axis(
                    event.getX() - touchDownX, event.getY() - touchDownY, dp(RendererTouch.SLOP_DP));
            if (axis == RendererTouch.AXIS_X) panning = true;
            if (axis == RendererTouch.AXIS_Y) {
                scrolling = true;
                // Dragging the conversation down while typing puts the keyboard away.
                if (event.getY() > touchDownY && composer != null) {
                    if (!composer.hideKeyboard()) KeyboardHide.hideIfShown(this);
                }
                // The drag is ours from here: the renderer's press (a link's
                // highlight, a selection) ends now, not at a lift it never sees.
                claimRendererTouch();
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
            // should not need the toolbar. Not decided here: the view that handles
            // the lift reports every tap (gpuiTerminalTapped), after it may have
            // sent it to the program as a mouse click.
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
        // A gesture the activity took over goes no further, its last lift included.
        if (!rendererTouch.forwards()) {
            if (masked == MotionEvent.ACTION_UP || masked == MotionEvent.ACTION_CANCEL) rendererTouch.end();
            return true;
        }
        if (masked == MotionEvent.ACTION_UP || masked == MotionEvent.ACTION_CANCEL) rendererTouch.end();
        int[] ids = new int[count];
        float[] xs = new float[count], ys = new float[count];
        for (int i = 0; i < count; i++) {
            ids[i] = event.getPointerId(i);
            xs[i] = event.getX(i);
            ys[i] = event.getY(i);
        }
        // A down carries the pane it begins on, through the renderer's queue.
        return nativeTouch(event.getActionMasked(), event.getActionIndex(), ids, xs, ys,
                terminalTap.stamp());
    }

    /** Tells the renderer, once, that the activity took the current gesture over. */
    private void claimRendererTouch() {
        int pointer = rendererTouch.claim();
        if (pointer == RendererTouch.NONE) return;
        nativeTouch(MotionEvent.ACTION_CANCEL, 0, new int[] {pointer},
                new float[] {touchDownX}, new float[] {touchDownY}, terminalTap.stamp());
    }

    /**
     * Home opened over this terminal (a launcher or notification start) and
     * an agent was picked there: show it here instead of refusing to open a
     * second terminal.
     */
    @Override protected void onNewIntent(android.content.Intent intent) {
        super.onNewIntent(intent);
        if (intent.getBooleanExtra(EXTRA_ATTACH_SHARED, false)) {
            // Every item refused: say why here; otherwise ask about the waiting images.
            String why = SharedImages.takeNotice(System.currentTimeMillis());
            if (why != null) note(why);
            else takeSharedImages();
        }
        String hostId = intent.getStringExtra(EXTRA_FOCUS_HOST);
        String pane = intent.getStringExtra(EXTRA_FOCUS_PANE);
        if (hostId == null || pane == null || destroyed) return;
        boolean picked = intent.getBooleanExtra(EXTRA_REVEAL_FLEET, true);
        if (!focusHostPane(hostId, pane, picked) && ACTION_OPEN_NOTICE.equals(intent.getAction())) {
            HomeActivity.openNotice(this, hostId, pane);
        }
    }

    /** Shows {@code pane} on saved host {@code hostId}; false when that host is not saved here. */
    private boolean focusHostPane(String hostId, String pane, boolean picked) {
        Connection target;
        synchronized (stateLock) { target = connection; }
        if (hostId.equals(currentHostId)) {
            if (target == null) {
                rememberHostPane(hostId, pane);
                if (picked) rail.requestRailReveal(hostId, pane);
            } else if (target.remoteHandle > 0 || target.offline) {
                focusRemotePane(target, pane, picked);
            }
            return true;
        }
        for (SavedRemoteHost host : savedHosts) {
            if (host.id.equals(hostId)) { switchHost(host, pane, picked); return true; }
        }
        return false;
    }

    static final String EXTRA_FOCUS_HOST = "focus_host", EXTRA_FOCUS_PANE = "focus_pane";
    static final String EXTRA_REVEAL_FLEET = "reveal_fleet";
    private static final String ACTION_OPEN_NOTICE = "dev.herdr.android.OPEN_AGENT_NOTICE";

    @Override protected void onResume() {
        super.onResume();
        if (isFinishing() || launch == null || shortcutJson == null || !hostStarted) return;
        hostResumed = true;
        boolean firstOpen = !openedOnce;
        openedOnce = true;
        nativeResumed();
        takeSharedImages();
        // Herdr in front is Wolf looking at it (item 71): the attach that follows says so.
        markUsed();
        if (monitor == null && !launch.shell) {
            monitor = new ConnectionMonitor(this, main, new ConnectionMonitor.Listener() {
                @Override public void attempt() { autoReconnect(); }
                @Override public void changed() { showLinkDown(); }
                @Override public void gone() { networkGone(); }
                @Override public void exhausted() { showLinkDown(); }
            });
        }
        if (monitor != null) monitor.start();
        loadFleetCache();
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
                        String shown = rememberedHostPane(currentHostId);
                        // The strip and the rail keep the last list seen, so
                        // panes switch while the link is still down.
                        installOffline(currentHostId, launch.name(), shown);
                        showSaved(currentHostId, shown);
                        main.post(this::reconnectCurrentHost);
                        return;
                    }
                    android.util.Log.w("herdr-gpui", "Remote session was paused. Reconnect from Remote hosts");
                    finish();
                    return;
                }
                next = new Connection(GENERATIONS.incrementAndGet(), remoteHandle);
                next.hostId = currentHostId;
                next.connectionRevision = launch.remoteHost.connectionRevision;
                next.fingerprint = launch.remoteHost.expectedFingerprint;
                next.session = launch.remoteHost.session;
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
        if (hostStarted) {
            claimRendererTouch();
            nativeSetTerminalTouchActive(false);
            nativePaused();
        }
        super.onPause();
    }

    @Override protected void onStart() {
        super.onStart();
        // Postures arrive while the activity is visible, as Jetpack delivers them.
        if (foldWatcher == null) {
            // A fold into or out of tabletop may keep the window's size: the
            // posture change itself is the use (item 71), not its first report.
            FoldPosture.Uses folds = new FoldPosture.Uses();
            foldWatcher = FoldPosture.Watcher.start(this, update -> main.post(() -> {
                if (folds.isUse(posture, update)) markUsed();
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
        open = false;
        elicitation.destroy();
        attachWorker.shutdownNow();
        if (outbox != null) outbox.unlisten(outboxChanged);
        if (composer != null) composer.destroy();
        if (usageFeed != null) usageFeed.detach(this);
        if (workPanel != null) workPanel.detach();
        Dictation.pending(this).setListener(null);
        stopHostSampling();
        stopConnection();
        worker.shutdownNow();
        connector.shutdownNow();
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
        runSessionAction(target, action, label, null);
    }

    /** {@code failed}, when given, also hears why it was not applied (on the main thread). */
    private void runSessionAction(Connection target, String action, String label, java.util.function.Consumer<String> failed) {
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
                if (!destroyed && failed != null) failed.accept(reason);
            });
        });
    }

    /** A layout edit (see core_session's TabEdit), applied like any session action. */
    void runEdit(Connection target, String label, Object... fields) {
        runEditOr(target, label, null, fields);
    }

    private void runEditOr(Connection target, String label, java.util.function.Consumer<String> failed, Object... fields) {
        try {
            JSONObject edit = new JSONObject();
            for (int i = 0; i + 1 < fields.length; i += 2) edit.put((String) fields[i], fields[i + 1]);
            runSessionAction(target, edit.toString(), label, failed);
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
    private void postNotice(Connection owner, String title, String body, String paneId) {
        if (destroyed || isFinishing() || !agentAlertsOn()) return;
        synchronized (stateLock) { if (!current(owner)) return; }
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
        android.app.Notification.Builder notice = new android.app.Notification.Builder(this, "herdr-agent-alerts")
                .setSmallIcon(android.R.drawable.stat_notify_chat)
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(new android.app.Notification.BigTextStyle().bigText(body))
                .setAutoCancel(true);
        String host = owner.hostId;
        if (host != null && !paneId.isEmpty()) {
            android.content.Intent open = new android.content.Intent(this, GpuiTerminalActivity.class)
                    .setAction(ACTION_OPEN_NOTICE)
                    .setIdentifier(host + "/" + paneId)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT | android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    .putExtra(EXTRA_FOCUS_HOST, host)
                    .putExtra(EXTRA_FOCUS_PANE, paneId);
            notice.setContentIntent(android.app.PendingIntent.getActivity(this, 0, open, android.app.PendingIntent.FLAG_IMMUTABLE));
        }
        try {
            manager.notify(paneId.isEmpty() ? title.hashCode() : paneId.hashCode(), notice.build());
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
    /** The renderer's selection changed (gpui-rio touch_select, android.rs selection_changed). */
    public void gpuiSelection(long expectedGeneration, String json) {
        main.post(() -> {
            synchronized (stateLock) {
                if (destroyed || connection == null || connection.generation != expectedGeneration) return;
            }
            if (selection != null) selection.report(json);
        });
    }

    public void gpuiLayoutHint(long expectedGeneration, String json) {
        // The drawn grid's geometry, for a debug build's tap tests (logcat tag herdr-tap).
        if (debuggable()) android.util.Log.d("herdr-tap", "layout " + json);
        // Posted, never run inline: Rust calls this from inside a paint, and the
        // composer answers with ghost text, which must not re-enter that frame.
        main.post(() -> {
            synchronized (stateLock) {
                if (destroyed || connection == null || connection.generation != expectedGeneration) return;
            }
            if (composer != null) composer.layoutHint(json);
        });
    }

    /**
     * The renderer had a press on the terminal: report {@code id}, made under
     * {@code stamp} (the pane shown when the finger went down), with what it
     * did ({@link TerminalTap.Outcome}). Acted on once, for the pane shown now.
     */
    public void gpuiTerminalTapped(long expectedGeneration, long stamp, long id, int outcome) {
        main.post(() -> {
            synchronized (stateLock) {
                if (destroyed || connection == null || connection.generation != expectedGeneration) return;
            }
            boolean accepted = terminalTap.accept(stamp, id);
            if (debuggable()) {
                android.util.Log.d("herdr-tap", "report id=" + id + " stamp=" + stamp + " shown="
                        + terminalTap.stamp() + " outcome=" + TerminalTap.Outcome.of(outcome)
                        + (accepted ? "" : " dropped"));
            }
            if (!accepted) return;
            terminalTapSettled(TerminalTap.Outcome.of(outcome), stamp);
        });
    }

    /**
     * A tap on the terminal, once decided: typing opens only for a tap the
     * program did not take, and only while its pane ({@code stamp}) is shown.
     */
    private void terminalTapSettled(TerminalTap.Outcome outcome, long stamp) {
        if (destroyed || outcome == TerminalTap.Outcome.IGNORED) return;
        boolean clicked = outcome == TerminalTap.Outcome.CLICKED;
        Connection target;
        synchronized (stateLock) { target = connection; }
        // An agent's pane types into the composer's draft instead.
        boolean handled = composer != null
                ? composer.terminalTapped(clicked, tapX, tapY)
                : TerminalTap.after(outcome, false) == TerminalTap.Action.NOTHING;
        if (!handled && target != null && target.ready && terminalSurface != null) {
            final long generation = target.generation;
            // After the renderer's own focus handling for the tap, which would
            // otherwise hide the keyboard this just asked for.
            terminalSurface.postDelayed(() -> {
                // A tapped file link opened the file page instead, or another
                // pane is shown by now: its keyboard is not this tap's to open.
                if (!destroyed && terminalTap.stillShown(stamp)
                        && (filePager == null || !filePager.showingFile())) {
                    nativeShowKeyboard(generation);
                }
            }, 150);
        }
    }

    /** Opens a terminal link through the platform browser; never a file or custom scheme. */
    public void gpuiOpenLink(long expectedGeneration, String url, String foundOnHost, String foundOnPane) {
        runOnUiThread(() -> {
            synchronized (stateLock) {
                if (destroyed || isFinishing() || connection == null
                        || connection.generation != expectedGeneration
                        || !LinkFrame.opens(foundOnHost, foundOnPane, connection.hostId, connection.paneId,
                                connection.ready)) return;
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

    /**
     * A finger held on a terminal link: its URL (or a file link's path) goes to the clipboard,
     * said in place in the crumb, never in a popup.
     */
    public void gpuiCopyLink(long expectedGeneration, String text, String foundOnHost, String foundOnPane) {
        runOnUiThread(() -> {
            synchronized (stateLock) {
                if (destroyed || isFinishing() || connection == null
                        || connection.generation != expectedGeneration
                        || !LinkFrame.selected(foundOnHost, foundOnPane, connection.hostId, connection.paneId)) return;
            }
            if (text == null || text.isEmpty() || text.length() > 4096) return;
            ActionSheet.copy(this, "Link", text);
            note("Link copied");
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
        return readFile(target, terminal, path, 0);
    }

    /** One {@code file.read} through another machine's mirror session {@code handle}, as {@link #readFile}. */
    private static String readThrough(long handle, String terminal, String path) {
        try {
            JSONObject read = new JSONObject().put("op", "file.read").put("terminal", terminal).put("path", path);
            return RemoteNative.sessionAction(handle, read.toString());
        } catch (JSONException impossible) {
            return "{\"queued\":false}";
        } catch (RuntimeException failed) {
            String kind = RemoteError.kindOf(failed);
            return "{\"queued\":false,\"error\":\"" + (kind.isEmpty() ? "failed" : kind) + "\"}";
        }
    }

    /** The name the panel's file reads are kept under in RefFiles (only relative names need it). */
    static final String DOC_FILES = "panel";

    /** The cover's panel stepped aside for a file opened from it: it comes back when the file closes. */
    private boolean reopenPanelAfterFile;

    /** One {@code file.read} through {@code target}'s session; {@code limit} lines, 0 for the tap's whole read. */
    private String readFile(Connection target, String terminal, String path, int limit) {
        if (target == null || target.remoteHandle == 0) {
            return "{\"queued\":false,\"error\":\"Not connected\"}";
        }
        try {
            JSONObject read = new JSONObject().put("op", "file.read").put("terminal", terminal).put("path", path);
            if (limit > 0) read.put("limit", limit);
            return RemoteNative.sessionAction(target.remoteHandle, read.toString());
        } catch (JSONException impossible) {
            return "{\"queued\":false}";
        } catch (RuntimeException failed) {
            // The session closed or is busy (RemoteError): a read that did not happen, never a
            // crash of the thread asking (a background prefetch has no one to catch it).
            String kind = RemoteError.kindOf(failed);
            return "{\"queued\":false,\"error\":\"" + (kind.isEmpty() ? "failed" : kind) + "\"}";
        }
    }

    /**
     * The file view's reader (on its worker). Live, the file as it is now, and a copy of it is
     * kept for reading offline. Otherwise the copy kept for this machine, said to be one, or why
     * there is none. A relayed pane's files are another machine's: live only (I3).
     */
    private String readFileOrCopy(String terminal, String path) {
        Here here = here();
        Connection target = here.connection;
        boolean direct = terminal != null && !terminal.contains("/");
        boolean live = target != null && target.ready && !target.isClosed() && !target.offline && target.remoteHandle > 0;
        if (live) {
            String reply = readFile(target, terminal, path, 0);
            MachineKey machine = here.machine;
            if (direct && machine != null && refFiles != null) {
                long at = System.currentTimeMillis();
                refWorker.execute(() -> refFiles.save(machine, terminal, path, reply, at));
            }
            return reply;
        }
        RefFiles.Copy copy = direct && here.machine != null && refFiles != null
                ? refFiles.open(here.machine, terminal, path, System.currentTimeMillis()) : null;
        if (copy != null) return copy.reply();
        return "{\"queued\":false,\"error\":\"not_saved\"}";
    }

    /**
     * On the poll, while the pane on screen is live: the files it names are offered to the
     * planner every few seconds, and one is read when the planner allows (never while typing or
     * scrolling, on a metered network, or in the background). Under stateLock, poll thread.
     */
    private void prefetchRefs(Connection current, MachineKey machine) {
        if (refFiles == null || machine == null || current.remoteHandle <= 0) return;
        long now = SystemClock.uptimeMillis();
        String pane = current.chromePane;
        if (pane.isEmpty() || pane.contains("/")) return;
        if (now - refsLookedAt >= 5_000) {
            refsLookedAt = now;
            try {
                JSONObject refs = new JSONObject(RemoteNative.fileRefs(current.remoteHandle));
                JSONArray files = refs.optJSONArray("files");
                if (files != null && pane.equals(refs.optString("pane"))) {
                    java.util.List<String> paths = new java.util.ArrayList<>();
                    for (int i = 0; i < files.length(); i++) paths.add(files.optString(i, ""));
                    refPlanner.offer(current.generation, machine, pane, paths, now);
                }
            } catch (JSONException | RuntimeException | LinkageError ignored) {
                return;
            }
        }
        // Typing and scrolling both push fastUntil ahead: the link is busy for the person.
        RefPlanner.Now gate = new RefPlanner.Now(now, hostResumed, current.ready && current.painted && !current.isClosed(),
                !metered(), now < current.fastUntil || scrolling || flinging, 0);
        RefPlanner.Ref ref = refPlanner.next(gate);
        if (ref == null) return;
        final Connection link = current;
        refWorker.execute(() -> {
            String reply = readFile(link, ref.terminal, ref.path, RefPlanner.PREFETCH_LINES);
            refFiles.save(ref.machine, ref.terminal, ref.path, reply, System.currentTimeMillis());
            refPlanner.done(ref, SystemClock.uptimeMillis());
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

    /** ctrl-alt-w or ctrl-alt-b, nothing else held: the rail's keys (item 83). */
    static boolean railKeyCode(KeyEvent event) {
        int code = event.getKeyCode();
        return (code == KeyEvent.KEYCODE_W || code == KeyEvent.KEYCODE_B)
                && event.isCtrlPressed() && event.isAltPressed()
                && !event.isShiftPressed() && !event.isMetaPressed();
    }

    /**
     * Item 71, latest-touched device wins: Wolf is using this phone. herdr-core
     * hears it from the session on screen (at most once a second) and from the
     * next attach's idle time. Touches reach here before any view; the
     * keyboard's own window does not, so typing, keys, Herdr coming to the
     * front, the keyboard showing and a fold or turn call it too.
     */
    private void markUsed() {
        if (launch == null || launch.shell || !hostStarted) return;
        RemoteNative.used();
    }

    @Override public boolean dispatchTouchEvent(MotionEvent event) {
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) markUsed();
        if (linkTouch(event)) return true;
        return super.dispatchTouchEvent(event);
    }

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) markUsed();
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
        // Item 83's keys, as the desktop's Work panel has them: ctrl-alt-w closes the
        // rail and brings it back, ctrl-alt-b minimizes it to its strip and back.
        // Ctrl with Alt reaches no terminal here (refused below), so nothing is taken from it.
        if (railKeyCode(event) && !shellMode()) {
            if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
                railKey(code == KeyEvent.KEYCODE_W);
            }
            capturedKeys.add(code);
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

    /** "claude", "codex" or "gemini" for the pane on screen, from its agent.kind label. */
    private static String agentKind(Connection current) {
        JSONArray panes = current.panes;
        for (int i = 0; panes != null && i < panes.length(); i++) {
            JSONObject pane = panes.optJSONObject(i);
            if (pane == null || !current.paneId.equals(pane.optString("id"))) continue;
            String kind = pane.optString("kind", "").toLowerCase(java.util.Locale.ROOT);
            if (kind.contains("claude")) return "claude";
            if (kind.contains("codex")) return "codex";
            if (kind.contains("gemini")) return "gemini";
            return kind;
        }
        return "";
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
                if (!current(current)) return;
                if (!current.ready) {
                    // Taken in the moment the pane stopped being ready: what a key
                    // means depends on the screen at that moment, so it is never
                    // replayed later (messages are held by the outbox instead).
                    main.post(() -> note(OfflineSwitch.KEYS_REFUSED));
                    return;
                }
                mutation = true;
                keyboardRaisedByApp = false;   // typed: the keyboard is in use
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
                        current.generation, LinkFrame.part(current.hostId), shortcutJson);
                if (rawState == null || rawState.length() > 1024 * 1024) throw new IOException("Oversized remote state");
                JSONObject state = new JSONObject(rawState);
                current.quietMs = state.optLong("quiet_ms", 0);
                // herdr-core answers a refresh every two seconds; silence well
                // past that is a dead path (TCP would take far longer to say so).
                if (current.ready && monitor != null && monitor.dead(current.quietMs)) {
                    fail(current, "The host stopped answering");
                    return;
                }
                // A checkpoint of the pane on screen, so it reads offline as it
                // was (item 77): at its turn's end, and every two minutes at most
                // while it prints.
                String onScreen = state.optString("pane_id", "");
                if (current.ready && current.hostId != null && onScreen.equals(current.chromePane)
                        && checkpoint.observe(onScreen, paneStatus(current.panes, onScreen),
                                state.optLong("output", 0), SystemClock.uptimeMillis())) {
                    snapshots.save(current.hostId, onScreen, current.remoteHandle);
                }
                noteAttach(current, state);
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
                            LinkFrame.part(current.hostId), shortcutJson);
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
                    fail(current, "Herdr restarted. Reopen the remote session before sending input");
                    return;
                }
                if (selected) {
                    current.remoteBoot = boot;
                    bindMachine(current, boot);
                }
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
                            // Never for a link lost since (factory-9zz.1): no keyboard offline. A keyboard
                            // the app raises is not Wolf's own (the question sheet may put it away).
                            main.post(() -> {
                                if (!(current(current) && current.ready && !current.isClosed())) return;
                                keyboardRaisedByApp = true;
                                nativeShowKeyboard(generation);
                            });
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
                    // A ready poll's list is this connection's own, not one inherited on resume.
                    if (selected) current.ownList = true;
                    current.projectsOrder = state.optJSONObject("projects_order");
                    current.title = state.optString("title", current.hostName);
                    if (current.hostId != null && current.panes != null && current.workspaces != null) {
                        seenLists.put(current.hostId, new JSONArray[] {current.workspaces, current.tabs, current.panes});
                        if (selected && current.ownList && rail.pinsRail != null && current.machine != null) {
                            Pins.shared(this).observe(current.machine, current.panes);
                            // Pins follow their agents' names here, with the list they come from, not in the rail's render.
                            java.util.Map<String, String> names = new java.util.HashMap<>();
                            for (FleetTree.Member m : BeadsModel.terms(current.workspaces, current.tabs, current.panes)) {
                                if (!AgentNames.unnamed(m)) names.put(m.id, AgentNames.of(m));
                            }
                            Pins.shared(this).relabel(current.machine, names);
                            rail.checkPinArrival(current);
                            rail.checkRailArrival(current);
                        }
                    }
                }
                // Reset for the daemon-confirmed pane, not a queued focus or
                // a title update on the pane the user is already reading.
                if (selected && !pane.equals(current.chromePane)) {
                    // The pane switched away from: its grid was kept natively
                    // when the attach replaced it, and is saved (and freed) now.
                    if (!current.chromePane.isEmpty() && current.hostId != null) {
                        snapshots.save(current.hostId, current.chromePane, current.remoteHandle);
                    }
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
                JSONObject features = state.optJSONObject("features");
                if (features != null) {
                    current.featuresKnown = true;
                    current.transcripts = features.optBoolean("read_bounded") && features.optBoolean("incarnation");
                }
                if (selected && chromeChanged && current.machine != null) {
                    // A direct link's own list: held messages for a terminal not on it are gone.
                    outbox.observe(current.machine, terminals(current.panes));
                }
                publishTranscriptProof(current, selected);
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
                        main.post(() -> postNotice(current, noticeTitle, noticeBody, noticePane));
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
                if (chromeChanged) main.post(() -> rail.refreshSidebar(false));
            }
        } catch (IOException | JSONException | RuntimeException failure) {
            fail(current, mutation
                    ? "SSH input stopped; it may have reached the host. Reconnect and inspect before retrying"
                    : "Remote session stopped. Reconnect from Remote hosts");
        }
    }

    /**
     * The phone has no network at all (ConnectionMonitor's grace has passed): the live link is
     * dead now, so the frame is held and scrolls on the phone at once, instead of after the
     * link's silence proves it (factory-9zz.1: 3.7 s of drags sent over a dead link). Main thread.
     */
    private void networkGone() {
        final Connection current;
        synchronized (stateLock) { current = connection; }
        if (current == null || current.shell || current.isClosed() || !current.ready) return;
        fail(current, "No network");
    }

    private void fail(Connection failed, String message) {
        synchronized (stateLock) {
            if (!current(failed)) return;
            // The agent's dialog belongs to this connection: its sheet closes with it.
            main.post(elicitation::close);
            failed.cancel();
            if (failed.poll != null) failed.poll.cancel(false);
            if (resumable(failed)) {
                // The frame stays, in full colour, with the keyboard up; input not yet
                // sent waits for the same pane and the monitor reconnects.
                failed.ready = false;
                holdUnsent(failed);
                nativeHold(failed.generation);
                // The frame held is the pane as of now; its copy is saved on close.
                savedShownHost = failed.hostId;
                savedShownAt = System.currentTimeMillis();
                // A switch the core made but the poll never confirmed: the pane left is kept natively.
                if (failed.hostId != null && !failed.chromePane.isEmpty() && !failed.chromePane.equals(failed.paneId)) {
                    snapshots.save(failed.hostId, failed.chromePane, failed.remoteHandle);
                }
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
                showLinkProblem(message);
            }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            Thread cleanup = new Thread(failed::close, "herdr-gpui-disconnect");
            cleanup.setDaemon(true);
            cleanup.start();
        } else failed.close();
    }

    /**
     * Draws {@code pane}'s saved grid, in full colour and scrollable, while there is no
     * live pane: the terminal opened with no link, came back to the screen
     * before reconnecting, or switched panes or machines offline. A pane with
     * no copy shows its title and one dim line instead. The live pane replaces
     * either as soon as it attaches.
     */
    private void showSaved(String host, String pane) {
        if (snapshots == null || host == null || pane == null || pane.isEmpty()) return;
        String title;
        synchronized (stateLock) { title = paneTitle(connection, pane); }
        int[] size = OfflineSwitch.gridSize(terminalGrid());
        snapshots.open(host, pane, shown -> main.post(() -> drawSaved(host, pane, shown.renderId, shown.savedAt)),
                () -> {
                    long id;
                    try {
                        id = RemoteNative.holdNote(pane, title, OfflineSwitch.NOT_CACHED, size[0], size[1]);
                    } catch (RuntimeException | LinkageError unavailable) {
                        id = 0;
                    }
                    final long note = id;
                    if (note > 0) main.post(() -> drawSaved(host, pane, note, 0));
                });
    }

    /** Puts a saved pane (or an uncached pane's note) on screen, unless a later pick or the live pane won. Main thread. */
    private void drawSaved(String host, String pane, long renderId, long savedAt) {
        synchronized (stateLock) {
            Connection now = connection;
            if (destroyed || !host.equals(currentHostId) || now != null && now.ready) return;
            if (now != null && !pane.equals(now.paneId)) return;
            long generation = now != null ? now.generation : GENERATIONS.incrementAndGet();
            nativeHold(generation);
            nativeAttachShell(generation, renderId, host, pane);
            savedShownHost = host;
            savedShownAt = savedAt;
        }
        if (switchAskedAt > 0) {
            android.util.Log.w("herdr-switch", "cached " + pane + " shown " + (SystemClock.uptimeMillis() - switchAskedAt)
                    + " ms after the tap (" + (savedAt > 0 ? "saved copy" : "not cached") + ")");
        }
        // A copy just drawn starts at its newest line.
        showLatest(false);
        showLinkDown();
        refreshTabStrip();
    }

    /** The title a pane goes by in the strip, from the last list seen; its id when unlisted. */
    private static String paneTitle(Connection source, String pane) {
        JSONArray panes = source == null ? null : source.panes;
        for (int i = 0; panes != null && i < panes.length(); i++) {
            JSONObject item = panes.optJSONObject(i);
            if (item != null && pane.equals(item.optString("id"))) {
                return RailTree.fallbackTitle(HomeActivity.cleanTitle(
                        chromeTitle(item.optString("title", ""), pane), pane), item);
            }
        }
        return pane;
    }

    /** The agent status of {@code pane} in {@code panes}, "" when unlisted. */
    private static String paneStatus(JSONArray panes, String pane) {
        for (int i = 0; panes != null && i < panes.length(); i++) {
            JSONObject item = panes.optJSONObject(i);
            if (item != null && pane.equals(item.optString("id"))) return item.optString("status", "");
        }
        return "";
    }

    /**
     * Makes the connection a stand-in for {@code hostId} with no session
     * (unless one for that host is already here): its lists are Home's last
     * view of that computer, so the strip and the rail switch panes offline.
     * Under stateLock or on the main thread.
     */
    private void installOffline(String hostId, String hostName, String pane) {
        synchronized (stateLock) {
            Connection now = connection;
            if (now != null && hostId.equals(now.hostId)) return;
            Connection offline = new Connection(GENERATIONS.incrementAndGet(), 0L);
            offline.offline = true;
            offline.closed = true;
            offline.hostId = hostId;
            offline.connectionRevision = RemoteHostStore.connectionRevision(hostId);
            offline.hostName = hostName;
            offline.title = hostName;
            offline.paneId = pane == null ? "" : pane;
            fillFromCache(offline);
            connection = offline;
            checkpoint.reset();
            nativeHold(offline.generation);
            if (imeSession > 0) nativeSetImeSession(offline.generation, imeSession);
            publish(offline, "Offline");
        }
    }

    /**
     * A stand-in with no lists takes the ones this terminal last saw live for
     * its computer, else Home's cached ones. Under stateLock.
     */
    private void fillFromCache(Connection offline) {
        if (!offline.offline || offline.panes != null) return;
        JSONArray[] last = lastLists(offline.hostId);
        if (last == null) return;
        offline.workspaces = last[0];
        offline.tabs = last[1];
        offline.panes = last[2];
    }

    /** {workspaces, tabs, panes} of a computer with no live list: as last seen here, else Home's cache; null if neither. */
    JSONArray[] lastLists(String hostId) {
        if (hostId == null) return null;
        JSONArray[] seen = seenLists.get(hostId);
        if (seen != null) return seen;
        HomeCache.Entry cached = fleetCache.get(hostId);
        if (cached == null) return null;
        return new JSONArray[] {cached.view.optJSONArray("workspaces"), cached.view.optJSONArray("tabs"),
                cached.view.optJSONArray("panes")};
    }

    /** Reads Home's cached Fleet view off the UI thread; the rail and strip redraw with it. */
    private void loadFleetCache() {
        final java.io.File file = new java.io.File(getNoBackupFilesDir(), HomeCache.FILE_NAME);
        connector.execute(() -> {
            java.util.Map<String, HomeCache.Entry> read = HomeCache.read(file);
            main.post(() -> {
                if (destroyed) return;
                fleetCache = java.util.Collections.unmodifiableMap(read);
                synchronized (stateLock) {
                    if (connection != null) fillFromCache(connection);
                }
                tabSignature = "";
                sidebarSignature = "";
                rail.refreshSidebar(true);
            });
        });
    }

    /**
     * Another computer picked while it cannot be reached (item 77): it
     * becomes the one on screen anyway, as a terminal opened with no link
     * does. Its picked (else last) pane is drawn from the cache and the
     * monitor reconnects to it. Main thread.
     */
    private void switchHostOffline(SavedRemoteHost host, String pane, boolean picked) {
        String shown = pane != null && !pane.isEmpty() ? pane : rememberedHostPane(host.id);
        if (shown.isEmpty()) {
            JSONArray[] last = lastLists(host.id);
            if (last != null) {
                try {
                    shown = FleetPanes.switchLanding(new JSONObject().put("workspaces", last[0])
                            .put("tabs", last[1]).put("panes", last[2]), "");
                } catch (JSONException unexpected) {
                    shown = "";
                }
            }
        }
        pickedAnotherHost();
        stopConnection();
        currentHostId = host.id;
        savedShownHost = null;
        savedShownAt = 0;
        if (!shown.isEmpty()) rememberHostPane(host.id, shown);
        installOffline(host.id, host.name, shown);
        showSaved(host.id, shown);
        if (picked) rail.requestRailReveal(host.id, pane);
        sidebarSignature = "";
        rail.refreshSidebar(true);
        if (monitor != null) monitor.lost();
        showLinkDown();
    }

    /** A Herdr session on a saved host: the pane outlives the link, so it can be resumed. */
    private boolean resumable(Connection failed) {
        return failed.remoteHandle > 0 && !shellMode() && currentHostId != null && monitor != null;
    }

    /**
     * Under stateLock, while the failed generation still owns the mailbox: keys taken from it are
     * dropped and the user is told, never replayed on another attach (what a key means depends on
     * the screen at that moment). Composer messages never pass here: the outbox holds them.
     */
    private void holdUnsent(Connection failed) {
        if (!failed.paneId.isEmpty()) rememberHostPane(failed.paneId);
        int dropped = 0;
        for (int i = 0; i < 64; i++) {
            String raw = nativeTakeCommand(failed.generation);
            if (raw == null) break;
            if (keyInput(raw)) dropped++;
        }
        if (dropped > 0) main.post(() -> note(OfflineSwitch.KEYS_REFUSED));
    }

    /** Input that would have reached the program; a close means leave. */
    private static boolean keyInput(String raw) {
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
        updateLinkBadge();
        refreshOffer();
        if (monitor == null || !monitor.reconnecting() || crumb == null) return;
        // The rail's machine row says offline too.
        rail.refreshSidebar(false);
    }

    /**
     * The top corner's indicator follows the link: offline or connecting, the
     * age of a cached pane on screen, what waits to be sent. The terminal itself
     * keeps its full colour and its scrolling. Main thread.
     */
    private void updateLinkBadge() {
        if (linkBadge == null || destroyed) return;
        LinkBadge.Look look;
        if (shellMode()) {
            // A raw shell's end, in the same corner: exited, or its connection lost.
            look = LinkBadge.shell(shellEnd, linkProblem);
        } else {
            // The whole queue, every computer and pane: a tap on the count opens it.
            int waiting = outbox == null ? 0 : outbox.outbox().waiting();
            String cachedHost = savedShownHost;
            long savedAt = savedShownAt;
            String when = cachedHost != null && savedAt > 0 ? OfflineSwitch.when(savedAt, System.currentTimeMillis(),
                    java.util.TimeZone.getDefault(), android.text.format.DateFormat.is24HourFormat(this),
                    java.util.Locale.getDefault()) : null;
            boolean down = monitor != null && monitor.reconnecting();
            boolean noNetwork = monitor != null && monitor.offline();
            boolean stopped = monitor != null && monitor.exhausted();
            look = LinkBadge.look(down, noNetwork, stopped, linkProblem, cachedHost != null, when, waiting);
        }
        boolean changed = look.shown != linkLook.shown;
        linkLook = look;
        if (look.shown) {
            android.text.SpannableStringBuilder text = new android.text.SpannableStringBuilder(LinkBadge.DOT);
            text.setSpan(new android.text.style.ForegroundColorSpan(
                    look.tone == LinkBadge.Tone.WARN ? chrome.warning : chrome.onSurfaceVariant), 0, text.length(), 0);
            text.append(' ').append(look.text);
            linkBadge.setTextColor(chrome.onSurfaceVariant);
            linkBadge.setText(text);
            linkBadge.setContentDescription(look.description);
            linkPrimary.setText(look.primary);
            linkPrimary.setContentDescription(look.title + " " + look.primary);
            linkHosts.setContentDescription(look.title + " " + LinkBadge.HOSTS);
        } else {
            linkActions.collapse();
        }
        renderLinkActions();
        linkBox.setVisibility(look.shown ? View.VISIBLE : View.GONE);
        if (changed) refreshTabStrip();
    }

    /** One small in-place action beside the indicator: drawn small, touched in 48 dp (linkTouch). */
    private TextView linkAction(View.OnClickListener action) {
        TextView button = new TextView(this);
        button.setTextSize(LinkBadge.TEXT_SP);
        button.setSingleLine(true);
        button.setGravity(android.view.Gravity.CENTER);
        button.setPadding(dp(8), 0, dp(8), 0);
        button.setMinWidth(dp(LinkActions.TOUCH_DP));
        button.setVisibility(View.GONE);
        button.setFocusable(false);
        button.setOnClickListener(action);
        return button;
    }

    private LinearLayout.LayoutParams linkActionParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-2, dp(24));
        params.leftMargin = dp(4);
        params.gravity = android.view.Gravity.CENTER_VERTICAL;
        return params;
    }

    /** Opens or closes the in-place actions; they close by themselves a few seconds later. Main thread. */
    private void toggleLinkActions() {
        if (destroyed || isFinishing()) return;
        if (outbox != null && outbox.outbox().waiting() > 0 && !linkActions.expanded()) {
            // "N queued": the queue first; Reconnect and Hosts are a second tap away.
            showQueueScreen();
            if (!linkLook.queuedOnly && linkActions.tap(linkLook.shown)) postLinkCollapse(linkActions.generation());
            renderLinkActions();
            return;
        }
        if (linkActions.tap(linkLook.shown)) postLinkCollapse(linkActions.generation());
        renderLinkActions();
    }

    /**
     * Closes the actions opened at {@code at} after the time the user gives
     * controls (at least LinkActions.COLLAPSE_MS), and never while TalkBack
     * is on one of them: then it waits again. Main thread.
     */
    private void postLinkCollapse(int at) {
        android.view.accessibility.AccessibilityManager access =
                (android.view.accessibility.AccessibilityManager) getSystemService(ACCESSIBILITY_SERVICE);
        long recommended = access == null ? LinkActions.COLLAPSE_MS : access.getRecommendedTimeoutMillis(
                (int) LinkActions.COLLAPSE_MS,
                android.view.accessibility.AccessibilityManager.FLAG_CONTENT_CONTROLS
                        | android.view.accessibility.AccessibilityManager.FLAG_CONTENT_TEXT);
        main.postDelayed(() -> {
            if (destroyed) return;
            if (linkActions.timeout(at, linkAccessibilityFocused())) renderLinkActions();
            else if (linkActions.waiting(at)) postLinkCollapse(at);
        }, LinkActions.collapseAfter(recommended));
    }

    /** Whether TalkBack (or another accessibility service) is on the indicator or one of its actions. */
    private boolean linkAccessibilityFocused() {
        for (View view : new View[] {linkBadge, linkPrimary, linkHosts}) {
            if (view != null && view.isAccessibilityFocused()) return true;
        }
        return false;
    }

    /** One in-place action was used: they close, then it runs. Main thread. */
    private void useLinkAction(Runnable action) {
        if (linkActions.collapse()) renderLinkActions();
        if (destroyed || isFinishing() || !linkLook.shown) return;
        action.run();
    }

    /** Draws the in-place actions as LinkActions says: beside the indicator, in its row. Main thread. */
    private void renderLinkActions() {
        if (linkPrimary == null) return;
        int shown = linkActions.expanded() ? View.VISIBLE : View.GONE;
        for (TextView button : new TextView[] {linkPrimary, linkHosts}) {
            button.setVisibility(shown);
            button.setTextColor(chrome.onSurface);
            button.setBackground(controlBackground(false, chrome.control, 8, 0));
        }
    }

    /**
     * Every touch passes here first (dispatchTouchEvent). While the indicator
     * shows, each of its controls takes touches in a region at least 48 dp
     * square around it, though drawn small. LinkActions.Press decides each
     * event: a press begun in the row is the control's; one begun under it,
     * where the region reaches into the terminal, also goes on to the
     * terminal and is a click only if it stays a tap (the terminal then gets a
     * cancel); a drag, a second finger or a cancel is never a click; a touch
     * anywhere else closes the in-place actions and reaches what it touched.
     * True when the indicator keeps the event.
     */
    private boolean linkTouch(MotionEvent event) {
        if (linkBox == null || linkBox.getVisibility() != View.VISIBLE) {
            linkPress.cancel();
            return false;
        }
        View[] targets = {linkBadge, linkPrimary, linkHosts};
        float x = event.getRawX();
        float y = event.getRawY();
        int verdict;
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                verdict = linkPress.down(linkRegions(targets), linkRowBottom(), x, y);
                if (linkPress.target() < 0 && linkActions.collapse()) renderLinkActions();
                break;
            case MotionEvent.ACTION_MOVE:
            case MotionEvent.ACTION_POINTER_DOWN:
            case MotionEvent.ACTION_POINTER_UP:
                // A second finger down (POINTER_DOWN counts it) ends any press.
                verdict = linkPress.move(x, y, event.getPointerCount(), linkTouchSlop());
                break;
            case MotionEvent.ACTION_UP: {
                int pressed = linkPress.target();
                verdict = linkPress.up(x, y, pressed >= 0 && targets[pressed].isShown(), linkTouchSlop());
                break;
            }
            case MotionEvent.ACTION_CANCEL:
                verdict = linkPress.cancel();
                break;
            default:
                verdict = LinkActions.Press.PASS;
        }
        if (verdict == LinkActions.Press.CANCEL_THEN_KEEP) {
            // The terminal saw this tap begin: it ends there as a cancel, not a tap.
            MotionEvent cancel = MotionEvent.obtain(event);
            cancel.setAction(MotionEvent.ACTION_CANCEL);
            super.dispatchTouchEvent(cancel);
            cancel.recycle();
        }
        int click = linkPress.takeClick();
        if (click >= 0) targets[click].performClick();
        return verdict != LinkActions.Press.PASS;
    }

    /** Where the indicator's row ends on screen: below it is content (the crumb, the terminal). */
    private int linkRowBottom() {
        View row = (View) linkBox.getParent();
        int[] at = new int[2];
        row.getLocationOnScreen(at);
        return at[1] + row.getHeight();
    }

    private float linkTouchSlop() {
        return android.view.ViewConfiguration.get(this).getScaledTouchSlop();
    }

    /** The shown controls' touch regions on screen, each grown to 48 dp; null for a hidden one. */
    private int[][] linkRegions(View[] targets) {
        android.util.DisplayMetrics screen = getResources().getDisplayMetrics();
        int[][] regions = new int[targets.length][];
        int[] at = new int[2];
        for (int i = 0; i < targets.length; i++) {
            View view = targets[i];
            if (view == null || !view.isShown() || view.getWidth() == 0) continue;
            view.getLocationOnScreen(at);
            regions[i] = LinkActions.grow(at[0], at[1], at[0] + view.getWidth(), at[1] + view.getHeight(),
                    dp(LinkActions.TOUCH_DP), screen.widthPixels, screen.heightPixels);
        }
        return regions;
    }

    /** A short note in the crumb's own slot, in place of any pop-up; null while none shows. */
    private String crumbNote;
    private final Runnable clearNote = () -> {
        crumbNote = null;
        crumbText = null;
        if (crumb == null) return;
        refreshTabStrip();
    };

    /**
     * Says something worth knowing (a failed action, a refused send) quietly,
     * in the status line where the crumb sits, for a few seconds; Herdr shows
     * no toasts, snackbars or pop-ups. Any thread.
     */
    void note(String text) {
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
        linkProblem = null;
        crumbText = null;
        updateLinkBadge();
        // The strip and the rail's machine row (live again).
        rail.refreshSidebar(false);
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
                nativeAttachShell(next.generation, next.shellHandle, LinkFrame.part(next.hostId),
                        LinkFrame.part(next.paneId));
                nativeSetActive(next.generation, true);
                publish(next, "Shell");
            }
            runOnUiThread(this::clearLinkProblem);
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
                boolean exited;
                synchronized (stateLock) {
                    if (!current(current)) return;
                    // The last screen stays readable and copyable until the user leaves.
                    current.ready = false;
                    nativeSetActive(current.generation, false);
                    if (current.poll != null) current.poll.cancel(false);
                    // The host ended it (an exit), or its connection was lost:
                    // the corner says which. Nothing typed is replayed either way.
                    exited = RemoteNative.shellExited(handle);
                    publish(current, exited ? BannerCopy.SHELL_EXITED : "Connection lost");
                }
                showShellEnd(exited ? LinkBadge.ShellEnd.EXITED : LinkBadge.ShellEnd.LOST,
                        exited ? BannerCopy.SHELL_EXITED : "The shell's connection was lost.");
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

    final class Connection {
        /**
         * A stand-in for a computer with no session (item 77): opened or
         * switched to with no link. Its lists are the last ones seen; it is
         * never polled, and the monitor's reconnect replaces it.
         */
        boolean offline;
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
        /** The saved revision that opened this connection, even if the profile is later edited. */
        String connectionRevision;
        /** That revision's pinned SSH host key and Herdr session: half of the machine's key. */
        volatile String fingerprint = "", session = "";
        /** The machine its hello proved, once bound; null before, and for a refused binding. */
        volatile MachineKey machine;
        /** The profile this one's machine collides with (a refused binding), or null. */
        volatile String refusedBy;
        /** The attach on screen (0: none) and whether it has painted, from the poll. */
        volatile long attach;
        volatile boolean painted;
        /** Its core offers the bounded read and incarnations (its hello's features). */
        volatile boolean transcripts;
        /** Its hello's features were seen, and a ready poll gave its own list (not a resumed one). */
        volatile boolean featuresKnown, ownList;
        /** The list revision last published as transcript proof; -1: none yet. */
        volatile long transcriptsListed = -1;
        volatile String paneId = launch.paneId;
        /** A pane switch asked for and not yet confirmed by the daemon, and until when to wait. */
        volatile String pendingPane = "";
        volatile long pendingUntil;
        volatile long remoteRevision;
        volatile long chromeRevision;
        volatile String remotePhase = "";
        volatile String status = "Connecting…";
        volatile long fastUntil;
        /** Milliseconds since the link last said anything, as the last poll saw it. */
        volatile long quietMs;
        /** Its first paint was logged. Under stateLock. */
        boolean paintLogged;
        volatile long renderedScroll;
        /** Last offset {@link #fetchRows} asked for and when, guarded by the connection. */
        long fetchedOffset = -1, fetchedAt;
        long nextRemotePollAt;
        volatile String title = launch.title;
        volatile String hostName = launch.name();
        String text = "";
        boolean remotePainted;
        JSONArray workspaces, tabs, panes;
        /** The core's projects.order doc (Wolf's order of repos and projects), or null. */
        JSONObject projectsOrder;
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
                snapshots.save(hostId, paneId, remoteHandle);
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
    private static native boolean nativeAttachShell(long generation, long handle, String host, String pane);
    private static native void nativeShowAgentFooter(boolean show);

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
    private static native void nativeSetTerminalTouchActive(boolean active);
    /** A touch-selection step for the drawn pane (TerminalSelection kinds), in logical pixels. */
    private static native boolean nativeSelect(long generation, int kind, float x, float y);
    private static native boolean nativeTouch(int action, int actionIndex, int[] ids, float[] xs, float[] ys,
            long tapStamp);
    private static native boolean nativeKey(long generation, int keyCode, int action, int metaState, int unicodeCodepoint);
    private static native void nativeSetImeSession(long generation, long session);
    private static native boolean nativeImeEvent(long generation, long session, int kind, String text, int start, int end);
}
