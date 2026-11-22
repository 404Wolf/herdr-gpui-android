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
 * pushpin glyph, no text or tooltip, dim outline when the agent is not pinned
 * and filled in the accent colour when it is. It is 48dp wide; the strip it
 * sits in is 30dp tall, so its touch target is extended down to 48dp with a
 * TouchDelegate over the crumb below. TalkBack hears "Pin
 * <name> on <machine>" or "Unpin …" and the pressed state.
 */
final class PinButton {
    static final int WIDTH_DP = 48, TARGET_DP = 48;

    /** What it says: "Pin phone-rail on desktop", or why it can't yet. */
    static String described(boolean pinned, String name, String machine, String cant) {
        if (cant != null && !cant.isEmpty()) return "Can't pin yet: " + cant;
        return (pinned ? "Unpin " : "Pin ") + name + " on " + machine;
    }

    private final Activity activity;
    private final GpuiChrome chrome;
    private final ImageButton button;
    private boolean pinned;

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
        int tint = pinned ? chrome.primary : GpuiChrome.withAlpha(chrome.onSurfaceVariant, 0xB0);
        button.setImageTintList(ColorStateList.valueOf(tint));
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
    void extendTouch(View below) {
        below.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> extend(below));
        button.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> extend(below));
    }

    private void extend(View below) {
        View strip = (View) button.getParent();
        if (button.getVisibility() != View.VISIBLE || strip == null) {
            below.setTouchDelegate(null);
            return;
        }
        float density = activity.getResources().getDisplayMetrics().density;
        int reach = Math.max(0, Math.round(TARGET_DP * density) - strip.getHeight());
        Rect hit = new Rect(strip.getLeft() + button.getLeft(), 0, strip.getLeft() + button.getRight(), reach);
        below.setTouchDelegate(reach == 0 ? null : new TouchDelegate(hit, button));
    }
}
