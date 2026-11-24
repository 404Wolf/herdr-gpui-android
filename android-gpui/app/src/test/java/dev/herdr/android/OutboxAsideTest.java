package dev.herdr.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * factory-9zz.16: a lane held by a dictation waiting for a transcriber. The user can see it
 * (how many wait behind it), and move that message aside so the rest offer again; nothing is
 * ever sent without the usual tap, skipped or dropped.
 */
public final class OutboxAsideTest {
    @Rule public final TemporaryFolder dir = new TemporaryFolder();

    private static final MachineKey A = MachineKey.of("core-a", "SHA256:host-a");
    private final Outbox.Live here = new Outbox.Live(A, "p", "t1", 7L);

    private Outbox fresh() {
        Outbox outbox = new Outbox(new File(dir.getRoot(), Outbox.FILE_NAME));
        outbox.load();
        return outbox;
    }

    private static Outbox.Message hold(Outbox outbox, String id, String text) {
        return outbox.add(id, new Outbox.Target(A, "p", "computer p", "default", "t1", 7L, "PM", "idle"), text,
                new Outbox.Composed(1_000, 50, 3));
    }

    @Test public void aWaitingDictationAtTheHeadSaysHowManyWaitBehindIt() {
        Outbox outbox = fresh();
        Outbox.Message head = hold(outbox, "m1", "fix it " + DictationToken.of("clip-1"));
        hold(outbox, "m2", "second");
        hold(outbox, "m3", "third");
        assertNull("held, as before", outbox.offer(here));
        Outbox.Blocked b = outbox.blocked(here);
        assertEquals(head.id, b.messageId);
        assertEquals("clip-1", b.clipId);
        assertEquals(2, b.behind);

        hold(outbox, "m4", "fourth");
        assertEquals(3, outbox.blocked(here).behind);
    }

    @Test public void movingItAsideLetsTheRestOfferAgainButSendsNothingByItself() {
        Outbox outbox = fresh();
        Outbox.Message head = hold(outbox, "m1", DictationToken.of("clip-1"));
        Outbox.Message second = hold(outbox, "m2", "second");
        assertTrue(outbox.moveAside(head.id));
        assertTrue(outbox.save());
        assertNull("no longer blocked", outbox.blocked(here));
        assertEquals("offered, still only for a tap", second.id, outbox.offer(here).id);
        assertEquals(Outbox.State.PENDING, outbox.message(second.id).state);
        assertTrue(outbox.message(head.id).aside);
        assertEquals("the aside message is kept, pending", Outbox.State.PENDING, outbox.message(head.id).state);
        assertFalse("never claimed while its dictation waits", outbox.claim(head.id, here, head.text, 0, false));
        assertTrue(outbox.claim(second.id, here, second.text, 0, false));

        // It survives a restart.
        Outbox again = fresh();
        assertTrue(again.message(head.id).aside);
    }

    @Test public void onlyAPendingMessageThatWaitsForItsDictationCanBeMovedAside() {
        Outbox outbox = fresh();
        Outbox.Message plain = hold(outbox, "m1", "plain words");
        assertFalse(outbox.moveAside(plain.id));
        assertFalse(outbox.moveAside("nope"));
        assertNull(outbox.blocked(here));
    }

    @Test public void onceItsTextIsInItTakesItsPlaceAgain() {
        Outbox outbox = fresh();
        Outbox.Message head = hold(outbox, "m1", "a " + DictationToken.of("clip-1"));
        Outbox.Message second = hold(outbox, "m2", "second");
        assertTrue(outbox.moveAside(head.id));
        assertTrue(outbox.fillDictation("clip-1", "transcribed words"));
        // Filled: an ordinary held message again, at its own place in the lane.
        assertFalse(outbox.message(head.id).aside);
        assertEquals(head.id, outbox.offer(here).id);
        assertFalse(outbox.claim(second.id, here, second.text, 0, false));
    }
}
