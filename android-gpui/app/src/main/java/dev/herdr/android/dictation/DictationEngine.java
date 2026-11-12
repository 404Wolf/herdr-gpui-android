package dev.herdr.android.dictation;

/**
 * Speech-to-text for the composer, always on the desktop's Whisper. Tap to {@link #start}, tap
 * again to {@link #stop}: an engine never stops itself on silence.
 *
 * <p>A session goes CONNECTING, LISTENING (partials), TRANSCRIBING, then onFinal and
 * {@link State#IDLE}. When the desktop cannot be reached, or drops mid-session, it goes
 * {@link State#OFFLINE_RECORDING} instead: the microphone keeps going and the whole session's
 * audio is saved in app storage; on {@link #stop} the session ends with onPending and
 * {@link State#PENDING}, and {@link PendingDictations} transcribes the recording once the desktop
 * is back. Every session ends in exactly one of IDLE or PENDING; after either, {@link #start}
 * begins a new one.
 *
 * <p>All methods are called on the main thread, and every {@link Listener} callback is delivered
 * on the main thread. One session at a time: {@link #start} during one is ignored.
 */
public interface DictationEngine {
    /** Where a session is. */
    enum State {
        /** No session; {@link #start} begins one. */
        IDLE,
        /** Recording already, while the desktop's dictation server is being reached. */
        CONNECTING,
        /** Recording on the desktop; partials arrive as the speaker talks. */
        LISTENING,
        /** {@link #stop} was called; the last audio is being transcribed before onFinal. */
        TRANSCRIBING,
        /**
         * The desktop is unreachable: recording into app storage, to be transcribed later. Any
         * partial shown for this session was withdrawn with {@code onPartial("")}: the whole
         * recording, including what was heard live, comes back later as one text.
         */
        OFFLINE_RECORDING,
        /**
         * The session is over and its recording waits for the desktop (see onPending). Like
         * {@link #IDLE}, {@link #start} begins a new session.
         */
        PENDING
    }

    /** Receives one session's text and state, on the main thread. */
    interface Listener {
        /**
         * The whole utterance so far, replacing the previous partial (not a delta). It may revise
         * earlier words. An empty text withdraws the partial.
         */
        void onPartial(String text);

        /** The finished utterance after {@link #stop}; {@link State#IDLE} follows. */
        void onFinal(String text);

        void onState(State state);

        /** The session failed; {@link State#IDLE} follows and no onFinal. */
        void onError(DictationException error);

        /**
         * The recording was saved for later; {@link State#PENDING} follows. Its text arrives
         * through {@link PendingDictations.Listener#onTranscribed} with the same clip id, even
         * after the app was killed in between.
         */
        void onPending(PendingClip clip);
    }

    /**
     * Begins a session reporting to {@code listener}. Needs the RECORD_AUDIO permission granted.
     * {@code target} (at most {@link PendingClip#MAX_TARGET_CHARS}) is kept with a pending
     * recording, so its text can find its way back, e.g. the host and pane it was spoken for.
     */
    void start(String target, Listener listener);

    /** Ends recording; then onFinal, or onPending when the desktop is away. */
    void stop();

    /** Ends the session at once, discarding its audio and text; no further callbacks for it. */
    void cancel();

    /** Whether {@link #start} can work, i.e. a dictation server is configured. */
    boolean isAvailable();
}
