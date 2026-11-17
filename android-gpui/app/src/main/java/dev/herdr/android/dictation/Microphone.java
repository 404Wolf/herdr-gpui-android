package dev.herdr.android.dictation;

import android.content.Context;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioRecordingConfiguration;
import android.media.MediaRecorder;
import java.util.List;

/**
 * The microphone as 16 kHz mono PCM16, read in 100 ms chunks on the caller's thread. It also
 * listens for Android silencing this recording ({@link #androidSilenced}), which Android does
 * without an error: the recorder then delivers zeros.
 *
 * <p>One at a time in the process: opening a microphone takes over from the one already open,
 * whose next read fails with {@link Failure.Cause#TAKEN_OVER}, and waits until it is released.
 * Across apps Android itself silences all but one recording, which the silenced session sees
 * through {@link #androidSilenced} and ends on.
 */
final class Microphone implements AudioInput {
    static final int RATE = 16000;
    static final int CHUNK_BYTES = RATE * 2 / 10;

    /** The microphone failed after opening; kept apart from network failures. */
    static final class Failure extends Exception {
        private static final long serialVersionUID = 1L;

        /** Why, as a class for the log: never the audio. */
        enum Cause {
            /** RECORD_AUDIO was refused when the recorder was made. */
            DENIED,
            /** The platform refused the format. */
            BAD_FORMAT,
            /** The recorder did not initialize (the microphone may be blocked or busy). */
            NOT_INITIALIZED,
            /** startRecording threw. */
            START_FAILED,
            /** Started, but not recording: usually another app (a call, the camera) has it. */
            IN_USE,
            /** A read returned an error code partway. */
            READ_ERROR,
            /** A newer recording in this app took the microphone over. */
            TAKEN_OVER
        }

        final Cause cause;

        Failure(Cause cause, String message) {
            super(message);
            this.cause = cause;
        }

        Failure(Cause cause, String message, Throwable source) {
            super(message, source);
            this.cause = cause;
        }
    }

    /** How long a new microphone waits for the one it takes over from to let go. */
    static final long TAKE_OVER_WAIT_MS = 600;
    private static final Object OPEN = new Object();
    /** The microphone open in this process, if any; guarded by {@link #OPEN}. */
    private static Microphone current;

    private final AudioRecord record;
    private final AudioManager audio;
    private volatile boolean takenOver;
    private final java.util.concurrent.CountDownLatch released = new java.util.concurrent.CountDownLatch(1);
    private final AudioManager.AudioRecordingCallback watch;
    private volatile boolean silenced;

    private Microphone(AudioRecord record, AudioManager audio) {
        this.record = record;
        this.audio = audio;
        int id = record.getAudioSessionId();
        watch = new AudioManager.AudioRecordingCallback() {
            @Override public void onRecordingConfigChanged(List<AudioRecordingConfiguration> configs) {
                silenced = silencedIn(configs, id);
            }
        };
    }

    /** Whether Android silences our recording ({@code id}) among {@code configs}. */
    private static boolean silencedIn(List<AudioRecordingConfiguration> configs, int id) {
        if (configs == null) return false;
        for (AudioRecordingConfiguration c : configs) {
            if (c.getClientAudioSessionId() == id) return c.isClientSilenced();
        }
        return false;
    }

    /**
     * Asks the microphone open in this process to let go, and waits a little for it: never two
     * recorders at once, which would leave one of them silenced.
     */
    private static void takeOver() {
        Microphone old;
        synchronized (OPEN) {
            old = current;
        }
        if (old == null) return;
        old.takenOver = true;
        try {
            old.released.await(TAKE_OVER_WAIT_MS, java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Opens and starts recording; the caller must hold RECORD_AUDIO. */
    static Microphone open(Context context) throws Failure {
        takeOver();
        AudioRecord record;
        try {
            int min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT);
            record = new AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, RATE,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                    Math.max(min, CHUNK_BYTES * 4));
        } catch (SecurityException e) {
            throw new Failure(Failure.Cause.DENIED, "The microphone permission was refused", e);
        } catch (IllegalArgumentException e) {
            throw new Failure(Failure.Cause.BAD_FORMAT, "The microphone could not be opened", e);
        }
        if (record.getState() != AudioRecord.STATE_INITIALIZED) {
            record.release();
            throw new Failure(Failure.Cause.NOT_INITIALIZED, "The microphone could not be opened");
        }
        try {
            record.startRecording();
        } catch (IllegalStateException e) {
            record.release();
            throw new Failure(Failure.Cause.START_FAILED, "The microphone could not start", e);
        }
        if (record.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) {
            record.release();
            throw new Failure(Failure.Cause.IN_USE, "The microphone is in use by another app");
        }
        Context app = context.getApplicationContext() != null ? context.getApplicationContext() : context;
        AudioManager audio = app.getSystemService(AudioManager.class);
        Microphone mic = new Microphone(record, audio);
        synchronized (OPEN) {
            current = mic;
        }
        if (audio != null) {
            // Callbacks on the main looper; the flag is read on the mic thread.
            audio.registerAudioRecordingCallback(mic.watch, null);
            mic.silenced = silencedIn(audio.getActiveRecordingConfigurations(), record.getAudioSessionId());
        }
        return mic;
    }

    /**
     * Fills {@code chunk} unless {@code stop} turns true first; returns the bytes read, which is
     * less than a chunk only when stopping.
     */
    @Override
    public int read(byte[] chunk, java.util.function.BooleanSupplier stop) throws Failure {
        int n = 0;
        while (n < chunk.length && !stop.getAsBoolean()) {
            if (takenOver) throw new Failure(Failure.Cause.TAKEN_OVER, "A newer recording took the microphone");
            int r = record.read(chunk, n, chunk.length - n);
            if (r < 0) throw new Failure(Failure.Cause.READ_ERROR, "The microphone stopped (" + r + ")");
            n += r;
        }
        return n;
    }

    @Override
    public boolean androidSilenced() {
        return silenced;
    }

    @Override
    public void close() {
        if (audio != null) audio.unregisterAudioRecordingCallback(watch);
        try {
            record.stop();
        } catch (IllegalStateException ignored) {
            // Never started or already stopped: release is what matters.
        }
        record.release();
        synchronized (OPEN) {
            if (current == this) current = null;
        }
        released.countDown();
    }
}
