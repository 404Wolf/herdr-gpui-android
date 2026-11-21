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
            case STOPS: {
                List<String> programs = new ArrayList<>();
                for (Pane p : panes) if (p.busyShell() && !programs.contains(p.program)) programs.add(p.program);
                return "Close tab and stop " + String.join(", ", programs);
            }
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

    /** "End factory-pm…" for an agent pane. */
    static String endLabel(Pane agent) {
        return "End " + name(agent) + "…";
    }

    /** The confirmation's words: who, doing what, and that it stops. */
    static String endMessage(Pane agent) {
        StringBuilder words = new StringBuilder(harness(agent.kind)).append(" in ").append(name(agent));
        if (!agent.status.isEmpty()) words.append(" is ").append(agent.status);
        words.append('.');
        if (!agent.msg.isEmpty()) words.append("\n\n").append(agent.msg);
        words.append("\n\nEnding it stops the agent and closes its terminal. Its tab and other panes stay.");
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
