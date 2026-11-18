package dev.herdr.android;

/**
 * A tap on the draft drawn at the prompt puts the keyboard's cursor there, as
 * in a text field. The renderer reports each drawn run of the draft as
 * {row, col, cells, first character, characters} ({@code ClusterPlacement.Hint#draftRuns},
 * ghost.rs {@code draft_runs}), counting the draft's characters as drawn (a
 * chip by its label); {@link GhostText#fieldOffsets} maps those back to the
 * typed text. Pure: no views.
 */
final class DraftTaps {
    private DraftTaps() { }

    /**
     * The drawn character a tap at fractional cell {@code col} of drawn row
     * {@code row} puts the cursor before, or -1 when no draft run is on that
     * row. On a run, the nearer edge of the tapped cell; off the runs (past
     * the row's last word, before its first, in a gap), the nearest run edge.
     */
    static int drawnAt(int[][] runs, int row, float col) {
        if (runs == null) return -1;
        int best = -1;
        float distance = Float.MAX_VALUE;
        for (int[] run : runs) {
            if (run.length < 4 || run[0] != row || run[2] <= 0) continue;
            int start = run[1], end = run[1] + run[2];
            // A wide or combined cluster (its cells and characters differ) is a run of its own:
            // a tap on it goes to its nearer edge, never inside it.
            int chars = run.length >= 5 ? run[4] : run[2];
            if (col >= start && col < end) {
                if (chars != run[2]) return run[3] + (col - start < run[2] / 2f ? 0 : chars);
                return run[3] + Math.round(col - start);
            }
            float toStart = Math.abs(col - start), toEnd = Math.abs(col - end);
            if (toStart < distance) {
                distance = toStart;
                best = run[3];
            }
            if (toEnd < distance) {
                distance = toEnd;
                best = run[3] + chars;
            }
        }
        return best;
    }

    /** {@code drawn} (from {@link #drawnAt}) in the typed text, through {@code offsets}; -1 for none. */
    static int fieldIndex(int[] offsets, int drawn) {
        if (drawn < 0 || offsets == null || offsets.length == 0) return -1;
        return offsets[Math.min(drawn, offsets.length - 1)];
    }
}
