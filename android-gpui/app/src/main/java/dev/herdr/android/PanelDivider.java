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

/**
 * The divider between the terminal and the docked work panel (factory-9zz.9):
 * a quiet 1dp line with a small grip, on a 24dp-wide touch strip. The panel
 * follows the finger continuously (Wolf): each move hands the live share to
 * {@code live}, at most once a frame, down to zero; the release keeps it, or
 * hides the panel under {@link WorkPanelLayout#HIDE_BELOW_DP}. Hidden, a short
 * strip at the right edge (excluded from Android's Back gesture) takes a drag
 * inward that grows the panel from zero with the finger, kept at the width it
 * is let go at. TalkBack gets Hide and Show actions.
 *
 * <p>On the cover the same right-edge strip pulls the full-page panel in under
 * the finger ({@link Moves#cover}), and the release settles it full or gone
 * ({@link Moves#coverSettled}). No handle is drawn anywhere (Wolf: edge swipes
 * only); the strips are invisible and excluded from Back only along the edge.
 */
final class PanelDivider {
    static final int TOUCH_DP = 24, GRIP_W_DP = 4, GRIP_H_DP = 36;
    /** How far in from the screen's edge Back's gesture reaches; a divider this near is excluded from it. */
    static final int BACK_EDGE_DP = 32;
    /** The divider's Back exclusion at the edge: this tall around its grip, inside Android's 200 dp per edge. */
    static final int EXCLUDED_DP = 160;
    private boolean dividerExcluded;

    /** What the divider and the edge strip report to the activity. */
    interface Moves {
        /** A release, a reveal or a TalkBack action: the docked split to keep. */
        void chose(WorkPanelLayout.Choice next);
        /** The docked share while a finger is down, at most once a frame; NaN: back to the kept one. */
        void live(float share);
        /** The cover: how much of the full page shows while a finger pulls it in. */
        void cover(float visible);
        /** The cover: the release settles the page open (full) or gone. */
        void coverSettled(boolean open);
    }

    private final Activity activity;
    private final FrameLayout divider, edge;
    private final Moves moves;
    private float pendingShare = Float.NaN;
    private boolean framePosted, revealing, cover;
    private final View line, grip;
    private final int gripColor, lineColor, dragColor;
    private WorkPanelLayout.Choice choice = WorkPanelLayout.Choice.DEFAULT;
    private float share;
    private int widthPx, originPx;

    PanelDivider(Activity activity, GpuiChrome chrome, Moves moves) {
        this.activity = activity;
        this.moves = moves;
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
        // Invisible: no handle or peek strip (Wolf, 11-22); the swipe itself is the way in.
        edge = new FrameLayout(activity);
        edge.setContentDescription("Show the work panel");
        edge.setVisibility(View.GONE);
        // TalkBack's double-tap (a touch listener takes real taps first).
        edge.setOnClickListener(v -> {
            if (cover) moves.coverSettled(true);
            else moves.chose(WorkPanelLayout.shownAgain(choice));
        });
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
            if (Float.isNaN(pendingShare)) return;
            if (cover) moves.cover(pendingShare);
            else moves.live(pendingShare);
        });
    }

    /** The cover's pull is over: settled open or gone when it moved at all ({@code moved}). */
    private void finishCover(boolean moved, boolean open) {
        boolean was = revealing;
        pendingShare = Float.NaN;
        revealing = false;
        if (moved || was) moves.coverSettled(open);
    }

    /** The finger's last horizontal speed, in dp/s (positive toward the right), and the tracker freed. */
    private float velocityDp(android.view.VelocityTracker[] speed) {
        if (speed[0] == null) return 0;
        speed[0].computeCurrentVelocity(1000);
        float vx = speed[0].getXVelocity() / density();
        speed[0].recycle();
        speed[0] = null;
        return vx;
    }

    /** The gesture is over: no live share pending; {@code next} is kept, or null to fall back to the kept one. */
    private void finish(WorkPanelLayout.Choice next) {
        pendingShare = Float.NaN;
        revealing = false;
        if (next != null) moves.chose(next);
        else moves.live(Float.NaN);
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
            // A panel dragged nearly to the edge keeps its exact width (Wolf), but its divider
            // stays whole on screen and, there, out of Back's edge: it can always be grabbed again.
            int left = WorkPanelLayout.dividerLeft(panelLeftPx, windowPx, dp(TOUCH_DP));
            if (at != null && at.leftMargin != left) {
                at.leftMargin = left;
                divider.setLayoutParams(at);
            }
            boolean atEdge = WorkPanelLayout.dividerAtEdge(left, windowPx, dp(TOUCH_DP), dp(BACK_EDGE_DP));
            if (atEdge != dividerExcluded && Build.VERSION.SDK_INT >= 29) {
                dividerExcluded = atEdge;
                divider.setSystemGestureExclusionRects(atEdge ? Collections.singletonList(gripRect()) : Collections.emptyList());
            }
        }
        // A reveal in progress keeps its strip, so the gesture's own view never goes away under it.
        // On the cover it is there while the page is away (and while it is being pulled in).
        cover = layout.overlay;
        boolean edgeShows = layout.divider ? layout.hidden || revealing : layout.overlay && (!layout.shown || revealing);
        edge.setVisibility(movable && edgeShows ? View.VISIBLE : View.GONE);
        // The strip sits where the thumb is: the lower half, above the icon cluster.
        if (edge.getParent() instanceof View && edge.getLayoutParams() instanceof FrameLayout.LayoutParams) {
            int heightDp = Math.round(((View) edge.getParent()).getHeight() / density());
            FrameLayout.LayoutParams at = (FrameLayout.LayoutParams) edge.getLayoutParams();
            int bottom = dp(WorkPanelLayout.edgeBottomDp(heightDp));
            if (at.bottomMargin != bottom || at.gravity != (Gravity.END | Gravity.BOTTOM)) {
                at.gravity = Gravity.END | Gravity.BOTTOM;
                at.bottomMargin = bottom;
                edge.setLayoutParams(at);
            }
        }
    }

    /** The divider's middle, {@link #EXCLUDED_DP} tall, in its own coordinates. */
    private Rect gripRect() {
        int h = divider.getHeight() > 0 ? divider.getHeight() : activity.getResources().getDisplayMetrics().heightPixels;
        int half = dp(EXCLUDED_DP) / 2, mid = h / 2;
        return new Rect(0, Math.max(0, mid - half), dp(TOUCH_DP), Math.min(h, mid + half));
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
        android.view.VelocityTracker[] speed = new android.view.VelocityTracker[1];
        edge.setOnTouchListener((v, e) -> {
            float xDp = (e.getRawX() - originPx) / density();
            int windowDp = Math.round(widthPx / density());
            float dx = (e.getRawX() - down[0]) / density(), dy = (e.getRawY() - down[1]) / density();
            if (e.getActionMasked() == MotionEvent.ACTION_DOWN) {
                if (speed[0] != null) speed[0].recycle();
                speed[0] = android.view.VelocityTracker.obtain();
            }
            if (speed[0] != null) {
                // Raw coordinates: the strip itself does not move, but the page under the finger does.
                MotionEvent raw = MotionEvent.obtain(e);
                raw.setLocation(e.getRawX(), e.getRawY());
                speed[0].addMovement(raw);
                raw.recycle();
            }
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    down[0] = e.getRawX();
                    down[1] = e.getRawY();
                    v.getParent().requestDisallowInterceptTouchEvent(true);
                    return true;
                case MotionEvent.ACTION_MOVE:
                    // Inward and mostly sideways: the panel comes in from zero with the finger.
                    if (!revealing && !WorkPanelLayout.restores(dx, dy)) return true;
                    revealing = true;
                    liveShare(cover ? WorkPanelLayout.coverRevealed(xDp, windowDp)
                            : WorkPanelLayout.dragged(WorkPanelLayout.snapped(xDp, windowDp), windowDp));
                    return true;
                case MotionEvent.ACTION_UP: {
                    float vx = velocityDp(speed);
                    // A tap on the invisible strip does nothing (no handle to tap).
                    if (!revealing) { finishCover(false, false); return true; }
                    if (cover) finishCover(true, WorkPanelLayout.coverOpens(WorkPanelLayout.coverRevealed(xDp, windowDp), vx));
                    else finish(WorkPanelLayout.revealed(WorkPanelLayout.snapped(xDp, windowDp), windowDp, share));
                    return true;
                }
                case MotionEvent.ACTION_CANCEL:
                    if (cover) finishCover(revealing, false);
                    else finish(null);
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
                    moves.chose(new WorkPanelLayout.Choice(share, true));
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
