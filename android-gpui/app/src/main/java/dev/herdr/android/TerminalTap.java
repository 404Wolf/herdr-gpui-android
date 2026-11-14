package dev.herdr.android;

/**
 * What a tap on the terminal does on the phone. The renderer has the tap
 * first: when the program asked for mouse events (Claude Code's fullscreen
 * view, other TUIs) it is sent to the program as a click, and then it opens
 * nothing here. A tap the program did not take opens typing as before. One
 * tap never both clicks and opens the keyboard; the drawn "type" control
 * still opens it.
 *
 * <p>The renderer reports each tap's outcome after the touch reached it.
 * The activity marks a tap it counted ({@link #tapped}) and acts once,
 * on the report or when the wait for it runs out ({@link #settle}).
 */
final class TerminalTap {
    /** Long enough for the renderer's report; it normally comes in a frame or two. */
    static final long REPORT_WAIT_MS = 400;

    enum Action {
        /** The program took the tap as a click: nothing opens. */
        NOTHING,
        /** An agent's pane: the composer's draft takes the typing. */
        TYPE_INTO_DRAFT,
        /** Any other pane: the terminal's own keyboard. */
        SHOW_KEYBOARD
    }

    static Action after(boolean clickedProgram, boolean agentPane) {
        if (clickedProgram) return Action.NOTHING;
        return agentPane ? Action.TYPE_INTO_DRAFT : Action.SHOW_KEYBOARD;
    }

    private boolean waiting;

    /** A tap the activity counted (short and still) went on to the renderer. */
    void tapped() {
        waiting = true;
    }

    /**
     * The renderer reported a tap, or the wait ran out: whether to act on the
     * counted tap now. True once per counted tap; a report with none counted
     * (a press the activity took for a scroll or a hold) is ignored.
     */
    boolean settle() {
        boolean was = waiting;
        waiting = false;
        return was;
    }
}
