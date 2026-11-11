package dev.herdr.android.dictation;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.res.AssetManager;
import android.os.Handler;
import android.os.Looper;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.json.JSONException;
import org.json.JSONObject;
import org.vosk.Model;
import org.vosk.Recognizer;

/**
 * Offline dictation with Vosk (vosk-model-small-en-us-0.15), the fallback when the tailnet server
 * is unreachable. The model is bundled in the APK's assets as vosk-model/ by the build (Nix
 * passes it; a build without it has no fallback and {@link #isAvailable} is false). Vosk needs
 * it as files, so the first use copies it to app storage, once per installed APK.
 *
 * <p>Recording starts on the tap; audio queues while the model loads (about a second, longer on
 * the first use after an install). Vosk ends an utterance at each pause and the engine keeps
 * going, so only {@link #stop} ends a session.
 */
public final class VoskDictationEngine implements DictationEngine {
    static final String ASSET_DIR = "vosk-model";
    /** Audio queued while the model loads: 30 s. */
    private static final int MAX_QUEUED_CHUNKS = 300;
    private static final byte[] END = new byte[0];

    /** Loaded once per process and kept: reloading costs a second on every tap. */
    private static Model model;

    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private Boolean bundled;
    /** Main thread only. */
    private Session session;

    public VoskDictationEngine(Context context) {
        this.context = context.getApplicationContext() != null ? context.getApplicationContext() : context;
    }

    @Override
    public boolean isAvailable() {
        if (bundled == null) {
            try {
                String[] files = context.getAssets().list(ASSET_DIR);
                bundled = files != null && files.length > 0;
            } catch (IOException e) {
                bundled = false;
            }
        }
        return bundled;
    }

    @Override
    public void start(Listener listener) {
        if (session != null) return;
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            listener.onError(new DictationException(
                    DictationException.Reason.PERMISSION, "Microphone permission is not granted"));
            return;
        }
        if (!isAvailable()) {
            listener.onError(new DictationException(DictationException.Reason.UNAVAILABLE,
                    "This build has no offline speech model"));
            return;
        }
        session = new Session(listener);
        session.begin();
    }

    @Override
    public void stop() {
        if (session != null) session.finish();
    }

    @Override
    public void cancel() {
        Session s = session;
        session = null;
        if (s == null) return;
        s.cancelled = true;
        s.stopping = true;
    }

    /** The words in a Vosk result, e.g. {"text": "..."} or {"partial": "..."}. */
    static String words(String json, String key) {
        if (json == null) return "";
        try {
            return new JSONObject(json).optString(key, "").trim();
        } catch (JSONException e) {
            return "";
        }
    }

    static String join(String a, String b) {
        if (a.isEmpty()) return b;
        if (b.isEmpty()) return a;
        return a + " " + b;
    }

    private final class Session {
        private final Listener listener;
        private final LinkedBlockingQueue<byte[]> audio = new LinkedBlockingQueue<>(MAX_QUEUED_CHUNKS);
        volatile boolean stopping;
        volatile boolean cancelled;

        Session(Listener listener) {
            this.listener = listener;
        }

        void begin() {
            listener.onState(State.CONNECTING);
            new Thread(this::record, "dictation-mic").start();
            new Thread(this::recognize, "dictation-vosk").start();
        }

        void finish() {
            if (stopping) return;
            stopping = true;
            listener.onState(State.FINISHING);
        }

        private void post(Runnable r) {
            main.post(() -> {
                if (session == this) r.run();
            });
        }

        private void fail(DictationException.Reason reason, String message, Exception cause) {
            stopping = true;
            post(() -> {
                session = null;
                listener.onError(new DictationException(reason, message, cause));
                listener.onState(State.IDLE);
            });
        }

        /** Mic thread: queues 100 ms chunks until stop, then the END marker. */
        private void record() {
            try (Microphone mic = Microphone.open()) {
                while (!stopping) {
                    byte[] chunk = new byte[Microphone.CHUNK_BYTES];
                    int n = mic.read(chunk, () -> stopping);
                    if (n == 0) continue;
                    // A full queue means the recognizer is stuck loading; drop the oldest audio.
                    while (!audio.offer(n == chunk.length ? chunk : java.util.Arrays.copyOf(chunk, n))) {
                        audio.poll();
                    }
                }
            } catch (Microphone.Failure e) {
                fail(DictationException.Reason.AUDIO, e.getMessage(), e);
            } finally {
                // Queued audio stays for the final; make room for the marker if the queue is full.
                while (!audio.offer(END)) audio.poll();
            }
        }

        /** Recognizer thread: loads the model, then turns queued audio into text. */
        private void recognize() {
            Model m;
            try {
                m = loadModel(context);
            } catch (IOException e) {
                fail(DictationException.Reason.UNAVAILABLE, "The offline speech model could not load", e);
                return;
            }
            if (cancelled) return;
            post(() -> listener.onState(State.LISTENING));
            String committed = "";
            String shown = "";
            try (Recognizer rec = new Recognizer(m, Microphone.RATE)) {
                while (!cancelled) {
                    byte[] chunk = audio.poll(1, TimeUnit.SECONDS);
                    if (chunk == null) continue;
                    if (chunk == END) break;
                    String text;
                    if (rec.acceptWaveForm(chunk, chunk.length)) {
                        committed = join(committed, words(rec.getResult(), "text"));
                        text = committed;
                    } else {
                        text = join(committed, words(rec.getPartialResult(), "partial"));
                    }
                    if (!text.equals(shown)) {
                        shown = text;
                        post(() -> listener.onPartial(text));
                    }
                }
                if (cancelled) return;
                String all = join(committed, words(rec.getFinalResult(), "text"));
                post(() -> {
                    session = null;
                    listener.onFinal(all);
                    listener.onState(State.IDLE);
                });
            } catch (IOException e) {
                fail(DictationException.Reason.UNAVAILABLE, "The offline recognizer could not start", e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static synchronized Model loadModel(Context context) throws IOException {
        if (model == null) model = new Model(unpack(context).getAbsolutePath());
        return model;
    }

    /** Copies the bundled model to app storage unless this APK's copy is already there. */
    private static File unpack(Context context) throws IOException {
        File dir = new File(context.getNoBackupFilesDir(), ASSET_DIR);
        File stamp = new File(dir, ".installed-from");
        String version = apkVersion(context);
        if (version.equals(readSmall(stamp))) return dir;
        File staging = new File(context.getNoBackupFilesDir(), ASSET_DIR + ".partial");
        deleteTree(staging);
        copyAssets(context.getAssets(), ASSET_DIR, staging);
        try (OutputStream out = new FileOutputStream(new File(staging, ".installed-from"))) {
            out.write(version.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        deleteTree(dir);
        if (!staging.renameTo(dir)) throw new IOException("could not move the speech model into place");
        return dir;
    }

    private static String apkVersion(Context context) throws IOException {
        try {
            PackageInfo info = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
            return info.getLongVersionCode() + "@" + info.lastUpdateTime;
        } catch (PackageManager.NameNotFoundException e) {
            throw new IOException("own package not found", e);
        }
    }

    private static void copyAssets(AssetManager assets, String path, File to) throws IOException {
        String[] children = assets.list(path);
        if (children == null || children.length == 0) {
            File parent = to.getParentFile();
            if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
                throw new IOException("cannot create " + parent);
            }
            try (InputStream in = assets.open(path); OutputStream out = new FileOutputStream(to)) {
                byte[] buf = new byte[1 << 16];
                for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
            }
            return;
        }
        if (!to.isDirectory() && !to.mkdirs()) throw new IOException("cannot create " + to);
        for (String child : children) copyAssets(assets, path + "/" + child, new File(to, child));
    }

    private static String readSmall(File file) {
        if (!file.isFile() || file.length() > 256) return null;
        try (InputStream in = new java.io.FileInputStream(file)) {
            byte[] buf = new byte[(int) file.length()];
            int n = 0;
            while (n < buf.length) {
                int r = in.read(buf, n, buf.length - n);
                if (r < 0) break;
                n += r;
            }
            return new String(buf, 0, n, java.nio.charset.StandardCharsets.UTF_8);
        } catch (IOException e) {
            return null;
        }
    }

    private static void deleteTree(File file) {
        File[] children = file.listFiles();
        if (children != null) for (File child : children) deleteTree(child);
        // A leftover file only costs space; the next unpack retries.
        //noinspection ResultOfMethodCallIgnored
        file.delete();
    }
}
