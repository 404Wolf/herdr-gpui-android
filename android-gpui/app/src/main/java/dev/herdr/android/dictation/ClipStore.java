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
 * <p>Bounded: a clip holds at most {@link #MAX_CLIP_BYTES} (the server's session limit) and all
 * clips together at most {@link #MAX_TOTAL_BYTES}; recording stops at either. Thread-safe.
 */
final class ClipStore {
    static final int BYTES_PER_SECOND = Microphone.RATE * 2;
    static final long MAX_CLIP_BYTES = 15L * 60 * BYTES_PER_SECOND;
    static final long MAX_TOTAL_BYTES = 30L * 60 * BYTES_PER_SECOND;
    static final int MAX_CLIPS = 64;
    static final String INDEX = "index-v1.json";

    /** One saved clip; {@code text} is null until transcribed. */
    static final class Entry {
        final PendingClip clip;
        final long bytes;
        final String text;

        Entry(PendingClip clip, long bytes, String text) {
            this.clip = clip;
            this.bytes = bytes;
            this.text = text;
        }
    }

    private final File dir;
    private final List<Entry> entries = new ArrayList<>();
    /** Bytes held by recordings not yet kept or discarded. */
    private long recording;

    ClipStore(File dir) {
        this.dir = dir;
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
            synchronized (this) {
                boolean ok = out != null;
                try {
                    if (out != null) out.close();
                } catch (IOException e) {
                    ok = false;
                }
                out = null;
                if (!ok || bytes == 0) {
                    discard();
                    return null;
                }
            }
            return ClipStore.this.keep(this);
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

    Recording record(String target, long now) {
        return new Recording(target, now);
    }

    private synchronized boolean reserve(long n) {
        if (held() + recording + n > MAX_TOTAL_BYTES) return false;
        recording += n;
        return true;
    }

    private synchronized void release(long n) {
        recording = Math.max(0, recording - n);
    }

    private synchronized PendingClip keep(Recording r) {
        release(r.bytes);
        if (entries.size() >= MAX_CLIPS) {
            //noinspection ResultOfMethodCallIgnored
            r.file.delete();
            return null;
        }
        File pcm = audio(r.id);
        if (!r.file.renameTo(pcm)) {
            //noinspection ResultOfMethodCallIgnored
            r.file.delete();
            return null;
        }
        PendingClip clip = new PendingClip(r.id, r.target, r.createdAt, r.durationMs());
        entries.add(new Entry(clip, r.bytes, null));
        if (!save()) {
            entries.remove(entries.size() - 1);
            //noinspection ResultOfMethodCallIgnored
            pcm.delete();
            return null;
        }
        return clip;
    }

    /** Audio bytes of kept clips not yet transcribed. */
    private long held() {
        long total = 0;
        for (Entry e : entries) if (e.text == null) total += e.bytes;
        return total;
    }

    File audio(String id) {
        return new File(dir, id + ".pcm");
    }

    /** The clips still waiting for the desktop, oldest first. */
    synchronized List<PendingClip> untranscribed() {
        List<PendingClip> out = new ArrayList<>();
        for (Entry e : entries) if (e.text == null) out.add(e.clip);
        return out;
    }

    /** Transcribed clips whose text the composer has not taken yet, oldest first. */
    synchronized List<Entry> transcribed() {
        List<Entry> out = new ArrayList<>();
        for (Entry e : entries) if (e.text != null) out.add(e);
        return out;
    }

    synchronized int size() {
        return entries.size();
    }

    /** Records the text and deletes the audio; false if the clip is gone (e.g. dropped). */
    synchronized boolean transcribed(String id, String text) {
        for (int i = 0; i < entries.size(); i++) {
            Entry e = entries.get(i);
            if (!e.clip.id.equals(id) || e.text != null) continue;
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
        String text = read(new File(dir, INDEX));
        if (text != null) {
            try {
                JSONArray list = new JSONArray(text);
                for (int i = 0; i < list.length() && entries.size() < MAX_CLIPS; i++) {
                    JSONObject o = list.getJSONObject(i);
                    String id = o.getString("id");
                    String clipText = o.isNull("text") ? null : o.optString("text", null);
                    long bytes = o.getLong("bytes");
                    if (clipText == null && audio(id).length() != bytes) continue;
                    PendingClip clip = new PendingClip(id, PendingClip.cutTarget(o.optString("target")),
                            o.getLong("created"), o.getLong("duration"));
                    entries.add(new Entry(clip, bytes, clipText));
                }
            } catch (JSONException damaged) {
                entries.clear();
            }
        }
        // Leftovers of a session cut short by a kill, and audio no entry refers to.
        Set<String> wanted = new HashSet<>();
        for (Entry e : entries) if (e.text == null) wanted.add(e.clip.id + ".pcm");
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            String name = f.getName();
            if (name.equals(INDEX) || wanted.contains(name)) continue;
            //noinspection ResultOfMethodCallIgnored
            f.delete();
        }
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
                list.put(o);
            }
            write(new File(dir, INDEX), list.toString());
            return true;
        } catch (IOException | JSONException e) {
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
