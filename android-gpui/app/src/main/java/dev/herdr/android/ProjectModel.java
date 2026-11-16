package dev.herdr.android;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Wolf's project model on the phone, a rule-for-rule port of the desktop's
 * (home-manager-conf users/wolf/programs/herdr-core/app/mods/projects/model.js
 * at 10ad20b: projects, summary, currentOf, shownIndex, orderBy, orderId,
 * repoOf, repoGroups). The shared cases are the fixture
 * app/src/test/resources/project-model-cases.json; a change on either side is
 * diffed against it.
 *
 * <p>Every PM (role=pm) is a project, wherever its tab is; window 1 is the PM
 * ("lead"); its other windows are the terminals whose parent chain leads to
 * it, on this computer only. The deacon is a project of its own. A terminal
 * no lead claims, or an empty tab, is a window of its space's first lead,
 * else of a project of its own space: nothing live is ever hidden.
 *
 * <p>Read from the phone's view of one core: its workspaces (spaces), tabs
 * and panes (one per live terminal, with its labels). Federated entries
 * ("machine/tN") belong to another computer's connection and are left out,
 * as a parent never crosses computers.
 */
final class ProjectModel {
    private ProjectModel() { }

    /** One window of a project: a terminal with its tab, or a tab with no terminal. */
    static final class Window {
        final String terminal, tab, space, cwd;
        String name, status;
        int n;
        /** Where its tab is: [space index, tab index]. */
        final int spaceIndex, tabIndex;
        Window(String terminal, String tab, String space, String cwd, String name, String status,
                int spaceIndex, int tabIndex) {
            this.terminal = terminal;
            this.tab = tab;
            this.space = space;
            this.cwd = cwd;
            this.name = name;
            this.status = status;
            this.spaceIndex = spaceIndex;
            this.tabIndex = tabIndex;
        }
    }

    /** A project: a lead and its windows, window 1 the lead. */
    static final class Project {
        final String key, name, space;
        final boolean deacon;
        Window lead;
        final List<Window> windows = new ArrayList<>();
        int waiting;
        long born = Long.MAX_VALUE;
        String orderId = "";
        Project(String key, String name, boolean deacon, String space) {
            this.key = key;
            this.name = name;
            this.deacon = deacon;
            this.space = space;
        }
        boolean led() { return deacon || key.startsWith("pm:"); }
    }

    /** One repo of the switcher's list (name null: the projects in no repo). */
    static final class Repo {
        final String name;
        final List<Project> projects = new ArrayList<>();
        Repo(String name) { this.name = name; }
    }

    /** The switcher's list: the deacon first, then the repos, "no repo" last. */
    static final class Groups {
        final List<Project> deacons = new ArrayList<>();
        final List<Repo> repos = new ArrayList<>();
    }

    /** What git says of a folder: its repo's name and whether it is a linked worktree. */
    static final class GitName {
        final String name;
        final boolean linked;
        GitName(String name, boolean linked) { this.name = name; this.linked = linked; }
    }

    private static final class Crew {
        String role = "", parent = "", project = "", repo = "", status = "", cwd = "";
        long born = Long.MAX_VALUE;
    }

    private static final Pattern TASKLESS = Pattern.compile(
            "^(claude|claude code|codex|zsh|bash|fish|sh|shell|terminal|nvim|vim|t[0-9][0-9a-z]*|\\d+)$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern TB = Pattern.compile("^tb\\d+$");
    private static final Pattern TERMINAL_ID = Pattern.compile("^t[0-9][0-9a-z]*$");

    /** A title without its status glyph ("✳ Fix tests" -> "Fix tests"). */
    static String cleanTitle(String s) {
        String t = s == null ? "" : s;
        t = t.replaceFirst("^[^\\p{L}\\p{N}]+", "");
        return t.replaceAll("\\s+", " ").trim();
    }

    /**
     * A window's name (model.js windowName): a tab nobody named goes by its
     * agent's task first, then by its name; a tab named "tbN" or nothing by
     * what runs in it. The phone also gets tabs it made up for a terminal no
     * tab holds, named by the terminal's address: those count as unnamed.
     */
    static String windowName(String tabName, Boolean named, String program, String title) {
        String own = tabName == null ? "" : tabName.trim();
        boolean unnamed = Boolean.FALSE.equals(named) || TERMINAL_ID.matcher(own).matches();
        if (unnamed) {
            String task = cleanTitle(title);
            if (!task.isEmpty() && !TASKLESS.matcher(task).matches()) return task;
        }
        if (!own.isEmpty() && !TB.matcher(own).matches() && !(unnamed && TERMINAL_ID.matcher(own).matches())) return own;
        return program == null || program.isEmpty() ? "shell" : program;
    }

    /** Every project of this core's view, in no particular order (see {@link #groups}). */
    static List<Project> projects(JSONArray workspaces, JSONArray tabs, JSONArray panes) {
        // Spaces and their tabs, in the view's order.
        List<String> spaceIds = new ArrayList<>();
        Map<String, String> spaceName = new HashMap<>();
        for (int i = 0; workspaces != null && i < workspaces.length(); i++) {
            JSONObject s = workspaces.optJSONObject(i);
            if (s == null || s.optString("id").isEmpty()) continue;
            spaceIds.add(s.optString("id"));
            spaceName.put(s.optString("id"), s.optString("label", s.optString("id")));
        }
        Map<String, List<JSONObject>> tabsOf = new LinkedHashMap<>();
        Map<String, int[]> tabAt = new HashMap<>();
        for (int i = 0; tabs != null && i < tabs.length(); i++) {
            JSONObject t = tabs.optJSONObject(i);
            if (t == null) continue;
            String space = t.optString("workspace_id");
            if (!spaceName.containsKey(space)) { spaceIds.add(space); spaceName.put(space, space); }
            List<JSONObject> list = tabsOf.computeIfAbsent(space, k -> new ArrayList<>());
            tabAt.put(space + "\n" + t.optString("id"), new int[] {spaceIds.indexOf(space), list.size()});
            list.add(t);
        }
        // This computer's terminals (its panes), their labels, and where they are.
        Map<String, Crew> crew = new LinkedHashMap<>();
        Map<String, JSONObject> paneOf = new LinkedHashMap<>();
        for (int i = 0; panes != null && i < Math.min(panes.length(), 1024); i++) {
            JSONObject p = panes.optJSONObject(i);
            if (p == null) continue;
            String id = p.optString("id");
            if (id.isEmpty() || id.contains("/") || paneOf.containsKey(id)) continue;
            Crew c = new Crew();
            String role = p.optString("role");
            c.role = "pm".equals(role) || "deacon".equals(role) ? role : "";
            c.parent = p.optString("parent");
            c.project = p.optString("project");
            c.repo = p.optString("repo").trim();
            c.status = p.optString("status");
            c.cwd = p.optString("cwd");
            long born = p.optLong("created_ms", 0);
            c.born = born > 0 ? born : Long.MAX_VALUE;
            crew.put(id, c);
            paneOf.put(id, p);
            String space = p.optString("workspace_id");
            if (!spaceName.containsKey(space)) { spaceIds.add(space); spaceName.put(space, space); }
        }
        Map<String, Window> windowOf = new HashMap<>();
        for (Map.Entry<String, JSONObject> e : paneOf.entrySet()) {
            JSONObject p = e.getValue();
            String space = p.optString("workspace_id"), tab = p.optString("tab_id");
            JSONObject t = tabObject(tabsOf, space, tab);
            int[] at = tabAt.get(space + "\n" + tab);
            Boolean named = t != null && t.has("named") && !t.isNull("named") ? t.optBoolean("named") : null;
            String name = windowName(t != null ? t.optString("label", tab) : "", named,
                    p.optString("program", p.optString("kind")), p.optString("title"));
            windowOf.put(e.getKey(), new Window(e.getKey(), tab, space, p.optString("cwd"), name, p.optString("status"),
                    at == null ? Integer.MAX_VALUE : at[0], at == null ? Integer.MAX_VALUE : at[1]));
        }
        // The leads, each its own project.
        Map<String, Project> out = new LinkedHashMap<>();
        for (Map.Entry<String, Crew> e : crew.entrySet()) {
            Crew c = e.getValue();
            if (c.role.isEmpty()) continue;
            Window lead = windowOf.get(e.getKey());
            boolean pm = "pm".equals(c.role);
            if (pm) lead.name = "lead";
            Project p = new Project(c.role + ":" + e.getKey(), pm ? (c.project.isEmpty() ? e.getKey() : c.project) : "deacon",
                    !pm, lead.space);
            p.lead = lead;
            p.windows.add(lead);
            out.put(e.getKey(), p);
        }
        // Each space's first lead (by its tab).
        Map<String, Project> firstLead = new HashMap<>();
        for (Project p : out.values()) {
            Project at = firstLead.get(p.space);
            if (at == null || p.lead.tabIndex < at.lead.tabIndex) firstLead.put(p.space, p);
        }
        Map<String, Project> bySpace = new LinkedHashMap<>();
        for (String address : paneOf.keySet()) {
            if (out.containsKey(address)) continue;
            Window w = windowOf.get(address);
            String up = leadOf(address, crew);
            if (up == null) {
                Project first = firstLead.get(w.space);
                if (first != null) first.windows.add(w);
                else spaceProject(bySpace, w.space, spaceName).windows.add(w);
                continue;
            }
            out.get(up).windows.add(w);
        }
        // Nothing live vanishes: a tab with no terminal is a window by its tab's name.
        Set<String> withTerminal = new HashSet<>();
        for (Window w : windowOf.values()) withTerminal.add(w.space + "\n" + w.tab);
        for (Map.Entry<String, List<JSONObject>> e : tabsOf.entrySet()) {
            String space = e.getKey();
            for (JSONObject t : e.getValue()) {
                String id = t.optString("id");
                if (withTerminal.contains(space + "\n" + id)) continue;
                int[] at = tabAt.get(space + "\n" + id);
                Boolean named = t.has("named") && !t.isNull("named") ? t.optBoolean("named") : null;
                Window w = new Window(null, id, space, "", windowName(t.optString("label", id), named, "empty", ""),
                        t.optString("status"), at[0], at[1]);
                Project first = firstLead.get(space);
                (first != null ? first : spaceProject(bySpace, space, spaceName)).windows.add(w);
            }
        }
        // A space none of whose terminals or tabs is listed anywhere: its own row.
        Set<String> listed = new HashSet<>();
        for (Project p : out.values()) for (Window w : p.windows) listed.add(w.space);
        for (Project p : bySpace.values()) for (Window w : p.windows) listed.add(w.space);
        for (String s : spaceIds) if (!listed.contains(s) && !firstLead.containsKey(s)) spaceProject(bySpace, s, spaceName);
        List<Project> all = new ArrayList<>(out.values());
        all.addAll(bySpace.values());
        for (Project p : all) {
            List<Window> rest = new ArrayList<>();
            for (Window w : p.windows) if (w != p.lead) rest.add(w);
            Collections.sort(rest, (a, b) -> a.spaceIndex != b.spaceIndex ? Integer.compare(a.spaceIndex, b.spaceIndex)
                    : Integer.compare(a.tabIndex, b.tabIndex));
            p.windows.clear();
            if (p.lead != null) p.windows.add(p.lead);
            p.windows.addAll(rest);
            if (p.lead == null && !p.windows.isEmpty()) p.lead = p.windows.get(0);
            int waiting = 0;
            for (int i = 0; i < p.windows.size(); i++) {
                Window w = p.windows.get(i);
                w.n = i + 1;
                if ("blocked".equals(w.status)) waiting++;
            }
            p.waiting = waiting;
            if (p.key.startsWith("space:")) {
                for (Window w : p.windows) if (w.terminal != null) p.born = Math.min(p.born, crew.get(w.terminal).born);
            } else {
                p.born = crew.get(p.key.substring(p.key.indexOf(':') + 1)).born;
            }
        }
        return all;
    }

    private static JSONObject tabObject(Map<String, List<JSONObject>> tabsOf, String space, String tab) {
        for (JSONObject t : tabsOf.getOrDefault(space, Collections.emptyList())) {
            if (tab.equals(t.optString("id"))) return t;
        }
        return null;
    }

    private static Project spaceProject(Map<String, Project> bySpace, String space, Map<String, String> spaceName) {
        return bySpace.computeIfAbsent(space,
                s -> new Project("space:" + s, spaceName.getOrDefault(s, s), false, s));
    }

    /** The lead a terminal works for: itself if it leads, else up its parent chain; null: none. */
    private static String leadOf(String address, Map<String, Crew> crew) {
        Set<String> seen = new HashSet<>();
        for (String at = address; at != null && !at.isEmpty() && seen.add(at); ) {
            Crew c = crew.get(at);
            if (c == null) return null;
            if (!c.role.isEmpty()) return at;
            at = c.parent;
        }
        return null;
    }

    /**
     * The project focus is in (model.js currentOf): the one whose window
     * holds the focused terminal (its own lead first); else the one whose
     * lead's tab is shown, else any with a window in that tab; else the one
     * leading the shown space, else any with a window there. null: none.
     */
    static Project currentOf(List<Project> list, String terminal, String tab, String space) {
        if (list == null || list.isEmpty()) return null;
        if (terminal != null && !terminal.isEmpty()) {
            for (Project p : list) if (p.lead != null && terminal.equals(p.lead.terminal)) return p;
            for (Project p : list) for (Window w : p.windows) if (terminal.equals(w.terminal)) return p;
        }
        if (tab != null && !tab.isEmpty()) {
            for (Project p : list) if (p.lead != null && tab.equals(p.lead.tab)) return p;
            for (Project p : list) for (Window w : p.windows) if (tab.equals(w.tab)) return p;
        }
        if (space != null && !space.isEmpty()) {
            for (Project p : list) if (p.lead != null && space.equals(p.lead.space)) return p;
            for (Project p : list) for (Window w : p.windows) if (space.equals(w.space)) return p;
        }
        return null;
    }

    /** Which window is shown (model.js shownIndex): the focused terminal's, else the first in the shown tab; -1 none. */
    static int shownIndex(Project p, String terminal, String tab) {
        if (p == null) return -1;
        if (terminal != null && !terminal.isEmpty()) {
            for (int i = 0; i < p.windows.size(); i++) if (terminal.equals(p.windows.get(i).terminal)) return i;
        }
        if (tab != null && !tab.isEmpty()) {
            for (int i = 0; i < p.windows.size(); i++) if (tab.equals(p.windows.get(i).tab)) return i;
        }
        return -1;
    }

    // ---- The switcher's order (items 61, 62, 63) ----------------------------------------------

    /** A git remote URL's repo name (model.js remoteName), or null. */
    static String remoteName(String url) {
        String u = (url == null ? "" : url).trim().replaceAll("/+$", "").replaceAll("(?i)\\.git$", "").replaceAll("/+$", "");
        String[] parts = u.split("[/:\\\\]");
        String last = parts.length == 0 ? "" : parts[parts.length - 1];
        return last.isEmpty() || last.matches("^\\.*$") ? null : last;
    }

    private static final Pattern WORKTREE = Pattern.compile("/\\.herdr/worktrees/([^/]+)/");

    /** The repo of a standard worktree path (~/.herdr/worktrees/<repo>/...), or null. */
    static String worktreeRepo(String cwd) {
        Matcher m = WORKTREE.matcher((cwd == null ? "" : cwd) + "/");
        return m.find() ? m.group(1) : null;
    }

    /**
     * The repo a project sits under (model.js repoOf): a repo= label (its
     * lead's, else any window's), named as a remote would be; else its lead's
     * repo by git; else a standard worktree path its lead is in; else the repo
     * most of its windows work in (ties: a main checkout). null: none.
     */
    static String repoOf(Project p, Map<String, GitName> git, Map<String, String> repoLabels) {
        List<Window> ws = new ArrayList<>();
        if (p.lead != null) ws.add(p.lead);
        ws.addAll(p.windows);
        for (Window w : ws) {
            String label = w.terminal == null ? null : repoLabels.get(w.terminal);
            if (label != null && !label.isEmpty()) {
                String name = remoteName(label);
                return name != null ? name : label;
            }
        }
        GitName lead = p.lead == null ? null : git.get(p.lead.cwd);
        if (lead != null && lead.name != null) return lead.name;
        String path = p.lead == null ? null : worktreeRepo(p.lead.cwd);
        if (path != null) return path;
        Map<String, Integer> n = new LinkedHashMap<>();
        Set<String> main = new HashSet<>();
        for (Window w : p.windows) {
            GitName g = git.get(w.cwd);
            if (g == null || g.name == null) continue;
            n.merge(g.name, 1, Integer::sum);
            if (!g.linked) main.add(g.name);
        }
        String best = null;
        for (String k : n.keySet()) {
            if (best == null || n.get(k) > n.get(best) || (n.get(k).equals(n.get(best)) && main.contains(k) && !main.contains(best))) best = k;
        }
        return best;
    }

    /** A project's id in the order (model.js orderId): its name with each "#" doubled, then "#at". */
    static String orderId(String name, String at) {
        return String.valueOf(name).replace("#", "##") + (at == null || at.isEmpty() ? "" : "#" + at);
    }

    /** What tells a project apart on its computer: its lead's address, else its space's id. */
    private static String localId(Project p) {
        if (p.lead != null && p.lead.terminal != null) return p.lead.terminal;
        return p.space != null ? p.space : p.key;
    }

    interface Id<T> { String of(T item); }

    /**
     * {@code list} with the ones {@code ids} names first, in that order, then
     * the rest as they were (model.js orderBy). {@code oldOf}: an id an entry
     * may have been written under before (a plain name); it places one item only.
     */
    static <T> List<T> orderBy(List<T> list, List<String> ids, Id<T> idOf, Id<T> oldOf) {
        if (ids == null || ids.isEmpty()) return list;
        Map<String, Integer> at = new HashMap<>();
        for (int i = 0; i < ids.size(); i++) if (ids.get(i) != null && !at.containsKey(ids.get(i))) at.put(ids.get(i), i);
        Set<String> used = new HashSet<>();
        List<Object[]> ranked = new ArrayList<>();
        for (int k = 0; k < list.size(); k++) {
            T x = list.get(k);
            Integer own = at.get(idOf.of(x));
            int rank;
            if (own != null) {
                rank = own;
            } else {
                String old = oldOf == null ? null : oldOf.of(x);
                if (old == null || old.equals(idOf.of(x)) || used.contains(old) || !at.containsKey(old)) {
                    rank = Integer.MAX_VALUE;
                } else {
                    used.add(old);
                    rank = at.get(old);
                }
            }
            ranked.add(new Object[] {rank, k, x});
        }
        Collections.sort(ranked, (a, b) -> (int) a[0] != (int) b[0] ? Integer.compare((int) a[0], (int) b[0])
                : Integer.compare((int) a[1], (int) b[1]));
        List<T> out = new ArrayList<>();
        for (Object[] r : ranked) {
            @SuppressWarnings("unchecked") T x = (T) r[2];
            out.add(x);
        }
        return out;
    }

    private static List<String> strings(JSONArray array) {
        if (array == null) return null;
        List<String> out = new ArrayList<>();
        for (int i = 0; i < array.length(); i++) out.add(array.optString(i, null));
        return out;
    }

    /**
     * The switcher's list (model.js leads + repoGroups, one computer): the
     * deacon first; then the repos by their oldest project's birth, Wolf's
     * order (the core's projects.order doc) over that; then "no repo". In a
     * repo, its projects by birth (their lead terminal's created_ms), name,
     * key, Wolf's order over that. A new project comes last; a name listed
     * that is gone is skipped.
     */
    static Groups groups(List<Project> all, Map<String, GitName> git, Map<String, String> repoLabels, JSONObject order) {
        List<Project> list = new ArrayList<>(all);
        Collections.sort(list, (a, b) -> {
            int ra = a.deacon ? 0 : a.key.startsWith("pm:") ? 1 : 2, rb = b.deacon ? 0 : b.key.startsWith("pm:") ? 1 : 2;
            if (ra != rb) return Integer.compare(ra, rb);
            if (a.born != b.born) return Long.compare(a.born, b.born);
            int byName = a.name.compareTo(b.name);
            return byName != 0 ? byName : a.key.compareTo(b.key);
        });
        Groups out = new Groups();
        Map<String, Repo> named = new LinkedHashMap<>();
        Repo none = new Repo(null);
        for (Project p : list) {
            if (p.deacon) { out.deacons.add(p); continue; }
            String r = repoOf(p, git, repoLabels);
            if (r == null) none.projects.add(p);
            else named.computeIfAbsent(r, Repo::new).projects.add(p);
        }
        List<Repo> repos = new ArrayList<>(named.values());
        Collections.sort(repos, (a, b) -> {
            long oa = oldest(a), ob = oldest(b);
            return oa != ob ? Long.compare(oa, ob) : a.name.compareTo(b.name);
        });
        repos = orderBy(repos, order == null ? null : strings(order.optJSONArray("repos")), g -> g.name, null);
        if (!none.projects.isEmpty()) repos.add(none);
        Map<String, Integer> sameName = new HashMap<>();
        for (Project p : list) sameName.merge(p.name, 1, Integer::sum);
        for (Project p : list) p.orderId = orderId(p.name, sameName.get(p.name) > 1 ? localId(p) : null);
        JSONObject byRepo = order == null ? null : order.optJSONObject("projects");
        for (Repo g : repos) {
            List<Project> ordered = new ArrayList<>(orderBy(g.projects,
                    byRepo == null ? null : strings(byRepo.optJSONArray(g.name == null ? "" : g.name)),
                    p -> p.orderId, p -> p.name));
            g.projects.clear();
            g.projects.addAll(ordered);
            out.repos.add(g);
        }
        return out;
    }

    private static long oldest(Repo g) {
        long min = Long.MAX_VALUE;
        for (Project p : g.projects) min = Math.min(min, p.born);
        return min;
    }

    /** The repo= labels of this view's terminals: address -> label. */
    static Map<String, String> repoLabels(JSONArray panes) {
        Map<String, String> out = new HashMap<>();
        for (int i = 0; panes != null && i < panes.length(); i++) {
            JSONObject p = panes.optJSONObject(i);
            if (p != null && !p.optString("repo").trim().isEmpty()) out.put(p.optString("id"), p.optString("repo").trim());
        }
        return out;
    }
}
