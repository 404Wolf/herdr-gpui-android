package dev.herdr.android;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.List;

/**
 * The controls that float over an agent's terminal in place of a bottom bar:
 * a small keyboard button, the mic and Enter (↵), side by side on one chip at
 * the bottom right, where the program's hint line is. The composer places it
 * clear of the caret, the ghost text and dialogs ({@link ClusterPlacement}).
 *
 * <p>Gestures on the chip: a sideways swipe goes to the previous or next pane,
 * a swipe up (or a long press on Enter) opens the terminal keys. Taps reach the
 * buttons as usual.
 */
final class FloatingCluster {
    interface Actions {
        void keyboard();
        void enter();
        void keys();
        void queue();
        /** -1 for the previous pane, +1 for the next. */
        void pane(int step);
    }

    /** Button height, in dp: the mic and Enter are the same size. */
    static final int SIZE = 36;

    private final Context context;
    private final float density;
    private final Actions actions;
    private final Chip view;
    private final ImageView keyboard;
    private final FrameLayout enterBox;
    private final ImageView enter;
    private final TextView badge;
    private GpuiChrome chrome;
    private List<Outbox.Message> queued = java.util.Collections.emptyList();

    FloatingCluster(Context context, GpuiChrome chrome, View mic, Actions actions) {
        this.context = context;
        this.chrome = chrome;
        this.actions = actions;
        density = context.getResources().getDisplayMetrics().density;
        view = new Chip(context);
        view.setOrientation(LinearLayout.HORIZONTAL);
        view.setGravity(Gravity.CENTER_VERTICAL);
        view.setPadding(dp(3), dp(3), dp(3), dp(3));
        view.setClipChildren(false);
        view.setClipToPadding(false);

        keyboard = new ImageView(context);
        keyboard.setImageResource(R.drawable.ic_bar_keyboard);
        keyboard.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        keyboard.setPadding(dp(5), dp(8), dp(5), dp(8));
        keyboard.setContentDescription("Type a message");
        keyboard.setTooltipText("Type a message");
        keyboard.setOnClickListener(v -> actions.keyboard());
        view.addView(keyboard, new LinearLayout.LayoutParams(dp(30), dp(SIZE)));

        LinearLayout.LayoutParams micParams = new LinearLayout.LayoutParams(-2, -2);
        micParams.setMarginStart(dp(2));
        view.addView(mic, micParams);

        enterBox = new FrameLayout(context);
        enterBox.setClipChildren(false);
        enter = new ImageView(context);
        enter.setImageResource(R.drawable.ic_bar_enter);
        enter.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        enter.setPadding(dp(8), dp(8), dp(8), dp(8));
        enter.setContentDescription("Enter: send the message, or press Enter in the terminal");
        enter.setOnClickListener(v -> actions.enter());
        enter.setOnLongClickListener(v -> {
            actions.keys();
            return true;
        });
        enterBox.addView(enter, new FrameLayout.LayoutParams(dp(SIZE), dp(SIZE)));
        badge = new TextView(context);
        badge.setTextSize(10);
        badge.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        badge.setGravity(Gravity.CENTER);
        badge.setIncludeFontPadding(false);
        badge.setMinWidth(dp(16));
        badge.setPadding(dp(4), 0, dp(4), 0);
        badge.setTranslationX(-dp(5));
        badge.setTranslationY(-dp(5));
        badge.setOnClickListener(v -> actions.queue());
        badge.setVisibility(View.GONE);
        enterBox.addView(badge, new FrameLayout.LayoutParams(-2, dp(16), Gravity.TOP | Gravity.START));
        LinearLayout.LayoutParams enterParams = new LinearLayout.LayoutParams(dp(SIZE), dp(SIZE));
        enterParams.setMarginStart(dp(4));
        view.addView(enterBox, enterParams);
        style(chrome);
    }

    View view() { return view; }

    /** Whether the mic sits in this chip (it moves to the typing box while the keyboard is up). */
    boolean holds(View mic) { return mic.getParent() == view; }

    void takeMic(View mic) {
        if (mic.getParent() == view) return;
        if (mic.getParent() instanceof android.view.ViewGroup) {
            ((android.view.ViewGroup) mic.getParent()).removeView(mic);
        }
        LinearLayout.LayoutParams micParams = new LinearLayout.LayoutParams(-2, -2);
        micParams.setMarginStart(dp(2));
        view.addView(mic, 1, micParams);
    }

    void style(GpuiChrome next) {
        chrome = next;
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(chrome.surface);
        shape.setStroke(Math.max(1, dp(1)), chrome.outline);
        shape.setCornerRadius(dp(SIZE / 2f + 3));
        view.setBackground(shape);
        keyboard.setImageTintList(ColorStateList.valueOf(chrome.onSurfaceVariant));
        enter.setImageTintList(ColorStateList.valueOf(chrome.onSurface));
        GradientDrawable round = new GradientDrawable();
        round.setShape(GradientDrawable.OVAL);
        round.setColor(chrome.control);
        enter.setBackground(new android.graphics.drawable.RippleDrawable(
                ColorStateList.valueOf(GpuiChrome.withAlpha(chrome.onSurface, 0x29)), round, null));
        setQueued(queued);
    }

    /** Enter's corner: how many messages wait for this pane, in the most pressing one's colour. */
    void setQueued(List<Outbox.Message> messages) {
        queued = messages;
        String count = QueuedMessages.badge(messages.size());
        badge.setVisibility(count.isEmpty() ? View.GONE : View.VISIBLE);
        if (count.isEmpty()) return;
        int color;
        switch (QueuedMessages.urgency(messages)) {
            case GONE: color = chrome.danger; break;
            case WAITING: color = chrome.warning; break;
            case SENDING: color = chrome.info; break;
            default: color = chrome.primary;
        }
        GradientDrawable pill = new GradientDrawable();
        pill.setColor(color);
        pill.setCornerRadius(dp(8));
        pill.setStroke(dp(2), chrome.surface);
        badge.setBackground(pill);
        badge.setTextColor(chrome.light ? 0xffffffff : chrome.background);
        badge.setText(count);
        String label = QueuedMessages.title(messages.size()) + "; tap to list";
        badge.setContentDescription(label);
        badge.setTooltipText(label);
    }

    /** No free spot: smaller and see-through, so what it covers still shows. */
    void setCrowded(boolean crowded) {
        view.setAlpha(crowded ? 0.55f : 1f);
        view.setScaleX(crowded ? 0.85f : 1f);
        view.setScaleY(crowded ? 0.85f : 1f);
        view.setPivotX(view.getWidth());
        view.setPivotY(view.getHeight());
    }

    private int dp(float value) { return Math.round(value * density); }

    /** A chip whose swipes are gestures and whose taps reach its buttons. */
    private final class Chip extends LinearLayout {
        private final int slop = ViewConfiguration.get(context).getScaledTouchSlop();
        private float downX, downY;
        private boolean claimed;

        Chip(Context context) { super(context); }

        @Override public boolean onInterceptTouchEvent(MotionEvent event) {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX = event.getX();
                    downY = event.getY();
                    claimed = false;
                    return false;
                case MotionEvent.ACTION_MOVE:
                    float dx = event.getX() - downX, dy = event.getY() - downY;
                    // Past the slop it is a swipe: the button under the finger lets go.
                    claimed = Math.abs(dx) > slop * 2 || -dy > slop * 2;
                    return claimed;
                default:
                    return false;
            }
        }

        @Override public boolean onTouchEvent(MotionEvent event) {
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) return true;
            if (event.getActionMasked() != MotionEvent.ACTION_UP) return true;
            float dx = event.getX() - downX, dy = event.getY() - downY;
            if (Math.abs(dx) > dp(28) && Math.abs(dx) > Math.abs(dy)) {
                actions.pane(dx < 0 ? 1 : -1);
            } else if (-dy > dp(20) && -dy > Math.abs(dx)) {
                actions.keys();
            }
            return true;
        }
    }
}
