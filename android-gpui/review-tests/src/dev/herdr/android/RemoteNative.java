package dev.herdr.android;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.concurrent.ConcurrentLinkedQueue;

/** Synthetic diffd feed ONLY for the separate dev.herdr.android.reviewtest APK. */
final class RemoteNative {
    private static final ConcurrentLinkedQueue<String> events = new ConcurrentLinkedQueue<>();
    private static long current;
    private static JSONObject state;
    static synchronized String reviewWatch(long handle, String id) {
        current++; events.clear();
        try { state = FixtureActivity.state(); events.add(new JSONObject().put("type", "state").put("state", state).toString()); }
        catch (Exception e) { throw new IllegalStateException(e); }
        return "{\"watch\":" + current + "}";
    }
    static String reviewList(long h) { return "[]"; }
    static String reviewPoll(long h, long w) {
        JSONArray batch = new JSONArray(); String event;
        while ((event = events.poll()) != null) { try { batch.put(new JSONObject(event)); } catch (Exception e) { throw new IllegalStateException(e); } }
        return batch.toString();
    }
    static String reviewSend(long h, long w, String raw) {
        try {
            JSONObject m = new JSONObject(raw); String type = m.getString("type");
            if ("comment".equals(type)) {
                JSONObject message = new JSONObject().put("id", m.getString("messageId")).put("author", "user").put("body", m.getString("body"));
                JSONObject thread = new JSONObject().put("id", m.getString("threadId")).put("anchor", m.getJSONObject("anchor")).put("resolved", false).put("changedIn", JSONObject.NULL).put("messages", new JSONArray().put(message));
                state.getJSONArray("threads").put(thread);
                events.add(new JSONObject().put("type", "thread").put("thread", thread).toString());
            }
            if ("reply".equals(type) || "resolve".equals(type)) {
                JSONArray threads = state.getJSONArray("threads");
                for (int i = 0; i < threads.length(); i++) {
                    JSONObject thread = threads.getJSONObject(i);
                    if (!thread.getString("id").equals(m.getString("threadId"))) continue;
                    if ("resolve".equals(type)) thread.put("resolved", m.getBoolean("resolved"));
                    else thread.getJSONArray("messages").put(new JSONObject().put("id", m.getString("messageId")).put("author", "user").put("body", m.getString("body")));
                    events.add(new JSONObject().put("type", "thread").put("thread", thread).toString());
                }
            }
            if ("chat".equals(type)) events.add(new JSONObject().put("type", "chat").put("message", new JSONObject().put("id", m.getString("messageId")).put("author", "user").put("body", m.getString("body"))).toString());
            if ("chat".equals(type) || "comment".equals(type) || "reply".equals(type)) events.add(new JSONObject().put("type", "ack").put("id", m.getString("messageId")).toString());
            android.util.Log.i("DiffReviewFixture", "sent " + type);
        } catch (Exception e) { throw new IllegalStateException(e); }
        return "{\"queued\":true}";
    }
    static void reviewStop(long h, long w) { }
}
