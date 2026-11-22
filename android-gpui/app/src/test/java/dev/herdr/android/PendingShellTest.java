package dev.herdr.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import org.junit.Test;

/** The quick shell's pill: starting until a new terminal shows, else failed with why (factory-setup.29). */
public final class PendingShellTest {
    private static Set<String> panes(String... ids) {
        return new HashSet<>(Arrays.asList(ids));
    }

    @Test public void aNewTerminalEndsTheWait() {
        PendingShell shell = new PendingShell();
        assertNull(shell.pill());
        shell.start("h1", panes("t1", "t2"), 1000);
        assertEquals("shell ◌", shell.pill());
        assertEquals("Starting a shell on demo", shell.described("demo"));
        assertFalse(shell.observe("h1", panes("t1", "t2"), 5000));
        // Another machine's view changes nothing.
        assertFalse(shell.observe("h2", panes("t9"), 5000));
        assertTrue(shell.observe("h1", panes("t1", "t2", "t3"), 6000));
        assertNull(shell.pill());
    }

    @Test public void tenSecondsWithoutATerminalIsSlowNotFailedAndALateShellStillEndsIt() {
        PendingShell shell = new PendingShell();
        shell.start("h1", panes("t1"), 1000);
        assertFalse(shell.observe("h1", panes("t1"), 1000 + PendingShell.TIMEOUT_MS - 1));
        assertTrue(shell.observe("h1", panes("t1"), 1000 + PendingShell.TIMEOUT_MS));
        assertEquals(PendingShell.State.SLOW, shell.state());
        assertEquals("shell ◌ slow", shell.pill());
        assertTrue(shell.described("demo").startsWith("Still starting a shell on demo"));
        assertFalse(shell.observe("h1", panes("t1"), 20_000));
        // The core was only slow: the shell shows up, and the pill goes (no second shell is offered).
        assertTrue(shell.observe("h1", panes("t1", "t5"), 30_000));
        assertNull(shell.pill());
    }

    @Test public void onlyTheCoresRefusalFailsItWithWhy() {
        PendingShell shell = new PendingShell();
        shell.start("h1", panes(), 0);
        shell.failed("terminal.run: denied");
        assertEquals(PendingShell.State.FAILED, shell.state());
        assertEquals("shell ✕", shell.pill());
        assertEquals("terminal.run: denied", shell.reason());
        assertTrue(shell.described("demo").startsWith("Couldn't start a shell on demo: terminal.run: denied"));
        shell.failed("later");
        assertEquals("terminal.run: denied", shell.reason());
        // A failed start is over: a terminal that arrives later does not touch it.
        assertFalse(shell.observe("h1", panes("t9"), 5000));
        shell.clear();
        assertNull(shell.pill());
        // Slow, then refused: failed.
        shell.start("h1", panes(), 0);
        shell.observe("h1", panes(), PendingShell.TIMEOUT_MS);
        shell.failed("gone");
        assertEquals(PendingShell.State.FAILED, shell.state());
    }

    @Test public void theStripShowsItAndItsTimerLeavesOtherHostsAlone() throws IOException {
        String rail = source();
        int start = rail.indexOf("    void refreshTabStrip(");
        String strip = rail.substring(start, rail.indexOf("private boolean tabCompact;", start));
        assertTrue(strip.contains("+ \"|\" + pending"));
        assertTrue(strip.indexOf("pendingShell.pill()") < strip.indexOf("windowChip(\"+\""));
        // Only new shells end the wait; the pill is never drawn as the active window.
        assertTrue(strip.contains("paneIds(current, true)"));
        assertTrue(strip.contains("windowChip(pending, false, \"\", false)"));
        assertTrue(strip.contains("announceForAccessibility"));
        int quick = rail.indexOf("private void startQuickShell(");
        String timer = rail.substring(quick, rail.indexOf("private void showPendingShellFailure(", quick));
        assertTrue(timer.contains("if (destroyed || host == null || !host.equals(currentHostId)) return;"));
    }

    static String source() throws IOException {
        return TerminalSource.text();
    }
}
