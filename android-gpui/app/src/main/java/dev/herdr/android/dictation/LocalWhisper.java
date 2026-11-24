package dev.herdr.android.dictation;

import java.io.File;

/**
 * whisper.cpp on the phone's CPU (libherdr_whisper, built from the pinned whisper.cpp source by
 * scripts/build-whisper.sh): the English base.en model in a separate, checksum-verified file
 * ({@link LocalModel}). Used only when the desktop's Whisper is unavailable, or for a saved clip
 * the user asks to transcribe on the phone. Never touches the network.
 *
 * <p>One transcription at a time per instance; {@link #abort} may be called from any thread.
 * Calls block for seconds: never on the main thread.
 */
public final class LocalWhisper implements AutoCloseable {
    /** The model is English-only: said wherever its text is shown. */
    public static final String LANGUAGE_NOTE = "English only";

    private static volatile boolean loaded;

    /** Loads the native library once; false when this build has none. */
    static synchronized boolean load() {
        if (loaded) return true;
        try {
            String path = System.getProperty("herdr.whisper.lib");
            if (path != null && !path.isEmpty()) System.load(path);
            else System.loadLibrary("herdr_whisper");
            loaded = true;
        } catch (UnsatisfiedLinkError | SecurityException e) {
            loaded = false;
        }
        return loaded;
    }

    private volatile long handle;
    private final int threads;

    private LocalWhisper(long handle, int threads) {
        this.handle = handle;
        this.threads = threads;
    }

    /** The engine for {@code model}, or null when the library or the model does not load. */
    static LocalWhisper open(File model, int threads) {
        if (model == null || !model.isFile() || !load()) return null;
        long h = open(model.getAbsolutePath());
        return h == 0 ? null : new LocalWhisper(h, threads);
    }

    /**
     * PCM16 mono 16 kHz audio's text (trimmed; empty when it heard no speech), or null on failure,
     * on an abort (one that came before the run too), or when {@code length} is not within
     * {@code pcm}.
     */
    synchronized String transcribe(byte[] pcm, int length) {
        if (handle == 0 || pcm == null || length < 0 || length > pcm.length) return null;
        return text(transcribePcm(handle, pcm, length, threads));
    }

    /** The same for a raw PCM16 file (a saved clip), at most a recording's bound. */
    synchronized String transcribe(File pcm) {
        return transcribe(pcm, ClipStore.MAX_CLIP_BYTES);
    }

    /** {@link #transcribe(File)} with a bound: a longer file is not read (null). */
    synchronized String transcribe(File pcm, long maxBytes) {
        if (handle == 0) return null;
        return text(transcribeFile(handle, pcm.getAbsolutePath(), maxBytes, threads));
    }

    /** Whisper's UTF-8, decoded with replacement (a split character never fails the text). */
    static String text(byte[] utf8) {
        return utf8 == null ? null : new String(utf8, java.nio.charset.StandardCharsets.UTF_8).trim();
    }

    /** Guards the handle between {@link #abort} (any thread) and {@link #close}. */
    private final Object handleLock = new Object();

    /** Stops a transcription in progress (it returns null); any thread. */
    void abort() {
        synchronized (handleLock) {
            if (handle != 0) abort(handle);
        }
    }

    /** Waits for a transcription in progress (abort it first to stop it). */
    @Override
    public synchronized void close() {
        long h;
        synchronized (handleLock) {
            h = handle;
            handle = 0;
        }
        if (h != 0) close(h);
    }

    private static native long open(String model);

    private static native byte[] transcribePcm(long handle, byte[] pcm, int length, int threads);

    private static native byte[] transcribeFile(long handle, String file, long maxBytes, int threads);

    private static native void abort(long handle);

    private static native void close(long handle);
}
