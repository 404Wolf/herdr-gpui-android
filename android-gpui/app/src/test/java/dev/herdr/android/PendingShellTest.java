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

    @Test public void tenSecondsWithoutATerminalOrARefusalFailsItWithWhy() {
        PendingShell shell = new PendingShell();
        shell.start("h1", panes("t1"), 1000);
        assertFalse(shell.observe("h1", panes("t1"), 1000 + PendingShell.TIMEOUT_MS - 1));
        assertTrue(shell.observe("h1", panes("t1"), 1000 + PendingShell.TIMEOUT_MS));
        assertEquals("shell ✕", shell.pill());
        assertEquals("no terminal after 10 s", shell.reason());
        assertTrue(shell.described("demo").startsWith("Couldn't start a shell on demo: no terminal after 10 s"));
        // A terminal arriving late no longer flips a failed pill back.
        assertFalse(shell.observe("h1", panes("t1", "t5"), 20_000));
        shell.clear();
        assertNull(shell.pill());

        shell.start("h1", panes(), 0);
        shell.failed("terminal.run: denied");
        assertEquals(PendingShell.State.FAILED, shell.state());
        assertEquals("terminal.run: denied", shell.reason());
        shell.failed("later");
        assertEquals("terminal.run: denied", shell.reason());
    }

    @Test public void theStripShowsItAndTheBarIsOneLayoutAtEveryWidth() throws IOException {
        String rail = new String(Files.readAllBytes(Paths.get("src/main/java/dev/herdr/android/GpuiTerminalActivity.java")),
                StandardCharsets.UTF_8);
        int start = rail.indexOf("private void refreshTabStrip(");
        String strip = rail.substring(start, rail.indexOf("private boolean tabCompact;", start));
        assertTrue(strip.contains("+ \"|\" + pending"));
        assertTrue(strip.indexOf("pendingShell.pill()") < strip.indexOf("windowChip(\"+\""));
        int bar = rail.indexOf("private View createShortcutBar(");
        String shortcut = rail.substring(bar, rail.indexOf("LinearLayout keys = new LinearLayout(this);", bar));
        assertFalse(shortcut.contains("ic_bar_panes"));
        assertFalse(shortcut.contains("ic_bar_view"));
        assertTrue(shortcut.contains("ic_bar_more"));
        assertFalse(shortcut.contains("if (compact)"));
        int more = rail.indexOf("private void showMoreActions(");
        String menu = rail.substring(more, rail.indexOf("menu.setForceShowIcon(true);", more));
        assertTrue(menu.indexOf("\"Spaces & panes\"") < menu.indexOf("if (navigation && !shellMode())"));
    }
}
