package dev.herdr.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;

/**
 * The sync engine, step by step (factory-9zz.14): each thread is a queue the test runs by hand,
 * the clock is a number, the machine is a fake fetcher. Covers the Tasks first-entry lifecycle
 * (who asks, who hears, what shows before the first answer), priorities, the typing gate,
 * machine identity, failures and restarts.
 */
public final class SyncEngineTest {
    static final MachineKey A = MachineKey.of("core-a", "ssh-ed25519 AAAA"), B = MachineKey.of("core-b", "ssh-ed25519 BBBB");

    /** One engine with hand-run threads. */
    static final class Rig {
        final ArrayDeque<Runnable> main = new ArrayDeque<>(), net = new ArrayDeque<>(), disk = new ArrayDeque<>();
        final Map<SyncEngine.Key, SyncEngine.Entry> files = new HashMap<>();
        final List<String> asked = new ArrayList<>(), log = new ArrayList<>();
        final Map<SyncEngine.Kind, SyncEngine.Fetcher> fetchers = new EnumMap<>(SyncEngine.Kind.class);
        long now = 1_000_000;
        boolean typing;
        /** What the fake machine answers next, per key name; null: "same". */
        final Map<String, String> answers = new HashMap<>();
        RuntimeException fails;
        final SyncEngine engine;

        Rig() {
            for (SyncEngine.Kind kind : SyncEngine.Kind.values()) {
                fetchers.put(kind, (key, kept) -> {
                    asked.add(key.kind + ":" + key.name + (kept == null ? "" : "@" + kept.fp));
                    if (fails != null) throw fails;
                    String text = answers.get(key.name);
                    return text == null ? SyncEngine.Fetched.same("m") : SyncEngine.Fetched.data(text, "fp-" + text.length(), "m");
                });
            }
            SyncEngine.Store store = new SyncEngine.Store() {
                @Override public SyncEngine.Entry load(SyncEngine.Key key) { return files.get(key); }
                @Override public void save(SyncEngine.Key key, SyncEngine.Entry entry) { files.put(key, entry); }
            };
            engine = new SyncEngine(fetchers, store, () -> typing, main::add, net::add, disk::add, () -> now, log::add);
        }

        /** Runs every queue until all are empty (each a thread doing its next task). */
        void settle() {
            while (!main.isEmpty() || !net.isEmpty() || !disk.isEmpty()) {
                if (!disk.isEmpty()) disk.poll().run();
                else if (!net.isEmpty()) net.poll().run();
                else main.poll().run();
            }
        }
    }

    static SyncEngine.Key beads(MachineKey m) {
        return new SyncEngine.Key(m, SyncEngine.Kind.BEADS, "");
    }

    static SyncEngine.Key doc(MachineKey m, String name) {
        return new SyncEngine.Key(m, SyncEngine.Kind.DOC, name);
    }

    /** The repro's shape: the first look at Tasks asks once, hears the answer, and draws it; the second is instant. */
    @Test public void theFirstLookAsksOnceAndItsListenerHearsTheAnswer() {
        Rig r = new Rig();
        r.engine.connected(A);
        int[] heard = {0};
        r.engine.listen(beads(A), () -> heard[0]++);
        r.answers.put("", "the tree");
        assertNull("nothing kept yet: the skeleton", r.engine.want(beads(A), SyncEngine.Priority.VISIBLE));
        // Asked again on every draw before the answer: still one read.
        r.engine.want(beads(A), SyncEngine.Priority.VISIBLE);
        r.settle();
        r.engine.want(beads(A), SyncEngine.Priority.VISIBLE);
        r.settle();
        assertEquals(List.of("BEADS:"), r.asked);
        assertEquals(1, heard[0]);
        SyncEngine.Entry e = r.engine.want(beads(A), SyncEngine.Priority.VISIBLE);
        assertEquals("the tree", e.text);
        assertEquals(SyncEngine.Entry.State.FRESH, e.state);
        assertEquals(r.now, e.atMs);
        assertTrue(r.log.get(0), r.log.get(0).startsWith("sync beads visible: waited 0 ms, ran 0 ms, 8 chars, data"));
    }

    /** factory-9zz.14's other half: a draft in the composer no longer keeps a cold screen loading forever. */
    @Test public void typingHoldsBackgroundReadsButNeverAColdVisibleOne() {
        Rig r = new Rig();
        r.engine.connected(A);
        r.typing = true;
        r.answers.put("", "tree");
        r.answers.put("x", "doc");
        r.engine.want(doc(A, "x"), SyncEngine.Priority.PREFETCH);
        r.settle();
        assertTrue("a prefetch waits while Wolf types", r.asked.isEmpty());
        r.engine.want(beads(A), SyncEngine.Priority.VISIBLE);
        r.settle();
        assertEquals("the cold visible read ran", List.of("BEADS:"), r.asked);
        // Kept now: its refresh waits for the typing to end, and so does the prefetch.
        r.now += SyncEngine.Kind.BEADS.ttlMs;
        r.engine.want(beads(A), SyncEngine.Priority.VISIBLE);
        r.settle();
        assertEquals(1, r.asked.size());
        r.typing = false;
        r.engine.tick();
        r.settle();
        assertEquals(List.of("BEADS:", "BEADS:@fp-4", "DOC:x"), r.asked);
    }

    @Test public void onScreenGoesFirstThenATapAwayThenTheBackground() {
        Rig r = new Rig();
        r.engine.want(doc(A, "refresh"), SyncEngine.Priority.REFRESH);
        r.engine.want(doc(A, "prefetch"), SyncEngine.Priority.PREFETCH);
        r.engine.want(doc(A, "visible"), SyncEngine.Priority.VISIBLE);
        r.settle();
        assertTrue("no machine connected: nothing runs", r.asked.isEmpty());
        r.engine.connected(A);
        r.settle();
        assertEquals(List.of("DOC:visible", "DOC:prefetch", "DOC:refresh"), r.asked);
        // A key asked at two priorities goes at the better one.
        r.asked.clear();
        r.now += SyncEngine.Kind.DOC.ttlMs;
        r.engine.want(doc(A, "refresh"), SyncEngine.Priority.REFRESH);
        r.engine.want(doc(A, "prefetch"), SyncEngine.Priority.PREFETCH);
        r.engine.want(doc(A, "refresh"), SyncEngine.Priority.VISIBLE);
        r.settle();
        assertEquals("DOC:refresh@", r.asked.get(0).substring(0, 12));
    }

    /** One machine's answers never show as another's, and a machine not on screen is never asked. */
    @Test public void answersStayWithTheMachineTheyCameFrom() {
        Rig r = new Rig();
        r.engine.connected(A);
        r.answers.put("", "A's tree");
        r.engine.want(beads(A), SyncEngine.Priority.VISIBLE);
        r.settle();
        assertNull("B has nothing of A's", r.engine.want(beads(B), SyncEngine.Priority.VISIBLE));
        r.settle();
        assertEquals("B is not on screen: not asked", 1, r.asked.size());
        r.engine.connected(B);
        r.answers.put("", "B's tree");
        r.settle();
        assertEquals("B's tree", r.engine.get(beads(B)).text);
        assertEquals("A's tree", r.engine.get(beads(A)).text);
    }

    /** A read cut off by a switch away is dropped, not taken as an answer, and asked again on return. */
    @Test public void aReadTheSwitchCutOffIsAskedAgainNotKept() {
        Rig r = new Rig();
        r.engine.connected(A);
        int[] heard = {0};
        r.engine.listen(beads(A), () -> heard[0]++);
        r.fails = new HostSwitched();
        r.engine.want(beads(A), SyncEngine.Priority.VISIBLE);
        r.settle();
        assertNull(r.engine.get(beads(A)));
        assertEquals(0, heard[0]);
        assertTrue(r.log.get(0), r.log.get(0).endsWith("dropped: the phone left that machine"));
        r.engine.tick();
        r.settle();
        assertEquals("no spinning: it waits a moment", 1, r.asked.size());
        r.fails = null;
        r.answers.put("", "tree");
        r.now += SyncEngine.SWITCHED_RETRY_MS;
        r.engine.tick();
        r.settle();
        assertEquals("tree", r.engine.get(beads(A)).text);
        assertEquals(1, heard[0]);
    }

    /** A failed read keeps the last answer and says why; it is retried after a growing wait. */
    @Test public void aFailureKeepsTheLastAnswerAndBacksOff() {
        Rig r = new Rig();
        r.engine.connected(A);
        r.answers.put("x", "first");
        r.engine.want(doc(A, "x"), SyncEngine.Priority.VISIBLE);
        r.settle();
        r.fails = new RemoteError(RemoteError.BUSY, "", "busy");
        r.now += SyncEngine.Kind.DOC.ttlMs;
        r.engine.want(doc(A, "x"), SyncEngine.Priority.VISIBLE);
        r.settle();
        SyncEngine.Entry e = r.engine.get(doc(A, "x"));
        assertEquals("first", e.text);
        assertEquals(SyncEngine.Entry.State.ERROR, e.state);
        assertNotNull(e.error);
        int asks = r.asked.size();
        r.now += SyncEngine.Kind.DOC.ttlMs / 2;
        r.engine.want(doc(A, "x"), SyncEngine.Priority.VISIBLE);
        r.settle();
        assertEquals("waiting out its backoff", asks, r.asked.size());
        r.now += SyncEngine.Kind.DOC.ttlMs;
        r.fails = null;
        r.engine.want(doc(A, "x"), SyncEngine.Priority.VISIBLE);
        r.settle();
        assertEquals(SyncEngine.Entry.State.FRESH, r.engine.get(doc(A, "x")).state);
        // With nothing kept, the failure itself shows (not a skeleton).
        r.fails = new RemoteError(RemoteError.TIMEOUT, "", "slow");
        r.engine.want(doc(A, "y"), SyncEngine.Priority.VISIBLE);
        r.settle();
        assertFalse(r.engine.get(doc(A, "y")).hasData());
        assertNotNull(r.engine.get(doc(A, "y")).error);
    }

    /** Too big to keep is an error the screen can say, never a silent hole read again forever. */
    @Test public void anAnswerTooBigToKeepIsAnErrorNotAHole() {
        Rig r = new Rig();
        r.engine.connected(A);
        StringBuilder big = new StringBuilder();
        while (big.length() <= SyncEngine.Kind.DOC.maxChars) big.append("0123456789");
        r.answers.put("x", big.toString());
        r.engine.want(doc(A, "x"), SyncEngine.Priority.VISIBLE);
        r.settle();
        SyncEngine.Entry e = r.engine.get(doc(A, "x"));
        assertTrue(e.error, e.error.contains("too big"));
        r.engine.want(doc(A, "x"), SyncEngine.Priority.VISIBLE);
        r.settle();
        assertEquals("not read again at once", 1, r.asked.size());
    }

    /** After a restart the kept answer draws before any read; a young one is not read again. */
    @Test public void aRestartDrawsTheKeptAnswerAndReadsOnlyWhenItIsOld() {
        Rig r = new Rig();
        r.files.put(beads(A), new SyncEngine.Entry("kept tree", "", r.now - 5_000, "server", SyncEngine.Entry.State.FRESH, null, 0));
        r.engine.connected(A);
        int[] heard = {0};
        r.engine.listen(beads(A), () -> heard[0]++);
        r.engine.want(beads(A), SyncEngine.Priority.VISIBLE);
        r.settle();
        assertEquals("kept tree", r.engine.get(beads(A)).text);
        assertEquals("server", r.engine.get(beads(A)).source);
        assertEquals(1, heard[0]);
        assertTrue("young: no read", r.asked.isEmpty());
        r.now += SyncEngine.Kind.BEADS.ttlMs;
        r.answers.put("", "new tree");
        r.engine.want(beads(A), SyncEngine.Priority.VISIBLE);
        r.settle();
        assertEquals("new tree", r.engine.get(beads(A)).text);
        assertEquals("new tree", r.files.get(beads(A)).text);
    }

    /** "Same" keeps the text and moves its age; the kept fingerprint goes with the ask. */
    @Test public void sameMovesTheAgeAndKeepsTheText() {
        Rig r = new Rig();
        r.engine.connected(A);
        r.answers.put("x", "doc");
        r.engine.want(doc(A, "x"), SyncEngine.Priority.VISIBLE);
        r.settle();
        r.answers.remove("x");
        r.now += SyncEngine.Kind.DOC.ttlMs;
        long v = r.engine.get(doc(A, "x")).version;
        r.engine.want(doc(A, "x"), SyncEngine.Priority.VISIBLE);
        r.settle();
        assertEquals("DOC:x@fp-3", r.asked.get(1));
        assertEquals("doc", r.engine.get(doc(A, "x")).text);
        assertEquals(r.now, r.engine.get(doc(A, "x")).atMs);
        assertTrue(r.engine.get(doc(A, "x")).version > v);
    }

    @Test public void pausedReadsNothingAndResumesWhereItWas() {
        Rig r = new Rig();
        r.engine.connected(A);
        r.engine.paused(true);
        r.engine.want(doc(A, "x"), SyncEngine.Priority.VISIBLE);
        r.settle();
        assertTrue(r.asked.isEmpty());
        r.engine.paused(false);
        r.settle();
        assertEquals(List.of("DOC:x"), r.asked);
    }

    /** Preview-15's log: a key asked again while its read was out ran a second read at once. */
    @Test public void askingWhileAReadIsOutDoesNotReadTwice() {
        Rig r = new Rig();
        r.engine.connected(A);
        r.answers.put("", "tree");
        r.engine.want(beads(A), SyncEngine.Priority.PREFETCH);
        // The read is out (disk done, net queued); the next draws ask again.
        while (!r.disk.isEmpty()) r.disk.poll().run();
        while (!r.main.isEmpty()) r.main.poll().run();
        assertTrue(r.engine.reading(beads(A)));
        r.engine.want(beads(A), SyncEngine.Priority.VISIBLE);
        r.engine.want(beads(A), SyncEngine.Priority.PREFETCH);
        r.settle();
        assertEquals(List.of("BEADS:"), r.asked);
    }
}
