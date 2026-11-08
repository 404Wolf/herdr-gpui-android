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
import android.view.MotionEvent;
import android.view.ViewConfiguration;
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
    final LinearLayout navigation;
    private final TextView statusDot, location, options;
    private final List<TextView> sectionTabs = new ArrayList<>();
    private final Button compose;
    private final DiffList list;
    private enum Indicator { OFFLINE, CONNECTING, LIVE, ACKNOWLEDGED, QUEUED, ERROR }
    private Indicator indicator = Indicator.OFFLINE;
    private String statusMessage = "Review closed";
    private JSONObject selectedFile;
    private JSONArray aligned;
    private float horizontalOffset, contentWidth;
    private int gutterWidth;
    private boolean wrapCode;
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
    private ScheduledFuture<?> polling;
    private AlertDialog draft;
    private final java.util.Set<String> awaiting = new java.util.HashSet<>();
    private static final class Row {
        final CharSequence text;
        final int background;
        final JSONObject anchor, thread;
        final String gutter;
        Row(CharSequence text, int background, JSONObject anchor, JSONObject thread) { this(text, background, anchor, thread, ""); }
        Row(CharSequence text, int background, JSONObject anchor, JSONObject thread, String gutter) {
            this.text = text; this.background = background; this.anchor = anchor; this.thread = thread; this.gutter = gutter;
        }
    }

    DiffReviewPanel(Activity activity, GpuiChrome colors) {
        super(activity); this.activity = activity; this.colors = colors;
        setOrientation(VERTICAL); setBackgroundColor(colors.background);
        setClickable(true); setFocusable(true);
        list = new DiffList();
        navigation = new LinearLayout(activity);
        navigation.setGravity(android.view.Gravity.CENTER_VERTICAL);
        statusDot = tab(activity, colors, "•");
        statusDot.setTextSize(13); statusDot.setPadding(dp(3), 0, dp(3), 0);
        statusDot.setOnClickListener(this::menu);
        navigation.addView(statusDot, new LayoutParams(dp(14), -1));
        for (Section destination : Section.values()) {
            TextView item = tab(activity, colors, destination.label);
            item.setOnClickListener(v -> {
                section = destination;
                if (destination == Section.FILES) path = "";
                render(); list.setSelection(0);
            });
            sectionTabs.add(item); navigation.addView(item, new LayoutParams(-2, -1));
        }
        options = tab(activity, colors, "⋮"); options.setTextSize(14);
        options.setContentDescription("Review options"); options.setOnClickListener(this::menu);
        navigation.addView(options, new LayoutParams(dp(24), -1));
        location = label(12); location.setSingleLine(true); location.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        location.setOnClickListener(v -> { section = Section.FILES; path = ""; render(); });
        addView(location, new LayoutParams(-1, dp(24)));
        list.setDivider(null); list.setAdapter(adapter);
        list.setOnItemClickListener((parent, view, index, id) -> {
            if (!ready || aligned != null) return;
            Row row = rows.get(index);
            if (row.thread != null) thread(row.thread);
            else if (section == Section.FILES && path.isEmpty()) {
                path = model.state.optJSONObject("snapshot").optJSONArray("files").optJSONObject(index).optString("path");
                horizontalOffset = contentWidth = 0;
                render(); list.setSelection(0);
            }
        });
        addView(list, new LayoutParams(-1, 0, 1));
        compose = button("Message agent"); compose.setOnClickListener(v -> message("Chat", body -> new JSONObject().put("type", "chat").put("messageId", UUID.randomUUID().toString()).put("body", body)));
        addView(compose, new LayoutParams(-1, dp(44)));
        refreshNavigation(); setVisibility(GONE);
    }
    void theme(GpuiChrome next) {
        colors = next; setBackgroundColor(next.background);
        tint(this); refreshNavigation(); render();
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
    static TextView tab(Activity activity, GpuiChrome colors, String title) {
        TextView text = new TextView(activity); text.setText(title); text.setTextSize(11);
        text.setGravity(android.view.Gravity.CENTER); text.setSingleLine(true); text.setIncludeFontPadding(false);
        int pad = Math.round(5 * activity.getResources().getDisplayMetrics().density);
        text.setPadding(pad, 0, pad, 0); text.setTextColor(colors.onSurfaceVariant);
        text.setClickable(true); text.setFocusable(true); return text;
    }
    private void refreshNavigation() {
        for (int i = 0; i < sectionTabs.size(); i++) {
            TextView item = sectionTabs.get(i); boolean selected = Section.values()[i] == section;
            item.setTextColor(selected ? colors.primary : colors.onSurfaceVariant);
            item.setBackgroundColor(selected ? colors.control : android.graphics.Color.TRANSPARENT);
        }
        options.setTextColor(colors.onSurfaceVariant);
        statusDot.setTextColor(indicator == Indicator.ERROR ? colors.danger : indicator == Indicator.QUEUED || indicator == Indicator.CONNECTING ? colors.warning : indicator == Indicator.LIVE || indicator == Indicator.ACKNOWLEDGED ? colors.success : colors.onSurfaceVariant);
        statusDot.setContentDescription("Review: " + statusMessage);
    }
    private void status(Indicator state, String message) {
        String next = message == null ? "Review error" : shortLabel(message);
        if (indicator == state && next.equals(statusMessage)) return;
        indicator = state; statusMessage = next; refreshNavigation();
    }
    private void menu(View anchor) {
        PopupMenu menu = new PopupMenu(new android.view.ContextThemeWrapper(activity, colors.popupTheme()), anchor);
        menu.getMenu().add(0, 1, 0, "Wrap code").setCheckable(true).setChecked(wrapCode).setEnabled(aligned != null);
        menu.getMenu().add(0, 2, 1, "Reconnect");
        menu.getMenu().add(statusMessage).setEnabled(false);
        menu.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == 1) { wrapCode = !wrapCode; list.invalidateViews(); return true; }
            if (item.getItemId() == 2) { start(handle, review); return true; }
            return false;
        });
        menu.show();
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
        stopWatch(); handle = newHandle; review = id; path = ""; horizontalOffset = contentWidth = 0; model.state = null;
        rows.clear(); selectedFile = null; aligned = null; adapter.notifyDataSetChanged(); status(Indicator.OFFLINE, "Review closed"); ready = false;
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
        ready = false; compose.setEnabled(false); status(Indicator.CONNECTING, "Connecting…");
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
                    if (changed) { ready = true; compose.setEnabled(true); render(); }
                    if (changed || acknowledged) status(!awaiting.isEmpty() ? Indicator.QUEUED : acknowledged ? Indicator.ACKNOWLEDGED : Indicator.LIVE, !awaiting.isEmpty() ? "Waiting for acknowledgement" : acknowledged ? "Sent · Live" : "Live");
                } catch (Exception e) { error(token, e.getMessage()); }
                finally { pending.set(false); }
            });
        } catch (Exception e) { pending.set(false); error(token, e.getMessage()); }
    }
    private void error(long token, String message) {
        main.post(() -> {
            if (token != epoch) return;
            stopWatch(); status(Indicator.ERROR, message == null ? "Review connection closed" : shortLabel(message));
            compose.setEnabled(false);
        });
    }
    private void render() {
        if (model.state == null) return;
        int position = list.getFirstVisiblePosition(); View first = list.getChildAt(0); int offset = first == null ? 0 : first.getTop();
        rows.clear(); selectedFile = null; aligned = null;
        refreshNavigation();
        try {
            if ((section == Section.FILES)) files();
            else if ((section == Section.THREADS)) threads();
            else chat();
            compose.setVisibility((section == Section.CHAT) ? VISIBLE : GONE);
            location.setText(shortLabel((section == Section.FILES) && !path.isEmpty() ? path : model.state.getJSONObject("review").optString("title")));
        } catch (Exception e) { status(Indicator.ERROR, "Invalid review: " + e.getMessage()); ready = false; }
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
        selectedFile = selected;
        aligned = selected.getJSONArray("rows");
        JSONObject old = selected.optJSONObject("old"), fresh = selected.optJSONObject("new");
        int count = Math.max(old == null ? 0 : old.getJSONArray("lines").length(), fresh == null ? 0 : fresh.getJSONArray("lines").length());
        android.text.TextPaint paint = new android.text.TextPaint(); paint.setTypeface(Typeface.MONOSPACE);
        paint.setTextSize(location.getTextSize());
        gutterWidth = Math.max(dp(32), (int) Math.ceil(paint.measureText("0") * (Integer.toString(count).length() + 2)) + dp(14));
    }
    private String line(JSONObject side, int n) {
        return side == null || n < 0 ? "" : side.optJSONArray("lines").optString(n);
    }
    private Row code(JSONObject side, int n, String which, String sign, int color) throws Exception {
        String source = line(side, n);
        String prefix = String.format(java.util.Locale.ROOT, "%d %s", n + 1, sign);
        // Android indices and diffd syntax offsets are both UTF-16 code units.
        String visible = source.length() > 8000 ? source.substring(0, 8000) + "…" : source;
        SpannableString text = new SpannableString(visible);
        JSONArray syntax = side.optJSONArray("syntax"); JSONArray runs = syntax == null ? null : syntax.optJSONArray(n);
        // Class indices follow diffd SyntaxClass::ALL; unknown classes keep the base text color.
        int[] syntaxColors = {colors.primary, colors.success, colors.onSurfaceVariant, colors.warning, colors.warning, colors.info, colors.info, colors.onSurface, colors.onSurface, colors.primary, colors.primary, colors.onSurfaceVariant, colors.onSurfaceVariant, colors.info, colors.warning, colors.primary, colors.info};
        for (int i = 0; runs != null && i + 2 < runs.length(); i += 3) {
            int a = runs.optInt(i, -1), b = runs.optInt(i + 1, -1), c = runs.optInt(i + 2, -1);
            if (a >= 0 && a < b && b <= visible.length() && c >= 0 && c < syntaxColors.length)
                text.setSpan(new ForegroundColorSpan(syntaxColors[c]), a, b, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        JSONArray novel = side.optJSONArray("novel");
        JSONArray changes = novel == null ? null : novel.optJSONArray(n);
        int changed = "old".equals(which) ? colors.danger : colors.success;
        for (int i = 0; changes != null && i + 1 < changes.length(); i += 2) {
            int a = changes.optInt(i, -1), b = changes.optInt(i + 1, -1);
            if (a >= 0 && a < b && b <= visible.length())
                text.setSpan(new BackgroundColorSpan(GpuiChrome.withAlpha(changed, 70)), a, b, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        JSONObject anchor = new JSONObject().put("path", path).put("side", which).put("start", n + 1).put("end", n + 1).put("text", source).put("range", JSONObject.NULL);
        return new Row(text, color == 0 ? colors.background : GpuiChrome.withAlpha(color, 35), anchor, null, prefix);
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
        if (anchor.optString("text").length() > 16000) { status(Indicator.ERROR, "This line exceeds the comment size limit"); return; }
        message(shortLabel(anchor.optString("path")) + ":" + anchor.optInt("start") + " · " + anchor.optString("side"), body -> new JSONObject().put("type", "comment").put("threadId", UUID.randomUUID().toString()).put("messageId", UUID.randomUUID().toString()).put("anchor", anchor).put("body", body));
    }
    private void thread(JSONObject thread) {
        new AlertDialog.Builder(activity, colors.dialogTheme()).setTitle("Thread")
            .setItems(new String[]{"Reply", thread.optBoolean("resolved") ? "Reopen" : "Resolve", "Show line"}, (d, which) -> {
                if (which == 0) message("Reply", body -> new JSONObject().put("type", "reply").put("threadId", thread.getString("id")).put("messageId", UUID.randomUUID().toString()).put("body", body));
                else if (which == 1) {
                    try { send(new JSONObject().put("type", "resolve").put("threadId", thread.getString("id")).put("resolved", !thread.optBoolean("resolved"))); } catch (Exception e) { status(Indicator.ERROR, e.getMessage()); }
                } else {
                    JSONObject anchor = thread.optJSONObject("anchor");
                    path = anchor.optString("path"); section = Section.FILES; int targetRow = 0; horizontalOffset = contentWidth = 0;
                    JSONArray files = model.state.optJSONObject("snapshot").optJSONArray("files");
                    for (int i = 0; i < files.length(); i++) {
                        JSONObject file = files.optJSONObject(i);
                        if (file == null || !path.equals(file.optString("path"))) continue;
                        JSONArray aligned = file.optJSONArray("rows"); int side = "old".equals(anchor.optString("side")) ? 0 : 1;
                        for (int j = 0; aligned != null && j < aligned.length(); j++) {
                            if (aligned.optJSONArray(j).optInt(side, -1) == anchor.optInt("start") - 1) { targetRow = j; break; }
                        }
                    }
                    render(); list.setSelection(targetRow);
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
            if (token != epoch || !ready) { status(Indicator.ERROR, "Review changed; reopen the draft"); return; }
            String text = input.getText().toString().trim(); if (text.isEmpty()) return;
            try { send(body.build(text)); draft.dismiss(); } catch (Exception e) { input.setError(e.getMessage()); }
        }));
        draft.setOnDismissListener(d -> { if (token == epoch) drafting(false); });
        drafting(true); draft.show(); input.requestFocus();
        draft.getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
    }
    private void drafting(boolean value) {
        try { send(new JSONObject().put("type", "drafting").put("drafting", value)); } catch (Exception e) { status(Indicator.ERROR, e.getMessage()); }
    }
    private void send(JSONObject message) throws Exception {
        // This JNI call only enqueues in a bounded mailbox; it performs no I/O.
        String id = message.optString("messageId");
        if (!id.isEmpty() && awaiting.size() >= 32) throw new IllegalStateException("Wait for the previous messages to be acknowledged");
        RemoteNative.reviewSend(handle, watch, message.toString());
        if (!id.isEmpty()) { awaiting.add(id); status(Indicator.QUEUED, "Queued"); }
    }
    private void pan(float delta) {
        horizontalOffset = Math.max(0, Math.min(horizontalOffset + delta, Math.max(0, contentWidth - Math.max(1, list.getWidth() - gutterWidth))));
        for (int i = 0; i < list.getChildCount(); i++) {
            View child = list.getChildAt(i);
            if (child instanceof CodePair) ((CodePair) child).pan();
        }
    }
    /** Vertical scrolling remains ListView's; one horizontal offset moves code, never gutters. */
    private final class DiffList extends ListView {
        private float downX, downY, lastX;
        private boolean horizontal;
        private final int slop = ViewConfiguration.get(activity).getScaledTouchSlop();
        DiffList() { super(activity); }
        @Override public boolean onInterceptTouchEvent(MotionEvent event) {
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) { downX = lastX = event.getX(); downY = event.getY(); horizontal = false; }
            if (aligned != null && !wrapCode && event.getActionMasked() == MotionEvent.ACTION_MOVE
                    && Math.abs(event.getX() - downX) > slop && Math.abs(event.getX() - downX) > Math.abs(event.getY() - downY) * 1.5f) {
                horizontal = true; getParent().requestDisallowInterceptTouchEvent(true); return true;
            }
            return super.onInterceptTouchEvent(event);
        }
        @Override public boolean onTouchEvent(MotionEvent event) {
            if (horizontal) {
                if (event.getActionMasked() == MotionEvent.ACTION_MOVE) { pan(lastX - event.getX()); lastX = event.getX(); }
                if (event.getActionMasked() == MotionEvent.ACTION_UP || event.getActionMasked() == MotionEvent.ACTION_CANCEL) horizontal = false;
                return true;
            }
            return super.onTouchEvent(event);
        }
    }
    /** Only visible aligned pairs have text layouts; the full diff is never materialized as views. */
    private final class CodePair extends LinearLayout {
        private final CodeLine first = new CodeLine(), second = new CodeLine();
        CodePair() { super(activity); setOrientation(VERTICAL); addView(first); addView(second); }
        void bind(int index) {
            try {
                JSONArray pair = aligned.getJSONArray(index);
                int old = pair.isNull(0) ? -1 : pair.getInt(0), fresh = pair.isNull(1) ? -1 : pair.getInt(1);
                if (old < 0 && fresh < 0) throw new org.json.JSONException("Diff row has no line");
                JSONObject left = selectedFile.optJSONObject("old"), right = selectedFile.optJSONObject("new");
                if (old >= 0 && fresh >= 0 && line(left, old).equals(line(right, fresh))) {
                    first.bind(code(right, fresh, "new", " ", 0)); second.setVisibility(GONE);
                } else if (old >= 0) {
                    first.bind(code(left, old, "old", "−", colors.danger));
                    second.setVisibility(fresh >= 0 ? VISIBLE : GONE);
                    if (fresh >= 0) second.bind(code(right, fresh, "new", "+", colors.success));
                } else {
                    first.bind(code(right, fresh, "new", "+", colors.success)); second.setVisibility(GONE);
                }
            } catch (Exception e) { first.bind(new Row("Invalid diff row", colors.background, null, null)); second.setVisibility(GONE); status(Indicator.ERROR, e.getMessage()); }
        }
        void pan() { first.pan(); second.pan(); }
    }
    private final class CodeLine extends LinearLayout {
        private final TextView gutter = label(12), code = label(12);
        private final FrameLayout viewport = new FrameLayout(activity);
        private JSONObject anchor;
        CodeLine() {
            super(activity); setOrientation(HORIZONTAL); setClickable(true);
            gutter.setTypeface(Typeface.MONOSPACE); code.setTypeface(Typeface.MONOSPACE);
            gutter.setIncludeFontPadding(false); code.setIncludeFontPadding(false);
            gutter.setGravity(android.view.Gravity.TOP); code.setGravity(android.view.Gravity.TOP);
            gutter.setPadding(dp(10), 0, dp(4), 0); code.setPadding(0, 0, dp(10), 0);
            addView(gutter, new LayoutParams(0, -1));
            viewport.setClipChildren(true); viewport.addView(code);
            addView(viewport, new LayoutParams(0, -2, 1));
            setOnClickListener(v -> { if (ready && anchor != null) comment(anchor); });
        }
        void bind(Row row) {
            anchor = row.anchor; setBackgroundColor(row.background);
            int pitch = (int) Math.ceil(code.getTextSize() * 1.3f);
            gutter.setTextColor(colors.onSurfaceVariant); code.setTextColor(colors.onSurface);
            gutter.setText(row.gutter); gutter.setLayoutParams(new LayoutParams(gutterWidth, -1));
            gutter.setLineHeight(pitch); code.setLineHeight(pitch); code.setMinHeight(pitch);
            code.setText(row.text); code.setSingleLine(!wrapCode); code.setHorizontallyScrolling(!wrapCode); code.setEllipsize(null);
            int width = (int) Math.ceil(android.text.Layout.getDesiredWidth(row.text, code.getPaint())) + dp(10);
            contentWidth = Math.max(contentWidth, width);
            code.setLayoutParams(new FrameLayout.LayoutParams(wrapCode ? -1 : Math.max(1, width), wrapCode ? -2 : pitch));
            setContentDescription(anchor == null ? "Invalid diff row" : anchor.optString("side") + " line " + anchor.optInt("start"));
            pan();
        }
        void pan() { code.setTranslationX(wrapCode ? 0 : -horizontalOffset); }
    }
    private final class Rows extends BaseAdapter {
        public int getCount() { return aligned == null ? rows.size() : aligned.length(); }
        public Object getItem(int n) { return aligned == null ? rows.get(n) : aligned.optJSONArray(n); }
        public long getItemId(int n) { return n; }
        public int getViewTypeCount() { return 2; }
        public int getItemViewType(int n) { return aligned == null ? 0 : 1; }
        public View getView(int n, View old, ViewGroup parent) {
            if (aligned != null) { CodePair pair = old instanceof CodePair ? (CodePair) old : new CodePair(); pair.bind(n); return pair; }
            TextView text = old instanceof TextView ? (TextView) old : label(14);
            Row row = rows.get(n); text.setText(row.text); text.setBackgroundColor(row.background);
            text.setTypeface(Typeface.DEFAULT); text.setTextColor(colors.onSurface); text.setTextSize(14);
            boolean fileEntry = section == Section.FILES && path.isEmpty();
            text.setMinHeight(dp(fileEntry ? 28 : 36)); text.setPadding(dp(10), dp(fileEntry ? 2 : 6), dp(10), dp(fileEntry ? 2 : 6));
            text.setSingleLine(fileEntry); text.setEllipsize(fileEntry ? android.text.TextUtils.TruncateAt.MIDDLE : null);
            return text;
        }
    }
}
