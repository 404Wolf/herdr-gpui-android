package dev.herdr.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** The PR #52 round-2 review's findings (and its #48 merge regression), each as the failure it found. */
public final class Review52r2Test {
    @Rule public final TemporaryFolder dir = new TemporaryFolder();

    private static final MachineKey A = HeldFixtures.machine("A");

    private File file() {
        return new File(dir.getRoot(), Outbox.FILE_NAME);
    }

    private static Outbox.Target target(String session, String pane) {
        return new Outbox.Target(A, "p", "computer p", session, pane, 7L, "", "");
    }

    private OutboxCourier pausedCourier(List<Runnable> later) {
        return new OutboxCourier(file(), later::add, (h, a, t) -> {
            throw new AssertionError("nothing is typed");
        });
    }

    // ---- 1: a cold Send at capacity is refused, never reported held and then lost ----

    @Test public void p2_1_aColdSendIntoAFullQueueIsRefusedAndTheQueueKept() {
        Outbox full = new Outbox(file());
        full.load();
        for (int i = 0; i < Outbox.MAX_MESSAGES; i++) {
            assertNotNull(full.add("old" + i, target("s", "t" + i), "held " + i, HeldFixtures.at(i)));
        }
        assertTrue(full.save());
        List<Runnable> later = new ArrayList<>();
        OutboxCourier courier = pausedCourier(later);
        assertNull("the queue is full: the draft stays", courier.add("new", target("s", "t1"), "one more", HeldFixtures.at(99)));
        Outbox after = new Outbox(file());
        after.load();
        assertEquals(Outbox.MAX_MESSAGES, after.size());
        assertNotNull(after.message("old" + (Outbox.MAX_MESSAGES - 1)));
    }

    @Test public void p2_1_aColdSendNeverTakesTheLastMigrationSlot() throws Exception {
        JSONArray list = new JSONArray();
        for (int i = 0; i < Outbox.MAX_MESSAGES; i++) {
            list.put(new JSONObject().put("id", "v1-" + i).put("host", "p").put("pane", "t1").put("text", "legacy " + i)
                    .put("created", i).put("state", "QUEUED").put("clearedUntil", 0));
        }
        Files.write(new File(dir.getRoot(), Outbox.V1_FILE_NAME).toPath(),
                new JSONObject().put("version", 1).put("messages", list).toString().getBytes(StandardCharsets.UTF_8));
        OutboxCourier courier = pausedCourier(new ArrayList<>());
        assertNull(courier.add("new", target("s", "t1"), "one more", HeldFixtures.at(99)));
        Outbox after = new Outbox(file());
        after.load();
        assertNotNull("the last legacy message is still in the queue", after.message("v1-" + (Outbox.MAX_MESSAGES - 1)));
    }

    // ---- 2: a submission once held is known to have been held, after its receipt is gone ----

    @Test public void p2_2_aSentMessageIsNeverOfferedAgainAfterItsReceiptExpires() {
        Outbox outbox = new Outbox(file());
        outbox.load();
        outbox.add("sub", target("s", "t1"), "go", HeldFixtures.at(1));
        assertTrue(outbox.claim("sub", new Outbox.Live(A, "p", "t1", 7L), "go", 2, false));
        outbox.settle("sub", Outbox.Outcome.WRITTEN, 2, true);
        assertTrue(outbox.purgeSent(2 + Outbox.SENT_KEEP_MS));
        assertTrue(outbox.save());
        Outbox after = new Outbox(file());
        after.load();
        assertNull(after.message("sub"));
        SendHandoff.Record record = new SendHandoff.Record("sub", "p\u0001t1", "go");
        assertEquals("its draft is cleared, never offered again", SendHandoff.Recovery.CLEAR_DRAFT,
                SendHandoff.reconcile(record, after::known));
        // A message typed at once (it leaves when written) is known too.
        after.add("now", target("s", "t1"), "typed now", HeldFixtures.at(3));
        assertTrue(after.claim("now", new Outbox.Live(A, "p", "t1", 7L), "typed now", 4, true));
        after.settle("now", Outbox.Outcome.WRITTEN, 4, false);
        assertTrue(after.known("now"));
        assertFalse(after.known("never-held"));
    }

    @Test public void p2_2_aSendBesideAnOfferedDraftClearsOnlyItsOwnWords() {
        ComposerDrafts before = new ComposerDrafts();
        before.setHost("p");
        before.switchTo("t1");
        before.edit("older words");
        ComposerDrafts app = new ComposerDrafts();
        app.restoreOffered(before.encode());
        app.setHost("p");
        app.switchTo("t1");
        app.edit("new words");
        // Step 1 of a Send of "new words": the drafts on disk, then the app dies after step 2.
        String disk = app.encode();
        ComposerDrafts restarted = new ComposerDrafts();
        restarted.restoreOffered(disk);
        restarted.setHost("p");
        restarted.switchTo("t1");
        assertTrue(restarted.removeSent(ComposerDrafts.key("p", "t1"), "new words"));
        assertEquals("the sent words never come back; the older ones still do", "older words",
                restarted.offered().text);
    }

    @Test public void p2_2_anOfferedDraftAndANewOneNearTheCapBothSurvive() {
        String big = "x".repeat(ComposerDrafts.MAX_SAVED_CHARS - 10);
        ComposerDrafts before = new ComposerDrafts();
        before.setHost("p");
        before.switchTo("t1");
        before.edit(big);
        ComposerDrafts app = new ComposerDrafts();
        app.restoreOffered(before.encode());
        app.setHost("p");
        app.switchTo("t1");
        app.edit("typed since");
        ComposerDrafts again = new ComposerDrafts();
        again.restoreOffered(app.encode());
        again.setHost("p");
        again.switchTo("t1");
        String offered = again.offered().text;
        assertTrue(offered.contains(big));
        assertTrue(offered.contains("typed since"));
    }

    // ---- 3: Send types into the pane and attach of the tap, captured together ----

    @Test public void p2_3_aSendTypesOnlyIntoThePaneAndAttachItWasTappedOver() {
        OfferGate.Tap tap = new OfferGate.Tap("t1", 7, 41);
        assertTrue(OfferGate.mayType(tap, "t1", 7, 41, true, false));
        assertFalse("switched to t2 while the outbox saved", OfferGate.mayType(tap, "t2", 7, 42, true, false));
        assertFalse("same pane, reattached", OfferGate.mayType(tap, "t1", 7, 42, true, false));
        assertFalse("another session", OfferGate.mayType(tap, "t1", 8, 41, true, false));
    }

    // ---- 4: held rows of one session are never offered or claimed in another ----

    @Test public void p2_4_aRowIsOfferedAndClaimedOnlyInTheSessionItWasTypedFor() {
        Outbox outbox = new Outbox(file());
        outbox.load();
        Outbox.Message row = outbox.add("m", target("offA", "t1"), "for the original", HeldFixtures.at(1));
        Outbox.Live copy = new Outbox.Live(A, "pc", "t1", 7L, "offCopy");
        assertNull(outbox.offer(copy));
        assertFalse(outbox.claim(row.id, copy, row.text, 2, false));
        Outbox.Live own = new Outbox.Live(A, "p", "t1", 7L, "offA");
        assertEquals(row.id, outbox.offer(own).id);
    }

    @Test public void p2_4_noBindingIsMadeBeforeTheHeldRowsAreRead() {
        Outbox notRead = new Outbox(file());
        assertFalse(MachineBindings.mayBind(notRead));
        notRead.load();
        assertTrue(MachineBindings.mayBind(notRead));
    }

    // ---- 5: Edit never moves text into a draft that cannot be saved whole ----

    @Test public void p2_5_editIntoANearlyFullDraftIsRefusedAndTheMessageKept() {
        List<String> steps = new ArrayList<>();
        HeldEdit.Steps nearlyFull = new HeldEdit.Steps() {
            @Override public String text(String id) { return "held words"; }
            @Override public boolean fits(String text) { steps.add("fits"); return false; }
            @Override public boolean commitDraft(String text) { steps.add("commit"); return true; }
            @Override public boolean take(String id) { steps.add("take"); return true; }
            @Override public void rollback() { steps.add("rollback"); }
        };
        assertEquals(HeldEdit.Result.TOO_LONG, HeldEdit.run("m", nearlyFull));
        assertEquals(java.util.Collections.singletonList("fits"), steps);
        assertFalse(ComposerDrafts.fits("x".repeat(ComposerDrafts.MAX_SAVED_CHARS - 3), "abcd"));
        assertTrue(ComposerDrafts.fits("short", "abcd"));
    }

    // ---- #48 merge: Restore while dictating would let Cancel cut the restored words ----

    @Test public void merge_restoreWaitsUntilTheDictationEnds() {
        ComposerDrafts before = new ComposerDrafts();
        before.setHost("p");
        before.switchTo("t1");
        before.edit("only copy of an edited message");
        ComposerDrafts app = new ComposerDrafts();
        app.restoreOffered(before.encode());
        app.setHost("p");
        app.switchTo("t1");
        app.dictationStarted();
        app.dictationPartial("spoken");
        assertNull("refused while dictating", app.acceptOffered());
        assertNotNull(app.offered());
        app.dictationCancelled();
        assertEquals("only copy of an edited message", app.acceptOffered());
    }
}
