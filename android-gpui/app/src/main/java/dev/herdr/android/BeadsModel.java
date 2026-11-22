package dev.herdr.android;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;

/**
 * The factory's beads reading and Work-panel model on the phone, a
 * rule-for-rule port of the desktop factory mod (home-manager-conf branch
 * factory/pm-beads at 5e6c79eb, users/wolf/programs/herdr-core/app/factory/src:
 * feed.ts parseBeads, errorLine, parseStores, knownArgs, parseDetail, webUrl,
 * splitBeads; model.ts parentOf, compareIds, compareBeads, beadTree, progress,
 * crewOf, workScope, beadOfWorker, joinProject, needsYou, agentsByBead,
 * agentsOnBead, beadPlace, epicProject, repoProjects; factory-sidebar.ts
 * readBeads' merge; factory-work.ts project()'s epics, tree and folding;
 * feed.ts buildWork's needs-you). The shared cases are the fixture
 * app/src/test/resources/beads-cases.json; a change on either side is diffed
 * against it.
 *
 * <p>One model is one core connection, so one machine: the desktop's machine
 * qualifiers (projectKey's "machine:", qualified addresses) are dropped, a
 * store is keyed by STORES_SH's name alone, and every member is live. Ev
 * (asks, pins, CI) is not read here: the desktop's Asking is always empty.
 */
final class BeadsModel {
    private BeadsModel() { }

    /** The label a PM sets on a bead that needs Wolf's decision. */
    static final String NEEDS_LABEL = "needs-wolf";
    /** A store kept inside a code repo: STORES_SH lists it as repo:&lt;name&gt;; never a project itself. */
    static final String REPO_STORE = "repo:";

    // ---- beads -----------------------------------------------------------------

    static final class Dep {
        final String issueId, dependsOnId, type;
        Dep(String issueId, String dependsOnId, String type) {
            this.issueId = issueId;
            this.dependsOnId = dependsOnId;
            this.type = type;
        }
    }

    /** One bead row of `bd list --json` (bd 1.3.x; only what is read). */
    static final class Bead {
        final String id, title, status;
        /** bd's issue_type (epic, task, ...), or null. */
        final String type;
        final String parent, assignee;
        final Integer priority;
        final List<String> labels;
        final List<Dep> dependencies;
        Bead(String id, String title, String status, String type, Integer priority, List<String> labels,
                String parent, String assignee, List<Dep> dependencies) {
            this.id = id;
            this.title = title;
            this.status = status;
            this.type = type;
            this.priority = priority;
            this.labels = labels;
            this.parent = parent;
            this.assignee = assignee;
            this.dependencies = dependencies;
        }
    }

    /** One store's read: beads, or an error, or (same) unchanged since the read that gave fp. */
    static final class Store {
        /** Null when same, or when the read failed and nothing was read before. */
        final List<Bead> beads;
        final String error, fp;
        final boolean same;
        Store(List<Bead> beads, String error, String fp, boolean same) {
            this.beads = beads;
            this.error = error;
            this.fp = fp;
            this.same = same;
        }
    }

    /** STORES_SH's output: bd missing, or every store it listed, in its order. */
    static final class Stores {
        final boolean missing;
        final Map<String, Store> byProject;
        Stores(boolean missing, Map<String, Store> byProject) {
            this.missing = missing;
            this.byProject = byProject;
        }
    }

    /** JS's \\s, spelled out: Android's Pattern has no UNICODE_CHARACTER_CLASS. */
    private static final String WS = "\\s\\u00a0\\u1680\\u2000-\\u200a\\u2028\\u2029\\u202f\\u205f\\u3000\\ufeff";
    private static final Pattern ERROR_LINE = Pattern.compile("^error\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern SAME = Pattern.compile("\\A\u001fsame[" + WS + "]*\\z");
    private static final Pattern CODE = Pattern.compile("\u001f([0-9]+)[" + WS + "]*\\z");
    private static final Pattern MISSING = Pattern.compile("\u001fmissing[" + WS + "]*\\z");
    private static final Pattern STATUS_WORD = Pattern.compile("\u001f\\w+[" + WS + "]*\\z");
    private static final Pattern WEB = Pattern.compile("^https?://[^" + WS + "]+$", Pattern.CASE_INSENSITIVE);
    private static final Pattern PREFIXED = Pattern.compile("^[A-Za-z0-9]+-(.+)$");

    /** JS String.prototype.trim: Unicode white space and line terminators, and U+FEFF. */
    static String trim(String s) {
        int a = 0, b = s.length();
        while (a < b && blank(s.charAt(a))) a++;
        while (b > a && blank(s.charAt(b - 1))) b--;
        return s.substring(a, b);
    }

    private static boolean blank(char c) {
        return Character.isWhitespace(c) || Character.isSpaceChar(c) || c == '\uFEFF';
    }

    private static String cut(String s, int max) {
        return s.length() > max ? s.substring(0, max) : s;
    }

    /** One JSON value, strictly: the whole text or a JSONException. */
    private static Object json(String text) throws JSONException {
        JSONTokener x = new JSONTokener(text);
        Object v = x.nextValue();
        if (x.nextClean() != 0) throw new JSONException("trailing text after JSON");
        if (v instanceof String && !trim(text).startsWith("\"")) throw new JSONException("not JSON: " + cut(trim(text), 40));
        return v;
    }

    private static String str(JSONObject o, String k) {
        Object v = o.opt(k);
        return v instanceof String ? (String) v : null;
    }

    /** A JS `filled`: a string with something besides white space, as it is. */
    private static String filled(JSONObject o, String k) {
        String v = o == null ? null : str(o, k);
        return v != null && !trim(v).isEmpty() ? v : null;
    }

    private static Bead bead(JSONObject r) {
        String id = str(r, "id");
        if (id == null) return null;
        String title = str(r, "title");
        String status = str(r, "status");
        Object p = r.opt("priority");
        List<String> labels = new ArrayList<>();
        JSONArray ls = r.optJSONArray("labels");
        for (int i = 0; ls != null && i < ls.length(); i++) if (ls.opt(i) instanceof String) labels.add((String) ls.opt(i));
        List<Dep> deps = new ArrayList<>();
        JSONArray ds = r.optJSONArray("dependencies");
        for (int i = 0; ds != null && i < ds.length(); i++) {
            JSONObject d = ds.optJSONObject(i);
            if (d != null) deps.add(new Dep(str(d, "issue_id"), str(d, "depends_on_id"), str(d, "type")));
        }
        return new Bead(id, title != null ? title : id, status != null ? status : "open", str(r, "issue_type"),
                p instanceof Number ? ((Number) p).intValue() : null, labels, str(r, "parent"), str(r, "assignee"), deps);
    }

    /** parseBeads' answer: the beads, or why there are none. */
    static final class BeadList {
        final List<Bead> beads;
        final String error;
        BeadList(List<Bead> beads, String error) {
            this.beads = beads;
            this.error = error;
        }
    }

    /** bd's JSON array, from output that may carry warnings before it. */
    static BeadList parseBeads(String text) {
        int start = text.indexOf('[');
        int end = text.lastIndexOf(']');
        if (start >= 0 && end < start) return new BeadList(null, "bd list: the JSON was cut short");
        if (start < 0) {
            String last = null;
            for (String line : trim(text).split("\n", -1)) if (!line.isEmpty()) last = line;
            return new BeadList(null, cut(last != null ? last : "no output", 200));
        }
        Object rows;
        try {
            rows = json(text.substring(start, end + 1));
        } catch (JSONException e) {
            return new BeadList(null, "bd list: bad JSON (" + e.getMessage() + ")");
        }
        if (!(rows instanceof JSONArray)) return new BeadList(null, "bd list didn't print a list");
        JSONArray a = (JSONArray) rows;
        List<Bead> beads = new ArrayList<>();
        for (int i = 0; i < a.length(); i++) {
            JSONObject r = a.optJSONObject(i);
            Bead b = r == null ? null : bead(r);
            if (b != null) beads.add(b);
        }
        return new BeadList(beads, null);
    }

    /** The line of bd's output that says what went wrong: its "Error:" line, else the last. */
    static String errorLine(String text) {
        List<String> lines = new ArrayList<>();
        for (String l : text.split("\n", -1)) {
            String t = trim(l);
            if (!t.isEmpty()) lines.add(t);
        }
        for (String l : lines) if (ERROR_LINE.matcher(l).find()) return cut(l, 200);
        return lines.isEmpty() ? "" : cut(lines.get(lines.size() - 1), 200);
    }

    /**
     * STORES_SH's output, per store, framed so one store's failure doesn't hide
     * the others: \x1e&lt;store&gt;\n\x1c&lt;fingerprint&gt;\n&lt;bd's output&gt;\n\x1f&lt;exit code&gt;\n,
     * or \x1fsame\n for an unchanged store; "\x1dnobd\n" alone when bd is missing.
     */
    static Stores parseStores(String out) {
        if (out.startsWith("\u001dnobd")) return new Stores(true, new LinkedHashMap<String, Store>());
        Map<String, Store> by = new LinkedHashMap<>();
        String[] chunks = out.split("\u001e", -1);
        for (int i = 1; i < chunks.length; i++) {
            String chunk = chunks[i];
            int nl = chunk.indexOf('\n');
            String project = nl < 0 ? chunk : chunk.substring(0, nl);
            String rest = nl < 0 ? "" : chunk.substring(nl + 1);
            String fp = "";
            int fnl = rest.indexOf('\n');
            if (rest.startsWith("\u001c") && fnl >= 0) {
                fp = rest.substring(1, fnl);
                rest = rest.substring(fnl + 1);
            }
            if (SAME.matcher(rest).find()) {
                by.put(project, new Store(null, null, fp, true));
                continue;
            }
            Matcher m = CODE.matcher(rest);
            boolean coded = m.find();
            String body = coded ? rest.substring(0, m.start()) : rest;
            if (!coded) {
                by.put(project, new Store(null, "the listing was cut short", "", false));
                continue;
            }
            BigInteger code = new BigInteger(m.group(1));
            if (code.signum() != 0) {
                by.put(project, new Store(null, "bd list exited " + code + ": " + errorLine(body), "", false));
                continue;
            }
            BeadList r = parseBeads(body);
            by.put(project, new Store(r.beads, r.error, fp, false));
        }
        return new Stores(false, by);
    }

    /** The args that tell STORES_SH which stores it can skip: store=fingerprint. */
    static List<String> knownArgs(Map<String, Store> kept) {
        List<String> out = new ArrayList<>();
        for (Map.Entry<String, Store> e : kept.entrySet()) {
            Store r = e.getValue();
            if (r.fp != null && !r.fp.isEmpty() && r.beads != null) out.add(e.getKey() + "=" + r.fp);
        }
        return out;
    }

    /**
     * One machine's stores after a read (factory-sidebar.ts readBeads): what
     * the read listed, gone stores dropped. A same store keeps the previous
     * read; a failed one keeps the beads it showed last, with the error.
     */
    static Map<String, Store> merge(Map<String, Store> previous, Stores next) {
        Map<String, Store> out = new LinkedHashMap<>();
        for (Map.Entry<String, Store> e : next.byProject.entrySet()) {
            Store prev = previous == null ? null : previous.get(e.getKey());
            Store res = e.getValue();
            if (res.same) out.put(e.getKey(), prev != null ? prev : new Store(null, null, "", false));
            else if (res.error != null) out.put(e.getKey(), new Store(prev != null ? prev.beads : null, res.error, "", false));
            else out.put(e.getKey(), new Store(res.beads, null, res.fp, false));
        }
        return out;
    }

    /** A project's beads from its own store and its epic's share of repo stores, with their read error. */
    static final class ProjectBeads {
        final List<Bead> beads;
        final String error;
        ProjectBeads(List<Bead> beads, String error) {
            this.beads = beads;
            this.error = error;
        }
    }

    /** Every project's beads, by project name (feed.ts splitBeads). */
    static Map<String, ProjectBeads> projectBeads(Map<String, Store> stores) {
        Map<String, ProjectBeads> out = new LinkedHashMap<>();
        if (stores == null) return out;
        for (Map.Entry<String, Store> e : stores.entrySet()) {
            String name = e.getKey();
            Store v = e.getValue();
            List<Bead> beads = v.beads != null ? v.beads : Collections.<Bead>emptyList();
            if (name.startsWith(REPO_STORE)) {
                String error = v.error != null ? name.substring(REPO_STORE.length()) + " store: " + v.error : null;
                for (Map.Entry<String, List<Bead>> p : repoProjects(beads).entrySet()) add(out, p.getKey(), p.getValue(), error);
            } else add(out, name, beads, v.error);
        }
        return out;
    }

    private static void add(Map<String, ProjectBeads> out, String key, List<Bead> beads, String error) {
        ProjectBeads cur = out.get(key);
        List<Bead> all = new ArrayList<>(cur != null ? cur.beads : Collections.<Bead>emptyList());
        all.addAll(beads);
        out.put(key, new ProjectBeads(all, cur != null && cur.error != null ? cur.error : error));
    }

    /**
     * The store, as STORES_SH names it, that holds {@code id} for {@code project}:
     * the project's own, else a repo store whose share is the project's; null when none.
     */
    static String storeOf(String project, String id, Map<String, Store> stores) {
        if (stores == null || project == null || id == null) return null;
        Store own = stores.get(project);
        if (own != null && own.beads != null) for (Bead b : own.beads) if (id.equals(b.id)) return project;
        for (Map.Entry<String, Store> e : stores.entrySet()) {
            if (!e.getKey().startsWith(REPO_STORE) || e.getValue().beads == null) continue;
            List<Bead> mine = repoProjects(e.getValue().beads).get(project);
            if (mine != null) for (Bead b : mine) if (id.equals(b.id)) return e.getKey();
        }
        return null;
    }

    /**
     * The Herdr project an epic of a repo store stands for: its project:&lt;name&gt;
     * label, else its id without the store's prefix (mac-crate-reorg is crate-reorg).
     */
    static String epicProject(Bead e) {
        for (String l : e.labels) {
            if (!l.startsWith("project:")) continue;
            if (l.length() > 8) return l.substring(8);
            break;
        }
        Matcher m = PREFIXED.matcher(e.id);
        return m.find() && !m.group(1).contains(".") ? m.group(1) : null;
    }

    private static String projectLabel(Bead b) {
        for (String l : b.labels) if (l.startsWith("project:")) return l.length() > 8 ? l.substring(8) : null;
        return null;
    }

    /**
     * A repo store's beads, per project: each epic with everything under it (by
     * parent, or by id: mac-prs.4, mac-crate-reorg-stale-docs), and any bead
     * labelled project:&lt;name&gt;. Beads of no project are left out.
     */
    static Map<String, List<Bead>> repoProjects(List<Bead> beads) {
        Map<String, Bead> byId = byId(beads);
        Set<String> ids = byId.keySet();
        Map<String, String> epicOf = new LinkedHashMap<>();
        for (Bead b : beads) if (isEpic(b)) {
            String p = epicProject(b);
            if (p != null) epicOf.put(b.id, p);
        }
        List<String> epicIds = new ArrayList<>(epicOf.keySet());
        Collections.sort(epicIds, (a, z) -> z.length() - a.length()); // the longest id prefix wins
        Map<String, List<Bead>> out = new LinkedHashMap<>();
        for (Bead b : beads) {
            String p = null;
            Set<String> seen = new HashSet<>();
            for (Bead x = b; x != null && seen.add(x.id); ) {
                if (epicOf.containsKey(x.id)) { p = epicOf.get(x.id); break; }
                String up = parentOf(x, ids);
                x = up == null ? null : byId.get(up);
            }
            if (p == null) for (String id : epicIds) {
                if (b.id.startsWith(id + ".") || b.id.startsWith(id + "-")) { p = epicOf.get(id); break; }
            }
            if (p == null) p = projectLabel(b);
            if (p == null) continue;
            List<Bead> list = out.get(p);
            if (list == null) out.put(p, list = new ArrayList<>());
            list.add(b);
        }
        return out;
    }

    private static Map<String, Bead> byId(List<Bead> beads) {
        Map<String, Bead> m = new LinkedHashMap<>();
        for (Bead b : beads) m.put(b.id, b);
        return m;
    }

    // ---- the tree --------------------------------------------------------------

    static boolean isClosed(Bead b) {
        return "closed".equals(b.status) || "tombstone".equals(b.status);
    }

    static boolean isEpic(Bead b) {
        return "epic".equals(b.type);
    }

    static boolean needsWolf(Bead b) {
        return !isClosed(b) && b.labels.contains(NEEDS_LABEL);
    }

    /**
     * A bead's parent: bd's parent, else its parent-child dependency, else the
     * hierarchical id's prefix (fp-dcx.2 under fp-dcx); only one that is listed.
     */
    static String parentOf(Bead b, Set<String> ids) {
        if (b.parent != null && !b.parent.isEmpty() && ids.contains(b.parent)) return b.parent;
        for (Dep d : b.dependencies) {
            if ("parent-child".equals(d.type) && b.id.equals(d.issueId) && d.dependsOnId != null && ids.contains(d.dependsOnId))
                return d.dependsOnId;
        }
        int dot = b.id.lastIndexOf('.');
        if (dot > 0 && ids.contains(b.id.substring(0, dot))) return b.id.substring(0, dot);
        return null;
    }

    /** ICU's root order of punctuation and symbols, which sort before digits and letters. */
    private static final String PUNCT = "_-,;:!?.'\"()[]{}@*/\\&#%`^+<=>|~$";

    private static int rank(char c) {
        if (blank(c)) return 0;
        if (Character.isDigit(c)) return 3;
        if (Character.isLetter(c)) return 4;
        return 1;
    }

    /**
     * Natural order of ids, as the desktop's localeCompare(…, "en", {numeric:
     * true}): digit runs by value (fp-dcx.2 before fp-dcx.10), punctuation
     * before digits before letters, case only as a last tie (lower first).
     */
    static int compareIds(String a, String b) {
        int i = 0, j = 0, tie = 0;
        while (i < a.length() && j < b.length()) {
            char x = a.charAt(i), y = b.charAt(j);
            if (isAsciiDigit(x) && isAsciiDigit(y)) {
                int si = i, sj = j;
                while (i < a.length() && isAsciiDigit(a.charAt(i))) i++;
                while (j < b.length() && isAsciiDigit(b.charAt(j))) j++;
                int c = new BigInteger(a.substring(si, i)).compareTo(new BigInteger(b.substring(sj, j)));
                if (c != 0) return c;
                continue;
            }
            int rx = rank(x), ry = rank(y);
            if (rx != ry) return rx - ry;
            if (rx == 1) {
                int px = PUNCT.indexOf(x), py = PUNCT.indexOf(y);
                int c = (px < 0 ? PUNCT.length() + x : px) - (py < 0 ? PUNCT.length() + y : py);
                if (c != 0) return c;
            } else {
                int c = Character.compare(Character.toLowerCase(x), Character.toLowerCase(y));
                if (c != 0) return c;
                if (tie == 0 && x != y) tie = Character.isLowerCase(x) ? -1 : 1;
            }
            i++;
            j++;
        }
        int left = (a.length() - i) - (b.length() - j);
        if (left != 0) return left < 0 ? -1 : 1;
        return tie;
    }

    private static boolean isAsciiDigit(char c) {
        return c >= '0' && c <= '9';
    }

    private static int statusOrder(String s) {
        switch (s) {
            case "in_progress": return 0;
            case "blocked": return 1;
            case "open": return 2;
            case "deferred": return 3;
            case "closed": return 4;
            default: return 3;
        }
    }

    /** Needs-you first, then in progress, blocked, open, deferred, closed; then priority and id. */
    static int compareBeads(Bead a, Bead b) {
        int c = Boolean.compare(needsWolf(b), needsWolf(a));
        if (c == 0) c = statusOrder(a.status) - statusOrder(b.status);
        if (c == 0) c = (a.priority != null ? a.priority : 2) - (b.priority != null ? b.priority : 2);
        return c != 0 ? c : compareIds(a.id, b.id);
    }

    /**
     * A bead in a tree. In {@link #beadTree} every child is there; in
     * {@link Work#tree} closed leaves nobody works on are folded into
     * {@link #closed}, as the desktop's "N closed".
     */
    static final class Node {
        final Bead bead;
        final List<Node> children = new ArrayList<>();
        /** Who works on it: its workers, then their helpers and sub-agents. */
        List<FleetTree.Member> agents = Collections.emptyList();
        /** Its closed children folded away ("N closed"). */
        int closed;
        /** Its mark in Work: "needs-you", "done", "working", or null. */
        String mark;
        Node(Bead bead) { this.bead = bead; }
    }

    /**
     * The bead forest: epics first, then compareBeads. A parent cycle (bad data)
     * is broken at the bead that closes it.
     */
    static List<Node> beadTree(List<Bead> beads) {
        Set<String> ids = new HashSet<>();
        for (Bead b : beads) ids.add(b.id);
        Map<String, Node> nodes = new HashMap<>();
        for (Bead b : beads) nodes.put(b.id, new Node(b));
        List<Node> roots = new ArrayList<>();
        for (Bead b : beads) {
            String p = parentOf(b, ids);
            Set<String> seen = new HashSet<>();
            seen.add(b.id);
            for (String q = p; q != null; q = parentOf(nodes.get(q).bead, ids)) {
                if (!seen.add(q)) { p = null; break; }
            }
            if (p != null) nodes.get(p).children.add(nodes.get(b.id));
            else roots.add(nodes.get(b.id));
        }
        sort(roots);
        return roots;
    }

    private static void sort(List<Node> list) {
        Collections.sort(list, (x, y) -> {
            int c = Boolean.compare(isEpic(y.bead), isEpic(x.bead));
            return c != 0 ? c : compareBeads(x.bead, y.bead);
        });
        for (Node n : list) sort(n.children);
    }

    /** An epic: its bead and its descendant leaf work, closed of all. */
    static final class Epic {
        final Bead bead;
        final int closed, total;
        Epic(Bead bead, int closed, int total) {
            this.bead = bead;
            this.closed = closed;
            this.total = total;
        }
    }

    /** An epic's progress over its descendant leaves. */
    static Epic progress(Node n) {
        int[] ct = new int[2];
        leaves(n, ct);
        return new Epic(n.bead, ct[0], ct[1]);
    }

    private static void leaves(Node m, int[] ct) {
        for (Node c : m.children) {
            if (!c.children.isEmpty()) leaves(c, ct);
            else {
                ct[1]++;
                if (isClosed(c.bead)) ct[0]++;
            }
        }
    }

    /** A bead's place: its parents up to the epic (outermost first) and its direct children. */
    static final class Place {
        final Bead bead;
        final List<Bead> trail = new ArrayList<>(), children = new ArrayList<>();
        Place(Bead bead) { this.bead = bead; }
    }

    static Place beadPlace(List<Bead> beads, String id) {
        Map<String, Bead> byId = byId(beads);
        Set<String> ids = byId.keySet();
        Place out = new Place(byId.get(id));
        Set<String> seen = new HashSet<>();
        seen.add(id);
        for (String p = out.bead != null ? parentOf(out.bead, ids) : null; p != null && seen.add(p); p = parentOf(byId.get(p), ids))
            out.trail.add(0, byId.get(p));
        for (Bead b : beads) if (!b.id.equals(id) && id.equals(parentOf(b, ids))) out.children.add(b);
        Collections.sort(out.children, BeadsModel::compareBeads);
        return out;
    }

    // ---- terminals -------------------------------------------------------------

    /**
     * A member with the factory's join labels that FleetTree.Member lacks:
     * a worker's issue= and task=, a helper's helper=. Fill with
     * {@link #labels}; a plain Member counts as having none.
     */
    static class Term extends FleetTree.Member {
        /** The bead a worker is on (issue=); task and helper are every Member's. */
        String issue = "";
    }

    /** The rail's members as Terms, each with its pane's join labels (the core view's panes). */
    static List<Term> terms(JSONArray workspaces, JSONArray tabs, JSONArray panes) {
        List<Term> out = FleetPanes.members(workspaces, tabs, panes, Term::new);
        Map<String, Term> byId = new HashMap<>();
        for (Term t : out) byId.put(t.id, t);
        for (int i = 0; panes != null && i < panes.length(); i++) {
            JSONObject pane = panes.optJSONObject(i);
            Term t = pane == null ? null : byId.get(pane.optString("id"));
            if (t != null) labels(t, pane);
        }
        return out;
    }

    /** A pane's issue/task/helper labels, flat beside project and role, or in a labels object. */
    static void labels(Term t, JSONObject pane) {
        JSONObject nested = pane.optJSONObject("labels");
        t.issue = label(pane, nested, "issue");
        t.task = label(pane, nested, "task");
        t.helper = label(pane, nested, "helper");
    }

    private static String label(JSONObject pane, JSONObject nested, String k) {
        // android's optString turns JSON null into "null": read strings only.
        Object v = pane.opt(k);
        if (!(v instanceof String) || ((String) v).isEmpty()) v = nested == null ? null : nested.opt(k);
        return v instanceof String ? ((String) v).trim() : "";
    }

    private static String issue(FleetTree.Member t) {
        return t instanceof Term ? ((Term) t).issue : "";
    }

    private static String task(FleetTree.Member t) {
        return t.task;
    }

    private static String helper(FleetTree.Member t) {
        return t.helper;
    }

    /** An agent status (working, blocked, done, idle), or null for a plain shell. */
    static String statusOf(FleetTree.Member t) {
        String s = t == null ? "" : t.status;
        return "working".equals(s) || "blocked".equals(s) || "done".equals(s) || "idle".equals(s) ? s : null;
    }

    /** deacon, pm, worker, helper (by its helper= label) or other. */
    static String roleOf(FleetTree.Member t) {
        if (!helper(t).isEmpty()) return "helper";
        String r = t.role;
        return "deacon".equals(r) || "pm".equals(r) || "worker".equals(r) ? r : "other";
    }

    /** A helper's short name: helper=audit, else its name. */
    static String helperName(FleetTree.Member t) {
        if (!helper(t).isEmpty()) return helper(t);
        return t.title.isEmpty() ? t.id : t.title;
    }

    private static String parentAddr(FleetTree.Member t) {
        return t.parent.isEmpty() ? null : t.parent;
    }

    private static Map<String, List<FleetTree.Member>> childrenOf(List<? extends FleetTree.Member> terms) {
        Map<String, List<FleetTree.Member>> out = new HashMap<>();
        for (FleetTree.Member t : terms) {
            String p = parentAddr(t);
            if (p == null) continue;
            List<FleetTree.Member> list = out.get(p);
            if (list == null) out.put(p, list = new ArrayList<>());
            list.add(t);
        }
        return out;
    }

    /** A project's lead, workers, and per worker its helpers and other sub-agents. */
    static final class Crew {
        FleetTree.Member lead;
        final List<FleetTree.Member> workers = new ArrayList<>();
        final Map<String, List<FleetTree.Member>> helpers = new HashMap<>(), subagents = new HashMap<>();
    }

    static Crew crewOf(String project, List<? extends FleetTree.Member> terms, Map<String, List<FleetTree.Member>> children) {
        Crew c = new Crew();
        for (FleetTree.Member t : terms) {
            if (!project.equals(t.project)) continue;
            if (c.lead == null && "pm".equals(roleOf(t))) c.lead = t;
            if ("worker".equals(roleOf(t))) c.workers.add(t);
        }
        Map<String, List<FleetTree.Member>> byParent = children != null ? children : childrenOf(terms);
        for (FleetTree.Member w : c.workers) {
            List<FleetTree.Member> h = new ArrayList<>(), s = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            seen.add(w.id);
            walkCrew(w.id, false, byParent, seen, h, s);
            c.helpers.put(w.id, h);
            c.subagents.put(w.id, s);
        }
        return c;
    }

    private static void walkCrew(String addr, boolean underHelper, Map<String, List<FleetTree.Member>> byParent,
            Set<String> seen, List<FleetTree.Member> h, List<FleetTree.Member> s) {
        List<FleetTree.Member> kids = byParent.get(addr);
        if (kids == null) return;
        for (FleetTree.Member c : kids) {
            String r = roleOf(c);
            if (seen.contains(c.id) || "worker".equals(r) || "pm".equals(r)) continue;
            seen.add(c.id);
            boolean isHelper = underHelper || "helper".equals(r);
            (isHelper ? h : s).add(c);
            walkCrew(c.id, isHelper, byParent, seen, h, s);
        }
    }

    /** A worker's bead: its issue= label, else a task= label that is a bead id. */
    static Bead beadOfWorker(FleetTree.Member w, Map<String, Bead> byId) {
        if (!issue(w).isEmpty() && byId.containsKey(issue(w))) return byId.get(issue(w));
        if (!task(w).isEmpty() && byId.containsKey(task(w))) return byId.get(task(w));
        return null;
    }

    /** Agents under each bead: the bead's worker, then its helpers and sub-agents. */
    static Map<String, List<FleetTree.Member>> agentsByBead(Crew crew, List<Bead> beads) {
        Map<String, Bead> byId = byId(beads);
        Map<String, List<FleetTree.Member>> out = new LinkedHashMap<>();
        for (FleetTree.Member w : crew.workers) {
            Bead b = beadOfWorker(w, byId);
            if (b == null) continue;
            List<FleetTree.Member> list = out.get(b.id);
            if (list == null) out.put(b.id, list = new ArrayList<>());
            list.add(w);
            list.addAll(crew.helpers.get(w.id));
            list.addAll(crew.subagents.get(w.id));
        }
        return out;
    }

    /**
     * What a PM's Work follows (model.ts workScope): the roots and their
     * descendants by parent, stopping at other leads (a PM or deacon below has
     * its own tab), and that project's crew among them.
     */
    static final class Scope {
        final String project;
        final List<FleetTree.Member> agents = new ArrayList<>();
        Crew crew;
        private final Map<String, FleetTree.Member> byAddr = new HashMap<>();
        Scope(String project) { this.project = project; }

        /** Whether one of this scope's agents works on the issue in project {@code name} (any when null). */
        boolean hasIssue(String name, String issue) {
            // A HashSet holds null: an owner whose chain names no project.
            Set<String> owners = new HashSet<>();
            if (issue != null) for (FleetTree.Member t : agents) {
                if (issue.equals(issue(t)) || issue.equals(task(t))) owners.add(issueProject(t));
            }
            return name != null ? owners.contains(name) : !owners.isEmpty();
        }

        private String issueProject(FleetTree.Member t) {
            Set<String> seen = new HashSet<>();
            for (FleetTree.Member cur = t; cur != null && seen.add(cur.id); ) {
                if (!cur.project.isEmpty()) return cur.project;
                String p = parentAddr(cur);
                cur = p == null ? null : byAddr.get(p);
            }
            return null;
        }
    }

    static Scope workScope(List<? extends FleetTree.Member> roots, List<? extends FleetTree.Member> terms) {
        FleetTree.Member main = null;
        for (FleetTree.Member t : roots) if (main == null && "pm".equals(roleOf(t))) main = t;
        for (FleetTree.Member t : roots) if (main == null && "worker".equals(roleOf(t))) main = t;
        if (main == null && !roots.isEmpty()) main = roots.get(0);
        Scope s = new Scope(main == null || main.project.isEmpty() ? null : main.project);
        for (FleetTree.Member t : terms) s.byAddr.put(t.id, t);
        Map<String, List<FleetTree.Member>> children = childrenOf(terms);
        Set<String> visited = new HashSet<>();
        for (FleetTree.Member r : roots) visit(r, children, visited, s.agents);
        if (s.project != null) s.crew = crewOf(s.project, s.agents, childrenOf(s.agents));
        return s;
    }

    private static void visit(FleetTree.Member t, Map<String, List<FleetTree.Member>> children, Set<String> visited,
            List<FleetTree.Member> out) {
        if (!visited.add(t.id)) return;
        out.add(t);
        List<FleetTree.Member> kids = children.get(t.id);
        if (kids == null) return;
        for (FleetTree.Member c : kids) {
            String r = roleOf(c);
            if (!"pm".equals(r) && !"deacon".equals(r)) visit(c, children, visited, out);
        }
    }

    // ---- the join --------------------------------------------------------------

    /** A sidebar task row: a worker with its bead, or a bead needing Wolf or in progress. */
    static final class TaskRow {
        final String key, title, state;
        final Bead bead;
        final FleetTree.Member worker;
        final List<FleetTree.Member> helpers;
        TaskRow(String key, Bead bead, FleetTree.Member worker, List<FleetTree.Member> helpers, String title, String state) {
            this.key = key;
            this.bead = bead;
            this.worker = worker;
            this.helpers = helpers;
            this.title = title;
            this.state = state;
        }
    }

    /** One project joined (model.ts joinProject without ev's asks). */
    static final class View {
        String name;
        FleetTree.Member lead;
        String leadStatus;
        final List<TaskRow> tasks = new ArrayList<>();
        int queued, needsYou, working, done;
        List<Node> tree;
        final List<Epic> epics = new ArrayList<>();
    }

    private static String taskState(Bead bead, FleetTree.Member worker, List<FleetTree.Member> helpers) {
        List<String> statuses = new ArrayList<>();
        statuses.add(statusOf(worker));
        for (FleetTree.Member h : helpers) statuses.add(statusOf(h));
        if ((bead != null && needsWolf(bead)) || statuses.contains("blocked")) return "needs-you";
        if (bead != null && isClosed(bead)) return "closed";
        if (statuses.contains("working")) return "working";
        if ("done".equals(statusOf(worker))) return "done";
        if (worker != null) return "idle";
        return "open";
    }

    private static int stateOrder(String s) {
        switch (s) {
            case "needs-you": return 0;
            case "working": return 1;
            case "done": return 2;
            case "idle": return 3;
            case "open": return 4;
            default: return 5;
        }
    }

    static View joinProject(String name, List<Bead> beads, Crew crew) {
        Map<String, Bead> byId = byId(beads);
        View v = new View();
        v.name = name;
        Set<String> claimed = new HashSet<>();
        for (FleetTree.Member w : crew.workers) {
            Bead bead = beadOfWorker(w, byId);
            if (bead != null) claimed.add(bead.id);
            List<FleetTree.Member> helpers = crew.helpers.get(w.id);
            String title = bead != null ? bead.title : !task(w).isEmpty() ? task(w) : w.title.isEmpty() ? w.id : w.title;
            v.tasks.add(new TaskRow(bead != null ? bead.id : w.id, bead, w, helpers, title, taskState(bead, w, helpers)));
        }
        for (Bead b : beads) {
            if (isEpic(b)) continue;
            if (isClosed(b)) { v.done++; continue; }
            if (claimed.contains(b.id)) continue;
            if (needsWolf(b) || "in_progress".equals(b.status) || "blocked".equals(b.status)) {
                List<FleetTree.Member> none = Collections.emptyList();
                v.tasks.add(new TaskRow(b.id, b, null, none, b.title, taskState(b, null, none)));
            } else v.queued++;
        }
        Collections.sort(v.tasks, (a, b) -> {
            int c = stateOrder(a.state) - stateOrder(b.state);
            return c != 0 ? c : compareIds(a.key, b.key);
        });
        v.lead = crew.lead;
        v.leadStatus = statusOf(crew.lead);
        for (TaskRow r : v.tasks) {
            if ("needs-you".equals(r.state)) v.needsYou++;
            if ("working".equals(r.state)) v.working++;
        }
        if ("blocked".equals(v.leadStatus)) v.needsYou++;
        v.tree = beadTree(beads);
        for (Node n : v.tree) if (isEpic(n.bead)) v.epics.add(progress(n));
        return v;
    }

    /** Something waiting on Wolf: what to open (an agent, if one is on it) and why. */
    static final class Need {
        final FleetTree.Member terminal;
        final String bead, title, why;
        Need(FleetTree.Member terminal, String bead, String title, String why) {
            this.terminal = terminal;
            this.bead = bead;
            this.title = title;
            this.why = why;
        }
    }

    /** Beads marked needs-wolf and blocked agents (a lead, a worker or a helper). */
    static List<Need> needsYou(View v) {
        List<Need> out = new ArrayList<>();
        if ("blocked".equals(v.leadStatus) && v.lead != null) {
            String who = !v.lead.title.isEmpty() ? v.lead.title : !task(v.lead).isEmpty() ? task(v.lead) : v.lead.id;
            out.add(new Need(v.lead, null, who + " (lead)", "blocked"));
        }
        for (TaskRow r : v.tasks) {
            if (!"needs-you".equals(r.state)) continue;
            FleetTree.Member blocked = null;
            if (r.worker != null && "blocked".equals(statusOf(r.worker))) blocked = r.worker;
            for (FleetTree.Member h : r.helpers) if (blocked == null && "blocked".equals(statusOf(h))) blocked = h;
            boolean decision = r.bead != null && needsWolf(r.bead);
            out.add(new Need(blocked != null ? blocked : r.worker, r.bead != null ? r.bead.id : null, r.title,
                    decision ? "decision" : "blocked"));
        }
        return out;
    }

    // ---- the Work panel, for one project ---------------------------------------

    /** One project's Work, as the desktop's panel draws it for its PM's tab. */
    static final class Work {
        String project;
        /** The project's store read error, if any (shown above the tree). */
        String error;
        View view;
        Scope scope;
        /** Epics with open work, and how many finished ones fold into "N done". */
        final List<Epic> epics = new ArrayList<>();
        int doneEpics;
        /** The open part of the bead tree; closed leaves fold into each Node.closed. */
        final List<Node> tree = new ArrayList<>();
        /** Every Node.closed summed (the desktop shows each under its parent). */
        int closedFolded;
        /** The Beads row: open epics of all epics, and the needs-you count. */
        int open, total, needsYou;
        final List<Need> needs = new ArrayList<>();
        /** Blocked agents of the tab that no needs-you row names. */
        final List<FleetTree.Member> blocked = new ArrayList<>();
        /** The tab's agents on no bead (not the lead): listed after the tree. */
        final List<FleetTree.Member> loose = new ArrayList<>();
        /** Every live agent on a bead, on this machine (agentsOnBead), not only the tab's. */
        Map<String, List<FleetTree.Member>> onBead = Collections.emptyMap();
    }

    /**
     * {@code project}'s Work, from the stores as last merged and this machine's
     * members. The tab is the project's lead (its first role=pm member) and
     * everything reporting to it; without a lead, the project's members.
     */
    static Work work(String project, Map<String, Store> stores, List<? extends FleetTree.Member> members) {
        Work w = new Work();
        w.project = project;
        FleetTree.Member lead = null;
        for (FleetTree.Member m : members) if (lead == null && "pm".equals(roleOf(m)) && project.equals(m.project)) lead = m;
        List<FleetTree.Member> roots = new ArrayList<>();
        if (lead != null) roots.add(lead);
        else for (FleetTree.Member m : members) if (project.equals(m.project)) roots.add(m);
        Scope scope = workScope(roots, members);
        if (scope.project == null || !scope.project.equals(project)) {
            scope = new Scope(project);
            scope.agents.addAll(roots);
            for (FleetTree.Member t : members) scope.byAddr.put(t.id, t);
            scope.crew = crewOf(project, scope.agents, childrenOf(scope.agents));
        }
        w.scope = scope;
        ProjectBeads pb = projectBeads(stores).get(project);
        List<Bead> beads = pb != null ? pb.beads : Collections.<Bead>emptyList();
        w.error = pb != null ? pb.error : null;
        View v = joinProject(project, beads, scope.crew);
        w.view = v;
        Map<String, List<FleetTree.Member>> agents = agentsByBead(scope.crew, beads);
        w.onBead = agentsByBead(crewOf(project, members, childrenOf(members)), beads);

        for (Need n : needsYou(v)) if (n.terminal != null || scope.hasIssue(project, n.bead)) w.needs.add(n);
        for (FleetTree.Member t : scope.agents) {
            if (!"blocked".equals(statusOf(t))) continue;
            boolean named = false;
            for (Need n : w.needs) named |= n.terminal == t;
            if (!named) w.blocked.add(t);
        }
        w.needsYou = w.needs.size() + w.blocked.size();

        for (Epic e : v.epics) {
            if (e.closed < e.total || !isClosed(e.bead)) w.epics.add(e);
            else w.doneEpics++;
        }
        w.total = v.epics.size();
        w.open = w.epics.size();

        for (Node n : v.tree) if (shown(n, agents)) w.tree.add(open(n, agents, scope, w));
        Set<String> nested = new HashSet<>();
        for (List<FleetTree.Member> list : agents.values()) for (FleetTree.Member a : list) nested.add(a.id);
        for (FleetTree.Member a : scope.agents) if (a != v.lead && !nested.contains(a.id)) w.loose.add(a);
        return w;
    }

    /** A root shows while it is open, has an agent, or has a child that shows. */
    private static boolean shown(Node n, Map<String, List<FleetTree.Member>> agents) {
        if (!isClosed(n.bead) || agents.containsKey(n.bead.id)) return true;
        for (Node c : n.children) if (shown(c, agents)) return true;
        return false;
    }

    private static Node open(Node n, Map<String, List<FleetTree.Member>> agents, Scope scope, Work w) {
        Node out = new Node(n.bead);
        List<FleetTree.Member> on = agents.get(n.bead.id);
        out.agents = on != null ? on : Collections.<FleetTree.Member>emptyList();
        boolean inTab = scope.hasIssue(w.project, n.bead.id);
        out.mark = inTab && needsWolf(n.bead) ? "needs-you" : isClosed(n.bead) ? "done"
                : "in_progress".equals(n.bead.status) ? "working" : null;
        for (Node k : n.children) {
            if (isClosed(k.bead) && k.children.isEmpty() && !agents.containsKey(k.bead.id)) out.closed++;
            else out.children.add(open(k, agents, scope, w));
        }
        w.closedFolded += out.closed;
        return out;
    }

    /** The live agents on a bead anywhere on this machine: its workers, then their helpers and sub-agents. */
    static List<FleetTree.Member> agentsOf(Work work, String beadId) {
        List<FleetTree.Member> on = work.onBead.get(beadId);
        return on != null ? on : Collections.<FleetTree.Member>emptyList();
    }

    // ---- one bead's detail -----------------------------------------------------

    static final class Comment {
        final String author, text, at;
        Comment(String author, String text, String at) {
            this.author = author;
            this.text = text;
            this.at = at;
        }
    }

    /** DETAIL_SH's answer: the bead, or (error set, the rest null) why there is none. */
    static final class Detail {
        String error;
        String id, title, status, type, assignee, owner, createdAt, updatedAt, closeReason, specId;
        Integer priority;
        /** description, design, acceptance criteria, notes: those with text, as {name, text}. */
        final List<String[]> sections = new ArrayList<>();
        final List<Comment> comments = new ArrayList<>();
        /** Published copies (metadata published.&lt;dest&gt; = an http(s) url), as {dest, url}, by dest. */
        final List<String[]> published = new ArrayList<>();
    }

    private static Detail failed(String why) {
        Detail d = new Detail();
        d.error = why;
        return d;
    }

    /** An http(s) url, else null. */
    static String webUrl(Object v) {
        String u = v instanceof String ? trim((String) v) : "";
        return WEB.matcher(u).find() ? u : null;
    }

    /** A JS String(v) for the JSON values bd prints. */
    private static String text(Object v) {
        if (v instanceof Double || v instanceof Float) {
            double d = ((Number) v).doubleValue();
            if (d == Math.rint(d) && !Double.isInfinite(d) && Math.abs(d) < 1e21) return String.valueOf((long) d);
        }
        return String.valueOf(v);
    }

    /**
     * DETAIL_SH's output (\x1e show-json \x1d comments-json \x1f ok, or
     * \x1f missing, or \x1dnobd): the bead, or why there is none. Metadata
     * other than published copies is not shown: the detail is the bead itself.
     */
    static Detail parseDetail(String out) {
        if (out.startsWith("\u001dnobd")) return failed("bd isn't installed on that machine.");
        if (MISSING.matcher(out).find() || !out.startsWith("\u001e")) return failed("This bead isn't in its project's store any more.");
        String[] parts = out.substring(1).split("\u001d", -1);
        String showPart = parts[0], rest = parts.length > 1 ? parts[1] : "";
        Object v;
        try {
            v = json(showPart);
        } catch (JSONException e) {
            return failed("bd show returned something unreadable.");
        }
        Object first = v instanceof JSONArray ? ((JSONArray) v).opt(0) : v;
        JSONObject b = first instanceof JSONObject ? (JSONObject) first : null;
        if (b == null || str(b, "id") == null) return failed("bd show returned no bead.");
        Detail d = new Detail();
        try {
            String list = trim(STATUS_WORD.matcher(rest).replaceFirst(""));
            Object c = json(list.isEmpty() ? "[]" : list);
            if (c instanceof JSONArray) {
                JSONArray a = (JSONArray) c;
                for (int i = 0; i < a.length(); i++) {
                    JSONObject x = a.optJSONObject(i);
                    Object t = x == null ? null : present(x, "text") ? x.opt("text") : present(x, "body") ? x.opt("body") : "";
                    String said = t == null ? "" : text(t);
                    if (!said.isEmpty()) d.comments.add(new Comment(filled(x, "author"), said, filled(x, "created_at")));
                }
            }
        } catch (JSONException e) {
            // No comments shown; the bead still is.
        }
        String[][] fields = { { "Description", "description" }, { "Design", "design" },
                { "Acceptance", "acceptance_criteria" }, { "Notes", "notes" } };
        for (String[] f : fields) {
            String t = filled(b, f[1]);
            if (t != null) d.sections.add(new String[] { f[0], t });
        }
        d.id = str(b, "id");
        d.title = present(b, "title") ? text(b.opt("title")) : d.id;
        d.status = present(b, "status") ? text(b.opt("status")) : "";
        d.type = filled(b, "issue_type");
        d.priority = b.opt("priority") instanceof Number ? ((Number) b.opt("priority")).intValue() : null;
        d.assignee = filled(b, "assignee");
        d.owner = filled(b, "owner");
        d.createdAt = filled(b, "created_at");
        d.updatedAt = filled(b, "updated_at");
        d.closeReason = filled(b, "close_reason");
        d.specId = filled(b, "spec_id");
        JSONObject meta = b.optJSONObject("metadata");
        Map<String, String> published = new TreeMap<>();
        if (meta != null) for (Iterator<String> it = meta.keys(); it.hasNext(); ) {
            String k = it.next();
            if (!k.startsWith("published.")) continue;
            String dest = k.substring("published.".length()), url = webUrl(meta.opt(k));
            if (!dest.isEmpty() && url != null) published.put(dest, url);
        }
        for (Map.Entry<String, String> e : published.entrySet()) d.published.add(new String[] { e.getKey(), e.getValue() });
        return d;
    }

    /** JS `x ?? y` sees null and undefined alike: a key holding JSON null is absent. */
    private static boolean present(JSONObject o, String k) {
        return o.has(k) && !o.isNull(k);
    }

    // ---- which bead an agent works on (its hidden labels) ----------------------

    /**
     * The bead an agent works on, by its hidden labels: its own issue=, else
     * (a helper, or a sub-agent) the nearest one up its parent chain below the
     * lead; null when none links one. Never guessed from titles.
     */
    static String beadOf(String agentId, List<? extends FleetTree.Member> members) {
        Map<String, FleetTree.Member> byId = new HashMap<>();
        for (FleetTree.Member m : members) byId.put(m.id, m);
        FleetTree.Member at = byId.get(agentId);
        for (int hops = 0; at != null && hops < 8; hops++) {
            // A lead's bead is not its workers': the chain stops at the PM or the Deacon.
            if (hops > 0 && ("pm".equals(at.role) || "deacon".equals(at.role))) return null;
            String issue = at instanceof Term ? ((Term) at).issue.trim() : "";
            if (!issue.isEmpty()) return issue;
            at = at.parent.isEmpty() ? null : byId.get(at.parent);
        }
        return null;
    }

    /** The project whose store holds an agent's bead: its own label, else up its chain. */
    static String projectOf(String agentId, List<? extends FleetTree.Member> members) {
        Map<String, FleetTree.Member> byId = new HashMap<>();
        for (FleetTree.Member m : members) byId.put(m.id, m);
        FleetTree.Member at = byId.get(agentId);
        for (int hops = 0; at != null && hops < 8; hops++) {
            if (!at.project.trim().isEmpty()) return at.project.trim();
            at = byId.get(at.parent);
        }
        return "";
    }

}
