package dev.herdr.android;

/**
 * Where the left edge's swipe beats the system's Back gesture (Wolf 11-22: left edge = the
 * sidebar, right edge = the Work panel): one strip, vertically centred, no taller than the
 * 200 dp Android honours per edge. Pure.
 */
final class EdgeStrip {
    static final int WIDTH_DP = 24, TALL_DP = 200;

    private EdgeStrip() { }

    /** The strip {left, top, right, bottom} in a view {@code width} x {@code height} px; {@code wide}, {@code tall} in px. */
    static int[] left(int width, int height, int wide, int tall) {
        int h = Math.min(Math.max(0, height), Math.max(0, tall));
        int top = (Math.max(0, height) - h) / 2;
        return new int[] {0, top, Math.min(Math.max(0, width), Math.max(0, wide)), top + h};
    }
}
