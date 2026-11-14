package dev.herdr.android;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * The renderer's layout hint for the controls over the terminal: the grid's
 * geometry, how many columns of each bottom row are busy ({@link Hint#busy}),
 * and where it drew the controls ("type ─ talk ─ send"). Pure: no views.
 */
final class ClusterPlacement {
    /** The renderer's report (ghost_bridge.rs), in logical pixels. */
    static final class Hint {
        final float left, top, cellWidth, cellHeight;
        final int cols, rows;
        final boolean anchored;
        final int[] busy;
        /** The first row of the bottom block that must stay in view (the prompt, a dialog). */
        final int guard;
        /** The controls as the renderer drew them; empty when it had no room. */
        java.util.List<ControlTargets.Cells> controls = java.util.Collections.emptyList();
        /** The first row touch targets may cover (the prompt's caret row). */
        int targetsTop;
        /** Whether the renderer drew the draft; when not, the composer shows it. */
        boolean draftShown;

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
                // rest count as taken.
                int from = o.optInt("busyFrom", 0);
                if (rows < 0 || rows > 1000 || from < 0 || from > rows || b.length() != rows - from) return null;
                int[] busy = new int[rows];
                java.util.Arrays.fill(busy, 0, from, cols);
                for (int i = from; i < rows; i++) busy[i] = b.getInt(i - from);
                float cellWidth = (float) o.getDouble("cellWidth");
                float cellHeight = (float) o.getDouble("cellHeight");
                if (!(cellWidth > 0) || !(cellHeight > 0)) return null;
                Hint hint = new Hint((float) o.getDouble("left"), (float) o.getDouble("top"),
                        cellWidth, cellHeight, cols, rows, o.optBoolean("anchored"), busy,
                        o.optInt("guard", rows));
                JSONArray drawn = o.optJSONArray("controls");
                if (drawn != null) {
                    java.util.List<ControlTargets.Cells> controls = new java.util.ArrayList<>();
                    for (int i = 0; i < drawn.length() && i < 8; i++) {
                        JSONObject c = drawn.getJSONObject(i);
                        int row = c.getInt("row"), start = c.getInt("start"), end = c.getInt("end");
                        if (row < 0 || row >= rows || start < 0 || end < start || end > cols) return null;
                        controls.add(new ControlTargets.Cells(c.getString("id"), row, start, end));
                    }
                    hint.controls = controls;
                }
                hint.targetsTop = Math.max(0, Math.min(rows, o.optInt("targetsTop", rows)));
                hint.draftShown = o.optBoolean("draftShown");
                return hint;
            } catch (JSONException malformed) {
                return null;
            }
        }
    }

    private ClusterPlacement() { }
}
