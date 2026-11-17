package dev.herdr.android;

import java.util.List;

/**
 * The controls drawn as words in the agent's prompt frame ({@code type ─ talk ─ ✕ ─ send ─
 * 2 queued}), made to fit the grid's width (items 75, 76). Pure: no views; {@link ComposerBar}
 * gives it the state.
 */
final class ControlLine {
    /**
     * Cells the renderer wants free beside the controls' line at the prompt, or it draws only
     * "talk", cut to fit (gpui-rio ghost.rs, {@code place_all}).
     */
    static final int MARGIN = 8;

    private ControlLine() { }

    /** What the controls' line shows: its state, for {@link #controls(State, int)}. */
    static class State {
        boolean typing;
        /** "talk"'s words from the fullest to the shortest; none without dictation. */
        List<GhostText.Control> talks = java.util.Collections.emptyList();
        boolean recording;
        /** Something to clear: a draft, a dictation in flight, or a queue ("✕"). */
        boolean clearable;
        /** The undo control's words, wide and narrow; null while nothing can be undone. */
        String undoWide, undoNarrow;
        /** Waiting to reach the pane (the queue control's count) and its colour. */
        int queued;
        String queueStyle = "info";
        boolean expanded;
        /** A queued row's actions: while set, they are the whole line. */
        List<GhostText.Control> rowActions;
    }

    /**
     * The controls in the program's frame, made to fit {@code cols}:
     * {@code type ─ talk ─ ✕ ─ undo ─ send ─ N queued}, or while recording
     * {@code type ─ ✕ ─ undo ─ N queued ─ ● stop} (the stop stays last, so it keeps the
     * bottom-right corner, and "send" is not drawn: no tap meant for the stop sends). "type"
     * is left out while the keyboard is up, and Herdr draws no hide of its own (the keyboard's
     * hide key, Back or a swipe down put it away; #30); "talk" is left out with no dictation;
     * "✕" shows when there is something to clear and "N queued" when something waits. After a
     * clear, "undo" follows "✕", or stands in its place when nothing is left to clear. When the
     * full line is too wide (the Fold's cover screen), "talk", "undo" and the queue take their
     * short words ("● 0:05 stop", "undo", "≡2"), then "type" goes, then "talk" its shortest
     * words, then "send", then "undo". "✕", the queue control and "talk" (the stop)
     * are never left out (items 75, 76): when even the barest line is too wide for the grid, the
     * renderer keeps exactly those three and cuts "talk" to fit (gpui-rio ghost.rs, {@code
     * place_all}). A selected row's actions take the line's place, with the queue control and
     * "talk" (the stop) kept.
     */
    static java.util.List<GhostText.Control> controls(State in, int cols) {
        GhostText.Control lead = in.typing ? null : new GhostText.Control("type", "type", "dim");
        GhostText.Control talk = in.talks.isEmpty() ? null : in.talks.get(0);
        GhostText.Control shorter = in.talks.isEmpty() ? null : in.talks.get(Math.min(1, in.talks.size() - 1));
        GhostText.Control shortest = in.talks.isEmpty() ? null : in.talks.get(in.talks.size() - 1);
        GhostText.Control clear = in.clearable ? new GhostText.Control("clear", "✕", "dim") : null;
        GhostText.Control undoWide = in.undoWide == null ? null : new GhostText.Control("undo", in.undoWide, "info");
        GhostText.Control undo = in.undoNarrow == null ? null : new GhostText.Control("undo", in.undoNarrow, "info");
        String mark = in.expanded ? " ▲" : "";
        GhostText.Control queueWide = in.queued <= 0 ? null
                : new GhostText.Control("queue", QueuedMessages.badge(in.queued) + " queued" + mark, in.queueStyle);
        GhostText.Control queue = in.queued <= 0 ? null
                : new GhostText.Control("queue", "≡" + QueuedMessages.badge(in.queued) + (in.expanded ? "▲" : ""),
                        in.queueStyle);
        GhostText.Control send = in.recording ? null : new GhostText.Control("send", "send", "bright");
        java.util.List<java.util.List<GhostText.Control>> ladder;
        if (in.rowActions != null && !in.rowActions.isEmpty()) {
            // A selected row's actions, with the queue control (it closes the list) and talk (the
            // stop while recording, still last, still the bottom-right corner) kept.
            java.util.List<GhostText.Control> acts = in.rowActions;
            java.util.List<GhostText.Control> noBack = new java.util.ArrayList<>();
            for (GhostText.Control c : acts) if (!"row-back".equals(c.id)) noBack.add(c);
            ladder = java.util.Arrays.asList(
                    join(acts, queueWide, talk),
                    join(acts, queue, shorter),
                    join(noBack, queue, shorter),
                    join(noBack, queue, shortest));
        } else if (in.recording) {
            ladder = java.util.Arrays.asList(
                    line(lead, clear, undoWide, queueWide, talk),
                    line(lead, clear, undoWide, queueWide, shorter),
                    line(lead, clear, undo, queue, shorter),
                    line(null, clear, undo, queue, shorter),
                    line(null, clear, undo, queue, shortest),
                    line(null, clear, null, queue, shortest));
        } else {
            ladder = java.util.Arrays.asList(
                    line(lead, talk, clear, undoWide, send, queueWide),
                    line(lead, shorter, clear, undoWide, send, queueWide),
                    line(lead, shorter, clear, undo, send, queue),
                    line(null, shorter, clear, undo, send, queue),
                    line(null, shortest, clear, undo, send, queue),
                    line(null, shortest, clear, undo, null, queue),
                    line(null, shortest, clear, null, null, queue));
        }
        java.util.List<GhostText.Control> barest = null;
        for (java.util.List<GhostText.Control> line : ladder) {
            if (line.isEmpty()) continue;
            if (width(line) + MARGIN <= cols) return line;
            barest = line;
        }
        return barest == null ? java.util.Collections.emptyList() : barest;
    }

    /** The queue control's colour: the most pressing of what waits. */
    static String queueStyle(List<Outbox.Message> queued, int clips, int linkDown) {
        switch (QueuedMessages.urgency(queued)) {
            case GONE: return "danger";
            case WAITING: return "warn";
            default: return clips > 0 || linkDown > 0 ? "warn" : "info";
        }
    }

    /** A queued row's actions, the whole line while its row is selected. */
    static java.util.List<GhostText.Control> rowActions(QueueView.Row row) {
        java.util.List<GhostText.Control> out = new java.util.ArrayList<>();
        if (row.kind == QueueView.Kind.MESSAGE && row.messageState == Outbox.State.UNSURE) {
            out.add(new GhostText.Control("row-again", "send again", "warn"));
        }
        if (row.kind == QueueView.Kind.PARKED) out.add(new GhostText.Control("row-retry", "retry", "warn"));
        if (row.kind == QueueView.Kind.MESSAGE) out.add(new GhostText.Control("row-edit", "edit", "bright"));
        out.add(new GhostText.Control("row-drop", "drop", "danger"));
        out.add(new GhostText.Control("row-back", "back", "dim"));
        return out;
    }

    private static java.util.List<GhostText.Control> join(java.util.List<GhostText.Control> first,
            GhostText.Control... rest) {
        java.util.List<GhostText.Control> out = new java.util.ArrayList<>(first);
        for (GhostText.Control control : rest) if (control != null) out.add(control);
        return out;
    }

    private static java.util.List<GhostText.Control> line(GhostText.Control... controls) {
        java.util.List<GhostText.Control> out = new java.util.ArrayList<>();
        for (GhostText.Control control : controls) if (control != null) out.add(control);
        return out;
    }

    /** Cells the renderer's line takes for {@code controls}: " a ─ b ─ c ──". */
    static int width(List<GhostText.Control> controls) {
        if (controls.isEmpty()) return 0;
        int width = 1 + 3 * (controls.size() - 1) + 3;
        for (GhostText.Control control : controls) {
            for (int i = 0; i + 1 < control.spans.length; i += 2) {
                width += control.spans[i].codePointCount(0, control.spans[i].length());
            }
        }
        return width;
    }
}
