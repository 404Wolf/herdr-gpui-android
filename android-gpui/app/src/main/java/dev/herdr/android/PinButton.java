package dev.herdr.android;

import android.app.Activity;
import android.content.res.ColorStateList;
import android.graphics.Rect;
import android.os.Build;
import android.view.TouchDelegate;
import android.view.View;
import android.widget.ImageButton;

/**
 * The pin at the top right of an agent's view (factory-9zz.2, P3): a small
 * pushpin glyph, no text or tooltip, drawn in the text colour (white on the
 * OLED palette): an outline when the agent is not pinned, filled when it is. It is 48dp wide; the strip it
 * sits in is 30dp tall, so its touch target is extended down to 48dp with a
 * TouchDelegate over the crumb below. TalkBack hears "Pin
 * <name> on <machine>" or "Unpin …" and the pressed state.
 */
final class PinButton {
    /**
     * Its width beside the ⋮ (also {@link #WIDTH_DP} wide): narrow enough that the glyphs of
     * "updated …", the pin and the ⋮ sit at the same small gap (Wolf 11-22); the 48 dp target
     * reaches below the bar instead.
     */
    static final int WIDTH_DP = 32, TARGET_DP = 48;
    /** The gap between "updated …" and the pin's glyph, the same as pin to ⋮ (32 - 20)/2 + (32 - 24)/2. */
    static final int GLYPH_GAP_DP = 10;

    /** What it says: "Pin phone-rail on desktop", or why it can't yet. */
    static String described(boolean pinned, String name, String machine, String cant) {
        if (cant != null && !cant.isEmpty()) return "Can't pin yet: " + cant;
        return (pinned ? "Unpin " : "Pin ") + name + " on " + machine;
    }

    private final Activity activity;
    private final GpuiChrome chrome;
    private final ImageButton button;
    private boolean pinned;
    /** The strip whose crumb extends its target; elsewhere it has no extension. */
    private View home;

    PinButton(Activity activity, GpuiChrome chrome, Runnable toggle) {
        this.activity = activity;
        this.chrome = chrome;
        button = new ImageButton(activity);
        button.setBackground(ActionSheet.ripple(chrome));
        button.setScaleType(android.widget.ImageView.ScaleType.CENTER);
        button.setVisibility(View.GONE);
        button.setOnClickListener(v -> toggle.run());
        bind(false, false, "", "", null);
    }

    View view() {
        return button;
    }

    /** Its glyph's colour for the text colour {@code onSurface}: the same either way, pinned or not. */
    static int tint(int onSurface) {
        return onSurface;
    }

    /**
     * Shown on an agent's pane only ({@code show}); {@code cant} says why it
     * can't pin yet (an unproven machine), and dims it.
     */
    void bind(boolean show, boolean pinned, String name, String machine, String cant) {
        button.setVisibility(show ? View.VISIBLE : View.GONE);
        if (!show) return;
        boolean changed = this.pinned != pinned;
        this.pinned = pinned;
        button.setImageResource(pinned ? R.drawable.ic_pin_filled : R.drawable.ic_pin_outline);
        // Wolf: white, not grey; the outline/filled shape keeps pinned apart from not pinned.
        button.setImageTintList(ColorStateList.valueOf(tint(chrome.onSurface)));
        boolean able = cant == null || cant.isEmpty();
        button.setEnabled(able);
        button.setAlpha(able ? 1f : 0.4f);
        button.setSelected(pinned);
        button.setContentDescription(described(pinned, name, machine, cant));
        if (Build.VERSION.SDK_INT >= 30) button.setStateDescription(pinned ? "pinned" : "not pinned");
        if (changed && button.isAttachedToWindow()) {
            button.announceForAccessibility((pinned ? "Pinned " : "Unpinned ") + name);
        }
    }

    /**
     * Completes the 48dp target downwards: {@code below} (the crumb under the
     * strip, which starts at the strip's left edge) hands touches in the
     * button's columns, in its top {@code TARGET_DP - strip height}, to the
     * button. A TouchDelegate on the view the finger lands on is the one Android
     * consults first, so the crumb's own tap keeps the rest of its width.
     */
    void extendTouch(View below, View... beside) {
        home = (View) button.getParent();
        this.beside = beside;
        below.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> extend(below));
        button.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> extend(below));
        for (View other : beside) other.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> extend(below));
    }

    /** Buttons beside the pin in the same strip whose targets reach down as well (the ⋮). */
    private View[] beside = new View[0];

    /** Several delegates on one view: the one whose rect the finger went down in takes the gesture. */
    static final class Delegates extends TouchDelegate {
        private final java.util.List<TouchDelegate> all;
        private TouchDelegate taking;

        Delegates(java.util.List<TouchDelegate> all, View any) {
            super(new Rect(), any);
            this.all = all;
        }

        @Override public boolean onTouchEvent(android.view.MotionEvent event) {
            if (event.getActionMasked() == android.view.MotionEvent.ACTION_DOWN) {
                taking = null;
                for (TouchDelegate d : all) {
                    if (d.onTouchEvent(event)) { taking = d; return true; }
                }
                return false;
            }
            return taking != null && taking.onTouchEvent(event);
        }
    }

    private void extend(View below) {
        View strip = (View) button.getParent();
        // Docked in the work panel's header (the unfolded split), the crumb is not under it.
        if (button.getVisibility() != View.VISIBLE || strip == null || strip != home) {
            below.setTouchDelegate(null);
            return;
        }
        float density = activity.getResources().getDisplayMetrics().density;
        int reach = Math.max(0, Math.round(TARGET_DP * density) - strip.getHeight());
        if (reach == 0) {
            below.setTouchDelegate(null);
            return;
        }
        java.util.List<TouchDelegate> all = new java.util.ArrayList<>();
        all.add(new TouchDelegate(new Rect(strip.getLeft() + button.getLeft(), 0, strip.getLeft() + button.getRight(), reach), button));
        for (View other : beside) {
            if (other.getParent() != strip || other.getVisibility() != View.VISIBLE) continue;
            all.add(new TouchDelegate(new Rect(strip.getLeft() + other.getLeft(), 0, strip.getLeft() + other.getRight(), reach), other));
        }
        below.setTouchDelegate(all.size() == 1 ? all.get(0) : new Delegates(all, button));
    }
}
