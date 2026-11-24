package dev.herdr.android.dictation;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.util.concurrent.Executor;

/**
 * The phone's Whisper as the session's own recognizer: it keeps the session's audio in memory
 * and transcribes it only once the engine says the desktop is out ({@link #needed}) and the
 * audio has ended, so it never spends the CPU while the desktop answers. No partials: Whisper
 * here is one pass over the whole recording. English only (base.en).
 */
final class WhisperRecognizer implements OnDeviceRecognizer {
    /** A session past the server's own limit is not held: it fails, and the recording waits. */
    static final long MAX_BYTES = ClipStore.MAX_CLIP_BYTES;

    /** Opens the engine for one run (null when it cannot), off the main thread. */
    interface Engine {
        LocalWhisper open();
    }

    private final Engine engine;
    private final Executor background;
    private final Callback callback;
    private final ByteArrayOutputStream audio = new ByteArrayOutputStream();
    private boolean ended;
    private boolean wanted;
    private boolean running;
    private volatile boolean over;
    private volatile LocalWhisper current;

    WhisperRecognizer(Engine engine, Executor background, Callback callback) {
        this.engine = engine;
        this.background = background;
        this.callback = callback;
    }

    /** A factory for the verified model {@code model}, or null when there is none. */
    static Factory factory(File model, Executor background) {
        if (model == null) return null;
        return cb -> new WhisperRecognizer(() -> LocalWhisper.open(model, threads()), background, cb);
    }

    /** Half the cores, at least 2 and at most 4: the UI keeps a core while it runs. */
    static int threads() {
        int cores = Runtime.getRuntime().availableProcessors();
        return Math.max(2, Math.min(4, cores / 2));
    }

    @Override
    public synchronized void write(byte[] chunk, int length) {
        if (over || ended) return;
        if (audio.size() + length > MAX_BYTES) {
            over = true;
            callback.onError("too long for the phone");
            return;
        }
        audio.write(chunk, 0, length);
    }

    @Override
    public synchronized void endOfAudio() {
        ended = true;
        runIfReady();
    }

    @Override
    public synchronized void needed() {
        wanted = true;
        runIfReady();
    }

    /** ~0.5x real time on the Fold's CPU (DICTATION.md), plus loading the model: 10 s + the audio. */
    @Override
    public long finalBudgetMs(long audioMs) {
        return 10_000 + Math.max(0, audioMs);
    }

    @Override
    public void cancel() {
        over = true;
        LocalWhisper w = current;
        if (w != null) w.abort();
    }

    private void runIfReady() {
        if (!ended || !wanted || running || over) return;
        running = true;
        byte[] pcm = audio.toByteArray();
        background.execute(() -> {
            if (over) return;
            LocalWhisper w = engine.open();
            if (w == null) {
                finish(null, "the model did not load");
                return;
            }
            current = w;
            String text;
            try {
                text = w.transcribe(pcm, pcm.length);
            } finally {
                current = null;
                w.close();
            }
            finish(text, "failed");
        });
    }

    private void finish(String text, String why) {
        if (over) return;
        over = true;
        if (text == null) callback.onError(why);
        else callback.onFinal(text);
    }
}
