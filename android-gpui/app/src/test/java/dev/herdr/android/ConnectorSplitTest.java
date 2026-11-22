package dev.herdr.android;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import org.junit.Test;

/** Audit A1: opening a connection never runs on the thread that pumps the terminal on screen. */
public final class ConnectorSplitTest {
    private static String activity() throws Exception {
        return TerminalSource.text();
    }

    private static String between(String text, String from, String to) {
        int a = text.indexOf(from);
        int b = text.indexOf(to, a);
        assertTrue(from, a >= 0 && b > a);
        return text.substring(a, b);
    }

    @Test public void switchesAndReconnectsRunOnTheConnectorAndPumpsOnTheWorker() throws Exception {
        String gta = activity();
        String switching = between(gta, "    void switchHost(SavedRemoteHost host, String pane, boolean picked) {",
                "private String awaitHostPane(");
        assertTrue(switching.contains("connector.execute(() -> {"));
        assertFalse("a switch never waits on the pump's thread", switching.contains("worker.execute("));
        String reconnect = between(gta, "publishCurrent(\"Reconnecting…\");", "awaitHostPane(handle, host.id, null, false");
        assertTrue(reconnect.contains("connector.execute(() -> {"));
        assertTrue(gta.contains("next.poll = worker.scheduleWithFixedDelay(() -> pump(next), 0, COMMAND_POLL_MS, TimeUnit.MILLISECONDS);"));
        assertTrue("both stop with the activity", gta.contains("worker.shutdownNow();\n        connector.shutdownNow();"));
    }

    @Test public void aSwitchsWaitsAreBoundedTogether() throws Exception {
        String wait = between(activity(), "private String awaitHostPane(", "private void adoptHost(");
        assertTrue(wait.contains("final long overall = until + AWAIT_LANDING_MS;"));
        assertFalse("every landing wait is capped by the overall bound",
                wait.contains("until = SystemClock.uptimeMillis() + 20_000;"));
        assertTrue(GpuiTerminalActivity.AWAIT_LANDING_MS <= 30_000);
    }
}
