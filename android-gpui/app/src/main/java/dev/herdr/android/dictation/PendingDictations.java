package dev.herdr.android.dictation;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.os.Handler;
import android.os.Looper;
import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Dictations recorded while the desktop was away, and the courier that transcribes them once it
 * is back. Process-wide (see {@link #shared}); the clips live on disk, so a killed app picks them
 * up again. One worker thread uploads them oldest first, retrying with backoff, when the network
 * comes back, and whenever a live session shows the desktop is up.
 *
 * <p>A transcribed clip's text is held, also on disk, until the composer says it has placed it
 * ({@link #delivered}): text is never lost for want of a screen to show it on.
 */
public final class PendingDictations {
    /** Receives transcribed clips, on the main thread. */
    public interface Listener {
        /**
         * {@code clip}'s text (empty if it held no speech). Call {@link #delivered} once the text
         * is saved where it belongs; until then it is offered again to the next listener.
         */
        void onTranscribed(PendingClip clip, String text);

        /** The clips waiting changed: {@link #count} may differ. */
        void onChanged();
    }

    /** Turns one clip's audio into text; an IOException means "try again later". */
    interface Transcriber {
        String transcribe(PendingClip clip, File audio) throws IOException;
    }

    static final String TAG = "HerdrDictation";
    static final long FIRST_RETRY_MS = 10_000;
    static final long MAX_RETRY_MS = 5 * 60_000;
    /** After the network comes back, give the tailnet a moment before trying. */
    static final long NETWORK_SETTLE_MS = 3_000;

    private static PendingDictations shared;

    private final ClipStore store;
    private final Transcriber transcriber;
    private final ScheduledExecutorService worker;
    /** Posts to the main thread. */
    private final Executor main;
    /** One line per courier pass, counts and timings only (never audio or text); logcat in the app. */
    private final java.util.function.Consumer<String> log;
    /** Main thread only. */
    private Listener listener;
    /** Clips offered to the current listener and not yet delivered; main thread only. */
    private final Set<String> offered = new HashSet<>();
    /** Worker thread only. */
    private ScheduledFuture<?> scheduled;
    private long retryMs = FIRST_RETRY_MS;

    /** The process's instance. Call on the main thread; {@code uri} null means no server. */
    public static synchronized PendingDictations shared(Context context, URI uri) {
        if (shared != null) return shared;
        Context app = context.getApplicationContext() != null ? context.getApplicationContext() : context;
        File dir = new File(app.getNoBackupFilesDir(), "dictation-pending");
        Transcriber transcriber = uri == null ? (clip, audio) -> {
            throw new IOException("no dictation server");
        } : (clip, audio) -> upload(uri, audio);
        shared = new PendingDictations(new ClipStore(dir), transcriber,
                Executors.newSingleThreadScheduledExecutor(r -> {
                    Thread t = new Thread(r, "dictation-pending");
                    t.setDaemon(true);
                    return t;
                }), new Handler(Looper.getMainLooper())::post, line -> android.util.Log.i(TAG, line));
        ConnectivityManager net = app.getSystemService(ConnectivityManager.class);
        if (net != null && uri != null) {
            net.registerDefaultNetworkCallback(new ConnectivityManager.NetworkCallback() {
                @Override public void onAvailable(Network network) {
                    shared.kick(NETWORK_SETTLE_MS);
                }
            });
        }
        shared.kick(0);
        return shared;
    }

    PendingDictations(ClipStore store, Transcriber transcriber, ScheduledExecutorService worker, Executor main) {
        this(store, transcriber, worker, main, line -> { });
    }

    PendingDictations(ClipStore store, Transcriber transcriber, ScheduledExecutorService worker, Executor main,
            java.util.function.Consumer<String> log) {
        this.store = store;
        this.transcriber = transcriber;
        this.worker = worker;
        this.main = main;
        this.log = log;
    }

    ClipStore store() {
        return store;
    }

    /**
     * Sets who places transcribed text (null to stop); held texts are offered to it at once.
     * Main thread.
     */
    public void setListener(Listener l) {
        listener = l;
        offered.clear();
        offer();
    }

    /** Clips not yet placed: waiting for the desktop, or transcribed but not delivered. */
    public int count() {
        return store.size();
    }

    /** Recordings still waiting for the desktop, oldest first. */
    public List<PendingClip> waiting() {
        return store.untranscribed();
    }

    /** {@code id}'s text is saved where it belongs: forget the clip. Main thread. */
    public void delivered(String id) {
        offered.remove(id);
        store.remove(id);
        changed();
    }

    /** The user threw the recording away. Main thread. */
    public void drop(String id) {
        delivered(id);
    }

    /** A clip was kept: try it after the current backoff (the desktop was just unreachable). */
    void saved() {
        changed();
        worker.execute(() -> schedule(retryMs));
    }

    /** Try the waiting clips after {@code delayMs}, e.g. the desktop was just seen up. */
    void kick(long delayMs) {
        worker.execute(() -> {
            retryMs = FIRST_RETRY_MS;
            schedule(delayMs);
        });
    }

    /** Worker thread. Keeps the earlier of the scheduled and the requested pass. */
    private void schedule(long delayMs) {
        if (scheduled != null && !scheduled.isDone()
                && scheduled.getDelay(TimeUnit.MILLISECONDS) <= delayMs) return;
        if (scheduled != null) scheduled.cancel(false);
        scheduled = worker.schedule(this::pass, delayMs, TimeUnit.MILLISECONDS);
    }

    /** Worker thread: transcribes waiting clips, oldest first, until one fails. */
    void pass() {
        scheduled = null;
        List<PendingClip> waiting = store.untranscribed();
        if (waiting.isEmpty()) return;
        int sent = 0;
        long sentMs = 0;
        long started = System.nanoTime();
        for (PendingClip clip : waiting) {
            String text;
            long t = System.nanoTime();
            try {
                text = transcriber.transcribe(clip, store.audio(clip.id));
            } catch (IOException e) {
                log.accept(line(sent, sentMs, waiting.size() - sent, started)
                        + ", desktop unreachable (" + e.getClass().getSimpleName() + "), retry in "
                        + retryMs / 1000 + " s");
                schedule(retryMs);
                retryMs = Math.min(MAX_RETRY_MS, retryMs * 2);
                return;
            }
            sent++;
            sentMs += clip.durationMs;
            log.accept("dictation: sent a " + clip.durationMs / 1000 + " s clip in "
                    + (System.nanoTime() - t) / 1_000_000 + " ms");
            if (store.transcribed(clip.id, text)) main.execute(this::offer);
        }
        log.accept(line(sent, sentMs, 0, started) + ", all sent");
        retryMs = FIRST_RETRY_MS;
    }

    private static String line(int sent, long sentMs, int left, long started) {
        return "dictation: " + sent + " sent (" + sentMs / 1000 + " s of audio) in "
                + (System.nanoTime() - started) / 1_000_000 + " ms, " + left + " waiting";
    }

    /** Main thread: hands the listener every held text it has not been offered. */
    private void offer() {
        changed();
        Listener l = listener;
        if (l == null) return;
        for (ClipStore.Entry e : store.transcribed()) {
            if (!offered.add(e.clip.id)) continue;
            l.onTranscribed(e.clip, e.text);
            if (listener != l) return;
        }
    }

    private void changed() {
        Listener l = listener;
        if (l != null) l.onChanged();
    }

    static final int UPLOAD_FRAME_BYTES = 64 * 1024;

    /** Sends a whole clip through the dictation protocol and returns the server's final text. */
    static String upload(URI uri, File audio) throws IOException {
        try (DictationSocket s = DictationSocket.connect(uri, ServerDictationEngine.CONNECT_TIMEOUT_MS);
             InputStream in = new BufferedInputStream(new FileInputStream(audio))) {
            s.sendText("{\"type\":\"start\",\"sample_rate\":" + Microphone.RATE + "}");
            byte[] frame = new byte[UPLOAD_FRAME_BYTES];
            for (int n; (n = in.read(frame)) > 0; ) s.sendBinary(frame, 0, n);
            s.sendText("{\"type\":\"stop\"}");
            // Decoding runs at ~30x real time on the desktop; allow a wide margin.
            long seconds = audio.length() / ClipStore.BYTES_PER_SECOND;
            s.readTimeout((int) Math.min(10 * 60_000, 60_000 + seconds * 500));
            while (true) {
                WebSocketFrames.Message m = s.read();
                if (m == null) throw new IOException("the server closed before the final");
                if (m.opcode != WebSocketFrames.TEXT) continue;
                JSONObject e = new JSONObject(m.text());
                String type = e.optString("type");
                if (type.equals("final")) return e.optString("text");
                if (type.equals("error")) throw new IOException("server: " + e.optString("message"));
            }
        } catch (JSONException e) {
            throw new IOException("bad message from the dictation server", e);
        }
    }
}
