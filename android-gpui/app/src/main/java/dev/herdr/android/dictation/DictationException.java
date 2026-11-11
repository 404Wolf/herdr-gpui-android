package dev.herdr.android.dictation;

/** Why a dictation session failed. Match on {@link #reason()}, never on the message. */
public final class DictationException extends Exception {
    private static final long serialVersionUID = 1L;

    /** The kinds of failure a composer may want to explain differently. */
    public enum Reason {
        /** RECORD_AUDIO is not granted. */
        PERMISSION,
        /** The microphone could not be opened or stopped delivering audio. */
        AUDIO,
        /** The transcription server or service could not be reached, or dropped the session. */
        UNREACHABLE,
        /** The server or service answered with an error. */
        SERVER,
        /** No engine is available (no server configured and no on-device model). */
        UNAVAILABLE
    }

    private final Reason reason;

    public DictationException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public DictationException(Reason reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
