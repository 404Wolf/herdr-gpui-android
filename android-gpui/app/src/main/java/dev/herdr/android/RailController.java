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
import dev.herdr.android.GpuiTerminalActivity.Connection;
import dev.herdr.android.GpuiTerminalActivity.HostSample;

/**
 * The terminal's rail (factory audit, PR 11): the machine chooser, the
 * Pinned section, the Deacon, projects and agents, folds, and their actions,
 * moved out of GpuiTerminalActivity as it was (behaviour unchanged). It
 * reads and drives the activity through {@link #a}.
 */
final class RailController {
    final GpuiTerminalActivity a;

    RailController(GpuiTerminalActivity a) {
        this.a = a;
    }

    private int dp(float value) {
        return a.dp(value);
    }

    /** Reused rail rows, so a sampler refresh never replaces the view under a finger. */
    private final java.util.Map<String, RailHostRow> railHostRows = new java.util.HashMap<>();
    final java.util.Map<String, RailWorkspaceRow> railWorkspaces = new java.util.HashMap<>();
    final java.util.Map<String, RailPaneRow> railPanes = new java.util.HashMap<>();
    private final java.util.Map<String, TextView> railNotes = new java.util.HashMap<>();
    private final java.util.Map<String, View> railRules = new java.util.HashMap<>();

    void refreshSidebar(boolean force) {
        a.refreshTabStrip();
        if (!a.sidebarOpen || a.sidebarRows == null) return;
        Connection current;
        synchronized (a.stateLock) { current = a.connection; }
        StringBuilder signature = new StringBuilder();
        for (SavedRemoteHost host : a.savedHosts) {
            // Hidden machines (the picker names them) don't redraw the rail.
            if (!a.railShows(host.id)) continue;
            HostSample sample = a.hostSamples.get(host.id);
            signature.append(host.id).append(':');
            if (host.id.equals(a.currentHostId)) {
                signature.append("live:").append(current == null ? 0 : current.chromeRevision)
                        .append(current != null && current.ready && !current.isClosed() ? "+" : "-");
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
        signature.append(a.currentHostId);
        java.util.List<String> savedIds = new java.util.ArrayList<>();
        for (SavedRemoteHost host : a.savedHosts) savedIds.add(host.id);
        if (pinsRail != null) signature.append(pinsRail.signature(savedIds));
        if (!force && signature.toString().equals(a.sidebarSignature)) return;
        a.sidebarSignature = signature.toString();
        if (a.savedHosts.isEmpty()) {
            pruneRailRows(java.util.Collections.emptySet());
            a.sidebarRows.removeAllViews();
            a.sidebarRows.addView(railText("No saved hosts yet", 13, a.chrome.onSurfaceVariant));
            return;
        }
        // Rows are reused, never rebuilt: the sampler refreshes every three
        // seconds and replacing a view under a finger cancels its tap.
        java.util.List<View> order = new java.util.ArrayList<>();
        java.util.Set<String> keep = new java.util.HashSet<>();
        // The machine a terminal is on leads the rail; the others follow in
        // saved order, so the top of the rail always names where you are.
        java.util.List<SavedRemoteHost> hosts = new java.util.ArrayList<>(a.savedHosts);
        for (int h = 0; h < hosts.size(); h++) {
            if (hosts.get(h).id.equals(a.currentHostId)) { hosts.add(0, hosts.remove(h)); break; }
        }
        // One machine at a time, as the mockup's machine picker has it: the
        // rail is the machine on screen; its header (name ▾) picks another.
        boolean picker = a.currentHostId != null && current != null && hosts.size() > 1
                && hosts.get(0).id.equals(a.currentHostId);
        if (picker) hosts = hosts.subList(0, 1);
        for (int h = 0; h < hosts.size(); h++) {
            SavedRemoteHost host = hosts.get(h);
            boolean live = host.id.equals(a.currentHostId);
            HostSample sample = a.hostSamples.get(host.id);
            JSONArray workspaces = live && current != null ? current.workspaces
                    : sample == null ? null : sample.workspaces;
            JSONArray panes = live && current != null ? current.panes
                    : sample == null ? null : sample.panes;
            JSONArray tabs = live && current != null ? current.tabs
                    : sample == null ? null : sample.tabs;
            JSONArray[] last = live || panes != null ? null : a.lastLists(host.id);
            if (last != null) {
                // Not reachable yet: its last lists, read only (item 77).
                workspaces = last[0];
                tabs = last[1];
                panes = last[2];
            }
            addHostRows(host, live, sample, workspaces, tabs, panes, current, order, keep);
            if (h + 1 < hosts.size()) order.add(railRule(host.id, keep));
        }
        // Pinned agents, on any machine, right under the machine chooser at the top (Wolf, 9zz.10):
        // the one-tap way to another machine's agent (factory-9zz.2).
        if (pinsRail != null) {
            RailHostRow first = hosts.isEmpty() ? null : railHostRows.get("host:" + hosts.get(0).id);
            int at = first == null ? -1 : order.indexOf(first.row);
            order.add(at + 1, pinsRail.section(savedIds));
        }
        pruneRailRows(keep);
        commitRailOrder(order);
        // The window on screen (opened from Home, a notification or the rail)
        // is always visible in the rail, selected.
        a.revealSelectedRow(false);
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
                note = new TextView(a);
                note.setTextSize(11);
                note.setTextColor(a.chrome.onSurfaceVariant);
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
        // The same tree as Home (FleetTree via FleetPanes): a machine's
        // Deacon, each project once under its PM, every terminal in the row of
        // its own checkout, unopened worktrees once per repo, leftovers kept.
        java.util.List<FleetTree.Member> members = FleetPanes.members(workspaces, tabs, panes, FleetTree.Member::new);
        railSpaceOf.clear();
        for (FleetTree.Member m : members) railSpaceOf.put(m.id, m.space);
        if (live) {
            // With the factory's issue/task/helper labels, so agents join their beads.
            railMembers = BeadsModel.terms(workspaces, tabs, panes);
        }
        if (live && current != null) maybeRailGitLook(host, current, members);
        FleetTree.Fleet fleet = FleetTree.build(members, FleetPanes.LOOKS.get(host.id));
        FleetTree.PmTree tree = FleetTree.pmTree(fleet);
        String selectedPane = live && current != null ? current.paneId : "";
        // Reveal an explicit pick even on cold launch. Merely restoring the
        // last pane leaves its PM at the saved fold state.
        FleetTree.Project focusedProject = FleetTree.projectOf(fleet, selectedPane);
        java.util.List<String> reveal = new java.util.ArrayList<>();
        java.util.List<String> revealRepos = new java.util.ArrayList<>();
        for (FleetTree.PmRow pm : tree.allProjects) {
            if (pm.project != focusedProject) continue;
            String key = pm.workerFoldKey(host.id, selectedPane);
            if (key != null) reveal.add(key);
            break;
        }
        for (FleetTree.RepoRow repo : tree.repos) {
            boolean selected = false;
            for (FleetTree.PmRow pm : repo.projects) if (pm.project == focusedProject) selected = true;
            for (FleetTree.PmRow pm : repo.idle) {
                if (pm.project == focusedProject) {
                    selected = true;
                    reveal.add(railFold(railKey(host, "idle/" + repo.key), FleetTree.Fold.IDLE));
                }
            }
            for (FleetTree.SpaceNode node : repo.loose) {
                if (containsMember(node.members, selectedPane)) {
                    selected = true;
                    reveal.add(railCheckoutKey(host, node));
                }
            }
            if (selected) revealRepos.add(railKey(host, "repo/" + repo.key));
        }
        for (FleetTree.RepoRow group : tree.groups) {
            for (FleetTree.PmRow pm : group.projects) {
                if (pm.project == focusedProject) revealRepos.add(railKey(host, "group/" + group.key));
            }
        }
        for (FleetTree.PmRow pm : tree.outside) {
            if (pm.project == focusedProject) reveal.add(railFold(railKey(host, "outside"), FleetTree.Fold.OUTSIDE));
        }
        for (FleetTree.SpaceNode node : tree.outsideLoose) {
            if (containsMember(node.members, selectedPane)) {
                reveal.add(railFold(railKey(host, "outside"), FleetTree.Fold.OUTSIDE));
                reveal.add(railCheckoutKey(host, node));
            }
        }
        if (containsMember(tree.otherMembers, selectedPane)) {
            reveal.add(railFold(railKey(host, "other-agents"), FleetTree.Fold.OTHERS));
        }
        // The selection can arrive before its pane metadata, especially on a host switch.
        if (containsMember(members, selectedPane)) {
            String focus = host.id + "/" + selectedPane;
            boolean picked = focus.equals(railPickedFocus);
            if (picked) railPickedFocus = "";
            if (railFolds().focusSeen(focus, reveal, revealRepos, picked)) saveRailFolds();
        }
        updateRailFleetButtons(header, host, live, current, fleet);
        FleetTree.Member deacon = fleet.deacon;
        // Repositories contain projects and checkouts, with catalogs at repo level.
        if (deacon != null) {
            String hKey = railFold(railKey(host, "helpers"), FleetTree.Fold.DEACON);
            boolean hFolded = railFolded(hKey) && !containsMember(tree.helpers, selectedPane);
            String said = FleetPanes.displayTitle(deacon);
            // Its own name under "Deacon", never its terminal id (factory-jmc).
            String sub = FleetTree.genericName(said) || AgentNames.UNNAMED.equals(said) ? "" : said;
            railAgent(host, live, current, deacon, "Deacon", sub, FleetTree.railAgentPadding(0, false),
                    tree.helpers.isEmpty() ? null : hKey, hFolded, 0, hFolded && tree.deaconBlocked(), selectedPane, order, keep);
            DeaconFlow.State flowed = deaconFlow.of(host.id);
            if (deacon.id.equals(flowed.pending)) {
                railNote(host, "deacon-state", DeaconFlow.starting(host.name), a.chrome.onSurfaceVariant, 1, null, order, keep);
            } else if (!flowed.error.isEmpty() || !FleetTree.running(deacon)) {
                String why = flowed.error.isEmpty() ? DeaconFlow.notRunning(host.name) : flowed.error;
                railNote(host, "deacon-state", why + ". Tap to open it.",
                        a.chrome.danger, 1, () -> openRailPane(host, live, current, deacon.id), order, keep);
            }
            if (!hFolded) {
                for (FleetTree.Member helper : tree.helpers) {
                    railAgent(host, live, current, helper, null, "", FleetTree.railAgentPadding(1, true),
                            null, false, 0, false, selectedPane, order, keep);
                }
            }
            // New project is the computer row's +; while it is asking, say so here.
            if (deaconFlow.of(host.id).asking) {
                railNote(host, "new-project", DeaconFlow.asking(), a.chrome.onSurfaceVariant, 1, null, order, keep);
            } else if (!deaconFlow.of(host.id).askError.isEmpty()) {
                railNote(host, "new-project", DeaconFlow.couldNotAsk(deaconFlow.of(host.id).askError), a.chrome.danger, 1,
                        live && current != null ? () -> railNewProject(host, live, current, deacon) : null, order, keep);
            }
        } else {
            DeaconFlow.State flowed = deaconFlow.of(host.id);
            String why = !live ? "No Deacon. Switch to " + host.name + " to start one."
                    : flowed.busy() ? DeaconFlow.starting(host.name)
                    : flowed.error.isEmpty() ? "◆ " + DeaconFlow.none(true, host.name)
                    : DeaconFlow.couldNotStart(flowed.error);
            railNote(host, "deacon-state", why, flowed.error.isEmpty() ? a.chrome.onSurfaceVariant : a.chrome.danger, 0,
                    live && !flowed.busy() ? () -> railStartDeacon(host, current) : null,
                    order, keep);
        }
        // PMs labelled herdr.group=<name>, in their named groups above the repositories (factory-r8f.6).
        for (FleetTree.RepoRow group : tree.groups) {
            String key = railFold(railKey(host, "group/" + group.key), FleetTree.Fold.GROUP);
            boolean folded = railFolded(key);
            railHeader(host, live, current, "named:" + group.key, group.name, group.label(folded), key, folded, 0, "", order, keep);
            if (folded) continue;
            for (FleetTree.PmRow pm : group.projects) railPm(host, live, current, pm, 1, selectedPane, order, keep);
        }
        for (FleetTree.RepoRow repo : tree.repos) {
            String key = railFold(railKey(host, "repo/" + repo.key), FleetTree.Fold.REPO);
            boolean folded = railFolded(key);
            railHeader(host, live, current, "repo:" + repo.key, repo.name, repo.label(folded), key, folded, 0, "", order, keep);
            if (folded) continue;
            for (FleetTree.PmRow pm : repo.projects) railPm(host, live, current, pm, 1, selectedPane, order, keep);
            if (!repo.idle.isEmpty()) {
                String idleKey = railFold(railKey(host, "idle/" + repo.key), FleetTree.Fold.IDLE);
                boolean idleFolded = railFolded(idleKey);
                railHeader(host, live, current, "idle:" + repo.key, repo.idle.size() + " idle", "", idleKey,
                        idleFolded, 1, "", order, keep);
                if (!idleFolded) {
                    for (FleetTree.PmRow pm : repo.idle) railPm(host, live, current, pm, 2, selectedPane, order, keep);
                }
            }
            for (FleetTree.SpaceNode node : repo.loose) railCheckout(host, live, current, node, 1, selectedPane, order, keep);
            railCatalogRows(host, live, current, repo, 1, order, keep);
        }
        if (tree.outsideCount() > 0) {
            String key = railFold(railKey(host, "outside"), FleetTree.Fold.OUTSIDE);
            boolean folded = railFolded(key);
            railHeader(host, live, current, "group:outside", "Outside git", "" + tree.outsideCount(), key,
                    folded, 0, "", order, keep);
            if (!folded) {
                for (FleetTree.PmRow pm : tree.outside) railPm(host, live, current, pm, 1, selectedPane, order, keep);
                for (FleetTree.SpaceNode node : tree.outsideLoose) railCheckout(host, live, current, node, 1, selectedPane, order, keep);
            }
        }
        if (!tree.others.isEmpty()) {
            String key = railFold(railKey(host, "other-agents"), FleetTree.Fold.OTHERS);
            boolean folded = railFolded(key);
            String meta = (tree.othersBlocked() ? "! " : "") + tree.otherMembers.size();
            railHeader(host, live, current, "group:other-agents", "Other agents", meta, key,
                    folded, 0, "", order, keep);
            if (!folded) {
                for (FleetTree.AgentNode node : tree.others) {
                    railAgentTree(host, live, current, node, FleetTree.railAgentPadding(1, false), selectedPane, order, keep);
                }
            }
        }
    }

    private static String railCheckoutKey(SavedRemoteHost host, FleetTree.SpaceNode node) {
        return railFold(railKey(host, "checkout/" + node.key), FleetTree.Fold.PROJECT);
    }

    private void railCheckout(SavedRemoteHost host, boolean live, Connection current, FleetTree.SpaceNode node,
            int depth, String selectedPane, java.util.List<View> order, java.util.Set<String> keep) {
        FleetTree.Member target = FleetTree.checkoutTarget(node);
        String key = node.members.size() > 1 ? railCheckoutKey(host, node) : null;
        boolean folded = key == null || railFolded(key);
        RailWorkspaceRow row = railHeader(host, live, current, "checkout:" + node.key, FleetTree.checkoutLabel(node), "", key,
                folded, depth, target == null ? "" : target.id, order, keep);
        selectRailHeader(row, containsMember(node.members, selectedPane));
        if (target == null && node.key.startsWith("/")) {
            FleetTree.GitWorktree worktree = new FleetTree.GitWorktree(node.key, node.branch, false, false);
            row.action = () -> openRailWorktree(host, live ? current : null, worktree);
        }
        if (!folded) {
            for (FleetTree.AgentNode agent : node.agents) {
                railAgentTree(host, live, current, agent, FleetTree.railAgentPadding(depth + 1, false), selectedPane, order, keep);
            }
        }
    }

    /** One PM row (its project's name, the PM's checkout below), then its workers when unfolded. */
    private void railPm(SavedRemoteHost host, boolean live, Connection current,
            FleetTree.PmRow pm, int depth, String selectedPane, java.util.List<View> order, java.util.Set<String> keep) {
        String key = pm.foldKey(host.id);
        // A PM with no workers has nothing to fold, so it is never folded: its Beads row shows.
        boolean folded = pm.workerRows > 0 && railFolds().folded(key);
        String foldKey = pm.workerRows == 0 ? null : key;
        if (!pm.labelledPm()) {
            // Live and idle projects without a labelled PM keep a dimmed, direct terminal target.
            FleetTree.Member target = FleetTree.projectTarget(pm);
            String meta = pm.countsLabel();
            RailWorkspaceRow header = railHeader(host, live, current, "proj:" + pm.name(), pm.name() + " · no PM", meta,
                    foldKey, folded, depth, target == null ? "" : target.id, order, keep);
            header.row.setAlpha(0.65f);
            header.label.setTextColor(a.chrome.onSurfaceVariant);
            selectRailHeader(header, pm.contains(selectedPane));
            if (live && !pm.catalogs.isEmpty()) header.worktrees = () -> showRailCatalogs(host, current, pm.catalogs);
        } else {
            String sub = pm.where;
            RailPaneRow row = railAgent(host, live, current, pm.pm, pm.name(), sub, FleetTree.railAgentPadding(depth, false),
                    foldKey, folded, pm.workerRows, folded && pm.blocked(), selectedPane, order, keep);
            if (row != null) {
                row.state.setText(pm.countsLabel());
                row.state.setTextColor(pm.blocked() ? a.chrome.danger : a.chrome.onSurfaceVariant);
                row.row.setContentDescription("Open " + pm.name() + ", " + pm.countsLabel()
                        + (pm.blocked() ? ", needs attention" : ""));
            }
            if (row != null && live && !pm.catalogs.isEmpty()) {
                row.worktrees = () -> showRailCatalogs(host, current, pm.catalogs);
            }
        }
        if (folded) return;
        int workerPadding = FleetTree.railAgentPadding(depth + 1, pm.labelledPm());
        for (FleetTree.AgentNode node : pm.workers) {
            railAgentTree(host, live, current, node, workerPadding, selectedPane, order, keep);
        }
        // No beads here (Wolf): the rail is project and agent navigation; beads belong to Tasks.
    }

    private void selectRailHeader(RailWorkspaceRow holder, boolean selected) {
        if (holder.selected == null || holder.selected != selected) {
            holder.selected = selected;
            holder.row.setSelected(selected);
            holder.row.setBackground(railBackground(selected));
        }
    }

    /** Visible catalogs live on the repository, once per canonical git repo. */
    private void railCatalogRows(SavedRemoteHost host, boolean live, Connection current,
            FleetTree.RepoRow repo, int depth, java.util.List<View> order, java.util.Set<String> keep) {
        for (FleetTree.Project project : repo.catalogs) {
            RailWorkspaceRow row = railHeader(host, live, current, "catalog:" + project.repo,
                    repo.catalogName(project), FleetTree.unopenedLabel(project), null, false, depth, "", order, keep);
            row.action = () -> showRailWorktrees(host, live ? current : null, project, 0);
            row.row.setContentDescription("Show " + FleetTree.unopenedLabel(project) + " in " + row.label.getText());
        }
    }

    /** A row's "Worktrees…": straight to the one repo's catalog, else a choice of repo first. */
    private void showRailCatalogs(SavedRemoteHost host, Connection current,
            java.util.List<FleetTree.Project> catalogs) {
        if (catalogs.size() == 1) { showRailWorktrees(host, current, catalogs.get(0), 0); return; }
        ActionSheet sheet = new ActionSheet(a, a.chrome, "Worktrees with no space");
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
        ActionSheet sheet = new ActionSheet(a, a.chrome, project.name + " · " + total
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
    private String railPickedFocus = "";

    void requestRailReveal(String hostId, String paneId) {
        railPickedFocus = hostId == null || paneId == null || paneId.isEmpty() ? "" : hostId + "/" + paneId;
    }

    /**
     * The rail opening: the agent on screen's path (its group or repository,
     * its PM) unfolds and its row scrolls into view, as on a switch
     * (factory-9zz.10); every other fold keeps Wolf's state.
     */
    void revealActiveOnOpen() {
        Connection current;
        synchronized (a.stateLock) { current = a.connection; }
        if (current != null) requestRailReveal(a.currentHostId, current.paneId);
    }

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

    private static boolean containsMember(java.util.List<FleetTree.Member> list, String id) {
        for (FleetTree.Member m : list) if (m.id.equals(id)) return true;
        return false;
    }

    /** Rail fold keys: per machine, apart from the old repo/space keys. */
    private static String railKey(SavedRemoteHost host, String what) {
        return host.id + "/fleet/" + what;
    }

    private void railAgentTree(SavedRemoteHost host, boolean live, Connection current, FleetTree.AgentNode node,
            int padding, String selectedPane, java.util.List<View> order, java.util.Set<String> keep) {
        railAgent(host, live, current, node.member, null, node.where, padding, null, false, 0, false, selectedPane, order, keep);
        for (FleetTree.AgentNode kid : node.children) {
            railAgentTree(host, live, current, kid, padding + FleetTree.RAIL_DEPTH_DP, selectedPane, order, keep);
        }
    }

    /**
     * One agent row: tapping focuses that exact terminal. {@code lead} names a
     * Deacon or PM row (its title, in medium weight, with a chevron slot that
     * folds {@code foldKey}); {@code sub} is the second line, usually the
     * checkout; {@code bubbled} marks blocked work folded under it. Returns
     * null when a terminal already has a row in a pass.
     */
    private RailPaneRow railAgent(SavedRemoteHost host, boolean live, Connection current, FleetTree.Member m,
            String lead, String sub, int padding, String foldKey, boolean folded, int workerRows, boolean bubbled, String selectedPane,
            java.util.List<View> order, java.util.Set<String> keep) {
        String paneKey = "pane:" + host.id + "/" + m.id;
        if (!keep.add(paneKey)) return null; // each terminal once
        RailPaneRow row = railPanes.get(paneKey);
        if (row == null) {
            row = createRailPaneRow();
            railPanes.put(paneKey, row);
        }
        // Its name (AgentNames), never its terminal id; "no task label" is drawn dim.
        String title = lead != null ? lead : FleetPanes.displayTitle(m);
        String changes = lead == null ? FleetTree.changes(FleetPanes.LOOKS.get(host.id), m.cwd) : "";
        String shownSub = sub + (changes.isEmpty() ? "" : (sub.isEmpty() ? "" : " · ") + changes);
        row.foldKey = foldKey;
        row.worktrees = null;
        // A lead row's fold column (RailDisclosure): its own button, empty when nothing folds.
        String foldSays = FleetTree.foldSays(lead, "deacon".equals(m.role) && "Deacon".equals(lead),
                workerRows, folded, foldKey);
        row.mark.setVisibility(lead == null ? View.GONE : View.VISIBLE);
        row.mark.bind(foldSays != null, folded, foldSays);
        row.row.setMinimumHeight(dp(lead == null ? FleetTree.AGENT_ROW_DP : FleetTree.LEAD_ROW_DP));
        if (!shownSub.contentEquals(row.sub.getText())) row.sub.setText(shownSub);
        row.sub.setVisibility(shownSub.isEmpty() ? View.GONE : View.VISIBLE);
        if (row.lead != (lead != null)) {
            row.lead = lead != null;
            row.title.setTypeface(railMono(row.lead));
        }
        String status = "question".equals(m.waitKind) && !"blocked".equals(m.status) && !"error".equals(m.status)
                ? "question" : m.status.isEmpty() ? "unknown" : m.status;
        updateRailPaneRow(row, host, live, current, m.id, title, status,
                m.id.equals(selectedPane), bubbled);
        int pad = dp(padding);
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
        row.meta.setTextColor(meta.contains("!") ? a.chrome.danger : a.chrome.onSurfaceVariant);
        row.mark.bind(foldKey != null, folded, foldKey == null ? null : (folded ? "Show " : "Hide ") + "what is under " + label);
        int pad = dp(2 + 12 * depth);
        if (row.row.getPaddingLeft() != pad) {
            row.row.setPadding(pad, dp(2), dp(4), dp(2));
            boolean top = depth == 0;
            row.label.setTextSize(top ? 13 : 12);
            row.label.setTypeface(railMono(top));
            row.label.setTextColor(top ? a.chrome.onSurface : a.chrome.primary);
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
        int startDp = 10 + 12 * depth;
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
        note.setPadding(dp(startDp), dp(1), dp(6), dp(1));
        note.setMinHeight(dp(action == null ? 28 : FleetTree.AGENT_ROW_DP));
        note.setOnClickListener(action == null ? null : v -> action.run());
        note.setClickable(action != null);
        order.add(note);
    }

    // ── Rail Fleet actions (explicit taps only) ─────────────────────────

    /** Machines with a New project (or other Deacon message) in flight: one send per tap. */
    /** A worktree open already queued by a tap, scoped to its computer. */
    private final java.util.Set<String> railWorktreeOpening = new java.util.HashSet<>();
    /** Each terminal's space, for the rail's header long-press menus. */
    private final java.util.Map<String, String> railSpaceOf = new java.util.HashMap<>();
    /** The pin button and the rail's Pinned section (PinsRail). */
    PinsRail pinsRail;

    /**
     * A pinned agent's row was tapped. P2 (t3jk) replaces a with the switch
     * path for any machine; until then a pin on the machine on screen focuses
     * its terminal, and another machine's pin says it opens there.
     */
    void openPin(Pins.Pin pin) {
        Connection now;
        synchronized (a.stateLock) { now = a.connection; }
        if (now != null && pin.key.machine.equals(now.machine)) {
            // Only the very terminal that was pinned: listed now with its creation time, never a reused address.
            Pins.Verify verdict = pin.ended() ? Pins.Verify.ENDED
                    : Pins.verify(pin.key, now.remoteBoot, now.fingerprint, now.panes);
            if (verdict != Pins.Verify.GO) {
                a.note(PinsRail.refusal(verdict, pin));
                return;
            }
            a.focusRemotePane(now, pin.key.address);
            Pins.shared(a).opened(pin.key);
            a.closeRailDrawer();
            return;
        }
        // Another machine's pin (Wolf: macro's deacon pin "never loads"; it only said "open it there").
        // Its profile is a hint; the machine that profile is bound to decides, and the terminal is
        // checked again on arrival (pinArriving), never another one in its place.
        if (a.switchingHost) {
            // Kept, not dropped: it opens (and is verified) once the switch settles.
            a.hostPicks.keep(new HostPick(pin.profile, pin.key.address, true, pin));
            a.note(HostPick.note(a.hostName(a.switchingTo), pin.machineName));
            a.closeRailDrawer();
            return;
        }
        if (now != null && now.machine == null && pin.profile.equals(a.currentHostId) && !now.isClosed()) {
            // This profile's machine hasn't said who it is yet: the pin opens when it does (bindMachine).
            pinAwaitingHello = pin;
            a.note("Waiting for " + pin.machineName + " to answer…");
            a.closeRailDrawer();
            return;
        }
        SavedRemoteHost host = pinHost(pin);
        if (host == null) {
            a.note((pin.label.isEmpty() ? "That pin" : pin.label) + " is on " + pin.machineName
                    + ", which a phone has no confirmed profile for");
            return;
        }
        a.switchHost(host, pin.key.address);
        // Only a switch that started waits for its list (one already under way keeps its own target).
        pinArriving = a.switchingHost && host.id.equals(a.switchingTo) ? pin : null;
        a.closeRailDrawer();
    }

    /** A pin opened while a profile's machine had not said who it is; opened when it does. */
    volatile Pins.Pin pinAwaitingHello;

    /** A pin opened on another machine, to verify against that machine's first own list. */
    private volatile Pins.Pin pinArriving;

    /** The saved profile bound to the pin's machine (the pin's own profile first), or null. */
    private SavedRemoteHost pinHost(Pins.Pin pin) {
        if (a.bindings == null) return null;
        SavedRemoteHost found = null;
        for (SavedRemoteHost h : a.savedHosts) {
            if (!pin.key.machine.equals(a.bindings.machine(h.id, h.connectionRevision))) continue;
            if (h.id.equals(pin.profile)) return h;
            if (found == null) found = h;
        }
        return found;
    }

    /** Poll thread, under stateLock: the first own list of the machine a pin was opened on. */
    void checkPinArrival(Connection current) {
        Pins.Pin pin = pinArriving;
        if (pin == null || current.machine == null || !pin.key.machine.equals(current.machine)) return;
        pinArriving = null;
        Pins.Verify verdict = Pins.verify(pin.key, current.remoteBoot, current.fingerprint, current.panes);
        a.main.post(() -> {
            if (a.destroyed) return;
            if (verdict == Pins.Verify.GO) Pins.shared(a).opened(pin.key);
            else a.note(PinsRail.refusal(verdict, pin));
        });
    }

    /** The live machine's members as the rail last read them, with their names and labels (AgentNames). */
    java.util.List<? extends FleetTree.Member> railMembers = java.util.Collections.emptyList();
    /** Start Deacon and New project, shared with Home (DeaconFlow, audit PR 10). */
    private final DeaconFlow deaconFlow = DeaconFlow.shared();
    private final java.util.Set<String> railGitBusy = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final java.util.Map<String, java.util.Set<String>> railGitAsked = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * Another machine's row: with a live mirror its id is current, so it is
     * opened directly; from a cached list it is read-only until the machine
     * answers (RailArrival): the switch lands as it would, and the terminal
     * opens only if that machine's own first list still has it.
     */
    private void switchToRailPane(SavedRemoteHost host, String paneId) {
        if (host == null) return;
        HostSample sample = a.hostSamples.get(host.id);
        boolean mirrored = sample != null && sample.connected && sample.panes != null;
        if (mirrored || paneId == null || paneId.isEmpty()) { a.switchHost(host, paneId); return; }
        JSONArray[] last = a.lastLists(host.id);
        JSONArray panes = last == null ? null : last[2];
        railArriving = new RailArrival(host.id, paneId, RailArrival.createdOf(panes, paneId), cachedName(panes, paneId));
        a.switchHost(host, null);
    }

    /** A cached row tapped before its machine answered: checked against that machine's first own list. */
    private volatile RailArrival railArriving;

    private static String cachedName(JSONArray panes, String paneId) {
        for (FleetTree.Member m : FleetPanes.members(null, null, panes, FleetTree.Member::new)) {
            if (m.id.equals(paneId)) return AgentNames.unnamed(m) ? "" : FleetPanes.displayTitle(m);
        }
        return "";
    }

    void checkRailArrival(Connection current) {
        RailArrival arrival = railArriving;
        if (arrival == null || current.hostId == null || !current.hostId.equals(arrival.hostId)) return;
        railArriving = null;
        RailArrival.Verdict verdict = arrival.check(current.panes);
        String machine = current.hostName;
        a.main.post(() -> {
            if (a.destroyed) return;
            if (verdict == RailArrival.Verdict.GO) {
                Connection now;
                synchronized (a.stateLock) { now = a.connection; }
                if (now != null && arrival.hostId.equals(now.hostId)) a.focusRemotePane(now, arrival.paneId);
            } else {
                a.note(arrival.refusal(machine));
            }
        });
    }

    private void openRailPane(SavedRemoteHost host, boolean live, Connection current, String paneId) {
        if (live && current != null) a.focusRemotePane(current, paneId);
        else switchToRailPane(host, paneId);
        a.closeRailDrawer();
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
        header.state.setTextColor(blocked > 0 ? a.chrome.danger : header.live ? a.chrome.onSurface : a.chrome.onSurfaceVariant);
        FleetTree.Member deacon = fleet.deacon;
        boolean busy = deaconFlow.of(host.id).busy();
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
        boolean asking = deaconFlow.of(host.id).asking;
        boolean canAsk = !asking && (deacon != null || live && !busy);
        header.plus.setEnabled(canAsk);
        header.plus.setAlpha(canAsk ? 1f : 0.4f);
        header.plus.setContentDescription(deacon != null ? "New project on " + host.name + ": ask its Deacon"
                : "New project on " + host.name + ": start its Deacon first");
        header.plus.setOnClickListener(v -> {
            if (deaconFlow.of(host.id).asking) return;
            if (deacon != null) railNewProject(host, live, current, deacon);
            else if (live && !busy) railStartDeacon(host, current);
        });
    }

    /** A Fleet header's menu: only its own terminal and details, never the shared space's edits. */
    private void showFleetHeaderActions(RailWorkspaceRow holder) {
        String label = holder.label.getText().toString();
        ActionSheet sheet = new ActionSheet(a, a.chrome, label);
        String pane = holder.firstPane;
        if (pane != null && !pane.isEmpty()) {
            sheet.add("Open", () -> {
                if (holder.live && holder.connection != null) a.focusRemotePane(holder.connection, pane);
                else switchToRailPane(holder.host, pane);
                a.closeRailDrawer();
            });
            String cwd = holder.live ? paneCwd(holder.connection, pane) : "";
            if (!cwd.isEmpty()) sheet.add("Copy path", () -> ActionSheet.copy(a, "Path", cwd));
        }
        Runnable worktrees = holder.worktrees;
        if (worktrees != null) sheet.add("Worktrees…", worktrees);
        sheet.add("Copy name", () -> ActionSheet.copy(a, "Name", label));
        sheet.show();
    }

    /** Runs one session action off the main thread; ok gets the reply on the main thread. */
    private void railAction(Connection target, String json, String label,
            java.util.function.Consumer<JSONObject> ok, Runnable failed) {
        a.actionWorker.execute(() -> {
            JSONObject result = null;
            String failure = null;
            try {
                result = new JSONObject(RemoteNative.sessionAction(target.remoteHandle, json));
                if (!result.optBoolean("queued", false)) failure = result.optString("error", "it did not answer");
            } catch (Exception | LinkageError error) {
                // By the failure's kind (ErrorWords), never its text.
                failure = ErrorWords.of(error, target.hostName);
            }
            final JSONObject reply = result;
            final String reason = failure;
            a.main.post(() -> {
                if (a.destroyed) return;
                if (reason == null) { ok.accept(reply); return; }
                if (failed != null) failed.run();
                a.note("Could not " + label + ": " + reason);
            });
        });
    }

    private void railStartDeacon(SavedRemoteHost host, Connection current) {
        if (current == null || !deaconFlow.begin(host.id)) return;
        refreshSidebar(true);
        String json;
        try {
            json = new JSONObject().put("op", "deacon.start").put("name", host.name).toString();
        } catch (JSONException impossible) { deaconFlow.failed(host.id, ""); return; }
        railAction(current, json, "start the Deacon", reply -> {
            String terminal = reply.optString("terminal", "");
            String layout = reply.optString("layout_error", "");
            DeaconFlow.Started outcome = deaconFlow.started(host.id, terminal, reply.optBoolean("started", false),
                    layout.isEmpty() ? null : "the Deacon started, but " + layout, System.currentTimeMillis());
            // Starting… until claude is seen running there (checkRailDeacon); an existing one opens.
            if (outcome == DeaconFlow.Started.EXISTING) a.focusRemotePane(current, terminal);
            refreshSidebar(true);
        }, () -> { deaconFlow.failed(host.id, "it did not answer"); refreshSidebar(true); });
    }

    /** Confirms a launched Deacon from the live view (DeaconFlow, as Home does); never relaunches. */
    private void checkRailDeacon(SavedRemoteHost host, Connection current, java.util.List<FleetTree.Member> members) {
        String pending = deaconFlow.pending(host.id);
        DeaconFlow.Check check = deaconFlow.check(host.id, host.name, members, System.currentTimeMillis());
        if (check == DeaconFlow.Check.NONE || check == DeaconFlow.Check.WAITING) return;
        if (check == DeaconFlow.Check.FAILED) a.note(deaconFlow.of(host.id).error + ".");
        boolean listed = false;
        for (FleetTree.Member m : members) if (m.id.equals(pending)) listed = true;
        if (listed) a.main.post(() -> a.focusRemotePane(current, pending));
    }

    private void railNewProject(SavedRemoteHost host, boolean live, Connection current, FleetTree.Member deacon) {
        if (!live || current == null) { switchToRailPane(host, deacon.id); a.closeRailDrawer(); return; }
        railSendToDeacon(host, current, deacon, FleetTree.newProjectPrompt(host.name));
    }

    private void railSendToDeacon(SavedRemoteHost host, Connection current, FleetTree.Member deacon, String text) {
        String json;
        try {
            json = new JSONObject().put("op", "pane.send").put("terminal", deacon.id).put("text", text).toString();
        } catch (JSONException impossible) { return; }
        // One send per tap: a second tap while a one is in flight does nothing.
        if (!deaconFlow.ask(host.id)) return;
        refreshSidebar(true);
        railAction(current, json, "reach the Deacon", reply -> {
            deaconFlow.asked(host.id, null);
            refreshSidebar(true);
            a.focusRemotePane(current, deacon.id);
            a.closeRailDrawer();
        }, () -> { deaconFlow.asked(host.id, "it did not answer"); refreshSidebar(true); });
    }

    /** One tap reuses a live terminal in the worktree, or opens a plain shell there. */
    private void openRailWorktree(SavedRemoteHost host, Connection current, FleetTree.GitWorktree wt) {
        synchronized (a.stateLock) {
            if (current == null || !current.ready || a.connection != current || !host.id.equals(a.currentHostId)) {
                a.note("Connect to " + host.name + " before opening a worktree.");
                return;
            }
        }
        java.util.List<FleetTree.Member> now = FleetPanes.members(current.workspaces, current.tabs, current.panes,
                FleetTree.Member::new);
        FleetTree.Member there = FleetTree.memberAt(now, wt.path);
        if (there != null) { a.focusRemotePane(current, there.id); a.closeRailDrawer(); return; }
        String key = host.id + "\n" + wt.path;
        if (!railWorktreeOpening.add(key)) {
            a.note("Opening " + FleetTree.lastSegment(wt.path) + "…");
            return;
        }
        String json;
        try {
            json = new JSONObject().put("op", "space.open").put("folder", wt.path).toString();
        } catch (JSONException impossible) { railWorktreeOpening.remove(key); return; }
        a.note("Opening " + FleetTree.lastSegment(wt.path) + "…");
        railAction(current, json, "open " + FleetTree.lastSegment(wt.path), reply -> {
            railWorktreeOpening.remove(key);
            synchronized (a.stateLock) { if (a.connection != current) return; }
            String terminal = reply.optString("terminal", "");
            if (terminal.isEmpty()) { a.note("The computer did not return a terminal for a worktree."); return; }
            a.focusRemotePane(current, terminal);
            a.closeRailDrawer();
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
        a.actionWorker.execute(() -> {
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
            a.main.post(() -> {
                railGitBusy.remove(host.id);
                if (a.destroyed || answer == null) return;
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
            if (view.getParent() != a.sidebarRows) {
                a.sidebarRows.addView(view, Math.min(i, a.sidebarRows.getChildCount()));
            } else if (a.sidebarRows.indexOfChild(view) != i) {
                a.sidebarRows.removeView(view);
                a.sidebarRows.addView(view, i);
            }
        }
        while (a.sidebarRows.getChildCount() > order.size()) {
            a.sidebarRows.removeViewAt(order.size());
        }
    }

    void pruneRailRows(java.util.Set<String> keep) {
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
            if (stale.getParent() == a.sidebarRows) a.sidebarRows.removeView(stale);
            iterator.remove();
        }
    }

    private RailHostRow createRailHostRow(SavedRemoteHost host) {
        RailHostRow holder = new RailHostRow();
        holder.row = new LinearLayout(a);
        holder.row.setOrientation(LinearLayout.HORIZONTAL);
        holder.row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        // Dense rail: Wolf scans many agents at once, so rows trade the 48 dp
        // touch guideline for more of them on screen.
        holder.row.setMinimumHeight(dp(36));
        holder.row.setPadding(dp(6), dp(4), dp(6), dp(4));
        holder.row.setSaveFromParentEnabled(false);
        holder.row.setBackground(railBackground(false));
        holder.dot = new View(a);
        holder.dot.setBackground(roundedDrawable(a.chrome.onSurfaceVariant, 4));
        LinearLayout.LayoutParams dotParams = new LinearLayout.LayoutParams(dp(8), dp(8));
        dotParams.setMarginEnd(dp(8));
        holder.row.addView(holder.dot, dotParams);
        holder.name = railText(host.name, 13, a.chrome.onSurface);
        holder.name.setTypeface(android.graphics.Typeface.create("sans-serif-medium",
                android.graphics.Typeface.NORMAL));
        LinearLayout.LayoutParams nameParams = new LinearLayout.LayoutParams(0, -2, 1);
        nameParams.leftMargin = dp(1);
        holder.row.addView(holder.name, nameParams);
        holder.state = railText("", 10, a.chrome.onSurfaceVariant);
        holder.state.setSingleLine(true);
        holder.state.setGravity(android.view.Gravity.END);
        LinearLayout.LayoutParams hostStateParams = new LinearLayout.LayoutParams(-2, -2);
        hostStateParams.leftMargin = dp(6);
        holder.row.addView(holder.state, hostStateParams);
        holder.deacon = railText("", 11, a.chrome.primary);
        holder.plus = railText("+", 15, a.chrome.primary);
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
            if (holder.live && a.savedHosts.size() > 1) {
                showMachinePicker();
                return;
            }
            a.switchHost(holder.host);
            a.closeRailDrawer();
        });
        holder.row.setOnLongClickListener(view -> {
            new ActionSheet(a, a.chrome, holder.host.name)
                    .add(holder.live ? "Reconnect" : "Connect", () -> {
                        if (holder.live) a.reconnectCurrentHost(); else a.switchHost(holder.host);
                    })
                    .add("Hosts and keys…", () -> a.startActivity(new android.content.Intent(a,
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
        Connection current;
        synchronized (a.stateLock) { current = a.connection; }
        // The machine on screen says what its link really is: a cached
        // stand-in or a dropped link is offline, never "live".
        String status = OfflineSwitch.railStatus(live, OfflineSwitch.link(current == null, current != null && current.offline,
                current != null && current.isClosed(), current != null && current.ready),
                sample != null, sample != null && sample.connected);
        if (!status.equals(holder.status)) {
            boolean pulse = holder.status != null;
            holder.status = status;
            // Colour alone cannot say it, so the dot is paired with its word.
            String word = OfflineSwitch.railWord(status, live);
            holder.state.setText(word);
            holder.hostState = word;
            holder.row.setContentDescription(a.hostDescription(holder));
            holder.dot.setBackground("connected".equals(status)
                    ? roundedDrawable(a.chrome.success, 4)
                    : ringDrawable("connecting".equals(status) ? a.chrome.working : a.chrome.onSurfaceVariant));
            if (pulse) pulseRailDot(holder.dot);
        }
        if (holder.live != live) {
            holder.live = live;
            // The connected host reads as the current destination, like a
            // selected navigation item, and says so to assistive technology.
            holder.row.setBackground(railBackground(live));
            holder.row.setSelected(live);
            // Secondary text is only guaranteed on the bare surface.
            holder.state.setTextColor(live ? a.chrome.onSurface : a.chrome.onSurfaceVariant);
        }
        String shown = live && a.savedHosts.size() > 1 ? host.name + "  ▾" : host.name;
        if (!shown.contentEquals(holder.name.getText())) holder.name.setText(shown);
        int count = workspaces == null ? 0 : workspaces.length();
        if (count != holder.counted) {
            // Workspaces are enumerated below; keep the count for screen readers.
            holder.counted = count;
            holder.row.setContentDescription(a.hostDescription(holder));
        }
    }

    /**
     * A space header: a full-width button (pressed ripple, chevron, window
     * count) that opens the space, its windows as the tabs on top.
     */
    private RailWorkspaceRow createRailWorkspaceRow() {
        RailWorkspaceRow holder = new RailWorkspaceRow();
        holder.row = new LinearLayout(a);
        holder.row.setOrientation(LinearLayout.HORIZONTAL);
        holder.row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        holder.row.setMinimumHeight(dp(FleetTree.LEAD_ROW_DP));
        holder.row.setSaveFromParentEnabled(false);
        holder.row.setFocusable(true);
        holder.row.setClickable(true);
        // The same fold control as a PM row's (RailDisclosure), even when the row opens a project terminal.
        holder.mark = new RailDisclosure(a, a.chrome);
        holder.mark.setOnClickListener(v -> {
            if (holder.foldKey != null) toggleRepo(holder.foldKey);
            else holder.row.performClick();
        });
        holder.mark.offerOn(holder.row);
        LinearLayout.LayoutParams markParams = new LinearLayout.LayoutParams(dp(FleetTree.PM_CHEVRON_DP), -1);
        holder.row.addView(holder.mark, markParams);
        // The drawn column is narrow (the chevron near its label); its touch reaches the edge and into the label.
        holder.mark.extendTouch(holder.row);
        holder.label = railText("", 12, a.chrome.primary);
        holder.label.setTypeface(railMono(false));
        holder.row.addView(holder.label, new LinearLayout.LayoutParams(0, -2, 1));
        holder.meta = railText("", 11, a.chrome.onSurfaceVariant);
        holder.meta.setTypeface(railMono(false));
        holder.meta.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        LinearLayout.LayoutParams metaParams = new LinearLayout.LayoutParams(-2, -2);
        metaParams.leftMargin = dp(6);
        holder.row.addView(holder.meta, metaParams);
        // No trailing "›": the fold chevron is the row's one arrow (review D5).
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
                    ? a.chrome.onSurface : a.chrome.primary);
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
                    ? a.chrome.onSurface : a.chrome.primary);
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
        ActionSheet sheet = new ActionSheet(a, a.chrome, "Machine");
        for (SavedRemoteHost host : a.savedHosts) {
            boolean live = host.id.equals(a.currentHostId);
            JSONArray panes, workspaces, tabs;
            Connection current;
            synchronized (a.stateLock) { current = a.connection; }
            boolean connected;
            if (live) {
                panes = current == null ? null : current.panes;
                workspaces = current == null ? null : current.workspaces;
                tabs = current == null ? null : current.tabs;
                connected = current != null && current.ready;
            } else {
                HostSample sample = a.hostSamples.get(host.id);
                panes = sample == null ? null : sample.panes;
                workspaces = sample == null ? null : sample.workspaces;
                tabs = sample == null ? null : sample.tabs;
                connected = sample != null && sample.connected;
                JSONArray[] last = panes != null ? null : a.lastLists(host.id);
                if (last != null) {
                    workspaces = last[0];
                    tabs = last[1];
                    panes = last[2];
                }
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
                    if (!live) a.switchHost(host);
                } else if (deacon != null) {
                    if (live && current != null) a.focusRemotePane(current, deacon.id);
                    else switchToRailPane(host, deacon.id);
                    a.closeRailDrawer();
                } else if (!live) {
                    // The rail then shows that computer with its Start Deacon row.
                    a.switchHost(host);
                }
            });
        }
        sheet.show();
    }

    private java.util.Set<String> railCollapsed;

    /** Collapsed repos, per machine and repo, kept across launches. */
    private java.util.Set<String> collapsedRepos() {
        if (railCollapsed == null) {
            railCollapsed = new java.util.HashSet<>(a.getSharedPreferences("terminal-display", android.content.Context.MODE_PRIVATE)
                    .getStringSet("rail_collapsed", java.util.Collections.emptySet()));
        }
        return railCollapsed;
    }

    private void toggleRepo(String key) {
        railFolds().toggle(key);
        saveRailFolds();
        refreshSidebar(true);
    }

    private void saveRailFolds() {
        a.getSharedPreferences("terminal-display", android.content.Context.MODE_PRIVATE).edit()
                .putStringSet("rail_collapsed", new java.util.HashSet<>(collapsedRepos())).apply();
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
            new ActionSheet(a, a.chrome, label)
                    .add("Open on " + holder.host.name, () -> switchToRailPane(holder.host, holder.firstPane))
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
            if (item != null && space.equals(item.optString("id"))) return GpuiTerminalActivity.chromeTitle(item.optString("label"), space);
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
        ActionSheet sheet = new ActionSheet(a, a.chrome, label)
                .add("Open", () -> { a.focusRemotePane(target, firstPane); a.closeRailDrawer(); })
                .add("New Claude here", () -> a.runEdit(target, "New Claude", "op", "space.new", "space", space, "kind", "claude"))
                .add("New Codex here", () -> a.runEdit(target, "New Codex", "op", "space.new", "space", space, "kind", "codex"))
                .add("New shell here", () -> a.runEdit(target, "New shell", "op", "space.new", "space", space, "kind", "shell"))
                .add("Rename…", () -> ActionSheet.prompt(a, a.chrome, "Rename space", label,
                        name -> a.runEdit(target, "Rename", "op", "space.rename", "space", space, "name", name)));
        if (at > 0) sheet.add("Move up", () -> a.runEdit(target, "Move", "op", "space.move", "space", space, "index", at - 1));
        if (at >= 0 && all != null && at < all.length() - 1) {
            sheet.add("Move down", () -> a.runEdit(target, "Move", "op", "space.move", "space", space, "index", at + 1));
        }
        String cwd = paneCwd(target, firstPane);
        if (!cwd.isEmpty()) sheet.add("Copy path", () -> ActionSheet.copy(a, "Path", cwd));
        sheet.show();
    }

    private void showPaneActions(RailPaneRow holder) {
        String title = holder.title.getText().toString();
        if (!holder.live) {
            new ActionSheet(a, a.chrome, title)
                    .add("Open on " + holder.host.name, () -> switchToRailPane(holder.host, holder.paneId))
                    .show();
            return;
        }
        Connection target = holder.connection;
        String pane = holder.paneId;
        String cwd = paneCwd(target, pane);
        ActionSheet sheet = new ActionSheet(a, a.chrome, title)
                .add("Open", () -> { a.focusRemotePane(target, pane); a.closeRailDrawer(); })
                .add("Interrupt (stop key)", () -> a.runEdit(target, "Interrupt", "op", "pane.interrupt", "terminal", pane));
        Runnable worktrees = holder.worktrees;
        if (worktrees != null) sheet.add("Worktrees…", worktrees);
        String space = railSpaceOf.getOrDefault(pane, "");
        String spaceLabel = space.isEmpty() ? "" : spaceName(target, space);
        if (!spaceLabel.isEmpty()) {
            sheet.add("Space “" + spaceLabel + "”…", () -> showSpaceSheet(target, space, spaceLabel, pane));
        }
        if (!cwd.isEmpty()) sheet.add("Copy path", () -> ActionSheet.copy(a, "Path", cwd));
        // No terminal id here (Wolf, factory-jmc): "i never need to see t3xz or the code since i do not use that".
        sheet.danger("Close terminal", () -> a.runEdit(target, "Close " + title, "op", "pane.kill", "terminal", pane))
                .show();
    }

    /**
     * Rail rows, boxy as the desktop sidebar: a square ripple for the press;
     * selected, a square fill with a 2 dp accent bar at the row's start.
     */
    private android.graphics.drawable.Drawable railBackground(boolean selected) {
        android.graphics.drawable.ColorDrawable mask = new android.graphics.drawable.ColorDrawable(0xffffffff);
        int pressed = selected ? a.chrome.outline : a.chrome.selected;
        android.graphics.drawable.LayerDrawable fill = null;
        if (selected) {
            fill = new android.graphics.drawable.LayerDrawable(new android.graphics.drawable.Drawable[] {
                    new android.graphics.drawable.ColorDrawable(a.chrome.selected),
                    new android.graphics.drawable.ColorDrawable(a.chrome.primary)});
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
        holder.row = new LinearLayout(a);
        holder.row.setOrientation(LinearLayout.HORIZONTAL);
        holder.row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        holder.row.setMinimumHeight(dp(28));
        holder.row.setPadding(dp(12), dp(2), dp(6), dp(2));
        holder.row.setSaveFromParentEnabled(false);
        holder.row.setBackground(railBackground(false));
        // A Deacon or PM row's chevron: it folds, the rest of the row focuses the agent.
        holder.mark = new RailDisclosure(a, a.chrome);
        holder.mark.setVisibility(View.GONE);
        // Folds only; an empty column (nothing to fold) is the row, so it focuses.
        holder.mark.setOnClickListener(v -> {
            if (holder.foldKey != null) toggleRepo(holder.foldKey);
            else holder.row.performClick();
        });
        holder.mark.offerOn(holder.row);
        LinearLayout.LayoutParams markParams = new LinearLayout.LayoutParams(dp(FleetTree.PM_CHEVRON_DP), -1);
        holder.row.addView(holder.mark, markParams);
        // The drawn column is narrow (the chevron near its label); its touch reaches the edge and into the label.
        holder.mark.extendTouch(holder.row);
        // Status as a small square mark (no ovals in the boxy rail).
        holder.dot = new View(a);
        holder.dot.setBackgroundColor(a.chrome.onSurfaceVariant);
        LinearLayout.LayoutParams dotParams = new LinearLayout.LayoutParams(dp(6), dp(6));
        dotParams.setMarginEnd(dp(8));
        holder.row.addView(holder.dot, dotParams);
        LinearLayout lines = new LinearLayout(a);
        lines.setOrientation(LinearLayout.VERTICAL);
        holder.title = railText("", 13, a.chrome.onSurface);
        holder.title.setTypeface(railMono(false));
        lines.addView(holder.title);
        // The second line: a terminal's checkout (worktree or branch), never a row of its own.
        holder.sub = railText("", 11, a.chrome.onSurfaceVariant);
        holder.sub.setTypeface(railMono(false));
        holder.sub.setVisibility(View.GONE);
        lines.addView(holder.sub);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, -2, 1);
        params.leftMargin = dp(1);
        holder.row.addView(lines, params);
        // A colour alone cannot say what an agent is doing; name the state.
        holder.state = railText("", 11, a.chrome.onSurfaceVariant);
        holder.state.setTypeface(railMono(false));
        holder.state.setSingleLine(true);
        holder.state.setEllipsize(android.text.TextUtils.TruncateAt.END);
        holder.state.setGravity(android.view.Gravity.END);
        LinearLayout.LayoutParams stateParams = new LinearLayout.LayoutParams(-2, -2);
        stateParams.leftMargin = dp(6);
        holder.row.addView(holder.state, stateParams);
        holder.row.setOnClickListener(view -> {
            if (holder.live) {
                a.focusRemotePane(holder.connection, holder.paneId);
            } else {
                switchToRailPane(holder.host, holder.paneId);
            }
            a.closeRailDrawer();
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
            holder.dot.setBackgroundColor(a.chrome.status("question".equals(status) ? "blocked" : status));
            if (pulse) pulseRailDot(holder.dot);
        }
        if (holder.selected != selected) {
            holder.selected = selected;
            holder.row.setBackground(railBackground(selected));
            holder.row.setSelected(selected);
        }
        if (!title.contentEquals(holder.title.getText())) holder.title.setText(title);
        holder.title.setTextColor(AgentNames.UNNAMED.equals(title) ? a.chrome.onSurfaceVariant : a.chrome.onSurface);
        // The dot is the state (Wolf: "the dot is enough"); no state word. A red "!" stays only on a
        // PM's or the Deacon's row while blocked work is folded under it, where no dot shows it.
        String state = bubbled ? "!" : "";
        if (!state.contentEquals(holder.state.getText())) holder.state.setText(state);
        holder.state.setTextColor(a.chrome.danger);
        CharSequence sub = holder.sub.getVisibility() == View.VISIBLE ? holder.sub.getText() : "";
        // A row description overrides its children, so the state has to be said here.
        String described = "Focus pane " + title + (sub.length() == 0 ? "" : ", " + sub)
                + ", " + AgentNames.stateWords(status, "question".equals(status) ? "question" : "")
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
        rule = new View(a);
        rule.setBackgroundColor(a.chrome.outline);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, dp(1));
        params.topMargin = dp(6);
        params.bottomMargin = dp(4);
        params.leftMargin = dp(6);
        params.rightMargin = dp(6);
        rule.setLayoutParams(params);
        railRules.put(key, rule);
        return rule;
    }

    static final class RailHostRow {
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

    static final class RailWorkspaceRow {
        LinearLayout row;
        RailDisclosure mark;
        TextView label, meta;
        SavedRemoteHost host;
        Connection connection;
        String workspaceId = "";
        String firstPane = "";
        boolean live;
        Boolean nested, selected;
        /** What a header folds (a repo's worktrees, or its own windows), or null. */
        String foldKey;
        boolean folded;
        /** The one physical space a row wholly is, or null for a Fleet header (scoped menu). */
        String physicalSpace = "";
        /** Optional catalog shortcut on a project's long-press menu. */
        Runnable worktrees;
        /** Primary tap for a catalog row; project and fold rows resolve from their own fields. */
        Runnable action;
    }

    static final class RailPaneRow {
        LinearLayout row;
        View dot;
        RailDisclosure mark;
        TextView title, sub;
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
        TextView label = new TextView(a);
        label.setText(text);
        label.setTextSize(size);
        label.setTextColor(color);
        label.setSingleLine(true);
        label.setEllipsize(android.text.TextUtils.TruncateAt.END);
        label.setSaveEnabled(false);
        return label;
    }

    android.graphics.drawable.GradientDrawable roundedDrawable(int color, int radiusDp) {
        android.graphics.drawable.GradientDrawable shape = new android.graphics.drawable.GradientDrawable();
        shape.setColor(color);
        shape.setCornerRadius(dp(radiusDp));
        return shape;
    }

}
