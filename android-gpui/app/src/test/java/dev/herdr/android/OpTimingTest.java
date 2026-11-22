package dev.herdr.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

/** A blocking phone op's time is logged, slow ones flagged, and its reply never (PR #55's finding 3). */
public final class OpTimingTest {
    @Test public void eachOpLogsItsNameAndTimeEvenWhenItFails() {
        List<String> log = new ArrayList<>();
        long[] now = {1000};
        String reply = OpTiming.timed("usage.look", () -> { now[0] += 120; return "{\"secret\":1}"; }, () -> now[0], log::add);
        assertEquals("{\"secret\":1}", reply);
        assertEquals("usage.look 120 ms", log.get(0));
        try {
            OpTiming.timed("beads.stores", () -> { now[0] += 900; throw new IllegalStateException("switched"); }, () -> now[0], log::add);
        } catch (IllegalStateException expected) { }
        assertEquals("beads.stores 900 ms (slow: input waited)", log.get(1));
        for (String line : log) assertTrue(!line.contains("secret"));
    }

    @Test public void theUsageLookIsTimed() throws IOException {
        // Split into where its time went (UsageTiming), never with the reply itself.
        String usage = read("UsageView") + (Files.exists(Paths.get("src/main/java/dev/herdr/android/UsageFeed.java")) ? read("UsageFeed") : "");
        assertTrue(usage.contains("UsageTiming.of("));
        assertTrue(!usage.contains("log(reply") && !usage.contains("+ reply"));
    }

    private static String read(String name) throws IOException {
        return new String(Files.readAllBytes(Paths.get("src/main/java/dev/herdr/android/" + name + ".java")), StandardCharsets.UTF_8);
    }

    @Test public void aFailedLookSaysWhatHappenedByItsKindNeverItsText() {
        // The words come from the kind (ErrorWords), not from what the exception says.
        assertEquals("the computer didn't allow it",
                OpTiming.reason("beads.detail", new RemoteError(RemoteError.REFUSED, "denied", "herdr-core protocol: exec refused")));
        assertEquals("the computer is busy; trying again",
                OpTiming.reason("usage.look", new RemoteError(RemoteError.REFUSED, "unavailable", "16 execs are running already")));
        assertEquals("Can't reach the computer", OpTiming.reason("beads.detail", new IllegalArgumentException("x")));
        assertEquals("Can't reach the computer", OpTiming.reason("beads.detail", new IllegalStateException("unavailable: whatever")));
        // Untrusted daemon text: one line, no control characters, no paths.
        assertEquals("exec failed: … not found", OpTiming.clean("exec failed:\n/home/wolf/.config/x\u001b[31m not found"));
        assertEquals("cannot read …", OpTiming.clean("cannot read ~/beads/demo/.beads"));
    }

    @Test public void aBusyCoreIsRetriedWithBackoffNotCalledUnreachable() {
        // What the phone logged when every beads read failed (FINDINGS, 2026-11-22).
        // Decided by kind, never by the words (audit E).
        assertTrue(RemoteError.busy(new RemoteError("refused", "unavailable", "16 execs are running already")));
        assertTrue(RemoteError.busy(new RemoteError("busy", "", "The computer is still busy")));
        assertTrue(!RemoteError.busy(new RemoteError("refused", "denied", "exec refused")));
        assertTrue(!RemoteError.busy(new RemoteError("closed", "", "Remote session is closed")));
        assertTrue("the words alone never count", !RemoteError.busy(
                new IllegalStateException("herdr-core protocol: \"unavailable\": 16 execs are running already")));
        assertTrue(!RemoteError.busy(null));
        assertEquals("timeout", RemoteError.kindOf(new RemoteError("timeout", "", "x")));
        assertEquals("", RemoteError.kindOf(new RuntimeException("x")));
        assertEquals(5_000, OpTiming.busyBackoff(0));
        assertEquals(10_000, OpTiming.busyBackoff(1));
        assertEquals(40_000, OpTiming.busyBackoff(3));
        assertEquals(60_000, OpTiming.busyBackoff(9));
    }
}
