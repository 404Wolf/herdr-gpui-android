package dev.herdr.android.dictation;

/**
 * The phone's own speech recognizer, fed the same audio the desktop gets, for when the desktop is
 * unreachable, slow, or transcribing on its CPU. It never opens the microphone itself: the
 * session's one microphone feeds the recording, the desktop and this.
 *
 * <p>{@link #write} and {@link #endOfAudio} are called on the microphone thread and must not
 * block. {@link #cancel} is called on the main thread. The callback may be called on any thread,
 * at most one of onFinal and onError per recognizer, and nothing after {@link #cancel}.
 */
public interface OnDeviceRecognizer {
    /** Starts recognizers; the engine asks for one per session, on the main thread. */
    interface Factory {
        /** A started recognizer reporting to {@code callback}, or null when the phone has none. */
        OnDeviceRecognizer start(Callback callback);
    }

    /** One recognizer's text. */
    interface Callback {
        /** The whole utterance so far, replacing the previous partial. */
        void onPartial(String text);

        /** The whole utterance, after {@link #endOfAudio}; may be empty when it heard no speech. */
        void onFinal(String text);

        /** It stopped working; no onFinal follows. {@code why} is for logs. */
        void onError(String why);
    }

    /** PCM16 mono 16 kHz audio, {@code length} bytes of {@code chunk}; copied before returning. */
    void write(byte[] chunk, int length);

    /** No more audio: the final follows. */
    void endOfAudio();

    /** Stops at once; no more callbacks. */
    void cancel();

    /**
     * The engine needs this recognizer's text: the desktop is out of the session. A streaming
     * recognizer already works as it hears; one that transcribes at the end (the phone's
     * Whisper, which costs seconds of CPU) starts only now, so it never runs while the desktop
     * answers. Main thread.
     */
    default void needed() { }

    /**
     * How long its final may take, from {@link #needed} or stop, for {@code audioMs} of audio;
     * 0 for the engine's default.
     */
    default long finalBudgetMs(long audioMs) {
        return 0;
    }
}
