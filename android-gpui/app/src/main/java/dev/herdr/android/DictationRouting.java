package dev.herdr.android;

import dev.herdr.android.dictation.DictationEngine;
import dev.herdr.android.dictation.DictationException;
import dev.herdr.android.dictation.PendingClip;
import dev.herdr.android.dictation.UnverifiedCuts;

/**
 * Where a dictation session's text goes, and the Enter that waits on it. Pure (no views), so
 * the routing is tested on the JVM; {@link ComposerBar} supplies the {@link Bar}.
 *
 * <p>Text goes to the pane the session was started in, even after a switch: on screen there,
 * else at the end of that pane's draft. An Enter pressed while a session finishes is kept with
 * the pane it was pressed in and settled exactly once, at the session's end (final, pending
 * clip, error or cancel): it sends that pane's draft if that pane is still on screen, else it is
 * dropped. It never outlives the session, so a later dictation never sends by itself.
 */
final class DictationRouting {
    /** What the composer does for a session, on the main thread. */
    interface Bar {
        void showText(String text);

        void setDictationState(DictationEngine.State state);

        /** The composer's Enter: sends the draft on screen once no session is finishing. */
        void enter();

        void notice(String text);

        /** {@code text} joins the end of {@code pane}'s draft (not the one on screen). */
        void appendDictation(String pane, String text);

        /** As {@link #appendDictation(String, String)}, with the desktop's unverified cuts in {@code text}. */
        default void appendDictation(String pane, String text, int[] unverifiedCuts) {
            appendDictation(pane, text);
        }

        void clipLength(String clipId, long durationMs);

        /** The text just placed on screen is the phone's: say so ("talk offline"). */
        void transcribedOnPhone();

        /** The session heard nothing ({@code reason}): "talk" says why until the next one. */
        default void nothingHeard(DictationException.Reason reason) { }

        /** The desktop answered this session with an error: say so rather than "offline". */
        default void desktopError() { }

        /** The desktop's final waits for a decode: "finishing…" instead of "transcribing…". */
        default void finishing() { }

        /**
         * Saves the drafts to disk now, before the recording a final came from is deleted; false
         * when the write failed (the engine then keeps the recording).
         */
        default boolean persistNow() {
            return true;
        }

        /** A cleared session's recording was kept for later anyway: hold it, never place it. */
        default void abandonedClip(PendingClip clip) { }
    }

    /** What the composer's Enter does in a dictation state ({@link #onEnter}). */
    enum Enter {
        /** Recording: stop, and send once the session ends. */
        STOP_THEN_SEND,
        /** Transcribing: send once the session ends, never the partial now. */
        SEND_WHEN_DONE,
        /** No session finishing: send the draft now. */
        SEND
    }

    /** The microphone is open: the clock runs, "talk" stops it, and Enter stops first. */
    static boolean recording(DictationEngine.State s) {
        return s == DictationEngine.State.CONNECTING || s == DictationEngine.State.LISTENING
                || s == DictationEngine.State.ON_DEVICE || s == DictationEngine.State.OFFLINE_RECORDING;
    }

    /** ComposerBar.enter's decision: a session still finishing makes Enter wait for its end. */
    static Enter onEnter(DictationEngine.State s) {
        if (recording(s)) return Enter.STOP_THEN_SEND;
        if (s == DictationEngine.State.TRANSCRIBING) return Enter.SEND_WHEN_DONE;
        return Enter.SEND;
    }

    private final ComposerDrafts drafts;
    private final Bar bar;
    /** The pane an Enter was pressed in while a session was finishing; null for none. */
    private String enterOwner;
    /** The session an Enter waits on, and whose draft owns it (host and pane): never another's. */
    private String sessionOwner = "";
    private int sessionGen = -1;
    private int enterGen = -1;
    /** Bumped by {@link #abandon}: listeners made before it say nothing more. */
    private int generation;

    DictationRouting(ComposerDrafts drafts, Bar bar) {
        this.drafts = drafts;
        this.bar = bar;
    }

    /** Enter was pressed in the pane on screen while a session finishes: send once it ends. */
    void sendWhenDone() {
        enterOwner = here();
        enterGen = sessionGen;
    }

    /** The draft on screen, as host and pane (the pane id alone is only unique per computer). */
    private String here() {
        String owner = drafts.owner();
        return owner.isEmpty() ? "p:" + drafts.pane() : owner;
    }

    /**
     * What Enter does now: it waits on a dictation only if that dictation belongs to the pane on
     * screen; on any other pane it sends that pane's own draft at once and never waits on, or sends
     * for, someone else's session.
     */
    Enter enterFor(DictationEngine.State s) {
        Enter wanted = onEnter(s);
        return wanted == Enter.SEND || sessionOwner.equals(here()) ? wanted : Enter.SEND;
    }

    /** An Enter is waiting on the session. */
    boolean sendPending() {
        return enterOwner != null;
    }

    /** The session was cancelled (or a new one starts): no Enter waits any more. */
    void forget() {
        enterOwner = null;
    }

    /**
     * The user cleared the session (item 76): whatever its listener still hears, a partial, a
     * final from the desktop or the phone, a queued clip, lands nowhere (item 74), and no Enter
     * waits on it.
     */
    void abandon() {
        generation++;
        enterOwner = null;
    }

    /**
     * The composer's clear (items 74, 76): a session still recording or transcribing
     * ({@code dictating}) is cancelled, its audio discarded, its partial withdrawn from the draft,
     * and nothing it would still say lands anywhere; an Enter waiting on it is dropped. True when
     * a session was cancelled.
     */
    boolean cancelForClear(DictationEngine engine, boolean dictating) {
        abandon();
        if (!dictating) return false;
        engine.cancel();
        drafts.dictationCancelled();
        bar.setDictationState(DictationEngine.State.IDLE);
        return true;
    }

    /** The callbacks of a session started in {@code pane}. */
    DictationEngine.Listener listener(String pane) {
        final int born = generation;
        final String owner = here();
        sessionOwner = owner;
        sessionGen = born;
        return new DictationEngine.Listener() {
            /** Abandoned: nothing of this session reaches the draft or the screen any more. */
            private boolean gone() {
                return born != generation;
            }

            @Override public void onPartial(String text) {
                onPartial(text, UnverifiedCuts.NONE);
            }

            /** The last words heard: kept if the session ends in an error after a pane switch. */
            private String lastPartial = "";

            @Override public void onPartial(String text, int[] cuts) {
                if (gone()) return;
                lastPartial = text == null ? "" : text;
                if (owner.equals(here())) bar.showText(drafts.dictationPartial(text, cuts));
            }

            @Override public void onFinal(String text) {
                onFinal(text, false, UnverifiedCuts.NONE);
            }

            /** Whether the last final's text reached the disk. */
            private boolean stored = true;

            @Override public boolean finalStored() {
                return stored;
            }

            @Override public void onFinal(String text, boolean onDevice) {
                onFinal(text, onDevice, UnverifiedCuts.NONE);
            }

            @Override public void onFinal(String text, boolean onDevice, int[] cuts) {
                if (gone()) return;
                if (onDevice) {
                    bar.notice("Offline: transcribed on the phone (desktop away or slow); recording kept 48 h");
                }
                if (owner.equals(here())) {
                    if (onDevice) bar.transcribedOnPhone();
                    bar.showText(drafts.dictationFinal(text, cuts));
                } else if (!text.isEmpty()) {
                    // Switched away while it finished: it joins the end of its own pane's draft.
                    bar.appendDictation(pane, text, cuts);
                }
                // Saved before the engine deletes the recording; not saved, it keeps it.
                stored = bar.persistNow();
                // The session is over (IDLE follows): Enter must send, not wait again.
                settle(DictationEngine.State.IDLE, born);
            }

            @Override public void onPending(PendingClip clip) {
                if (gone()) {
                    // Cleared while it was saving: the recording is held, never placed.
                    bar.abandonedClip(clip);
                    return;
                }
                bar.clipLength(clip.id, clip.durationMs);
                // Elsewhere its chip is already in its pane's draft; the text finds it later.
                if (owner.equals(here())) bar.showText(drafts.dictationPending(clip.id));
                // A message sent with the chip waits in the outbox until the desktop fills it in.
                settle(DictationEngine.State.PENDING, born);
            }

            @Override public void onFinishing(double tailSeconds) {
                bar.finishing();
            }

            @Override public void onState(DictationEngine.State state) {
                if (gone()) return;
                bar.setDictationState(state);
            }

            @Override public void onDesktopError() {
                bar.desktopError();
            }

            @Override public void onError(DictationException error) {
                if (gone()) return;
                forget();
                bar.setDictationState(DictationEngine.State.IDLE);
                if (owner.equals(here())) bar.showText(drafts.dictationEnded());
                else if (!lastPartial.isEmpty()) bar.appendDictation(pane, lastPartial);
                if (error.reason().nothingHeard()) {
                    // Said where the mic is, in "talk": no note over the terminal.
                    bar.nothingHeard(error.reason());
                    return;
                }
                bar.notice(error.getMessage() == null ? "Dictation stopped" : error.getMessage());
            }
        };
    }

    /** The session ended in {@code next}: a waiting Enter sends in its own pane, or is dropped. */
    private void settle(DictationEngine.State next, int born) {
        String waiting = enterOwner;
        int waitingGen = enterGen;
        enterOwner = null;
        if (waiting == null || waitingGen != born || !waiting.equals(here())) return;
        bar.setDictationState(next);
        bar.enter();
    }
}
