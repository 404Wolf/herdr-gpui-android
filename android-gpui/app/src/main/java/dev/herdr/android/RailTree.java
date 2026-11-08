package dev.herdr.android;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Groups herdr spaces as repo → worktrees for the terminal rail. */
final class RailTree {
    private static final String WORKTREES = "/.herdr/worktrees/";

    private RailTree() { }

    /** Worktree space id → its repo, from the spaces' pane folders. Other spaces are absent. */
    static Map<String, String> repos(JSONArray panes) {
        Map<String, String> repos = new HashMap<>();
        for (int i = 0; panes != null && i < panes.length(); i++) {
            JSONObject pane = panes.optJSONObject(i);
            if (pane == null) continue;
            String space = pane.optString("workspace_id", "");
            if (repos.containsKey(space)) continue;
            String repo = repoOfPath(pane.optString("cwd", ""));
            if (repo != null) repos.put(space, repo);
        }
        return repos;
    }

    /** "~/.herdr/worktrees/monopoly/wolf-x/src" → "monopoly". */
    static String repoOfPath(String cwd) {
        int at = cwd == null ? -1 : cwd.indexOf(WORKTREES);
        if (at < 0) return null;
        String rest = cwd.substring(at + WORKTREES.length());
        int slash = rest.indexOf('/');
        String repo = slash < 0 ? "" : rest.substring(0, slash);
        // A bare worktrees/<repo> folder is not a worktree yet.
        return repo.isEmpty() || rest.substring(slash + 1).isEmpty() ? null : repo;
    }

    /**
     * Spaces in the daemon's order, except that each repo's worktrees follow
     * the repo's own space (matched by name). Worktrees of a repo without a
     * space stay together where the first of them was.
     */
    static List<JSONObject> order(JSONArray workspaces, Map<String, String> repos) {
        Map<String, List<JSONObject>> groups = new LinkedHashMap<>();
        List<String> keys = new ArrayList<>();
        for (int i = 0; workspaces != null && i < workspaces.length(); i++) {
            JSONObject space = workspaces.optJSONObject(i);
            if (space == null) continue;
            String id = space.optString("id", "");
            String repo = repos.get(id);
            String key = repo != null ? repo : space.optString("label", id);
            if (!groups.containsKey(key)) {
                groups.put(key, new ArrayList<>());
                keys.add(key);
            }
            List<JSONObject> group = groups.get(key);
            // The repo's own space leads its group even if a worktree came first.
            if (repo == null) {
                int lead = 0;
                while (lead < group.size() && !repos.containsKey(group.get(lead).optString("id", ""))) lead++;
                group.add(lead, space);
            } else {
                group.add(space);
            }
        }
        List<JSONObject> out = new ArrayList<>();
        for (String key : keys) out.addAll(groups.get(key));
        return out;
    }

    /**
     * Agents title themselves "Task | worktree"; under the worktree's own
     * header the suffix only repeats it. "Task | wolf-ins1584-study-fr…" → "Task".
     */
    static String withoutSpace(String title, String space) {
        int bar = title.lastIndexOf(" | ");
        if (bar <= 0 || space.isEmpty()) return title;
        String tail = title.substring(bar + 3).replace("…", "").replace("...", "").trim();
        while (tail.endsWith(".")) tail = tail.substring(0, tail.length() - 1);
        return !tail.isEmpty() && space.startsWith(tail) ? title.substring(0, bar).trim() : title;
    }
}
