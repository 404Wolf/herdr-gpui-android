package dev.herdr.android;

/**
 * When the answer sheet reads the pane, opens and closes (factory-setup.33), with no Android
 * in it: {@link ElicitationOffer} is the thin shell that runs these decisions on the main
 * thread, reads on a worker and shows the sheet. One instance per activity; main thread only.
 *
 * <p>The rules: read only the pane on screen while it can take input and may be asking; open
 * once per dialog; never open over the user's typing (a draft in the composer, or the keyboard
 * up), but look again once they stop; close when the pane, its attach or the connection
 * changes, since the sheet's answers and keys belong to the pane it opened on.
 */
final class ElicitationPolicy {
    /** What the shell does after {@link #check}. */
    enum Next {
        /** Read the pane now (on the worker), then report with {@link #landed}. */
        READ,
        /** Look again after {@link ElicitationOffer#CHECK_MILLIS}. */
        LATER,
        /** Nothing to do until {@link #observe} or {@link #sheetClosed} says otherwise. */
        IDLE,
    }

    /** What the shell does after {@link #landed}. */
    enum Landed {
        /** Open the sheet on the dialog just read. */
        OPEN,
        /** Something changed while reading: check again at once. */
        CHECK,
        LATER,
        /** Stop reading until {@link #observe} reports a change (the pane has no renderer). */
        IDLE,
    }

    /** generation/pane/attach/live: the pane the reads and keys are for. */
    private String where = "";
    /** Null while the pane cannot take input. */
    private String status;
    private String agentKind = "";
    /** The dialog already offered on this pane ({@link #identity}), so it opens once. */
    private String offered = "";
    private boolean reading;
    private boolean recheck;
    private boolean sheetOpen;
    /**
     * The pane on screen has no renderer (offline, or between attaches): reading it again
     * every few hundred ms finds nothing, so the next read waits for the activity to report
     * a new attach, status or connection.
     */
    private boolean detached;

    /** The pane kind {@link ElicitationNative} takes, from the activity's agent kind. */
    static String kind(String agentKind) {
        switch (agentKind == null ? "" : agentKind) {
            case "claude":
            case "codex":
            case "gemini":
                return agentKind;
            default:
                return "other";
        }
    }

    /**
     * Whether a pane with this core status and kind may show a dialog worth reading for.
     * Claude reports {@code blocked} while it asks; Codex and Gemini do not report it reliably
     * (see ELICITATION.md), so their screens are read while they are not working. A null
     * status is a pane that cannot take input now.
     */
    static boolean mayAsk(String status, String agentKind) {
        if (status == null) return false;
        if ("claude".equals(agentKind)) return "blocked".equals(status);
        return !"working".equals(status);
    }

    /**
     * Whether a dialog read on a pane of {@code agentKind} is offered: on a pane of unknown kind
     * only Gemini's shape is, since any program may draw a numbered list with a cursor.
     */
    static boolean offers(Elicitation.Dialog dialog, String agentKind) {
        if (dialog == null) return false;
        if ("other".equals(kind(agentKind))) return dialog.harness == Elicitation.Harness.GEMINI;
        return true;
    }

    /**
     * One dialog however it is redrawn: by its tabs when it has several (moving between them
     * on the terminal is the same dialog), else by its question.
     */
    static String identity(Elicitation.Dialog dialog) {
        StringBuilder out = new StringBuilder(dialog.harness.name());
        if (dialog.tabs.size() > 1) {
            for (Elicitation.Tab tab : dialog.tabs) out.append('|').append(tab.label);
        } else {
            out.append('|').append(dialog.question);
        }
        return out.toString();
    }

    /**
     * The activity's view of the pane changed. Returns true when the open sheet must close:
     * it was opened on another pane, attach or connection.
     */
    boolean observe(String where, String status, String agentKind) {
        boolean moved = !where.equals(this.where);
        boolean close = moved && sheetOpen;
        if (moved) {
            this.where = where;
            offered = "";
        }
        this.status = status;
        this.agentKind = agentKind == null ? "" : agentKind;
        // Something changed (the activity reports changes only): worth reading again.
        detached = false;
        // The agent stopped asking: its next dialog, even the same question again, is new.
        if (!mayAsk(status, this.agentKind)) offered = "";
        return close;
    }

    /**
     * Whether to read now. {@code ready} is false until the shell can draw a sheet (the theme
     * arrives with the renderer's first frame); {@code busy} while the user is typing.
     */
    Next check(boolean ready, boolean busy) {
        if (reading) {
            recheck = true;
            return Next.IDLE;
        }
        if (sheetOpen || detached || !mayAsk(status, agentKind)) return Next.IDLE;
        if (!ready || busy) return Next.LATER;
        reading = true;
        return Next.READ;
    }

    /** The read for {@code at} came back with {@code dialog} (null: none on screen). */
    Landed landed(String at, Elicitation.Dialog dialog, boolean ready, boolean busy) {
        reading = false;
        boolean open = at.equals(where) && ready && !busy && !sheetOpen && mayAsk(status, agentKind)
                && offers(dialog, agentKind) && !identity(dialog).equals(offered);
        if (open) {
            offered = identity(dialog);
            sheetOpen = true;
            recheck = false;
            return Landed.OPEN;
        }
        if (recheck) {
            recheck = false;
            return Landed.CHECK;
        }
        return Landed.LATER;
    }

    /**
     * The read for {@code at} found no renderer for the pane. Reading stops until the next
     * {@link #observe}; a change that came in while the read was out is read at once.
     */
    Landed detached(String at) {
        reading = false;
        if (at.equals(where)) detached = true;
        if (recheck) {
            recheck = false;
            return Landed.CHECK;
        }
        return Landed.IDLE;
    }

    /** The shell could not read (the worker is gone): no read is out any more. */
    void readFailed() {
        reading = false;
    }

    /** The sheet closed, however it closed. The same dialog is not offered again. */
    void sheetClosed() {
        sheetOpen = false;
    }

    /**
     * Why the user counts as busy, or null. The keyboard counts only when the user opened it:
     * the app raises it by itself on opening an agent that waits on a question, and that
     * keyboard is for exactly the answer the sheet gives, so it must not hold the sheet back.
     * Text in the composer or a dictation in flight ({@code composer}) always counts.
     */
    static String busy(boolean keyboardShown, boolean raisedByApp, String composer) {
        if (composer != null) return composer;
        if (keyboardShown && !raisedByApp) return "the keyboard";
        return null;
    }

    /** Whether opening the sheet puts the keyboard away: only one the app raised by itself. */
    static boolean hidesKeyboard(boolean keyboardShown, boolean raisedByApp) {
        return keyboardShown && raisedByApp;
    }

    /**
     * Whose answers a sheet starts from: the same dialog on the same pane after a reconnect or
     * a re-attach keeps what the user picked; anything else starts empty.
     */
    static String answersKey(String where, Elicitation.Dialog dialog) {
        String[] parts = where.split("/", -1);
        String pane = parts.length > 1 ? parts[1] : where;
        return pane + "|" + identity(dialog);
    }

    String where() {
        return where;
    }

    boolean sheetOpen() {
        return sheetOpen;
    }
}
