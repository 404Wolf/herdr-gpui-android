package dev.herdr.android;

import java.util.Objects;

/**
 * Which host and pane the terminal frame on screen was drawn for, so a link tapped on a held
 * frame (a reconnect, an offline or cached pane, the app coming back) opens only while that frame
 * is the selected pane's. It is recorded wherever a frame is attached: a live session painting its
 * pane, a raw shell, a saved pane drawn. Selecting another host or pane makes it mismatch until
 * that pane's frame is attached; coming back to the same pane matches at once; a frame left over
 * from before a switch never matches. A live session's links open as before. Pure; the activity
 * keeps one under its state lock.
 */
final class LinkFrame {
    private String host;
    private String pane;

    /** A frame drawn for {@code host}'s {@code pane} is on screen now. */
    void attached(String host, String pane) {
        this.host = host;
        this.pane = pane == null ? "" : pane;
    }

    /** No frame is on screen (a fresh session blanked it). */
    void cleared() {
        host = null;
        pane = null;
    }

    /**
     * Whether a link on the frame shown may open for the selected {@code host} and {@code pane}:
     * always on a live session, otherwise only on a frame drawn for that very host and pane.
     */
    boolean opens(String host, String pane, boolean live) {
        if (live) return true;
        return this.host != null && this.host.equals(host)
                && Objects.equals(this.pane, pane == null ? "" : pane);
    }
}
