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
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** The outbox: order, bounds, the one ambiguous moment, and what survives a restart. */
public final class OutboxTest {
    @Rule public final TemporaryFolder dir = new TemporaryFolder();

    private static Set<String> panes(String... ids) {
        return new HashSet<>(Arrays.asList(ids));
    }

    private Outbox fresh() {
        Outbox outbox = new Outbox(new File(dir.getRoot(), Outbox.FILE_NAME));
        outbox.load();
        return outbox;
    }

    private Outbox reopened() {
        return fresh();
    }

    @Test public void messagesLeaveInTheOrderTheyWereTyped() {
        Outbox outbox = fresh();
        outbox.ready("h", "b1", panes("t1"));
        outbox.add("h", "t1", "first", 1);
        outbox.add("h", "t1", "second", 2);
        List<String> sent = new ArrayList<>();
        for (Outbox.Message m; (m = outbox.next("h", 10)) != null; ) {
            assertTrue(outbox.sending(m.id));
            // Nothing else of this pane goes while one is in flight.
            assertNull(outbox.next("h", 10));
            outbox.settle(m.id, Outbox.Outcome.SENT, 10);
            sent.add(m.text);
        }
        assertEquals(Arrays.asList("first", "second"), sent);
        assertEquals(0, outbox.size());
    }

    @Test public void anUnsureSendIsNeverResentOnItsOwnAndHoldsItsPane() {
        Outbox outbox = fresh();
        outbox.ready("h", "b1", panes("t1", "t2"));
        Outbox.Message first = outbox.add("h", "t1", "may have gone", 1);
        outbox.add("h", "t1", "after it", 2);
        Outbox.Message other = outbox.add("h", "t2", "other pane", 3);
        assertTrue(outbox.sending(first.id));
        outbox.settle(first.id, Outbox.Outcome.UNSURE, 4);
        // The unsure message's pane waits; another pane's line is independent.
        Outbox.Message next = outbox.next("h", 5);
        assertEquals(other.id, next.id);
        outbox.take(other.id);
        assertNull(outbox.next("h", 5));
        assertEquals(Outbox.State.UNSURE, outbox.shown("h", "t1", 5).get(0).state);
        // Only the user's word puts it back, at the head of its line.
        assertTrue(outbox.resend(first.id));
        assertEquals(first.id, outbox.next("h", 6).id);
    }

    @Test public void aProcessKilledMidSendLeavesTheMessageUnsureNotQueued() {
        Outbox outbox = fresh();
        outbox.ready("h", "b1", panes("t1"));
        Outbox.Message m = outbox.add("h", "t1", "in flight", 1);
        outbox.sending(m.id);
        assertTrue(outbox.save());
        Outbox after = reopened();
        List<Outbox.Message> shown = after.shown("h", "t1", 2);
        assertEquals(1, shown.size());
        assertEquals(Outbox.State.UNSURE, shown.get(0).state);
        assertNull(after.next("h", 2));
    }

    @Test public void queuedMessagesAndBootsSurviveARestart() {
        Outbox outbox = fresh();
        outbox.ready("h", "b1", panes("t1"));
        outbox.add("h", "t1", "line one\nline two", 1);
        assertTrue(outbox.save());
        Outbox after = reopened();
        Outbox.Message m = after.next("h", 2);
        assertNotNull(m);
        assertEquals("line one\nline two", m.text);
        assertEquals("b1", m.boot);
        // Typed offline after the restart, it takes the boot last seen ready.
        assertEquals("b1", after.add("h", "t1", "offline", 3).boot);
    }

    @Test public void messagesTypedBeforeTheLoadFinishedComeAfterTheSavedOnes() throws Exception {
        Outbox first = fresh();
        first.ready("h", "b1", panes("t1"));
        first.add("h", "t1", "older", 1);
        assertTrue(first.save());
        Outbox outbox = new Outbox(new File(dir.getRoot(), Outbox.FILE_NAME));
        outbox.add("h", "t1", "newer", 2);
        outbox.load();
        List<Outbox.Message> shown = outbox.shown("h", "t1", 3);
        assertEquals("older", shown.get(0).text);
        assertEquals("newer", shown.get(1).text);
    }

    @Test public void aClosedPaneOrRestartedCoreMakesMessagesGoneButKeepsThem() {
        Outbox outbox = fresh();
        outbox.ready("h", "b1", panes("t1", "t2"));
        Outbox.Message closed = outbox.add("h", "t1", "pane closed", 1);
        outbox.add("h", "t2", "core restarted", 2);
        outbox.ready("h", "b1", panes("t2"));
        assertEquals(Outbox.State.GONE, outbox.shown("h", "t2", 3).get(1).state);
        outbox.ready("h", "b2", panes("t1", "t2"));
        assertNull(outbox.next("h", 3));
        List<Outbox.Message> shown = outbox.shown("h", "t9", 3);
        assertEquals(2, shown.size());
        for (Outbox.Message m : shown) assertEquals(Outbox.State.GONE, m.state);
        // Taken back as a draft, not lost.
        assertEquals("pane closed", outbox.take(closed.id));
        assertEquals(0, outbox.waiting("h"));
    }

    @Test public void aMessageBeingSentIsNotMadeGoneUnderItsAnswer() {
        Outbox outbox = fresh();
        outbox.ready("h", "b1", panes("t1"));
        Outbox.Message m = outbox.add("h", "t1", "racing", 1);
        outbox.sending(m.id);
        outbox.ready("h", "b1", panes());
        outbox.settle(m.id, Outbox.Outcome.SENT, 2);
        assertEquals(0, outbox.size());
        // And a message being sent cannot be taken out from under the sender.
        Outbox.Message n = outbox.add("h", "t1", "again", 3);
        outbox.ready("h", "b1", panes("t1"));
        outbox.sending(n.id);
        assertNull(outbox.take(n.id));
    }

    @Test public void aRefusalWaitsAndThenTriesAgain() {
        Outbox outbox = fresh();
        outbox.ready("h", "b1", panes("t1"));
        Outbox.Message m = outbox.add("h", "t1", "after the dialog", 1);
        outbox.sending(m.id);
        outbox.settle(m.id, Outbox.Outcome.BLOCKED, 100);
        assertNull(outbox.next("h", 101));
        assertTrue(outbox.shown("h", "t1", 101).get(0).blocked);
        assertEquals(m.id, outbox.next("h", 100 + Outbox.BLOCKED_BACKOFF_MS).id);
    }

    @Test public void aMessageWithNoKnownBootWaitsForOneAndTakesIt() {
        Outbox outbox = fresh();
        Outbox.Message m = outbox.add("h", "t1", "cold start, offline", 1);
        assertEquals("", m.boot);
        assertNull(outbox.next("h", 2));
        outbox.ready("h", "b7", panes("t1"));
        assertEquals("b7", outbox.next("h", 3).boot);
    }

    @Test public void boundsAndTextRulesKeepTheDraftInsteadOfQueueing() {
        Outbox outbox = fresh();
        assertNull(outbox.add("h", "t1", "   ", 1));
        assertNull(outbox.add("h", "t1", "esc\u001b[2J", 1));
        assertNull(outbox.add("h", "", "no pane", 1));
        assertNull(outbox.add("h", "t1", "x".repeat(Outbox.MAX_TEXT_BYTES + 1), 1));
        assertNotNull(outbox.add("h", "t1", "tab\tand\nnewline", 1));
        for (int i = 1; i < Outbox.MAX_MESSAGES; i++) assertNotNull(outbox.add("h", "t1", "m" + i, 1));
        assertNull(outbox.add("h", "t1", "one too many", 1));
    }

    @Test public void aDamagedOrForeignFileYieldsNothingAndIsNotParsedWhenHuge() throws Exception {
        File file = new File(dir.getRoot(), Outbox.FILE_NAME);
        Files.write(file.toPath(), "{\"version\":1,\"messages\":[".getBytes(StandardCharsets.UTF_8));
        Outbox damaged = new Outbox(file);
        damaged.load();
        assertEquals(0, damaged.size());
        Files.write(file.toPath(), ("{\"version\":1,\"messages\":[{\"id\":\"a\",\"host\":\"h\",\"pane\":\"t1\","
                + "\"boot\":\"b\",\"text\":\"hi\",\"state\":\"FUTURE\"}]}").getBytes(StandardCharsets.UTF_8));
        Outbox future = new Outbox(file);
        future.load();
        // A state this build does not know might have been in flight: unsure, never resent.
        assertEquals(Outbox.State.UNSURE, future.shown("h", "t1", 1).get(0).state);
    }

    @Test public void theCourierSendsInOrderAndStopsAtTheFirstThatDoesNotGo() {
        File file = new File(dir.getRoot(), Outbox.FILE_NAME);
        List<String> wire = new ArrayList<>();
        String[] replies = {"{\"outcome\":\"sent\"}", "{\"outcome\":\"unsure\",\"why\":\"no answer\"}"};
        OutboxCourier courier = new OutboxCourier(file, Runnable::run,
                (handle, pane, text) -> {
                    wire.add(pane + ":" + text);
                    return replies[Math.min(wire.size() - 1, replies.length - 1)];
                });
        courier.add("h", "t1", "one");
        courier.add("h", "t1", "two");
        courier.add("h", "t1", "three");
        courier.ready("h", 5, "b1", panes("t1"));
        assertEquals(Arrays.asList("t1:one", "t1:two"), wire);
        // "two" is unsure: nothing more goes, however often the poll says ready.
        courier.ready("h", 5, "b1", panes("t1"));
        assertEquals(2, wire.size());
        Outbox after = reopened();
        List<Outbox.Message> shown = after.shown("h", "t1", 1);
        assertEquals(Outbox.State.UNSURE, shown.get(0).state);
        assertEquals("two", shown.get(0).text);
        assertEquals("three", shown.get(1).text);
    }

    @Test public void aNativeRefusalOfTheArgumentsIsOfferedBackNotRetried() {
        File file = new File(dir.getRoot(), Outbox.FILE_NAME);
        OutboxCourier courier = new OutboxCourier(file, Runnable::run,
                (handle, pane, text) -> { throw new IllegalStateException("Invalid or oversized remote argument"); });
        courier.add("h", "t1", "refused");
        courier.ready("h", 5, "b1", panes("t1"));
        assertEquals(Outbox.State.GONE, courier.outbox().shown("h", "t1", 1).get(0).state);
        assertFalse(courier.outbox().holds("h", "t1"));
    }

    @Test public void unknownOutcomesAreUnsure() {
        assertEquals(Outbox.Outcome.UNSURE, Outbox.Outcome.parse("maybe"));
        assertEquals(Outbox.Outcome.UNSURE, Outbox.Outcome.parse(null));
        assertEquals(Outbox.Outcome.SENT, Outbox.Outcome.parse("sent"));
        assertEquals(Outbox.Outcome.RETRY, Outbox.Outcome.parse("retry"));
    }

    @Test public void aFilledDictationCanBeUndoneWhenItsSaveFails() {
        Outbox outbox = fresh();
        outbox.ready("h", "b1", panes("t1"));
        String token = DictationToken.of("c1");
        Outbox.Message m = outbox.add("h", "t1", "see " + token, 1);
        Runnable undo = outbox.fillDictationUndoably("c1", "the words");
        assertNotNull(undo);
        assertEquals("see the words", outbox.shown("h", "t1", 2).get(0).text);
        undo.run();
        assertEquals("see " + token, outbox.shown("h", "t1", 2).get(0).text);
        // A message left blank by an empty dictation is dropped; the undo brings it back.
        Outbox.Message only = outbox.add("h", "t1", DictationToken.of("c2"), 3);
        Runnable undo2 = outbox.fillDictationUndoably("c2", "");
        assertEquals(1, outbox.shown("h", "t1", 4).size());
        undo2.run();
        assertEquals(Arrays.asList(m.id, only.id), ids(outbox.shown("h", "t1", 5)));
        assertNull(outbox.fillDictationUndoably("nobody", "x"));
    }

    @Test public void theCourierSaysWhenAFilledDictationCouldNotBeSaved() throws Exception {
        File file = new File(dir.getRoot(), "courier-" + Outbox.FILE_NAME);
        OutboxCourier courier = new OutboxCourier(file, Runnable::run, (handle, pane, text) -> "sent");
        courier.outbox().ready("h", "b1", panes("t1"));
        String token = DictationToken.of("c1");
        courier.add("h", "t1", "see " + token);
        assertEquals(0, courier.fillDictation("nobody", "x"));
        // The file cannot be written: its temporary's place is taken.
        File tmp = new File(file.getPath() + ".tmp");
        assertTrue(tmp.mkdirs());
        assertTrue(new File(tmp, "x").createNewFile());
        assertEquals(-1, courier.fillDictation("c1", "the words"));
        assertEquals("as it was", "see " + token, courier.outbox().shown("h", "t1", 1).get(0).text);
        new File(tmp, "x").delete();
        assertTrue(tmp.delete());
        assertEquals(1, courier.fillDictation("c1", "the words"));
        assertEquals("see the words", courier.outbox().shown("h", "t1", 1).get(0).text);
    }

    @Test public void anOlderSaveNeverLandsOverANewerOne() throws Exception {
        // The courier's thread snapshots the outbox and is paused before writing; meanwhile a
        // dictation's fill is saved on the main thread. The older write must not win.
        Outbox outbox = fresh();
        outbox.ready("h", "b1", panes("t1"));
        String token = DictationToken.of("c1");
        outbox.add("h", "t1", "see " + token, 1);
        java.util.concurrent.CountDownLatch paused = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch go = new java.util.concurrent.CountDownLatch(1);
        Thread courier = new Thread(() -> {
            outbox.beforeWrite = () -> {
                outbox.beforeWrite = null;
                paused.countDown();
                try {
                    go.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            };
            outbox.save();
        });
        courier.start();
        assertTrue(paused.await(5, java.util.concurrent.TimeUnit.SECONDS));
        assertTrue(outbox.fillDictation("c1", "the words"));
        Thread main = new Thread(outbox::save);
        main.start();
        Thread.sleep(200);
        go.countDown();
        courier.join(5000);
        main.join(5000);
        assertEquals("see the words", reopened().shown("h", "t1", 2).get(0).text);
    }

    private static List<String> ids(List<Outbox.Message> messages) {
        List<String> out = new ArrayList<>();
        for (Outbox.Message m : messages) out.add(m.id);
        return out;
    }
}
