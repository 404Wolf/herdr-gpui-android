package dev.herdr.android;

import java.util.ArrayList;
import java.util.List;

/**
 * Touch targets for the controls the renderer draws as words in the program's
 * frame ("type ─ talk ─ send"): invisible rectangles over their cells, at least
 * {@link #MIN_DP} square where there is room, "talk" the widest because it is
 * the main action.
 * They reach down to the bottom of the terminal and up only to the row above
 * the prompt's caret (the prompt's own rule), never into the program's text
 * above it, which stays free to scroll and select. Neighbours never overlap:
 * "talk" takes the gaps it wants and the others give way, but no target ever
 * covers another control's drawn words. While recording, "talk" is the stop
 * and takes the bottom-right block, from its words to the grid's right edge
 * and at least {@link #STOP_MIN_WIDTH_DP} × {@link #STOP_MIN_HEIGHT_DP} where
 * that space is free, reaching above the prompt if need be. Pure: no views.
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
        /** Where the control's words are drawn: its target always covers them. */
        final float labelLeft, labelRight;

        Rect(String id, float left, float top, float right, float bottom, float labelLeft, float labelRight) {
            this.id = id;
            this.left = left;
            this.top = top;
            this.right = right;
            this.bottom = bottom;
            this.labelLeft = labelLeft;
            this.labelRight = labelRight;
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
     *
     * <p>Every target covers its own drawn words and never another control's:
     * padding, the least sizes and the stop's growth only take free space,
     * between one control's words and the next. Where two want the same gap,
     * "talk" wins it and the others split it evenly.
     */
    static List<Rect> layout(List<Cells> controls, float left, float top, float cellWidth, float cellHeight,
            int topRow, float surfaceBottom, float maxRight, boolean recording) {
        return layout(controls, left, top, cellWidth, cellHeight, topRow, surfaceBottom, maxRight, recording, null);
    }

    /**
     * As above, never over the draft's own cells ({@code draftSpans}: {row, first, past last},
     * one per row, as the renderer drew them at Claude's prompt or its own): a tap on the
     * draft's words puts the caret there, never "send" or "✕" (Wolf's ask 1). Every target
     * gives up every draft cell, whichever way it lies: one above its row stops it below that
     * row, one below stops it above, one beside it on its own row stops it at the draft's end.
     */
    static List<Rect> layout(List<Cells> controls, float left, float top, float cellWidth, float cellHeight,
            int topRow, float surfaceBottom, float maxRight, boolean recording, int[][] draftSpans) {
        List<Rect> out = new ArrayList<>();
        float ceiling = top + Math.max(0, topRow - 1) * cellHeight;
        for (Cells c : controls) {
            // The status words take no taps. A reserved slot ("gap-type", "gap-send") keeps its
            // place in the geometry exactly as its button would, so no neighbour's target changes
            // when it is empty; its own target takes taps and does nothing (ComposerBar).
            if (ControlLine.STATUS.equals(c.id)) continue;
            // While recording the stop owns the corner, the reserved send slot included.
            if (recording && ControlLine.GAP_SEND.equals(c.id)) continue;
            boolean talk = "talk".equals(c.id);
            float pad = talk ? TALK_PAD_DP : PAD_DP;
            float minWidth = talk ? TALK_MIN_DP : MIN_DP;
            float labelLeft = left + c.start * cellWidth, labelRight = left + c.end * cellWidth;
            float l = labelLeft - pad, r = labelRight + pad;
            if (r - l < minWidth) {
                float grow = (minWidth - (r - l)) / 2;
                l -= grow;
                r += grow;
            }
            float rowTop = top + c.row * cellHeight;
            float b = Math.max(surfaceBottom, rowTop + cellHeight);
            float t = Math.max(ceiling, Math.min(rowTop - cellHeight, b - MIN_DP));
            if (talk && recording) {
                // The stop wants the whole bottom-right block; its neighbours'
                // words still bound it below.
                r = maxRight;
                l = Math.min(l, maxRight - STOP_MIN_WIDTH_DP);
                t = Math.max(top, Math.min(t, b - STOP_MIN_HEIGHT_DP));
            }
            if (draftSpans != null) {
                // Beside it first (its own row), then above and below.
                for (int pass = 0; pass < 2; pass++) {
                    for (int[] span : draftSpans) {
                        if (span == null || span.length < 3 || span[2] <= span[1]) continue;
                        if ((span[0] == c.row) != (pass == 0)) continue;
                        float spanLeft = left + span[1] * cellWidth, spanRight = left + span[2] * cellWidth;
                        float spanTop = top + span[0] * cellHeight, spanBottom = spanTop + cellHeight;
                        if (spanRight <= l || spanLeft >= r || spanBottom <= t || spanTop >= b) continue;
                        if (span[0] < c.row) {
                            t = spanBottom;
                        } else if (span[0] > c.row) {
                            b = spanTop;
                        } else if (spanRight <= labelLeft) {
                            l = spanRight;
                        } else if (spanLeft >= labelRight) {
                            r = spanLeft;
                        }
                    }
                }
            }
            out.add(new Rect(c.id, Math.max(left, l), t, Math.min(maxRight, r), b, labelLeft, labelRight));
        }
        out.sort((a, b) -> Float.compare(a.labelLeft, b.labelLeft));
        for (int i = 1; i < out.size(); i++) {
            Rect a = out.get(i - 1), b = out.get(i);
            if (a.right <= b.left) continue;
            // The boundary stays in the gap between their words. The slots (type, talk, send
            // and their reserved forms) keep their own bounds whatever comes and goes on their
            // left (Wolf's ask 4): a left-zone control gives way to a slot.
            float wanted;
            if ("talk".equals(a.id)) wanted = a.right;
            else if ("talk".equals(b.id)) wanted = b.left;
            else if (slot(b.id) && !slot(a.id)) wanted = b.left;
            else wanted = (a.right + b.left) / 2;
            float boundary = Math.max(a.labelRight, Math.min(b.labelLeft, wanted));
            a.right = boundary;
            b.left = boundary;
        }
        return out;
    }

    /** One of the line's fixed slots: type, talk, send, or a reserved one. */
    static boolean slot(String id) {
        return "type".equals(id) || "talk".equals(id) || "send".equals(id) || id.startsWith("gap-");
    }

    /**
     * Targets for the queue's rows drawn above the prompt ("echo-<row id>", while the queue is open): each
     * the full width of the grid and one row high, so a row is tapped anywhere along it. Rows do
     * not overlap the controls' targets, which reach up only to the prompt's rule.
     */
    static List<Rect> rows(List<Cells> rows, float left, float top, float cellWidth, float cellHeight, int cols) {
        List<Rect> out = new ArrayList<>();
        for (Cells c : rows) {
            float t = top + c.row * cellHeight;
            out.add(new Rect(c.id, left, t, left + cols * cellWidth, t + cellHeight,
                    left + c.start * cellWidth, left + c.end * cellWidth));
        }
        return out;
    }
}
