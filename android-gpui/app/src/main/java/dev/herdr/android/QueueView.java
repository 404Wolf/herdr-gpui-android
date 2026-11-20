package dev.herdr.android;

import dev.herdr.android.dictation.PendingClip;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Everything waiting to reach the pane on screen, as rows drawn above the agent's prompt (items 75
 * and 76): the outbox's messages, recordings waiting for the desktop, input typed while the link
 * was down, and messages just sent (shown "✓ sent" for {@link #SENT_FADE_MS}, then gone). Each row
 * says what is happening to it; at a cover-screen width a glyph says it instead. Collapsed, only
 * the newest rows show, dim; expanded, as many as fit, the oldest hidden behind a "+N more" row.
 *
 * <p>Pure: no views. A message that leaves the outbox counts as sent unless the user took it out
 * ({@link #forget}) or it was left blank by an empty dictation.
 */
final class QueueView {
    static final long SENT_FADE_MS = 3000;
    /** Narrower grids (the Fold's cover screen) get one-line rows with a state glyph. */
    static final int COVER_COLS = 60;
    static final int MAX_ROWS_WIDE = 6, MAX_ROWS_COVER = 3;
    /** Rows left showing while the list is collapsed: the queue is never invisible. */
    static final int COLLAPSED_ROWS = 2;

    enum Kind { MESSAGE, SENT, CLIP, PARKED, LINK, MORE }

    /** One row above the prompt. */
    static final class Row {
        final String id;
        final Kind kind;
        /** The row's words (a message keeps its dictation tokens: they are drawn as chips). */
        final String text;
        /** What is happening to it, in words, or a glyph at cover width. */
        final String state;
        /** The state's style: "tag", "info", "warn" or "danger". */
        final String stateStyle;
        /** A tap on it does something (opens its actions, or the full list). */
        final boolean actionable;
        /** The message's own state, for its actions; null for other rows. */
        final Outbox.State messageState;

        Row(String id, Kind kind, String text, String state, String stateStyle, boolean actionable,
                Outbox.State messageState) {
            this.id = id;
            this.kind = kind;
            this.text = text;
            this.state = state;
            this.stateStyle = stateStyle;
            this.actionable = actionable;
            this.messageState = messageState;
        }
    }

    /** A message seen sent: its words stay a moment, "✓ sent". */
    private static final class Sent {
        final String id, pane, text;
        final long until;

        Sent(String id, String pane, String text, long until) {
            this.id = id;
            this.pane = pane;
            this.text = text;
            this.until = until;
        }
    }

    /** The pane's messages as last seen, by id, for telling a send from a take. */
    private final Map<String, Outbox.Message> seen = new LinkedHashMap<>();
    private String seenPane = "";
    /** Ids the user took out (edit, drop, clear): their leaving is not a send. */
    private final Set<String> forgotten = new HashSet<>();
    private final List<Sent> sent = new ArrayList<>();

    /**
     * The outbox's messages for {@code pane} now. Those that left since the last call for the same
     * pane, not taken by the user and not blank dictations, were sent.
     */
    void update(String pane, List<Outbox.Message> messages, long now) {
        String p = pane == null ? "" : pane;
        if (!p.equals(seenPane)) {
            seen.clear();
            seenPane = p;
        }
        Set<String> present = new HashSet<>();
        for (Outbox.Message m : messages) present.add(m.id);
        for (Outbox.Message gone : seen.values()) {
            if (present.contains(gone.id) || forgotten.remove(gone.id)) continue;
            // Only a message typed at once leaves when written; a held one stays, "sent · check it arrived".
            boolean wasSendable = gone.pane.equals(p) && !gone.awaitsDictation && gone.state == Outbox.State.SENDING;
            if (wasSendable) sent.add(new Sent(gone.id, gone.pane, gone.text, now + SENT_FADE_MS));
        }
        seen.clear();
        for (Outbox.Message m : messages) seen.put(m.id, m);
        prune(now);
    }

    /** The user took {@code id} out of the queue: it was not sent. */
    void forget(String id) {
        forgotten.add(id);
    }

    /** When the next "sent" row fades, for a refresh; {@link Long#MAX_VALUE} with none. */
    long nextChange(long now) {
        prune(now);
        long next = Long.MAX_VALUE;
        for (Sent s : sent) next = Math.min(next, s.until);
        return next;
    }

    /** What the queue control counts: everything still waiting (sent rows excluded). */
    static int count(List<Outbox.Message> messages, List<PendingClip> clips, int linkDown) {
        return count(messages, clips, Collections.emptyList(), linkDown);
    }

    /** With recordings the desktop gave up on: they still wait (for "retry"). */
    static int count(List<Outbox.Message> messages, List<PendingClip> clips, List<PendingClip> parked,
            int linkDown) {
        int waiting = 0;
        for (Outbox.Message m : messages) if (m.state != Outbox.State.SENT) waiting++;
        return waiting + clips.size() + parked.size() + (linkDown > 0 ? 1 : 0);
    }

    /**
     * The rows for {@code pane}, oldest first (the newest is drawn next to the prompt).
     *
     * @param clips recordings waiting for the desktop that no draft or message on screen holds
     * @param linkDown input typed while the link was down, waiting for it
     * @param selected the row whose actions are open, or null
     */
    List<Row> rows(String pane, List<Outbox.Message> messages, List<PendingClip> clips, int linkDown,
            int cols, boolean expanded, String selected, long now) {
        return rows(pane, messages, clips, Collections.emptyList(), linkDown, cols, expanded, selected, now);
    }

    /**
     * @param parked recordings the desktop gave up on after its attempts: kept, shown "not
     *     transcribed yet", sent again only by their row's "retry"
     */
    List<Row> rows(String pane, List<Outbox.Message> messages, List<PendingClip> clips, List<PendingClip> parked,
            int linkDown, int cols, boolean expanded, String selected, long now) {
        boolean cover = cols < COVER_COLS;
        int max = expanded ? (cover ? MAX_ROWS_COVER : MAX_ROWS_WIDE) : COLLAPSED_ROWS;
        return rowsUpTo(pane, messages, clips, parked, linkDown, cols, max, expanded, now);
    }

    /** Every row for {@code pane}, none hidden behind "+N more" (the full list's sheet). */
    List<Row> all(String pane, List<Outbox.Message> messages, List<PendingClip> clips, List<PendingClip> parked,
            int linkDown, int cols, long now) {
        return rowsUpTo(pane, messages, clips, parked, linkDown, cols, Integer.MAX_VALUE, false, now);
    }

    private List<Row> rowsUpTo(String pane, List<Outbox.Message> messages, List<PendingClip> clips,
            List<PendingClip> parked, int linkDown, int cols, int max, boolean more, long now) {
        prune(now);
        boolean cover = cols < COVER_COLS;
        List<Row> all = new ArrayList<>();
        for (Sent s : sent) {
            if (!s.pane.equals(pane)) continue;
            all.add(new Row(s.id, Kind.SENT, QueuedMessages.PROMPT + QueuedMessages.oneLine(s.text),
                    cover ? "✓" : "✓ sent", "info", false, null));
        }
        int place = 0;
        for (Outbox.Message m : messages) {
            boolean waitsInLine = m.state == Outbox.State.PENDING && m.pane.equals(pane);
            if (waitsInLine) place++;
            all.add(new Row(m.id, Kind.MESSAGE, QueuedMessages.PROMPT + QueuedMessages.oneLine(m.text),
                    state(m, pane, place, cover), style(m), m.state != Outbox.State.SENDING, m.state));
        }
        for (PendingClip clip : clips) {
            all.add(new Row(clip.id, Kind.CLIP, DictationToken.of(clip.id),
                    cover ? "◌" : "◌ waits for desktop", "tag", true, null));
        }
        for (PendingClip clip : parked) {
            all.add(new Row(clip.id, Kind.PARKED, DictationToken.of(clip.id),
                    cover ? "✗ retry" : "✗ not transcribed yet · retry", "warn", true, null));
        }
        if (linkDown > 0) {
            all.add(new Row("link", Kind.LINK, "◌ " + linkDown + (linkDown == 1 ? " input" : " inputs")
                    + " typed while reconnecting", cover ? "◌" : "sends when back", "warn", false, null));
        }
        if (all.size() <= max) return all;
        List<Row> shown = new ArrayList<>(all.subList(all.size() - max, all.size()));
        if (more) {
            // The oldest give way to one row that opens the full list.
            int hidden = all.size() - max + 1;
            shown.set(0, new Row("more", Kind.MORE, "+" + hidden + " more ▲", "", "tag", true, null));
        }
        return Collections.unmodifiableList(shown);
    }

    /** A message's state in a few words, or a glyph at cover width. */
    static String state(Outbox.Message m, String pane, int place, boolean cover) {
        switch (m.state) {
            case SENDING: return cover ? "●" : "● sending…";
            case SENT:
                return cover ? "✓?" : "✓ sent " + HeldWords.time(m.sentAt, java.util.TimeZone.getDefault(), true)
                        + " · check it arrived";
            case NEEDS_YOU:
                if (m.reason == Outbox.Reason.GONE) return cover ? "✗" : "✗ needs you · " + m.pane + " gone";
                return cover ? "?" : "? may have been sent · needs you";
            default:
                if (m.awaitsDictation) return cover ? "◌" : "◌ waits for desktop";
                if (m.unverified()) return cover ? "◌?" : "◌ held · destination not verified";
                return cover ? "◌" + place : "◌ held " + ordinal(place) + " · tap Send";
        }
    }

    private static String style(Outbox.Message m) {
        switch (m.state) {
            case SENDING: return "info";
            case SENT: return "info";
            case NEEDS_YOU: return m.reason == Outbox.Reason.GONE ? "danger" : "warn";
            default: return "tag";
        }
    }

    static String ordinal(int n) {
        int mod100 = n % 100;
        if (mod100 >= 11 && mod100 <= 13) return n + "th";
        switch (n % 10) {
            case 1: return n + "st";
            case 2: return n + "nd";
            case 3: return n + "rd";
            default: return n + "th";
        }
    }

    private void prune(long now) {
        sent.removeIf(s -> s.until <= now);
    }
}
