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

    /** What a refused binding collides with: another revision's binding, or a held message. */
    enum Kind { BINDING, HELD }

    /** What a revision's hello established. */
    static final class Binding {
        final String profile, revision, session, name;
        final MachineKey machine;
        /** The profile whose binding this one collides with, or null when it is bound. */
        final String refusedBy;
        /** For a refused binding: the colliding session ("" when it was saved without one), and its kind. */
        final String refusedSession;
        final Kind refusedKind;
        /** Bound because the person re-confirmed it ({@link #reconfirm}): a later hello keeps it. */
        final boolean confirmed;

        Binding(String profile, String revision, String session, String name, MachineKey machine, String refusedBy) {
            this(profile, revision, session, name, machine, refusedBy, null, null, false);
        }

        Binding(String profile, String revision, String session, String name, MachineKey machine, String refusedBy,
                String refusedSession, Kind refusedKind, boolean confirmed) {
            this.profile = profile;
            this.revision = revision;
            this.session = session;
            this.name = name;
            this.machine = machine;
            this.refusedBy = refusedBy;
            this.refusedSession = refusedBy == null ? null : refusedSession == null ? "" : refusedSession;
            this.refusedKind = refusedBy == null ? null : refusedKind == null ? Kind.BINDING : refusedKind;
            this.confirmed = refusedBy == null && confirmed;
        }

        boolean bound() {
            return refusedBy == null;
        }
    }

    /**
     * The refused banner (Wolf's Fold, 11-24): which saved computer or held message this one
     * collides with, its session, and the machine's core id and host key, shortened, with the way
     * out. Null for a bound binding.
     */
    static String refusal(Binding b) {
        if (b == null || b.bound()) return null;
        String with = b.refusedKind == Kind.HELD ? "a message held for " + b.refusedBy : "saved computer " + b.refusedBy;
        return "This computer reports the same Herdr as " + b.refusedBy + " (" + with + ", session \""
                + b.refusedSession + "\"; core " + prefix(b.machine.core) + ", host key " + prefix(hostKey(b.machine))
                + "); it works live only. ⋮ › Re-confirm this computer";
    }

    private static String hostKey(MachineKey machine) {
        String key = machine.hostKey;
        int colon = key.indexOf(':');
        return colon >= 0 ? key.substring(colon + 1) : key;
    }

    private static String prefix(String value) {
        return value.length() <= 8 ? value : value.substring(0, 8);
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
                Kind kind = "held".equals(item.optString("refused_kind", "")) ? Kind.HELD : Kind.BINDING;
                bindings.add(new Binding(profile, revision, item.optString("session", ""), item.optString("name", ""),
                        machine, refused.isEmpty() ? null : refused, item.optString("refused_session", ""), kind,
                        item.optBoolean("confirmed", false)));
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
        return bind(profile, revision, session, name, machine, java.util.Collections.emptyList());
    }

    /**
     * As {@link #bind(String, String, String, String, MachineKey)}, with the held messages: a
     * machine a held message was typed for in another session stays that session's even when its
     * binding has aged out of this store, and a binding a held message refers to is never evicted.
     */
    synchronized Binding bind(String profile, String revision, String session, String name, MachineKey machine,
            java.util.Collection<Outbox.Message> held) {
        if (profile == null || profile.isEmpty() || revision == null || revision.isEmpty() || machine == null) {
            return null;
        }
        String ownSession = session == null ? "" : session;
        String ownName = name == null ? "" : name;
        Binding previous = find(profile, revision);
        // Re-confirmed by the person for this very machine and session: kept, not asked again.
        if (previous != null && previous.confirmed && previous.machine.equals(machine)
                && previous.session.equals(ownSession)) {
            return previous;
        }
        Binding next = collision(profile, revision, ownSession, ownName, machine, held, null);
        if (next == null) next = new Binding(profile, revision, ownSession, ownName, machine, null);
        if (previous != null && same(previous, next)) return previous;
        return record(previous, next, held);
    }

    /**
     * The person's "Re-confirm this computer", from the live hello of {@code profile} at
     * {@code revision}: binds it to {@code machine} when everything it collides with is stale,
     * that is a binding of a revision that is no longer its profile's saved one ({@code current}
     * says which are), or a binding or held message saved with no session. A real duplicate (a
     * saved profile, or a held message, of another named session on this machine) stays refused.
     * The stale bindings are kept as they are and no held message changes: those of another
     * session stay that session's (Outbox's lanes and observe go by session).
     */
    synchronized Binding reconfirm(String profile, String revision, String session, String name, MachineKey machine,
            java.util.Collection<Outbox.Message> held, java.util.function.BiPredicate<String, String> current) {
        if (profile == null || profile.isEmpty() || revision == null || revision.isEmpty() || machine == null
                || current == null) {
            return null;
        }
        String ownSession = session == null ? "" : session;
        String ownName = name == null ? "" : name;
        Binding previous = find(profile, revision);
        Binding real = collision(profile, revision, ownSession, ownName, machine, held, current);
        if (real != null) return previous != null && !previous.bound() ? previous : real;
        Binding next = new Binding(profile, revision, ownSession, ownName, machine, null, null, null, true);
        return record(previous, next, held);
    }

    /**
     * The first collision of a hello, as a refused binding, or null. With {@code current}, only a
     * real one: stale bindings and sessionless entries are let through (see {@link #reconfirm}).
     */
    private Binding collision(String profile, String revision, String ownSession, String ownName, MachineKey machine,
            java.util.Collection<Outbox.Message> held, java.util.function.BiPredicate<String, String> current) {
        for (Binding other : bindings) {
            if (other.profile.equals(profile) && other.revision.equals(revision)) continue;
            if (!other.bound() || !other.machine.equals(machine) || other.session.equals(ownSession)) continue;
            if (current != null && (other.session.isEmpty() || ownSession.isEmpty()
                    || !current.test(other.profile, other.revision))) {
                continue;
            }
            String by = other.name.isEmpty() ? "another saved computer" : other.name;
            return new Binding(profile, revision, ownSession, ownName, machine, by, other.session, Kind.BINDING, false);
        }
        for (Outbox.Message m : held) {
            if (!machine.equals(m.target.machine) || m.target.session.equals(ownSession)) continue;
            if (current != null && (m.target.session.isEmpty() || ownSession.isEmpty())) continue;
            String by = m.target.profileName.isEmpty() ? "another saved computer" : m.target.profileName;
            return new Binding(profile, revision, ownSession, ownName, machine, by, m.target.session, Kind.HELD, false);
        }
        return null;
    }

    /** Records {@code next} in place of {@code previous}, evicts the oldest beyond the cap, saves. */
    private Binding record(Binding previous, Binding next, java.util.Collection<Outbox.Message> held) {
        if (previous != null) bindings.remove(previous);
        bindings.add(next);
        java.util.Set<MachineKey> referenced = new java.util.HashSet<>();
        for (Outbox.Message m : held) if (m.target.machine != null) referenced.add(m.target.machine);
        // The oldest go first, never one a held message refers to (at most one per message).
        for (java.util.Iterator<Binding> it = bindings.iterator(); bindings.size() > MAX_BINDINGS && it.hasNext(); ) {
            Binding old = it.next();
            if (old != next && !referenced.contains(old.machine)) it.remove();
        }
        try {
            Outbox.write(file, encode());
        } catch (IOException | JSONException unsaved) {
            // Held in memory: a restart asks the machine again.
        }
        return next;
    }

    private static boolean same(Binding a, Binding b) {
        return a.machine.equals(b.machine) && a.session.equals(b.session) && a.name.equals(b.name)
                && java.util.Objects.equals(a.refusedBy, b.refusedBy)
                && java.util.Objects.equals(a.refusedSession, b.refusedSession) && a.refusedKind == b.refusedKind
                && a.confirmed == b.confirmed;
    }

    /**
     * Whether a hello may be bound now: only once the held messages are read, so a session's
     * ownership of a machine is known before another session can claim it.
     */
    static boolean mayBind(Outbox held) {
        return held != null && held.loaded();
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
            if (b.refusedBy != null) {
                item.put("refused_by", b.refusedBy).put("refused_session", b.refusedSession)
                        .put("refused_kind", b.refusedKind == Kind.HELD ? "held" : "binding");
            }
            if (b.confirmed) item.put("confirmed", true);
            list.put(item);
        }
        return new JSONObject().put("v", 1).put("bindings", list).toString();
    }
}
