package dev.herdr.android.dictation;

/**
 * Speech-to-text for the composer, on the desktop's Whisper when it answers in time, else on the
 * phone. Tap to {@link #start}, tap again to {@link #stop}: an engine never stops itself on
 * silence.
 *
 * <p>A session goes CONNECTING, LISTENING (partials), TRANSCRIBING, then onFinal and
 * {@link State#IDLE}. When the desktop cannot be reached, drops mid-session, says its
 * transcription is slow or on its CPU, or has no final soon enough after {@link #stop}, the
 * phone's own recognizer takes over ({@link State#ON_DEVICE}): its text comes back through
 * {@code onFinal(text, true)}, marked offline. Without a recognizer on the phone the session goes
 * {@link State#OFFLINE_RECORDING} instead: the microphone keeps going and the whole session's
 * audio is saved in app storage; on {@link #stop} the session ends with onPending and
 * {@link State#PENDING}, and {@link PendingDictations} transcribes the recording once the desktop
 * is back. Every session ends in exactly one of IDLE or PENDING; after either, {@link #start}
 * begins a new one. Audio is never lost: a session the phone transcribed keeps its recording
 * for {@link ClipStore#KEEP_MS} (not sent anywhere, never replacing the text).
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
         * Recording, with the phone's own recognizer giving the partials and the final: the
         * desktop is out of this session (unreachable, slow, or on its CPU). Any desktop partial
         * shown was replaced by the phone's.
         */
        ON_DEVICE,
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

        /**
         * The finished utterance, {@code onDevice} when the phone transcribed it (show it as
         * offline). Engines call this one; by default it forwards to {@link #onFinal(String)}.
         */
        default void onFinal(String text, boolean onDevice) {
            onFinal(text);
        }
        /**
         * {@link #onPartial(String)}, with where the desktop cut the audio without a verified
         * seam ({@link UnverifiedCuts}: offsets into {@code text}, often none). Only the desktop
         * sends them; by default they are dropped.
         */
        default void onPartial(String text, int[] unverifiedCuts) {
            onPartial(text);
        }
        /** {@link #onFinal(String, boolean)}, with the desktop's unverified cuts (see onPartial). */
        default void onFinal(String text, boolean onDevice, int[] unverifiedCuts) {
            onFinal(text, onDevice);
        }

        /**
         * After stop, the desktop's final waits for a decode in flight or queued
         * ({@code {"type":"status","state":"finishing"}}): show "finishing…"; the final follows.
         */
        default void onFinishing(double tailSeconds) { }

        /**
         * The phone transcribes this session (the desktop is out): {@code asItHears} false when
         * its words come only every few seconds or at stop (the phone's Whisper), true when they
         * stream. Before the ON_DEVICE state.
         */
        default void onPhoneWords(boolean asItHears) { }

        /**
         * Asked right after onFinal: whether its text is durably stored. Only then is the
         * recording deleted; otherwise it is kept as a parked clip, with no expiry, shown "not
         * transcribed yet · retry".
         */
        default boolean finalStored() {
            return true;
        }

        void onState(State state);

        /**
         * The desktop answered this session with an error a retry may cure (not away, not
         * unreachable): show it as a desktop error. The session goes on as if the desktop were
         * away ({@link State#ON_DEVICE} or {@link State#OFFLINE_RECORDING}).
         */
        default void onDesktopError() { }

        /**
         * The session failed; {@link State#IDLE} follows and no onFinal. A reason that
         * {@link DictationException.Reason#nothingHeard} means nothing was heard and nothing is kept.
         */
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

    /** Ends recording; then onFinal, or onPending when neither the desktop nor the phone can transcribe. */
    void stop();

    /** Ends the session at once, discarding its audio and text; no further callbacks for it. */
    void cancel();

    /** Whether {@link #start} can work, i.e. a dictation server is configured. */
    boolean isAvailable();
}
