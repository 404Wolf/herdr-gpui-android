package dev.herdr.android;

/**
 * A remote session's failure as the native layer throws it (remote_jni `fail_with`): its kind
 * from a closed set, and the core's own kind of a refusal, so the app decides by kind and never
 * by the words (audit E). Still an IllegalStateException, so existing catches keep working.
 */
final class RemoteError extends IllegalStateException {
    /** remote.rs ErrorKind::as_str. */
    static final String INVALID = "invalid", CLOSED = "closed", BUSY = "busy", TIMEOUT = "timeout",
            CAPACITY = "capacity", REFUSED = "refused", PROTOCOL = "protocol", NETWORK = "network",
            LOCAL = "local";

    final String kind;
    /** The core's error kind of a refusal ("unavailable": its exec slots are full); "" otherwise. */
    final String coreKind;

    RemoteError(String kind, String coreKind, String message) {
        super(message);
        this.kind = kind == null ? "" : kind;
        this.coreKind = coreKind == null ? "" : coreKind;
    }

    /** The kind of {@code failure}, or "" when it is not a RemoteError. */
    static String kindOf(Throwable failure) {
        return failure instanceof RemoteError ? ((RemoteError) failure).kind : "";
    }

    /** Busy, not broken: worth a retry with backoff (a busy session, or a core out of exec slots). */
    static boolean busy(Throwable failure) {
        if (!(failure instanceof RemoteError)) return false;
        RemoteError e = (RemoteError) failure;
        return BUSY.equals(e.kind) || REFUSED.equals(e.kind) && "unavailable".equals(e.coreKind);
    }
}
