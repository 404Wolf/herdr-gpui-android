package dev.herdr.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import org.json.JSONArray;
import org.junit.Test;

/** A cached rail row opens its terminal only if the machine still has that very one (factory audit, PR 7). */
public final class RailArrivalTest {
    @Test public void theSameTerminalOpensAReusedIdNever() throws Exception {
        JSONArray cached = new JSONArray("[{\"id\":\"t5\",\"created_ms\":1000,\"name\":\"phone-rail\"}]");
        RailArrival arrival = new RailArrival("server", "t5", RailArrival.createdOf(cached, "t5"), "phone-rail");
        assertEquals(1000, arrival.createdMs);
        assertEquals(RailArrival.Verdict.GO, arrival.check(new JSONArray("[{\"id\":\"t5\",\"created_ms\":1000}]")));
        // A restarted core gave t5 to another terminal: never opened.
        assertEquals(RailArrival.Verdict.ENDED, arrival.check(new JSONArray("[{\"id\":\"t5\",\"created_ms\":2000}]")));
        assertEquals(RailArrival.Verdict.NOT_LISTED, arrival.check(new JSONArray("[]")));
        assertEquals("phone-rail isn't on server any more", arrival.refusal("server"));
        // A cache without creation times can only check the id.
        RailArrival bare = new RailArrival("server", "t5", 0, "");
        assertEquals(RailArrival.Verdict.GO, bare.check(new JSONArray("[{\"id\":\"t5\",\"created_ms\":2000}]")));
        assertEquals("That terminal isn't on that computer any more", bare.refusal(""));
    }

    @Test public void cachedRowsNeverForceTheirIdOnASwitch() throws IOException {
        String gta = new String(Files.readAllBytes(Paths.get("src/main/java/dev/herdr/android/GpuiTerminalActivity.java")), StandardCharsets.UTF_8);
        String to = gta.substring(gta.indexOf("private void switchToRailPane("), gta.indexOf("private volatile RailArrival railArriving;"));
        // Mirrored (live) rows open directly; cached ones switch without the id and are checked on arrival.
        assertTrue(to.contains("if (mirrored || paneId == null || paneId.isEmpty()) { switchHost(host, paneId); return; }"));
        assertTrue(to.contains("switchHost(host, null);"));
        assertTrue(gta.contains("checkRailArrival(current);"));
        // The rail's own "open on another machine" paths all go through it.
        String rail = gta.substring(gta.indexOf("private void openRailPane("), gta.indexOf("private void openRailPane(") + 400);
        assertTrue(rail.contains("else switchToRailPane(host, paneId);"));
        assertFalse(gta.contains("() -> switchHost(holder.host, holder.paneId)"));
    }
}
