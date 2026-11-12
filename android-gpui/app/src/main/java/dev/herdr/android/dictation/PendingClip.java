package dev.herdr.android.dictation;

/** A dictation recorded while the desktop was away, saved until it is transcribed. */
public final class PendingClip {
    /** The longest {@code target} kept; a longer one is cut. */
    public static final int MAX_TARGET_CHARS = 512;

    /** Unique, stable across restarts: what the composer's placeholder refers to. */
    public final String id;
    /** What the session was started with, e.g. the host and pane it was spoken for. */
    public final String target;
    /** Wall-clock time the recording started, in epoch milliseconds. */
    public final long createdAt;
    public final long durationMs;

    PendingClip(String id, String target, long createdAt, long durationMs) {
        this.id = id;
        this.target = target;
        this.createdAt = createdAt;
        this.durationMs = durationMs;
    }

    static String cutTarget(String target) {
        if (target == null) return "";
        return target.length() > MAX_TARGET_CHARS ? target.substring(0, MAX_TARGET_CHARS) : target;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof PendingClip && ((PendingClip) o).id.equals(id);
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }
}
