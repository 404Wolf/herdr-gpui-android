package dev.herdr.android;

import android.content.Context;
import dev.herdr.android.dictation.DictationEngine;
import dev.herdr.android.dictation.NoopDictationEngine;
import dev.herdr.android.dictation.OnDeviceRecognizers;
import dev.herdr.android.dictation.PendingDictations;
import dev.herdr.android.dictation.PhoneWhisper;
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
        // The phone's Whisper (a verified, provisioned model) when there is one, else the
        // trusted Android recognizer, else none: decided per session, once the check is done.
        PhoneWhisper.prepare(context, settings.dictationLocalModel);
        return new ServerDictationEngine(context, url, pending(context),
                PhoneWhisper.factory(OnDeviceRecognizers.factory(context, settings.dictationRecognizers)),
                settings.dictationFinalTimeoutMs);
    }

    private static DictationEngine shared;
    private static URI sharedUrl;
    /** The composer whose session the shared engine runs, or null. */
    private static Object owner;

    /**
     * The process's one engine, made on first use and again only when the provisioned server
     * changes: Herdr has one microphone entry point ("talk"), and one engine means one
     * recording, whichever window started it. Call on the main thread.
     */
    public static DictationEngine shared(Context context) {
        URI url = ProvisionedSettings.get(context).dictationUrl;
        if (shared == null || !java.util.Objects.equals(url, sharedUrl)) {
            if (shared != null) shared.cancel();
            owner = null;
            shared = create(context.getApplicationContext());
            sharedUrl = url;
        }
        return shared;
    }

    /** {@code composer} starts a session on the shared engine. */
    static void own(Object composer) { owner = composer; }

    /** {@code composer}'s session ended (or it never had one). */
    static void release(Object composer) {
        if (owner == composer) owner = null;
    }

    static boolean owns(Object composer) { return owner == composer; }

    /** Another window's composer has a session running on the shared engine. */
    static boolean busyElsewhere(Object composer) { return owner != null && owner != composer; }

    /** The process's recordings waiting for the desktop (none ever without a server). */
    public static PendingDictations pending(Context context) {
        return PendingDictations.shared(context, ProvisionedSettings.get(context).dictationUrl);
    }
}
