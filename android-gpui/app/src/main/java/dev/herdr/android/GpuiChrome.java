package dev.herdr.android;

/**
 * Chrome colors for one terminal palette. The native renderer derives them from
 * the palette it paints the header with ({@code ChromeRoles::to_argb}), so the
 * host list, system bars, toolbar, rail and sheets follow the selected theme and
 * every text role keeps WCAG AA contrast on its surface.
 */
final class GpuiChrome {
    /** Thirteen ARGB colors, then 1 when the surface is light. */
    static final int ROLE_COUNT = 14;

    final int background, surface, onSurface, onSurfaceVariant, outline, primary;
    /** Tonal container for keycaps and the selected rail row. */
    final int control, selected;
    final int danger, warning, success, info, working;
    final boolean light;

    private GpuiChrome(int[] roles) {
        background = roles[0];
        surface = roles[1];
        onSurface = roles[2];
        onSurfaceVariant = roles[3];
        outline = roles[4];
        primary = roles[5];
        control = roles[6];
        selected = roles[7];
        danger = roles[8];
        warning = roles[9];
        success = roles[10];
        info = roles[11];
        working = roles[12];
        light = roles[13] != 0;
    }

    static GpuiChrome of(int[] roles) {
        if (roles == null || roles.length != ROLE_COUNT) {
            throw new IllegalStateException("The renderer returned no chrome roles for this theme");
        }
        return new GpuiChrome(roles);
    }

    /**
     * The roles for a renderer palette; an unknown index falls back to Default.
     * The caller must already have loaded the native library.
     */
    static GpuiChrome load(int palette) {
        ProvisionedSettings.applyPalette();
        int[] roles = nativeThemeColors(palette);
        return of(roles != null ? roles : nativeThemeColors(0));
    }

    /** Dot color for an agent state; the state's word always sits beside it. */
    int status(String status) {
        if ("working".equals(status)) return working;
        if ("blocked".equals(status)) return danger;
        if ("done".equals(status)) return info;
        if ("idle".equals(status)) return success;
        return onSurfaceVariant;
    }

    /** Platform alert theme with this chrome's lightness, so sheets match it. */
    int dialogTheme() {
        return light ? android.R.style.Theme_DeviceDefault_Light_Dialog_Alert
                : android.R.style.Theme_DeviceDefault_Dialog_Alert;
    }

    /** Platform theme for popup menus opened over this chrome. */
    int popupTheme() {
        return light ? android.R.style.Theme_DeviceDefault_Light : android.R.style.Theme_DeviceDefault;
    }

    /** The same color at {@code alpha} of 255. */
    static int withAlpha(int color, int alpha) {
        return (color & 0x00ffffff) | (alpha << 24);
    }

    /** Replaces the Default palette; see ProvisionedSettings. */
    static void setDefaultPalette(String json) {
        nativeSetDefaultPalette(json);
    }

    private static native boolean nativeSetDefaultPalette(String json);

    /** Roles for a palette in this class's order, or null for an unknown index. */
    private static native int[] nativeThemeColors(int index);
}
