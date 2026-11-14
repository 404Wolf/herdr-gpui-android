package dev.herdr.android;

import java.util.ArrayList;
import java.util.List;

/**
 * Touch targets for the controls the renderer draws as words in the program's
 * frame ("type ─ talk ─ send"): invisible rectangles over their cells, at least
 * {@link #MIN_DP} square, "talk" the widest because it is the main action.
 * They reach down to the bottom of the terminal and up only to the row above
 * the prompt's caret (the prompt's own rule), never into the program's text
 * above it, which stays free to scroll and select. Neighbours never overlap:
 * "talk" keeps its width and the others give way. While recording, "talk" is
 * the stop and takes the whole bottom-right block, from its words to the
 * grid's right edge and at least {@link #STOP_MIN_WIDTH_DP} ×
 * {@link #STOP_MIN_HEIGHT_DP}, reaching above the prompt if need be: the one
 * thing to tap then should be impossible to miss. Pure: no views.
 */
final class ControlTargets {
    static final int MIN_DP = 44;
    /** Extra width each side of a control's words, in dp; "talk" gets more. */
    static final int PAD_DP = 8, TALK_PAD_DP = 20;
    static final int TALK_MIN_DP = 88;
    /** The stop's least size while recording, in dp. */
    static final int STOP_MIN_WIDTH_DP = 120, STOP_MIN_HEIGHT_DP = 96;

    /** A control as the renderer drew it: cells {@code start..end} on {@code row}. */
    static final class Cells {
        final String id;
        final int row, start, end;

        Cells(String id, int row, int start, int end) {
            this.id = id;
            this.row = row;
            this.start = start;
            this.end = end;
        }
    }

    /** A target in the renderer's logical pixels (dp), relative to its surface. */
    static final class Rect {
        final String id;
        float left, top, right, bottom;

        Rect(String id, float left, float top, float right, float bottom) {
            this.id = id;
            this.left = left;
            this.top = top;
            this.right = right;
            this.bottom = bottom;
        }

        float width() { return right - left; }

        float height() { return bottom - top; }
    }

    private ControlTargets() { }

    /**
     * The targets for {@code controls} on a grid at ({@code left}, {@code top})
     * with cells {@code cellWidth}×{@code cellHeight}, the surface ending at
     * {@code surfaceBottom}; none reaches above row {@code topRow - 1} or past
     * {@code maxRight}, except "talk" while {@code recording}.
     */
    static List<Rect> layout(List<Cells> controls, float left, float top, float cellWidth, float cellHeight,
            int topRow, float surfaceBottom, float maxRight, boolean recording) {
        List<Rect> out = new ArrayList<>();
        float ceiling = top + Math.max(0, topRow - 1) * cellHeight;
        for (Cells c : controls) {
            boolean talk = "talk".equals(c.id);
            float pad = talk ? TALK_PAD_DP : PAD_DP;
            float minWidth = talk ? TALK_MIN_DP : MIN_DP;
            float l = left + c.start * cellWidth - pad, r = left + c.end * cellWidth + pad;
            if (r - l < minWidth) {
                float grow = (minWidth - (r - l)) / 2;
                l -= grow;
                r += grow;
            }
            float rowTop = top + c.row * cellHeight;
            float b = Math.max(surfaceBottom, rowTop + cellHeight);
            float t = Math.max(ceiling, Math.min(rowTop - cellHeight, b - MIN_DP));
            out.add(new Rect(c.id, Math.max(left, l), t, Math.min(maxRight, r), b));
        }
        // Neighbours on one row give way to "talk", or split the overlap evenly.
        out.sort((a, b) -> Float.compare(a.left, b.left));
        for (int i = 1; i < out.size(); i++) {
            Rect a = out.get(i - 1), b = out.get(i);
            if (a.right <= b.left) continue;
            if ("talk".equals(a.id)) b.left = a.right;
            else if ("talk".equals(b.id)) a.right = b.left;
            else {
                float middle = (a.right + b.left) / 2;
                a.right = middle;
                b.left = middle;
            }
        }
        if (recording) growStop(out, left, top, maxRight);
        return out;
    }

    /** "talk" takes the bottom-right block; what lies under it gives way, or goes. */
    private static void growStop(List<Rect> rects, float left, float top, float maxRight) {
        Rect stop = null;
        for (Rect r : rects) if ("talk".equals(r.id)) stop = r;
        if (stop == null) return;
        stop.right = maxRight;
        stop.left = Math.max(left, Math.min(stop.left, maxRight - STOP_MIN_WIDTH_DP));
        stop.top = Math.max(top, Math.min(stop.top, stop.bottom - STOP_MIN_HEIGHT_DP));
        java.util.Iterator<Rect> each = rects.iterator();
        while (each.hasNext()) {
            Rect r = each.next();
            if (r == stop) continue;
            r.right = Math.min(r.right, stop.left);
            if (r.width() <= 0) each.remove();
        }
    }
}
