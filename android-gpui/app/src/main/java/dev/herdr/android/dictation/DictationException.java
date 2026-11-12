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
        /** The desktop could not be reached and the recording could not be saved for later. */
        UNREACHABLE,
        /** No dictation server is configured. */
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
