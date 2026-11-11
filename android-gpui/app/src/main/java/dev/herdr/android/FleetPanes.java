package dev.herdr.android;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * The one adapter from a core's view (workspaces, tabs, panes) to
 * FleetTree members, shared by Home and the terminal's rail so both draw
 * the same Deacon › PM › worktree › worker tree. Also the per-machine git
 * look cache both of them fill and read.
 */
final class FleetPanes {
    private FleetPanes() { }

    /** Each machine's latest git look and when it was taken, by saved host id. */
    static final Map<String, FleetTree.GitLook> LOOKS = new ConcurrentHashMap<>();
    static final Map<String, Long> LOOKED_AT = new ConcurrentHashMap<>();

    /**
     * This core's own terminals (federated "machine/tN" entries belong to
     * other computers, each shown under its own connection), in view order.
     */
    static <T extends FleetTree.Member> List<T> members(JSONArray workspaces, JSONArray tabs, JSONArray panes,
            Supplier<T> make) {
        List<T> out = new ArrayList<>();
        Map<String, String> labels = new HashMap<>();
        Map<String, Integer> spaceIndex = new HashMap<>();
        for (int i = 0; workspaces != null && i < workspaces.length(); i++) {
            JSONObject space = workspaces.optJSONObject(i);
            if (space == null) continue;
            labels.put(space.optString("id"), space.optString("label", ""));
            spaceIndex.put(space.optString("id"), i);
        }
        Map<String, Integer> tabIndex = new HashMap<>();
        Map<String, Integer> perSpace = new HashMap<>();
        for (int i = 0; tabs != null && i < tabs.length(); i++) {
            JSONObject tab = tabs.optJSONObject(i);
            if (tab == null) continue;
            String space = tab.optString("workspace_id");
            int at = perSpace.merge(space, 1, Integer::sum) - 1;
            tabIndex.put(space + "\n" + tab.optString("id"), at);
        }
        for (int i = 0; panes != null && i < Math.min(panes.length(), 1024); i++) {
            JSONObject pane = panes.optJSONObject(i);
            if (pane == null) continue;
            String id = pane.optString("id");
            if (id.isEmpty() || id.contains("/")) continue;
            T m = make.get();
            m.id = id;
            m.title = HomeActivity.cleanTitle(GpuiTerminalProtocol.title(pane.optString("title", id)), id);
            m.status = pane.optString("status", "");
            m.space = pane.optString("workspace_id", "loose");
            m.spaceName = labels.containsKey(m.space) ? labels.get(m.space).trim() : "";
            m.tab = pane.optString("tab_id", "");
            m.spaceIndex = spaceIndex.getOrDefault(m.space, Integer.MAX_VALUE);
            m.tabIndex = tabIndex.getOrDefault(m.space + "\n" + m.tab, Integer.MAX_VALUE);
            m.createdMs = pane.optLong("created_ms", 0);
            m.parent = pane.optString("parent", "");
            // Only the deacon skill's roles count; any other value is no role.
            m.role = FleetTree.role(pane.optString("role", ""));
            m.program = pane.optString("program", "");
            m.project = pane.optString("project", "");
            m.kind = pane.optString("kind", "");
            if ("none".equals(m.kind)) m.kind = "";
            m.cwd = pane.optString("cwd", "");
            m.msg = pane.optString("msg", "").replace('\n', ' ').trim();
            m.waitKind = pane.optString("wait_kind", "");
            m.statusMs = pane.optLong("status_ms", 0);
            out.add(m);
        }
        return out;
    }

    /**
     * {@link FleetTree#switchLanding} on a new session's poll: only its own
     * live terminals count, so the Deacon focused is that computer's, now.
     */
    static String switchLanding(JSONObject state, String explicit) {
        if (state == null) return "";
        return FleetTree.switchLanding(explicit, members(state.optJSONArray("workspaces"), state.optJSONArray("tabs"),
                state.optJSONArray("panes"), FleetTree.Member::new));
    }

    /** Whether a poll already carries the spaces (a Deacon may be known only by its space's name). */
    static boolean hasSpaces(JSONObject state) {
        return state != null && state.optJSONArray("workspaces") != null;
    }

    /** A title worth showing: the agent's own, else its harness or "shell" and folder. */
    static String displayTitle(FleetTree.Member m) {
        if (!m.title.isEmpty() && !RailTree.isTerminalId(m.title)) return m.title;
        String folder = FleetTree.lastSegment(m.cwd);
        String who = m.kind.isEmpty() ? "shell" : m.kind;
        return folder.isEmpty() ? who : who + " · " + folder;
    }

    /** Every folder git should be asked about: each space's first, then each terminal's own (≤ 96). */
    static java.util.Set<String> gitPaths(List<? extends FleetTree.Member> all) {
        Map<String, List<FleetTree.Member>> bySpace = new java.util.LinkedHashMap<>();
        for (FleetTree.Member m : all) if (m.hasSpace()) bySpace.computeIfAbsent(m.space, k -> new ArrayList<>()).add(m);
        java.util.Set<String> paths = new java.util.LinkedHashSet<>();
        for (List<FleetTree.Member> list : bySpace.values()) {
            String probe = FleetTree.probe(list);
            if (probe.startsWith("/") && paths.size() < 96) paths.add(probe);
        }
        for (FleetTree.Member m : all) {
            String cwd = FleetTree.trimSlash(m.cwd);
            if (m.hasSpace() && cwd.startsWith("/") && paths.size() < 96) paths.add(cwd);
        }
        return paths;
    }
}
