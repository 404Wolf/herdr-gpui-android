package dev.herdr.android;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.util.HashMap;
import java.util.Map;

/** diffd's wire shapes, including atomic, revision-fenced snapshot deltas. */
final class DiffReviewModel {
    JSONObject state;
    static boolean validId(String value) { return value != null && value.matches("[A-Za-z0-9_-]{1,128}"); }

    static String reviewFor(String pane, JSONArray panes, JSONArray reviews) {
        JSONObject selected = null;
        for (int i = 0; panes != null && i < panes.length(); i++) {
            JSONObject item = panes.optJSONObject(i);
            if (item != null && pane.equals(item.optString("id"))) selected = item;
        }
        if (selected == null) return "";
        String label = selected.optString("review_id");
        // An authoritative label never falls through to a different repository review.
        if (!label.isEmpty()) return validId(label) ? label : "";
        String cwd = selected.optString("cwd");
        if (cwd.isEmpty() || selected.optString("kind").isEmpty()) return "";
        int agents = 0;
        for (int i = 0; panes != null && i < panes.length(); i++) {
            JSONObject item = panes.optJSONObject(i);
            if (item != null && cwd.equals(item.optString("cwd")) && !item.optString("kind").isEmpty()) agents++;
        }
        if (agents != 1) return "";
        String id = "";
        long newest = -1;
        for (int i = 0; reviews != null && i < reviews.length(); i++) {
            JSONObject summary = reviews.optJSONObject(i);
            JSONObject review = summary == null ? null : summary.optJSONObject("review");
            if (review != null && cwd.equals(review.optString("repoPath"))
                    && validId(review.optString("id")) && review.optLong("createdAt") > newest) {
                newest = review.optLong("createdAt"); id = review.optString("id");
            }
        }
        return id;
    }

    boolean apply(JSONObject event) throws JSONException {
        String type = event.getString("type");
        if ("state".equals(type)) {
            JSONObject next = event.getJSONObject("state");
            if (next.getJSONObject("snapshot").getJSONArray("files").length() > 4096) throw new JSONException("Too many review files");
            if (next.getJSONObject("review").getLong("revision") != next.getJSONObject("snapshot").getLong("revision")) throw new JSONException("Mismatched review revision");
            if (next.getJSONArray("threads").length() > 4096 || next.getJSONArray("chat").length() > 10000) throw new JSONException("Review discussion exceeds the display limit");
            state = next;
            return true;
        }
        if (state == null) throw new JSONException("Review state is missing; reconnect");
        switch (type) {
            case "revision": {
                JSONObject delta = event.getJSONObject("delta");
                JSONObject old = state.getJSONObject("snapshot");
                if (delta.getLong("base") != old.getLong("revision")) throw new JSONException("Review revision changed; reconnect");
                Map<String, JSONObject> files = new HashMap<>();
                JSONArray before = old.getJSONArray("files"), changed = delta.getJSONArray("files"), paths = delta.getJSONArray("paths");
                if (paths.length() > 4096) throw new JSONException("Too many review files");
                for (int i = 0; i < before.length(); i++) files.put(before.getJSONObject(i).getString("path"), before.getJSONObject(i));
                for (int i = 0; i < changed.length(); i++) files.put(changed.getJSONObject(i).getString("path"), changed.getJSONObject(i));
                JSONArray after = new JSONArray();
                java.util.Set<String> seen = new java.util.HashSet<>();
                for (int i = 0; i < paths.length(); i++) {
                    String path = paths.getString(i);
                    if (!seen.add(path) || !files.containsKey(path)) throw new JSONException("Invalid review delta");
                    after.put(files.get(path));
                }
                JSONObject snapshot = new JSONObject().put("revision", delta.getLong("revision")).put("files", after).put("symbols", delta.getJSONArray("symbols"));
                JSONObject review = event.getJSONObject("review");
                if (delta.getLong("revision") <= delta.getLong("base") || review.getLong("revision") != delta.getLong("revision")) throw new JSONException("Mismatched review revision");
                state.put("snapshot", snapshot).put("review", review);
                return true;
            }
            case "thread": {
                JSONObject thread = event.getJSONObject("thread");
                JSONArray threads = state.getJSONArray("threads");
                for (int i = 0; i < threads.length(); i++) {
                    if (threads.getJSONObject(i).getString("id").equals(thread.getString("id"))) { threads.put(i, thread); return true; }
                }
                if (threads.length() >= 4096) throw new JSONException("Too many threads");
                threads.put(thread); return true;
            }
            case "chat": {
                JSONArray chat = state.getJSONArray("chat");
                JSONObject message = event.getJSONObject("message");
                for (int i = 0; i < chat.length(); i++) if (chat.getJSONObject(i).getString("id").equals(message.getString("id"))) return false;
                if (chat.length() >= 10000) throw new JSONException("Too many chat messages");
                chat.put(message); return true;
            }
            default: return false;
        }
    }
}
