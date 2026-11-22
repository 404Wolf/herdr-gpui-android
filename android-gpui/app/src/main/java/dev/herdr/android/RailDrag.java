package dev.herdr.android;

import android.annotation.SuppressLint;
import android.content.ClipData;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.view.DragEvent;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.widget.ScrollView;
import java.util.IdentityHashMap;
import java.util.Map;

/**
 * Dragging the rail's groups, repos and PMs to reorder them (factory-r8f.8),
 * with the framework's own drag-and-drop ({@link View#startDragAndDrop} and an
 * {@link View.OnDragListener} on the rail's rows): no new dependency, and the
 * rail stays a LinearLayout in a ScrollView.
 *
 * <p>A long press arms a draggable row (haptic); moving past the touch slop
 * starts the drag; lifting without moving opens the row's menu as before, so
 * menus and drags share the gesture with no handle. Once armed the rail's
 * scroll and the drawer's swipe give the gesture up. While dragging, a 2dp
 * accent line shows where it lands, a group lights up when a PM would join
 * it, and the rail scrolls itself near its top and bottom edges. A drop is
 * planned ({@link Planner}) and done by the caller; a drop outside the rail,
 * Back, or a refused place writes nothing and puts the row back.
 */
final class RailDrag {
    /** What a draggable row is: its machine, and a section or a PM in a section. */
    static final class Ref {
        final String hostId;
        final RailOrder.At at;

        Ref(String hostId, RailOrder.At at) {
            this.hostId = hostId;
            this.at = at;
        }
    }

    /** What a drop over {@code over} would do (null: nothing), and doing it. */
    interface Planner {
        RailOrder.Plan plan(Ref item, Ref over, boolean after);
        void drop(Ref item, RailOrder.Plan plan);
    }

    static final int LINE_DP = 2, EDGE_DP = 48, MAX_SCROLL_DP = 18;

    private final ViewGroup rows;
    private final ScrollView scroll;
    private final GpuiChrome chrome;
    private final Planner planner;
    private final Map<View, Ref> refs = new IdentityHashMap<>();
    private final int slop;
    private View armed, dragged, marked;
    private Drawable markedWas;
    private Runnable sheet;
    private float downX, downY, lastY = -1;
    private boolean scrolling;

    RailDrag(ViewGroup rows, ScrollView scroll, GpuiChrome chrome, Planner planner) {
        this.rows = rows;
        this.scroll = scroll;
        this.chrome = chrome;
        this.planner = planner;
        slop = ViewConfiguration.get(rows.getContext()).getScaledTouchSlop();
        rows.setOnDragListener(this::onDrag);
    }

    /** Before each redraw: which rows drag is said again (rows are reused). */
    void clear() {
        refs.clear();
    }

    /** {@code row} drags as {@code ref} (null: it doesn't). */
    void mark(View row, Ref ref) {
        if (ref == null) refs.remove(row);
        else refs.put(row, ref);
    }

    /** Every reusable rail row watches its own touches (doing nothing until armed). */
    @SuppressLint("ClickableViewAccessibility")
    void watch(View row) {
        row.setOnTouchListener((v, e) -> onRowTouch(v, e));
    }

    /**
     * A long press on {@code row}: armed when it drags, with {@code menu}
     * kept for a lift without moving; false when it doesn't drag (show the
     * menu at once).
     */
    boolean arm(View row, Runnable menu) {
        if (!refs.containsKey(row) || dragged != null) return false;
        armed = row;
        sheet = menu;
        row.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
        // From here the gesture is the row's: neither the scroll nor the drawer's swipe takes it.
        if (row.getParent() != null) row.getParent().requestDisallowInterceptTouchEvent(true);
        return true;
    }

    private boolean onRowTouch(View v, MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = e.getRawX();
                downY = e.getRawY();
                return false;
            case MotionEvent.ACTION_MOVE:
                if (armed != v) return false;
                if (Math.hypot(e.getRawX() - downX, e.getRawY() - downY) < slop) return true;
                start(v);
                return true;
            case MotionEvent.ACTION_UP:
                if (armed != v) return false;
                // Long-pressed and let go in place: the row's menu, as before.
                Runnable menu = sheet;
                disarm();
                if (menu != null) menu.run();
                return true;
            case MotionEvent.ACTION_CANCEL:
                if (armed == v) disarm();
                return false;
            default:
                return false;
        }
    }

    private void disarm() {
        armed = null;
        sheet = null;
    }

    private void start(View row) {
        Ref ref = refs.get(row);
        disarm();
        if (ref == null) return;
        boolean started = row.startDragAndDrop(ClipData.newPlainText("rail", ""), new View.DragShadowBuilder(row), ref, 0);
        if (!started) return;
        dragged = row;
        row.setAlpha(0.4f);
    }

    private boolean onDrag(View v, DragEvent e) {
        Object state = e.getLocalState();
        if (!(state instanceof Ref)) return false;
        Ref item = (Ref) state;
        switch (e.getAction()) {
            case DragEvent.ACTION_DRAG_STARTED:
                return true;
            case DragEvent.ACTION_DRAG_LOCATION:
                lastY = e.getY();
                show(item, lastY);
                autoScroll();
                return true;
            case DragEvent.ACTION_DRAG_EXITED:
                lastY = -1;
                unmark();
                return true;
            case DragEvent.ACTION_DROP: {
                Target t = target(item, e.getY());
                RailOrder.Plan plan = t == null ? null : planner.plan(item, t.ref, t.after);
                unmark();
                if (plan == null) return false;
                planner.drop(item, plan);
                return plan.op != RailOrder.Op.REFUSE;
            }
            case DragEvent.ACTION_DRAG_ENDED:
                // Dropped or not, the row is back where the list draws it; nothing was written unless dropped.
                lastY = -1;
                unmark();
                if (dragged != null) dragged.setAlpha(1f);
                dragged = null;
                return true;
            default:
                return true;
        }
    }

    /** The draggable row under {@code y} (rows' coordinates), else the one above it; after: its lower half. */
    private static final class Target {
        final View row;
        final Ref ref;
        final boolean after;

        Target(View row, Ref ref, boolean after) {
            this.row = row;
            this.ref = ref;
            this.after = after;
        }
    }

    private Target target(Ref item, float y) {
        View best = null;
        for (int i = 0; i < rows.getChildCount(); i++) {
            View c = rows.getChildAt(i);
            if (c.getVisibility() != View.VISIBLE || c.getTop() > y) continue;
            Ref r = refs.get(c);
            if (r == null || !r.hostId.equals(item.hostId)) continue;
            best = c;
        }
        if (best == null) return null;
        boolean inside = y < best.getBottom();
        boolean after = !inside || y > best.getTop() + best.getHeight() / 2f;
        return new Target(best, refs.get(best), after);
    }

    private void show(Ref item, float y) {
        Target t = target(item, y);
        RailOrder.Plan plan = t == null ? null : planner.plan(item, t.ref, t.after);
        if (plan == null || plan.op == RailOrder.Op.REFUSE) { unmark(); return; }
        // A PM joining a group onto its header: the header lights up; else the line where it lands.
        boolean intoHeader = plan.op == RailOrder.Op.REGROUP && t.ref.at.isSection();
        Drawable mark = intoHeader ? new ColorDrawable(GpuiChrome.withAlpha(chrome.primary, 0x33)) : line(t.after);
        if (marked != t.row) {
            unmark();
            marked = t.row;
            markedWas = t.row.getForeground();
        }
        t.row.setForeground(mark);
    }

    private Drawable line(boolean bottom) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(chrome.primary);
        LayerDrawable layer = new LayerDrawable(new Drawable[] {g});
        layer.setLayerGravity(0, (bottom ? Gravity.BOTTOM : Gravity.TOP) | Gravity.FILL_HORIZONTAL);
        layer.setLayerHeight(0, dp(LINE_DP));
        return layer;
    }

    private void unmark() {
        if (marked != null) marked.setForeground(markedWas);
        marked = null;
        markedWas = null;
    }

    /** Near the rail's top or bottom edge the rail scrolls, faster nearer the edge, once a frame while held there. */
    private void autoScroll() {
        if (scrolling) return;
        scrolling = true;
        scroll.postOnAnimation(new Runnable() {
            @Override public void run() {
                float step = step();
                if (lastY < 0 || dragged == null || step == 0) { scrolling = false; return; }
                scroll.scrollBy(0, Math.round(step));
                scroll.postOnAnimation(this);
            }
        });
    }

    /** px to scroll this frame: toward the edge the pointer is within {@link #EDGE_DP} of. */
    private float step() {
        float inView = lastY - scroll.getScrollY();
        return scrollStep(inView, scroll.getHeight(), dp(EDGE_DP), dp(MAX_SCROLL_DP));
    }

    /** Pure: the scroll for a pointer {@code y} px into a {@code height} px viewport (negative: up). */
    static float scrollStep(float y, int height, int edgePx, int maxPx) {
        if (height <= 0) return 0;
        if (y < edgePx) return -maxPx * Math.min(1f, (edgePx - Math.max(0, y)) / edgePx);
        if (y > height - edgePx) return maxPx * Math.min(1f, (y - (height - edgePx)) / edgePx);
        return 0;
    }

    private int dp(float v) {
        return Math.round(v * rows.getResources().getDisplayMetrics().density);
    }
}
