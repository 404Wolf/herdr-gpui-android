package dev.herdr.android;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.os.Handler;
import android.os.SystemClock;

import java.util.HashSet;
import java.util.Set;

/**
 * Keeps a remote terminal connected while it is on screen.
 *
 * Watches the phone's networks and runs the reconnect attempt the activity
 * supplies on the main thread: at once when a network appears or changes, and
 * on {@link ReconnectPolicy}'s quick ladder after each failure. It never runs
 * between {@link #stop()} and {@link #start()}, so a backgrounded terminal
 * spends no battery or data.
 *
 * Two callbacks, because Tailscale is a VPN: the default network stays the
 * VPN while Wi-Fi gives way to cellular underneath it, so only the physical
 * networks show the handover that strands the old socket.
 */
final class ConnectionMonitor {
    /** After a network change, how long a short silence is treated as a dead link. */
    private static final long CHANGE_WINDOW_MS = 8_000;
    /**
     * How long the phone must have no network before the live link is given up: a handover
     * that loses Wi-Fi a moment before cellular is up is not an outage.
     */
    static final long GONE_GRACE_MS = 500;

    private final ConnectivityManager connectivity;
    private final Handler main;
    private final Runnable attempt;
    private final Runnable changed;
    private final Runnable gone;
    private final ReconnectPolicy policy = new ReconnectPolicy();
    private final Set<Network> physical = new HashSet<>();
    private final Runnable fire = this::fire;
    private final Runnable goneCheck = this::goneCheck;
    /** A physical network was seen since start: an empty set means they were lost, not unknown yet. */
    private boolean seenPhysical;
    /** Every physical network was lost and none came back yet: the link cannot be up. Any thread. */
    private volatile boolean noNetwork;
    private boolean started;
    private boolean wanted;
    private volatile long changedAt;

    /**
     * @param attempt runs one reconnect; it must report back with
     *     {@link #succeeded()} or {@link #failed()}
     * @param changed runs on any network change, to re-check a live link
     * @param gone runs when the phone has had no network for {@link #GONE_GRACE_MS}: a live
     *     link is dead now, without waiting for its silence to say so
     */
    ConnectionMonitor(Context context, Handler main, Runnable attempt, Runnable changed, Runnable gone) {
        this.connectivity = context.getSystemService(ConnectivityManager.class);
        this.main = main;
        this.attempt = attempt;
        this.changed = changed;
        this.gone = gone;
    }

    private final ConnectivityManager.NetworkCallback physicalCallback = new ConnectivityManager.NetworkCallback() {
        @Override public void onAvailable(Network network) {
            main.post(() -> {
                seenPhysical = true;
                noNetwork = false;
                main.removeCallbacks(goneCheck);
                if (physical.add(network)) networkChanged();
            });
        }

        @Override public void onLost(Network network) {
            main.post(() -> {
                if (!physical.remove(network)) return;
                if (physical.isEmpty() && seenPhysical) {
                    // In doubt at once (a drag sends nothing from now on); the live link is
                    // given up only after the grace, so a handover gap costs no reconnect.
                    noNetwork = true;
                    main.removeCallbacks(goneCheck);
                    main.postDelayed(goneCheck, GONE_GRACE_MS);
                }
                networkChanged();
            });
        }
    };

    private final ConnectivityManager.NetworkCallback defaultCallback = new ConnectivityManager.NetworkCallback() {
        private boolean validated;

        @Override public void onAvailable(Network network) {
            main.post(ConnectionMonitor.this::networkChanged);
        }

        @Override public void onCapabilitiesChanged(Network network, NetworkCapabilities caps) {
            boolean now = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
            if (now == validated) return;
            validated = now;
            // A path that just validated (the VPN found its peers again) is
            // the moment a waiting reconnect can succeed.
            if (now) main.post(ConnectionMonitor.this::networkChanged);
        }
    };

    /** The terminal is on screen. Main thread. */
    void start() {
        if (started || connectivity == null) return;
        started = true;
        try {
            connectivity.registerNetworkCallback(new NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .build(), physicalCallback, main);
            connectivity.registerDefaultNetworkCallback(defaultCallback, main);
        } catch (RuntimeException failure) {
            // Without callbacks the ladder still reconnects; it just cannot
            // jump ahead on a network change.
            android.util.Log.w("herdr-reconnect", "network callbacks unavailable: " + failure);
        }
        if (wanted) schedule(0);
    }

    /** The terminal left the screen: no attempts until {@link #start()}. Main thread. */
    void stop() {
        if (!started) return;
        started = false;
        main.removeCallbacks(fire);
        main.removeCallbacks(goneCheck);
        physical.clear();
        seenPhysical = false;
        noNetwork = false;
        try {
            connectivity.unregisterNetworkCallback(physicalCallback);
        } catch (RuntimeException ignored) {
            // Never registered.
        }
        try {
            connectivity.unregisterNetworkCallback(defaultCallback);
        } catch (RuntimeException ignored) {
            // Never registered.
        }
    }

    /**
     * The session dropped: reconnect now, then keep trying. A session that
     * drops again before it was ever ready continues the ladder instead of
     * restarting it, so a host that accepts and then hangs up is not hammered.
     * Main thread.
     */
    void lost() {
        if (!wanted) {
            wanted = true;
            policy.reset();
        }
        schedule(policy.next());
    }

    /** An attempt failed: try again after the next step. Main thread. */
    void failed() {
        wanted = true;
        schedule(policy.next());
    }

    /** The session is back (or was replaced on purpose): stop trying. Main thread. */
    void succeeded() {
        wanted = false;
        policy.reset();
        main.removeCallbacks(fire);
    }

    /** Whether a reconnect is waiting or running. */
    boolean reconnecting() {
        return wanted;
    }

    /** Attempts made since the link was last up. */
    int attempts() {
        return policy.attempts();
    }

    /** Whether the phone has any network to reconnect over. */
    boolean offline() {
        return started && physical.isEmpty() && connectivity != null
                && connectivity.getActiveNetwork() == null;
    }

    /**
     * Whether a live link is in doubt, so that nothing may be sent over it (a drag scrolls the
     * phone's own rows instead of giving a full-screen agent wheel input): every network is
     * gone, or the network just changed and the link has been quiet for {@code quietMs}. Any thread.
     */
    boolean suspect(long quietMs) {
        if (noNetwork) return true;
        long since = SystemClock.uptimeMillis() - changedAt;
        return ReconnectPolicy.suspect(quietMs, changedAt != 0 && since < CHANGE_WINDOW_MS);
    }

    /** Whether a live session quiet this long should be replaced now. Any thread. */
    boolean dead(long quietMs) {
        long since = SystemClock.uptimeMillis() - changedAt;
        return ReconnectPolicy.dead(quietMs, changedAt != 0 && since < CHANGE_WINDOW_MS);
    }

    private void networkChanged() {
        if (!started) return;
        changedAt = SystemClock.uptimeMillis();
        if (wanted) {
            // A new path: the ladder starts over with an immediate attempt.
            policy.reset();
            schedule(policy.next());
        }
        changed.run();
    }

    /** Still no network after the grace: the live link is gone. Main thread. */
    private void goneCheck() {
        if (!started || !physical.isEmpty()) return;
        android.util.Log.i("herdr-net", "no network");
        gone.run();
    }

    private void schedule(long delayMs) {
        main.removeCallbacks(fire);
        if (started) main.postDelayed(fire, delayMs);
    }

    private void fire() {
        if (started && wanted) attempt.run();
    }
}
