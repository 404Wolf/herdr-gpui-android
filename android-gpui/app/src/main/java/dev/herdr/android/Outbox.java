package dev.herdr.android;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Composer messages held on the phone (outbox v2, stage 1 of the offline design): kept on disk so
 * a dead link, a closed app or a killed process loses none, shown in one queue with their target
 * and age, and sent only by the user's tap in the live pane they were typed for. Nothing here
 * sends by itself, and nothing is ever reported as delivered: the best a send can say is "sent ·
 * check it arrived".
 *
 * <p>A message belongs to one lane: a terminal ({@code address}, its {@code created_ms}) on one
 * {@link MachineKey}. A lane sends one message at a time and only its head; a head that is being
 * sent or needs the user stops its lane. A message migrated from v1 has no proven machine: it is
 * offered in the pane with its old profile and pane id, saying its destination is not verified.
 *
 * <p>The send is the one moment a message can be lost or doubled. It is saved as
 * {@link State#SENDING} before a byte is written; if no answer comes, because the link or the
 * process died, it {@link State#NEEDS_YOU needs the user}: it may have been sent, or may sit
 * unsent in the input box. Only the user decides what happens next.
 *
 * <p>Pure state plus a file; every public method is synchronized. Disk work happens only in
 * {@link #load} and {@link #save}, which callers run off the main thread (the Send handoff's
 * one synchronous save excepted).
 */
final class Outbox {
    static final int MAX_MESSAGES = 64;
    static final int MAX_CHARS = 256 * 1024;
    /** One compose command's text, the most one send may carry (model.rs MAX_TEXT_BYTES). */
    static final int MAX_TEXT_BYTES = 65_536;
    /** A file past this is not parsed: the bounds keep a real one far smaller. */
    static final int MAX_FILE_BYTES = 4 * 1024 * 1024;
    /** How long a sent row keeps its text, for Copy and Send again. */
    static final long SENT_KEEP_MS = 60L * 60 * 1000;
    static final String FILE_NAME = "outbox-v2.json";
    /** The outbox before stage 1, migrated once into {@link #FILE_NAME}. */
    static final String V1_FILE_NAME = "outbox-v1.json";
    static final String MIGRATED_SUFFIX = ".migrated";

    enum State {
        /** Kept, not sent; goes only by the user's tap in its live pane. */
        PENDING("pending"),
        /** The attempt is saved and its one write is on its way. */
        SENDING("sending"),
        /** Every byte was written to the pane on screen. Not confirmed: the user checks. */
        SENT("sent"),
        /** In doubt, or cannot go on: only the user moves it. */
        NEEDS_YOU("needs_you");

        final String wire;

        State(String wire) {
            this.wire = wire;
        }

        /** Unknown words are in doubt: a state this build does not know might be a send in flight. */
        static State parse(String word) {
            for (State s : values()) if (s.wire.equals(word)) return s;
            return NEEDS_YOU;
        }
    }

    /** Why a message waits for the user. Stage 1 uses unsure, gone and unverified. */
    enum Reason {
        UNSURE("unsure"), GONE("gone"), RESTARTED("restarted"), CHANGED("changed"), STALE("stale"),
        CLOCK("clock"), UNVERIFIED("unverified");

        final String wire;

        Reason(String wire) {
            this.wire = wire;
        }

        static Reason parse(String word) {
            if (word == null || word.isEmpty() || "null".equals(word)) return null;
            for (Reason r : values()) if (r.wire.equals(word)) return r;
            return UNSURE;
        }
    }

    /** What one send did, as {@code RemoteNative.compose} reports it. */
    enum Outcome {
        /** Every byte left for the attach on screen. */
        WRITTEN,
        /** Nothing was written: the link or the attach was gone first. */
        UNWRITTEN,
        /** Some may have left; or no answer, or one this build does not know. */
        UNSURE;

        static Outcome parse(String word) {
            if ("written".equals(word)) return WRITTEN;
            if ("unwritten".equals(word)) return UNWRITTEN;
            return UNSURE;
        }
    }

    /** Where a message goes: the pane the user had picked, as it was when they typed. */
    static final class Target {
        /** Null only for a message migrated from v1. */
        final MachineKey machine;
        /** The saved profile it was typed through, and its name and session then. */
        final String profileId, profileName, session;
        /** The terminal: its address on that machine, and its {@code created_ms} when known. */
        final String address;
        final Long createdMs;
        /** The pane's name and its program's status report when typed ("" when none). */
        final String label, status;

        Target(MachineKey machine, String profileId, String profileName, String session, String address,
                Long createdMs, String label, String status) {
            this.machine = machine;
            this.profileId = nonNull(profileId);
            this.profileName = nonNull(profileName);
            this.session = nonNull(session);
            this.address = nonNull(address);
            this.createdMs = createdMs;
            this.label = nonNull(label);
            this.status = nonNull(status);
        }
    }

    /** The pane live on screen: attached over a direct link, first paint done. */
    static final class Live {
        final MachineKey machine;
        final String profileId, address;
        final Long createdMs;
        /** The Herdr session of the profile on screen; null where it does not matter (a view). */
        final String session;

        Live(MachineKey machine, String profileId, String address, Long createdMs) {
            this(machine, profileId, address, createdMs, null);
        }

        Live(MachineKey machine, String profileId, String address, Long createdMs, String session) {
            this.machine = machine;
            this.profileId = nonNull(profileId);
            this.address = nonNull(address);
            this.createdMs = createdMs;
            this.session = session;
        }
    }

    /** When a message was typed: wall time, and the phone's elapsed time on that boot. */
    static final class Composed {
        final long wallMs;
        /** {@code elapsedRealtime} when typed; -1 when not known (a migrated message). */
        final long elapsedMs;
        /** The phone's boot count when typed; -1 when not known. */
        final int boot;

        Composed(long wallMs, long elapsedMs, int boot) {
            this.wallMs = wallMs;
            this.elapsedMs = elapsedMs;
            this.boot = boot;
        }

        /**
         * How long ago it was typed, measured on the phone's own clock that no clock change moves;
         * -1 when that cannot be known (another boot, or no boot count).
         */
        long ageMs(long elapsedNow, int bootNow) {
            if (elapsedMs < 0 || boot < 0 || bootNow != boot || elapsedNow < elapsedMs) return -1;
            return elapsedNow - elapsedMs;
        }
    }

    /** An immutable view of one message, for the rows, the queue screen and the sender. */
    static final class Message {
        final String id;
        /** The profile id it was typed through: the draft owner domain of the composer. */
        final String host;
        /** Its terminal's address. */
        final String pane;
        final String text;
        /** Wall time it was typed. */
        final long created;
        final State state;
        final Reason reason;
        /** Saved: once a send was tried or decided on, a tap is always needed (gate G1). */
        final boolean tapRequired;
        /** It holds a dictation the desktop has not transcribed yet; it waits, still pending. */
        final boolean awaitsDictation;
        final Target target;
        final Composed composed;
        /** When it was written, for a sent row; 0 otherwise. */
        final long sentAt;
        /** What the phone knows, in words, for the row; "" when its state says it all. */
        final String known;
        /**
         * The user moved it aside while its dictation waits for a transcriber: it no longer holds
         * its lane, and is never offered until its text is in (then it takes its place again).
         */
        final boolean aside;

        Message(String id, Target target, String text, Composed composed, State state, Reason reason,
                boolean tapRequired, long sentAt, String known) {
            this(id, target, text, composed, state, reason, tapRequired, sentAt, known, false);
        }

        Message(String id, Target target, String text, Composed composed, State state, Reason reason,
                boolean tapRequired, long sentAt, String known, boolean aside) {
            this.aside = aside && DictationToken.in(text);
            this.id = id;
            this.target = target;
            this.host = target.profileId;
            this.pane = target.address;
            this.text = text;
            this.composed = composed;
            this.created = composed.wallMs;
            this.state = state;
            this.reason = reason;
            this.tapRequired = tapRequired;
            this.sentAt = sentAt;
            this.known = nonNull(known);
            this.awaitsDictation = DictationToken.in(text);
        }

        /** A message in a pane, for tests and views that need no target details. */
        Message(String id, String host, String pane, String text, State state) {
            this(id, host, pane, text, state, state == State.NEEDS_YOU ? Reason.UNSURE : null);
        }

        Message(String id, String host, String pane, String text, State state, Reason reason) {
            this(id, new Target(null, host, "", "", pane, null, "", ""), text, new Composed(1, -1, -1), state,
                    reason, false, 0, "");
        }

        /** Migrated from v1: nothing proves which machine it was for. */
        boolean unverified() {
            return target.machine == null;
        }
    }

    private static final class Entry {
        final String id;
        final Target target;
        String text;
        final Composed composed;
        State state;
        Reason reason;
        boolean tapRequired;
        /** The saved attempt: how it went out and exactly what was written. */
        String attemptMode;
        long attemptAt;
        String attemptText;
        long sentAt;
        String known = "";
        /**
         * Saved: the user cleared it and may still undo until this epoch time. A cleared message
         * is never sent, shown or counted; it goes for good after the window.
         */
        long clearedUntil;
        /** Saved: moved aside by the user (see {@link Message#aside}). */
        boolean aside;

        /** Aside counts only while its dictation still waits: once filled, it is in line again. */
        boolean waitsAside() {
            return aside && DictationToken.in(text);
        }

        Entry(String id, Target target, String text, Composed composed, State state) {
            this.id = id;
            this.target = target;
            this.text = text;
            this.composed = composed;
            this.state = state;
        }

        Message view() {
            return new Message(id, target, text, composed, state, reason, tapRequired, sentAt, known, waitsAside());
        }

        /** Sent, and still keeping its text for Copy and Send again; out of every lane's way. */
        boolean settled() {
            return state == State.SENT;
        }
    }

    private final File file;
    private final List<Entry> entries = new ArrayList<>();
    /**
     * Ids of messages that were held and have left (sent receipts past their hour, messages
     * typed at once, dropped or taken back), saved: a Send whose draft survived a stop is known
     * to have been held after its row is gone, so its draft is never offered again as unsent.
     */
    private final java.util.LinkedHashSet<String> settled = new java.util.LinkedHashSet<>();
    static final int MAX_SETTLED = 512;
    private int chars;
    /** Bumped by every change; {@link #save} writes only when it moved. */
    private long version;
    private long savedVersion;
    private volatile boolean diskRead;
    /** The file could not be read: it was put aside, and the queue started empty. */
    private volatile boolean damaged;

    Outbox(File file) {
        this.file = file;
    }

    /**
     * Reads the file and puts what it held ahead of anything added since (messages typed before
     * the load finished are newer). A v1 file is migrated first, once. A message that was being
     * sent when the process died now needs the user. Sent rows past their hour go.
     */
    void load() {
        // The same order as save: a save of a state from before the load can never land after
        // the migration's write and lose what was migrated.
        synchronized (writeLock) {
            synchronized (this) {
                // Once: a save forces the load first, and the courier's own load then does nothing.
                if (diskRead) return;
                List<Entry> loaded = new ArrayList<>();
                String text = read(file);
                if (text != null && !decode(text, loaded, settled)) {
                    damaged = true;
                    File aside = new File(file.getPath() + ".unreadable-" + System.currentTimeMillis());
                    if (!file.renameTo(aside)) file.delete();
                    loaded.clear();
                }
                boolean migrated = migrateV1(loaded);
                Set<String> known = new HashSet<>();
                for (Entry entry : entries) known.add(entry.id);
                List<Entry> merged = new ArrayList<>();
                for (Entry entry : loaded) {
                    if (known.contains(entry.id)) continue;
                    if (entry.state == State.SENDING) needsYou(entry, Reason.UNSURE);
                    merged.add(entry);
                }
                merged.addAll(entries);
                entries.clear();
                chars = 0;
                for (Entry entry : merged) {
                    entries.add(entry);
                    chars += entry.text.length();
                }
                if (!merged.isEmpty()) version++;
                if (migrated) {
                    // v2 is on disk before v1 is put aside: a crash in between migrates again,
                    // skipping the ids already here.
                    try {
                        write(file, encode());
                        savedVersion = version;
                        File v1 = new File(file.getParentFile(), V1_FILE_NAME);
                        if (!v1.renameTo(new File(v1.getPath() + MIGRATED_SUFFIX))) v1.delete();
                    } catch (IOException | JSONException unsaved) {
                        // v1 stays; the next start migrates it again.
                    }
                }
                diskRead = true;
            }
        }
    }

    /** Whether {@link #load} has run: until then cleared messages on disk are not known. */
    boolean loaded() {
        return diskRead;
    }

    /** Whether the last load found an unreadable file (put aside; the queue started empty). */
    boolean damaged() {
        return damaged;
    }

    /**
     * v1's messages into {@code into}: none of them has a proven destination. Queued ones wait
     * for the user to affirm the pane; ones that were being sent or unsure may have been typed;
     * gone ones are gone. Cancelled ones stay cancelled. Ids already present are skipped.
     */
    private boolean migrateV1(List<Entry> into) {
        File v1 = new File(file.getParentFile(), V1_FILE_NAME);
        String text = read(v1);
        if (text == null) return false;
        Set<String> present = new HashSet<>();
        for (Entry e : into) present.add(e.id);
        for (Entry e : entries) present.add(e.id);
        try {
            JSONObject root = new JSONObject(text);
            JSONArray list = root.optJSONArray("messages");
            for (int i = 0; list != null && i < list.length(); i++) {
                JSONObject item = list.optJSONObject(i);
                if (item == null) continue;
                String id = item.optString("id", "");
                String host = item.optString("host", "");
                String pane = item.optString("pane", "");
                String body = item.optString("text", "");
                if (id.isEmpty() || present.contains(id) || host.isEmpty() || pane.isEmpty() || !sendable(body)) continue;
                if (into.size() + entries.size() >= MAX_MESSAGES) break;
                Target target = new Target(null, host, "", "", pane, null, "", "");
                Entry entry = new Entry(id, target, body, new Composed(item.optLong("created", 0), -1, -1),
                        State.PENDING);
                entry.tapRequired = true;
                switch (item.optString("state", "")) {
                    case "QUEUED":
                        entry.reason = Reason.UNVERIFIED;
                        break;
                    case "GONE":
                        needsYou(entry, Reason.GONE);
                        break;
                    default:
                        needsYou(entry, Reason.UNSURE);
                        break;
                }
                entry.clearedUntil = item.optLong("clearedUntil", 0);
                into.add(entry);
                present.add(id);
            }
        } catch (JSONException damagedV1) {
            // Nothing readable to bring over; it is still put aside.
        }
        return true;
    }

    /** A message the user cleared, still recoverable: for a clear's recovery after a restart. */
    static final class Cleared {
        final String id, owner, text;
        final long until;

        Cleared(String id, String owner, String text, long until) {
            this.id = id;
            this.owner = owner;
            this.text = text;
            this.until = until;
        }
    }

    /**
     * Messages cleared and still in their undo window at {@code now}, among those the panes of
     * {@code host} show while it reaches {@code machine} ({@link #shown}'s rule).
     */
    synchronized List<Cleared> clearedOn(MachineKey machine, String host, long now) {
        List<Cleared> out = new ArrayList<>();
        for (Entry e : entries) {
            boolean shown = e.target.machine == null ? e.target.profileId.equals(host) : e.target.machine.equals(machine);
            if (shown && e.clearedUntil > now) {
                out.add(new Cleared(e.id, ComposerDrafts.key(host, e.target.address), e.text, e.clearedUntil));
            }
        }
        return out;
    }

    /** The texts of every message not cleared: what still holds its dictation chips. */
    synchronized List<String> liveTexts() {
        List<String> out = new ArrayList<>();
        for (Entry e : entries) if (e.clearedUntil == 0) out.add(e.text);
        return out;
    }

    /** The text of message {@code id}, cleared or not; null when there is none. */
    synchronized String text(String id) {
        Entry e = find(id);
        return e == null ? null : e.text;
    }

    /** Whether submission {@code id} was ever held here: it is, or it left after being held. */
    synchronized boolean known(String id) {
        return find(id) != null || settled.contains(id);
    }

    /** Message {@code id} as it is now, cleared or not; null when there is none. */
    synchronized Message message(String id) {
        Entry e = find(id);
        return e == null ? null : e.view();
    }

    /** Writes the file if anything changed since the last write; true when it is current. */
    boolean save() {
        // One writer at a time, each writing the state it read inside the lock: a slower save of
        // an older state can never land over a newer one.
        synchronized (writeLock) {
            // Never write over a file not read yet: what it holds is merged first, so the first
            // Send after a start can never replace a queue the startup load has not read.
            if (!diskRead) load();
            String text;
            long at;
            synchronized (this) {
                if (version == savedVersion) return true;
                at = version;
                try {
                    text = encode();
                } catch (JSONException impossible) {
                    return false;
                }
            }
            Runnable hook = beforeWrite;
            if (hook != null) hook.run();
            if (failSaves) return false;
            try {
                write(file, text);
            } catch (IOException failed) {
                return false;
            }
            synchronized (this) {
                if (savedVersion < at) savedVersion = at;
            }
            return true;
        }
    }

    /** Serializes {@link #save} and {@link #load}: snapshot and write together. */
    private final Object writeLock = new Object();
    /** Tests: runs between a save's snapshot and its write. */
    volatile Runnable beforeWrite;
    /** Tests: every save fails, as on a full disk. */
    volatile boolean failSaves;

    /**
     * Holds {@code text} for {@code target} as submission {@code id}; null when it is empty,
     * too long for one send, has no proven machine, or the bounds are reached (the caller keeps
     * the draft). An id already held is that message: a repeated handoff adds nothing.
     */
    synchronized Message add(String id, Target target, String text, Composed composed) {
        if (id == null || id.isEmpty() || target == null || target.machine == null || target.address.isEmpty()
                || composed == null || !sendable(text)) {
            return null;
        }
        // Admitted only against the queue as it is on disk: before the load, the bounds would be
        // checked against part of it, and a row reported held could be dropped by the next load.
        if (!diskRead) return null;
        Entry existing = find(id);
        if (existing != null) return existing.view();
        if (unsettledCount() >= MAX_MESSAGES || entries.size() >= MAX_MESSAGES
                || chars + text.length() > MAX_CHARS) {
            purgeSentLocked(composed.wallMs, true);
            if (entries.size() >= MAX_MESSAGES || chars + text.length() > MAX_CHARS) return null;
        }
        Entry entry = new Entry(id, target, text, composed, State.PENDING);
        entries.add(entry);
        chars += text.length();
        version++;
        return entry.view();
    }

    /** Text one send can carry: not blank, one compose command at most, no control but line breaks and tabs. */
    static boolean sendable(String text) {
        if (text == null || text.trim().isEmpty()) return false;
        if (text.getBytes(StandardCharsets.UTF_8).length > MAX_TEXT_BYTES) return false;
        for (int i = 0; i < text.length(); ) {
            int c = text.codePointAt(i);
            if (Character.isISOControl(c) && c != '\n' && c != '\t') return false;
            i += Character.charCount(c);
        }
        return true;
    }

    /** Whether {@code e} belongs to the lane of the pane {@code live}. */
    private static boolean inLane(Entry e, Live live) {
        if (live == null || !e.target.address.equals(live.address)) return false;
        if (e.target.machine == null) return e.target.profileId.equals(live.profileId);
        if (!e.target.machine.equals(live.machine)) return false;
        // A message belongs to the session it was typed in: a copied core in another session
        // reports the same machine and is never offered it.
        if (live.session != null && !e.target.session.equals(live.session)) return false;
        return e.target.createdMs == null || live.createdMs == null || e.target.createdMs.equals(live.createdMs);
    }

    private static boolean sameLane(Entry a, Entry b) {
        if (!a.target.address.equals(b.target.address)) return false;
        if (a.target.machine == null || b.target.machine == null) {
            return a.target.machine == null && b.target.machine == null
                    && a.target.profileId.equals(b.target.profileId);
        }
        return a.target.machine.equals(b.target.machine)
                && (a.target.createdMs == null || b.target.createdMs == null
                        || a.target.createdMs.equals(b.target.createdMs));
    }

    /** The head of {@code e}'s lane: its oldest message not cleared and not already sent. */
    private Entry head(Entry e) {
        for (Entry other : entries) {
            if (other.clearedUntil > 0 || other.settled() || (other.waitsAside() && other != e)) continue;
            if (sameLane(other, e)) return other;
        }
        return null;
    }

    /**
     * What the pane {@code live} offers for a tap: its lane's head, when it is pending and holds
     * no untranscribed dictation; null otherwise (a head being sent or needing the user stops its
     * lane). Never a message for another machine, terminal or unproven pane.
     */
    synchronized Message offer(Live live) {
        if (live == null || live.address.isEmpty() || (live.machine == null)) return null;
        for (Entry e : entries) {
            if (e.clearedUntil > 0 || e.settled() || !inLane(e, live) || e.waitsAside()) continue;
            if (e.state != State.PENDING || DictationToken.in(e.text)) return null;
            return e.view();
        }
        return null;
    }

    /** A lane held by a dictation waiting for a transcriber: its message, clip, and how many wait behind. */
    static final class Blocked {
        final String messageId;
        final String clipId;
        final int behind;

        Blocked(String messageId, String clipId, int behind) {
            this.messageId = messageId;
            this.clipId = clipId;
            this.behind = behind;
        }
    }

    /**
     * Whether the lane of {@code live} is held at its head by a message whose dictation has not
     * been transcribed, with messages waiting behind it; null otherwise (factory-9zz.16: said
     * on screen, never worked around silently).
     */
    synchronized Blocked blocked(Live live) {
        if (live == null || live.address.isEmpty() || live.machine == null) return null;
        Entry head = null;
        int behind = 0;
        for (Entry e : entries) {
            if (e.clearedUntil > 0 || e.settled() || !inLane(e, live) || e.waitsAside()) continue;
            if (head == null) {
                if (e.state != State.PENDING || !DictationToken.in(e.text)) return null;
                head = e;
            } else {
                behind++;
            }
        }
        if (head == null || behind == 0) return null;
        java.util.List<String> clips = DictationToken.ids(head.text);
        return new Blocked(head.id, clips.isEmpty() ? null : clips.get(0), behind);
    }

    /**
     * The user's "move the clip aside": pending message {@code id}, waiting for its dictation,
     * stops holding its lane. Nothing is sent by this; the rest are only offered for their tap.
     * Save after. False when it is not such a message.
     */
    synchronized boolean moveAside(String id) {
        Entry e = find(id);
        if (e == null || e.state != State.PENDING || e.clearedUntil > 0 || !DictationToken.in(e.text) || e.aside) {
            return false;
        }
        e.aside = true;
        version++;
        return true;
    }

    /** Takes back a {@link #moveAside} that could not be saved. */
    synchronized void unmoveAside(String id) {
        Entry e = find(id);
        if (e == null || !e.aside) return;
        e.aside = false;
        version++;
    }

    /**
     * How many messages of the lane of {@code live} are not settled (pending, being sent, or
     * needing the user): a new Send there waits behind them instead of overtaking.
     */
    synchronized int waitingIn(Live live) {
        int count = 0;
        for (Entry e : entries) {
            if (e.clearedUntil == 0 && !e.settled() && inLane(e, live)) count++;
        }
        return count;
    }

    /**
     * The user's tap: message {@code id} is about to be written to {@code live} as
     * {@code wireText}. True only when it is pending, heads its lane and that lane is the pane
     * on screen; it is then {@link State#SENDING} with its attempt (save before writing).
     */
    synchronized boolean claim(String id, Live live, String wireText, long wallMs, boolean immediate) {
        Entry entry = find(id);
        if (entry == null || entry.state != State.PENDING || entry.clearedUntil > 0 || !inLane(entry, live)) return false;
        if (head(entry) != entry || DictationToken.in(entry.text)) return false;
        entry.state = State.SENDING;
        entry.tapRequired = true;
        entry.attemptMode = immediate ? "now" : "live";
        entry.attemptAt = wallMs;
        entry.attemptText = wireText;
        entry.known = "";
        version++;
        return true;
    }

    /** A claim that could not be saved is taken back: nothing was written. */
    synchronized void unclaim(String id) {
        Entry entry = find(id);
        if (entry == null || entry.state != State.SENDING) return;
        entry.state = State.PENDING;
        entry.attemptMode = null;
        entry.attemptText = null;
        version++;
    }

    /**
     * The answer for a message {@link #claim} marked. Written: a message sent at once from the
     * live pane leaves ({@code keepSent} false), a held one is {@link State#SENT} for an hour.
     * Unwritten: pending again, needing a tap. Anything else needs the user.
     */
    synchronized void settle(String id, Outcome outcome, long wallMs, boolean keepSent) {
        Entry entry = find(id);
        if (entry == null || entry.state != State.SENDING) return;
        switch (outcome) {
            case WRITTEN:
                if (keepSent) {
                    entry.state = State.SENT;
                    entry.sentAt = wallMs;
                    entry.reason = null;
                    entry.known = "";
                } else {
                    remove(entry);
                }
                break;
            case UNWRITTEN:
                entry.state = State.PENDING;
                entry.known = "not sent: the pane was not live";
                break;
            default:
                needsYou(entry, Reason.UNSURE);
                break;
        }
        version++;
    }

    private static void needsYou(Entry entry, Reason reason) {
        entry.state = State.NEEDS_YOU;
        entry.reason = reason;
        entry.tapRequired = true;
        entry.known = "";
    }

    /**
     * A direct link to {@code machine} lists these live terminals ({@code address} to
     * {@code created_ms}, null when the list has none). Pending messages for a terminal that is
     * not there any more need the user: it is gone. A message that may have been sent keeps
     * that doubt, and a sent one stays sent. True when anything changed.
     */
    synchronized boolean observe(MachineKey machine, Map<String, Long> terminals) {
        if (machine == null || terminals == null) return false;
        boolean changed = false;
        for (Entry e : entries) {
            if (e.state != State.PENDING || !machine.equals(e.target.machine)) continue;
            boolean listed = terminals.containsKey(e.target.address);
            Long created = listed ? terminals.get(e.target.address) : null;
            boolean replaced = created != null && e.target.createdMs != null && !created.equals(e.target.createdMs);
            if (listed && !replaced) continue;
            needsYou(e, Reason.GONE);
            changed = true;
        }
        if (changed) version++;
        return changed;
    }

    /**
     * Puts a transcribed dictation where its token is; true when a message held it. A message
     * left blank by an empty dictation is dropped, and one that grew past what a send can carry
     * needs the user.
     */
    synchronized boolean fillDictation(String clipId, String spoken) {
        return fillDictationUndoably(clipId, spoken) != null;
    }

    /**
     * {@link #fillDictation}, with a way back: null when no message held the token, else what
     * puts the message as it was (for a fill that could not be saved).
     */
    synchronized Runnable fillDictationUndoably(String clipId, String spoken) {
        for (int i = 0; i < entries.size(); i++) {
            Entry entry = entries.get(i);
            if (entry.state == State.SENDING || entry.clearedUntil > 0) continue;
            String filled = DictationToken.fill(entry.text, clipId, spoken);
            if (filled == null) continue;
            String oldText = entry.text;
            State oldState = entry.state;
            Reason oldReason = entry.reason;
            boolean oldTap = entry.tapRequired;
            int at = i;
            if (filled.trim().isEmpty()) {
                remove(entry);
            } else {
                chars += filled.length() - entry.text.length();
                entry.text = filled;
                if (!sendable(filled)) needsYou(entry, Reason.GONE);
            }
            version++;
            return () -> {
                synchronized (Outbox.this) {
                    if (!entries.contains(entry)) {
                        entries.add(Math.min(at, entries.size()), entry);
                        chars += oldText.length();
                    } else {
                        chars += oldText.length() - entry.text.length();
                    }
                    entry.text = oldText;
                    entry.state = oldState;
                    entry.reason = oldReason;
                    entry.tapRequired = oldTap;
                    version++;
                }
            };
        }
        return null;
    }

    /**
     * The user's "Send again" on a message that needs them or was sent: pending where it was in
     * its lane, offered again in its live pane. A tap is still needed.
     */
    synchronized boolean resend(String id) {
        Entry entry = find(id);
        if (entry == null || (entry.state != State.NEEDS_YOU && entry.state != State.SENT)) return false;
        if (entry.state == State.NEEDS_YOU && entry.reason == Reason.GONE) return false;
        entry.state = State.PENDING;
        entry.tapRequired = true;
        entry.known = "";
        entry.sentAt = 0;
        // A migrated message stays unverified: its pane is affirmed at the tap.
        entry.reason = entry.target.machine == null ? Reason.UNVERIFIED : null;
        version++;
        return true;
    }

    /** The user dropped a message, took it back as a draft, or said it arrived: its text, or null. */
    synchronized String take(String id) {
        Entry entry = find(id);
        if (entry == null || entry.state == State.SENDING) return null;
        remove(entry);
        version++;
        return entry.text;
    }

    /**
     * The composer's clear: every message {@link #shown} for {@code pane} but one being sent is
     * marked cleared until {@code until} (epoch ms): kept on disk, never sent, shown or counted,
     * so an undo (or a restart within the window) can bring it back. Their ids.
     */
    synchronized List<String> clearAll(MachineKey machine, String host, String pane, long until, long now) {
        return clear(clearable(machine, host, pane, now), until);
    }

    /**
     * What a clear of {@code pane} would take: the pending messages and those needing the user
     * among exactly the rows {@link #shown} shows there. A profile repointed to another machine
     * never clears the old machine's rows it does not show.
     */
    synchronized List<String> clearable(MachineKey machine, String host, String pane, long now) {
        List<String> ids = new ArrayList<>();
        Live live = new Live(machine, host, pane, null);
        for (Entry e : entries) {
            if (e.clearedUntil > 0 || !inLane(e, live) || !shownFor(e, machine)) continue;
            if (e.state == State.PENDING || e.state == State.NEEDS_YOU) ids.add(e.id);
        }
        return ids;
    }

    /** Whether a pane of a profile reaching {@code machine} shows {@code e} at all. */
    private static boolean shownFor(Entry e, MachineKey machine) {
        return e.target.machine == null || e.target.machine.equals(machine);
    }

    /** Marks these messages cleared until {@code until}, never one being sent; those marked. */
    synchronized List<String> clear(Collection<String> ids, long until) {
        List<String> marked = new ArrayList<>();
        for (String id : ids) {
            Entry entry = find(id);
            if (entry == null || entry.state == State.SENDING || entry.clearedUntil > 0) continue;
            entry.clearedUntil = until;
            marked.add(id);
        }
        if (!marked.isEmpty()) version++;
        return marked;
    }

    /** The clear's undo: the messages are back where they were, as they were. */
    synchronized boolean unclear(Collection<String> ids) {
        boolean changed = false;
        for (String id : ids) {
            Entry entry = find(id);
            if (entry == null || entry.clearedUntil == 0) continue;
            entry.clearedUntil = 0;
            changed = true;
        }
        if (changed) version++;
        return changed;
    }

    /** The undo window closed: cleared messages go for good. */
    synchronized boolean purgeCleared(Collection<String> ids) {
        boolean changed = false;
        for (String id : ids) {
            Entry entry = find(id);
            if (entry == null || entry.clearedUntil == 0) continue;
            remove(entry);
            changed = true;
        }
        if (changed) version++;
        return changed;
    }

    /** Cleared messages whose window has passed (e.g. while the app was not running) go. */
    synchronized boolean purgeExpired(long now) {
        boolean changed = false;
        for (Entry entry : new ArrayList<>(entries)) {
            if (entry.clearedUntil > 0 && entry.clearedUntil <= now) {
                remove(entry);
                changed = true;
            }
        }
        if (changed) version++;
        return changed;
    }

    /** Sent rows whose hour has passed go; true when any did. */
    synchronized boolean purgeSent(long now) {
        return purgeSentLocked(now, false);
    }

    private boolean purgeSentLocked(long now, boolean force) {
        boolean changed = false;
        for (Entry entry : new ArrayList<>(entries)) {
            if (entry.state != State.SENT) continue;
            // A clock set back before the send keeps the row: its hour is not known to be over.
            boolean over = now >= entry.sentAt && now - entry.sentAt >= SENT_KEEP_MS;
            if (over || force) {
                remove(entry);
                changed = true;
            }
        }
        if (changed) version++;
        return changed;
    }

    /** The cleared messages among {@code ids}, as they were, for an undo after a restart. */
    synchronized List<Message> cleared(Collection<String> ids, long now) {
        List<Message> out = new ArrayList<>();
        for (String id : ids) {
            Entry entry = find(id);
            if (entry != null && entry.clearedUntil > 0) out.add(entry.view());
        }
        return out;
    }

    /**
     * The rows above the prompt for the pane on screen: its lane's messages, in order, when
     * {@code live} names the machine; with no proven machine, only messages migrated for that
     * profile and pane (an unbound profile shows nothing of any machine's).
     */
    synchronized List<Message> shown(MachineKey machine, String host, String pane) {
        List<Message> out = new ArrayList<>();
        Live live = new Live(machine, host, pane, null);
        for (Entry e : entries) {
            if (e.clearedUntil == 0 && inLane(e, live) && shownFor(e, machine)) out.add(e.view());
        }
        return out;
    }

    /** Every message not cleared, in order: the queue screen. */
    synchronized List<Message> all() {
        List<Message> out = new ArrayList<>();
        for (Entry e : entries) if (e.clearedUntil == 0) out.add(e.view());
        return out;
    }

    /** The whole queue's count, every machine and pane: messages not cleared and not sent. */
    synchronized int waiting() {
        return unsettledCount();
    }

    private int unsettledCount() {
        int count = 0;
        for (Entry e : entries) if (e.clearedUntil == 0 && !e.settled()) count++;
        return count;
    }

    synchronized int size() {
        return entries.size();
    }

    private Entry find(String id) {
        for (Entry entry : entries) if (entry.id.equals(id)) return entry;
        return null;
    }

    private void remove(Entry entry) {
        if (!entries.remove(entry)) return;
        chars -= entry.text.length();
        settled.remove(entry.id);
        settled.add(entry.id);
        while (settled.size() > MAX_SETTLED) settled.remove(settled.iterator().next());
    }

    private String encode() throws JSONException {
        JSONArray list = new JSONArray();
        for (Entry e : entries) {
            Target t = e.target;
            JSONObject item = new JSONObject()
                    .put("id", e.id)
                    .put("machine", t.machine == null ? JSONObject.NULL : t.machine.json())
                    .put("profile_id", t.profileId)
                    .put("profile", t.profileName)
                    .put("session", t.session)
                    .put("address", t.address)
                    .put("created_ms", t.createdMs == null ? JSONObject.NULL : t.createdMs)
                    .put("incarnation", JSONObject.NULL)
                    .put("label", t.label)
                    .put("seen", new JSONObject().put("ctx", JSONObject.NULL).put("status", t.status)
                            .put("as_of_ms", e.composed.wallMs))
                    .put("text", e.text)
                    .put("composed", new JSONObject().put("wall_ms", e.composed.wallMs)
                            .put("elapsed_ms", e.composed.elapsedMs).put("boot", e.composed.boot))
                    .put("state", e.state.wire)
                    .put("tap_required", e.tapRequired)
                    .put("reason", e.reason == null ? JSONObject.NULL : e.reason.wire)
                    .put("attempt", e.attemptMode == null ? JSONObject.NULL : new JSONObject()
                            .put("mode", e.attemptMode).put("wall_ms", e.attemptAt)
                            .put("wire_text", e.attemptText == null ? "" : e.attemptText))
                    .put("known", e.known)
                    .put("sent", e.sentAt == 0 ? JSONObject.NULL : new JSONObject().put("wall_ms", e.sentAt))
                    .put("cleared_until", e.clearedUntil)
                    .put("aside", e.aside);
            list.put(item);
        }
        return new JSONObject().put("v", 2).put("messages", list).put("settled", new JSONArray(settled)).toString();
    }

    /**
     * Whatever of the file is well formed and within the bounds, into {@code into}. False when the
     * file is not an outbox this build can read at all (it is then put aside).
     */
    private static boolean decode(String text, List<Entry> into, java.util.Set<String> settled) {
        try {
            JSONObject root = new JSONObject(text);
            if (root.optInt("v") != 2) return false;
            JSONArray gone = root.optJSONArray("settled");
            for (int i = 0; gone != null && i < gone.length() && settled.size() < MAX_SETTLED; i++) {
                String id = gone.optString(i, "");
                if (!id.isEmpty()) settled.add(id);
            }
            JSONArray list = root.optJSONArray("messages");
            int chars = 0;
            for (int i = 0; list != null && i < list.length() && into.size() < MAX_MESSAGES; i++) {
                JSONObject item = list.optJSONObject(i);
                if (item == null) continue;
                String id = item.optString("id", "");
                String body = item.optString("text", "");
                String address = item.optString("address", "");
                if (id.isEmpty() || address.isEmpty() || !sendable(body)) continue;
                if (chars + body.length() > MAX_CHARS) break;
                chars += body.length();
                JSONObject seen = item.optJSONObject("seen");
                Target target = new Target(MachineKey.from(item.optJSONObject("machine")),
                        item.optString("profile_id", ""), item.optString("profile", ""),
                        item.optString("session", ""), address,
                        item.isNull("created_ms") ? null : item.optLong("created_ms"),
                        item.optString("label", ""), seen == null ? "" : seen.optString("status", ""));
                JSONObject composed = item.optJSONObject("composed");
                Composed when = composed == null ? new Composed(0, -1, -1) : new Composed(
                        composed.optLong("wall_ms", 0), composed.optLong("elapsed_ms", -1), composed.optInt("boot", -1));
                Entry entry = new Entry(id, target, body, when, State.parse(item.optString("state", "")));
                entry.reason = Reason.parse(item.isNull("reason") ? null : item.optString("reason", ""));
                entry.tapRequired = item.optBoolean("tap_required", false);
                if (entry.state == State.NEEDS_YOU && entry.reason == null) entry.reason = Reason.UNSURE;
                // A pending message with a reason this build does not know is in doubt.
                if (entry.state == State.PENDING && entry.reason != null && entry.reason != Reason.UNVERIFIED) {
                    needsYou(entry, entry.reason);
                }
                JSONObject attempt = item.optJSONObject("attempt");
                if (attempt != null) {
                    entry.attemptMode = attempt.optString("mode", "live");
                    entry.attemptAt = attempt.optLong("wall_ms", 0);
                    entry.attemptText = attempt.optString("wire_text", "");
                }
                entry.known = item.optString("known", "");
                JSONObject sent = item.optJSONObject("sent");
                entry.sentAt = sent == null ? 0 : sent.optLong("wall_ms", 0);
                entry.clearedUntil = item.optLong("cleared_until", 0);
                entry.aside = item.optBoolean("aside", false);
                into.add(entry);
            }
            return true;
        } catch (JSONException damagedFile) {
            into.clear();
            return false;
        }
    }

    static String read(File file) {
        if (file == null || !file.isFile() || file.length() > MAX_FILE_BYTES) return null;
        try (InputStream in = new FileInputStream(file)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            for (int n; (n = in.read(buffer)) > 0; ) {
                out.write(buffer, 0, n);
                if (out.size() > MAX_FILE_BYTES) return null;
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        } catch (IOException unreadable) {
            return null;
        }
    }

    /** Write, flush to the disk, then rename over the old file: a crash leaves one or the other whole. */
    static void write(File file, String text) throws IOException {
        File dir = file.getParentFile();
        if (dir != null && !dir.isDirectory() && !dir.mkdirs()) throw new IOException("no directory for " + file);
        File tmp = new File(file.getPath() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(text.getBytes(StandardCharsets.UTF_8));
            out.getFD().sync();
        }
        if (!tmp.renameTo(file)) {
            tmp.delete();
            throw new IOException("could not replace " + file);
        }
    }

    private static String nonNull(String value) {
        return value == null ? "" : value;
    }
}
