package dev.herdr.android;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Keeps agents' transcripts while a direct link is up and finds the proven one offline (stage 2
 * of the offline design). The poll thread tells it what each poll showed ({@link #listed},
 * {@link #tick}); reads run on their own thread, one at a time, and disk work on another, so
 * neither the poll nor the UI thread ever waits on them.
 */
final class TranscriptKeeper {
    /** The bounded read; {@code RemoteNative::readTranscript} in the app. */
    interface Wire {
        String read(long handle, String terminal, int lines, int maxBytes);
    }

    /** The most rows of history one read asks for. */
    static final int LINES = 2000;

    private static TranscriptKeeper shared;

    /** The process's one keeper: a read thread and a disk thread of its own, low priority. */
    static synchronized TranscriptKeeper shared(android.content.Context context) {
        if (shared == null) {
            shared = new TranscriptKeeper(Transcripts.shared(context), thread("herdr-transcripts-disk"),
                    thread("herdr-transcripts-read"), RemoteNative::readTranscript,
                    android.os.SystemClock::elapsedRealtime, System::currentTimeMillis);
        }
        return shared;
    }

    private static Executor thread(String name) {
        return java.util.concurrent.Executors.newSingleThreadExecutor(task -> {
            Thread worker = new Thread(task, name);
            worker.setDaemon(true);
            worker.setPriority(Thread.MIN_PRIORITY);
            return worker;
        });
    }

    private final Transcripts store;
    private final Executor disk;
    private final Executor reader;
    private final Wire wire;
    private final LongSupplier elapsed;
    private final LongSupplier wall;
    private final TranscriptPlanner planner = new TranscriptPlanner();
    /** What the latest list of the current link names: address to incarnation. Under this. */
    private final Map<String, String> listed = new HashMap<>();
    private long listedToken;

    TranscriptKeeper(Transcripts store, Executor disk, Executor reader, Wire wire, LongSupplier elapsed,
            LongSupplier wall) {
        this.store = store;
        this.disk = disk;
        this.reader = reader;
        this.wire = wire;
        this.elapsed = elapsed;
        this.wall = wall;
    }

    /** One pane of a poll's list as the planner sees it. */
    static List<TranscriptPlanner.Pane> panes(JSONArray list) {
        List<TranscriptPlanner.Pane> out = new ArrayList<>();
        for (int i = 0; list != null && i < list.length(); i++) {
            JSONObject p = list.optJSONObject(i);
            if (p == null) continue;
            long created = p.optLong("created_ms", 0);
            String incarnation = p.isNull("incarnation") ? null : p.optString("incarnation", null);
            out.add(new TranscriptPlanner.Pane(p.optString("id", ""), created > 0 ? created : null, incarnation,
                    !p.optString("kind", "").isEmpty()));
        }
        return out;
    }

    /**
     * A direct link's list for {@code machine}, on the link {@code token}: it is what proves a
     * transcript offline, so it is saved as the machine's last list.
     */
    void listed(MachineKey machine, long token, JSONArray list) {
        if (machine == null) return;
        Map<String, Transcripts.Seen> seen = new HashMap<>();
        synchronized (this) {
            listed.clear();
            listedToken = token;
            for (TranscriptPlanner.Pane p : panes(list)) {
                if (!TranscriptKey.plainAddress(p.address)) continue;
                listed.put(p.address, p.incarnation);
                seen.put(p.address, new Transcripts.Seen(p.createdMs, p.incarnation));
            }
        }
        long asOf = wall.getAsLong();
        disk.execute(() -> store.observe(machine, seen, asOf));
    }

    /**
     * Whether a poll may publish its connection's list as transcript proof ({@link #listed}):
     * ready ({@code selected}), its machine bound by this connection's hello, the list its own
     * (a resumed connection starts with the previous one's, which must never delete the current
     * copies), its hello's features known, and a list revision not yet published.
     */
    static boolean mayPublish(boolean selected, boolean bound, boolean ownList, boolean featuresKnown,
            long publishedRevision, long revision) {
        return selected && bound && ownList && featuresKnown && publishedRevision != revision;
    }

    /** What one poll of a live link knows, for {@link #tick}. */
    static final class Link {
        final long handle, token;
        final MachineKey machine;
        final boolean supported, metered;
        final String onScreen;
        final long output;
        final JSONArray panes;

        Link(long handle, long token, MachineKey machine, boolean supported, boolean metered, String onScreen,
                long output, JSONArray panes) {
            this.handle = handle;
            this.token = token;
            this.machine = machine;
            this.supported = supported;
            this.metered = metered;
            this.onScreen = onScreen == null ? "" : onScreen;
            this.output = output;
            this.panes = panes;
        }
    }

    /** A poll of a live link: starts the next read, if one is due. Poll thread; returns at once. */
    void tick(Link link) {
        List<TranscriptPlanner.Pane> panes = panes(link.panes);
        String address;
        synchronized (this) {
            address = planner.next(link.supported, link.machine != null, link.metered, link.onScreen, link.output,
                    panes, elapsed.getAsLong());
        }
        if (address == null) return;
        TranscriptPlanner.Pane pane = null;
        for (TranscriptPlanner.Pane p : panes) if (p.address.equals(address)) pane = p;
        Long created = pane == null ? null : pane.createdMs;
        reader.execute(() -> {
            try {
                read(link, address, created);
            } finally {
                synchronized (this) {
                    planner.finished(address, elapsed.getAsLong());
                }
            }
        });
    }

    private void read(Link link, String address, Long created) {
        String reply;
        try {
            reply = wire.read(link.handle, address, LINES, Transcripts.MAX_TEXT_BYTES);
        } catch (RuntimeException refused) {
            return;
        }
        if (reply == null) return;
        JSONObject answer;
        try {
            answer = new JSONObject(reply);
        } catch (JSONException unreadable) {
            return;
        }
        if (!answer.optBoolean("supported", false) || answer.has("error")) return;
        String incarnation = answer.isNull("incarnation") ? null : answer.optString("incarnation", null);
        String listedNow;
        long tokenNow;
        synchronized (this) {
            listedNow = listed.get(address);
            tokenNow = listedToken;
        }
        // Kept only for the link still current and the program its list names now.
        if (!TranscriptPlanner.keep(incarnation, listedNow, link.token, tokenNow)) return;
        TranscriptKey key = TranscriptKey.of(link.machine, address, created, incarnation);
        if (key == null) return;
        String text = answer.optString("text", "");
        boolean truncated = answer.optBoolean("truncated", false);
        // An answer that does not say is not claimed as history.
        boolean screen = answer.optBoolean("screen", true);
        long asOf = wall.getAsLong();
        disk.execute(() -> store.save(key, text, truncated, screen, asOf));
    }

    /** The proven transcript of {@code address} on {@code machine}, given to {@code then} (on the disk thread). */
    void offline(MachineKey machine, String address, Consumer<Transcripts.Transcript> then) {
        disk.execute(() -> then.accept(store.proven(machine, address)));
    }
}
