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

/**
 * The bottom bar's input: a local-first composer over Claude and Codex panes,
 * a mic-only mode, and the terminal keys folded behind a chevron.
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
        /** Sends Enter as a terminal key, with the toolbar's usual Enter behaviour. */
        void enterKey();
        /** Hides the on-screen keyboard, whichever view owns it. */
        void hideKeyboard();
        /** A short status for the user; the connection stays as it is. */
        void notice(String message);
        /**
         * Runs {@code granted} once the microphone may be used: at once when
         * RECORD_AUDIO is already granted, or after the user allows it.
         */
        void withMicrophone(Runnable granted);
    }

    private static final String PREFS = "terminal-display";
    private static final String KEYS_OPEN = "keys_row_open";
    private static final String MIC_ONLY = "mic_only";
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
    private GpuiChrome chrome;

    private LinearLayout bar;
    private View pinned;
    private View keyStrip;
    private FrameLayout slot;
    private ImageButton modeButton;
    private ImageButton chevron;
    private LinearLayout micPanel;
    private ImageButton record;
    private TextView transcript;

    private boolean agent;
    private boolean keysOpen;
    private boolean micOnly;
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
        micOnly = preferences.getBoolean(MIC_ONLY, false);
        keysRow = new FrameLayout(context);
        field = createField();
    }

    /** The collapsible keys row; the activity places it directly above the bar. */
    View keysRow() { return keysRow; }

    /** The floating composer field; the activity adds it to a frame over the terminal column. */
    View field() { return field; }

    /**
     * Builds the 48 dp bar: mode toggle at bottom-left, the toolbar's pinned
     * controls, the middle slot (composer, keys or transcript), the keys
     * chevron and Enter, which closes the bar so nothing sits dead beyond it.
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
        modeButton = icon(R.drawable.ic_bar_mic, "Mic only", v -> setMicOnly(!micOnly));
        bar.addView(modeButton);
        bar.addView(pinned, new LinearLayout.LayoutParams(-2, dp(48)));
        slot = new FrameLayout(context);
        slot.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> v.post(this::placeField));
        bar.addView(slot, new LinearLayout.LayoutParams(0, dp(48), 1));
        micPanel = createMicPanel();
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
        LinearLayout.LayoutParams enterParams = new LinearLayout.LayoutParams(-2, dp(48));
        enterParams.setMargins(dp(2), 0, dp(6), 0);
        bar.addView(enter, enterParams);
        keysRow.setBackgroundColor(chrome.surface);
        styleField();
        apply();
        return bar;
    }

    /** The pane on screen changed, or what it runs did. Only agents get the composer. */
    void setPane(String paneId, boolean agentPane) {
        boolean switched = !drafts.pane().equals(paneId == null ? "" : paneId);
        if (switched && dictationState != DictationEngine.State.IDLE) {
            // An utterance belongs to the pane it was spoken for.
            dictation.cancel();
            dictationState = DictationEngine.State.IDLE;
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
    }

    private void setMicOnly(boolean on) {
        if (micOnly == on) return;
        micOnly = on;
        preferences.edit().putBoolean(MIC_ONLY, on).apply();
        if (on) host.hideKeyboard();
        else if (dictationState != DictationEngine.State.IDLE) {
            dictation.cancel();
            dictationState = DictationEngine.State.IDLE;
            showText(drafts.dictationEnded());
        }
        apply();
    }

    private void setKeysOpen(boolean open) {
        keysOpen = open;
        preferences.edit().putBoolean(KEYS_OPEN, open).apply();
        apply();
    }

    /** Puts every part where the current mode wants it. No view changes height. */
    private void apply() {
        if (bar == null) return;
        boolean composing = agent && !micOnly;
        // Mic-only shows the mic, the transcript and Enter: nothing that types.
        pinned.setVisibility(micOnly ? View.GONE : View.VISIBLE);
        modeButton.setImageResource(micOnly ? R.drawable.ic_bar_keyboard : R.drawable.ic_bar_mic);
        String mode = micOnly ? "Leave mic-only mode" : "Mic only: dictate and send, no keyboard";
        modeButton.setContentDescription(mode);
        modeButton.setTooltipText(mode);
        detach(keyStrip);
        detach(micPanel);
        slot.removeAllViews();
        keysRow.removeAllViews();
        if (micOnly) slot.addView(micPanel, new FrameLayout.LayoutParams(-1, -1));
        else if (composing) {
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
        updateMicPanel();
        placeField();
    }

    /** Aligns the floating field with the slot, bottom to the bar's bottom. */
    private void placeField() {
        if (slot == null || !(field.getParent() instanceof ViewGroup) || slot.getWidth() == 0) return;
        ViewGroup frame = (ViewGroup) field.getParent();
        int[] at = new int[2];
        int[] origin = new int[2];
        slot.getLocationInWindow(at);
        frame.getLocationInWindow(origin);
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(slot.getWidth(), -2,
                Gravity.BOTTOM | Gravity.START);
        params.leftMargin = at[0] - origin[0];
        params.bottomMargin = Math.max(0, origin[1] + frame.getHeight() - at[1] - slot.getHeight());
        FrameLayout.LayoutParams old = (FrameLayout.LayoutParams) field.getLayoutParams();
        if (old != null && old.width == params.width && old.leftMargin == params.leftMargin
                && old.bottomMargin == params.bottomMargin && old.gravity == params.gravity) return;
        field.setLayoutParams(params);
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
                if (!updatingField) drafts.edit(s.toString());
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

    private LinearLayout createMicPanel() {
        LinearLayout panel = new LinearLayout(context);
        panel.setOrientation(LinearLayout.HORIZONTAL);
        panel.setGravity(Gravity.CENTER_VERTICAL);
        record = icon(R.drawable.ic_bar_mic, "Dictate", v -> toggleDictation());
        panel.addView(record);
        transcript = new TextView(context);
        transcript.setTextSize(13);
        transcript.setSingleLine(true);
        transcript.setEllipsize(android.text.TextUtils.TruncateAt.START);
        transcript.setGravity(Gravity.CENTER_VERTICAL);
        transcript.setPadding(dp(8), 0, dp(8), 0);
        transcript.setOnClickListener(v -> toggleDictation());
        transcript.setOnLongClickListener(v -> {
            if (dictationState != DictationEngine.State.IDLE) return false;
            drafts.sent();
            showText("");
            host.notice("Draft cleared");
            return true;
        });
        panel.addView(transcript, new LinearLayout.LayoutParams(0, -1, 1));
        return panel;
    }

    private void updateMicPanel() {
        if (record == null) return;
        boolean live = dictationState != DictationEngine.State.IDLE;
        record.setImageTintList(ColorStateList.valueOf(live ? chrome.danger : chrome.onSurface));
        record.setBackground(fill(live ? GpuiChrome.withAlpha(chrome.danger, 0x33) : chrome.control, 24, dp(4)));
        String label = live ? "Stop dictating" : "Dictate";
        record.setContentDescription(label);
        record.setTooltipText(label);
        transcript.setTextColor(drafts.text().isEmpty() ? chrome.onSurfaceVariant : chrome.onSurface);
        transcript.setText(transcriptText());
    }

    private String transcriptText() {
        if (!drafts.text().isEmpty()) return drafts.text();
        switch (dictationState) {
            case CONNECTING: return "Connecting…";
            case LISTENING: return "Listening…";
            case FINISHING: return "Finishing…";
            default: return "Tap the mic and speak";
        }
    }

    private void toggleDictation() {
        if (dictationState == DictationEngine.State.LISTENING
                || dictationState == DictationEngine.State.CONNECTING) {
            dictation.stop();
            return;
        }
        if (dictationState != DictationEngine.State.IDLE) return;
        if (!dictation.isAvailable()) {
            host.notice("Dictation is not set up on this phone");
            return;
        }
        // The engines refuse to start without RECORD_AUDIO; ask first, start on yes.
        host.withMicrophone(this::startDictation);
    }

    private void startDictation() {
        if (!micOnly || dictationState != DictationEngine.State.IDLE) return;
        drafts.dictationStarted();
        final String pane = drafts.pane();
        dictation.start(new DictationEngine.Listener() {
            @Override public void onPartial(String text) {
                if (pane.equals(drafts.pane())) showText(drafts.dictationPartial(text));
            }

            @Override public void onFinal(String text) {
                if (!pane.equals(drafts.pane())) return;
                showText(drafts.dictationFinal(text));
                if (sendWhenFinal) {
                    sendWhenFinal = false;
                    enter();
                }
            }

            @Override public void onState(DictationEngine.State state) {
                dictationState = state;
                updateMicPanel();
            }

            @Override public void onError(DictationException error) {
                dictationState = DictationEngine.State.IDLE;
                sendWhenFinal = false;
                if (pane.equals(drafts.pane())) showText(drafts.dictationEnded());
                host.notice(error.getMessage() == null ? "Dictation stopped" : error.getMessage());
            }
        });
    }

    /**
     * Enter: while listening, finish the utterance and send it; with a draft,
     * queue it as one paste plus Enter; otherwise Enter goes to the program.
     */
    private void enter() {
        if (dictationState == DictationEngine.State.LISTENING
                || dictationState == DictationEngine.State.CONNECTING) {
            sendWhenFinal = true;
            dictation.stop();
            return;
        }
        if (dictationState == DictationEngine.State.FINISHING) {
            sendWhenFinal = true;
            return;
        }
        boolean drafting = micOnly || agent;
        if (drafting && field.getVisibility() == View.VISIBLE) {
            // Commit the keyboard's underlined word into the draft before reading it.
            BaseInputConnection.removeComposingSpans(field.getText());
            InputMethodManager input = context.getSystemService(InputMethodManager.class);
            if (input != null && field.hasFocus()) input.restartInput(field);
            drafts.edit(field.getText().toString());
        }
        String outgoing = drafting ? drafts.outgoing() : null;
        if (outgoing == null) {
            host.enterKey();
            return;
        }
        String command;
        try {
            command = GpuiTerminalProtocol.composeCommand(outgoing);
        } catch (org.json.JSONException | IllegalArgumentException invalid) {
            host.notice("Message is too long to send in one piece");
            return;
        }
        // The draft stays until the input is queued, so a full queue loses nothing.
        if (!host.queue(command)) return;
        drafts.sent();
        showText("");
    }

    private void showText(String text) {
        if (!field.getText().toString().equals(text)) {
            updatingField = true;
            try {
                field.setText(text);
                field.setSelection(text.length());
            } finally {
                updatingField = false;
            }
        }
        updateMicPanel();
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
