package dev.herdr.android;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * What a tab pill's long-press sheet offers (factory-setup.29), from what runs
 * in the tab. Closing a tab and ending what runs in it are different things,
 * and the words say which: a tab of idle shells just closes; a shell running
 * a program says it stops that program; a tab with an agent is never closed
 * by one ambiguous "Close", only by an explicit "End &lt;agent&gt;…" that is
 * confirmed with the agent's name, state and task. Pure: the activity runs
 * the edits.
 */
final class TabMenu {
    private TabMenu() { }

    /** Programs that are a terminal's own shell, not something it runs. */
    static final Set<String> SHELLS = new HashSet<>(Arrays.asList(
            "", "sh", "bash", "zsh", "fish", "dash", "ksh", "nu", "login", "-zsh", "-bash", "-sh"));

    /** One terminal in the tab, from the core view's panes. */
    static final class Pane {
        final String id, title, kind, program, status, msg;

        Pane(String id, String title, String kind, String program, String status, String msg) {
            this.id = id;
            this.title = title;
            this.kind = kind;
            this.program = program;
            this.status = status;
            this.msg = msg;
        }

        /**
         * An agent the hook named (claude, codex, …) that still runs: once it
         * exits, its kept terminal is back at its shell and is a shell again.
         */
        boolean agent() {
            return !kind.isEmpty() && (program.isEmpty() || !SHELLS.contains(program));
        }

        /** A shell running something in the foreground (vim, a build). */
        boolean busyShell() {
            return !agent() && !SHELLS.contains(program);
        }
    }

    /** How closing the tab is offered. */
    enum Close {
        /** Only idle shells: "Close tab". */
        SHELLS,
        /** A shell runs a program: "Close tab and stop &lt;program&gt;". */
        STOPS,
        /** An agent runs here: no tab close; each agent gets its own "End …". */
        AGENTS
    }

    /** The panes of {@code space}/{@code tab} in the core's view. */
    static List<Pane> panes(JSONArray panes, String space, String tab) {
        List<Pane> out = new ArrayList<>();
        for (int i = 0; panes != null && i < panes.length(); i++) {
            JSONObject p = panes.optJSONObject(i);
            if (p == null || !space.equals(p.optString("workspace_id")) || !tab.equals(p.optString("tab_id"))) continue;
            out.add(new Pane(p.optString("id", ""), p.optString("title", ""), p.optString("kind", ""),
                    p.optString("program", ""), p.optString("status", ""), p.optString("msg", "")));
        }
        return out;
    }

    static Close close(List<Pane> panes) {
        Close close = Close.SHELLS;
        for (Pane p : panes) {
            if (p.agent()) return Close.AGENTS;
            if (p.busyShell()) close = Close.STOPS;
        }
        return close;
    }

    /** The close row's words, or null when the tab has an agent (see {@link Close#AGENTS}). */
    static String closeLabel(List<Pane> panes) {
        switch (close(panes)) {
            case AGENTS: return null;
            case STOPS: return "Close tab and stop " + String.join(", ", busyPrograms(panes)) + "…";
            default: return "Close tab";
        }
    }

    /** Only an idle-shell tab closes without a confirmation. */
    static boolean closeConfirms(List<Pane> panes) {
        return close(panes) != Close.SHELLS;
    }

    /** The sheet's title says what runs there: "factory-pm · Claude · working". */
    static String title(String label, List<Pane> panes) {
        for (Pane p : panes) {
            if (!p.agent()) continue;
            return label + " · " + harness(p.kind) + (p.status.isEmpty() ? "" : " · " + p.status);
        }
        for (Pane p : panes) if (p.busyShell()) return label + " · " + p.program;
        return label + " · shell";
    }

    /** The words of a busy shell's close confirmation: what stops. */
    static String stopMessage(List<Pane> panes) {
        List<String> programs = busyPrograms(panes);
        return "Closing this tab stops " + String.join(" and ", programs) + " in it. Unsaved work there is lost.";
    }

    /**
     * Why an agent tab offers no close that keeps its agent running, or null:
     * a shell in it runs a program that the close would stop.
     */
    static String noCloseReason(List<Pane> panes) {
        if (close(panes) != Close.AGENTS || detach(panes) != null) return null;
        return String.join(" and ", busyPrograms(panes)) + " is running in this tab, so it can't close with the agent kept. "
                + "Stop it first, or end the agent.";
    }

    private static List<String> busyPrograms(List<Pane> panes) {
        List<String> programs = new ArrayList<>();
        for (Pane p : panes) if (p.busyShell() && !programs.contains(p.program)) programs.add(p.program);
        return programs;
    }

    /** The detach row's words: it reads as the desktop's Park. */
    static final String DETACH = "Close tab — agent keeps running in the rail";

    /**
     * What closing an agent tab while its agents keep running does
     * (tab.detach, the desktop's Park): {@code [park, kill]}, its agents parked
     * and its idle shells closed; null when the tab has no agent, or a shell
     * runs a program that a close would stop.
     */
    static List<List<String>> detach(List<Pane> panes) {
        List<String> park = new ArrayList<>(), kill = new ArrayList<>();
        for (Pane p : panes) {
            if (p.agent()) park.add(p.id);
            else if (p.busyShell()) return null;
            else kill.add(p.id);
        }
        return park.isEmpty() ? null : Arrays.asList(park, kill);
    }

    /** "End factory-pm…" for an agent pane. */
    static String endLabel(Pane agent) {
        return "End " + name(agent) + "…";
    }

    /** The confirmation's words: who, doing what, and that it stops. */
    static String endMessage(Pane agent) {
        return endMessage(agent, 0);
    }

    /** As {@link #endMessage(Pane)}, true to the tab: {@code others} panes stay in it, or the tab goes with it. */
    static String endMessage(Pane agent, int others) {
        StringBuilder words = new StringBuilder(harness(agent.kind)).append(" in ").append(name(agent));
        if (!agent.status.isEmpty()) words.append(" is ").append(agent.status);
        words.append('.');
        if (!agent.msg.isEmpty()) words.append("\n\n").append(agent.msg);
        words.append("\n\nEnding it stops the agent and closes its terminal.");
        words.append(others == 0 ? " Its tab closes with it." : others == 1 ? " The other pane in its tab stays."
                : " The other " + others + " panes in its tab stay.");
        return words.toString();
    }

    /** "Claude", "Codex", "Gemini", else the kind as it is. */
    static String harness(String kind) {
        if (kind.isEmpty()) return "Shell";
        return Character.toUpperCase(kind.charAt(0)) + kind.substring(1);
    }

    private static String name(Pane p) {
        return p.title.isEmpty() ? p.id : p.title;
    }
}
