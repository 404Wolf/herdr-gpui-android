package dev.herdr.android;

/**
 * Wolf's ask: offline is one small indicator in a top corner, never a grayed
 * or dimmed screen. While the link is down, reconnecting, or a cached copy of
 * the pane is on screen, the terminal keeps its full colour and keeps
 * scrolling; this indicator alone says it is not live. It sits at the top
 * right, in the tab row (the screen's first row), so it covers no terminal
 * cell. Tapping it opens Reconnect and Hosts in place beside it (LinkActions),
 * which the full-width reconnect banner used to carry: no dialog, no scrim.
 * A raw shell says the same way that its connection was lost, or that it
 * exited, which is not a lost link.
 *
 * <p>Pure: what it shows is decided here, so it is testable without a device.
 */
final class LinkBadge {
    private LinkBadge() { }

    /** The longest text the indicator shows; longer details go to its description. */
    static final int MAX_CHARS = 26;
    /** Its type size, the crumb's: small, but legible on the cover screen. */
    static final int TEXT_SP = 11;
    /** Its widest, so a long host or date can never crowd the tab strip out. */
    static final int MAX_WIDTH_DP = 170;
    /** Where it sits: the top row's end, beside the More button. */
    static final int GRAVITY = android.view.Gravity.TOP | android.view.Gravity.END;
    /** The leading dot. */
    static final String DOT = "●";

    /** How strongly it speaks: offline is a warning, connecting is quiet. */
    enum Tone { WARN, QUIET }

    /** The in-place actions' first label: a Herdr pane or a lost shell reconnects. */
    static final String RECONNECT = "Reconnect";
    /** A shell that exited has nothing to reconnect to: it opens a new one. */
    static final String NEW_SHELL = "New shell";
    /** The second action, always: the host list. */
    static final String HOSTS = "Hosts";

    /** How a raw shell ended. */
    enum ShellEnd {
        /** The host ended it: the shell exited (Ctrl-D, exit, the host closed it). */
        EXITED,
        /** Its connection was lost: the network, the host or the SSH session went away. */
        LOST
    }

    /** What the indicator shows; {@link #shown} false hides it. */
    static final class Look {
        final boolean shown;
        final String text;
        final String description;
        final Tone tone;
        /** What the in-place actions say: the failure, else the description's first sentences. */
        final String title;
        /** The first in-place action: {@link #RECONNECT} or {@link #NEW_SHELL}. */
        final String primary;

        Look(boolean shown, String text, String description, Tone tone, String title, String primary) {
            this.shown = shown;
            this.text = text;
            this.description = description;
            this.tone = tone;
            this.title = title;
            this.primary = primary;
        }
    }

    static final Look HIDDEN = new Look(false, "", "", Tone.QUIET, "", RECONNECT);

    /**
     * @param down the link is down or a reconnect is running
     * @param noNetwork the phone has no network at all
     * @param problem a failure worth saying on tap (already human, see BannerCopy), or null
     * @param cached a held or saved copy of the pane is on screen
     * @param when that copy's age ("14:32"), null when the pane has no copy
     * @param waiting messages and inputs that wait for the link
     */
    static Look look(boolean down, boolean noNetwork, String problem, boolean cached, String when, int waiting) {
        boolean failed = problem != null && !problem.isEmpty();
        if (!down && !failed && !cached) return HIDDEN;
        boolean offline = noNetwork || failed;
        String word = offline ? "offline" : "connecting";
        String age = !cached ? null : when == null ? "not cached" : when;
        String queued = waiting > 0 ? waiting + " queued" : null;
        String text = join(word, age, queued);
        if (text.length() > MAX_CHARS) text = join(word, age, null);
        if (text.length() > MAX_CHARS) text = word;

        StringBuilder said = new StringBuilder(offline ? "Offline." : "Connecting.");
        if (cached) {
            said.append(when == null ? " This pane has no saved copy." : " Showing the copy from " + when + ".");
        }
        if (waiting > 0) {
            said.append(' ').append(waiting).append(waiting == 1 ? " message waits" : " messages wait")
                    .append(" to be sent.");
        }
        String title = failed ? problem : said.toString();
        said.append(" Tap to reconnect or open the hosts.");
        return new Look(true, text, said.toString(), offline ? Tone.WARN : Tone.QUIET, title, RECONNECT);
    }

    /**
     * A raw shell's end, in the same corner: hidden while it runs (null). A
     * lost connection is "offline" and offers a new connection; an exit is
     * "exited", quiet, and offers a new shell. Neither sends anything typed
     * before the end again: a shell has no state to resume.
     *
     * @param end how it ended, or null while it runs
     * @param problem the failure worth saying (already human), or null
     */
    static Look shell(ShellEnd end, String problem) {
        if (end == null) return HIDDEN;
        if (end == ShellEnd.EXITED) {
            return new Look(true, "exited", "The shell exited. Tap to open a new shell or the hosts.",
                    Tone.QUIET, "The shell exited.", NEW_SHELL);
        }
        String why = problem == null || problem.trim().isEmpty() ? "The shell's connection was lost." : problem.trim();
        return new Look(true, "offline", "Offline. " + sentence(why)
                + " Nothing typed is sent again. Tap to open a new shell or the hosts.",
                Tone.WARN, why, RECONNECT);
    }

    /** Ends {@code text} as a sentence, so the next one never runs into it. */
    static String sentence(String text) {
        if (text.isEmpty()) return text;
        char last = text.charAt(text.length() - 1);
        return last == '.' || last == '!' || last == '?' || last == '…' ? text : text + ".";
    }

    private static String join(String word, String age, String queued) {
        StringBuilder text = new StringBuilder(word);
        if (age != null) text.append(" · ").append(age);
        if (queued != null) text.append(" · ").append(queued);
        return text.toString();
    }
}
