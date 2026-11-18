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
        /** The queue's rows as the renderer drew them above the prompt ("echo-<row id>"), oldest first. */
        java.util.List<ControlTargets.Cells> echoes = java.util.Collections.emptyList();
        /** The first row touch targets may cover (the prompt's caret row). */
        int targetsTop;
        /** Whether the renderer drew the draft; when not, the composer shows it. */
        boolean draftShown;
        /**
         * The draft's drawn runs: {row, col, cells, first drawn character, characters, cluster}
         * ({@link DraftTaps}).
         */
        int[][] draftRuns = new int[0][];
        /**
         * Every row's drawn draft cells, {row, first, past last}, one per row: no control target
         * covers any of them ({@link ControlTargets}).
         */
        int[][] draftSpans = new int[0][];

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

        /** The most draft runs a hint carries: ghost.rs {@code MAX_DRAFT_RUNS}, the same bound. */
        static final int MAX_DRAFT_RUNS = 4096;

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
                    // As many as the renderer draws (ControlLine.MAX_CONTROLS): every drawn control
                        // gets its target and accessibility node.
                    for (int i = 0; i < drawn.length() && i < ControlLine.MAX_CONTROLS; i++) {
                        JSONObject c = drawn.getJSONObject(i);
                        int row = c.getInt("row"), start = c.getInt("start"), end = c.getInt("end");
                        if (row < 0 || row >= rows || start < 0 || end < start || end > cols) return null;
                        controls.add(new ControlTargets.Cells(c.getString("id"), row, start, end));
                    }
                    hint.controls = controls;
                }
                JSONArray rowsDrawn = o.optJSONArray("echoes");
                if (rowsDrawn != null) {
                    java.util.List<ControlTargets.Cells> echoes = new java.util.ArrayList<>();
                    for (int i = 0; i < rowsDrawn.length() && i < 8; i++) {
                        JSONObject c = rowsDrawn.getJSONObject(i);
                        int row = c.getInt("row"), start = c.getInt("start"), end = c.getInt("end");
                        if (row < 0 || row >= rows || start < 0 || end < start || end > cols) return null;
                        echoes.add(new ControlTargets.Cells(c.getString("id"), row, start, end));
                    }
                    hint.echoes = echoes;
                }
                hint.targetsTop = Math.max(0, Math.min(rows, o.optInt("targetsTop", rows)));
                hint.draftShown = o.optBoolean("draftShown");
                JSONArray runs = o.optJSONArray("draft");
                // Rows past the shared bound (never sent by this renderer) are kept whole from
                // the controls: nothing drawn is ever open to them.
                java.util.TreeMap<Integer, int[]> rowsOf = new java.util.TreeMap<>();
                if (runs != null) {
                    int n = Math.min(runs.length(), MAX_DRAFT_RUNS);
                    hint.draftRuns = new int[n][];
                    for (int i = 0; i < runs.length(); i++) {
                        JSONArray r = runs.getJSONArray(i);
                        if (r.length() < 4 || r.length() > 6) return null;
                        int row = r.getInt(0), col = r.getInt(1), len = r.getInt(2);
                        if (row < 0 || row >= rows || col < 0 || len < 0) return null;
                        int[] span = rowsOf.get(row);
                        if (i >= n) {
                            rowsOf.put(row, new int[] {row, 0, cols});
                            continue;
                        }
                        // [row, col, cells, first character, characters shown, 1 for a cluster]
                        // (the last two absent from an older renderer: one cell per character).
                        int chars = r.optInt(4, len);
                        hint.draftRuns[i] = new int[] {row, col, len, r.getInt(3), chars,
                                r.optInt(5, chars != len ? 1 : 0)};
                        if (span == null) rowsOf.put(row, new int[] {row, col, col + len});
                        else {
                            span[1] = Math.min(span[1], col);
                            span[2] = Math.max(span[2], col + len);
                        }
                    }
                }
                JSONArray spans = o.optJSONArray("draftSpans");
                if (spans != null) {
                    // One per row: bounded by the grid's rows, never cut.
                    if (spans.length() > rows) return null;
                    for (int i = 0; i < spans.length(); i++) {
                        JSONArray s = spans.getJSONArray(i);
                        if (s.length() != 3) return null;
                        int row = s.getInt(0), first = s.getInt(1), to = s.getInt(2);
                        if (row < 0 || row >= rows || first < 0 || to < first) return null;
                        int[] known = rowsOf.get(row);
                        if (known == null) rowsOf.put(row, new int[] {row, first, to});
                        else {
                            known[1] = Math.min(known[1], first);
                            known[2] = Math.max(known[2], to);
                        }
                    }
                }
                hint.draftSpans = rowsOf.values().toArray(new int[0][]);
                return hint;
            } catch (JSONException malformed) {
                return null;
            }
        }
    }

    private ClusterPlacement() { }
}
