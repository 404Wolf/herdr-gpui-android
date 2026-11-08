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
 * status. Folded it is one dense list; unfolded the tree is a persistent left
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
    }

    /** One agent (terminal) as the tree and the picker show it. */
    private static final class Agent {
        Machine machine;
        String id, title, status, space, spaceLabel, parent, role, project, kind, cwd;
        int depth;
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newFixedThreadPool(4);
    private final Map<String, Machine> machines = new LinkedHashMap<>();
    private final Set<String> collapsed = new HashSet<>();
    private RemoteHostStore store;
    private RemoteIdentityStore identityStore;
    private GpuiChrome chrome;
    private LinearLayout tree, fleet;
    private TextView summary;
    private String signature = "";
    private boolean resumed, destroyed, opening;
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
                if (destroyed || machines.get(host.id) != machine) {
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
        Map<String, String> labels = new HashMap<>();
        for (int i = 0; workspaces != null && i < workspaces.length(); i++) {
            JSONObject space = workspaces.optJSONObject(i);
            if (space != null) labels.put(space.optString("id"), space.optString("label", space.optString("id")));
        }
        Map<String, List<Agent>> flat = new LinkedHashMap<>();
        for (int i = 0; panes != null && i < Math.min(panes.length(), 1024); i++) {
            JSONObject pane = panes.optJSONObject(i);
            if (pane == null) continue;
            // A core also lists its federated machines' terminals ("server/t4m").
            // The home connects to each machine itself, so it shows only each
            // machine's own terminals, once, under that machine.
            String id = pane.optString("id");
            if (id.contains("/")) continue;
            Agent agent = new Agent();
            agent.machine = machine;
            agent.id = id;
            agent.title = GpuiTerminalProtocol.title(pane.optString("title", agent.id));
            agent.status = pane.optString("status", "");
            agent.space = pane.optString("workspace_id", "loose");
            agent.spaceLabel = labels.containsKey(agent.space) ? labels.get(agent.space) : agent.space;
            agent.parent = pane.optString("parent", "");
            agent.role = pane.optString("role", "");
            agent.project = pane.optString("project", "");
            agent.kind = pane.optString("kind", "");
            if ("none".equals(agent.kind)) agent.kind = "";
            agent.cwd = pane.optString("cwd", "");
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
                roots.computeIfAbsent(agent.space, key -> new ArrayList<>()).add(agent);
            }
        }
        Set<String> seen = new HashSet<>();
        for (Map.Entry<String, List<Agent>> entry : roots.entrySet()) {
            List<Agent> ordered = new ArrayList<>();
            sortAgents(entry.getValue());
            for (Agent root : entry.getValue()) walk(root, 0, children, ordered, seen);
            result.put(entry.getKey(), ordered);
        }
        // Parent cycles: anything unvisited still shows, at the top of its space.
        for (Agent agent : agents) {
            if (seen.add(agent.id)) {
                agent.depth = 0;
                result.computeIfAbsent(agent.space, key -> new ArrayList<>()).add(agent);
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

    private static void walk(Agent agent, int depth, Map<String, List<Agent>> children,
            List<Agent> out, Set<String> seen) {
        if (!seen.add(agent.id) || depth > 8) return;
        agent.depth = depth;
        out.add(agent);
        List<Agent> kids = children.get(agent.id);
        if (kids == null) return;
        sortAgents(kids);
        for (Agent kid : kids) walk(kid, depth + 1, children, out, seen);
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
        root.addView(topBar(), new LinearLayout.LayoutParams(-1, dp(40)));
        root.addView(divider(), new LinearLayout.LayoutParams(-1, 1));
        tree = column();
        tree.setPadding(dp(6), dp(2), dp(6), dp(16));
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
        setContentView(root);
        // The insets controller belongs to the decor view, which exists only now.
        if (Build.VERSION.SDK_INT >= 30 && getWindow().getInsetsController() != null) {
            int light = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                    | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
            getWindow().getInsetsController().setSystemBarsAppearance(chrome.light ? light : 0, light);
        }
        render(true);
    }

    private View topBar() {
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
        bar.addView(iconButton("⋯", "Hosts, device key and settings",
                v -> startActivity(new Intent(this, RemoteHostsActivity.class).putExtra("skip_reopen", true))));
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
        StringBuilder key = new StringBuilder(String.valueOf(collapsed)).append(note);
        for (Machine machine : machines.values()) {
            key.append(machine.host.id).append(machine.state).append(machine.status);
            if (machine.view != null) key.append(machine.view.optJSONArray("panes"))
                    .append(machine.view.optJSONArray("workspaces"));
        }
        String next = key.toString();
        if (!force && next.equals(signature)) return;
        signature = next;
        List<Agent> all = allAgents();
        int[] counts = new int[6];
        for (Agent agent : all) counts[statusPriority(agent.status)]++;
        summary.setText(summaryText(counts));
        tree.removeAllViews();
        if (note != null) {
            TextView line = text(note, 12, chrome.onSurfaceVariant);
            line.setPadding(dp(6), dp(4), dp(6), dp(4));
            tree.addView(line);
        }
        if (machines.isEmpty()) {
            TextView empty = text("No hosts yet. Add one under ⋯ Hosts, or let the device's "
                    + "provisioned hosts file add them.", 13, chrome.onSurfaceVariant);
            empty.setPadding(dp(8), dp(16), dp(8), dp(16));
            tree.addView(empty);
        }
        // Folded, what needs Wolf sits on top of the tree; unfolded it is the Fleet pane.
        if (!wide()) attention(tree, all, false);
        List<Machine> byActivity = new ArrayList<>(machines.values());
        Map<Machine, Integer> busy = new HashMap<>();
        for (Machine machine : byActivity) {
            List<Agent> agents = new ArrayList<>();
            for (List<Agent> list : spaces(machine).values()) agents.addAll(list);
            busy.put(machine, "ready".equals(machine.state) ? activity(agents) : 9000);
        }
        Collections.sort(byActivity, (a, b) -> Integer.compare(busy.get(a), busy.get(b)));
        for (Machine machine : byActivity) renderMachine(machine);
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

    private void renderMachine(Machine machine) {
        Map<String, List<Agent>> spaces = spaces(machine);
        int agents = 0;
        for (List<Agent> list : spaces.values()) agents += list.size();
        boolean closed = collapsed.contains(machine.host.id);
        LinearLayout row = row();
        row.setPadding(dp(4), dp(7), dp(4), dp(3));
        row.addView(dot(machineColor(machine)));
        TextView name = text(machine.host.name, 14, chrome.onSurface);
        name.setTypeface(Typeface.DEFAULT_BOLD);
        row.addView(name);
        int waiting = 0, working = 0;
        for (List<Agent> list : spaces.values()) {
            for (Agent agent : list) {
                if (statusPriority(agent.status) <= 1) waiting++;
                if ("working".equals(agent.status)) working++;
            }
        }
        String detail = "ready".equals(machine.state)
                ? agents + (agents == 1 ? " terminal" : " terminals")
                        + (working > 0 ? " · " + working + " working" : "")
                        + (waiting > 0 ? " · " + waiting + " waiting" : "")
                : machine.status;
        TextView meta = text("  " + (closed ? "▸ " : "") + detail, 11, chrome.onSurfaceVariant);
        meta.setSingleLine(true);
        meta.setEllipsize(TextUtils.TruncateAt.END);
        row.addView(meta, new LinearLayout.LayoutParams(0, -2, 1));
        row.setOnClickListener(v -> {
            if (!collapsed.remove(machine.host.id)) collapsed.add(machine.host.id);
            if ("closed".equals(machine.state) && machine.handle <= 0 && !machine.connecting) connect(machine);
            render(true);
        });
        row.setContentDescription(machine.host.name + ", " + detail);
        tree.addView(row);
        if (closed) return;
        for (Map.Entry<String, List<Agent>> space : spaces.entrySet()) {
            List<Agent> list = space.getValue();
            if (list.isEmpty()) continue;
            String spaceKey = machine.host.id + "/" + space.getKey();
            boolean spaceClosed = collapsed.contains(spaceKey);
            LinearLayout header = row();
            header.setPadding(dp(14), dp(4), dp(4), dp(1));
            TextView label = text((spaceClosed ? "▸ " : "") + list.get(0).spaceLabel, 12, chrome.primary);
            label.setSingleLine(true);
            label.setEllipsize(TextUtils.TruncateAt.END);
            header.addView(label, new LinearLayout.LayoutParams(0, -2, 1));
            header.addView(text(statusCounts(list), 10, chrome.onSurfaceVariant));
            header.setOnClickListener(v -> {
                if (!collapsed.remove(spaceKey)) collapsed.add(spaceKey);
                render(true);
            });
            tree.addView(header);
            if (spaceClosed) continue;
            for (Agent agent : list) tree.addView(agentRow(agent, true));
        }
    }

    private String statusCounts(List<Agent> agents) {
        int working = 0, waiting = 0;
        for (Agent agent : agents) {
            if ("working".equals(agent.status)) working++;
            if ("blocked".equals(agent.status) || "error".equals(agent.status)) waiting++;
        }
        List<String> parts = new ArrayList<>();
        if (waiting > 0) parts.add(waiting + "!");
        if (working > 0) parts.add(working + "▶");
        parts.add(String.valueOf(agents.size()));
        return TextUtils.join(" ", parts);
    }

    private View agentRow(Agent agent, boolean inTree) {
        LinearLayout row = row();
        int indent = inTree ? 20 + 12 * agent.depth : 4;
        row.setPadding(dp(indent), dp(2), dp(4), dp(2));
        row.setMinimumHeight(dp(26));
        row.addView(dot(statusColor(agent.status)));
        TextView title = text(inTree ? agent.title : agent.machine.host.name + " › " + agent.spaceLabel
                + " › " + agent.title, 12, chrome.onSurface);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        row.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        String tag = "deacon".equals(agent.role) || "pm".equals(agent.role) || "worker".equals(agent.role)
                ? agent.role : agent.kind;
        if (!tag.isEmpty()) {
            TextView meta = text(tag, 10, chrome.onSurfaceVariant);
            meta.setPadding(dp(6), 0, 0, 0);
            row.addView(meta);
        }
        row.setContentDescription(agent.title + ", " + (agent.status.isEmpty() ? "no status" : agent.status)
                + (tag.isEmpty() ? "" : ", " + tag));
        row.setOnClickListener(v -> openAgent(agent));
        return row;
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
                    GpuiLaunch.launchRemote(this, machine.host, handle, agent.id, agent.title);
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
                    + agent.cwd).toLowerCase(Locale.ROOT);
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
