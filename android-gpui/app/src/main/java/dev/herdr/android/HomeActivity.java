package dev.herdr.android;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * The app's home: every machine the device trusts, connected in the background,
 * as one tree of Machine › Space › agents arranged by role (deacon, project
 * managers, workers, from the deacon skill's labels and hc's parent), with live
 * status. Tree guides draw who reports to whom; a deacon or manager folds its
 * reports and counts how many of them wait or work; each of the skill's agents
 * says its status in words, for how long, and its latest message (hc's status
 * msg). Folded it is one dense list; unfolded the tree is a persistent left
 * pane beside a Fleet overview of what is working, waiting or failing. A small
 * search button (or Ctrl+K) opens a picker across all of it. Hosts, keys and
 * settings stay on the host list ("Hosts").
 */
public final class HomeActivity extends Activity {
    private static final long POLL_MS = 1500;

    @Override protected void attachBaseContext(Context base) {
        super.attachBaseContext(ProvisionedSettings.scaled(base));
    }

    /** One machine's background session. Touched on the main thread only. */
    private static final class Machine {
        SavedRemoteHost host;
        long handle;
        boolean connecting;
        /** connecting, ready, closed, untrusted, nokey */
        String state = "connecting";
        String status = "";
        JSONObject view;
        /** The machine's git look (FleetTree.parseGitLook), when it was asked, and what. */
        FleetTree.GitLook git;
        long gitAt;
        boolean gitBusy;
        Set<String> gitAsked = new HashSet<>();
        /** Start Deacon / New project / opening a worktree: in flight, and the last error. */
        boolean deaconBusy, projectBusy;
        /** A Deacon just launched, not yet confirmed running: its terminal and when. */
        String deaconPending = "";
        long deaconPendingAt;
        String deaconError = "", projectError = "";
        final Set<String> opening = new HashSet<>();
        /** Bumped whenever any of the above changes, so the tree redraws. */
        int ui;
    }

    /** One agent (terminal) as the tree and the picker show it. */
    static final class Agent extends FleetTree.Member {
        Machine machine;
        /** The space's name as shown (spaceName is herdr's own, maybe empty). */
        String spaceLabel = "";
        int depth;
        /** Tree guide in front of the row: "│  ├ ", "└ ", nothing at the top. */
        String guide = "";
        /** Everything under it in the hierarchy, and how much of that waits or works. */
        int below, belowWaiting, belowWorking;
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newFixedThreadPool(4);
    private final Map<String, Machine> machines = new LinkedHashMap<>();
    /** Pages of unopened worktrees shown per project ("+N more" taps). */
    private final Map<String, Integer> closedPages = new HashMap<>();
    /** Folded rows (and "open:" rows that start folded), kept across launches. */
    private final Set<String> collapsed = new HashSet<>();
    /** Git looks: at most every GIT_MS per machine, sooner (GIT_NEW_MS) for a new folder. */
    private static final long GIT_MS = 300_000, GIT_NEW_MS = 15_000;
    /** A Start Deacon in flight blocks another for this long, across launches. */
    private static final long DEACON_GUARD_MS = 90_000;
    private RemoteHostStore store;
    private RemoteIdentityStore identityStore;
    private GpuiChrome chrome;
    private LinearLayout tree, fleet;
    private TextView summary;
    private String signature = "", chatter = "";
    /** When a working agent's message last rebuilt the tree on its own. */
    private long chatterAt;
    private static final long CHATTER_MS = 10_000;
    private boolean resumed, destroyed, opening;
    /** Hidden (onStop): no background sessions until the next onResume. */
    private boolean stopped;
    private String note;

    private final Runnable poll = new Runnable() {
        @Override public void run() {
            if (!resumed || destroyed) return;
            for (Machine machine : machines.values()) pollMachine(machine);
            main.postDelayed(this, POLL_MS);
        }
    };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(null);
        if (!ProvisionedSettings.capturable(this)) getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        RemoteNative.ensureLoaded(this);
        store = new RemoteHostStore(this);
        identityStore = new RemoteIdentityStore(this);
        int theme = getSharedPreferences("terminal-display", MODE_PRIVATE).getInt("theme", 0);
        boolean dark = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
        chrome = GpuiChrome.load(GpuiDisplayOptions.palette(theme, dark));
        try {
            collapsed.addAll(getSharedPreferences("home", MODE_PRIVATE).getStringSet("folds", Collections.emptySet()));
        } catch (RuntimeException unreadable) {
            // Folds start open.
        }
        // "Reopen the last session at launch" (Hosts): a fresh launch goes back to
        // the remembered agent once its machine is connected, if it still exists.
        if (state == null && RemoteHostsActivity.reopenEnabled(this)) {
            android.content.SharedPreferences prefs = getSharedPreferences("remote-startup", MODE_PRIVATE);
            reopenHost = prefs.getString("last_host", "");
            reopenPane = prefs.getString("last_pane", "");
        }
        build();
    }

    private String reopenHost = "", reopenPane = "";

    private void tryReopen(Machine machine) {
        if (reopenPane.isEmpty() || !machine.host.id.equals(reopenHost) || !"ready".equals(machine.state)) return;
        Map<String, List<Agent>> spaces = spaces(machine);
        // The first ready poll can precede the terminal list; wait for it.
        if (spaces.isEmpty()) return;
        String pane = reopenPane;
        reopenPane = "";
        for (List<Agent> agents : spaces.values()) {
            for (Agent agent : agents) {
                if (agent.id.equals(pane)) { openAgent(agent); return; }
            }
        }
    }

    @Override public void onConfigurationChanged(Configuration configuration) {
        super.onConfigurationChanged(configuration);
        // Folding or unfolding keeps every session; only the layout changes.
        build();
    }

    @Override protected void onResume() {
        super.onResume();
        stopped = false;
        resumed = true;
        opening = false;
        loadMachines();
        main.removeCallbacks(poll);
        main.post(poll);
    }

    @Override protected void onPause() {
        resumed = false;
        main.removeCallbacks(poll);
        super.onPause();
    }

    /**
     * Hidden behind a terminal, Home's sessions kept polling every machine
     * (and the terminal's rail opens its own), so each host was refreshed two
     * or three times over, every quarter second while agents worked. Close
     * them; returning reconnects (onResume → loadMachines).
     */
    @Override protected void onStop() {
        stopped = true;
        for (Machine machine : machines.values()) {
            if (machine.handle > 0) RemoteNative.close(machine.handle);
            machine.handle = 0;
            if (!machine.connecting) machine.state = "connecting";
        }
        super.onStop();
    }

    @Override protected void onDestroy() {
        destroyed = true;
        main.removeCallbacksAndMessages(null);
        for (Machine machine : machines.values()) {
            if (machine.handle > 0) RemoteNative.close(machine.handle);
            machine.handle = 0;
        }
        worker.shutdownNow();
        super.onDestroy();
    }

    @Override public boolean onKeyDown(int code, KeyEvent event) {
        if (code == KeyEvent.KEYCODE_K && event.isCtrlPressed()) {
            openPicker();
            return true;
        }
        return super.onKeyDown(code, event);
    }

    // ── Sessions ────────────────────────────────────────────────────────────

    /** Imports the provisioned hosts, then connects each trusted host not yet connected. */
    private void loadMachines() {
        worker.execute(() -> {
            String provisioned = ProvisionedHosts.importIfChanged(this, store);
            List<SavedRemoteHost> hosts;
            boolean identity;
            try {
                hosts = store.read();
                identity = identityStore.exists();
            } catch (Exception unreadable) {
                main.post(() -> { note = "Saved hosts could not be read"; render(true); });
                return;
            }
            main.post(() -> {
                if (destroyed) return;
                if (provisioned != null) note = provisioned;
                Map<String, Machine> next = new LinkedHashMap<>();
                for (SavedRemoteHost host : hosts) {
                    Machine machine = machines.remove(host.id);
                    if (machine == null) machine = new Machine();
                    machine.host = host;
                    next.put(host.id, machine);
                }
                for (Machine gone : machines.values()) if (gone.handle > 0) RemoteNative.close(gone.handle);
                machines.clear();
                machines.putAll(next);
                for (Machine machine : machines.values()) {
                    if (!"tcp".equals(machine.host.transport) && !"iroh".equals(machine.host.transport)) continue;
                    if (machine.host.expectedFingerprint.isEmpty()) {
                        machine.state = "untrusted";
                        machine.status = "Connect once from Hosts to trust its key";
                    } else if (!identity) {
                        machine.state = "nokey";
                        machine.status = "Create a device key in Hosts";
                    } else if (machine.handle <= 0 && !machine.connecting) {
                        connect(machine);
                    }
                }
                render(true);
            });
        });
    }

    private void connect(Machine machine) {
        machine.connecting = true;
        machine.state = "connecting";
        machine.status = "Connecting…";
        SavedRemoteHost host = machine.host;
        worker.execute(() -> {
            long handle = 0;
            String failure = null;
            char[] secret = null;
            try {
                secret = identityStore.read();
                if (RemoteIdentityStore.needsPassphrase(secret)) {
                    failure = "The device key needs its passphrase; connect from Hosts";
                } else {
                    String credential = new JSONObject().put("private_key", new String(secret)).toString();
                    // A background session lists terminals without attaching to one.
                    handle = RemoteNative.connectGrid(host.connectionJson(), credential, "80,24,8,16", false);
                    if (handle <= 0) failure = "Could not start the connection";
                }
            } catch (Exception | LinkageError error) {
                failure = error.getMessage() == null ? "Connection failed" : error.getMessage();
            } finally {
                if (secret != null) Arrays.fill(secret, '\0');
            }
            final long ready = handle;
            final String reason = failure;
            main.post(() -> {
                machine.connecting = false;
                if (destroyed || stopped || machines.get(host.id) != machine) {
                    if (ready > 0) RemoteNative.close(ready);
                    return;
                }
                if (ready > 0) machine.handle = ready;
                else { machine.state = "closed"; machine.status = reason; }
                render(false);
            });
        });
    }

    private void pollMachine(Machine machine) {
        long handle = machine.handle;
        if (handle <= 0) return;
        worker.execute(() -> {
            JSONObject view;
            try {
                view = new JSONObject(RemoteNative.poll(handle, 0, false));
            } catch (Exception | LinkageError unreadable) {
                return;
            }
            main.post(() -> {
                if (machine.handle != handle || destroyed) return;
                String state = view.optString("state", "connecting");
                machine.view = view;
                machine.status = view.optString("status", "");
                if ("closed".equals(state)) {
                    RemoteNative.close(handle);
                    machine.handle = 0;
                    machine.state = "closed";
                } else {
                    machine.state = state;
                }
                render(false);
                checkDeaconStart(machine);
                tryReopen(machine);
            });
        });
    }

    // ── Model ───────────────────────────────────────────────────────────────

    private static int rolePriority(String role) {
        switch (role) {
            case "deacon": return 0;
            case "pm": return 1;
            case "worker": return 2;
            default: return 3;
        }
    }

    /** Waiting for Wolf first, then failures, work, and the quiet ones. */
    private static int statusPriority(String status) {
        switch (status) {
            case "blocked": return 0;
            case "error": return 1;
            case "working": return 2;
            case "idle": return 3;
            case "done": return 4;
            default: return 5;
        }
    }

    /** Every agent of a machine, per space, in tree order with its depth. */
    private static Map<String, List<Agent>> spaces(Machine machine) {
        Map<String, List<Agent>> result = new LinkedHashMap<>();
        if (machine.view == null) return result;
        JSONArray panes = machine.view.optJSONArray("panes");
        JSONArray workspaces = machine.view.optJSONArray("workspaces");
        JSONArray tabs = machine.view.optJSONArray("tabs");
        Map<String, List<Agent>> flat = new LinkedHashMap<>();
        // The same adapter as the terminal's rail, so both draw one tree.
        for (Agent agent : FleetPanes.members(workspaces, tabs, panes, Agent::new)) {
            agent.machine = machine;
            agent.spaceLabel = agent.spaceName;
            if (agent.spaceLabel.isEmpty() && !"loose".equals(agent.space) && !agent.cwd.isEmpty()) {
                agent.spaceLabel = FleetTree.lastSegment(agent.cwd);
            }
            if (agent.spaceLabel.isEmpty()) agent.spaceLabel = "Other terminals";
            flat.computeIfAbsent(agent.space, key -> new ArrayList<>()).add(agent);
        }
        // The hierarchy spans spaces: a deacon's managers and their workers
        // usually live in their projects' own spaces, yet nest under it here.
        List<Agent> agents = new ArrayList<>();
        for (List<Agent> list : flat.values()) agents.addAll(list);
        Map<String, Agent> byId = new HashMap<>();
        for (Agent agent : agents) byId.put(agent.id, agent);
        Map<String, List<Agent>> children = new HashMap<>();
        Map<String, List<Agent>> roots = new LinkedHashMap<>();
        for (Agent agent : agents) {
            if (!agent.parent.isEmpty() && byId.containsKey(agent.parent) && !agent.parent.equals(agent.id)) {
                children.computeIfAbsent(agent.parent, key -> new ArrayList<>()).add(agent);
            } else {
                // Sections are per space name: two spaces with one name are one place to Wolf.
                roots.computeIfAbsent(agent.spaceLabel, key -> new ArrayList<>()).add(agent);
            }
        }
        Set<String> seen = new HashSet<>();
        for (Map.Entry<String, List<Agent>> entry : roots.entrySet()) {
            List<Agent> ordered = new ArrayList<>();
            sortAgents(entry.getValue());
            for (Agent root : entry.getValue()) walk(root, 0, "", true, children, ordered, seen);
            rollUp(ordered);
            result.put(entry.getKey(), ordered);
        }
        // Parent cycles: anything unvisited still shows, at the top of its space.
        for (Agent agent : agents) {
            if (seen.add(agent.id)) {
                agent.depth = 0;
                result.computeIfAbsent(agent.spaceLabel, key -> new ArrayList<>()).add(agent);
            }
        }
        // Busiest spaces first: waiting on Wolf, then failing, then working.
        List<Map.Entry<String, List<Agent>>> sorted = new ArrayList<>(result.entrySet());
        Collections.sort(sorted, (a, b) -> Integer.compare(activity(a.getValue()), activity(b.getValue())));
        Map<String, List<Agent>> ordered = new LinkedHashMap<>();
        for (Map.Entry<String, List<Agent>> entry : sorted) ordered.put(entry.getKey(), entry.getValue());
        result = ordered;
        return result;
    }

    /**
     * Agents put a spinner or progress glyph in front of their title ("⠂: Implement
     * lane 1"); the tree has its own status dot, so only the words stay.
     */
    static String cleanTitle(String title, String fallback) {
        int start = 0;
        while (start < title.length()) {
            int c = title.codePointAt(start);
            boolean glyph = (c >= 0x2800 && c <= 0x28ff) // braille spinners
                    || Character.isWhitespace(c) || c == ':' || c == '·' || c == '•' || c == '*'
                    || c == '✳' || c == '✶' || c == '✻' || c == '✽' || c == '✢' || c == '⋯' || c == '…'
                    || Character.getType(c) == Character.OTHER_SYMBOL;
            if (!glyph) break;
            start += Character.charCount(c);
        }
        String clean = title.substring(start).trim();
        return clean.isEmpty() ? fallback : clean;
    }

    /** Lower is busier: the most urgent status present, then fewer idle agents. */
    private static int activity(List<Agent> agents) {
        int best = 5, working = 0;
        for (Agent agent : agents) {
            best = Math.min(best, statusPriority(agent.status));
            if ("working".equals(agent.status)) working++;
        }
        return best * 1000 - Math.min(working, 999);
    }

    private static void sortAgents(List<Agent> agents) {
        Collections.sort(agents, (a, b) -> {
            int role = Integer.compare(rolePriority(a.role), rolePriority(b.role));
            if (role != 0) return role;
            return Integer.compare(statusPriority(a.status), statusPriority(b.status));
        });
    }

    private static void walk(Agent agent, int depth, String prefix, boolean last,
            Map<String, List<Agent>> children, List<Agent> out, Set<String> seen) {
        if (!seen.add(agent.id) || depth > 8) return;
        agent.depth = depth;
        agent.guide = depth == 0 ? "" : prefix + (last ? "└ " : "├ ");
        agent.below = agent.belowWaiting = agent.belowWorking = 0;
        out.add(agent);
        List<Agent> kids = children.get(agent.id);
        if (kids == null) return;
        sortAgents(kids);
        String next = depth == 0 ? "" : prefix + (last ? "   " : "│  ");
        for (int i = 0; i < kids.size(); i++) {
            walk(kids.get(i), depth + 1, next, i == kids.size() - 1, children, out, seen);
        }
    }

    /** Counts each agent's reports, all the way down, from the tree order. */
    static void rollUp(List<Agent> ordered) {
        List<Agent> path = new ArrayList<>();
        for (Agent agent : ordered) {
            while (!path.isEmpty() && path.get(path.size() - 1).depth >= agent.depth) path.remove(path.size() - 1);
            for (Agent above : path) {
                above.below++;
                if (statusPriority(agent.status) <= 1) above.belowWaiting++;
                if ("working".equals(agent.status)) above.belowWorking++;
            }
            path.add(agent);
        }
    }

    /** What the deacon view says of a status, in Wolf's words. */
    static String statusWord(String status, String waitKind) {
        switch (status) {
            case "blocked": return waitKind == null || waitKind.isEmpty() ? "needs you" : "needs you: " + waitKind;
            case "error": return "error";
            case "working": return "working";
            case "idle": return "idle";
            case "done": return "done";
            default: return status.isEmpty() ? "no status" : status;
        }
    }

    /** "now", "4m", "2h", "3d": how long a status has held. Empty when unknown. */
    static String age(long sinceMs, long nowMs) {
        if (sinceMs <= 0) return "";
        long minutes = Math.max(0, nowMs - sinceMs) / 60_000;
        if (minutes < 1) return "now";
        if (minutes < 60) return minutes + "m";
        if (minutes < 48 * 60) return (minutes / 60) + "h";
        return (minutes / (24 * 60)) + "d";
    }

    private List<Agent> allAgents() {
        List<Agent> all = new ArrayList<>();
        for (Machine machine : machines.values()) {
            for (List<Agent> agents : spaces(machine).values()) all.addAll(agents);
        }
        return all;
    }

    // ── Layout ──────────────────────────────────────────────────────────────

    private boolean wide() {
        return getResources().getConfiguration().screenWidthDp >= 600;
    }

    private void build() {
        getWindow().setBackgroundDrawable(new ColorDrawable(chrome.background));
        getWindow().setStatusBarColor(chrome.background);
        getWindow().setNavigationBarColor(chrome.background);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(chrome.background);
        root.setFitsSystemWindows(true);
        // The top bar is hidden by default (Wolf: no wasted space). Its toggle,
        // the picker and Hosts float at the top right while it is hidden.
        boolean barShown = getSharedPreferences("home", MODE_PRIVATE).getBoolean("top_bar", false);
        View bar = topBar(barShown);
        if (barShown) {
            root.addView(bar, new LinearLayout.LayoutParams(-1, dp(36)));
            root.addView(divider(), new LinearLayout.LayoutParams(-1, 1));
        }
        tree = column();
        tree.setPadding(dp(6), dp(2), dp(6), dp(16));
        // One computer at a time, as the desktop sidebar: its picker and Deacon
        // card, then the repos, projects, worktrees and workers below.
        header = column();
        machineBar = column();
        deaconCard = column();
        header.addView(machineBar);
        header.addView(deaconCard);
        body = column();
        tree.addView(header);
        tree.addView(body);
        ScrollView treeScroll = scroll(tree);
        fleet = column();
        fleet.setPadding(dp(10), dp(4), dp(10), dp(16));
        if (wide()) {
            LinearLayout panes = new LinearLayout(this);
            panes.setOrientation(LinearLayout.HORIZONTAL);
            panes.addView(treeScroll, new LinearLayout.LayoutParams(0, -1, 0.42f));
            panes.addView(divider(), new LinearLayout.LayoutParams(1, -1));
            panes.addView(scroll(fleet), new LinearLayout.LayoutParams(0, -1, 0.58f));
            root.addView(panes, new LinearLayout.LayoutParams(-1, 0, 1));
        } else {
            root.addView(treeScroll, new LinearLayout.LayoutParams(-1, 0, 1));
        }
        if (barShown) {
            setContentView(root);
        } else {
            android.widget.FrameLayout frame = new android.widget.FrameLayout(this);
            frame.setBackgroundColor(chrome.background);
            frame.setFitsSystemWindows(true);
            root.setFitsSystemWindows(false);
            frame.addView(root, new android.widget.FrameLayout.LayoutParams(-1, -1));
            frame.addView(floatingControls(), new android.widget.FrameLayout.LayoutParams(-2, -2,
                    Gravity.TOP | Gravity.END));
            setContentView(frame);
        }
        // The insets controller belongs to the decor view, which exists only now.
        if (Build.VERSION.SDK_INT >= 30 && getWindow().getInsetsController() != null) {
            int light = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                    | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
            getWindow().getInsetsController().setSystemBarsAppearance(chrome.light ? light : 0, light);
        }
        render(true);
    }

    /** ⌕, ⋯ and the bar toggle, small on black, while the top bar is hidden. */
    private View floatingControls() {
        LinearLayout controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.HORIZONTAL);
        controls.setBackgroundColor(chrome.background);
        controls.addView(smallButton("⌕", "Search machines, spaces and agents (Ctrl+K)", v -> openPicker()));
        controls.addView(smallButton("⋯", "Hosts, device key and settings", v -> openHosts()));
        controls.addView(smallButton("▾", "Show the top bar", v -> setTopBar(true)));
        return controls;
    }

    private TextView smallButton(String glyph, String description, View.OnClickListener action) {
        TextView button = iconButton(glyph, description, action);
        button.setTextSize(15);
        button.setLayoutParams(new LinearLayout.LayoutParams(dp(32), dp(30)));
        return button;
    }

    private void setTopBar(boolean shown) {
        getSharedPreferences("home", MODE_PRIVATE).edit().putBoolean("top_bar", shown).apply();
        build();
    }

    private void openHosts() {
        startActivity(new Intent(this, RemoteHostsActivity.class).putExtra("skip_reopen", true));
    }

    private View topBar(boolean shown) {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(12), 0, dp(4), 0);
        TextView title = text("Herdr", 15, chrome.onSurface);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        bar.addView(title);
        summary = text("", 12, chrome.onSurfaceVariant);
        summary.setSingleLine(true);
        summary.setEllipsize(TextUtils.TruncateAt.END);
        summary.setPadding(dp(10), 0, dp(6), 0);
        bar.addView(summary, new LinearLayout.LayoutParams(0, -2, 1));
        bar.addView(iconButton("⌕", "Search machines, spaces and agents (Ctrl+K)", v -> openPicker()));
        bar.addView(iconButton("⋯", "Hosts, device key and settings", v -> openHosts()));
        bar.addView(iconButton("▴", "Hide the top bar", v -> setTopBar(false)));
        return bar;
    }

    private TextView iconButton(String glyph, String description, View.OnClickListener action) {
        TextView button = text(glyph, 18, chrome.onSurfaceVariant);
        button.setGravity(Gravity.CENTER);
        button.setContentDescription(description);
        button.setOnClickListener(action);
        button.setFocusable(true);
        button.setBackground(ripple());
        button.setLayoutParams(new LinearLayout.LayoutParams(dp(40), dp(40)));
        return button;
    }

    // ── Rendering ───────────────────────────────────────────────────────────

    /** Rebuilds the tree and the Fleet only when what they show changed. */
    private void render(boolean force) {
        if (tree == null || destroyed) return;
        long now = System.currentTimeMillis();
        StringBuilder key = new StringBuilder(String.valueOf(collapsed)).append(note);
        // Working agents rewrite their message as they go; like their spinner
        // titles, rebuilding for each would flicker and drop taps, so those
        // messages alone refresh the tree at most every CHATTER_MS.
        StringBuilder messages = new StringBuilder();
        // Keyed on what the tree shows, not the raw pane list: agents retitle
        // themselves with spinner glyphs many times a second, and rebuilding
        // the whole tree for each made it flicker and dropped taps.
        for (Machine machine : machines.values()) {
            key.append(machine.host.id).append(machine.state).append(machine.status).append(machine.ui)
                    .append(machine.git == null ? 0 : System.identityHashCode(machine.git)).append('\n');
            for (Map.Entry<String, List<Agent>> space : spaces(machine).entrySet()) {
                key.append(space.getKey()).append('\n');
                for (Agent agent : space.getValue()) {
                    key.append(agent.id).append('\t').append(agent.title).append('\t').append(agent.status)
                            .append('\t').append(agent.depth).append('\t').append(agent.role)
                            .append('\t').append(agent.kind).append('\t').append(agent.project)
                            .append('\t').append(agent.parent).append('\t').append(agent.cwd)
                            .append('\t').append(agent.waitKind).append('\t').append(age(agent.statusMs, now))
                            .append('\t').append("working".equals(agent.status) ? "" : agent.msg).append('\n');
                    if ("working".equals(agent.status)) messages.append(agent.id).append(agent.msg).append('\n');
                }
            }
        }
        String next = key.toString(), said = messages.toString();
        boolean chatty = !said.equals(chatter) && now - chatterAt >= CHATTER_MS;
        if (!force && next.equals(signature) && !chatty) return;
        signature = next;
        if (!said.equals(chatter)) { chatter = said; chatterAt = now; }
        List<Agent> all = allAgents();
        int[] counts = new int[6];
        for (Agent agent : all) counts[statusPriority(agent.status)]++;
        summary.setText(summaryText(counts));
        body.removeAllViews();
        machineBar.removeAllViews();
        deaconCard.removeAllViews();
        if (note != null) {
            TextView line = text(note, 12, chrome.onSurfaceVariant);
            line.setPadding(dp(6), dp(4), dp(6), dp(4));
            machineBar.addView(line);
        }
        if (machines.isEmpty()) {
            TextView empty = text("No hosts yet. Add one under ⋯ Hosts, or let the device's "
                    + "provisioned hosts file add them.", 13, chrome.onSurfaceVariant);
            empty.setPadding(dp(8), dp(16), dp(8), dp(16));
            body.addView(empty);
        }
        Machine shown = shownMachine();
        if (shown != null) renderMachine(shown);
        if (fleet != null && wide()) {
            fleet.removeAllViews();
            fleetOverview(all, counts);
        }
    }

    private String summaryText(int[] counts) {
        List<String> parts = new ArrayList<>();
        if (counts[0] > 0) parts.add(counts[0] + " waiting");
        if (counts[1] > 0) parts.add(counts[1] + " error");
        parts.add(counts[2] + " working");
        int connected = 0;
        for (Machine machine : machines.values()) if ("ready".equals(machine.state)) connected++;
        parts.add(connected + "/" + machines.size() + " machines");
        return TextUtils.join(" · ", parts);
    }

    /** Every terminal of a machine, flat, in space/tab order. */
    private static List<Agent> members(Machine machine) {
        List<Agent> all = new ArrayList<>();
        for (List<Agent> list : spaces(machine).values()) all.addAll(list);
        return all;
    }

    /**
     * One machine: its header (fold, status, Deacon / Start Deacon), then the
     * Fleet tree of the desktop's contract: the Deacon, each project once
     * with its PM, main and worktree rows (open ones live, closed ones dim)
     * and their agents nested by parent, then Spaces, Other agents and New
     * project. Built from this machine's own core only.
     */
    /** The computer Home shows: the one Wolf picked, else the busiest. */
    private Machine shownMachine() {
        if (machines.isEmpty()) return null;
        if (selectedHost == null) {
            try {
                selectedHost = getSharedPreferences("home", MODE_PRIVATE).getString("machine", null);
            } catch (RuntimeException unreadable) {
                selectedHost = null;
            }
        }
        Machine picked = selectedHost == null ? null : machines.get(selectedHost);
        if (picked != null) return picked;
        Machine best = null;
        int bestScore = Integer.MAX_VALUE;
        for (Machine machine : machines.values()) {
            int score = "ready".equals(machine.state) ? activity(members(machine)) : 9000;
            if (best == null || score < bestScore) { best = machine; bestScore = score; }
        }
        return best;
    }

    /**
     * Picking a computer shows it and focuses its canonical live Deacon (the
     * exact terminal). With none, its card offers Start Deacon; nothing starts.
     */
    private void pickMachine(Machine machine) {
        selectedHost = machine.host.id;
        try {
            getSharedPreferences("home", MODE_PRIVATE).edit().putString("machine", selectedHost).apply();
        } catch (RuntimeException ignored) {
            // The pick lasts this session.
        }
        render(true);
        // Only a connected computer's list is live; a cached one is never focused.
        boolean connected = "ready".equals(machine.state) && machine.handle > 0;
        switch (FleetTree.pick(connected, members(machine))) {
            case FOCUS: openAgent((Agent) FleetTree.pickTarget(members(machine))); break;
            case START:
                note = "No Deacon on " + machine.host.name + ". Use Start Deacon to start one.";
                render(true);
                break;
            default:
                note = machine.host.name + " is offline.";
                render(true);
                break;
        }
    }

    /** Every computer, with how many agents it runs and how many wait on Wolf. */
    private void showMachinePicker() {
        ActionSheet sheet = new ActionSheet(this, chrome, "Computer");
        for (Machine machine : machines.values()) {
            int[] n = agentCounts(machine);
            boolean here = machine == shownMachine();
            String label = (here ? "● " : "○ ") + machine.host.name + "   " + n[0] + " agents"
                    + (n[1] > 0 ? " · " + n[1] + " need you" : "")
                    + ("ready".equals(machine.state) ? "" : " · offline");
            sheet.add(label, () -> pickMachine(machine));
        }
        sheet.add("Hosts and keys…", this::openHosts);
        sheet.show();
    }

    /** {agents, blocked} on one computer. */
    private static int[] agentCounts(Machine machine) {
        int[] n = new int[2];
        for (Agent agent : members(machine)) {
            if (!agent.isAgent()) continue;
            n[0]++;
            if ("blocked".equals(agent.status)) n[1]++;
        }
        return n;
    }

    /**
     * One computer, as the desktop's single-machine sidebar: the computer
     * picker, its Deacon card (status, Needs you, the message box, + Project),
     * then its repos with their projects, worktrees and workers, unopened
     * worktrees folded per repo, Outside git folded, Other agents.
     */
    private void renderMachine(Machine machine) {
        List<Agent> all = members(machine);
        FleetTree.GitLook shared = FleetPanes.LOOKS.get(machine.host.id);
        if (shared != null && shared != machine.git) {
            machine.git = shared;
            machine.gitAt = Math.max(machine.gitAt, FleetPanes.LOOKED_AT.getOrDefault(machine.host.id, 0L));
        }
        FleetTree.Fleet fleet = FleetTree.build(all, machine.git);
        boolean ready = "ready".equals(machine.state);
        if (fleet.deacon != null && deaconGuard(machine) > 0 && machine.deaconPending.isEmpty()) clearDeaconGuard(machine);
        // Claude runs there again (Wolf started it): the failure no longer applies.
        if (fleet.deacon != null && machine.deaconPending.isEmpty() && !fleet.deacon.status.isEmpty()) {
            machine.deaconError = "";
        }
        maybeLookGit(machine, all, ready, false);

        // The computer picker: name, agents, how many need you, ▾ for the others.
        LinearLayout row = row();
        row.setPadding(dp(4), dp(7), dp(4), dp(3));
        row.addView(dot(machineColor(machine)));
        TextView name = text(machine.host.name + (machines.size() > 1 ? "  ▾" : ""), 14, chrome.onSurface);
        name.setTypeface(Typeface.DEFAULT_BOLD);
        row.addView(name);
        int[] n = agentCounts(machine);
        String detail = ready ? n[0] + (n[0] == 1 ? " agent" : " agents") + (n[1] > 0 ? " · " + n[1] + "!" : "")
                : machine.status.isEmpty() ? "offline" : machine.status;
        TextView meta = text("  " + detail, 11, n[1] > 0 ? chrome.danger : chrome.onSurfaceVariant);
        meta.setSingleLine(true);
        meta.setEllipsize(TextUtils.TruncateAt.END);
        row.addView(meta, new LinearLayout.LayoutParams(0, -2, 1));
        switch (FleetTree.deaconAction(ready, fleet.deacon, machine.deaconBusy || deaconGuard(machine) > 0)) {
            case OPEN:
                if (!machine.deaconPending.isEmpty()) row.addView(chip("Starting…", false, null));
                else row.addView(chip("Deacon", true, () -> openAgent((Agent) fleet.deacon)));
                break;
            case START: row.addView(chip("Start Deacon", true, () -> startDeacon(machine))); break;
            case STARTING: row.addView(chip("Starting…", false, null)); break;
            default: break;
        }
        if (ready) {
            TextView plus = chip("+", !machine.projectBusy && !machine.deaconBusy, () -> {
                if (fleet.deacon != null) newProject(machine, (Agent) fleet.deacon);
                else startDeacon(machine);
            });
            plus.setContentDescription(fleet.deacon != null
                    ? "New project on " + machine.host.name + ": ask its Deacon"
                    : "New project on " + machine.host.name + ": start its Deacon first");
            row.addView(plus);
        }
        row.setOnClickListener(v -> {
            if (machines.size() > 1) showMachinePicker();
            if ("closed".equals(machine.state) && machine.handle <= 0 && !machine.connecting) connect(machine);
        });
        row.setContentDescription("Computer " + machine.host.name + ", " + detail
                + (machines.size() > 1 ? ", tap to pick another" : ""));
        row.setOnLongClickListener(v -> {
            ActionSheet sheet = new ActionSheet(this, chrome, machine.host.name);
            if (fleet.deacon != null) sheet.add("Open the Deacon", () -> openAgent((Agent) fleet.deacon));
            else if (ready) sheet.add("Start Deacon", () -> startDeacon(machine));
            sheet.add("Reconnect", () -> {
                        if (machine.handle > 0) RemoteNative.close(machine.handle);
                        machine.handle = 0;
                        if (!machine.connecting) connect(machine);
                        render(true);
                    })
                    .add("Hosts and keys…", this::openHosts)
                    .show();
            return true;
        });
        machineBar.addView(row);

        deaconRows(machine, fleet, ready);

        LinearLayout rows = column();
        // Offline: what was last seen stays, dimmed; nothing can be started there.
        if (!ready) rows.setAlpha(0.5f);
        for (Row top : bodyRows(machine, fleet, ready)) emit(rows, top, "", true, 0);
        body.addView(rows);
    }

    private LinearLayout header, body, machineBar, deaconCard;
    private String selectedHost;

    /** The Deacon card: the Deacon (id, status), its state, Needs you, + Project. */
    private void deaconRows(Machine machine, FleetTree.Fleet fleet, boolean ready) {
        if (fleet.deacon == null) {
            boolean busy = machine.deaconBusy || deaconGuard(machine) > 0;
            String said = !ready ? "No Deacon. Connect this computer to start one."
                    : busy ? "Starting the Deacon…"
                    : machine.deaconError.isEmpty() ? "No Deacon on this computer. Tap to start one."
                    : "Could not start the Deacon: " + machine.deaconError + ". Tap to try again.";
            deaconCard.addView(actionLine("", null, "◆", said, machine.deaconError.isEmpty()
                    ? chrome.onSurfaceVariant : chrome.danger, ready && !busy ? () -> startDeacon(machine) : null));
            return;
        }
        Agent deacon = (Agent) fleet.deacon;
        deaconCard.addView(agentLine("", deacon, "Deacon " + deacon.id, null));
        if (deacon.id.equals(machine.deaconPending)) {
            deaconCard.addView(actionLine("   ", null, "◆", "Starting the Deacon on " + machine.host.name + "…",
                    chrome.onSurfaceVariant, null));
        } else if (!machine.deaconError.isEmpty() || !FleetTree.running(deacon)) {
            String said = machine.deaconError.isEmpty() ? notRunning(machine) : machine.deaconError;
            deaconCard.addView(actionLine("   ", null, "!", said + ". Tap to open it.", chrome.danger,
                    () -> openAgent(deacon)));
        }
        for (FleetTree.Member helper : fleet.helpers) deaconCard.addView(agentLine("   ", (Agent) helper, roleTag((Agent) helper), null));
        // Needs you: agents whose own status says they wait on Wolf; each opens its chat.
        List<FleetTree.Member> needs = FleetTree.needsYou(fleet);
        if (!needs.isEmpty()) {
            TextView heading = text("Needs you", 11, chrome.danger);
            heading.setPadding(dp(14), dp(4), dp(4), dp(1));
            deaconCard.addView(heading);
            for (FleetTree.Member waiting : needs) {
                Agent agent = (Agent) waiting;
                String who = "deacon".equals(agent.role) ? "Deacon · "
                        : "pm".equals(agent.role) ? "PM " + (agent.project.isEmpty() ? "" : agent.project + " · ") : "";
                deaconCard.addView(actionLine("   ", null, "!", who + FleetPanes.displayTitle(agent), chrome.danger,
                        () -> openAgent(agent)));
            }
        }
        String said = machine.projectBusy ? "Asking the Deacon…"
                : machine.projectError.isEmpty() ? "+ Project (asks the Deacon)"
                : "Could not reach the Deacon: " + machine.projectError + ". Tap to try again.";
        deaconCard.addView(actionLine("   ", null, "+", said, machine.projectError.isEmpty() ? chrome.primary : chrome.danger,
                ready && !machine.projectBusy ? () -> newProject(machine, deacon) : null));
    }

    /** One row of the tree and the rows it folds. */
    private static final class Row {
        java.util.function.BiFunction<String, Row, View> view;
        final List<Row> kids = new ArrayList<>();
        /** Fold key, or null when it cannot fold; folded by default flips the key's meaning. */
        String fold;
        boolean foldedByDefault;
        Row(java.util.function.BiFunction<String, Row, View> view) { this.view = view; }
    }

    private boolean folded(Row row) {
        if (row.fold == null || row.kids.isEmpty()) return false;
        return row.foldedByDefault ? !collapsed.contains("open:" + row.fold) : collapsed.contains(row.fold);
    }

    private void toggleFold(String key, boolean foldedByDefault) {
        String stored = foldedByDefault ? "open:" + key : key;
        if (!collapsed.remove(stored)) collapsed.add(stored);
        try {
            getSharedPreferences("home", MODE_PRIVATE).edit().putStringSet("folds", new HashSet<>(collapsed)).apply();
        } catch (RuntimeException ignored) {
            // Folds are a convenience; the tree still works without storage.
        }
        render(true);
    }

    /** Draws a row with its tree guide, then its children unless folded. */
    private void emit(LinearLayout into, Row row, String prefix, boolean last, int depth) {
        String guide = depth == 0 ? "" : prefix + (last ? "└ " : "├ ");
        into.addView(row.view.apply(guide, row));
        if (folded(row)) return;
        String next = depth == 0 ? "" : prefix + (last ? "   " : "│  ");
        for (int i = 0; i < row.kids.size(); i++) emit(into, row.kids.get(i), next, i == row.kids.size() - 1, depth + 1);
    }

    /** Repos (each with its projects, loose spaces and unopened worktrees), Outside git, Other agents. */
    private List<Row> bodyRows(Machine machine, FleetTree.Fleet fleet, boolean ready) {
        String m = machine.host.id;
        List<Row> rows = new ArrayList<>();
        for (FleetTree.RepoGroup group : FleetTree.repoGroups(fleet)) {
            String key = m + (group.outside() ? "/outside" : "/r/" + group.repo);
            int pms = group.pms();
            String tally = tally(group.tally);
            String count = group.outside() ? String.valueOf(group.projects.size() + group.loose.size())
                    : pms + (pms == 1 ? " PM" : " PMs") + (tally.isEmpty() ? "" : "  " + tally);
            Row repo = new Row((guide, self) -> groupLine(guide, self, group.name, count));
            repo.fold = key;
            repo.foldedByDefault = group.outside();
            for (FleetTree.Project project : group.projects) repo.kids.add(projectRow(machine, project));
            for (FleetTree.SpaceNode node : group.loose) repo.kids.add(spaceRow(machine, node, null));
            if (group.owner != null) repo.kids.add(unopenedRow(machine, group));
            rows.add(repo);
        }
        if (!fleet.others.isEmpty()) {
            Row others = new Row((guide, self) -> groupLine(guide, self, "Other agents", fleet.others.size() + ""));
            others.fold = m + "/others";
            for (FleetTree.Member other : fleet.others) {
                Agent agent = (Agent) other;
                String tag = "deacon".equals(agent.role) ? "another deacon" : roleTag(agent);
                others.kids.add(new Row((guide, self) -> agentLine(guide, agent, tag, null)));
            }
            rows.add(others);
        }
        return rows;
    }

    /** A repo's unopened worktrees: ONE folded row; open, a page at a time. */
    private Row unopenedRow(Machine machine, FleetTree.RepoGroup group) {
        FleetTree.Project owner = group.owner;
        String key = machine.host.id + "/u/" + group.repo;
        int total = owner.closed.size();
        Row row = new Row((guide, self) -> groupLine(guide, self, total + (total == 1 ? " worktree" : " worktrees")
                + " with no space" + (owner.closedIncomplete ? " (list cut short)" : ""), ""));
        row.fold = key;
        row.foldedByDefault = true;
        int pages = closedPages.getOrDefault(key, 0);
        int shown = FleetTree.shownClosed(owner, pages);
        for (int i = 0; i < shown; i++) row.kids.add(closedRow(machine, owner.closed.get(i)));
        int more = total - shown;
        if (more > 0) {
            row.kids.add(new Row((guide, self) -> actionLine(guide, null, "○", "+" + more + " more worktrees",
                    chrome.primary, () -> { closedPages.put(key, pages + 1); machine.ui++; render(true); })));
        }
        if (owner.closedIncomplete) {
            row.kids.add(new Row((guide, self) -> actionLine(guide, null, "!", "Git's list was cut short;"
                    + " some worktrees may be missing", chrome.warning, null)));
        }
        return row;
    }

    private Row projectRow(Machine machine, FleetTree.Project project) {
        String key = machine.host.id + "/p/" + project.name;
        String tally = tally(project.tally);
        Row row;
        if (project.pm != null) {
            Agent pm = (Agent) project.pm;
            // The project's name leads its row; the manager's own title is the second line.
            String tag = "PM" + (tally.isEmpty() ? "" : "  " + tally);
            row = new Row((guide, self) -> agentLine(guide, pm, tag, self, project.name));
        } else {
            row = new Row((guide, self) -> actionLine(guide, self, "●", project.name + " · no PM"
                    + (tally.isEmpty() ? "" : "  " + tally), chrome.onSurfaceVariant, () -> askForManager(machine, project)));
        }
        row.fold = key;
        // Projects start folded, as on the desktop: a repo shows its PM rows,
        // opening one shows its worktrees and workers. Home reveals nothing on its own.
        row.foldedByDefault = true;
        boolean flat = FleetTree.flat(project);
        for (FleetTree.SpaceNode entry : project.entries) {
            if (flat) {
                for (FleetTree.AgentNode node : entry.agents) row.kids.add(agentTree(machine.host.id, node, null));
            } else {
                row.kids.add(spaceRow(machine, entry, project));
            }
        }
        // Unopened worktrees are listed once per repo (unopenedRow), not under projects.
        return row;
    }

    /** A main checkout or worktree with its agents; every space at that checkout is one row. */
    private Row spaceRow(Machine machine, FleetTree.SpaceNode node, FleetTree.Project project) {
        // Namespaced by project: one checkout can show in two projects' groups.
        String key = machine.host.id + "/s/" + (project == null ? "" : project.name) + "/" + node.key;
        Agent first = null;
        int[] tally = new int[4];
        int worst = 5;
        for (FleetTree.Member member : node.members) {
            if (first == null) first = (Agent) member;
            worst = Math.min(worst, statusPriority(member.status));
            switch (member.status) {
                case "blocked": case "error": tally[0]++; break;
                case "done": tally[1]++; break;
                case "working": tally[2]++; break;
                case "idle": tally[3]++; break;
                default: break;
            }
        }
        final Agent open = first;
        final String worstStatus = worst == 0 ? "blocked" : worst == 1 ? "error" : worst == 2 ? "working"
                : worst == 3 ? "idle" : worst == 4 ? "done" : "";
        String sub = (!node.branch.isEmpty() && !node.branch.equals(node.name) ? node.branch : "")
                + (node.spaces.size() > 1 ? (node.branch.isEmpty() || node.branch.equals(node.name) ? "" : " · ")
                        + node.spaces.size() + " spaces" : "");
        String mark = node.worktree ? "⑂ " : "";
        Row row = new Row((guide, self) -> {
            LinearLayout line = treeLine(guide, self);
            line.addView(dot(statusColor(worstStatus)));
            LinearLayout lines = column();
            lines.addView(single(text(mark + node.name, 12, chrome.primary)));
            if (!sub.isEmpty()) lines.addView(single(text(sub, 10, chrome.onSurfaceVariant)));
            line.addView(lines, new LinearLayout.LayoutParams(0, -2, 1));
            String counts = tally(tally);
            if (!counts.isEmpty()) line.addView(tag(counts, chrome.onSurfaceVariant));
            line.setContentDescription((node.worktree ? "Worktree " : "Checkout ") + node.name
                    + (sub.isEmpty() ? "" : ", " + sub) + ", " + node.members.size() + " terminals");
            if (open != null) line.setOnClickListener(v -> openAgent(open));
            return line;
        });
        row.fold = key;
        row.foldedByDefault = node.members.size() > 6 && tally[0] == 0;
        for (FleetTree.AgentNode agent : node.agents) row.kids.add(agentTree(machine.host.id, agent, null));
        return row;
    }

    /** A worktree no space has open: dim; a tap opens a shell space there. */
    private Row closedRow(Machine machine, FleetTree.GitWorktree wt) {
        String name = wt.branch.isEmpty() ? FleetTree.lastSegment(wt.path) + " (detached)" : wt.branch;
        boolean busy = machine.opening.contains(wt.path);
        return new Row((guide, self) -> {
            View line = actionLine(guide, null, "○", busy ? name + " · opening…" : name, chrome.onSurfaceVariant,
                    busy || !"ready".equals(machine.state) ? null : () -> closedWorktreeActions(machine, wt, name));
            line.setContentDescription("Worktree " + name + ", not open. Choose who works there.");
            return line;
        });
    }

    private Row agentTree(String machineId, FleetTree.AgentNode node, String tagOverride) {
        Agent agent = (Agent) node.member;
        String key = machineId + "/a/" + agent.id;
        Row row = new Row((guide, self) -> agentLine(guide, agent, tagOverride != null ? tagOverride : roleTag(agent), self));
        row.fold = key;
        for (FleetTree.AgentNode kid : node.children) row.kids.add(agentTree(machineId, kid, null));
        return row;
    }

    /** "1! 2✓ 3▶" over blocked, done, working; idle is the rest. */
    private static String tally(int[] n) {
        List<String> parts = new ArrayList<>();
        if (n[0] > 0) parts.add(n[0] + "!");
        if (n[1] > 0) parts.add(n[1] + "✓");
        if (n[2] > 0) parts.add(n[2] + "▶");
        return TextUtils.join(" ", parts);
    }

    /** A tree row: guide lines, then ▾/▸ when it folds something (its own tap target). */
    private LinearLayout treeLine(String guide, Row fold) {
        LinearLayout row = row();
        row.setPadding(dp(14), dp(2), dp(4), dp(2));
        row.setMinimumHeight(dp(26));
        if (!guide.isEmpty()) {
            TextView lines = text(guide, 12, GpuiChrome.withAlpha(chrome.onSurfaceVariant, 0x80));
            lines.setTypeface(Typeface.MONOSPACE);
            lines.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            row.addView(lines);
        }
        if (fold != null && fold.fold != null && !fold.kids.isEmpty()) {
            boolean folded = folded(fold);
            TextView toggle = text(folded ? "▸" : "▾", 12, chrome.onSurfaceVariant);
            toggle.setGravity(Gravity.CENTER);
            toggle.setMinWidth(dp(18));
            toggle.setMinHeight(dp(24));
            toggle.setContentDescription((folded ? "Show " : "Hide ") + fold.kids.size() + " rows under it");
            toggle.setOnClickListener(v -> toggleFold(fold.fold, fold.foldedByDefault));
            row.addView(toggle);
        }
        return row;
    }

    private TextView single(TextView view) {
        view.setSingleLine(true);
        view.setEllipsize(TextUtils.TruncateAt.END);
        return view;
    }

    private TextView tag(String value, int color) {
        TextView meta = text(value, 10, color);
        meta.setPadding(dp(6), 0, 0, 0);
        return meta;
    }

    /** A plain row: glyph and words; tappable when there is an action. */
    private View actionLine(String guide, Row fold, String glyph, String words, int color, Runnable action) {
        LinearLayout line = treeLine(guide, fold);
        TextView mark = text(glyph, 11, color);
        mark.setPadding(0, 0, dp(6), 0);
        mark.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        line.addView(mark);
        line.addView(single(text(words, 12, color)), new LinearLayout.LayoutParams(0, -2, 1));
        line.setContentDescription(words);
        if (action != null) line.setOnClickListener(v -> action.run());
        else line.setClickable(false);
        return line;
    }

    /** "Spaces", "Other agents": the whole row folds. */
    private View groupLine(String guide, Row fold, String words, String count) {
        LinearLayout line = treeLine(guide, fold);
        line.addView(single(text(words, 11, chrome.primary)), new LinearLayout.LayoutParams(0, -2, 1));
        line.addView(tag(count, chrome.onSurfaceVariant));
        line.setContentDescription(words + ", " + count + (folded(fold) ? ", folded" : ""));
        line.setOnClickListener(v -> toggleFold(fold.fold, fold.foldedByDefault));
        return line;
    }

    /** A small bordered button on the machine row. */
    private TextView chip(String words, boolean enabled, Runnable action) {
        TextView chip = text(words, 11, enabled ? chrome.primary : chrome.onSurfaceVariant);
        chip.setPadding(dp(8), dp(4), dp(8), dp(4));
        GradientDrawable border = new GradientDrawable();
        border.setCornerRadius(dp(10));
        border.setStroke(Math.max(1, dp(1)), enabled ? chrome.primary : chrome.outline);
        chip.setBackground(border);
        chip.setEnabled(enabled);
        if (enabled) chip.setOnClickListener(v -> action.run());
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-2, -2);
        params.setMarginStart(dp(6));
        chip.setLayoutParams(params);
        return chip;
    }

    private static boolean hasRole(Agent agent) {
        return "deacon".equals(agent.role) || "pm".equals(agent.role) || "worker".equals(agent.role);
    }

    /**
     * One agent in the tree: guide, fold toggle when it has children, status
     * dot, title and, for the skill's agents or any agent that said something,
     * its status in words, for how long, and its latest message.
     */
    private View agentLine(String guide, Agent agent, String tag, Row fold) {
        return agentLine(guide, agent, tag, fold, null);
    }

    /**
     * {@code heading}, when given (a project's name), leads the row and the
     * agent's own title moves to the second line. An untitled terminal reads
     * as its harness and folder; its id only ever sits on the second line.
     */
    private View agentLine(String guide, Agent agent, String tag, Row fold, String heading) {
        LinearLayout row = treeLine(guide, fold);
        row.addView(dot(statusColor(agent.status)));
        LinearLayout lines = column();
        boolean lead = heading != null || "deacon".equals(agent.role) || "pm".equals(agent.role)
                || tag.startsWith("deacon") || tag.startsWith("pm");
        boolean untitled = agent.title.isEmpty() || RailTree.isTerminalId(agent.title);
        String shown = heading != null ? heading : FleetPanes.displayTitle(agent);
        TextView title = single(text(shown, lead ? 13 : 12, chrome.onSurface));
        if (lead) title.setTypeface(Typeface.DEFAULT_BOLD);
        lines.addView(title);
        List<String> second = new ArrayList<>();
        if (heading != null && !untitled) second.add(agent.title);
        String state = detail(agent);
        if (!state.isEmpty()) second.add(state);
        if (untitled) second.add(agent.id);
        String said = TextUtils.join(" · ", second);
        if (!said.isEmpty()) {
            TextView sub = single(text(said, 11, statusPriority(agent.status) <= 1 ? statusColor(agent.status)
                    : chrome.onSurfaceVariant));
            sub.setPadding(0, dp(1), 0, 0);
            lines.addView(sub);
        }
        row.addView(lines, new LinearLayout.LayoutParams(0, -2, 1));
        if (!tag.isEmpty()) row.addView(tag(tag, "deacon".equals(tag) ? chrome.primary : chrome.onSurfaceVariant));
        row.setContentDescription(shown + (heading != null && !untitled ? ", " + agent.title : "") + ", "
                + statusWord(agent.status, agent.waitKind)
                + (tag.isEmpty() ? "" : ", " + tag) + (agent.msg.isEmpty() ? "" : ", " + agent.msg));
        row.setOnClickListener(v -> openAgent(agent));
        row.setOnLongClickListener(v -> { agentActions(agent); return true; });
        return row;
    }

    private void agentActions(Agent agent) {
        ActionSheet sheet = new ActionSheet(this, chrome, breadcrumb(agent)).add("Open", () -> openAgent(agent));
        if (!agent.msg.isEmpty()) sheet.add("Copy message", () -> ActionSheet.copy(this, "Message", agent.msg));
        if (!agent.cwd.isEmpty()) sheet.add("Copy path", () -> ActionSheet.copy(this, "Path", agent.cwd));
        sheet.add("Copy terminal id", () -> ActionSheet.copy(this, "Terminal id", agent.id)).show();
    }

    /** An agent in a flat list (Needs you, Working, the picker): breadcrumb title, no tree. */
    private View agentRow(Agent agent, boolean unused) {
        LinearLayout row = row();
        row.setPadding(dp(4), dp(2), dp(4), dp(2));
        row.setMinimumHeight(dp(26));
        row.addView(dot(statusColor(agent.status)));
        LinearLayout lines = column();
        lines.addView(single(text(breadcrumb(agent), 12, chrome.onSurface)));
        String said = detail(agent);
        if (!said.isEmpty()) {
            lines.addView(single(text(said, 11, statusPriority(agent.status) <= 1 ? statusColor(agent.status)
                    : chrome.onSurfaceVariant)));
        }
        row.addView(lines, new LinearLayout.LayoutParams(0, -2, 1));
        String tag = roleTag(agent);
        if (!tag.isEmpty()) row.addView(tag(tag, chrome.onSurfaceVariant));
        row.setContentDescription(FleetPanes.displayTitle(agent) + ", " + statusWord(agent.status, agent.waitKind)
                + (tag.isEmpty() ? "" : ", " + tag) + (agent.msg.isEmpty() ? "" : ", " + agent.msg));
        row.setOnClickListener(v -> openAgent(agent));
        row.setOnLongClickListener(v -> { agentActions(agent); return true; });
        return row;
    }

    /** "deacon", "pm · billing", "worker", else the agent's harness. */
    private static String roleTag(Agent agent) {
        if ("pm".equals(agent.role)) return agent.project.isEmpty() ? "pm" : "pm · " + agent.project;
        return hasRole(agent) ? agent.role : agent.kind;
    }

    /** The second line: "needs you: question · 12m · Run the migration?". */
    private static String detail(Agent agent) {
        if (!hasRole(agent) && agent.msg.isEmpty() && statusPriority(agent.status) > 1) return "";
        List<String> parts = new ArrayList<>();
        parts.add(statusWord(agent.status, agent.waitKind));
        String since = age(agent.statusMs, System.currentTimeMillis());
        if (!since.isEmpty()) parts.add(since);
        if (!agent.msg.isEmpty()) parts.add(agent.msg);
        return TextUtils.join(" · ", parts);
    }

    // ── Fleet actions (explicit taps only; rendering never starts anything) ──

    /** Milliseconds left on this machine's Start Deacon guard, or 0. */
    private long deaconGuard(Machine machine) {
        try {
            long at = getSharedPreferences("home", MODE_PRIVATE).getLong("deacon_start:" + machine.host.id, 0);
            long left = at + DEACON_GUARD_MS - System.currentTimeMillis();
            return left > 0 && left <= DEACON_GUARD_MS ? left : 0;
        } catch (RuntimeException unreadable) {
            return 0;
        }
    }

    private void clearDeaconGuard(Machine machine) {
        try {
            getSharedPreferences("home", MODE_PRIVATE).edit().remove("deacon_start:" + machine.host.id).apply();
        } catch (RuntimeException ignored) {
            // The guard expires on its own.
        }
    }

    /**
     * Starts this machine's Deacon on its own core, never another's and never
     * locally. The core rechecks for one first and returns it instead of
     * launching a second; a guard blocks repeat taps for 90 s.
     */
    private void startDeacon(Machine machine) {
        long handle = machine.handle;
        if (machine.deaconBusy || deaconGuard(machine) > 0) return;
        if (handle <= 0 || !"ready".equals(machine.state)) {
            machine.deaconError = machine.host.name + " is offline";
            machine.ui++;
            render(true);
            return;
        }
        machine.deaconBusy = true;
        machine.deaconError = "";
        machine.ui++;
        try {
            getSharedPreferences("home", MODE_PRIVATE).edit()
                    .putLong("deacon_start:" + machine.host.id, System.currentTimeMillis()).apply();
        } catch (RuntimeException ignored) {
            // The in-memory flag still guards this session.
        }
        render(true);
        String action;
        try {
            action = new JSONObject().put("op", "deacon.start").put("name", machine.host.name).toString();
        } catch (org.json.JSONException impossible) {
            return;
        }
        worker.execute(() -> {
            String failure = null, terminal = "";
            boolean started = false;
            try {
                JSONObject result = new JSONObject(RemoteNative.sessionAction(handle, action));
                if (!result.optBoolean("queued", false)) failure = result.optString("error", "it did not answer");
                terminal = result.optString("terminal", "");
                started = result.optBoolean("started", false);
                if (!result.optString("layout_error", "").isEmpty()) {
                    failure = "the Deacon started, but " + result.optString("layout_error");
                }
            } catch (Exception | LinkageError error) {
                failure = "the computer did not answer";
            }
            final String reason = failure, address = terminal;
            final boolean fresh = started;
            main.post(() -> {
                if (destroyed) return;
                if (address.isEmpty()) {
                    machine.deaconBusy = false;
                    clearDeaconGuard(machine);
                    machine.deaconError = reason == null ? "it did not answer" : reason;
                    machine.ui++;
                    render(true);
                    return;
                }
                if (reason != null) machine.deaconError = reason;
                if (fresh) {
                    // Launched: "Starting…" until claude is seen running there (checkDeaconStart).
                    machine.deaconPending = address;
                    machine.deaconPendingAt = System.currentTimeMillis();
                    machine.ui++;
                    render(true);
                    return;
                }
                // An existing Deacon: open it; a kept shell gets a recovery message, not "found".
                machine.deaconBusy = false;
                FleetTree.Member found = null;
                for (Agent agent : members(machine)) if (agent.id.equals(address)) found = agent;
                if (found != null && !FleetTree.running(found)) {
                    machine.deaconError = notRunning(machine);
                } else if (reason == null) {
                    machine.deaconError = "";
                }
                machine.ui++;
                render(true);
                if (machine.handle == handle) openTerminal(machine, address, "Deacon");
            });
        });
    }

    /** How long a new Deacon has to show claude running before it counts as failed. */
    private static final long DEACON_START_MS = 45_000;

    private static String notRunning(Machine machine) {
        return "The Deacon's terminal on " + machine.host.name + " is open, but claude isn't running in it."
                + " Start claude there, or check that it is installed";
    }

    /**
     * A launched Deacon is confirmed by what the core reports: a status or a
     * non-shell program in it. Gone, or still a plain shell after
     * DEACON_START_MS, it failed; its terminal keeps the role, so a retry
     * finds it instead of launching a second.
     */
    private void checkDeaconStart(Machine machine) {
        if (machine.deaconPending.isEmpty() || !"ready".equals(machine.state)) return;
        FleetTree.Member launched = null;
        for (Agent agent : members(machine)) if (agent.id.equals(machine.deaconPending)) launched = agent;
        // Not in the list yet right after launch is not "gone": give the view a few polls.
        long elapsed = System.currentTimeMillis() - machine.deaconPendingAt;
        if (launched == null && elapsed < 5_000) return;
        FleetTree.StartHealth health = FleetTree.startHealth(launched, elapsed, DEACON_START_MS);
        if (health == FleetTree.StartHealth.WAITING) return;
        String address = machine.deaconPending;
        machine.deaconPending = "";
        machine.deaconBusy = false;
        if (health == FleetTree.StartHealth.RUNNING) {
            machine.deaconError = "";
            note = "Started the Deacon on " + machine.host.name;
            clearDeaconGuard(machine);
        } else {
            machine.deaconError = launched == null
                    ? "The Deacon on " + machine.host.name + " stopped right away. Check that claude is installed"
                            + " and signed in there"
                    : "The Deacon's terminal opened on " + machine.host.name + ", but claude didn't start in it."
                            + " Check that claude is installed there";
            clearDeaconGuard(machine);
        }
        machine.ui++;
        render(true);
        if (launched != null) openTerminal(machine, address, "Deacon");
    }

    /**
     * New project: one complete message into this machine's Deacon chat, then
     * the chat itself. The Deacon asks for the goal and folder and sets up
     * the project manager; nothing here starts one.
     */
    private void newProject(Machine machine, Agent deacon) {
        long handle = machine.handle;
        if (machine.projectBusy) return;
        if (handle <= 0 || !"ready".equals(machine.state)) {
            machine.projectError = machine.host.name + " is offline";
            machine.ui++;
            render(true);
            return;
        }
        sendToDeacon(machine, deacon, FleetTree.newProjectPrompt(machine.host.name));
    }

    /** A project with no manager: ask that machine's Deacon to set one up. */
    private void askForManager(Machine machine, FleetTree.Project project) {
        FleetTree.Member found = FleetTree.canonicalDeacon(members(machine));
        if (found == null) {
            note = "Start the Deacon on " + machine.host.name + " first; it sets up project managers.";
            render(true);
            return;
        }
        Agent target = (Agent) found;
        new ActionSheet(this, chrome, project.name + " has no project manager")
                .add("Ask the Deacon to set one up", () -> sendToDeacon(machine, target,
                        "The " + project.name.replaceAll("\\p{Cntrl}", "") + " project on "
                                + machine.host.name + " has no project manager. Please set one up for it."))
                .show();
    }

    private void sendToDeacon(Machine machine, Agent deacon, String text) {
        if (machine.projectBusy) return; // one send per tap
        long handle = machine.handle;
        String action;
        try {
            action = new JSONObject().put("op", "pane.send").put("terminal", deacon.id).put("text", text).toString();
        } catch (org.json.JSONException impossible) {
            return;
        }
        machine.projectBusy = true;
        machine.projectError = "";
        machine.ui++;
        render(true);
        worker.execute(() -> {
            String failure = null;
            try {
                JSONObject result = new JSONObject(RemoteNative.sessionAction(handle, action));
                if (!result.optBoolean("queued", false)) failure = result.optString("error", "it did not answer");
            } catch (Exception | LinkageError error) {
                failure = "the computer did not answer";
            }
            final String reason = failure;
            main.post(() -> {
                if (destroyed) return;
                machine.projectBusy = false;
                machine.projectError = reason == null ? "" : reason;
                machine.ui++;
                render(true);
                if (reason == null) openAgent(deacon);
            });
        });
    }

    /**
     * An unopened worktree: work there goes through the Deacon (it starts a
     * labelled worker); a bare shell is only the secondary choice.
     */
    private void closedWorktreeActions(Machine machine, FleetTree.GitWorktree wt, String name) {
        // The git cache can lag: a terminal already there (a slow first open) is opened instead.
        FleetTree.Member there = FleetTree.memberAt(members(machine), wt.path);
        if (there != null) { openAgent((Agent) there); return; }
        FleetTree.Member deacon = FleetTree.canonicalDeacon(members(machine));
        ActionSheet sheet = new ActionSheet(this, chrome, name);
        if (deacon != null) {
            sheet.add("Ask the Deacon to start work here", () -> sendToDeacon(machine, (Agent) deacon,
                    "I want to work in the " + name.replaceAll("\\p{Cntrl}", "") + " worktree ("
                            + wt.path.replaceAll("\\p{Cntrl}", "") + ") on " + machine.host.name
                            + ". Help me set up a worker there."));
        } else {
            sheet.add("Start the Deacon first", () -> startDeacon(machine));
        }
        sheet.add("Open a plain shell there", () -> openWorktree(machine, wt.path));
        sheet.add("Copy path", () -> ActionSheet.copy(this, "Path", wt.path));
        sheet.show();
    }

    /** A closed worktree: a shell space there (no agent), then that shell. */
    private void openWorktree(Machine machine, String path) {
        long handle = machine.handle;
        if (handle <= 0 || !"ready".equals(machine.state) || !machine.opening.add(path)) return;
        machine.ui++;
        render(true);
        String action;
        try {
            action = new JSONObject().put("op", "space.open").put("folder", path).toString();
        } catch (org.json.JSONException impossible) {
            return;
        }
        worker.execute(() -> {
            String failure = null, terminal = "";
            try {
                JSONObject result = new JSONObject(RemoteNative.sessionAction(handle, action));
                if (!result.optBoolean("queued", false)) failure = result.optString("error", "it did not answer");
                terminal = result.optString("terminal", "");
            } catch (Exception | LinkageError error) {
                failure = "the computer did not answer";
            }
            final String reason = failure, address = terminal;
            main.post(() -> {
                if (destroyed) return;
                machine.opening.remove(path);
                machine.ui++;
                note = reason == null ? null : "Could not open " + FleetTree.lastSegment(path) + ": " + reason;
                render(true);
                if (!address.isEmpty() && machine.handle == handle) openTerminal(machine, address, FleetTree.lastSegment(path));
            });
        });
    }

    /** Opens a terminal the core just made or found, by address on this machine. */
    private void openTerminal(Machine machine, String address, String title) {
        Agent agent = new Agent();
        agent.machine = machine;
        agent.id = address;
        agent.title = title;
        openAgent(agent);
    }

    /**
     * Asks the machine's git about the folders its spaces show (never other
     * repos): at most every GIT_MS, or GIT_NEW_MS after a new folder appears.
     */
    private void maybeLookGit(Machine machine, List<Agent> all, boolean ready, boolean folded) {
        long handle = machine.handle;
        if (handle <= 0) return;
        Set<String> paths = FleetPanes.gitPaths(all);
        if (paths.isEmpty()) return;
        long now = System.currentTimeMillis();
        boolean fresh = !machine.gitAsked.containsAll(paths);
        if (!FleetTree.gitDue(ready, folded, machine.gitBusy, fresh, now, machine.gitAt, GIT_MS, GIT_NEW_MS)) return;
        machine.gitBusy = true;
        machine.gitAt = now;
        String action;
        try {
            action = new JSONObject().put("op", "git.look").put("paths", new JSONArray(paths)).toString();
        } catch (org.json.JSONException impossible) {
            machine.gitBusy = false;
            return;
        }
        worker.execute(() -> {
            FleetTree.GitLook look = null;
            try {
                JSONObject result = new JSONObject(RemoteNative.sessionAction(handle, action));
                if (result.optBoolean("queued", false)) {
                    look = FleetTree.parseGitLook(result.optString("text", ""), FleetTree.PER_REPO);
                    look.allTruncated = result.optBoolean("truncated", false);
                }
            } catch (Exception | LinkageError error) {
                look = null;
            }
            final FleetTree.GitLook answer = look;
            main.post(() -> {
                machine.gitBusy = false;
                if (destroyed || answer == null) return;
                machine.git = answer;
                machine.gitAsked = paths;
                FleetPanes.LOOKS.put(machine.host.id, answer);
                FleetPanes.LOOKED_AT.put(machine.host.id, System.currentTimeMillis());
                render(true);
            });
        });
    }

    /** machine › space › agent, without empty segments. */
    private static String breadcrumb(Agent agent) {
        List<String> parts = new ArrayList<>();
        for (String part : new String[] {agent.machine.host.name, agent.spaceLabel, FleetPanes.displayTitle(agent)}) {
            if (part != null && !part.trim().isEmpty()) parts.add(part.trim());
        }
        return TextUtils.join(" › ", parts);
    }

    /** What needs Wolf: agents waiting on him or failing, across every machine. */
    private void attention(LinearLayout into, List<Agent> all, boolean always) {
        List<Agent> needs = new ArrayList<>();
        for (Agent agent : all) if (statusPriority(agent.status) <= 1) needs.add(agent);
        if (needs.isEmpty() && !always) return;
        TextView heading = text("Needs you", 11, chrome.danger);
        heading.setPadding(dp(4), dp(6), dp(4), dp(2));
        into.addView(heading);
        if (needs.isEmpty()) {
            TextView none = text("Nothing is waiting on you.", 12, chrome.onSurfaceVariant);
            none.setPadding(dp(4), dp(2), dp(4), dp(4));
            into.addView(none);
        }
        for (Agent agent : needs) into.addView(agentRow(agent, false));
        into.addView(divider(), new LinearLayout.LayoutParams(-1, 1));
    }

    /** The unfolded right pane until an agent is picked: the whole fleet at a glance. */
    private void fleetOverview(List<Agent> all, int[] counts) {
        TextView heading = text("Fleet", 15, chrome.onSurface);
        heading.setTypeface(Typeface.DEFAULT_BOLD);
        heading.setPadding(dp(4), dp(6), dp(4), dp(2));
        fleet.addView(heading);
        fleet.addView(text(summaryText(counts) + " · " + counts[3] + " idle · " + counts[4] + " done",
                12, chrome.onSurfaceVariant));
        attention(fleet, all, true);
        List<Agent> working = new ArrayList<>();
        for (Agent agent : all) if ("working".equals(agent.status)) working.add(agent);
        TextView workHeading = text("Working", 11, chrome.working);
        workHeading.setPadding(dp(4), dp(8), dp(4), dp(2));
        fleet.addView(workHeading);
        if (working.isEmpty()) fleet.addView(text("No agent is working right now.", 12, chrome.onSurfaceVariant));
        for (Agent agent : working) fleet.addView(agentRow(agent, false));
        TextView machinesHeading = text("Machines", 11, chrome.primary);
        machinesHeading.setPadding(dp(4), dp(10), dp(4), dp(2));
        fleet.addView(machinesHeading);
        for (Machine machine : machines.values()) {
            LinearLayout row = row();
            row.setPadding(dp(4), dp(3), dp(4), dp(3));
            row.addView(dot(machineColor(machine)));
            int count = 0;
            for (List<Agent> list : spaces(machine).values()) count += list.size();
            String detail = "ready".equals(machine.state) ? count + (count == 1 ? " terminal" : " terminals")
                    : machine.status.isEmpty() ? machine.state : machine.status;
            TextView line = text(machine.host.name + " · " + detail, 12, chrome.onSurfaceVariant);
            line.setSingleLine(true);
            line.setEllipsize(TextUtils.TruncateAt.END);
            row.addView(line, new LinearLayout.LayoutParams(0, -2, 1));
            fleet.addView(row);
        }
    }

    // ── Actions ─────────────────────────────────────────────────────────────

    /** Hands this machine's session to the terminal, attached to the agent. */
    private void openAgent(Agent agent) {
        Machine machine = agent.machine;
        long handle = machine.handle;
        if (GpuiLaunch.terminalOpen()) {
            // A terminal is already open under this Home: it shows the agent.
            startActivity(new Intent(this, GpuiTerminalActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT | Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    .putExtra(GpuiTerminalActivity.EXTRA_FOCUS_HOST, machine.host.id)
                    .putExtra(GpuiTerminalActivity.EXTRA_FOCUS_PANE, agent.id));
            return;
        }
        if (opening || handle <= 0 || !"ready".equals(machine.state)) {
            note = machine.host.name + " is not connected yet";
            render(true);
            return;
        }
        opening = true;
        worker.execute(() -> {
            String failure = null;
            try {
                JSONObject response = new JSONObject(RemoteNative.focus(handle, agent.id));
                if (!Boolean.TRUE.equals(response.opt("queued"))) failure = response.optString("error", "Not available");
            } catch (Exception | LinkageError error) {
                failure = "Not available";
            }
            final String reason = failure;
            main.post(() -> {
                if (destroyed || machine.handle != handle) { opening = false; return; }
                if (reason != null) {
                    opening = false;
                    note = agent.title + ": " + reason;
                    render(true);
                    return;
                }
                try {
                    RemoteHostsActivity.rememberLastSession(this, machine.host, agent.id);
                    GpuiLaunch.launchRemote(this, machine.host, handle, agent.id, FleetPanes.displayTitle(agent));
                    // The terminal owns the session now; coming back reconnects in the background.
                    machine.handle = 0;
                    machine.state = "connecting";
                } catch (RuntimeException | LinkageError failed) {
                    opening = false;
                    note = "The terminal could not be opened";
                    render(true);
                }
            });
        });
    }

    /** Fuzzy search over machines, spaces and agents, keyboard up at once. */
    private void openPicker() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this, chrome.dialogTheme());
        LinearLayout body = column();
        body.setPadding(dp(8), dp(8), dp(8), 0);
        EditText query = new EditText(builder.getContext());
        query.setHint("Machine, space, agent, label, folder or status");
        query.setSingleLine(true);
        query.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        query.setImeOptions(EditorInfo.IME_ACTION_GO | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        query.setTextSize(15);
        body.addView(query, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout results = column();
        ScrollView list = scroll(results);
        body.addView(list, new LinearLayout.LayoutParams(-1, dp(360)));
        AlertDialog dialog = builder.setView(body).create();
        List<Agent> all = allAgents();
        List<Agent> shown = new ArrayList<>();
        Runnable refresh = () -> {
            shown.clear();
            shown.addAll(rank(all, query.getText().toString()));
            results.removeAllViews();
            for (Agent agent : shown) {
                View row = agentRow(agent, false);
                row.setOnClickListener(v -> { dialog.dismiss(); openAgent(agent); });
                results.addView(row);
            }
            if (shown.isEmpty()) results.addView(text("No match", 13, chrome.onSurfaceVariant));
        };
        query.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            public void onTextChanged(CharSequence s, int a, int b, int c) { }
            public void afterTextChanged(Editable s) { refresh.run(); }
        });
        query.setOnEditorActionListener((view, action, event) -> {
            if (shown.isEmpty()) return true;
            dialog.dismiss();
            openAgent(shown.get(0));
            return true;
        });
        refresh.run();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE
                    | WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
            if (!ProvisionedSettings.capturable(this)) {
                dialog.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
            }
        }
        dialog.show();
        query.requestFocus();
    }

    /**
     * fzf-like ranking: every query character in order, rewarding runs and
     * word starts, then shorter context; ties keep waiting/working agents first.
     */
    private static List<Agent> rank(List<Agent> agents, String query) {
        String q = query.trim().toLowerCase(Locale.ROOT);
        List<Object[]> scored = new ArrayList<>();
        for (Agent agent : agents) {
            String hay = (agent.machine.host.name + " " + agent.spaceLabel + " " + agent.title + " "
                    + agent.kind + " " + agent.role + " " + agent.project + " " + agent.status + " "
                    + agent.cwd + " " + agent.msg).toLowerCase(Locale.ROOT);
            int score = q.isEmpty() ? 0 : fuzzy(hay, q);
            if (score < 0) continue;
            scored.add(new Object[] {agent, score, hay.length()});
        }
        Collections.sort(scored, (a, b) -> {
            int s = Integer.compare((Integer) b[1], (Integer) a[1]);
            if (s != 0) return s;
            int p = Integer.compare(statusPriority(((Agent) a[0]).status), statusPriority(((Agent) b[0]).status));
            if (p != 0) return p;
            return Integer.compare((Integer) a[2], (Integer) b[2]);
        });
        List<Agent> out = new ArrayList<>();
        for (int i = 0; i < Math.min(scored.size(), 60); i++) out.add((Agent) scored.get(i)[0]);
        return out;
    }

    private static int fuzzy(String hay, String query) {
        int score = 0, at = 0, run = 0;
        for (int i = 0; i < query.length(); i++) {
            char c = query.charAt(i);
            if (c == ' ') { run = 0; continue; }
            int found = hay.indexOf(c, at);
            if (found < 0) return -1;
            score += 1;
            if (found == at && i > 0) { run++; score += 2 + run; } else run = 0;
            if (found == 0 || !Character.isLetterOrDigit(hay.charAt(found - 1))) score += 3;
            at = found + 1;
        }
        return score;
    }

    // ── Small views ─────────────────────────────────────────────────────────

    private int machineColor(Machine machine) {
        switch (machine.state) {
            case "ready": return chrome.success;
            case "connecting": return chrome.warning;
            default: return chrome.danger;
        }
    }

    private int statusColor(String status) {
        if ("error".equals(status)) return chrome.danger;
        return chrome.status(status);
    }

    private TextView dot(int color) {
        TextView dot = text("●", 10, color);
        dot.setPadding(0, 0, dp(6), 0);
        dot.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        return dot;
    }

    private LinearLayout row() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setFocusable(true);
        row.setBackground(ripple());
        return row;
    }

    private LinearLayout column() {
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        return column;
    }

    private ScrollView scroll(View child) {
        ScrollView scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setFillViewport(true);
        scroll.addView(child, new ScrollView.LayoutParams(-1, -2));
        return scroll;
    }

    private View divider() {
        View line = new View(this);
        line.setBackgroundColor(chrome.outline);
        return line;
    }

    /** Selection feedback only: base01-like, never a filled panel. */
    private android.graphics.drawable.Drawable ripple() {
        GradientDrawable mask = new GradientDrawable();
        mask.setColor(0xffffffff);
        mask.setCornerRadius(dp(6));
        return new android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(chrome.selected), null, mask);
    }

    private TextView text(String value, float size, int color) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setIncludeFontPadding(false);
        return view;
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
