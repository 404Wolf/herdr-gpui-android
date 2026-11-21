package dev.herdr.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

/**
 * The phone's beads reading and Work model against the desktop's own answers
 * (beads-cases.json: every expect was computed by the desktop factory mod at
 * 5e6c79eb on the same input; a case ported from a desktop test names it).
 */
public final class BeadsModelTest {
    private static JSONObject fixture() throws Exception {
        try (InputStream in = BeadsModelTest.class.getClassLoader().getResourceAsStream("beads-cases.json")) {
            assertNotNull("beads-cases.json is on the test classpath", in);
            return new JSONObject(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    /** A string, or null for JSON null or a missing key (android.jar's optString would say "null"). */
    private static String s(JSONObject o, String k) throws Exception {
        return o.isNull(k) ? null : o.getString(k);
    }

    private static String s(JSONArray a, int i) throws Exception {
        return a.isNull(i) ? null : a.getString(i);
    }

    /** A JSON object's keys, in order (android.jar's JSONObject has no keySet). */
    private static List<String> keys(JSONObject o) throws Exception {
        List<String> out = new ArrayList<>();
        for (Iterator<String> it = o.keys(); it.hasNext(); ) out.add(it.next());
        return out;
    }

    private static List<String> strings(JSONArray a) throws Exception {
        List<String> out = new ArrayList<>();
        for (int i = 0; a != null && i < a.length(); i++) out.add(s(a, i));
        return out;
    }

    private static List<String> ids(List<? extends FleetTree.Member> ms) throws Exception {
        List<String> out = new ArrayList<>();
        for (FleetTree.Member m : ms) out.add(m.id);
        return out;
    }

    private static List<String> beadIds(List<BeadsModel.Bead> bs) throws Exception {
        if (bs == null) return null;
        List<String> out = new ArrayList<>();
        for (BeadsModel.Bead b : bs) out.add(b.id);
        return out;
    }

    private static List<BeadsModel.Bead> beads(JSONArray rows) throws Exception {
        BeadsModel.BeadList r = BeadsModel.parseBeads(rows.toString());
        assertNull(r.error);
        return r.beads;
    }

    private static List<BeadsModel.Term> terms(JSONArray a) throws Exception {
        List<BeadsModel.Term> out = new ArrayList<>();
        for (int i = 0; i < a.length(); i++) {
            JSONObject p = a.getJSONObject(i);
            BeadsModel.Term t = new BeadsModel.Term();
            t.id = p.getString("id");
            t.title = p.optString("title", "");
            t.status = p.optString("status", "");
            t.role = FleetTree.role(p.optString("role", ""));
            t.project = p.optString("project", "");
            t.parent = p.optString("parent", "");
            BeadsModel.labels(t, p);
            out.add(t);
        }
        return out;
    }

    private static Map<String, BeadsModel.Store> kept(String out) throws Exception {
        BeadsModel.Stores read = BeadsModel.parseStores(out);
        assertFalse(read.missing);
        return BeadsModel.merge(new LinkedHashMap<String, BeadsModel.Store>(), read);
    }

    private static void error(String what, JSONObject want, String got) throws Exception {
        if (want.has("error_prefix")) {
            assertNotNull(what, got);
            assertTrue(what + ": " + got, got.startsWith(want.getString("error_prefix")));
        } else assertEquals(what, s(want, "error"), got);
    }

    @Test
    public void theFixtureNamesItsDesktopCommit() throws Exception {
        JSONObject doc = fixture();
        assertTrue(doc.getString("source").contains("5e6c79eb"));
        for (String section : new String[] { "stores", "beads", "merges", "splits", "trees", "works", "details" })
            assertTrue(section + " has cases", doc.getJSONArray(section).length() > 0);
    }

    @Test
    public void storesParseAsTheDesktopDoes() throws Exception {
        JSONArray cases = fixture().getJSONArray("stores");
        for (int i = 0; i < cases.length(); i++) {
            JSONObject c = cases.getJSONObject(i);
            String name = c.getString("name");
            JSONObject expect = c.getJSONObject("expect");
            BeadsModel.Stores got = BeadsModel.parseStores(c.getString("out"));
            assertEquals(name + " missing", expect.getBoolean("missing"), got.missing);
            assertEquals(name + " order", strings(expect.getJSONArray("order")), new ArrayList<>(got.byProject.keySet()));
            JSONObject stores = expect.getJSONObject("stores");
            for (String k : keys(stores)) {
                JSONObject want = stores.getJSONObject(k);
                BeadsModel.Store store = got.byProject.get(k);
                assertEquals(name + " " + k + " ids", want.isNull("ids") ? null : strings(want.getJSONArray("ids")), beadIds(store.beads));
                error(name + " " + k + " error", want, store.error);
                assertEquals(name + " " + k + " fp", want.getString("fp"), store.fp);
                assertEquals(name + " " + k + " same", want.getBoolean("same"), store.same);
            }
        }
    }

    @Test
    public void bdListsParseAsTheDesktopDoes() throws Exception {
        JSONArray cases = fixture().getJSONArray("beads");
        for (int i = 0; i < cases.length(); i++) {
            JSONObject c = cases.getJSONObject(i);
            String name = c.getString("name");
            JSONObject expect = c.getJSONObject("expect");
            BeadsModel.BeadList got = BeadsModel.parseBeads(c.getString("text"));
            error(name, expect, got.error);
            if (expect.isNull("beads")) {
                assertNull(name, got.beads);
                continue;
            }
            JSONArray want = expect.getJSONArray("beads");
            assertEquals(name + " count", want.length(), got.beads.size());
            for (int k = 0; k < want.length(); k++) {
                JSONObject w = want.getJSONObject(k);
                BeadsModel.Bead b = got.beads.get(k);
                assertEquals(name, w.getString("id"), b.id);
                assertEquals(name, w.getString("title"), b.title);
                assertEquals(name, w.getString("status"), b.status);
                assertEquals(name, s(w, "type"), b.type);
                assertEquals(name, w.isNull("priority") ? null : (Integer) w.getInt("priority"), b.priority);
                assertEquals(name, strings(w.getJSONArray("labels")), b.labels);
                assertEquals(name, s(w, "parent"), b.parent);
                assertEquals(name, s(w, "assignee"), b.assignee);
            }
        }
        JSONArray lines = fixture().getJSONArray("error_lines");
        for (int i = 0; i < lines.length(); i++) {
            JSONObject c = lines.getJSONObject(i);
            assertEquals(c.getString("text"), c.getString("expect"), BeadsModel.errorLine(c.getString("text")));
        }
    }

    @Test
    public void readsMergeAsTheSidebarDoes() throws Exception {
        JSONArray cases = fixture().getJSONArray("merges");
        for (int i = 0; i < cases.length(); i++) {
            JSONObject c = cases.getJSONObject(i);
            JSONArray reads = c.getJSONArray("reads"), expect = c.getJSONArray("expect");
            Map<String, BeadsModel.Store> kept = new LinkedHashMap<>();
            for (int r = 0; r < reads.length(); r++) {
                String what = c.getString("name") + " read " + (r + 1);
                kept = BeadsModel.merge(kept, BeadsModel.parseStores(reads.getString(r)));
                JSONObject want = expect.getJSONObject(r);
                assertEquals(what + " order", strings(want.getJSONArray("order")), new ArrayList<>(kept.keySet()));
                assertEquals(what + " known", strings(want.getJSONArray("known")), BeadsModel.knownArgs(kept));
                JSONObject stores = want.getJSONObject("kept");
                for (String k : keys(stores)) {
                    JSONObject w = stores.getJSONObject(k);
                    BeadsModel.Store got = kept.get(k);
                    assertEquals(what + " " + k, w.isNull("ids") ? null : strings(w.getJSONArray("ids")), beadIds(got.beads));
                    assertEquals(what + " " + k, s(w, "error"), got.error);
                    assertEquals(what + " " + k, w.getString("fp"), got.fp);
                    assertFalse(what + " " + k + " is never same once kept", got.same);
                }
            }
        }
    }

    @Test
    public void storesSplitIntoProjects() throws Exception {
        JSONArray cases = fixture().getJSONArray("splits");
        for (int i = 0; i < cases.length(); i++) {
            JSONObject c = cases.getJSONObject(i);
            String name = c.getString("name");
            Map<String, BeadsModel.Store> previous = new LinkedHashMap<>();
            JSONObject prev = c.optJSONObject("previous");
            if (prev != null) for (String k : keys(prev))
                previous.put(k, new BeadsModel.Store(beads(prev.getJSONArray(k)), null, "1", false));
            Map<String, BeadsModel.Store> kept = BeadsModel.merge(previous, BeadsModel.parseStores(c.getString("out")));
            Map<String, BeadsModel.ProjectBeads> per = BeadsModel.projectBeads(kept);
            JSONObject expect = c.getJSONObject("expect");
            assertEquals(name + " order", strings(expect.getJSONArray("order")), new ArrayList<>(per.keySet()));
            JSONObject projects = expect.getJSONObject("projects");
            for (String p : keys(projects)) {
                JSONObject w = projects.getJSONObject(p);
                assertEquals(name + " " + p, strings(w.getJSONArray("ids")), beadIds(per.get(p).beads));
                assertEquals(name + " " + p, s(w, "error"), per.get(p).error);
            }
            JSONArray storeOf = c.optJSONArray("store_of");
            for (int k = 0; storeOf != null && k < storeOf.length(); k++) {
                JSONObject q = storeOf.getJSONObject(k);
                assertEquals(name + " store of " + q, s(q, "expect"),
                        BeadsModel.storeOf(q.getString("project"), q.getString("id"), kept));
            }
        }
    }

    private static String shape(BeadsModel.Node n) throws Exception {
        StringBuilder b = new StringBuilder(n.bead.id);
        if (!n.children.isEmpty()) {
            b.append('(');
            for (int i = 0; i < n.children.size(); i++) b.append(i > 0 ? " " : "").append(shape(n.children.get(i)));
            b.append(')');
        }
        return b.toString();
    }

    private static String shape(JSONObject n) throws Exception {
        StringBuilder b = new StringBuilder(n.getString("id"));
        JSONArray kids = n.getJSONArray("children");
        if (kids.length() > 0) {
            b.append('(');
            for (int i = 0; i < kids.length(); i++) b.append(i > 0 ? " " : "").append(shape(kids.getJSONObject(i)));
            b.append(')');
        }
        return b.toString();
    }

    @Test
    public void theTreeOrdersAndCountsAsTheDesktopDoes() throws Exception {
        JSONObject doc = fixture();
        JSONArray parents = doc.getJSONArray("parents");
        for (int i = 0; i < parents.length(); i++) {
            JSONObject c = parents.getJSONObject(i);
            Set<String> ids = new HashSet<>(strings(c.getJSONArray("ids")));
            BeadsModel.Bead b = beads(new JSONArray().put(c.getJSONObject("bead"))).get(0);
            assertEquals(c.getString("name"), s(c, "expect"), BeadsModel.parentOf(b, ids));
        }
        JSONArray compare = doc.getJSONArray("compare_ids");
        for (int i = 0; i < compare.length(); i++) {
            JSONObject c = compare.getJSONObject(i);
            assertEquals(c.getString("a") + " vs " + c.getString("b"), c.getInt("expect"),
                    Integer.signum(BeadsModel.compareIds(c.getString("a"), c.getString("b"))));
        }
        JSONArray trees = doc.getJSONArray("trees");
        for (int i = 0; i < trees.length(); i++) {
            JSONObject c = trees.getJSONObject(i);
            String name = c.getString("name");
            List<BeadsModel.Node> tree = BeadsModel.beadTree(beads(c.getJSONArray("beads")));
            JSONArray want = c.getJSONObject("expect").getJSONArray("tree");
            List<String> wanted = new ArrayList<>(), got = new ArrayList<>();
            for (int k = 0; k < want.length(); k++) wanted.add(shape(want.getJSONObject(k)));
            for (BeadsModel.Node n : tree) got.add(shape(n));
            assertEquals(name, wanted, got);
            List<String> epics = new ArrayList<>();
            for (BeadsModel.Node n : tree) if (BeadsModel.isEpic(n.bead)) {
                BeadsModel.Epic e = BeadsModel.progress(n);
                epics.add(e.bead.id + " " + e.closed + "/" + e.total);
            }
            assertEquals(name + " epics", epicStrings(c.getJSONObject("expect").getJSONArray("epics")), epics);
        }
        JSONArray repos = doc.getJSONArray("repo_projects");
        for (int i = 0; i < repos.length(); i++) {
            JSONObject c = repos.getJSONObject(i);
            Map<String, List<BeadsModel.Bead>> got = BeadsModel.repoProjects(beads(c.getJSONArray("beads")));
            JSONObject want = c.getJSONObject("expect");
            // A map of projects: org.json keeps no key order, so compare the names, not their order.
            List<String> wantNames = keys(want), gotNames = new ArrayList<>(got.keySet());
            java.util.Collections.sort(wantNames);
            java.util.Collections.sort(gotNames);
            assertEquals(c.getString("name"), wantNames, gotNames);
            for (String p : keys(want)) assertEquals(p, strings(want.getJSONArray(p)), beadIds(got.get(p)));
        }
        JSONArray epicProjects = doc.getJSONArray("epic_projects");
        for (int i = 0; i < epicProjects.length(); i++) {
            JSONObject c = epicProjects.getJSONObject(i);
            BeadsModel.Bead b = beads(new JSONArray().put(c.getJSONObject("bead"))).get(0);
            assertEquals(c.getJSONObject("bead").toString(), s(c, "expect"), BeadsModel.epicProject(b));
        }
        JSONArray places = doc.getJSONArray("places");
        for (int i = 0; i < places.length(); i++) {
            JSONObject c = places.getJSONObject(i);
            BeadsModel.Place p = BeadsModel.beadPlace(beads(c.getJSONArray("beads")), c.getString("id"));
            JSONObject want = c.getJSONObject("expect");
            assertEquals(c.getString("id"), s(want, "bead"), p.bead == null ? null : p.bead.id);
            assertEquals(c.getString("id") + " trail", strings(want.getJSONArray("trail")), beadIds(p.trail));
            assertEquals(c.getString("id") + " children", strings(want.getJSONArray("children")), beadIds(p.children));
        }
    }

    private static List<String> epicStrings(JSONArray a) throws Exception {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < a.length(); i++) {
            JSONArray e = a.getJSONArray(i);
            out.add(e.getString(0) + " " + e.getInt(1) + "/" + e.getInt(2));
        }
        return out;
    }

    private static Map<String, List<String>> lists(JSONObject o) throws Exception {
        Map<String, List<String>> out = new LinkedHashMap<>();
        for (String k : keys(o)) out.put(k, strings(o.getJSONArray(k)));
        return out;
    }

    private static Map<String, List<String>> lists(Map<String, List<FleetTree.Member>> m) throws Exception {
        Map<String, List<String>> out = new LinkedHashMap<>();
        for (Map.Entry<String, List<FleetTree.Member>> e : m.entrySet()) out.put(e.getKey(), ids(e.getValue()));
        return out;
    }

    @Test
    public void theJoinMatchesTheDesktop() throws Exception {
        JSONArray cases = fixture().getJSONArray("joins");
        for (int i = 0; i < cases.length(); i++) {
            JSONObject c = cases.getJSONObject(i);
            String name = c.getString("name"), project = c.getString("project");
            List<BeadsModel.Term> terms = terms(c.getJSONArray("terms"));
            List<BeadsModel.Bead> beads = beads(c.getJSONArray("beads"));
            BeadsModel.Crew crew = BeadsModel.crewOf(project, terms, null);
            JSONObject expect = c.getJSONObject("expect");
            assertEquals(name + " lead", s(expect, "lead"), crew.lead == null ? null : crew.lead.id);
            assertEquals(name + " workers", strings(expect.getJSONArray("workers")), ids(crew.workers));
            for (String w : keys(expect.getJSONObject("helpers"))) {
                assertEquals(name + " helpers of " + w, strings(expect.getJSONObject("helpers").getJSONArray(w)), ids(crew.helpers.get(w)));
                assertEquals(name + " sub-agents of " + w, strings(expect.getJSONObject("subagents").getJSONArray(w)), ids(crew.subagents.get(w)));
            }
            BeadsModel.View v = BeadsModel.joinProject(project, beads, crew);
            List<String> tasks = new ArrayList<>(), wantTasks = new ArrayList<>();
            for (BeadsModel.TaskRow r : v.tasks) tasks.add(r.key + " " + r.state + " " + r.title);
            JSONArray wt = expect.getJSONArray("tasks");
            for (int k = 0; k < wt.length(); k++) wantTasks.add(String.join(" ", strings(wt.getJSONArray(k))));
            assertEquals(name + " tasks", wantTasks, tasks);
            assertEquals(name + " queued", expect.getInt("queued"), v.queued);
            JSONObject counts = expect.getJSONObject("counts");
            assertEquals(name + " needs you", counts.getInt("needsYou"), v.needsYou);
            assertEquals(name + " working", counts.getInt("working"), v.working);
            assertEquals(name + " done", counts.getInt("done"), v.done);
            List<String> epics = new ArrayList<>();
            for (BeadsModel.Epic e : v.epics) epics.add(e.bead.id + " " + e.closed + "/" + e.total);
            assertEquals(name + " epics", epicStrings(expect.getJSONArray("epics")), epics);
            List<String> needs = new ArrayList<>(), wantNeeds = new ArrayList<>();
            for (BeadsModel.Need n : BeadsModel.needsYou(v))
                needs.add(n.bead + " " + (n.terminal == null ? null : n.terminal.id) + " " + n.why + " " + n.title);
            JSONArray wn = expect.getJSONArray("needs");
            for (int k = 0; k < wn.length(); k++) wantNeeds.add(String.join(" ", String.valueOf(strings(wn.getJSONArray(k)).get(0)),
                    String.valueOf(strings(wn.getJSONArray(k)).get(1)), wn.getJSONArray(k).getString(2), wn.getJSONArray(k).getString(3)));
            assertEquals(name + " needs", wantNeeds, needs);
            assertEquals(name + " agents by bead", lists(expect.getJSONObject("agents_by_bead")), lists(BeadsModel.agentsByBead(crew, beads)));
        }
    }

    private static String node(BeadsModel.Node n) throws Exception {
        StringBuilder b = new StringBuilder(n.bead.id).append('[').append(n.mark).append('|')
                .append(String.join(",", ids(n.agents))).append('|').append(n.closed).append(']');
        if (!n.children.isEmpty()) {
            b.append('(');
            for (int i = 0; i < n.children.size(); i++) b.append(i > 0 ? " " : "").append(node(n.children.get(i)));
            b.append(')');
        }
        return b.toString();
    }

    private static String node(JSONObject n) throws Exception {
        StringBuilder b = new StringBuilder(n.getString("id")).append('[').append(s(n, "mark")).append('|')
                .append(String.join(",", strings(n.getJSONArray("agents")))).append('|').append(n.getInt("closed")).append(']');
        JSONArray kids = n.getJSONArray("children");
        if (kids.length() > 0) {
            b.append('(');
            for (int i = 0; i < kids.length(); i++) b.append(i > 0 ? " " : "").append(node(kids.getJSONObject(i)));
            b.append(')');
        }
        return b.toString();
    }

    @Test
    public void aPmsWorkMatchesTheDesktopPanel() throws Exception {
        JSONArray cases = fixture().getJSONArray("works");
        for (int i = 0; i < cases.length(); i++) {
            JSONObject c = cases.getJSONObject(i);
            String name = c.getString("name"), project = c.getString("project");
            List<BeadsModel.Term> terms = terms(c.getJSONArray("terms"));
            BeadsModel.Work w = BeadsModel.work(project, kept(c.getString("out")), terms);
            JSONObject expect = c.getJSONObject("expect");
            assertEquals(name + " error", s(expect, "error"), w.error);
            assertEquals(name + " scope", strings(expect.getJSONArray("scope")), ids(w.scope.agents));
            List<String> tasks = new ArrayList<>(), wantTasks = new ArrayList<>();
            for (BeadsModel.TaskRow r : w.view.tasks) tasks.add(r.key + " " + r.state);
            JSONArray wt = expect.getJSONArray("tasks");
            for (int k = 0; k < wt.length(); k++) wantTasks.add(String.join(" ", strings(wt.getJSONArray(k))));
            assertEquals(name + " tasks", wantTasks, tasks);
            List<String> epics = new ArrayList<>();
            for (BeadsModel.Epic e : w.epics) epics.add(e.bead.id + " " + e.closed + "/" + e.total);
            assertEquals(name + " epics", epicStrings(expect.getJSONArray("epics")), epics);
            assertEquals(name + " done epics", expect.getInt("done_epics"), w.doneEpics);
            assertEquals(name + " open", expect.getInt("open"), w.open);
            assertEquals(name + " total", expect.getInt("total"), w.total);
            List<String> needs = new ArrayList<>(), wantNeeds = new ArrayList<>();
            for (BeadsModel.Need n : w.needs) needs.add(n.bead + " " + (n.terminal == null ? null : n.terminal.id) + " " + n.why);
            JSONArray wn = expect.getJSONArray("needs");
            for (int k = 0; k < wn.length(); k++) {
                List<String> row = strings(wn.getJSONArray(k));
                wantNeeds.add(row.get(0) + " " + row.get(1) + " " + row.get(2));
            }
            assertEquals(name + " needs", wantNeeds, needs);
            assertEquals(name + " blocked", strings(expect.getJSONArray("blocked")), ids(w.blocked));
            assertEquals(name + " needs you", expect.getInt("needs_you"), w.needsYou);
            List<String> tree = new ArrayList<>(), wantTree = new ArrayList<>();
            for (BeadsModel.Node n : w.tree) tree.add(node(n));
            JSONArray want = expect.getJSONArray("tree");
            for (int k = 0; k < want.length(); k++) wantTree.add(node(want.getJSONObject(k)));
            assertEquals(name + " tree", wantTree, tree);
            assertEquals(name + " closed folded", expect.getInt("closed_folded"), w.closedFolded);
            assertEquals(name + " loose", strings(expect.getJSONArray("loose")), ids(w.loose));
            JSONObject onBead = expect.getJSONObject("agents_on_bead");
            for (String id : keys(onBead)) assertEquals(name + " agents on " + id, strings(onBead.getJSONArray(id)), ids(BeadsModel.agentsOf(w, id)));
            JSONObject roles = expect.getJSONObject("row_roles");
            for (BeadsModel.Term t : terms) {
                if (!roles.has(t.id)) continue;
                String role = "worker".equals(BeadsModel.roleOf(t)) ? "worker" : BeadsModel.helperName(t);
                assertEquals(name + " " + t.id + "'s chip", roles.getString(t.id), role);
            }
        }
    }

    @Test
    public void aBeadsDetailMatchesTheDesktop() throws Exception {
        JSONArray cases = fixture().getJSONArray("details");
        for (int i = 0; i < cases.length(); i++) {
            JSONObject c = cases.getJSONObject(i);
            String name = c.getString("name");
            JSONObject want = c.getJSONObject("expect");
            BeadsModel.Detail d = BeadsModel.parseDetail(c.getString("out"));
            if (want.has("error")) {
                assertEquals(name, want.getString("error"), d.error);
                assertNull(name, d.id);
                continue;
            }
            assertNull(name + ": " + d.error, d.error);
            assertEquals(name, want.getString("id"), d.id);
            assertEquals(name, want.getString("title"), d.title);
            assertEquals(name, want.getString("status"), d.status);
            assertEquals(name, s(want, "type"), d.type);
            assertEquals(name, want.isNull("priority") ? null : (Integer) want.getInt("priority"), d.priority);
            assertEquals(name, s(want, "assignee"), d.assignee);
            assertEquals(name, s(want, "owner"), d.owner);
            assertEquals(name, s(want, "created_at"), d.createdAt);
            assertEquals(name, s(want, "updated_at"), d.updatedAt);
            assertEquals(name, s(want, "close_reason"), d.closeReason);
            assertEquals(name, s(want, "spec_id"), d.specId);
            assertEquals(name + " sections", pairs(want.getJSONArray("sections")), pairs(d.sections));
            assertEquals(name + " published", pairs(want.getJSONArray("published")), pairs(d.published));
            List<String> comments = new ArrayList<>(), wantComments = new ArrayList<>();
            for (BeadsModel.Comment k : d.comments) comments.add(k.author + "|" + k.text + "|" + k.at);
            JSONArray wc = want.getJSONArray("comments");
            for (int k = 0; k < wc.length(); k++) wantComments.add(String.join("|", String.valueOf(s(wc.getJSONArray(k), 0)),
                    wc.getJSONArray(k).getString(1), String.valueOf(s(wc.getJSONArray(k), 2))));
            assertEquals(name + " comments", wantComments, comments);
        }
    }

    private static List<String> pairs(JSONArray a) throws Exception {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < a.length(); i++) out.add(a.getJSONArray(i).getString(0) + "=" + a.getJSONArray(i).getString(1));
        return out;
    }

    private static List<String> pairs(List<String[]> a) throws Exception {
        List<String> out = new ArrayList<>();
        for (String[] p : a) out.add(p[0] + "=" + p[1]);
        return out;
    }

    @Test
    public void webUrlsAreHttpOnly() {
        assertEquals("https://x.example/a", BeadsModel.webUrl(" https://x.example/a "));
        assertNull(BeadsModel.webUrl("https://x.example/a b"));
        assertNull(BeadsModel.webUrl("file:///etc/passwd"));
        assertNull(BeadsModel.webUrl(5));
    }
}
