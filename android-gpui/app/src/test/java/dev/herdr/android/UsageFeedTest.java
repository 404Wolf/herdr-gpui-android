package dev.herdr.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.HashMap;
import java.util.Map;
import org.junit.Test;

/** When the usage feed asks, what it keeps, and how it logs a look's time (synthetic accounts). */
public final class UsageFeedTest {
    private static final String REPLY = "{\"queued\":true,\"usage\":{\"schema_version\":1,\"accounts\":[{\"label\":\"demo-a\","
            + "\"provider\":\"claude\",\"active\":true,\"usage_status\":\"ok\",\"windows\":[]}]},"
            + "\"timing\":{\"rpc_ms\":72,\"exec_ms\":60}}";

    private static UsageFeed.Pace watched() {
        UsageFeed.Pace pace = new UsageFeed.Pace();
        pace.online = true;
        pace.watch(UsageFeed.Watch.SHEET, true);
        return pace;
    }

    @Test public void nothingAsksUnlessSomeoneWatchesInFrontAndOnline() {
        UsageFeed.Pace pace = new UsageFeed.Pace();
        pace.online = true;
        assertEquals(-1, pace.delay(0));
        pace.watch(UsageFeed.Watch.RAIL, true);
        assertEquals(0, pace.delay(0));
        pace.paused = true;
        assertEquals(-1, pace.delay(0));
        pace.paused = false;
        pace.online = false;
        assertEquals(-1, pace.delay(0));
        pace.online = true;
        pace.watch(UsageFeed.Watch.RAIL, false);
        assertEquals(-1, pace.delay(0));
    }

    @Test public void oneLookAtATimeThenEveryTenSeconds() {
        UsageFeed.Pace pace = watched();
        assertEquals(0, pace.delay(1_000));
        pace.started();
        assertEquals(-1, pace.delay(1_000));
        // The rail starts watching too while the sheet's look is out: still one look.
        pace.watch(UsageFeed.Watch.RAIL, true);
        assertEquals(-1, pace.delay(1_500));
        pace.ended(UsageFeed.Outcome.DATA, 2_000);
        assertEquals(UsageFeed.Pace.CADENCE_MS, pace.delay(2_000));
        assertEquals(4_000, pace.delay(8_000));
        assertEquals(0, pace.delay(13_000));
        // Closing the sheet and opening it again does not ask early: the rail's look is 3 s old.
        pace.watch(UsageFeed.Watch.SHEET, false);
        pace.watch(UsageFeed.Watch.SHEET, true);
        pace.started();
        pace.ended(UsageFeed.Outcome.DATA, 20_000);
        assertEquals(7_000, pace.delay(23_000));
    }

    @Test public void aBusyOrUnreachableMachineIsAskedLessAndLessOften() {
        UsageFeed.Pace pace = watched();
        pace.started();
        pace.ended(UsageFeed.Outcome.BUSY, 0);
        long first = pace.delay(0);
        pace.started();
        pace.ended(UsageFeed.Outcome.BUSY, 0);
        long second = pace.delay(0);
        pace.started();
        pace.ended(UsageFeed.Outcome.UNREACHABLE, 0);
        long third = pace.delay(0);
        assertTrue(first >= UsageFeed.Pace.CADENCE_MS);
        assertTrue(second > first && third > second);
        assertTrue(third <= 60_000);
        // An answer puts it back on the cadence.
        pace.started();
        pace.ended(UsageFeed.Outcome.DATA, 0);
        assertEquals(UsageFeed.Pace.CADENCE_MS, pace.delay(0));
        // So does selecting another machine, which is asked at once.
        pace.started();
        pace.ended(UsageFeed.Outcome.BUSY, 0);
        pace.reset();
        assertEquals(0, pace.delay(0));
    }

    @Test public void keptAnswersSurviveARestartBoundedAndAreDroppedWhenDamaged() {
        Map<String, Object[]> kept = new HashMap<>();
        kept.put("host-a", new Object[] {REPLY, 1_000L});
        Map<String, Object[]> again = UsageFeed.decode(UsageFeed.encode(kept));
        assertEquals(REPLY, again.get("host-a")[0]);
        assertEquals(1_000L, again.get("host-a")[1]);
        assertTrue(UsageFeed.decode("not json").isEmpty());
        assertTrue(UsageFeed.decode("{\"v\":2,\"hosts\":{}}").isEmpty());
        // Only replies that list accounts are kept: never an error, a missing aswap or garbage.
        assertTrue(UsageFeed.decode("{\"v\":1,\"hosts\":{\"h\":{\"at\":5,\"reply\":\"{\\\"queued\\\":true,\\\"missing\\\":true}\"}}}").isEmpty());
        assertTrue(UsageFeed.decode("{\"v\":1,\"hosts\":{\"h\":{\"at\":0,\"reply\":" + org.json.JSONObject.quote(REPLY) + "}}}").isEmpty());
        for (int i = 0; i < UsageFeed.MAX_HOSTS + 4; i++) kept.put("host-" + i, new Object[] {REPLY, 2_000L + i});
        UsageFeed.trim(kept);
        assertEquals(UsageFeed.MAX_HOSTS, kept.size());
        assertFalse(kept.containsKey("host-a"));
        assertFalse(kept.containsKey("host-0"));
        assertTrue(kept.containsKey("host-" + (UsageFeed.MAX_HOSTS + 3)));
    }

    @Test public void aLooksTimeIsSplitIntoWhereItWent() {
        assertEquals("usage.look 915 ms: queued 3 ms, session 840 ms, rpc 72 ms, aswap 60 ms",
                UsageTiming.of(3, 912, REPLY).line());
        assertEquals("usage.look 20 ms: queued 0 ms, call 20 ms", UsageTiming.of(0, 20, null).line());
        assertEquals("usage.look 30 ms: queued 0 ms, session 0 ms, rpc 30 ms",
                UsageTiming.of(0, 30, "{\"timing\":{\"rpc_ms\":30,\"exec_ms\":null}}").line());
        assertEquals("usage.sheet kept rows 0 ms after open", UsageTiming.drawn("kept", 0));
        assertEquals("usage.sheet fresh rows 741 ms after open: the main thread took 560 ms to run it, drawing 30 ms",
                UsageTiming.drawn("fresh", 741) + UsageTiming.onMain(560, 30));
        assertEquals(": drawing 4 ms", UsageTiming.onMain(-1, 4));
    }
}
