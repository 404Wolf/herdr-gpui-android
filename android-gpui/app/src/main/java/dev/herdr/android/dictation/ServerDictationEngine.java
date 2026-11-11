package dev.herdr.android.dictation;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
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
 * Streams the microphone to the tailnet Whisper server (see the dictation module in ~/Config):
 * PCM16 mono at 16 kHz in 100 ms websocket frames, partials back about once a second, and the
 * final after {@link #stop}. Recording starts at once and is buffered while the socket connects,
 * so the first words are not lost.
 */
public final class ServerDictationEngine implements DictationEngine {
    static final int RATE = 16000;
    static final int CHUNK_BYTES = RATE * 2 / 10;
    /** Audio kept while connecting: 10 s. */
    static final int MAX_PENDING_CHUNKS = 100;
    static final int CONNECT_TIMEOUT_MS = 2500;
    static final int FINAL_TIMEOUT_MS = 20000;

    private final Context context;
    private final URI uri;
    private final Handler main = new Handler(Looper.getMainLooper());
    /** Main thread only. */
    private Session session;

    public ServerDictationEngine(Context context, URI uri) {
        this.context = context.getApplicationContext() != null ? context.getApplicationContext() : context;
        this.uri = uri;
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
    public void start(Listener listener) {
        if (session != null) return;
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            listener.onError(new DictationException(
                    DictationException.Reason.PERMISSION, "Microphone permission is not granted"));
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
        if (s != null) s.abort();
    }

    @Override
    public boolean isAvailable() {
        return true;
    }

    private final class Session {
        private final Listener listener;
        /** Counted down once the server is ready, or once it never will be. */
        private final CountDownLatch readyLatch = new CountDownLatch(1);
        private final Runnable finishTimeout = () -> fail(new DictationException(
                DictationException.Reason.UNREACHABLE, "The dictation server did not finish in time"));
        private volatile boolean stopping;
        private volatile boolean cancelled;
        private volatile DictationSocket socket;
        private volatile DictationSocket ready;

        Session(Listener listener) {
            this.listener = listener;
        }

        void begin() {
            listener.onState(State.CONNECTING);
            new Thread(this::record, "dictation-mic").start();
            new Thread(this::converse, "dictation-socket").start();
        }

        /** Main thread: stop recording and wait for the final. */
        void finish() {
            if (stopping) return;
            stopping = true;
            listener.onState(State.FINISHING);
            main.postDelayed(finishTimeout, FINAL_TIMEOUT_MS);
        }

        /** Main thread, after {@link #cancel}: release everything, no more callbacks. */
        void abort() {
            cancelled = true;
            release();
        }

        private void release() {
            stopping = true;
            main.removeCallbacks(finishTimeout);
            readyLatch.countDown();
            DictationSocket s = socket;
            if (s != null) s.close();
        }

        /** Main thread: the session is over; later callbacks for it are dropped. */
        private void end() {
            if (session == this) session = null;
            release();
        }

        private void fail(DictationException error) {
            if (session != this) return;
            end();
            listener.onError(error);
            listener.onState(State.IDLE);
        }

        /** Runs {@code r} on the main thread while this is still the live session. */
        private void post(Runnable r) {
            main.post(() -> {
                if (session == this) r.run();
            });
        }

        private void postFailure(DictationException.Reason reason, String message, Exception cause) {
            if (!cancelled) post(() -> fail(new DictationException(reason, message, cause)));
        }

        /** Mic thread: records until stop, sending once the server is ready, then sends "stop". */
        private void record() {
            AudioRecord rec;
            try {
                int min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT);
                rec = new AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, RATE,
                        AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                        Math.max(min, CHUNK_BYTES * 4));
            } catch (IllegalArgumentException | SecurityException e) {
                postFailure(DictationException.Reason.AUDIO, "The microphone could not be opened", e);
                return;
            }
            ArrayDeque<byte[]> pending = new ArrayDeque<>();
            try {
                if (rec.getState() != AudioRecord.STATE_INITIALIZED) {
                    throw new AudioException("AudioRecord did not initialize");
                }
                rec.startRecording();
                while (!stopping) {
                    byte[] chunk = new byte[CHUNK_BYTES];
                    int n = 0;
                    while (n < chunk.length && !stopping) {
                        int r = rec.read(chunk, n, chunk.length - n);
                        if (r < 0) throw new AudioException("AudioRecord.read returned " + r);
                        n += r;
                    }
                    if (n > 0) {
                        enqueue(pending, n == chunk.length ? chunk : Arrays.copyOf(chunk, n));
                    }
                    DictationSocket s = ready;
                    if (s != null) drain(pending, s);
                }
                rec.stop();
                if (cancelled) return;
                readyLatch.await(CONNECT_TIMEOUT_MS * 2L, TimeUnit.MILLISECONDS);
                DictationSocket s = ready;
                if (s == null || cancelled) return;
                drain(pending, s);
                s.sendText("{\"type\":\"stop\"}");
            } catch (AudioException e) {
                postFailure(DictationException.Reason.AUDIO, "The microphone stopped", e);
            } catch (IOException e) {
                postFailure(DictationException.Reason.UNREACHABLE, "Lost the dictation server", e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                rec.release();
            }
        }

        /** Socket thread: connects, then delivers what the server says. */
        private void converse() {
            DictationSocket s;
            try {
                s = DictationSocket.connect(uri, CONNECT_TIMEOUT_MS);
            } catch (IOException e) {
                readyLatch.countDown();
                postFailure(DictationException.Reason.UNREACHABLE,
                        "Could not reach the dictation server", e);
                return;
            }
            socket = s;
            try {
                if (cancelled) return;
                s.sendText("{\"type\":\"start\",\"sample_rate\":" + RATE + "}");
                while (true) {
                    WebSocketFrames.Message m = s.read();
                    if (m == null) throw new IOException("the server closed the session");
                    if (m.opcode != WebSocketFrames.TEXT) continue;
                    JSONObject e = new JSONObject(m.text());
                    String type = e.optString("type");
                    String text = e.optString("text");
                    if (type.equals("ready")) {
                        ready = s;
                        readyLatch.countDown();
                        post(() -> listener.onState(State.LISTENING));
                    } else if (type.equals("partial")) {
                        post(() -> listener.onPartial(text));
                    } else if (type.equals("final")) {
                        post(() -> {
                            end();
                            listener.onFinal(text);
                            listener.onState(State.IDLE);
                        });
                        return;
                    } else if (type.equals("error")) {
                        String message = e.optString("message", "The dictation server failed");
                        postFailure(DictationException.Reason.SERVER, message, null);
                        return;
                    }
                }
            } catch (IOException | JSONException e) {
                postFailure(DictationException.Reason.UNREACHABLE, "Lost the dictation server", e);
            } finally {
                readyLatch.countDown();
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
            byte[] p = pending.removeFirst();
            s.sendBinary(p, 0, p.length);
        }
    }

    /** The microphone failed after opening; kept apart from socket I/O failures. */
    private static final class AudioException extends Exception {
        private static final long serialVersionUID = 1L;

        AudioException(String message) {
            super(message);
        }
    }
}
