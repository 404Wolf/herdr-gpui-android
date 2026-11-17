package dev.herdr.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import dev.herdr.android.dictation.DictationEngine;
import dev.herdr.android.dictation.DictationException;
import dev.herdr.android.dictation.PendingClip;
import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Test;

/**
 * A session's text goes to the pane it was spoken for, and an Enter pressed while it finishes
 * is settled exactly once at its end, in the pane it was pressed in (cases from t1vg's review
 * of #14: a pane switch during the phone's fallback, then Enter on the new pane).
 */
public final class DictationRoutingTest {
    /** The composer, as far as routing sees it: Enter waits on a finishing session, else sends. */
    private static final class Composer implements DictationRouting.Bar {
        final ComposerDrafts drafts = new ComposerDrafts();
        final DictationRouting routing = new DictationRouting(drafts, this);
        final List<String> sent = new ArrayList<>();
        DictationEngine.State state = DictationEngine.State.IDLE;
        boolean offlineTag;
        final List<String> notices = new ArrayList<>();
        dev.herdr.android.dictation.DictationException.Reason nothingHeard;
        boolean desktopError;

        @Override public void showText(String text) { }

        @Override public void setDictationState(DictationEngine.State s) {
            state = s;
        }

        @Override public void enter() {
            // ComposerBar.enter's own decision: while a session records or transcribes, Enter waits.
            if (DictationRouting.onEnter(state) != DictationRouting.Enter.SEND) {
                routing.sendWhenDone();
                return;
            }
            String outgoing = drafts.outgoing();
            if (outgoing == null) return;
            sent.add(drafts.pane() + ":" + outgoing);
            drafts.sent();
        }

        @Override public void notice(String text) {
            notices.add(text);
        }

        @Override public void nothingHeard(dev.herdr.android.dictation.DictationException.Reason reason) {
            nothingHeard = reason;
        }

        @Override public void desktopError() {
            desktopError = true;
        }

        @Override public void appendDictation(String pane, String text) {
            drafts.append(pane, text);
        }

        @Override public void clipLength(String clipId, long durationMs) { }

        boolean saves = true;

        @Override public boolean persistNow() {
            return saves;
        }

        @Override public void transcribedOnPhone() {
            offlineTag = true;
        }

        /** A session started on pane A falls back, and Wolf switches to B (it keeps going). */
        DictationEngine.Listener dictateOnAThenSwitchToB() {
            drafts.switchTo("A");
            drafts.edit("before");
            drafts.dictationStarted();
            DictationEngine.Listener a = routing.listener("A");
            state = DictationEngine.State.TRANSCRIBING;
            drafts.dictationEnded();
            drafts.switchTo("B");
            drafts.edit("beta");
            return a;
        }
    }

    private static PendingClip clip(String target) throws Exception {
        Constructor<PendingClip> c = PendingClip.class.getDeclaredConstructor(
                String.class, String.class, long.class, long.class);
        c.setAccessible(true);
        return c.newInstance("clip-1", target, 1L, 2000L);
    }

    @Test public void enterWaitsWhileASessionRecordsOrTranscribes() {
        for (DictationEngine.State st : DictationEngine.State.values()) {
            DictationRouting.Enter expected;
            switch (st) {
                case CONNECTING: case LISTENING: case ON_DEVICE: case OFFLINE_RECORDING:
                    expected = DictationRouting.Enter.STOP_THEN_SEND;
                    break;
                case TRANSCRIBING:
                    expected = DictationRouting.Enter.SEND_WHEN_DONE;
                    break;
                default:
                    expected = DictationRouting.Enter.SEND;
            }
            assertEquals(st.name(), expected, DictationRouting.onEnter(st));
        }
    }

    @Test public void theOldPanesFinalSendsTheEnterPressedOnTheNewPane() {
        Composer c = new Composer();
        DictationEngine.Listener a = c.dictateOnAThenSwitchToB();
        c.enter();
        assertEquals("waits for the session", Collections.emptyList(), c.sent);
        a.onFinal("alpha", true);
        assertEquals(Arrays.asList("B:beta"), c.sent);
        assertFalse(c.routing.sendPending());
        assertTrue("the text joined A's draft", c.drafts.switchTo("A").contains("alpha"));
    }

    @Test public void theOldPanesQueuedClipSendsTheEnterPressedOnTheNewPane() throws Exception {
        Composer c = new Composer();
        DictationEngine.Listener a = c.dictateOnAThenSwitchToB();
        c.enter();
        a.onPending(clip("A"));
        a.onState(DictationEngine.State.PENDING);
        assertEquals("the Enter on B is not lost", Arrays.asList("B:beta"), c.sent);
        assertFalse(c.routing.sendPending());
    }

    @Test public void anOldPanesQueuedClipLeavesNoEnterForALaterDictation() throws Exception {
        Composer c = new Composer();
        DictationEngine.Listener a = c.dictateOnAThenSwitchToB();
        c.enter();
        a.onPending(clip("A"));
        a.onState(DictationEngine.State.PENDING);
        // A later dictation on B, with no Enter: it must not send by itself.
        c.drafts.dictationStarted();
        DictationEngine.Listener b = c.routing.listener("B");
        c.state = DictationEngine.State.TRANSCRIBING;
        b.onFinal("new private utterance", true);
        b.onState(DictationEngine.State.IDLE);
        assertEquals(Arrays.asList("B:beta"), c.sent);
        assertTrue(c.drafts.text().contains("new private utterance"));
    }

    @Test public void anEnterPressedOnTheOldPaneIsDroppedOnceThatPaneIsGone() {
        Composer c = new Composer();
        c.drafts.switchTo("A");
        c.drafts.edit("before");
        c.drafts.dictationStarted();
        DictationEngine.Listener a = c.routing.listener("A");
        c.state = DictationEngine.State.TRANSCRIBING;
        c.enter(); // on A
        c.drafts.dictationEnded();
        c.drafts.switchTo("B");
        c.drafts.edit("beta");
        a.onFinal("alpha", false);
        assertEquals("neither A (not on screen) nor B (no Enter there)", Collections.emptyList(), c.sent);
        assertFalse(c.routing.sendPending());
    }

    @Test public void onTheSamePaneTheFinalIsSentWithTheDraft() {
        Composer c = new Composer();
        c.drafts.switchTo("A");
        c.drafts.edit("note:");
        c.drafts.dictationStarted();
        DictationEngine.Listener a = c.routing.listener("A");
        c.state = DictationEngine.State.ON_DEVICE;
        c.enter();
        a.onFinal("alpha", true);
        assertEquals(1, c.sent.size());
        assertTrue(c.sent.get(0), c.sent.get(0).startsWith("A:note:") && c.sent.get(0).contains("alpha"));
        assertTrue("marked offline", c.offlineTag);
    }

    @Test public void onTheSamePaneAQueuedClipIsSentAsItsChip() throws Exception {
        Composer c = new Composer();
        c.drafts.switchTo("A");
        c.drafts.dictationStarted();
        DictationEngine.Listener a = c.routing.listener("A");
        c.state = DictationEngine.State.OFFLINE_RECORDING;
        c.enter();
        a.onPending(clip("A"));
        assertEquals(1, c.sent.size());
        assertTrue(c.sent.get(0), c.sent.get(0).contains(DictationToken.of("clip-1")));
        assertEquals(DictationEngine.State.PENDING, c.state);
    }

    @Test public void anErrorDropsTheWaitingEnter() {
        Composer c = new Composer();
        c.drafts.switchTo("A");
        c.drafts.edit("draft");
        c.drafts.dictationStarted();
        DictationEngine.Listener a = c.routing.listener("A");
        c.state = DictationEngine.State.TRANSCRIBING;
        c.enter();
        a.onError(new DictationException(DictationException.Reason.AUDIO, "The recording could not be saved"));
        assertEquals(Collections.emptyList(), c.sent);
        assertFalse(c.routing.sendPending());
    }

    @Test public void nothingHeardIsSaidInTalkNotInANote() {
        Composer c = new Composer();
        c.drafts.switchTo("A");
        c.drafts.dictationStarted();
        DictationEngine.Listener l = c.routing.listener("A");
        l.onError(new dev.herdr.android.dictation.DictationException(
                dev.herdr.android.dictation.DictationException.Reason.SILENT_AUDIO, "The mic sent silence"));
        assertEquals(dev.herdr.android.dictation.DictationException.Reason.SILENT_AUDIO, c.nothingHeard);
        assertTrue(c.notices.isEmpty());
        assertEquals(DictationEngine.State.IDLE, c.state);
        l.onDesktopError();
        assertTrue(c.desktopError);
    }

    @Test public void eachReasonHasItsOwnWords() {
        java.util.Set<String> said = new java.util.HashSet<>();
        for (dev.herdr.android.dictation.DictationException.Reason r
                : dev.herdr.android.dictation.DictationException.Reason.values()) {
            if (r.nothingHeard()) assertTrue(r.toString(), said.add(ComposerBar.nothingHeardWords(r, true)));
        }
        assertEquals("mic sent silence", ComposerBar.nothingHeardWords(
                dev.herdr.android.dictation.DictationException.Reason.SILENT_AUDIO, true));
    }

    @Test public void aFinalWhoseDraftCouldNotBeSavedTellsTheEngineToKeepTheRecording() {
        Composer c = new Composer();
        c.drafts.switchTo("A");
        c.drafts.dictationStarted();
        DictationEngine.Listener a = c.routing.listener("A");
        a.onFinal("words", false);
        assertTrue(a.finalStored());
        c.saves = false;
        c.drafts.dictationStarted();
        DictationEngine.Listener b = c.routing.listener("A");
        b.onFinal("more words", false);
        assertFalse("not saved: the engine keeps the recording", b.finalStored());
        assertTrue("the words stay on screen", c.drafts.text().contains("more words"));
    }
}
