package dev.herdr.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.json.JSONArray;
import org.junit.Test;

/** A bead's plan and comments, kept so it opens at once, and who works on it (synthetic). */
public final class BeadCacheTest {
    private static final String READ = "\u001e[{\"id\":\"x-1\",\"title\":\"Fix\",\"status\":\"open\",\"comment_count\":1}]\n\u001d[]\n"
            + "\u001cabc fresh\n\u001fok\n\n\u0007\n\u001d[{\"author\":\"wolf\",\"text\":\"Ship it\"}]\n\u001fok\n";

    @Test public void oneReadCarriesThePlanAndTheComments() {
        BeadsModel.Detail d = BeadCache.detail(READ);
        assertEquals("x-1", d.id);
        assertEquals("abc", d.rev);
        BeadsModel.Comments c = BeadCache.comments(READ);
        assertNull(c.error);
        assertEquals("Ship it", c.list.get(0).text);
        // A bead with no comments: the read skipped them and says so with an empty list.
        assertTrue(BeadCache.comments("\u001e[{\"id\":\"x\"}]\n\u001d[]\n\u001c\n\u001fok\n\n\u0007\n\u001d[]\n\u001fok\n").list.isEmpty());
        assertEquals("its comments weren't read", BeadCache.comments("\u001e[{\"id\":\"x\"}]\n").error);
        assertEquals("read just now", BeadCache.age(1000, 30_000));
        assertEquals("read 4m ago", BeadCache.age(0, 4 * 60_000));
    }

    @Test public void keptReadsSurviveARestartAndStayBounded() throws Exception {
        File dir = Files.createTempDirectory("beads").toFile();
        File file = new File(dir, BeadCache.FILE_NAME);
        BeadCache cache = new BeadCache(file);
        String key = BeadCache.key("host", "factory", "x-1");
        cache.put(key, new BeadCache.Entry(READ, 5_000, "17.5"), Runnable::run);
        BeadCache again = new BeadCache(file);
        again.load();
        assertEquals(READ, again.get(key).text);
        assertEquals("17.5", again.get(key).fp);
        Map<String, BeadCache.Entry> many = new HashMap<>();
        for (int i = 0; i < BeadCache.MAX + 5; i++) many.put(BeadCache.key("h", "s", "b-" + i), new BeadCache.Entry("t", 1000 + i, ""));
        BeadCache.trim(many);
        assertEquals(BeadCache.MAX, many.size());
        assertFalse(many.containsKey(BeadCache.key("h", "s", "b-0")));
        assertTrue(BeadCache.decode("garbage").isEmpty());
        assertTrue(BeadCache.decode("{\"v\":1,\"beads\":{\"no-newlines\":{\"at\":5,\"text\":\"t\"}}}").isEmpty());
    }

    @Test public void theKeptListingStaysWholeAcrossReadsThatSaySame() {
        String first = "\u001edemo\n\u001c17.5\n[{\"id\":\"demo-1\",\"title\":\"A\",\"status\":\"open\"}]\n\u001f0\n"
                + "\u001eweb\n\u001c9.1\n[{\"id\":\"web-1\",\"title\":\"B\",\"status\":\"open\"}]\n\u001f0\n";
        String next = "\u001edemo\n\u001c17.5\n\u001fsame\n"
                + "\u001eweb\n\u001c9.2\n[{\"id\":\"web-2\",\"title\":\"C\",\"status\":\"open\"}]\n\u001f0\n";
        String kept = BeadCache.keepStores(first, next);
        BeadsModel.Stores read = BeadsModel.parseStores(kept);
        assertEquals("demo-1", read.byProject.get("demo").beads.get(0).id);
        assertEquals("web-2", read.byProject.get("web").beads.get(0).id);
        assertEquals("9.2", read.byProject.get("web").fp);
        // A store no longer listed is gone; no bd is said as it is.
        assertFalse(BeadsModel.parseStores(BeadCache.keepStores(first, "\u001eweb\n\u001c9.1\n\u001fsame\n")).byProject.containsKey("demo"));
        assertTrue(BeadsModel.parseStores(BeadCache.keepStores(first, "\u001dnobd\n")).missing);
    }

    @Test public void onlyALiveAgentLabelledWithTheBeadInItsProjectIsOnIt() throws Exception {
        JSONArray panes = new JSONArray("["
                + "{\"id\":\"t2\",\"role\":\"pm\",\"project\":\"demo\",\"name\":\"demo-pm\",\"kind\":\"claude\",\"workspace_id\":\"s1\"},"
                + "{\"id\":\"t3\",\"role\":\"worker\",\"name\":\"agent-a\",\"issue\":\"demo-1.2\",\"parent\":\"t2\",\"kind\":\"claude\",\"workspace_id\":\"s1\"},"
                + "{\"id\":\"t4\",\"role\":\"worker\",\"project\":\"other\",\"name\":\"agent-b\",\"issue\":\"demo-1.2\",\"kind\":\"claude\",\"workspace_id\":\"s1\"},"
                + "{\"id\":\"t5\",\"role\":\"worker\",\"project\":\"demo\",\"name\":\"agent-c\",\"issue\":\"demo-1.3\",\"kind\":\"claude\",\"workspace_id\":\"s1\"},"
                + "{\"id\":\"t6\",\"role\":\"worker\",\"project\":\"demo\",\"name\":\"ended\",\"issue\":\"demo-1.2\",\"kind\":\"claude\",\"program\":\"zsh\",\"workspace_id\":\"s1\"}]");
        List<BeadsModel.Term> terms = BeadsModel.terms(null, null, panes);
        List<FleetTree.Member> on = PanelModel.assignees(terms, "demo-1.2", "demo");
        // t3 by its PM's project; not t4 (another project), t5 (another bead) or t6 (its agent ended).
        assertEquals(Arrays.asList("t3"), Arrays.asList(on.stream().map(m -> m.id).toArray()));
        assertTrue(PanelModel.assignees(terms, "demo-9", "demo").isEmpty());
    }
    /**
     * factory-9zz.14, the first Tasks entry that "loads forever": a real machine's listing (server:
     * 10 stores, 173 KB; macro: 674 KB, measured 11-22) is over the old 128 KiB entry cap, so put()
     * dropped it and every first entry after a start waited on a cold, full read behind whatever held
     * the core session. The listing must be kept and come back after a restart.
     */
    @Test public void aRealSizedListingIsKeptAndDrawsAfterARestart() throws Exception {
        StringBuilder listing = new StringBuilder();
        for (int s = 0; s < 10; s++) {
            listing.append("\u001estore").append(s).append("\n\u001c1795368238.").append(s).append("\n[");
            for (int b = 0; b < 160; b++) {
                if (b > 0) listing.append(',');
                listing.append("{\"id\":\"store").append(s).append('-').append(b)
                        .append("\",\"title\":\"A bead title long enough to look like a real one, number ").append(b)
                        .append("\",\"status\":\"open\",\"issue_type\":\"task\"}");
            }
            listing.append("]\n\u001f0\n");
        }
        assertTrue("as big as server's listing", listing.length() > 170_000);
        File dir = Files.createTempDirectory("beads").toFile();
        File file = new File(dir, BeadCache.FILE_NAME);
        String key = BeadCache.storesKey("host");
        new BeadCache(file).put(key, new BeadCache.Entry(listing.toString(), 5_000, ""), Runnable::run);
        BeadCache again = new BeadCache(file);
        again.load();
        assertTrue("the listing was kept", again.get(key) != null);
        BeadsModel.Stores read = BeadsModel.parseStores(again.get(key).text);
        assertEquals(10, read.byProject.size());
        assertEquals(160, read.byProject.get("store9").beads.size());
    }

    /** A bead read over the cap is kept (its plan first) rather than dropped and read again every second. */
    @Test public void aBigBeadReadIsKeptNotReadAgainForever() {
        BeadCache cache = new BeadCache(new File("/nonexistent/never-written"));
        StringBuilder big = new StringBuilder(READ);
        while (big.length() < 300_000) big.append("x");
        String key = BeadCache.key("host", "factory", "x-1");
        cache.put(key, new BeadCache.Entry(big.toString(), 5_000, ""), Runnable::run);
        assertTrue("kept", cache.get(key) != null);
        assertEquals("x-1", BeadCache.detail(cache.get(key).text).id);
        assertEquals("its comments weren't read", BeadCache.comments(cache.get(key).text).error);
    }
}
