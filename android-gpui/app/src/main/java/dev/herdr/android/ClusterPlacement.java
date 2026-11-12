package dev.herdr.android;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Where the floating mic-and-Enter cluster goes over the terminal: bottom
 * right, on cells that hold nothing that matters (the program's hint line,
 * the rules around its prompt, blank space), never over the caret, the ghost
 * text or a dialog. The renderer reports, per drawn row, how many columns
 * from the left are busy ({@link Hint#busy}); this picks the lowest rows whose
 * right end is free. It stays where it was while that still fits, so the
 * cluster does not hop as output arrives. Pure: no views.
 */
final class ClusterPlacement {
    /** How far up from the bottom a spot is looked for, in rows. */
    static final int SEARCH_ROWS = 16;

    /** The renderer's report (ghost_bridge.rs), in logical pixels. */
    static final class Hint {
        final float left, top, cellWidth, cellHeight;
        final int cols, rows;
        final boolean anchored;
        final int[] busy;
        /** The first row of the bottom block that must stay in view (the prompt, a dialog). */
        final int guard;

        Hint(float left, float top, float cellWidth, float cellHeight, int cols, int rows,
                boolean anchored, int[] busy, int guard) {
            this.left = left;
            this.top = top;
            this.cellWidth = cellWidth;
            this.cellHeight = cellHeight;
            this.cols = cols;
            this.rows = rows;
            this.anchored = anchored;
            this.busy = busy;
            this.guard = Math.max(0, Math.min(rows, guard));
        }

        /** The renderer's JSON, or null when it is malformed. */
        static Hint parse(String json) {
            try {
                JSONObject o = new JSONObject(json);
                JSONArray b = o.getJSONArray("busy");
                int rows = o.getInt("rows");
                int cols = o.getInt("cols");
                // Only the bottom rows are reported, from busyFrom down; the
                // rest count as taken (the cluster never goes that high).
                int from = o.optInt("busyFrom", 0);
                if (rows < 0 || rows > 1000 || from < 0 || from > rows || b.length() != rows - from) return null;
                int[] busy = new int[rows];
                java.util.Arrays.fill(busy, 0, from, cols);
                for (int i = from; i < rows; i++) busy[i] = b.getInt(i - from);
                float cellWidth = (float) o.getDouble("cellWidth");
                float cellHeight = (float) o.getDouble("cellHeight");
                if (!(cellWidth > 0) || !(cellHeight > 0)) return null;
                return new Hint((float) o.getDouble("left"), (float) o.getDouble("top"),
                        cellWidth, cellHeight, cols, rows, o.optBoolean("anchored"), busy,
                        o.optInt("guard", rows));
            } catch (JSONException malformed) {
                return null;
            }
        }
    }

    /** Where the cluster sits: its bottom on {@link #bottomRow}'s bottom, its right on the grid's right. */
    static final class Spot {
        final int topRow, bottomRow;
        /** Columns the cluster covers, counted from the right. */
        final int cols;
        /** No free spot: the cluster shrinks and goes translucent in the corner. */
        final boolean crowded;
        /** In the renderer's logical pixels. */
        final float right, bottom;

        Spot(int topRow, int bottomRow, int cols, boolean crowded, float right, float bottom) {
            this.topRow = topRow;
            this.bottomRow = bottomRow;
            this.cols = cols;
            this.crowded = crowded;
            this.right = right;
            this.bottom = bottom;
        }

        @Override public boolean equals(Object o) {
            if (!(o instanceof Spot)) return false;
            Spot s = (Spot) o;
            return s.topRow == topRow && s.bottomRow == bottomRow && s.cols == cols && s.crowded == crowded
                    && s.right == right && s.bottom == bottom;
        }

        @Override public int hashCode() { return bottomRow * 31 + cols; }
    }

    private ClusterPlacement() { }

    /**
     * The spot for a {@code width}×{@code height} cluster (logical pixels):
     * the previous one while it still fits, else the lowest that fits, else
     * the bottom corner, crowded.
     */
    static Spot place(Hint hint, float width, float height, Spot previous) {
        int cols = Math.min(hint.cols, (int) Math.ceil(width / hint.cellWidth));
        int tall = Math.max(1, (int) Math.ceil(height / hint.cellHeight));
        int last = hint.rows - 1;
        if (last < 0 || tall > hint.rows) return spot(hint, Math.max(0, last), 1, cols, true);
        if (previous != null && !previous.crowded && previous.cols == cols
                && previous.bottomRow <= last && fits(hint, previous.bottomRow, tall, cols)) {
            return spot(hint, previous.bottomRow, tall, cols, false);
        }
        int lowest = Math.max(tall - 1, last - SEARCH_ROWS);
        for (int bottom = last; bottom >= lowest; bottom--) {
            if (fits(hint, bottom, tall, cols)) return spot(hint, bottom, tall, cols, false);
        }
        return spot(hint, last, tall, cols, true);
    }

    /** Whether rows {@code bottom - tall + 1 .. bottom} are free in their last {@code cols} columns. */
    static boolean fits(Hint hint, int bottom, int tall, int cols) {
        int top = bottom - tall + 1;
        if (top < 0 || bottom >= hint.rows) return false;
        for (int row = top; row <= bottom; row++) {
            if (hint.busy[row] > hint.cols - cols) return false;
        }
        return true;
    }

    private static Spot spot(Hint hint, int bottom, int tall, int cols, boolean crowded) {
        return new Spot(Math.max(0, bottom - tall + 1), bottom, cols, crowded,
                hint.left + hint.cols * hint.cellWidth, hint.top + (bottom + 1) * hint.cellHeight);
    }
}
