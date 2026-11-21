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
 * reads them. Stores are read when the rail shows a PM's row (at most every
 * {@link #ROW_POLL_MS}) and every {@link #POLL_MS} while drilled in; a detail
 * on tap and every {@link #POLL_MS} while it shows. Nothing is read while the
 * rail is hidden or the app is paused: each read holds the core session.
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
    /** Bumped with every new stores answer, so a cached Work knows it is old. */
    private static int storesStamp;
    /** Each (host, project)'s Work for the stores stamp and members it was built from. */
    private static final Map<String, Object[]> WORK = new HashMap<>();

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
    private boolean paused;
    /** What the body shows was drawn from these inputs; the same inputs draw nothing new. */
    private String drawn = "";
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
        back = mono("", 14, chrome.primary, true);
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
        PauseWatch.on(activity, () -> {
            paused = true;
            main.removeCallbacks(poll);
        }, () -> {
            paused = false;
            if (isOpen()) {
                main.removeCallbacks(poll);
                main.post(poll);
            }
        });
    }

    /**
     * {@code project}'s Work on {@code hostId}, built once per stores answer and
     * members change: the rail asks for it on every redraw (row, badge, view).
     */
    static BeadsModel.Work work(String hostId, String project, List<? extends FleetTree.Member> members) {
        Map<String, BeadsModel.Store> stores = STORES.get(hostId);
        if (stores == null) return null;
        String key = hostId + "\n" + project, sig = storesStamp + "\n" + membersKey(members);
        Object[] cached = WORK.get(key);
        if (cached != null && sig.equals(cached[0])) return (BeadsModel.Work) cached[1];
        BeadsModel.Work work = BeadsModel.work(project, stores, members);
        WORK.put(key, new Object[] {sig, work});
        return work;
    }

    /** What of the members a Work reads: who they are, where they report, what they are on. */
    static String membersKey(List<? extends FleetTree.Member> members) {
        StringBuilder key = new StringBuilder();
        for (FleetTree.Member m : members) {
            key.append(m.id).append('|').append(m.role).append('|').append(m.parent).append('|')
                    .append(m.project).append('|').append(m.status).append('|').append(m.waitKind).append('|')
                    .append(m.title);
            if (m instanceof BeadsModel.Term) {
                BeadsModel.Term t = (BeadsModel.Term) m;
                key.append('|').append(t.issue).append('|').append(t.task).append('|').append(t.helper);
            }
            key.append('\n');
        }
        return key.toString();
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
        BeadsModel.Work work = work(hostId, project, members);
        if (work == null) return READ_ERROR.containsKey(hostId) ? "☰ Beads · can't read" : null;
        if (work.total == 0 && work.tree.isEmpty()) return work.error != null ? "☰ Beads · can't read" : "☰ Beads · none";
        // Open epics of all epics, as the desktop's Work header counts them.
        return "☰ Beads" + (work.total > 0 ? " " + work.open + "/" + work.total : "")
                + (work.needsYou > 0 ? " · !" + work.needsYou : "");
    }

    /** The row's needs-you count, for the PM row's own badge while it is folded. */
    static int needsYou(String hostId, String project, List<? extends FleetTree.Member> members) {
        BeadsModel.Work work = work(hostId, project, members);
        return work == null ? 0 : work.needsYou;
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
        if (paused || READING.contains(hostId) || now - READ_AT.getOrDefault(hostId, 0L) < every) return;
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
            } catch (HostSwitched gone) {
                error = null;
            } catch (JSONException | RuntimeException | LinkageError e) {
                error = "can't reach the machine";
            }
            BeadsModel.Stores answer = read;
            String why = error;
            main.post(() -> {
                READING.remove(hostId);
                if (answer == null && why == null) {
                    // The phone left that machine (or paused): neither news nor an error; ask again later.
                    READ_AT.remove(hostId);
                    return;
                }
                if (answer == null) {
                    READ_ERROR.put(hostId, why);
                } else {
                    READ_ERROR.remove(hostId);
                    MISSING.put(hostId, answer.missing);
                    STORES.put(hostId, BeadsModel.merge(previous, answer));
                    storesStamp++;
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
        // The fleet list hides at once, not when the first read answers.
        changed.run();
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
        if (!isOpen() || paused) return;
        // Hidden (the drawer closed over a terminal, a docked rail minimized): no reads, keep the timer.
        if (root.isShown()) {
            want(hostId, look);
            if (detailId != null) readDetail(detailId);
        }
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
                } else if (reply.optBoolean("truncated", false)) {
                    error = "the bead is too long to show here";
                } else {
                    read = BeadsModel.parseDetail(reply.optString("text", ""));
                }
            } catch (HostSwitched gone) {
                error = null;
            } catch (JSONException | RuntimeException | LinkageError e) {
                error = "can't reach the machine";
            }
            BeadsModel.Detail answer = read;
            String why = error;
            main.post(() -> {
                detailBusy = false;
                if (!id.equals(detailId) || (answer == null && why == null)) return;
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

    /** Redraws when what it shows changed; keeps the focused row (by its key) across a redraw. */
    private void render() {
        if (!isOpen()) return;
        String sig = inputs();
        if (sig.equals(drawn)) return;
        drawn = sig;
        View focused = root.findFocus();
        Object focusKey = focused == null ? null : focused.getTag();
        body.removeAllViews();
        if (detailId != null) {
            back.setText("‹ " + project + "  ›  " + detailId);
            back.setContentDescription("Back to " + project + "'s beads");
            renderDetail();
        } else {
            back.setText("‹ " + project);
            back.setContentDescription("Back to the agents");
            renderList();
        }
        if (focusKey != null) {
            View again = body.findViewWithTag(focusKey);
            if (again != null) again.requestFocus();
        }
    }

    /** Everything the body is drawn from, so an unchanged redraw is skipped (and focus kept). */
    private String inputs() {
        return hostId + "\n" + project + "\n" + detailId + "\n" + storesStamp + "\n" + MISSING.get(hostId) + "\n"
                + READ_ERROR.get(hostId) + "\n" + STORES.containsKey(hostId) + "\n" + membersKey(members) + "\n"
                + unfolded + "\n" + showAll + "\n" + System.identityHashCode(detail) + "\n" + detailError;
    }

    private void renderList() {
        if (Boolean.TRUE.equals(MISSING.get(hostId))) {
            body.addView(note("bd isn't installed on this machine, so there are no beads to show.", chrome.warning));
            return;
        }
        String readError = READ_ERROR.get(hostId);
        BeadsModel.Work work = work(hostId, project, members);
        if (work == null) {
            body.addView(note(readError != null ? "Can't read the beads: " + readError : "Reading the beads…",
                    readError != null ? chrome.danger : chrome.onSurfaceVariant));
            return;
        }
        if (readError != null) body.addView(note("Showing the last read; the newest failed: " + readError, chrome.warning));
        if (work.error != null) body.addView(note(work.error, chrome.danger));
        // What needs Wolf first, as the desktop Work panel puts it: the "!k" of the Beads row.
        if (!work.needs.isEmpty() || !work.blocked.isEmpty()) {
            body.addView(heading("NEEDS YOU"));
            for (BeadsModel.Need need : work.needs) body.addView(needRow(need));
            for (FleetTree.Member agent : work.blocked) body.addView(agentRow(agent, 0));
        }
        if (!work.epics.isEmpty() || work.doneEpics > 0) body.addView(heading("EPICS"));
        for (BeadsModel.Epic epic : work.epics) body.addView(epicRow(epic));
        if (work.doneEpics > 0) body.addView(indented(note(work.doneEpics + " done", chrome.onSurfaceVariant), 0));
        if (!work.tree.isEmpty()) body.addView(heading("BEADS"));
        for (BeadsModel.Node node : work.tree) addNode(node, 0);
        if (!work.loose.isEmpty()) {
            body.addView(heading("ON NO BEAD"));
            for (FleetTree.Member agent : work.loose) body.addView(agentRow(agent, 0));
        }
        if (work.epics.isEmpty() && work.doneEpics == 0 && work.tree.isEmpty() && work.error == null) {
            body.addView(note("No open beads for " + project + ".", chrome.onSurfaceVariant));
        }
    }

    private View needRow(BeadsModel.Need need) {
        LinearLayout row = rowLayout(0, "need:" + need.bead + ":" + (need.terminal == null ? "" : need.terminal.id));
        row.setPadding(row.getPaddingLeft() + dp(FleetTree.PM_CHEVRON_DP), dp(2), dp(6), dp(2));
        row.addView(mark("blocked"), markParams());
        LinearLayout lines = new LinearLayout(activity);
        lines.setOrientation(LinearLayout.VERTICAL);
        TextView title = mono(need.title, 13, chrome.onSurface, false);
        title.setMaxLines(2);
        title.setEllipsize(TextUtils.TruncateAt.END);
        lines.addView(title);
        lines.addView(mono("! " + need.why, 11, chrome.danger, false));
        row.addView(lines, new LinearLayout.LayoutParams(0, -2, 1));
        row.setContentDescription(need.title + ", needs you: " + need.why);
        if (need.bead != null && !need.bead.isEmpty()) row.setOnClickListener(v -> showDetail(need.bead));
        else if (need.terminal != null) row.setOnClickListener(v -> openAgent.accept(need.terminal.id));
        return row;
    }

    private View epicRow(BeadsModel.Epic epic) {
        LinearLayout row = rowLayout(0, "epic:" + epic.bead.id);
        row.setOrientation(LinearLayout.VERTICAL);
        // Lined up with the bead rows' titles, past their chevron column.
        row.setPadding(row.getPaddingLeft() + dp(FleetTree.PM_CHEVRON_DP + 6), dp(4), dp(6), dp(4));
        LinearLayout line = new LinearLayout(activity);
        line.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = mono(epic.bead.title, 13, chrome.onSurface, true);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        line.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        line.addView(mono(epic.closed + "/" + epic.total, 11, chrome.onSurfaceVariant, false));
        row.addView(line);
        row.addView(mono(UsageModel.bar(epic.total == 0 ? 0.0 : 100.0 * epic.closed / epic.total), 11, chrome.primary, false));
        row.setContentDescription("Epic " + epic.bead.title + ", " + epic.closed + " of " + epic.total + " done");
        row.setOnClickListener(v -> showDetail(epic.bead.id));
        return row;
    }

    private void addNode(BeadsModel.Node node, int depth) {
        LinearLayout row = rowLayout(depth, "bead:" + node.bead.id);
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
        boolean needs = "needs-you".equals(node.mark);
        LinearLayout lines = new LinearLayout(activity);
        lines.setOrientation(LinearLayout.VERTICAL);
        TextView title = mono((needs ? "! " : "") + node.bead.title, 13,
                needs ? chrome.danger : BeadsModel.isClosed(node.bead) ? chrome.onSurfaceVariant : chrome.onSurface, false);
        title.setMaxLines(2);
        title.setEllipsize(TextUtils.TruncateAt.END);
        lines.addView(title);
        String meta = node.bead.id + " · " + node.bead.status + (node.bead.assignee == null ? "" : " · " + node.bead.assignee)
                + (node.mark == null ? "" : " · " + markWords(node.mark));
        lines.addView(mono(meta, 11, chrome.onSurfaceVariant, false));
        row.addView(lines, new LinearLayout.LayoutParams(0, -2, 1));
        row.setContentDescription(node.bead.title + ", " + node.bead.id + ", " + node.bead.status
                + (node.mark == null ? "" : ", " + markWords(node.mark))
                + (node.agents.isEmpty() ? "" : ", " + node.agents.size() + " working on it"));
        row.setOnClickListener(v -> showDetail(node.bead.id));
        body.addView(row);
        for (FleetTree.Member agent : node.agents) body.addView(agentRow(agent, depth + 1));
        if (!folds || !open) return;
        for (BeadsModel.Node child : node.children) addNode(child, depth + 1);
        // The desktop folds each parent's closed children into one line under it.
        if (node.closed > 0) body.addView(indented(note(node.closed + " closed", chrome.onSurfaceVariant), depth + 1));
    }

    /** A Work mark in words: "needs you", "done", "working". */
    static String markWords(String mark) {
        return "needs-you".equals(mark) ? "needs you" : mark;
    }

    private View agentRow(FleetTree.Member agent, int depth) {
        LinearLayout row = rowLayout(depth, "agent:" + agent.id);
        row.setPadding(row.getPaddingLeft() + dp(FleetTree.PM_CHEVRON_DP), dp(2), dp(6), dp(2));
        String status = agent.status.isEmpty() ? "" : agent.status;
        boolean alarm = "blocked".equals(status) || "error".equals(status) || "question".equals(agent.waitKind);
        // The rail's square status mark (no ovals in the boxy rail).
        row.addView(mark(alarm ? "blocked" : status.isEmpty() ? "unknown" : status), markParams());
        String name = agent.title.isEmpty() ? agent.id : agent.title;
        TextView label = mono(name, 12, chrome.onSurface, false);
        label.setSingleLine(true);
        label.setEllipsize(TextUtils.TruncateAt.END);
        row.addView(label, new LinearLayout.LayoutParams(0, -2, 1));
        row.addView(mono((alarm ? "! " : "") + status, 11, alarm ? chrome.danger : chrome.onSurfaceVariant, false));
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
        if (detailError != null) body.addView(note("Showing the last read; the newest failed: " + detailError, chrome.warning));
        TextView title = mono(detail.title, 15, chrome.onSurface, true);
        title.setPadding(dp(6), dp(6), dp(6), dp(2));
        body.addView(title);
        List<String> meta = new ArrayList<>();
        if (detail.status != null && !detail.status.isEmpty()) meta.add(detail.status);
        if (detail.type != null) meta.add(detail.type);
        if (detail.priority != null) meta.add("P" + detail.priority);
        if (detail.assignee != null) meta.add(detail.assignee);
        TextView metaLine = mono(String.join(" · ", meta), 11, chrome.onSurfaceVariant, false);
        metaLine.setPadding(dp(6), 0, dp(6), 0);
        body.addView(metaLine);
        if (detail.closeReason != null) body.addView(section("Closed", detail.closeReason, false));
        // Its running agents, each a tap away (the rail's focus).
        BeadsModel.Work work = work(hostId, project, members);
        List<FleetTree.Member> agents = work == null ? Collections.emptyList() : BeadsModel.agentsOf(work, detail.id);
        body.addView(heading("AGENTS"));
        if (agents.isEmpty()) body.addView(note("No agent is working on it.", chrome.onSurfaceVariant));
        for (FleetTree.Member agent : agents) body.addView(agentRow(agent, 0));
        boolean all = showAll.contains(detail.id);
        boolean hidden = false;
        for (String[] s : detail.sections) {
            boolean description = "Description".equals(s[0]);
            if (!all && !description) { hidden = true; continue; }
            if (!all && lineCount(s[1]) > DESCRIPTION_LINES) hidden = true;
            body.addView(section(s[0], s[1], !all));
        }
        List<BeadsModel.Comment> comments = detail.comments;
        if (!comments.isEmpty()) {
            body.addView(heading("COMMENTS (" + comments.size() + ")"));
            int from = all ? 0 : Math.max(0, comments.size() - COMMENTS);
            if (from > 0) hidden = true;
            for (int i = from; i < comments.size(); i++) {
                BeadsModel.Comment c = comments.get(i);
                TextView by = mono((c.author == null ? "" : c.author) + (c.at == null ? "" : "  " + c.at), 11, chrome.onSurfaceVariant, false);
                by.setPadding(dp(6), 0, dp(6), 0);
                body.addView(by);
                TextView text = prose(c.text);
                text.setPadding(dp(6), 0, dp(6), dp(8));
                body.addView(text);
            }
        }
        if (!all && !hidden) return;
        TextView more = mono(all ? "Show less" : "Show all", 13, chrome.primary, true);
        more.setTag("more:" + detail.id);
        more.setMinHeight(dp(44));
        more.setGravity(Gravity.CENTER_VERTICAL);
        more.setPadding(dp(6), 0, dp(6), 0);
        more.setBackground(ActionSheet.ripple(chrome));
        more.setFocusable(true);
        more.setOnClickListener(v -> {
            if (!showAll.remove(detail.id)) showAll.add(detail.id);
            render();
        });
        body.addView(more);
    }

    /** Lines a text takes before wrapping, as written (its newlines). */
    static int lineCount(String text) {
        return text == null || text.isEmpty() ? 0 : text.split("\n", -1).length;
    }

    private View section(String name, String text, boolean compact) {
        LinearLayout box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.VERTICAL);
        box.addView(heading(name.toUpperCase(java.util.Locale.ROOT)));
        TextView words = prose(text);
        words.setPadding(dp(6), 0, dp(6), dp(6));
        if (compact) {
            words.setMaxLines(DESCRIPTION_LINES);
            words.setEllipsize(TextUtils.TruncateAt.END);
        }
        box.addView(words);
        return box;
    }

    private LinearLayout rowLayout(int depth, String key) {
        LinearLayout row = new LinearLayout(activity);
        row.setTag(key);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(FleetTree.AGENT_ROW_DP));
        row.setPadding(dp(2 + FleetTree.RAIL_DEPTH_DP * depth), dp(2), dp(6), dp(2));
        row.setBackground(ActionSheet.ripple(chrome));
        row.setFocusable(true);
        return row;
    }

    /** The rail's 6dp square status mark, in the status colour. */
    private View mark(String status) {
        View dot = new View(activity);
        dot.setBackgroundColor(chrome.status(status));
        dot.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        return dot;
    }

    private LinearLayout.LayoutParams markParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(6), dp(6));
        params.setMarginEnd(dp(8));
        return params;
    }

    private View indented(TextView view, int depth) {
        view.setPadding(dp(8 + FleetTree.RAIL_DEPTH_DP * depth + FleetTree.PM_CHEVRON_DP), dp(4), dp(6), dp(4));
        return view;
    }

    private TextView heading(String words) {
        TextView view = mono(words, 11, chrome.onSurfaceVariant, true);
        view.setPadding(dp(6), dp(10), dp(6), dp(4));
        view.setAccessibilityHeading(true);
        return view;
    }

    private TextView note(String words, int color) {
        TextView view = mono(words, 12, color, false);
        view.setPadding(dp(6), dp(6), dp(6), dp(6));
        return view;
    }

    /** The rail's own face: monospace, medium for titles (as GpuiTerminalActivity.railMono). */
    private TextView mono(String value, float size, int color, boolean medium) {
        TextView view = ActionSheet.label(activity, value == null ? "" : value, size, color);
        view.setTypeface(Typeface.create(Typeface.MONOSPACE, medium ? 500 : 400, false));
        return view;
    }

    /** Long text (a description, a comment): the reading face, selectable. */
    private TextView prose(String value) {
        TextView view = ActionSheet.label(activity, value == null ? "" : value, 13, chrome.onSurface);
        view.setTextIsSelectable(true);
        return view;
    }

    private int dp(float value) {
        return ActionSheet.dp(activity, value);
    }
}
