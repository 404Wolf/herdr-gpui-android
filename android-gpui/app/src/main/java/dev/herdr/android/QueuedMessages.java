package dev.herdr.android;

import java.util.List;

/**
 * How the outbox's messages for the pane on screen are summarised without an
 * extra line over the terminal: a count badge on Enter, coloured by the most
 * pressing message, and a tap-to-list sheet whose rows read like Claude Code's
 * own echo of a prompt, {@code ❯ text · state}. Pure: no views.
 */
final class QueuedMessages {
    /** What the badge shows; past this it reads {@code 9+}. */
    static final int MAX_COUNT = 9;
    static final String PROMPT = "❯ ";

    /** The most pressing look among the messages, for the badge colour. */
    enum Urgency { NONE, QUEUED, SENDING, WAITING, GONE }

    private QueuedMessages() { }

    /** The badge's text, or "" when nothing is queued (the badge is hidden). */
    static String badge(int count) {
        if (count <= 0) return "";
        return count > MAX_COUNT ? MAX_COUNT + "+" : Integer.toString(count);
    }

    static Urgency urgency(List<Outbox.Message> messages) {
        Urgency most = Urgency.NONE;
        for (Outbox.Message message : messages) {
            Urgency one = urgency(message);
            if (one.ordinal() > most.ordinal()) most = one;
        }
        return most;
    }

    private static Urgency urgency(Outbox.Message message) {
        switch (message.state) {
            case GONE: return Urgency.GONE;
            case UNSURE: return Urgency.WAITING;
            case SENDING: return Urgency.SENDING;
            default: return message.blocked ? Urgency.WAITING : Urgency.QUEUED;
        }
    }

    /** The sheet's title. */
    static String title(int count) {
        return count == 1 ? "1 queued message, sent in order" : count + " queued messages, sent in order";
    }

    /** One sheet row: the message on one line, then what is happening to it. */
    static String row(Outbox.Message message, String pane) {
        return PROMPT + oneLine(message.text) + "  · " + label(message, pane);
    }

    /** What is happening to the message, in a few words. */
    static String label(Outbox.Message message, String pane) {
        switch (message.state) {
            case SENDING: return "sending…";
            case UNSURE: return "may have been sent";
            case GONE: return message.pane.equals(pane) ? "not sent" : "not sent · " + message.pane + " gone";
            default: return message.blocked ? "waits for its dialog" : "queued";
        }
    }

    /** A message's lines on one line: a break shows as ⏎ so nothing looks cut off. */
    static String oneLine(String text) {
        return text.trim().replaceAll("\\s*\\n\\s*", " ⏎ ").replace('\t', ' ');
    }
}
