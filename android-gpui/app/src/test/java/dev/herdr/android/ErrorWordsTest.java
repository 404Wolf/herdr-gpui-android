package dev.herdr.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import org.junit.Test;

/** A remote failure's words come from its kind alone (factory audit, PR 9). */
public final class ErrorWordsTest {
    private static String say(String kind, String core) {
        return ErrorWords.of(new RemoteError(kind, core, "anything at all, never shown"), "server");
    }

    @Test public void eachKindHasItsOwnWords() {
        assertEquals("server is busy; trying again", say(RemoteError.BUSY, ""));
        assertEquals("server is busy; trying again", say(RemoteError.REFUSED, "unavailable"));
        assertEquals("That isn't on server any more", say(RemoteError.REFUSED, "not_found"));
        assertEquals("server didn't allow it", say(RemoteError.REFUSED, "denied"));
        assertEquals("server refused it", say(RemoteError.REFUSED, "weird"));
        assertEquals("server didn't answer in time; it may still finish there", say(RemoteError.TIMEOUT, ""));
        assertEquals("The connection to server ended", say(RemoteError.CLOSED, ""));
        assertEquals("Can't reach server", say(RemoteError.NETWORK, ""));
        assertEquals("Too much is going on with server; try again in a moment", say(RemoteError.CAPACITY, ""));
        assertEquals("server answered something this app can't read", say(RemoteError.PROTOCOL, ""));
        assertEquals("That isn't something server can do here", say(RemoteError.INVALID, ""));
        assertEquals("Something went wrong on this phone", say(RemoteError.LOCAL, ""));
        assertEquals("Can't reach server", ErrorWords.of(new IllegalStateException("x"), "server"));
        assertEquals("Can't reach the computer", ErrorWords.of(null, null));
        // No words are ever taken from the failure's own message.
        assertFalse(say(RemoteError.REFUSED, "denied").contains("anything"));
        assertTrue(ErrorWords.retries(new RemoteError(RemoteError.BUSY, "", "")));
        assertFalse(ErrorWords.retries(new RemoteError(RemoteError.TIMEOUT, "", "")));
    }

    @Test public void theRailAndHomeSayItByKind() throws IOException {
        String gta = TerminalSource.text();
        String home = new String(Files.readAllBytes(Paths.get("src/main/java/dev/herdr/android/HomeActivity.java")), StandardCharsets.UTF_8);
        assertTrue(gta.contains("failure = ErrorWords.of(error, target.hostName);"));
        assertTrue(home.contains("failure = ErrorWords.of(error, host.name);"));
        String timing = new String(Files.readAllBytes(Paths.get("src/main/java/dev/herdr/android/OpTiming.java")), StandardCharsets.UTF_8);
        String reason = timing.substring(timing.indexOf("static String reason("), timing.indexOf("static final long BUSY_FIRST_MS"));
        assertFalse(reason.contains("getMessage()"));
    }

    @Test public void t4g3BusyBeads_anUnreachableMachineIsNeverBusyAndIsNamed() {
        // herdr-core says "unavailable" for both; the Rust boundary tells unreachable apart.
        assertEquals("server is busy; trying again", say(RemoteError.REFUSED, "unavailable"));
        assertEquals("Can't reach server", say(RemoteError.REFUSED, "unreachable"));
        assertFalse(ErrorWords.retries(new RemoteError(RemoteError.REFUSED, "unreachable", "")));
        assertTrue(ErrorWords.retries(new RemoteError(RemoteError.REFUSED, "unavailable", "")));
        // Timed out is its own words, neither busy nor unreachable.
        assertTrue(say(RemoteError.TIMEOUT, "").contains("didn't answer in time"));
        // The panel's and the sync's reasons name the machine.
        assertEquals("Can't reach laptop", OpTiming.reason("sync",
                new RemoteError(RemoteError.REFUSED, "unreachable", "laptop is unreachable"), "laptop"));
    }
}
