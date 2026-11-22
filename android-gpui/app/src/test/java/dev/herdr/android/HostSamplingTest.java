package dev.herdr.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import org.junit.Test;

/** The rail's mirrors read only what it shows, at full pace (factory audit, PR 8, Java side). */
public final class HostSamplingTest {
    @Test public void hiddenMachinesAreReadSlowly() {
        // Connected with three machines saved: only the one on screen is on the rail.
        assertTrue(HostSampling.onlyCurrent("server", true, 3));
        assertTrue(HostSampling.shown("server", "server", true, 3));
        assertFalse(HostSampling.shown("laptop", "server", true, 3));
        // Not connected, or one machine: every machine shows.
        assertTrue(HostSampling.shown("laptop", "server", false, 3));
        assertTrue(HostSampling.shown("laptop", null, true, 3));
        assertTrue(HostSampling.shown("server", "server", true, 1));
        assertEquals(3_000, HostSampling.every(true));
        assertEquals(20_000, HostSampling.every(false));
    }

    @Test public void aHiddenMachinesChangeNeverRebuildsTheRail() throws IOException {
        String gta = TerminalSource.text();
        String sample = gta.substring(gta.indexOf("private void sampleHost(String hostId, HostSample sample) {"), gta.indexOf("private void sampleHost(String hostId, HostSample sample) {") + 4000);
        assertTrue(sample.contains("sample.nextPollAt = now + HostSampling.every(shown) - 500;"));
        assertTrue(sample.contains("if (shown) {\n                    sidebarSignature = \"\";"));
        assertFalse(sample.contains("if (sample.connected) sample.connected = true;"));
        // The rail's signature skips machines it doesn't show.
        assertTrue(gta.contains("if (!a.railShows(host.id)) continue;"));
    }
}
