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
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.inputmethod.BaseInputConnection;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import dev.herdr.android.dictation.DictationEngine;
import dev.herdr.android.dictation.DictationException;
import dev.herdr.android.dictation.PendingClip;
import java.util.Collections;
import java.util.List;

/**
 * Input for the pane on screen.
 *
 * <p>On an agent's pane there is no bottom bar: the terminal takes the whole
 * height, and a small chip floats over its bottom right ({@link FloatingCluster}:
 * keyboard, mic, Enter), clear of the caret and of dialogs. What is spoken or
 * typed is drawn as ghost text at the agent's own prompt caret by the renderer
 * ({@link GhostText}), and reaches the agent only on Enter, as one bracketed
 * paste plus Enter (through the outbox when the link is down). A typing box
 * exists only while the keyboard is up. The terminal keys open over the
 * terminal, above the prompt, on a swipe up or a long press on Enter.
 *
 * <p>A plain shell keeps a bar: its pinned controls, its keys and Enter, and
 * raw typing.
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
        void showQueue(List<Outbox.Message> messages);
        /**
         * Runs {@code granted} once the microphone may be used: at once when
         * RECORD_AUDIO is already granted, or after the user allows it.
         */
        void withMicrophone(Runnable granted);
        /** Has the renderer draw ghost text ({@link GhostText} JSON); "" clears it. */
        void setGhost(String json);
        /** Shows the previous (-1) or next (+1) pane of the tab strip. */
        void switchPane(int step);
        /** The terminal's surface: the renderer's layout hints are relative to it. */
        View surface();
        /** Shows the keyboard typing straight into the program (its menus, y/n). */
        void rawKeyboard();
    }

    private static final String PREFS = "terminal-display";
    /** Every pane's draft, so a closed or killed app finds what was typed. */
    private static final String DRAFTS = "composer_drafts";
    private static final long SAVE_DRAFTS_MS = 400;
    private static final int MAX_LINES = 6;
    /** Gap between the chip and the grid's right edge and the row under it, in dp. */
    private static final int INSET = 4;
    private static final java.util.Set<String> SHELLS = new java.util.HashSet<>(java.util.Arrays.asList(
            "shell", "sh", "bash", "zsh", "fish", "nu", "dash", "ksh", "tcsh", "csh"));

    private final Context context;
    private final SharedPreferences preferences;
    private final DictationEngine dictation;
    private final Host host;
    private final ComposerDrafts drafts = new ComposerDrafts();
    private final float density;

    /** The typing box: present only while the keyboard is up. */
    private final EditText field;
    /** What the activity floats at the terminal's bottom: the box with its mic and send. */
    private final PullDownRow stack;
    /** In the box: closes the keyboard (and the box); the draft stays as ghost text. */
    private final android.widget.TextView hide;
    private final ImageView send;
    private final MicButton mic;
    private final FloatingCluster cluster;
    /** The terminal keys, floated above the prompt while open. */
    private final KeysOverlay keysOverlay;
    /** Invisible touch targets over the controls the renderer draws in the prompt's frame. */
    private final TargetStrip targets;
    /** Recordings waiting for the desktop, counted after "talk". */
    private int waiting;
    /** When the microphone opened, for the clock in the controls; 0 while not recording. */
    private long recordingSince;
    /** In the keys' overlay: the keyboard typing straight into the program. */
    private final ImageView raw;
    /**
     * The draft, above the chip, when the renderer finds no prompt to draw it
     * at (a dialog, an unknown program): ghost text never covers the program.
     */
    private final android.widget.TextView fallback;
    /** Recording lengths of clips dictated this run, for their chips. */
    private final java.util.Map<String, Long> clipLengths = new java.util.HashMap<>();
    private GpuiChrome chrome;

    /** A shell's bar: pinned controls, keys, Enter. Gone on an agent's pane. */
    private LinearLayout bar;
    private FrameLayout slot;
    private View keyStrip;

    /** The pane's program kind: "claude", "codex", another agent's, or "" for a shell. */
    private String kind = "";
    private boolean agent;
    /** The typing box is open (the keyboard was asked for). */
    private boolean typing;
    /** The keyboard has shown since typing began, so its hiding ends typing. */
    private boolean imeShown;
    private boolean keysOpen;
    /** Something (the file page) lies over the terminal: nothing floats over it. */
    private boolean covered;
    private List<Outbox.Message> queuedMessages = Collections.emptyList();
    private ClusterPlacement.Hint layout;
    private ClusterPlacement.Spot spot;
    /** The ghost JSON last handed over, so an unchanged one is not sent again. */
    private String lastGhost = "";
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
        drafts.restore(preferences.getString(DRAFTS, ""));
        mic = new MicButton(context, chrome, v -> toggleDictation(), FloatingCluster.SIZE, host::notice);
        cluster = new FloatingCluster(context, chrome, mic.view(), new FloatingCluster.Actions() {
            @Override public void keyboard() { startTyping(); }
            @Override public void enter() { ComposerBar.this.enter(); }
            @Override public void keys() { setKeysOpen(!keysOpen); }
            @Override public void queue() { if (!queuedMessages.isEmpty()) host.showQueue(queuedMessages); }
            @Override public void pane(int step) { host.switchPane(step); }
        });
        cluster.view().setVisibility(View.GONE);
        cluster.view().addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            if (r - l != or - ol || b - t != ob - ot) v.post(this::place);
        });
        field = createField();
        send = new ImageView(context);
        send.setImageResource(R.drawable.ic_bar_enter);
        send.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        send.setPadding(dp(8), dp(8), dp(8), dp(8));
        send.setContentDescription("Send");
        send.setOnClickListener(v -> enter());
        stack = new PullDownRow(context);
        stack.setOrientation(LinearLayout.HORIZONTAL);
        // A swipe down on the box puts the keyboard away, as chat apps do.
        stack.setOnPull(this::hideKeyboard);
        hide = new android.widget.TextView(context);
        hide.setText("hide ▾");
        hide.setTextSize(13);
        hide.setTypeface(android.graphics.Typeface.MONOSPACE);
        hide.setGravity(Gravity.CENTER);
        hide.setMinWidth(dp(48));
        hide.setPadding(dp(4), 0, dp(6), 0);
        hide.setContentDescription("Hide the keyboard");
        hide.setOnClickListener(v -> hideKeyboard());
        stack.addView(hide, new LinearLayout.LayoutParams(-2, dp(48)));
        stack.setGravity(Gravity.BOTTOM);
        stack.setPadding(dp(6), dp(4), dp(6), dp(4));
        stack.addView(field, new LinearLayout.LayoutParams(0, -2, 1));
        LinearLayout.LayoutParams sendParams = new LinearLayout.LayoutParams(dp(40), dp(40));
        sendParams.setMarginStart(dp(4));
        sendParams.bottomMargin = dp(4);
        stack.addView(send, sendParams);
        stack.setVisibility(View.GONE);
        targets = new TargetStrip(context);
        targets.setVisibility(View.GONE);
        keysOverlay = new KeysOverlay(context);
        keysOverlay.setVisibility(View.GONE);
        raw = new ImageView(context);
        raw.setImageResource(R.drawable.ic_bar_keyboard);
        raw.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        raw.setPadding(dp(10), dp(12), dp(10), dp(12));
        raw.setContentDescription("Type straight into the program");
        raw.setTooltipText("Type straight into the program");
        raw.setOnClickListener(v -> host.rawKeyboard());
        keysOverlay.addView(raw, new FrameLayout.LayoutParams(dp(44), dp(48), Gravity.START));
        fallback = new android.widget.TextView(context);
        fallback.setTextSize(13);
        fallback.setTypeface(android.graphics.Typeface.create(android.graphics.Typeface.MONOSPACE,
                android.graphics.Typeface.ITALIC));
        fallback.setMaxLines(3);
        fallback.setEllipsize(android.text.TextUtils.TruncateAt.START);
        fallback.setPadding(dp(8), dp(4), dp(8), dp(4));
        fallback.setOnClickListener(v -> startTyping());
        fallback.setVisibility(View.GONE);
        style(chrome);
    }

    /** The typing box, floated at the bottom of the terminal's frame (over the keyboard). */
    View field() { return stack; }

    /** The floating chip; the activity adds it to the frame over the terminal. */
    View cluster() { return cluster.view(); }

    /** The terminal keys' overlay; the activity adds it to the frame over the terminal. */
    View keysOverlay() { return keysOverlay; }

    /** The controls' touch targets; the activity adds them to the frame over the terminal. */
    View targets() { return targets; }

    /** Whether {@code kind} (a pane's program, lowercase) gets the composer: any agent, not a shell. */
    static boolean isAgent(String kind) {
        return kind != null && !kind.isEmpty() && !SHELLS.contains(kind);
    }

    /** Recordings waiting for the desktop, counted on the mic; their lengths label their chips. */
    void setWaiting(int count) {
        for (PendingClip clip : Dictation.pending(context).waiting()) clipLengths.put(clip.id, clip.durationMs);
        mic.setWaiting(count);
        waiting = Math.max(0, count);
        DraftChips.apply(field.getText(), chip());
        lastGhost = "";
        pushGhost();
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

    /** Where the drawn screen is busy, from the renderer after a paint that changed it. */
    void layoutHint(String json) {
        ClusterPlacement.Hint hint = ClusterPlacement.Hint.parse(json);
        if (hint == null) return;
        layout = hint;
        place();
    }

    /** The outbox's messages for the pane on screen (from {@link Outbox#shown}). */
    void showQueued(List<Outbox.Message> messages) {
        queuedMessages = messages;
        cluster.setQueued(messages);
        pushGhost();
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
     * A shell's 48 dp bar: the toolbar's pinned controls, the keys and Enter.
     * Built for every pane; hidden on an agent's, whose terminal takes its row.
     */
    View bar(GpuiChrome chrome, View pinnedControls, View keys) {
        style(chrome);
        detach(keyStrip);
        keyStrip = keys;
        bar = new LinearLayout(context);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(chrome.surface);
        bar.addView(pinnedControls, new LinearLayout.LayoutParams(-2, dp(48)));
        slot = new FrameLayout(context);
        bar.addView(slot, new LinearLayout.LayoutParams(0, dp(48), 1));
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
        enter.setContentDescription("Enter");
        enter.setOnClickListener(v -> host.enterKey());
        LinearLayout.LayoutParams enterParams = new LinearLayout.LayoutParams(-2, dp(48));
        enterParams.setMargins(dp(2), 0, dp(6), 0);
        bar.addView(enter, enterParams);
        apply();
        return bar;
    }

    /**
     * The pane on screen changed, or what it runs did. {@code paneKind} is the
     * program's kind ("claude", "codex", another agent's), or "" for a shell.
     */
    void setPane(String paneId, String paneKind) {
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
        if (switched) {
            keysOpen = false;
            spot = null;
            showText(text);
        }
        String next = paneKind == null ? "" : paneKind;
        if (next.equals(kind) && !switched) return;
        kind = next;
        agent = isAgent(kind);
        if (!agent && typing) stopTyping();
        apply();
    }

    /**
     * A tap on the terminal. On an agent's pane it opens the typing box (true);
     * elsewhere the terminal's own keyboard is the caller's to show (false).
     */
    boolean terminalTapped() {
        if (!agent) return false;
        startTyping();
        return true;
    }

    /** The file page opened or closed over the terminal. */
    void setCovered(boolean coveredNow) {
        if (covered == coveredNow) return;
        covered = coveredNow;
        apply();
    }

    /** The keyboard showed or hid; hiding it closes the typing box, the text stays as ghost text. */
    void imeVisible(boolean visible) {
        if (visible) {
            if (typing) imeShown = true;
            return;
        }
        if (typing && imeShown) stopTyping();
    }

    /** Physical keys go to the box, not the terminal, while it is being typed in. */
    boolean ownsKeys() {
        return typing && field.hasFocus();
    }

    void destroy() {
        dictation.cancel();
        mic.destroy();
        persist();
        host.setGhost("");
    }

    /** Saves the drafts now (the activity is stopping: the process may be killed next). */
    void persist() {
        field.removeCallbacks(saveDrafts);
        saveDrafts.run();
    }

    private final Runnable saveDrafts = this::saveDrafts;

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (recordingSince == 0) return;
            pushGhost();
            field.postDelayed(this, 1000);
        }
    };

    private void saveDrafts() {
        preferences.edit().putString(DRAFTS, drafts.encode()).apply();
    }

    /** Typing saves at most a few times a second; apply() writes off the main thread. */
    private void saveDraftsSoon() {
        field.removeCallbacks(saveDrafts);
        field.postDelayed(saveDrafts, SAVE_DRAFTS_MS);
    }

    private void style(GpuiChrome next) {
        chrome = next;
        cluster.style(chrome);
        mic.style(chrome);
        field.setTextColor(chrome.onSurface);
        field.setHintTextColor(chrome.onSurfaceVariant);
        GradientDrawable box = new GradientDrawable();
        box.setColor(chrome.control);
        box.setStroke(Math.max(1, dp(1)), chrome.outline);
        box.setCornerRadius(dp(10));
        field.setBackground(new android.graphics.drawable.InsetDrawable(box, 0, dp(4), 0, dp(4)));
        field.setPadding(dp(10), dp(8), dp(10), dp(8));
        stack.setBackgroundColor(chrome.surface);
        send.setImageTintList(ColorStateList.valueOf(chrome.onSurface));
        GradientDrawable round = new GradientDrawable();
        round.setShape(GradientDrawable.OVAL);
        round.setColor(chrome.control);
        send.setBackground(round);
        keysOverlay.setBackgroundColor(chrome.surface);
        hide.setTextColor(chrome.onSurfaceVariant);
        GradientDrawable label = new GradientDrawable();
        label.setColor(chrome.surface);
        label.setStroke(Math.max(1, dp(1)), chrome.outline);
        label.setCornerRadius(dp(6));
        fallback.setBackground(label);
        fallback.setTextColor(chrome.onSurfaceVariant);
        raw.setImageTintList(ColorStateList.valueOf(chrome.primary));
        lastGhost = "";
        pushGhost();
    }

    private void startTyping() {
        if (!agent) return;
        if (!typing) {
            typing = true;
            imeShown = false;
            // The box shows the draft now; the ghost would only repeat it.
            showText(drafts.text());
            apply();
        }
        // After the box is laid out: a just-shown field cannot take focus or
        // the keyboard in the same frame, and SHOW_IMPLICIT may be ignored.
        field.post(() -> {
            if (!typing) return;
            field.requestFocus();
            field.setSelection(field.getText().length());
            InputMethodManager input = context.getSystemService(InputMethodManager.class);
            if (input != null) input.showSoftInput(field, 0);
            if (android.os.Build.VERSION.SDK_INT >= 30 && field.getWindowInsetsController() != null) {
                field.getWindowInsetsController().show(android.view.WindowInsets.Type.ime());
            }
        });
    }

    /**
     * Puts the keyboard and its box away; the draft stays as ghost text at the
     * prompt. False when they were not up (Back then does its usual thing).
     */
    boolean hideKeyboard() {
        if (!typing) return false;
        host.hideKeyboard();
        InputMethodManager input = context.getSystemService(InputMethodManager.class);
        if (input != null) input.hideSoftInputFromWindow(field.getWindowToken(), 0);
        stopTyping();
        return true;
    }

    /** Whether the typing box (and its keyboard) is up. */
    boolean typing() { return typing; }

    /** Our own Shift+Enter newline is being inserted: it is not a typed Enter. */
    private boolean insertingNewline;

    /**
     * An Enter key while the box is up, wherever focus is: HeliBoard sends one
     * (not an editor action) in apps it treats as terminals, and it must never
     * reach the terminal instead. Enter sends (one loop later, so text the
     * keyboard committed just before it lands first); Shift+Enter is a new
     * line. True when it was Enter (press and release are both taken).
     */
    boolean enterKey(KeyEvent event) {
        if (event == null || !typing || !EnterPaths.isEnter(event.getKeyCode())) return false;
        if (event.getAction() != KeyEvent.ACTION_DOWN) return true;
        if (event.isShiftPressed()) {
            Editable text = field.getText();
            int from = Math.min(field.getSelectionStart(), field.getSelectionEnd());
            int to = Math.max(field.getSelectionStart(), field.getSelectionEnd());
            insertingNewline = true;
            try {
                text.replace(Math.max(0, from), Math.max(0, to), "\n");
            } finally {
                insertingNewline = false;
            }
        } else if (EnterPaths.sendsOnKey(event.getKeyCode(), event.getAction(), false)) {
            field.post(this::enter);
        }
        return true;
    }

    /** The "type" control: opens the keyboard, or closes it while it is up. */
    private void toggleTyping() {
        if (typing) hideKeyboard();
        else startTyping();
    }

    private void stopTyping() {
        if (!typing) return;
        // Commit the keyboard's underlined word into the draft before closing.
        BaseInputConnection.removeComposingSpans(field.getText());
        drafts.edit(field.getText().toString());
        typing = false;
        imeShown = false;
        if (field.hasFocus()) field.clearFocus();
        apply();
    }

    private void setKeysOpen(boolean open) {
        keysOpen = open && agent;
        apply();
    }

    /** Puts every part where the pane and mode want it; only a shell's bar takes terminal rows. */
    private void apply() {
        if (bar != null) bar.setVisibility(agent ? View.GONE : View.VISIBLE);
        if (keyStrip != null && slot != null) {
            detach(keyStrip);
            if (agent) {
                if (keysOpen) {
                    FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(-1, dp(48));
                    params.setMarginStart(dp(44));
                    keysOverlay.addView(keyStrip, params);
                }
            } else {
                // A shell's keys are its main input: they stay in its bar.
                slot.addView(keyStrip, new FrameLayout.LayoutParams(-1, dp(48)));
            }
        }
        keysOverlay.setVisibility(agent && keysOpen && !covered ? View.VISIBLE : View.GONE);
        boolean dictates = agent && dictation.isAvailable();
        mic.view().setVisibility(dictates ? View.VISIBLE : View.GONE);
        if (typing) {
            if (mic.view().getParent() != stack) {
                detach(mic.view());
                LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-2, -2);
                params.setMarginStart(dp(4));
                params.bottomMargin = dp(6);
                stack.addView(mic.view(), 1, params);
            }
        } else {
            cluster.takeMic(mic.view());
        }
        stack.setVisibility(agent && typing ? View.VISIBLE : View.GONE);
        if (!agent || typing || covered) {
            cluster.view().setVisibility(View.GONE);
            targets.setVisibility(View.GONE);
        }
        place();
        pushGhost();
    }

    /**
     * Puts the touch targets on the words the renderer drew in the program's
     * frame ("type ─ talk ─ send"), from its last layout hint. When it had no
     * room for them (a full screen), the floating chip stands in, on the lowest
     * free cells. The keys float just above the prompt (or a dialog).
     */
    private void place() {
        View surface = host.surface();
        if (layout == null || surface == null || !(targets.getParent() instanceof ViewGroup)) return;
        ViewGroup frame = (ViewGroup) targets.getParent();
        int[] at = new int[2];
        int[] origin = new int[2];
        surface.getLocationInWindow(at);
        frame.getLocationInWindow(origin);
        float left = at[0] - origin[0], top = at[1] - origin[1];
        // While typing the box lies over the prompt; where it does not, the
        // rule's "hide" still works.
        boolean showing = agent && !covered;
        boolean drawn = !layout.controls.isEmpty();
        placeTargets(showing && drawn, left, top, surface.getHeight() / density);
        View chip = cluster.view();
        chip.setVisibility(showing && !drawn ? View.VISIBLE : View.GONE);
        FrameLayout.LayoutParams anchorAt = null;
        int anchorWidth = 0;
        if (chip.getVisibility() == View.VISIBLE) {
            chip.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
            float width = (chip.getMeasuredWidth() + dp(INSET)) / density;
            float height = (chip.getMeasuredHeight() + dp(INSET)) / density;
            ClusterPlacement.Spot next = ClusterPlacement.place(layout, width, height, spot);
            FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.START);
            params.leftMargin = Math.round(left + next.right * density - chip.getMeasuredWidth() - dp(INSET));
            params.topMargin = Math.round(top + next.bottom * density - chip.getMeasuredHeight() - dp(INSET) / 2f);
            FrameLayout.LayoutParams old = (FrameLayout.LayoutParams) chip.getLayoutParams();
            if (old == null || old.leftMargin != params.leftMargin || old.topMargin != params.topMargin
                    || old.gravity != params.gravity) {
                chip.setLayoutParams(params);
            }
            cluster.setCrowded(next.crowded);
            spot = next;
            anchorAt = params;
            anchorWidth = chip.getMeasuredWidth();
        } else if (drawn) {
            FrameLayout.LayoutParams t = (FrameLayout.LayoutParams) targets.getLayoutParams();
            anchorAt = t;
            anchorWidth = t.width;
        }
        placeFallback(frame, showing ? anchorAt : null, anchorWidth);
        if (keysOverlay.getVisibility() == View.VISIBLE) {
            float guardTop = top + (layout.top + layout.guard * layout.cellHeight) * density;
            FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(-1, dp(48), Gravity.TOP | Gravity.START);
            params.topMargin = Math.max(Math.round(top), Math.round(guardTop) - dp(48));
            keysOverlay.setLayoutParams(params);
        }
    }

    /** Lays the invisible targets over the drawn words ({@link ControlTargets}). */
    private void placeTargets(boolean show, float left, float top, float surfaceBottom) {
        if (!show) {
            targets.setVisibility(View.GONE);
            return;
        }
        float right = layout.left + layout.cols * layout.cellWidth;
        java.util.List<ControlTargets.Rect> rects = ControlTargets.layout(layout.controls, layout.left,
                layout.top, layout.cellWidth, layout.cellHeight, layout.targetsTop, surfaceBottom, right);
        if (rects.isEmpty()) {
            targets.setVisibility(View.GONE);
            return;
        }
        float l = Float.MAX_VALUE, t = Float.MAX_VALUE, rr = 0, b = 0;
        for (ControlTargets.Rect rect : rects) {
            l = Math.min(l, rect.left);
            t = Math.min(t, rect.top);
            rr = Math.max(rr, rect.right);
            b = Math.max(b, rect.bottom);
        }
        targets.removeAllViews();
        for (ControlTargets.Rect rect : rects) {
            View target = new View(context);
            target.setTag(rect.id);
            target.setContentDescription(describe(rect.id));
            target.setOnClickListener(this::targetTapped);
            if ("send".equals(rect.id)) {
                target.setOnLongClickListener(v -> { setKeysOpen(!keysOpen); return true; });
                target.setOnTouchListener((v, e) -> {
                    if (e.getActionMasked() == MotionEvent.ACTION_DOWN) sendTouchX = e.getX() / density + rect.left;
                    return false;
                });
            }
            FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                    Math.round(rect.width() * density), Math.round(rect.height() * density), Gravity.TOP | Gravity.START);
            params.leftMargin = Math.round((rect.left - l) * density);
            params.topMargin = Math.round((rect.top - t) * density);
            targets.addView(target, params);
        }
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                Math.round((rr - l) * density), Math.round((b - t) * density), Gravity.TOP | Gravity.START);
        params.leftMargin = Math.round(left + l * density);
        params.topMargin = Math.round(top + t * density);
        targets.setLayoutParams(params);
        targets.setVisibility(View.VISIBLE);
    }

    /** Where the last touch on "send" went down (dp, surface), to tell its count apart. */
    private float sendTouchX = -1;

    private void targetTapped(View target) {
        String id = String.valueOf(target.getTag());
        switch (id) {
            case "type": toggleTyping(); break;
            case "talk": toggleDictation(); break;
            case "send": {
                // The count after "send" lists the queue; the word itself sends.
                ControlTargets.Cells cells = null;
                for (ControlTargets.Cells c : layout.controls) if (c.id.equals("send")) cells = c;
                float countFrom = cells == null ? Float.MAX_VALUE
                        : layout.left + (cells.end - sendCount) * layout.cellWidth - layout.cellWidth / 2;
                if (sendCount > 0 && sendTouchX >= countFrom) host.showQueue(queuedMessages);
                else enter();
                break;
            }
            default:
        }
    }

    private static String describe(String id) {
        switch (id) {
            case "type": return "Type a message, or hide the keyboard";
            case "talk": return "Dictate; tap again to stop";
            case "send": return "Send, or press Enter; hold for the terminal keys";
            default: return id;
        }
    }

    /** The draft beside the controls when the renderer could not draw it (no prompt, no room). */
    private void placeFallback(ViewGroup frame, FrameLayout.LayoutParams nearAt, int nearWidth) {
        String draft = drafts.text();
        boolean show = nearAt != null && layout != null && !layout.draftShown && !typing
                && !draft.trim().isEmpty();
        if (!show) {
            fallback.setVisibility(View.GONE);
            return;
        }
        if (fallback.getParent() != frame) {
            detach(fallback);
            frame.addView(fallback);
        }
        android.text.SpannableString text = new android.text.SpannableString(draft.replace('\n', ' '));
        DraftChips.apply(text, chip());
        fallback.setText(text);
        fallback.setVisibility(View.VISIBLE);
        int width = Math.max(dp(80), frame.getWidth() - dp(16));
        fallback.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.AT_MOST),
                View.MeasureSpec.UNSPECIFIED);
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.START);
        params.width = fallback.getMeasuredWidth();
        int right = nearAt.leftMargin + nearWidth;
        params.leftMargin = Math.max(dp(8), right - fallback.getMeasuredWidth());
        params.topMargin = Math.max(0, nearAt.topMargin - fallback.getMeasuredHeight() - dp(4));
        fallback.setLayoutParams(params);
    }

    /** Characters of the queued count drawn after "send" ("·2"), 0 when none. */
    private int sendCount;

    /** The controls drawn in the program's frame, for the current state. */
    private java.util.List<GhostText.Control> controls() {
        String count = queuedMessages.isEmpty() ? "" : "·" + QueuedMessages.badge(queuedMessages.size());
        sendCount = count.length();
        return controls(typing, dictation.isAvailable() ? talk() : null, queuedMessages);
    }

    /**
     * "type ─ talk ─ send": "type" reads "hide" while the keyboard is up (it
     * toggles it), "talk" is left out with no dictation, and "send" carries the
     * queued count in the most pressing message's colour.
     */
    static java.util.List<GhostText.Control> controls(boolean typing, GhostText.Control talk,
            List<Outbox.Message> queued) {
        java.util.List<GhostText.Control> out = new java.util.ArrayList<>();
        out.add(new GhostText.Control("type", typing ? "hide" : "type", "dim"));
        if (talk != null) out.add(talk);
        String count = queued.isEmpty() ? "" : "·" + QueuedMessages.badge(queued.size());
        String countStyle;
        switch (QueuedMessages.urgency(queued)) {
            case GONE: countStyle = "danger"; break;
            case WAITING: countStyle = "warn"; break;
            default: countStyle = "info";
        }
        out.add(new GhostText.Control("send", "send", "bright", count, countStyle));
        return out;
    }

    /** "talk", or what the dictation is doing, as TUI-style words. */
    private GhostText.Control talk() {
        long elapsed = recordingSince == 0 ? 0 : android.os.SystemClock.elapsedRealtime() - recordingSince;
        String clock = MicButton.clock(elapsed);
        switch (dictationState) {
            case CONNECTING: return new GhostText.Control("talk", "connecting…", "info");
            case LISTENING: return new GhostText.Control("talk", "● rec " + clock + " · tap to stop", "danger");
            case TRANSCRIBING: return new GhostText.Control("talk", "transcribing…", "info");
            case OFFLINE_RECORDING:
                return new GhostText.Control("talk", "desktop offline · rec " + clock + " · tap to stop", "warn");
            default:
                return new GhostText.Control("talk", "talk", "dim", waiting > 0 ? "·" + waiting : "", "warn");
        }
    }

    /** Hands the renderer the pane's draft, queued messages and controls, unless unchanged. */
    private void pushGhost() {
        String json = "";
        if (agent) {
            json = GhostText.json(GhostText.kind(kind), typing ? "" : drafts.text(),
                    typing ? -1 : drafts.partialFrom(), clipLengths, queuedMessages, drafts.pane(),
                    controls(), GhostText.Colors.of(chrome));
        }
        if (json.equals(lastGhost)) return;
        lastGhost = json;
        host.setGhost(json);
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
        // Every way a keyboard says "send" ends in enter(), the app's own send
        // (EnterPaths): its action key, an Enter key event, a typed newline.
        edit.setOnEditorActionListener((view, action, event) -> {
            if (event != null && EnterPaths.isEnter(event.getKeyCode())) {
                return enterKey(event);
            }
            if (!EnterPaths.sendsOnAction(action)) return false;
            enter();
            return true;
        });
        edit.setOnKeyListener((view, code, event) -> enterKey(event));
        edit.addTextChangedListener(new TextWatcher() {
            private int changeStart, changeBefore, changeCount;

            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                changeStart = start;
                changeBefore = before;
                changeCount = count;
            }
            @Override public void afterTextChanged(Editable s) {
                if (updatingField) return;
                // A keyboard that types "\n" for Enter: send, as the button does.
                String withoutNewline = insertingNewline ? null
                        : EnterPaths.withoutTypedNewline(s, changeStart, changeBefore, changeCount);
                if (withoutNewline != null) {
                    showText(withoutNewline);
                    drafts.edit(withoutNewline);
                    edit.post(ComposerBar.this::enter);
                    return;
                }
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
        return edit;
    }

    /** A session runs: IDLE and PENDING both mean none does. */
    private boolean dictating() {
        return dictationState != DictationEngine.State.IDLE && dictationState != DictationEngine.State.PENDING;
    }

    private void setDictationState(DictationEngine.State state) {
        boolean wasRecording = MicButton.recording(dictationState);
        dictationState = state;
        mic.setState(state);
        boolean recording = MicButton.recording(state);
        if (recording && !wasRecording) recordingSince = android.os.SystemClock.elapsedRealtime();
        if (!recording) recordingSince = 0;
        // The clock in the controls ticks while the microphone is open.
        field.removeCallbacks(tick);
        if (recording) field.postDelayed(tick, 1000);
        pushGhost();
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
        if (typing) drafts.edit(field.getText().toString());
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
        if (typing) {
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

    /** The draft changed under us (dictation, a pane switch): the box and the ghost follow. */
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
        pushGhost();
        if (layout != null && !layout.anchored) place();
    }

    /** How a waiting dictation's token reads in the box. */
    private DraftChips.Style chip() {
        return new DraftChips.Style(chrome.warning, density, clipLengths,
                context.getDrawable(R.drawable.ic_bar_schedule));
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

    /**
     * The controls' targets: a sideways swipe goes to the previous or next
     * pane, a swipe up opens the terminal keys; taps reach the targets.
     */
    private final class TargetStrip extends FrameLayout {
        private final int slop = ViewConfiguration.get(context).getScaledTouchSlop();
        private float downX, downY;

        TargetStrip(Context context) { super(context); }

        @Override public boolean onInterceptTouchEvent(MotionEvent event) {
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                downX = event.getX();
                downY = event.getY();
                return false;
            }
            if (event.getActionMasked() == MotionEvent.ACTION_MOVE) {
                float dx = event.getX() - downX, dy = event.getY() - downY;
                return Math.abs(dx) > slop * 2 || -dy > slop * 2;
            }
            return false;
        }

        @Override public boolean onTouchEvent(MotionEvent event) {
            if (event.getActionMasked() != MotionEvent.ACTION_UP) return true;
            float dx = event.getX() - downX, dy = event.getY() - downY;
            if (Math.abs(dx) > dp(28) && Math.abs(dx) > Math.abs(dy)) host.switchPane(dx < 0 ? 1 : -1);
            else if (-dy > dp(20) && -dy > Math.abs(dx)) setKeysOpen(!keysOpen);
            return true;
        }
    }

    /** The keys' overlay: a swipe down on it closes it; sideways drags scroll the keys. */
    private final class KeysOverlay extends FrameLayout {
        private final int slop = ViewConfiguration.get(context).getScaledTouchSlop();
        private float downX, downY;

        KeysOverlay(Context context) { super(context); }

        @Override public boolean onInterceptTouchEvent(MotionEvent event) {
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                downX = event.getX();
                downY = event.getY();
                return false;
            }
            if (event.getActionMasked() == MotionEvent.ACTION_MOVE) {
                float dx = event.getX() - downX, dy = event.getY() - downY;
                return dy > slop * 2 && dy > Math.abs(dx);
            }
            return false;
        }

        @Override public boolean onTouchEvent(MotionEvent event) {
            if (event.getActionMasked() == MotionEvent.ACTION_UP && event.getY() - downY > dp(20)) {
                setKeysOpen(false);
            }
            return true;
        }
    }
}
