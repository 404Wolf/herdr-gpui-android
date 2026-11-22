package dev.herdr.android;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.function.LongSupplier;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Pinned agents, for switching quickly between a few agents on different machines
 * (factory-9zz.2). A pin names one terminal on one machine, as the phone can prove it: the
 * machine ({@link MachineKey}: core id and SSH host key), the terminal's address and its
 * {@code created_ms}. An address the core reuses for a new terminal is another terminal: the pin
 * ends and never follows it. The program inside may restart (a new incarnation); the pin stays,
 * marked restarted until it is next opened.
 *
 * <p>A pin's state comes only from what the phone already knows: a list a direct connection to
 * that machine proved ({@link #observe}), and what was saved from the last one. Nothing here
 * polls, connects or schedules anything but its own disk writes.
 *
 * <p>Kept in {@link #FILE_NAME} in the no-backup directory, bounded ({@link #MAX_PINS},
 * {@link #MAX_BYTES}). A damaged file, or one written by a newer version, gives no pins and is not
 * overwritten until the user pins or unpins again; it is then kept beside as {@code .bad}.
 * Every public method is synchronized; disk writes go to the store's own executor.
 */
final class Pins {
    static final String FILE_NAME = "pins-v1.json";
    static final int VERSION = 1;
    /** The drawer's section stays compact: past this, pinning asks to unpin one first. */
    static final int MAX_PINS = 12;
    /** A larger file is not one this version wrote: ignored, like a damaged one. */
    static final int MAX_BYTES = 64 * 1024;
    /** Display strings (a label, a machine's name) are cut to this. */
    static final int MAX_TEXT = 64;

    /** Which terminal a pin names. A relayed address (machine/t7) proves nothing and is refused. */
    static final class Key {
        final MachineKey machine;
        final String address;
        final long createdMs;

        private Key(MachineKey machine, String address, long createdMs) {
            this.machine = machine;
            this.address = address;
            this.createdMs = createdMs;
        }

        /** The key, or null when the machine is unproven, the address relayed or the creation time unknown. */
        static Key of(MachineKey machine, String address, Long createdMs) {
            if (machine == null || createdMs == null || createdMs <= 0 || !TranscriptKey.plainAddress(address)) {
                return null;
            }
            return new Key(machine, address, createdMs);
        }

        @Override public boolean equals(Object other) {
            if (!(other instanceof Key)) return false;
            Key that = (Key) other;
            return machine.equals(that.machine) && address.equals(that.address) && createdMs == that.createdMs;
        }

        @Override public int hashCode() {
            return (machine.hashCode() * 31 + address.hashCode()) * 31 + Long.hashCode(createdMs);
        }
    }

    /** What the last proven list of the pin's machine said about it. */
    enum Seen { LISTED, GONE, REPLACED }

    /** One pin as kept. Immutable: a change makes a new one. */
    static final class Pin {
        final Key key;
        /** The program the user last opened it with; null when its core did not say. */
        final String incarnation;
        /** The program the last proven list showed in it; null when unknown. */
        final String seenIncarnation;
        /** The profile it was pinned from (a hint: the machine decides), its session. */
        final String profile, session;
        /** Display caches: the agent's name and the machine's, both bounded. */
        final String label, machineName;
        final Seen seen;
        /** The agent's status in that list ("working", "blocked", ...), or "". */
        final String status;
        /** When that list was seen, and when the pin was made (wall clock, ms). */
        final long asOfMs, pinnedMs;
        Pin(Key key, String incarnation, String seenIncarnation, String profile, String session, String label,
                String machineName, Seen seen, String status, long asOfMs, long pinnedMs) {
            this.key = key;
            this.incarnation = incarnation;
            this.seenIncarnation = seenIncarnation;
            this.profile = bounded(profile);
            this.session = bounded(session);
            this.label = bounded(label);
            this.machineName = bounded(machineName);
            this.seen = seen;
            this.status = bounded(status);
            this.asOfMs = asOfMs;
            this.pinnedMs = pinnedMs;
        }

        boolean ended() {
            return seen != Seen.LISTED;
        }

        /** Another program started in the terminal since the user last opened the pin. */
        boolean restarted() {
            return incarnation != null && seenIncarnation != null && !incarnation.equals(seenIncarnation);
        }

        Pin seen(Seen seen, String status, String seenIncarnation, long asOfMs) {
            return new Pin(key, incarnation, seenIncarnation, profile, session, label, machineName, seen, status,
                    asOfMs, pinnedMs);
        }

        Pin opened() {
            return new Pin(key, seenIncarnation != null ? seenIncarnation : incarnation, seenIncarnation, profile,
                    session, label, machineName, seen, status, asOfMs, pinnedMs);
        }
    }

    /** What a toggle did. */
    enum Toggle { PINNED, UNPINNED, FULL, NOT_LOADED, INVALID }

    /** A pin's row state, first match wins: see {@link #state}. */
    enum State { ENDED, UNAVAILABLE, LIVE, RECONNECTING, OFFLINE }

    /** What a just-opened connection's first list says about the pin it was opened for. */
    enum Verify { GO, ENDED, WRONG_MACHINE, NOT_LISTED_YET }

    private static Pins shared;

    /** The process's one store, read from disk on a thread of its own. */
    static synchronized Pins shared(android.content.Context context) {
        if (shared == null) {
            Executor disk = java.util.concurrent.Executors.newSingleThreadExecutor(task -> {
                Thread thread = new Thread(task, "herdr-pins");
                thread.setDaemon(true);
                return thread;
            });
            Pins made = new Pins(new File(context.getApplicationContext().getNoBackupFilesDir(), FILE_NAME), disk,
                    System::currentTimeMillis);
            disk.execute(made::load);
            shared = made;
        }
        return shared;
    }

    private final File file;
    private final Executor disk;
    private final LongSupplier clock;
    private final List<Pin> pins = new ArrayList<>();
    private boolean loaded;
    /** The file on disk is not ours to overwrite (damaged, too large, or a newer version). */
    private boolean foreign;

    Pins(File file, Executor disk, LongSupplier clock) {
        this.file = file;
        this.disk = disk;
        this.clock = clock;
    }

    /** Reads the file. Until this ran, nothing is pinnable or observed (no state is lost to a race). */
    synchronized void load() {
        pins.clear();
        foreign = false;
        try {
            if (!file.isFile()) return;
            if (file.length() > MAX_BYTES) {
                foreign = true;
                return;
            }
            String text = Outbox.read(file);
            if (text == null) {
                foreign = true;
                return;
            }
            JSONObject root = new JSONObject(text);
            if (root.optInt("v", -1) != VERSION) {
                foreign = true;
                return;
            }
            JSONArray list = root.optJSONArray("pins");
            for (int i = 0; list != null && i < list.length() && pins.size() < MAX_PINS; i++) {
                Pin pin = parse(list.optJSONObject(i));
                if (pin != null && find(pin.key) < 0) pins.add(pin);
            }
        } catch (JSONException damaged) {
            pins.clear();
            foreign = true;
        } finally {
            loaded = true;
        }
    }

    synchronized boolean loaded() {
        return loaded;
    }

    /** The pins, oldest first (the drawer's order). */
    synchronized List<Pin> all() {
        return Collections.unmodifiableList(new ArrayList<>(pins));
    }

    synchronized boolean pinned(Key key) {
        return key != null && find(key) >= 0;
    }

    /** The pin for this exact terminal, or null. */
    synchronized Pin get(Key key) {
        int at = key == null ? -1 : find(key);
        return at < 0 ? null : pins.get(at);
    }

    /**
     * Pins or unpins the agent on screen. {@code key} must come from the connection on screen,
     * bound to its machine; {@code incarnation} is what that list says runs in it.
     */
    synchronized Toggle toggle(Key key, String incarnation, String profile, String session, String label,
            String machineName, String status) {
        if (!loaded) return Toggle.NOT_LOADED;
        if (key == null) return Toggle.INVALID;
        int at = find(key);
        if (at >= 0) {
            pins.remove(at);
            save();
            return Toggle.UNPINNED;
        }
        if (pins.size() >= MAX_PINS) return Toggle.FULL;
        long now = clock.getAsLong();
        String program = plainIncarnation(incarnation);
        pins.add(new Pin(key, program, program, profile, session, label, machineName, Seen.LISTED, status, now, now));
        save();
        return Toggle.PINNED;
    }

    /** Removes a pin whatever its state (the row's Unpin, also for an ended or unavailable one). */
    synchronized boolean unpin(Key key) {
        int at = key == null || !loaded ? -1 : find(key);
        if (at < 0) return false;
        pins.remove(at);
        save();
        return true;
    }

    /**
     * A list that a direct connection proved to come from {@code machine} (the connection on
     * screen, or a mirror whose own hello names it). Updates every pin of that machine: listed
     * with the same creation time stays listed (a new incarnation marks it restarted); missing,
     * or listed with another creation time, ends it for good. Lists from a cache or a stand-in
     * must never come here.
     */
    synchronized void observe(MachineKey machine, JSONArray panes) {
        if (!loaded || machine == null || panes == null) return;
        long now = clock.getAsLong();
        boolean changed = false;
        for (int i = 0; i < pins.size(); i++) {
            Pin pin = pins.get(i);
            if (!pin.key.machine.equals(machine) || pin.ended()) continue;
            JSONObject item = listed(panes, pin.key.address);
            Pin next;
            if (item == null) {
                next = pin.seen(Seen.GONE, "", pin.seenIncarnation, now);
            } else if (item.optLong("created_ms", 0) != pin.key.createdMs) {
                next = pin.seen(Seen.REPLACED, "", pin.seenIncarnation, now);
            } else {
                String program = plainIncarnation(item.isNull("incarnation") ? null : item.optString("incarnation", null));
                next = pin.seen(Seen.LISTED, item.optString("status", ""),
                        program != null ? program : pin.seenIncarnation, now);
            }
            // In memory the as-of time always advances; the file is rewritten only when what the
            // pin says changed (after a restart it may show an older, never a newer, time).
            if (!same(pin, next)) changed = true;
            pins.set(i, next);
        }
        if (changed) save();
    }

    /** The pin was opened: a restart it showed is acknowledged. */
    synchronized void opened(Key key) {
        int at = key == null || !loaded ? -1 : find(key);
        if (at < 0 || !pins.get(at).restarted()) return;
        pins.set(at, pins.get(at).opened());
        save();
    }
    // ---- pure rules ----

    /**
     * A just-opened connection's first list, for the pin it was opened for: its hello must name
     * the pin's machine, and the list must hold the same terminal. Never fall back to another
     * terminal (the Deacon) when this is not GO.
     */
    static Verify verify(Key pin, String bootId, String fingerprint, JSONArray panes) {
        if (pin == null) return Verify.WRONG_MACHINE;
        if (!pin.machine.equals(MachineKey.of(bootId, fingerprint))) return Verify.WRONG_MACHINE;
        if (panes == null) return Verify.NOT_LISTED_YET;
        JSONObject item = listed(panes, pin.address);
        if (item == null) return Verify.ENDED;
        return item.optLong("created_ms", 0) == pin.createdMs ? Verify.GO : Verify.ENDED;
    }

    /** What the phone knows right now, from the connection on screen and its rail mirrors. */
    static final class Context {
        /** The machine on screen, and whether that connection is ready and open. */
        MachineKey onScreen;
        boolean screenLive;
        /** The connection on screen is down and reconnecting. */
        boolean reconnecting;
        /** Pins whose machine has a connected, proven mirror that lists them now. */
        java.util.Set<Key> mirrored = Collections.emptySet();
        /** Pins that resolve to no saved profile, or only to a refused or untrusted one. */
        java.util.Set<Key> unresolved = Collections.emptySet();
    }

    static State state(Pin pin, Context now) {
        if (pin.ended()) return State.ENDED;
        if (now.unresolved.contains(pin.key)) return State.UNAVAILABLE;
        boolean onScreen = pin.key.machine.equals(now.onScreen);
        if (onScreen && now.screenLive) return State.LIVE;
        if (!onScreen && now.mirrored.contains(pin.key)) return State.LIVE;
        if (onScreen && now.reconnecting) return State.RECONNECTING;
        return State.OFFLINE;
    }

    /** One saved profile the pin could be opened through. */
    static final class Candidate {
        final String profile;
        /** The machine its current revision is bound to, or null when unbound (an edited profile). */
        final MachineKey bound;
        final boolean refused, trusted;

        Candidate(String profile, MachineKey bound, boolean refused, boolean trusted) {
            this.profile = profile;
            this.bound = bound;
            this.refused = refused;
            this.trusted = trusted;
        }
    }

    /** The profile to open a pin through, and whether its hello must still prove the machine. */
    static final class Route {
        final String profile;
        final boolean mustVerify;

        Route(String profile, boolean mustVerify) {
            this.profile = profile;
            this.mustVerify = mustVerify;
        }
    }

    /**
     * Prefers the profile the pin was made from when it is still bound to the pin's machine, then
     * any profile bound to it (in saved order), then the hint itself if its revision is unbound
     * (edited: the hello decides). Null: nothing reaches the machine (unavailable).
     */
    static Route resolve(Pin pin, List<Candidate> saved) {
        Candidate hint = null;
        for (Candidate c : saved) if (c.profile.equals(pin.profile)) hint = c;
        if (usable(hint) && pin.key.machine.equals(hint.bound)) return new Route(hint.profile, false);
        for (Candidate c : saved) {
            if (usable(c) && pin.key.machine.equals(c.bound)) return new Route(c.profile, false);
        }
        if (usable(hint) && hint.bound == null) return new Route(hint.profile, true);
        return null;
    }

    private static boolean usable(Candidate c) {
        return c != null && c.trusted && !c.refused;
    }

    // ---- helpers ----

    private static JSONObject listed(JSONArray panes, String address) {
        for (int i = 0; i < panes.length(); i++) {
            JSONObject item = panes.optJSONObject(i);
            if (item != null && address.equals(item.optString("id", ""))) return item;
        }
        return null;
    }

    private int find(Key key) {
        for (int i = 0; i < pins.size(); i++) if (pins.get(i).key.equals(key)) return i;
        return -1;
    }

    private static String plainIncarnation(String value) {
        return TranscriptKey.plainIncarnation(value) ? value : null;
    }

    private static String bounded(String value) {
        if (value == null) return "";
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < value.length() && out.length() < MAX_TEXT; i++) {
            char c = value.charAt(i);
            if (!Character.isISOControl(c)) out.append(c);
        }
        return out.toString();
    }

    /** Only a change of what the pin says is written; a newer as-of time alone is not worth a write. */
    private static boolean same(Pin a, Pin b) {
        return a.seen == b.seen && a.status.equals(b.status)
                && java.util.Objects.equals(a.seenIncarnation, b.seenIncarnation);
    }

    private void save() {
        final String text;
        try {
            JSONArray list = new JSONArray();
            for (Pin pin : pins) list.put(json(pin));
            text = new JSONObject().put("v", VERSION).put("pins", list).toString();
        } catch (JSONException impossible) {
            return;
        }
        final boolean keepForeign = foreign;
        foreign = false;
        disk.execute(() -> {
            try {
                if (keepForeign && file.isFile()) {
                    File bad = new File(file.getPath() + ".bad");
                    if (bad.exists()) bad.delete();
                    if (!file.renameTo(bad)) return;
                }
                Outbox.write(file, text);
            } catch (IOException unsaved) {
                android.util.Log.w("herdr-pins", "pins not saved: " + unsaved.getMessage());
            }
        });
    }

    private static JSONObject json(Pin pin) throws JSONException {
        JSONObject seen = new JSONObject().put("state", pin.seen.name().toLowerCase(java.util.Locale.ROOT))
                .put("status", pin.status).put("as_of_ms", pin.asOfMs);
        JSONObject out = new JSONObject().put("machine", pin.key.machine.json()).put("address", pin.key.address)
                .put("created_ms", pin.key.createdMs)
                .put("incarnation", pin.incarnation == null ? JSONObject.NULL : pin.incarnation)
                .put("seen_incarnation", pin.seenIncarnation == null ? JSONObject.NULL : pin.seenIncarnation)
                .put("profile", pin.profile).put("session", pin.session).put("label", pin.label)
                .put("machine_name", pin.machineName).put("seen", seen).put("pinned_ms", pin.pinnedMs);
        return out;
    }

    private static Pin parse(JSONObject item) {
        if (item == null) return null;
        Key key = Key.of(MachineKey.from(item.optJSONObject("machine")), item.optString("address", ""),
                item.optLong("created_ms", 0));
        if (key == null) return null;
        JSONObject seen = item.optJSONObject("seen");
        Seen state;
        switch (seen == null ? "" : seen.optString("state", "")) {
            case "listed": state = Seen.LISTED; break;
            case "gone": state = Seen.GONE; break;
            case "replaced": state = Seen.REPLACED; break;
            default: return null;
        }
        return new Pin(key, incarnationOf(item, "incarnation"), incarnationOf(item, "seen_incarnation"),
                item.optString("profile", ""), item.optString("session", ""), item.optString("label", ""),
                item.optString("machine_name", ""), state, seen.optString("status", ""), seen.optLong("as_of_ms", 0),
                item.optLong("pinned_ms", 0));
    }

    private static String incarnationOf(JSONObject item, String field) {
        return item.isNull(field) ? null : plainIncarnation(item.optString(field, null));
    }
}
