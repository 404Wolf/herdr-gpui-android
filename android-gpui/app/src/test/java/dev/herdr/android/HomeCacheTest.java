package dev.herdr.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

/** Home's cached last view: what is kept, what is refused, and that it reads back whole. */
public final class HomeCacheTest {
    private static JSONObject poll() throws Exception {
        return new JSONObject()
                .put("state", "ready")
                .put("status", "Connected")
                .put("workspaces", new JSONArray().put(new JSONObject().put("id", "w1").put("name", "deacon")))
                .put("tabs", new JSONArray())
                .put("panes", new JSONArray().put(new JSONObject().put("pane_id", "t1")));
    }

    @Test public void the_fleet_cache_keeps_the_revision_that_listed_the_pane() throws Exception {
        JSONObject view = new JSONObject().put("panes", new JSONArray().put(new JSONObject().put("id", "t1")))
                .put("workspaces", new JSONArray());
        String a = PaneSnapshots.computer("host", "revision-a");
        String b = PaneSnapshots.computer("host", "revision-b");
        HomeCache.Entry entry = new HomeCache.Entry(view, "", false, a);
        String saved = HomeCache.encode(java.util.Collections.singletonMap("host", entry));
        HomeCache.Entry reopened = HomeCache.decode(saved).get("host");
        assertTrue(reopened.matches(a));
        assertFalse(reopened.matches(b));
        assertFalse(reopened.matches(null));
    }

    @Test public void aPollWithoutTheTerminalListIsNotATree() throws Exception {
        assertFalse(HomeCache.hasTree(null));
        assertFalse(HomeCache.hasTree(new JSONObject().put("state", "connecting")));
        assertFalse(HomeCache.hasTree(new JSONObject().put("panes", new JSONArray())));
        assertTrue(HomeCache.hasTree(poll()));
    }

    @Test public void onlyTheTreeAndGitLookAreKeptAndReadBack() throws Exception {
        Map<String, HomeCache.Entry> entries = new LinkedHashMap<>();
        entries.put("laptop", new HomeCache.Entry(poll(), "P\t/a\t/a/.git\t/a\tmain\nR\t/a/.git\nworktree /a\n\nE\n", true));
        entries.put("early", new HomeCache.Entry(new JSONObject().put("state", "connecting"), "", false));
        Map<String, HomeCache.Entry> back = HomeCache.decode(HomeCache.encode(entries));

        assertEquals(1, back.size());
        HomeCache.Entry laptop = back.get("laptop");
        assertFalse(laptop.view.has("state"));
        assertFalse(laptop.view.has("status"));
        assertEquals("t1", laptop.view.getJSONArray("panes").getJSONObject(0).getString("pane_id"));
        FleetTree.GitLook look = laptop.look();
        assertEquals("main", look.places.get("/a").branch);
        assertTrue(look.allTruncated);
    }

    @Test public void noGitLookIsNullNotEmpty() throws Exception {
        assertNull(new HomeCache.Entry(poll(), "", false).look());
    }

    @Test public void anUnreadableOrOtherVersionCacheIsEmpty() {
        assertTrue(HomeCache.decode("").isEmpty());
        assertTrue(HomeCache.decode("{not json").isEmpty());
        assertTrue(HomeCache.decode("{\"version\":2,\"hosts\":{}}").isEmpty());
        assertTrue(HomeCache.decode("{\"version\":1,\"hosts\":{\"a\":{\"view\":{}}}}").isEmpty());
    }

    @Test public void writeReplacesTheFileWhole() throws Exception {
        File dir = Files.createTempDirectory("home-cache").toFile();
        File file = new File(dir, HomeCache.FILE_NAME);
        Map<String, HomeCache.Entry> entries = new LinkedHashMap<>();
        entries.put("laptop", new HomeCache.Entry(poll(), "", false));
        HomeCache.write(file, HomeCache.encode(entries));
        HomeCache.write(file, HomeCache.encode(entries));
        assertEquals(1, HomeCache.read(file).size());
        assertFalse(new File(dir, HomeCache.FILE_NAME + ".tmp").exists());
        assertTrue(HomeCache.read(new File(dir, "missing.json")).isEmpty());
        file.delete();
        dir.delete();
    }

    @Test public void ask2AColdOfflineStartListsThePanesFromTheSealedFleetCache() throws Exception {
        File dir = Files.createTempDirectory("fleet-cache").toFile();
        File sealed = new File(dir, HomeCache.SEALED_NAME);
        javax.crypto.SecretKey key = CacheSeal.newKey();
        // The terminal merges the computer it is connected to; Home's git look stays.
        Map<String, HomeCache.Entry> home = new LinkedHashMap<>();
        home.put("server", new HomeCache.Entry(poll(), "E\n", false));
        HomeCache.writeSealed(sealed, new CacheSeal(key), HomeCache.encode(home));
        JSONObject live = poll();
        live.getJSONArray("panes").put(new JSONObject().put("pane_id", "t2").put("title", "secret-project"));
        HomeCache.merge(sealed, new CacheSeal(key), "server", new HomeCache.Entry(live, "", false));
        HomeCache.merge(sealed, new CacheSeal(key), "laptop", new HomeCache.Entry(poll(), "", false));
        assertFalse(new String(Files.readAllBytes(sealed.toPath()), java.nio.charset.StandardCharsets.ISO_8859_1)
                .contains("secret-project"));
        // Cold start, no link: the list is there, both computers, every pane.
        Map<String, HomeCache.Entry> back = HomeCache.readSealed(sealed, null, new CacheSeal(key));
        assertEquals(2, back.size());
        assertEquals(2, back.get("server").view.getJSONArray("panes").length());
        assertEquals("E\n", back.get("server").git);
        // Another key (data cleared): nothing, and the file is gone.
        assertTrue(HomeCache.readSealed(sealed, null, new CacheSeal(CacheSeal.newKey())).isEmpty());
        assertFalse(sealed.exists());
        dir.delete();
    }

    @Test public void ask2AnOfflineSessionsEmptyListsNeverReplaceTheCachedOne() throws Exception {
        // What a session polls before it reaches its core, and still after it
        // failed (offline): the lists are there, and empty.
        JSONObject offline = new JSONObject().put("state", "connecting").put("status", "transport I/O failed")
                .put("workspaces", new JSONArray()).put("tabs", new JSONArray()).put("panes", new JSONArray());
        assertTrue(HomeCache.hasTree(offline));
        assertFalse(HomeCache.listed(offline));
        assertFalse(HomeCache.listed(new JSONObject(offline.toString()).put("state", "closed")));
        // The core's list: one with terminals, or an empty one from a session that reached it.
        assertTrue(HomeCache.listed(poll()));
        assertTrue(HomeCache.listed(new JSONObject(poll().toString()).put("state", "connecting")));
        assertTrue(HomeCache.listed(new JSONObject(offline.toString()).put("state", "ready")));
        assertFalse(HomeCache.listed(null));
    }

    /** Review P3-5: Home's write keeps what the terminal listed for a computer Home has no view of. */
    @Test public void ask2HomesWriteKeepsTheTerminalsListOfAnotherComputer() throws Exception {
        File dir = Files.createTempDirectory("fleet-cache").toFile();
        File sealed = new File(dir, HomeCache.SEALED_NAME);
        CacheSeal seal = new CacheSeal(CacheSeal.newKey());
        HomeCache.merge(sealed, seal, "laptop", new HomeCache.Entry(poll(), "", false));
        Map<String, HomeCache.Entry> home = new LinkedHashMap<>();
        home.put("server", new HomeCache.Entry(poll(), "E\n", false));
        HomeCache.mergeAll(sealed, seal, home, new java.util.HashSet<>(java.util.Arrays.asList("server", "laptop")));
        Map<String, HomeCache.Entry> back = HomeCache.readSealed(sealed, null, seal);
        assertEquals(2, back.size());
        assertEquals("E\n", back.get("server").git);
        // A computer no longer saved goes.
        HomeCache.mergeAll(sealed, seal, home, new java.util.HashSet<>(java.util.Collections.singletonList("server")));
        assertEquals(java.util.Collections.singleton("server"), HomeCache.readSealed(sealed, null, seal).keySet());
    }

    /** Review mutant J3: a plain file left over never replaces a newer sealed cache. */
    @Test public void ask2ALeftoverPlainFleetFileNeverReplacesTheSealedOne() throws Exception {
        File dir = Files.createTempDirectory("fleet-cache").toFile();
        File legacy = new File(dir, HomeCache.FILE_NAME);
        File sealed = new File(dir, HomeCache.SEALED_NAME);
        CacheSeal seal = new CacheSeal(CacheSeal.newKey());
        HomeCache.merge(sealed, seal, "server", new HomeCache.Entry(poll(), "", false));
        Map<String, HomeCache.Entry> old = new LinkedHashMap<>();
        old.put("laptop", new HomeCache.Entry(poll(), "", false));
        HomeCache.write(legacy, HomeCache.encode(old));
        assertEquals(java.util.Collections.singleton("server"), HomeCache.readSealed(sealed, legacy, seal).keySet());
        assertFalse(legacy.exists());
    }

    @Test public void ask2ThePlainVersion1FleetFileIsSealedOnceThenDeleted() throws Exception {
        File dir = Files.createTempDirectory("fleet-cache").toFile();
        File legacy = new File(dir, HomeCache.FILE_NAME);
        File sealed = new File(dir, "cache-v2/" + HomeCache.SEALED_NAME);
        Map<String, HomeCache.Entry> entries = new LinkedHashMap<>();
        entries.put("laptop", new HomeCache.Entry(poll(), "", false));
        HomeCache.write(legacy, HomeCache.encode(entries));
        CacheSeal seal = new CacheSeal(CacheSeal.newKey());
        assertEquals(1, HomeCache.readSealed(sealed, legacy, seal).size());
        assertFalse(legacy.exists());
        assertTrue(sealed.isFile());
        assertEquals(1, HomeCache.readSealed(sealed, legacy, seal).size());
        // With no seal at all, no plain copy is kept either.
        HomeCache.write(legacy, HomeCache.encode(entries));
        assertTrue(HomeCache.readSealed(sealed, legacy, null).isEmpty());
        assertFalse(legacy.exists());
    }
}
