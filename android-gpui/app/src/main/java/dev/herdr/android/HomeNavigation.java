package dev.herdr.android;

/** Main-thread navigation intent, shared by every stage of Home's asynchronous opens. */
final class HomeNavigation {
    private long epoch;

    /** A newer choice supersedes pending opens, even when the old host remains connected. */
    long begin() { return ++epoch; }

    /** The same intent can span a session action followed by focusing its returned terminal. */
    boolean complete(long intent, long handle, long currentHandle, Runnable action) {
        if (intent != epoch || handle <= 0 || handle != currentHandle) return false;
        action.run();
        return true;
    }
}
