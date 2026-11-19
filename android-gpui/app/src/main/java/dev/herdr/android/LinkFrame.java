package dev.herdr.android;

/**
 * Whether a link tapped in the terminal may open (or be copied) now. Every link carries the host
 * and pane of the frame it was found on: the renderer records them natively when it is installed
 * (a live session's poll, a raw shell, a saved pane), and the link takes them at the press. Java
 * acts on it only while that is the selected host and pane, however long the link took to arrive:
 * a link from a frame left on screen during a switch, or one queued before the new pane's frame
 * was drawn, opens nothing; the same pane's frame (back from the browser, a reconnect) opens at
 * once. Pure.
 */
final class LinkFrame {
    private LinkFrame() { }

    /** A host or pane id as the native side records it: null is "". */
    static String part(String id) {
        return id == null ? "" : id;
    }

    /** Whether a link found on {@code foundOn*} may act while {@code selected*} is selected. */
    static boolean selected(String foundOnHost, String foundOnPane, String selectedHost, String selectedPane) {
        return foundOnHost != null && foundOnPane != null
                && foundOnHost.equals(part(selectedHost)) && foundOnPane.equals(part(selectedPane));
    }

    /**
     * {@link #selected} for a link found on a renderer; a link that carries none (Herdr's own
     * pane rows, drawn only by a live session) acts only while the session is {@code live}.
     */
    static boolean opens(String foundOnHost, String foundOnPane, String selectedHost, String selectedPane,
            boolean live) {
        if (foundOnHost == null && foundOnPane == null) return live;
        return selected(foundOnHost, foundOnPane, selectedHost, selectedPane);
    }
}
