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
 * Wolf's bug on the old box (keyboard dictation): send while still transcribing sent the
 * partial, then the whole transcript came back into the box. On the native Talk path, send
 * while recording or transcribing waits for the final, sends exactly once (draft + final, never
 * a partial), and leaves the draft empty, also when the phone's recognizer gives the text (a
 * slow desktop, backend_slow, a desktop on its CPU) and when the recording is queued.
 *
 * <p>A real ServerDictationEngine (fake desktop, scripted microphone and recognizer) drives the
 * composer's routing ({@link DictationRouting}) and a composer whose Enter is ComposerBar's.
 */
public final class SendWhileTranscribingTest {
    @Rule public final TemporaryFolder dir = new TemporaryFolder();

    /** The composer around the routing: Enter as in ComposerBar.enter, sends recorded. */
    private static final class Composer implements DictationRouting.Bar {
        final ComposerDrafts drafts = new ComposerDrafts();
        final DictationRouting routing = new DictationRouting(drafts, this);
        final List<String> sent = Collections.synchronizedList(new ArrayList<>());
        final DictationEngine engine;
        volatile DictationEngine.State state = DictationEngine.State.IDLE;

        Composer(DictationEngine engine) {
            this.engine = engine;
        }

        static boolean recording(DictationEngine.State s) {
            return s == DictationEngine.State.CONNECTING || s == DictationEngine.State.LISTENING
                    || s == DictationEngine.State.ON_DEVICE || s == DictationEngine.State.OFFLINE_RECORDING;
        }

        /** ComposerBar.enter, minus the keyboard's field. */
        @Override public void enter() {
            if (recording(state)) {
                routing.sendWhenDone();
                engine.stop();
                return;
            }
            if (state == DictationEngine.State.TRANSCRIBING) {
                routing.sendWhenDone();
                return;
            }
            String outgoing = drafts.outgoing();
            if (outgoing == null) return;
            sent.add(drafts.pane() + ":" + outgoing);
            drafts.sent();
        }

        @Override public void showText(String text) { }

        @Override public void setDictationState(DictationEngine.State s) {
            state = s;
        }

        @Override public void notice(String text) { }

        @Override public void appendDictation(String pane, String text) {
            drafts.append(pane, text);
        }

        @Override public void clipLength(String clipId, long durationMs) { }

        @Override public void transcribedOnPhone() { }
    }

    /** When Enter is pressed. */
    private enum EnterWhen { RECORDING, TRANSCRIBING }

    /**
     * Dictates "note:" + speech on pane A, presses Enter, and checks the one send. Returns the
     * message sent.
     */
    private String dictateAndSend(Behaviour desktop, FakeRecognizer phone, EnterWhen when) throws Exception {
        try (DictationFakes.Rig rig = new DictationFakes.Rig(dir.newFolder(), desktop, phone)) {
            Composer c = new Composer(rig.engine);
            rig.onMain(() -> {
                c.drafts.switchTo("A");
                c.drafts.edit("note:");
                c.drafts.dictationStarted();
                rig.engine.start("A", c.routing.listener("A"));
            });
            Thread.sleep(400);
            if (when == EnterWhen.RECORDING) {
                rig.onMain(c::enter);
            } else {
                rig.onMain(rig.engine::stop); // the talk control's tap
                rig.onMain(c::enter);
            }
            // Until the session ends, then long enough for anything late to land.
            for (int i = 0; i < 150 && c.state != DictationEngine.State.IDLE
                    && c.state != DictationEngine.State.PENDING; i++) {
                Thread.sleep(100);
            }
            rig.settle(1500);
            List<String> sent = rig.onMain(() -> new ArrayList<>(c.sent));
            String draft = rig.onMain(c.drafts::text);
            assertEquals("exactly one send: " + sent, 1, sent.size());
            assertEquals("the draft is empty after the send", "", draft);
            assertFalse("routing holds no Enter", rig.onMain(c.routing::sendPending));
            assertTrue(sent.get(0), sent.get(0).startsWith("A:note:"));
            return sent.get(0);
        }
    }

    private static void assertSentWords(String sent, String words) {
        assertTrue(sent, sent.endsWith(words));
        assertFalse("never the partial alone: " + sent, sent.equals("A:note: words"));
    }

    @Test public void desktopFinalEnterWhileRecording() throws Exception {
        String sent = dictateAndSend(Behaviour.fast(DictationFakes.READY_GPU),
                new FakeRecognizer(DictationFakes.PHONE_WORDS, 50), EnterWhen.RECORDING);
        assertSentWords(sent, DictationFakes.DESKTOP_WORDS);
    }

    @Test public void slowDesktopFinalEnterWhileTranscribing() throws Exception {
        // The desktop answers after 1.2 s; with no phone recognizer the engine waits for it.
        String sent = dictateAndSend(Behaviour.fast(DictationFakes.READY_GPU).finalAfter(1200),
                null, EnterWhen.TRANSCRIBING);
        assertSentWords(sent, DictationFakes.DESKTOP_WORDS);
    }

    @Test public void onDeviceFallbackAfterTheBudgetEnterWhileTranscribing() throws Exception {
        String sent = dictateAndSend(Behaviour.fast(DictationFakes.READY_GPU).finalAfter(4000),
                new FakeRecognizer(DictationFakes.PHONE_WORDS, 100), EnterWhen.TRANSCRIBING);
        assertSentWords(sent, DictationFakes.PHONE_WORDS);
    }

    @Test public void onDeviceFallbackAfterTheBudgetEnterWhileRecording() throws Exception {
        String sent = dictateAndSend(Behaviour.fast(DictationFakes.READY_GPU).finalAfter(4000),
                new FakeRecognizer(DictationFakes.PHONE_WORDS, 100), EnterWhen.RECORDING);
        assertSentWords(sent, DictationFakes.PHONE_WORDS);
    }

    @Test public void backendSlowEnterWhileRecording() throws Exception {
        String sent = dictateAndSend(Behaviour.fast(DictationFakes.READY_GPU).errorOnAudio(DictationFakes.ERROR_SLOW),
                new FakeRecognizer(DictationFakes.PHONE_WORDS, 100), EnterWhen.RECORDING);
        assertSentWords(sent, DictationFakes.PHONE_WORDS);
    }

    @Test public void backendSlowEnterWhileTranscribing() throws Exception {
        String sent = dictateAndSend(Behaviour.fast(DictationFakes.READY_GPU).errorOnAudio(DictationFakes.ERROR_SLOW),
                new FakeRecognizer(DictationFakes.PHONE_WORDS, 600), EnterWhen.TRANSCRIBING);
        assertSentWords(sent, DictationFakes.PHONE_WORDS);
    }

    @Test public void desktopOnItsCpuEnterWhileRecording() throws Exception {
        String sent = dictateAndSend(Behaviour.fast(DictationFakes.READY_CPU).finalAfter(4000),
                new FakeRecognizer(DictationFakes.PHONE_WORDS, 100), EnterWhen.RECORDING);
        assertSentWords(sent, DictationFakes.PHONE_WORDS);
    }

    @Test public void queuedRecordingIsSentOnceAsItsChip() throws Exception {
        // Desktop unreachable and no phone recognizer: the message goes with the clip's chip.
        String sent = dictateAndSend(null, null, EnterWhen.RECORDING);
        assertTrue(sent, sent.contains("⟦dictation "));
    }
}
