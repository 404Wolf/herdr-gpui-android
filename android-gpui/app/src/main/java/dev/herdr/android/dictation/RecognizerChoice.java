package dev.herdr.android.dictation;

import java.util.Collection;
import java.util.List;

/**
 * Which of the phone's recognizers may listen alongside the desktop. Android documents that a
 * service which does not support {@code EXTRA_AUDIO_SOURCE} silently opens the microphone
 * itself, competing with the session's own recording; so only recognizers trusted to read the
 * piped audio are used: the system's on-device recognizer, or a service whose component is
 * allowed explicitly (provisioned "dictationRecognizers", e.g. once checked on the phone). Any
 * other phone gets no shadow recognizer: the session records and queues as before.
 */
final class RecognizerChoice {
    /** Use the system's on-device recognizer. */
    static final String ON_DEVICE = "on-device";

    private RecognizerChoice() {}

    /**
     * The recognizer to use: {@link #ON_DEVICE}, an allowed and installed service component
     * ("package/class", the first allowed one installed), or null for none.
     */
    static String choose(boolean onDeviceAvailable, Collection<String> installed, List<String> allowed) {
        if (onDeviceAvailable) return ON_DEVICE;
        if (allowed == null) return null;
        for (String component : allowed) {
            if (component != null && installed.contains(component)) return component;
        }
        return null;
    }

    /**
     * Whether a recording other than ours started after the recognizer did: {@code baseline}
     * active recordings when it started (ours included), {@code now} active recordings now. Then
     * the service ignored the piped audio and opened the microphone itself.
     */
    static boolean foreignCapture(int baseline, int now) {
        return now > baseline;
    }

    /** The phone's language as a BCP 47 tag ("en-GB"), else "en-US" when it has none. */
    static String language(java.util.Locale locale) {
        if (locale == null || locale.getLanguage().isEmpty()) return "en-US";
        return locale.toLanguageTag();
    }

    /** A recognizer error (SpeechRecognizer.ERROR_*) in words, for the log and the status. */
    static String errorName(int error) {
        switch (error) {
            case 1: return "network timeout (1)";
            case 2: return "network error (2)";
            case 3: return "audio error (3)";
            case 4: return "server error (4)";
            case 5: return "client error (5)";
            case 6: return "heard no speech in time (6)";
            case 7: return "no match (7)";
            case 8: return "busy (8)";
            case 9: return "no microphone permission (9)";
            case 10: return "too many requests (10)";
            case 11: return "server disconnected (11)";
            case 12: return "it does not support this language (12)";
            case 13: return "its language model is not downloaded (13)";
            case 14: return "it cannot check this language (14)";
            case 15: return "the request was cut short (15)";
            default: return "error " + error;
        }
    }
}
