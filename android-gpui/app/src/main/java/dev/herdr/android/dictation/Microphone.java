package dev.herdr.android.dictation;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import java.io.Closeable;

/** The microphone as 16 kHz mono PCM16, read in 100 ms chunks on the caller's thread. */
final class Microphone implements Closeable {
    static final int RATE = 16000;
    static final int CHUNK_BYTES = RATE * 2 / 10;

    /** The microphone failed after opening; kept apart from network failures. */
    static final class Failure extends Exception {
        private static final long serialVersionUID = 1L;

        Failure(String message) {
            super(message);
        }

        Failure(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private final AudioRecord record;

    private Microphone(AudioRecord record) {
        this.record = record;
    }

    /** Opens and starts recording; the caller must hold RECORD_AUDIO. */
    static Microphone open() throws Failure {
        AudioRecord record;
        try {
            int min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT);
            record = new AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, RATE,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                    Math.max(min, CHUNK_BYTES * 4));
        } catch (IllegalArgumentException | SecurityException e) {
            throw new Failure("The microphone could not be opened", e);
        }
        if (record.getState() != AudioRecord.STATE_INITIALIZED) {
            record.release();
            throw new Failure("The microphone could not be opened");
        }
        try {
            record.startRecording();
        } catch (IllegalStateException e) {
            record.release();
            throw new Failure("The microphone could not start", e);
        }
        return new Microphone(record);
    }

    /**
     * Fills {@code chunk} unless {@code stop} turns true first; returns the bytes read, which is
     * less than a chunk only when stopping.
     */
    int read(byte[] chunk, java.util.function.BooleanSupplier stop) throws Failure {
        int n = 0;
        while (n < chunk.length && !stop.getAsBoolean()) {
            int r = record.read(chunk, n, chunk.length - n);
            if (r < 0) throw new Failure("The microphone stopped (" + r + ")");
            n += r;
        }
        return n;
    }

    @Override
    public void close() {
        try {
            record.stop();
        } catch (IllegalStateException ignored) {
            // Never started or already stopped: release is what matters.
        }
        record.release();
    }
}
