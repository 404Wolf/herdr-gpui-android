package dev.herdr.android;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Home's last view of each computer (its spaces, tabs and terminals, and the
 * git look the tree was grouped with), so a cold launch draws the Fleet at
 * once instead of a blank page that fills in. It is only ever a cached view:
 * Home dims it and offers nothing on it until the computer answers live.
 * Kept in the app's no-backup directory; files run on the worker only.
 */
final class HomeCache {
    private HomeCache() { }

    /** A cache file past this is ignored, not parsed (a tree is a few hundred KB at most). */
    static final int MAX_BYTES = 2 * 1024 * 1024;
    static final String FILE_NAME = "home-view.json";

    /** One computer's last seen view and git look. */
    static final class Entry {
        /** {"workspaces", "tabs", "panes"} as the core's poll had them. */
        final JSONObject view;
        /** FleetTree.parseGitLook's input, or "" when git was never asked. */
        final String git;
        final boolean gitTruncated;
        Entry(JSONObject view, String git, boolean gitTruncated) {
            this.view = view;
            this.git = git == null ? "" : git;
            this.gitTruncated = gitTruncated;
        }

        FleetTree.GitLook look() {
            if (git.isEmpty()) return null;
            FleetTree.GitLook look = FleetTree.parseGitLook(git, FleetTree.PER_REPO);
            look.allTruncated = gitTruncated;
            return look;
        }
    }

    private static final String[] KEYS = {"workspaces", "tabs", "panes"};

    /** Whether a poll carries the terminal list (an early poll may not): only then is it a view to keep. */
    static boolean hasTree(JSONObject view) {
        return view != null && view.optJSONArray("panes") != null && view.optJSONArray("workspaces") != null;
    }

    /** The tree part of a poll, without anything else it carried. */
    static JSONObject treeOf(JSONObject view) throws JSONException {
        JSONObject out = new JSONObject();
        for (String key : KEYS) {
            JSONArray value = view.optJSONArray(key);
            if (value != null) out.put(key, value);
        }
        return out;
    }

    static String encode(Map<String, Entry> entries) throws JSONException {
        JSONObject hosts = new JSONObject();
        for (Map.Entry<String, Entry> e : entries.entrySet()) {
            Entry entry = e.getValue();
            if (!hasTree(entry.view)) continue;
            hosts.put(e.getKey(), new JSONObject().put("view", treeOf(entry.view))
                    .put("git", entry.git).put("git_truncated", entry.gitTruncated));
        }
        return new JSONObject().put("version", 1).put("hosts", hosts).toString();
    }

    /** Host id → entry; anything unreadable or of another version is an empty cache. */
    static Map<String, Entry> decode(String text) {
        Map<String, Entry> out = new LinkedHashMap<>();
        try {
            JSONObject root = new JSONObject(text);
            if (root.optInt("version") != 1) return out;
            JSONObject hosts = root.optJSONObject("hosts");
            if (hosts == null) return out;
            for (java.util.Iterator<String> it = hosts.keys(); it.hasNext(); ) {
                String id = it.next();
                JSONObject host = hosts.optJSONObject(id);
                JSONObject view = host == null ? null : host.optJSONObject("view");
                if (!hasTree(view)) continue;
                out.put(id, new Entry(view, host.optString("git", ""), host.optBoolean("git_truncated", false)));
            }
        } catch (JSONException | RuntimeException unreadable) {
            out.clear();
        }
        return out;
    }

    static Map<String, Entry> read(File file) {
        if (!file.isFile() || file.length() > MAX_BYTES) return new LinkedHashMap<>();
        try (InputStream in = new FileInputStream(file)) {
            byte[] bytes = new byte[(int) file.length()];
            int at = 0;
            for (int n; at < bytes.length && (n = in.read(bytes, at, bytes.length - at)) > 0; ) at += n;
            return decode(new String(bytes, 0, at, StandardCharsets.UTF_8));
        } catch (IOException unreadable) {
            return new LinkedHashMap<>();
        }
    }

    /** Replaces the file atomically, so a launch never reads half a write. */
    static synchronized void write(File file, String text) throws IOException {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_BYTES) return;
        File tmp = new File(file.getPath() + ".tmp");
        try (OutputStream out = new FileOutputStream(tmp)) {
            out.write(bytes);
        }
        if (!tmp.renameTo(file)) {
            tmp.delete();
            throw new IOException("Could not replace " + file.getName());
        }
    }
}
