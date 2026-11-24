package dev.herdr.android.dictation;

import android.content.Context;
import android.util.Log;
import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The process's view of the phone's Whisper: whether the provisioned model is there and verified
 * (checked once, off the main thread), the session recognizer that uses it, and the engine for
 * "Transcribe on this phone". One background thread runs every local transcription, so two never
 * compete for the CPU.
 */
public final class PhoneWhisper {
    private static final String TAG = "HerdrDictation";
    private static final ExecutorService LOCAL = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "dictation-local");
        t.setDaemon(true);
        return t;
    });
    private static volatile LocalModel.Result status = new LocalModel.Result(LocalModel.State.NOT_PROVISIONED, null);
    private static volatile boolean checking;

    private PhoneWhisper() {}

    /** Checks the provisioned model once, in the background ({@code spec} null: none provisioned). */
    public static synchronized void prepare(Context context, LocalModel.Spec spec) {
        if (checking) return;
        checking = true;
        Context app = context.getApplicationContext() != null ? context.getApplicationContext() : context;
        LOCAL.execute(() -> {
            long t = System.nanoTime();
            LocalModel.Result r = LocalModel.check(app.getExternalFilesDir(null), spec, app.getNoBackupFilesDir());
            status = r;
            Log.i(TAG, "dictation: the phone's model is " + r.state.name().toLowerCase(java.util.Locale.ROOT)
                    + " (" + (System.nanoTime() - t) / 1_000_000 + " ms)"
                    + (r.state == LocalModel.State.OK && !LocalWhisper.load() ? ", but this build has no engine" : ""));
        });
    }

    /** The model's state now; {@link LocalModel.State#OK} only once verified. */
    public static LocalModel.State state() {
        LocalModel.Result r = status;
        if (r.state == LocalModel.State.OK && !LocalWhisper.load()) return LocalModel.State.NOT_PROVISIONED;
        return r.state;
    }

    /** Whether "Transcribe on this phone" can be offered. */
    public static boolean available() {
        return state() == LocalModel.State.OK;
    }

    /**
     * Session recognizers: the phone's Whisper when its model is verified, else {@code fallback}
     * (the Android recognizer, null when the phone has none trusted). Decided per session.
     */
    public static OnDeviceRecognizer.Factory factory(OnDeviceRecognizer.Factory fallback) {
        return cb -> {
            LocalModel.Result r = status;
            if (r.state == LocalModel.State.OK && LocalWhisper.load()) {
                return new WhisperRecognizer(() -> LocalWhisper.open(r.file, WhisperRecognizer.threads()), LOCAL, cb);
            }
            return fallback == null ? null : fallback.start(cb);
        };
    }

    /** The engine for a saved clip, or null when the model is not verified. */
    public static PendingDictations.LocalTranscriber transcriber() {
        LocalModel.Result r = status;
        if (r.state != LocalModel.State.OK || !LocalWhisper.load()) return null;
        File model = r.file;
        return audio -> {
            try (LocalWhisper w = LocalWhisper.open(model, WhisperRecognizer.threads())) {
                if (w == null) throw new java.io.IOException("the model did not load");
                return w.transcribe(audio);
            }
        };
    }

    /** Where local transcriptions run. */
    public static ExecutorService executor() {
        return LOCAL;
    }
}
