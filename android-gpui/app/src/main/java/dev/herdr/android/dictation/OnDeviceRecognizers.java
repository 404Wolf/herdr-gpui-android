package dev.herdr.android.dictation;

import android.content.Context;

/** The phone's own recognizers, for the composer's engine. */
public final class OnDeviceRecognizers {
    private OnDeviceRecognizers() {}

    /** Starts the phone's recognizer per session, or null when this Android cannot feed one audio. */
    public static OnDeviceRecognizer.Factory factory(Context context) {
        return AndroidOnDeviceRecognizer.factory(context);
    }
}
