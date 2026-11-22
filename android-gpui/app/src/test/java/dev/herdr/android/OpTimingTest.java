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
        assertTrue(read("UsageView").contains("OpTiming.timed(\"usage.look\""));
    }

    private static String read(String name) throws IOException {
        return new String(Files.readAllBytes(Paths.get("src/main/java/dev/herdr/android/" + name + ".java")), StandardCharsets.UTF_8);
    }

    @Test public void aFailedLookSaysTheCoresOwnWordsElseThatTheMachineCouldNotBeAsked() {
        assertEquals("herdr-core protocol: \"denied\": exec refused",
                OpTiming.reason("beads.detail", new IllegalStateException("herdr-core protocol: \"denied\": exec refused")));
        assertEquals("can't reach the machine", OpTiming.reason("beads.detail", new IllegalArgumentException("x")));
        assertEquals("can't reach the machine", OpTiming.reason("beads.detail", new IllegalStateException("  ")));
        assertEquals(OpTiming.REASON_MAX, OpTiming.reason("usage.look", new IllegalStateException("x".repeat(300))).length());
        // Untrusted daemon text: one line, no control characters, no paths.
        assertEquals("exec failed: … not found", OpTiming.clean("exec failed:\n/home/wolf/.config/x\u001b[31m not found"));
        assertEquals("cannot read …", OpTiming.clean("cannot read ~/beads/demo/.beads"));
    }
}
