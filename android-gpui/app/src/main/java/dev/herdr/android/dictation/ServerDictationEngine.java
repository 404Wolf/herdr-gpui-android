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
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Streams the microphone to the desktop's Whisper server (see the dictation module in ~/Config):
 * PCM16 mono at 16 kHz in 100 ms websocket frames, partials back about once a second, and the
 * final after {@link #stop}. Recording starts at once and is buffered while the socket connects,
 * so the first words are not lost.
 *
 * <p>Every session's audio is also written to app storage as it is recorded. If the desktop
 * cannot be reached, stops answering, or drops mid-session, the session carries on in
 * {@link State#OFFLINE_RECORDING} and, on stop, keeps that file as a {@link PendingClip} for
 * {@link PendingDictations}; a finished live session deletes it.
 */
public final class ServerDictationEngine implements DictationEngine {
    static final int CHUNK_BYTES = Microphone.CHUNK_BYTES;
    /** Audio kept in memory while connecting: 10 s (it is on disk as well). */
    static final int MAX_PENDING_CHUNKS = 100;
    static final int CONNECT_TIMEOUT_MS = 2500;
    /** From the tap until the server says ready, else the session records offline. */
    static final int READY_TIMEOUT_MS = 4000;
    static final int FINAL_TIMEOUT_MS = 20000;

    private final Context context;
    private final URI uri;
    private final PendingDictations pending;
    private final Handler main = new Handler(Looper.getMainLooper());
    /** Main thread only. */
    private Session session;

    public ServerDictationEngine(Context context, URI uri, PendingDictations pending) {
        this.context = context.getApplicationContext() != null ? context.getApplicationContext() : context;
        this.uri = uri;
        this.pending = pending;
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
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
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

    private final class Session {
        private final Listener listener;
        private final ClipStore.Recording recording;
        private final Runnable readyTimeout = () -> goOffline(null);
        private final Runnable finishTimeout = () -> goOffline(null);
        /** Counted down once the server is ready or given up on, for a stop that came first. */
        private final CountDownLatch settled = new CountDownLatch(1);
        /** Main thread: the desktop is out of this session; its audio goes to the clip. */
        private boolean offline;
        /** Main thread: the mic thread has written its last audio. */
        private boolean micDone;
        /** Main thread: a partial is on screen. */
        private boolean shownPartial;
        private volatile boolean stopping;
        private volatile boolean cancelled;
        private volatile DictationSocket socket;
        /** Set once the server is ready and cleared when it is given up on: where audio goes. */
        private volatile DictationSocket ready;

        Session(Listener listener, ClipStore.Recording recording) {
            this.listener = listener;
            this.recording = recording;
        }

        void begin() {
            listener.onState(State.CONNECTING);
            main.postDelayed(readyTimeout, READY_TIMEOUT_MS);
            new Thread(this::record, "dictation-mic").start();
            new Thread(this::converse, "dictation-socket").start();
        }

        /** Main thread: stop recording; then the final, or the clip once the mic is done. */
        void finish() {
            if (stopping) return;
            stopping = true;
            if (offline) {
                keepIfDone();
                return;
            }
            listener.onState(State.TRANSCRIBING);
            main.postDelayed(finishTimeout, FINAL_TIMEOUT_MS);
        }

        /** Main thread, after {@link #cancel}: release everything, no more callbacks. */
        void abort() {
            cancelled = true;
            release();
            if (micDone) recording.discard();
        }

        private void release() {
            stopping = true;
            main.removeCallbacks(readyTimeout);
            main.removeCallbacks(finishTimeout);
            ready = null;
            settled.countDown();
            DictationSocket s = socket;
            if (s != null) s.close();
        }

        /** Main thread: the session is over; later callbacks for it are dropped. */
        private void end() {
            if (session == this) session = null;
            release();
        }

        /**
         * Main thread: the desktop is out of this session, from the start or partway. The audio
         * so far is in the recording, so nothing heard is lost; if it is not being saved, the
         * session fails instead.
         */
        private void goOffline(Exception cause) {
            if (session != this || offline) return;
            offline = true;
            main.removeCallbacks(readyTimeout);
            main.removeCallbacks(finishTimeout);
            ready = null;
            settled.countDown();
            DictationSocket s = socket;
            if (s != null) s.close();
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

        /** Main thread: once stopped, offline and the mic has finished, keeps the clip. */
        private void keepIfDone() {
            if (session != this || !offline || !stopping || !micDone) return;
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

        /** Runs {@code r} on the main thread while this is still the live session. */
        private void post(Runnable r) {
            main.post(() -> {
                if (session == this) r.run();
            });
        }

        /** Mic thread: records until stop, saving everything and sending while the server is ready. */
        private void record() {
            ArrayDeque<byte[]> unsent = new ArrayDeque<>();
            boolean opened = false;
            try (Microphone mic = Microphone.open()) {
                opened = true;
                while (!stopping) {
                    byte[] chunk = new byte[CHUNK_BYTES];
                    int n = mic.read(chunk, () -> stopping);
                    if (n == 0) continue;
                    if (!recording.write(chunk, n)) {
                        // A storage or session limit: end the session as if tapped.
                        post(this::finish);
                        break;
                    }
                    enqueue(unsent, n == chunk.length ? chunk : Arrays.copyOf(chunk, n));
                    send(unsent);
                }
            } catch (Microphone.Failure e) {
                if (!opened) {
                    micFinished();
                    if (!cancelled) post(() -> fail(new DictationException(
                            DictationException.Reason.AUDIO, e.getMessage(), e)));
                    return;
                }
                // The mic failed partway: keep what was heard, as if the user tapped stop.
                post(this::finish);
            }
            micFinished();
            if (cancelled) return;
            try {
                // A stop before the server was ready waits for it, or for the session to go offline.
                if (ready == null) settled.await(READY_TIMEOUT_MS, TimeUnit.MILLISECONDS);
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
                post(() -> goOffline(e));
            }
        }

        /** Mic thread: tells the main thread the recording is complete. */
        private void micFinished() {
            main.post(() -> {
                micDone = true;
                if (cancelled || session != this) {
                    // Over (final, failure or cancel) before the mic finished: only keepIfDone,
                    // which needs micDone, keeps a recording, so nothing will.
                    recording.discard();
                    return;
                }
                keepIfDone();
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
                post(() -> goOffline(e));
            }
        }

        /** Socket thread: connects, then delivers what the server says. */
        private void converse() {
            DictationSocket s;
            try {
                s = DictationSocket.connect(uri, CONNECT_TIMEOUT_MS);
            } catch (IOException e) {
                post(() -> goOffline(e));
                return;
            }
            socket = s;
            try {
                if (cancelled) return;
                s.sendText("{\"type\":\"start\",\"sample_rate\":" + Microphone.RATE + "}");
                while (true) {
                    WebSocketFrames.Message m = s.read();
                    if (m == null) throw new IOException("the server closed the session");
                    if (m.opcode != WebSocketFrames.TEXT) continue;
                    JSONObject e = new JSONObject(m.text());
                    String type = e.optString("type");
                    String text = e.optString("text");
                    if (type.equals("ready")) {
                        post(() -> {
                            if (offline) return;
                            main.removeCallbacks(readyTimeout);
                            ready = s;
                            settled.countDown();
                            listener.onState(stopping ? State.TRANSCRIBING : State.LISTENING);
                            pending.kick(0);
                        });
                    } else if (type.equals("partial")) {
                        post(() -> {
                            if (offline) return;
                            shownPartial = !text.isEmpty();
                            listener.onPartial(text);
                        });
                    } else if (type.equals("final")) {
                        post(() -> {
                            if (offline) return;
                            end();
                            if (micDone) recording.discard();
                            listener.onFinal(text);
                            listener.onState(State.IDLE);
                        });
                        return;
                    } else if (type.equals("error")) {
                        // e.g. the desktop's Whisper is down: the recording is still good.
                        throw new IOException("server: " + e.optString("message"));
                    }
                }
            } catch (IOException | JSONException e) {
                post(() -> goOffline(e));
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
