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

    /** The talk slot's width in cells: its words ("talk", "● stop", "…") are padded to it. */
    static final int TALK_SLOT = 6;
    /** Ids of the reserved, untappable cells and the status words: never touch targets. */
    static final String GAP_TYPE = "gap-type", GAP_SEND = "gap-send", STATUS = "status";

    /** Whether {@code id} is a control a tap acts on (not a reserved slot or the status words). */
    static boolean tappable(String id) {
        return !id.startsWith("gap-") && !STATUS.equals(id);
    }

    /**
     * The controls in the program's frame, made to fit {@code cols} (Wolf's ask 4, 11-17: the
     * buttons never jump). The line ends in three fixed slots, right-anchored, the same cells in
     * every state: {@code type ─ talk ─ send ──}.
     * <ul>
     *   <li>"type" leaves its slot while the keyboard is up (#30: no Herdr hide), and "send"
     *       while recording (no tap meant for the stop sends); each slot then shows the rule,
     *       untappable, so nothing beside it moves.
     *   <li>"talk" is its shortest words, padded to {@link #TALK_SLOT}: "talk", "● stop" while
     *       recording (the stop's target takes the bottom-right corner, {@link ControlTargets}),
     *       "…" while connecting or transcribing.
     * </ul>
     * Everything that comes and goes lives left of the slots, right-aligned against them, and
     * never moves them: "✕" (something to clear), "undo", "N queued" / "≡N", a selected row's
     * actions, and the status words ("rec 0:05", "transcribing…", "offline", "mic silenced":
     * talk's fuller words). When the grid is narrow, the status words shorten and then go, then
     * the undo's and the queue's words shorten, then "undo" goes; "✕" and the queue control
     * stay (items 75, 76). Narrower than even that, the renderer keeps "✕", the queue and the
     * stop (gpui-rio ghost.rs, {@code narrow_line}).
     */
    static java.util.List<GhostText.Control> controls(State in, int cols) {
        java.util.List<java.util.List<GhostText.Control>> ladder = ladder(in);
        java.util.List<GhostText.Control> barest = null;
        for (java.util.List<GhostText.Control> line : ladder) {
            if (line.isEmpty()) continue;
            if (width(line) + MARGIN <= cols) return line;
            barest = line;
        }
        return barest == null ? java.util.Collections.emptyList() : barest;
    }

    /**
     * Every line {@link #controls} may pick, widest first, each ending in the same slots: the
     * renderer gets them all and picks the one that fits its grid in the same frame, so a fold
     * or a zoom never draws a line meant for the old width (ghost.rs).
     */
    static java.util.List<java.util.List<GhostText.Control>> ladder(State in) {
        java.util.List<GhostText.Control> slots = slots(in);
        java.util.List<GhostText.Control> statuses = new java.util.ArrayList<>();
        // talk's fuller words, fullest first: status words left of the slots.
        for (int i = 0; i + 1 < in.talks.size(); i++) {
            GhostText.Control t = in.talks.get(i);
            statuses.add(new GhostText.Control(STATUS, t.spans));
        }
        GhostText.Control clear = in.clearable ? new GhostText.Control("clear", "✕", "dim") : null;
        GhostText.Control undoWide = in.undoWide == null ? null : new GhostText.Control("undo", in.undoWide, "info");
        GhostText.Control undo = in.undoNarrow == null ? null : new GhostText.Control("undo", in.undoNarrow, "info");
        String mark = in.expanded ? " ▲" : "";
        GhostText.Control queueWide = in.queued <= 0 ? null
                : new GhostText.Control("queue", QueuedMessages.badge(in.queued) + " queued" + mark, in.queueStyle);
        GhostText.Control queue = in.queued <= 0 ? null
                : new GhostText.Control("queue", "≡" + QueuedMessages.badge(in.queued) + (in.expanded ? "▲" : ""),
                        in.queueStyle);
        GhostText.Control fullStatus = statuses.isEmpty() ? null : statuses.get(0);
        GhostText.Control shortStatus = statuses.isEmpty() ? null : statuses.get(statuses.size() - 1);
        java.util.List<java.util.List<GhostText.Control>> left = new java.util.ArrayList<>();
        if (in.rowActions != null && !in.rowActions.isEmpty()) {
            // A selected row's actions, with the queue control (it closes the list) kept.
            java.util.List<GhostText.Control> noBack = new java.util.ArrayList<>();
            for (GhostText.Control c : in.rowActions) if (!"row-back".equals(c.id)) noBack.add(c);
            left.add(join(in.rowActions, queueWide, shortStatus));
            left.add(join(in.rowActions, queue));
            left.add(join(noBack, queue));
        } else {
            left.add(line(clear, undoWide, queueWide, fullStatus));
            left.add(line(clear, undoWide, queueWide, shortStatus));
            left.add(line(clear, undo, queue, shortStatus));
            left.add(line(clear, undo, queue, null));
            left.add(line(clear, null, queue, null));
        }
        java.util.List<java.util.List<GhostText.Control>> out = new java.util.ArrayList<>();
        for (java.util.List<GhostText.Control> l : left) {
            java.util.List<GhostText.Control> full = new java.util.ArrayList<>(l);
            full.addAll(slots);
            if (out.isEmpty() || !ids(out.get(out.size() - 1)).equals(ids(full)) || width(out.get(out.size() - 1)) != width(full)) {
                out.add(full);
            }
        }
        return out;
    }

    /** The three fixed slots: "type" (or its reserved rule), "talk", "send" (or its rule). */
    static java.util.List<GhostText.Control> slots(State in) {
        java.util.List<GhostText.Control> out = new java.util.ArrayList<>();
        out.add(in.typing ? new GhostText.Control(GAP_TYPE, "────", "dim") : new GhostText.Control("type", "type", "dim"));
        if (!in.talks.isEmpty()) {
            GhostText.Control t = in.talks.get(in.talks.size() - 1);
            out.add(new GhostText.Control("talk", padded(t.spans, TALK_SLOT)));
        }
        out.add(in.recording ? new GhostText.Control(GAP_SEND, "────", "dim")
                : new GhostText.Control("send", "send", "bright"));
        return out;
    }

    /** {@code spans} ((text, style) pairs) centred in {@code cells} with spaces, cut if longer. */
    static String[] padded(String[] spans, int cells) {
        int n = 0;
        for (int i = 0; i + 1 < spans.length; i += 2) n += spans[i].codePointCount(0, spans[i].length());
        if (n >= cells) return spans;
        int before = (cells - n) / 2, after = cells - n - before;
        String[] out = spans.clone();
        out[0] = " ".repeat(before) + out[0];
        out[out.length - 2] = out[out.length - 2] + " ".repeat(after);
        return out;
    }

    private static String ids(java.util.List<GhostText.Control> line) {
        StringBuilder out = new StringBuilder();
        for (GhostText.Control c : line) out.append(c.id).append(' ');
        return out.toString();
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
