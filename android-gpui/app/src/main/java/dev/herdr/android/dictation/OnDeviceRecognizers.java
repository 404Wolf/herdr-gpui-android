package dev.herdr.android.dictation;

import android.content.Context;
import java.util.List;

/** The phone's own recognizers, for the composer's engine. */
public final class OnDeviceRecognizers {
    private OnDeviceRecognizers() {}

    /**
     * Starts the phone's recognizer per session, or null when this Android cannot feed one audio.
     * Only the system's on-device recognizer, or a service in {@code allowed} ("package/class"),
     * is used: others may ignore the piped audio and open the microphone themselves.
     */
    public static OnDeviceRecognizer.Factory factory(Context context, List<String> allowed) {
        return AndroidOnDeviceRecognizer.factory(context, allowed);
    }
}
