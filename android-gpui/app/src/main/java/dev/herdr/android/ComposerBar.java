package dev.herdr.android;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.drawable.GradientDrawable;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.BaseInputConnection;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;
import dev.herdr.android.dictation.DictationEngine;
import dev.herdr.android.dictation.DictationException;
import dev.herdr.android.dictation.PendingClip;

/**
 * The bottom bar's input: a local-first composer over Claude and Codex panes,
 * one floating mic that dictates into it, and the terminal keys folded behind
 * a chevron.
 *
 * <p>The composer edits on the phone with no round trip: nothing reaches the
 * host until Enter, which queues the whole draft as one bracketed paste plus
 * Enter. Its field floats over the terminal, aligned to the bar's free middle,
 * so a long draft grows upward over the output without resizing the pane (a
 * resize would make the agent redraw over the link). The terminal keyboard
 * button keeps raw input one tap away.
 *
 * <p>Owns no connection: the activity sends through {@link Host}. Lives for the
 * activity; {@link #bar} may be called again when the toolbar is rebuilt.
 */
final class ComposerBar {
    interface Host {
        /** Queues a mailbox command for the pane on screen; false when it was not queued. */
        boolean queue(String commandJson);
        /**
         * Sends a finished draft to the pane on screen: at once when the link
         * is up, or through the outbox when it is not. False keeps the draft.
         */
        boolean send(String text);
        /** Sends Enter as a terminal key, with the toolbar's usual Enter behaviour. */
        void enterKey();
        /** Hides the on-screen keyboard, whichever view owns it. */
        void hideKeyboard();
        /** A short status for the user; the connection stays as it is. */
        void notice(String message);
        /** The Enter badge was tapped: list the pane's queued messages. */
        void showQueue(java.util.List<Outbox.Message> messages);
        /**
         * Runs {@code granted} once the microphone may be used: at once when
         * RECORD_AUDIO is already granted, or after the user allows it.
         */
        void withMicrophone(Runnable granted);
    }

    private static final String PREFS = "terminal-display";
    private static final String KEYS_OPEN = "keys_row_open";
    /** Every pane's draft, so a closed or killed app finds what was typed. */
    private static final String DRAFTS = "composer_drafts";
    private static final long SAVE_DRAFTS_MS = 400;
    private static final int MAX_LINES = 6;

    private final Context context;
    private final SharedPreferences preferences;
    private final DictationEngine dictation;
    private final Host host;
    private final ComposerDrafts drafts = new ComposerDrafts();
    private final float density;

    /** Above the bar, holding the terminal keys while the chevron has them open. */
    private final FrameLayout keysRow;
    /** Floats over the terminal; the parent frame positions it over {@link #slot}. */
    private final EditText field;
    /** What the activity floats over the terminal: the field. */
    private final LinearLayout stack;
    /** The outbox's messages for this pane, counted on Enter's badge. */
    private java.util.List<Outbox.Message> queuedMessages = java.util.Collections.emptyList();
    private TextView queueBadge;
    /** Floats over the terminal straight above Enter; the activity adds it to the same frame. */
    private final MicButton mic;
    /** Recording lengths of clips dictated this run, for their chips in the draft. */
    private final java.util.Map<String, Long> clipLengths = new java.util.HashMap<>();
    private GpuiChrome chrome;

    private LinearLayout bar;
    private View pinned;
    private View keyStrip;
    private FrameLayout slot;
    private ImageButton chevron;
    private View enterBox;

    private boolean agent;
    private boolean keysOpen;
    private DictationEngine.State dictationState = DictationEngine.State.IDLE;
    /** Enter was pressed while listening: send the utterance once it is final. */
    private boolean sendWhenFinal;
    /** Our own setText calls, which must not count as user edits. */
    private boolean updatingField;

    ComposerBar(Context context, GpuiChrome chrome, DictationEngine dictation, Host host) {
        this.context = context;
        this.chrome = chrome;
        this.dictation = dictation;
        this.host = host;
        this.density = context.getResources().getDisplayMetrics().density;
        preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        keysOpen = preferences.getBoolean(KEYS_OPEN, false);
        drafts.restore(preferences.getString(DRAFTS, ""));
        keysRow = new FrameLayout(context);
        field = createField();
        stack = new LinearLayout(context);
        stack.setOrientation(LinearLayout.VERTICAL);
        stack.addView(field, new LinearLayout.LayoutParams(-1, -2));
        stack.setVisibility(View.GONE);
        mic = new MicButton(context, chrome, v -> toggleDictation());
        mic.view().setVisibility(View.GONE);
    }

    /** The floating mic; the activity adds it to the frame that holds {@link #field}. */
    View mic() { return mic.view(); }

    /** Recordings waiting for the desktop, counted on the mic; their lengths label their chips. */
    void setWaiting(int count) {
        for (PendingClip clip : Dictation.pending(context).waiting()) clipLengths.put(clip.id, clip.durationMs);
        mic.setWaiting(count);
        DraftChips.apply(field.getText(), chip());
    }

    /**
     * A recording made offline came back as text: it replaces its chip in
     * whichever pane's draft holds it, saved at once. False when no draft does.
     */
    boolean placeDictation(String clipId, String text) {
        String pane = drafts.placeDictation(clipId, text);
        if (pane == null) return false;
        clipLengths.remove(clipId);
        if (pane.equals(drafts.pane())) showText(drafts.text());
        persist();
        return true;
    }

    /** A dictation whose chip is gone from everywhere: it joins the end of its pane's draft. */
    void appendDictation(String pane, String text) {
        String draft = drafts.append(pane, text);
        if ((pane == null ? "" : pane).equals(drafts.pane())) showText(draft);
        persist();
    }

    /** The renderer's latest report of where the screen is busy (ClusterPlacement). */
    private ClusterPlacement.Hint layout;

    /** Where the drawn screen is busy, from the renderer after a paint that changed it. */
    void layoutHint(String json) {
        ClusterPlacement.Hint hint = ClusterPlacement.Hint.parse(json);
        if (hint != null) layout = hint;
    }

    /** The collapsible keys row; the activity places it directly above the bar. */
    View keysRow() { return keysRow; }

    /** The floating composer field; the activity adds it to a frame over the terminal column. */
    View field() { return stack; }

    /** The outbox's messages for the pane on screen (from {@link Outbox#shown}). */
    void showQueued(java.util.List<Outbox.Message> messages) {
        queuedMessages = messages;
        updateQueueBadge();
    }

    /** A queued message taken back: its text joins the draft, on a line of its own. */
    void takeBack(String text) {
        if (text == null || text.isEmpty()) return;
        String draft = drafts.text();
        String joined = draft.trim().isEmpty() ? text : draft.replaceAll("\\s+$", "") + "\n" + text;
        drafts.edit(joined);
        showText(joined);
    }

    /**
     * Builds the 48 dp bar: the toolbar's pinned controls, the middle slot
     * (the composer, or a shell's keys), the keys chevron and Enter, which
     * closes the bar so nothing sits dead beyond it.
     */
    View bar(GpuiChrome chrome, View pinnedControls, View keys) {
        this.chrome = chrome;
        detach(keyStrip);
        pinned = pinnedControls;
        keyStrip = keys;
        bar = new LinearLayout(context);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(chrome.surface);
        bar.addView(pinned, new LinearLayout.LayoutParams(-2, dp(48)));
        slot = new FrameLayout(context);
        slot.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> v.post(this::placeField));
        bar.addView(slot, new LinearLayout.LayoutParams(0, dp(48), 1));
        chevron = icon(R.drawable.ic_bar_expand, "Keys", v -> setKeysOpen(!keysOpen));
        bar.addView(chevron);
        Button enter = new Button(context);
        enter.setText("Enter");
        enter.setTextSize(13);
        enter.setAllCaps(false);
        enter.setTextColor(chrome.onSurface);
        enter.setMinWidth(dp(64));
        enter.setMinimumWidth(dp(64));
        enter.setMinHeight(dp(48));
        enter.setMinimumHeight(dp(48));
        enter.setPadding(dp(12), 0, dp(12), 0);
        enter.setBackground(fill(chrome.control, 10, dp(4)));
        enter.setStateListAnimator(null);
        enter.setFocusable(false);
        enter.setSaveEnabled(false);
        enter.setContentDescription("Enter: send the draft, or press Enter in the terminal");
        enter.setOnClickListener(v -> enter());
        enter.setOnLongClickListener(v -> {
            if (queuedMessages.isEmpty()) return false;
            host.showQueue(queuedMessages);
            return true;
        });
        // The queue's count rides on Enter's corner, so queued messages cost
        // no line over the terminal; it overhangs into the bar's own padding.
        FrameLayout enterBox = new FrameLayout(context);
        this.enterBox = enterBox;
        enterBox.setClipChildren(false);
        enterBox.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> v.post(this::placeMic));
        enterBox.addView(enter, new FrameLayout.LayoutParams(-2, dp(48)));
        queueBadge = createQueueBadge();
        enterBox.addView(queueBadge, new FrameLayout.LayoutParams(-2, dp(18), Gravity.TOP | Gravity.START));
        bar.setClipChildren(false);
        LinearLayout.LayoutParams enterParams = new LinearLayout.LayoutParams(-2, dp(48));
        enterParams.setMargins(dp(2), 0, dp(6), 0);
        bar.addView(enterBox, enterParams);
        keysRow.setBackgroundColor(chrome.surface);
        styleField();
        updateQueueBadge();
        mic.style(chrome);
        apply();
        return bar;
    }

    /** The pane on screen changed, or what it runs did. Only agents get the composer. */
    void setPane(String paneId, boolean agentPane) {
        boolean switched = !drafts.pane().equals(paneId == null ? "" : paneId);
        if (switched && dictationState == DictationEngine.State.OFFLINE_RECORDING) {
            // Keep the recording: its text finds its pane's draft later, by target.
            sendWhenFinal = false;
            dictation.stop();
            drafts.dictationEnded();
        } else if (switched && dictating()) {
            // An utterance belongs to the pane it was spoken for.
            dictation.cancel();
            setDictationState(DictationEngine.State.IDLE);
            sendWhenFinal = false;
            drafts.dictationEnded();
        }
        String text = drafts.switchTo(paneId);
        if (switched) showText(text);
        if (agentPane == agent && !switched) return;
        agent = agentPane;
        apply();
    }

    /** Physical keys go to the field, not the terminal, while it is being typed in. */
    boolean ownsKeys() {
        return field.getVisibility() == View.VISIBLE && field.hasFocus();
    }

    void destroy() {
        dictation.cancel();
        mic.destroy();
        persist();
    }

    /** Saves the drafts now (the activity is stopping: the process may be killed next). */
    void persist() {
        field.removeCallbacks(saveDrafts);
        saveDrafts.run();
    }

    private final Runnable saveDrafts = this::saveDrafts;

    private void saveDrafts() {
        preferences.edit().putString(DRAFTS, drafts.encode()).apply();
    }

    /** Typing saves at most a few times a second; apply() writes off the main thread. */
    private void saveDraftsSoon() {
        field.removeCallbacks(saveDrafts);
        field.postDelayed(saveDrafts, SAVE_DRAFTS_MS);
    }

    private void setKeysOpen(boolean open) {
        keysOpen = open;
        preferences.edit().putBoolean(KEYS_OPEN, open).apply();
        apply();
        mic.view().post(this::placeMic);
    }

    /** Puts every part where the current mode wants it. No view changes height. */
    private void apply() {
        if (bar == null) return;
        boolean composing = agent;
        detach(keyStrip);
        slot.removeAllViews();
        keysRow.removeAllViews();
        if (composing) {
            if (keysOpen) keysRow.addView(keyStrip, new FrameLayout.LayoutParams(-1, dp(48)));
        } else {
            // A shell's keys are its main input: they stay in the bar itself.
            slot.addView(keyStrip, new FrameLayout.LayoutParams(-1, dp(48)));
        }
        keysRow.setVisibility(composing && keysOpen ? View.VISIBLE : View.GONE);
        chevron.setVisibility(composing ? View.VISIBLE : View.GONE);
        chevron.setRotation(keysOpen ? 180f : 0f);
        String keys = keysOpen ? "Hide the terminal keys" : "Show Ctrl, Esc, Tab and the other terminal keys";
        chevron.setContentDescription(keys);
        chevron.setTooltipText(keys);
        field.setVisibility(composing ? View.VISIBLE : View.GONE);
        if (!composing && field.hasFocus()) field.clearFocus();
        // Dictation lands in the draft, so the mic is only where a draft is;
        // with no server configured there is nothing for it to do.
        mic.view().setVisibility(composing && dictation.isAvailable() ? View.VISIBLE : View.GONE);
        updateStack();
        placeMic();
    }

    /** Shows the floating stack while it holds the field. */
    private void updateStack() {
        boolean shown = field.getVisibility() == View.VISIBLE;
        stack.setVisibility(shown ? View.VISIBLE : View.GONE);
        placeField();
    }

    /**
     * Aligns the floating field with the slot, its bottom on the bar's bottom.
     */
    private void placeField() {
        if (slot == null || !(stack.getParent() instanceof ViewGroup) || slot.getWidth() == 0) return;
        ViewGroup frame = (ViewGroup) stack.getParent();
        int[] at = new int[2];
        int[] origin = new int[2];
        slot.getLocationInWindow(at);
        frame.getLocationInWindow(origin);
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(slot.getWidth(), -2,
                Gravity.BOTTOM | Gravity.START);
        params.leftMargin = at[0] - origin[0];
        params.bottomMargin = Math.max(0, origin[1] + frame.getHeight() - at[1] - slot.getHeight());
        FrameLayout.LayoutParams old = (FrameLayout.LayoutParams) stack.getLayoutParams();
        if (old != null && old.width == params.width && old.leftMargin == params.leftMargin
                && old.bottomMargin == params.bottomMargin && old.gravity == params.gravity) return;
        stack.setLayoutParams(params);
    }

    /**
     * Stacks the mic straight above Enter, over the terminal: its right edge on
     * Enter's, its bottom a little above the bar (or the open keys row), so the
     * thumb goes from speaking to sending in one column.
     */
    private void placeMic() {
        View view = mic.view();
        if (enterBox == null || !(view.getParent() instanceof ViewGroup) || enterBox.getWidth() == 0) return;
        ViewGroup frame = (ViewGroup) view.getParent();
        View top = keysRow.getVisibility() == View.VISIBLE && keysRow.getHeight() > 0 ? keysRow : enterBox;
        int[] enterAt = new int[2];
        int[] topAt = new int[2];
        int[] origin = new int[2];
        enterBox.getLocationInWindow(enterAt);
        top.getLocationInWindow(topAt);
        frame.getLocationInWindow(origin);
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.END);
        params.rightMargin = Math.max(0, origin[0] + frame.getWidth() - enterAt[0] - enterBox.getWidth());
        params.bottomMargin = Math.max(0, origin[1] + frame.getHeight() - topAt[1]) + dp(8);
        FrameLayout.LayoutParams old = (FrameLayout.LayoutParams) view.getLayoutParams();
        if (old != null && old.rightMargin == params.rightMargin && old.bottomMargin == params.bottomMargin
                && old.gravity == params.gravity) return;
        view.setLayoutParams(params);
    }

    private EditText createField() {
        EditText edit = new EditText(context);
        edit.setSaveEnabled(false);
        edit.setTextSize(14);
        edit.setHint("Message");
        edit.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        // Single-line input type so the keyboard's action key sends; it still
        // wraps and grows to MAX_LINES. Shift+Enter on a hardware keyboard and
        // pasted text keep their line breaks.
        edit.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                | InputType.TYPE_TEXT_FLAG_AUTO_CORRECT);
        edit.setHorizontallyScrolling(false);
        edit.setMaxLines(MAX_LINES);
        edit.setMinHeight(dp(48));
        edit.setImeOptions(EditorInfo.IME_ACTION_SEND | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        edit.setOnEditorActionListener((view, action, event) -> {
            if (action == EditorInfo.IME_ACTION_SEND
                    || (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER)) {
                if (event == null || event.getAction() == KeyEvent.ACTION_DOWN) enter();
                return true;
            }
            return false;
        });
        edit.setOnKeyListener((view, code, event) -> {
            if (code != KeyEvent.KEYCODE_ENTER && code != KeyEvent.KEYCODE_NUMPAD_ENTER) return false;
            if (event.getAction() != KeyEvent.ACTION_DOWN) return true;
            if (event.isShiftPressed()) {
                Editable text = edit.getText();
                text.replace(Math.min(edit.getSelectionStart(), edit.getSelectionEnd()),
                        Math.max(edit.getSelectionStart(), edit.getSelectionEnd()), "\n");
            } else {
                enter();
            }
            return true;
        });
        edit.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { }
            @Override public void afterTextChanged(Editable s) {
                if (updatingField) return;
                // A backspace into a chip takes the whole chip; its text is
                // appended to the draft when it comes, so nothing is lost.
                String text = s.toString();
                String repaired = DraftChips.repair(text);
                if (!repaired.equals(text)) {
                    showText(repaired);
                    drafts.edit(repaired);
                    return;
                }
                DraftChips.apply(s, chip());
                drafts.edit(text);
                saveDraftsSoon();
            }
        });
        edit.setVisibility(View.GONE);
        return edit;
    }

    private void styleField() {
        field.setTextColor(chrome.onSurface);
        field.setHintTextColor(chrome.onSurfaceVariant);
        GradientDrawable box = new GradientDrawable();
        box.setColor(chrome.control);
        box.setStroke(Math.max(1, dp(1)), chrome.outline);
        box.setCornerRadius(dp(10));
        field.setBackground(new android.graphics.drawable.InsetDrawable(box, 0, dp(4), 0, dp(4)));
        field.setPadding(dp(10), dp(8), dp(10), dp(8));
    }

    private TextView createQueueBadge() {
        TextView badge = new TextView(context);
        badge.setTextSize(11);
        badge.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        badge.setGravity(Gravity.CENTER);
        badge.setIncludeFontPadding(false);
        badge.setMinWidth(dp(18));
        badge.setPadding(dp(5), 0, dp(5), 0);
        badge.setTranslationX(-dp(6));
        badge.setTranslationY(-dp(1));
        badge.setOnClickListener(v -> host.showQueue(queuedMessages));
        badge.setVisibility(View.GONE);
        return badge;
    }

    /** Enter's corner: how many messages wait for this pane, in the most pressing one's colour. */
    private void updateQueueBadge() {
        if (queueBadge == null) return;
        String count = QueuedMessages.badge(queuedMessages.size());
        queueBadge.setVisibility(count.isEmpty() ? View.GONE : View.VISIBLE);
        if (count.isEmpty()) return;
        int color;
        switch (QueuedMessages.urgency(queuedMessages)) {
            case GONE: color = chrome.danger; break;
            case WAITING: color = chrome.warning; break;
            case SENDING: color = chrome.info; break;
            default: color = chrome.primary;
        }
        GradientDrawable pill = new GradientDrawable();
        pill.setColor(color);
        pill.setCornerRadius(dp(9));
        pill.setStroke(dp(2), chrome.surface);
        queueBadge.setBackground(pill);
        queueBadge.setTextColor(chrome.light ? 0xffffffff : chrome.background);
        queueBadge.setText(count);
        String label = QueuedMessages.title(queuedMessages.size()) + "; tap to list";
        queueBadge.setContentDescription(label);
        queueBadge.setTooltipText(label);
    }

    /** A session runs: IDLE and PENDING both mean none does. */
    private boolean dictating() {
        return dictationState != DictationEngine.State.IDLE && dictationState != DictationEngine.State.PENDING;
    }

    private void setDictationState(DictationEngine.State state) {
        dictationState = state;
        mic.setState(state);
    }

    /** The mic's one tap: start, or stop what is recording. Transcribing ignores it. */
    private void toggleDictation() {
        if (MicButton.recording(dictationState)) {
            dictation.stop();
            return;
        }
        if (dictating()) return;
        if (!dictation.isAvailable()) {
            host.notice("Dictation is not set up on this phone");
            return;
        }
        // The engine refuses to start without RECORD_AUDIO; ask first, start on yes.
        host.withMicrophone(this::startDictation);
    }

    private void startDictation() {
        if (!agent || dictating()) return;
        drafts.dictationStarted();
        final String pane = drafts.pane();
        dictation.start(pane, new DictationEngine.Listener() {
            @Override public void onPartial(String text) {
                if (pane.equals(drafts.pane())) showText(drafts.dictationPartial(text));
            }

            @Override public void onFinal(String text) {
                if (!pane.equals(drafts.pane())) return;
                showText(drafts.dictationFinal(text));
                if (sendWhenFinal) {
                    sendWhenFinal = false;
                    // The session is over (IDLE follows): Enter must send, not wait again.
                    setDictationState(DictationEngine.State.IDLE);
                    enter();
                }
            }

            @Override public void onPending(PendingClip clip) {
                clipLengths.put(clip.id, clip.durationMs);
                if (!pane.equals(drafts.pane())) return;
                showText(drafts.dictationPending(clip.id));
                if (sendWhenFinal) {
                    // The message waits in the outbox until the desktop fills the dictation in.
                    sendWhenFinal = false;
                    setDictationState(DictationEngine.State.PENDING);
                    enter();
                }
            }

            @Override public void onState(DictationEngine.State state) {
                setDictationState(state);
            }

            @Override public void onError(DictationException error) {
                setDictationState(DictationEngine.State.IDLE);
                sendWhenFinal = false;
                if (pane.equals(drafts.pane())) showText(drafts.dictationEnded());
                host.notice(error.getMessage() == null ? "Dictation stopped" : error.getMessage());
            }
        });
    }

    /**
     * Enter: while recording, finish the utterance and send it; with a draft,
     * queue it as one paste plus Enter; otherwise Enter goes to the program.
     */
    private void enter() {
        if (MicButton.recording(dictationState)) {
            sendWhenFinal = true;
            dictation.stop();
            return;
        }
        if (dictationState == DictationEngine.State.TRANSCRIBING) {
            sendWhenFinal = true;
            return;
        }
        if (agent && field.getVisibility() == View.VISIBLE) {
            // Commit the keyboard's underlined word into the draft before reading it.
            BaseInputConnection.removeComposingSpans(field.getText());
            InputMethodManager input = context.getSystemService(InputMethodManager.class);
            if (input != null && field.hasFocus()) input.restartInput(field);
            drafts.edit(field.getText().toString());
        }
        String outgoing = agent ? drafts.outgoing() : null;
        if (outgoing == null) {
            host.enterKey();
            return;
        }
        // The draft stays until the message is queued or kept, so nothing is lost.
        if (!host.send(outgoing)) return;
        drafts.sent();
        showText("");
    }

    private void showText(String text) {
        saveDraftsSoon();
        if (!field.getText().toString().equals(text)) {
            updatingField = true;
            try {
                field.setText(text);
                field.setSelection(text.length());
            } finally {
                updatingField = false;
            }
        }
        DraftChips.apply(field.getText(), chip());
    }

    /** How a waiting dictation's token reads in the field. */
    private DraftChips.Style chip() {
        return new DraftChips.Style(chrome.warning, density, clipLengths,
                context.getDrawable(R.drawable.ic_bar_schedule));
    }

    private ImageButton icon(int drawable, String label, View.OnClickListener action) {
        ImageButton button = new ImageButton(context);
        button.setImageResource(drawable);
        button.setImageTintList(ColorStateList.valueOf(chrome.onSurface));
        button.setBackground(fill(0x00000000, 12, dp(4)));
        button.setScaleType(android.widget.ImageView.ScaleType.CENTER);
        button.setContentDescription(label);
        button.setTooltipText(label);
        button.setFocusable(false);
        button.setSaveEnabled(false);
        button.setOnClickListener(action);
        button.setLayoutParams(new LinearLayout.LayoutParams(dp(44), dp(48)));
        return button;
    }

    private android.graphics.drawable.Drawable fill(int color, int radiusDp, int inset) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(color);
        shape.setCornerRadius(dp(radiusDp));
        GradientDrawable mask = new GradientDrawable();
        mask.setColor(0xffffffff);
        mask.setCornerRadius(dp(radiusDp));
        return new android.graphics.drawable.RippleDrawable(
                ColorStateList.valueOf(GpuiChrome.withAlpha(chrome.onSurface, 0x29)),
                new android.graphics.drawable.InsetDrawable(shape, 0, inset, 0, inset),
                new android.graphics.drawable.InsetDrawable(mask, 0, inset, 0, inset));
    }

    private static void detach(View view) {
        if (view != null && view.getParent() instanceof ViewGroup) ((ViewGroup) view.getParent()).removeView(view);
    }

    private int dp(float value) { return Math.round(value * density); }
}
