package dev.herdr.android;

import android.app.Activity;
import android.os.Handler;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import org.json.JSONArray;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Per-agent Session / Review tabs; no terminal output or URL guessing. */
final class DiffReviewController {
    interface Notice { void shared(String pane, String title); }
    final LinearLayout tabs;
    final DiffReviewPanel panel;
    private final Button session, review;
    private final Handler main = new Handler(android.os.Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor(r -> { Thread t = new Thread(r, "diffd-discovery"); t.setDaemon(true); return t; });
    private final Map<String, String> known = new HashMap<>();
    private final java.util.Set<String> unread = new java.util.HashSet<>();
    private final java.util.Set<String> initializedHosts = new java.util.HashSet<>();
    private final java.util.function.Consumer<Boolean> visibility;
    private final Notice notice;
    private long handle, fetchedAt;
    private JSONArray panes, recent;
    private String pane = "", host = "", id = "";
    private boolean showing, fetching, closed;
    DiffReviewController(Activity activity, GpuiChrome colors, java.util.function.Consumer<Boolean> visibility, Notice notice) {
        this.visibility = visibility; this.notice = notice;
        panel = new DiffReviewPanel(activity, colors);
        tabs = new LinearLayout(activity); tabs.setVisibility(View.GONE);
        session = new Button(activity); review = new Button(activity);
        for (Button button : new Button[]{session, review}) {
            button.setTextSize(12); button.setAllCaps(false); button.setPadding(0, 0, 0, 0);
            button.setTextColor(colors.primary); button.setBackgroundTintList(android.content.res.ColorStateList.valueOf(colors.control));
            tabs.addView(button, new LinearLayout.LayoutParams(0, -1, 1));
        }
        session.setText("Session"); review.setText("Review");
        session.setOnClickListener(v -> show(false)); review.setOnClickListener(v -> show(true));
    }
    void theme(GpuiChrome colors) {
        panel.theme(colors);
        for (Button b : new Button[]{session, review}) {
            b.setTextColor(colors.primary);
            b.setBackgroundTintList(android.content.res.ColorStateList.valueOf(colors.control));
        }
    }
    boolean showing() { return showing; }
    void show(boolean show) {
        showing = show && DiffReviewModel.validId(id);
        if (showing) unread.remove(host + ":" + pane);
        panel.showReview(showing); visibility.accept(showing);
        session.setSelected(!showing); review.setSelected(showing);
        session.setText(showing ? "Session" : "● Session");
        review.setText(showing ? "● Review" : unread.contains(host + ":" + pane) ? "Review ●" : "Review");
    }
    void update(long nextHandle, String nextHost, String nextPane, JSONArray nextPanes) {
        if (closed) return;
        if (handle != nextHandle) { handle = nextHandle; recent = null; fetchedAt = 0; }
        host = nextHost; pane = nextPane; panes = nextPanes;
        boolean selectedAgent = false, labelled = false;
        boolean initialSnapshot = initializedHosts.add(host);
        // Record every terminal on this host, so another agent's share is visible
        // even while Wolf is reading the focused agent's review.
        for (int i = 0; panes != null && i < panes.length(); i++) {
            org.json.JSONObject p = panes.optJSONObject(i); if (p == null) continue;
            String shared = p.optString("review_id"), key = host + ":" + p.optString("id");
            if (pane.equals(p.optString("id"))) {
                selectedAgent = !p.optString("kind").isEmpty();
                labelled = !shared.isEmpty();
            }
            String previous = known.put(key, shared);
            if (!DiffReviewModel.validId(shared)) continue;
            if (!shared.equals(previous)) {
                unread.add(key);
                if (previous != null || !initialSnapshot) notice.shared(p.optString("id"), p.optString("title", p.optString("id")));
            }
        }
        if (known.size() > 1024) { known.clear(); unread.clear(); initializedHosts.clear(); }
        String selected = DiffReviewModel.reviewFor(pane, panes, recent);
        if (!selected.equals(id) || panelHandle != handle) {
            id = selected; panelHandle = handle; panel.bind(handle, id);
            if (showing && id.isEmpty()) show(false);
        }
        if (showing && !id.isEmpty()) unread.remove(host + ":" + pane);
        tabs.setVisibility(id.isEmpty() ? View.GONE : View.VISIBLE);
        review.setText(showing ? "● Review" : unread.contains(host + ":" + pane) ? "Review ●" : "Review");
        // Labels need no HTTP poll. Older agents use the agreed unambiguous-cwd fallback.
        long now = android.os.SystemClock.elapsedRealtime();
        if (handle > 0 && selectedAgent && !labelled && !fetching && now - fetchedAt > 10000) {
            fetching = true; fetchedAt = now; final long owner = handle;
            io.execute(() -> {
                JSONArray result = null;
                try { result = new JSONArray(RemoteNative.reviewList(owner)); } catch (Exception unavailable) { /* Optional service. */ }
                final JSONArray found = result;
                main.post(() -> { fetching = false; if (!closed && owner == handle) recent = found; });
            });
        }
    }
    private long panelHandle;
    void pause() { panel.pause(); }
    void close() { closed = true; panel.close(); io.shutdownNow(); }
}
