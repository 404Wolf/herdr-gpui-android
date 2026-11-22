package dev.herdr.android;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Agents' transcripts for reading offline (stage 2 of the offline design): the text the core's
 * bounded read gave while online, kept per machine, terminal and incarnation
 * ({@link TranscriptKey}), under {@code noBackupFilesDir/transcripts-v1/<machine>/}.
 *
 * <p>A transcript is shown offline only where identity is proven: the last list a direct link
 * gave for that machine ({@link #observe}) must name the same terminal running the same
 * incarnation. A replaced program, another machine, a list from a core without incarnations, or
 * no list at all shows nothing. The phone never claims it is the pane's content now: it reads
 * "transcript as of 14:02 · may be outdated".
 *
 * <p>Depth accrues (the offline redesign's §7.2). A read of history (anything but a full-screen
 * program's screen: a shell's scrollback, a stitched read, an agent's conversation) is appended
 * to what is kept, anchored on the lines the two share; with no overlap a {@link #GAP} row says
 * lines may be missing, and nothing is joined by guesswork. A screen-only read replaces the copy.
 * Each transcript is a body, appended to, and a small header beside it.
 *
 * <p>Bounded, generously (Wolf's steer): the store has its share of the {@link OfflineBudget};
 * each transcript keeps the newest lines its age allows (32 MiB while hot, tapering to 512 KiB,
 * gone after 90 days with no growth); the coldest go first when the store is full; nothing is
 * written under the free-space floor. Opening reads only the newest {@link #WINDOW_LINES} lines
 * (at most {@link #WINDOW_BYTES}). Writes are tmp + fsync + rename, or an append; {@code .tmp}
 * files are swept at start. Every method does disk work: callers run it on their own worker thread.
 */
final class Transcripts {
    /** The most one read may give (the keeper's bounded read asks for this). */
    static final int MAX_TEXT_BYTES = 256 * 1024;
    /** Transcripts kept at once, at most. */
    static final int MAX_FILES = 4096;
    /** What opening a transcript reads at most: its newest lines. */
    static final int WINDOW_LINES = 4000;
    static final int WINDOW_BYTES = 4 * 1024 * 1024;
    /** Lines of the kept tail a new read must repeat, in order, to be joined to it. */
    static final int ANCHOR_LINES = 50;
    /** The row put where two reads did not overlap: lines between them may be missing. */
    static final String GAP = "· gap · lines may be missing here ·";
    /** The longest header a transcript may have. */
    static final int MAX_HEADER = 4096;
    private static final String LEGACY = ".txt";
    private static final String BODY = ".body";
    private static final String HEAD = ".head";
    private static final String SEEN = "seen.json";
    /** At most this many terminals of one machine are remembered from its last list. */
    static final int MAX_SEEN = 1024;

    private static Transcripts shared;

    /** The process's one store; its disk work runs on the caller's worker thread. */
    static synchronized Transcripts shared(android.content.Context context) {
        if (shared == null) {
            File dir = new File(context.getApplicationContext().getNoBackupFilesDir(), "transcripts-v1");
            shared = new Transcripts(dir, OfflineBudget.forFree(dir.getParentFile().getUsableSpace()),
                    dir.getParentFile()::getUsableSpace);
        }
        return shared;
    }

    /** One terminal as a direct list showed it: when it was created and what runs in it. */
    static final class Seen {
        final Long createdMs;
        final String incarnation;

        Seen(Long createdMs, String incarnation) {
            this.createdMs = createdMs;
            this.incarnation = incarnation;
        }
    }

    /** A transcript as read back: what it is of, its newest lines, and when it was read. */
    static final class Transcript {
        final TranscriptKey key;
        final String text;
        /** Older lines than these are not shown: cut by the core or the taper, or past the window. */
        final boolean truncated;
        /**
         * The program's screen only, not its history: what the core's bounded read gives for a
         * full-screen program (Claude Code, Codex). It shows nothing the held frame does not.
         */
        final boolean screen;
        final long asOfMs;

        Transcript(TranscriptKey key, String text, boolean truncated, boolean screen, long asOfMs) {
            this.key = key;
            this.text = text;
            this.truncated = truncated;
            this.screen = screen;
            this.asOfMs = asOfMs;
        }
    }

    private final File dir;
    private final OfflineBudget budget;
    private final java.util.function.LongSupplier freeBytes;
    private final int maxFiles;
    private boolean started;

    Transcripts(File dir) {
        this(dir, OfflineBudget.forFree(Long.MAX_VALUE / 64), () -> Long.MAX_VALUE);
    }

    Transcripts(File dir, OfflineBudget budget, java.util.function.LongSupplier freeBytes) {
        this(dir, budget, freeBytes, MAX_FILES);
    }

    /** With a smaller count bound, for tests. */
    Transcripts(File dir, OfflineBudget budget, java.util.function.LongSupplier freeBytes, int maxFiles) {
        this.dir = dir;
        this.budget = budget;
        this.freeBytes = freeBytes;
        this.maxFiles = maxFiles;
    }

    /** Makes the directory and sweeps what a killed write left behind. */
    synchronized void start() {
        started = true;
        if (!dir.isDirectory() && !dir.mkdirs()) return;
        for (File machine : list(dir, File::isDirectory)) {
            for (File f : list(machine, (d, name) -> name.endsWith(".tmp"))) f.delete();
        }
    }

    /** "transcript as of 14:02 · may be outdated". */
    static String label(String when) {
        return "transcript as of " + when + " · may be outdated";
    }

    /**
     * The transcript view's title: its pane, when it was read, and what it is not. A screen-only
     * read says "screen only"; one with older lines not shown says "older lines not kept".
     */
    static String title(String pane, String when, Transcript t) {
        return pane + " · " + label(when) + (t.screen ? " · screen only" : "")
                + (t.truncated ? " · older lines not kept" : "");
    }

    /** The file a transcript was kept in before depth accrued (one header line, then the text). */
    File file(TranscriptKey key) {
        return new File(machineDir(key.machine), base(key) + LEGACY);
    }

    File body(TranscriptKey key) {
        return new File(machineDir(key.machine), base(key) + BODY);
    }

    File head(TranscriptKey key) {
        return new File(machineDir(key.machine), base(key) + HEAD);
    }

    private static String base(TranscriptKey key) {
        return key.address + "-" + key.createdMs + "-" + key.incarnation;
    }

    private File machineDir(MachineKey machine) {
        return new File(dir, digest(machine.core + "\n" + machine.hostKey));
    }

    private static String digest(String text) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder();
            for (int i = 0; i < 12; i++) out.append(String.format(java.util.Locale.ROOT, "%02x", hash[i]));
            return out.toString();
        } catch (NoSuchAlgorithmException impossible) {
            return Integer.toHexString(text.hashCode());
        }
    }

    synchronized boolean save(TranscriptKey key, String text, boolean truncated, long asOfMs) {
        return save(key, text, truncated, false, asOfMs);
    }

    /**
     * Keeps {@code text}, read at {@code asOfMs}, as the transcript of {@code key}: a history read
     * is joined to what is kept, a screen-only read ({@code screen}) replaces it. False when it is
     * larger than one read may be, an equal or newer read is already kept, the phone is under its
     * free-space floor, or the write failed (what was kept then stays whole).
     */
    synchronized boolean save(TranscriptKey key, String text, boolean truncated, boolean screen, long asOfMs) {
        if (!started) start();
        if (key == null || text == null) return false;
        if (text.getBytes(StandardCharsets.UTF_8).length > MAX_TEXT_BYTES) return false;
        if (freeBytes.getAsLong() < budget.freeFloorBytes) return false;
        Header existing = readHead(head(key));
        if (existing != null && !existing.names(key)) existing = null;
        if (existing != null && existing.asOfMs >= asOfMs) return false;
        Header legacy = existing == null ? header(file(key)) : null;
        if (legacy != null && legacy.names(key) && legacy.asOfMs >= asOfMs) return false;
        File body = body(key);
        String next = lines(text);
        boolean cut = truncated;
        long grewMs = asOfMs;
        try {
            // Room for the read and its header.
            makeRoom(body, next.getBytes(StandardCharsets.UTF_8).length + (long) MAX_HEADER, asOfMs);
            if (screen || existing == null || existing.screen || !body.isFile()) {
                if (existing != null && existing.screen == screen && sameAs(body, next)) grewMs = existing.grewMs;
                writeAtomically(body, next);
            } else {
                Join join = join(body, next);
                if (join.append.isEmpty() && join.dropFrom < 0) grewMs = existing.grewMs;
                if (join.dropFrom >= 0) {
                    try (RandomAccessFile f = new RandomAccessFile(body, "rw")) {
                        f.setLength(join.dropFrom);
                    }
                }
                append(body, join.append);
                // The oldest lines are as they were first kept; a gap says nothing of them.
                cut = existing.truncated;
            }
            long depth = OfflineBudget.depth(0);
            if (body.length() > depth) {
                taper(body, depth);
                cut = true;
            }
            writeHead(key, new Header(key, asOfMs, grewMs, cut, screen));
        } catch (IOException | JSONException failed) {
            return false;
        }
        file(key).delete();
        return true;
    }

    /** The transcript of exactly {@code key}, its newest lines, or null: a header naming anything else is a miss. */
    synchronized Transcript open(TranscriptKey key) {
        if (key == null) return null;
        Header head = readHead(head(key));
        if (head != null && head.names(key)) {
            Window w = window(body(key));
            if (w == null) return null;
            // Kept as whole lines; shown as read, without the last line's end.
            String text = w.text.endsWith("\n") ? w.text.substring(0, w.text.length() - 1) : w.text;
            return new Transcript(key, text, head.truncated || w.older, head.screen, head.asOfMs);
        }
        // Kept before depth accrued: one header line, then the text.
        String text = Outbox.read(file(key));
        if (text == null) return null;
        int end = text.indexOf('\n');
        if (end <= 0 || end >= MAX_HEADER) return null;
        Header header = Header.parse(text.substring(0, end), 1);
        if (header == null || !header.names(key)) return null;
        String body = text.substring(end + 1);
        if (body.getBytes(StandardCharsets.UTF_8).length > MAX_TEXT_BYTES) return null;
        return new Transcript(key, body, header.truncated, header.screen, header.asOfMs);
    }

    /**
     * A direct link to {@code machine} listed these live terminals at {@code asOfMs}. It is what
     * proves a transcript offline, so it replaces the last list; transcripts of terminals not in
     * it, or of another incarnation than it names, are no longer any pane's and go.
     */
    synchronized void observe(MachineKey machine, Map<String, Seen> terminals, long asOfMs) {
        if (machine == null || terminals == null) return;
        if (!started) start();
        File machineDir = machineDir(machine);
        if (!machineDir.isDirectory() && !machineDir.mkdirs()) return;
        JSONObject list = new JSONObject();
        try {
            int n = 0;
            for (Map.Entry<String, Seen> e : terminals.entrySet()) {
                if (n++ >= MAX_SEEN || !TranscriptKey.plainAddress(e.getKey())) continue;
                Seen s = e.getValue();
                JSONObject item = new JSONObject().put("created_ms", s.createdMs == null ? JSONObject.NULL : s.createdMs)
                        .put("incarnation", s.incarnation == null ? JSONObject.NULL : s.incarnation);
                list.put(e.getKey(), item);
            }
            Outbox.write(new File(machineDir, SEEN), new JSONObject().put("v", 1).put("core", machine.core)
                    .put("hostkey", machine.hostKey).put("as_of_ms", asOfMs).put("terminals", list).toString());
        } catch (JSONException | IOException unsaved) {
            return;
        }
        for (File f : list(machineDir, (d, name) -> name.endsWith(LEGACY) || name.endsWith(HEAD))) {
            Header h = f.getName().endsWith(HEAD) ? readHead(f) : header(f);
            Seen s = h == null ? null : terminals.get(h.address);
            boolean current = s != null && s.createdMs != null && s.createdMs == h.createdMs
                    && h.incarnation.equals(s.incarnation);
            if (current) continue;
            f.delete();
            if (f.getName().endsWith(HEAD)) sibling(f, BODY).delete();
        }
    }

    /**
     * The transcript of {@code address} on {@code machine} for the program the last direct list
     * showed running there, or null when that list does not prove one.
     */
    synchronized Transcript proven(MachineKey machine, String address) {
        if (machine == null || !TranscriptKey.plainAddress(address)) return null;
        JSONObject seen;
        try {
            String text = Outbox.read(new File(machineDir(machine), SEEN));
            if (text == null) return null;
            seen = new JSONObject(text);
        } catch (JSONException damaged) {
            return null;
        }
        if (!machine.core.equals(seen.optString("core")) || !machine.hostKey.equals(seen.optString("hostkey"))) return null;
        JSONObject terminals = seen.optJSONObject("terminals");
        JSONObject item = terminals == null ? null : terminals.optJSONObject(address);
        if (item == null || item.isNull("incarnation") || item.isNull("created_ms")) return null;
        TranscriptKey key = TranscriptKey.of(machine, address, item.optLong("created_ms"), item.optString("incarnation"));
        return open(key);
    }

    /** How many transcripts are kept. */
    synchronized int count() {
        return heads().size() + legacy().size();
    }

    /** Their bytes on disk. */
    synchronized long bytes() {
        long total = 0;
        for (File f : heads()) total += f.length() + sibling(f, BODY).length();
        for (File f : legacy()) total += f.length();
        return total;
    }

    private List<File> heads() {
        List<File> out = new ArrayList<>();
        for (File machine : list(dir, File::isDirectory)) out.addAll(Arrays.asList(list(machine, (d, n) -> n.endsWith(HEAD))));
        return out;
    }

    private List<File> legacy() {
        List<File> out = new ArrayList<>();
        for (File machine : list(dir, File::isDirectory)) out.addAll(Arrays.asList(list(machine, (d, n) -> n.endsWith(LEGACY))));
        return out;
    }

    /**
     * Before {@code size} more bytes go to {@code target}, at {@code nowMs}: each transcript keeps
     * the depth its age allows (newest lines), one past cold goes, and then the coldest go until
     * the store's share of the budget and the count hold. {@code target} is never evicted for
     * another: it is the one being read now, so it is the hottest.
     */
    private void makeRoom(File target, long size, long nowMs) throws IOException {
        List<File> heads = heads();
        List<long[]> kept = new ArrayList<>();   // {index, grewMs, bytes}
        long total = 0;
        for (int i = 0; i < heads.size(); i++) {
            File head = heads.get(i);
            File body = sibling(head, BODY);
            if (body.equals(target)) continue;
            Header h = readHead(head);
            long grew = h == null ? 0 : h.grewMs;
            long depth = OfflineBudget.depth(nowMs - grew);
            if (depth == 0) {
                body.delete();
                head.delete();
                continue;
            }
            if (body.length() > depth) {
                taper(body, depth);
                if (h != null) {
                    try {
                        writeHead(head, new Header(h, true));
                    } catch (JSONException impossible) {
                        // The header stays as it was; its text is still the newest lines.
                    }
                }
            }
            long bytes = body.length() + head.length();
            total += bytes;
            kept.add(new long[] {i, grew, bytes});
        }
        for (File f : legacy()) total += f.length();
        kept.sort((a, b) -> Long.compare(a[1], b[1]));   // coldest (oldest growth) first
        int count = kept.size() + legacy().size();
        long existingTarget = target.isFile() ? target.length() : 0;
        for (long[] k : kept) {
            if (count + 1 <= maxFiles && total + size + existingTarget <= budget.transcriptBytes) break;
            File head = heads.get((int) k[0]);
            sibling(head, BODY).delete();
            head.delete();
            total -= k[2];
            count--;
        }
    }

    /** Keeps the newest whole lines of {@code body} within {@code depth} bytes. */
    private static void taper(File body, long depth) throws IOException {
        long length = body.length();
        if (length <= depth) return;
        // One byte more than kept: whether the cut falls at a line's start.
        byte[] tail = readRange(body, length - depth - 1, (int) depth + 1);
        int start = 0;
        while (start < tail.length && tail[start] != '\n') start++;
        start = Math.min(tail.length, start + 1);
        String kept = new String(tail, start, tail.length - start, StandardCharsets.UTF_8);
        writeAtomically(body, kept);
    }

    /** What a new read adds to the kept tail: drop the kept last line from {@code dropFrom} (or -1), then append. */
    static final class Join {
        final long dropFrom;
        final String append;

        Join(long dropFrom, String append) {
            this.dropFrom = dropFrom;
            this.append = append;
        }
    }

    /**
     * How {@code next} (whole lines) joins the body kept in {@code body}. The kept tail's lines but
     * its last (a prompt still being typed may change) are looked for, in order, in the new read;
     * found, the new read continues after them and replaces that last line. Not found, the new read
     * is kept whole after a {@link #GAP} row. A read that adds nothing changes nothing.
     */
    static Join join(File body, String next) throws IOException {
        long length = body.length();
        int want = (int) Math.min(length, 256 * 1024);
        byte[] tailBytes = readRange(body, length - want, want);
        String tail = new String(tailBytes, StandardCharsets.UTF_8);
        List<String> kept = split(tail);
        // A tail read from the middle of the body starts mid-line: that first line is not used.
        if (length > want && !kept.isEmpty()) kept.remove(0);
        List<String> fresh = split(next);
        if (fresh.isEmpty()) return new Join(-1, "");
        if (kept.isEmpty()) return new Join(-1, next);
        String last = kept.get(kept.size() - 1);
        List<String> anchor = kept.subList(Math.max(0, kept.size() - 1 - ANCHOR_LINES), kept.size() - 1);
        long lastStart = length - (last + "\n").getBytes(StandardCharsets.UTF_8).length;
        if (anchor.isEmpty()) {
            // Only one line kept: the new read starts again from it, or is joined after it.
            if (fresh.get(0).equals(last) || fresh.contains(last)) {
                int at = fresh.lastIndexOf(last);
                return new Join(lastStart, String.join("\n", fresh.subList(at, fresh.size())) + "\n");
            }
            return new Join(-1, GAP + "\n" + next);
        }
        int at = lastIndexOf(fresh, anchor);
        if (at < 0) return new Join(-1, GAP + "\n" + next);
        List<String> after = fresh.subList(at + anchor.size(), fresh.size());
        if (after.isEmpty() || after.size() == 1 && after.get(0).equals(last)) return new Join(-1, "");
        return new Join(lastStart, String.join("\n", after) + "\n");
    }

    private static int lastIndexOf(List<String> in, List<String> run) {
        for (int at = in.size() - run.size(); at >= 0; at--) {
            if (in.subList(at, at + run.size()).equals(run)) return at;
        }
        return -1;
    }

    /** {@code text} as whole lines, each ended by a newline (an unended last line gets one). */
    private static String lines(String text) {
        if (text.isEmpty() || text.endsWith("\n")) return text;
        return text + "\n";
    }

    private static List<String> split(String text) {
        if (text.isEmpty()) return Collections.emptyList();
        String trimmed = text.endsWith("\n") ? text.substring(0, text.length() - 1) : text;
        return new ArrayList<>(Arrays.asList(trimmed.split("\n", -1)));
    }

    /** The newest lines of a body, at most {@link #WINDOW_LINES} and {@link #WINDOW_BYTES}. */
    private static final class Window {
        final String text;
        final boolean older;

        Window(String text, boolean older) {
            this.text = text;
            this.older = older;
        }
    }

    private static Window window(File body) {
        if (!body.isFile()) return null;
        try {
            long length = body.length();
            int want = (int) Math.min(length, WINDOW_BYTES);
            byte[] bytes = readRange(body, length - want, want);
            int start = 0;
            if (length > want) {
                while (start < bytes.length && bytes[start] != '\n') start++;
                start = Math.min(bytes.length, start + 1);
            }
            String text = new String(bytes, start, bytes.length - start, StandardCharsets.UTF_8);
            boolean older = length > want;
            int lines = 0, cut = text.length();
            for (int i = text.length() - 2; i >= 0; i--) {
                if (text.charAt(i) == '\n' && ++lines >= WINDOW_LINES) {
                    cut = i + 1;
                    break;
                }
            }
            if (cut < text.length() && lines >= WINDOW_LINES) {
                text = text.substring(cut);
                older = true;
            }
            return new Window(text, older);
        } catch (IOException unreadable) {
            return null;
        }
    }

    private static boolean sameAs(File body, String text) {
        if (!body.isFile() || body.length() != text.getBytes(StandardCharsets.UTF_8).length) return false;
        try {
            return new String(readRange(body, 0, (int) body.length()), StandardCharsets.UTF_8).equals(text);
        } catch (IOException unreadable) {
            return false;
        }
    }

    private static byte[] readRange(File file, long from, int count) throws IOException {
        byte[] out = new byte[Math.max(0, count)];
        try (RandomAccessFile f = new RandomAccessFile(file, "r")) {
            f.seek(Math.max(0, from));
            f.readFully(out);
        }
        return out;
    }

    private static void writeAtomically(File file, String text) throws IOException {
        Outbox.write(file, text);
    }

    private static void append(File file, String text) throws IOException {
        if (text.isEmpty()) return;
        try (FileOutputStream out = new FileOutputStream(file, true)) {
            out.write(text.getBytes(StandardCharsets.UTF_8));
            out.getFD().sync();
        }
    }

    private void writeHead(TranscriptKey key, Header h) throws IOException, JSONException {
        writeHead(head(key), h);
    }

    private static void writeHead(File head, Header h) throws IOException, JSONException {
        Outbox.write(head, h.json().toString());
    }

    private static File sibling(File head, String suffix) {
        String name = head.getName();
        return new File(head.getParentFile(), name.substring(0, name.length() - HEAD.length()) + suffix);
    }

    private static Header readHead(File file) {
        if (file == null || !file.isFile() || file.length() > MAX_HEADER) return null;
        String text = Outbox.read(file);
        return text == null ? null : Header.parse(text, 2);
    }

    /** A legacy file's header line. */
    private static Header header(File file) {
        if (file == null || !file.isFile()) return null;
        try (InputStream in = new FileInputStream(file)) {
            byte[] buffer = new byte[MAX_HEADER];
            int n = 0;
            for (int r; n < buffer.length && (r = in.read(buffer, n, buffer.length - n)) > 0; ) n += r;
            String start = new String(buffer, 0, n, StandardCharsets.UTF_8);
            int end = start.indexOf('\n');
            return end <= 0 ? null : Header.parse(start.substring(0, end), 1);
        } catch (IOException unreadable) {
            return null;
        }
    }

    private static File[] list(File parent, java.io.FileFilter filter) {
        File[] files = parent.listFiles(filter);
        return files == null ? new File[0] : files;
    }

    private static File[] list(File parent, java.io.FilenameFilter filter) {
        File[] files = parent.listFiles(filter);
        return files == null ? new File[0] : files;
    }

    private static final class Header {
        final String core, hostKey, address, incarnation;
        final long createdMs, asOfMs;
        /** When a read last added lines (the age the taper goes by). */
        final long grewMs;
        final boolean truncated, screen;

        Header(TranscriptKey key, long asOfMs, long grewMs, boolean truncated, boolean screen) {
            core = key.machine.core;
            hostKey = key.machine.hostKey;
            address = key.address;
            incarnation = key.incarnation;
            createdMs = key.createdMs;
            this.asOfMs = asOfMs;
            this.grewMs = grewMs;
            this.truncated = truncated;
            this.screen = screen;
        }

        /** {@code h}, now cut by the taper. */
        Header(Header h, boolean truncated) {
            core = h.core;
            hostKey = h.hostKey;
            address = h.address;
            incarnation = h.incarnation;
            createdMs = h.createdMs;
            asOfMs = h.asOfMs;
            grewMs = h.grewMs;
            this.truncated = truncated;
            screen = h.screen;
        }

        private Header(JSONObject h) {
            core = h.optString("core", "");
            hostKey = h.optString("hostkey", "");
            address = h.optString("address", "");
            incarnation = h.optString("incarnation", "");
            createdMs = h.optLong("created_ms", 0);
            asOfMs = h.optLong("as_of_ms", 0);
            grewMs = h.optLong("grew_ms", asOfMs);
            truncated = h.optBoolean("truncated", false);
            // Files written before the flag were all read without stitching, so an agent's was its
            // screen only: unknown counts as the screen, and offers no history.
            screen = h.optBoolean("screen", true);
        }

        static Header parse(String line, int version) {
            try {
                JSONObject h = new JSONObject(line);
                return h.optInt("v") == version ? new Header(h) : null;
            } catch (JSONException damaged) {
                return null;
            }
        }

        JSONObject json() throws JSONException {
            return new JSONObject().put("v", 2).put("core", core).put("hostkey", hostKey).put("address", address)
                    .put("created_ms", createdMs).put("incarnation", incarnation).put("as_of_ms", asOfMs)
                    .put("grew_ms", grewMs).put("truncated", truncated).put("screen", screen);
        }

        boolean names(TranscriptKey key) {
            return key.machine.core.equals(core) && key.machine.hostKey.equals(hostKey) && key.address.equals(address)
                    && key.createdMs == createdMs && key.incarnation.equals(incarnation);
        }
    }
}
