package dev.herdr.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** E13 below the emulator (§1.3): a draft from before a restart is offered, never loaded by itself. */
public final class DraftOfferTest {
    private static ComposerDrafts on(String host, String pane) {
        ComposerDrafts drafts = new ComposerDrafts();
        drafts.clock = () -> 1_000;
        drafts.setHost(host);
        drafts.switchTo(pane);
        return drafts;
    }

    @Test public void aDraftFromBeforeARestartIsOfferedWithItsTimeAndLoadedOnlyByATap() {
        ComposerDrafts before = on("h", "t1");
        before.clock = () -> 42_000;
        before.edit("half a thought");
        String saved = before.encode();
        ComposerDrafts after = new ComposerDrafts();
        after.restoreOffered(saved);
        after.setHost("h");
        assertEquals("not loaded into the composer", "", after.switchTo("t1"));
        ComposerDrafts.Draft offered = after.offered();
        assertNotNull(offered);
        assertEquals("half a thought", offered.text);
        assertEquals(42_000, offered.at);
        // Typing meanwhile is kept; the tap puts the old words first.
        after.edit("new words");
        assertEquals("half a thought\nnew words", after.acceptOffered());
        assertNull(after.offered());
        // Offered drafts are saved until taken, so another restart still offers them.
        ComposerDrafts twice = new ComposerDrafts();
        ComposerDrafts again = new ComposerDrafts();
        again.restoreOffered(saved);
        twice.restoreOffered(again.encode());
        twice.setHost("h");
        twice.switchTo("t1");
        assertEquals("half a thought", twice.offered().text);
    }

    @Test public void anotherPanesOfferIsNotShownHere() {
        ComposerDrafts before = on("h", "t1");
        before.edit("for t1");
        ComposerDrafts after = new ComposerDrafts();
        after.restoreOffered(before.encode());
        after.setHost("h");
        after.switchTo("t2");
        assertNull(after.offered());
        after.switchTo("t1");
        assertEquals("for t1", after.offered().text);
        // A Send whose message was held before the stop clears exactly that draft.
        assertTrue(after.removeSent(ComposerDrafts.key("h", "t1"), "for t1"));
        assertNull(after.offered());
    }

    @Test public void anOlderBuildsPlainDraftStillRestores() {
        ComposerDrafts after = new ComposerDrafts();
        after.restoreOffered("{\"h\\u0001t1\":\"plain\"}");
        after.setHost("h");
        after.switchTo("t1");
        assertEquals("plain", after.offered().text);
        assertEquals(0, after.offered().at);
    }
}
