package dev.herdr.android;

import java.util.regex.Pattern;

/**
 * What an agent row is called (factory-jmc). Wolf: "i never need to see t3xz
 * or the code since i do not use that", and "the dot is enough for state". So
 * a row is the agent's short hyphenated name and its state dot: never a
 * terminal id, never a status or role word. Ids stay the rows' keys inside.
 *
 * <p>The first of these that is not an alias: a helper's own purpose (its
 * helper= role and its task, "design-review: right-panel"); the terminal's
 * name ("phone-rail"); its task= label; its title. Else "no task label",
 * drawn dim: a name is never invented. An alias is an address or short id
 * ("t3xz", "server/t1z", "ab12"), as the desktop's agents.ts reads it.
 */
final class AgentNames {
    private AgentNames() { }

    /** Shown, dim, when nothing names the agent. */
    static final String UNNAMED = "no task label";

    private static final Pattern SHORT_ID = Pattern.compile("(?i)t[0-9a-z]{2,5}|[a-z0-9]{1,5}");
    private static final Pattern BEAD_ID = Pattern.compile("(?i)[a-z][a-z0-9]*-[a-z0-9]+(\\.[0-9]+)+");

    /** An address or short id: "t3xz", "server/t1z", "ab12". Never shown as a name. */
    static boolean alias(String text) {
        if (text == null) return true;
        String t = text.trim();
        if (t.isEmpty()) return true;
        int slash = t.lastIndexOf('/');
        if (slash >= 0 && slash < t.length() - 1 && !t.contains(" ")) t = t.substring(slash + 1);
        return SHORT_ID.matcher(t).matches();
    }

    /** "factory-setup.21": a bead id, which names a task's bead, not the agent. */
    static boolean beadId(String text) {
        return text != null && BEAD_ID.matcher(text.trim()).matches();
    }

    /** The row's name for {@code m}, or {@link #UNNAMED}. */
    static String of(FleetTree.Member m) {
        String helper = m.helper.trim(), task = m.task.trim();
        // A helper's role is a word the spawner chose ("audit", "review"): short is fine, an id is not.
        if (!helper.isEmpty() && !RailTree.isTerminalId(helper)) {
            boolean usableTask = !task.isEmpty() && !alias(task) && !beadId(task);
            return usableTask ? helper + ": " + task : helper;
        }
        for (String candidate : new String[] {m.name, task, m.title}) {
            if (candidate == null) continue;
            String c = candidate.trim();
            if (!c.isEmpty() && !alias(c) && !beadId(c)) return c;
        }
        return UNNAMED;
    }

    /** True when {@link #of} had nothing to go on (the row draws it dim). */
    static boolean unnamed(FleetTree.Member m) {
        return UNNAMED.equals(of(m));
    }

    /**
     * Two agents here with the same name are told apart by their folders
     * ("phone-rail · factory-phone-rail"), else by order ("phone-rail · 2"):
     * never by their ids. Sets each one's {@code nameNote}.
     */
    static void disambiguate(java.util.List<? extends FleetTree.Member> members) {
        java.util.Map<String, java.util.List<FleetTree.Member>> byName = new java.util.LinkedHashMap<>();
        for (FleetTree.Member m : members) {
            m.nameNote = "";
            if (!unnamed(m)) byName.computeIfAbsent(of(m), k -> new java.util.ArrayList<>()).add(m);
        }
        for (java.util.List<FleetTree.Member> same : byName.values()) {
            if (same.size() < 2) continue;
            java.util.Set<String> folders = new java.util.HashSet<>();
            for (FleetTree.Member m : same) folders.add(FleetTree.lastSegment(m.cwd));
            boolean byFolder = folders.size() == same.size() && !folders.contains("");
            for (int i = 0; i < same.size(); i++) {
                FleetTree.Member m = same.get(i);
                m.nameNote = byFolder ? FleetTree.lastSegment(m.cwd) : i == 0 ? "" : String.valueOf(i + 1);
            }
        }
    }

    /** What a screen reader hears for the state the dot shows: "working", "needs you". */
    static String stateWords(String status, String waitKind) {
        if ("question".equals(waitKind) || "blocked".equals(status)) return "needs you";
        if (status == null || status.isEmpty() || "unknown".equals(status)) return "state unknown";
        return status;
    }
}
