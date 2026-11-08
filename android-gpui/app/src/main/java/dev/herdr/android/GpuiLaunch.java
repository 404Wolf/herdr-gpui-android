package dev.herdr.android;

import android.app.Activity;
import android.content.Intent;
import java.util.concurrent.TimeUnit;

/** One process-local, single-use launch grant. No connection data enters an Intent. */
public final class GpuiLaunch {
    private static final long MAX_PENDING_NANOS = TimeUnit.SECONDS.toNanos(30);
    private static Session pending;
    private static Session active;

    private GpuiLaunch() { }

    /** Used reflectively by the shared launcher; the Java-only build has no dependency here. */
    public static void launch(Activity activity, SavedHost host, String paneId, String title) {
        if (activity == null) throw new IllegalArgumentException("Activity is required");
        try {
            // Resolve ABI/dependency failures while the caller can still show a recoverable error.
            System.loadLibrary("herdr_gpui_android");
        } catch (LinkageError unavailable) {
            throw new IllegalStateException("The GPUI renderer could not be loaded");
        }
        Session grant = reserve(host, paneId, title, System.nanoTime());
        try {
            activity.startActivity(new Intent(activity, GpuiTerminalActivity.class));
        } catch (RuntimeException failure) {
            release(grant);
            throw failure;
        }
    }

    /** Whether a terminal holds (or is about to take) the one GPUI session. */
    static boolean terminalOpen() {
        synchronized (GpuiLaunch.class) {
            discardExpired(System.nanoTime());
            return pending != null || active != null;
        }
    }

    static void launchRemote(Activity activity, SavedRemoteHost host, long handle, String paneId, String title) {
        if (activity == null || host == null || handle <= 0) throw new IllegalArgumentException("Remote session is required");
        RemoteNative.ensureLoaded(activity);
        final Session grant;
        synchronized (GpuiLaunch.class) {
            discardExpired(System.nanoTime());
            if (pending != null || active != null) throw new IllegalStateException("Close the open GPUI terminal first");
            validatePane(paneId);
            grant = new Session(host, handle, paneId, GpuiTerminalProtocol.title(title), System.nanoTime());
            pending = grant;
        }
        try { activity.startActivity(new Intent(activity, GpuiTerminalActivity.class)); }
        catch (RuntimeException failure) {
            // Ownership stays with caller when Activity launch fails.
            synchronized (GpuiLaunch.class) { grant.remoteHandle = 0; release(grant); }
            throw failure;
        }
    }

    /** The host's own login shell instead of a Herdr pane; the terminal connects it. */
    static void launchShell(Activity activity, SavedRemoteHost host) {
        if (activity == null || host == null) throw new IllegalArgumentException("Remote host is required");
        RemoteNative.ensureLoaded(activity);
        final Session grant;
        synchronized (GpuiLaunch.class) {
            discardExpired(System.nanoTime());
            if (pending != null || active != null) throw new IllegalStateException("Close the open GPUI terminal first");
            grant = new Session(host, GpuiTerminalProtocol.title(host.name), System.nanoTime());
            pending = grant;
        }
        try { activity.startActivity(new Intent(activity, GpuiTerminalActivity.class)); }
        catch (RuntimeException failure) {
            release(grant);
            throw failure;
        }
    }

    private static void validatePane(String paneId) {
        if (paneId == null || paneId.length() > 128
                || !paneId.matches("[A-Za-z0-9_./:-]+")) {
            throw new IllegalArgumentException("Invalid pane identifier");
        }
    }

    static synchronized Session reserve(SavedHost host, String paneId, String title, long now) {
        discardExpired(now);
        if (pending != null || active != null) {
            throw new IllegalStateException("Close the open GPUI terminal before opening another");
        }
        if (host == null) throw new IllegalArgumentException("Host is required");
        validatePane(paneId);
        pending = new Session(host, paneId, GpuiTerminalProtocol.title(title), now);
        return pending;
    }

    static synchronized Session consume(long now) {
        discardExpired(now);
        if (active != null || pending == null) return null;
        active = pending;
        pending = null;
        return active;
    }

    static synchronized void release(Session session) {
        if (session != null && session.remoteHandle != 0) {
            RemoteNative.close(session.remoteHandle);
            session.remoteHandle = 0;
        }
        if (pending == session) pending = null;
        if (active == session) active = null;
    }

    private static void discardExpired(long now) {
        if (pending != null && now - pending.createdAt >= MAX_PENDING_NANOS) release(pending);
    }

    static final class Session {
        final SavedHost host;
        final SavedRemoteHost remoteHost;
        long remoteHandle;
        final String paneId;
        final String title;
        final long createdAt;
        /** A raw SSH shell: no Herdr daemon, panes or agent status. */
        final boolean shell;

        Session(SavedHost host, String paneId, String title, long createdAt) {
            this.host = host;
            this.remoteHost = null;
            this.paneId = paneId;
            this.title = title;
            this.createdAt = createdAt;
            this.shell = false;
        }

        Session(SavedRemoteHost host, long handle, String paneId, String title, long now) {
            this.host = null;
            this.remoteHost = host;
            this.remoteHandle = handle;
            this.paneId = paneId;
            this.title = title;
            this.createdAt = now;
            this.shell = false;
        }

        Session(SavedRemoteHost host, String title, long now) {
            this.host = null;
            this.remoteHost = host;
            this.paneId = "";
            this.title = title;
            this.createdAt = now;
            this.shell = true;
        }

        synchronized long takeRemoteHandle() {
            long result = remoteHandle;
            remoteHandle = 0;
            return result;
        }

        String name() { return remoteHost == null ? host.profile.getName() : remoteHost.name; }
        String shortcuts() { return remoteHost == null ? host.shortcuts : remoteHost.shortcuts; }
    }
}
