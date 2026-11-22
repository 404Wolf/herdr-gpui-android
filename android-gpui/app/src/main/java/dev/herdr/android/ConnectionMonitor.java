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
 * supplies on the main thread: at once when a network appears or validates,
 * and on {@link ReconnectPolicy}'s ladder after each failure, a bounded number
 * of times per network (an epoch). With no network at all nothing is tried:
 * the network's return starts the next attempt. It never runs between
 * {@link #stop()} and {@link #start()}, so a backgrounded terminal spends no
 * battery or data.
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

    /** What the monitor asks of the activity. Main thread, every one. */
    interface Listener {
        /** Runs one reconnect, closing any still running first; it reports {@link #succeeded()} or {@link #failed()}. */
        void attempt();

        /** Any network change: re-check a live link and the indicator. */
        void changed();

        /** No network for {@link #GONE_GRACE_MS}: a live link is dead now, without waiting for its silence. */
        void gone();

        /** The epoch's attempts are spent: nothing more runs until a network arrives or the user asks. */
        void exhausted();
    }

    private final ConnectivityManager connectivity;
    private final Handler main;
    private final Listener listener;
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
    /** When a network last arrived or validated (uptime ms), 0 for never: reconnects are timed from it. */
    private volatile long arrivedAt;

    ConnectionMonitor(Context context, Handler main, Listener listener) {
        this.connectivity = context.getSystemService(ConnectivityManager.class);
        this.main = main;
        this.listener = listener;
    }

    private final ConnectivityManager.NetworkCallback physicalCallback = new ConnectivityManager.NetworkCallback() {
        @Override public void onAvailable(Network network) {
            main.post(() -> {
                seenPhysical = true;
                noNetwork = false;
                main.removeCallbacks(goneCheck);
                if (physical.add(network)) networkChanged(true);
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
                networkChanged(false);
            });
        }
    };

    private final ConnectivityManager.NetworkCallback defaultCallback = new ConnectivityManager.NetworkCallback() {
        private boolean validated;

        @Override public void onAvailable(Network network) {
            main.post(() -> networkChanged(true));
        }

        @Override public void onCapabilitiesChanged(Network network, NetworkCapabilities caps) {
            boolean now = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
            if (now == validated) return;
            validated = now;
            // A path that just validated (the VPN found its peers again) is
            // the moment a waiting reconnect can succeed.
            if (now) main.post(() -> networkChanged(true));
        }
    };

    /** The terminal is on screen. Main thread. */
    void start() {
        if (started || connectivity == null) return;
        started = true;
        policy.reset();
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
        next();
    }

    /** An attempt failed: try again after the next step, while the epoch lasts. Main thread. */
    void failed() {
        wanted = true;
        next();
    }

    /** The user asked (Reconnect): a new epoch, tried at once. Main thread. */
    void retry() {
        wanted = true;
        policy.reset();
        next();
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

    /** Whether this epoch's attempts are spent: the indicator offers Reconnect. Main thread. */
    boolean exhausted() {
        return wanted && policy.exhausted();
    }

    /** Attempts made since the link was last up. */
    int attempts() {
        return policy.attempts();
    }

    /** When a network last arrived or validated (uptime ms), 0 for never. Any thread. */
    long arrivedAt() {
        return arrivedAt;
    }

    /** Whether the phone has any network to reconnect over. */
    boolean offline() {
        return started && (noNetwork || physical.isEmpty() && connectivity != null
                && connectivity.getActiveNetwork() == null);
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

    /**
     * A network changed. One that arrived or validated starts a new epoch with an immediate
     * attempt, which closes an attempt still hanging on the old path (factory-9zz.1: the
     * return of the network was dropped while a doomed attempt ran). Main thread.
     */
    private void networkChanged(boolean arrived) {
        if (!started) return;
        long now = SystemClock.uptimeMillis();
        changedAt = now;
        if (arrived) {
            arrivedAt = now;
            android.util.Log.i("herdr-net", "network arrived or validated");
            if (wanted) {
                policy.reset();
                next();
            }
        }
        listener.changed();
    }

    /** Schedules the epoch's next attempt, or says it is spent. Main thread. */
    private void next() {
        long delay = policy.next(SystemClock.uptimeMillis());
        if (delay == ReconnectPolicy.EXHAUSTED) {
            main.removeCallbacks(fire);
            android.util.Log.i("herdr-reconnect", "stopped after " + policy.attempts() + " attempts");
            listener.exhausted();
            return;
        }
        schedule(delay);
    }

    /** Still no network after the grace: the live link is gone. Main thread. */
    private void goneCheck() {
        if (!started || !physical.isEmpty()) return;
        android.util.Log.i("herdr-net", "no network");
        listener.gone();
    }

    private void schedule(long delayMs) {
        main.removeCallbacks(fire);
        if (started) main.postDelayed(fire, delayMs);
    }

    private void fire() {
        if (!started || !wanted) return;
        // No network: nothing to try over. Its return starts the next attempt; the
        // epoch keeps the attempts it has left.
        if (noNetwork) return;
        listener.attempt();
    }
}
