package dev.herdr.android;

/**
 * Wolf's ask: offline is one small indicator in a top corner, never a grayed
 * or dimmed screen. While the link is down, reconnecting, or a cached copy of
 * the pane is on screen, the terminal keeps its full colour and keeps
 * scrolling; this indicator alone says it is not live. It sits at the top
 * right, in the tab row (the screen's first row), so it covers no terminal
 * cell and pushes nothing up or down. Tapping it offers Reconnect and Hosts,
 * which the full-width reconnect banner used to carry.
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

    /** What the indicator shows; {@link #shown} false hides it. */
    static final class Look {
        final boolean shown;
        final String text;
        final String description;
        final Tone tone;
        /** The tap sheet's title: the failure, else the description's first sentence. */
        final String title;

        Look(boolean shown, String text, String description, Tone tone, String title) {
            this.shown = shown;
            this.text = text;
            this.description = description;
            this.tone = tone;
            this.title = title;
        }
    }

    static final Look HIDDEN = new Look(false, "", "", Tone.QUIET, "");

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
        return new Look(true, text, said.toString(), offline ? Tone.WARN : Tone.QUIET, title);
    }

    private static String join(String word, String age, String queued) {
        StringBuilder text = new StringBuilder(word);
        if (age != null) text.append(" · ").append(age);
        if (queued != null) text.append(" · ").append(queued);
        return text.toString();
    }
}
