package dev.herdr.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import dev.herdr.android.dictation.DictationEngine;
import org.junit.Test;

/** An Enter belongs to the session of its own pane: the new routing decision. */
public final class KbTalkRound2NewApiTest {
    @Test public void enterOnAnotherPaneNeverWaitsOnTheOldPanesSession() {
        ComposerDrafts drafts = new ComposerDrafts();
        DictationRouting routing = new DictationRouting(drafts, null);
        drafts.switchTo("A");
        routing.listener("A");
        // A is transcribing; the user is now on B.
        drafts.switchTo("B");
        assertEquals(DictationRouting.Enter.SEND, routing.enterFor(DictationEngine.State.TRANSCRIBING));
        drafts.switchTo("A");
        assertEquals(DictationRouting.Enter.SEND_WHEN_DONE, routing.enterFor(DictationEngine.State.TRANSCRIBING));
        assertEquals(DictationRouting.Enter.STOP_THEN_SEND, routing.enterFor(DictationEngine.State.LISTENING));
    }

    @Test public void theSameIdOnAnotherHostIsAnotherPane() {
        ComposerDrafts drafts = new ComposerDrafts();
        DictationRouting routing = new DictationRouting(drafts, null);
        drafts.setHost("hostA");
        drafts.switchTo("t1");
        routing.listener("t1");
        drafts.setHost("hostB");
        drafts.switchTo("t1");
        assertEquals(DictationRouting.Enter.SEND, routing.enterFor(DictationEngine.State.TRANSCRIBING));
    }
}
