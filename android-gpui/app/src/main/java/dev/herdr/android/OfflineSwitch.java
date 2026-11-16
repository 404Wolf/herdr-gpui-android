package dev.herdr.android;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Switching panes and machines with no link (item 77): which way a switch
 * goes, and the one status line a cached pane shows. Pure; the terminal
 * activity does the drawing.
 */
final class OfflineSwitch {
    private OfflineSwitch() { }

    /** What a pane switch does. */
    enum Route {
        /** The live session's own focus request. */
        LIVE,
        /** No session: remember the pane, move the selection, draw its saved copy. */
        OFFLINE,
        /** Not a Herdr session at all (a companion or shell): nothing to switch. */
        REFUSE
    }

    /**
     * {@code offline}: a stand-in for a computer with no session (opened with
     * no link, or switched to while it is unreachable). {@code closed}: the
     * session ended (the link dropped; the monitor is reconnecting).
     * {@code handle}: the native session, 0 for none.
     */
    static Route route(boolean offline, boolean closed, long handle) {
        if (offline) return Route.OFFLINE;
        if (handle <= 0) return Route.REFUSE;
        return closed ? Route.OFFLINE : Route.LIVE;
    }

    /** The one dim line under the title of a pane with no saved copy. */
    static final String NOT_CACHED = "not cached · opens when the link is back";

    /**
     * When a cached copy was saved, as its label says it: the time today,
     * the weekday and time within the week, else the date and time.
     */
    static String when(long savedAt, long now, TimeZone zone, boolean twentyFour, Locale locale) {
        Calendar saved = Calendar.getInstance(zone, locale);
        saved.setTimeInMillis(savedAt);
        Calendar today = Calendar.getInstance(zone, locale);
        today.setTimeInMillis(now);
        String time = twentyFour ? "HH:mm" : "h:mm a";
        String pattern;
        if (saved.get(Calendar.YEAR) == today.get(Calendar.YEAR)
                && saved.get(Calendar.DAY_OF_YEAR) == today.get(Calendar.DAY_OF_YEAR)) {
            pattern = time;
        } else if (savedAt <= now && now - savedAt < 6L * 24 * 60 * 60 * 1000) {
            pattern = "EEE " + time;
        } else {
            pattern = "MMM d " + time;
        }
        SimpleDateFormat format = new SimpleDateFormat(pattern, locale);
        format.setTimeZone(zone);
        return format.format(new Date(savedAt));
    }

    /**
     * The status line while a cached pane is on screen, in the crumb's slot:
     * {@code cached 14:32 · offline} (no network), {@code cached 14:32 ·
     * connecting} (a network, the session not back yet), {@code not cached ·
     * offline} when the pane has no copy ({@code when} null), then how many
     * messages wait to be sent.
     */
    static String label(String when, boolean noNetwork, int waiting) {
        StringBuilder text = new StringBuilder(when == null ? "not cached" : "cached " + when);
        text.append(noNetwork ? " · offline" : " · connecting");
        if (waiting > 0) text.append(" · ").append(waiting).append(waiting == 1 ? " waits" : " wait");
        return text.toString();
    }

    /** "cols,rows,…" (TerminalGrid) as {cols, rows}: an uncached pane's note fills the screen. 80×24 when unreadable. */
    static int[] gridSize(String grid) {
        String[] parts = grid == null ? new String[0] : grid.split(",");
        try {
            if (parts.length >= 2) return new int[] {Integer.parseInt(parts[0].trim()), Integer.parseInt(parts[1].trim())};
        } catch (NumberFormatException ignored) {
            // Falls through to the default.
        }
        return new int[] {80, 24};
    }
}
