package dev.herdr.android;

import android.content.Context;
import dev.herdr.android.dictation.DictationEngine;
import dev.herdr.android.dictation.NoopDictationEngine;
import dev.herdr.android.dictation.OnDeviceRecognizers;
import dev.herdr.android.dictation.PendingDictations;
import dev.herdr.android.dictation.ServerDictationEngine;
import java.net.URI;

/** Picks the composer's dictation engine from the provisioned settings. */
public final class Dictation {
    private Dictation() {}

    /**
     * The desktop's Whisper server when "dictationUrl" is provisioned, with the phone's own
     * recognizer when the desktop is away or slow (recording for later when the phone has none),
     * otherwise a no-op engine. Call on the main thread; the engine keeps only the application
     * context.
     */
    public static DictationEngine create(Context context) {
        ProvisionedSettings settings = ProvisionedSettings.get(context);
        URI url = settings.dictationUrl;
        if (url == null) return new NoopDictationEngine();
        return new ServerDictationEngine(context, url, pending(context),
                OnDeviceRecognizers.factory(context), settings.dictationFinalTimeoutMs);
    }

    /** The process's recordings waiting for the desktop (none ever without a server). */
    public static PendingDictations pending(Context context) {
        return PendingDictations.shared(context, ProvisionedSettings.get(context).dictationUrl);
    }
}
