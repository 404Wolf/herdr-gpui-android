package dev.herdr.android.dictation;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Dictations recorded while the desktop was away, in app-private storage (no backup): one raw
 * PCM16 16 kHz mono file per clip, and an index written atomically. A clip is first pending
 * (audio, no text), then transcribed (text, audio deleted) until the composer has taken its text.
 *
 * <p>A session the phone transcribed itself keeps its audio too, as a kept clip: never sent and
 * never offered, deleted after {@link #KEEP_MS}, or earlier when a recording needs the room.
 *
 * <p>Bounded: a clip holds at most {@link #MAX_CLIP_BYTES} (the server's session limit) and all
 * clips together at most {@link #MAX_TOTAL_BYTES}; recording stops at either. Thread-safe.
 */
final class ClipStore {
    static final int BYTES_PER_SECOND = Microphone.RATE * 2;
    static final long MAX_CLIP_BYTES = 15L * 60 * BYTES_PER_SECOND;
    static final long MAX_TOTAL_BYTES = 30L * 60 * BYTES_PER_SECOND;
    static final int MAX_CLIPS = 64;
    static final String INDEX = "index-v1.json";
    /** Uploads a clip gets before it is parked: a long clip is never re-decoded without end. */
    static final int MAX_ATTEMPTS = 3;
    /** How long a recording the phone transcribed itself is kept: 48 h. */
    static final long KEEP_MS = 48L * 60 * 60 * 1000;

    /** One saved clip; {@code text} is null until transcribed. */
    static final class Entry {
        final PendingClip clip;
        final long bytes;
        final String text;
        /** Non-zero for a kept clip (the phone transcribed it): deleted after this epoch time. */
        final long keptUntil;

        Entry(PendingClip clip, long bytes, String text) {
            this(clip, bytes, text, 0);
        }

        /** Uploads the desktop took and then failed to finish (slow, unavailable, cut off). */
        final int attempts;
        /** Given up on after {@link #MAX_ATTEMPTS}: kept and shown, sent again only by hand. */
        final boolean parked;

        Entry(PendingClip clip, long bytes, String text, long keptUntil) {
            this(clip, bytes, text, keptUntil, 0, false);
        }

        Entry(PendingClip clip, long bytes, String text, long keptUntil, int attempts, boolean parked) {
            this.clip = clip;
            this.bytes = bytes;
            this.text = text;
            this.keptUntil = keptUntil;
            this.attempts = attempts;
            this.parked = parked;
        }

        boolean kept() {
            return keptUntil != 0;
        }
    }

    private final File dir;
    private final java.util.function.LongSupplier clock;
    private final List<Entry> entries = new ArrayList<>();
    /** Bytes held by recordings not yet kept or discarded. */
    private long recording;

    ClipStore(File dir) {
        this(dir, System::currentTimeMillis);
    }

    ClipStore(File dir, java.util.function.LongSupplier clock) {
        this.dir = dir;
        this.clock = clock;
        load();
    }

    /** A session's audio as it is recorded; kept as a clip or discarded at the end. */
    final class Recording {
        final String id = UUID.randomUUID().toString();
        final String target;
        final long createdAt;
        private final File file;
        private OutputStream out;
        private long bytes;
        private boolean full;

        Recording(String target, long createdAt) {
            this.target = PendingClip.cutTarget(target);
            this.createdAt = createdAt;
            file = new File(dir, id + ".part");
            try {
                if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("cannot create " + dir);
                out = new FileOutputStream(file);
            } catch (IOException e) {
                out = null;
            }
        }

        /** Whether the audio is being saved (false when storage failed). */
        synchronized boolean saved() {
            return out != null;
        }

        long durationMs() {
            return bytes * 1000 / BYTES_PER_SECOND;
        }

        /**
         * Appends audio; false once a limit is reached (the rest is not saved and the session
         * should stop). A storage failure only stops saving: a live session can go on.
         */
        synchronized boolean write(byte[] chunk, int length) {
            if (full) return false;
            if (bytes + length > MAX_CLIP_BYTES || !reserve(length)) {
                full = true;
                return false;
            }
            bytes += length;
            if (out != null) {
                try {
                    out.write(chunk, 0, length);
                } catch (IOException e) {
                    closeQuietly();
                }
            }
            return true;
        }

        /** Saves the audio as a pending clip; null when nothing was saved. */
        PendingClip keep() {
            return keep(0);
        }

        /**
         * Saves the audio of a session the phone transcribed itself, for {@link #KEEP_MS}: never
         * sent, never offered. Null when nothing was saved.
         */
        PendingClip keepTranscribedOnPhone() {
            return keep(clock.getAsLong() + KEEP_MS);
        }

        /**
         * Keeps whatever audio reached the disk, even after a write failed partway: audio is
         * never deleted without a stored final. Null only when nothing was saved at all.
         */
        private PendingClip keep(long keptUntil) {
            synchronized (this) {
                closeQuietly();
            }
            long onDisk = file.isFile() ? file.length() : 0;
            if (onDisk == 0) {
                discard();
                return null;
            }
            return ClipStore.this.keep(this, onDisk, keptUntil);
        }

        void discard() {
            synchronized (this) {
                closeQuietly();
            }
            release(bytes);
            //noinspection ResultOfMethodCallIgnored
            file.delete();
        }

        private void closeQuietly() {
            try {
                if (out != null) out.close();
            } catch (IOException ignored) {
                // The file is deleted or reported unsaved anyway.
            }
            out = null;
        }
    }

    /** Starts a session's recording; kept clips past their 48 h go first. */
    Recording record(String target, long now) {
        dropExpired();
        return new Recording(target, now);
    }

    private synchronized boolean reserve(long n) {
        // Kept clips give way to a recording, oldest first.
        while (held() + recording + n > MAX_TOTAL_BYTES && dropOldestKept()) { }
        if (held() + recording + n > MAX_TOTAL_BYTES) return false;
        recording += n;
        return true;
    }

    /** Deletes the oldest kept clip; false when there is none. */
    private synchronized boolean dropOldestKept() {
        for (int i = 0; i < entries.size(); i++) {
            Entry e = entries.get(i);
            if (!e.kept()) continue;
            entries.remove(i);
            save();
            //noinspection ResultOfMethodCallIgnored
            audio(e.clip.id).delete();
            return true;
        }
        return false;
    }

    /** Deletes kept clips past their time: on load, at each session start and courier pass. */
    synchronized void dropExpired() {
        long now = clock.getAsLong();
        boolean changed = false;
        for (int i = entries.size() - 1; i >= 0; i--) {
            Entry e = entries.get(i);
            if (!e.kept() || e.keptUntil > now) continue;
            entries.remove(i);
            //noinspection ResultOfMethodCallIgnored
            audio(e.clip.id).delete();
            changed = true;
        }
        if (changed) save();
    }

    private synchronized void release(long n) {
        recording = Math.max(0, recording - n);
    }

    /**
     * Files a finished recording. Its audio is never deleted here: past {@link #MAX_CLIPS} it is
     * kept anyway (the byte budget bounds recording), and when the index cannot be written the
     * clip stays in memory, the index is written again later ({@link #flush}), and a restart
     * recovers the audio in any case ({@link #load}).
     */
    private synchronized PendingClip keep(Recording r, long bytes, long keptUntil) {
        release(r.bytes);
        dropExpired();
        if (entries.size() >= MAX_CLIPS) dropOldestKept();
        PendingClip clip = new PendingClip(r.id, r.target, r.createdAt, bytes * 1000 / BYTES_PER_SECOND);
        File pcm = audio(r.id);
        if (!r.file.renameTo(pcm)) {
            // The .part stays where it is; the next start recovers it ("not transcribed yet").
            indexDirty = true;
            return clip;
        }
        entries.add(new Entry(clip, bytes, null, keptUntil));
        save();
        return clip;
    }

    /** The index could not be written: {@link #flush} tries again. Guarded by this. */
    private boolean indexDirty;

    /** Writes the index if an earlier write failed; true when it is current. */
    synchronized boolean flush() {
        return !indexDirty || save();
    }

    /** Audio bytes of saved clips not yet transcribed, kept ones included. */
    private long held() {
        long total = 0;
        for (Entry e : entries) if (e.text == null) total += e.bytes;
        return total;
    }

    File audio(String id) {
        return new File(dir, id + ".pcm");
    }

    /** The clips still waiting for the desktop, oldest first (parked and kept ones not). */
    synchronized List<PendingClip> untranscribed() {
        List<PendingClip> out = new ArrayList<>();
        for (Entry e : entries) if (e.text == null && !e.kept() && !e.parked) out.add(e.clip);
        return out;
    }

    /** Clips given up on after {@link #MAX_ATTEMPTS} uploads, oldest first: sent again only by hand. */
    synchronized List<PendingClip> parked() {
        List<PendingClip> out = new ArrayList<>();
        for (Entry e : entries) if (e.text == null && !e.kept() && e.parked) out.add(e.clip);
        return out;
    }

    /**
     * An upload of {@code id} reached the desktop and did not come back with its text: counted,
     * and at {@link #MAX_ATTEMPTS} the clip is parked. The attempts so far, -1 if unknown.
     */
    synchronized int attempted(String id) {
        for (int i = 0; i < entries.size(); i++) {
            Entry e = entries.get(i);
            if (!e.clip.id.equals(id) || e.text != null) continue;
            int n = e.attempts + 1;
            entries.set(i, new Entry(e.clip, e.bytes, null, e.keptUntil, n, n >= MAX_ATTEMPTS));
            save();
            return n;
        }
        return -1;
    }

    /** The user's retry of a parked clip: it waits for the desktop again, its count reset. */
    synchronized boolean unpark(String id) {
        for (int i = 0; i < entries.size(); i++) {
            Entry e = entries.get(i);
            if (!e.clip.id.equals(id) || !e.parked) continue;
            entries.set(i, new Entry(e.clip, e.bytes, e.text, e.keptUntil, 0, false));
            return save();
        }
        return false;
    }

    /**
     * Recordings the phone transcribed itself, kept for a while, oldest first. Expired ones go on
     * load, at each session start ({@link #record}) and at each courier pass.
     */
    synchronized List<PendingClip> keptClips() {
        List<PendingClip> out = new ArrayList<>();
        for (Entry e : entries) if (e.kept()) out.add(e.clip);
        return out;
    }

    /** Transcribed clips whose text the composer has not taken yet, oldest first. */
    synchronized List<Entry> transcribed() {
        List<Entry> out = new ArrayList<>();
        for (Entry e : entries) if (e.text != null) out.add(e);
        return out;
    }

    /** Clips waiting or not yet placed; kept clips do not count. */
    synchronized int size() {
        int n = 0;
        for (Entry e : entries) if (!e.kept()) n++;
        return n;
    }

    /** Records the text and deletes the audio; false if the clip is gone (e.g. dropped). */
    synchronized boolean transcribed(String id, String text) {
        for (int i = 0; i < entries.size(); i++) {
            Entry e = entries.get(i);
            if (!e.clip.id.equals(id) || e.text != null || e.kept()) continue;
            entries.set(i, new Entry(e.clip, e.bytes, text == null ? "" : text));
            if (!save()) {
                entries.set(i, e);
                return false;
            }
            //noinspection ResultOfMethodCallIgnored
            audio(id).delete();
            return true;
        }
        return false;
    }

    /** Forgets a clip: its text was taken, or the user dropped it. */
    synchronized void remove(String id) {
        if (!entries.removeIf(e -> e.clip.id.equals(id))) return;
        save();
        //noinspection ResultOfMethodCallIgnored
        audio(id).delete();
    }

    private synchronized void load() {
        entries.clear();
        Set<String> expired = new HashSet<>();
        String text = read(new File(dir, INDEX));
        if (text != null) {
            try {
                JSONArray list = new JSONArray(text);
                for (int i = 0; i < list.length() && entries.size() < MAX_CLIPS; i++) {
                    JSONObject o = list.getJSONObject(i);
                    String id = o.getString("id");
                    String clipText = o.isNull("text") ? null : o.optString("text", null);
                    long bytes = o.getLong("bytes");
                    long keptUntil = o.optLong("keptUntil", 0);
                    if (clipText == null && audio(id).length() != bytes) continue;
                    if (keptUntil != 0 && keptUntil <= clock.getAsLong()) {
                        expired.add(id);
                        continue;
                    }
                    PendingClip clip = new PendingClip(id, PendingClip.cutTarget(o.optString("target")),
                            o.getLong("created"), o.getLong("duration"));
                    entries.add(new Entry(clip, bytes, clipText, keptUntil, o.optInt("attempts", 0),
                            o.optBoolean("parked", false)));
                }
            } catch (JSONException damaged) {
                entries.clear();
            }
        }
        // Audio no entry refers to (a session cut short by a kill, an index write that failed)
        // is never deleted: it is recovered as a parked clip, "not transcribed yet", sent again
        // only by hand. Only audio whose final is stored, or whose 48 h are up, goes.
        Set<String> known = new HashSet<>();
        for (Entry e : entries) known.add(e.clip.id);
        Set<String> wanted = new HashSet<>();
        for (Entry e : entries) if (e.text == null) wanted.add(e.clip.id + ".pcm");
        File[] files = dir.listFiles();
        if (files == null) return;
        boolean recovered = false;
        for (File f : files) {
            String name = f.getName();
            if (name.equals(INDEX) || wanted.contains(name)) continue;
            boolean pcm = name.endsWith(".pcm"), part = name.endsWith(".part");
            if (!pcm && !part) continue;
            String id = name.substring(0, name.lastIndexOf('.'));
            if (known.contains(id) || expired.contains(id) || f.length() == 0) {
                //noinspection ResultOfMethodCallIgnored
                f.delete();
                continue;
            }
            File audio = audio(id);
            if (part && !f.renameTo(audio)) continue;
            long bytes = audio.length();
            PendingClip clip = new PendingClip(id, "", f.lastModified(), bytes * 1000 / BYTES_PER_SECOND);
            entries.add(new Entry(clip, bytes, null, 0, 0, true));
            known.add(id);
            recovered = true;
        }
        if (recovered) save();
    }

    private boolean save() {
        try {
            JSONArray list = new JSONArray();
            for (Entry e : entries) {
                JSONObject o = new JSONObject();
                o.put("id", e.clip.id);
                o.put("target", e.clip.target);
                o.put("created", e.clip.createdAt);
                o.put("duration", e.clip.durationMs);
                o.put("bytes", e.bytes);
                o.put("text", e.text == null ? JSONObject.NULL : e.text);
                if (e.kept()) o.put("keptUntil", e.keptUntil);
                if (e.attempts > 0) o.put("attempts", e.attempts);
                if (e.parked) o.put("parked", true);
                list.put(o);
            }
            write(new File(dir, INDEX), list.toString());
            indexDirty = false;
            return true;
        } catch (IOException | JSONException e) {
            indexDirty = true;
            return false;
        }
    }

    private static String read(File file) {
        if (!file.isFile() || file.length() > 4 * 1024 * 1024) return null;
        try (InputStream in = new FileInputStream(file)) {
            byte[] buf = new byte[(int) file.length()];
            int n = 0;
            for (int r; n < buf.length && (r = in.read(buf, n, buf.length - n)) > 0; ) n += r;
            return new String(buf, 0, n, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return null;
        }
    }

    private void write(File file, String text) throws IOException {
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("cannot create " + dir);
        File tmp = new File(dir, INDEX + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(text.getBytes(StandardCharsets.UTF_8));
            out.getFD().sync();
        }
        if (!tmp.renameTo(file)) throw new IOException("cannot replace " + file);
    }
}
