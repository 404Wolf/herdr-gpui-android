package dev.herdr.android;

import android.app.Activity;
import android.graphics.Rect;
import java.lang.reflect.Method;
import java.util.List;
import java.util.function.Consumer;

/**
 * A half-open fold running across this window: the posture Android calls
 * tabletop. It comes from the device's own androidx.window.extensions library,
 * the source Jetpack WindowManager reads. The manifest loads that library as
 * optional, so a device without it never reports a fold.
 */
final class FoldPosture {
    /** {@code FoldingFeature.STATE_HALF_OPENED} in the extensions contract. */
    static final int STATE_HALF_OPENED = 2;
    static final FoldPosture FLAT = new FoldPosture(false, 0, 0);

    /** The device stands like a small laptop, its fold across this window. */
    final boolean tabletop;
    /** The fold's edges in window coordinates; equal for a seamless fold. */
    final int foldTop, foldBottom;

    private FoldPosture(boolean tabletop, int foldTop, int foldBottom) {
        this.tabletop = tabletop;
        this.foldTop = foldTop;
        this.foldBottom = foldBottom;
    }

    /**
     * Classifies one folding feature. A half-open fold that runs down the
     * window is book posture, which this layout does not split: halving the
     * terminal's width would cost more than the angled page.
     */
    static FoldPosture of(int state, int left, int top, int right, int bottom) {
        if (state != STATE_HALF_OPENED || right - left <= bottom - top) return FLAT;
        return new FoldPosture(true, top, bottom);
    }

    /**
     * Height the terminal keeps above the fold, or -1 when a split does not
     * fit: at least {@code minAbove} pixels of content must stay above it and
     * {@code minBelow} below, so an open keyboard restores the normal layout.
     */
    int heightAbove(int contentTop, int contentBottom, int minAbove, int minBelow) {
        if (!tabletop) return -1;
        int above = foldTop - contentTop;
        return above >= minAbove && contentBottom - foldBottom >= minBelow ? above : -1;
    }

    @Override public boolean equals(Object other) {
        if (!(other instanceof FoldPosture)) return false;
        FoldPosture that = (FoldPosture) other;
        return tabletop == that.tabletop && foldTop == that.foldTop && foldBottom == that.foldBottom;
    }

    @Override public int hashCode() {
        return (tabletop ? 1 : 0) + 31 * foldTop + 961 * foldBottom;
    }

    /** Fold updates for one activity, registered while it is started. */
    /**
     * Which deliveries are a physical fold, so Wolf using the phone (item 71):
     * a change of posture, not the watcher's first delivery after it starts,
     * which only says how the phone already was. One per watcher.
     */
    static final class Uses {
        private boolean heard;

        boolean isUse(FoldPosture before, FoldPosture after) {
            boolean first = !heard;
            heard = true;
            return !first && !after.equals(before);
        }
    }

    static final class Watcher {
        private final Object component;
        private final Method remove;
        private final Consumer<Object> listener;

        private Watcher(Object component, Method remove, Consumer<Object> listener) {
            this.component = component;
            this.remove = remove;
            this.listener = listener;
        }

        /**
         * Starts delivering postures to {@code update}, or returns null when the
         * device has no usable extensions. The library is a platform contract
         * versioned by its vendor API level, read here by name because this app
         * deliberately ships without AndroidX.
         */
        static Watcher start(Activity activity, Consumer<FoldPosture> update) {
            try {
                Class<?> extensionsType = Class.forName("androidx.window.extensions.WindowExtensions");
                Object extensions = Class.forName("androidx.window.extensions.WindowExtensionsProvider")
                        .getMethod("getWindowExtensions").invoke(null);
                if (extensions == null
                        || (Integer) extensionsType.getMethod("getVendorApiLevel").invoke(extensions) < 1) {
                    return null;
                }
                Object component = extensionsType.getMethod("getWindowLayoutComponent").invoke(extensions);
                if (component == null) return null;
                Class<?> componentType = Class.forName("androidx.window.extensions.layout.WindowLayoutComponent");
                Class<?> foldType = Class.forName("androidx.window.extensions.layout.FoldingFeature");
                Method features = Class.forName("androidx.window.extensions.layout.WindowLayoutInfo")
                        .getMethod("getDisplayFeatures");
                Method bounds = foldType.getMethod("getBounds");
                Method state = foldType.getMethod("getState");
                Consumer<Object> listener = info -> update.accept(read(info, features, foldType, bounds, state));
                componentType.getMethod("addWindowLayoutInfoListener", Activity.class, Consumer.class)
                        .invoke(component, activity, listener);
                return new Watcher(component,
                        componentType.getMethod("removeWindowLayoutInfoListener", Consumer.class), listener);
            } catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) {
                // No extensions, or a contract this build does not know: stay flat.
                return null;
            }
        }

        void stop() {
            try {
                remove.invoke(component, listener);
            } catch (ReflectiveOperationException | RuntimeException ignored) {
                // The listener dies with the activity either way.
            }
        }

        private static FoldPosture read(Object info, Method features, Class<?> foldType, Method bounds,
                Method state) {
            try {
                for (Object feature : (List<?>) features.invoke(info)) {
                    if (!foldType.isInstance(feature)) continue;
                    Rect rect = (Rect) bounds.invoke(feature);
                    FoldPosture posture = of((Integer) state.invoke(feature),
                            rect.left, rect.top, rect.right, rect.bottom);
                    if (posture.tabletop) return posture;
                }
            } catch (ReflectiveOperationException | RuntimeException unreadable) {
                // An unreadable report is treated as flat rather than guessed at.
            }
            return FLAT;
        }
    }
}
