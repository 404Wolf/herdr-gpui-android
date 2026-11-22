package dev.herdr.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import org.junit.Test;

/** A pick during a switch is kept, not dropped (factory audit, PR 6). */
public final class HostPickTest {
    @Test public void theLatestPickWaitsAndRunsOnce() {
        HostPick.Queue queue = new HostPick.Queue();
        assertFalse(queue.waiting());
        queue.keep(new HostPick("server", "t1", true, null));
        queue.keep(new HostPick("laptop", null, true, null));
        assertTrue(queue.waiting());
        HostPick pick = queue.take();
        assertEquals("laptop", pick.hostId);
        assertNull(queue.take());
        assertEquals("Still switching to server · then laptop", HostPick.note("server", "laptop"));
        assertEquals("Still switching to server…", HostPick.note("server", "server"));
        assertEquals("Still switching to another computer…", HostPick.note("", null));
    }

    @Test public void everySwitchsEndRunsTheWaitingPickAndPinsOpenFromAnyState() throws IOException {
        String gta = TerminalSource.text();
        int start = gta.indexOf("    void switchHost(SavedRemoteHost host, String pane, boolean picked) {");
        String head = gta.substring(start, gta.indexOf("pendingShell.clear();", start));
        // A switch in flight keeps the pick instead of returning with nothing done.
        assertTrue(head.contains("hostPicks.keep(new HostPick(host.id, pane, picked, null));"));
        // Four ends of a switch, and the refused one (audit A3).
        assertEquals(5, count(gta, "runWaitingPick();"));
        String pin = gta.substring(gta.indexOf("    void openPin(Pins.Pin pin)"), gta.indexOf("    volatile Pins.Pin pinAwaitingHello;"));
        assertTrue(pin.contains("hostPicks.keep(new HostPick(pin.profile, pin.key.address, true, pin));"));
        assertTrue(pin.contains("pinAwaitingHello = pin;"));
        assertTrue(gta.contains("Pins.Pin waiting = rail.pinAwaitingHello;"));
        // Pins follow names in the poll, beside observe, not in the rail's render.
        String render = gta.substring(gta.indexOf("private void addHostRows("), gta.indexOf("private void addHostRows(") + 4000);
        assertFalse(render.contains(".relabel("));
        int observe = gta.indexOf("Pins.shared(this).observe(current.machine, current.panes);");
        assertTrue(gta.indexOf("Pins.shared(this).relabel(current.machine, names);", observe) - observe < 600);
    }

    private static int count(String s, String what) {
        int n = 0;
        for (int at = s.indexOf(what); at >= 0; at = s.indexOf(what, at + 1)) n++;
        return n;
    }
}
