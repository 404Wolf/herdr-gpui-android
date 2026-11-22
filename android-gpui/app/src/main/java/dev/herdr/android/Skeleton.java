package dev.herdr.android;

import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.widget.LinearLayout;

/**
 * What loads, drawn as its own shape (Wolf): dark rounded bars where the rows
 * will be, a title line and a short meta line each, sized like the real rows
 * so nothing jumps when they arrive, with a slow, quiet pulse. No words, no
 * spinner; TalkBack hears "Loading". One implementation for Tasks, Overview,
 * a bead's plan and Usage. The bars are the palette's raised fill (#111 to
 * #1a1a1a on the OLED black).
 */
final class Skeleton extends LinearLayout {
    /** A row's shape: its height, the lead before its bars (a marker's room), and whether it has a meta line. */
    static final class Shape {
        final int rowDp, leadDp;
        final boolean meta;

        Shape(int rowDp, int leadDp, boolean meta) {
            this.rowDp = rowDp;
            this.leadDp = leadDp;
            this.meta = meta;
        }
    }

    /** The bars' sizes and the pulse (spacing tokens shared by every skeleton). */
    static final int TITLE_DP = 10, META_DP = 8, GAP_DP = 6, RADIUS_DP = 4, PULSE_MS = 1400;
    static final float DIM = 0.45f;
    /** Lengths that vary like real titles and meta lines, so it reads as a list, not a grid. */
    static final float[] TITLES = {0.72f, 0.56f, 0.84f, 0.64f, 0.48f}, METAS = {0.34f, 0.26f, 0.42f};

    private final ObjectAnimator pulse;

    Skeleton(Context context, GpuiChrome chrome, int rows, Shape shape) {
        super(context);
        setOrientation(VERTICAL);
        setContentDescription("Loading");
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
        for (int i = 0; i < rows; i++) addView(row(context, chrome, i, shape), new LayoutParams(-1, dp(shape.rowDp)));
        pulse = ObjectAnimator.ofFloat(this, View.ALPHA, 1f, DIM);
        pulse.setDuration(PULSE_MS);
        pulse.setRepeatMode(ValueAnimator.REVERSE);
        pulse.setRepeatCount(ValueAnimator.INFINITE);
    }

    private LinearLayout row(Context context, GpuiChrome chrome, int i, Shape shape) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(VERTICAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        row.setPadding(dp(shape.leadDp), 0, 0, 0);
        row.addView(bar(context, chrome, TITLES[i % TITLES.length], TITLE_DP));
        if (shape.meta) {
            View meta = bar(context, chrome, METAS[i % METAS.length], META_DP);
            ((LayoutParams) meta.getLayoutParams()).topMargin = dp(GAP_DP);
            row.addView(meta);
        }
        return row;
    }

    /** One rounded bar {@code share} of the row's width. */
    private LinearLayout bar(Context context, GpuiChrome chrome, float share, int heightDp) {
        LinearLayout line = new LinearLayout(context);
        View fill = new View(context);
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(chrome.raised);
        shape.setCornerRadius(dp(RADIUS_DP));
        fill.setBackground(shape);
        line.addView(fill, new LayoutParams(0, -1, share));
        line.addView(new View(context), new LayoutParams(0, -1, 1 - share));
        line.setLayoutParams(new LayoutParams(-1, dp(heightDp)));
        return line;
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        // ValueAnimator honours the system's animation scale: off, it stays still.
        pulse.start();
    }

    @Override protected void onDetachedFromWindow() {
        pulse.cancel();
        setAlpha(1f);
        super.onDetachedFromWindow();
    }

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
