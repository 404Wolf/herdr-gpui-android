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
import dev.herdr.android.dictation.PendingClip;
import java.util.Collections;
import java.util.List;

/**
 * Input for the pane on screen.
 *
 * <p>On an agent's pane there is no bottom bar: the terminal takes the whole
 * height, and the renderer draws the controls as words in the agent's own
 * prompt box ("type ─ talk ─ send"), with invisible touch targets over them.
 * What is spoken or typed is drawn as ghost text at the agent's own prompt
 * caret by the renderer ({@link GhostText}), and reaches the agent only on
 * Enter, as one bracketed paste plus Enter (through the outbox when the link
 * is down). There is no typing box: while the keyboard is up it types into an
 * invisible field only it sees ({@link #field}), and the typed words are drawn
 * at the prompt like dictated ones, the caret at their end. The terminal keys
 * open over the terminal, above the prompt, on a swipe up or a long press on
 * "send".
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
        /** Scrolls the pane back to its live bottom, if it is scrolled into history. */
        default void toLive() { }
        /** A short status for the user; the connection stays as it is. */
        void notice(String message);
        /**
         * The "+N more" row was tapped: list every row of the pane's queue (messages, waiting and
         * parked recordings), each with its actions ({@link #queueCommand}).
         */
        void showQueue(List<QueueView.Row> rows, List<Outbox.Message> messages);
        /** The user took one queued message back into the draft (edit); its text, or null. */
        String takeQueued(String id);
        /** "send again" on a message that may have been sent. */
        void resendQueued(String id);
        /** What the composer's clear takes: the pane's queued messages but one being sent. */
        List<String> clearableQueued();
        /** A queued message's text, cleared or not; null when there is none. */
        String queuedText(String id);
        /** Marks messages cleared until {@code until} (epoch ms), saved; false when that failed (none). */
        boolean clearQueued(List<String> ids, long until);
        /** The clear's undo: those messages back in line at once; true when that is saved too. */
        boolean unclearQueued(List<String> ids);
        /** The undo window closed: the cleared messages go for good. */
        void purgeCleared(List<String> ids);
        /** Whether the outbox has been read from disk. */
        boolean outboxLoaded();
        /** The host's messages cleared and still in their undo window. */
        List<Outbox.Cleared> clearedQueued(long now);
        /** The texts of every queued message not cleared (any pane). */
        List<String> liveQueuedTexts();
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
        /** Wolf typed into the draft: he is using the phone (item 71). */
        default void typed() { }
    }

    private static final String PREFS = "terminal-display";
    /** Every pane's draft, so a closed or killed app finds what was typed. */
    private static final String DRAFTS = "composer_drafts";
    private static final long SAVE_DRAFTS_MS = 400;
    private static final int MAX_LINES = 6;
    private static final java.util.Set<String> SHELLS = new java.util.HashSet<>(java.util.Arrays.asList(
            "shell", "sh", "bash", "zsh", "fish", "nu", "dash", "ksh", "tcsh", "csh"));

    private final Context context;
    private final SharedPreferences preferences;
    private final DictationEngine dictation;
    private final Host host;
    private final ComposerDrafts drafts = new ComposerDrafts();
    private final float density;

    /**
     * What the keyboard types into while it is up: invisible and one pixel
     * high, never seen or touched. Its text is the draft, drawn as ghost text.
     */
    private final EditText field;
    /** The terminal keys, floated above the prompt while open. */
    private final KeysOverlay keysOverlay;
    /** Invisible touch targets over the controls the renderer draws in the prompt's frame. */
    private final TargetStrip targets;
    /** Everything waiting to reach the pane, drawn as rows above the prompt (item 75). */
    private final QueueView queue = new QueueView();
    /** The queue's rows are open (tap "N queued"), and the row whose actions show. */
    private boolean queueOpen;
    private String selectedRow;
    /** The rows as last drawn, oldest first: the renderer reports them as "echo-<row id>". */
    private List<QueueView.Row> shownRows = Collections.emptyList();
    /** Input typed while the link is down, waiting for it. */
    private int linkDown;
    /** Taps on controls that just moved are ignored ({@link TapGuard}). */
    private final TapGuard tapGuard = new TapGuard();
    /** The clear (✕) and its undo (item 76). */
    private final ClearUndo clearing = new ClearUndo(new ClearUndo.Effects() {
        @Override public boolean cancelDictation() { return cancelDictationForClear(); }

        @Override public String draftText() { return drafts.text(); }

        @Override public void setDraft(String text) {
            drafts.edit(text);
            if (text.isEmpty()) transcribedOnPhone = false;
            showText(text);
        }

        @Override public void restoreDraft(String text) {
            String now = drafts.text();
            String joined = now.trim().isEmpty() ? text : text.replaceAll("\\s+$", "") + " " + now;
            drafts.edit(joined);
            showText(joined);
        }

        @Override public List<String> clearable() { return host.clearableQueued(); }

        @Override public String messageText(String id) { return host.queuedText(id); }

        @Override public List<String> looseClips() {
            List<String> ids = new java.util.ArrayList<>();
            for (PendingClip clip : ComposerBar.this.looseClips()) ids.add(clip.id);
            // Parked recordings the pane shows on their own are part of its queue too.
            java.util.Set<String> placed = new java.util.HashSet<>(DictationToken.ids(drafts.text()));
            for (Outbox.Message m : queuedMessages) placed.addAll(DictationToken.ids(m.text));
            for (PendingClip clip : parkedHere()) if (!placed.contains(clip.id)) ids.add(clip.id);
            return ids;
        }

        @Override public boolean holdClips(List<String> ids) { return Dictation.pending(context).hold(ids); }

        @Override public boolean releaseClips(List<String> ids) { return Dictation.pending(context).release(ids); }

        @Override public boolean clearMessages(List<String> ids, long until) {
            if (!host.clearQueued(ids, until)) return false;
            for (String id : ids) queue.forget(id);
            return true;
        }

        @Override public boolean unclearMessages(List<String> ids) { return host.unclearQueued(ids); }

        @Override public void purgeMessages(List<String> ids) { host.purgeCleared(ids); }

        @Override public boolean outboxLoaded() { return host.outboxLoaded(); }

        @Override public List<Outbox.Cleared> clearedMessages(long now) { return host.clearedQueued(now); }

        @Override public List<String> liveTexts() {
            List<String> out = new java.util.ArrayList<>(drafts.all());
            out.addAll(host.liveQueuedTexts());
            return out;
        }

        @Override public void retryLater() {
            if (field == null) return;
            field.removeCallbacks(reconcileClear);
            field.postDelayed(reconcileClear, RETRY_CLEAR_MS);
        }

        @Override public boolean persist(String record) {
            // Also from the constructor (a clear resumed after a restart), before the field exists.
            if (field != null) field.removeCallbacks(saveDrafts);
            return preferences.edit().putString(DRAFTS, drafts.encode()).putString(CLEAR_RECORD, record).commit();
        }

        @Override public void failed(String why) { host.notice(why); }
    });
    /** The clear's undo record (see {@link ClearUndo#encode}), so an undo outlives a restart. */
    private static final String CLEAR_RECORD = "composer_clear";
    /** After a write a clear or an undo needs failed: try again this much later. */
    private static final long RETRY_CLEAR_MS = 2000;
    private final Runnable reconcileClear = new Runnable() {
        @Override public void run() {
            clearing.reconcile(wall());
            pushGhost();
            scheduleQueueRefresh();
        }
    };

    /** Wall-clock time: a clear's undo window outlives the process. */
    private static long wall() {
        return System.currentTimeMillis();
    }

    /** Redraws when an undo window closes or a "sent" row fades. */
    private final Runnable refreshQueue = new Runnable() {
        @Override public void run() {
            clearing.reconcile(wall());
            pushGhost();
            scheduleQueueRefresh();
        }
    };
    /** When the microphone opened, for the clock in the controls; 0 while not recording. */
    private long recordingSince;
    /** In the keys' overlay: the keyboard typing straight into the program. */
    private final ImageView raw;
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
    /** The keyboard was asked for: it types into {@link #field}. */
    private boolean typing;
    /** The keyboard has shown since typing began, so its hiding ends typing. */
    private boolean imeShown;
    private boolean keysOpen;
    /** Something (the file page) lies over the terminal: nothing floats over it. */
    private boolean covered;
    private List<Outbox.Message> queuedMessages = Collections.emptyList();
    private ClusterPlacement.Hint layout;
    /** The ghost JSON last handed over, so an unchanged one is not sent again. */
    private String lastGhost = "";
    private DictationEngine.State dictationState = DictationEngine.State.IDLE;
    /**
     * The last dictation was transcribed on the phone (the desktop was away or slow): "talk"
     * reads "talk offline" until the next dictation, a send or a pane switch.
     */
    private boolean transcribedOnPhone;
    /**
     * Why the last dictation heard nothing (a silenced mic, or the desktop's code), said after
     * "talk" until the next dictation, a send or a pane switch; null for nothing to say.
     */
    private dev.herdr.android.dictation.DictationException.Reason nothingHeard;
    /** This session's desktop answered with an error: "desktop error" rather than "offline". */
    private boolean desktopError;
    /**
     * Each session's text, and an Enter pressed while it finishes: kept with the pane it was
     * pressed in and settled at that session's end, never left for a later dictation.
     */
    private final DictationRouting routing = new DictationRouting(drafts, new DictationRouting.Bar() {
        @Override public void showText(String text) {
            ComposerBar.this.showText(text);
        }

        @Override public void setDictationState(DictationEngine.State state) {
            ComposerBar.this.setDictationState(state);
        }

        @Override public void enter() {
            ComposerBar.this.enter();
        }

        @Override public void notice(String text) {
            host.notice(text);
        }

        @Override public void appendDictation(String pane, String text) {
            appendDictation(pane, text, null);
        }

        @Override public void appendDictation(String pane, String text, int[] unverifiedCuts) {
            // A live final for another pane: it stays in memory either way; persistNow follows.
            if (pane == null || pane.isEmpty()) pane = drafts.pane();
            String draft = drafts.append(pane, text, unverifiedCuts);
            if (pane.equals(drafts.pane())) showText(draft);
        }

        @Override public void clipLength(String clipId, long durationMs) {
            clipLengths.put(clipId, durationMs);
        }

        @Override public void transcribedOnPhone() {
            transcribedOnPhone = true;
        }

        @Override public void nothingHeard(dev.herdr.android.dictation.DictationException.Reason reason) {
            ComposerBar.this.nothingHeard(reason);
        }

        @Override public void desktopError() {
            desktopError = true;
            pushGhost();
        }

        @Override public void finishing() {
            finishing = true;
            pushGhost();
        }

        @Override public boolean persistNow() {
            return ComposerBar.this.persistNow();
        }

        @Override public void abandonedClip(PendingClip clip) {
            // A cleared session's recording was saved anyway: held, never placed (item 74).
            Dictation.pending(context).hold(java.util.Collections.singletonList(clip.id));
        }
    });
    /** The desktop said its final waits for a decode ("finishing…"), until the session ends. */
    private boolean finishing;
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
        // A clear made before a restart: its undo is still on offer within its window.
        clearing.resume(preferences.getString(CLEAR_RECORD, ""), wall());
        field = createField();
        field.setVisibility(View.GONE);
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
        style(chrome);
    }

    /**
     * The keyboard's invisible field; the activity adds it to the frame over
     * the terminal, one pixel high ({@link #FIELD_HEIGHT_PX}) at its bottom.
     */
    View field() { return field; }

    /** The field's height: one pixel row, so it never takes a touch meant for the terminal. */
    static final int FIELD_HEIGHT_PX = 1;

    /** The terminal keys' overlay; the activity adds it to the frame over the terminal. */
    View keysOverlay() { return keysOverlay; }

    /** The controls' touch targets; the activity adds them to the frame over the terminal. */
    View targets() { return targets; }

    /** Whether {@code kind} (a pane's program, lowercase) gets the composer: any agent, not a shell. */
    static boolean isAgent(String kind) {
        return kind != null && !kind.isEmpty() && !SHELLS.contains(kind);
    }

    /** Recordings waiting for the desktop changed: their lengths label their chips and rows. */
    void setWaiting(int count) {
        for (PendingClip clip : Dictation.pending(context).waiting()) clipLengths.put(clip.id, clip.durationMs);
        DraftChips.apply(field.getText(), chip());
        lastGhost = "";
        pushGhost();
    }

    /**
     * A dictation, live or a recording sent later, held nothing to transcribe ({@code reason}):
     * "talk" says why, in place, until the next dictation.
     */
    void nothingHeard(dev.herdr.android.dictation.DictationException.Reason reason) {
        nothingHeard = reason;
        lastGhost = "";
        pushGhost();
    }

    /** How "talk" says why nothing was heard, in as many words as fit. */
    static String nothingHeardWords(dev.herdr.android.dictation.DictationException.Reason reason, boolean full) {
        switch (reason) {
            case SILENCED: return full ? "mic silenced" : "silenced";
            case SILENT_AUDIO: return full ? "mic sent silence" : "silence";
            case QUIET_AUDIO: return full ? "too quiet" : "quiet";
            case NO_AUDIO: return "no audio";
            default: return full ? "nothing heard" : "nothing";
        }
    }

    /**
     * A recording made offline came back as text: it replaces its chip in
     * whichever pane's draft holds it, saved at once. 1 when placed and saved,
     * 0 when no draft holds it, -1 when it could not be saved (left as it was).
     */
    int placeDictation(String clipId, String text) {
        Object before = drafts.snapshot();
        String pane = drafts.placeDictation(clipId, text);
        if (pane == null) return 0;
        if (!persistNow()) {
            // Not saved: as it was, and the recording stays to be offered again.
            drafts.rollback(before);
            showText(drafts.text());
            return -1;
        }
        clipLengths.remove(clipId);
        if (pane.equals(drafts.pane())) showText(drafts.text());
        return 1;
    }

    /**
     * A dictation whose chip is gone from everywhere: it joins the end of its pane's draft (a
     * recovered recording, whose pane is not known, joins the pane on screen).
     */
    boolean appendDictation(String pane, String text) {
        if (pane == null || pane.isEmpty()) pane = drafts.pane();
        Object before = drafts.snapshot();
        String draft = drafts.append(pane, text);
        if (!persistNow()) {
            drafts.rollback(before);
            showText(drafts.text());
            return false;
        }
        if (pane.equals(drafts.pane())) showText(draft);
        return true;
    }

    /**
     * Saves the drafts to disk before returning: a dictation's text is stored before its
     * recording is deleted (the courier's {@code delivered}, the live engine's discard).
     */
    boolean persistNow() {
        field.removeCallbacks(saveDrafts);
        return preferences.edit().putString(DRAFTS, drafts.encode()).commit();
    }

    /** Where the drawn screen is busy, from the renderer after a paint that changed it. */
    void layoutHint(String json) {
        ClusterPlacement.Hint hint = ClusterPlacement.Hint.parse(json);
        if (hint == null) return;
        layout = hint;
        place();
        // The grid's width picks the controls' words (a fold or unfold changes it).
        pushGhost();
    }

    /** The outbox's messages for the pane on screen (from {@link Outbox#shown}). */
    void showQueued(List<Outbox.Message> messages) {
        queuedMessages = messages;
        queue.update(drafts.pane(), messages, android.os.SystemClock.uptimeMillis());
        // The outbox changed (or was just read from disk): an interrupted clear or undo is put right.
        clearing.reconcile(wall());
        if (selectedRow != null) {
            boolean still = false;
            for (Outbox.Message m : messages) if (m.id.equals(selectedRow)) still = m.state != Outbox.State.SENDING;
            for (PendingClip clip : looseClips()) if (clip.id.equals(selectedRow)) still = true;
            for (PendingClip clip : parkedHere()) if (clip.id.equals(selectedRow)) still = true;
            if (!still) selectedRow = null;
        }
        scheduleQueueRefresh();
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
        String previous = drafts.pane();
        boolean switched = !previous.equals(paneId == null ? "" : paneId);
        if (switched && keepsAcrossPaneSwitch(dictationState)) {
            // Keep the recording and its text: offline it finds its pane's draft later, by
            // target; the phone's (or a late desktop) final joins its own pane's draft. An Enter
            // pressed in the old pane is settled with it (sent only if that pane is back).
            if (recording(dictationState)) dictation.stop();
            drafts.dictationEnded();
        } else if (switched && dictating()) {
            // An utterance belongs to the pane it was spoken for.
            dictation.cancel();
            setDictationState(DictationEngine.State.IDLE);
            routing.forget();
            drafts.dictationEnded();
        }
        String text = drafts.switchTo(paneId);
        if (switched) {
            // An undo belongs to the pane it cleared; the rows to the pane on screen. The first
            // pane after a start is no switch: a clear resumed from before it stays on offer.
            if (clearing.endsOnPaneChange(previous, drafts.pane())) clearing.commit();
            queueOpen = false;
            selectedRow = null;
            transcribedOnPhone = false;
            nothingHeard = null;
            keysOpen = false;
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
     * A tap on the terminal, once the renderer had it. A tap the program took
     * as a mouse click opens nothing (true). On an agent's pane any other tap
     * opens the keyboard into the draft (true); elsewhere the terminal's own
     * keyboard is the caller's to show (false). See {@link TerminalTap}.
     */
    boolean terminalTapped(boolean clickedProgram) {
        return terminalTapped(clickedProgram, Float.NaN, Float.NaN);
    }

    /**
     * {@link #terminalTapped(boolean)} where the finger lifted, in the
     * terminal surface's pixels (NaN when not known): a tap on the draft's
     * words puts the cursor there, as in a text field.
     */
    boolean terminalTapped(boolean clickedProgram, float x, float y) {
        switch (TerminalTap.after(
                clickedProgram ? TerminalTap.Outcome.CLICKED : TerminalTap.Outcome.PLAIN, agent)) {
            case NOTHING:
                return true;
            case TYPE_INTO_DRAFT:
                startTyping(cursorAt(x, y));
                return true;
            default:
                return false;
        }
    }

    /** The file page opened or closed over the terminal. */
    void setCovered(boolean coveredNow) {
        if (covered == coveredNow) return;
        covered = coveredNow;
        apply();
    }

    /** The keyboard showed or hid; hiding it ends typing, the text stays as ghost text. */
    void imeVisible(boolean visible) {
        if (visible) {
            if (typing) imeShown = true;
            return;
        }
        if (typing && imeShown) stopTyping();
    }

    /** Physical keys go to the draft, not the terminal, while typing. */
    boolean ownsKeys() {
        return typing && field.hasFocus();
    }

    void destroy() {
        // The engine is the process's: only this window's own session is cancelled.
        if (Dictation.owns(this)) {
            dictation.cancel();
            Dictation.release(this);
        }
        routing.forget();
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
        keysOverlay.setBackgroundColor(chrome.surface);
        raw.setImageTintList(ColorStateList.valueOf(chrome.primary));
        lastGhost = "";
        pushGhost();
    }

    /** Where in the draft a tap at ({@code x}, {@code y}) surface pixels puts the cursor; -1 off its words. */
    private int cursorAt(float x, float y) {
        if (layout == null || Float.isNaN(x) || Float.isNaN(y) || layout.draftRuns.length == 0) return -1;
        float col = (x / density - layout.left) / layout.cellWidth;
        int row = (int) Math.floor((y / density - layout.top) / layout.cellHeight);
        int drawn = DraftTaps.drawnAt(layout.draftRuns, row, col);
        return DraftTaps.fieldIndex(GhostText.fieldOffsets(drafts.text(), drafts.cuts(), clipLengths, parkedIds()),
                drawn);
    }

    private void startTyping() {
        startTyping(-1);
    }

    /** Opens the keyboard into the draft, its cursor at {@code cursor} (-1: the end). */
    private void startTyping(int cursor) {
        if (!agent) return;
        // The draft is written where Claude's prompt is: at the live bottom, not in history.
        host.toLive();
        // Read when the keyboard connects, on the focus below.
        field.setPrivateImeOptions(privateImeOptions(drawsTalk()));
        if (!typing) {
            typing = true;
            imeShown = false;
            // The keyboard edits the draft from its end, where the ghost caret is.
            showText(drafts.text());
            apply();
        }
        // After the field is laid out: a just-shown field cannot take focus or
        // the keyboard in the same frame, and SHOW_IMPLICIT may be ignored.
        field.post(() -> {
            if (!typing) return;
            field.requestFocus();
            int length = field.getText().length();
            field.setSelection(cursor >= 0 && cursor <= length ? cursor : length);
            InputMethodManager input = context.getSystemService(InputMethodManager.class);
            if (input != null) input.showSoftInput(field, 0);
            if (android.os.Build.VERSION.SDK_INT >= 30 && field.getWindowInsetsController() != null) {
                field.getWindowInsetsController().show(android.view.WindowInsets.Type.ime());
            }
        });
    }

    /**
     * Puts the keyboard away; the draft stays as ghost text at the prompt.
     * False when it was not up (Back then does its usual thing).
     */
    boolean hideKeyboard() {
        if (!typing) return false;
        host.hideKeyboard();
        InputMethodManager input = context.getSystemService(InputMethodManager.class);
        if (input != null) input.hideSoftInputFromWindow(field.getWindowToken(), 0);
        stopTyping();
        return true;
    }

    /** Whether the keyboard is up, typing into the draft. */
    boolean typing() { return typing; }

    /** Our own Shift+Enter newline is being inserted: it is not a typed Enter. */
    private boolean insertingNewline;

    /**
     * An Enter key while typing, wherever focus is: HeliBoard sends one
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
        field.setVisibility(agent && typing ? View.VISIBLE : View.GONE);
        if (!agent || covered) {
            tapGuard.hidden();
            targets.setVisibility(View.GONE);
        }
        place();
        pushGhost();
    }

    /**
     * Puts the touch targets on the words the renderer drew in the program's
     * frame ("type ─ talk ─ send"), from its last layout hint; it always draws
     * them somewhere (see gpui-rio ghost.rs, {@code place_all}). The keys float
     * just above the prompt (or a dialog).
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
        // While typing too: "talk", "✕" and "send" work ("type" is left out).
        boolean showing = agent && !covered;
        boolean drawn = !layout.controls.isEmpty();
        placeTargets(showing && drawn, left, top, surface.getHeight() / density);
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
            // Hidden controls are forgotten: shown again, even in the same place, they are new.
            tapGuard.hidden();
            targets.setVisibility(View.GONE);
            return;
        }
        float right = layout.left + layout.cols * layout.cellWidth;
        java.util.List<ControlTargets.Rect> rects = new java.util.ArrayList<>(ControlTargets.layout(layout.controls,
                layout.left, layout.top, layout.cellWidth, layout.cellHeight, layout.targetsTop, surfaceBottom, right,
                recording(dictationState), layout.draftSpans));
        // The queue's rows above the prompt, while it is open: one target per row, its full width.
        if (queueOpen) rects.addAll(ControlTargets.rows(layout.echoes, layout.left, layout.top, layout.cellWidth,
                layout.cellHeight, layout.cols));
        tapGuard.laid(rects, android.os.SystemClock.uptimeMillis());
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
            // One per target: each finger's gesture is decided on its own touch path.
            GuardedTarget guarded = new GuardedTarget(rect.id, tapGuard, this::targetTapped);
            target.setOnTouchListener(guarded);
            target.setOnClickListener(guarded);
            if ("send".equals(rect.id)) {
                target.setOnLongClickListener(v -> { setKeysOpen(!keysOpen); return true; });
            } else if ("clear".equals(rect.id)) {
                target.setOnLongClickListener(v -> { tapClear(true); return true; });
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

    /**
     * A control's touch target, as both its touch and its click listener. The guard is decided
     * on the touch path: a finger that goes down on a control that just slid under it
     * ({@link TapGuard}, e.g. "talk" into undo's place as its window ends) has its whole gesture
     * taken, so it never becomes a click. A click that does arrive (a finger on a settled
     * control, TalkBack's double tap, a performClick) always acts: a named activation says
     * which control it means. One per target, so each finger's gesture is its own, and the
     * gesture's state ends with its UP or CANCEL.
     */
    static final class GuardedTarget implements View.OnTouchListener, View.OnClickListener {
        final String id;
        private final TapGuard guard;
        private final java.util.function.Consumer<String> act;
        /** The gesture in progress began on this control while it was still moving: all of it is taken. */
        private boolean taking;

        GuardedTarget(String id, TapGuard guard, java.util.function.Consumer<String> act) {
            this.id = id;
            this.guard = guard;
            this.act = act;
        }

        @Override public boolean onTouch(View v, MotionEvent e) {
            return touch(e.getActionMasked(), android.os.SystemClock.uptimeMillis());
        }

        /** Whether this event of the gesture is taken here (so the view never makes it a click). */
        boolean touch(int action, long now) {
            if (action == MotionEvent.ACTION_DOWN) taking = !guard.accepts(id, now);
            boolean taken = taking;
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) taking = false;
            return taken;
        }

        @Override public void onClick(View v) {
            act.accept(id);
        }
    }

    private void targetTapped(String id) {
        if (id.startsWith("echo-")) {
            tapRow(id);
            return;
        }
        if (id.startsWith("row-")) {
            rowAction(id);
            return;
        }
        switch (id) {
            case "type": toggleTyping(); break;
            case "talk": toggleDictation(); break;
            case "send": enter(); break;
            case "clear": tapClear(false); break;
            case "undo":
                clearing.undo(wall());
                pushGhost();
                scheduleQueueRefresh();
                break;
            case "queue":
                queueOpen = !queueOpen;
                selectedRow = null;
                pushGhost();
                break;
            default:
        }
    }

    private static String describe(String id) {
        if (id.startsWith("echo-")) return "A queued message: tap for its actions";
        switch (id) {
            case "type": return "Type a message";
            case "talk": return "Dictate; tap again to stop";
            case "row-retry": return "Send this recording to the desktop again";
            case "send": return "Send, or press Enter; hold for the terminal keys";
            case "clear": return "Clear the message; tap again or hold to clear the queue too";
            case "undo": return "Undo the clear";
            case "queue": return "Show or hide what waits to be sent";
            case "row-edit": return "Take this message back into the draft";
            case "row-drop": return "Drop this message";
            case "row-again": return "Send this message again";
            case "row-back": return "Back";
            default: return id;
        }
    }

    /** Recordings the desktop gave up on (after {@code ClipStore.MAX_ATTEMPTS} uploads). */
    private java.util.Set<String> parkedIds() {
        java.util.Set<String> ids = new java.util.HashSet<>();
        for (PendingClip clip : Dictation.pending(context).parked()) ids.add(clip.id);
        return ids;
    }

    /** The pane's parked recordings: recorded for it, or in its draft or queued messages. */
    private List<PendingClip> parkedHere() {
        java.util.Set<String> held = new java.util.HashSet<>(DictationToken.ids(drafts.text()));
        for (Outbox.Message m : queuedMessages) held.addAll(DictationToken.ids(m.text));
        List<PendingClip> out = new java.util.ArrayList<>();
        for (PendingClip clip : Dictation.pending(context).parked()) {
            // A recovered recording's pane is not known: it shows on every pane.
            if (held.contains(clip.id) || clip.target.isEmpty() || drafts.pane().equals(clip.target)) out.add(clip);
        }
        return out;
    }

    /** The controls drawn in the program's frame, for the current state and the grid's width. */
    private java.util.List<GhostText.Control> controls(List<PendingClip> loose) {
        boolean dictates = dictation.isAvailable();
        int cols = layout == null ? Integer.MAX_VALUE : layout.cols;
        long now = android.os.SystemClock.uptimeMillis();
        Line in = new Line();
        in.typing = typing;
        in.talks = dictates
                ? java.util.Arrays.asList(talk(Words.FULL), talk(Words.SHORT), talk(Words.TINY))
                : java.util.Collections.emptyList();
        in.recording = recording(dictationState);
        in.queued = QueueView.count(queuedMessages, loose, parkedHere(), linkDown);
        in.queueStyle = queueStyle(queuedMessages, loose.size() + parkedHere().size(), linkDown);
        in.expanded = queueOpen;
        in.clearable = !drafts.text().trim().isEmpty() || dictating() || in.queued > 0;
        if (clearing.open(wall()) && clearing.pane().equals(drafts.pane())) {
            in.undoWide = clearing.label(false);
            in.undoNarrow = clearing.label(true);
        }
        QueueView.Row selected = selectedRow == null ? null : row(selectedRow);
        if (queueOpen && selected != null) in.rowActions = rowActions(selected);
        return controls(in, cols);
    }

    /** The shown row with {@code id}, or null. */
    private QueueView.Row row(String id) {
        for (QueueView.Row r : shownRows) if (r.id.equals(id)) return r;
        return null;
    }

    /**
     * Recordings waiting for the desktop that belong to the pane on screen and that neither its
     * draft nor a queued message holds: rows of their own in the queue.
     */
    private List<PendingClip> looseClips() {
        String pane = drafts.pane();
        java.util.Set<String> held = new java.util.HashSet<>(DictationToken.ids(drafts.text()));
        for (Outbox.Message m : queuedMessages) held.addAll(DictationToken.ids(m.text));
        List<PendingClip> out = new java.util.ArrayList<>();
        for (PendingClip clip : Dictation.pending(context).waiting()) {
            if (pane.equals(clip.target) && !held.contains(clip.id)) out.add(clip);
        }
        return out;
    }

    /** Input typed while the link was down waits for it ({@code count}, 0 once it is back). */
    void setLinkDown(int count) {
        if (count == linkDown) return;
        linkDown = Math.max(0, count);
        pushGhost();
    }

    /** The activity took a queued message out by the user's hand (the full list's sheet). */
    void forgetQueued(String id) {
        queue.forget(id);
    }

    /** Wakes up for the next undo window to close or "sent" row to fade. */
    private void scheduleQueueRefresh() {
        field.removeCallbacks(refreshQueue);
        long up = android.os.SystemClock.uptimeMillis();
        long sent = queue.nextChange(up);
        long undo = clearing.until();
        long delay = Long.MAX_VALUE;
        if (sent != Long.MAX_VALUE) delay = sent - up;
        if (undo != Long.MAX_VALUE) delay = Math.min(delay, undo - wall());
        if (delay == Long.MAX_VALUE) return;
        field.postDelayed(refreshQueue, Math.max(50, delay + 20));
    }

    /**
     * The clear's first act (item 74): a dictation still recording or transcribing ends at once,
     * its audio discarded, and nothing it would still say (a partial, the desktop's or the phone's
     * final, a queued clip) lands in the draft afterwards; an Enter waiting on it is dropped.
     */
    private boolean cancelDictationForClear() {
        // One engine for the process (#30): only the session this window started is cancelled;
        // another window's recording is its own.
        return routing.cancelForClear(dictation, dictating() && Dictation.owns(this));
    }

    private void tapClear(boolean longPress) {
        long now = wall();
        boolean draftClearable = !drafts.text().trim().isEmpty() || dictating();
        if (longPress) clearing.longPress(drafts.pane(), now);
        else clearing.tap(drafts.pane(), draftClearable, now);
        selectedRow = null;
        pushGhost();
        scheduleQueueRefresh();
    }

    /**
     * A tap on a drawn row: {@code echo-<row id>}. Rows are found by their own id, so a tap on a
     * row from a frame that has since changed acts on that row or on nothing, never on another.
     */
    private void tapRow(String echoId) {
        QueueView.Row r = row(echoId.substring("echo-".length()));
        if (r == null) return;
        if (r.kind == QueueView.Kind.MORE) {
            host.showQueue(allRows(), queuedMessages);
            return;
        }
        if (!queueOpen) {
            queueOpen = true;
        } else if (r.actionable) {
            selectedRow = r.id.equals(selectedRow) ? null : r.id;
        }
        pushGhost();
    }

    /** Every row of the pane's queue, none hidden behind "+N more". */
    private List<QueueView.Row> allRows() {
        return queue.all(drafts.pane(), queuedMessages, looseClips(), parkedHere(), linkDown,
                layout == null ? Integer.MAX_VALUE : layout.cols, android.os.SystemClock.uptimeMillis());
    }

    /**
     * A row's action from the full list ({@link Host#showQueue}): "edit", "drop", "again" or
     * "retry" on the row with {@code id}.
     */
    void queueCommand(String id, String action) {
        QueueView.Row r = null;
        for (QueueView.Row row : allRows()) if (row.id.equals(id)) r = row;
        if (r == null) return;
        selectedRow = id;
        shownRowsExtra = r;
        rowAction("row-" + action);
        shownRowsExtra = null;
    }

    /** A row from the full list, acted on though it is not drawn. */
    private QueueView.Row shownRowsExtra;

    private void rowAction(String action) {
        QueueView.Row r = selectedRow == null ? null : row(selectedRow);
        if (r == null && shownRowsExtra != null && shownRowsExtra.id.equals(selectedRow)) r = shownRowsExtra;
        selectedRow = null;
        if (r == null) {
            pushGhost();
            return;
        }
        long now = wall();
        switch (action) {
            case "row-again":
                host.resendQueued(r.id);
                break;
            case "row-retry":
                Dictation.pending(context).retry(r.id);
                host.notice("Sending the recording to the desktop again");
                break;
            case "row-edit": {
                queue.forget(r.id);
                String text = host.takeQueued(r.id);
                if (text != null) takeBack(text);
                break;
            }
            case "row-drop":
                if (r.kind == QueueView.Kind.CLIP || r.kind == QueueView.Kind.PARKED) {
                    clearing.dropped(drafts.pane(), null, r.id, now);
                } else if (r.kind == QueueView.Kind.MESSAGE) {
                    clearing.dropped(drafts.pane(), r.id, null, now);
                }
                break;
            default:
        }
        pushGhost();
        scheduleQueueRefresh();
    }

    /** Cells the renderer wants free beside the controls' line (see {@link ControlLine#MARGIN}). */
    static final int CONTROLS_MARGIN = ControlLine.MARGIN;

    /** The line's state; see {@link ControlLine}. */
    static final class Line extends ControlLine.State { }

    static java.util.List<GhostText.Control> controls(Line in, int cols) {
        return ControlLine.controls(in, cols);
    }

    static String queueStyle(List<Outbox.Message> queued, int clips, int linkDown) {
        return ControlLine.queueStyle(queued, clips, linkDown);
    }

    static java.util.List<GhostText.Control> rowActions(QueueView.Row row) {
        return ControlLine.rowActions(row);
    }

    /** Cells the renderer's line takes for {@code controls}: " a ─ b ─ c ──". */
    static int lineWidth(List<GhostText.Control> controls) {
        return ControlLine.width(controls);
    }

    /** How many words "talk" may take: all of them, a narrow grid's, or a tiny grid's. */
    private enum Words { FULL, SHORT, TINY }

    /** "talk", or what the dictation is doing, as TUI-style words. */
    private GhostText.Control talk(Words words) {
        long elapsed = recordingSince == 0 ? 0 : android.os.SystemClock.elapsedRealtime() - recordingSince;
        String clock = DraftChips.clock(elapsed);
        switch (dictationState) {
            case CONNECTING:
                return new GhostText.Control("talk", words == Words.TINY ? "…" : "connecting…", "info");
            case LISTENING: {
                String text;
                switch (words) {
                    case FULL: text = "● rec " + clock + " · tap to stop"; break;
                    case SHORT: text = "● " + clock + " stop"; break;
                    default: text = "● stop";
                }
                return new GhostText.Control("talk", text, "danger");
            }
            case TRANSCRIBING:
                return new GhostText.Control("talk", words == Words.TINY ? "…"
                        : finishing ? "finishing…" : "transcribing…", "info");
            case ON_DEVICE: {
                // The phone transcribes: the warning colour says offline when the words cannot.
                String why = desktopError ? "desktop error" : "offline";
                String text;
                switch (words) {
                    case FULL: text = "● " + why + " · rec " + clock + " · tap to stop"; break;
                    case SHORT: text = "● " + (desktopError ? "error" : "offline") + " " + clock + " stop"; break;
                    default: text = "● stop";
                }
                return new GhostText.Control("talk", text, "warn");
            }
            case OFFLINE_RECORDING: {
                // No recognizer on the phone: the recording waits for the desktop.
                String text;
                switch (words) {
                    case FULL: text = (desktopError ? "desktop error" : "offline") + " · queued · rec " + clock
                            + " · tap to stop"; break;
                    case SHORT: text = "● queued " + clock + " stop"; break;
                    default: text = "● stop";
                }
                return new GhostText.Control("talk", text, "warn");
            }
            default:
                // "Nothing heard" (#31) stays; waiting recordings are counted by the queue control.
                String said = nothingHeard == null || words == Words.TINY ? ""
                        : " " + nothingHeardWords(nothingHeard, words == Words.FULL);
                return new GhostText.Control("talk", "talk", "dim",
                        (transcribedOnPhone ? " offline" : "") + said, "warn");
        }
    }

    /** Hands the renderer the pane's draft, queued messages and controls, unless unchanged. */
    private void pushGhost() {
        String json = "";
        if (agent) {
            // While typing, the keyboard's underlined word reads as the
            // revisable tail, as an engine's partial words do.
            String draft = drafts.text();
            int partialFrom = drafts.partialFrom();
            GhostText.Marks marks;
            if (partialFrom >= 0 && partialFrom <= draft.length()) {
                // An engine's revisable words, at the draft's end.
                marks = new GhostText.Marks(-1, -1, -1, partialFrom, draft.length());
            } else if (typing && field.getText().toString().equals(draft)) {
                // The keyboard's cursor, selection and underlined word, where
                // they are in the draft (a field out of step marks nothing).
                Editable typed = field.getText();
                marks = GhostText.Marks.of(field.getSelectionStart(), field.getSelectionEnd(),
                        BaseInputConnection.getComposingSpanStart(typed),
                        BaseInputConnection.getComposingSpanEnd(typed));
            } else {
                marks = GhostText.Marks.NONE;
            }
            List<PendingClip> loose = looseClips();
            int cols = layout == null ? Integer.MAX_VALUE : layout.cols;
            long now = android.os.SystemClock.uptimeMillis();
            shownRows = queue.rows(drafts.pane(), queuedMessages, loose, parkedHere(), linkDown, cols, queueOpen,
                    selectedRow, now);
            json = GhostText.json(GhostText.kind(kind), draft, marks, drafts.cuts(), clipLengths, parkedIds(),
                    shownRows, queueOpen, selectedRow, cols, controls(loose), GhostText.Colors.of(chrome));
        }
        if (json.equals(lastGhost)) return;
        lastGhost = json;
        host.setGhost(json);
    }

    /**
     * The field's input type: single-line, so the keyboard's action key sends;
     * the text still keeps line breaks from Shift+Enter on a hardware keyboard
     * and from pasted text.
     */
    static final int FIELD_INPUT_TYPE = InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            | InputType.TYPE_TEXT_FLAG_AUTO_CORRECT;

    /** Send, and no full-screen extract box in landscape: that would be a typing box again. */
    static final int FIELD_IME_OPTIONS = EditorInfo.IME_ACTION_SEND | EditorInfo.IME_FLAG_NO_EXTRACT_UI
            | EditorInfo.IME_FLAG_NO_FULLSCREEN;

    /**
     * Asks the keyboard to leave out its microphone key
     * ({@code EditorInfo.privateImeOptions}, comma-separated, no spaces):
     * HeliBoard looks for its own package name plus ".noMicrophoneKey"
     * (release and debug builds), AOSP LatinIME for "noMicrophoneKey" and the
     * older "nm", Gboard for its package's. Keyboards that know none of them
     * ignore them.
     */
    static final String NO_MICROPHONE_KEY = String.join(",",
            "helium314.keyboard.noMicrophoneKey",
            "helium314.keyboard.debug.noMicrophoneKey",
            "noMicrophoneKey",
            "nm",
            "com.google.android.inputmethod.latin.noMicrophoneKey");

    /**
     * The keyboard's private options for a field on a pane where Herdr draws
     * its own "talk": no microphone key there, so there is one talk control,
     * Herdr's, in every fold state. Elsewhere (no dictation set up, a shell)
     * the keyboard keeps its own.
     */
    static String privateImeOptions(boolean drawsTalk) {
        return drawsTalk ? NO_MICROPHONE_KEY : null;
    }

    /** Herdr draws "talk" on this pane: an agent's, with dictation set up. */
    boolean drawsTalk() {
        return agent && dictation.isAvailable();
    }

    private EditText createField() {
        // The cursor and selection go where the keyboard puts them (arrow
        // keys, its cursor pad or spacebar swipe, select all): the renderer
        // draws both in the draft at the prompt, as a text field would.
        EditText edit = new EditText(context) {
            @Override protected void onSelectionChanged(int start, int end) {
                super.onSelectionChanged(start, end);
                if (typing) pushGhost();
            }
        };
        edit.setSaveEnabled(false);
        edit.setTextSize(14);
        edit.setHint("Message");
        // Never seen: the keyboard's target only. The draft is drawn as ghost
        // text at the agent's prompt, so nothing non-native shows.
        edit.setAlpha(0f);
        edit.setBackground(null);
        edit.setPadding(0, 0, 0, 0);
        edit.setCursorVisible(false);
        edit.setLongClickable(false);
        edit.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        edit.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        edit.setInputType(FIELD_INPUT_TYPE);
        edit.setHorizontallyScrolling(false);
        edit.setMaxLines(MAX_LINES);
        edit.setImeOptions(FIELD_IME_OPTIONS);
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
                host.typed();
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
                // What was typed shows at the prompt as it is typed.
                pushGhost();
            }
        });
        return edit;
    }

    /**
     * A session that a pane switch stops rather than cancels: its text is the phone's or is on
     * its way, and is placed in the pane it was spoken for. A desktop session still listening
     * is cancelled (the utterance belongs to its pane, and nothing was transcribed yet).
     */
    static boolean keepsAcrossPaneSwitch(DictationEngine.State s) {
        return s == DictationEngine.State.OFFLINE_RECORDING || s == DictationEngine.State.ON_DEVICE
                || s == DictationEngine.State.TRANSCRIBING;
    }

    /** The microphone is open: the clock runs, and "talk" stops it. */
    static boolean recording(DictationEngine.State s) {
        return DictationRouting.recording(s);
    }

    /** A session runs: IDLE and PENDING both mean none does. */
    private boolean dictating() {
        return dictationState != DictationEngine.State.IDLE && dictationState != DictationEngine.State.PENDING;
    }

    private void setDictationState(DictationEngine.State state) {
        boolean wasRecording = recording(dictationState);
        dictationState = state;
        if (!dictating()) Dictation.release(this);
        if (state != DictationEngine.State.TRANSCRIBING) finishing = false;
        boolean recording = recording(state);
        if (recording && !wasRecording) recordingSince = android.os.SystemClock.elapsedRealtime();
        if (!recording) recordingSince = 0;
        // The clock in the controls ticks while the microphone is open.
        field.removeCallbacks(tick);
        if (recording) field.postDelayed(tick, 1000);
        pushGhost();
    }

    /** The "talk" control's one tap: start, or stop what is recording. Transcribing ignores it. */
    private void toggleDictation() {
        if (recording(dictationState)) {
            dictation.stop();
            return;
        }
        if (dictating()) return;
        if (Dictation.busyElsewhere(this)) {
            // One recording at a time: another Herdr window's is finished first (its words go
            // to its own pane), then "talk" here starts a new one.
            dictation.stop();
            host.notice("Finishing the recording in Herdr's other window");
            return;
        }
        if (!dictation.isAvailable()) {
            host.notice("Dictation is not set up on this phone");
            return;
        }
        // The engine refuses to start without RECORD_AUDIO; ask first, start on yes.
        host.withMicrophone(this::startDictation);
    }

    private void startDictation() {
        if (!agent || dictating() || Dictation.busyElsewhere(this)) return;
        if (typing) drafts.edit(field.getText().toString());
        drafts.dictationStarted();
        transcribedOnPhone = false;
        nothingHeard = null;
        desktopError = false;
        clearing.commit();
        final String pane = drafts.pane();
        routing.forget();
        Dictation.own(this);
        dictation.start(pane, routing.listener(pane));
    }

    /**
     * Enter: while recording, finish the utterance and send it; with a draft,
     * queue it as one paste plus Enter; otherwise Enter goes to the program.
     */
    private void enter() {
        switch (DictationRouting.onEnter(dictationState)) {
            case STOP_THEN_SEND:
                routing.sendWhenDone();
                dictation.stop();
                return;
            case SEND_WHEN_DONE:
                routing.sendWhenDone();
                return;
            default:
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
        transcribedOnPhone = false;
        nothingHeard = null;
        showText("");
    }

    /** The draft changed under us (dictation, a pane switch): the field and the ghost follow. */
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

    /** How a waiting dictation's token reads in the field. */
    private DraftChips.Style chip() {
        return new DraftChips.Style(chrome.warning, density, clipLengths, parkedIds(),
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
            if (!stripKeeps(event.getActionMasked())) return false;
            if (event.getActionMasked() != MotionEvent.ACTION_UP) return true;
            float dx = event.getX() - downX, dy = event.getY() - downY;
            if (Math.abs(dx) > dp(28) && Math.abs(dx) > Math.abs(dy)) host.switchPane(dx < 0 ? 1 : -1);
            else if (-dy > dp(20) && -dy > Math.abs(dx)) setKeysOpen(!keysOpen);
            return true;
        }
    }

    /**
     * Whether the targets' strip keeps a touch that reached the strip itself.
     * The strip spans the box around every target, so a finger that goes down
     * there and on no target is on the terminal between them (a program's own
     * button or tab, say) and must reach it. A swipe begun on a target is
     * still the strip's: it takes over from the target after the first move,
     * so only moves and lifts reach it.
     */
    static boolean stripKeeps(int action) {
        return action != MotionEvent.ACTION_DOWN;
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
