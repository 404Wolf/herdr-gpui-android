package dev.herdr.android;

import android.app.Activity;
import android.os.Handler;
import android.view.View;
import android.widget.TextView;
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
    private final TextView session, review, badge;
    private GpuiChrome colors;
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
        this.colors = colors;
        panel = new DiffReviewPanel(activity, colors);
        tabs = new LinearLayout(activity); tabs.setGravity(android.view.Gravity.CENTER_VERTICAL); tabs.setVisibility(View.GONE);
        session = DiffReviewPanel.tab(activity, colors, "Session");
        TextView divider = DiffReviewPanel.tab(activity, colors, "|"); divider.setClickable(false); divider.setFocusable(false); divider.setPadding(0, 0, 0, 0);
        review = DiffReviewPanel.tab(activity, colors, "Review");
        badge = DiffReviewPanel.tab(activity, colors, "•"); badge.setTextSize(9); badge.setPadding(0, 0, 0, 0);
        badge.setClickable(false); badge.setFocusable(false); badge.setContentDescription("New review");
        tabs.addView(session, new LinearLayout.LayoutParams(-2, -1));
        tabs.addView(divider, new LinearLayout.LayoutParams(-2, -1));
        tabs.addView(review, new LinearLayout.LayoutParams(-2, -1));
        tabs.addView(badge, new LinearLayout.LayoutParams(Math.round(8 * activity.getResources().getDisplayMetrics().density), -1));
        tabs.addView(new View(activity), new LinearLayout.LayoutParams(0, 1, 1));
        tabs.addView(panel.navigation, new LinearLayout.LayoutParams(-2, -1));
        session.setOnClickListener(v -> show(false)); review.setOnClickListener(v -> show(true));
        style();
    }
    /** The terminal keeps the entire weighted viewport; only this one bar consumes rows. */
    android.widget.FrameLayout attachTo(LinearLayout parent, View terminal) {
        int height = Math.max(36, (int) Math.ceil(session.getPaint().descent() - session.getPaint().ascent()) + 6);
        parent.addView(tabs, new LinearLayout.LayoutParams(-1, height));
        android.widget.FrameLayout viewport = new android.widget.FrameLayout(parent.getContext());
        viewport.addView(terminal, new android.widget.FrameLayout.LayoutParams(-1, -1));
        viewport.addView(panel, new android.widget.FrameLayout.LayoutParams(-1, -1));
        parent.addView(viewport, new LinearLayout.LayoutParams(-1, 0, 1));
        return viewport;
    }
    void theme(GpuiChrome colors) { this.colors = colors; panel.theme(colors); style(); }
    private void style() {
        tabs.setBackgroundColor(colors.surface);
        session.setTextColor(showing ? colors.onSurfaceVariant : colors.primary);
        review.setTextColor(showing ? colors.primary : colors.onSurfaceVariant);
        badge.setTextColor(colors.info);
        badge.setVisibility(unread.contains(host + ":" + pane) && !showing ? View.VISIBLE : View.INVISIBLE);
        panel.navigation.setVisibility(showing ? View.VISIBLE : View.INVISIBLE);
    }
    boolean showing() { return showing; }
    void show(boolean show) {
        showing = show && DiffReviewModel.validId(id);
        if (showing) unread.remove(host + ":" + pane);
        panel.showReview(showing); visibility.accept(showing);
        session.setSelected(!showing); review.setSelected(showing); style();
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
        style();
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
