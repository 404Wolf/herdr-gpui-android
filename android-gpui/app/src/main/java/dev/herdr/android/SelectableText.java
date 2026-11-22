package dev.herdr.android;

import android.content.ClipData;
import android.content.ClipDescription;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PersistableBundle;
import android.text.Selection;
import android.text.Spannable;
import android.text.Spanned;
import android.view.ActionMode;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import java.util.function.Consumer;

/**
 * Panel text Wolf can select (factory-9zz.4): a long press selects a word
 * and Android's own handles refine it; a drag that starts as a drag still
 * scrolls. The selection is copied by itself once it settles
 * ({@link SelectionCopy.Settle}) and again when its toolbar closes, marked
 * sensitive (it can hold secrets, so Android does not preview it), and the
 * crumb says "Copied 3 lines". The toolbar keeps the system's items and adds
 * Quote, which puts the text in the composer's draft and never sends.
 *
 * <p>A panel that redraws on a poll must not replace this view while it holds
 * a selection: {@link #anySelected} says when to wait.
 */
final class SelectableText extends TextView {
    private static final int QUOTE = 0x5e1ec7;

    /** Quote: the selected range [start, end) of the text. */
    interface Quote { void quote(int start, int end); }

    private final Consumer<String> note;
    private Quote quote;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final SelectionCopy.Settle settle = new SelectionCopy.Settle();
    private final Runnable copySettled = this::copySettled;
    private boolean modeUp;

    /** {@code note} shows the crumb's short note. */
    SelectableText(Context context, Consumer<String> note) {
        super(context);
        this.note = note;
        setTextIsSelectable(true);
        setCustomSelectionActionModeCallback(new ActionMode.Callback() {
            @Override public boolean onCreateActionMode(ActionMode mode, Menu menu) {
                modeUp = true;
                if (SelectableText.this.quote != null) menu.add(Menu.NONE, QUOTE, Menu.FIRST, "Quote");
                return true;
            }

            @Override public boolean onPrepareActionMode(ActionMode mode, Menu menu) {
                return false;
            }

            @Override public boolean onActionItemClicked(ActionMode mode, MenuItem item) {
                if (item.getItemId() != QUOTE) return false;
                int start = Math.min(getSelectionStart(), getSelectionEnd()), end = Math.max(getSelectionStart(), getSelectionEnd());
                if (end > start) SelectableText.this.quote.quote(start, end);
                mode.finish();
                return true;
            }

            @Override public void onDestroyActionMode(ActionMode mode) {
                modeUp = false;
                main.removeCallbacks(copySettled);
                copy(settle.ended());
            }
        });
    }

    /** Adds Quote to the toolbar: it hands the selected range to {@code quote} (which never sends). */
    void setQuote(Quote quote) {
        this.quote = quote;
    }

    /**
     * A tap on a link opens it; with selectable text Android would only move
     * the cursor. Not while a selection is up, and not after a drag or a hold.
     */
    // TalkBack reaches links through its own links menu; this is only the finger's tap on one.
    @android.annotation.SuppressLint("ClickableViewAccessibility")
    @Override public boolean onTouchEvent(android.view.MotionEvent event) {
        int action = event.getActionMasked();
        if (action == android.view.MotionEvent.ACTION_DOWN) {
            downX = event.getX();
            downY = event.getY();
            downAt = event.getEventTime();
        } else if (action == android.view.MotionEvent.ACTION_UP && !hasSelection() && !modeUp
                && Math.hypot(event.getX() - downX, event.getY() - downY) < android.view.ViewConfiguration.get(getContext()).getScaledTouchSlop()
                && event.getEventTime() - downAt < android.view.ViewConfiguration.getLongPressTimeout()
                && getText() instanceof Spanned) {
            int at = getOffsetForPosition(event.getX(), event.getY());
            android.text.style.ClickableSpan[] links = ((Spanned) getText()).getSpans(at, at, android.text.style.ClickableSpan.class);
            if (links.length > 0 && at < getText().length()) {
                links[0].onClick(this);
                return true;
            }
        }
        return super.onTouchEvent(event);
    }

    private float downX, downY;
    private long downAt;

    @Override protected void onSelectionChanged(int start, int end) {
        super.onSelectionChanged(start, end);
        // Called from TextView's constructor, before this class's fields exist.
        if (settle == null) return;
        settle.changed(selected(), android.os.SystemClock.uptimeMillis());
        main.removeCallbacks(copySettled);
        main.postDelayed(copySettled, SelectionCopy.Settle.SETTLE_MS);
    }

    @Override protected void onDetachedFromWindow() {
        main.removeCallbacks(copySettled);
        super.onDetachedFromWindow();
    }

    /** A selection is up, or its toolbar: the panel should not redraw this view now. */
    boolean selecting() {
        return modeUp || hasSelection();
    }

    /** Clears the selection (Back clears it before anything else). True when there was one. */
    boolean clearSelection() {
        if (!selecting()) return false;
        CharSequence text = getText();
        if (text instanceof Spannable) Selection.removeSelection((Spannable) text);
        clearFocus();
        return true;
    }

    private void copySettled() {
        copy(settle.due(android.os.SystemClock.uptimeMillis()));
    }

    private String selected() {
        int start = Math.max(0, Math.min(getSelectionStart(), getSelectionEnd()));
        int end = Math.max(getSelectionStart(), getSelectionEnd());
        CharSequence text = getText();
        return end <= start || end > text.length() ? "" : text.subSequence(start, end).toString();
    }

    private void copy(String text) {
        if (text == null || text.isEmpty()) return;
        String said = SelectableText.copy(getContext(), text);
        if (said == null) return;
        note.accept(said);
        announceForAccessibility(said);
    }

    /**
     * Puts {@code text} on the clipboard as Herdr's, marked sensitive, cut to
     * {@link SelectionCopy#CAP_BYTES}; returns what to say, or null when the
     * clipboard refused (a locked device throws).
     */
    static String copy(Context context, String text) {
        SelectionCopy.Clip clip = SelectionCopy.of(text);
        ClipboardManager clipboard = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard == null) return null;
        ClipData data = ClipData.newPlainText(SelectionCopy.LABEL, clip.text);
        if (Build.VERSION.SDK_INT >= 33) {
            PersistableBundle extras = new PersistableBundle();
            extras.putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true);
            data.getDescription().setExtras(extras);
        }
        try {
            clipboard.setPrimaryClip(data);
        } catch (RuntimeException e) {
            return null;
        }
        return clip.said();
    }

    /** Any {@link SelectableText} under {@code view} holds a selection. */
    static boolean anySelected(View view) {
        if (view instanceof SelectableText) return ((SelectableText) view).selecting();
        if (!(view instanceof ViewGroup)) return false;
        ViewGroup group = (ViewGroup) view;
        for (int i = 0; i < group.getChildCount(); i++) if (anySelected(group.getChildAt(i))) return true;
        return false;
    }

    /** Clears any selection under {@code view}; true when one was cleared. */
    static boolean clearAll(View view) {
        if (view instanceof SelectableText) return ((SelectableText) view).clearSelection();
        if (!(view instanceof ViewGroup)) return false;
        ViewGroup group = (ViewGroup) view;
        boolean cleared = false;
        for (int i = 0; i < group.getChildCount(); i++) cleared |= clearAll(group.getChildAt(i));
        return cleared;
    }
}
