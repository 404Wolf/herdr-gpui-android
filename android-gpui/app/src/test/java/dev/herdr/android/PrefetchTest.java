package dev.herdr.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

/** Item 77, ask 2 (PR B): what the phone reads into its offline cache while open, and the data it may spend. */
public final class PrefetchTest {
    private static JSONObject pane(String id, String project, String role, String status, long since) throws Exception {
        return new JSONObject().put("id", id).put("project", project).put("role", role)
                .put("status", status).put("status_ms", since).put("turn", 1);
    }

    private static JSONArray fleet() throws Exception {
        return new JSONArray()
                .put(pane("t1", "android", "pm", "done", 10))
                .put(pane("t2", "android", "worker", "done", 20))
                .put(pane("t3", "android", "worker", "working", 30))
                .put(pane("t4", "android", "worker", "blocked", 40))
                .put(pane("t5", "monopoly", "worker", "done", 50))
                .put(pane("t6", "monopoly", "pm", "idle", 60))
                .put(pane("t7", "", "deacon", "done", 70));
    }

    @Test public void ask2TheProjectOnScreenComesFirstThenDeaconAndPmsNeverBusyOrOnScreen() throws Exception {
        assertEquals(Arrays.asList("t1", "t6", "t7"), Prefetch.plan(fleet(), "t2", Collections.emptyMap()));
        assertEquals(Arrays.asList("t2", "t6", "t7"), Prefetch.plan(fleet(), "t1", Collections.emptyMap()));
        assertEquals(Arrays.asList("t5", "t1", "t7"), Prefetch.plan(fleet(), "t6", Collections.emptyMap()));
    }

    @Test public void ask2APaneIsReadAgainOnlyOnceItsStatusMoved() throws Exception {
        JSONArray panes = fleet();
        Map<String, String> seen = new HashMap<>();
        seen.put("t1", Prefetch.marker(panes.getJSONObject(0)));
        seen.put("t6", Prefetch.marker(panes.getJSONObject(5)));
        assertEquals(Collections.singletonList("t7"), Prefetch.plan(panes, "t2", seen));
        panes.getJSONObject(0).put("status_ms", 11);
        assertEquals(Arrays.asList("t1", "t7"), Prefetch.plan(panes, "t2", seen));
        panes.getJSONObject(5).put("turn", 2);
        assertEquals(Arrays.asList("t1", "t6", "t7"), Prefetch.plan(panes, "t2", seen));
        assertEquals(seen, Prefetch.decode(Prefetch.encode(seen)));
        assertTrue(Prefetch.decode("{broken").isEmpty());
    }

    @Test public void ask2MobileDataStopsAtTwentyMegabytesADayWifiNever() {
        long cap = Prefetch.METERED_DAY_BYTES;
        assertEquals(20L * 1024 * 1024, cap);
        Prefetch.Budget budget = new Prefetch.Budget();
        long day = 20_000;
        // A read starts only while a whole reservation still fits under the cap.
        budget.spend(day, cap - Prefetch.RESERVE_BYTES, true);
        assertTrue(budget.allows(day, true));
        budget.spend(day, 1, true);
        assertFalse(budget.allows(day, true));
        // Wi-Fi is not counted and never stops.
        budget.spend(day, 500L * 1024 * 1024, false);
        assertEquals(cap - Prefetch.RESERVE_BYTES + 1, budget.used);
        assertTrue(budget.allows(day, false));
        // A new day starts again.
        assertTrue(budget.allows(day + 1, true));
        budget.spend(day + 1, 1, true);
        assertEquals(1, budget.used);
    }

    private static final class FakeStore implements Prefetch.Store {
        String seen = "{}";
        long day;
        long used;
        @Override public String seen() { return seen; }
        @Override public long day() { return day; }
        @Override public long used() { return used; }
        @Override public void save(String seen, long day, long used) { this.seen = seen; this.day = day; this.used = used; }
    }

    /** Review #44 P1-2: a read that fails or times out costs its whole reservation. */
    @Test public void ask2AFailedOrTimedOutReadIsChargedItsReservation() {
        Prefetch.Budget budget = new Prefetch.Budget();
        java.util.Map<String, String> seen = new java.util.HashMap<>();
        byte[] copy = Prefetch.step(budget, seen, "k", "t5", "m", 20_000, () -> true, pane -> {
            throw new IllegalStateException("terminal.read timed out");
        });
        assertNull(copy);
        assertEquals(Prefetch.RESERVE_BYTES, budget.used);
        // A passing failure is tried again on a later tick: not marked read.
        assertTrue(seen.isEmpty());
    }

    /** Review #44 P2-1: what the replies took is charged, not the copy's size. */
    @Test public void ask2AReadIsChargedTheBytesItReceived() {
        Prefetch.Budget budget = new Prefetch.Budget();
        java.util.Map<String, String> seen = new java.util.HashMap<>();
        byte[] copy = Prefetch.step(budget, seen, "k", "t5", "m", 20_000, () -> true,
                pane -> new Prefetch.Read(false, 300_000, new byte[] {1, 2, 3}));
        assertArrayEquals(new byte[] {1, 2, 3}, copy);
        assertEquals(300_000, budget.used);
        assertEquals("m", seen.get("k"));
        // Refused by the core: nothing stored, the bytes still charged, asked again only once its marker moves.
        Prefetch.step(budget, seen, "k2", "t6", "m2", 20_000, () -> true, pane -> new Prefetch.Read(true, 400, null));
        assertEquals(300_400, budget.used);
        assertEquals("m2", seen.get("k2"));
        // A read that starts on Wi-Fi and ends on mobile data counts.
        boolean[] metered = {false};
        Prefetch.step(budget, seen, "k3", "t7", "m3", 20_000, () -> metered[0], pane -> {
            metered[0] = true;
            return new Prefetch.Read(false, 1000, new byte[] {9});
        });
        assertEquals(301_400, budget.used);
        // Wi-Fi throughout: free.
        Prefetch.step(budget, seen, "k4", "t8", "m4", 20_000, () -> false, pane -> new Prefetch.Read(false, 9_999_999, new byte[] {9}));
        assertEquals(301_400, budget.used);
    }

    /** Review #44 P2-3: no read at all once the cap could be passed; the step never calls the reader. */
    @Test public void ask2NoReadStartsOnceTheCapCouldBePassed() {
        Prefetch.Budget budget = new Prefetch.Budget();
        budget.spend(20_000, Prefetch.METERED_DAY_BYTES - Prefetch.RESERVE_BYTES + 1, true);
        boolean[] read = {false};
        assertNull(Prefetch.step(budget, new java.util.HashMap<>(), "k", "t5", "m", 20_000, () -> true, pane -> {
            read[0] = true;
            return new Prefetch.Read(false, 1, new byte[] {1});
        }));
        assertFalse(read[0]);
        // Boundary: exactly one reservation left still reads.
        Prefetch.Budget edge = new Prefetch.Budget();
        edge.spend(20_000, Prefetch.METERED_DAY_BYTES - Prefetch.RESERVE_BYTES, true);
        assertTrue(edge.reserve(20_000, true));
        assertEquals(Prefetch.METERED_DAY_BYTES, edge.used);
        assertFalse(edge.reserve(20_000, true));
    }

    /** Review #44 P2-3: what was spent survives a restart, and the panes read too. */
    @Test public void ask2TheBudgetAndThePanesReadSurviveARestart() {
        FakeStore store = new FakeStore();
        Prefetch.Budget budget = new Prefetch.Budget();
        java.util.Map<String, String> seen = new java.util.HashMap<>();
        Prefetch.step(budget, seen, "k", "t5", "m", 20_000, () -> true, pane -> new Prefetch.Read(false, 5000, new byte[] {1}));
        Prefetch.save(store, seen, budget);
        Prefetch.Budget after = Prefetch.load(store, new Prefetch.Budget());
        assertEquals(20_000, after.day);
        assertEquals(5000, after.used);
        assertEquals("m", Prefetch.decode(store.seen).get("k"));
        // The panes remembered are bounded without being wiped.
        java.util.Map<String, String> many = new java.util.HashMap<>();
        for (int i = 0; i < Prefetch.MAX_SEEN + 10; i++) many.put("f" + i, "m");
        Prefetch.save(store, many, after);
        assertEquals(Prefetch.MAX_SEEN, Prefetch.decode(store.seen).size());
    }

    @Test public void theNativeResultReadsBack() {
        byte[] bytes = new byte[9 + 2];
        bytes[0] = 1;
        java.nio.ByteBuffer.wrap(bytes, 1, 8).putLong(123_456L);
        bytes[9] = 7;
        bytes[10] = 8;
        Prefetch.Read read = Prefetch.Read.parse(bytes);
        assertTrue(read.refused);
        assertEquals(123_456L, read.received);
        assertArrayEquals(new byte[] {7, 8}, read.copy);
        assertNull(Prefetch.Read.parse(new byte[9]).copy);
    }

    /** Review #44 P2-1: a read may not start when it could pass the cap. */
    @Test public void ask2AReadNeverStartsWhenItCouldPassTheCap() {
        Prefetch.Budget budget = new Prefetch.Budget();
        budget.spend(20_000, Prefetch.METERED_DAY_BYTES - 1, true);
        assertFalse(budget.allows(20_000, true));
    }

    /** Review #44 P2-2: only a later day starts a new budget. */
    @Test public void ask2TheClockGoingBackNeverResetsTheBudget() {
        Prefetch.Budget budget = new Prefetch.Budget();
        budget.spend(20_000, Prefetch.METERED_DAY_BYTES, true);
        assertFalse(budget.allows(19_999, true));
        budget.spend(19_999, 1, true);
        assertFalse(budget.allows(20_000, true));
    }
}
