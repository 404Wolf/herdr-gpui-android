package dev.herdr.android;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Composer messages that have not reached their agent yet, kept on disk so a
 * dead link, a closed app or a killed process loses none of them.
 *
 * <p>Each message belongs to one pane on one computer, in the core boot it
 * was typed for, and goes out in the order it was typed: a pane's messages
 * leave strictly one after another, so a later one never overtakes an earlier
 * one that is waiting.
 *
 * <p>The send itself is the only moment a message can be lost or doubled. A
 * message is marked {@link State#SENDING} (and saved) before it is handed to
 * the core; if the answer never comes, because the link or the process died,
 * it becomes {@link State#UNSURE}. An unsure message is never sent again on its
 * own: only the user can say "send again" or "drop", since the core may have
 * typed it. A message whose pane closed or whose core restarted is
 * {@link State#GONE}: kept, never sent, offered back as a draft.
 *
 * <p>A message holding a {@link DictationToken} (a dictation recorded while
 * the desktop was away) waits at the head of its pane's line, holding back the
 * messages behind it, until {@link #fillDictation} puts the text in.
 *
 * <p>Pure state plus a file; not thread-safe on its own (callers hold its
 * lock: every public method is synchronized). Disk work happens only in
 * {@link #load} and {@link #save}, which callers run off the main thread.
 */
final class Outbox {
    static final int MAX_MESSAGES = PendingInput.MAX_COMMANDS;
    static final int MAX_CHARS = PendingInput.MAX_CHARS;
    /** One compose command's text, the most one send may carry (model.rs MAX_TEXT_BYTES). */
    static final int MAX_TEXT_BYTES = 65_536;
    /** A file past this is not parsed: the bounds keep a real one far smaller. */
    static final int MAX_FILE_BYTES = 4 * 1024 * 1024;
    /** How long a pane that refused (its program waits on a dialog) is left alone. */
    static final long BLOCKED_BACKOFF_MS = 4_000;
    static final String FILE_NAME = "outbox-v1.json";

    enum State { QUEUED, SENDING, UNSURE, GONE }

    /** What a send did, as {@code RemoteNative.deliver} reports it. */
    enum Outcome {
        SENT, BLOCKED, GONE, UNSURE, RETRY;

        /** Unknown words are unsure: an unrecognised answer must never cause a resend. */
        static Outcome parse(String word) {
            if (word == null) return UNSURE;
            switch (word) {
                case "sent": return SENT;
                case "blocked": return BLOCKED;
                case "gone": return GONE;
                case "retry": return RETRY;
                default: return UNSURE;
            }
        }
    }

    /** An immutable view of one message, for the strip and the sender. */
    static final class Message {
        final String id;
        final String host;
        final String pane;
        final String boot;
        final String text;
        final long created;
        final State state;
        /** The pane refused it a moment ago (a dialog is open); it waits, still queued. */
        final boolean blocked;
        /** It holds a dictation the desktop has not transcribed yet; it waits, still queued. */
        final boolean awaitsDictation;

        Message(String id, String host, String pane, String boot, String text, long created,
                State state, boolean blocked) {
            this.awaitsDictation = DictationToken.in(text);
            this.id = id;
            this.host = host;
            this.pane = pane;
            this.boot = boot;
            this.text = text;
            this.created = created;
            this.state = state;
            this.blocked = blocked;
        }
    }

    private static final class Entry {
        final String id;
        final String host;
        final String pane;
        String boot;
        String text;
        final long created;
        State state;
        /** Not saved: a refusal is only a reason to wait a little. */
        long blockedUntil;

        Entry(String id, String host, String pane, String boot, String text, long created, State state) {
            this.id = id;
            this.host = host;
            this.pane = pane;
            this.boot = boot;
            this.text = text;
            this.created = created;
            this.state = state;
        }

        Message view(long now) {
            return new Message(id, host, pane, boot, text, created, state,
                    state == State.QUEUED && blockedUntil > now);
        }
    }

    private final File file;
    private final List<Entry> entries = new ArrayList<>();
    /** Each computer's core boot as last seen ready, so a message typed offline knows its boot. */
    private final Map<String, String> boots = new HashMap<>();
    private int chars;
    /** Bumped by every change; {@link #save} writes only when it moved. */
    private long version;
    private long savedVersion;

    Outbox(File file) {
        this.file = file;
    }

    /**
     * Reads the file and puts what it held ahead of anything added since
     * (messages typed before the load finished are newer). A message that was
     * being sent when the process died becomes unsure.
     */
    synchronized void load() {
        List<Entry> loaded = new ArrayList<>();
        Map<String, String> loadedBoots = new HashMap<>();
        decode(read(file), loaded, loadedBoots);
        Set<String> known = new HashSet<>();
        for (Entry entry : entries) known.add(entry.id);
        List<Entry> merged = new ArrayList<>();
        for (Entry entry : loaded) {
            if (known.contains(entry.id)) continue;
            if (entry.state == State.SENDING) entry.state = State.UNSURE;
            merged.add(entry);
        }
        merged.addAll(entries);
        entries.clear();
        chars = 0;
        for (Entry entry : merged) {
            entries.add(entry);
            chars += entry.text.length();
        }
        for (Map.Entry<String, String> boot : loadedBoots.entrySet()) {
            boots.putIfAbsent(boot.getKey(), boot.getValue());
        }
        if (!merged.isEmpty() || !loadedBoots.isEmpty()) version++;
    }

    /** Writes the file if anything changed since the last write; true when it is current. */
    boolean save() {
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

    /**
     * Queues {@code text} for {@code pane} on {@code host}; null when it is
     * empty, too long for one send, or the bounds are reached (the caller keeps
     * the draft). The boot is the one last seen ready for that host.
     */
    synchronized Message add(String host, String pane, String text, long now) {
        if (host == null || host.isEmpty() || pane == null || pane.isEmpty() || !sendable(text)) return null;
        if (entries.size() >= MAX_MESSAGES || chars + text.length() > MAX_CHARS) return null;
        String boot = boots.get(host);
        Entry entry = new Entry(UUID.randomUUID().toString(), host, pane, boot == null ? "" : boot, text, now,
                State.QUEUED);
        entries.add(entry);
        chars += text.length();
        version++;
        return entry.view(now);
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

    /**
     * The computer's core is ready with {@code boot} and these panes. Messages
     * typed without a known boot take this one; messages for another boot or
     * for a pane that is not there any more are gone. True when anything changed.
     */
    synchronized boolean ready(String host, String boot, Set<String> panes) {
        if (host == null || boot == null || boot.isEmpty()) return false;
        boolean changed = !boot.equals(boots.put(host, boot));
        for (Entry entry : entries) {
            if (!entry.host.equals(host) || entry.state == State.GONE) continue;
            if (entry.boot.isEmpty()) {
                entry.boot = boot;
                changed = true;
            }
            boolean live = entry.boot.equals(boot) && panes != null && panes.contains(entry.pane);
            // A message being sent is settled by its own answer, not by this.
            if (!live && entry.state != State.SENDING) {
                entry.state = State.GONE;
                changed = true;
            }
        }
        if (changed) version++;
        return changed;
    }

    /**
     * The next message to send on {@code host}: the oldest that heads its
     * pane's line and is queued and not resting after a refusal. A pane whose
     * head is unsure, gone or being sent sends nothing until that is settled.
     */
    synchronized Message next(String host, long now) {
        Set<String> headed = new HashSet<>();
        for (Entry entry : entries) {
            if (!entry.host.equals(host)) continue;
            // GONE messages never block their pane's line: they are not sent.
            if (entry.state == State.GONE || !headed.add(entry.pane)) continue;
            if (entry.state == State.QUEUED && entry.blockedUntil <= now && !entry.boot.isEmpty()
                    && !DictationToken.in(entry.text)) {
                return entry.view(now);
            }
        }
        return null;
    }

    /** The message is about to be handed to the core: from now on its fate is unknown until answered. */
    synchronized boolean sending(String id) {
        Entry entry = find(id);
        if (entry == null || entry.state != State.QUEUED) return false;
        entry.state = State.SENDING;
        version++;
        return true;
    }

    /** The core's answer for a message {@link #sending} marked. */
    synchronized void settle(String id, Outcome outcome, long now) {
        Entry entry = find(id);
        if (entry == null || entry.state != State.SENDING) return;
        switch (outcome) {
            case SENT:
                remove(entry);
                break;
            case BLOCKED:
                entry.state = State.QUEUED;
                entry.blockedUntil = now + BLOCKED_BACKOFF_MS;
                break;
            case RETRY:
                entry.state = State.QUEUED;
                break;
            case GONE:
                entry.state = State.GONE;
                break;
            default:
                entry.state = State.UNSURE;
                break;
        }
        version++;
    }

    /**
     * Puts a transcribed dictation where its token is; true when a message held
     * it. A message left blank by an empty dictation is dropped, and one that
     * grew past what a send can carry is gone (offered back as a draft).
     */
    synchronized boolean fillDictation(String clipId, String spoken) {
        for (Entry entry : entries) {
            if (entry.state == State.SENDING) continue;
            String filled = DictationToken.fill(entry.text, clipId, spoken);
            if (filled == null) continue;
            if (filled.trim().isEmpty()) {
                remove(entry);
            } else {
                chars += filled.length() - entry.text.length();
                entry.text = filled;
                if (!sendable(filled)) entry.state = State.GONE;
            }
            version++;
            return true;
        }
        return false;
    }

    /** The user's "send again" on an unsure message: back in line where it was. */
    synchronized boolean resend(String id) {
        Entry entry = find(id);
        if (entry == null || entry.state != State.UNSURE) return false;
        entry.state = State.QUEUED;
        entry.blockedUntil = 0;
        version++;
        return true;
    }

    /** The user dropped or took back a message; its text, or null if it is being sent or unknown. */
    synchronized String take(String id) {
        Entry entry = find(id);
        if (entry == null || entry.state == State.SENDING) return null;
        remove(entry);
        version++;
        return entry.text;
    }

    /**
     * What the strip shows for {@code pane}: its own messages in order, then
     * the computer's gone ones (their panes are not on screen any more).
     */
    synchronized List<Message> shown(String host, String pane, long now) {
        List<Message> own = new ArrayList<>();
        List<Message> gone = new ArrayList<>();
        for (Entry entry : entries) {
            if (!entry.host.equals(host)) continue;
            if (entry.pane.equals(pane) && entry.state != State.GONE) own.add(entry.view(now));
            else if (entry.state == State.GONE) gone.add(entry.view(now));
        }
        own.addAll(gone);
        return own;
    }

    /** Whether {@code pane} has anything not yet settled, so new input must queue behind it. */
    synchronized boolean holds(String host, String pane) {
        for (Entry entry : entries) {
            if (entry.host.equals(host) && entry.pane.equals(pane) && entry.state != State.GONE) return true;
        }
        return false;
    }

    /** Messages on {@code host} still waiting to go out (gone ones excluded). */
    synchronized int waiting(String host) {
        int count = 0;
        for (Entry entry : entries) {
            if (entry.host.equals(host) && entry.state != State.GONE) count++;
        }
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
        if (entries.remove(entry)) chars -= entry.text.length();
    }

    private String encode() throws JSONException {
        JSONArray list = new JSONArray();
        for (Entry entry : entries) {
            list.put(new JSONObject()
                    .put("id", entry.id)
                    .put("host", entry.host)
                    .put("pane", entry.pane)
                    .put("boot", entry.boot)
                    .put("text", entry.text)
                    .put("created", entry.created)
                    .put("state", entry.state.name()));
        }
        JSONObject bootMap = new JSONObject();
        for (Map.Entry<String, String> boot : boots.entrySet()) bootMap.put(boot.getKey(), boot.getValue());
        return new JSONObject().put("version", 1).put("messages", list).put("boots", bootMap).toString();
    }

    /** Whatever of the file is well formed and within the bounds; a damaged file yields nothing. */
    private static void decode(String text, List<Entry> into, Map<String, String> boots) {
        if (text == null || text.isEmpty()) return;
        try {
            JSONObject root = new JSONObject(text);
            if (root.optInt("version") != 1) return;
            JSONArray list = root.optJSONArray("messages");
            int chars = 0;
            for (int i = 0; list != null && i < list.length() && into.size() < MAX_MESSAGES; i++) {
                JSONObject item = list.optJSONObject(i);
                if (item == null) continue;
                String id = item.optString("id", "");
                String host = item.optString("host", "");
                String pane = item.optString("pane", "");
                String messageText = item.optString("text", "");
                State state;
                try {
                    state = State.valueOf(item.optString("state", ""));
                } catch (IllegalArgumentException unknown) {
                    // A state this build does not know might be a send in flight.
                    state = State.UNSURE;
                }
                if (id.isEmpty() || host.isEmpty() || pane.isEmpty() || !sendable(messageText)) continue;
                if (chars + messageText.length() > MAX_CHARS) break;
                chars += messageText.length();
                into.add(new Entry(id, host, pane, item.optString("boot", ""), messageText,
                        item.optLong("created", 0), state));
            }
            JSONObject bootMap = root.optJSONObject("boots");
            if (bootMap != null) {
                for (Iterator<String> keys = bootMap.keys(); keys.hasNext(); ) {
                    String host = keys.next();
                    String boot = bootMap.optString(host, "");
                    if (!boot.isEmpty()) boots.put(host, boot);
                }
            }
        } catch (JSONException damaged) {
            into.clear();
        }
    }

    private static String read(File file) {
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
    private static void write(File file, String text) throws IOException {
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
}
