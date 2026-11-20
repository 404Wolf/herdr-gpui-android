package dev.herdr.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import dev.herdr.android.dictation.DictationEngine;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

/**
 * Send through the real routing, real drafts and the real SendHandoff, with ComposerBar.enter's own
 * decision (routing.enterFor, then the durable handoff): Stop-and-Send during recording, Send while
 * the final is awaited, plain Send and Enter must each hand off exactly the right text once.
 */
public final class StopAndSendFlowTest {
    private static final class Composer implements DictationRouting.Bar {
        final ComposerDrafts drafts = new ComposerDrafts();
        final DictationRouting routing = new DictationRouting(drafts, this);
        final List<String> held = new ArrayList<>();
        DictationEngine.State state = DictationEngine.State.IDLE;
        int stops;

        Composer() {
            drafts.setHost("hostA", "rev1", saved -> true);
            drafts.switchTo("t1");
        }

        /** ComposerBar.enter, minus the Android views. */
        @Override public void enter() {
            switch (routing.enterFor(state)) {
                case STOP_THEN_SEND:
                    routing.sendWhenDone();
                    stops++;
                    return;
                case SEND_WHEN_DONE:
                    routing.sendWhenDone();
                    return;
                default:
            }
            String outgoing = drafts.outgoing();
            if (outgoing == null) return;
            SendHandoff.Record record = new SendHandoff.Record("id-" + held.size(),
                    drafts.owner().isEmpty() ? drafts.pane() : drafts.owner(), outgoing);
            SendHandoff.Result result = SendHandoff.run(record, new SendHandoff.Steps() {
                @Override public boolean commitDraft(SendHandoff.Record r) { return true; }
                @Override public boolean holdMessage(SendHandoff.Record r) { held.add(r.text); return true; }
                @Override public boolean clearDraft(SendHandoff.Record r) { drafts.sent(); return true; }
                @Override public void forget(SendHandoff.Record r) { }
                @Override public void act(SendHandoff.Record r) { }
            });
            assertEquals(SendHandoff.Result.HELD, result);
        }

        @Override public void showText(String text) { }
        @Override public void setDictationState(DictationEngine.State s) { state = s; }
        @Override public void notice(String text) { }
        @Override public void appendDictation(String pane, String text) { drafts.append(pane, text); }
        @Override public void clipLength(String clipId, long durationMs) { }
        @Override public void transcribedOnPhone() { }
    }

    @Test public void sendDuringRecordingStopsThenSendsExactlyTheFinal() {
        Composer c = new Composer();
        c.drafts.dictationStarted();
        DictationEngine.Listener session = c.routing.listener("t1");
        c.state = DictationEngine.State.LISTENING;
        session.onPartial("fixture partial");
        c.enter();                       // Send tapped while recording
        assertEquals("the recording is stopped", 1, c.stops);
        assertTrue("nothing is sent before the final", c.held.isEmpty());
        c.state = DictationEngine.State.TRANSCRIBING;
        session.onFinal("fixture final words", false);
        assertEquals(java.util.Arrays.asList("fixture final words"), c.held);
        assertEquals("the draft is cleared by the handoff", "", c.drafts.text());
    }

    @Test public void sendWhileTheFinalIsAwaitedSendsOnceWhenItArrives() {
        Composer c = new Composer();
        c.drafts.dictationStarted();
        DictationEngine.Listener session = c.routing.listener("t1");
        c.state = DictationEngine.State.TRANSCRIBING;
        session.onPartial("fixture partial");
        c.enter();
        assertTrue(c.held.isEmpty());
        session.onFinal("fixture final words", false);
        assertEquals(java.util.Arrays.asList("fixture final words"), c.held);
    }

    @Test public void plainSendAndEnterHandOffTheDraftOnce() {
        Composer c = new Composer();
        c.drafts.edit("hello");
        c.enter();
        assertEquals(java.util.Arrays.asList("hello"), c.held);
        c.drafts.edit("again");
        c.enter();
        assertEquals(java.util.Arrays.asList("hello", "again"), c.held);
    }

    @Test public void aSendAfterANewSessionStartedOnTheSamePaneStillSendsItsOwnFinal() {
        Composer c = new Composer();
        // An earlier session was cancelled, then a new one started and Send is tapped in it.
        c.routing.listener("t1");
        c.routing.abandon();
        c.drafts.dictationStarted();
        DictationEngine.Listener session = c.routing.listener("t1");
        c.state = DictationEngine.State.LISTENING;
        session.onPartial("fixture partial");
        c.enter();
        assertEquals(1, c.stops);
        c.state = DictationEngine.State.TRANSCRIBING;
        session.onFinal("fixture final words", false);
        assertEquals(java.util.Arrays.asList("fixture final words"), c.held);
    }
}
