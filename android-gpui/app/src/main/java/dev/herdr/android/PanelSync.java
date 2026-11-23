package dev.herdr.android;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BooleanSupplier;
import java.util.function.Function;

/**
 * The right panel's reads through the {@link SyncEngine} (factory-9zz.14): what WorkPanel's
 * readStores / useKeptStores / readBead / readDoc and its BeadCache calls become. The machine
 * on screen's whole beads mirror ({@code beads.dump}) draws the Tasks tree and opens every
 * bead with no read; Overview's doc is kept between starts; the deacon's Tasks are its own
 * machine's mirror only. Everything is keyed by the proven machine.
 *
 * <p>Main thread only, as the engine. {@link #version()} grows with every change the panel
 * shows, for WorkPanel's redraw signature.
 */
final class PanelSync {
    /** The machine's own beads ("" name); a name is another machine read through this one's core. */
    static final String HERE = "";
    private static final BeadsMirror EMPTY = BeadsMirror.of("");

    private final SyncEngine engine;
    private final List<Runnable> changed = new ArrayList<>();
    private final Runnable fire = () -> { for (Runnable r : new ArrayList<>(changed)) r.run(); };
    private final List<SyncEngine.Key> heard = new ArrayList<>();
    private final String[] machineName;
    private final java.util.Map<SyncEngine.Key, Object[]> docs = new java.util.HashMap<>();
    private MachineKey machine;

    PanelSync(SyncEngine engine, String[] machineName) {
        this.engine = engine;
        this.machineName = machineName;
        // A machine's whole beads are parsed where they arrive, never while drawing.
        engine.decode(SyncEngine.Kind.BEADS, text -> {
            BeadsMirror m = BeadsMirror.of(text);
            m.listing();
            return m;
        });
    }

    /** The app's: reads on a thread of their own through {@code op} (WorkPanel.Host.op), files in the sync share. */
    static PanelSync create(android.app.Activity activity, Function<String, String> op, BooleanSupplier typing) {
        android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
        String[] name = {""};
        SyncEngine engine = new SyncEngine(fetchers(op, () -> name[0]), SyncFiles.in(activity), typing::getAsBoolean,
                main::post, thread("herdr-sync"), thread("herdr-sync-disk"), System::currentTimeMillis,
                line -> {
                    try {
                        android.util.Log.i("herdr-ops", line);
                    } catch (RuntimeException notOnAndroid) {
                        // JVM unit tests have no logcat.
                    }
                });
        return new PanelSync(engine, name);
    }

    private static Executor thread(String name) {
        ExecutorService one = Executors.newSingleThreadExecutor(task -> {
            Thread t = new Thread(task, name);
            t.setDaemon(true);
            return t;
        });
        return one::execute;
    }

    /** What a key made by {@link #liveOnly} starts with: never written to disk. */
    static final String LIVE_ONLY = "live-only ";

    /**
     * The key the panel's reads go under: the proven machine; for a profile refused its machine
     * (it works live only, MachineBindings), one of its own that is never kept on disk; null
     * while the connection has not proven its machine yet (nothing is read until it has).
     */
    static MachineKey keyFor(MachineKey proven, boolean refused, String hostId) {
        if (proven != null) return proven;
        return refused ? liveOnly(hostId) : null;
    }

    static MachineKey liveOnly(String hostId) {
        return MachineKey.of(LIVE_ONLY + (hostId == null || hostId.isEmpty() ? "-" : hostId), "unproven");
    }

    static boolean isLiveOnly(MachineKey key) {
        return key != null && key.core.startsWith(LIVE_ONLY);
    }

    /** The machine on screen, as proven (null: not yet), and what it calls itself. */
    void select(MachineKey machine, String name) {
        machineName[0] = name == null ? "" : name;
        if (java.util.Objects.equals(this.machine, machine)) return;
        this.machine = machine;
        engine.connected(machine);
    }

    void paused(boolean on) {
        engine.paused(on);
    }

    /** The panel's one-second tick. */
    void tick() {
        engine.tick();
    }

    /** {@code r} runs whenever something the panel asked for changed. */
    void onChange(Runnable r) {
        changed.add(r);
    }

    long version() {
        long v = 0;
        for (SyncEngine.Key k : heard) {
            SyncEngine.Entry e = engine.get(k);
            v = v * 31 + (e == null ? 0 : e.version) + (engine.reading(k) ? 1 : 0);
        }
        return v;
    }

    // ---- Tasks ----

    /** One machine's beads as the panel draws them. */
    static final class Beads {
        /** The machine they are from, as it calls itself; "" when nothing is kept yet. */
        final String machine;
        final BeadsMirror mirror;
        /** When they were read (wall clock; 0 never), why the last read failed (or null), and whether one is out. */
        final long atMs;
        final String error;
        final boolean reading;

        Beads(String machine, BeadsMirror mirror, long atMs, String error, boolean reading) {
            this.machine = machine;
            this.mirror = mirror;
            this.atMs = atMs;
            this.error = error;
            this.reading = reading;
        }

        /** The skeleton rule: nothing kept and nothing wrong. */
        boolean loading() {
            return mirror.isEmpty() && atMs <= 0 && error == null;
        }

        /** As WorkPanel's {@code stores}. */
        Map<String, BeadsModel.Store> stores() {
            return mirror.listing();
        }

        /** A bead's plan and comments as BeadCache keeps a read of it; null when the mirror has no such bead. */
        BeadCache.Entry bead(String store, String id) {
            String text = mirror.read(store, id);
            BeadsModel.Store s = stores().get(store);
            return text == null ? null : new BeadCache.Entry(text, atMs, s == null ? "" : s.fp);
        }
    }

    /** The machine on screen's beads ({@code from} HERE), or another machine's read through its core. */
    Beads beads(String from, SyncEngine.Priority priority) {
        if (machine == null) return new Beads("", BeadsMirror.of(""), 0, null, false);
        SyncEngine.Key key = key(SyncEngine.Kind.BEADS, from);
        SyncEngine.Entry e = engine.want(key, priority);
        String name = e != null && !e.source.isEmpty() ? e.source : from.isEmpty() ? machineName[0] : from;
        BeadsMirror mirror = e != null && e.value instanceof BeadsMirror ? (BeadsMirror) e.value : EMPTY;
        return new Beads(name, mirror, e == null ? 0 : e.atMs, e == null ? null : e.error, engine.reading(key));
    }

    // ---- Overview ----

    /** An agent's doc as Overview draws it: the kept read (null: none yet), why the last failed, and its age. */
    static final class Doc {
        final DocModel.Read read;
        final String error;
        final boolean reading;

        Doc(DocModel.Read read, String error, boolean reading) {
            this.read = read;
            this.error = error;
            this.reading = reading;
        }
    }

    Doc doc(String name, SyncEngine.Priority priority) {
        if (machine == null || name == null) return new Doc(null, null, false);
        SyncEngine.Key key = key(SyncEngine.Kind.DOC, name);
        SyncEngine.Entry e = engine.want(key, priority);
        DocModel.Read read = null;
        if (e != null && e.hasData()) {
            // Parsed once per version, not on every tick.
            Object[] memo = docs.get(key);
            if (memo == null || (long) memo[0] != e.version) docs.put(key, memo = new Object[] {e.version, DocModel.parse(e.text)});
            read = (DocModel.Read) memo[1];
        }
        return new Doc(read, e == null ? null : e.error, engine.reading(key));
    }

    private SyncEngine.Key key(SyncEngine.Kind kind, String name) {
        SyncEngine.Key key = new SyncEngine.Key(machine, kind, name);
        if (!heard.contains(key)) {
            heard.add(key);
            engine.listen(key, fire);
        }
        return key;
    }

    // ---- the reads ----

    /** Each kind's read through {@code op} (WorkPanel.Host.op: blocks; throws HostSwitched). */
    static Map<SyncEngine.Kind, SyncEngine.Fetcher> fetchers(Function<String, String> op, java.util.function.Supplier<String> here) {
        Map<SyncEngine.Kind, SyncEngine.Fetcher> out = new EnumMap<>(SyncEngine.Kind.class);
        out.put(SyncEngine.Kind.BEADS, (key, kept) -> {
            JSONObject ask = op("beads.dump");
            put(ask, "known", new JSONArray(BeadsMirror.known(kept == null ? "" : kept.text)));
            if (!key.name.isEmpty()) put(ask, "on", key.name);
            String on = key.name.isEmpty() ? here.get() : key.name;
            JSONObject reply;
            try {
                reply = reply(op.apply(ask.toString()));
            } catch (HostSwitched gone) {
                throw gone;
            } catch (RuntimeException e) {
                return SyncEngine.Fetched.failed(OpTiming.reason("sync", e, on));
            }
            String text = textOf(reply);
            if (text == null) return SyncEngine.Fetched.failed(errorOf(reply));
            BeadsMirror.Dump dump = BeadsMirror.parseDump(text);
            if (dump.missing != null) return SyncEngine.Fetched.failed(dump.missing);
            String source = key.name.isEmpty() ? here.get() : key.name;
            String merged = BeadsMirror.merge(kept == null ? "" : kept.text, dump);
            if (kept != null && merged.equals(kept.text)) return SyncEngine.Fetched.same(source);
            return SyncEngine.Fetched.data(merged, "", source);
        });
        out.put(SyncEngine.Kind.DOC, (key, kept) -> {
            JSONObject ask = op("doc.read");
            put(ask, "name", key.name);
            if (kept != null && kept.hasData() && !kept.fp.isEmpty()) put(ask, "key", kept.fp);
            JSONObject reply;
            try {
                reply = reply(op.apply(ask.toString()));
            } catch (HostSwitched gone) {
                throw gone;
            } catch (RuntimeException e) {
                return SyncEngine.Fetched.failed(OpTiming.reason("sync", e, here.get()));
            }
            String text = textOf(reply);
            if (text == null) return SyncEngine.Fetched.failed(errorOf(reply));
            DocModel.Read read = DocModel.parse(text);
            if (read.error != null) return SyncEngine.Fetched.failed(read.error);
            if (read.same && kept != null && kept.hasData()) {
                // Unchanged: the kept text with that machine's clock now, so its age reads true.
                return SyncEngine.Fetched.data(withNow(kept.text, read.now), kept.fp, here.get());
            }
            return SyncEngine.Fetched.data(text, read.key == null ? "" : read.key, here.get());
        });
        return out;
    }

    /** DOC_SH's text with its second line (the machine's clock) replaced by {@code now}. */
    static String withNow(String text, long now) {
        String[] lines = text.split("\n", 3);
        if (lines.length < 2) return text;
        return lines[0] + "\n" + now + (lines.length > 2 ? "\n" + lines[2] : "");
    }

    private static JSONObject reply(String json) {
        try {
            return new JSONObject(json);
        } catch (JSONException | NullPointerException e) {
            JSONObject bad = new JSONObject();
            put(bad, "error", "the machine answered something this app can't read");
            return bad;
        }
    }

    private static String textOf(JSONObject reply) {
        if (!reply.optBoolean("queued", false) || reply.has("error")) return null;
        return reply.optString("text", "");
    }

    private static String errorOf(JSONObject reply) {
        String e = reply.optString("error", "");
        return e.isEmpty() ? "can't reach the machine" : e;
    }

    private static JSONObject op(String name) {
        JSONObject o = new JSONObject();
        put(o, "op", name);
        return o;
    }

    private static void put(JSONObject o, String k, Object v) {
        try {
            o.put(k, v);
        } catch (JSONException impossible) {
            // A plain key and value always go in.
        }
    }
}
