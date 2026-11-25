package dev.herdr.android.dictation;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.util.concurrent.Executor;

/**
 * The phone's Whisper as the session's own recognizer. It keeps the session's audio in memory and
 * works only once the engine says the desktop is out ({@link #needed}), so it never spends the
 * CPU while the desktop answers. Then every {@link #PIECE_BYTES} of new audio (~10 s) is
 * transcribed and appended as a partial, so words appear while the user speaks; at the end one
 * pass over the whole recording gives the final, which replaces them. English only (base.en).
 *
 * <p>One engine (model and buffers) per session, on {@code background}, closed when it ends.
 */
final class WhisperRecognizer implements OnDeviceRecognizer {
    /** A session past the server's own limit is not held: it fails, and the recording waits. */
    static final long MAX_BYTES = ClipStore.MAX_CLIP_BYTES;
    /** New audio transcribed as a partial: 10 s (base.en took ~5 s for 6 s on the Fold's CPU). */
    static final int PIECE_BYTES = 10 * ClipStore.BYTES_PER_SECOND;

    /** One session's engine. */
    interface Transcriber {
        /** PCM16's text, or null on failure or abort. */
        String transcribe(byte[] pcm, int length);

        void abort();

        void close();
    }

    /** Opens the engine for the session (null when it cannot), on {@code background}. */
    interface Engine {
        Transcriber open();
    }

    private final Engine engine;
    private final Executor background;
    private final Callback callback;
    private final ByteArrayOutputStream audio = new ByteArrayOutputStream();
    /** Guarded by this. */
    private boolean ended, wanted, finalStarted, pieceRunning;
    /** Guarded by this: where the next piece starts, and the pieces' words so far. */
    private int pieceFrom;
    private final StringBuilder pieces = new StringBuilder();
    private volatile boolean over;
    /** Opened on the background thread; read by {@link #cancel} to stop a run at once. */
    private volatile Transcriber session;
    private boolean opened;

    WhisperRecognizer(Engine engine, Executor background, Callback callback) {
        this.engine = engine;
        this.background = background;
        this.callback = callback;
    }

    /** A factory for the verified model {@code model}, or null when there is none. */
    static Factory factory(File model, Executor background) {
        if (model == null) return null;
        return cb -> new WhisperRecognizer(() -> local(model), background, cb);
    }

    /** The real engine on {@code model}, or null when it does not load. */
    static Transcriber local(File model) {
        LocalWhisper w = LocalWhisper.open(model, threads());
        if (w == null) return null;
        return new Transcriber() {
            @Override public String transcribe(byte[] pcm, int length) {
                return w.transcribe(pcm, length);
            }

            @Override public void abort() {
                w.abort();
            }

            @Override public void close() {
                w.close();
            }
        };
    }

    /** Half the cores, at least 2 and at most 4: the UI keeps a core while it runs. */
    static int threads() {
        int cores = Runtime.getRuntime().availableProcessors();
        return Math.max(2, Math.min(4, cores / 2));
    }

    @Override
    public boolean streams() {
        return false;
    }

    @Override
    public void write(byte[] chunk, int length) {
        byte[] piece = null;
        synchronized (this) {
            if (over || ended) return;
            if (audio.size() + length > MAX_BYTES) {
                over = true;
                callback.onError("too long for the phone");
                return;
            }
            audio.write(chunk, 0, length);
            piece = nextPiece();
        }
        if (piece != null) runPiece(piece);
    }

    /** Guarded by this: the audio since the last piece, once there is enough and none is running. */
    private byte[] nextPiece() {
        if (!wanted || ended || finalStarted || pieceRunning || audio.size() - pieceFrom < PIECE_BYTES) return null;
        byte[] all = audio.toByteArray();
        byte[] piece = java.util.Arrays.copyOfRange(all, pieceFrom, all.length);
        pieceFrom = all.length;
        pieceRunning = true;
        return piece;
    }

    private void runPiece(byte[] piece) {
        background.execute(() -> {
            Transcriber t = over ? null : session();
            String text = t == null ? null : t.transcribe(piece, piece.length);
            synchronized (this) {
                pieceRunning = false;
                if (over || finalStarted || text == null || text.trim().isEmpty()) return;
                if (pieces.length() > 0) pieces.append(' ');
                pieces.append(text.trim());
            }
            if (!over) callback.onPartial(pieces.toString());
        });
    }

    @Override
    public synchronized void endOfAudio() {
        ended = true;
        runIfReady();
    }

    @Override
    public void needed() {
        byte[] piece;
        synchronized (this) {
            wanted = true;
            runIfReady();
            piece = nextPiece();
        }
        if (piece != null) runPiece(piece);
    }

    /** ~0.5x real time on the Fold's CPU (DICTATION.md), plus loading the model: 10 s + the audio. */
    @Override
    public long finalBudgetMs(long audioMs) {
        return 10_000 + Math.max(0, audioMs);
    }

    @Override
    public void cancel() {
        over = true;
        Transcriber running = session;
        if (running != null) running.abort();
        // Closed on the background thread, after whatever runs there now.
        background.execute(this::closeSession);
    }

    /** Guarded by this. */
    private void runIfReady() {
        if (!ended || !wanted || finalStarted || over) return;
        finalStarted = true;
        byte[] pcm = audio.toByteArray();
        background.execute(() -> {
            if (over) {
                closeSession();
                return;
            }
            Transcriber t = session();
            if (t == null) {
                finish(null, "the model did not load");
                return;
            }
            String text = t.transcribe(pcm, pcm.length);
            closeSession();
            finish(text, "failed");
        });
    }

    /** Background thread: the session's engine, opened once. */
    private Transcriber session() {
        if (!opened) {
            opened = true;
            session = engine.open();
        }
        return session;
    }

    /** Background thread. */
    private void closeSession() {
        Transcriber t = session;
        session = null;
        if (t != null) t.close();
    }

    private void finish(String text, String why) {
        if (over) return;
        over = true;
        if (text == null) callback.onError(why);
        else callback.onFinal(text);
    }
}
