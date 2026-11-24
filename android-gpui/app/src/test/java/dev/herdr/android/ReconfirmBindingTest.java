package dev.herdr.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * Wolf's Fold, 11-24: "Macro (Google)" was refused as "the same Herdr as Server" and worked live
 * only, so Send, queued sends and dictation all kept their drafts. A refused binding can be
 * re-confirmed by the person, from the live hello, when what it collides with is stale; a real
 * duplicate stays refused, and no held message moves.
 */
public final class ReconfirmBindingTest {
    @Rule public final TemporaryFolder dir = new TemporaryFolder();

    private static final MachineKey MACRO = MachineKey.of("024fdee722d4c37364d1c785e88f40b8",
            "SHA256:A0pfzyx/dGvoAHppqp0uwgQl7Ek3agUSrijJC7/n8zQ");

    private MachineBindings fresh() {
        MachineBindings bindings = new MachineBindings(new File(dir.getRoot(), MachineBindings.FILE_NAME));
        bindings.load();
        return bindings;
    }

    private Outbox outbox() {
        Outbox outbox = new Outbox(new File(dir.getRoot(), "outbox.json"));
        outbox.load();
        return outbox;
    }

    /** Only the profiles' saved revisions are current; an older revision of a profile is not. */
    private static boolean current(String profile, String revision) {
        return profile.equals("macro") && revision.equals("r1")
                || profile.equals("a") || profile.equals("b");
    }

    private static Outbox.Target serverTarget(String session, String pane) {
        return new Outbox.Target(MACRO, "server", "Server", session, pane, 7L, "deacon", "");
    }

    @Test public void staleBindingUnderAnotherSession_isRefusedThenReconfirmable() {
        MachineBindings bindings = fresh();
        // An older build saved Server's binding with no session.
        assertTrue(bindings.bind("server", "old", "", "Server", MACRO).bound());
        MachineBindings.Binding refused = bindings.bind("macro", "r1", "default", "Macro (Google)", MACRO);
        assertFalse("the safety check still refuses on its own", refused.bound());
        assertEquals("Server", refused.refusedBy);
        assertEquals("", refused.refusedSession);
        assertEquals(MachineBindings.Kind.BINDING, refused.refusedKind);

        MachineBindings.Binding confirmed = bindings.reconfirm("macro", "r1", "default", "Macro (Google)", MACRO,
                Collections.emptyList(), ReconfirmBindingTest::current);
        assertTrue(confirmed.bound());
        assertTrue(confirmed.confirmed);
        assertEquals(MACRO, bindings.machine("macro", "r1"));
        // The next hello, and a restart, keep it.
        assertTrue(bindings.bind("macro", "r1", "default", "Macro (Google)", MACRO).bound());
        assertEquals(MACRO, fresh().machine("macro", "r1"));
        assertTrue(fresh().bind("macro", "r1", "default", "Macro (Google)", MACRO).bound());
    }

    @Test public void aRealDuplicate_isStillRefused() {
        MachineBindings bindings = fresh();
        assertTrue(bindings.bind("a", "r", "work", "A", MACRO).bound());
        assertFalse(bindings.bind("b", "r", "default", "B", MACRO).bound());
        // Both profiles are saved as they are now, each with its own session: a real duplicate.
        MachineBindings.Binding still = bindings.reconfirm("b", "r", "default", "B", MACRO,
                Collections.emptyList(), ReconfirmBindingTest::current);
        assertFalse(still.bound());
        assertEquals("A", still.refusedBy);
        assertNull(bindings.machine("b", "r"));
        assertEquals(MACRO, bindings.machine("a", "r"));
    }

    @Test public void reconfirmingNeverMovesAHeldMessage() {
        Outbox outbox = outbox();
        outbox.add("h1", serverTarget("", "t1b6"), "first", new Outbox.Composed(1_000, 50, 3));
        outbox.add("h2", serverTarget("", "t1b6"), "second", new Outbox.Composed(2_000, 60, 3));
        outbox.save();
        List<String> before = describe(outbox.all());

        MachineBindings bindings = fresh();
        MachineBindings.Binding refused = bindings.bind("macro", "r1", "default", "Macro (Google)", MACRO, outbox.all());
        assertFalse(refused.bound());
        assertEquals("Server", refused.refusedBy);
        assertEquals(MachineBindings.Kind.HELD, refused.refusedKind);
        assertTrue(bindings.reconfirm("macro", "r1", "default", "Macro (Google)", MACRO, outbox.all(),
                ReconfirmBindingTest::current).bound());
        assertEquals(before, describe(outbox.all()));
        // Bound now, a list of this session's panes without theirs never marks them gone, and its
        // live pane is never offered them: they keep waiting for Server.
        Map<String, Long> panes = Collections.singletonMap("t9", 9L);
        assertFalse(outbox.observe(MACRO, "default", panes));
        assertNull(outbox.offer(new Outbox.Live(MACRO, "macro", "t1b6", 7L, "default")));
        assertEquals(before, describe(outbox.all()));
        assertEquals(before, describe(outbox().all()));
    }

    @Test public void aHeldMessageOfAnotherNamedSession_keepsTheRefusal() {
        Outbox outbox = outbox();
        outbox.add("h1", serverTarget("work", "t1b6"), "first", new Outbox.Composed(1_000, 50, 3));
        MachineBindings bindings = fresh();
        assertFalse(bindings.bind("macro", "r1", "default", "Macro (Google)", MACRO, outbox.all()).bound());
        assertFalse(bindings.reconfirm("macro", "r1", "default", "Macro (Google)", MACRO, outbox.all(),
                ReconfirmBindingTest::current).bound());
    }

    @Test public void theRefusalNamesWhatItCollidesWith() {
        MachineBindings bindings = fresh();
        bindings.bind("server", "old", "", "Server", MACRO);
        String words = MachineBindings.refusal(bindings.bind("macro", "r1", "default", "Macro (Google)", MACRO));
        assertTrue(words, words.startsWith("This computer reports the same Herdr as Server"));
        assertTrue(words, words.contains("saved computer Server, session \"\""));
        assertTrue(words, words.contains("core 024fdee7"));
        assertTrue(words, words.contains("host key A0pfzyx/"));
        assertTrue(words, words.contains("Re-confirm this computer"));
    }

    private static List<String> describe(List<Outbox.Message> messages) {
        List<String> out = new ArrayList<>();
        for (Outbox.Message m : messages) {
            out.add(m.id + "|" + m.target.machine + "|" + m.target.profileId + "|" + m.target.profileName + "|"
                    + m.target.session + "|" + m.target.address + "|" + m.state + "|" + m.text);
        }
        return out;
    }
}
