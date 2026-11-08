package dev.herdr.android;

/** Local, non-secret display choices. No device names or vendor keycodes are inferred. */
final class GpuiDisplayOptions {
    static final int AUTO = 0, COMPACT = 1, STANDARD = 2;
    static final int ALT_AUTO = 0, ALT_SYMBOLS = 1, ALT_TERMINAL = 2;
    /** Renderer palettes a theme choice can name directly: Default, Nord, Mocha, Latte. */
    static final int PALETTE_MOCHA = 2, PALETTE_LATTE = 3;
    /** The theme choice after the palettes: follow the system's dark theme setting. */
    static final int THEME_SYSTEM = 4;
    final int mode;
    final int fontSize;
    final int altMode;

    GpuiDisplayOptions(int mode, int fontSize, int altMode) {
        this.mode = mode >= AUTO && mode <= STANDARD ? mode : AUTO;
        this.fontSize = fontSize == 0 || fontSize >= 9 && fontSize <= 24 ? fontSize : 0;
        this.altMode = altMode >= ALT_AUTO && altMode <= ALT_TERMINAL ? altMode : ALT_AUTO;
    }

    boolean compact(float widthDp, float heightDp) {
        // Include narrow fold-cover windows while leaving wide, nearly-square inner screens standard.
        return mode == COMPACT || mode == AUTO && widthDp > 0 && heightDp > 0
                && (heightDp <= 500 || widthDp <= 480);
    }

    float effectiveFont(boolean compact, float systemScale) {
        if (!Float.isFinite(systemScale) || systemScale <= 0) systemScale = 1;
        float base = fontSize == 0 ? compact ? 12 : 13 : fontSize;
        return Math.max(9, Math.min(36, base * systemScale));
    }

    boolean altUsesSymbols() {
        // Geometry and IME visibility must never change a physical key’s meaning.
        return altMode != ALT_TERMINAL;
    }

    /**
     * The palette a saved theme choice paints with. Following the system pairs
     * the two halves of one family, so switching never changes the terminal's
     * colour scheme, only its lightness. Unknown choices fall back to Default.
     */
    static int palette(int theme, boolean systemDark) {
        if (theme == THEME_SYSTEM) return systemDark ? PALETTE_MOCHA : PALETTE_LATTE;
        return theme >= 0 && theme < THEME_SYSTEM ? theme : 0;
    }
}
