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
}
