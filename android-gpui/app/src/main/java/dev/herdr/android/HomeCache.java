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
 * The Fleet cache: the last view of each computer (its spaces, tabs and
 * terminals, and the git look the tree was grouped with), shared by Home and
 * the terminal (item 77, ask 2). A cold launch draws the Fleet at once
 * instead of a blank page that fills in, and with no link the terminal's
 * rail and strip still list every pane to switch to. Home writes every
 * computer it shows; the terminal merges in the one it is connected to.
 * Sealed ({@link CacheSeal}) in {@code no_backup/cache-v2/fleet.sealed}; the
 * plain version 1 file is sealed once, then deleted. Files run on workers only.
 */
final class HomeCache {
    private HomeCache() { }

    /** A cache file past this is ignored, not parsed (a tree is a few hundred KB at most). */
    static final int MAX_BYTES = 2 * 1024 * 1024;
    /** The plain version 1 file, read once to migrate it. */
    static final String FILE_NAME = "home-view.json";
    static final String SEALED_NAME = "fleet.sealed";

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

    /**
     * Whether a session's poll is a list the core gave, to show and keep in
     * place of the cached one. A session that has not reached its core yet
     * (connecting, or failed: offline) polls empty lists, which are not the
     * computer's terminals: taking them blanked Home on a cold start offline.
     * A list with panes in it, or any list once the session is ready, is real.
     */
    static boolean listed(JSONObject view) {
        if (!hasTree(view)) return false;
        JSONArray panes = view.optJSONArray("panes");
        return panes.length() > 0 || "ready".equals(view.optString("state"));
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

    static File sealedFile(android.content.Context context) {
        return new File(CacheSeal.dir(context), SEALED_NAME);
    }

    private static File legacyFile(android.content.Context context) {
        return new File(context.getApplicationContext().getNoBackupFilesDir(), FILE_NAME);
    }

    /** The app's Fleet cache (empty when it cannot be sealed). Worker only. */
    static Map<String, Entry> read(android.content.Context context) {
        return readSealed(sealedFile(context), legacyFile(context), CacheSeal.shared(context));
    }

    /** Replaces the app's Fleet cache with {@code text} (an {@link #encode}). Worker only. */
    static void write(android.content.Context context, String text) throws IOException {
        CacheSeal seal = CacheSeal.shared(context);
        if (seal != null) writeSealed(sealedFile(context), seal, text);
    }

    /** Merges one computer's view into the app's Fleet cache, keeping its git look if {@code entry} has none. Worker only. */
    static void merge(android.content.Context context, String host, Entry entry) throws IOException {
        CacheSeal seal = CacheSeal.shared(context);
        if (seal != null) merge(sealedFile(context), seal, host, entry);
    }

    /**
     * {@code sealed}'s entries. A plain {@code legacy} file is sealed into it
     * first (unless a sealed one exists) and deleted either way; with no seal
     * nothing is read and no plain copy is kept.
     */
    static synchronized Map<String, Entry> readSealed(File sealed, File legacy, CacheSeal seal) {
        if (legacy != null && legacy.isFile()) {
            Map<String, Entry> plain = read(legacy);
            try {
                if (seal != null && !sealed.isFile() && !plain.isEmpty()) writeSealed(sealed, seal, encode(plain));
            } catch (IOException | JSONException unwritable) {
                // Dropped: a cache is a convenience, and plain text must not stay.
            }
            legacy.delete();
        }
        if (seal == null) return new LinkedHashMap<>();
        byte[] bytes = seal.read(sealed, MAX_BYTES);
        return bytes == null ? new LinkedHashMap<>() : decode(new String(bytes, StandardCharsets.UTF_8));
    }

    static synchronized void writeSealed(File sealed, CacheSeal seal, String text) throws IOException {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_BYTES) return;
        File dir = sealed.getParentFile();
        if (dir != null && !dir.isDirectory() && !dir.mkdirs()) throw new IOException("No cache directory");
        seal.write(sealed, bytes);
    }

    static synchronized void merge(File sealed, CacheSeal seal, String host, Entry entry) throws IOException {
        if (host == null || entry == null || !hasTree(entry.view)) return;
        Map<String, Entry> all = readSealed(sealed, null, seal);
        Entry before = all.get(host);
        if (entry.git.isEmpty() && before != null && !before.git.isEmpty()) {
            entry = new Entry(entry.view, before.git, before.gitTruncated);
        }
        all.put(host, entry);
        try {
            writeSealed(sealed, seal, encode(all));
        } catch (JSONException unencodable) {
            throw new IOException(unencodable);
        }
    }
}
