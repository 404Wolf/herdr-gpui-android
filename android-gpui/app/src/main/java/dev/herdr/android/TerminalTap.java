package dev.herdr.android;

/**
 * What a tap on the terminal does on the phone. The view that handles the
 * finger's lift decides what the tap was and did, and reports every one
 * ({@code gpuiTerminalTapped}): sent to the program as a mouse click (the
 * program asked for mouse events, as Claude Code's fullscreen view does), a
 * tap no program took (the pane's own view reports its taps this way too),
 * or no tap at all (it travelled, or was held). Only a tap no program took
 * opens typing, so one tap never both clicks and opens the keyboard; the
 * drawn "type" control still opens it. This side never decides a tap itself.
 *
 * <p>Each touch-down carries the stamp of the pane shown then
 * ({@link #stamp}), through the renderer's queue with the touch itself, and
 * its report brings it back with an id that only increases. A report for a
 * pane left since, or one seen before, is dropped ({@link #accept}).
 */
final class TerminalTap {
    /** What the renderer did with a press, as {@code gpuiTerminalTapped} codes it. */
    enum Outcome {
        /** Sent to the program as a mouse click. */
        CLICKED,
        /** A tap the program did not take. */
        PLAIN,
        /** No tap: it travelled, or the finger was held. */
        IGNORED;

        static Outcome of(int code) {
            switch (code) {
                case 0: return CLICKED;
                case 1: return PLAIN;
                default: return IGNORED;
            }
        }
    }

    enum Action {
        /** Nothing opens. */
        NOTHING,
        /** An agent's pane: the composer's draft takes the typing. */
        TYPE_INTO_DRAFT,
        /** Any other pane: the terminal's own keyboard. */
        SHOW_KEYBOARD
    }

    static Action after(Outcome outcome, boolean agentPane) {
        if (outcome != Outcome.PLAIN) return Action.NOTHING;
        return agentPane ? Action.TYPE_INTO_DRAFT : Action.SHOW_KEYBOARD;
    }

    private String shown;
    private long stamp;
    private long lastReport;

    /** The stamp a touch-down carries now: the pane shown. */
    long stamp() {
        return stamp;
    }

    /**
     * The pane shown now. On a change it returns the new stamp, carried by
     * touches from now on; otherwise -1.
     */
    long show(String paneKey) {
        if (paneKey.equals(shown)) return -1;
        shown = paneKey;
        return ++stamp;
    }

    /** Whether the pane a tap began on ({@code tapStamp}) is still the one shown. */
    boolean stillShown(long tapStamp) {
        return tapStamp == stamp;
    }

    /**
     * A report from the renderer: whether to act on it. Only once per
     * report, and only for a tap that began on the pane shown now.
     */
    boolean accept(long reportStamp, long reportId) {
        if (reportStamp != stamp || reportId <= lastReport) return false;
        lastReport = reportId;
        return true;
    }
}
