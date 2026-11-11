package dev.herdr.android;

import android.content.Context;
import android.content.res.Configuration;
import dev.herdr.android.dictation.ServerDictationEngine;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Display defaults provisioned by whoever administers the device, beside the
 * provisioned hosts file in the app's own external files directory (only adb or
 * the app can write there; nix-android pushes it):
 *
 * <pre>
 * {"format": "remote-for-herdr-settings", "version": 1,
 *  "fontSize": 12,        terminal text in points before the system text scale, 9..24
 *  "uiScale": 0.9,        every control and its text, 0.7..1.3
 *  "capturable": true,    allow screenshots and screen mirroring (no FLAG_SECURE)
 *  "scrollSpeed": 1.4,    lines scrolled per row of finger travel, 0.5..3
 *  "dictationUrl": "ws://100.x.y.z:8770/v1/dictate",  the tailnet Whisper server
 *  "palette": {...}}      replaces Default: see model::set_default_palette_json
 * </pre>
 *
 * Missing or invalid fields keep the app's own default. Choices made in the app
 * (a theme, a text size, a pinch) still win over these defaults.
 */
final class ProvisionedSettings {
    static final String FILE_NAME = "remote-for-herdr-settings.json";
    static final String FORMAT = "remote-for-herdr-settings";
    private static final int MAX_BYTES = 64 * 1024;
    /** Wolf found one line per row of travel slow; 1.4 is the app's own default. */
    static final float DEFAULT_SCROLL_SPEED = 1.4f;

    private static ProvisionedSettings cached;
    private static boolean paletteApplied;

    /** 0 when unset. */
    final float fontSize;
    final float uiScale;
    /** Null when unset: the build's own rule applies. */
    final Boolean capturable;
    final String paletteJson;
    final float scrollSpeed;
    /** Null when unset or not a ws:// or wss:// URL: dictation uses the phone's recognizer. */
    final URI dictationUrl;

    private ProvisionedSettings(float fontSize, float uiScale, Boolean capturable, String paletteJson,
            float scrollSpeed, URI dictationUrl) {
        this.dictationUrl = dictationUrl;
        this.scrollSpeed = scrollSpeed;
        this.fontSize = fontSize;
        this.uiScale = uiScale;
        this.capturable = capturable;
        this.paletteJson = paletteJson;
    }

    /** Read once per process; a changed file applies on the next launch. */
    static synchronized ProvisionedSettings get(Context context) {
        if (cached == null) cached = read(context.getApplicationContext() != null
                ? context.getApplicationContext() : context);
        return cached;
    }

    private static ProvisionedSettings read(Context context) {
        ProvisionedSettings none = new ProvisionedSettings(0, 1, null, null, DEFAULT_SCROLL_SPEED, null);
        File dir = context.getExternalFilesDir(null);
        File file = dir == null ? null : new File(dir, FILE_NAME);
        if (file == null || !file.isFile() || file.length() > MAX_BYTES) return none;
        try (InputStream in = new FileInputStream(file)) {
            byte[] bytes = new byte[(int) file.length()];
            int read = 0;
            while (read < bytes.length) {
                int n = in.read(bytes, read, bytes.length - read);
                if (n < 0) break;
                read += n;
            }
            JSONObject root = new JSONObject(new String(bytes, 0, read, StandardCharsets.UTF_8));
            if (!FORMAT.equals(root.optString("format")) || root.optInt("version", 0) != 1) return none;
            double font = root.optDouble("fontSize", 0);
            double scale = root.optDouble("uiScale", 1);
            JSONObject palette = root.optJSONObject("palette");
            double speed = root.optDouble("scrollSpeed", DEFAULT_SCROLL_SPEED);
            return new ProvisionedSettings(
                    font >= 9 && font <= 24 ? (float) font : 0,
                    scale >= 0.7 && scale <= 1.3 ? (float) scale : 1,
                    root.has("capturable") ? root.optBoolean("capturable") : null,
                    palette == null ? null : palette.toString(),
                    speed >= 0.5 && speed <= 3 ? (float) speed : DEFAULT_SCROLL_SPEED,
                    ServerDictationEngine.parseUrl(root.optString("dictationUrl", null)));
        } catch (IOException | JSONException | RuntimeException unreadable) {
            return none;
        }
    }

    /** Whether windows may be captured: the provisioned choice, else upstream's debug-only rule. */
    static boolean capturable(Context context) {
        Boolean chosen = get(context).capturable;
        if (chosen != null) return chosen;
        // Upstream's rule: only a debuggable build with the probe setting on.
        return (context.getApplicationInfo().flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
                && "1".equals(android.provider.Settings.Global.getString(
                        context.getContentResolver(), "herdr_probe"));
    }

    /**
     * The base context with every dp and sp scaled by uiScale, for an Activity's
     * attachBaseContext. Density, not each view, so dialogs and sheets follow.
     */
    static Context scaled(Context base) {
        float scale = get(base).uiScale;
        if (scale == 1) return base;
        Configuration configuration = new Configuration(base.getResources().getConfiguration());
        configuration.densityDpi = Math.max(1, Math.round(configuration.densityDpi * scale));
        return base.createConfigurationContext(configuration);
    }

    /** Installs the provisioned palette once; the native library must be loaded. */
    static synchronized void applyPalette() {
        if (paletteApplied || cached == null || cached.paletteJson == null) return;
        paletteApplied = true;
        GpuiChrome.setDefaultPalette(cached.paletteJson);
    }
}
