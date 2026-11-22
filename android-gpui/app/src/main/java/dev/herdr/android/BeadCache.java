package dev.herdr.android;

import android.content.Context;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * The Tasks tab's last read of each bead (its plan and comments together, the
 * {@code beads.read} reply), kept in memory and on disk so a bead opens at
 * once, even after a restart, with how old the read is; the panel refreshes it
 * in the background. At most {@link #MAX} entries, a bead's at most
 * {@link #MAX_CHARS} (a longer read keeps its plan and says its comments
 * weren't kept) and a machine's stores listing at most {@link #MAX_LISTING_CHARS};
 * the oldest go first. Only what the panel shows is kept.
 * Thread-safe: the panel's thread writes, the main thread reads.
 */
final class BeadCache {
    /**
     * A real machine's listing is far over a bead's cap (server 173 KB, macro 674 KB on 11-22):
     * under one cap for both, the listing was never kept and every first Tasks entry after a
     * start waited on a cold read (factory-9zz.14).
     */
    static final int MAX = 64, MAX_CHARS = 128 * 1024, MAX_LISTING_CHARS = 4 * 1024 * 1024;
    /** The most the file can hold: every bead at its cap, a few listings, UTF-8 and JSON escapes. */
    static final long MAX_FILE_BYTES = ((long) MAX * MAX_CHARS + 4L * MAX_LISTING_CHARS) * 3;
    static final String FILE_NAME = "panel-beads.json";

    /** One kept read: the reply's text, when it was read (wall clock), and its store's fingerprint then. */
    static final class Entry {
        final String text, fp;
        final long atMs;

        Entry(String text, long atMs, String fp) {
            this.text = text;
            this.atMs = atMs;
            this.fp = fp == null ? "" : fp;
        }
    }

    /** A bead's key: its machine (saved host), store and id. */
    static String key(String hostId, String store, String id) {
        return hostId + "\n" + store + "\n" + id;
    }

    private final File file;
    private final Map<String, Entry> kept = new HashMap<>();

    BeadCache(File file) {
        this.file = file;
    }

    static BeadCache in(Context context) {
        return new BeadCache(new File(context.getApplicationContext().getNoBackupFilesDir(), FILE_NAME));
    }

    synchronized Entry get(String key) {
        return kept.get(key);
    }

    /** Reads the file (on the panel's thread); a damaged one is ignored. */
    void load() {
        Map<String, Entry> read = new HashMap<>();
        try {
            if (file.isFile() && file.length() <= MAX_FILE_BYTES) {
                read = decode(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
            }
        } catch (IOException e) {
            // Nothing kept: beads read as on a first run.
        }
        synchronized (this) {
            for (Map.Entry<String, Entry> e : read.entrySet()) kept.putIfAbsent(e.getKey(), e.getValue());
        }
    }

    /** Keeps a read now, and writes the file atomically on {@code io}. */
    void put(String key, Entry entry, java.util.concurrent.Executor io) {
        entry = bounded(key, entry);
        if (entry == null) return;
        synchronized (this) {
            kept.put(key, entry);
            trim(kept);
        }
        io.execute(this::write);
    }

    private void write() {
        String encoded;
        synchronized (this) {
            encoded = encode(kept);
        }
        try {
            File tmp = new File(file.getPath() + ".tmp");
            Files.write(tmp.toPath(), encoded.getBytes(StandardCharsets.UTF_8));
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            // Kept in memory; the next read tries the disk again.
        }
    }

    /** Whether {@code key} is a machine's stores listing (see {@link #storesKey}). */
    static boolean isListing(String key) {
        return key.endsWith("\nstores\n");
    }

    /**
     * {@code entry} as it may be kept: a listing whole up to its cap; a bead over its cap keeps
     * its plan (comments say they weren't kept), so it is not dropped and read again every tick.
     * Null when even that is over the cap.
     */
    static Entry bounded(String key, Entry entry) {
        int cap = isListing(key) ? MAX_LISTING_CHARS : MAX_CHARS;
        if (entry.text.length() <= cap) return entry;
        if (isListing(key)) return null;
        int split = entry.text.indexOf("\u0007\n");
        String plan = split < 0 ? entry.text : entry.text.substring(0, split);
        return plan.length() <= cap ? new Entry(plan, entry.atMs, entry.fp) : null;
    }

    static void trim(Map<String, Entry> kept) {
        while (kept.size() > MAX) {
            String oldest = null;
            long at = Long.MAX_VALUE;
            for (Map.Entry<String, Entry> e : kept.entrySet()) {
                if (e.getValue().atMs < at) { at = e.getValue().atMs; oldest = e.getKey(); }
            }
            kept.remove(oldest);
        }
    }

    static String encode(Map<String, Entry> kept) {
        JSONObject beads = new JSONObject();
        try {
            for (Map.Entry<String, Entry> e : kept.entrySet()) {
                beads.put(e.getKey(), new JSONObject().put("at", e.getValue().atMs).put("fp", e.getValue().fp).put("text", e.getValue().text));
            }
            return new JSONObject().put("v", 1).put("beads", beads).toString();
        } catch (JSONException impossible) {
            return "{}";
        }
    }

    static Map<String, Entry> decode(String text) {
        Map<String, Entry> out = new HashMap<>();
        try {
            JSONObject root = new JSONObject(text);
            JSONObject beads = root.optInt("v", 0) == 1 ? root.optJSONObject("beads") : null;
            if (beads == null) return out;
            for (Iterator<String> it = beads.keys(); it.hasNext(); ) {
                String key = it.next();
                JSONObject e = beads.optJSONObject(key);
                if (e == null || key.split("\n", -1).length != 3) continue;
                String t = e.optString("text", "");
                long at = e.optLong("at", 0);
                if (t.isEmpty() || t.length() > (isListing(key) ? MAX_LISTING_CHARS : MAX_CHARS) || at <= 0) continue;
                out.put(key, new Entry(t, at, e.optString("fp", "")));
            }
        } catch (JSONException e) {
            return new HashMap<>();
        }
        trim(out);
        return out;
    }

    /** A beads.read reply's text as the plan and the comments it carries. */
    static BeadsModel.Detail detail(String text) {
        int split = text.indexOf("\u0007\n");
        return BeadsModel.parseDetail(split < 0 ? text : text.substring(0, split));
    }

    static BeadsModel.Comments comments(String text) {
        int split = text.indexOf("\u0007\n");
        if (split < 0) {
            BeadsModel.Comments none = new BeadsModel.Comments();
            none.error = "its comments weren't read";
            return none;
        }
        return BeadsModel.parseComments(text.substring(split + 2));
    }

    /** "read just now", "read 4m ago": how old a kept read is. */
    static String age(long atMs, long nowMs) {
        long s = Math.max(0, nowMs - atMs) / 1000;
        return "read " + (s < 60 ? "just now" : UsageModel.span(s) + " ago");
    }

    /** Where a machine's last stores listing is kept (beside the beads). */
    static String storesKey(String hostId) {
        return hostId + "\nstores\n";
    }

    /**
     * The full listing after a read: each store's own part from {@code reply},
     * or, where the reply says "same", its part from {@code previous}; a store
     * the reply no longer lists is gone. So the kept listing always parses on
     * its own (BeadsModel.parseStores), and the Tasks tree draws at once.
     */
    static String keepStores(String previous, String reply) {
        if (reply.startsWith("\u001dnobd")) return reply;
        java.util.Map<String, String> before = chunks(previous == null ? "" : previous);
        StringBuilder out = new StringBuilder();
        for (java.util.Map.Entry<String, String> e : chunks(reply).entrySet()) {
            String chunk = e.getValue();
            if (chunk.matches("(?s)[^\n]*\n\u001c[^\n]*\n\u001fsame\\s*") && before.containsKey(e.getKey())) chunk = before.get(e.getKey());
            out.append('\u001e').append(chunk);
        }
        return out.toString();
    }

    private static java.util.Map<String, String> chunks(String listing) {
        java.util.Map<String, String> out = new java.util.LinkedHashMap<>();
        String[] parts = listing.split("\u001e", -1);
        for (int i = 1; i < parts.length; i++) {
            int nl = parts[i].indexOf('\n');
            out.put(nl < 0 ? parts[i] : parts[i].substring(0, nl), parts[i]);
        }
        return out;
    }
}
