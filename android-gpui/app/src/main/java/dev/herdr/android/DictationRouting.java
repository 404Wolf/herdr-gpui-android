package dev.herdr.android;

import dev.herdr.android.dictation.DictationEngine;
import dev.herdr.android.dictation.DictationException;
import dev.herdr.android.dictation.PendingClip;

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

        void clipLength(String clipId, long durationMs);

        /** The text just placed on screen is the phone's: say so ("talk offline"). */
        void transcribedOnPhone();

        /** The desktop's final waits for a decode: "finishing…" instead of "transcribing…". */
        default void finishing() { }

        /** Saves the drafts to disk now, before the recording a final came from is deleted. */
        default void persistNow() { }
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
    private String enterPane;

    DictationRouting(ComposerDrafts drafts, Bar bar) {
        this.drafts = drafts;
        this.bar = bar;
    }

    /** Enter was pressed in the pane on screen while a session finishes: send once it ends. */
    void sendWhenDone() {
        enterPane = drafts.pane();
    }

    /** An Enter is waiting on the session. */
    boolean sendPending() {
        return enterPane != null;
    }

    /** The session was cancelled (or a new one starts): no Enter waits any more. */
    void forget() {
        enterPane = null;
    }

    /** The callbacks of a session started in {@code pane}. */
    DictationEngine.Listener listener(String pane) {
        return new DictationEngine.Listener() {
            @Override public void onPartial(String text) {
                if (pane.equals(drafts.pane())) bar.showText(drafts.dictationPartial(text));
            }

            @Override public void onFinal(String text) {
                onFinal(text, false);
            }

            @Override public void onFinal(String text, boolean onDevice) {
                if (onDevice) {
                    bar.notice("Offline: transcribed on the phone (desktop away or slow); recording kept 48 h");
                }
                if (pane.equals(drafts.pane())) {
                    if (onDevice) bar.transcribedOnPhone();
                    bar.showText(drafts.dictationFinal(text));
                } else if (!text.isEmpty()) {
                    // Switched away while it finished: it joins the end of its own pane's draft.
                    bar.appendDictation(pane, text);
                }
                // Saved before the engine deletes the recording.
                bar.persistNow();
                // The session is over (IDLE follows): Enter must send, not wait again.
                settle(DictationEngine.State.IDLE);
            }

            @Override public void onPending(PendingClip clip) {
                bar.clipLength(clip.id, clip.durationMs);
                // Elsewhere its chip is already in its pane's draft; the text finds it later.
                if (pane.equals(drafts.pane())) bar.showText(drafts.dictationPending(clip.id));
                // A message sent with the chip waits in the outbox until the desktop fills it in.
                settle(DictationEngine.State.PENDING);
            }

            @Override public void onFinishing(double tailSeconds) {
                bar.finishing();
            }

            @Override public void onState(DictationEngine.State state) {
                bar.setDictationState(state);
            }

            @Override public void onError(DictationException error) {
                forget();
                bar.setDictationState(DictationEngine.State.IDLE);
                if (pane.equals(drafts.pane())) bar.showText(drafts.dictationEnded());
                bar.notice(error.getMessage() == null ? "Dictation stopped" : error.getMessage());
            }
        };
    }

    /** The session ended in {@code next}: a waiting Enter sends in its pane, or is dropped. */
    private void settle(DictationEngine.State next) {
        String pane = enterPane;
        enterPane = null;
        if (pane == null || !pane.equals(drafts.pane())) return;
        bar.setDictationState(next);
        bar.enter();
    }
}
