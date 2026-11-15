package dev.herdr.android.dictation;

import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayDeque;

/**
 * Carries the microphone's audio to a recognizer's pipe on its own thread, so the microphone
 * thread never blocks on the recognizer. Audio is never dropped silently: every chunk reaches the
 * sink in order, or the feeder reports a failure and stops (the session's recording still has
 * the audio, so the engine falls back to it).
 *
 * <p>The sink can be swapped ({@link #setSink}) when the recognizer restarts on a fresh pipe. A
 * write that fails waits up to {@code swapGraceMs} for such a swap and then goes to the new sink;
 * with no swap in time it is a failure.
 */
final class PcmFeeder {
    /**
     * Hears how the feeding ends, at most one of the two: on the feeder's thread, or onFailed on
     * the writing thread when the queue is full.
     */
    interface Listener {
        /** Every chunk was written and the sink closed, after {@link #end}. */
        void onDrained();

        /** Audio could not be delivered; {@code why} is for logs. No more audio is written. */
        void onFailed(String why);
    }

    private final int maxChunks;
    private final long swapGraceMs;
    private final Listener listener;
    private final Object lock = new Object();
    /** Guarded by {@link #lock}. */
    private final ArrayDeque<byte[]> queue = new ArrayDeque<>();
    /** Guarded by {@link #lock}. */
    private OutputStream sink;
    /** Guarded by {@link #lock}: counts {@link #setSink} calls. */
    private long generation;
    /** Guarded by {@link #lock}. */
    private boolean ending;
    /** Guarded by {@link #lock}: cancelled, failed or drained; nothing more happens. */
    private boolean over;
    private final Thread thread;

    PcmFeeder(int maxChunks, long swapGraceMs, Listener listener) {
        this.maxChunks = maxChunks;
        this.swapGraceMs = swapGraceMs;
        this.listener = listener;
        thread = new Thread(this::run, "dictation-on-device");
        thread.setDaemon(true);
    }

    /** Starts the feeder's thread; audio written before waits for it. */
    void start() {
        thread.start();
    }

    /** Where audio goes from now on; the previous sink is closed. */
    void setSink(OutputStream next) {
        OutputStream previous;
        synchronized (lock) {
            if (over) {
                previous = next; // nothing will write to it: close it, so its reader sees the end
            } else {
                previous = sink;
                sink = next;
            }
            generation++;
            lock.notifyAll();
        }
        closeQuietly(previous);
    }

    /**
     * Queues a copy of the chunk. False when the queue is full (the recognizer is not keeping
     * up): that is a failure, reported through the listener, never a silent drop.
     */
    boolean write(byte[] chunk, int length) {
        boolean overflow;
        synchronized (lock) {
            if (over || ending) return false;
            overflow = queue.size() >= maxChunks;
            if (!overflow) {
                queue.addLast(java.util.Arrays.copyOf(chunk, length));
                lock.notifyAll();
                return true;
            }
            over = true;
            queue.clear();
            lock.notifyAll();
        }
        listener.onFailed("not keeping up (" + maxChunks + " chunks queued)");
        return false;
    }

    /** No more audio: everything queued is written, then the sink is closed and onDrained. */
    void end() {
        synchronized (lock) {
            ending = true;
            lock.notifyAll();
        }
    }

    /** Stops at once; no more listener calls. */
    void cancel() {
        OutputStream s;
        synchronized (lock) {
            over = true;
            queue.clear();
            s = sink;
            sink = null;
            lock.notifyAll();
        }
        closeQuietly(s);
    }

    private void run() {
        try {
            while (true) {
                byte[] chunk;
                synchronized (lock) {
                    while (!over && queue.isEmpty() && !ending) lock.wait();
                    if (over) return;
                    if (queue.isEmpty()) break; // ending, all written
                    chunk = queue.peekFirst();
                }
                String failure = deliver(chunk);
                if (failure != null) {
                    if (fail()) listener.onFailed(failure);
                    return;
                }
                synchronized (lock) {
                    if (!queue.isEmpty() && queue.peekFirst() == chunk) queue.removeFirst();
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }
        OutputStream s;
        synchronized (lock) {
            if (over) return;
            over = true;
            s = sink;
            sink = null;
        }
        closeQuietly(s);
        listener.onDrained();
    }

    /** Feeder thread: writes one chunk, across one sink swap if need be; null, or why it failed. */
    private String deliver(byte[] chunk) throws InterruptedException {
        while (true) {
            OutputStream s;
            long gen;
            synchronized (lock) {
                long deadline = System.currentTimeMillis() + swapGraceMs;
                while (!over && sink == null) {
                    long left = deadline - System.currentTimeMillis();
                    if (left <= 0) return "no pipe to the recognizer";
                    lock.wait(left);
                }
                if (over) return null;
                s = sink;
                gen = generation;
            }
            try {
                s.write(chunk);
                s.flush();
                return null;
            } catch (IOException e) {
                // A recognizer that restarts closes its old pipe: wait for the new one.
                synchronized (lock) {
                    long deadline = System.currentTimeMillis() + swapGraceMs;
                    while (!over && generation == gen) {
                        long left = deadline - System.currentTimeMillis();
                        if (left <= 0) {
                            return "pipe write failed (" + e.getClass().getSimpleName() + ": "
                                    + e.getMessage() + ")";
                        }
                        lock.wait(left);
                    }
                    if (over) return null;
                }
            }
        }
    }

    /** Marks the feeder failed; false if it was already over (cancelled). */
    private boolean fail() {
        OutputStream s;
        synchronized (lock) {
            if (over) return false;
            over = true;
            queue.clear();
            s = sink;
            sink = null;
        }
        closeQuietly(s);
        return true;
    }

    private static void closeQuietly(OutputStream s) {
        if (s == null) return;
        try {
            s.close();
        } catch (IOException ignored) {
            // Nothing left to release.
        }
    }
}
