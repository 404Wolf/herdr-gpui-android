package dev.herdr.android.dictation;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;
import java.io.IOException;
import java.net.URI;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Streams the microphone to the desktop's Whisper server (see the dictation module in ~/Config):
 * PCM16 mono at 16 kHz in 100 ms websocket frames, partials back about once a second, and the
 * final after {@link #stop}. Recording starts at once and is buffered while the socket connects,
 * so the first words are not lost.
 *
 * <p>The same audio also goes, from the first chunk, to the phone's own recognizer
 * ({@link OnDeviceRecognizer}), which listens alongside. Its text is used instead of the
 * desktop's when the desktop cannot be reached, is not ready in {@link #READY_TIMEOUT_MS}, drops,
 * sends an error, says its transcription runs on the CPU ({@code ready.backend} other than
 * "gpu"), or has no final within the final budget after stop (provisioned, default
 * {@link #DEFAULT_FINAL_BUDGET_MS}). That text is delivered as {@code onFinal(text, true)}.
 *
 * <p>Every session's audio is also written to app storage as it is recorded. A finished desktop
 * session deletes it; a session the phone transcribed keeps it for {@link ClipStore#KEEP_MS}.
 * When the phone has no recognizer (or it fails) and the desktop is out, the session carries on
 * in {@link State#OFFLINE_RECORDING} and, on stop, keeps that file as a {@link PendingClip} for
 * {@link PendingDictations}.
 */
public final class ServerDictationEngine implements DictationEngine {
    static final int CHUNK_BYTES = Microphone.CHUNK_BYTES;
    /** Audio kept in memory while connecting: 10 s (it is on disk as well). */
    static final int MAX_PENDING_CHUNKS = 100;
    static final int CONNECT_TIMEOUT_MS = 2500;
    /** From the tap until the server says ready, else the desktop is out of the session. */
    static final int READY_TIMEOUT_MS = 4000;
    /** From stop to the desktop's final, when the phone's own text is there to fall back on. */
    public static final int DEFAULT_FINAL_BUDGET_MS = 5000;
    /** From stop to the desktop's final with nothing on the phone to fall back on. */
    static final int FINAL_TIMEOUT_MS = 20000;
    /** From the moment the phone's recognizer is the one in use to its final, else the clip waits. */
    static final int ON_DEVICE_FINAL_TIMEOUT_MS = 10000;

    /** The main thread: runs callbacks in order, one at a time. */
    interface Main {
        void post(Runnable r);

        void postDelayed(Runnable r, long delayMs);

        void remove(Runnable r);
    }

    /** How long each wait may take, in milliseconds. */
    static final class Timeouts {
        final long readyMs;
        final long finalBudgetMs;
        final long finalMs;
        final long onDeviceFinalMs;

        Timeouts(long readyMs, long finalBudgetMs, long finalMs, long onDeviceFinalMs) {
            this.readyMs = readyMs;
            this.finalBudgetMs = finalBudgetMs;
            this.finalMs = finalMs;
            this.onDeviceFinalMs = onDeviceFinalMs;
        }

        static Timeouts withFinalBudget(long finalBudgetMs) {
            return new Timeouts(READY_TIMEOUT_MS, finalBudgetMs, FINAL_TIMEOUT_MS, ON_DEVICE_FINAL_TIMEOUT_MS);
        }
    }

    private final URI uri;
    private final PendingDictations pending;
    private final Main main;
    private final AudioInput.Opener microphone;
    private final BooleanSupplier permitted;
    /** Null when the phone has no recognizer of its own. */
    private final OnDeviceRecognizer.Factory onDevice;
    private final Timeouts timeouts;
    /** One line per fallback, reasons and timings only (never audio or text). */
    private final Consumer<String> log;
    /** Main thread only. */
    private Session session;

    /** Without the phone's recognizer: the desktop or a queued clip, as before. */
    public ServerDictationEngine(Context context, URI uri, PendingDictations pending) {
        this(context, uri, pending, null, DEFAULT_FINAL_BUDGET_MS);
    }

    /**
     * {@code onDevice} null when the phone has no recognizer; {@code finalBudgetMs} is how long
     * after stop the desktop's final is waited for before the phone's text is used.
     */
    public ServerDictationEngine(Context context, URI uri, PendingDictations pending,
            OnDeviceRecognizer.Factory onDevice, long finalBudgetMs) {
        this(uri, pending, androidMain(), Microphone::open, permission(context), onDevice,
                Timeouts.withFinalBudget(finalBudgetMs),
                line -> android.util.Log.i(PendingDictations.TAG, line));
    }

    ServerDictationEngine(URI uri, PendingDictations pending, Main main, AudioInput.Opener microphone,
            BooleanSupplier permitted, OnDeviceRecognizer.Factory onDevice, Timeouts timeouts,
            Consumer<String> log) {
        this.uri = uri;
        this.pending = pending;
        this.main = main;
        this.microphone = microphone;
        this.permitted = permitted;
        this.onDevice = onDevice;
        this.timeouts = timeouts;
        this.log = log;
    }

    private static Main androidMain() {
        Handler h = new Handler(Looper.getMainLooper());
        return new Main() {
            @Override public void post(Runnable r) {
                h.post(r);
            }

            @Override public void postDelayed(Runnable r, long delayMs) {
                h.postDelayed(r, delayMs);
            }

            @Override public void remove(Runnable r) {
                h.removeCallbacks(r);
            }
        };
    }

    private static BooleanSupplier permission(Context context) {
        Context app = context.getApplicationContext() != null ? context.getApplicationContext() : context;
        return () -> app.checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED;
    }

    /**
     * The server URL from configuration: a ws:// or wss:// URL with a host, or null. Parsed at
     * the boundary so the engine only ever holds a usable URI.
     */
    public static URI parseUrl(String text) {
        if (text == null || text.isEmpty() || text.length() > 512) return null;
        try {
            URI uri = new URI(text.trim());
            String scheme = uri.getScheme();
            if (uri.getHost() == null || scheme == null) return null;
            return scheme.equalsIgnoreCase("ws") || scheme.equalsIgnoreCase("wss") ? uri : null;
        } catch (java.net.URISyntaxException e) {
            return null;
        }
    }

    @Override
    public void start(String target, Listener listener) {
        if (session != null) return;
        if (!permitted.getAsBoolean()) {
            listener.onError(new DictationException(
                    DictationException.Reason.PERMISSION, "Microphone permission is not granted"));
            listener.onState(State.IDLE);
            return;
        }
        session = new Session(listener, pending.store().record(target, System.currentTimeMillis()));
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
        if (s != null) s.abort();
    }

    @Override
    public boolean isAvailable() {
        return true;
    }

    /** The desktop's {@code ready.backend}: anything but "gpu" means its text will be late. */
    static boolean fastBackend(JSONObject ready) {
        return ready.optString("backend", "gpu").equals("gpu");
    }

    private final class Session {
        private final Listener listener;
        private final ClipStore.Recording recording;
        private final long startedAt = System.nanoTime();
        private final Runnable readyTimeout = () -> goOffline("not ready in time", null);
        private final Runnable finishTimeout = () -> goOffline("no final in time", null);
        private final Runnable onDeviceTimeout = () -> onDeviceFailed("no final in time");
        /** Counted down once the server is ready or given up on, for a stop that came first. */
        private final CountDownLatch settled = new CountDownLatch(1);
        /** Main thread: the desktop is out of this session; the phone or the clip has its audio. */
        private boolean offline;
        /** Main thread: the mic thread has written its last audio. */
        private boolean micDone;
        /** Main thread: a partial is on screen. */
        private boolean shownPartial;
        /** Main thread: when stop was tapped, for the log. */
        private long stoppedAt;
        /** Main thread: the phone's recognizer, null when there is none or it failed. */
        private OnDeviceRecognizer local;
        /** Main thread: the phone's latest partial, shown once the desktop is out. */
        private String localPartial = "";
        /** Main thread: the phone's final, once it has one. */
        private String localFinal;
        private volatile boolean stopping;
        private volatile boolean cancelled;
        private volatile DictationSocket socket;
        /** Set once the server is ready and cleared when it is given up on: where audio goes. */
        private volatile DictationSocket ready;
        /** What the mic thread feeds besides the clip and the desktop; null when none. */
        private volatile OnDeviceRecognizer localFeed;

        Session(Listener listener, ClipStore.Recording recording) {
            this.listener = listener;
            this.recording = recording;
        }

        void begin() {
            listener.onState(State.CONNECTING);
            // Our microphone first: the phone's recognizer then reads our audio, and one that
            // would open the microphone itself is caught against it (AndroidOnDeviceRecognizer).
            AudioInput mic;
            try {
                mic = microphone.open();
            } catch (Microphone.Failure e) {
                micDone = true;
                fail(new DictationException(DictationException.Reason.AUDIO, e.getMessage(), e));
                return;
            }
            startOnDevice();
            main.postDelayed(readyTimeout, timeouts.readyMs);
            new Thread(() -> record(mic), "dictation-mic").start();
            new Thread(this::converse, "dictation-socket").start();
        }

        /**
         * Main thread, with our microphone open: the phone's recognizer listens alongside the
         * desktop from the first chunk.
         */
        private void startOnDevice() {
            if (onDevice == null) return;
            try {
                local = onDevice.start(new OnDeviceRecognizer.Callback() {
                    @Override public void onPartial(String text) {
                        post(() -> localPartial(text));
                    }

                    @Override public void onFinal(String text) {
                        post(() -> localFinal(text));
                    }

                    @Override public void onError(String why) {
                        post(() -> onDeviceFailed(why));
                    }
                });
            } catch (RuntimeException e) {
                log.accept("dictation: the phone's recognizer did not start (" + e.getClass().getSimpleName() + ")");
                local = null;
            }
            localFeed = local;
        }

        /** Main thread: stop recording; then the final, or the clip once the mic is done. */
        void finish() {
            if (stopping) return;
            stopping = true;
            stoppedAt = System.nanoTime();
            if (offline) {
                if (local != null) {
                    listener.onState(State.TRANSCRIBING);
                    main.postDelayed(onDeviceTimeout, timeouts.onDeviceFinalMs);
                    deliverLocalIfDone();
                } else {
                    keepIfDone();
                }
                return;
            }
            listener.onState(State.TRANSCRIBING);
            main.postDelayed(finishTimeout, local != null ? timeouts.finalBudgetMs : timeouts.finalMs);
        }

        /** Main thread, after {@link #cancel}: release everything, no more callbacks. */
        void abort() {
            cancelled = true;
            release();
            if (micDone) recording.discard();
        }

        private void release() {
            stopping = true;
            main.remove(readyTimeout);
            main.remove(finishTimeout);
            main.remove(onDeviceTimeout);
            ready = null;
            settled.countDown();
            DictationSocket s = socket;
            if (s != null) s.close();
            stopOnDevice();
        }

        private void stopOnDevice() {
            OnDeviceRecognizer l = local;
            local = null;
            localFeed = null;
            if (l != null) l.cancel();
        }

        /** Main thread: the session is over; later callbacks for it are dropped. */
        private void end() {
            if (session == this) session = null;
            release();
        }

        /**
         * Main thread: the desktop is out of this session, from the start or partway. The phone's
         * recognizer carries on if there is one; otherwise the audio so far is in the recording,
         * so nothing heard is lost; if it is not being saved either, the session fails instead.
         */
        private void goOffline(String why, Exception cause) {
            if (session != this || offline) return;
            offline = true;
            main.remove(readyTimeout);
            main.remove(finishTimeout);
            ready = null;
            settled.countDown();
            DictationSocket s = socket;
            if (s != null) s.close();
            log.accept("dictation: desktop out (" + why
                    + (cause == null ? "" : ", " + cause.getClass().getSimpleName() + ": " + cause.getMessage())
                    + ") " + sinceStart() + (local != null ? ", the phone transcribes" : ", recording for later"));
            if (local != null) {
                // The phone's own words replace the desktop's on screen.
                shownPartial = !localPartial.isEmpty();
                listener.onPartial(localPartial);
                if (stopping) {
                    main.postDelayed(onDeviceTimeout, timeouts.onDeviceFinalMs);
                    deliverLocalIfDone();
                } else {
                    listener.onState(State.ON_DEVICE);
                }
                return;
            }
            toOfflineRecording(cause);
        }

        /** Main thread: no transcription now; the recording waits for the desktop. */
        private void toOfflineRecording(Exception cause) {
            if (!recording.saved()) {
                fail(new DictationException(DictationException.Reason.UNREACHABLE,
                        "The desktop is offline and the phone could not save the recording", cause));
                return;
            }
            if (shownPartial) {
                shownPartial = false;
                listener.onPartial("");
            }
            if (stopping) keepIfDone();
            else listener.onState(State.OFFLINE_RECORDING);
        }

        private void localPartial(String text) {
            if (local == null) return;
            localPartial = text == null ? "" : text;
            if (!offline || stopping) return;
            shownPartial = !localPartial.isEmpty();
            listener.onPartial(localPartial);
        }

        private void localFinal(String text) {
            if (local == null) return;
            localFinal = text == null ? "" : text;
            deliverLocalIfDone();
        }

        /** Main thread: the phone's recognizer stopped working. */
        private void onDeviceFailed(String why) {
            if (session != this || local == null) return;
            log.accept("dictation: the phone's recognizer failed (" + why + ") " + sinceStart());
            main.remove(onDeviceTimeout);
            stopOnDevice();
            localFinal = null;
            if (offline) {
                toOfflineRecording(null);
            } else if (stopping) {
                // Back to the long wait for the desktop: the phone has nothing to offer.
                main.remove(finishTimeout);
                main.postDelayed(finishTimeout, timeouts.finalMs);
            }
        }

        /**
         * Main thread: the phone's text is the session's once the desktop is out, stop was tapped,
         * the mic has finished and the phone has its final. No speech heard by the phone leaves
         * the recording for the desktop instead.
         */
        private void deliverLocalIfDone() {
            if (session != this || !offline || !stopping || !micDone || local == null || localFinal == null) return;
            String text = localFinal.trim();
            if (text.isEmpty()) {
                onDeviceFailed("heard no speech");
                return;
            }
            end();
            PendingClip kept = recording.keepTranscribedOnPhone();
            log.accept("dictation: the phone's final " + (System.nanoTime() - stoppedAt) / 1_000_000
                    + " ms after stop, recording " + (kept != null ? "kept" : "not saved"));
            listener.onFinal(text, true);
            listener.onState(State.IDLE);
        }

        /** Main thread: once stopped, offline and the mic has finished, keeps the clip. */
        private void keepIfDone() {
            if (session != this || !offline || !stopping || !micDone || local != null) return;
            end();
            PendingClip clip = recording.keep();
            if (clip == null) {
                listener.onError(new DictationException(DictationException.Reason.AUDIO,
                        "The recording could not be saved"));
                listener.onState(State.IDLE);
                return;
            }
            pending.saved();
            listener.onPending(clip);
            listener.onState(State.PENDING);
        }

        private void fail(DictationException error) {
            if (session != this) return;
            end();
            if (micDone) recording.discard();
            listener.onError(error);
            listener.onState(State.IDLE);
        }

        private String sinceStart() {
            return (System.nanoTime() - startedAt) / 1_000_000 + " ms into the session";
        }

        /** Runs {@code r} on the main thread while this is still the live session. */
        private void post(Runnable r) {
            main.post(() -> {
                if (session == this) r.run();
            });
        }

        /** Mic thread: records until stop, saving everything and sending while the server is ready. */
        private void record(AudioInput opened) {
            ArrayDeque<byte[]> unsent = new ArrayDeque<>();
            try (AudioInput mic = opened) {
                while (!stopping) {
                    byte[] chunk = new byte[CHUNK_BYTES];
                    int n = mic.read(chunk, () -> stopping);
                    if (n == 0) continue;
                    if (!recording.write(chunk, n)) {
                        // A storage or session limit: end the session as if tapped.
                        post(this::finish);
                        break;
                    }
                    OnDeviceRecognizer l = localFeed;
                    if (l != null) l.write(chunk, n);
                    enqueue(unsent, n == chunk.length ? chunk : Arrays.copyOf(chunk, n));
                    send(unsent);
                }
            } catch (Microphone.Failure e) {
                // The mic failed partway: keep what was heard, as if the user tapped stop.
                post(this::finish);
            }
            OnDeviceRecognizer l = localFeed;
            if (l != null) l.endOfAudio();
            micFinished();
            if (cancelled) return;
            try {
                // A stop before the server was ready waits for it, or for the session to go offline.
                if (ready == null) settled.await(timeouts.readyMs, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            DictationSocket s = ready;
            if (s == null) return;
            try {
                send(unsent);
                s.sendText("{\"type\":\"stop\"}");
            } catch (IOException e) {
                post(() -> goOffline("send failed", e));
            }
        }

        /** Mic thread: tells the main thread the recording is complete. */
        private void micFinished() {
            main.post(() -> {
                micDone = true;
                if (cancelled || session != this) {
                    // Over (final, failure or cancel) before the mic finished: only keepIfDone or
                    // deliverLocalIfDone, which need micDone, keep a recording, so nothing will.
                    recording.discard();
                    return;
                }
                keepIfDone();
                deliverLocalIfDone();
            });
        }

        /** Mic thread: sends what is unsent while the server is ready. */
        private void send(ArrayDeque<byte[]> unsent) {
            DictationSocket s = ready;
            if (s == null) return;
            try {
                drain(unsent, s);
            } catch (IOException e) {
                ready = null;
                post(() -> goOffline("send failed", e));
            }
        }

        /** Socket thread: connects, then delivers what the server says. */
        private void converse() {
            DictationSocket s;
            try {
                s = DictationSocket.connect(uri, CONNECT_TIMEOUT_MS);
            } catch (IOException e) {
                post(() -> goOffline("unreachable", e));
                return;
            }
            socket = s;
            try {
                if (cancelled) return;
                // The recording's id: the courier resends a kept clip under the same id, so the
                // desktop resumes from what it decoded or answers from its cache.
                s.sendText(PendingDictations.startMessage(recording.id, "live"));
                while (true) {
                    WebSocketFrames.Message m = s.read();
                    if (m == null) throw new IOException("the server closed the session");
                    if (m.opcode != WebSocketFrames.TEXT) continue;
                    JSONObject e = new JSONObject(m.text());
                    String type = e.optString("type");
                    String text = e.optString("text");
                    if (type.equals("ready")) {
                        boolean fast = fastBackend(e);
                        String backend = e.optString("backend", "gpu");
                        // The courier learns the desktop's state from every live session.
                        pending.desktopReady(fast);
                        post(() -> {
                            if (offline) return;
                            if (!fast && local != null) {
                                // Its text would be late: the phone's is used from the start.
                                goOffline("backend " + backend, null);
                                return;
                            }
                            main.remove(readyTimeout);
                            ready = s;
                            settled.countDown();
                            listener.onState(stopping ? State.TRANSCRIBING : State.LISTENING);
                        });
                    } else if (type.equals("status")) {
                        // "finishing": the final waits for a decode; it follows, not an error.
                        if ("finishing".equals(e.optString("state"))) {
                            double tail = e.optDouble("tail_s", 0);
                            post(() -> {
                                if (!offline) listener.onFinishing(tail);
                            });
                        }
                    } else if (type.equals("partial")) {
                        post(() -> {
                            if (offline) return;
                            shownPartial = !text.isEmpty();
                            listener.onPartial(text);
                        });
                    } else if (type.equals("final")) {
                        post(() -> {
                            if (offline) return;
                            log.accept("dictation: desktop final " + (System.nanoTime() - stoppedAt) / 1_000_000
                                    + " ms after stop");
                            end();
                            // The text is stored first (the composer saves its draft at once);
                            // only then is the recording deleted: a crash in between keeps the
                            // audio, recovered at the next start.
                            listener.onFinal(text, false);
                            listener.onState(State.IDLE);
                            if (micDone) recording.discard();
                        });
                        return;
                    } else if (type.equals("error")) {
                        // e.g. the desktop's Whisper is slow or down: the recording is still good.
                        String code = e.optString("code", "unknown");
                        if (UploadFailure.degradedCode(code)) pending.desktopDegraded();
                        throw new IOException("server error " + code + ": " + e.optString("message"));
                    }
                }
            } catch (IOException | JSONException e) {
                post(() -> goOffline("dropped", e));
            } finally {
                s.close();
            }
        }
    }

    static void enqueue(ArrayDeque<byte[]> pending, byte[] chunk) {
        if (pending.size() == MAX_PENDING_CHUNKS) pending.removeFirst();
        pending.addLast(chunk);
    }

    private static void drain(ArrayDeque<byte[]> pending, DictationSocket s) throws IOException {
        while (!pending.isEmpty()) {
            byte[] p = pending.peekFirst();
            s.sendBinary(p, 0, p.length);
            pending.removeFirst();
        }
    }
}
