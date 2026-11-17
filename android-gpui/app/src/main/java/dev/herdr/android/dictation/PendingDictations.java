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

        /** The clips waiting changed: {@link #count} or {@link #stuck} may differ. */
        void onChanged();

        /**
         * The desktop found nothing to transcribe in {@code clip} ({@code reason}): it is gone,
         * and {@link #onTranscribed} gave it an empty text so its placeholder goes too.
         */
        default void onNothing(PendingClip clip, DictationException.Reason reason) { }
    }

    /**
     * Turns one clip's audio into text. A {@link DesktopError} means the desktop answered with an
     * error (counted against the clip); any other IOException means it is away (try later).
     */
    interface Transcriber {
        Transcript transcribe(PendingClip clip, File audio) throws IOException;
    }

    /** What the desktop made of a clip: its text, or nothing to transcribe, for good. */
    static final class Transcript {
        final String text;
        /** Non-null when there was nothing to transcribe; then {@link #text} is empty. */
        final DictationException.Reason nothing;
        /** The desktop's code for {@link #nothing}, for the log. */
        final String code;

        private Transcript(String text, DictationException.Reason nothing, String code) {
            this.text = text;
            this.nothing = nothing;
            this.code = code;
        }

        static Transcript of(String text) {
            return new Transcript(text == null ? "" : text, null, "");
        }

        static Transcript nothing(DictationException.Reason reason, String code) {
            return new Transcript("", reason, code);
        }
    }

    /** The desktop answered with an error that may pass (no code, or one not known as final). */
    static final class DesktopError extends IOException {
        private static final long serialVersionUID = 1L;
        /** The error's code, or "" without one. */
        final String code;

        DesktopError(String code) {
            super("desktop error " + (code.isEmpty() ? "without a code" : code));
            this.code = code;
        }
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

    /** Waiting recordings the courier stopped trying by itself, after repeated desktop errors. */
    public int stuck() {
        return store.stuck();
    }

    /**
     * The user tapped the microphone: stuck recordings are tried again, as is everything waiting.
     * Main thread; the work is on the courier's thread.
     */
    public void retryStuck() {
        worker.execute(() -> {
            if (store.retryStuck()) {
                log.accept("dictation: stuck clips tried again on a tap");
                main.execute(this::changed);
            }
            retryMs = FIRST_RETRY_MS;
            schedule(0);
        });
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

    /**
     * Worker thread: transcribes waiting clips, oldest first, until the desktop is away. A clip
     * the desktop answers with an error is counted and passed over, so it never holds up newer
     * ones; one with nothing to transcribe is dropped.
     */
    void pass() {
        scheduled = null;
        // Cheap (an index scan): recordings the phone transcribed go once their 48 h are up.
        store.dropExpired();
        List<PendingClip> waiting = store.due();
        if (waiting.isEmpty()) return;
        int sent = 0;
        int errors = 0;
        int dropped = 0;
        boolean again = false;
        long sentMs = 0;
        long started = System.nanoTime();
        for (PendingClip clip : waiting) {
            Transcript result;
            long t = System.nanoTime();
            try {
                result = transcriber.transcribe(clip, store.audio(clip.id));
            } catch (DesktopError e) {
                errors++;
                int n = store.failed(clip.id);
                boolean stuck = n >= ClipStore.MAX_SERVER_ERRORS;
                again |= n > 0 && !stuck;
                log.accept("dictation: desktop error on a " + clip.durationMs / 1000 + " s clip ("
                        + (e.code.isEmpty() ? "no code" : e.code) + "), " + n + " of "
                        + ClipStore.MAX_SERVER_ERRORS + (stuck ? ", stuck until the next mic tap" : ""));
                main.execute(this::changed);
                continue;
            } catch (IOException e) {
                log.accept(line(sent, sentMs, waiting.size() - sent - errors - dropped, started)
                        + ", desktop unreachable (" + e.getClass().getSimpleName() + "), retry in "
                        + retryMs / 1000 + " s");
                retryLater();
                return;
            }
            if (result.nothing != null) {
                dropped++;
                log.accept("dictation: a " + clip.durationMs / 1000 + " s clip had nothing to transcribe ("
                        + result.code + "), dropped");
                DictationException.Reason reason = result.nothing;
                if (store.transcribed(clip.id, "")) {
                    main.execute(() -> {
                        offer();
                        Listener l = listener;
                        if (l != null) l.onNothing(clip, reason);
                    });
                }
                continue;
            }
            sent++;
            sentMs += clip.durationMs;
            log.accept("dictation: sent a " + clip.durationMs / 1000 + " s clip in "
                    + (System.nanoTime() - t) / 1_000_000 + " ms");
            if (store.transcribed(clip.id, result.text)) main.execute(this::offer);
        }
        log.accept(line(sent, sentMs, 0, started) + (dropped > 0 ? ", " + dropped + " with nothing to transcribe" : "")
                + (errors > 0 ? ", " + errors + " desktop errors" : ", all sent"));
        if (again) {
            retryLater();
        } else {
            retryMs = FIRST_RETRY_MS;
        }
    }

    /** Worker thread: another pass after the backoff, which grows. */
    private void retryLater() {
        schedule(retryMs);
        retryMs = Math.min(MAX_RETRY_MS, retryMs * 2);
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

    /** Sends a whole clip through the dictation protocol and returns what the server made of it. */
    static Transcript upload(URI uri, File audio) throws IOException {
        try (DictationSocket s = DictationSocket.connect(uri, ServerDictationEngine.CONNECT_TIMEOUT_MS,
                ServerDictationEngine.HANDSHAKE_TIMEOUT_MS);
             InputStream in = new BufferedInputStream(new FileInputStream(audio))) {
            s.sendText(ServerDictationEngine.START);
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
                String code = e.optString("code", "");
                if (type.equals("final")) {
                    DictationException.Reason nothing = DictationException.Reason.ofFinalCode(code);
                    return nothing != null ? Transcript.nothing(nothing, code) : Transcript.of(e.optString("text"));
                }
                if (type.equals("error")) {
                    DictationException.Reason nothing = DictationException.Reason.ofErrorCode(code);
                    if (nothing != null) return Transcript.nothing(nothing, code);
                    throw new DesktopError(code);
                }
            }
        } catch (JSONException e) {
            throw new IOException("bad message from the dictation server", e);
        }
    }
}
