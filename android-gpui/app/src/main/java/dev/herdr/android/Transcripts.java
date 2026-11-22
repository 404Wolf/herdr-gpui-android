package dev.herdr.android;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Agents' transcripts for reading offline (stage 2 of the offline design): the text the core's
 * bounded read gave while online, one file per machine, terminal and incarnation
 * ({@link TranscriptKey}), under {@code noBackupFilesDir/transcripts-v1/<machine>/}.
 *
 * <p>A transcript is shown offline only where identity is proven: the last list a direct link
 * gave for that machine ({@link #observe}) must name the same terminal running the same
 * incarnation. A replaced program, another machine, a list from a core without incarnations, or
 * no list at all shows nothing. The phone never claims it is the pane's content now: it reads
 * "transcript as of 14:02 · may be outdated".
 *
 * <p>Bounded: {@link #MAX_TEXT_BYTES} per transcript, {@link #MAX_FILES} files and
 * {@link #MAX_TOTAL_BYTES} in all; room is made before a write, oldest {@code as_of_ms} first,
 * with no exemptions. Writes are tmp + fsync + rename; {@code .tmp} files are swept at start.
 * Every method does disk work: callers run it on their own worker thread.
 */
final class Transcripts {
    static final int MAX_TEXT_BYTES = 256 * 1024;
    static final int MAX_FILES = 48;
    static final long MAX_TOTAL_BYTES = 16L * 1024 * 1024;
    /** The longest header line a file may start with. */
    static final int MAX_HEADER = 4096;
    private static final String SUFFIX = ".txt";
    private static final String SEEN = "seen.json";
    /** At most this many terminals of one machine are remembered from its last list. */
    static final int MAX_SEEN = 1024;

    private static Transcripts shared;

    /** The process's one store; its disk work runs on the caller's worker thread. */
    static synchronized Transcripts shared(android.content.Context context) {
        if (shared == null) {
            shared = new Transcripts(new File(context.getApplicationContext().getNoBackupFilesDir(), "transcripts-v1"));
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

    /** A transcript as read back: what it is of, its text, and when it was read. */
    static final class Transcript {
        final TranscriptKey key;
        final String text;
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
    private boolean started;

    Transcripts(File dir) {
        this.dir = dir;
    }

    /** Makes the directory and sweeps what a killed write left behind. */
    synchronized void start() {
        if (!dir.isDirectory() && !dir.mkdirs()) return;
        File[] machines = dir.listFiles(File::isDirectory);
        for (File machine : machines == null ? new File[0] : machines) {
            File[] tmp = machine.listFiles((d, name) -> name.endsWith(".tmp"));
            for (File f : tmp == null ? new File[0] : tmp) f.delete();
        }
        started = true;
    }

    /** "transcript as of 14:02 · may be outdated". */
    static String label(String when) {
        return "transcript as of " + when + " · may be outdated";
    }

    /**
     * The transcript view's title: its pane, when it was read, and what it is not. A screen-only
     * read says "screen only"; one the core cut says "older lines not kept".
     */
    static String title(String pane, String when, Transcript t) {
        return pane + " · " + label(when) + (t.screen ? " · screen only" : "")
                + (t.truncated ? " · older lines not kept" : "");
    }

    /** The file a transcript is kept in. */
    File file(TranscriptKey key) {
        return new File(machineDir(key.machine), key.address + "-" + key.createdMs + "-" + key.incarnation + SUFFIX);
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

    /**
     * Keeps {@code text} as the transcript of {@code key}, read at {@code asOfMs}. False when it is
     * too large, an equal or newer copy is already kept, or the write failed (the older copy then
     * stays whole).
     */
    synchronized boolean save(TranscriptKey key, String text, boolean truncated, long asOfMs) {
        return save(key, text, truncated, false, asOfMs);
    }

    /** As {@link #save(TranscriptKey, String, boolean, long)}; {@code screen}: the screen only, no history. */
    synchronized boolean save(TranscriptKey key, String text, boolean truncated, boolean screen, long asOfMs) {
        if (!started) start();
        if (key == null || text == null) return false;
        byte[] body = text.getBytes(StandardCharsets.UTF_8);
        if (body.length > MAX_TEXT_BYTES) return false;
        File file = file(key);
        Header existing = header(file);
        if (existing != null && existing.asOfMs >= asOfMs) return false;
        String header;
        try {
            header = new JSONObject().put("v", 1).put("core", key.machine.core).put("hostkey", key.machine.hostKey)
                    .put("address", key.address).put("created_ms", key.createdMs).put("incarnation", key.incarnation)
                    .put("as_of_ms", asOfMs).put("truncated", truncated).put("screen", screen).toString();
        } catch (JSONException impossible) {
            return false;
        }
        if (header.length() >= MAX_HEADER) return false;
        long size = header.getBytes(StandardCharsets.UTF_8).length + 1L + body.length;
        makeRoom(file, size);
        try {
            Outbox.write(file, header + "\n" + text);
            return true;
        } catch (IOException failed) {
            return false;
        }
    }

    /** The transcript of exactly {@code key}, or null: a file whose header names anything else is a miss. */
    synchronized Transcript open(TranscriptKey key) {
        if (key == null) return null;
        File file = file(key);
        String text = Outbox.read(file);
        if (text == null) return null;
        int end = text.indexOf('\n');
        if (end <= 0 || end >= MAX_HEADER) return null;
        Header header = Header.parse(text.substring(0, end));
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
        File[] files = machineDir.listFiles((d, name) -> name.endsWith(SUFFIX));
        for (File f : files == null ? new File[0] : files) {
            Header h = header(f);
            Seen s = h == null ? null : terminals.get(h.address);
            boolean current = s != null && s.createdMs != null && s.createdMs == h.createdMs
                    && h.incarnation.equals(s.incarnation);
            if (!current) f.delete();
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
        return all().size();
    }

    /** Their bytes on disk. */
    synchronized long bytes() {
        long total = 0;
        for (File f : all()) total += f.length();
        return total;
    }

    private List<File> all() {
        List<File> out = new ArrayList<>();
        File[] machines = dir.listFiles(File::isDirectory);
        for (File machine : machines == null ? new File[0] : machines) {
            File[] files = machine.listFiles((d, name) -> name.endsWith(SUFFIX));
            if (files != null) for (File f : files) out.add(f);
        }
        return out;
    }

    /** Oldest first out, before a write of {@code size} bytes to {@code target}, with no exemptions. */
    private void makeRoom(File target, long size) {
        List<File> files = all();
        files.remove(target);
        Map<File, Long> asOf = new HashMap<>();
        long total = 0;
        for (File f : files) {
            Header h = header(f);
            asOf.put(f, h == null ? Long.MIN_VALUE : h.asOfMs);
            total += f.length();
        }
        files.sort((a, b) -> Long.compare(asOf.get(a), asOf.get(b)));
        Iterator<File> oldest = files.iterator();
        int count = files.size();
        while (oldest.hasNext() && (count + 1 > MAX_FILES || total + size > MAX_TOTAL_BYTES)) {
            File f = oldest.next();
            long length = f.length();
            if (f.delete()) {
                count--;
                total -= length;
            }
        }
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

    private static final class Header {
        final String core, hostKey, address, incarnation;
        final long createdMs, asOfMs;
        final boolean truncated, screen;

        private Header(JSONObject h) {
            core = h.optString("core", "");
            hostKey = h.optString("hostkey", "");
            address = h.optString("address", "");
            incarnation = h.optString("incarnation", "");
            createdMs = h.optLong("created_ms", 0);
            asOfMs = h.optLong("as_of_ms", 0);
            truncated = h.optBoolean("truncated", false);
            // Files written before the flag were all read without stitching, so an agent's was its
            // screen only: unknown counts as the screen, and offers no history.
            screen = h.optBoolean("screen", true);
        }

        static Header parse(String line) {
            try {
                JSONObject h = new JSONObject(line);
                return h.optInt("v") == 1 ? new Header(h) : null;
            } catch (JSONException damaged) {
                return null;
            }
        }

        boolean names(TranscriptKey key) {
            return key.machine.core.equals(core) && key.machine.hostKey.equals(hostKey) && key.address.equals(address)
                    && key.createdMs == createdMs && key.incarnation.equals(incarnation);
        }
    }
}
