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
        /** The pane can take an image: "img" leads the line while the full line leaves it room. */
        boolean attach;
        /** A dictation is on (connecting, recording, transcribing): "cancel" shows, and "send" stays. */
        boolean dictating;
    }

    /** The attach control's id: it opens the image sources (library, camera, clipboard). */
    static final String ATTACH = "attach";
    /** While a dictation is on: stop and discard it, keeping the text that was there before. */
    static final String CANCEL = "cancel";

    /** The talk slot's width in cells: its words ("talk", "stop", "…") are padded to it. */
    static final int TALK_SLOT = 4;
    /** The most alternatives the native bridge takes in one update (ghost_bridge.rs MAX_RUNGS). */
    static final int MAX_RUNGS = 8;
    /** The most controls a line has (the renderer's limit, ghost_bridge MAX_CONTROLS). */
    static final int MAX_CONTROLS = 10;
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
     *   <li>"talk" is its shortest words, padded to {@link #TALK_SLOT}: "talk", "stop" while
     *       recording (the stop's target takes the bottom-right corner, {@link ControlTargets}),
     *       "…" while connecting or transcribing.
     * </ul>
     * Everything that comes and goes lives left of the slots, right-aligned against them, and
     * never moves them: "✕" (something to clear), "undo", "N queued" / "≡N", and the status words ("rec 0:05", "transcribing…", "offline", "mic silenced":
     * talk's fuller words). When the grid is narrow, the status words shorten and then go, then
     * the undo's and the queue's words shorten, then "undo" goes; "✕" and the queue control
     * stay (items 75, 76). A selected queue row's actions are never here: the renderer draws
     * them on that row ({@link #rowRungs}). On a grid narrower than the barest line, the
     * renderer still draws that line without the draft's margin; only narrower than the line
     * itself (32 cells with "✕" and "≡3▲"; {@link #width}) does it keep just "✕", the queue and
     * talk (gpui-rio ghost.rs, {@code narrow_line}).
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
        return trimmed(fullLadder(in));
    }

    /**
     * At most {@link #MAX_RUNGS} lines, widest and barest kept: the native bridge refuses a whole
     * update with more, which would drop Cancel and freeze the controls. The line whose neighbours
     * are closest in width is the first to go.
     */
    static java.util.List<java.util.List<GhostText.Control>> trimmed(java.util.List<java.util.List<GhostText.Control>> rungs) {
        java.util.List<java.util.List<GhostText.Control>> out = new java.util.ArrayList<>(rungs);
        while (out.size() > MAX_RUNGS) {
            int drop = 1, best = Integer.MAX_VALUE;
            for (int i = 1; i + 1 < out.size(); i++) {
                int gap = width(out.get(i - 1)) - width(out.get(i + 1));
                if (gap < best) {
                    best = gap;
                    drop = i;
                }
            }
            out.remove(drop);
        }
        return out;
    }

    private static java.util.List<java.util.List<GhostText.Control>> fullLadder(State in) {
        java.util.List<GhostText.Control> slots = slots(in);
        java.util.List<GhostText.Control> statuses = new java.util.ArrayList<>();
        // talk's fuller words, fullest first: status words left of the slots.
        for (int i = 0; i + 1 < in.talks.size(); i++) {
            GhostText.Control t = in.talks.get(i);
            statuses.add(new GhostText.Control(STATUS, t.spans));
        }
        GhostText.Control clear = in.clearable ? new GhostText.Control("clear", "✕", "dim") : null;
        // The wide lines say "clear" in words (a lone dim cross was hard to find); the narrow ones
        // keep the cross. While a dictation is on, "cancel" is the clearer word beside it, so the
        // cross stays a cross there.
        GhostText.Control clearWord = in.clearable ? new GhostText.Control("clear", "clear", "warn") : null;
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
        // While a dictation is on, "cancel" leads every line: it stops and discards what was
        // dictated, keeping the text that was there before. "send" (in the slots) stops and sends.
        // Priority, highest first: hide, cancel, stop, send, the timer; then img; then the cross,
        // undo and the queue, which give way on narrow grids (they come back with the idle line).
        boolean dictation = in.recording || in.dictating;
        GhostText.Control cancel = dictation ? new GhostText.Control(CANCEL, "cancel", "warn") : null;
        GhostText.Control img = in.attach ? new GhostText.Control(ATTACH, "img", "dim") : null;
        java.util.List<java.util.List<GhostText.Control>> out = new java.util.ArrayList<>();
        if (dictation) {
            // Every combination, widest first: the renderer takes the widest that fits, so what
            // the grid can hold of the lower priorities (img, the cross, undo, the queue) is kept.
            java.util.List<java.util.List<GhostText.Control>> all = new java.util.ArrayList<>();
            GhostText.Control[] leads = img == null ? new GhostText.Control[] {null} : new GhostText.Control[] {img, null};
            for (GhostText.Control lead : leads) {
                add(all, slots, lead, cancel, clear, undoWide, queueWide, fullStatus);
                add(all, slots, lead, cancel, clear, undoWide, queueWide, shortStatus);
                add(all, slots, lead, cancel, clear, undo, queue, shortStatus);
                add(all, slots, lead, cancel, clear, shortStatus);
                add(all, slots, lead, cancel, shortStatus);
                add(all, slots, lead, cancel, clear);
                add(all, slots, lead, cancel);
            }
            all.sort((x, y) -> Integer.compare(width(y), width(x)));
            for (java.util.List<GhostText.Control> line : all) {
                if (out.isEmpty() || !ids(out.get(out.size() - 1)).equals(ids(line))
                        || width(out.get(out.size() - 1)) != width(line)) {
                    out.add(line);
                }
            }
            return out;
        }
        // Idle: where img shows it comes first and keeps the cross (the word needs room img would
        // lose to); without img the wide lines say "clear".
        if (in.attach) {
            add(out, slots, img, clearWord, undoWide, queueWide, fullStatus);
            add(out, slots, img, clear, undoWide, queueWide, fullStatus);
        } else {
            add(out, slots, null, clearWord, undoWide, queueWide, fullStatus);
        }
        add(out, slots, null, in.attach ? clear : clearWord, undoWide, queueWide, shortStatus);
        add(out, slots, null, clear, undo, queue, shortStatus);
        add(out, slots, null, clear, undo, queue, null);
        add(out, slots, null, clear, null, queue, null);
        return out;
    }

    /** Appends {@code lead + words + slots} unless it repeats the last line (same ids and width). */
    private static void add(java.util.List<java.util.List<GhostText.Control>> out,
            java.util.List<GhostText.Control> slots, GhostText.Control lead, GhostText.Control... words) {
        java.util.List<GhostText.Control> full = new java.util.ArrayList<>();
        if (lead != null) full.add(lead);
        for (GhostText.Control w : words) if (w != null) full.add(w);
        full.addAll(slots);
        if (out.isEmpty() || !ids(out.get(out.size() - 1)).equals(ids(full)) || width(out.get(out.size() - 1)) != width(full)) {
            out.add(full);
        }
    }

    /** The three fixed slots: "type" (or its reserved rule), "talk", "send" (or its rule). */
    static java.util.List<GhostText.Control> slots(State in) {
        java.util.List<GhostText.Control> out = new java.util.ArrayList<>();
        // The keyboard up: the same slot, the same width, says "hide" and puts it away (Back and a
        // downward swipe do too). It was an untappable rule here until asks 9 and 10.
        out.add(in.typing ? new GhostText.Control("type", "hide", "dim") : new GhostText.Control("type", "type", "dim"));
        if (!in.talks.isEmpty()) {
            GhostText.Control t = in.talks.get(in.talks.size() - 1);
            out.add(new GhostText.Control("talk", padded(t.spans, TALK_SLOT)));
        }
        // Always there, recording or not: while a dictation is on it stops it, waits for the final
        // transcript and sends the draft (ComposerBar.enter).
        out.add(new GhostText.Control("send", "send", "bright"));
        return out;
    }

    /** {@code spans} ((text, style) pairs) centred in {@code cells} with spaces, cut if longer. */
    static String[] padded(String[] spans, int cells) {
        int n = 0;
        for (int i = 0; i + 1 < spans.length; i += 2) n += spans[i].codePointCount(0, spans[i].length());
        if (n >= cells) return spans;
        int before = (cells - n) / 2, after = cells - n - before;
        String[] out = spans.clone();
        out[0] = spaces(before) + out[0];
        out[out.length - 2] = out[out.length - 2] + spaces(after);
        return out;
    }

    /** {@code n} spaces (String.repeat needs API 33; the app runs from 29). */
    static String spaces(int n) {
        char[] out = new char[Math.max(0, n)];
        java.util.Arrays.fill(out, ' ');
        return new String(out);
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

    /** A queued row's actions, drawn on the row while it is selected ({@link #rowRungs}). */
    static java.util.List<GhostText.Control> rowActions(QueueView.Row row) {
        java.util.List<GhostText.Control> out = new java.util.ArrayList<>();
        if (row.kind == QueueView.Kind.MESSAGE && row.messageState == Outbox.State.NEEDS_YOU) {
            out.add(new GhostText.Control("row-again", "send again", "warn"));
        }
        if (row.kind == QueueView.Kind.BLOCKED) {
            // factory-9zz.16: a lane held by a dictation; each way out is the user's, none silent.
            out.add(new GhostText.Control("row-retry", "retry", "warn"));
            if (row.proposal != null) {
                out.add(new GhostText.Control("row-use", "use phone's text", "bright"));
            } else if (row.onPhone) {
                out.add(new GhostText.Control("row-here", "on phone", "bright"));
            }
            out.add(new GhostText.Control("row-aside", "move aside", "warn"));
            out.add(new GhostText.Control("row-back", "keep waiting", "dim"));
            return out;
        }
        if (row.kind == QueueView.Kind.PARKED) out.add(new GhostText.Control("row-retry", "retry", "warn"));
        if (row.proposal != null) {
            out.add(new GhostText.Control("row-use", "use it", "bright"));
            out.add(new GhostText.Control("row-wait", "keep waiting", "dim"));
        } else if (row.onPhone) {
            out.add(new GhostText.Control("row-here", "on phone", "bright"));
        }
        if (row.kind == QueueView.Kind.MESSAGE) out.add(new GhostText.Control("row-edit", "edit", "bright"));
        out.add(new GhostText.Control("row-drop", "drop", "danger"));
        out.add(new GhostText.Control("row-back", "back", "dim"));
        return out;
    }

    /**
     * A selected row's actions as the renderer may draw them on the row's right end, widest
     * first (Wolf's ask 4, review of #35): all of them; without "back" (a tap on the row closes
     * it too); for an unsure message, "send again" as "again". The renderer draws the first that
     * leaves the row some of its words, so every row kind keeps every action on the cover
     * screen, and the controls' line never makes room for them.
     */
    static java.util.List<java.util.List<GhostText.Control>> rowRungs(QueueView.Row row) {
        java.util.List<GhostText.Control> all = rowActions(row);
        java.util.List<GhostText.Control> noBack = new java.util.ArrayList<>();
        java.util.List<GhostText.Control> shortest = new java.util.ArrayList<>();
        for (GhostText.Control c : all) {
            if ("row-back".equals(c.id)) continue;
            noBack.add(c);
            shortest.add("row-again".equals(c.id) ? new GhostText.Control(c.id, "again", "warn") : c);
        }
        java.util.List<java.util.List<GhostText.Control>> out = new java.util.ArrayList<>();
        out.add(all);
        out.add(noBack);
        if (width(shortest) < width(noBack)) out.add(shortest);
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
