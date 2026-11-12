package dev.herdr.android;

import java.io.File;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Carries the {@link Outbox} to the computers: loads and saves it, and sends
 * its messages one at a time on its own thread, so a send that waits seconds
 * for the core (an agent settling, a person typing elsewhere) never stalls the
 * terminal's poll.
 *
 * <p>Every change is saved before the next step that depends on it: a message
 * is only handed to the core once its "sending" mark is on disk, so a process
 * killed mid-send leaves it unsure rather than queued (which would send it
 * twice) or missing (which would lose it).
 */
final class OutboxCourier {
    /** The one call that reaches the core; {@code RemoteNative::deliver} in the app. */
    interface Wire {
        String deliver(long handle, String pane, String text);
    }

    private static OutboxCourier shared;

    /**
     * The process's one courier. It outlives activities: a send in flight when
     * the terminal closes still gets its answer recorded.
     */
    static synchronized OutboxCourier shared(android.content.Context context) {
        if (shared == null) {
            File file = new File(context.getApplicationContext().getNoBackupFilesDir(), Outbox.FILE_NAME);
            Executor thread = java.util.concurrent.Executors.newSingleThreadExecutor(task -> {
                Thread worker = new Thread(task, "herdr-outbox");
                worker.setDaemon(true);
                return worker;
            });
            shared = new OutboxCourier(file, thread, RemoteNative::deliver);
        }
        return shared;
    }

    private final Outbox outbox;
    private final Executor thread;
    private final Wire wire;
    /** Told after anything the strip or the status line shows changed; on any thread. */
    private final Set<Runnable> listeners = new java.util.concurrent.CopyOnWriteArraySet<>();
    private final AtomicBoolean sending = new AtomicBoolean();

    OutboxCourier(File file, Executor thread, Wire wire) {
        this.outbox = new Outbox(file);
        this.thread = thread;
        this.wire = wire;
        thread.execute(() -> {
            outbox.load();
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

    /** Queues a composer message; null keeps the draft (empty, too long, or the outbox is full). */
    Outbox.Message add(String host, String pane, String text) {
        Outbox.Message message = outbox.add(host, pane, text, System.currentTimeMillis());
        if (message == null) return null;
        thread.execute(outbox::save);
        changed();
        return message;
    }

    /** A transcribed dictation for a queued message; true when one held its token. */
    boolean fillDictation(String clipId, String spoken) {
        if (!outbox.fillDictation(clipId, spoken)) return false;
        thread.execute(outbox::save);
        changed();
        return true;
    }

    /** Send again, or drop/take back, by the user's hand. */
    void resend(String id) {
        if (!outbox.resend(id)) return;
        thread.execute(outbox::save);
        changed();
    }

    String take(String id) {
        String text = outbox.take(id);
        if (text == null) return null;
        thread.execute(outbox::save);
        changed();
        return text;
    }

    /**
     * The session on {@code host} is ready with this core boot and these panes.
     * Called on every poll; cheap when there is nothing to send.
     */
    void ready(String host, long handle, String boot, Set<String> panes) {
        if (handle <= 0 || panes == null || panes.isEmpty()) return;
        if (outbox.ready(host, boot, panes)) {
            thread.execute(outbox::save);
            changed();
        }
        if (outbox.next(host, System.currentTimeMillis()) == null) return;
        if (!sending.compareAndSet(false, true)) return;
        thread.execute(() -> {
            try {
                drain(host, handle);
            } finally {
                sending.set(false);
            }
        });
    }

    /** Sends {@code host}'s messages in order until one does not go through. Courier thread. */
    private void drain(String host, long handle) {
        for (Outbox.Message message; (message = outbox.next(host, System.currentTimeMillis())) != null; ) {
            if (!outbox.sending(message.id)) return;
            if (!outbox.save()) {
                // Unrecorded, a send could be repeated after a crash: do not send.
                outbox.settle(message.id, Outbox.Outcome.RETRY, System.currentTimeMillis());
                return;
            }
            changed();
            Outbox.Outcome outcome = send(handle, message);
            outbox.settle(message.id, outcome, System.currentTimeMillis());
            outbox.save();
            changed();
            if (outcome != Outbox.Outcome.SENT) return;
        }
    }

    private Outbox.Outcome send(long handle, Outbox.Message message) {
        String reply;
        try {
            reply = wire.deliver(handle, message.pane, message.text);
        } catch (IllegalArgumentException | IllegalStateException refused) {
            // The native side refuses arguments before anything is sent: this
            // text can never go, so it is offered back instead of retried.
            return Outbox.Outcome.GONE;
        }
        if (reply == null) return Outbox.Outcome.GONE;
        try {
            return Outbox.Outcome.parse(new JSONObject(reply).optString("outcome", ""));
        } catch (JSONException unreadable) {
            return Outbox.Outcome.UNSURE;
        }
    }
}
