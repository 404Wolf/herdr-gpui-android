package dev.herdr.android;

import android.app.Activity;
import android.os.Bundle;
import android.widget.*;
import org.json.JSONArray;
import org.json.JSONObject;

/** Runs production native widgets against explicit synthetic diffd data. No GPUI or SSH claim. */
public final class FixtureActivity extends Activity {
    private DiffReviewController controller;
    private static boolean longLabels, longDiff;
    @Override public void onCreate(Bundle bundle) {
        super.onCreate(bundle);
        longLabels = getIntent().getBooleanExtra("long_labels", false);
        longDiff = getIntent().getBooleanExtra("long_diff", false);
        try {
            DiffReviewSelfTest.run();
            GpuiChrome chrome = GpuiChrome.of(new int[]{0xff16171b,0xff202127,0xffe5e7ed,0xffa0a5b3,0xff444955,0xffa9b8fb,0xff2b2e38,0xff363d56,0xffff8d95,0xffe8c583,0xff9ad4b0,0xff8fcde1,0xffc5a3e6,0});
            LinearLayout root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL); root.setBackgroundColor(chrome.background);
            TextView title = new TextView(this); title.setText("SYNTHETIC · diffd native review QA"); title.setTextColor(chrome.onSurfaceVariant); title.setTextSize(12); title.setPadding(Math.round(10 * getResources().getDisplayMetrics().density), 0, 0, 0); root.addView(title);
            TextView terminal = new TextView(this); terminal.setText("Terminal viewport (synthetic)"); terminal.setGravity(android.view.Gravity.TOP); terminal.setPadding(Math.round(10 * getResources().getDisplayMetrics().density), 0, 0, 0); terminal.setTextColor(chrome.onSurface);
            java.util.concurrent.atomic.AtomicInteger notices = new java.util.concurrent.atomic.AtomicInteger();
            controller = new DiffReviewController(this, chrome, show -> root.post(() -> {
                if (!show && root.getHeight() > 0) {
                    int used = root.getHeight() - root.getPaddingTop() - root.getPaddingBottom() - title.getHeight() - terminal.getHeight();
                    if (used != controller.tabs.getHeight()) throw new AssertionError("Session must lose only the compact bar");
                    android.util.Log.i("DiffReviewFixture", "SESSION_BAR_PIXELS " + used);
                }
            }), (pane, titleText) -> {
                notices.incrementAndGet();
                android.util.Log.i("DiffReviewFixture", "new review " + pane);
            });
            controller.attachTo(root, terminal); setContentView(root);
            root.setOnApplyWindowInsetsListener((v, insets) -> {
                android.graphics.Insets bars = insets.getInsets(android.view.WindowInsets.Type.systemBars());
                v.setPadding(bars.left, bars.top, bars.right, bars.bottom); return insets;
            });
            JSONArray agents = new JSONArray("[{\"id\":\"t_test\",\"kind\":\"codex\",\"cwd\":\"/synthetic\",\"review_id\":\"r_test\"}]");
            controller.update(1, "synthetic", "t_test", agents);
            if (notices.get() != 0) throw new AssertionError("Initial reviews must not send old-share alerts");
            agents.getJSONObject(0).put("review_id", "r_next");
            controller.update(1, "synthetic", "t_test", agents);
            if (notices.get() != 1) throw new AssertionError("An existing agent's first new share must notify");
            agents.put(new JSONObject("{\"id\":\"t_other\",\"review_id\":\"r_other\"}"));
            controller.update(1, "synthetic", "t_test", agents);
            controller.update(1, "synthetic", "t_test", agents);
            if (notices.get() != 2) throw new AssertionError("A new agent must notify exactly once");
            agents.getJSONObject(0).put("review_id", "r_test");
            controller.update(1, "synthetic", "t_test", agents);
            ListView visibleRows = findList(controller.panel);
            visibleRows.setOnScrollListener(new AbsListView.OnScrollListener() {
                private boolean jumped;
                public void onScrollStateChanged(AbsListView view, int state) { }
                public void onScroll(AbsListView view, int first, int visible, int total) {
                    if (longDiff && total > 0 && visibleRows.getAdapter().getItemViewType(0) == 1) {
                        if (total != 600 || view.getChildCount() > 64) throw new AssertionError("Diff must be continuous and virtualized");
                        android.util.Log.i("DiffReviewFixture", "CONTINUOUS " + total + " VISIBLE " + view.getChildCount());
                        if (!jumped && getIntent().getBooleanExtra("jump_end", false)) { jumped = true; view.post(() -> visibleRows.setSelection(599)); }
                    }
                    int actual = controller.tabs.getHeight();
                    if (actual != 0 && actual != controller.tabs.getLayoutParams().height) throw new AssertionError("Review chrome must occupy one compact bar");
                    if (getResources().getConfiguration().fontScale <= 1 && actual > 44) throw new AssertionError("Default review bar must stay close to 36 pixels");
                }
            });
            controller.show(true);
            android.util.Log.i("DiffReviewFixture", "MODEL TESTS PASSED");
        } catch (Exception e) { throw new IllegalStateException(e); }
    }
    private static ListView findList(android.view.View view) {
        if (view instanceof ListView) return (ListView) view;
        if (view instanceof android.view.ViewGroup) {
            android.view.ViewGroup group = (android.view.ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) { ListView list = findList(group.getChildAt(i)); if (list != null) return list; }
        }
        return null;
    }
    static JSONObject state() throws Exception {
        String path = longLabels ? "src/very_long_component_name/another_nested_directory/platform/android/transport/authenticated_loopback_review.rs" : "src/review.rs";
        JSONObject file = new JSONObject().put("path", path).put("added", 3).put("removed", 1).put("omitted", JSONObject.NULL);
        file.put("old", new JSONObject().put("lines", new JSONArray("[\"fn connect() {\",\"    open_public_port();\",\"}\"]")).put("syntax", new JSONArray("[[0,2,0,3,10,5],[],[]]")).put("novel", new JSONArray("[[],[4,20],[]]")));
        file.put("new", new JSONObject().put("lines", new JSONArray("[\"fn connect() {\",\"    // Reuse the agent’s pinned SSH session; reuse that authenticated connection without exposing a public port, preserve all terminal rows, and keep every character of this deliberately long suffix readable: HORIZONTAL_END\",\"    ssh.forward_loopback(3433)?;\",\"}\"]")).put("syntax", new JSONArray("[[0,2,0,3,10,5],[4,42,2],[8,24,5,25,29,4],[]]")).put("novel", new JSONArray("[[],[],[8,30],[]]")));
        file.put("rows", new JSONArray("[[0,0],[1,null],[null,1],[null,2],[2,3]]"));
        file.getJSONObject("new").getJSONArray("syntax").getJSONArray(1).put(1, file.getJSONObject("new").getJSONArray("lines").getString(1).length());
        if (longDiff) {
            JSONArray lines = new JSONArray(), pairs = new JSONArray();
            for (int i = 0; i < 600; i++) { lines.put(i == 599 ? "// CONTINUOUS_END" : "// Continuous line " + (i + 1)); pairs.put(new JSONArray().put(i).put(i)); }
            JSONObject side = new JSONObject().put("lines", lines).put("syntax", new JSONArray()).put("novel", new JSONArray());
            file.put("old", side).put("new", side).put("rows", pairs).put("added", 0).put("removed", 0);
        }
        JSONObject anchor = new JSONObject().put("path", path).put("side", "new").put("start", 3).put("end", 3).put("text", "    ssh.forward_loopback(3433)?;").put("range", JSONObject.NULL);
        JSONArray threads = new JSONArray().put(new JSONObject().put("id", "th_test").put("anchor", anchor).put("resolved", false).put("changedIn", JSONObject.NULL).put("messages", new JSONArray().put(new JSONObject().put("author", "agent").put("body", "The review uses the existing SSH authentication. Closing it leaves the terminal running."))));
        return new JSONObject().put("review", new JSONObject().put("id", "r_test").put("revision", 1).put("title", longLabels ? "Keep reviews beside the agent session while testing long titles, narrow phone screens and larger system fonts" : "Keep reviews beside the agent session"))
            .put("snapshot", new JSONObject().put("revision", 1).put("files", new JSONArray().put(file)))
            .put("threads", threads).put("chat", new JSONArray().put(new JSONObject().put("id", "m_test").put("author", "agent").put("body", "Ready for review. Tap a line to comment, or reply here.")));
    }
    @Override protected void onDestroy() { if (controller != null) controller.close(); super.onDestroy(); }
}
