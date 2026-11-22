package dev.herdr.android;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/**
 * One machine's whole beads, mirrored on the phone (factory-9zz.14): every store's
 * {@code bd export} as DUMP_SH sends it (each issue with its plan, labels, dependencies and
 * comments), so the Tasks tree and every bead's plan and comments draw with no read at all.
 * Pure: text in, values out.
 *
 * <p>The kept text frames each store as {@code \x1e<store>\n\x1c<fp>\n\x1dok\n<jsonl>\x1f0\n},
 * or {@code \x1derror\n<words>\n\x1f<code>\n} when that store could not be exported; JSON never
 * holds those control characters raw, so the framing cannot be confused by a bead's text.
 */
final class BeadsMirror {
    /** One store as last exported. */
    static final class Store {
        final String fp, error;
        /** The export's lines, one issue each; empty on error. */
        final List<String> lines;

        Store(String fp, String error, List<String> lines) {
            this.fp = fp;
            this.error = error;
            this.lines = lines;
        }
    }

    /** What a dump said: bd missing, or each store (or its "same"). */
    static final class Dump {
        final String missing;
        final Map<String, Store> stores;
        final Map<String, String> same;
        /** Every store it named, in its order. */
        final List<String> order;

        Dump(String missing, Map<String, Store> stores, Map<String, String> same, List<String> order) {
            this.missing = missing;
            this.stores = stores;
            this.same = same;
            this.order = order;
        }
    }

    /** The most decoded bytes one store's export may be (macro's repo store is 3.5 MB). */
    static final int MAX_STORE_BYTES = 8 * 1024 * 1024;

    private final Map<String, Store> stores;
    private Map<String, BeadsModel.Store> listing;
    /** Beads opened so far, per store (main thread). */
    private final Map<String, Map<String, JSONObject>> byId = new HashMap<>();

    private BeadsMirror(Map<String, Store> stores) {
        this.stores = stores;
    }

    /** The mirror a kept text holds (an empty one for "" or a damaged text). */
    static BeadsMirror of(String kept) {
        return new BeadsMirror(parseKept(kept == null ? "" : kept));
    }

    Map<String, Store> stores() {
        return Collections.unmodifiableMap(stores);
    }

    boolean isEmpty() {
        return stores.isEmpty();
    }

    /** The {@code store=fp} args that tell DUMP_SH which stores it can skip. */
    static List<String> known(String kept) {
        List<String> out = new ArrayList<>();
        for (Map.Entry<String, Store> e : parseKept(kept == null ? "" : kept).entrySet()) {
            Store s = e.getValue();
            if (s.error == null && !s.fp.isEmpty() && s.fp.matches("[0-9.]{1,32}")) out.add(e.getKey() + "=" + s.fp);
        }
        return out;
    }

    /** DUMP_SH's reply text. */
    static Dump parseDump(String out) {
        if (out.startsWith("\u001d")) {
            int nl = out.indexOf('\n');
            String why = (nl < 0 ? out.substring(1) : out.substring(1, nl)).trim();
            return new Dump("nobd".equals(why) ? "bd isn't installed on that machine" : "the machine can't send its beads (" + why + ")",
                    Collections.emptyMap(), Collections.emptyMap(), Collections.emptyList());
        }
        Map<String, Store> stores = new LinkedHashMap<>();
        Map<String, String> same = new LinkedHashMap<>();
        List<String> order = new ArrayList<>();
        String[] chunks = out.split("\u001e", -1);
        for (int i = 1; i < chunks.length; i++) {
            String[] head = chunks[i].split("\n", 3);
            if (head.length < 2 || !head[1].startsWith("\u001c")) continue;
            String name = head[0], fp = head[1].substring(1).trim(), rest = head.length > 2 ? head[2] : "";
            order.add(name);
            if (rest.startsWith("\u001fsame")) { same.put(name, fp); continue; }
            stores.put(name, store(fp, rest));
        }
        return new Dump(null, stores, same, order);
    }

    private static Store store(String fp, String rest) {
        int nl = rest.indexOf('\n');
        if (!rest.startsWith("\u001d") || nl < 0) return new Store("", "its export was cut short", Collections.emptyList());
        String encoding = rest.substring(1, nl);
        String body = rest.substring(nl + 1);
        int end = body.lastIndexOf("\n\u001f");
        if (end < 0) return new Store("", "its export was cut short", Collections.emptyList());
        String code = body.substring(end + 2).trim();
        String payload = body.substring(0, end);
        if ("-".equals(encoding)) {
            String why = BeadsModel.errorLine(payload);
            return new Store("", "bd export exited " + code + (why.isEmpty() ? "" : ": " + why), Collections.emptyList());
        }
        byte[] raw;
        try {
            raw = Base64.getDecoder().decode(payload.trim());
            if ("gzip".equals(encoding)) raw = gunzip(raw);
            else if (!"plain".equals(encoding)) return new Store("", "its export came in a form this app can't read", Collections.emptyList());
        } catch (IllegalArgumentException | IOException e) {
            return new Store("", "its export came garbled", Collections.emptyList());
        }
        if (raw == null) return new Store("", "its export is too big for this phone", Collections.emptyList());
        List<String> lines = new ArrayList<>();
        for (String line : new String(raw, StandardCharsets.UTF_8).split("\n")) if (line.startsWith("{")) lines.add(line);
        return new Store(fp, null, lines);
    }

    /** At most {@link #MAX_STORE_BYTES}, else null. */
    private static byte[] gunzip(byte[] gz) throws IOException {
        try (InputStream in = new GZIPInputStream(new ByteArrayInputStream(gz))) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[64 * 1024];
            for (int n; (n = in.read(buf)) > 0; ) {
                out.write(buf, 0, n);
                if (out.size() > MAX_STORE_BYTES) return null;
            }
            return out.toByteArray();
        }
    }

    /**
     * The kept text after a dump: each store the dump listed, from the dump, or from
     * {@code kept} where it said "same"; a store it no longer lists is gone. A "same" for a
     * store the phone no longer has is left out, so the next dump sends it whole.
     */
    static String merge(String kept, Dump dump) {
        Map<String, Store> before = parseKept(kept == null ? "" : kept);
        Map<String, Store> after = new LinkedHashMap<>();
        for (String name : dump.order) {
            Store now = dump.stores.get(name), was = before.get(name);
            if (now != null) after.put(name, now);
            else if (was != null && was.error == null) after.put(name, was);
        }
        return encode(after);
    }

    static String encode(Map<String, Store> stores) {
        StringBuilder out = new StringBuilder();
        for (Map.Entry<String, Store> e : stores.entrySet()) {
            Store s = e.getValue();
            out.append('\u001e').append(e.getKey()).append('\n').append('\u001c').append(s.fp).append('\n');
            if (s.error != null) {
                out.append("\u001derror\n").append(s.error.replace('\n', ' ')).append("\n\u001f1\n");
                continue;
            }
            out.append("\u001dok\n");
            for (String line : s.lines) out.append(line).append('\n');
            out.append("\u001f0\n");
        }
        return out.toString();
    }

    private static Map<String, Store> parseKept(String kept) {
        Map<String, Store> out = new LinkedHashMap<>();
        String[] chunks = kept.split("\u001e", -1);
        for (int i = 1; i < chunks.length; i++) {
            String[] head = chunks[i].split("\n", 4);
            if (head.length < 4 || !head[1].startsWith("\u001c")) continue;
            String fp = head[1].substring(1);
            String body = head[3];
            int end = body.lastIndexOf("\u001f");
            if (end < 0) continue;
            if ("\u001derror".equals(head[2])) {
                out.put(head[0], new Store(fp, body.substring(0, end).trim(), Collections.emptyList()));
                continue;
            }
            if (!"\u001dok".equals(head[2])) continue;
            List<String> lines = new ArrayList<>();
            for (String line : body.substring(0, end).split("\n")) if (line.startsWith("{")) lines.add(line);
            out.put(head[0], new Store(fp, null, lines));
        }
        return out;
    }

    // ---- what the panel reads ----

    /** The stores as the Tasks tree takes them (BeadsModel.Store, as a STORES_SH read gives). */
    Map<String, BeadsModel.Store> listing() {
        if (listing != null) return listing;
        Map<String, BeadsModel.Store> out = new LinkedHashMap<>();
        for (Map.Entry<String, Store> e : stores.entrySet()) {
            Store s = e.getValue();
            if (s.error != null) { out.put(e.getKey(), new BeadsModel.Store(null, s.error, "", false)); continue; }
            BeadsModel.BeadList list = BeadsModel.parseBeads("[" + String.join(",", s.lines) + "]");
            out.put(e.getKey(), new BeadsModel.Store(list.beads, list.error, s.fp, false));
        }
        return listing = out;
    }

    /**
     * Bead {@code id} of {@code store} as one {@code beads.read} reply (its plan, then its
     * comments), so BeadCache.detail and BeadCache.comments read it as they read a live one;
     * null when the mirror has no such bead. Its revision is the store's fingerprint, and it is
     * fresh: an export is the database itself at the time of the dump.
     */
    String read(String store, String id) {
        JSONObject bead = bead(store, id);
        if (bead == null) return null;
        JSONArray comments = bead.optJSONArray("comments");
        JSONObject plan;
        try {
            plan = new JSONObject(bead.toString());
        } catch (JSONException impossible) {
            return null;
        }
        plan.remove("comments");
        Store s = stores.get(store);
        String rev = s == null || s.fp.isEmpty() ? "-" : s.fp;
        return "\u001e[" + plan + "]\n\u001d[]\n\u001c" + rev + " fresh\n\u001fok\n\n\u0007\n\u001d"
                + (comments == null ? "[]" : comments.toString()) + "\n\u001fok\n";
    }

    /**
     * Bead {@code id}'s line, parsed alone: found by its id near the start of each line (bd
     * export writes it there), so opening a bead never parses its whole store on the main thread.
     */
    private JSONObject bead(String store, String id) {
        Map<String, JSONObject> seen = byId.computeIfAbsent(store, k -> new HashMap<>());
        JSONObject got = seen.get(id);
        if (got != null) return got;
        Store s = stores.get(store);
        if (s == null) return null;
        String needle = "\"id\":" + JSONObject.quote(id);
        for (String line : s.lines) {
            int at = line.indexOf(needle);
            if (at < 0 || at > 256) continue;
            try {
                JSONObject o = new JSONObject(line);
                if (!id.equals(o.optString("id", ""))) continue;
                seen.put(id, o);
                return o;
            } catch (JSONException e) {
                // A damaged line is one bead missing, never the store.
            }
        }
        return null;
    }
}
