package dev.herdr.android;

import android.content.Context;
import dev.herdr.android.dictation.DictationEngine;
import dev.herdr.android.dictation.FallbackDictationEngine;
import dev.herdr.android.dictation.NoopDictationEngine;
import dev.herdr.android.dictation.ServerDictationEngine;
import dev.herdr.android.dictation.SystemDictationEngine;
import java.net.URI;

/** Picks the composer's dictation engine from the provisioned settings. */
public final class Dictation {
    private Dictation() {}

    /**
     * The tailnet Whisper server with the phone's recognizer as fallback when "dictationUrl" is
     * provisioned; otherwise the phone's recognizer alone, or a no-op engine if it has none.
     * Call on the main thread; the engine keeps only the application context.
     */
    public static DictationEngine create(Context context) {
        Context app = context.getApplicationContext() != null ? context.getApplicationContext() : context;
        SystemDictationEngine system = new SystemDictationEngine(app);
        URI url = ProvisionedSettings.get(app).dictationUrl;
        if (url != null) return new FallbackDictationEngine(new ServerDictationEngine(app, url), system);
        return system.isAvailable() ? system : new NoopDictationEngine();
    }
}
