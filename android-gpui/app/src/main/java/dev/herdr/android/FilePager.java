package dev.herdr.android;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import java.util.concurrent.Executor;
import java.util.function.BiFunction;
import org.json.JSONObject;

/**
 * A read-only, highlighted file over the terminal's area. A file opens from a tapped path in the
 * terminal or the panel, or an agent's {@code open_file} event; it comes DOWN FROM THE TOP over
 * the terminal (Wolf 11-22) and goes back up with an upward swipe that starts on its header, or
 * one past the end of the file, or Back or the header's arrow. Scrolling and selecting the text
 * keep their own gestures. The terminal keeps running underneath and the file page lies over it,
 * so neither page changes the other's layout, and closing returns to exactly what was there. A
 * small tab at the top says a file is there; a tap or a downward pull on it brings it back.
 */
final class FilePager extends FrameLayout {
    /** Reads {@code (terminal, path)} with a blocking session action; returns its JSON reply. */
    private final BiFunction<String, String, String> reader;
    private final Executor worker;
    private final LinearLayout page;
    private final TextView title;
    private final TextView where;
    private final TextView back;
    private final ListView list;
    private final View handle;
    private final Adapter adapter = new Adapter();
    private GpuiChrome chrome;
    private FileDocument document;
    /** The last read asked for; a slower earlier answer is dropped. */
    private int request;
    private boolean shown;
    /** Told when the file page opens or closes over the terminal. */
    private Runnable onShownChanged;
    private long lastSeq;
    private boolean seeded;
    // Drag state: where it started, and whether this pager claimed it.
    private float downX, downY;
    private boolean dragging;
    private final int slop;

    FilePager(Context context, View terminal, GpuiChrome chrome,
            BiFunction<String, String, String> reader, Executor worker) {
        super(context);
        this.reader = reader;
        this.worker = worker;
        this.chrome = chrome;
        slop = ViewConfiguration.get(context).getScaledTouchSlop() * 2;
        addView(terminal, new LayoutParams(-1, -1));

        page = new LinearLayout(context);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setVisibility(INVISIBLE);
        page.setClickable(true);
        LinearLayout header = new LinearLayout(context);
        header.setGravity(Gravity.CENTER_VERTICAL);
        back = label(context, "‹", 18);
        back.setPadding(dp(10), 0, dp(10), 0);
        back.setContentDescription("Back to the terminal");
        back.setOnClickListener(v -> hide());
        title = label(context, "", 13);
        title.setEllipsize(TextUtils.TruncateAt.START);
        where = label(context, "", 12);
        where.setPadding(dp(8), 0, dp(10), 0);
        header.addView(back, new LinearLayout.LayoutParams(-2, -1));
        header.addView(title, new LinearLayout.LayoutParams(0, -1, 1));
        header.addView(where, new LinearLayout.LayoutParams(-2, -1));
        page.addView(header, new LinearLayout.LayoutParams(-1, dp(28)));
        list = new ListView(context);
        list.setDivider(null);
        list.setFastScrollEnabled(true);
        list.setAdapter(adapter);
        page.addView(list, new LinearLayout.LayoutParams(-1, 0, 1));
        addView(page, new LayoutParams(-1, -1));

        handle = new View(context);
        handle.setVisibility(GONE);
        handle.setContentDescription("Show the file");
        handle.setOnClickListener(v -> show());
        // A wide touch target around a thin mark, at the top: the file comes down from there.
        addView(handle, new LayoutParams(dp(56), dp(14), Gravity.TOP | Gravity.CENTER_HORIZONTAL));
        applyChrome(chrome);
    }

    /** Repaints for a theme change. */
    void applyChrome(GpuiChrome next) {
        chrome = next;
        page.setBackgroundColor(chrome.background);
        ((View) title.getParent()).setBackgroundColor(chrome.surface);
        title.setTextColor(chrome.onSurface);
        back.setTextColor(chrome.onSurface);
        where.setTextColor(chrome.onSurfaceVariant);
        GradientDrawable mark = new GradientDrawable();
        mark.setColor(GpuiChrome.withAlpha(chrome.outline, 200));
        mark.setCornerRadius(dp(2));
        // Inset: the mark is 3dp at the top edge, the rest is touch target.
        handle.setBackground(new android.graphics.drawable.InsetDrawable(mark, dp(8), 0, dp(8), dp(11)));
        adapter.notifyDataSetChanged();
    }

    /** Whether the file page is the one on screen. */
    boolean showingFile() {
        return shown;
    }

    /** Opens {@code path} (from {@code terminal}'s folder) at {@code line}, reading it off the UI thread. */
    void open(String terminal, String path, int line) {
        open(terminal, path, line, reader);
    }

    /** As {@link #open(String, String, int)}, read through {@code through} (another machine's session). */
    void open(String terminal, String path, int line, BiFunction<String, String, String> through) {
        if (terminal == null || terminal.isEmpty() || path == null || path.isEmpty()) return;
        final int ticket = ++request;
        title.setText(path);
        where.setText("reading…");
        document = null;
        adapter.notifyDataSetChanged();
        show();
        worker.execute(() -> {
            FileDocument read;
            try {
                read = FileDocument.parse(path, line, through.apply(terminal, path));
            } catch (RuntimeException | LinkageError failure) {
                read = FileDocument.failed(path, line, "Reopen and check the session");
            }
            final FileDocument done = read;
            post(() -> {
                if (ticket == request) setDocument(done);
            });
        });
    }

    /**
     * An {@code open_file} from the session's view. The first one seen only
     * marks where events start: a view kept from before this screen opened
     * must not reopen an old file.
     */
    void offer(JSONObject event, String shownTerminal) {
        long seq = event == null ? 0 : event.optLong("seq", 0);
        if (!seeded) {
            seeded = true;
            lastSeq = seq;
            return;
        }
        if (!FileDocument.shouldOpen(event, shownTerminal, lastSeq)) return;
        lastSeq = seq;
        open(shownTerminal, event.optString("path"), event.optInt("line", 0));
    }

    private void setDocument(FileDocument next) {
        document = next;
        title.setText(next.path);
        if (next.savedAtMs > 0) {
            // A copy read while the link was up (offline redesign PR-F): never the file now.
            String when = OfflineSwitch.when(next.savedAtMs, System.currentTimeMillis(), java.util.TimeZone.getDefault(),
                    android.text.format.DateFormat.is24HourFormat(getContext()), java.util.Locale.getDefault());
            where.setText(FileDocument.savedNote(when, next));
        } else if (next.error != null) {
            where.setText(next.error);
        } else {
            String count = next.total > next.lines.size()
                    ? next.lines.size() + " of " + next.total + " lines" : next.total + " lines";
            where.setText(next.line > 0 ? "L" + next.line + " · " + count : count);
        }
        adapter.notifyDataSetChanged();
        list.setSelection(next.scrollRow());
    }

    /** Runs {@code listener} whenever the file page opens or closes. */
    void setOnShownChanged(Runnable listener) { onShownChanged = listener; }

    void show() {
        if (getWidth() == 0) {
            shown = true;
            if (onShownChanged != null) onShownChanged.run();
            page.setVisibility(VISIBLE);
            handle.setVisibility(GONE);
            return;
        }
        settle(true);
    }

    /** Back to the terminal; false when the file was not showing. */
    boolean hide() {
        if (!shown) return false;
        settle(false);
        return true;
    }

    private void settle(boolean open) {
        boolean changed = shown != open;
        shown = open;
        if (changed && onShownChanged != null) onShownChanged.run();
        page.setVisibility(VISIBLE);
        page.animate().translationY(open ? 0 : -getHeight()).setDuration(180).withEndAction(() -> {
            if (!shown) page.setVisibility(INVISIBLE);
        }).start();
        handle.setVisibility(!open && hasFile() ? VISIBLE : GONE);
    }

    @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        page.setTranslationX(0);
        page.setTranslationY(shown ? 0 : -h);
    }

    /**
     * Claims a mostly vertical drag that moves the page (Dismiss.claims): upward on the file page
     * when it starts on the header or the list is at its end, downward from the top tab while a
     * file is there. A claimed drag reaches the views under it as a cancel, so it never becomes
     * a tap, a scroll or a selection.
     */
    @Override public boolean onInterceptTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = event.getX();
                downY = event.getY();
                dragging = false;
                return false;
            case MotionEvent.ACTION_MOVE:
                if (event.getPointerCount() > 1) return false;
                float dx = Math.abs(event.getX() - downX);
                float dy = event.getY() - downY;
                boolean onHeader = downY < page.getTranslationY() + dp(28);
                boolean onTab = !shown && hasFile() && downY < dp(28) && Math.abs(downX - getWidth() / 2f) < dp(48);
                dragging = Dismiss.claims(shown, dy, dx, slop, onHeader, !list.canScrollVertically(1), onTab);
                if (dragging) page.setVisibility(VISIBLE);
                return dragging;
            default:
                return false;
        }
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        if (!dragging) return super.onTouchEvent(event);
        float dy = event.getY() - downY;
        int height = getHeight();
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_MOVE:
                float base = shown ? 0 : -height;
                page.setTranslationY(Math.max(-height, Math.min(0, base + dy)));
                return true;
            case MotionEvent.ACTION_UP:
                dragging = false;
                // A third of the way commits; less springs back.
                settle(shown ? -dy < height / 3f : dy > height / 3f);
                return true;
            case MotionEvent.ACTION_CANCEL:
                dragging = false;
                settle(shown);
                return true;
            default:
                return true;
        }
    }

    /** Which vertical drags move the page: pure, for tests. */
    static final class Dismiss {
        private Dismiss() { }

        /**
         * {@code dy} down positive. Shown: an upward drag past the slop, mostly vertical, that
         * starts on the header or comes when the list is already at its end. Hidden: a downward
         * drag from the top tab.
         */
        static boolean claims(boolean shown, float dy, float dx, float slop, boolean onHeader, boolean atEnd,
                boolean onTab) {
            boolean vertical = Math.abs(dy) > slop && Math.abs(dy) > dx * 2;
            if (!vertical) return false;
            if (shown) return dy < 0 && (onHeader || atEnd);
            return dy > 0 && onTab;
        }
    }

    private boolean hasFile() {
        return document != null || request > 0;
    }

    private TextView label(Context context, String text, int sp) {
        TextView view = new TextView(context);
        view.setText(text);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        view.setSingleLine(true);
        view.setGravity(Gravity.CENTER_VERTICAL);
        return view;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private int color(int kind) {
        switch (kind) {
            case SyntaxHighlighter.KEYWORD: case SyntaxHighlighter.HEADING: return chrome.primary;
            case SyntaxHighlighter.STRING: return chrome.success;
            case SyntaxHighlighter.COMMENT: return chrome.onSurfaceVariant;
            case SyntaxHighlighter.NUMBER: return chrome.warning;
            case SyntaxHighlighter.TYPE: return chrome.info;
            default: return chrome.onSurface;
        }
    }

    /** One row per line: a right-aligned number gutter, then the wrapped, coloured text. */
    private final class Adapter extends BaseAdapter {
        @Override public int getCount() {
            FileDocument doc = document;
            return doc == null ? 0 : doc.error != null ? 1 : doc.lines.size();
        }

        @Override public Object getItem(int position) {
            return position;
        }

        @Override public long getItemId(int position) {
            return position;
        }

        @Override public View getView(int position, View convert, ViewGroup parent) {
            TextView row = convert instanceof TextView ? (TextView) convert : new TextView(getContext());
            row.setTypeface(Typeface.MONOSPACE);
            row.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            row.setPadding(dp(4), 0, dp(6), 0);
            row.setTextColor(chrome.onSurface);
            FileDocument doc = document;
            if (doc == null) return row;
            if (doc.error != null) {
                row.setText(doc.error);
                row.setTextColor(chrome.onSurfaceVariant);
                row.setBackgroundColor(0);
                return row;
            }
            String text = doc.lines.get(position);
            int digits = Math.max(3, String.valueOf(doc.lines.size()).length());
            String number = String.valueOf(position + 1);
            SpannableStringBuilder line = new SpannableStringBuilder();
            for (int i = number.length(); i < digits; i++) line.append(' ');
            line.append(number).append("  ");
            int gutter = line.length();
            line.setSpan(new ForegroundColorSpan(chrome.onSurfaceVariant), 0, gutter, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            line.append(text);
            for (SyntaxHighlighter.Span span : doc.spans.get(position)) {
                if (span.kind == SyntaxHighlighter.PLAIN || span.end > text.length()) continue;
                line.setSpan(new ForegroundColorSpan(color(span.kind)), gutter + span.start,
                        gutter + span.end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                if (span.kind == SyntaxHighlighter.HEADING) {
                    line.setSpan(new StyleSpan(Typeface.BOLD), gutter + span.start, gutter + span.end,
                            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                }
            }
            row.setText(line);
            row.setBackgroundColor(position + 1 == doc.line ? GpuiChrome.withAlpha(chrome.selected, 140) : 0);
            return row;
        }
    }
}
