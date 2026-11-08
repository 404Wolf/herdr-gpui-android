package dev.herdr.android;

import org.json.JSONArray;
import org.json.JSONObject;

/** Deterministic wire-model tests, also run by the isolated native-view harness. */
public final class DiffReviewSelfTest {
    public static void main(String[] args) throws Exception { run(); }
    static void run() throws Exception {
        JSONArray panes = new JSONArray("[{\"id\":\"t1\",\"cwd\":\"/repo\",\"kind\":\"codex\",\"review_id\":\"r_explicit\"}]");
        JSONArray recent = new JSONArray("[{\"review\":{\"id\":\"r_fallback\",\"repoPath\":\"/repo\",\"createdAt\":1}}]");
        require("r_explicit".equals(DiffReviewModel.reviewFor("t1", panes, recent)), "label wins");
        panes.getJSONObject(0).put("review_id", "http://other");
        require(DiffReviewModel.reviewFor("t1", panes, recent).isEmpty(), "invalid authoritative label cannot fall back");
        panes.getJSONObject(0).put("review_id", "");
        require("r_fallback".equals(DiffReviewModel.reviewFor("t1", panes, recent)), "unique exact cwd fallback");
        panes.put(new JSONObject("{\"id\":\"t2\",\"cwd\":\"/repo\",\"kind\":\"claude\"}"));
        require(DiffReviewModel.reviewFor("t1", panes, recent).isEmpty(), "shared cwd must not guess");
        require(DiffReviewModel.reviewFor("missing", panes, recent).isEmpty(), "unknown agent");
        DiffReviewModel model = new DiffReviewModel();
        model.apply(new JSONObject("{\"type\":\"state\",\"state\":{\"review\":{\"revision\":1},\"snapshot\":{\"revision\":1,\"files\":[{\"path\":\"a\"},{\"path\":\"b\"}]},\"threads\":[],\"chat\":[]}}"));
        JSONObject delta = new JSONObject("{\"type\":\"revision\",\"review\":{\"revision\":2},\"delta\":{\"base\":0,\"revision\":2,\"paths\":[\"a\"],\"files\":[],\"symbols\":[]}}");
        rejected(model, delta); require(model.state.getJSONObject("snapshot").getInt("revision") == 1, "stale delta is atomic");
        delta.getJSONObject("delta").put("base", 1).put("paths", new JSONArray("[\"missing\"]"));
        rejected(model, delta); require(model.state.getJSONObject("snapshot").getJSONArray("files").length() == 2, "missing file is atomic");
        delta.getJSONObject("delta").put("paths", new JSONArray("[\"a\",\"a\"]")); rejected(model, delta);
        delta.getJSONObject("delta").put("paths", new JSONArray("[\"b\",\"a\"]")).put("files", new JSONArray("[{\"path\":\"a\",\"added\":2}]"));
        model.apply(delta); JSONArray files = model.state.getJSONObject("snapshot").getJSONArray("files");
        require(files.getJSONObject(0).getString("path").equals("b") && files.getJSONObject(1).getInt("added") == 2, "delta preserves order and unchanged files");
        JSONObject chat = new JSONObject("{\"type\":\"chat\",\"message\":{\"id\":\"m1\",\"body\":\"hello\"}}");
        model.apply(chat); model.apply(chat); require(model.state.getJSONArray("chat").length() == 1, "chat is idempotent");
        model.apply(new JSONObject("{\"type\":\"thread\",\"thread\":{\"id\":\"th1\",\"resolved\":false}}"));
        model.apply(new JSONObject("{\"type\":\"thread\",\"thread\":{\"id\":\"th1\",\"resolved\":true}}"));
        require(model.state.getJSONArray("threads").length() == 1 && model.state.getJSONArray("threads").getJSONObject(0).getBoolean("resolved"), "thread updates replace by id");
        System.out.println("DiffReviewSelfTest: passed");
    }
    private static void rejected(DiffReviewModel model, JSONObject event) throws Exception {
        try { model.apply(event); throw new AssertionError("Invalid delta accepted"); } catch (org.json.JSONException expected) { }
    }
    private static void require(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
