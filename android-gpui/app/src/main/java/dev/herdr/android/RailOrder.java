package dev.herdr.android;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Wolf's order of the rail's groups, repos and PMs, and what a drag-and-drop
 * does to it (factory-r8f.8), as the desktop's projects model has them
 * (home-manager-conf mods/projects/model.js repoGroups and dropPlan, checked
 * through the shared order-cases.json). Pure: one computer's projects and its
 * core doc projects.order in, sections and drop plans out.
 *
 * <p>Sections: the named groups first (a PM's herdr.group label, else its
 * older group= label), by name then Wolf's {@code groups}; a group's PMs by
 * birth, then name, then his {@code grouped[<group>]}. Then the repos (as the
 * caller lists them, then his {@code repos}), each repo's PMs as listed, then
 * his {@code projects[<repo>]}. The PMs in no repo last.
 */
final class RailOrder {
    private RailOrder() {}

    /** One PM on its computer: what the order and a drop need of it. */
    static final class Item {
        final String name;
        /** Its lead terminal's address on its computer, or "" (none to label). */
        final String lead;
        /** Its lead's birth (created_ms); Long.MAX_VALUE when unknown. */
        final long born;
        /** herdr.group, and the older group= label ("" none). */
        final String group, oldGroup;
        /** Its repo's name, or null: in no repo. */
        final String repo;
        /** Its id in projects.order: its name, or name#lead when two PMs share it (sections()). */
        String orderId;

        Item(String name, String lead, long born, String group, String oldGroup, String repo) {
            this.name = name == null ? "" : name;
            this.lead = lead == null ? "" : lead;
            this.born = born <= 0 ? Long.MAX_VALUE : born;
            this.group = group == null ? "" : group;
            this.oldGroup = oldGroup == null ? "" : oldGroup;
            this.repo = repo;
            this.orderId = this.name;
        }

        /** The group it is drawn in (herdr.group ?? group), or "". */
        String shownGroup() {
            return !group.isEmpty() ? group : oldGroup;
        }

        /** The section it sits in without a group: repo:<name>, else none. */
        String home() {
            return repo == null ? NONE : "repo:" + repo;
        }
    }

    static final String NONE = "none";

    /** A group, a repo or no repo, and its PMs in drawn order. */
    static final class Section {
        final String key;
        /** Its name; null for no repo. */
        final String name;
        final boolean group;
        final List<Item> projects = new ArrayList<>();

        Section(String key, String name, boolean group) {
            this.key = key;
            this.name = name;
            this.group = group;
        }

        /** The list its PMs' moves change. */
        String scope() {
            return group ? "group:" + name : "repo:" + (name == null ? "" : name);
        }
    }

    private static List<String> strings(JSONArray a) {
        if (a == null) return null;
        List<String> out = new ArrayList<>();
        for (int i = 0; i < a.length(); i++) {
            Object x = a.opt(i);
            if (x instanceof String) out.add((String) x);
        }
        return out;
    }

    private static List<String> list(JSONObject order, String key) {
        return order == null ? null : strings(order.optJSONArray(key));
    }

    private static List<String> list(JSONObject order, String key, String in) {
        JSONObject lists = order == null ? null : order.optJSONObject(key);
        return lists == null ? null : strings(lists.optJSONArray(in == null ? "" : in));
    }

    static final Comparator<Item> BY_BIRTH = (a, b) -> {
        int c = Long.compare(a.born, b.born);
        return c != 0 ? c : a.name.compareTo(b.name);
    };

    /**
     * The sections in drawn order (model.js repoGroups, less the deacon and
     * the git look: {@link Item#repo} says the repo). {@code items}: the
     * computer's PMs as the rail lists them; repos keep the order their first
     * PM comes in.
     */
    static List<Section> sections(List<Item> items, JSONObject order) {
        Map<String, Integer> names = new HashMap<>();
        for (Item p : items) names.merge(p.name, 1, Integer::sum);
        for (Item p : items) p.orderId = ProjectModel.orderId(p.name, names.get(p.name) > 1 ? p.lead : null);
        Map<String, List<Item>> labelled = new TreeMap<>();
        Map<String, Section> repos = new LinkedHashMap<>();
        Section none = new Section(NONE, null, false);
        for (Item p : items) {
            String g = p.shownGroup();
            if (!g.isEmpty()) { labelled.computeIfAbsent(g, k -> new ArrayList<>()).add(p); continue; }
            if (p.repo == null) { none.projects.add(p); continue; }
            repos.computeIfAbsent(p.repo, k -> new Section("repo:" + k, k, false)).projects.add(p);
        }
        List<Section> groups = new ArrayList<>();
        for (Map.Entry<String, List<Item>> e : labelled.entrySet()) {
            Section s = new Section("group:" + e.getKey(), e.getKey(), true);
            List<Item> byBirth = new ArrayList<>(e.getValue());
            Collections.sort(byBirth, BY_BIRTH);
            s.projects.addAll(ProjectModel.orderBy(byBirth, list(order, "grouped", e.getKey()), x -> x.orderId, x -> x.name));
            groups.add(s);
        }
        List<Section> out = new ArrayList<>(ProjectModel.orderBy(groups, list(order, "groups"), s -> s.name, null));
        for (Section r : ProjectModel.orderBy(new ArrayList<>(repos.values()), list(order, "repos"), s -> s.name, null)) {
            List<Item> placed = ProjectModel.orderBy(new ArrayList<>(r.projects), list(order, "projects", r.name), x -> x.orderId, x -> x.name);
            r.projects.clear();
            r.projects.addAll(placed);
            out.add(r);
        }
        if (!none.projects.isEmpty()) {
            List<Item> placed = ProjectModel.orderBy(new ArrayList<>(none.projects), list(order, "projects", ""), x -> x.orderId, x -> x.name);
            none.projects.clear();
            none.projects.addAll(placed);
            out.add(none);
        }
        return out;
    }

    /** {@code ids} with {@code id} just before or after {@code target}; null when nothing moves (model.js moved). */
    static List<String> moved(List<String> ids, String id, String target, boolean after) {
        if (id.equals(target) || !ids.contains(id) || !ids.contains(target)) return null;
        List<String> rest = new ArrayList<>(ids);
        rest.remove(id);
        rest.add(rest.indexOf(target) + (after ? 1 : 0), id);
        return rest.equals(ids) ? null : rest;
    }

    /** What is dragged, or what it is over: a section (by key) or a PM (by orderId, in its section). */
    static final class At {
        final String section, project;

        private At(String section, String project) {
            this.section = section;
            this.project = project;
        }

        static At section(String key) {
            return new At(key, null);
        }

        static At project(String id, String section) {
            return new At(section, id);
        }

        boolean isSection() {
            return project == null;
        }
    }

    /** One move in projects.order: `id` before or after `target` in `scope`'s list, drawn as `peers`. */
    static final class Move {
        final String scope, id, target;
        final boolean after;
        final List<String> peers;

        Move(String scope, String id, String target, boolean after, List<String> peers) {
            this.scope = scope;
            this.id = id;
            this.target = target;
            this.after = after;
            this.peers = peers;
        }
    }

    enum Op { MOVE, REGROUP, REFUSE }

    /** What a drop does: a move; a herdr.group relabel of `leads` (`group` null: cleared) and a move; or a refusal. */
    static final class Plan {
        final Op op;
        final Move move;
        final List<String> leads;
        final String group, why;

        private Plan(Op op, Move move, List<String> leads, String group, String why) {
            this.op = op;
            this.move = move;
            this.leads = leads;
            this.group = group;
            this.why = why;
        }

        static Plan move(Move m) {
            return new Plan(Op.MOVE, m, Collections.emptyList(), null, null);
        }

        static Plan regroup(List<String> leads, String group, Move m) {
            return new Plan(Op.REGROUP, m, leads, group, null);
        }

        static Plan refuse(String why) {
            return new Plan(Op.REFUSE, null, Collections.emptyList(), null, why);
        }
    }

    private static Section at(List<Section> sections, String key) {
        for (Section s : sections) if (s.key.equals(key)) return s;
        return null;
    }

    /**
     * What a drop does (model.js dropPlan): {@code item} let go over
     * {@code over}, in its lower half when {@code after}. A section moves
     * among its own kind (groups among groups, named repos among named
     * repos); a PM moves within its section, into another group (its
     * herdr.group becomes that group), or out of its group onto its own repo
     * (herdr.group cleared, refused while an older group= label would keep it
     * grouped); into any other repo it is refused. Null: nothing changes.
     * {@code order}: the computer's projects.order, for where a PM lands in a
     * section it is not in yet.
     */
    static Plan dropPlan(List<Section> sections, At item, At over, boolean after, JSONObject order) {
        if (item.isSection()) {
            Section from = at(sections, item.section), to = at(sections, over.section);
            if (from == null || to == null || from.name == null || to.name == null) return null;
            if (from.group != to.group) return Plan.refuse(from.group ? "groups stay above the repos" : "repos stay below the groups");
            if (from == to) return null;
            List<String> peers = new ArrayList<>();
            for (Section s : sections) if (s.group == from.group && s.name != null) peers.add(s.name);
            return moved(peers, from.name, to.name, after) == null ? null
                    : Plan.move(new Move(from.group ? "groups" : "repos", from.name, to.name, after, peers));
        }
        Section from = at(sections, item.section), to = at(sections, over.section);
        Item p = null;
        if (from != null) for (Item x : from.projects) if (x.orderId.equals(item.project)) p = x;
        if (from == null || p == null || to == null) return null;
        Move m = moveIn(to, p, item, over, after, order);
        if (from == to) return m == null ? null : Plan.move(m);
        if (p.lead.isEmpty()) return Plan.refuse("no lead terminal to label");
        List<String> leads = Collections.singletonList(p.lead);
        if (to.group) return Plan.regroup(leads, to.name, m);
        if (from.group && to.key.equals(p.home())) {
            if (!p.oldGroup.isEmpty()) return Plan.refuse("its group= label keeps it grouped (herdr-group --clear)");
            return Plan.regroup(leads, null, m);
        }
        return Plan.refuse("a project's repo comes from git");
    }

    /**
     * {@code order} with {@code m} made, as the core's writer makes it
     * (core_session order_move): the scope's stored list plus the names
     * {@code m.peers} adds, then the move; every other key as it was. Drawn
     * at once after a drop, until the core's own doc comes back.
     */
    static JSONObject applied(JSONObject order, Move m) {
        JSONObject out;
        try {
            out = order == null ? new JSONObject() : new JSONObject(order.toString());
            String top, key;
            if ("repos".equals(m.scope) || "groups".equals(m.scope)) { top = m.scope; key = null; }
            else if (m.scope.startsWith("group:")) { top = "grouped"; key = m.scope.substring(6); }
            else { top = "projects"; key = m.scope.startsWith("repo:") ? m.scope.substring(5) : m.scope; }
            List<String> stored = key == null ? list(out, top) : list(out, top, key);
            List<String> ids = stored == null ? new ArrayList<>() : new ArrayList<>(stored);
            for (String x : m.peers) if (!ids.contains(x)) ids.add(x);
            List<String> next = moved(ids, m.id, m.target, m.after);
            if (next == null) return order;
            if (key == null) {
                out.put(top, new JSONArray(next));
            } else {
                JSONObject lists = out.optJSONObject(top);
                if (lists == null) { lists = new JSONObject(); out.put(top, lists); }
                lists.put(key, new JSONArray(next));
            }
        } catch (org.json.JSONException e) {
            return order;
        }
        return out;
    }

    /** The move that puts {@code p} where it was dropped in {@code to}, from where it would show there. */
    private static Move moveIn(Section to, Item p, At item, At over, boolean after, JSONObject order) {
        List<Item> drawn;
        if (to.projects.contains(p)) {
            drawn = to.projects;
        } else {
            List<Item> byBirth = new ArrayList<>(to.projects);
            byBirth.add(p);
            Collections.sort(byBirth, BY_BIRTH);
            List<String> stored = to.group ? list(order, "grouped", to.name) : list(order, "projects", to.name == null ? "" : to.name);
            drawn = ProjectModel.orderBy(byBirth, stored, x -> x.orderId, x -> x.name);
        }
        List<String> peers = new ArrayList<>();
        for (Item x : drawn) peers.add(x.orderId);
        String target;
        boolean below;
        if (!over.isSection() && !over.project.equals(item.project) && peers.contains(over.project)) {
            target = over.project;
            below = after;
        } else {
            target = null;
            for (String x : peers) if (!x.equals(item.project)) { target = x; break; }
            below = false;
            if (target == null) return null;
        }
        if (moved(peers, item.project, target, below) == null) return null;
        return new Move(to.scope(), item.project, target, below, peers);
    }
}
