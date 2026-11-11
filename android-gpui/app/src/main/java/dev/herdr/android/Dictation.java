package dev.herdr.android;

import android.content.Context;
import dev.herdr.android.dictation.DictationEngine;
import dev.herdr.android.dictation.FallbackDictationEngine;
import dev.herdr.android.dictation.NoopDictationEngine;
import dev.herdr.android.dictation.ServerDictationEngine;
import dev.herdr.android.dictation.VoskDictationEngine;
import java.net.URI;

/** Picks the composer's dictation engine from the provisioned settings. */
public final class Dictation {
    private Dictation() {}

    /**
     * The tailnet Whisper server with offline Vosk as fallback when "dictationUrl" is
     * provisioned; otherwise Vosk alone, or a no-op engine if this build has no Vosk model.
     * Call on the main thread; the engine keeps only the application context.
     */
    public static DictationEngine create(Context context) {
        Context app = context.getApplicationContext() != null ? context.getApplicationContext() : context;
        VoskDictationEngine offline = new VoskDictationEngine(app);
        URI url = ProvisionedSettings.get(app).dictationUrl;
        if (url != null) return new FallbackDictationEngine(new ServerDictationEngine(app, url), offline);
        return offline.isAvailable() ? offline : new NoopDictationEngine();
    }
}
