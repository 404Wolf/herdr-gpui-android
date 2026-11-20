package dev.herdr.android;

import java.util.ArrayDeque;

/**
 * Input that never left the phone, held across a reconnect and sent once, in
 * order, when the same PTY is ready again.
 *
 * Only commands that were never handed to a session belong here: what the
 * terminal's queue still held when the link dropped, what was typed while it
 * was reconnecting. A command that was being sent when the link failed may
 * have reached the host, so it is never put back: that would type it twice.
 *
 * An entry leaves only through {@link #sent()}, after the session accepted it,
 * so a send that fails keeps it for the next connection. Input for another
 * PTY is not redirected: if the reconnect attaches elsewhere it is discarded and
 * counted, so the user is told instead of it typing into the wrong program.
 * Not thread-safe; the activity guards it with its state lock.
 */
final class PendingInput {
    static final int MAX_COMMANDS = 64;
    static final int MAX_CHARS = 256 * 1024;

    private static final class Entry {
        final String target;
        final String command;

        Entry(String target, String command) {
            this.target = target;
            this.command = command;
        }
    }

    private final ArrayDeque<Entry> entries = new ArrayDeque<>();
    private int chars;
    private int discarded;

    /** False (and nothing held) when the attach identity is unknown or the bound is reached. */
    boolean add(String target, String command) {
        if (target == null || target.isEmpty() || command == null) return false;
        if (entries.size() >= MAX_COMMANDS || chars + command.length() > MAX_CHARS) return false;
        entries.addLast(new Entry(target, command));
        chars += command.length();
        return true;
    }

    /**
     * The next command to send to {@code target}, left in place until
     * {@link #sent()}. Held input for any other PTY is discarded first.
     */
    String next(String target) {
        while (!entries.isEmpty() && !entries.peekFirst().target.equals(target)) {
            chars -= entries.pollFirst().command.length();
            discarded++;
        }
        Entry head = entries.peekFirst();
        return head == null ? null : head.command;
    }

    /** The command {@link #next(String)} returned was accepted by the session. */
    void sent() {
        Entry head = entries.pollFirst();
        if (head != null) chars -= head.command.length();
    }

    /** How many commands were discarded since the last call. */
    int takeDiscarded() {
        int count = discarded;
        discarded = 0;
        return count;
    }

    /** Drops everything, counting it as discarded (a new host, a restarted Herdr). */
    void discardAll() {
        discarded += entries.size();
        entries.clear();
        chars = 0;
    }

    boolean isEmpty() {
        return entries.isEmpty();
    }

    int size() {
        return entries.size();
    }
}
