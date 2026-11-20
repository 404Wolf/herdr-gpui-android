package dev.herdr.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import dev.herdr.android.dictation.DictationEngine;
import dev.herdr.android.dictation.DictationFakes;
import dev.herdr.android.dictation.DictationFakes.Behaviour;
import dev.herdr.android.dictation.DictationFakes.FakeRecognizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * Item 74 + 76: ✕ while a dictation records or transcribes cancels it for good. Whatever it would
 * still have said, the desktop's final, the phone's fallback, a partial, a queued recording, never
 * lands in the draft afterwards, nothing is sent, and undo brings back only the draft as it was.
 * A real ServerDictationEngine (fake desktop, scripted microphone and recognizer) drives the
 * composer's routing and the production clear ({@link DictationRouting#cancelForClear},
 * {@link ClearUndo}).
 */
public final class ClearDuringDictationTest {
    @Rule public final TemporaryFolder dir = new TemporaryFolder();

    /** The composer: routing and clear as in ComposerBar, sends and every draft change recorded. */
    private static final class Composer implements DictationRouting.Bar, ClearUndo.Effects {
        final ComposerDrafts drafts = new ComposerDrafts();
        final DictationRouting routing = new DictationRouting(drafts, this);
        final ClearUndo clearing = new ClearUndo(this);
        final List<String> sent = Collections.synchronizedList(new ArrayList<>());
        final List<String> shown = Collections.synchronizedList(new ArrayList<>());
        final DictationEngine engine;
        volatile DictationEngine.State state = DictationEngine.State.IDLE;

        Composer(DictationEngine engine) {
            this.engine = engine;
        }

        boolean dictating() {
            return state != DictationEngine.State.IDLE && state != DictationEngine.State.PENDING;
        }

        // ---- DictationRouting.Bar ----
        @Override public void showText(String text) {
            shown.add(text);
        }

        @Override public void setDictationState(DictationEngine.State s) {
            state = s;
        }

        @Override public void enter() {
            if (DictationRouting.onEnter(state) != DictationRouting.Enter.SEND) {
                routing.sendWhenDone();
                return;
            }
            String outgoing = drafts.outgoing();
            sent.add(outgoing == null ? "enterKey" : outgoing);
            if (outgoing != null) drafts.sent();
        }

        @Override public void notice(String text) { }

        @Override public void appendDictation(String pane, String text) {
            drafts.append(pane, text);
        }

        @Override public void clipLength(String clipId, long durationMs) { }

        @Override public void transcribedOnPhone() { }

        // ---- ClearUndo.Effects, as ComposerBar's ----
        @Override public boolean cancelDictation() {
            return routing.cancelForClear(engine, dictating());
        }

        @Override public ComposerDrafts.Draft draft() { return drafts.draft(); }

        @Override public String connectionRevision() { return drafts.connectionRevision(); }

        @Override public void setDraft(ComposerDrafts.Draft draft) { drafts.setDraft(draft); }

        @Override public void restoreDraft(ComposerDrafts.Draft draft) { drafts.restoreDraft(draft); }

        @Override public List<String> clearable() { return Collections.emptyList(); }

        @Override public String messageText(String id) { return null; }

        @Override public List<String> looseClips() { return Collections.emptyList(); }

        @Override public boolean holdClips(List<String> ids) { return true; }

        @Override public boolean releaseClips(List<String> ids) { return true; }

        @Override public boolean clearMessages(List<String> ids, long until) { return true; }

        @Override public boolean unclearMessages(List<String> ids) { return true; }

        @Override public void purgeMessages(List<String> ids) { }

        @Override public boolean persist(String record) { return true; }

        @Override public boolean outboxLoaded() { return true; }

        @Override public List<Outbox.Cleared> clearedMessages(long now) { return Collections.emptyList(); }

        @Override public List<String> liveTexts() { return Collections.emptyList(); }

        @Override public void retryLater() { }

        @Override public void failed(String why) { }
    }

    private enum ClearWhen { RECORDING, TRANSCRIBING }

    /** Dictates "note:" + speech on pane A, taps ✕ in the state under test, waits, checks. */
    private void clearAndCheck(Behaviour desktop, FakeRecognizer phone, ClearWhen when) throws Exception {
        try (DictationFakes.Rig rig = new DictationFakes.Rig(dir.newFolder(), desktop, phone)) {
            Composer c = new Composer(rig.engine);
            rig.onMain(() -> {
                c.drafts.setHost("h");
                c.drafts.switchTo("A");
                c.drafts.edit("note:");
                c.drafts.dictationStarted();
                rig.engine.start("A", c.routing.listener("A"));
            });
            Thread.sleep(400);
            if (when == ClearWhen.TRANSCRIBING) rig.onMain(rig.engine::stop);
            boolean cleared = false;
            for (int i = 0; i < 1000 && !cleared; i++) {
                cleared = rig.onMain(() -> {
                    boolean ready = when == ClearWhen.RECORDING ? DictationRouting.recording(c.state)
                            : c.state == DictationEngine.State.TRANSCRIBING;
                    if (ready) c.clearing.tap(c.drafts.owner(), true, 0);
                    return ready;
                });
                if (!cleared) Thread.sleep(2);
            }
            assertTrue("✕ tapped while " + when, cleared);
            // Long enough for the desktop's final, the phone's, or a queued clip to have landed.
            rig.settle(5000);
            String draft = rig.onMain(c.drafts::text);
            assertEquals("nothing lands after the clear", "", draft);
            assertEquals(DictationEngine.State.IDLE, c.state);
            assertEquals(Collections.emptyList(), new ArrayList<>(c.sent));
            assertEquals("no recording waits for the desktop", 0, rig.waitingClips());
            for (String s : new ArrayList<>(c.shown)) {
                assertFalse("never shown: " + s, s.contains(DictationFakes.DESKTOP_WORDS)
                        || s.contains(DictationFakes.PHONE_WORDS));
            }
            // Undo: the draft as it was, without the cancelled words.
            assertTrue(rig.onMain(() -> c.clearing.undo(c.drafts.owner(), 1)));
            assertEquals("note:", rig.onMain(c.drafts::text));
            // A new dictation afterwards starts as usual (its listener is a new one).
            rig.onMain(() -> {
                c.drafts.dictationStarted();
                rig.engine.start("A", c.routing.listener("A"));
            });
            assertTrue(DictationRouting.recording(c.state));
            rig.onMain(rig.engine::cancel);
        }
    }

    @Test public void clearWhileRecordingOnTheDesktop() throws Exception {
        clearAndCheck(Behaviour.fast(DictationFakes.READY_GPU), null, ClearWhen.RECORDING);
    }

    @Test public void clearWhileTheDesktopTranscribes() throws Exception {
        clearAndCheck(Behaviour.fast(DictationFakes.READY_GPU).finalAfter(1500), null, ClearWhen.TRANSCRIBING);
    }

    @Test public void clearWhileThePhoneWouldFallBack() throws Exception {
        clearAndCheck(Behaviour.fast(DictationFakes.READY_GPU).finalAfter(4000),
                new FakeRecognizer(DictationFakes.PHONE_WORDS, 300), ClearWhen.TRANSCRIBING);
    }

    @Test public void clearWhileThePhoneListensAfterBackendSlow() throws Exception {
        clearAndCheck(Behaviour.fast(DictationFakes.READY_GPU).errorOnAudio(DictationFakes.ERROR_SLOW),
                new FakeRecognizer(DictationFakes.PHONE_WORDS, 300), ClearWhen.RECORDING);
    }

    @Test public void clearWhileRecordingForLater() throws Exception {
        // Desktop unreachable, no recognizer on the phone: the recording would be queued.
        clearAndCheck(null, null, ClearWhen.RECORDING);
    }

    @Test public void anAbandonedListenerSaysNothingMore() {
        Composer c = new Composer(null);
        c.drafts.switchTo("A");
        c.drafts.edit("x");
        DictationEngine.Listener old = c.routing.listener("A");
        c.routing.abandon();
        old.onPartial("late partial");
        old.onFinal("late final", true);
        old.onState(DictationEngine.State.IDLE);
        assertEquals("x", c.drafts.text());
        assertTrue(c.shown.isEmpty());
        assertEquals(DictationEngine.State.IDLE, c.state);
        // A new listener is heard.
        DictationEngine.Listener fresh = c.routing.listener("A");
        c.drafts.dictationStarted();
        fresh.onFinal("new words", false);
        assertTrue(c.drafts.text(), c.drafts.text().contains("new words"));
    }
}
