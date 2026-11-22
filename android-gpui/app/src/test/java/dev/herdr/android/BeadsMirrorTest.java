package dev.herdr.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.zip.GZIPOutputStream;
import org.json.JSONObject;
import org.junit.Test;

/** The whole-store beads mirror, the panel's reads through the engine, and its files (synthetic beads). */
public final class BeadsMirrorTest {
    static final String EXPORT = "{\"id\":\"demo-1\",\"title\":\"Epic\",\"status\":\"open\",\"issue_type\":\"epic\"}\n"
            + "{\"id\":\"demo-1.1\",\"title\":\"Grey one\",\"status\":\"open\",\"description\":\"Do the thing\","
            + "\"dependencies\":[{\"issue_id\":\"demo-1.1\",\"depends_on_id\":\"demo-1\",\"type\":\"parent-child\"}],"
            + "\"comment_count\":1,\"comments\":[{\"author\":\"wolf\",\"text\":\"Ship it\",\"created_at\":\"2026-11-22T10:00:00Z\"}]}\n";

    static String gz(String text) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (GZIPOutputStream z = new GZIPOutputStream(out)) {
            z.write(text.getBytes(StandardCharsets.UTF_8));
        }
        return Base64.getEncoder().encodeToString(out.toByteArray());
    }

    static String chunk(String store, String fp, String export) throws Exception {
        return "\u001e" + store + "\n\u001c" + fp + "\n\u001dgzip\n" + gz(export) + "\n\u001f0\n";
    }

    @Test public void aDumpDrawsTheTreeAndOpensEveryBeadWithNoRead() throws Exception {
        String reply = chunk("demo", "11.5", EXPORT)
                + "\u001ebroken\n\u001c\n\u001d-\nError: database is locked\n\u001f3\n";
        BeadsMirror m = BeadsMirror.of(BeadsMirror.merge("", BeadsMirror.parseDump(reply)));
        Map<String, BeadsModel.Store> stores = m.listing();
        assertEquals(2, stores.get("demo").beads.size());
        assertEquals("11.5", stores.get("demo").fp);
        assertTrue(stores.get("broken").error, stores.get("broken").error.contains("database is locked"));
        // The grey (open) bead opens from the mirror, plan and comments, as a live read would.
        String read = m.read("demo", "demo-1.1");
        BeadsModel.Detail d = BeadCache.detail(read);
        assertNull(d.error);
        assertEquals("Grey one", d.title);
        assertEquals("Do the thing", d.sections.get(0)[1]);
        assertEquals("11.5", d.rev);
        assertFalse(d.maybeStale);
        BeadsModel.Comments c = BeadCache.comments(read);
        assertNull(c.error);
        assertEquals("Ship it", c.list.get(0).text);
        assertNull(m.read("demo", "demo-9"));
        // The tree nests by the export's parent-child dependency.
        List<BeadsModel.Node> roots = BeadsModel.beadTree(stores.get("demo").beads);
        assertEquals("demo-1", roots.get(0).bead.id);
        assertEquals("demo-1.1", roots.get(0).children.get(0).bead.id);
    }

    @Test public void sameKeepsTheStoreAndOnlyFreshStoresAreAskedForAgain() throws Exception {
        String first = BeadsMirror.merge("", BeadsMirror.parseDump(chunk("demo", "11.5", EXPORT) + chunk("web", "7.1", EXPORT)));
        assertEquals(List.of("demo=11.5", "web=7.1"), BeadsMirror.known(first));
        String next = BeadsMirror.merge(first, BeadsMirror.parseDump(
                "\u001edemo\n\u001c11.5\n\u001fsame\n" + chunk("web", "7.2", "{\"id\":\"web-1\",\"title\":\"New\",\"status\":\"open\"}\n")));
        BeadsMirror m = BeadsMirror.of(next);
        assertEquals(2, m.listing().get("demo").beads.size());
        assertEquals("web-1", m.listing().get("web").beads.get(0).id);
        assertEquals(List.of("demo=11.5", "web=7.2"), BeadsMirror.known(next));
        // A store no longer listed is gone; a "same" for a store the phone lost is dropped (sent whole next time).
        assertEquals(List.of(), BeadsMirror.known(BeadsMirror.merge("", BeadsMirror.parseDump("\u001edemo\n\u001c11.5\n\u001fsame\n"))));
        assertTrue(BeadsMirror.of(BeadsMirror.merge(next, BeadsMirror.parseDump(chunk("web", "7.2", EXPORT)))).stores().keySet().equals(java.util.Set.of("web")));
        // Damaged input is a store's error or nothing, never an exception.
        assertNotNull(BeadsMirror.parseDump("\u001edemo\n\u001c1\n\u001dgzip\nnot-base64!\n\u001f0\n").stores.get("demo").error);
        assertEquals("bd isn't installed on that machine", BeadsMirror.parseDump("\u001dnobd\n").missing);
        assertTrue(BeadsMirror.of("garbage").isEmpty());
    }

    /** The adapter's reads: what each kind asks the machine, and what it makes of the answer. */
    @Test public void thePanelsReadsAskTheMachineAsDumpSHAndDocSHExpect() throws Exception {
        List<JSONObject> asked = new ArrayList<>();
        String[] answer = {""};
        Function<String, String> op = json -> {
            try {
                asked.add(new JSONObject(json));
            } catch (org.json.JSONException e) {
                throw new AssertionError(e);
            }
            return answer[0];
        };
        Map<SyncEngine.Kind, SyncEngine.Fetcher> f = PanelSync.fetchers(op, () -> "server");
        MachineKey a = SyncEngineTest.A;
        answer[0] = new JSONObject().put("queued", true).put("text", chunk("demo", "11.5", EXPORT)).toString();
        SyncEngine.Fetched got = f.get(SyncEngine.Kind.BEADS).fetch(new SyncEngine.Key(a, SyncEngine.Kind.BEADS, ""), null);
        assertEquals("beads.dump", asked.get(0).getString("op"));
        assertFalse(asked.get(0).has("on"));
        assertEquals("server", got.source);
        SyncEngine.Entry kept = new SyncEngine.Entry(got.text, "", 1, "server", SyncEngine.Entry.State.FRESH, null, 1);
        // The deacon's relayed read names the machine and sends what it already has.
        answer[0] = new JSONObject().put("queued", true).put("text", "\u001edemo\n\u001c11.5\n\u001fsame\n").toString();
        got = f.get(SyncEngine.Kind.BEADS).fetch(new SyncEngine.Key(a, SyncEngine.Kind.BEADS, "laptop"), kept);
        assertEquals("laptop", asked.get(1).getString("on"));
        assertEquals("demo=11.5", asked.get(1).getJSONArray("known").getString(0));
        assertTrue("all same", got.same);
        assertEquals("laptop", got.source);
        answer[0] = new JSONObject().put("queued", true).put("error", "the beads read timed out on the machine").toString();
        assertEquals("the beads read timed out on the machine", f.get(SyncEngine.Kind.BEADS).fetch(new SyncEngine.Key(a, SyncEngine.Kind.BEADS, ""), kept).error);
        // A doc: its key goes with the ask; "same" keeps the text with the machine's clock now.
        answer[0] = new JSONObject().put("queued", true).put("text", "ok\n1000\n900\n12345\n# Hi").toString();
        got = f.get(SyncEngine.Kind.DOC).fetch(new SyncEngine.Key(a, SyncEngine.Kind.DOC, "factory/x"), null);
        assertEquals("900:12345", got.fp);
        SyncEngine.Entry doc = new SyncEngine.Entry(got.text, got.fp, 1, "server", SyncEngine.Entry.State.FRESH, null, 1);
        answer[0] = new JSONObject().put("queued", true).put("text", "same\n5000").toString();
        got = f.get(SyncEngine.Kind.DOC).fetch(new SyncEngine.Key(a, SyncEngine.Kind.DOC, "factory/x"), doc);
        assertEquals("900:12345", asked.get(asked.size() - 1).getString("key"));
        DocModel.Read read = DocModel.parse(got.text);
        assertEquals("# Hi", read.text);
        assertEquals(5000, read.now);
        // Machines: the connected, lasting ones only.
        answer[0] = new JSONObject().put("queued", true).put("machines", new org.json.JSONArray(
                "[{\"name\":\"laptop\",\"state\":\"connected\"},{\"name\":\"gone\",\"state\":\"unreachable\"},"
                + "{\"name\":\"box\",\"state\":\"connected\",\"temporary\":true}]")).toString();
        got = f.get(SyncEngine.Kind.MACHINES).fetch(new SyncEngine.Key(a, SyncEngine.Kind.MACHINES, ""), null);
        assertEquals(List.of("laptop"), PanelSync.connectedMachines(got.text));
    }

    /** End to end on the engine: the deacon's Tasks list this machine, then every connected one. */
    @Test public void theDeaconSeesEveryMachinesBeads() throws Exception {
        SyncEngineTest.Rig r = new SyncEngineTest.Rig();
        String here = chunk("factory", "1.1", EXPORT), there = chunk("zulip", "2.2", EXPORT);
        String machines = "[{\"name\":\"server\",\"state\":\"connected\"},{\"name\":\"laptop\",\"state\":\"connected\"}]";
        r.fetchers.put(SyncEngine.Kind.BEADS, (key, kept) -> SyncEngine.Fetched.data(
                BeadsMirror.merge("", BeadsMirror.parseDump(key.name.isEmpty() ? here : there)), "", key.name.isEmpty() ? "server" : key.name));
        r.fetchers.put(SyncEngine.Kind.MACHINES, (key, kept) -> SyncEngine.Fetched.data(machines, "", "server"));
        // The rig's engine was built with the old map; build one on these fetchers.
        SyncEngine engine = new SyncEngine(r.fetchers, new SyncEngine.Store() {
            @Override public SyncEngine.Entry load(SyncEngine.Key key) { return null; }
            @Override public void save(SyncEngine.Key key, SyncEngine.Entry entry) { }
        }, () -> false, r.main::add, r.net::add, r.disk::add, () -> r.now, line -> { });
        PanelSync sync = new PanelSync(engine, new String[] {"server"});
        sync.select(SyncEngineTest.A, "server");
        int[] changes = {0};
        sync.onChange(() -> changes[0]++);
        assertTrue(sync.beads(PanelSync.HERE, SyncEngine.Priority.VISIBLE).loading());
        for (int i = 0; i < 3; i++) {
            sync.everywhere(SyncEngine.Priority.VISIBLE);
            r.settle();
        }
        List<PanelSync.Beads> all = sync.everywhere(SyncEngine.Priority.VISIBLE);
        assertEquals(2, all.size());
        assertEquals("server", all.get(0).machine);
        assertTrue(all.get(0).stores().containsKey("factory"));
        assertEquals("laptop", all.get(1).machine);
        assertTrue(all.get(1).stores().containsKey("zulip"));
        assertNotNull(all.get(1).bead("zulip", "demo-1.1"));
        assertTrue(changes[0] >= 3);
        long v = sync.version();
        assertEquals(v, sync.version());
    }

    @Test public void filesKeepEachKeyApartAndStayInTheBudget() throws Exception {
        File dir = Files.createTempDirectory("sync").toFile();
        long[] now = {OfflineBudget.GONE_MS * 2};
        SyncFiles files = new SyncFiles(dir, 4096, 0, () -> now[0], () -> Long.MAX_VALUE);
        SyncEngine.Key a = new SyncEngine.Key(SyncEngineTest.A, SyncEngine.Kind.DOC, "x");
        SyncEngine.Key b = new SyncEngine.Key(SyncEngineTest.B, SyncEngine.Kind.DOC, "x");
        files.save(a, new SyncEngine.Entry("A's doc", "1:2", 5, "server", SyncEngine.Entry.State.FRESH, null, 3));
        SyncEngine.Entry back = files.load(a);
        assertEquals("A's doc", back.text);
        assertEquals("1:2", back.fp);
        assertEquals("server", back.source);
        assertNull("another machine's key never reads A's file", files.load(b));
        // A's file copied under B's name is still refused: the file names its own key.
        File fa = files.fileOf(a), fb = files.fileOf(b);
        assertTrue(fb.getParentFile().mkdirs() || fb.getParentFile().isDirectory());
        Files.copy(fa.toPath(), fb.toPath());
        assertNull(files.load(b));
        // Over the budget: the least recently written go first, never the one just written.
        StringBuilder big = new StringBuilder();
        while (big.length() < 3950) big.append("x");
        SyncEngine.Key c = new SyncEngine.Key(SyncEngineTest.A, SyncEngine.Kind.DOC, "big");
        assertTrue(fa.setLastModified(now[0] - 20_000));
        assertTrue(fb.setLastModified(now[0] - 10_000));
        files.save(c, new SyncEngine.Entry(big.toString(), "", 6, "server", SyncEngine.Entry.State.FRESH, null, 4));
        assertNotNull(files.load(c));
        assertNull("evicted for room", files.load(a));
        // Nothing is written under the free-space floor.
        SyncFiles full = new SyncFiles(dir, 1 << 20, 100, () -> now[0], () -> 99);
        SyncEngine.Key d = new SyncEngine.Key(SyncEngineTest.A, SyncEngine.Kind.DOC, "d");
        full.save(d, new SyncEngine.Entry("doc", "", 7, "", SyncEngine.Entry.State.FRESH, null, 5));
        assertNull(full.load(d));
    }

    /** Unproven: nothing reads; a profile refused its machine reads live and keeps nothing on disk. */
    @Test public void aRefusedProfileReadsLiveAndKeepsNothing() throws Exception {
        assertNull(PanelSync.keyFor(null, false, "h1"));
        assertEquals(SyncEngineTest.A, PanelSync.keyFor(SyncEngineTest.A, true, "h1"));
        MachineKey live = PanelSync.keyFor(null, true, "h1");
        assertTrue(PanelSync.isLiveOnly(live));
        assertFalse(PanelSync.isLiveOnly(SyncEngineTest.A));
        File dir = Files.createTempDirectory("sync").toFile();
        SyncFiles files = new SyncFiles(dir, 1 << 20, 0, System::currentTimeMillis, () -> Long.MAX_VALUE);
        SyncEngine.Key key = new SyncEngine.Key(live, SyncEngine.Kind.DOC, "x");
        files.save(key, new SyncEngine.Entry("doc", "", 5, "", SyncEngine.Entry.State.FRESH, null, 1));
        assertNull(files.load(key));
        assertFalse(files.fileOf(key).exists());
    }
}
