package dev.herdr.android;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Typeface;
import android.os.Handler;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.InputFilter;
import android.text.style.ForegroundColorSpan;
import android.text.style.BackgroundColorSpan;
import android.view.View;
import android.view.ViewGroup;
import android.widget.*;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Native Android review surface. SSH and parsing stay off the UI thread. */
final class DiffReviewPanel extends LinearLayout {
    private final Activity activity;
    private GpuiChrome colors;
    private final Handler main = new Handler(android.os.Looper.getMainLooper());
    private final ScheduledExecutorService io = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "diffd-mobile"); t.setDaemon(true); return t;
    });
    private final AtomicBoolean pending = new AtomicBoolean();
    private final DiffReviewModel model = new DiffReviewModel();
    private final TextView status, location;
    private final Button retry, compose, previous, next;
    private final LinearLayout pagination;
    private final ListView list;
    private final Rows adapter = new Rows();
    private final List<Row> rows = new ArrayList<>();
    private volatile long epoch, handle, watch;
    private enum Section {
        FILES("Files"), THREADS("Threads"), CHAT("Chat");
        final String label;
        Section(String label) { this.label = label; }
    }
    private String review = "", path = "";
    private Section section = Section.FILES;
    private boolean showing, ready, stopped;
    private int page;
    private ScheduledFuture<?> polling;
    private AlertDialog draft;
    private final java.util.Set<String> awaiting = new java.util.HashSet<>();
    private static final int PAGE = 160;
    private static final class Row {
        final CharSequence text;
        final int background;
        final JSONObject anchor, thread;
        Row(CharSequence text, int background, JSONObject anchor, JSONObject thread) {
            this.text = text; this.background = background; this.anchor = anchor; this.thread = thread;
        }
    }

    DiffReviewPanel(Activity activity, GpuiChrome colors) {
        super(activity); this.activity = activity; this.colors = colors;
        setOrientation(VERTICAL); setBackgroundColor(colors.background);
        setClickable(true); setFocusable(true);
        LinearLayout tabs = new LinearLayout(activity);
        for (Section destination : Section.values()) {
            Button b = button(destination.label); b.setOnClickListener(v -> { section = destination; if (destination == Section.FILES) path = ""; page = 0; render(); });
            tabs.addView(b, new LayoutParams(0, dp(40), 1));
        }
        addView(tabs);
        LinearLayout health = new LinearLayout(activity); health.setGravity(android.view.Gravity.CENTER_VERTICAL);
        status = label(12); status.setSingleLine(true); status.setEllipsize(android.text.TextUtils.TruncateAt.END);
        health.addView(status, new LayoutParams(0, dp(32), 1));
        retry = button("Reconnect"); retry.setOnClickListener(v -> start(handle, review));
        health.addView(retry, new LayoutParams(-2, dp(36))); addView(health);
        location = label(12); location.setSingleLine(true); location.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        addView(location, new LayoutParams(-1, dp(32)));
        list = new ListView(activity); list.setDivider(null); list.setAdapter(adapter);
        list.setOnItemClickListener((p, v, index, id) -> {
            Row row = rows.get(index);
            if (!ready) return;
            if (row.anchor != null) comment(row.anchor);
            else if (row.thread != null) thread(row.thread);
            else if ((section == Section.FILES) && path.isEmpty()) {
                path = model.state.optJSONObject("snapshot").optJSONArray("files").optJSONObject(index).optString("path");
                page = 0; render(); list.setSelection(0);
            }
        });
        addView(list, new LayoutParams(-1, 0, 1));
        pagination = new LinearLayout(activity);
        previous = button("‹ Previous"); next = button("Next ›");
        previous.setOnClickListener(v -> { page = Math.max(0, page - 1); render(); list.setSelection(0); });
        next.setOnClickListener(v -> { page++; render(); list.setSelection(0); });
        pagination.addView(previous, new LayoutParams(0, dp(40), 1)); pagination.addView(next, new LayoutParams(0, dp(40), 1));
        addView(pagination);
        compose = button("Message agent"); compose.setOnClickListener(v -> message("Chat", body -> new JSONObject().put("type", "chat").put("messageId", UUID.randomUUID().toString()).put("body", body)));
        addView(compose, new LayoutParams(-1, dp(44)));
        setVisibility(GONE);
    }
    void theme(GpuiChrome next) {
        colors = next; setBackgroundColor(next.background);
        tint(this); render();
    }
    private void tint(View view) {
        if (view instanceof Button) {
            ((Button) view).setTextColor(colors.primary);
            view.setBackgroundTintList(android.content.res.ColorStateList.valueOf(colors.control));
        } else if (view instanceof TextView) ((TextView) view).setTextColor(colors.onSurface);
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) tint(group.getChildAt(i));
        }
    }
    private int dp(int n) { return Math.round(n * getResources().getDisplayMetrics().density); }
    private TextView label(int size) {
        TextView t = new TextView(activity); t.setTextColor(colors.onSurface); t.setTextSize(size);
        t.setGravity(android.view.Gravity.CENTER_VERTICAL); t.setPadding(dp(10), 0, dp(10), 0); return t;
    }
    private Button button(String text) {
        Button b = new Button(activity); b.setText(text); b.setAllCaps(false); b.setTextSize(12);
        b.setTextColor(colors.primary); b.setBackgroundTintList(android.content.res.ColorStateList.valueOf(colors.control));
        b.setMinWidth(0); b.setMinimumWidth(0); b.setPadding(dp(8), 0, dp(8), 0); return b;
    }
    void bind(long newHandle, String id) {
        if (newHandle == handle && id.equals(review)) return;
        if (draft != null) draft.dismiss();
        stopWatch(); handle = newHandle; review = id; path = ""; page = 0; model.state = null;
        rows.clear(); adapter.notifyDataSetChanged(); status.setText(""); ready = false;
        if (showing && DiffReviewModel.validId(id)) start(newHandle, id);
    }
    void showReview(boolean show) {
        showing = show; setVisibility(show ? VISIBLE : GONE);
        if (!show) { if (draft != null) draft.dismiss(); stopWatch(); }
        else if (watch == 0 && DiffReviewModel.validId(review)) start(handle, review);
    }
    private void start(long target, String id) {
        stopWatch(); if (stopped || target == 0 || !DiffReviewModel.validId(id)) return;
        final long token = ++epoch;
        ready = false; compose.setEnabled(false); status.setText("Connecting…"); retry.setVisibility(INVISIBLE);
        io.execute(() -> {
            try {
                long opened = new JSONObject(RemoteNative.reviewWatch(target, id)).getLong("watch");
                main.post(() -> {
                    if (token != epoch || !showing) { RemoteNative.reviewStop(target, opened); return; }
                    watch = opened;
                    polling = io.scheduleWithFixedDelay(this::poll, 0, 150, TimeUnit.MILLISECONDS);
                });
            } catch (Exception e) { error(token, e.getMessage()); }
        });
    }
    private void stopWatch() {
        if (polling != null) { polling.cancel(false); polling = null; }
        epoch++; long old = watch, owner = handle; watch = 0; ready = false; awaiting.clear();
        if (old != 0) RemoteNative.reviewStop(owner, old);
    }
    void pause() { if (draft != null) draft.dismiss(); stopWatch(); }
    void close() { stopped = true; pause(); io.shutdownNow(); }
    private void poll() {
        long current = watch, token = epoch, owner = handle;
        if (current == 0 || !pending.compareAndSet(false, true)) return;
        try {
            JSONArray events = new JSONArray(RemoteNative.reviewPoll(owner, current));
            main.post(() -> {
                try {
                    if (token != epoch) return;
                    boolean changed = false, acknowledged = false;
                    for (int i = 0; i < events.length(); i++) {
                        JSONObject event = events.getJSONObject(i); String type = event.optString("type");
                        if ("transportError".equals(type) || "error".equals(type) || "gone".equals(type)) {
                            error(token, event.optString("message", "Review connection closed")); return;
                        }
                        if ("ack".equals(type)) acknowledged |= awaiting.remove(event.optString("id"));
                        changed |= model.apply(event);
                    }
                    if (changed) { ready = true; compose.setEnabled(true); retry.setVisibility(INVISIBLE); render(); }
                    if (changed || acknowledged) status.setText(!awaiting.isEmpty() ? "Waiting for acknowledgement" : acknowledged ? "Sent · Live" : "Live");
                } catch (Exception e) { error(token, e.getMessage()); }
                finally { pending.set(false); }
            });
        } catch (Exception e) { pending.set(false); error(token, e.getMessage()); }
    }
    private void error(long token, String message) {
        main.post(() -> {
            if (token != epoch) return;
            stopWatch(); status.setText(message == null ? "Review connection closed" : shortLabel(message));
            retry.setVisibility(VISIBLE); compose.setEnabled(false);
        });
    }
    private void render() {
        if (model.state == null) return;
        int position = list.getFirstVisiblePosition(); View first = list.getChildAt(0); int offset = first == null ? 0 : first.getTop();
        rows.clear();
        try {
            if ((section == Section.FILES)) files();
            else if ((section == Section.THREADS)) threads();
            else chat();
            pagination.setVisibility((section == Section.FILES) && !path.isEmpty() ? VISIBLE : GONE);
            compose.setVisibility((section == Section.CHAT) ? VISIBLE : GONE);
            location.setText(shortLabel((section == Section.FILES) && !path.isEmpty() ? path : model.state.getJSONObject("review").optString("title")));
        } catch (Exception e) { status.setText("Invalid review: " + e.getMessage()); ready = false; }
        adapter.notifyDataSetChanged(); list.setSelectionFromTop(position, offset);
    }
    private void files() throws Exception {
        JSONArray files = model.state.getJSONObject("snapshot").getJSONArray("files");
        JSONObject selected = null;
        for (int i = 0; i < files.length(); i++) {
            JSONObject file = files.getJSONObject(i);
            if (path.isEmpty()) rows.add(new Row(shortLabel(file.getString("path")) + "   +" + file.optInt("added") + " −" + file.optInt("removed"), colors.background, null, null));
            if (path.equals(file.optString("path"))) selected = file;
        }
        if (path.isEmpty()) return;
        if (selected == null) { path = ""; files(); return; }
        if (!selected.isNull("omitted")) { rows.add(new Row("Contents omitted: " + selected.optString("omitted"), colors.background, null, null)); return; }
        JSONArray aligned = selected.getJSONArray("rows");
        page = Math.min(page, Math.max(0, (aligned.length() - 1) / PAGE));
        int end = Math.min(aligned.length(), (page + 1) * PAGE);
        previous.setEnabled(page > 0); next.setEnabled(end < aligned.length());
        previous.setText("‹ " + (page * PAGE + 1) + "–" + end + " / " + aligned.length());
        for (int i = page * PAGE; i < end; i++) {
            JSONArray row = aligned.getJSONArray(i);
            int old = row.isNull(0) ? -1 : row.getInt(0), fresh = row.isNull(1) ? -1 : row.getInt(1);
            JSONObject left = selected.optJSONObject("old"), right = selected.optJSONObject("new");
            String oldText = line(left, old), newText = line(right, fresh);
            if (old >= 0 && fresh >= 0 && oldText.equals(newText)) code(right, fresh, "new", " ", 0);
            else {
                if (old >= 0) code(left, old, "old", "−", colors.danger);
                if (fresh >= 0) code(right, fresh, "new", "+", colors.success);
            }
        }
    }
    private String line(JSONObject side, int n) {
        return side == null || n < 0 ? "" : side.optJSONArray("lines").optString(n);
    }
    private void code(JSONObject side, int n, String which, String sign, int color) throws Exception {
        String source = line(side, n);
        String prefix = String.format(java.util.Locale.ROOT, "%5d %s ", n + 1, sign);
        // Android indices and diffd syntax offsets are both UTF-16 code units.
        String visible = source.length() > 8000 ? source.substring(0, 8000) + "…" : source;
        SpannableString text = new SpannableString(prefix + visible);
        JSONArray syntax = side.optJSONArray("syntax"); JSONArray runs = syntax == null ? null : syntax.optJSONArray(n);
        // Class indices follow diffd SyntaxClass::ALL; unknown classes keep the base text color.
        int[] syntaxColors = {colors.primary, colors.success, colors.onSurfaceVariant, colors.warning, colors.warning, colors.info, colors.info, colors.onSurface, colors.onSurface, colors.primary, colors.primary, colors.onSurfaceVariant, colors.onSurfaceVariant, colors.info, colors.warning, colors.primary, colors.info};
        for (int i = 0; runs != null && i + 2 < runs.length(); i += 3) {
            int a = runs.optInt(i, -1), b = runs.optInt(i + 1, -1), c = runs.optInt(i + 2, -1);
            if (a >= 0 && a < b && b <= visible.length() && c >= 0 && c < syntaxColors.length)
                text.setSpan(new ForegroundColorSpan(syntaxColors[c]), prefix.length() + a, prefix.length() + b, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        JSONArray novel = side.optJSONArray("novel");
        JSONArray changes = novel == null ? null : novel.optJSONArray(n);
        int changed = "old".equals(which) ? colors.danger : colors.success;
        for (int i = 0; changes != null && i + 1 < changes.length(); i += 2) {
            int a = changes.optInt(i, -1), b = changes.optInt(i + 1, -1);
            if (a >= 0 && a < b && b <= visible.length())
                text.setSpan(new BackgroundColorSpan(GpuiChrome.withAlpha(changed, 70)), prefix.length() + a, prefix.length() + b, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        JSONObject anchor = new JSONObject().put("path", path).put("side", which).put("start", n + 1).put("end", n + 1).put("text", source).put("range", JSONObject.NULL);
        rows.add(new Row(text, color == 0 ? colors.background : GpuiChrome.withAlpha(color, 35), anchor, null));
    }
    private void threads() throws Exception {
        JSONArray threads = model.state.getJSONArray("threads");
        for (int i = 0; i < threads.length(); i++) {
            JSONObject t = threads.getJSONObject(i), a = t.getJSONObject("anchor");
            StringBuilder text = new StringBuilder(t.optBoolean("resolved") ? "✓ " : "● ");
            text.append(shortLabel(a.optString("path"))).append(':').append(a.optInt("start"));
            if (t.optBoolean("outdated")) text.append(" · outdated");
            if (!t.isNull("changedIn")) text.append(" · changed");
            JSONArray messages = t.getJSONArray("messages");
            for (int j = 0; j < messages.length() && text.length() < 16000; j++) { JSONObject m = messages.getJSONObject(j); text.append('\n').append(author(m)).append(": ").append(bounded(m.optString("body"))); }
            rows.add(new Row(bounded(text.toString()), colors.background, null, t));
        }
    }
    private void chat() throws Exception {
        JSONArray chat = model.state.getJSONArray("chat");
        for (int i = 0; i < chat.length(); i++) {
            JSONObject m = chat.getJSONObject(i);
            rows.add(new Row(author(m) + "\n" + bounded(m.optString("body")), colors.background, null, null));
        }
    }
    private static String shortLabel(String text) { return text.length() > 512 ? text.substring(0, 512) + "…" : text; }
    private static String author(JSONObject message) {
        String value = message.optString("author");
        return "agent".equals(value) ? "Agent" : "user".equals(value) ? "You" : "Unknown";
    }
    private static String bounded(String text) { return text.length() > 16000 ? text.substring(0, 16000) + "…" : text; }
    private void comment(JSONObject anchor) {
        if (anchor.optString("text").length() > 16000) { status.setText("This line exceeds the comment size limit"); return; }
        message(shortLabel(anchor.optString("path")) + ":" + anchor.optInt("start") + " · " + anchor.optString("side"), body -> new JSONObject().put("type", "comment").put("threadId", UUID.randomUUID().toString()).put("messageId", UUID.randomUUID().toString()).put("anchor", anchor).put("body", body));
    }
    private void thread(JSONObject thread) {
        new AlertDialog.Builder(activity, colors.dialogTheme()).setTitle("Thread")
            .setItems(new String[]{"Reply", thread.optBoolean("resolved") ? "Reopen" : "Resolve", "Show line"}, (d, which) -> {
                if (which == 0) message("Reply", body -> new JSONObject().put("type", "reply").put("threadId", thread.getString("id")).put("messageId", UUID.randomUUID().toString()).put("body", body));
                else if (which == 1) {
                    try { send(new JSONObject().put("type", "resolve").put("threadId", thread.getString("id")).put("resolved", !thread.optBoolean("resolved"))); } catch (Exception e) { status.setText(e.getMessage()); }
                } else {
                    JSONObject anchor = thread.optJSONObject("anchor");
                    path = anchor.optString("path"); section = Section.FILES; page = 0;
                    JSONArray files = model.state.optJSONObject("snapshot").optJSONArray("files");
                    for (int i = 0; i < files.length(); i++) {
                        JSONObject file = files.optJSONObject(i);
                        if (file == null || !path.equals(file.optString("path"))) continue;
                        JSONArray aligned = file.optJSONArray("rows"); int side = "old".equals(anchor.optString("side")) ? 0 : 1;
                        for (int j = 0; aligned != null && j < aligned.length(); j++) {
                            if (aligned.optJSONArray(j).optInt(side, -1) == anchor.optInt("start") - 1) { page = j / PAGE; break; }
                        }
                    }
                    render(); list.setSelection(0);
                }
            }).show();
    }
    private interface Body { JSONObject build(String text) throws Exception; }
    private void message(String title, Body body) {
        if (!ready) return;
        final long token = epoch;
        EditText input = new EditText(activity); input.setMinLines(3); input.setMaxLines(8);
        input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(12000)});
        input.setTextColor(colors.onSurface); input.setBackgroundTintList(android.content.res.ColorStateList.valueOf(colors.primary));
        LinearLayout wrap = new LinearLayout(activity); wrap.setPadding(dp(12), 0, dp(12), 0); wrap.addView(input, new LayoutParams(-1, -2));
        draft = new AlertDialog.Builder(activity, colors.dialogTheme()).setTitle(title).setView(wrap).setNegativeButton("Cancel", null).setPositiveButton("Send", null).create();
        draft.setOnShowListener(d -> draft.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            if (token != epoch || !ready) { status.setText("Review changed; reopen the draft"); return; }
            String text = input.getText().toString().trim(); if (text.isEmpty()) return;
            try { send(body.build(text)); draft.dismiss(); } catch (Exception e) { input.setError(e.getMessage()); }
        }));
        draft.setOnDismissListener(d -> { if (token == epoch) drafting(false); });
        drafting(true); draft.show(); input.requestFocus();
        draft.getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
    }
    private void drafting(boolean value) {
        try { send(new JSONObject().put("type", "drafting").put("drafting", value)); } catch (Exception e) { status.setText(e.getMessage()); }
    }
    private void send(JSONObject message) throws Exception {
        // This JNI call only enqueues in a bounded mailbox; it performs no I/O.
        String id = message.optString("messageId");
        if (!id.isEmpty() && awaiting.size() >= 32) throw new IllegalStateException("Wait for the previous messages to be acknowledged");
        RemoteNative.reviewSend(handle, watch, message.toString());
        if (!id.isEmpty()) { awaiting.add(id); status.setText("Queued"); }
    }
    private final class Rows extends BaseAdapter {
        public int getCount() { return rows.size(); }
        public Object getItem(int n) { return rows.get(n); }
        public long getItemId(int n) { return n; }
        public int getViewTypeCount() { return 2; }
        public int getItemViewType(int n) { return rows.get(n).anchor == null ? 0 : 1; }
        public View getView(int n, View old, ViewGroup parent) {
            Row row = rows.get(n); boolean code = row.anchor != null;
            HorizontalScrollView scroll = code ? old instanceof HorizontalScrollView ? (HorizontalScrollView) old : new HorizontalScrollView(activity) : null;
            TextView text = code ? scroll.getChildCount() == 0 ? label(12) : (TextView) scroll.getChildAt(0) : old instanceof TextView ? (TextView) old : label(14);
            text.setText(row.text); text.setBackgroundColor(row.background);
            text.setTypeface(code ? Typeface.MONOSPACE : Typeface.DEFAULT);
            text.setTextSize(code ? 12 : 14); text.setMinHeight(dp(code ? 28 : 44));
            text.setPadding(dp(10), dp(code ? 2 : 9), dp(10), dp(code ? 2 : 9));
            boolean fileEntry = (section == Section.FILES) && path.isEmpty();
            text.setSingleLine(code || fileEntry);
            text.setEllipsize(fileEntry ? android.text.TextUtils.TruncateAt.MIDDLE : null);
            if (!code) return text;
            scroll.setFillViewport(true); scroll.setHorizontalScrollBarEnabled(false);
            if (text.getParent() == null) scroll.addView(text, new android.widget.FrameLayout.LayoutParams(-2, -2));
            scroll.setBackgroundColor(row.background);
            text.setOnClickListener(v -> list.performItemClick(scroll, n, n));
            return scroll;
        }
    }
}
