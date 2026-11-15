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
}
