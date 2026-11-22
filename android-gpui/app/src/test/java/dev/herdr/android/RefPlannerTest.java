package dev.herdr.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import java.util.Arrays;
import org.junit.Test;

/** PR-F: referenced files are read only when the live terminal cannot notice, and each rarely. */
public final class RefPlannerTest {
    private static final MachineKey A = MachineKey.of("core-a", "SHA256:host-a");

    private static RefPlanner.Now at(long ms) {
        return new RefPlanner.Now(ms, true, true, true, false, 0);
    }

    @Test public void oneReadAtATimeSpacedAndEachFileOnce() {
        RefPlanner planner = new RefPlanner();
        planner.offer(1, A, "t5", Arrays.asList("a.md", "b.md", "a.md"), 0);
        assertEquals(2, planner.waiting());
        RefPlanner.Ref first = planner.next(at(10_000));
        assertEquals("a.md", first.path);
        // In flight: no second read.
        assertNull(planner.next(at(10_000 + RefPlanner.SPACING_MS)));
        planner.done(first, 10_500);
        // Spaced from the last start.
        assertNull(planner.next(at(10_000 + RefPlanner.SPACING_MS - 1)));
        assertEquals("b.md", planner.next(at(10_000 + RefPlanner.SPACING_MS)).path);
        // Seen again soon after: not read again.
        planner.offer(1, A, "t5", Arrays.asList("a.md"), 20_000);
        assertEquals(0, planner.waiting());
        // After the refresh time, it is.
        planner.offer(1, A, "t5", Arrays.asList("a.md"), 10_500 + RefPlanner.REFRESH_MS);
        assertEquals(1, planner.waiting());
    }

    @Test public void aNewLinkReadsEveryFileAgain() {
        RefPlanner planner = new RefPlanner();
        planner.offer(1, A, "t5", Arrays.asList("a.md"), 0);
        RefPlanner.Ref ref = planner.next(at(10_000));
        planner.done(ref, 10_100);
        planner.offer(1, A, "t5", Arrays.asList("a.md"), 11_000);
        assertEquals(0, planner.waiting());
        planner.offer(2, A, "t5", Arrays.asList("a.md"), 11_000);
        assertEquals(1, planner.waiting());
    }

    @Test public void neverWhileTypingScrollingMeteredOfflineOrInTheBackground() {
        RefPlanner planner = new RefPlanner();
        planner.offer(1, A, "t5", Arrays.asList("a.md"), 0);
        long t = 10_000;
        assertNull(planner.next(new RefPlanner.Now(t, false, true, true, false, 0)));
        assertNull(planner.next(new RefPlanner.Now(t, true, false, true, false, 0)));
        assertNull(planner.next(new RefPlanner.Now(t, true, true, false, false, 0)));
        assertNull(planner.next(new RefPlanner.Now(t, true, true, true, true, 0)));
        assertNull(planner.next(new RefPlanner.Now(t, true, true, true, false, t - RefPlanner.QUIET_AFTER_INPUT_MS + 1)));
        assertNotNull(planner.next(new RefPlanner.Now(t, true, true, true, false, t - RefPlanner.QUIET_AFTER_INPUT_MS)));
    }

    @Test public void urlsAndOversizeNamesAreNeverQueuedAndTheQueueIsBounded() {
        RefPlanner planner = new RefPlanner();
        planner.offer(1, A, "t5", Arrays.asList("https://x.dev/a.md", "", null), 0);
        assertEquals(0, planner.waiting());
        String[] many = new String[RefPlanner.MAX_QUEUED + 10];
        for (int i = 0; i < many.length; i++) many[i] = "f" + i + ".md";
        planner.offer(1, A, "t5", Arrays.asList(many), 0);
        assertEquals(RefPlanner.MAX_QUEUED, planner.waiting());
    }

    @Test public void aReadThatNeverEndsHoldsUpOthersOnlyUntilItsTimeout() {
        RefPlanner planner = new RefPlanner();
        planner.offer(1, A, "t5", Arrays.asList("a.md", "b.md"), 0);
        assertNotNull(planner.next(at(10_000)));
        assertNull(planner.next(at(10_000 + RefPlanner.READ_TIMEOUT_MS - 1)));
        assertEquals("b.md", planner.next(at(10_000 + RefPlanner.READ_TIMEOUT_MS)).path);
    }
}
