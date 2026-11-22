package dev.herdr.android;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.FrameLayout;

import java.util.Collections;
import java.util.function.Consumer;

/**
 * The divider between the terminal and the docked work panel (factory-9zz.9):
 * a quiet 1dp line with a small grip, on a 24dp-wide touch strip. Dragging it
 * moves only a preview line; the terminal and the panel resize once, on the
 * release, so the terminal never reflows under the finger. Released with the
 * panel under {@link WorkPanelLayout#HIDE_BELOW_DP} it hides the panel; then
 * a short strip at the right edge (excluded from Android's Back gesture) takes
 * an inward swipe to bring it back. TalkBack gets Hide and Show actions.
 */
final class PanelDivider {
    static final int TOUCH_DP = 24, GRIP_W_DP = 3, GRIP_H_DP = 28;

    private final Activity activity;
    private final FrameLayout divider, edge;
    private final View preview;
    private final Consumer<WorkPanelLayout.Choice> chose;
    private WorkPanelLayout.Choice choice = WorkPanelLayout.Choice.DEFAULT;
    private float share;
    private int widthPx, originPx;

    /** {@code chose} gets Wolf's new choice on a release, a restoring swipe or a TalkBack action. */
    PanelDivider(Activity activity, GpuiChrome chrome, Consumer<WorkPanelLayout.Choice> chose) {
        this.activity = activity;
        this.chose = chose;
        divider = new FrameLayout(activity);
        View line = new View(activity);
        line.setBackgroundColor(chrome.outline);
        divider.addView(line, new FrameLayout.LayoutParams(dp(1), -1, Gravity.CENTER_HORIZONTAL));
        View grip = new View(activity);
        GradientDrawable g = new GradientDrawable();
        g.setColor(chrome.raised);
        g.setCornerRadius(dp(2));
        grip.setBackground(g);
        divider.addView(grip, new FrameLayout.LayoutParams(dp(GRIP_W_DP), dp(GRIP_H_DP), Gravity.CENTER));
        divider.setContentDescription("Work panel divider: drag to resize, or all the way right to hide");
        divider.setVisibility(View.GONE);
        preview = new View(activity);
        preview.setBackgroundColor(chrome.primary);
        preview.setVisibility(View.GONE);
        edge = new FrameLayout(activity);
        edge.setContentDescription("Show the work panel");
        edge.setVisibility(View.GONE);
        edge.setOnClickListener(v -> chose.accept(WorkPanelLayout.shownAgain(choice)));
        dragging();
        restoring();
        actions();
    }

    View divider() { return divider; }
    View preview() { return preview; }
    View edge() { return edge; }

    /**
     * Where things are now: the docked panel's left edge ({@code panelLeftPx}),
     * the window's width, the choice it came from; {@code movable} false hides
     * the divider and the edge (the cover, a shell).
     */
    void place(boolean movable, WorkPanelLayout layout, WorkPanelLayout.Choice choice, int panelLeftPx, int windowPx,
            int originPx) {
        this.originPx = originPx;
        this.choice = choice == null ? WorkPanelLayout.Choice.DEFAULT : choice;
        this.share = layout.share;
        this.widthPx = windowPx;
        boolean showDivider = movable && layout.divider && !layout.hidden;
        divider.setVisibility(showDivider ? View.VISIBLE : View.GONE);
        if (showDivider) {
            FrameLayout.LayoutParams at = (FrameLayout.LayoutParams) divider.getLayoutParams();
            int left = panelLeftPx - dp(TOUCH_DP) / 2;
            if (at != null && at.leftMargin != left) {
                at.leftMargin = left;
                divider.setLayoutParams(at);
            }
        }
        edge.setVisibility(movable && layout.divider && layout.hidden ? View.VISIBLE : View.GONE);
    }

    @SuppressLint("ClickableViewAccessibility")
    private void dragging() {
        float[] down = new float[1];
        divider.setOnTouchListener((v, e) -> {
            float xPx = e.getRawX() - originPx, xDp = xPx / density();
            int windowDp = Math.round(widthPx / density());
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    down[0] = e.getRawX();
                    v.getParent().requestDisallowInterceptTouchEvent(true);
                    movePreview(xPx);
                    preview.setVisibility(View.VISIBLE);
                    return true;
                case MotionEvent.ACTION_MOVE:
                    float shareNow = WorkPanelLayout.dragged(xDp, windowDp);
                    movePreview(widthPx - shareNow * widthPx);
                    return true;
                case MotionEvent.ACTION_UP:
                    preview.setVisibility(View.GONE);
                    // A tap is no drag: nothing changes.
                    if (Math.abs(e.getRawX() - down[0]) < dp(4)) return true;
                    chose.accept(WorkPanelLayout.released(xDp, windowDp, share));
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    preview.setVisibility(View.GONE);
                    return true;
                default:
                    return true;
            }
        });
    }

    private void movePreview(float xPx) {
        preview.setTranslationX(Math.max(0, Math.min(widthPx - dp(2), xPx - dp(1))));
    }

    @SuppressLint("ClickableViewAccessibility")
    private void restoring() {
        float[] down = new float[2];
        edge.setOnTouchListener((v, e) -> {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    down[0] = e.getRawX();
                    down[1] = e.getRawY();
                    return true;
                case MotionEvent.ACTION_MOVE:
                case MotionEvent.ACTION_UP:
                    float dx = (e.getRawX() - down[0]) / density(), dy = (e.getRawY() - down[1]) / density();
                    if (WorkPanelLayout.restores(dx, dy)) {
                        down[0] = Float.MAX_VALUE;
                        chose.accept(WorkPanelLayout.shownAgain(choice));
                    }
                    return true;
                default:
                    return true;
            }
        });
        // The edge is Android's Back gesture; this short strip is ours (a system cap of 200dp a side).
        edge.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            if (Build.VERSION.SDK_INT >= 29) v.setSystemGestureExclusionRects(Collections.singletonList(new Rect(0, 0, r - l, b - t)));
        });
    }

    private void actions() {
        divider.setAccessibilityDelegate(new View.AccessibilityDelegate() {
            @Override public void onInitializeAccessibilityNodeInfo(View host, AccessibilityNodeInfo info) {
                super.onInitializeAccessibilityNodeInfo(host, info);
                info.addAction(new AccessibilityNodeInfo.AccessibilityAction(R.id.panel_hide_action, "Hide the work panel"));
            }

            @Override public boolean performAccessibilityAction(View host, int action, android.os.Bundle args) {
                if (action == R.id.panel_hide_action) {
                    chose.accept(new WorkPanelLayout.Choice(share, true));
                    return true;
                }
                return super.performAccessibilityAction(host, action, args);
            }
        });
    }

    private float density() {
        return activity.getResources().getDisplayMetrics().density;
    }

    private int dp(float v) {
        return Math.round(v * density());
    }
}
