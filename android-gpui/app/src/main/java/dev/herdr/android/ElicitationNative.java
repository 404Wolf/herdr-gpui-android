package dev.herdr.android;

/**
 * Agent question dialogs read from a pane's live grid (Rust {@code elicitation.rs}). Read-only:
 * nothing here sends input. Both calls lock the pane's grid briefly and scan its bottom rows,
 * cheap enough for the UI thread. The library is loaded by {@link RemoteNative#ensureLoaded}.
 */
final class ElicitationNative {
    private ElicitationNative() { }

    /**
     * The dialog on the pane drawn by {@code renderHandle} (the handle the activity attaches as
     * the shell), as JSON, or null when none is on screen. {@code kind} is claude, codex, gemini
     * or other.
     */
    static native String parse(long renderHandle, String kind);

    /** Whether {@code renderHandle} draws a pane now (false offline, or between attaches). */
    static native boolean attached(long renderHandle);

    /**
     * The next step toward {@code goalJson}: {@code {"step":…, "progress":…, "dialog":…}}.
     * {@code progressJson} is the previous reply's progress, or "" to start.
     */
    static native String step(long renderHandle, String kind, String goalJson, String progressJson);
}
