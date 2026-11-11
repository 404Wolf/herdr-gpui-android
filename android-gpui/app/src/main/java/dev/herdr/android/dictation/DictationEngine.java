package dev.herdr.android.dictation;

/**
 * Speech-to-text for the composer. Tap to {@link #start}, tap again to {@link #stop}: an engine
 * never stops itself on silence. It ends a session on its own only through
 * {@link Listener#onError}, after which it is back in {@link State#IDLE}.
 *
 * <p>All methods are called on the main thread, and every {@link Listener} callback is delivered
 * on the main thread. One session at a time: {@link #start} while not idle is ignored.
 */
public interface DictationEngine {
    /** Where a session is. */
    enum State {
        /** No session; {@link #start} begins one. */
        IDLE,
        /** Opening the microphone or the transcription server. */
        CONNECTING,
        /** Recording; partials arrive as the speaker talks. */
        LISTENING,
        /** {@link #stop} was called; the last audio is being transcribed before onFinal. */
        FINISHING
    }

    /** Receives one session's text and state, on the main thread. */
    interface Listener {
        /**
         * The whole utterance so far, replacing the previous partial (not a delta). It may revise
         * earlier words.
         */
        void onPartial(String text);

        /** The finished utterance after {@link #stop}; the session is then {@link State#IDLE}. */
        void onFinal(String text);

        void onState(State state);

        /** The session failed and is now {@link State#IDLE}; no onFinal follows. */
        void onError(DictationException error);
    }

    /** Begins a session reporting to {@code listener}. Needs the RECORD_AUDIO permission granted. */
    void start(Listener listener);

    /** Ends recording and delivers {@link Listener#onFinal} once the rest is transcribed. */
    void stop();

    /** Ends the session at once, discarding its text; no further callbacks for it. */
    void cancel();

    /** Whether {@link #start} can work now, e.g. false with no engine configured. */
    boolean isAvailable();
}
