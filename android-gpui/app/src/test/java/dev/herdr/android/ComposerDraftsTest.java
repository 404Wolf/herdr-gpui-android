package dev.herdr.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class ComposerDraftsTest {
    @Test
    public void draftsArePerPane() {
        ComposerDrafts drafts = new ComposerDrafts();
        drafts.switchTo("a");
        drafts.edit("hello");
        assertEquals("", drafts.switchTo("b"));
        drafts.edit("other");
        assertEquals("hello", drafts.switchTo("a"));
        assertEquals("other", drafts.switchTo("b"));
    }

    @Test
    public void oldestPaneDraftIsDroppedPastTheBound() {
        ComposerDrafts drafts = new ComposerDrafts();
        for (int i = 0; i <= ComposerDrafts.MAX_PANES; i++) {
            drafts.switchTo("p" + i);
            drafts.edit("t" + i);
        }
        assertEquals("", drafts.switchTo("p0"));
        assertEquals("t1", drafts.switchTo("p1"));
    }

    @Test
    public void partialsReplaceTheUtteranceNotAppend() {
        ComposerDrafts drafts = new ComposerDrafts();
        drafts.switchTo("a");
        drafts.edit("Fix");
        drafts.dictationStarted();
        assertTrue(drafts.dictating());
        assertEquals("Fix the", drafts.dictationPartial("the"));
        assertEquals("Fix the bug", drafts.dictationPartial("the bug"));
        assertEquals("Fix the bug now", drafts.dictationFinal(" the bug now "));
        assertFalse(drafts.dictating());
        // A second utterance builds on the committed text.
        drafts.dictationStarted();
        assertEquals("Fix the bug now please", drafts.dictationPartial("please"));
    }

    @Test
    public void thePartialStartsAfterTheSettledDraft() {
        ComposerDrafts drafts = new ComposerDrafts();
        drafts.switchTo("a");
        drafts.edit("Fix");
        assertEquals(-1, drafts.partialFrom());
        drafts.dictationStarted();
        assertEquals(-1, drafts.partialFrom());
        drafts.dictationPartial("the bug");
        assertEquals(4, drafts.partialFrom());
        assertEquals("the bug", drafts.text().substring(drafts.partialFrom()));
        drafts.dictationFinal("the bug");
        assertEquals(-1, drafts.partialFrom());
    }

    @Test
    public void anEndedUtteranceKeepsItsLastPartial() {
        ComposerDrafts drafts = new ComposerDrafts();
        drafts.switchTo("a");
        drafts.edit("keep");
        drafts.dictationStarted();
        drafts.dictationPartial("these");
        drafts.dictationPartial("these words");
        assertEquals("keep these words", drafts.dictationEnded());
        assertFalse(drafts.dictating());
        // The next utterance appends after the kept words, not over them.
        drafts.dictationStarted();
        assertEquals("keep these words too", drafts.dictationPartial("too"));
    }

    @Test
    public void switchingPanesEndsTheUtteranceBase() {
        ComposerDrafts drafts = new ComposerDrafts();
        drafts.switchTo("a");
        drafts.dictationStarted();
        drafts.switchTo("b");
        assertFalse(drafts.dictating());
    }

    @Test
    public void outgoingTrimsAndClearsOnlyWhenSent() {
        ComposerDrafts drafts = new ComposerDrafts();
        drafts.switchTo("a");
        assertNull(drafts.outgoing());
        drafts.edit("  \n");
        assertNull(drafts.outgoing());
        drafts.edit("\n two\nlines \n");
        assertEquals("two\nlines", drafts.outgoing());
        assertEquals("\n two\nlines \n", drafts.text());
        drafts.sent();
        assertEquals("", drafts.text());
    }

    @Test
    public void draftsSurviveARestartWithoutHidingWhatWasTypedSince() {
        ComposerDrafts before = new ComposerDrafts();
        before.switchTo("a");
        before.edit("kept\nacross a kill");
        before.switchTo("b");
        before.edit("old b");
        ComposerDrafts after = new ComposerDrafts();
        after.switchTo("b");
        after.edit("typed before the restore");
        after.restore(before.encode());
        assertEquals("typed before the restore", after.text());
        after.switchTo("a");
        assertEquals("kept\nacross a kill", after.text());
        ComposerDrafts damaged = new ComposerDrafts();
        damaged.restore("{not json");
        damaged.switchTo("a");
        assertEquals("", damaged.text());
    }
}
