package dev.herdr.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** A dictation waiting for the desktop: its place in a draft or a queued message, then its text. */
public final class DictationTokenTest {
    @Rule public final TemporaryFolder dir = new TemporaryFolder();

    private static final String ID = "0b1c2d3e-aaaa-bbbb-cccc-000000000001";
    private static final String OTHER = "0b1c2d3e-aaaa-bbbb-cccc-000000000002";

    @Test public void fillPutsTheWordsWhereTheTokenWas() {
        String t = DictationToken.of(ID);
        assertTrue(DictationToken.in("fix " + t + " please"));
        assertFalse(DictationToken.in("fix ⟦ nothing"));
        assertEquals(List.of(ID, OTHER), DictationToken.ids(t + " and " + DictationToken.of(OTHER)));
        assertEquals("fix the bug please", DictationToken.fill("fix " + t + " please", ID, " the bug "));
        assertEquals("fix the bug.", DictationToken.fill("fix " + t + ".", ID, "the bug"));
        assertEquals("fix the bug", DictationToken.fill("fix" + t, ID, "the bug"));
        assertEquals("fix please", DictationToken.fill("fix " + t + " please", ID, ""));
        assertEquals("", DictationToken.fill(t, ID, ""));
        assertNull(DictationToken.fill("fix " + t, OTHER, "x"));
    }

    @Test public void aPendingDictationHoldsItsPlaceInTheDraft() {
        ComposerDrafts drafts = new ComposerDrafts();
        drafts.switchTo("a");
        drafts.edit("Look at");
        drafts.dictationStarted();
        drafts.dictationPartial("the live words");
        drafts.dictationPartial(""); // the desktop dropped: the partial is withdrawn
        assertEquals("Look at " + DictationToken.of(ID), drafts.dictationPending(ID));
        assertFalse(drafts.dictating());
        drafts.edit(drafts.text() + " and tell me");
        drafts.switchTo("b");
        assertEquals("a", drafts.placeDictation(ID, "the failing test"));
        assertEquals("Look at the failing test and tell me", drafts.switchTo("a"));
        assertNull("placed once", drafts.placeDictation(ID, "again"));
    }

    @Test public void aDictationLandingMidUtteranceSurvivesTheNextPartial() {
        ComposerDrafts drafts = new ComposerDrafts();
        drafts.switchTo("a");
        drafts.dictationStarted();
        drafts.dictationPending(ID);
        drafts.dictationStarted();
        drafts.dictationPartial("second");
        assertEquals("a", drafts.placeDictation(ID, "first"));
        assertEquals("first second part", drafts.dictationPartial("second part"));
    }

    @Test public void aDeletedTokenAppendsToItsPane() {
        ComposerDrafts drafts = new ComposerDrafts();
        drafts.switchTo("a");
        drafts.edit("kept");
        assertNull(drafts.placeDictation(ID, "late words"));
        assertEquals("kept late words", drafts.append("a", "late words"));
        assertEquals("only", drafts.append("b", "only"));
    }

    private Outbox outbox() {
        Outbox outbox = new Outbox(new File(dir.getRoot(), Outbox.FILE_NAME));
        outbox.load();
        outbox.ready("h", "boot", new HashSet<>(Arrays.asList("p", "q")));
        return outbox;
    }

    @Test public void aQueuedMessageWaitsForItsDictationAndHoldsItsPaneBack() {
        Outbox outbox = outbox();
        Outbox.Message waiting = outbox.add("h", "p", "please " + DictationToken.of(ID), 0);
        outbox.add("h", "p", "after it", 0);
        outbox.add("h", "q", "other pane", 0);
        assertTrue(outbox.shown("h", "p", 0).get(0).awaitsDictation);
        Outbox.Message next = outbox.next("h", 0);
        assertEquals("other pane", next.text);
        outbox.sending(next.id);
        outbox.settle(next.id, Outbox.Outcome.SENT, 0);
        assertNull("p waits for its dictation", outbox.next("h", 0));
        assertFalse(outbox.fillDictation(OTHER, "x"));
        assertTrue(outbox.fillDictation(ID, "run the tests"));
        next = outbox.next("h", 0);
        assertEquals(waiting.id, next.id);
        assertEquals("please run the tests", next.text);
        assertFalse(next.awaitsDictation);
    }

    @Test public void aFilledMessageSurvivesARestartAndAnEmptyOneIsDropped() {
        Outbox outbox = outbox();
        outbox.add("h", "p", DictationToken.of(ID), 0);
        outbox.add("h", "p", "a " + DictationToken.of(OTHER), 0);
        assertTrue(outbox.fillDictation(ID, "  "));
        assertTrue(outbox.fillDictation(OTHER, "b"));
        assertTrue(outbox.save());
        Outbox again = new Outbox(new File(dir.getRoot(), Outbox.FILE_NAME));
        again.load();
        assertEquals(1, again.size());
        again.ready("h", "boot", new HashSet<>(Arrays.asList("p")));
        assertEquals("a b", again.next("h", 0).text);
    }
}
