package dev.herdr.android.dictation;

import java.io.IOException;

/** Why one upload of a waiting clip did not bring its text back, for the courier's backoff. */
final class UploadFailure extends IOException {
    private static final long serialVersionUID = 1L;

    enum Kind {
        /** Not reached, or gone before it said ready: nothing was decoded. */
        UNREACHABLE,
        /**
         * The desktop transcribes slowly or not at all: {@code ready.backend} other than "gpu"
         * (the clip was not sent), or {@code backend_slow} / {@code backend_unavailable}.
         */
        DEGRADED,
        /** It took the clip and then failed otherwise: cut off, timed out, another error. */
        FAILED
    }

    final Kind kind;
    /** The desktop received the whole clip (it may have decoded it): counts as an attempt. */
    final boolean attempted;

    UploadFailure(Kind kind, boolean attempted, String message) {
        super(message);
        this.kind = kind;
        this.attempted = attempted;
    }

    UploadFailure(Kind kind, boolean attempted, String message, Throwable cause) {
        super(message, cause);
        this.kind = kind;
        this.attempted = attempted;
    }

    /** The server's error codes that mean the desktop itself is degraded, not this clip. */
    static boolean degradedCode(String code) {
        return "backend_slow".equals(code) || "backend_unavailable".equals(code);
    }
}
