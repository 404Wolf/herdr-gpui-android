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
 * a quiet 1dp line with a small grip, on a 24dp-wide touch strip. The panel
 * follows the finger continuously (Wolf): each move hands the live share to
 * {@code live}, at most once a frame, down to zero; the release keeps it, or
 * hides the panel under {@link WorkPanelLayout#HIDE_BELOW_DP}. Hidden, a short
 * strip at the right edge (excluded from Android's Back gesture) takes a drag
 * inward that grows the panel from zero with the finger, kept at the width it
 * is let go at. TalkBack gets Hide and Show actions.
 */
final class PanelDivider {
    static final int TOUCH_DP = 24, GRIP_W_DP = 4, GRIP_H_DP = 36, EDGE_GRIP_H_DP = 48;

    private final Activity activity;
    private final FrameLayout divider, edge;
    private final Consumer<WorkPanelLayout.Choice> chose;
    /** The share while a finger moves it; NaN when the gesture ends without a choice. */
    private final Consumer<Float> live;
    private float pendingShare = Float.NaN;
    private boolean framePosted, revealing;
    private final View line, grip;
    private final int gripColor, lineColor, dragColor;
    private WorkPanelLayout.Choice choice = WorkPanelLayout.Choice.DEFAULT;
    private float share;
    private int widthPx, originPx;

    /**
     * {@code chose} gets Wolf's new choice on a release, a reveal or a TalkBack
     * action; {@code live} the share while his finger is down (NaN: back to the kept one).
     */
    PanelDivider(Activity activity, GpuiChrome chrome, Consumer<WorkPanelLayout.Choice> chose, Consumer<Float> live) {
        this.activity = activity;
        this.chose = chose;
        this.live = live;
        divider = new FrameLayout(activity);
        line = new View(activity);
        line.setBackgroundColor(chrome.outline);
        divider.addView(line, new FrameLayout.LayoutParams(dp(1), -1, Gravity.CENTER_HORIZONTAL));
        // A 4x36dp pill on the line, mid-grey (review P0-3); the accent while dragged.
        gripColor = GpuiChrome.withAlpha(chrome.onSurfaceVariant, 0x8C);
        lineColor = chrome.outline;
        dragColor = chrome.primary;
        grip = pill(gripColor);
        divider.addView(grip, new FrameLayout.LayoutParams(dp(GRIP_W_DP), dp(GRIP_H_DP), Gravity.CENTER));
        divider.setContentDescription("Work panel divider: drag to resize, or all the way right to hide");
        divider.setVisibility(View.GONE);
        edge = new FrameLayout(activity);
        // Hidden, a visible handle at the right edge says the swipe is there (review P0-3).
        edge.addView(pill(gripColor), new FrameLayout.LayoutParams(dp(GRIP_W_DP), dp(EDGE_GRIP_H_DP), Gravity.END | Gravity.CENTER_VERTICAL));
        edge.setContentDescription("Show the work panel");
        edge.setVisibility(View.GONE);
        // TalkBack's double-tap (a touch listener takes real taps first).
        edge.setOnClickListener(v -> chose.accept(WorkPanelLayout.shownAgain(choice)));
        dragging();
        restoring();
        actions();
    }

    private View pill(int color) {
        View pill = new View(activity);
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(GRIP_W_DP) / 2f);
        pill.setBackground(g);
        return pill;
    }

    private void dragged(boolean on) {
        line.setBackgroundColor(on ? dragColor : lineColor);
        ((GradientDrawable) grip.getBackground()).setColor(on ? dragColor : gripColor);
    }

    View divider() { return divider; }
    View edge() { return edge; }

    /** One live update a frame: the latest share wins. */
    private void liveShare(float share) {
        pendingShare = share;
        if (framePosted) return;
        framePosted = true;
        divider.postOnAnimation(() -> {
            framePosted = false;
            if (!Float.isNaN(pendingShare)) live.accept(pendingShare);
        });
    }

    /** The gesture is over: no live share pending; {@code next} is kept, or null to fall back to the kept one. */
    private void finish(WorkPanelLayout.Choice next) {
        pendingShare = Float.NaN;
        revealing = false;
        if (next != null) chose.accept(next);
        else live.accept(Float.NaN);
    }

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
        // A reveal in progress keeps its strip, so the gesture's own view never goes away under it.
        edge.setVisibility(movable && layout.divider && (layout.hidden || revealing) ? View.VISIBLE : View.GONE);
    }

    @SuppressLint("ClickableViewAccessibility")
    private void dragging() {
        float[] down = new float[1];
        divider.setOnTouchListener((v, e) -> {
            float xDp = (e.getRawX() - originPx) / density();
            int windowDp = Math.round(widthPx / density());
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    down[0] = e.getRawX();
                    v.getParent().requestDisallowInterceptTouchEvent(true);
                    dragged(true);
                    return true;
                case MotionEvent.ACTION_MOVE:
                    // The panel itself follows, down to zero (the hinge holds it within the snap).
                    liveShare(WorkPanelLayout.dragged(WorkPanelLayout.snapped(xDp, windowDp), windowDp));
                    return true;
                case MotionEvent.ACTION_UP:
                    dragged(false);
                    // A tap is no drag: nothing changes.
                    if (Math.abs(e.getRawX() - down[0]) < dp(4)) { finish(null); return true; }
                    finish(WorkPanelLayout.released(WorkPanelLayout.snapped(xDp, windowDp), windowDp, share));
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    dragged(false);
                    finish(null);
                    return true;
                default:
                    return true;
            }
        });
    }

    @SuppressLint("ClickableViewAccessibility")
    private void restoring() {
        float[] down = new float[2];
        edge.setOnTouchListener((v, e) -> {
            float xDp = (e.getRawX() - originPx) / density();
            int windowDp = Math.round(widthPx / density());
            float dx = (e.getRawX() - down[0]) / density(), dy = (e.getRawY() - down[1]) / density();
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    down[0] = e.getRawX();
                    down[1] = e.getRawY();
                    v.getParent().requestDisallowInterceptTouchEvent(true);
                    return true;
                case MotionEvent.ACTION_MOVE:
                    // Inward and mostly sideways: the panel grows from zero with the finger.
                    if (!revealing && !WorkPanelLayout.restores(dx, dy)) return true;
                    revealing = true;
                    liveShare(WorkPanelLayout.dragged(WorkPanelLayout.snapped(xDp, windowDp), windowDp));
                    return true;
                case MotionEvent.ACTION_UP:
                    if (!revealing) {
                        // A tap on the handle brings it back where it was.
                        finish(Math.abs(dx) < 4 && Math.abs(dy) < 4 ? WorkPanelLayout.shownAgain(choice) : null);
                        return true;
                    }
                    finish(WorkPanelLayout.revealed(WorkPanelLayout.snapped(xDp, windowDp), windowDp, share));
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    finish(null);
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
