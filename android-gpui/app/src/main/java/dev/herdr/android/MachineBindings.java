package dev.herdr.android;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Which machine each saved profile revision reaches, as proven by a {@code hello} on a direct
 * connection built from exactly that revision (an identity edit makes a new revision, which is
 * unbound until its own hello). An unbound revision can queue nothing and is offered nothing.
 *
 * <p>Two profiles that reach the same machine in the same session share it. A profile with
 * another session that reports a machine already bound (core state copied into a second session
 * on one host) is refused: that profile works live only, and says which profile it collides with.
 *
 * <p>Pure state plus a small file; every public method is synchronized. Disk work happens in
 * {@link #load} and {@link #bind}, which callers run off the main thread.
 */
final class MachineBindings {
    static final String FILE_NAME = "machine-bindings-v1.json";
    /** Revisions remembered; the oldest binding goes first beyond this. */
    static final int MAX_BINDINGS = 64;

    /** What a revision's hello established. */
    static final class Binding {
        final String profile, revision, session, name;
        final MachineKey machine;
        /** The profile whose binding this one collides with, or null when it is bound. */
        final String refusedBy;

        Binding(String profile, String revision, String session, String name, MachineKey machine, String refusedBy) {
            this.profile = profile;
            this.revision = revision;
            this.session = session;
            this.name = name;
            this.machine = machine;
            this.refusedBy = refusedBy;
        }

        boolean bound() {
            return refusedBy == null;
        }
    }

    private static MachineBindings shared;

    /** The process's one record, read from disk on a thread of its own. */
    static synchronized MachineBindings shared(android.content.Context context) {
        if (shared == null) {
            MachineBindings made = new MachineBindings(
                    new File(context.getApplicationContext().getNoBackupFilesDir(), FILE_NAME));
            Thread reader = new Thread(made::load, "herdr-bindings");
            reader.setDaemon(true);
            reader.start();
            shared = made;
        }
        return shared;
    }

    private final File file;
    private final List<Binding> bindings = new ArrayList<>();

    MachineBindings(File file) {
        this.file = file;
    }

    /** Reads the file; a missing or damaged one leaves nothing bound. */
    synchronized void load() {
        bindings.clear();
        String text = Outbox.read(file);
        if (text == null) return;
        try {
            JSONArray list = new JSONObject(text).optJSONArray("bindings");
            for (int i = 0; list != null && i < list.length() && bindings.size() < MAX_BINDINGS; i++) {
                JSONObject item = list.optJSONObject(i);
                if (item == null) continue;
                MachineKey machine = MachineKey.from(item.optJSONObject("machine"));
                String profile = item.optString("profile", "");
                String revision = item.optString("revision", "");
                if (machine == null || profile.isEmpty() || revision.isEmpty()) continue;
                String refused = item.optString("refused_by", "");
                bindings.add(new Binding(profile, revision, item.optString("session", ""), item.optString("name", ""),
                        machine, refused.isEmpty() ? null : refused));
            }
        } catch (JSONException damaged) {
            bindings.clear();
        }
    }

    /**
     * A hello on a direct connection from {@code profile} at {@code revision} reported
     * {@code machine}. Returns what is now recorded: bound, or refused for a collision. Saved
     * before it returns; a failed save still answers (the binding holds until the app restarts).
     */
    synchronized Binding bind(String profile, String revision, String session, String name, MachineKey machine) {
        if (profile == null || profile.isEmpty() || revision == null || revision.isEmpty() || machine == null) {
            return null;
        }
        String ownSession = session == null ? "" : session;
        String ownName = name == null ? "" : name;
        String refusedBy = null;
        for (Binding other : bindings) {
            if (other.profile.equals(profile) && other.revision.equals(revision)) continue;
            if (other.bound() && other.machine.equals(machine) && !other.session.equals(ownSession)) {
                refusedBy = other.name.isEmpty() ? "another saved computer" : other.name;
                break;
            }
        }
        Binding next = new Binding(profile, revision, ownSession, ownName, machine, refusedBy);
        Binding previous = find(profile, revision);
        if (previous != null && same(previous, next)) return previous;
        if (previous != null) bindings.remove(previous);
        bindings.add(next);
        while (bindings.size() > MAX_BINDINGS) bindings.remove(0);
        try {
            Outbox.write(file, encode());
        } catch (IOException | JSONException unsaved) {
            // Held in memory: a restart asks the machine again.
        }
        return next;
    }

    private static boolean same(Binding a, Binding b) {
        return a.machine.equals(b.machine) && a.session.equals(b.session) && a.name.equals(b.name)
                && java.util.Objects.equals(a.refusedBy, b.refusedBy);
    }

    /** The machine {@code revision} of {@code profile} reaches, or null when unbound or refused. */
    synchronized MachineKey machine(String profile, String revision) {
        Binding binding = find(profile, revision);
        return binding == null || !binding.bound() ? null : binding.machine;
    }

    /** What is recorded for this revision, or null. */
    synchronized Binding binding(String profile, String revision) {
        return find(profile, revision);
    }

    private Binding find(String profile, String revision) {
        if (profile == null || revision == null) return null;
        for (Binding b : bindings) if (b.profile.equals(profile) && b.revision.equals(revision)) return b;
        return null;
    }

    private String encode() throws JSONException {
        JSONArray list = new JSONArray();
        for (Binding b : bindings) {
            JSONObject item = new JSONObject().put("profile", b.profile).put("revision", b.revision)
                    .put("session", b.session).put("name", b.name).put("machine", b.machine.json());
            if (b.refusedBy != null) item.put("refused_by", b.refusedBy);
            list.put(item);
        }
        return new JSONObject().put("v", 1).put("bindings", list).toString();
    }
}
