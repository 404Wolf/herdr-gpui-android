package dev.herdr.android;

/**
 * Which computer pick is current. An attempt to reach a computer (a reconnect
 * or a switch) takes {@link #now()} when it starts; every new pick calls
 * {@link #change()}. An attempt whose token is {@link #stale(int)} when it
 * finishes is for a computer no longer picked: it must not adopt its host,
 * change the link state or start the monitor over. Written on the main thread,
 * read from workers.
 */
final class HostEpoch {
    private volatile int epoch;

    int now() {
        return epoch;
    }

    /** Another computer was picked (or the same one again, from scratch). */
    void change() {
        epoch++;
    }

    boolean stale(int token) {
        return token != epoch;
    }
}
