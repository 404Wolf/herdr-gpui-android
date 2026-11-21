package dev.herdr.android;

import android.app.Activity;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * The phone's Work view of one project (factory-setup.21), inside the rail:
 * an expanded PM shows a "☰ Beads 2/5 · !1" row ({@link #row}); tapping it
 * drills the rail into that project's epics and bead tree, a bead tap into
 * its compact detail, and ‹ or Back pops one level (detail, beads, fleet).
 * Beads is the source and this is read-only: the core's beads.stores and
 * beads.detail run the desktop Work panel's own scripts, {@link BeadsModel}
 * reads them. Stores are read while the row is shown and every
 * {@link #POLL_MS} while drilled in; a detail on tap and every
 * {@link #POLL_MS} while it shows.
 */
final class BeadsRail {
    static final long POLL_MS = 15_000;
    /**
     * The Beads row alone (not drilled in) reads less often: each read holds
     * the core session, and typing, until it answers (see {@link OpTiming}).
     */
    static final long ROW_POLL_MS = 60_000;
    /** The compact detail: the description's first lines and the latest comments, until Show all. */
    static final int DESCRIPTION_LINES = 6, COMMENTS = 2;

    /** Each machine's stores, as last read, and when (by saved host id). */
    private static final Map<String, Map<String, BeadsModel.Store>> STORES = new HashMap<>();
    private static final Map<String, Long> READ_AT = new HashMap<>();
    private static final Map<String, String> READ_ERROR = new HashMap<>();
    private static final Map<String, Boolean> MISSING = new HashMap<>();
    private static final Set<String> READING = new HashSet<>();

    private final Activity activity;
    private final GpuiChrome chrome;
    private final Executor worker;
    private final Consumer<String> openAgent;
    private final Runnable changed;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final LinearLayout root, body;
    private final TextView back;
    private final ScrollView scroll;
    private final Set<String> unfolded = new HashSet<>();
    private final Set<String> showAll = new HashSet<>();

    private String hostId, project;
    private Function<String, String> look;
    private List<? extends FleetTree.Member> members = Collections.emptyList();
    /** The bead whose detail shows, or null for the list. */
    private String detailId;
    private BeadsModel.Detail detail;
    private String detailError;
    private boolean detailBusy;
    private final Runnable poll = this::tick;

    /**
     * {@code openAgent} focuses a terminal (the rail's own tap); {@code changed}
     * redraws the rail when a read brings new counts for the Beads row.
     */
    BeadsRail(Activity activity, GpuiChrome chrome, Executor worker, Consumer<String> openAgent, Runnable changed) {
        this.activity = activity;
        this.chrome = chrome;
        this.worker = worker;
        this.openAgent = openAgent;
        this.changed = changed;
        root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setVisibility(View.GONE);
        back = text("", 14, chrome.primary, true);
        back.setGravity(Gravity.CENTER_VERTICAL);
        back.setMinHeight(dp(48));
        back.setPadding(dp(12), 0, dp(12), 0);
        back.setSingleLine(true);
        back.setEllipsize(TextUtils.TruncateAt.END);
        back.setBackground(ActionSheet.ripple(chrome));
        back.setOnClickListener(v -> back());
        root.addView(back, new LinearLayout.LayoutParams(-1, -2));
        body = new LinearLayout(activity);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(6), 0, dp(6), dp(12));
        scroll = new ScrollView(activity);
        scroll.addView(body);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
    }

    View view() {
        return root;
    }

    boolean isOpen() {
        return project != null;
    }

    /**
     * The Beads row's words for {@code project} on {@code hostId}, from the last
     * read, or null when nothing is known yet (the row then says it is reading).
     */
    static String row(String hostId, String project, List<? extends FleetTree.Member> members) {
        if (Boolean.TRUE.equals(MISSING.get(hostId))) return "☰ Beads · bd isn't installed";
        Map<String, BeadsModel.Store> stores = STORES.get(hostId);
        if (stores == null) return READ_ERROR.containsKey(hostId) ? "☰ Beads · can't read" : null;
        BeadsModel.Work work = BeadsModel.work(project, stores, members);
        if (work.total == 0 && work.tree.isEmpty()) return work.error != null ? "☰ Beads · can't read" : "☰ Beads · none";
        // Open epics of all epics, as the desktop's Work header counts them.
        return "☰ Beads" + (work.total > 0 ? " " + work.open + "/" + work.total : "")
                + (work.needsYou > 0 ? " · !" + work.needsYou : "");
    }

    /** The row's needs-you count, for the PM row's own badge while it is folded. */
    static int needsYou(String hostId, String project, List<? extends FleetTree.Member> members) {
        Map<String, BeadsModel.Store> stores = STORES.get(hostId);
        return stores == null ? 0 : BeadsModel.work(project, stores, members).needsYou;
    }

    /**
     * Reads {@code hostId}'s stores when the last read is older than
     * {@link #POLL_MS} while drilled into it, else {@link #ROW_POLL_MS}, and
     * none is running. {@code look} sends one op to that
     * machine's core and returns its reply; it blocks, so it runs on the worker.
     */
    void want(String hostId, Function<String, String> look) {
        long now = System.currentTimeMillis();
        long every = isOpen() && hostId.equals(this.hostId) ? POLL_MS : ROW_POLL_MS;
        if (READING.contains(hostId) || now - READ_AT.getOrDefault(hostId, 0L) < every) return;
        READING.add(hostId);
        READ_AT.put(hostId, now);
        Map<String, BeadsModel.Store> previous = STORES.getOrDefault(hostId, Collections.emptyMap());
        String op;
        try {
            op = new JSONObject().put("op", "beads.stores")
                    .put("known", new JSONArray(BeadsModel.knownArgs(previous))).toString();
        } catch (JSONException impossible) {
            READING.remove(hostId);
            return;
        }
        worker.execute(() -> {
            BeadsModel.Stores read = null;
            String error = null;
            try {
                JSONObject reply = new JSONObject(OpTiming.timed("beads.stores", () -> look.apply(op)));
                if (!reply.optBoolean("queued", false) || reply.has("error")) {
                    error = reply.optString("error", "the machine refused the read");
                } else if (reply.optBoolean("truncated", false)) {
                    error = "the beads list was too long";
                } else {
                    read = BeadsModel.parseStores(reply.optString("text", ""));
                }
            } catch (JSONException | RuntimeException | LinkageError e) {
                error = "unreachable";
            }
            BeadsModel.Stores answer = read;
            String why = error;
            main.post(() -> {
                READING.remove(hostId);
                if (answer == null) {
                    READ_ERROR.put(hostId, why);
                } else {
                    READ_ERROR.remove(hostId);
                    MISSING.put(hostId, answer.missing);
                    STORES.put(hostId, BeadsModel.merge(previous, answer));
                }
                changed.run();
                if (hostId.equals(this.hostId)) render();
            });
        });
    }

    /** Drills in: the project's beads, read again now and while it shows. */
    void open(String hostId, String project, List<? extends FleetTree.Member> members, Function<String, String> look) {
        this.hostId = hostId;
        this.project = project;
        this.members = members;
        this.look = look;
        detailId = null;
        detail = null;
        root.setVisibility(View.VISIBLE);
        READ_AT.remove(hostId);
        want(hostId, look);
        render();
        main.removeCallbacks(poll);
        main.postDelayed(poll, POLL_MS);
    }

    /** New members (the rail's latest poll), so agents under their beads stay current. */
    void members(List<? extends FleetTree.Member> members) {
        this.members = members;
        if (isOpen()) render();
    }

    /** Pops one level; false when there was nothing to pop. */
    boolean back() {
        if (!isOpen()) return false;
        if (detailId != null) {
            detailId = null;
            detail = null;
            detailError = null;
            render();
            return true;
        }
        close();
        return true;
    }

    /** Leaves the beads view (a machine switch drops it). */
    void close() {
        project = null;
        hostId = null;
        look = null;
        detailId = null;
        detail = null;
        main.removeCallbacks(poll);
        root.setVisibility(View.GONE);
        changed.run();
    }

    private void tick() {
        if (!isOpen()) return;
        want(hostId, look);
        if (detailId != null) readDetail(detailId);
        main.postDelayed(poll, POLL_MS);
    }

    private void showDetail(String id) {
        detailId = id;
        detail = null;
        detailError = null;
        render();
        scroll.scrollTo(0, 0);
        readDetail(id);
    }

    private void readDetail(String id) {
        if (detailBusy || look == null) return;
        String op;
        try {
            op = new JSONObject().put("op", "beads.detail").put("project", project).put("id", id).toString();
        } catch (JSONException impossible) {
            return;
        }
        detailBusy = true;
        Function<String, String> asking = look;
        worker.execute(() -> {
            BeadsModel.Detail read = null;
            String error = null;
            try {
                JSONObject reply = new JSONObject(OpTiming.timed("beads.detail", () -> asking.apply(op)));
                if (!reply.optBoolean("queued", false) || reply.has("error")) {
                    error = reply.optString("error", "the machine refused the read");
                } else {
                    read = BeadsModel.parseDetail(reply.optString("text", ""));
                }
            } catch (JSONException | RuntimeException | LinkageError e) {
                error = "unreachable";
            }
            BeadsModel.Detail answer = read;
            String why = error;
            main.post(() -> {
                detailBusy = false;
                if (!id.equals(detailId)) return;
                if (answer != null && answer.error == null) {
                    detail = answer;
                    detailError = null;
                } else {
                    // An older good answer stays; the error says why it is not newer.
                    detailError = answer != null ? answer.error : why;
                }
                render();
            });
        });
    }

    private void render() {
        if (!isOpen()) return;
        body.removeAllViews();
        if (detailId != null) {
            back.setText("‹ " + project + "  ›  " + detailId);
            back.setContentDescription("Back to " + project + "'s beads");
            renderDetail();
            return;
        }
        back.setText("‹ " + project);
        back.setContentDescription("Back to the agents");
        if (Boolean.TRUE.equals(MISSING.get(hostId))) {
            body.addView(note("bd isn't installed on this machine.", chrome.warning));
            return;
        }
        Map<String, BeadsModel.Store> stores = STORES.get(hostId);
        String readError = READ_ERROR.get(hostId);
        if (stores == null) {
            body.addView(note(readError != null ? "Can't read the beads: " + readError : "Reading the beads…",
                    readError != null ? chrome.danger : chrome.onSurfaceVariant));
            return;
        }
        if (readError != null) body.addView(note("Showing the last read: " + readError, chrome.warning));
        BeadsModel.Work work = BeadsModel.work(project, stores, members);
        if (work.error != null) body.addView(note(work.error, chrome.danger));
        if (!work.epics.isEmpty() || work.doneEpics > 0) body.addView(heading("EPICS"));
        for (BeadsModel.Epic epic : work.epics) body.addView(epicRow(epic));
        if (work.doneEpics > 0) body.addView(note(work.doneEpics + " done", chrome.onSurfaceVariant));
        if (!work.tree.isEmpty()) body.addView(heading("BEADS"));
        for (BeadsModel.Node node : work.tree) addNode(node, 0);
        if (work.total == 0 && work.error == null) body.addView(note("No beads for " + project + ".", chrome.onSurfaceVariant));
    }

    private View epicRow(BeadsModel.Epic epic) {
        LinearLayout row = rowLayout(0);
        row.setOrientation(LinearLayout.VERTICAL);
        LinearLayout line = new LinearLayout(activity);
        line.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = text(epic.bead.title, 13, chrome.onSurface, true);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        line.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        line.addView(text(epic.closed + "/" + epic.total, 12, chrome.onSurfaceVariant, false));
        row.addView(line);
        row.addView(mono(UsageModel.bar(epic.total == 0 ? 0.0 : 100.0 * epic.closed / epic.total), chrome.primary));
        row.setContentDescription("Epic " + epic.bead.title + ", " + epic.closed + " of " + epic.total + " done");
        row.setOnClickListener(v -> showDetail(epic.bead.id));
        return row;
    }

    private void addNode(BeadsModel.Node node, int depth) {
        LinearLayout row = rowLayout(depth);
        boolean folds = !node.children.isEmpty() || node.closed > 0;
        boolean open = unfolded.contains(node.bead.id);
        RailDisclosure chevron = new RailDisclosure(activity, chrome);
        String says = folds ? (open ? "Hide " : "Show ") + (node.children.size() + node.closed) + " under " + node.bead.id : null;
        chevron.bind(folds, !open, says);
        chevron.setOnClickListener(v -> {
            if (!folds) { row.performClick(); return; }
            if (!unfolded.remove(node.bead.id)) unfolded.add(node.bead.id);
            render();
        });
        chevron.offerOn(row);
        row.addView(chevron, new LinearLayout.LayoutParams(dp(FleetTree.PM_CHEVRON_DP), -1));
        LinearLayout lines = new LinearLayout(activity);
        lines.setOrientation(LinearLayout.VERTICAL);
        TextView title = text(node.bead.title, 13, BeadsModel.isClosed(node.bead) ? chrome.onSurfaceVariant : chrome.onSurface, false);
        title.setMaxLines(2);
        title.setEllipsize(TextUtils.TruncateAt.END);
        lines.addView(title);
        lines.addView(mono(node.bead.id + " · " + node.bead.status
                + (node.bead.assignee == null ? "" : " · " + node.bead.assignee), chrome.onSurfaceVariant));
        row.addView(lines, new LinearLayout.LayoutParams(0, -2, 1));
        row.setContentDescription(node.bead.title + ", " + node.bead.id + ", " + node.bead.status
                + (node.agents.isEmpty() ? "" : ", " + node.agents.size() + " working on it"));
        row.setOnClickListener(v -> showDetail(node.bead.id));
        body.addView(row);
        for (FleetTree.Member agent : node.agents) body.addView(agentRow(agent, depth + 1));
        if (!folds || !open) return;
        for (BeadsModel.Node child : node.children) addNode(child, depth + 1);
        // The desktop folds each parent's closed children into one line under it.
        if (node.closed > 0) {
            TextView done = note(node.closed + " closed", chrome.onSurfaceVariant);
            done.setPadding(dp(8 + FleetTree.RAIL_DEPTH_DP * (depth + 1) + FleetTree.PM_CHEVRON_DP), dp(4), dp(6), dp(4));
            body.addView(done);
        }
    }

    private View agentRow(FleetTree.Member agent, int depth) {
        LinearLayout row = rowLayout(depth);
        row.setPadding(row.getPaddingLeft() + dp(FleetTree.PM_CHEVRON_DP), dp(2), dp(6), dp(2));
        String status = agent.status.isEmpty() ? "" : agent.status;
        boolean alarm = "blocked".equals(status) || "error".equals(status) || "question".equals(agent.waitKind);
        TextView mark = text("● ", 12, chrome.status(alarm ? "blocked" : status.isEmpty() ? "unknown" : status), false);
        row.addView(mark);
        String name = agent.title.isEmpty() ? agent.id : agent.title;
        TextView label = text(name, 12, chrome.onSurface, false);
        label.setSingleLine(true);
        label.setEllipsize(TextUtils.TruncateAt.END);
        row.addView(label, new LinearLayout.LayoutParams(0, -2, 1));
        row.addView(text((alarm ? "! " : "") + status, 11, alarm ? chrome.danger : chrome.onSurfaceVariant, false));
        row.setContentDescription("Focus " + name + (status.isEmpty() ? "" : ", " + status));
        row.setOnClickListener(v -> openAgent.accept(agent.id));
        return row;
    }

    private void renderDetail() {
        if (detail == null) {
            body.addView(note(detailError != null ? "Can't read " + detailId + ": " + detailError : "Reading " + detailId + "…",
                    detailError != null ? chrome.danger : chrome.onSurfaceVariant));
            return;
        }
        if (detailError != null) body.addView(note("Showing the last read: " + detailError, chrome.warning));
        TextView title = text(detail.title, 15, chrome.onSurface, true);
        title.setPadding(dp(6), dp(6), dp(6), dp(2));
        body.addView(title);
        List<String> meta = new ArrayList<>();
        if (detail.status != null && !detail.status.isEmpty()) meta.add(detail.status);
        if (detail.type != null) meta.add(detail.type);
        if (detail.priority != null) meta.add("P" + detail.priority);
        if (detail.assignee != null) meta.add(detail.assignee);
        body.addView(mono(String.join(" · ", meta), chrome.onSurfaceVariant));
        if (detail.closeReason != null) body.addView(section("Closed", detail.closeReason, false));
        // Its running agents, each a tap away (the rail's focus).
        BeadsModel.Work work = BeadsModel.work(project, STORES.getOrDefault(hostId, Collections.emptyMap()), members);
        List<FleetTree.Member> agents = BeadsModel.agentsOf(work, detail.id);
        body.addView(heading("AGENTS"));
        if (agents.isEmpty()) body.addView(note("No agent is working on it.", chrome.onSurfaceVariant));
        for (FleetTree.Member agent : agents) body.addView(agentRow(agent, 0));
        boolean all = showAll.contains(detail.id);
        for (String[] s : detail.sections) {
            if (!all && !"Description".equals(s[0])) continue;
            body.addView(section(s[0], s[1], !all));
        }
        List<BeadsModel.Comment> comments = detail.comments;
        if (!comments.isEmpty()) {
            body.addView(heading("COMMENTS (" + comments.size() + ")"));
            int from = all ? 0 : Math.max(0, comments.size() - COMMENTS);
            for (int i = from; i < comments.size(); i++) {
                BeadsModel.Comment c = comments.get(i);
                body.addView(mono((c.author == null ? "" : c.author) + (c.at == null ? "" : "  " + c.at), chrome.onSurfaceVariant));
                TextView text = text(c.text, 13, chrome.onSurface, false);
                text.setPadding(dp(6), 0, dp(6), dp(8));
                text.setTextIsSelectable(true);
                body.addView(text);
            }
        }
        TextView more = text(all ? "Show less" : "Show all", 13, chrome.primary, true);
        more.setMinHeight(dp(44));
        more.setGravity(Gravity.CENTER_VERTICAL);
        more.setPadding(dp(6), 0, dp(6), 0);
        more.setBackground(ActionSheet.ripple(chrome));
        more.setOnClickListener(v -> {
            if (!showAll.remove(detail.id)) showAll.add(detail.id);
            render();
        });
        body.addView(more);
    }

    private View section(String name, String text, boolean compact) {
        LinearLayout box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.VERTICAL);
        box.addView(heading(name.toUpperCase(java.util.Locale.ROOT)));
        TextView words = text(text, 13, chrome.onSurface, false);
        words.setPadding(dp(6), 0, dp(6), dp(6));
        words.setTextIsSelectable(true);
        if (compact) {
            words.setMaxLines(DESCRIPTION_LINES);
            words.setEllipsize(TextUtils.TruncateAt.END);
        }
        box.addView(words);
        return box;
    }


    private LinearLayout rowLayout(int depth) {
        LinearLayout row = new LinearLayout(activity);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(FleetTree.AGENT_ROW_DP));
        row.setPadding(dp(2 + FleetTree.RAIL_DEPTH_DP * depth), dp(2), dp(6), dp(2));
        row.setBackground(ActionSheet.ripple(chrome));
        row.setFocusable(true);
        return row;
    }

    private TextView heading(String words) {
        TextView view = text(words, 11, chrome.onSurfaceVariant, true);
        view.setPadding(dp(6), dp(10), dp(6), dp(4));
        return view;
    }

    private TextView note(String words, int color) {
        TextView view = text(words, 12, color, false);
        view.setPadding(dp(6), dp(6), dp(6), dp(6));
        return view;
    }

    private TextView text(String value, float size, int color, boolean bold) {
        TextView view = ActionSheet.label(activity, value == null ? "" : value, size, color);
        if (bold) view.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        return view;
    }

    private TextView mono(String value, int color) {
        TextView view = text(value, 11, color, false);
        view.setTypeface(Typeface.MONOSPACE);
        view.setPadding(dp(6), 0, dp(6), 0);
        view.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        return view;
    }

    private int dp(float value) {
        return ActionSheet.dp(activity, value);
    }
}
