package dev.herdr.android;

import android.app.Activity;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextPaint;
import android.text.TextUtils;
import android.text.style.AbsoluteSizeSpan;
import android.text.style.BackgroundColorSpan;
import android.text.style.ClickableSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.LeadingMarginSpan;
import android.text.style.QuoteSpan;
import android.text.style.RelativeSizeSpan;
import android.text.style.StyleSpan;
import android.text.style.TypefaceSpan;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The right panel (factory-r8f, the settled preview): two tabs for the agent
 * on screen. Overview is its own status doc (~/deacon/work/..., DocModel),
 * drawn as Markdown with its age by its machine's clock, or an honest "No
 * overview yet". Tasks is its project's epic and bead tree; a bead opens in
 * place as one Markdown plan with its comments under it (comments only
 * here), and a worker's own bead opens first. All text is selectable
 * ({@link SelectableText}) and copied by itself; Quote hands it to the draft.
 *
 * <p>Reads go through the core one at a time on a thread of their own, only
 * while the panel shows, the app is in front and Wolf is not typing (each
 * read holds the core session's worker, and typing waits behind it): the doc
 * every {@link #DOC_MS} (an unchanged doc costs no text), the stores every
 * {@link #STORES_MS} while Tasks shows. A redraw waits while text is
 * selected, so a selection is never pulled out from under a finger.
 */
final class WorkPanel {
    static final long DOC_MS = 10_000, STORES_MS = 30_000, TICK_MS = 1_000;

    /** What the activity gives the panel. */
    interface Host {
        /** One phone op on the connection on screen; blocks; throws HostSwitched once it is another's. */
        String op(String json);
        void note(String text);
        /** Puts {@code text}, quoted, in the draft of the agent on screen (never sends). */
        void quote(String header, String text);
        /** Opens a local path the doc links, on the doc's own machine. */
        void openPath(String path);
        /** Opens a web link (http/https), as the terminal's links open. */
        void openLink(String url);
        /** The keyboard is up or a draft is in the composer. */
        boolean typing();
        /** The cover's panel was closed (× or Back). */
        void closed();
        /** Shows terminal {@code id} of the machine on screen (the agent working on a bead). */
        void focus(String id);
    }

    private final Activity activity;
    private final GpuiChrome chrome;
    private final Host host;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService thread = Executors.newSingleThreadExecutor(task -> {
        Thread t = new Thread(task, "herdr-panel");
        t.setDaemon(true);
        return t;
    });
    private final LinearLayout root, tabs, body;
    private final ScrollView scroll;
    private final TextView overviewTab, tasksTab, close;
    /** The overview's age, at the right of the tab row. */
    private TextView age;
    /** The panel's type (review P1-4): 14sp body on 20dp lines, 16dp sides, 14sp tabs. */
    static final float BODY_SP = 14f, TAB_SP = 14f;
    static final int LINE_DP = 20, SIDE_DP = 16, BOTTOM_DP = 64;
    private PauseWatch pauses;

    // What is on screen.
    private PanelModel.Agent agent;
    private String hostId = "", machine = "";
    private PanelModel.Tab tab = PanelModel.Tab.OVERVIEW;
    private boolean shown, paused, inFlight;
    private final int[] scrollOf = new int[2];

    // What was read, per machine and agent (doc) or project (stores).
    private final Map<String, DocModel.Read> docs = new HashMap<>();
    private final Map<String, Long> docAt = new HashMap<>();
    private final Map<String, String> docErrors = new HashMap<>();
    private Map<String, BeadsModel.Store> stores = new HashMap<>();
    private String storesHost = "", storesError;
    private long storesAt = -1;
    /** Each bead's last read (plan and comments together), kept across opens and restarts. */
    private final BeadCache cache;
    /** Beads read this open, beads confirmed with bd, and the one being read. */
    private final Set<String> refreshed = new HashSet<>(), confirmed = new HashSet<>();
    private String reading = "";
    /** When a bead was opened (elapsed), and which of its firsts were logged. */
    private final Map<String, Long> openedAt = new HashMap<>();
    private final Set<String> loggedPlan = new HashSet<>(), loggedComments = new HashSet<>();
    private List<? extends FleetTree.Member> members = java.util.Collections.emptyList();
    private final Set<String> folded = new HashSet<>(), open = new HashSet<>();
    private String ownOpened = "";
    private String drawn = null;
    private boolean pending;

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            main.removeCallbacks(this);
            if (!shown) return;
            step();
            redraw(false);
            main.postDelayed(this, TICK_MS);
        }
    };

    private final LinearLayout trailing;

    WorkPanel(Activity activity, GpuiChrome chrome, Host host) {
        this.activity = activity;
        this.chrome = chrome;
        this.host = host;
        cache = BeadCache.in(activity);
        thread.execute(cache::load);
        root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(chrome.panel);
        root.setVisibility(View.GONE);
        tabs = new LinearLayout(activity);
        tabs.setGravity(Gravity.CENTER_VERTICAL);
        tabs.setPadding(dp(12), 0, dp(4), 0);
        overviewTab = tab("Overview", PanelModel.Tab.OVERVIEW);
        tasksTab = tab("Tasks", PanelModel.Tab.TASKS);
        tabs.addView(overviewTab, new LinearLayout.LayoutParams(-2, dp(40)));
        LinearLayout.LayoutParams second = new LinearLayout.LayoutParams(-2, dp(40));
        second.setMarginStart(dp(16));
        tabs.addView(tasksTab, second);
        tabs.addView(new View(activity), new LinearLayout.LayoutParams(0, 1, 1));
        age = ActionSheet.label(activity, "", 12, chrome.onSurfaceVariant);
        age.setSingleLine(true);
        age.setVisibility(View.GONE);
        tabs.addView(age, new LinearLayout.LayoutParams(-2, -2));
        close = ActionSheet.label(activity, "×", 20, chrome.onSurfaceVariant);
        close.setGravity(Gravity.CENTER);
        close.setBackground(ActionSheet.ripple(chrome));
        close.setContentDescription("Close the work panel");
        close.setOnClickListener(v -> host.closed());
        tabs.addView(close, new LinearLayout.LayoutParams(dp(48), dp(48)));
        // The app's own top-right controls (pin, ⋮) when the panel is docked: the far right of the whole screen.
        trailing = new LinearLayout(activity);
        trailing.setGravity(Gravity.CENTER_VERTICAL);
        tabs.addView(trailing, new LinearLayout.LayoutParams(-2, dp(40)));
        root.addView(tabs, new LinearLayout.LayoutParams(-1, -2));
        View rule = new View(activity);
        rule.setBackgroundColor(chrome.outline);
        root.addView(rule, new LinearLayout.LayoutParams(-1, 1));
        scroll = new ScrollView(activity);
        body = new LinearLayout(activity);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(SIDE_DP), dp(8), dp(SIDE_DP), dp(8));
        // The composer's icons float over the panel's bottom right (no bar): the
        // text scrolls under them, and its end scrolls clear of them (review A6).
        scroll.setPadding(0, 0, 0, dp(BOTTOM_DP));
        scroll.setClipToPadding(false);
        scroll.addView(body, new FrameLayout.LayoutParams(-1, -2));
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
    }

    View view() {
        return root;
    }

    /** Where the activity docks its top-right controls while the panel sits beside the terminal. */
    LinearLayout trailing() {
        return trailing;
    }

    /** Follows {@code activity}'s pauses: nothing reads while it is not in front. */
    void attach() {
        if (pauses != null) return;
        pauses = PauseWatch.on(activity, () -> paused = true, () -> { paused = false; main.post(tick); });
    }

    void detach() {
        if (pauses != null) pauses.stop(activity);
        pauses = null;
        shown = false;
        main.removeCallbacksAndMessages(null);
        thread.shutdownNow();
    }

    /** Shown or not (the cover opens it; the split always shows it); {@code closable}: the cover's ×. */
    void show(boolean on, boolean closable) {
        close.setVisibility(closable ? View.VISIBLE : View.GONE);
        if (on == shown) return;
        shown = on;
        root.setVisibility(on ? View.VISIBLE : View.GONE);
        if (on) { redraw(true); main.post(tick); }
        else main.removeCallbacks(tick);
    }

    boolean shown() {
        return shown;
    }

    /**
     * The agent on screen, its machine (the saved host and its name) and that
     * machine's live members (who works on which bead). Cheap when unchanged.
     */
    void select(PanelModel.Agent next, String hostId, String machine, List<? extends FleetTree.Member> members) {
        this.members = members == null ? java.util.Collections.emptyList() : members;
        String was = agent == null ? "" : agent.key() + this.hostId;
        String now = next == null ? "" : next.key() + (hostId == null ? "" : hostId);
        this.machine = machine == null ? "" : machine;
        if (was.equals(now)) return;
        if (!this.hostId.equals(hostId == null ? "" : hostId)) {
            stores = new HashMap<>();
            storesAt = -1;
            storesKeptAt = 0;
            storesError = null;
            visible.clear();
            refreshed.clear();
            confirmed.clear();
        }
        this.hostId = hostId == null ? "" : hostId;
        agent = next;
        scrollOf[0] = scrollOf[1] = 0;
        ownOpened = "";
        redraw(true);
        main.post(tick);
    }

    /** Back: a selection first, then (the cover) the panel. True when it did something. */
    boolean back() {
        if (SelectableText.clearAll(body)) return true;
        return false;
    }

    // ---- reading ----

    private String docKey() {
        return agent == null || agent.doc() == null ? null : hostId + "\n" + agent.doc();
    }

    /** One read at a time: what is due, in order of what shows. */
    private void step() {
        if (inFlight || paused || agent == null || host.typing()) return;
        long now = SystemClock.elapsedRealtime();
        String key = docKey();
        if (tab == PanelModel.Tab.OVERVIEW && key != null && due(docAt.get(key), DOC_MS, now)) {
            readDoc(key, agent.doc());
            return;
        }
        if (tab == PanelModel.Tab.TASKS && agent.hasTasks()) {
            useKeptStores();
            if (storesAt < 0 || !hostId.equals(storesHost) || due(storesAt, STORES_MS, now)) { readStores(); return; }
            for (String id : open) {
                String bead = beadKey(id);
                BeadCache.Entry kept = cache.get(bead);
                String fp = storeFp(id);
                // Kept: shown at once; read again once per open when it is not fresh, and whenever its store moved.
                boolean stale = kept == null || !refreshed.contains(bead) && System.currentTimeMillis() - kept.atMs > BEAD_FRESH_MS
                        || !fp.isEmpty() && !fp.equals(kept.fp);
                if (stale) { refreshed.add(bead); readBead(id, false); return; }
                BeadsModel.Detail d = BeadCache.detail(kept.text);
                if (d.error == null && d.maybeStale && confirmed.add(bead)) { readBead(id, true); return; }
            }
            // Then the beads in view, one at a time, so a tap on one finds it read: each once per open,
            // and only when its kept read is older than PREFETCH_MS.
            for (String id : visible) {
                String bead = beadKey(id);
                BeadCache.Entry kept = cache.get(bead);
                if (refreshed.contains(bead) || kept != null && System.currentTimeMillis() - kept.atMs < PREFETCH_MS) continue;
                refreshed.add(bead);
                readBead(id, false);
                return;
            }
        }
    }

    private static boolean due(Long at, long every, long now) {
        return at == null || at < 0 || now - at >= every;
    }

    private void readDoc(String key, String name) {
        DocModel.Read last = docs.get(key);
        JSONObject op = json("doc.read");
        put(op, "name", name);
        if (last != null && last.key != null) put(op, "key", last.key);
        run(op, reply -> {
            docAt.put(key, SystemClock.elapsedRealtime());
            String text = textOf(reply);
            if (text == null) { docErrors.put(key, errorOf(reply)); return; }
            DocModel.Read read = DocModel.parse(text);
            if (read.error != null) { docErrors.put(key, read.error); return; }
            docErrors.remove(key);
            if (read.same && last != null) { last.now = read.now; return; }
            docs.put(key, read);
        });
    }

    /** The kept listing, while this open has not read one yet: drawn at once, with its age. */
    private long storesKeptAt;

    private void useKeptStores() {
        if (!stores.isEmpty() || storesAt >= 0) return;
        BeadCache.Entry kept = cache.get(BeadCache.storesKey(hostId));
        if (kept == null) return;
        BeadsModel.Stores read = BeadsModel.parseStores(kept.text);
        if (read.missing) return;
        stores = BeadsModel.merge(new HashMap<>(), read);
        storesHost = hostId;
        storesKeptAt = kept.atMs;
    }

    private void readStores() {
        JSONObject op = json("beads.stores");
        if (hostId.equals(storesHost)) put(op, "known", new JSONArray(BeadsModel.knownArgs(stores)));
        String asked = hostId;
        run(op, reply -> {
            storesAt = SystemClock.elapsedRealtime();
            String text = textOf(reply);
            if (text == null) { storesError = errorOf(reply); return; }
            BeadsModel.Stores read = BeadsModel.parseStores(text);
            if (read.missing) { storesError = "bd isn't installed on " + machine; return; }
            stores = BeadsModel.merge(asked.equals(storesHost) ? stores : new HashMap<>(), read);
            storesHost = asked;
            storesError = null;
            storesKeptAt = 0;
            // Kept whole, so the tree draws at once after a restart.
            BeadCache.Entry was = cache.get(BeadCache.storesKey(asked));
            cache.put(BeadCache.storesKey(asked), new BeadCache.Entry(BeadCache.keepStores(was == null ? null : was.text, text),
                    System.currentTimeMillis(), ""), thread);
        });
    }

    /** A kept read this young is not read again on open. */
    static final long BEAD_FRESH_MS = 20_000;
    /** Beads in view are read ahead when their kept read is older than this; at most {@link #PREFETCH} of them. */
    static final long PREFETCH_MS = 5 * 60_000;
    static final int PREFETCH = 8;
    /** The open beads' ids in view, top first, as last drawn: what is read ahead. */
    private final List<String> visible = new java.util.ArrayList<>();

    private String storeOf(String id) {
        String store = BeadsModel.storeOf(agent.project, id, stores);
        return store == null ? agent.project : store;
    }

    private String beadKey(String id) {
        return BeadCache.key(hostId, storeOf(id), id);
    }

    /** The fingerprint of the store a bead is in, as the last stores read saw it ("" unknown). */
    private String storeFp(String id) {
        BeadsModel.Store store = stores.get(storeOf(id));
        return store == null || store.fp == null ? "" : store.fp;
    }

    /** One bead's plan and comments together, in one exec; kept, then drawn. */
    private void readBead(String id, boolean confirm) {
        JSONObject op = json("beads.read");
        put(op, "project", storeOf(id));
        put(op, "id", id);
        if (confirm) put(op, "confirm", true);
        String key = beadKey(id), fp = storeFp(id);
        reading = key;
        run(op, reply -> {
            reading = "";
            String text = textOf(reply);
            if (text == null) {
                // A failed read keeps what was kept; with nothing kept, it says why.
                if (cache.get(key) == null) beadErrors.put(key, errorOf(reply));
                return;
            }
            beadErrors.remove(key);
            cache.put(key, new BeadCache.Entry(text, System.currentTimeMillis(), fp), thread);
        });
    }

    private final Map<String, String> beadErrors = new HashMap<>();

    /** Runs {@code op} on the panel's thread; {@code done} gets the reply (null: unreachable) on the main thread. */
    private void run(JSONObject op, java.util.function.Consumer<JSONObject> done) {
        inFlight = true;
        String asked = hostId;
        String json = op.toString();
        thread.execute(() -> {
            JSONObject reply;
            try {
                reply = new JSONObject(host.op(json));
            } catch (HostSwitched gone) {
                reply = null;
            } catch (RuntimeException | JSONException e) {
                reply = new JSONObject();
                put(reply, "error", OpTiming.reason("panel", e instanceof RuntimeException ? (RuntimeException) e : new RuntimeException(e)));
            } catch (LinkageError e) {
                reply = null;
            }
            JSONObject answer = reply;
            main.post(() -> {
                inFlight = false;
                if (!asked.equals(hostId)) return;
                if (answer != null) done.accept(answer);
                redraw(false);
                main.post(tick);
            });
        });
    }

    private static String textOf(JSONObject reply) {
        if (!reply.optBoolean("queued", false) || reply.has("error")) return null;
        return reply.optString("text", "");
    }

    private static String errorOf(JSONObject reply) {
        String e = reply.optString("error", "");
        return e.isEmpty() ? "can't reach the machine" : e;
    }

    private static JSONObject json(String op) {
        JSONObject o = new JSONObject();
        put(o, "op", op);
        return o;
    }

    private static void put(JSONObject o, String k, Object v) {
        try {
            o.put(k, v);
        } catch (JSONException impossible) {
            // A plain key and value always go in.
        }
    }

    // ---- drawing ----

    private TextView tab(String label, PanelModel.Tab which) {
        TextView view = ActionSheet.label(activity, label, TAB_SP, chrome.onSurfaceVariant);
        view.setGravity(Gravity.CENTER);
        view.setOnClickListener(v -> {
            if (tab == which) return;
            scrollOf[tab.ordinal()] = scroll.getScrollY();
            tab = which;
            redraw(true);
            main.post(tick);
        });
        return view;
    }

    /** Redraws when what shows changed; never under a selection (it waits, and redraws once it ends). */
    private void redraw(boolean force) {
        if (!shown) return;
        PanelModel.Tab now = PanelModel.tab(tab, agent);
        String sig = signature(now);
        if (!force && sig.equals(drawn) && !pending) return;
        // Never under a selection: it waits, and draws once the selection ends (the tick checks).
        if (!force && SelectableText.anySelected(body)) { pending = true; return; }
        pending = false;
        drawn = sig;
        boolean tasks = agent != null && agent.hasTasks();
        tasksTab.setVisibility(tasks ? View.VISIBLE : View.GONE);
        styleTab(overviewTab, now == PanelModel.Tab.OVERVIEW);
        styleTab(tasksTab, now == PanelModel.Tab.TASKS);
        int y = force ? scrollOf[now.ordinal()] : scroll.getScrollY();
        body.removeAllViews();
        showAge("", true);
        if (agent == null) body.addView(dim("Open an agent to see its overview and tasks."));
        else if (now == PanelModel.Tab.OVERVIEW) overview();
        else tasks();
        scroll.post(() -> scroll.scrollTo(0, y));
    }

    private String signature(PanelModel.Tab now) {
        StringBuilder s = new StringBuilder(now.name()).append('|').append(agent == null ? "" : agent.key()).append('|').append(hostId);
        String key = docKey();
        DocModel.Read d = key == null ? null : docs.get(key);
        if (d != null) s.append('|').append(d.key).append('|').append(DocModel.freshness(d).text);
        if (key != null) s.append('|').append(docErrors.get(key));
        if (now == PanelModel.Tab.TASKS) {
            for (Map.Entry<String, BeadsModel.Store> e : stores.entrySet()) s.append('|').append(e.getKey()).append(e.getValue().fp);
            s.append('|').append(storesError).append(folded).append(open).append(storesKeptAt);
            long nowMs = System.currentTimeMillis();
            for (String id : open) {
                String bead = beadKey(id);
                BeadCache.Entry kept = cache.get(bead);
                s.append('|').append(kept == null ? "-" : kept.atMs + BeadCache.age(kept.atMs, nowMs));
                s.append(reading.equals(bead)).append(beadErrors.get(bead));
            }
        }
        return s.toString();
    }

    private void styleTab(TextView view, boolean selected) {
        view.setTextColor(selected ? chrome.onSurface : chrome.onSurfaceVariant);
        view.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        // The tab on screen: a 2dp accent underline under its label (review P1-4).
        android.graphics.drawable.GradientDrawable line = new android.graphics.drawable.GradientDrawable();
        line.setColor(chrome.primary);
        android.graphics.drawable.LayerDrawable under = new android.graphics.drawable.LayerDrawable(
                new android.graphics.drawable.Drawable[] {line});
        under.setLayerGravity(0, Gravity.BOTTOM | Gravity.FILL_HORIZONTAL);
        under.setLayerHeight(0, dp(2));
        view.setBackground(selected ? under : null);
        view.setSelected(selected);
        view.setContentDescription(view.getText() + (selected ? ", selected tab" : ", tab"));
    }

    private void overview() {
        String name = agent.doc();
        if (name == null) {
            body.addView(heading("No overview yet"));
            body.addView(dim(agent.label + " has no project or name to keep a doc by."));
            return;
        }
        String key = docKey();
        DocModel.Read d = docs.get(key);
        String error = docErrors.get(key);
        if (d == null) {
            body.addView(dim(error != null ? "Can't read the overview: " + error : "Reading " + DocModel.path(name) + "…"));
            return;
        }
        if (d.missing) {
            body.addView(heading("No overview yet"));
            body.addView(dim(agent.label + " hasn't written its doc. It would live at " + DocModel.path(name) + " on " + machine + "."));
            return;
        }
        DocModel.Freshness f = DocModel.freshness(d);
        // Its age sits in the tab row, not a row of its own (review P1-4).
        showAge(f.text + (error != null ? " · can't read it now" : ""), f.level == DocModel.Level.OK && error == null);
        String header = DocModel.header(agent.label + "'s overview", name, machine, d.rev, d.rev, d.mtime);
        body.addView(markdown(d.text, header));
    }

    private void tasks() {
        Map<String, BeadsModel.ProjectBeads> byProject = BeadsModel.projectBeads(stores);
        BeadsModel.ProjectBeads mine = byProject.get(agent.project);
        useKeptStores();
        if (stores.isEmpty() && storesAt < 0 && storesError == null) { body.addView(dim("Reading " + agent.project + "'s beads…")); return; }
        if (storesKeptAt > 0) body.addView(dim("beads " + BeadCache.age(storesKeptAt, System.currentTimeMillis()) + " · refreshing…"));
        if (storesError != null) body.addView(warn("Can't read the beads now: " + storesError + (mine != null ? " · showing the last read" : "")));
        if (mine == null || mine.beads == null || mine.beads.isEmpty()) {
            body.addView(dim(mine != null && mine.error != null ? mine.error : "No epics or beads in " + agent.project + "'s store."));
            return;
        }
        List<BeadsModel.Node> roots = BeadsModel.beadTree(mine.beads);
        // A worker's own bead first: its parents unfolded, its plan open, scrolled to it.
        String own = agent.bead;
        if (own != null && !own.equals(ownOpened)) {
            ownOpened = own;
            for (String p : PanelModel.parentsOf(roots, own)) folded.remove(p);
            if (open.add(own)) openedAt.put(own, SystemClock.elapsedRealtime());
            main.post(tick);
        }
        View ownRow = null;
        visible.clear();
        for (PanelModel.Row r : PanelModel.rows(roots, folded, open)) {
            if (!"closed".equals(r.node.bead.status) && !r.epic && visible.size() < PREFETCH) visible.add(r.node.bead.id);
            View row = row(r);
            body.addView(row);
            if (r.node.bead.id.equals(own)) ownRow = row;
            if (r.open) body.addView(plan(r));
            if (r.closedAfter > 0) {
                TextView closed = dim(r.closedAfter + " closed");
                closed.setPadding(dp(12 + 16 * (r.depth + 1) + 30), 0, 0, dp(4));
                body.addView(closed);
            }
        }
        View target = ownRow;
        if (target != null && scrollOf[1] == 0) scroll.post(() -> scroll.scrollTo(0, target.getTop()));
    }

    private View row(PanelModel.Row r) {
        LinearLayout row = new LinearLayout(activity);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(44));
        row.setPadding(dp(16 * r.depth), 0, 0, 0);
        boolean kids = !r.node.children.isEmpty() || r.node.closed > 0;
        RailDisclosure fold = new RailDisclosure(activity, chrome);
        fold.bind(kids, r.folded, kids ? (r.folded ? "Show" : "Hide") + " its beads" : null);
        fold.setOnClickListener(v -> {
            if (!folded.remove(r.node.bead.id)) folded.add(r.node.bead.id);
            redraw(true);
        });
        row.addView(fold, new LinearLayout.LayoutParams(dp(36), dp(44)));
        View mark = new View(activity);
        String status = r.node.bead.status;
        mark.setBackgroundColor("closed".equals(status) ? GpuiChrome.withAlpha(chrome.onSurfaceVariant, 0x66)
                : "in_progress".equals(status) ? chrome.status("working") : chrome.status("idle"));
        LinearLayout.LayoutParams markAt = new LinearLayout.LayoutParams(dp(6), dp(6));
        markAt.setMarginEnd(dp(8));
        row.addView(mark, markAt);
        LinearLayout words = new LinearLayout(activity);
        words.setOrientation(LinearLayout.VERTICAL);
        TextView title = ActionSheet.label(activity, r.node.bead.title, r.epic ? 14 : 13, chrome.onSurface);
        title.setMaxLines(3);
        title.setEllipsize(TextUtils.TruncateAt.END);
        if (r.epic) title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        words.addView(title);
        if (!r.progress.isEmpty()) words.addView(dim(r.progress));
        row.addView(words, new LinearLayout.LayoutParams(0, -2, 1));
        row.setBackground(ActionSheet.ripple(chrome));
        String id = r.node.bead.id;
        List<FleetTree.Member> on = PanelModel.assignees(members, id, agent.project);
        row.setContentDescription(r.node.bead.title + ", " + status.replace('_', ' ')
                + (r.progress.isEmpty() ? "" : ", " + r.progress)
                + (on.size() == 1 ? ". Tap to read its plan and show the agent on it" : ". Tap to read its plan"));
        // The title opens the plan (it stays open) and shows the one agent clearly on it; the chevron only folds.
        row.setOnClickListener(v -> {
            open.add(id);
            // Timed from this tap, also for a bead already open (its first draw after the tap is logged).
            openedAt.put(id, SystemClock.elapsedRealtime());
            loggedPlan.remove(id);
            loggedComments.remove(id);
            redraw(true);
            main.post(tick);
            if (on.size() == 1) host.focus(on.get(0).id);
        });
        if (r.open) {
            TextView shut = ActionSheet.label(activity, "▴", 14, chrome.onSurfaceVariant);
            shut.setGravity(Gravity.CENTER);
            shut.setBackground(ActionSheet.ripple(chrome));
            shut.setContentDescription("Close its plan");
            shut.setOnClickListener(v -> {
                open.remove(id);
                openedAt.remove(id);
                redraw(true);
            });
            row.addView(shut, new LinearLayout.LayoutParams(dp(44), dp(44)));
        }
        return row;
    }

    private View plan(PanelModel.Row r) {
        LinearLayout box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(16 * r.depth + 44), dp(2), 0, dp(10));
        String id = r.node.bead.id, key = beadKey(id);
        // Who works on it, when it is more than one: named, each a tap away (one is focused on the tap itself).
        List<FleetTree.Member> on = PanelModel.assignees(members, id, agent.project);
        if (on.size() > 1) box.addView(workedOnBy(on));
        BeadCache.Entry kept = cache.get(key);
        boolean busy = reading.equals(key);
        if (kept == null) {
            String error = beadErrors.get(key);
            box.addView(error != null ? warn("Can't read it: " + error) : dim("Reading its plan…"));
            return box;
        }
        BeadsModel.Detail d = BeadCache.detail(kept.text);
        if (d.error != null) { box.addView(warn(d.error)); return box; }
        String doc = PanelModel.plan(d);
        String header = DocModel.beadHeader(id, d.title, null, null, d.updatedAt, d.rev);
        if (doc.isEmpty()) box.addView(dim(PanelModel.noPlan(r.node)));
        else box.addView(markdown(doc, header));
        logFirst(loggedPlan, id, "plan", kept);
        BeadsModel.Comments c = BeadCache.comments(kept.text);
        if (c.error != null) box.addView(warn("Can't read its comments: " + c.error));
        if (!c.list.isEmpty()) {
            box.addView(heading("Comments"));
            for (BeadsModel.Comment comment : c.list) {
                box.addView(dim(PanelModel.byLine(comment)));
                box.addView(markdown(comment.text, DocModel.beadHeader(id, d.title, comment.author, comment.at, d.updatedAt, d.rev)));
            }
        }
        if (c.error == null) logFirst(loggedComments, id, "comments", kept);
        // Honest freshness: how old this read is, and whether a newer one is on its way.
        String age = BeadCache.age(kept.atMs, System.currentTimeMillis())
                + (d.maybeStale ? " · from the store's export" : "") + (busy ? " · refreshing…" : "");
        box.addView(dim(age));
        return box;
    }

    private View workedOnBy(List<FleetTree.Member> on) {
        LinearLayout line = new LinearLayout(activity);
        line.setGravity(Gravity.CENTER_VERTICAL);
        line.addView(dim("Worked on by"));
        for (FleetTree.Member m : on) {
            String name = AgentNames.unnamed(m) ? AgentNames.UNNAMED : FleetPanes.displayTitle(m);
            TextView who = ActionSheet.label(activity, name, 12, chrome.primary);
            who.setPadding(dp(8), 0, dp(8), 0);
            who.setMinHeight(dp(40));
            who.setGravity(Gravity.CENTER_VERTICAL);
            who.setBackground(ActionSheet.ripple(chrome));
            who.setContentDescription("Show " + name);
            who.setOnClickListener(v -> host.focus(m.id));
            line.addView(who);
        }
        return line;
    }

    /** "panel.bead demo-1.2 plan 4 ms after the tap (kept 3m)": the first draw of each part after an open. */
    private void logFirst(Set<String> logged, String id, String part, BeadCache.Entry kept) {
        Long at = openedAt.get(id);
        if (at == null || !logged.add(id)) return;
        long ms = SystemClock.elapsedRealtime() - at;
        long age = Math.max(0, System.currentTimeMillis() - kept.atMs) / 1000;
        android.util.Log.i("herdr-ops", "panel.bead " + id + " " + part + " " + ms + " ms after the tap ("
                + (age < 5 ? "read now" : "kept " + UsageModel.span(age)) + ")");
    }

    /** Markdown as one selectable text; Quote carries the source of the blocks selected, under {@code header}. */
    private View markdown(String src, String header) {
        Map<String, BeadsModel.ProjectBeads> byProject = BeadsModel.projectBeads(stores);
        Set<String> ids = new HashSet<>();
        for (BeadsModel.ProjectBeads p : byProject.values()) if (p.beads != null) for (BeadsModel.Bead b : p.beads) ids.add(b.id);
        MdText md = MdText.of(src, ids::contains);
        SelectableText view = new SelectableText(activity, host::note);
        view.setQuote((start, end) -> host.quote(header, DocModel.quoteBody(md.sourceOf(start, end))));
        view.setText(spans(md), TextView.BufferType.SPANNABLE);
        view.setTextColor(chrome.onSurface);
        view.setTextSize(BODY_SP);
        if (android.os.Build.VERSION.SDK_INT >= 28) view.setLineHeight(dp(LINE_DP));
        else view.setLineSpacing(0, 1.4f);
        view.setPadding(0, dp(2), 0, dp(4));
        return view;
    }

    private CharSequence spans(MdText md) {
        SpannableStringBuilder s = new SpannableStringBuilder(md.text());
        int dim = chrome.raised;
        for (MdText.Style st : md.styles) {
            if (st.end <= st.start && st.kind != MdText.Style.Kind.RULE) continue;
            int a = st.start, b = st.end, flags = Spanned.SPAN_EXCLUSIVE_EXCLUSIVE;
            switch (st.kind) {
                case HEADING: {
                    int level = Integer.parseInt(st.value);
                    // 18sp and 16sp over the 14sp body; deeper headings at the body's size, bold.
                    s.setSpan(new RelativeSizeSpan(level == 1 ? 18f / BODY_SP : level == 2 ? 16f / BODY_SP : 1f), a, b, flags);
                    s.setSpan(new StyleSpan(Typeface.BOLD), a, b, flags);
                    break;
                }
                case BOLD: s.setSpan(new StyleSpan(Typeface.BOLD), a, b, flags); break;
                case EM: s.setSpan(new StyleSpan(Typeface.ITALIC), a, b, flags); break;
                case CODE:
                    s.setSpan(new TypefaceSpan("monospace"), a, b, flags);
                    s.setSpan(new BackgroundColorSpan(dim), a, b, flags);
                    break;
                case PRE:
                    s.setSpan(new TypefaceSpan("monospace"), a, b, flags);
                    s.setSpan(new RelativeSizeSpan(0.9f), a, b, flags);
                    break;
                case QUOTE: s.setSpan(new QuoteSpan(GpuiChrome.withAlpha(chrome.onSurfaceVariant, 0x88)), a, b, flags); break;
                case BULLET: {
                    // Wrapped lines hang at the item's text, just past its "• " or "1. ".
                    int first = dp(14 * Integer.parseInt(st.value));
                    String marker = md.text().substring(a, Math.min(b, md.text().indexOf(' ', a) + 1));
                    s.setSpan(new LeadingMarginSpan.Standard(first, first + Math.round(bodyPaint().measureText(marker))), a, b, flags);
                    break;
                }
                case RULE: s.setSpan(new ForegroundColorSpan(GpuiChrome.withAlpha(chrome.onSurfaceVariant, 0x88)), a, b, flags); break;
                default: s.setSpan(link(st), a, b, flags); break;
            }
        }
        // The blank line between blocks is a gap, not a full line.
        String t = md.text();
        // 20dp above a heading, 8dp between other blocks (the gap line is sized; review P1-4).
        Set<Integer> headings = new HashSet<>();
        for (MdText.Block b : md.blocks) if ("heading".equals(b.kind)) headings.add(b.start);
        for (int i = t.indexOf("\n\n"); i >= 0; i = t.indexOf("\n\n", i + 2)) {
            s.setSpan(new AbsoluteSizeSpan(dp(headings.contains(i + 2) ? 18 : 8)), i + 1, i + 2, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        return s;
    }

    private ClickableSpan link(MdText.Style st) {
        return new ClickableSpan() {
            @Override public void onClick(View widget) {
                switch (st.kind) {
                    case BEAD: openBead(st.value); break;
                    case LOCAL: host.openPath(st.value); break;
                    default: host.openLink(st.value); break;
                }
            }

            @Override public void updateDrawState(TextPaint paint) {
                paint.setColor(chrome.primary);
                paint.setUnderlineText(st.kind != MdText.Style.Kind.BEAD);
            }
        };
    }

    /** A bead named in a doc: shown in Tasks, its parents unfolded and its plan open. */
    private void openBead(String id) {
        if (agent == null || !agent.hasTasks()) return;
        scrollOf[tab.ordinal()] = scroll.getScrollY();
        tab = PanelModel.Tab.TASKS;
        Map<String, BeadsModel.ProjectBeads> byProject = BeadsModel.projectBeads(stores);
        BeadsModel.ProjectBeads mine = byProject.get(agent.project);
        if (mine != null && mine.beads != null) for (String p : PanelModel.parentsOf(BeadsModel.beadTree(mine.beads), id)) folded.remove(p);
        if (open.add(id)) openedAt.put(id, SystemClock.elapsedRealtime());
        redraw(true);
        main.post(tick);
    }

    private void showAge(String text, boolean ok) {
        age.setText(text);
        age.setTextColor(ok ? chrome.onSurfaceVariant : chrome.warning);
        age.setVisibility(text.isEmpty() ? View.GONE : View.VISIBLE);
    }

    private TextPaint bodyPaint() {
        TextPaint paint = new TextPaint();
        paint.setTextSize(BODY_SP * activity.getResources().getDisplayMetrics().scaledDensity);
        return paint;
    }

    private TextView heading(String text) {
        TextView view = ActionSheet.label(activity, text, 14, chrome.onSurface);
        view.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        view.setPadding(0, dp(12), 0, dp(6));
        if (android.os.Build.VERSION.SDK_INT >= 28) view.setAccessibilityHeading(true);
        return view;
    }

    private TextView dim(String text) {
        TextView view = ActionSheet.label(activity, text, 12, chrome.onSurfaceVariant);
        view.setPadding(0, dp(2), 0, dp(2));
        return view;
    }

    private TextView warn(String text) {
        TextView view = dim(text);
        view.setTextColor(chrome.warning);
        return view;
    }

    private int dp(float value) {
        return ActionSheet.dp(activity, value);
    }
}
