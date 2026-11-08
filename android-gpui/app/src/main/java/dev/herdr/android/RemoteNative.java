package dev.herdr.android;

import android.content.Context;

/** Native remote connection boundary. No password is written to storage, logs or Intents. */
final class RemoteNative {
    private static boolean loaded;
    private RemoteNative() { }

    static synchronized void ensureLoaded(Context context) {
        if (loaded) return;
        System.loadLibrary("herdr_gpui_android");
        initialize(context.getApplicationContext());
        loaded = true;
    }

    static native void initialize(Context context);
    static native String discover(long handle);
    static native String inspect(String configJson);
    /**
     * {"endpoint_id", "endpoint_ticket", "relay_url"} for a pasted endpoint ID or ticket and an
     * optional relay URL. Parsing only: safe on the UI thread.
     */
    static native String irohTarget(String text, String relayUrl);
    static native long connect(String configJson, String credentialJson);

    /** Reports the client grid; metadata-only host mirrors do not request terminal surfaces. */
    static native long connectGrid(String configJson, String credentialJson, String grid,
            boolean surfaceActive);
    static native String poll(long handle, long sinceRevision, boolean mirror);
    /** Paints the native body and returns metadata only; zero tokens refresh frame/navigation. */
    static native String pollTerminal(long handle, long sinceRevision, long sinceChromeRevision,
            long generation, String hostName, String shortcuts);
    static native String input(long handle, String paneId, String commandJson);
    static native String focus(long handle, String paneId);
    static native String focusTab(long handle, String tabId);
    static native String focusWorkspace(long handle, String workspaceId);
    static native String sessionAction(long handle, String action);
    static native String scrollTo(long handle, long offsetFromBottom);
    static native String scrollLocal(long handle, long deltaRows, long generation,
            String title, String status, String shortcuts);
    static native void close(long handle);

    /** A raw login shell on a remote PTY, drawn by the Rio terminal. Blocks for SSH: worker only. */
    static native long openShell(String configJson, String credentialJson, int columns, int rows);
    /** Sends one mailbox command; false when the shell's input queue is full. */
    static native boolean shellInput(long handle, String commandJson);
    static native boolean shellAlive(long handle);
    /** The shell's screen text for copying, or null once it is closed. */
    static native String shellText(long handle);
    /** Never blocks: the SSH connection ends on a native background thread. */
    static native void closeShell(long handle);
}
