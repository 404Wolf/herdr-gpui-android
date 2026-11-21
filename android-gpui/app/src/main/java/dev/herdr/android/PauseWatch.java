package dev.herdr.android;

import android.app.Activity;
import android.app.Application;
import android.os.Bundle;

/**
 * Tells a view that polls its machine when its activity pauses and resumes,
 * so nothing reads in the background (each read holds the core session, and
 * typing, until it answers). Registered on the activity itself, so it ends
 * with it.
 */
final class PauseWatch implements Application.ActivityLifecycleCallbacks {
    private final Runnable paused, resumed;

    private PauseWatch(Runnable paused, Runnable resumed) {
        this.paused = paused;
        this.resumed = resumed;
    }

    static PauseWatch on(Activity activity, Runnable paused, Runnable resumed) {
        PauseWatch watch = new PauseWatch(paused, resumed);
        activity.registerActivityLifecycleCallbacks(watch);
        return watch;
    }

    void stop(Activity activity) {
        activity.unregisterActivityLifecycleCallbacks(this);
    }

    @Override public void onActivityPaused(Activity activity) { paused.run(); }
    @Override public void onActivityResumed(Activity activity) { resumed.run(); }
    @Override public void onActivityCreated(Activity activity, Bundle state) { }
    @Override public void onActivityStarted(Activity activity) { }
    @Override public void onActivityStopped(Activity activity) { }
    @Override public void onActivitySaveInstanceState(Activity activity, Bundle state) { }
    @Override public void onActivityDestroyed(Activity activity) { }
}
