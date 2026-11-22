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
    private final Map<String, BeadsModel.Detail> details = new HashMap<>();
    private final Map<String, BeadsModel.Comments> comments = new HashMap<>();
    private final Set<String> folded = new HashSet<>(), open = new HashSet<>(), confirmed = new HashSet<>();
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

    WorkPanel(Activity activity, GpuiChrome chrome, Host host) {
        this.activity = activity;
        this.chrome = chrome;
        this.host = host;
        root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(chrome.surface);
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
        close = ActionSheet.label(activity, "×", 20, chrome.onSurfaceVariant);
        close.setGravity(Gravity.CENTER);
        close.setBackground(ActionSheet.ripple(chrome));
        close.setContentDescription("Close the work panel");
        close.setOnClickListener(v -> host.closed());
        tabs.addView(close, new LinearLayout.LayoutParams(dp(48), dp(48)));
        root.addView(tabs, new LinearLayout.LayoutParams(-1, -2));
        View rule = new View(activity);
        rule.setBackgroundColor(GpuiChrome.withAlpha(chrome.onSurfaceVariant, 0x33));
        root.addView(rule, new LinearLayout.LayoutParams(-1, 1));
        scroll = new ScrollView(activity);
        body = new LinearLayout(activity);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(12), dp(8), dp(12), dp(16));
        scroll.addView(body, new FrameLayout.LayoutParams(-1, -2));
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
    }

    View view() {
        return root;
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

    /** The agent on screen, and its machine (the saved host and its name). Cheap when unchanged. */
    void select(PanelModel.Agent next, String hostId, String machine) {
        String was = agent == null ? "" : agent.key() + this.hostId;
        String now = next == null ? "" : next.key() + (hostId == null ? "" : hostId);
        this.machine = machine == null ? "" : machine;
        if (was.equals(now)) return;
        if (!this.hostId.equals(hostId == null ? "" : hostId)) {
            stores = new HashMap<>();
            storesAt = -1;
            storesError = null;
            details.clear();
            comments.clear();
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
            if (!hostId.equals(storesHost) || due(storesAt, STORES_MS, now)) { readStores(); return; }
            for (String id : open) {
                if (!details.containsKey(id)) { readDetail(id, false); return; }
                BeadsModel.Detail d = details.get(id);
                if (d.error == null && d.maybeStale && confirmed.add(id)) { readDetail(id, true); return; }
                Integer count = d.commentCount;
                if (d.error == null && !comments.containsKey(id) && (count == null || count > 0)) { readComments(id); return; }
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
        });
    }

    private void readDetail(String id, boolean confirm) {
        String store = BeadsModel.storeOf(agent.project, id, stores);
        JSONObject op = json("beads.detail");
        put(op, "project", store == null ? agent.project : store);
        put(op, "id", id);
        if (confirm) put(op, "confirm", true);
        run(op, reply -> {
            String text = textOf(reply);
            BeadsModel.Detail d = text == null ? null : BeadsModel.parseDetail(text);
            if (d == null) {
                d = new BeadsModel.Detail();
                d.error = errorOf(reply);
            }
            // A confirm that failed keeps the export's copy.
            if (confirm && d.error != null && details.containsKey(id)) return;
            details.put(id, d);
        });
    }

    private void readComments(String id) {
        String store = BeadsModel.storeOf(agent.project, id, stores);
        JSONObject op = json("beads.comments");
        put(op, "project", store == null ? agent.project : store);
        put(op, "id", id);
        run(op, reply -> {
            String text = textOf(reply);
            BeadsModel.Comments c = text == null ? null : BeadsModel.parseComments(text);
            if (c == null) {
                c = new BeadsModel.Comments();
                c.error = errorOf(reply);
            }
            comments.put(id, c);
        });
    }

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
        TextView view = ActionSheet.label(activity, label, 13, chrome.onSurfaceVariant);
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
            s.append('|').append(storesError).append(folded).append(open);
            for (String id : open) {
                BeadsModel.Detail det = details.get(id);
                s.append('|').append(det == null ? "-" : det.rev + det.maybeStale + det.error + det.updatedAt);
                s.append(comments.containsKey(id) ? comments.get(id).list.size() : -1);
            }
        }
        return s.toString();
    }

    private void styleTab(TextView view, boolean selected) {
        view.setTextColor(selected ? chrome.onSurface : chrome.onSurfaceVariant);
        view.setTypeface(Typeface.create("sans-serif-medium", selected ? Typeface.BOLD : Typeface.NORMAL));
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
        TextView age = dim(f.text + (error != null ? " · can't read it now: " + error : ""));
        age.setGravity(Gravity.END);
        age.setTextColor(f.level == DocModel.Level.OK && error == null ? chrome.onSurfaceVariant : chrome.warning);
        body.addView(age);
        String header = DocModel.header(agent.label + "'s overview", name, machine, d.rev, d.rev, d.mtime);
        body.addView(markdown(d.text, header));
    }

    private void tasks() {
        Map<String, BeadsModel.ProjectBeads> byProject = BeadsModel.projectBeads(stores);
        BeadsModel.ProjectBeads mine = byProject.get(agent.project);
        if (storesAt < 0 && storesError == null) { body.addView(dim("Reading " + agent.project + "'s beads…")); return; }
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
            open.add(own);
            main.post(tick);
        }
        View ownRow = null;
        for (PanelModel.Row r : PanelModel.rows(roots, folded, open)) {
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
        row.setContentDescription(r.node.bead.title + ", " + status.replace('_', ' ')
                + (r.progress.isEmpty() ? "" : ", " + r.progress) + (r.open ? ". Tap to close its plan" : ". Tap to read its plan"));
        row.setOnClickListener(v -> {
            String id = r.node.bead.id;
            if (!open.remove(id)) open.add(id);
            redraw(true);
            main.post(tick);
        });
        return row;
    }

    private View plan(PanelModel.Row r) {
        LinearLayout box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(16 * r.depth + 44), dp(2), 0, dp(10));
        String id = r.node.bead.id;
        BeadsModel.Detail d = details.get(id);
        if (d == null) { box.addView(dim("Reading its plan…")); return box; }
        if (d.error != null) { box.addView(warn(d.error)); return box; }
        String doc = PanelModel.plan(d);
        String header = DocModel.beadHeader(id, d.title, null, null, d.updatedAt, d.rev);
        if (doc.isEmpty()) box.addView(dim(PanelModel.noPlan(r.node)));
        else box.addView(markdown(doc, header));
        if (d.maybeStale) box.addView(dim("from the store's export · checking it…"));
        BeadsModel.Comments c = comments.get(id);
        if (c != null && c.error != null) box.addView(warn("Can't read its comments: " + c.error));
        if (c != null && !c.list.isEmpty()) {
            box.addView(heading("Comments"));
            for (BeadsModel.Comment comment : c.list) {
                box.addView(dim(PanelModel.byLine(comment)));
                box.addView(markdown(comment.text, DocModel.beadHeader(id, d.title, comment.author, comment.at, d.updatedAt, d.rev)));
            }
        }
        return box;
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
        view.setTextSize(13);
        view.setLineSpacing(0, 1.2f);
        view.setPadding(0, dp(2), 0, dp(4));
        return view;
    }

    private CharSequence spans(MdText md) {
        SpannableStringBuilder s = new SpannableStringBuilder(md.text());
        int dim = GpuiChrome.withAlpha(chrome.onSurfaceVariant, 0x33);
        for (MdText.Style st : md.styles) {
            if (st.end <= st.start && st.kind != MdText.Style.Kind.RULE) continue;
            int a = st.start, b = st.end, flags = Spanned.SPAN_EXCLUSIVE_EXCLUSIVE;
            switch (st.kind) {
                case HEADING: {
                    int level = Integer.parseInt(st.value);
                    s.setSpan(new RelativeSizeSpan(level == 1 ? 1.3f : level == 2 ? 1.18f : 1.08f), a, b, flags);
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
                case BULLET: s.setSpan(new LeadingMarginSpan.Standard(dp(14 * Integer.parseInt(st.value)), dp(14 * Integer.parseInt(st.value) + 12)), a, b, flags); break;
                case RULE: s.setSpan(new ForegroundColorSpan(GpuiChrome.withAlpha(chrome.onSurfaceVariant, 0x88)), a, b, flags); break;
                default: s.setSpan(link(st), a, b, flags); break;
            }
        }
        // The blank line between blocks is a gap, not a full line.
        String t = md.text();
        for (int i = t.indexOf("\n\n"); i >= 0; i = t.indexOf("\n\n", i + 2)) s.setSpan(new AbsoluteSizeSpan(dp(8)), i + 1, i + 2, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
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
        open.add(id);
        redraw(true);
        main.post(tick);
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
