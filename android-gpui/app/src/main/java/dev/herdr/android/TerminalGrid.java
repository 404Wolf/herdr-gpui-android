package dev.herdr.android;

import android.content.Context;
import android.util.DisplayMetrics;

/**
 * The phone's terminal grid, estimated from the display and the chosen type
 * size before the terminal has laid out: "cols,rows,cellW,cellH".
 *
 * Every connection that may attach a pane passes it, so an attach never
 * offers herdr-core a made-up 80x24. The terminal still sends its measured
 * size once drawn; with viewer visibility the core waits for that.
 */
final class TerminalGrid {
    private TerminalGrid() {}

    static String estimate(Context context) {
        DisplayMetrics dm = context.getResources().getDisplayMetrics();
        int stored = context.getSharedPreferences("terminal-display", Context.MODE_PRIVATE).getInt("font", 13);
        float fontPx = Math.max(8f, (stored > 0 ? stored : 13) * context.getResources()
                .getConfiguration().fontScale * dm.density / dm.scaledDensity * dm.density);
        int cellW = Math.max(1, Math.round(fontPx * 0.6f));
        int cellH = Math.max(1, Math.round(fontPx * 1.25f));
        int bars = Math.round(48 * dm.density) * 3;
        int cols = Math.min(400, Math.max(20, dm.widthPixels / cellW));
        int rows = Math.min(200, Math.max(5, (dm.heightPixels - bars) / cellH));
        return cols + "," + rows + "," + cellW + "," + cellH;
    }
}
