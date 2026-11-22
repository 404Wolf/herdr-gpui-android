package dev.herdr.android;

import android.app.Activity;
import android.content.res.TypedArray;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.view.ActionMode;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.FrameLayout;
import android.widget.ImageView;

import org.json.JSONObject;

/**
 * Select and copy in the terminal (factory-9zz.4). A long press selects the word under the
 * finger; the framework's own selection handles move either end; a floating text-selection
 * toolbar (ActionMode.TYPE_FLOATING) offers Copy, which puts the text on the clipboard
 * (ClipboardManager). The gesture is the host's from the long press on: the renderer's touch is
 * cancelled, so the program is never sent the press, the handle drags or a scroll while a
 * selection shows; a tap outside clears it. The renderer owns the grid and its geometry
 * (gpui-rio touch_select): it is asked for the word and the handles' places in logical pixels.
 *
 * <p>The decisions are pure ({@link Gesture}), so the arbitration is tested on the JVM; this
 * class wires them to the views.
 */
final class TerminalSelection {
    /** What a selection step asks the renderer (GpuiTerminalActivity.nativeSelect's kinds). */
    static final int WORD = 0, MOVE_START = 1, MOVE_END = 2, CLEAR = 3;

    interface Host {
        /** Queues a step for the drawn pane, in the renderer's logical pixels; false when none is drawn. */
        boolean select(int kind, float x, float y);
        /** The renderer is told to forget the current touch (nothing of it reaches the program). */
        void claimTouch();
        /** Puts the text on the clipboard. */
        void copy(String text);
    }

    /**
     * Who owns a touch on the terminal while selection is possible: pure.
     * <ul>
     *   <li>No selection: a finger held still for the long-press time selects; one that moves
     *       past the touch slop first, a second finger, or a lift before then is the renderer's
     *       as always.
     *   <li>A selection showing: a touch on a handle drags it; any other touch is ours too and
     *       clears the selection on its lift (a tap outside), never reaching the program.
     * </ul>
     */
    static final class Gesture {
        static final int NONE = 0, HANDLE_START = 1, HANDLE_END = 2;

        final long longPressMs;
        final float slopPx;
        boolean shown;
        /** The current gesture is ours (a long press that fired, a handle drag or a tap outside). */
        boolean owned;
        int dragging = NONE;
        private float downX, downY;
        private long downAt;
        private boolean moved, multi;

        Gesture(long longPressMs, float slopPx) {
            this.longPressMs = longPressMs;
            this.slopPx = slopPx;
        }

        /** A first finger down at ({@code x}, {@code y}) at {@code now}; {@code handle} is the handle under it. */
        void down(float x, float y, long now, int handle) {
            downX = x;
            downY = y;
            downAt = now;
            moved = false;
            multi = false;
            dragging = shown ? handle : NONE;
            owned = shown;
        }

        /** True when the finger moved past the slop (no long press any more). */
        boolean move(float x, float y) {
            if (Math.abs(x - downX) > slopPx || Math.abs(y - downY) > slopPx) moved = true;
            return moved;
        }

        void secondFinger() {
            multi = true;
        }

        /** Whether a long press fires now (the timer's check: still, single, not yet ours). */
        boolean longPress(long now) {
            return !shown && !owned && !moved && !multi && now - downAt >= longPressMs;
        }

        /** What the lift does: true when it clears a shown selection (a tap outside the handles). */
        boolean liftClears() {
            // A tap outside clears; a drag outside (a scroll attempt) keeps the selection.
            boolean clears = shown && dragging == NONE && owned && !moved;
            dragging = NONE;
            return clears;
        }
    }

    /** Where the handles and the toolbar go, from the renderer's report (logical px), or null. */
    static final class Places {
        final float startX, startY, endX, endY, lineHeight;
        final boolean startShown, endShown;
        final String text;

        Places(float startX, float startY, boolean startShown, float endX, float endY, boolean endShown,
                float lineHeight, String text) {
            this.startX = startX;
            this.startY = startY;
            this.startShown = startShown;
            this.endX = endX;
            this.endY = endY;
            this.endShown = endShown;
            this.lineHeight = lineHeight;
            this.text = text;
        }

        /** {@code json} from the renderer (android.rs selection_json); null for `{}` or bad input. */
        static Places parse(String json) {
            try {
                JSONObject o = new JSONObject(json);
                if (!o.has("text")) return null;
                JSONObject s = o.optJSONObject("start"), e = o.optJSONObject("end");
                return new Places(s == null ? 0 : (float) s.optDouble("x"), s == null ? 0 : (float) s.optDouble("y"), s != null,
                        e == null ? 0 : (float) e.optDouble("x"), e == null ? 0 : (float) e.optDouble("y"), e != null,
                        (float) o.optDouble("lineHeight", 16), o.optString("text", ""));
            } catch (org.json.JSONException | RuntimeException bad) {
                return null;
            }
        }
    }

    private final Activity activity;
    private final FrameLayout overlay;
    private final View surface;
    private final Host host;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ImageView startHandle, endHandle;
    private final float density;
    final Gesture gesture;
    private Places places;
    private ActionMode toolbar;
    private final Runnable longPress = this::longPressNow;
    private float pressX, pressY;

    TerminalSelection(Activity activity, FrameLayout overlay, View surface, Host host) {
        this.activity = activity;
        this.overlay = overlay;
        this.surface = surface;
        this.host = host;
        density = activity.getResources().getDisplayMetrics().density;
        ViewConfiguration config = ViewConfiguration.get(activity);
        gesture = new Gesture(ViewConfiguration.getLongPressTimeout(), config.getScaledTouchSlop());
        // The framework's own selection handles, as a TextView draws them.
        TypedArray handles = activity.obtainStyledAttributes(new int[] {
                android.R.attr.textSelectHandleLeft, android.R.attr.textSelectHandleRight});
        startHandle = handle(handles.getDrawable(0), "Selection start");
        endHandle = handle(handles.getDrawable(1), "Selection end");
        handles.recycle();
    }

    private ImageView handle(Drawable drawable, String description) {
        ImageView view = new ImageView(activity);
        view.setImageDrawable(drawable);
        view.setContentDescription(description);
        view.setVisibility(View.GONE);
        overlay.addView(view, new FrameLayout.LayoutParams(-2, -2));
        return view;
    }

    boolean shown() {
        return gesture.shown;
    }

    /**
     * The terminal's touch, before the renderer sees it. True when it is the selection's (the
     * caller then neither forwards it nor scrolls).
     */
    boolean onTouch(MotionEvent event) {
        int masked = event.getActionMasked();
        float x = event.getX(), y = event.getY();
        switch (masked) {
            case MotionEvent.ACTION_DOWN:
                gesture.down(x, y, event.getEventTime(), handleAt(event));
                if (!gesture.shown) {
                    pressX = x;
                    pressY = y;
                    main.postDelayed(longPress, gesture.longPressMs);
                }
                return gesture.owned;
            case MotionEvent.ACTION_POINTER_DOWN:
                gesture.secondFinger();
                main.removeCallbacks(longPress);
                return gesture.owned;
            case MotionEvent.ACTION_MOVE:
                if (gesture.move(x, y)) main.removeCallbacks(longPress);
                if (gesture.dragging != Gesture.NONE) {
                    // The finger is on the handle, which hangs under the line: aim at the line.
                    float aimY = y - (places == null ? 0 : places.lineHeight * density * 0.75f);
                    host.select(gesture.dragging == Gesture.HANDLE_START ? MOVE_START : MOVE_END,
                            x / density, aimY / density);
                }
                return gesture.owned;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                main.removeCallbacks(longPress);
                boolean ours = gesture.owned;
                if (gesture.liftClears() && masked == MotionEvent.ACTION_UP) clear();
                else if (gesture.shown) showToolbar();
                gesture.owned = false;
                return ours;
            default:
                return gesture.owned;
        }
    }

    private void longPressNow() {
        if (!gesture.longPress(android.os.SystemClock.uptimeMillis())) return;
        if (!host.select(WORD, pressX / density, pressY / density)) return;
        gesture.owned = true;
        // From here the gesture is ours: the renderer forgets the press (no click, no tap).
        host.claimTouch();
        surface.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
    }

    /** The renderer's report (main thread). */
    void report(String json) {
        Places next = Places.parse(json);
        places = next;
        gesture.shown = next != null;
        if (next == null) {
            hide();
            return;
        }
        place(startHandle, next.startX, next.startY, next.startShown, true);
        place(endHandle, next.endX, next.endY, next.endShown, false);
        if (gesture.dragging == Gesture.NONE) showToolbar();
        else if (toolbar != null) toolbar.invalidateContentRect();
    }

    void clear() {
        host.select(CLEAR, 0, 0);
        places = null;
        gesture.shown = false;
        hide();
    }

    private void hide() {
        startHandle.setVisibility(View.GONE);
        endHandle.setVisibility(View.GONE);
        if (toolbar != null) {
            ActionMode mode = toolbar;
            toolbar = null;
            mode.finish();
        }
    }

    private void place(ImageView view, float x, float y, boolean shown, boolean start) {
        if (!shown) {
            view.setVisibility(View.GONE);
            return;
        }
        Drawable d = view.getDrawable();
        int w = d == null ? 0 : d.getIntrinsicWidth();
        float px = overlayX(x * density), py = overlayY(y * density);
        // The left handle's point is its top-right corner, the right handle's its top-left.
        view.setTranslationX(start ? px - w * 0.75f : px - w * 0.25f);
        view.setTranslationY(py);
        view.setVisibility(View.VISIBLE);
    }

    private int handleAt(MotionEvent event) {
        if (!gesture.shown) return Gesture.NONE;
        if (hit(endHandle, event)) return Gesture.HANDLE_END;
        if (hit(startHandle, event)) return Gesture.HANDLE_START;
        return Gesture.NONE;
    }

    private boolean hit(View handle, MotionEvent event) {
        if (handle.getVisibility() != View.VISIBLE) return false;
        // Surface coordinates to the overlay's, then against the handle with a finger's margin.
        float x = overlayX(event.getX()), y = overlayY(event.getY());
        float slop = gesture.slopPx;
        float left = handle.getTranslationX() - slop, top = handle.getTranslationY() - slop;
        return x >= left && x <= left + handle.getWidth() + 2 * slop && y >= top && y <= top + handle.getHeight() + 2 * slop;
    }

    private void showToolbar() {
        if (places == null || places.text.isEmpty()) return;
        if (toolbar != null) {
            toolbar.invalidateContentRect();
            return;
        }
        toolbar = surface.startActionMode(new ActionMode.Callback2() {
            @Override public boolean onCreateActionMode(ActionMode mode, Menu menu) {
                menu.add(Menu.NONE, android.R.id.copy, 0, android.R.string.copy)
                        .setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);
                return true;
            }

            @Override public boolean onPrepareActionMode(ActionMode mode, Menu menu) {
                return false;
            }

            @Override public boolean onActionItemClicked(ActionMode mode, MenuItem item) {
                if (item.getItemId() != android.R.id.copy || places == null) return false;
                host.copy(places.text);
                clear();
                return true;
            }

            @Override public void onDestroyActionMode(ActionMode mode) {
                if (toolbar == mode) toolbar = null;
            }

            @Override public void onGetContentRect(ActionMode mode, View view, Rect out) {
                if (places == null) {
                    out.setEmpty();
                    return;
                }
                float top = Math.min(places.startY, places.endY) - places.lineHeight;
                float bottom = Math.max(places.startY, places.endY);
                float left = Math.min(places.startX, places.endX), right = Math.max(places.startX, places.endX);
                out.set(Math.round(left * density), Math.round(top * density),
                        Math.round(Math.max(right, left + 1) * density), Math.round(bottom * density));
            }
        }, ActionMode.TYPE_FLOATING);
    }

    // The renderer's window is the surface: its logical pixels are the surface's, divided by density.
    private float overlayX(float surfaceX) {
        return surfaceX + offset()[0];
    }

    private float overlayY(float surfaceY) {
        return surfaceY + offset()[1];
    }

    /** The surface's top-left within the overlay. */
    private int[] offset() {
        int[] s = new int[2], o = new int[2];
        surface.getLocationInWindow(s);
        overlay.getLocationInWindow(o);
        return new int[] {s[0] - o[0], s[1] - o[1]};
    }
}
