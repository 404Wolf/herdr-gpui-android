package dev.herdr.android;

import android.animation.ValueAnimator;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.drawable.RippleDrawable;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;

/**
 * The fold control in front of a Deacon or PM row of the rail: a 48dp column
 * the full height of the row, with a real chevron on an always-visible tonal
 * square, so it reads as a button and not as a count. Tapping it folds only;
 * the rest of the row keeps focusing the agent. A lead row with nothing to
 * fold keeps the empty column so titles line up and a first worker never
 * shifts the row. A hairline on its trailing edge says these are two targets.
 */
final class RailDisclosure extends View {
    /** Rotation of the chevron while folded (pointing right) and open (pointing down). */
    static final float FOLDED_DEGREES = 0f, OPEN_DEGREES = 90f;
    static final long SPIN_MS = 150;

    private final float density;
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tone = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint hairline = new Paint();
    private final Path chevron = new Path();
    private final RectF square = new RectF();
    private boolean shown;
    private boolean folded = true;
    private float degrees = FOLDED_DEGREES;
    private ValueAnimator spin;

    RailDisclosure(Context context, GpuiChrome chrome) {
        super(context);
        density = context.getResources().getDisplayMetrics().density;
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(2 * density);
        stroke.setStrokeCap(Paint.Cap.ROUND);
        stroke.setStrokeJoin(Paint.Join.ROUND);
        stroke.setColor(chrome.onSurface);
        tone.setColor(GpuiChrome.withAlpha(chrome.onSurfaceVariant, 0x1F));
        hairline.setColor(GpuiChrome.withAlpha(chrome.outline, 0x66));
        RippleDrawable ripple = new RippleDrawable(
                ColorStateList.valueOf(GpuiChrome.withAlpha(chrome.primary, 0x40)), null, null);
        ripple.setRadius(Math.round(22 * density));
        setForeground(ripple);
        setSaveFromParentEnabled(false);
        bind(false, true, null);
    }

    /**
     * Shows the chevron for a row that folds something ({@code says} is what a
     * screen reader hears, e.g. "Show 3 rows under factory"), or the empty
     * column when {@code says} is null. A change of {@code folded} on a shown
     * control turns the chevron over {@link #SPIN_MS}.
     */
    void bind(boolean show, boolean fold, String says) {
        boolean turn = shown && show && folded != fold;
        shown = show;
        folded = fold;
        // Clickable either way: an empty column's tap is the row's (its listener says so).
        setFocusable(show);
        setImportantForAccessibility(show ? IMPORTANT_FOR_ACCESSIBILITY_YES : IMPORTANT_FOR_ACCESSIBILITY_NO);
        setContentDescription(show ? says : null);
        setStateDescription(show ? FleetTree.foldState(fold) : null);
        float target = fold ? FOLDED_DEGREES : OPEN_DEGREES;
        if (spin != null) spin.cancel();
        if (turn && isAttachedToWindow()) {
            spin = ValueAnimator.ofFloat(degrees, target).setDuration(SPIN_MS);
            spin.addUpdateListener(a -> {
                degrees = (float) a.getAnimatedValue();
                invalidate();
            });
            spin.start();
        } else {
            degrees = target;
            invalidate();
        }
    }

    boolean shown() {
        return shown;
    }

    boolean folded() {
        return folded;
    }

    /**
     * Offers Expand or Collapse on the whole {@code row}, so TalkBack users can
     * fold it without finding the column. The action taps this control.
     */
    void offerOn(View row) {
        row.setAccessibilityDelegate(new View.AccessibilityDelegate() {
            @Override public void onInitializeAccessibilityNodeInfo(View host, AccessibilityNodeInfo info) {
                super.onInitializeAccessibilityNodeInfo(host, info);
                if (!shown) return;
                info.addAction(folded ? AccessibilityNodeInfo.AccessibilityAction.ACTION_EXPAND
                        : AccessibilityNodeInfo.AccessibilityAction.ACTION_COLLAPSE);
            }

            @Override public boolean performAccessibilityAction(View host, int action, android.os.Bundle args) {
                boolean fold = action == AccessibilityNodeInfo.AccessibilityAction.ACTION_COLLAPSE.getId();
                boolean unfold = action == AccessibilityNodeInfo.AccessibilityAction.ACTION_EXPAND.getId();
                if (shown && ((fold && !folded) || (unfold && folded))) return performClick();
                return super.performAccessibilityAction(host, action, args);
            }
        });
    }

    @Override protected void onDetachedFromWindow() {
        if (spin != null) spin.end();
        super.onDetachedFromWindow();
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float w = getWidth(), h = getHeight();
        canvas.drawRect(w - density, h * 0.2f, w, h * 0.8f, hairline);
        if (!shown) return;
        float cx = (w - density) / 2f, cy = h / 2f;
        float half = 16 * density;
        square.set(cx - half, cy - half, cx + half, cy + half);
        canvas.drawRoundRect(square, 8 * density, 8 * density, tone);
        // An 18dp chevron "›": arms 4dp across, 8dp tall, centred on the square.
        float arm = 4 * density;
        chevron.rewind();
        chevron.moveTo(cx - arm / 2f, cy - arm);
        chevron.lineTo(cx + arm / 2f, cy);
        chevron.lineTo(cx - arm / 2f, cy + arm);
        canvas.save();
        canvas.rotate(degrees, cx, cy);
        canvas.drawPath(chevron, stroke);
        canvas.restore();
    }
}
