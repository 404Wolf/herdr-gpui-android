package dev.herdr.android;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/**
 * The phone's one cache and sync engine for what its screens read live (factory-9zz.14,
 * DESIGN.md): each entry is one machine's answer to one question (its beads, an agent's doc),
 * kept in memory and on disk, shown at once and refreshed in the background
 * (stale-while-revalidate).
 *
 * <ul>
 *   <li><b>Keys</b> are the proven {@link MachineKey} the answer came from (never a profile id, so
 *       one machine's data never shows as another's), a {@link Kind}, and a name within it.
 *   <li><b>One read at a time</b>, on the connected machine's core session, best first:
 *       {@link Priority#VISIBLE} (on screen) before {@link Priority#PREFETCH} (a tap away) before
 *       {@link Priority#REFRESH} (background). A key is asked at most once at a time.
 *   <li><b>Gates</b>: nothing runs while the app is paused. While Wolf types (the keyboard is
 *       up or a draft is in the composer) only a visible read with nothing kept runs, so a cold
 *       screen is never left loading and typing never waits behind background work. The engine
 *       never touches the composer: it only asks {@link Host#typing}.
 *   <li><b>Honest</b>: every entry carries when it was read and from which machine; a failed
 *       read keeps the last answer and says why, and is retried after a growing wait; an answer
 *       too big to keep is an error, never a silent hole.
 *   <li><b>Observable</b>: every read logs one line (key, priority, wait, run, bytes, outcome).
 * </ul>
 *
 * <p>Threads: every public method runs on the main thread; reads run on {@code net} and disk
 * work on {@code disk} (one thread each), and results come back through {@code main}. All
 * three are injected, so the JVM tests drive it step by step.
 */
final class SyncEngine {
    /** What an entry is (closed: one fetcher each). */
    enum Kind {
        /** A machine's whole beads mirror ({@code beads.dump}); name "" here, else the machine it was relayed from. */
        BEADS(30_000, 4 * 1024 * 1024 * 3),
        /** An agent's status doc ({@code doc.read}); name the doc's. */
        DOC(10_000, 256 * 1024);

        /** Older than this, an entry is read again when asked for. */
        final long ttlMs;
        /** The most text an entry of this kind may keep. */
        final int maxChars;

        Kind(long ttlMs, int maxChars) {
            this.ttlMs = ttlMs;
            this.maxChars = maxChars;
        }
    }

    enum Priority { VISIBLE, PREFETCH, REFRESH }

    /** One question to one machine. */
    static final class Key {
        final MachineKey machine;
        final Kind kind;
        final String name;

        Key(MachineKey machine, Kind kind, String name) {
            this.machine = Objects.requireNonNull(machine);
            this.kind = Objects.requireNonNull(kind);
            this.name = name == null ? "" : name;
        }

        @Override public boolean equals(Object o) {
            if (!(o instanceof Key)) return false;
            Key k = (Key) o;
            return machine.equals(k.machine) && kind == k.kind && name.equals(k.name);
        }

        @Override public int hashCode() {
            return Objects.hash(machine, kind, name);
        }

        @Override public String toString() {
            return kind.name().toLowerCase(java.util.Locale.ROOT) + (name.isEmpty() ? "" : ":" + name);
        }
    }

    /** One kept answer. Immutable; a newer one replaces it with a higher {@link #version}. */
    static final class Entry {
        enum State { FRESH, ERROR }

        /** The answer's text as its kind keeps it; "" when nothing was ever read. */
        final String text;
        /** Its fingerprint (a store's, a doc's key), sent back so an unchanged answer costs nothing. */
        final String fp;
        /** When it was read (wall clock), and which machine said so (its name as it gave it). */
        final long atMs;
        final String source;
        final State state;
        /** Why the last read failed (words for the screen), or null. */
        final String error;
        final long version;
        /** The text decoded by its kind's decoder off the main thread (a BeadsMirror), or null. Never saved. */
        final Object value;

        Entry(String text, String fp, long atMs, String source, State state, String error, long version) {
            this(text, fp, atMs, source, state, error, version, null);
        }

        Entry(String text, String fp, long atMs, String source, State state, String error, long version, Object value) {
            this.value = value;
            this.text = text == null ? "" : text;
            this.fp = fp == null ? "" : fp;
            this.atMs = atMs;
            this.source = source == null ? "" : source;
            this.state = state;
            this.error = error;
            this.version = version;
        }

        boolean hasData() {
            return !text.isEmpty();
        }
    }

    /** What a fetch found: new text, the kept text still current ("same"), or why it failed. */
    static final class Fetched {
        final String text, fp, source, error;
        final boolean same;

        private Fetched(String text, String fp, String source, boolean same, String error) {
            this.text = text;
            this.fp = fp;
            this.source = source;
            this.same = same;
            this.error = error;
        }

        static Fetched data(String text, String fp, String source) {
            return new Fetched(text, fp, source, false, null);
        }

        static Fetched same(String source) {
            return new Fetched(null, null, source, true, null);
        }

        static Fetched failed(String error) {
            return new Fetched(null, null, null, false, error == null || error.isEmpty() ? "can't reach the machine" : error);
        }
    }

    /** Reads one kind's answer; runs on the net thread; throws {@link HostSwitched} when the machine is not on screen. */
    interface Fetcher {
        Fetched fetch(Key key, Entry kept);
    }

    /** Where entries are kept between starts (SyncFiles; a map in the tests). Runs on the disk thread. */
    interface Store {
        Entry load(Key key);
        void save(Key key, Entry entry);
    }

    /** What the engine asks of the app. */
    interface Host {
        /** The keyboard is up or a draft is in the composer (only asked; never changed). */
        boolean typing();
    }

    static final long MAX_BACKOFF_MS = 5 * 60_000;
    /** After "the phone left that machine", the same key waits this long (no spinning on a closing link). */
    static final long SWITCHED_RETRY_MS = 1_000;

    private final Map<Kind, Fetcher> fetchers;
    private final Map<Kind, java.util.function.Function<String, Object>> decoders = new HashMap<>();
    private final Store store;
    private final Host host;
    private final Executor main, net, disk;
    private final LongSupplier clock;
    private final Consumer<String> log;

    private final Map<Key, Entry> entries = new HashMap<>();
    /** Keys whose disk copy was asked for (loaded or not). */
    private final Set<Key> loaded = new HashSet<>(), loading = new HashSet<>();
    private final Map<Key, List<Runnable>> listeners = new HashMap<>();
    /** What is wanted, and how badly: the best priority asked since it was last read. */
    private final Map<Key, Priority> wanted = new LinkedHashMap<>();
    private final Map<Key, Long> wantedAt = new HashMap<>();
    /** Failed reads in a row, per key, and when the next may run. */
    private final Map<Key, Integer> failures = new HashMap<>();
    private final Map<Key, Long> retryAt = new HashMap<>();
    private MachineKey connected;
    private Key inFlight;
    private boolean paused;
    private long versions;

    SyncEngine(Map<Kind, Fetcher> fetchers, Store store, Host host, Executor main, Executor net, Executor disk,
            LongSupplier clock, Consumer<String> log) {
        this.fetchers = new HashMap<>(fetchers);
        this.store = store;
        this.host = host;
        this.main = main;
        this.net = net;
        this.disk = disk;
        this.clock = clock;
        this.log = log;
    }

    /**
     * {@code kind}'s text is decoded by {@code decode} on the net or disk thread, before it reaches
     * the main thread, so a big answer (a machine's whole beads) is never parsed while drawing.
     */
    void decode(Kind kind, java.util.function.Function<String, Object> decode) {
        decoders.put(kind, decode);
    }

    private Object decoded(Kind kind, String text) {
        java.util.function.Function<String, Object> d = decoders.get(kind);
        if (d == null || text == null || text.isEmpty()) return null;
        try {
            return d.apply(text);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** The machine the phone is connected to now (null: none proven), whose core reads go through. */
    void connected(MachineKey machine) {
        if (Objects.equals(connected, machine)) return;
        connected = machine;
        pump();
    }

    void paused(boolean on) {
        paused = on;
        if (!on) pump();
    }

    /** The kept entry now (null: nothing kept yet, or its disk copy is still loading). */
    Entry get(Key key) {
        load(key);
        return entries.get(key);
    }

    /**
     * Wants {@code key} at {@code priority}: the kept entry now (null while nothing is kept), and a
     * read when it is missing or older than its kind's TTL. Cheap to call on every draw.
     */
    Entry want(Key key, Priority priority) {
        Entry kept = get(key);
        if (!loaded.contains(key)) {
            // Its disk copy first: the read is decided once it is in.
            ask(key, priority);
            return kept;
        }
        if (stale(key, kept)) ask(key, priority);
        return kept;
    }

    /** {@code listener} runs on the main thread whenever {@code key}'s entry changes. */
    void listen(Key key, Runnable listener) {
        listeners.computeIfAbsent(key, k -> new ArrayList<>()).add(listener);
    }

    void unlisten(Key key, Runnable listener) {
        List<Runnable> l = listeners.get(key);
        if (l != null && l.remove(listener) && l.isEmpty()) listeners.remove(key);
    }

    /** Starts what is due (the screens' one-second tick: a gate may have lifted). */
    void tick() {
        pump();
    }

    /** Whether a read of {@code key} is on its way now. */
    boolean reading(Key key) {
        return key.equals(inFlight);
    }

    // ---- inside ----

    private boolean stale(Key key, Entry kept) {
        if (kept == null || kept.atMs <= 0) return true;
        return clock.getAsLong() - kept.atMs >= key.kind.ttlMs;
    }

    private void ask(Key key, Priority priority) {
        Priority was = wanted.get(key);
        if (was == null || priority.ordinal() < was.ordinal()) {
            wanted.put(key, priority);
            if (was == null) wantedAt.put(key, clock.getAsLong());
        }
        pump();
    }

    private void load(Key key) {
        if (loaded.contains(key) || !loading.add(key)) return;
        disk.execute(() -> {
            Entry read;
            try {
                read = store.load(key);
            } catch (RuntimeException e) {
                read = null;
            }
            Entry found = read == null ? null
                    : new Entry(read.text, read.fp, read.atMs, read.source, read.state, read.error, 0, decoded(key.kind, read.text));
            main.execute(() -> {
                loading.remove(key);
                loaded.add(key);
                Entry now = entries.get(key);
                if (found != null && (now == null || now.atMs < found.atMs)) {
                    entries.put(key, new Entry(found.text, found.fp, found.atMs, found.source, found.state, found.error, ++versions, found.value));
                    notifyChanged(key);
                }
                // Kept and young enough: nobody needs to read it now.
                Priority p = wanted.get(key);
                if (p != null && !stale(key, entries.get(key))) wanted.remove(key);
                pump();
            });
        });
    }

    /** Starts the best read that may run now, if none is running. */
    private void pump() {
        if (inFlight != null || paused || connected == null) return;
        Key best = null;
        Priority bestP = null;
        long now = clock.getAsLong();
        boolean typing = host.typing();
        for (Map.Entry<Key, Priority> w : wanted.entrySet()) {
            Key key = w.getKey();
            if (!key.machine.equals(connected) || !loaded.contains(key)) continue;
            Long retry = retryAt.get(key);
            if (retry != null && now < retry) continue;
            Entry kept = entries.get(key);
            // Typing: only a visible screen with nothing to show reads.
            if (typing && !(w.getValue() == Priority.VISIBLE && (kept == null || !kept.hasData()))) continue;
            if (best == null || w.getValue().ordinal() < bestP.ordinal()) {
                best = key;
                bestP = w.getValue();
            }
        }
        if (best == null) return;
        Key key = best;
        Priority priority = bestP;
        wanted.remove(key);
        long queued = now - wantedAt.getOrDefault(key, now);
        wantedAt.remove(key);
        inFlight = key;
        Entry kept = entries.get(key);
        Fetcher fetcher = fetchers.get(key.kind);
        long started = clock.getAsLong();
        net.execute(() -> {
            Fetched got;
            boolean switched = false;
            try {
                got = fetcher.fetch(key, kept);
            } catch (HostSwitched gone) {
                got = null;
                switched = true;
            } catch (RuntimeException e) {
                got = Fetched.failed(OpTiming.reason("sync", e));
            }
            Fetched answer = got;
            boolean dropped = switched;
            Object value = got == null || got.error != null || got.text == null || got.text.length() > key.kind.maxChars
                    ? null : decoded(key.kind, got.text);
            main.execute(() -> done(key, priority, queued, started, answer, value, dropped));
        });
    }

    private void done(Key key, Priority priority, long queued, long started, Fetched got, Object value, boolean switched) {
        inFlight = null;
        long ran = clock.getAsLong() - started;
        if (switched) {
            // Not this machine's fault: it is asked again once it is back on screen.
            wanted.putIfAbsent(key, priority);
            wantedAt.putIfAbsent(key, clock.getAsLong());
            retryAt.put(key, clock.getAsLong() + SWITCHED_RETRY_MS);
            log("dropped: the phone left that machine", key, priority, queued, ran, 0);
            pump();
            return;
        }
        Entry kept = entries.get(key);
        long now = clock.getAsLong();
        Entry next;
        String outcome;
        if (got.error == null && !got.same && got.text != null && got.text.length() > key.kind.maxChars) {
            got = Fetched.failed("its answer is too big to keep on this phone (" + got.text.length() / 1024 + " KB)");
        }
        if (got.error != null) {
            int n = failures.merge(key, 1, Integer::sum);
            retryAt.put(key, now + Math.min(MAX_BACKOFF_MS, key.kind.ttlMs << Math.min(n - 1, 10)));
            next = kept == null
                    ? new Entry("", "", 0, "", Entry.State.ERROR, got.error, ++versions)
                    : new Entry(kept.text, kept.fp, kept.atMs, kept.source, Entry.State.ERROR, got.error, ++versions, kept.value);
            outcome = "error: " + got.error;
        } else {
            failures.remove(key);
            retryAt.remove(key);
            if (got.same && kept != null) {
                next = new Entry(kept.text, kept.fp, now, got.source != null ? got.source : kept.source, Entry.State.FRESH, null, ++versions, kept.value);
                outcome = "same";
            } else {
                next = new Entry(got.text == null ? "" : got.text, got.fp, now, got.source, Entry.State.FRESH, null, ++versions, value);
                outcome = "data";
            }
        }
        entries.put(key, next);
        // Asked again while this read was out (every draw asks): this answer is that answer.
        if (next.state == Entry.State.FRESH) {
            wanted.remove(key);
            wantedAt.remove(key);
        }
        if (next.state == Entry.State.FRESH || next.hasData()) {
            Entry keep = next;
            disk.execute(() -> {
                try {
                    store.save(key, keep);
                } catch (RuntimeException e) {
                    // Kept in memory; the next read tries the disk again.
                }
            });
        }
        log(outcome, key, priority, queued, ran, got.text == null ? 0 : got.text.length());
        notifyChanged(key);
        pump();
    }

    private void notifyChanged(Key key) {
        List<Runnable> l = listeners.get(key);
        if (l == null) return;
        for (Runnable r : new ArrayList<>(l)) r.run();
    }

    private void log(String outcome, Key key, Priority priority, long queued, long ran, int chars) {
        log.accept("sync " + key + " " + priority.name().toLowerCase(java.util.Locale.ROOT) + ": waited " + queued
                + " ms, ran " + ran + " ms, " + chars + " chars, " + outcome);
    }
}
