package dev.herdr.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * The clear (✕, item 76) and its undo window: tap clears the draft and a dictation in flight;
 * a second tap or a long press, the queue too, never a message being sent; undo brings back
 * exactly what was cleared; past the window, nothing comes back.
 *
 * <p>And durably: the queue is a real {@link OutboxCourier} on disk, the drafts a real
 * {@link ComposerDrafts}, and the app can be killed between any two writes ({@link Crash}) or have
 * any write fail. After a restart nothing cleared is sent, nothing is lost, an interrupted clear or
 * undo is put right, and no chip is left on a held recording.
 */
public final class ClearUndoTest {
    private static final String T1 = ComposerDrafts.key("h", "t1");
    private static final String T2 = ComposerDrafts.key("h", "t2");
    @Rule public final TemporaryFolder dir = new TemporaryFolder();

    /** The process dies here. */
    static final class Crash extends Error { }

    /** Recordings as the store has them on disk: those held, and every write's fate. */
    static final class Clips {
        final Set<String> held = new LinkedHashSet<>();
        boolean failHold, failRelease;
    }

    /** The composer, outbox and recordings as the clear sees them. */
    private final class World implements ClearUndo.Effects {
        final File file;
        final OutboxCourier courier;
        final ComposerDrafts drafts = new ComposerDrafts();
        final Clips clips;
        final List<String> loose = new ArrayList<>();
        final List<String> notices = new ArrayList<>();
        boolean dictating;
        int cancelled;
        /** What the last successful {@link #persist} wrote: the drafts and the undo record. */
        String savedDrafts = "", savedRecord = "";
        boolean failPersist, failUnclear;
        /** The effect after which the process dies. */
        String crashAfter = "";
        int retries;

        World() {
            this(new File(dir.getRoot(), Outbox.FILE_NAME), new Clips());
        }

        World(File file, Clips clips) {
            this.file = file;
            this.clips = clips;
            courier = new OutboxCourier(file, Runnable::run, (handle, pane, text) -> {
                throw new AssertionError("nothing is sent here");
            });
            courier.outbox().ready("h", "boot", new java.util.HashSet<>(Arrays.asList("t1", "t2")));
            drafts.setHost("h");
            drafts.switchTo("t1");
        }

        Outbox.Message add(String text) {
            return courier.add("h", "t1", text);
        }

        List<String> queue() {
            List<String> out = new ArrayList<>();
            for (Outbox.Message m : courier.outbox().shown("h", "t1", now())) out.add(m.text);
            return out;
        }

        private void crash(String step) {
            if (crashAfter.equals(step)) throw new Crash();
        }

        @Override public boolean cancelDictation() {
            if (!dictating) return false;
            dictating = false;
            cancelled++;
            return true;
        }

        @Override public String draftText() { return drafts.text(); }

        @Override public void setDraft(String text) { drafts.edit(text); }

        @Override public void restoreDraft(String text) {
            String now = drafts.text();
            drafts.edit(now.trim().isEmpty() ? text : text + " " + now);
        }

        @Override public List<String> clearable() {
            return courier.outbox().clearable("h", drafts.pane(), now());
        }

        @Override public String messageText(String id) { return courier.outbox().text(id); }

        @Override public List<String> looseClips() { return loose; }

        @Override public boolean holdClips(List<String> ids) {
            if (clips.failHold) return false;
            clips.held.addAll(ids);
            crash("hold");
            return true;
        }

        @Override public boolean releaseClips(List<String> ids) {
            boolean any = false;
            for (String id : ids) if (clips.held.contains(id)) any = true;
            if (!any) return true;
            if (clips.failRelease) return false;
            clips.held.removeAll(ids);
            return true;
        }

        @Override public boolean clearMessages(List<String> ids, long until) {
            boolean ok = courier.clear(ids, until);
            crash("messages");
            return ok;
        }

        @Override public boolean unclearMessages(List<String> ids) {
            if (failUnclear) {
                courier.outbox().unclear(ids);
                return false;
            }
            boolean ok = courier.unclear(ids);
            crash("unclear");
            return ok;
        }

        @Override public void purgeMessages(List<String> ids) {
            courier.purgeCleared(ids);
            courier.outbox().save();
        }

        @Override public boolean persist(String record) {
            if (failPersist) return false;
            savedDrafts = drafts.encode();
            savedRecord = record;
            crash("persist:" + (record.contains("\"undone\":true") ? "undone" : record.isEmpty() ? "none" : "record"));
            return true;
        }

        @Override public boolean outboxLoaded() { return courier.outbox().loaded(); }

        @Override public List<Outbox.Cleared> clearedMessages(long now) {
            return courier.outbox().clearedOn("h", now);
        }

        @Override public List<String> liveTexts() {
            List<String> out = new ArrayList<>(drafts.all());
            out.addAll(courier.outbox().liveTexts());
            return out;
        }

        @Override public void retryLater() { retries++; }

        @Override public void failed(String why) { notices.add(why); }

        /**
         * Killed and started again, as the composer starts: the drafts and the record from what was
         * last saved, the outbox and recordings from disk, the record resumed, then the first pane.
         */
        Restarted restart() {
            World w = new World(file, clips);
            w.drafts.switchTo("");
            w.drafts.restore(savedDrafts);
            w.savedDrafts = savedDrafts;
            w.savedRecord = savedRecord;
            ClearUndo c = new ClearUndo(w);
            c.resume(savedRecord, now());
            // ComposerBar.setPane for the first pane after the start.
            String previous = w.drafts.pane();
            w.drafts.switchTo("t1");
            if (c.endsOnPaneChange(previous, T1)) c.commit();
            c.reconcile(now());
            return new Restarted(w, c);
        }
    }

    private static final class Restarted {
        final World w;
        final ClearUndo c;

        Restarted(World w, ClearUndo c) {
            this.w = w;
            this.c = c;
        }
    }

    private static long now() {
        return System.currentTimeMillis();
    }

    // ---- the gestures ----

    @Test public void aTapClearsTheDraftAndCancelsADictationInFlightAndUndoBringsTheDraftBack() {
        World w = new World();
        w.drafts.edit("half a thought");
        w.dictating = true;
        w.add("queued");
        ClearUndo clear = new ClearUndo(w);
        long t = now();
        clear.tap(T1, true, t);
        assertEquals("", w.drafts.text());
        assertEquals(1, w.cancelled);
        assertEquals("the queue is untouched by one tap", 1, w.queue().size());
        assertTrue(clear.open(t + ClearUndo.DRAFT_WINDOW_MS - 1));
        assertEquals("cleared · undo", clear.label(false));
        assertEquals("undo", clear.label(true));
        assertTrue(clear.undo(T1, t + 1000));
        assertEquals("half a thought", w.drafts.text());
        assertEquals("the cancelled dictation is not brought back", 1, w.cancelled);
        assertFalse(w.dictating);
        assertFalse(clear.open(t + 1000));
        assertEquals("", w.savedRecord);
    }

    @Test public void aSecondTapInTheWindowClearsTheQueueTooButNeverAMessageBeingSent() {
        World w = new World();
        w.drafts.edit("draft");
        Outbox.Message s = w.add("going");
        w.add("one");
        w.add("unsure");
        assertTrue(w.courier.outbox().sending(s.id));
        ClearUndo clear = new ClearUndo(w);
        long t = now();
        clear.tap(T1, true, t);
        clear.tap(T1, true, t + 2000);
        assertEquals(ClearUndo.Level.QUEUE, clear.level());
        assertEquals(Collections.singletonList("going"), w.queue());
        assertEquals("cleared 3 · undo", clear.label(false));
        // The longer window, from the second tap.
        assertTrue(clear.open(t + 2000 + ClearUndo.QUEUE_WINDOW_MS - 1));
        assertTrue(clear.undo(T1, t + 5000));
        assertEquals("draft", w.drafts.text());
        assertEquals(Arrays.asList("going", "one", "unsure"), w.queue());
    }

    @Test public void aLongPressClearsDraftAndQueueAtOnce() {
        World w = new World();
        w.drafts.edit("d");
        w.add("x");
        ClearUndo clear = new ClearUndo(w);
        clear.longPress(T1, now());
        assertEquals("", w.drafts.text());
        assertTrue(w.queue().isEmpty());
        assertEquals(ClearUndo.Level.QUEUE, clear.level());
    }

    @Test public void withNothingInTheDraftATapClearsTheQueue() {
        World w = new World();
        w.add("x");
        new ClearUndo(w).tap(T1, false, now());
        assertTrue(w.queue().isEmpty());
    }

    @Test public void recordingsWhosePlaceIsClearedAreHeldAndUndoLetsThemGo() {
        World w = new World();
        w.drafts.edit("see " + DictationToken.of("d1"));
        w.add("and " + DictationToken.of("q1"));
        w.loose.add("l1");
        w.loose.add("parked1");
        ClearUndo clear = new ClearUndo(w);
        long t = now();
        clear.tap(T1, true, t);
        assertEquals(new LinkedHashSet<>(Collections.singletonList("d1")), w.clips.held);
        clear.tap(T1, true, t + 100);
        assertEquals(new LinkedHashSet<>(Arrays.asList("d1", "q1", "l1", "parked1")), w.clips.held);
        assertTrue(clear.undo(T1, t + 200));
        assertTrue("all released", w.clips.held.isEmpty());
    }

    @Test public void pastTheWindowNothingComesBackAndHeldRecordingsStayHeld() {
        World w = new World();
        w.drafts.edit("gone for good " + DictationToken.of("d1"));
        w.add("cleared message");
        ClearUndo clear = new ClearUndo(w);
        long t = now();
        clear.longPress(T1, t);
        clear.expire(t + ClearUndo.QUEUE_WINDOW_MS);
        assertFalse(clear.open(t + ClearUndo.QUEUE_WINDOW_MS));
        assertFalse(clear.undo(T1, t + ClearUndo.QUEUE_WINDOW_MS + 1));
        assertEquals("", w.drafts.text());
        assertTrue(w.queue().isEmpty());
        assertEquals("the message went for good", 0, w.courier.outbox().waiting("h"));
        assertTrue("quarantined, as rescue audio", w.clips.held.contains("d1"));
        // A tap after the window starts over: the draft level, not the queue.
        w.drafts.edit("new");
        w.add("x");
        clear.tap(T1, true, t + ClearUndo.QUEUE_WINDOW_MS + 10);
        assertEquals(ClearUndo.Level.DRAFT, clear.level());
        assertEquals(1, w.queue().size());
    }

    @Test public void aTapOnAnotherPaneStartsItsOwnClear() {
        World w = new World();
        w.drafts.edit("on t1");
        ClearUndo clear = new ClearUndo(w);
        long t = now();
        clear.tap(T1, true, t);
        w.drafts.switchTo("t2");
        w.drafts.edit("on t2");
        w.courier.add("h", "t2", "x");
        clear.tap(T2, true, t + 100);
        assertEquals("a second tap elsewhere is not the queue's", ClearUndo.Level.DRAFT, clear.level());
        assertEquals(1, w.courier.outbox().shown("h", "t2", now()).size());
        assertTrue(clear.undo(T2, t + 200));
        assertEquals("on t2", w.drafts.text());
    }

    @Test public void aDroppedMessageComesBackWithUndoWithItsRecording() {
        World w = new World();
        Outbox.Message m = w.add("dropped " + DictationToken.of("c1"));
        ClearUndo clear = new ClearUndo(w);
        long t = now();
        assertTrue(clear.dropped(T1, m.id, null, t));
        assertTrue(w.queue().isEmpty());
        assertTrue(w.clips.held.contains("c1"));
        assertEquals("dropped · undo", clear.label(false));
        assertTrue(clear.undo(T1, t + 10));
        assertEquals(Collections.singletonList(m.text), w.queue());
        assertTrue(w.clips.held.isEmpty());
    }

    @Test public void aDroppedRecordingIsHeldAndUndoLetsItGo() {
        World w = new World();
        ClearUndo clear = new ClearUndo(w);
        long t = now();
        assertTrue(clear.dropped(T1, null, "p1", t));
        assertEquals(Collections.singleton("p1"), w.clips.held);
        assertTrue(clear.undo(T1, t + 10));
        assertTrue(w.clips.held.isEmpty());
    }

    // ---- across a restart ----

    @Test public void aClearedQueueIsStillRecoverableAfterARestartWithinTheWindow() {
        World w = new World();
        w.drafts.edit("my draft");
        w.add("first");
        w.add("second");
        new ClearUndo(w).longPress(T1, now());
        Restarted r = w.restart();
        String otherOwner = ComposerDrafts.key("other-host", "t1");
        r.w.drafts.setHost("other-host");
        r.w.drafts.edit("other host draft");
        assertFalse(r.c.open(otherOwner, now()));
        assertFalse(r.c.undo(otherOwner, now()));
        assertEquals("other host draft", r.w.drafts.text());
        r.w.drafts.setHost("h");
        assertTrue("the owning pane keeps the undo across restart", r.c.open(T1, now()));
        assertTrue("nothing cleared is shown", r.w.queue().isEmpty());
        assertNull("or sent", r.w.courier.outbox().next("h", now()));
        assertTrue(r.c.undo(T1, now()));
        assertEquals("my draft", r.w.drafts.text());
        assertEquals(Arrays.asList("first", "second"), r.w.queue());
    }

    @Test public void aSwitchToAnotherPaneStillEndsTheWindow() {
        World w = new World();
        w.drafts.edit("my draft");
        ClearUndo c = new ClearUndo(w);
        c.tap(T1, true, now());
        assertFalse(c.endsOnPaneChange("", T1));
        assertFalse(c.endsOnPaneChange(T2, T1));
        assertTrue(c.endsOnPaneChange(T1, T2));
    }

    @Test public void aRestartPastTheWindowFinishesTheClear() {
        World w = new World();
        w.drafts.edit("my draft");
        w.add("first");
        long t = now() - ClearUndo.QUEUE_WINDOW_MS - 1;
        new ClearUndo(w).longPress(T1, t);
        Restarted r = w.restart();
        assertFalse(r.c.open(now()));
        assertEquals("", r.w.drafts.text());
        assertTrue(r.w.queue().isEmpty());
        assertEquals(0, r.w.courier.outbox().waiting("h"));
        assertEquals("", r.w.savedRecord);
    }

    // ---- a message picked to be sent before the clear (finding 2) ----

    @Test public void aMessageTheSenderPickedBeforeTheClearIsNotSentAfterIt() {
        World w = new World();
        Outbox.Message m = w.add("must not send");
        Outbox.Message picked = w.courier.outbox().next("h", now());
        assertEquals(m.id, picked.id);
        new ClearUndo(w).tap(T1, false, now());
        assertNull(w.courier.outbox().next("h", now()));
        assertFalse("the sender's pick is refused at dispatch", w.courier.outbox().sending(picked.id));
    }

    // ---- killed in the middle (finding 3) ----

    @Test public void killedAfterTheQueueWasClearedBeforeItsRecordTheUndoStillBringsItBack() {
        World w = new World();
        w.drafts.edit("draft");
        w.add("queued " + DictationToken.of("q1"));
        ClearUndo c = new ClearUndo(w);
        long t = now();
        c.tap(T1, true, t);
        w.crashAfter = "messages";
        try {
            c.tap(T1, true, t + 100);
            fail("killed");
        } catch (Crash expected) {
        }
        Restarted r = w.restart();
        assertTrue("the cleared message is not shown", r.w.queue().isEmpty());
        assertNull("or sent", r.w.courier.outbox().next("h", now()));
        assertTrue("its recording stays held", r.w.clips.held.contains("q1"));
        assertTrue("the undo is on offer", r.c.open(now()));
        assertTrue(r.c.undo(T1, now()));
        assertEquals(Collections.singletonList("queued " + DictationToken.of("q1")), r.w.queue());
        assertEquals("draft", r.w.drafts.text());
        assertTrue("its recording waits again", r.w.clips.held.isEmpty());
    }

    @Test public void killedAfterTheHoldsBeforeAnythingElseNothingIsClearedAndNoChipIsStranded() {
        World w = new World();
        w.drafts.edit("see " + DictationToken.of("d1"));
        // The composer saved the draft as it was typed.
        w.savedDrafts = w.drafts.encode();
        w.add("and " + DictationToken.of("q1"));
        w.crashAfter = "hold";
        try {
            new ClearUndo(w).longPress(T1, now());
            fail("killed");
        } catch (Crash expected) {
        }
        assertTrue(w.clips.held.contains("d1"));
        Restarted r = w.restart();
        assertEquals("see " + DictationToken.of("d1"), r.w.drafts.text());
        assertEquals(1, r.w.queue().size());
        assertTrue("the draft's chip has its recording back", r.w.clips.held.isEmpty());
        assertFalse(r.c.open(now()));
    }

    @Test public void killedInTheQueueStepAfterItsHoldsTheQueueStaysAndItsChipsAreFree() {
        World w = new World();
        w.drafts.edit("draft");
        w.add("and " + DictationToken.of("q1"));
        ClearUndo c = new ClearUndo(w);
        long t = now();
        c.tap(T1, true, t);
        w.crashAfter = "hold";
        try {
            c.tap(T1, true, t + 100);
            fail("killed");
        } catch (Crash expected) {
        }
        Restarted r = w.restart();
        assertEquals("the queue was never cleared", 1, r.w.queue().size());
        assertTrue(r.w.clips.held.isEmpty());
        // The draft's clear, saved before, is still undoable.
        assertTrue(r.c.open(now()));
        assertTrue(r.c.undo(T1, now()));
        assertEquals("draft", r.w.drafts.text());
    }

    @Test public void killedWhileDroppingALooseRecordingItWaitsAgainAfterTheRestart() {
        World w = new World();
        w.crashAfter = "hold";
        try {
            new ClearUndo(w).dropped(T1, null, "loose1", now());
            fail("killed");
        } catch (Crash expected) {
        }
        assertTrue(w.clips.held.contains("loose1"));
        assertTrue("its intent was saved first", w.savedRecord.contains("loose1"));
        Restarted r = w.restart();
        assertFalse("given back: no chip would have said so", r.w.clips.held.contains("loose1"));
        assertFalse(r.c.open(now()));
    }

    @Test public void anUndoAcceptedBeforeARestartPastTheWindowStillBringsTheMessagesBack() {
        World w = new World();
        w.drafts.edit("draft");
        w.add("queued");
        long t = now() - ClearUndo.QUEUE_WINDOW_MS - 1000;
        ClearUndo c = new ClearUndo(w);
        c.longPress(T1, t);
        w.crashAfter = "persist:undone";
        try {
            c.undo(T1, t + 100);
            fail("killed");
        } catch (Crash expected) {
        }
        Restarted r = w.restart();
        assertEquals("draft", r.w.drafts.text());
        assertEquals(Collections.singletonList("queued"), r.w.queue());
    }

    @Test public void killedInTheMiddleOfAnUndoTheRestartFinishesIt() {
        World w = new World();
        w.drafts.edit("draft " + DictationToken.of("d1"));
        w.add("queued " + DictationToken.of("q1"));
        ClearUndo c = new ClearUndo(w);
        long t = now();
        c.longPress(T1, t);
        w.crashAfter = "persist:undone";
        try {
            c.undo(T1, t + 100);
            fail("killed");
        } catch (Crash expected) {
        }
        Restarted r = w.restart();
        assertEquals("draft " + DictationToken.of("d1"), r.w.drafts.text());
        assertEquals(Collections.singletonList("queued " + DictationToken.of("q1")), r.w.queue());
        assertTrue(r.w.clips.held.isEmpty());
        assertFalse(r.c.restoring());
        assertEquals("", r.w.savedRecord);
    }

    // ---- writes that fail (findings 4, 5) ----

    @Test public void anUndoWhoseDraftCannotBeSavedFailsSaysSoAndStaysOnOffer() {
        World w = new World();
        w.drafts.edit("draft");
        ClearUndo c = new ClearUndo(w);
        long t = now();
        c.tap(T1, true, t);
        w.failPersist = true;
        assertFalse(c.undo(T1, t + 100));
        assertTrue("still on offer", c.open(t + 100));
        assertEquals("", w.drafts.text());
        assertEquals(1, w.notices.size());
        w.failPersist = false;
        assertTrue(c.undo(T1, t + 200));
        assertEquals("draft", w.drafts.text());
    }

    @Test public void anUndoWhoseMessagesCannotBeSavedKeepsItsRecordUntilTheyAre() {
        World w = new World();
        w.add("queued");
        ClearUndo c = new ClearUndo(w);
        long t = now();
        c.tap(T1, false, t);
        w.failUnclear = true;
        assertTrue("the draft is saved: the undo is done", c.undo(T1, t + 100));
        assertEquals("back at once", 1, w.queue().size());
        assertTrue("its record stays", c.restoring());
        assertTrue(w.retries > 0);
        // Killed before the retry: the restart finishes it.
        Restarted r = w.restart();
        assertEquals(1, r.w.queue().size());
        assertFalse(r.c.restoring());
        assertEquals("", r.w.savedRecord);
    }

    @Test public void anUndoWhoseRecordingsCannotBeReleasedRetriesThem() {
        World w = new World();
        w.drafts.edit("see " + DictationToken.of("d1"));
        ClearUndo c = new ClearUndo(w);
        long t = now();
        c.tap(T1, true, t);
        w.clips.failRelease = true;
        assertTrue(c.undo(T1, t + 100));
        assertTrue(w.clips.held.contains("d1"));
        assertTrue(c.restoring());
        // A new clear waits for it.
        c.tap(T1, true, t + 200);
        assertEquals("see " + DictationToken.of("d1"), w.drafts.text());
        assertFalse(w.notices.isEmpty());
        w.clips.failRelease = false;
        c.reconcile(t + 300);
        assertTrue(w.clips.held.isEmpty());
        assertFalse(c.restoring());
    }

    @Test public void aHoldThatCannotBeSavedLeavesTheDraftAsItWasAndSaysSo() {
        World w = new World();
        w.drafts.edit("see " + DictationToken.of("d1"));
        w.clips.failHold = true;
        ClearUndo clear = new ClearUndo(w);
        clear.tap(T1, true, now());
        assertEquals("nothing cleared", "see " + DictationToken.of("d1"), w.drafts.text());
        assertTrue(w.clips.held.isEmpty());
        assertFalse(clear.open(now()));
        assertEquals(1, w.notices.size());
    }

    @Test public void aQueueHoldThatCannotBeSavedLeavesTheQueueInLine() {
        World w = new World();
        w.add("and " + DictationToken.of("q1"));
        w.clips.failHold = true;
        new ClearUndo(w).longPress(T1, now());
        assertEquals(1, w.queue().size());
        assertTrue(w.clips.held.isEmpty());
        assertFalse(w.notices.isEmpty());
    }

    @Test public void aRecordThatCannotBeSavedUndoesTheWholeClearAndNoChipIsStranded() {
        World w = new World();
        w.drafts.edit("d " + DictationToken.of("d1"));
        w.add("m " + DictationToken.of("q1"));
        w.failPersist = true;
        w.clips.failRelease = true;
        ClearUndo clear = new ClearUndo(w);
        clear.longPress(T1, now());
        assertEquals("d " + DictationToken.of("d1"), w.drafts.text());
        assertEquals(1, w.queue().size());
        assertFalse(clear.open(now()));
        assertTrue("its release is retried", w.retries > 0);
        // Storage back: the next reconcile lets the draft's recording go.
        w.failPersist = false;
        w.clips.failRelease = false;
        clear.reconcile(now());
        assertTrue(w.clips.held.isEmpty());
    }
}
