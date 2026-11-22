package dev.herdr.android;

import android.app.Activity;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * The selected machine's aswap usage, kept warm (factory-setup.32): what the
 * Usage sheet shows the moment it opens, with its age, and the one look that
 * refreshes it.
 *
 * <p>Measured on the device and the emulator (research/usage-speed): aswap's
 * {@code list --json} reads its cache only and runs in 50–300 ms on the
 * machine; a look's cost is the exec and the connection, and the sheet used to
 * wait for both before it drew anything, behind any other action queued on the
 * terminal's worker. So: the last answer for each machine is kept, on disk
 * too, and drawn at once; one look at a time asks the <em>selected</em>
 * machine only, on a thread of its own; while the Usage sheet or the rail is
 * visible it asks every {@link Pace#CADENCE_MS} (a 50–300 ms exec every 10 s
 * holds one of the core's 16 exec slots about 1–3% of the time); nothing asks
 * while the app is paused or the machine is offline; and a core whose exec
 * slots are full ("execs are running already") is asked again after a
 * growing wait. Never a refresh: aswap's upstream quota calls are its own.
 */
final class UsageFeed {
    /** Who wants fresh usage on screen. */
    enum Watch { SHEET, RAIL }

    /**
     * What an answer is: a look's reply (DATA, which may still be a whole-sheet
     * state such as aswap missing), a busy or unreachable machine, the machine
     * no longer selected, or what was kept from an earlier run (KEPT).
     */
    enum Outcome { DATA, BUSY, UNREACHABLE, SWITCHED, KEPT }

    /** One look's answer for one machine. */
    static final class Answer {
        final String hostId;
        final Outcome outcome;
        /** The parsed reply (DATA, KEPT), else null. */
        final UsageModel.Look look;
        /** When the shown data was read (wall clock), for its age. */
        final long atMs;
        final UsageTiming timing;
        /** When it was handed to the main thread (elapsed clock), for the timing log; 0 when not a look's. */
        long postedMs;

        Answer(String hostId, Outcome outcome, UsageModel.Look look, long atMs, UsageTiming timing) {
            this.hostId = hostId;
            this.outcome = outcome;
            this.look = look;
            this.atMs = atMs;
            this.timing = timing;
        }
    }

    /**
     * When to ask, pure so it can be tested: the times are arguments. Asks
     * only while someone watches, the app is in front and the machine is
     * online; never two at once; every {@link #CADENCE_MS} after an answer, or
     * after a growing wait while the machine is busy or unreachable.
     */
    static final class Pace {
        static final long CADENCE_MS = 10_000;

        private final Set<Watch> watching = EnumSet.noneOf(Watch.class);
        boolean paused, online, inFlight;
        /** When the last look ended (elapsed clock), or -1 before any. */
        long lastEndMs = -1;
        private int busyTries;
        private long waitMs = CADENCE_MS;

        void watch(Watch who, boolean on) {
            if (on) watching.add(who);
            else watching.remove(who);
        }

        boolean watched() {
            return !watching.isEmpty() && !paused && online;
        }

        /** A new machine is selected: nothing of the old one's pacing carries over. */
        void reset() {
            lastEndMs = -1;
            busyTries = 0;
            waitMs = CADENCE_MS;
        }

        /** How long until the next look, or -1 when nothing should ask (not watched, or one is out). */
        long delay(long nowMs) {
            if (!watched() || inFlight) return -1;
            if (lastEndMs < 0) return 0;
            return Math.max(0, lastEndMs + waitMs - nowMs);
        }

        void started() {
            inFlight = true;
        }

        /** A look ended at {@code nowMs}: a busy or unreachable machine waits longer each time. */
        void ended(Outcome outcome, long nowMs) {
            inFlight = false;
            lastEndMs = nowMs;
            if (outcome == Outcome.BUSY || outcome == Outcome.UNREACHABLE) {
                // Never sooner than the cadence: 10, 20, 40, then 60 s.
                waitMs = Math.max(CADENCE_MS, OpTiming.busyBackoff(1 + busyTries++));
            } else {
                busyTries = 0;
                waitMs = CADENCE_MS;
            }
        }
    }

    /** Kept answers across restarts: this many machines at most, each reply at most this long. */
    static final int MAX_HOSTS = 16, MAX_REPLY_CHARS = 64 * 1024;
    static final String FILE_NAME = "usage-looks.json";

    private static UsageFeed shared;

    /** The app's one feed. */
    static synchronized UsageFeed shared(Context context) {
        if (shared == null) {
            shared = new UsageFeed(new File(context.getApplicationContext().getNoBackupFilesDir(), FILE_NAME));
        }
        return shared;
    }

    private final File file;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService thread = Executors.newSingleThreadExecutor(task -> {
        Thread t = new Thread(task, "herdr-usage");
        t.setDaemon(true);
        return t;
    });
    private final Pace pace = new Pace();
    /** The kept reply and its time per saved host; read from disk once, on the usage thread. */
    private final Map<String, Object[]> kept = new HashMap<>();
    private boolean loaded;
    private final List<Consumer<Answer>> listeners = new ArrayList<>();
    private String hostId;
    private Supplier<String> look;
    private PauseWatch pauses;
    private final Runnable ask = this::ask;

    private UsageFeed(File file) {
        this.file = file;
        thread.execute(this::load);
    }

    /** Follows {@code activity}'s pauses: nothing asks while it is not in front. */
    UsageFeed attach(Activity activity) {
        if (pauses != null) return this;
        pauses = PauseWatch.on(activity, () -> { pace.paused = true; schedule(); }, () -> { pace.paused = false; schedule(); });
        return this;
    }

    void detach(Activity activity) {
        if (pauses != null) pauses.stop(activity);
        pauses = null;
        pace.paused = true;
        schedule();
    }

    /**
     * The machine on screen ({@code hostId}), whether it is online, and how to
     * ask its core ({@code look} blocks; it runs on the usage thread only and
     * throws {@link HostSwitched} once that machine is no longer on screen).
     * Cheap when nothing changed: called on every chrome refresh.
     */
    void select(String hostId, boolean online, Supplier<String> look) {
        boolean changed = hostId == null ? this.hostId != null : !hostId.equals(this.hostId);
        this.look = look;
        if (changed) {
            String was = this.hostId;
            this.hostId = hostId;
            pace.reset();
            if (was != null) {
                Answer gone = new Answer(was, Outcome.SWITCHED, null, System.currentTimeMillis(), null);
                for (Consumer<Answer> listener : new ArrayList<>(listeners)) listener.accept(gone);
            }
        }
        if (changed || pace.online != online) {
            pace.online = online && hostId != null;
            schedule();
        }
    }

    void watch(Watch who, boolean on) {
        pace.watch(who, on);
        schedule();
    }

    /** {@code listener} hears every answer for the selected machine, on the main thread, until removed. */
    void listen(Consumer<Answer> listener) {
        listeners.add(listener);
    }

    void unlisten(Consumer<Answer> listener) {
        listeners.remove(listener);
    }

    /** The last data kept for {@code hostId}, or null (none yet, or not read from disk yet). */
    Answer kept(String hostId) {
        synchronized (kept) {
            Object[] at = kept.get(hostId);
            if (at == null) return null;
            return new Answer(hostId, Outcome.KEPT, UsageModel.parse((String) at[0]), (Long) at[1], null);
        }
    }

    boolean loaded() {
        synchronized (kept) {
            return loaded;
        }
    }

    private void schedule() {
        main.removeCallbacks(ask);
        long delay = pace.delay(SystemClock.elapsedRealtime());
        // Due now: asked now, not after the main thread draws the sheet that wants it (~0.7 s on the emulator).
        if (delay == 0) ask();
        else if (delay > 0) main.postDelayed(ask, delay);
    }

    private void ask() {
        long delay = pace.delay(SystemClock.elapsedRealtime());
        if (delay != 0 || look == null) {
            if (delay > 0) main.postDelayed(ask, delay);
            return;
        }
        String host = hostId;
        Supplier<String> asking = look;
        pace.started();
        long queued = SystemClock.elapsedRealtime();
        thread.execute(() -> {
            long started = SystemClock.elapsedRealtime();
            Outcome outcome;
            UsageModel.Look parsed = null;
            String reply = null;
            try {
                reply = asking.get();
                parsed = UsageModel.parse(reply);
                outcome = Outcome.DATA;
            } catch (HostSwitched gone) {
                outcome = Outcome.SWITCHED;
            } catch (RuntimeException e) {
                outcome = OpTiming.busy(OpTiming.reason("usage.look", e)) ? Outcome.BUSY : Outcome.UNREACHABLE;
            } catch (LinkageError e) {
                outcome = Outcome.UNREACHABLE;
            }
            long ended = SystemClock.elapsedRealtime();
            UsageTiming timing = UsageTiming.of(started - queued, ended - started, reply);
            android.util.Log.i("herdr-ops", timing.line() + " (" + outcome.name().toLowerCase(java.util.Locale.ROOT) + ")");
            long at = System.currentTimeMillis();
            if (outcome == Outcome.DATA && parsed.kind == UsageModel.Kind.ACCOUNTS) keep(host, reply, at);
            Answer answer = new Answer(host, outcome, parsed, at, timing);
            Outcome said = outcome;
            answer.postedMs = SystemClock.elapsedRealtime();
            main.post(() -> {
                pace.ended(said, SystemClock.elapsedRealtime());
                if (host != null && host.equals(hostId)) {
                    for (Consumer<Answer> listener : new ArrayList<>(listeners)) listener.accept(answer);
                }
                schedule();
            });
        });
    }

    // ---- the kept answers, on the usage thread ----

    private void load() {
        Map<String, Object[]> read = new HashMap<>();
        try {
            if (file.isFile() && file.length() <= (long) MAX_HOSTS * MAX_REPLY_CHARS * 2) {
                read = decode(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
            }
        } catch (IOException e) {
            // Nothing kept: the sheet asks, as it would on a first run.
        }
        Map<String, Object[]> found = read;
        synchronized (kept) {
            for (Map.Entry<String, Object[]> e : found.entrySet()) kept.putIfAbsent(e.getKey(), e.getValue());
            loaded = true;
        }
        main.post(() -> {
            Answer here = hostId == null ? null : kept(hostId);
            if (here == null) return;
            for (Consumer<Answer> listener : new ArrayList<>(listeners)) listener.accept(here);
        });
    }

    private void keep(String host, String reply, long at) {
        if (host == null || reply == null || reply.length() > MAX_REPLY_CHARS) return;
        String encoded;
        synchronized (kept) {
            kept.put(host, new Object[] {reply, at});
            trim(kept);
            encoded = encode(kept);
        }
        try {
            File tmp = new File(file.getPath() + ".tmp");
            Files.write(tmp.toPath(), encoded.getBytes(StandardCharsets.UTF_8));
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            // Kept in memory still; the next answer tries the disk again.
        }
    }

    /** Drops the oldest machines past {@link #MAX_HOSTS}. */
    static void trim(Map<String, Object[]> kept) {
        while (kept.size() > MAX_HOSTS) {
            String oldest = null;
            long at = Long.MAX_VALUE;
            for (Map.Entry<String, Object[]> e : kept.entrySet()) {
                long when = (Long) e.getValue()[1];
                if (when < at) { at = when; oldest = e.getKey(); }
            }
            kept.remove(oldest);
        }
    }

    /** The file: {@code {"v":1,"hosts":{"<host>":{"at":<ms>,"reply":"<usage.look reply>"}}}}. */
    static String encode(Map<String, Object[]> kept) {
        JSONObject hosts = new JSONObject();
        try {
            for (Map.Entry<String, Object[]> e : kept.entrySet()) {
                hosts.put(e.getKey(), new JSONObject().put("at", e.getValue()[1]).put("reply", e.getValue()[0]));
            }
            return new JSONObject().put("v", 1).put("hosts", hosts).toString();
        } catch (JSONException impossible) {
            return "{}";
        }
    }

    /** What a kept file holds; a damaged file, a newer version or a bad entry is left out, never thrown. */
    static Map<String, Object[]> decode(String text) {
        Map<String, Object[]> out = new HashMap<>();
        try {
            JSONObject root = new JSONObject(text);
            if (root.optInt("v", 0) != 1) return out;
            JSONObject hosts = root.optJSONObject("hosts");
            if (hosts == null) return out;
            Iterator<String> keys = hosts.keys();
            while (keys.hasNext()) {
                String host = keys.next();
                JSONObject entry = hosts.optJSONObject(host);
                if (entry == null) continue;
                String reply = entry.optString("reply", "");
                long at = entry.optLong("at", 0);
                if (host.isEmpty() || reply.isEmpty() || reply.length() > MAX_REPLY_CHARS || at <= 0) continue;
                if (UsageModel.parse(reply).kind != UsageModel.Kind.ACCOUNTS) continue;
                out.put(host, new Object[] {reply, at});
            }
        } catch (JSONException e) {
            return new HashMap<>();
        }
        trim(out);
        return out;
    }
}
