package dev.herdr.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.List;
import org.junit.Test;

/** One Deacon flow for Home and the rail (factory audit, PR 10), without a window. */
public final class DeaconFlowTest {
    private static FleetTree.Member deacon(String id, String status, String program) {
        FleetTree.Member m = new FleetTree.Member();
        m.id = id;
        m.role = "deacon";
        m.status = status;
        m.program = program;
        m.kind = "claude";
        return m;
    }

    @Test public void oneStartAtATimeAndAFreshOneIsPendingUntilClaudeRuns() {
        DeaconFlow flow = new DeaconFlow();
        assertTrue(flow.begin("server"));
        assertFalse(flow.begin("server"));
        assertEquals(DeaconFlow.Started.PENDING, flow.started("server", "t9", true, null, 1_000));
        // Still busy: neither screen launches a second.
        assertTrue(flow.of("server").busy());
        assertFalse(flow.begin("server"));
        List<FleetTree.Member> none = Collections.emptyList();
        assertEquals(DeaconFlow.Check.WAITING, flow.check("server", "server", none, 3_000));
        List<FleetTree.Member> running = Collections.singletonList(deacon("t9", "idle", "claude"));
        assertEquals(DeaconFlow.Check.RUNNING, flow.check("server", "server", running, 4_000));
        assertFalse(flow.of("server").busy());
        assertEquals("", flow.of("server").error);
    }

    @Test public void aLaunchThatDiesOrNeverStartsSaysWhy() {
        DeaconFlow flow = new DeaconFlow();
        flow.begin("laptop");
        flow.started("laptop", "t3", true, null, 0);
        assertEquals(DeaconFlow.Check.FAILED, flow.check("laptop", "laptop", Collections.emptyList(), 6_000));
        assertEquals(DeaconFlow.stoppedRightAway("laptop"), flow.of("laptop").error);
        flow.begin("laptop");
        flow.started("laptop", "t4", true, null, 0);
        List<FleetTree.Member> shell = Collections.singletonList(deacon("t4", "", "zsh"));
        assertEquals(DeaconFlow.Check.FAILED, flow.check("laptop", "laptop", shell, DeaconFlow.START_MS + 1));
        assertEquals(DeaconFlow.didNotStart("laptop"), flow.of("laptop").error);
        // No terminal back: a failure with the core's words.
        flow.begin("laptop");
        assertEquals(DeaconFlow.Started.FAILED, flow.started("laptop", "", false, "denied", 0));
        assertEquals("denied", flow.of("laptop").error);
        assertEquals("Could not start the Deacon: denied. Tap to try again.", DeaconFlow.couldNotStart("denied"));
        // An existing one is opened, not pending.
        flow.begin("laptop");
        assertEquals(DeaconFlow.Started.EXISTING, flow.started("laptop", "t1", false, null, 0));
        assertFalse(flow.of("laptop").busy());
    }

    @Test public void newProjectIsOneSendPerTap() {
        DeaconFlow flow = new DeaconFlow();
        assertTrue(flow.ask("server"));
        assertFalse(flow.ask("server"));
        flow.asked("server", "it did not answer");
        assertEquals("it did not answer", flow.of("server").askError);
        assertTrue(flow.ask("server"));
        assertEquals("", flow.of("server").askError);
    }

    @Test public void homeAndTheRailDriveTheSameSharedFlow() throws IOException {
        String home = new String(Files.readAllBytes(Paths.get("src/main/java/dev/herdr/android/HomeActivity.java")), StandardCharsets.UTF_8);
        String rail = new String(Files.readAllBytes(Paths.get("src/main/java/dev/herdr/android/GpuiTerminalActivity.java")), StandardCharsets.UTF_8);
        assertTrue(home.contains("private final DeaconFlow deaconFlow = DeaconFlow.shared();"));
        assertTrue(rail.contains("private final DeaconFlow deaconFlow = DeaconFlow.shared();"));
        for (String gone : new String[] {"railDeaconPending", "railDeaconBusy", "railProjectBusy"}) assertFalse(rail.contains(gone));
        for (String gone : new String[] {"machine.deaconBusy", "machine.deaconPending", "machine.projectBusy", "machine.deaconError"}) {
            assertFalse(home.contains(gone));
        }
        assertTrue(DeaconFlow.shared() == DeaconFlow.shared());
    }
}
