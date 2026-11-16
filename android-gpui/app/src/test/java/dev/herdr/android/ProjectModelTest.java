package dev.herdr.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

/**
 * The phone's project model against the cases shared with the desktop's
 * (project-model-cases.json: each case names the desktop test it is ported
 * from), and the top bar built from it.
 */
public final class ProjectModelTest {
    private static JSONObject fixture() throws Exception {
        try (InputStream in = ProjectModelTest.class.getClassLoader().getResourceAsStream("project-model-cases.json")) {
            assertNotNull("project-model-cases.json is on the test classpath", in);
            return new JSONObject(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    private static String window(ProjectModel.Window w) {
        return (w.terminal != null ? w.terminal : "tab:" + w.tab) + " " + w.name;
    }

    private static Map<String, ProjectModel.GitName> git(JSONObject c) {
        Map<String, ProjectModel.GitName> out = new HashMap<>();
        JSONObject g = c.optJSONObject("git");
        if (g == null) return out;
        for (Iterator<String> it = g.keys(); it.hasNext(); ) {
            String cwd = it.next();
            JSONObject v = g.getJSONObject(cwd);
            out.put(cwd, new ProjectModel.GitName(v.optString("name"), v.optBoolean("linked")));
        }
        return out;
    }

    @Test
    public void everySharedCaseHoldsOnThePhone() throws Exception {
        JSONObject doc = fixture();
        assertTrue(doc.getString("source").contains("10ad20b"));
        JSONArray cases = doc.getJSONArray("cases");
        assertTrue(cases.length() >= 7);
        for (int i = 0; i < cases.length(); i++) {
            JSONObject c = cases.getJSONObject(i);
            String name = c.getString("name");
            assertTrue(name + " names its desktop test", !c.optString("desktop_test").isEmpty());
            JSONObject view = c.getJSONObject("view");
            List<ProjectModel.Project> all = ProjectModel.projects(view.optJSONArray("workspaces"),
                    view.optJSONArray("tabs"), view.optJSONArray("panes"));
            if (c.has("order")) ProjectModel.ordered(all, c.getJSONObject("order"));
            JSONObject expect = c.getJSONObject("expect");
            JSONObject projects = expect.optJSONObject("projects");
            if (projects != null) {
                Map<String, ProjectModel.Project> byKey = new HashMap<>();
                for (ProjectModel.Project p : all) byKey.put(p.key, p);
                assertEquals(name + ": the projects", sorted(projects.keySet()), sorted(byKey.keySet()));
                for (String key : projects.keySet()) {
                    JSONObject want = projects.getJSONObject(key);
                    ProjectModel.Project p = byKey.get(key);
                    assertEquals(name + " " + key + " name", want.getString("name"), p.name);
                    List<String> windows = new ArrayList<>();
                    for (ProjectModel.Window w : p.windows) windows.add(window(w));
                    assertEquals(name + " " + key + " windows", strings(want.getJSONArray("windows")), windows);
                    assertEquals(name + " " + key + " waiting", want.getInt("waiting"), p.waiting);
                    for (int n = 0; n < p.windows.size(); n++) assertEquals(n + 1, p.windows.get(n).n);
                }
            }
            JSONArray current = expect.optJSONArray("current");
            for (int k = 0; current != null && k < current.length(); k++) {
                JSONObject cur = current.getJSONObject(k);
                JSONObject focus = cur.getJSONObject("focus");
                ProjectModel.Project p = ProjectModel.currentOf(all, focus.optString("terminal", null),
                        focus.optString("tab", null), focus.optString("space", null));
                assertNotNull(name + " current " + focus, p);
                assertEquals(name + " current " + focus, cur.getString("project"), p.key);
                assertEquals(name + " shown " + focus, cur.getInt("shown"), ProjectModel.shownIndex(p,
                        focus.optString("terminal", null), focus.optString("tab", null)));
            }
            Map<String, ProjectModel.Project> keyed = new HashMap<>();
            for (ProjectModel.Project p : all) keyed.put(p.key, p);
            JSONArray moves = expect.optJSONArray("moves");
            for (int k = 0; moves != null && k < moves.length(); k++) {
                JSONObject m = moves.getJSONObject(k);
                ProjectModel.WindowMove got = ProjectModel.windowMove(keyed.get(m.getString("project")),
                        m.getInt("from"), m.getInt("to"), m.getBoolean("after"));
                String what = name + " move " + m.getInt("from") + (m.getBoolean("after") ? " after " : " before ") + m.getInt("to");
                if (m.isNull("expect")) {
                    org.junit.Assert.assertNull(what, got);
                    continue;
                }
                JSONObject want = m.getJSONObject("expect");
                assertNotNull(what, got);
                assertEquals(what, want.getString("project"), got.project);
                assertEquals(what, want.getString("id"), got.id);
                assertEquals(what, want.getString("target"), got.target);
                assertEquals(what, want.getBoolean("after"), got.after);
                assertEquals(what, strings(want.getJSONArray("shown")), got.shown);
            }
            JSONObject owns = expect.optJSONObject("owns_tab");
            if (owns != null) {
                for (String key : owns.keySet()) {
                    ProjectModel.Project p = keyed.get(key);
                    for (String terminal : owns.getJSONObject(key).keySet()) {
                        ProjectModel.Window w = null;
                        for (ProjectModel.Window x : p.windows) if (terminal.equals(x.terminal)) w = x;
                        assertEquals(name + " " + key + " owns " + terminal + "'s tab",
                                owns.getJSONObject(key).getBoolean(terminal), ProjectModel.ownsTab(all, p, w));
                    }
                }
            }
            JSONObject groups = expect.optJSONObject("groups");
            if (groups != null || expect.has("order_ids")) {
                ProjectModel.Groups g = ProjectModel.groups(all, git(c),
                        ProjectModel.repoLabels(view.optJSONArray("panes")), c.optJSONObject("order"));
                if (groups != null) {
                    List<String> deacons = new ArrayList<>();
                    for (ProjectModel.Project p : g.deacons) deacons.add(p.key);
                    assertEquals(name + " deacons", strings(groups.getJSONArray("deacons")), deacons);
                    JSONArray repos = groups.getJSONArray("repos");
                    assertEquals(name + " repos", repos.length(), g.repos.size());
                    for (int r = 0; r < repos.length(); r++) {
                        JSONObject want = repos.getJSONObject(r);
                        ProjectModel.Repo got = g.repos.get(r);
                        assertEquals(name + " repo " + r, want.isNull("name") ? null : want.getString("name"), got.name);
                        List<String> keys = new ArrayList<>();
                        for (ProjectModel.Project p : got.projects) keys.add(p.key);
                        assertEquals(name + " repo " + got.name, strings(want.getJSONArray("projects")), keys);
                    }
                }
                JSONObject ids = expect.optJSONObject("order_ids");
                if (ids != null) {
                    for (ProjectModel.Project p : all) {
                        if (ids.has(p.key)) assertEquals(name + " id of " + p.key, ids.getString(p.key), p.orderId);
                    }
                }
            }
        }
    }

    @Test
    public void windowNamesFollowTheDesktop() {
        assertEquals("Fix the limits", ProjectModel.windowName("tb3", false, "claude", "✳ Fix the limits"));
        assertEquals("review", ProjectModel.windowName("review", true, "claude", "✳ Fix the limits"));
        assertEquals("claude", ProjectModel.windowName("tb3", null, "claude", ""));
        assertEquals("shell", ProjectModel.windowName("", null, "", ""));
        // A task that says nothing (the program's own name) is not a name.
        assertEquals("codex", ProjectModel.windowName("tb1", false, "codex", "codex"));
        // A tab the phone made up for a terminal, named by its address.
        assertEquals("Build docs", ProjectModel.windowName("t40", null, "claude", "✳ Build docs"));
        assertEquals("claude", ProjectModel.windowName("t40", null, "claude", ""));
    }

    @Test
    public void orderByPlacesTheListedFirstAndKeepsTheRest() {
        List<String> list = java.util.Arrays.asList("a", "b", "c", "d");
        assertEquals(java.util.Arrays.asList("c", "a", "b", "d"),
                ProjectModel.orderBy(list, java.util.Arrays.asList("c", "gone", "a"), x -> x, null));
        assertEquals(list, ProjectModel.orderBy(list, null, x -> x, null));
        assertEquals("a##b#t9", ProjectModel.orderId("a#b", "t9"));
        assertEquals("monopoly", ProjectModel.remoteName("ssh://git@host:2222/org/monopoly.git"));
        assertEquals("monopoly", ProjectModel.remoteName("https://host/org/monopoly/"));
        assertEquals("monopoly", ProjectModel.worktreeRepo("/home/w/.herdr/worktrees/monopoly/wolf-x"));
    }

    private static List<ProjectModel.Project> barCase() throws Exception {
        JSONObject view = fixture().getJSONArray("cases").getJSONObject(0).getJSONObject("view");
        return ProjectModel.projects(view.optJSONArray("workspaces"), view.optJSONArray("tabs"), view.optJSONArray("panes"));
    }

    @Test
    public void theBarListsTheProjectsWindowsOnlyTheShownOneWhole() throws Exception {
        List<ProjectModel.Project> all = barCase();
        ProjectModel.Project p = ProjectModel.currentOf(all, "t12", "tE", "s1");
        int shown = ProjectModel.shownIndex(p, "t12", "tE");
        assertEquals("research-ui", p.name);
        // The cover: others short, the shown one whole and filled.
        List<String> cover = new ArrayList<>();
        for (ProjectBar.Chip c : ProjectBar.chips(p, shown, true)) cover.add(c.text + (c.active ? "*" : ""));
        assertEquals(java.util.Arrays.asList("1 lead", "2 traces", "3 Fix the limits*", "4 review"), cover);
        assertEquals("2 traces-backt…", ProjectBar.text(window("traces-backtesting", 2), ProjectBar.COMPACT + 3));
        assertEquals("2 traces-ba…", ProjectBar.text(window("traces-backtesting", 2), ProjectBar.COMPACT));
        assertEquals("2 traces-backtesting", ProjectBar.text(window("traces-backtesting", 2), ProjectBar.WIDE));
        // Never another PM's windows, though they share the space; a total
        // of every project's agents waiting on Wolf.
        for (ProjectBar.Chip c : ProjectBar.chips(p, shown, false)) assertTrue(!"t21".equals(c.window.terminal));
        assertEquals("[research-ui]", ProjectBar.projectText(p));
        assertEquals(1, ProjectBar.needYou(all));
    }

    @Test
    public void aNewWindowGoesToTheLeadsSpaceInTheShownFolderAndWorksForTheLead() throws Exception {
        List<ProjectModel.Project> all = barCase();
        ProjectModel.Project p = ProjectModel.currentOf(all, "t13", "tF", "s2");
        String[] place = ProjectBar.newWindowPlace(p, ProjectModel.shownIndex(p, "t13", "tF"));
        // The lead's space (s1), not the shown window's (s2); the lead t10 is its parent.
        assertEquals(java.util.Arrays.asList("s1", "t10", "/w/monopoly"), java.util.Arrays.asList(place));
        // A space nobody leads: no adoption, the plain new tab.
        JSONObject view = fixture().getJSONArray("cases").getJSONObject(2).getJSONObject("view");
        List<ProjectModel.Project> loose = ProjectModel.projects(view.optJSONArray("workspaces"),
                view.optJSONArray("tabs"), view.optJSONArray("panes"));
        ProjectModel.Project space = ProjectModel.currentOf(loose, "t40", "tX", "s3");
        assertEquals("space:s3", space.key);
        org.junit.Assert.assertNull(ProjectBar.newWindowPlace(space, 0));
    }

    private static ProjectModel.Window window(String name, int n) {
        ProjectModel.Window w = new ProjectModel.Window("t1", "a", "s", "", name, "", 0, 0);
        w.n = n;
        return w;
    }

    @Test
    public void aDraggedChipLandsBesideTheNearestWindowNeverBeforeTheLead() throws Exception {
        JSONObject c = null;
        JSONArray cases = fixture().getJSONArray("cases");
        for (int i = 0; i < cases.length(); i++) if ("window-order-79".equals(cases.getJSONObject(i).getString("name"))) c = cases.getJSONObject(i);
        JSONObject view = c.getJSONObject("view");
        List<ProjectModel.Project> all = ProjectModel.ordered(ProjectModel.projects(view.optJSONArray("workspaces"),
                view.optJSONArray("tabs"), view.optJSONArray("panes")), c.getJSONObject("order"));
        ProjectModel.Project p = all.get(0);
        // Six chips 100 px apart: lead, t1c, notes, t1a, t1b, t1new.
        float[] centres = {50, 150, 250, 350, 450, 550};
        // t1b (4) let go just left of t1c's centre: before it.
        ProjectModel.WindowMove m = ProjectBar.dropMove(p, 4, 140, centres);
        assertEquals("t1b before t1c", m.id + (m.after ? " after " : " before ") + m.target, "t1b before t1c");
        // Over the lead: beside window 2 at most, never before the lead.
        m = ProjectBar.dropMove(p, 4, 20, centres);
        assertEquals("t1b before t1c", m.id + (m.after ? " after " : " before ") + m.target);
        // Let go where it already is: nothing to store.
        org.junit.Assert.assertNull(ProjectBar.dropMove(p, 4, 452, centres));
        // Past the end: after the last.
        m = ProjectBar.dropMove(p, 1, 900, centres);
        assertEquals("t1c after t1new", m.id + (m.after ? " after " : " before ") + m.target);
    }

    @Test
    public void theShownChipIsRevealedByTheLeastScrollThatShowsItWhole() {
        // t1xw's case: a 270 px chip at 100..370 in a 284 px bar at 0: whole at 93 (7 px each side).
        int to = ProjectBar.revealScroll(100, 370, 0, 284, 24);
        assertEquals(93, to);
        org.junit.Assert.assertTrue(100 >= to && 370 <= to + 284);
        // Room to spare: the full margin.
        assertEquals(370 - 284 + 24, ProjectBar.revealScroll(300, 370, 0, 284, 24));
        // Off the left: its start, less the margin, never below 0.
        assertEquals(176, ProjectBar.revealScroll(200, 260, 500, 284, 24));
        assertEquals(0, ProjectBar.revealScroll(10, 60, 300, 284, 24));
        // Already whole: no scroll, a scroll Wolf made stays.
        assertEquals(-1, ProjectBar.revealScroll(100, 200, 50, 284, 24));
        // Wider than the bar (the activity then cuts it to the bar): its start.
        assertEquals(400, ProjectBar.revealScroll(400, 800, 0, 284, 24));
        // Every whole-able chip ends whole, for any start.
        for (int left = 0; left < 600; left += 7) {
            for (int chip = 1; chip <= 284; chip += 13) {
                for (int from = 0; from < 600; from += 31) {
                    int got = ProjectBar.revealScroll(left, left + chip, from, 284, 24);
                    int at = got < 0 ? from : got;
                    org.junit.Assert.assertTrue(left + " " + chip + " " + from, left >= at && left + chip <= at + 284);
                }
            }
        }
    }

    @Test
    public void aTabStopsBeingItsProjectsAloneWhenAnotherProjectsTerminalJoinsIt() throws Exception {
        // t1xw's round-2 case: t10 (research-ui) alone in its tab, then incident-fixes' t21 joins it.
        JSONArray workspaces = new JSONArray().put(new JSONObject().put("id", "s1").put("label", "w"));
        JSONArray tabs = new JSONArray()
                .put(new JSONObject().put("id", "a").put("workspace_id", "s1").put("label", "a"))
                .put(new JSONObject().put("id", "b").put("workspace_id", "s1").put("label", "b"))
                .put(new JSONObject().put("id", "c").put("workspace_id", "s1").put("label", "c"));
        JSONObject t10 = new JSONObject().put("id", "t10").put("workspace_id", "s1").put("tab_id", "a").put("role", "pm")
                .put("project", "research-ui").put("created_ms", 1);
        JSONObject t20 = new JSONObject().put("id", "t20").put("workspace_id", "s1").put("tab_id", "b").put("role", "pm")
                .put("project", "incident-fixes").put("created_ms", 2);
        JSONObject t21 = new JSONObject().put("id", "t21").put("workspace_id", "s1").put("tab_id", "c").put("parent", "t20");
        List<ProjectModel.Project> all = ProjectModel.projects(workspaces, tabs, new JSONArray().put(t10).put(t20).put(t21));
        ProjectModel.Project research = all.get(0);
        org.junit.Assert.assertTrue(ProjectModel.ownsTab(all, research, research.windows.get(0)));
        String drawn = ownership(all, research);
        t21.put("tab_id", "a");
        all = ProjectModel.projects(workspaces, tabs, new JSONArray().put(t10).put(t20).put(t21));
        research = all.get(0);
        org.junit.Assert.assertFalse(ProjectModel.ownsTab(all, research, research.windows.get(0)));
        // What the activity adds to its bar signature changes, so the bar (and the menus) are rebuilt.
        org.junit.Assert.assertNotEquals(drawn, ownership(all, research));
    }

    /** The ownership part of the bar's signature, as the activity draws it. */
    private static String ownership(List<ProjectModel.Project> all, ProjectModel.Project p) {
        StringBuilder out = new StringBuilder();
        for (ProjectModel.Window w : p.windows) out.append(w.terminal).append(ProjectModel.ownsTab(all, p, w));
        return out.toString();
    }

    @Test
    public void aCloneIsNamedByItsRemoteAsTheDesktopNamesIt() {
        // git.look's answer: a clone in /w/clone whose origin is org/monopoly, and a repo with no remote.
        FleetTree.GitLook look = FleetTree.parseGitLook(
                "P\t/w/clone\t/w/clone/.git\t/w/clone\tmain\t0\t0\n"
                        + "U\t/w/clone/.git\tgit@github.com:org/monopoly.git\n"
                        + "R\t/w/clone/.git\nworktree /w/clone\nbranch refs/heads/main\n\nE\n"
                        + "P\t/w/lane\t/w/clone/.git\t/w/lane\tlane\t0\t0\n"
                        + "P\t/w/solo\t/w/solo/.git\t/w/solo\tmain\t0\t0\n"
                        + "U\t/w/solo/.git\t\n"
                        + "R\t/w/solo/.git\nworktree /w/solo\n\nE\n", 32);
        Map<String, ProjectModel.GitName> names = ProjectModel.gitNames(look);
        assertEquals("monopoly", names.get("/w/clone").name);
        assertEquals(false, names.get("/w/clone").linked);
        assertEquals("monopoly", names.get("/w/lane").name);
        assertEquals(true, names.get("/w/lane").linked);
        assertEquals("solo", names.get("/w/solo").name);
        // The switcher then follows the desktop's saved order for that repo.
        JSONArray workspaces = new JSONArray().put(new JSONObject().put("id", "s1").put("label", "w"));
        JSONArray tabs = new JSONArray()
                .put(new JSONObject().put("id", "a").put("workspace_id", "s1").put("label", "a"))
                .put(new JSONObject().put("id", "b").put("workspace_id", "s1").put("label", "b"));
        JSONArray panes = new JSONArray()
                .put(new JSONObject().put("id", "t10").put("workspace_id", "s1").put("tab_id", "a").put("role", "pm")
                        .put("project", "research-ui").put("cwd", "/w/clone").put("created_ms", 1))
                .put(new JSONObject().put("id", "t20").put("workspace_id", "s1").put("tab_id", "b").put("role", "pm")
                        .put("project", "incident-fixes").put("cwd", "/w/lane").put("created_ms", 2));
        JSONObject order = new JSONObject().put("projects", new JSONObject().put("monopoly",
                new JSONArray().put("incident-fixes").put("research-ui")));
        ProjectModel.Groups g = ProjectModel.groups(ProjectModel.projects(workspaces, tabs, panes), names,
                new HashMap<>(), order);
        assertEquals("monopoly", g.repos.get(0).name);
        assertEquals("pm:t20", g.repos.get(0).projects.get(0).key);
        assertEquals("pm:t10", g.repos.get(0).projects.get(1).key);
    }

    private static List<String> strings(JSONArray a) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < a.length(); i++) out.add(a.getString(i));
        return out;
    }

    private static List<String> sorted(java.util.Set<String> keys) {
        List<String> out = new ArrayList<>(keys);
        java.util.Collections.sort(out);
        return out;
    }
}
