package dev.herdr.android.dictation;

import java.io.Closeable;
import java.util.function.BooleanSupplier;

/** Where a session's audio comes from: the microphone, or a script in tests. */
interface AudioInput extends Closeable {
    /** Opens one session's input; the caller holds RECORD_AUDIO. */
    interface Opener {
        AudioInput open() throws Microphone.Failure;
    }

    /**
     * Fills {@code chunk} with 16 kHz mono PCM16 unless {@code stop} turns true first; returns the
     * bytes read, which is less than a chunk only when stopping.
     */
    int read(byte[] chunk, BooleanSupplier stop) throws Microphone.Failure;

    /** Whether Android says it is silencing this input now (privacy toggle, a call, another app). */
    default boolean androidSilenced() {
        return false;
    }

    @Override
    void close();
}
