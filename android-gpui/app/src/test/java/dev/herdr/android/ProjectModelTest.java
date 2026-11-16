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
