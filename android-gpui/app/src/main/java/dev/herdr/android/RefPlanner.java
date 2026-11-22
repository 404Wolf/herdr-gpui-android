package dev.herdr.android;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;

/**
 * Which referenced file to read next for offline reading (PR-F of the offline redesign), and
 * when: never at the cost of the live terminal. A read is allowed only while the app is in the
 * foreground, the link is live, the network is validated and unmetered, no scroll gesture runs,
 * and nothing was typed or sent in the last {@link #QUIET_AFTER_INPUT_MS}; at most one is in
 * flight, at least {@link #SPACING_MS} apart; each file is read at most once in
 * {@link #REFRESH_MS} unless the link was replaced since (a reconnect reads them again).
 *
 * <p>Until the session's reads stop holding up typing (factory-setup.38), each read is small:
 * {@link #PREFETCH_LINES} lines; a tap while live reads the whole file and keeps that.
 *
 * <p>Pure (its clock is passed in), so the pacing is unit-tested; thread-safe, since the poll
 * offers and starts reads and the reader reports them done.
 */
final class RefPlanner {
    static final long QUIET_AFTER_INPUT_MS = 1_500;
    static final long SPACING_MS = 2_000;
    static final long REFRESH_MS = 10 * 60_000;
    /** How long a read may stay in flight before another may start. */
    static final long READ_TIMEOUT_MS = 30_000;
    static final int PREFETCH_LINES = 2_000;
    /** References waiting at once, newest kept. */
    static final int MAX_QUEUED = 256;
    /** Files remembered as read, for their refresh time. */
    static final int MAX_REMEMBERED = 4_096;

    /** One file to read: as {@code terminal} on {@code machine} named it. */
    static final class Ref {
        final MachineKey machine;
        final String terminal;
        final String path;

        Ref(MachineKey machine, String terminal, String path) {
            this.machine = machine;
            this.terminal = terminal;
            this.path = path;
        }

        String key() {
            return machine.core + "\n" + machine.hostKey + "\n" + terminal + "\n" + path;
        }
    }

    /** What the phone is doing now, as the gate reads it. */
    static final class Now {
        final long ms;
        final boolean foreground, live, unmetered, scrolling;
        final long lastInputMs;

        Now(long ms, boolean foreground, boolean live, boolean unmetered, boolean scrolling, long lastInputMs) {
            this.ms = ms;
            this.foreground = foreground;
            this.live = live;
            this.unmetered = unmetered;
            this.scrolling = scrolling;
            this.lastInputMs = lastInputMs;
        }
    }

    private final Deque<Ref> queue = new ArrayDeque<>();
    private final Map<String, Long> readAt = new HashMap<>();
    private long lastStartMs = Long.MIN_VALUE / 2;
    private Ref inFlight;
    private long inFlightSince;
    /** The link the reads belong to; a new one reads every file again. */
    private long link;

    /**
     * {@code files} were seen on {@code terminal}'s screen over link {@code linkToken} at
     * {@code nowMs}. Files not
     * worth a copy (URLs, directories, names with no text extension) never get here: the
     * renderer's link rules already left them out.
     */
    synchronized void offer(long linkToken, MachineKey machine, String terminal, Iterable<String> files, long nowMs) {
        if (machine == null || terminal == null || terminal.isEmpty() || files == null) return;
        if (linkToken != link) {
            link = linkToken;
            readAt.clear();
        }
        for (String path : files) {
            if (path == null || path.isEmpty() || path.length() > 4096 || path.contains("://")) continue;
            Ref ref = new Ref(machine, terminal, path);
            Long at = readAt.get(ref.key());
            if (at != null && nowMs - at < REFRESH_MS || queued(ref)) continue;
            queue.addLast(ref);
            while (queue.size() > MAX_QUEUED) queue.removeFirst();
        }
    }

    private synchronized boolean queued(Ref ref) {
        if (inFlight != null && inFlight.key().equals(ref.key())) return true;
        for (Ref waiting : queue) if (waiting.key().equals(ref.key())) return true;
        return false;
    }

    /** Whether a read may start now. */
    synchronized boolean allowed(Now now) {
        if (!now.foreground || !now.live || !now.unmetered || now.scrolling) return false;
        if (now.ms - now.lastInputMs < QUIET_AFTER_INPUT_MS) return false;
        if (inFlight != null && now.ms - inFlightSince < READ_TIMEOUT_MS) return false;
        return now.ms - lastStartMs >= SPACING_MS;
    }

    /** The next file to read now, or null; the caller reads it and reports {@link #done}. */
    synchronized Ref next(Now now) {
        if (!allowed(now)) return null;
        while (!queue.isEmpty()) {
            Ref ref = queue.removeFirst();
            Long at = readAt.get(ref.key());
            if (at != null && now.ms - at < REFRESH_MS) continue;
            inFlight = ref;
            inFlightSince = now.ms;
            lastStartMs = now.ms;
            return ref;
        }
        return null;
    }

    /** The read of {@code ref} ended (kept or not) at {@code nowMs}. */
    synchronized void done(Ref ref, long nowMs) {
        if (ref == null) return;
        if (inFlight != null && inFlight.key().equals(ref.key())) inFlight = null;
        readAt.put(ref.key(), nowMs);
        if (readAt.size() > MAX_REMEMBERED) readAt.clear();
    }

    /** References waiting to be read. */
    synchronized int waiting() {
        return queue.size();
    }
}
