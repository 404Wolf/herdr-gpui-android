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
        UNAVAILABLE,
        /**
         * The phone found its microphone silenced: Android said so for this recording, or it
         * gave only digital silence. Nothing was heard, so nothing is kept.
         */
        SILENCED,
        /** The desktop got only silence ({@code silent_audio}): nothing to transcribe, nothing kept. */
        SILENT_AUDIO,
        /** The desktop got audio too quiet to transcribe ({@code quiet_audio}); nothing kept. */
        QUIET_AUDIO,
        /** The desktop got no audio at all ({@code no_audio}); nothing kept. */
        NO_AUDIO,
        /** The desktop had nothing to transcribe, for a reason this phone does not know yet. */
        NOTHING;

        /**
         * The reason for a {@code final} carrying {@code code}: every code on a final means
         * there is nothing to transcribe, for good. Null without a code.
         */
        public static Reason ofFinalCode(String code) {
            if (code == null || code.isEmpty()) return null;
            Reason known = ofErrorCode(code);
            return known != null ? known : NOTHING;
        }

        /**
         * The reason for an {@code error} carrying {@code code} when that code is permanent
         * (retrying the same audio cannot help); null for a transient error, the default.
         */
        public static Reason ofErrorCode(String code) {
            if (code == null) return null;
            switch (code) {
                case "silent_audio": return SILENT_AUDIO;
                case "quiet_audio": return QUIET_AUDIO;
                case "no_audio": return NO_AUDIO;
                default: return null;
            }
        }

        /** Whether this is the microphone or the audio, not the desktop or the phone's setup. */
        public boolean nothingHeard() {
            return this == SILENCED || this == SILENT_AUDIO || this == QUIET_AUDIO || this == NO_AUDIO
                    || this == NOTHING;
        }
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

    /** The message for a session that ended with nothing to transcribe. */
    static String nothingMessage(Reason reason) {
        switch (reason) {
            case SILENCED: return "The mic was silenced (Android: privacy toggle, a call, or another app)";
            case SILENT_AUDIO: return "The mic sent silence";
            case QUIET_AUDIO: return "Too quiet to transcribe";
            case NO_AUDIO: return "No audio reached the desktop";
            default: return "Nothing to transcribe";
        }
    }
}
