package dev.herdr.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** The PR #52 review's six P2 findings and its draft P3, each as the failure it found. */
public final class Review52Test {
    @Rule public final TemporaryFolder dir = new TemporaryFolder();

    private static final MachineKey A = HeldFixtures.machine("A");
    private static final MachineKey B = HeldFixtures.machine("B");

    private File file() {
        return new File(dir.getRoot(), Outbox.FILE_NAME);
    }

    private static Outbox.Target target(MachineKey machine, String profile, String session, String pane) {
        return new Outbox.Target(machine, profile, "computer " + profile, session, pane, 7L, "", "");
    }

    // ---- 1: the first Send must never overwrite a queue the startup load has not read yet ----

    @Test public void p2_1_aSendBeforeTheStartupLoadKeepsTheQueueAlreadyOnDisk() {
        Outbox before = new Outbox(file());
        before.load();
        assertNotNull(before.add("old", target(A, "p", "s", "t1"), "held earlier", HeldFixtures.at(1)));
        assertTrue(before.save());
        // The courier's loader has not run yet: its executor holds it.
        List<Runnable> later = new ArrayList<>();
        OutboxCourier courier = new OutboxCourier(file(), later::add, (h, a, t) -> {
            throw new AssertionError("nothing is typed");
        });
        assertNotNull(courier.add("new", target(A, "p", "s", "t1"), "typed now", HeldFixtures.at(2)));
        for (Runnable r : later) r.run();
        Outbox after = new Outbox(file());
        after.load();
        assertNotNull("the queue already on disk survives the first Send", after.message("old"));
        assertNotNull(after.message("new"));
        assertEquals(2, after.size());
        assertEquals("the old one stays first in its lane", "old",
                after.offer(new Outbox.Live(A, "p", "t1", 7L)).id);
    }

    // ---- 2: Clear works on the rows the pane shows, never on another machine's ----

    @Test public void p2_2_clearOnARepointedProfileLeavesThePreviousMachinesRows() {
        Outbox outbox = new Outbox(file());
        outbox.load();
        Outbox.Message onA = outbox.add("a", target(A, "p", "s", "t1"), "for A", HeldFixtures.at(1));
        Outbox.Message onB = outbox.add("b", target(B, "p", "s", "t1"), "for B", HeldFixtures.at(2));
        // The profile now reaches B: its pane shows B's row only, and Clear takes only that.
        assertEquals(Collections.singletonList(onB.id), outbox.clearable(B, "p", "t1", 3));
        assertEquals(Collections.singletonList(onB.id), outbox.clearAll(B, "p", "t1", 100, 3));
        assertTrue(outbox.purgeExpired(100));
        assertNotNull("A's hidden row is not cleared", outbox.message(onA.id));
        // Recovery after a restart looks at the same rows only.
        outbox.clear(Collections.singletonList(onA.id), 500);
        assertTrue(outbox.clearedOn(B, "p", 200).isEmpty());
        assertEquals(1, outbox.clearedOn(A, "p", 200).size());
    }

    // ---- 3: nothing is typed until the draft's clear is on disk ----

    @Test public void p2_3_aFailedDraftClearNeverTypesAndARestartStillFindsTheMessageHeld() {
        AtomicInteger acted = new AtomicInteger();
        Outbox outbox = new Outbox(file());
        outbox.load();
        SendHandoff.Record record = new SendHandoff.Record("sub", "h\u0001t1", "go");
        SendHandoff.Result result = SendHandoff.run(record, new SendHandoff.Steps() {
            @Override public boolean commitDraft(SendHandoff.Record r) { return true; }

            @Override public boolean holdMessage(SendHandoff.Record r) {
                return outbox.add(r.id, target(A, "h", "s", "t1"), r.text, HeldFixtures.at(1)) != null && outbox.save();
            }

            @Override public boolean clearDraft(SendHandoff.Record r) { return false; }

            @Override public void forget(SendHandoff.Record r) { }

            @Override public void act(SendHandoff.Record r) { acted.incrementAndGet(); }
        });
        assertEquals(SendHandoff.Result.HELD_DRAFT_STALE, result);
        assertEquals("not typed while the draft may still say it was not sent", 0, acted.get());
        Outbox after = new Outbox(file());
        after.load();
        assertEquals(SendHandoff.Recovery.CLEAR_DRAFT, SendHandoff.reconcile(record, id -> after.text(id) != null));
        assertEquals(Outbox.State.PENDING, after.message("sub").state);
    }

    @Test public void p2_3_aClearedDraftActsOnce() {
        AtomicInteger acted = new AtomicInteger();
        SendHandoff.Result result = SendHandoff.run(new SendHandoff.Record("s", "o", "x"), new SendHandoff.Steps() {
            @Override public boolean commitDraft(SendHandoff.Record r) { return true; }
            @Override public boolean holdMessage(SendHandoff.Record r) { return true; }
            @Override public boolean clearDraft(SendHandoff.Record r) { return true; }
            @Override public void forget(SendHandoff.Record r) { }
            @Override public void act(SendHandoff.Record r) { acted.incrementAndGet(); }
        });
        assertEquals(SendHandoff.Result.HELD, result);
        assertEquals(1, acted.get());
    }

    // ---- 4: a confirmation types only into the attach it was shown over, uncovered ----

    @Test public void p2_4_aSendShownOverOneAttachNeverTypesIntoItsReplacement() {
        assertTrue(OfferGate.mayType(41, 41, true, false));
        assertFalse("the holder died and the pane reattached", OfferGate.mayType(41, 42, true, false));
        assertFalse("the file page covers the terminal", OfferGate.mayType(41, 41, true, true));
        assertFalse("not live", OfferGate.mayType(41, 41, false, false));
        assertFalse("no attach", OfferGate.mayType(0, 0, true, false));
    }

    // ---- 5: a copied session never takes a machine whose held rows belong to another session ----

    @Test public void p2_5_evictionNeverHandsHeldRowsToACopiedSession() {
        MachineBindings bindings = new MachineBindings(new File(dir.getRoot(), MachineBindings.FILE_NAME));
        bindings.load();
        Outbox outbox = new Outbox(file());
        outbox.load();
        assertTrue(bindings.bind("orig", "r", "offA", "original", A, outbox.all()).bound());
        Outbox.Message row = outbox.add("m", target(A, "orig", "offA", "t1"), "held for the original",
                HeldFixtures.at(1));
        // 64 other revisions later the original binding has aged out of the store...
        for (int i = 0; i < MachineBindings.MAX_BINDINGS + 1; i++) {
            bindings.bind("other" + i, "r", "s" + i, "other " + i, MachineKey.of("core" + i, "key" + i), outbox.all());
        }
        MachineBindings reloaded = new MachineBindings(new File(dir.getRoot(), MachineBindings.FILE_NAME));
        reloaded.load();
        // ...but while a row is held for it, its session still owns that machine.
        assertEquals(A, reloaded.machine("orig", "r"));
        MachineBindings.Binding copy = reloaded.bind("copy", "r", "offCopy", "copied", A, outbox.all());
        assertFalse("a copied session reporting the same machine is refused", copy.bound());
        assertNull(reloaded.machine("copy", "r"));
        assertEquals(row.id, outbox.offer(new Outbox.Live(A, "orig", "t1", 7L)).id);
    }

    @Test public void p2_5_heldRowsAloneRefuseAnotherSessionEvenWithNoBindingLeft() {
        MachineBindings bindings = new MachineBindings(new File(dir.getRoot(), MachineBindings.FILE_NAME));
        bindings.load();
        List<Outbox.Message> held = Collections.singletonList(new Outbox.Message("m", target(A, "orig", "offA", "t1"),
                "x", HeldFixtures.at(1), Outbox.State.PENDING, null, false, 0, ""));
        MachineBindings.Binding copy = bindings.bind("copy", "r", "offCopy", "copied", A, held);
        assertFalse(copy.bound());
        assertEquals("computer orig", copy.refusedBy);
        assertTrue("the same session shares it", bindings.bind("orig2", "r", "offA", "again", A, held).bound());
    }

    // ---- 6: Edit as draft keeps the text on disk at every moment ----

    @Test public void p2_6_editCommitsTheDraftBeforeTheMessageLeaves() {
        List<String> steps = new ArrayList<>();
        boolean[] draftSaves = {true};
        HeldEdit.Steps fake = new HeldEdit.Steps() {
            @Override public String text(String id) { steps.add("text"); return "held words"; }
            @Override public boolean fits(String text) { return true; }
            @Override public boolean commitDraft(String text) { steps.add("commit " + text); return draftSaves[0]; }
            @Override public boolean take(String id) { steps.add("take"); return true; }
            @Override public void rollback() { steps.add("rollback"); }
        };
        assertEquals(HeldEdit.Result.EDITED, HeldEdit.run("m", fake));
        assertEquals(java.util.Arrays.asList("text", "commit held words", "take"), steps);
        steps.clear();
        draftSaves[0] = false;
        assertEquals(HeldEdit.Result.DRAFT_NOT_SAVED, HeldEdit.run("m", fake));
        assertEquals("a draft that could not be saved never takes the message",
                java.util.Arrays.asList("text", "commit held words", "rollback"), steps);
    }

    @Test public void p2_6_aMessageThatCannotBeTakenLeavesTheDraftAsItWas() {
        List<String> steps = new ArrayList<>();
        HeldEdit.Steps sending = new HeldEdit.Steps() {
            @Override public String text(String id) { return "being sent"; }
            @Override public boolean fits(String text) { return true; }
            @Override public boolean commitDraft(String text) { steps.add("commit"); return true; }
            @Override public boolean take(String id) { steps.add("take"); return false; }
            @Override public void rollback() { steps.add("rollback"); }
        };
        assertEquals(HeldEdit.Result.NOT_TAKEN, HeldEdit.run("m", sending));
        assertEquals(java.util.Arrays.asList("commit", "take", "rollback"), steps);
        assertEquals(HeldEdit.Result.GONE, HeldEdit.run("m", new HeldEdit.Steps() {
            @Override public String text(String id) { return null; }
            @Override public boolean fits(String text) { throw new AssertionError(); }
            @Override public boolean commitDraft(String text) { throw new AssertionError(); }
            @Override public boolean take(String id) { throw new AssertionError(); }
            @Override public void rollback() { throw new AssertionError(); }
        }));
    }

    // ---- P3: typing after "Later" keeps the offered draft on disk too ----

    @Test public void p3_typingAfterLaterKeepsBothDraftsAcrossAnotherRestart() {
        ComposerDrafts before = new ComposerDrafts();
        before.setHost("h");
        before.switchTo("t1");
        before.edit("from before the restart");
        ComposerDrafts after = new ComposerDrafts();
        after.restoreOffered(before.encode());
        after.setHost("h");
        after.switchTo("t1");
        after.edit("typed since");
        ComposerDrafts again = new ComposerDrafts();
        again.restoreOffered(after.encode());
        again.setHost("h");
        again.switchTo("t1");
        String kept = again.offered().text;
        assertTrue(kept, kept.contains("from before the restart") && kept.contains("typed since"));
    }
}
