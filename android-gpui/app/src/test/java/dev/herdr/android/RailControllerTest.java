package dev.herdr.android;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import org.junit.Test;

/** The rail lives in RailController, not in the terminal activity (factory audit, PR 11). */
public final class RailControllerTest {
    @Test public void theActivityHoldsOneRailAndNoneOfItsRenderer() throws IOException {
        String gta = new String(Files.readAllBytes(Paths.get("src/main/java/dev/herdr/android/GpuiTerminalActivity.java")),
                StandardCharsets.UTF_8);
        String rail = TerminalSource.rail();
        assertTrue(gta.contains("final RailController rail = new RailController(this);"));
        for (String moved : new String[] {"void refreshSidebar(boolean force)", "void addHostRows(", "void railStartDeacon(",
                "void openRailWorktree(", "void showMachinePicker()", "RailHostRow createRailHostRow(", "railHostRows ="}) {
            assertFalse(moved, gta.contains(moved));
            assertTrue(moved, rail.contains(moved));
        }
        // The rail's state is its own: rows, folds, pins, the Deacon flow.
        assertTrue(rail.contains("PinsRail pinsRail;"));
        assertTrue(rail.contains("private final DeaconFlow deaconFlow = DeaconFlow.shared();"));
        assertFalse(gta.contains("DeaconFlow"));
    }
}
