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

        /**
         * The desktop found nothing to transcribe in {@code clip} ({@code reason}): it is gone,
         * and {@link #onTranscribed} gave it an empty text so its chip goes too.
         */
        default void onNothing(PendingClip clip, DictationException.Reason reason) { }
    }

    /** Turns one clip's audio into text; an IOException means "try again later". */
    interface Transcriber {
        String transcribe(PendingClip clip, File audio) throws IOException;
    }

    static final String TAG = "HerdrDictation";
    static final long FIRST_RETRY_MS = 10_000;
    static final long MAX_RETRY_MS = 5 * 60_000;
    /**
     * While the desktop is degraded (on its CPU, slow, its backend down) clips wait this long,
     * doubling to {@link #MAX_DEGRADED_RETRY_MS}; only the desktop seen ready on its GPU ends it.
     */
    static final long FIRST_DEGRADED_RETRY_MS = 2 * 60_000;
    static final long MAX_DEGRADED_RETRY_MS = 30 * 60_000;
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
    /** Worker thread only: the desktop is degraded; {@link #kick} does not cut the wait short. */
    private boolean degraded;
    private long degradedRetryMs = FIRST_DEGRADED_RETRY_MS;
    /** Worker thread only: the clock for {@link #degradedUntil} (milliseconds). */
    private final java.util.function.LongSupplier clock;
    /** Worker thread only: no pass before this while degraded. */
    private long degradedUntil;

    /** The process's instance. Call on the main thread; {@code uri} null means no server. */
    public static synchronized PendingDictations shared(Context context, URI uri) {
        if (shared != null) return shared;
        Context app = context.getApplicationContext() != null ? context.getApplicationContext() : context;
        File dir = new File(app.getNoBackupFilesDir(), "dictation-pending");
        Transcriber transcriber = uri == null ? (clip, audio) -> {
            throw new IOException("no dictation server");
        } : (clip, audio) -> upload(uri, clip.id, audio);
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
        this(store, transcriber, worker, main, log, System::currentTimeMillis);
    }

    PendingDictations(ClipStore store, Transcriber transcriber, ScheduledExecutorService worker, Executor main,
            java.util.function.Consumer<String> log, java.util.function.LongSupplier clock) {
        this.clock = clock;
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

    /** Its text could not be stored where it goes: offer it again (the recording is kept). Main thread. */
    public void offerAgain(String id) {
        offered.remove(id);
        offer();
    }

    /**
     * The user cleared where these recordings go (the composer's clear): they are held, never
     * sent or offered, until {@link #release} (its undo) or for 48 h as rescue audio. All or
     * none, in one write: false when it could not be saved (then none is held). Main thread.
     */
    public boolean hold(java.util.Collection<String> ids) {
        if (!store.holdAll(ids)) return false;
        for (String id : ids) offered.remove(id);
        changed();
        return true;
    }

    /**
     * Undoes {@link #hold}: they wait for the desktop again (parked ones stay parked), and their
     * text is offered. All or none, in one write: false when it could not be saved (then they are
     * still held, and the caller tries again). Main thread.
     */
    public boolean release(java.util.Collection<String> ids) {
        java.util.List<String> held = new java.util.ArrayList<>();
        for (String id : ids) if (store.isHeld(id)) held.add(id);
        if (held.isEmpty()) return true;
        if (!store.releaseAll(held)) return false;
        changed();
        offer();
        kick(0);
        return true;
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

    /**
     * Try the waiting clips after {@code delayMs}, e.g. the network came back. While the desktop
     * is degraded this does not cut its wait short: only {@link #desktopReady} on its GPU does.
     */
    void kick(long delayMs) {
        worker.execute(() -> {
            if (degraded) return;
            retryMs = FIRST_RETRY_MS;
            schedule(delayMs);
        });
    }

    /**
     * A live session found the desktop ready: on its GPU ({@code fast}) the waiting clips go now
     * and a degraded spell ends; otherwise the desktop is degraded and the clips keep waiting.
     */
    void desktopReady(boolean fast) {
        worker.execute(() -> {
            if (fast) {
                degraded = false;
                degradedRetryMs = FIRST_DEGRADED_RETRY_MS;
                retryMs = FIRST_RETRY_MS;
                schedule(0);
            } else {
                markDegraded();
            }
        });
    }

    /** A live session heard backend_slow / backend_unavailable: the clips wait too. */
    void desktopDegraded() {
        worker.execute(this::markDegraded);
    }

    /** Worker thread: starts (or keeps) a degraded spell, with its long wait. */
    private void markDegraded() {
        long now = clock.getAsLong();
        if (degraded && now < degradedUntil) return;
        degraded = true;
        degradedUntil = now + degradedRetryMs;
        reschedule(degradedRetryMs);
        degradedRetryMs = Math.min(MAX_DEGRADED_RETRY_MS, degradedRetryMs * 2);
    }

    /** Recordings given up on after {@link ClipStore#MAX_ATTEMPTS} uploads, for a manual retry. */
    public List<PendingClip> parked() {
        return store.parked();
    }

    /** The user's retry of a parked recording: it waits for the desktop again. Main thread. */
    public void retry(String id) {
        if (!store.unpark(id)) return;
        changed();
        // The user's word: it goes now, whatever the desktop was last seen doing.
        worker.execute(() -> {
            degraded = false;
            degradedUntil = 0;
            retryMs = FIRST_RETRY_MS;
            reschedule(0);
        });
    }

    /** Worker thread. Keeps the earlier of the scheduled and the requested pass. */
    private void schedule(long delayMs) {
        if (scheduled != null && !scheduled.isDone()
                && scheduled.getDelay(TimeUnit.MILLISECONDS) <= delayMs) return;
        reschedule(delayMs);
    }

    /** Worker thread. The next pass in {@code delayMs}, earlier or later than one already set. */
    private void reschedule(long delayMs) {
        if (scheduled != null) scheduled.cancel(false);
        scheduled = worker.schedule(this::pass, delayMs, TimeUnit.MILLISECONDS);
    }

    /**
     * Worker thread: transcribes waiting clips, oldest first, until the desktop is away or
     * degraded. A clip it took and failed on is counted ({@link #failed}) and passed over, so it
     * never holds up newer ones; one with nothing to transcribe is dropped.
     */
    void pass() {
        scheduled = null;
        // An index write that failed earlier (a full disk): try again first.
        store.flush();
        long now = clock.getAsLong();
        if (degraded && now < degradedUntil) {
            // Something asked early (a new clip, a retry): the degraded wait still holds.
            reschedule(degradedUntil - now);
            return;
        }
        // Cheap (an index scan): recordings the phone transcribed go once their 48 h are up.
        store.dropExpired();
        List<PendingClip> waiting = store.untranscribed();
        if (waiting.isEmpty()) return;
        int sent = 0;
        int passedOver = 0;
        long sentMs = 0;
        long started = System.nanoTime();
        for (PendingClip clip : waiting) {
            String text;
            long t = System.nanoTime();
            // Counted before it is sent, on disk: a crash or a lost index write never lets a long
            // clip be re-sent without end. No count written, no send.
            int charged = store.charge(clip.id);
            if (charged == -2) continue; // held or parked since this pass began: never sent
            if (charged < 0) {
                log.accept(line(sent, sentMs, waiting.size() - sent, started)
                        + ", cannot record the attempt (storage), retry in " + retryMs / 1000 + " s");
                schedule(retryMs);
                retryMs = Math.min(MAX_RETRY_MS, retryMs * 2);
                return;
            }
            try {
                text = transcriber.transcribe(clip, store.audio(clip.id));
            } catch (UploadFailure f) {
                if (f.kind == UploadFailure.Kind.NOTHING) {
                    nothing(clip, f);
                    continue;
                }
                if (f.kind == UploadFailure.Kind.FAILED && f.attempted) {
                    // This clip's trouble, not the desktop's: counted, and the next one goes.
                    failed(clip, f, line(sent, sentMs, waiting.size() - sent, started));
                    passedOver++;
                    continue;
                }
                failed(clip, f, line(sent, sentMs, waiting.size() - sent, started));
                return;
            } catch (IOException e) {
                failed(clip, e, line(sent, sentMs, waiting.size() - sent, started));
                return;
            }
            sent++;
            sentMs += clip.durationMs;
            log.accept("dictation: sent a " + clip.durationMs / 1000 + " s clip in "
                    + (System.nanoTime() - t) / 1_000_000 + " ms");
            if (store.transcribed(clip.id, text)) main.execute(this::offer);
        }
        if (passedOver > 0) {
            // failed() set the backoff for the clips passed over.
            log.accept(line(sent, sentMs, passedOver, started) + ", the rest sent");
            return;
        }
        log.accept(line(sent, sentMs, 0, started) + ", all sent");
        retryMs = FIRST_RETRY_MS;
        // The desktop took them all: whatever was degraded is over.
        degraded = false;
        degradedRetryMs = FIRST_DEGRADED_RETRY_MS;
    }

    /**
     * Worker thread: one upload failed. An attempt the desktop took (it got the whole clip) is
     * counted, and the clip is parked at {@link ClipStore#MAX_ATTEMPTS}, so a long clip is never
     * re-decoded without end. A degraded desktop starts the long wait; anything else, the usual
     * backoff.
     */
    private void failed(PendingClip clip, IOException e, String sofar) {
        UploadFailure f = e instanceof UploadFailure ? (UploadFailure) e
                : new UploadFailure(UploadFailure.Kind.UNREACHABLE, false, e.getMessage(), e);
        String what = f.kind.name().toLowerCase(java.util.Locale.ROOT);
        if (f.attempted) {
            int n = store.attempts(clip.id);
            what += ", attempt " + n + " of " + ClipStore.MAX_ATTEMPTS;
            if (n >= ClipStore.MAX_ATTEMPTS) {
                store.park(clip.id);
                what += ", parked";
                main.execute(this::changed);
            }
        } else {
            // Charged before sending, but the desktop never decoded it.
            store.refund(clip.id);
        }
        if (f.kind == UploadFailure.Kind.DEGRADED) {
            degraded = false; // a fresh spell: its wait starts now
            markDegraded();
            log.accept(sofar + ", desktop " + what + ", retry in " + (degradedUntil - clock.getAsLong()) / 1000 + " s");
            return;
        }
        log.accept(sofar + ", desktop " + what + " (" + e.getClass().getSimpleName() + "), retry in "
                + retryMs / 1000 + " s");
        schedule(retryMs);
        retryMs = Math.min(MAX_RETRY_MS, retryMs * 2);
    }

    /**
     * Worker thread: the desktop found nothing to transcribe in {@code clip}. It is dropped for
     * good (an empty text, so its chip goes), never resent and never parked.
     */
    private void nothing(PendingClip clip, UploadFailure f) {
        log.accept("dictation: a " + clip.durationMs / 1000 + " s clip had nothing to transcribe ("
                + f.nothing + "), dropped");
        if (!store.transcribed(clip.id, "")) return;
        DictationException.Reason reason = f.nothing;
        main.execute(() -> {
            offer();
            Listener l = listener;
            if (l != null) l.onNothing(clip, reason);
        });
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

    /** How long an upload waits for the desktop to say ready before giving up (nothing sent). */
    static final int UPLOAD_READY_MS = 4000;

    /**
     * Sends a whole clip through the dictation protocol and returns the server's final text. The
     * start carries the clip's id (the recording's, the same the live session sent) and
     * {@code "mode":"upload"}, so the desktop resumes from what it already decoded or answers a
     * resend from its cache, and holds an upload to no realtime budget. Nothing is sent unless
     * the desktop is ready on its GPU.
     */
    static String upload(URI uri, String id, File audio) throws IOException {
        DictationSocket s;
        try {
            s = DictationSocket.connect(uri, ServerDictationEngine.CONNECT_TIMEOUT_MS,
                    ServerDictationEngine.HANDSHAKE_TIMEOUT_MS);
        } catch (IOException e) {
            throw new UploadFailure(UploadFailure.Kind.UNREACHABLE, false, "not reached", e);
        }
        boolean attempted = false;
        try (DictationSocket socket = s;
             InputStream in = new BufferedInputStream(new FileInputStream(audio))) {
            socket.sendText(startMessage(id, "upload"));
            socket.readTimeout(UPLOAD_READY_MS);
            JSONObject ready = read(socket);
            if (ready == null || !"ready".equals(ready.optString("type"))) {
                throw failure(ready, false);
            }
            // Only a desktop that says it is on its GPU gets the clip: a missing backend (an old
            // server, without resume or an upload mode) is not taken on trust here.
            if (!"gpu".equals(ready.optString("backend", ""))) {
                throw new UploadFailure(UploadFailure.Kind.DEGRADED, false,
                        "backend " + ready.optString("backend", "not given") + ": not sent");
            }
            byte[] frame = new byte[UPLOAD_FRAME_BYTES];
            for (int n; (n = in.read(frame)) > 0; ) socket.sendBinary(frame, 0, n);
            socket.sendText("{\"type\":\"stop\"}");
            attempted = true;
            // Decoding runs at ~30x real time on the desktop; allow a wide margin.
            long seconds = audio.length() / ClipStore.BYTES_PER_SECOND;
            socket.readTimeout((int) Math.min(10 * 60_000, 60_000 + seconds * 500));
            while (true) {
                JSONObject e = read(socket);
                if (e == null) throw new UploadFailure(UploadFailure.Kind.FAILED, true, "closed before the final");
                String type = e.optString("type");
                String code = e.optString("code", "");
                if (type.equals("final")) {
                    // A final with a code: nothing to transcribe, for good.
                    DictationException.Reason nothing = DictationException.Reason.ofFinalCode(code);
                    if (nothing != null) throw UploadFailure.nothing(nothing, code);
                    return e.optString("text");
                }
                if (type.equals("error")) {
                    DictationException.Reason nothing = DictationException.Reason.ofErrorCode(code);
                    if (nothing != null) throw UploadFailure.nothing(nothing, code);
                    throw failure(e, true);
                }
                // "partial", "status" (finishing…): the final follows.
            }
        } catch (UploadFailure f) {
            throw f;
        } catch (JSONException e) {
            throw new UploadFailure(UploadFailure.Kind.FAILED, attempted, "bad message from the dictation server", e);
        } catch (IOException e) {
            throw new UploadFailure(attempted ? UploadFailure.Kind.FAILED : UploadFailure.Kind.UNREACHABLE,
                    attempted, e.getMessage(), e);
        }
    }

    /** The start message: the recording's id, and whether it is live or an upload. */
    static String startMessage(String id, String mode) {
        try {
            return new JSONObject().put("type", "start").put("sample_rate", Microphone.RATE)
                    .put("id", id).put("mode", mode).toString();
        } catch (JSONException impossible) {
            return "{\"type\":\"start\",\"sample_rate\":" + Microphone.RATE + "}";
        }
    }

    /** The next text message as JSON, or null when the server closed. */
    private static JSONObject read(DictationSocket s) throws IOException, JSONException {
        while (true) {
            WebSocketFrames.Message m = s.read();
            if (m == null) return null;
            if (m.opcode == WebSocketFrames.TEXT) return new JSONObject(m.text());
        }
    }

    /** What an error (or an unexpected message) means for the courier. */
    private static UploadFailure failure(JSONObject e, boolean attempted) {
        if (e == null) {
            return new UploadFailure(attempted ? UploadFailure.Kind.FAILED : UploadFailure.Kind.UNREACHABLE,
                    attempted, "closed");
        }
        String code = e.optString("code", "");
        UploadFailure.Kind kind = UploadFailure.degradedCode(code) ? UploadFailure.Kind.DEGRADED
                : attempted ? UploadFailure.Kind.FAILED : UploadFailure.Kind.UNREACHABLE;
        return new UploadFailure(kind, attempted, "server " + e.optString("type") + " " + code + ": "
                + e.optString("message"));
    }
}
