package dev.herdr.android;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Copies of the text files agents reference, for reading offline (PR-F of the offline redesign,
 * factory-9zz.1): what {@code file.read} gave for a file while the link was up, one copy per
 * machine ({@link MachineKey}) and resolved path, under {@code noBackupFilesDir/refs-v1/}.
 *
 * <p>A request names a file as the agent printed it: relative to its terminal's folder, from
 * {@code ~/}, or absolute. The read's answer says where it resolved; a request is remembered as
 * an alias of that path, so two agents naming one file share one copy, and the same path on two
 * machines is two copies.
 *
 * <p>A read that failed (missing on the host, unreadable, not text, too large) is kept too, so
 * offline says why there is no copy. Nothing here is the file now: the view says "saved copy · as
 * of 14:02 · may be outdated".
 *
 * <p>Bounded on disk, generously (Wolf's steer): {@link #MAX_FILE_BYTES} per copy (a longer read
 * keeps its first part and says so), and least recently used first out past the total and count
 * budgets, or when a copy was not opened or referenced for {@link #MAX_IDLE_MS}. Nothing is written
 * while the phone has less than the free-space floor. Eviction only ever touches these copies.
 * Every method does disk work: callers run it on their own worker thread.
 */
final class RefFiles {
    static final int MAX_FILE_BYTES = 8 * 1024 * 1024;
    static final long MAX_TOTAL_BYTES = 1024L * 1024 * 1024;
    static final int MAX_FILES = 20_000;
    static final long MAX_IDLE_MS = 30L * 24 * 60 * 60 * 1000;
    static final long FREE_FLOOR_BYTES = 8L * 1024 * 1024 * 1024;
    /** Aliases kept per machine: requests as agents printed them. */
    static final int MAX_ALIASES = 4096;
    /** The longest header line a copy may start with. */
    static final int MAX_HEADER = 8192;
    private static final String SUFFIX = ".ref";
    private static final String ALIASES = "aliases.json";

    /** Budgets, scaled down in tests. */
    static final class Budget {
        final long totalBytes;
        final int files;
        final long idleMs;
        final long freeFloorBytes;
        final int fileBytes;

        Budget(long totalBytes, int files, long idleMs, long freeFloorBytes, int fileBytes) {
            this.totalBytes = totalBytes;
            this.files = files;
            this.idleMs = idleMs;
            this.freeFloorBytes = freeFloorBytes;
            this.fileBytes = fileBytes;
        }

        static final Budget PHONE = new Budget(MAX_TOTAL_BYTES, MAX_FILES, MAX_IDLE_MS, FREE_FLOOR_BYTES, MAX_FILE_BYTES);
    }

    /** What a read of a file was, as the view shows it offline. */
    static final class Copy {
        final String path;
        /** The text (empty for a failed read). */
        final String text;
        /** Lines in the whole file, as the host counted them. */
        final int lines;
        /** The text is the whole file. */
        final boolean whole;
        /** Why there is no text: the host's error kind, or null. */
        final String error;
        final long asOfMs;

        Copy(String path, String text, int lines, boolean whole, String error, long asOfMs) {
            this.path = path;
            this.text = text;
            this.lines = lines;
            this.whole = whole;
            this.error = error;
            this.asOfMs = asOfMs;
        }

        /**
         * The copy as core_session's {@code file.read} reply, with {@code saved_as_of_ms}: what
         * {@link FileDocument#parse} reads, so the view draws a copy as it draws a live read.
         */
        String reply() {
            try {
                JSONObject reply = new JSONObject().put("path", path).put("saved_as_of_ms", asOfMs);
                if (error != null) return reply.put("queued", false).put("error", error).toString();
                return reply.put("queued", true).put("text", text).put("lines", lines).put("last", whole).toString();
            } catch (JSONException impossible) {
                return "{\"queued\":false}";
            }
        }
    }

    private final File dir;
    private final Budget budget;
    private final java.util.function.LongSupplier freeBytes;
    private boolean started;

    RefFiles(File dir) {
        this(dir, Budget.PHONE, dir::getUsableSpace);
    }

    RefFiles(File dir, Budget budget, java.util.function.LongSupplier freeBytes) {
        this.dir = dir;
        this.budget = budget;
        this.freeBytes = freeBytes;
    }

    private static RefFiles shared;

    /** The process's one store; its disk work runs on the caller's worker thread. */
    static synchronized RefFiles shared(android.content.Context context) {
        if (shared == null) {
            File dir = new File(context.getApplicationContext().getNoBackupFilesDir(), "refs-v1");
            // The referenced files' share of the offline budget (1 GiB of a full one).
            OfflineBudget budget = OfflineBudget.forFree(dir.getParentFile().getUsableSpace());
            shared = new RefFiles(dir, new Budget(Math.min(MAX_TOTAL_BYTES, budget.refBytes), MAX_FILES, MAX_IDLE_MS,
                    budget.freeFloorBytes, MAX_FILE_BYTES), dir.getParentFile()::getUsableSpace);
        }
        return shared;
    }

    /** Makes the directory and sweeps what a killed write left behind. */
    synchronized void start() {
        started = true;
        if (!dir.isDirectory() && !dir.mkdirs()) return;
        for (File machine : list(dir, File::isDirectory)) {
            for (File tmp : list(machine, f -> f.getName().endsWith(".tmp"))) tmp.delete();
        }
    }

    /**
     * Keeps what {@code file.read} answered (core_session's reply JSON) for {@code requested}, as
     * {@code terminal} on {@code machine} named it, read at {@code asOfMs}. False when nothing was
     * kept: no machine, a malformed reply, the free-space floor, or a failed write (an older copy
     * then stays whole).
     */
    synchronized boolean save(MachineKey machine, String terminal, String requested, String raw, long asOfMs) {
        if (!started) start();
        if (machine == null || terminal == null || requested == null || requested.isEmpty() || raw == null) return false;
        JSONObject reply;
        try {
            reply = new JSONObject(raw);
        } catch (JSONException malformed) {
            return false;
        }
        // A folder's listing is read live only: its copy would need its own format, and a saved
        // empty "file" in its place would mislead.
        if (reply.optBoolean("folder", false)) return false;
        String path = reply.optString("path", "");
        // Unresolved (a failed read of a relative name): kept under the request itself.
        if (!path.startsWith("/")) path = requested.startsWith("/") ? requested : aliasKey(terminal, requested);
        // A refusal of the session itself (not connected, a stale handle) says nothing of the file.
        if (!reply.optBoolean("queued", false) && !fileError(reply.optString("error", ""))) return false;
        if (freeBytes.getAsLong() < budget.freeFloorBytes) return false;
        String error = reply.optBoolean("queued", false) ? null : reply.optString("error", "");
        String text = error == null ? reply.optString("text", "") : "";
        int lines = reply.optInt("lines", 0);
        boolean whole = error == null && reply.optBoolean("last", true);
        byte[] body = text.getBytes(StandardCharsets.UTF_8);
        if (body.length > budget.fileBytes) {
            text = firstLines(text, budget.fileBytes);
            body = text.getBytes(StandardCharsets.UTF_8);
            whole = false;
        }
        String header;
        try {
            JSONObject h = new JSONObject().put("v", 1).put("core", machine.core).put("hostkey", machine.hostKey)
                    .put("path", path).put("as_of_ms", asOfMs).put("lines", lines).put("whole", whole)
                    .put("sha256", digest(body));
            if (error != null) h.put("error", error);
            header = h.toString();
        } catch (JSONException impossible) {
            return false;
        }
        if (header.length() >= MAX_HEADER) return false;
        File file = file(machine, path);
        Header existing = header(file);
        if (existing != null && existing.asOfMs > asOfMs) return false;
        makeRoom(file, header.length() + 1L + body.length, asOfMs);
        try {
            Outbox.write(file, header + "\n" + text);
        } catch (IOException failed) {
            return false;
        }
        file.setLastModified(asOfMs);
        alias(machine, terminal, requested, path);
        return true;
    }

    /** The copy {@code terminal} on {@code machine} would read for {@code requested}, or null; opening it counts as use. */
    synchronized Copy open(MachineKey machine, String terminal, String requested, long nowMs) {
        if (machine == null || requested == null || requested.isEmpty()) return null;
        String path = resolved(machine, terminal, requested);
        if (path == null) return null;
        File file = file(machine, path);
        String text = read(file, (long) budget.fileBytes + MAX_HEADER + 1);
        if (text == null) return null;
        int end = text.indexOf('\n');
        if (end <= 0 || end >= MAX_HEADER) return null;
        Header header = Header.parse(text.substring(0, end));
        if (header == null || !header.of(machine, path)) return null;
        file.setLastModified(nowMs);
        String shown = path.startsWith("/") ? path : requested;
        return new Copy(shown, text.substring(end + 1), header.lines, header.whole, header.error, header.asOfMs);
    }

    /**
     * Whether {@code requested} by {@code terminal} has a copy (or a kept failure) read at or after
     * {@code sinceMs}. Being referenced again counts as use at {@code nowMs}, so a file an agent
     * keeps naming is never the first out.
     */
    synchronized boolean fresh(MachineKey machine, String terminal, String requested, long sinceMs, long nowMs) {
        if (machine == null || requested == null || requested.isEmpty()) return false;
        String path = resolved(machine, terminal, requested);
        if (path == null) return false;
        File file = file(machine, path);
        Header header = header(file);
        if (header == null || !header.of(machine, path)) return false;
        file.setLastModified(nowMs);
        return header.asOfMs >= sinceMs;
    }

    /**
     * The path a request's copy is kept under: itself when absolute, else where a read resolved
     * it, else the request itself (a failed read of a name no read resolved).
     */
    private String resolved(MachineKey machine, String terminal, String requested) {
        if (requested.startsWith("/")) return requested;
        String key = aliasKey(terminal, requested);
        String path = aliases(machine).get(key);
        return path != null ? path : key;
    }

    /** The file a copy of {@code path} on {@code machine} is kept in. */
    File file(MachineKey machine, String path) {
        return new File(machineDir(machine), digest((path).getBytes(StandardCharsets.UTF_8)) + SUFFIX);
    }

    private File machineDir(MachineKey machine) {
        return new File(dir, digest((machine.core + "\n" + machine.hostKey).getBytes(StandardCharsets.UTF_8)).substring(0, 24));
    }

    private static String aliasKey(String terminal, String requested) {
        return (terminal == null ? "" : terminal) + "\n" + requested;
    }

    private Map<String, String> aliases(MachineKey machine) {
        Map<String, String> out = new LinkedHashMap<>();
        String text = read(new File(machineDir(machine), ALIASES), 4L * 1024 * 1024);
        if (text == null) return out;
        try {
            JSONObject all = new JSONObject(text);
            for (Iterator<String> keys = all.keys(); keys.hasNext(); ) {
                String key = keys.next();
                String path = all.optString(key, "");
                if (path.startsWith("/")) out.put(key, path);
            }
        } catch (JSONException damaged) {
            return new LinkedHashMap<>();
        }
        return out;
    }

    /** Remembers that {@code requested} by {@code terminal} resolved to {@code path}, newest last, bounded. */
    private void alias(MachineKey machine, String terminal, String requested, String path) {
        if (requested.startsWith("/") || !path.startsWith("/")) return;
        Map<String, String> all = aliases(machine);
        String key = aliasKey(terminal, requested);
        if (path.equals(all.get(key))) return;
        all.remove(key);
        all.put(key, path);
        Iterator<String> oldest = all.keySet().iterator();
        while (all.size() > MAX_ALIASES && oldest.hasNext()) {
            oldest.next();
            oldest.remove();
        }
        try {
            JSONObject out = new JSONObject();
            for (Map.Entry<String, String> e : all.entrySet()) out.put(e.getKey(), e.getValue());
            Outbox.write(new File(machineDir(machine), ALIASES), out.toString());
        } catch (JSONException | IOException lost) {
            // The copy is kept; only a relative request misses offline.
        }
    }

    /**
     * Makes room for {@code size} more bytes in {@code keep}: copies idle past the budget go, then
     * the least recently used until the total and the count fit. {@code keep} itself is replaced,
     * not counted twice.
     */
    private void makeRoom(File keep, long size, long nowMs) {
        List<File> all = new ArrayList<>();
        for (File machine : list(dir, File::isDirectory)) {
            all.addAll(Arrays.asList(list(machine, f -> f.getName().endsWith(SUFFIX))));
        }
        long total = 0;
        for (Iterator<File> it = all.iterator(); it.hasNext(); ) {
            File f = it.next();
            if (f.equals(keep)) {
                it.remove();
            } else if (nowMs - f.lastModified() > budget.idleMs) {
                f.delete();
                it.remove();
            } else {
                total += f.length();
            }
        }
        all.sort(Comparator.comparingLong(File::lastModified));
        Iterator<File> oldest = all.iterator();
        int count = all.size();
        while (oldest.hasNext() && (count + 1 > budget.files || total + size > budget.totalBytes)) {
            File f = oldest.next();
            total -= f.length();
            count--;
            f.delete();
        }
    }

    /** Whether a {@code file.read} error is about the file (kept, shown offline), not the link. */
    static boolean fileError(String error) {
        if (error == null || error.isEmpty()) return false;
        return error.contains("not_found") || error.contains("not_text") || error.contains("Permission denied")
                || error.contains("is not a regular file") || error.contains("is over");
    }

    /** The longest run of whole lines of {@code text} within {@code maxBytes} of UTF-8. */
    static String firstLines(String text, int maxBytes) {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        if (bytes.length <= maxBytes) return text;
        int end = maxBytes;
        while (end > 0 && bytes[end - 1] != '\n') end--;
        return new String(bytes, 0, end, StandardCharsets.UTF_8);
    }

    private static Header header(File file) {
        if (file == null || !file.isFile()) return null;
        try (InputStream in = new FileInputStream(file)) {
            byte[] buffer = new byte[MAX_HEADER];
            int n = 0;
            for (int r; n < buffer.length && (r = in.read(buffer, n, buffer.length - n)) > 0; ) n += r;
            String start = new String(buffer, 0, n, StandardCharsets.UTF_8);
            int end = start.indexOf('\n');
            return end <= 0 ? null : Header.parse(start.substring(0, end));
        } catch (IOException unreadable) {
            return null;
        }
    }

    private static String read(File file, long max) {
        if (file == null || !file.isFile() || file.length() > max) return null;
        try (InputStream in = new FileInputStream(file)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[65536];
            for (int n; (n = in.read(buffer)) > 0; ) {
                out.write(buffer, 0, n);
                if (out.size() > max) return null;
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        } catch (IOException unreadable) {
            return null;
        }
    }

    private static File[] list(File parent, java.io.FileFilter filter) {
        File[] files = parent.listFiles(filter);
        return files == null ? new File[0] : files;
    }

    private static String digest(byte[] bytes) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder out = new StringBuilder();
            for (byte b : hash) out.append(String.format(java.util.Locale.ROOT, "%02x", b));
            return out.toString();
        } catch (NoSuchAlgorithmException impossible) {
            return Integer.toHexString(Arrays.hashCode(bytes));
        }
    }

    private static final class Header {
        final String core, hostKey, path, error;
        final long asOfMs;
        final int lines;
        final boolean whole;

        private Header(JSONObject h) {
            core = h.optString("core", "");
            hostKey = h.optString("hostkey", "");
            path = h.optString("path", "");
            asOfMs = h.optLong("as_of_ms", 0);
            lines = h.optInt("lines", 0);
            whole = h.optBoolean("whole", false);
            error = h.has("error") ? h.optString("error", "") : null;
        }

        static Header parse(String line) {
            try {
                JSONObject h = new JSONObject(line);
                return h.optInt("v") == 1 ? new Header(h) : null;
            } catch (JSONException damaged) {
                return null;
            }
        }

        boolean of(MachineKey machine, String path) {
            return machine.core.equals(core) && machine.hostKey.equals(hostKey) && this.path.equals(path);
        }
    }
}
