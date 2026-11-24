package dev.herdr.android;

import java.io.File;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Carries the {@link Outbox}: loads and saves it, and makes the one write a tap asks for on its
 * own thread, so a send never stalls the terminal's poll or the UI. It never sends by itself:
 * every write starts from a tap on Send (the composer's, or a held message's in its live pane).
 *
 * <p>Every change is saved before the step that depends on it: a message is written only once
 * its "sending" mark is on disk, so a process killed mid-send leaves it needing the user rather
 * than pending (which would invite a second send) or missing (which would lose it).
 */
final class OutboxCourier {
    /** The one call that types; {@code RemoteNative::compose} in the app. */
    interface Wire {
        String compose(long handle, long attach, String text);
    }

    private static OutboxCourier shared;

    /**
     * The process's one courier. It outlives activities: a send in flight when the terminal
     * closes still gets its answer recorded.
     */
    static synchronized OutboxCourier shared(android.content.Context context) {
        if (shared == null) {
            File file = new File(context.getApplicationContext().getNoBackupFilesDir(), Outbox.FILE_NAME);
            Executor thread = java.util.concurrent.Executors.newSingleThreadExecutor(task -> {
                Thread worker = new Thread(task, "herdr-outbox");
                worker.setDaemon(true);
                return worker;
            });
            shared = new OutboxCourier(file, thread, RemoteNative::compose);
        }
        return shared;
    }

    private final Outbox outbox;
    private final Executor thread;
    private final Wire wire;
    /** Told after anything the rows, the queue or the badge show changed; on any thread. */
    private final Set<Runnable> listeners = new java.util.concurrent.CopyOnWriteArraySet<>();

    OutboxCourier(File file, Executor thread, Wire wire) {
        this.outbox = new Outbox(file);
        this.thread = thread;
        this.wire = wire;
        thread.execute(() -> {
            outbox.load();
            outbox.purgeSent(System.currentTimeMillis());
            outbox.save();
            changed();
        });
    }

    void listen(Runnable listener) {
        listeners.add(listener);
    }

    void unlisten(Runnable listener) {
        listeners.remove(listener);
    }

    private void changed() {
        for (Runnable listener : listeners) listener.run();
    }

    Outbox outbox() {
        return outbox;
    }

    /**
     * Step 2 of the Send handoff: holds the message under its submission id and saves it before
     * returning (fsync). Null when it cannot be held or saved: then nothing is held and the
     * caller keeps the draft.
     */
    Outbox.Message add(String id, Outbox.Target target, String text, Outbox.Composed composed) {
        // The queue on disk first (once; the startup load then does nothing): the bounds and the
        // order apply to the whole queue, never to the part read so far.
        outbox.load();
        boolean known = outbox.message(id) != null;
        Outbox.Message message = outbox.add(id, target, text, composed);
        if (message == null) return null;
        if (!outbox.save()) {
            if (!known) outbox.take(id);
            return null;
        }
        changed();
        return message;
    }

    /**
     * A tap: writes message {@code id} into the pane {@code live}, on the attach {@code attach}
     * of session {@code handle}, as {@code wireText}. The attempt is saved first; the outcome is
     * saved, then told to {@code done} (on the courier's thread). {@code immediate}: typed at
     * once from the composer in that live pane, so a written one leaves instead of staying as
     * "sent · check it arrived". Null outcome: nothing was tried (it was not the lane's pending
     * head, or the attempt could not be saved).
     */
    void send(String id, Outbox.Live live, long handle, long attach, String wireText, boolean immediate,
            Consumer<Outbox.Outcome> done) {
        thread.execute(() -> {
            Outbox.Outcome outcome = null;
            try {
                if (!outbox.claim(id, live, wireText, System.currentTimeMillis(), immediate)) return;
                if (!outbox.save()) {
                    // Unrecorded, a write could be repeated after a crash: nothing is written.
                    outbox.unclaim(id);
                    return;
                }
                changed();
                outcome = type(handle, attach, wireText);
                outbox.settle(id, outcome, System.currentTimeMillis(), !immediate);
                outbox.save();
                changed();
            } finally {
                if (done != null) done.accept(outcome);
            }
        });
    }

    private Outbox.Outcome type(long handle, long attach, String text) {
        String reply;
        try {
            reply = wire.compose(handle, attach, text);
        } catch (IllegalArgumentException | IllegalStateException refused) {
            // The native side refuses arguments before anything is written.
            return Outbox.Outcome.UNWRITTEN;
        }
        if (reply == null) return Outbox.Outcome.UNWRITTEN;
        try {
            return Outbox.Outcome.parse(new JSONObject(reply).optString("outcome", ""));
        } catch (JSONException unreadable) {
            return Outbox.Outcome.UNSURE;
        }
    }

    /** {@link #observe(MachineKey, String, java.util.Map)} by a link of any session. */
    void observe(MachineKey machine, java.util.Map<String, Long> terminals) {
        observe(machine, null, terminals);
    }

    /**
     * A direct link to {@code machine} in {@code session} lists these live terminals; pending ones
     * of that session not there are gone.
     */
    void observe(MachineKey machine, String session, java.util.Map<String, Long> terminals) {
        if (!outbox.observe(machine, session, terminals)) return;
        thread.execute(outbox::save);
        changed();
    }

    /**
     * A transcribed dictation for a held message: 1 when a message held its token and the text
     * is saved; 0 when none held it; -1 when the save failed, and the message is as it was (the
     * caller keeps the recording, which is offered again).
     */
    int fillDictation(String clipId, String spoken) {
        Runnable undo = outbox.fillDictationUndoably(clipId, spoken);
        if (undo == null) return 0;
        if (!outbox.save()) {
            undo.run();
            return -1;
        }
        changed();
        return 1;
    }

    /** "Send again" on a message that needs the user or was sent: pending again, a tap away. */
    void resend(String id) {
        if (!outbox.resend(id)) return;
        thread.execute(outbox::save);
        changed();
    }

    /**
     * Marks these messages cleared until {@code until} (never one being sent) and saves that
     * before returning; false when the save failed: then none is cleared.
     */
    boolean clear(java.util.Collection<String> ids, long until) {
        java.util.List<String> marked = outbox.clear(ids, until);
        if (marked.isEmpty()) return true;
        if (!outbox.save()) {
            outbox.unclear(marked);
            return false;
        }
        changed();
        return true;
    }

    /** One message cleared (a dropped row), saved; false when the save failed (nothing cleared). */
    boolean clear(String id, long until) {
        return clear(java.util.Collections.singletonList(id), until);
    }

    /**
     * The clear's undo: the messages back in line at once; true when that is saved too. Not
     * saved, they are still back here, the save is tried again on the courier's thread, and the
     * caller keeps its record until a later call saves (a restart finds them cleared on disk).
     */
    boolean unclear(java.util.Collection<String> ids) {
        boolean changed = outbox.unclear(ids);
        if (changed) changed();
        if (outbox.save()) return true;
        thread.execute(outbox::save);
        return false;
    }

    /** The undo window closed: the cleared messages go for good. */
    void purgeCleared(java.util.Collection<String> ids) {
        if (!outbox.purgeCleared(ids)) return;
        thread.execute(outbox::save);
        changed();
    }

    /** Sent rows past their hour go. */
    void purgeSent() {
        if (!outbox.purgeSent(System.currentTimeMillis())) return;
        thread.execute(outbox::save);
        changed();
    }

    /** Drop, Edit as draft, or "It arrived": the message leaves; its text, or null. */
    String take(String id) {
        String text = outbox.take(id);
        if (text == null) return null;
        thread.execute(outbox::save);
        changed();
        return text;
    }
}
