package dev.herdr.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * Wolf's Fold, 11-25: after "Re-confirm this computer", Send and "Send here" on the deacon pane did
 * nothing. Two older messages held for Server (saved with no session) target the same machine and
 * terminal; the lane the pane offers goes by session, but the head a send claims did not, so the
 * offered message was never its lane's head and every tap was silently refused.
 */
public final class SessionLaneTest {
    @Rule public final TemporaryFolder dir = new TemporaryFolder();

    private static final MachineKey MACRO = MachineKey.of("024fdee722d4c37364d1c785e88f40b8",
            "SHA256:A0pfzyx/dGvoAHppqp0uwgQl7Ek3agUSrijJC7/n8zQ");

    private Outbox fresh() {
        Outbox outbox = new Outbox(new File(dir.getRoot(), "outbox.json"));
        outbox.load();
        return outbox;
    }

    private static Outbox.Target target(String profile, String name, String session) {
        return new Outbox.Target(MACRO, profile, name, session, "t1b6", 7L, "deacon", "");
    }

    @Test public void anotherSessionsOlderMessageNeverHoldsThisSessionsLane() {
        Outbox outbox = fresh();
        outbox.add("server-1", target("server", "Server", ""), "older", new Outbox.Composed(1_000, 50, 3));
        outbox.add("server-2", target("server", "Server", ""), "older too", new Outbox.Composed(2_000, 60, 3));
        outbox.add("mine", target("macro", "Macro (Google)", "default"), "new", new Outbox.Composed(3_000, 70, 3));
        Outbox.Live here = new Outbox.Live(MACRO, "macro", "t1b6", 7L, "default");

        Outbox.Message offered = outbox.offer(here);
        assertNotNull(offered);
        assertEquals("mine", offered.id);
        assertEquals(1, outbox.waitingIn(here));
        assertTrue("the offered message is its lane's head, so the tap claims it",
                outbox.claim("mine", here, "new", 4_000, false));
        // The other session's messages are untouched.
        assertEquals(Outbox.State.PENDING, outbox.message("server-1").state);
        assertEquals(Outbox.State.PENDING, outbox.message("server-2").state);
    }

    @Test public void withinOneSessionTheOrderStillHolds() {
        Outbox outbox = fresh();
        outbox.add("first", target("macro", "Macro (Google)", "default"), "first", new Outbox.Composed(1_000, 50, 3));
        outbox.add("second", target("macro", "Macro (Google)", "default"), "second", new Outbox.Composed(2_000, 60, 3));
        Outbox.Live here = new Outbox.Live(MACRO, "macro", "t1b6", 7L, "default");
        assertEquals("first", outbox.offer(here).id);
        assertTrue(!outbox.claim("second", here, "second", 3_000, false));
        assertTrue(outbox.claim("first", here, "first", 3_000, false));
    }
}
