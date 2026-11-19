package dev.herdr.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
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
        Prefetch.Budget budget = new Prefetch.Budget();
        long day = 20_000;
        assertTrue(budget.allows(day, true));
        budget.spend(day, 19L * 1024 * 1024, true);
        assertTrue(budget.allows(day, true));
        budget.spend(day, 2L * 1024 * 1024, true);
        assertFalse(budget.allows(day, true));
        // Wi-Fi is not counted and never stops.
        budget.spend(day, 500L * 1024 * 1024, false);
        assertTrue(budget.allows(day, false));
        // A new day starts again.
        assertTrue(budget.allows(day + 1, true));
        budget.spend(day + 1, 1, true);
        assertEquals(1, budget.used);
        assertEquals(20L * 1024 * 1024, Prefetch.METERED_DAY_BYTES);
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
