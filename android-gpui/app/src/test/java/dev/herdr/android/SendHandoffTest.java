package dev.herdr.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.util.HashMap;
import java.util.Map;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * E2 below the emulator (§3.2): the process exits before the first commit of a Send and after
 * each later one. Before the first: the last saved draft, no message. After it: the exact text as
 * a draft or as a held message, never both and never neither.
 */
public final class SendHandoffTest {
    @Rule public final TemporaryFolder dir = new TemporaryFolder();

    private static final String DRAFTS = "drafts";
    private static final String TEXT = "please run the tests";

    /** The process dies here. */
    static final class Crash extends Error { }

    /** What is on disk: the composer's preferences, committed or not at all, and the outbox file. */
    private final Map<String, String> disk = new HashMap<>();

    private File outboxFile() {
        return new File(dir.getRoot(), Outbox.FILE_NAME);
    }

    /** A composer and outbox as the app has them; {@code crashAfter} is 1, 2 or 3 (a step), or 0. */
    private SendHandoff.Result send(ComposerDrafts drafts, OutboxCourier courier, int crashAfter) {
        String owner = drafts.owner();
        SendHandoff.Record record = new SendHandoff.Record("sub-1", owner, drafts.outgoing());
        return SendHandoff.run(record, new SendHandoff.Steps() {
            @Override public boolean commitDraft(SendHandoff.Record r) {
                disk.put(DRAFTS, drafts.encode());
                disk.put(SendHandoff.KEY, r.encode());
                if (crashAfter == 1) throw new Crash();
                return true;
            }

            @Override public boolean holdMessage(SendHandoff.Record r) {
                boolean held = courier.add(r.id, HeldFixtures.target("h", "t1"), r.text, HeldFixtures.at(1)) != null;
                if (crashAfter == 2) throw new Crash();
                return held;
            }

            @Override public boolean clearDraft(SendHandoff.Record r) {
                drafts.sent();
                disk.put(DRAFTS, drafts.encode());
                disk.remove(SendHandoff.KEY);
                if (crashAfter == 3) throw new Crash();
                return true;
            }

            @Override public void forget(SendHandoff.Record r) {
                disk.remove(SendHandoff.KEY);
            }
        });
    }

    private OutboxCourier courier() {
        return new OutboxCourier(outboxFile(), Runnable::run, (h, a, t) -> {
            throw new AssertionError("nothing is typed by a Send's handoff");
        });
    }

    /** The app starts again: drafts restored, outbox read, the record settled as ComposerBar does. */
    private String[] restart(StringBuilder note) {
        ComposerDrafts drafts = new ComposerDrafts();
        drafts.restoreOffered(disk.getOrDefault(DRAFTS, ""));
        drafts.setHost("h");
        drafts.switchTo("t1");
        Outbox outbox = new Outbox(outboxFile());
        outbox.load();
        SendHandoff.Record record = SendHandoff.Record.decode(disk.get(SendHandoff.KEY));
        SendHandoff.Recovery recovery = SendHandoff.reconcile(record, id -> outbox.text(id) != null);
        if (recovery == SendHandoff.Recovery.CLEAR_DRAFT) drafts.removeSent(record.owner, record.text);
        if (recovery == SendHandoff.Recovery.OFFER_AGAIN) note.append(SendHandoff.NOT_SENT);
        ComposerDrafts.Draft offered = drafts.offered();
        String draft = offered == null ? drafts.text() : offered.text;
        String held = outbox.size() == 0 ? null : outbox.all().get(0).text;
        return new String[] {draft, held};
    }

    private ComposerDrafts typed() {
        ComposerDrafts drafts = new ComposerDrafts();
        drafts.setHost("h");
        drafts.switchTo("t1");
        drafts.edit(TEXT);
        return drafts;
    }

    @Test public void killedBeforeTheFirstCommitTheLastSavedDraftStandsAndNothingIsHeld() {
        ComposerDrafts drafts = typed();
        disk.put(DRAFTS, drafts.encode());
        drafts.edit(TEXT + " now");
        // The process dies as Send is tapped, before step 1.
        StringBuilder note = new StringBuilder();
        String[] after = restart(note);
        assertEquals(TEXT, after[0]);
        assertNull(after[1]);
        assertEquals("", note.toString());
    }

    @Test public void killedAfterEachCommitTheExactTextIsADraftOrAMessageNeverBothNeverNeither() {
        for (int step = 1; step <= 3; step++) {
            disk.clear();
            assertTrue(outboxFile().delete() || !outboxFile().exists());
            ComposerDrafts drafts = typed();
            OutboxCourier courier = courier();
            try {
                send(drafts, courier, step);
                throw new AssertionError("crash expected");
            } catch (Crash expected) {
                // The process is gone.
            }
            StringBuilder note = new StringBuilder();
            String[] after = restart(note);
            boolean asDraft = TEXT.equals(after[0]);
            boolean asMessage = TEXT.equals(after[1]);
            assertTrue("after step " + step + ": never neither", asDraft || asMessage);
            assertFalse("after step " + step + ": never both", asDraft && asMessage);
            if (step == 1) {
                assertTrue(asDraft);
                assertEquals("its Send says it did not happen", SendHandoff.NOT_SENT, note.toString());
            } else {
                assertTrue(asMessage);
            }
        }
    }

    @Test public void aMessageThatCannotBeHeldKeepsTheDraftAndItsRecordGoes() {
        ComposerDrafts drafts = typed();
        OutboxCourier courier = courier();
        courier.outbox().failSaves = true;
        assertEquals(SendHandoff.Result.NOT_HELD, send(drafts, courier, 0));
        assertEquals(TEXT, drafts.text());
        assertNull(disk.get(SendHandoff.KEY));
        courier.outbox().failSaves = false;
        assertEquals(SendHandoff.Result.HELD, send(drafts, courier, 0));
        assertEquals("", drafts.text());
        assertEquals(TEXT, courier.outbox().text("sub-1"));
    }

    @Test public void aRecordSurvivesItsOwnEncoding() {
        SendHandoff.Record r = SendHandoff.Record.decode(new SendHandoff.Record("id", "h\u0001t1", "a\nb").encode());
        assertEquals("id", r.id);
        assertEquals("h\u0001t1", r.owner);
        assertEquals("a\nb", r.text);
        assertNull(SendHandoff.Record.decode("{broken"));
        assertEquals(SendHandoff.Recovery.NONE, SendHandoff.reconcile(null, id -> true));
    }
}
