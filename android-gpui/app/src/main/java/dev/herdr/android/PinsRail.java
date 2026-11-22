package dev.herdr.android;

import android.app.Activity;

import org.json.JSONArray;
import org.json.JSONObject;

import java.time.ZoneId;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Pins in the terminal's chrome (factory-9zz.2, P3): the pin button at the
 * top right of an agent's view and the Pinned section at the top of the rail,
 * kept in step with {@link Pins}. The activity tells it what is on screen
 * ({@link #bindScreen}) and draws the section where the rail starts; opening a
 * pin is the activity's ({@code open}, P2's switch path).
 */
final class PinsRail {
    private final Pins pins;
    private final PinButton button;
    private final PinnedSection section;
    private final Runnable changed;
    private final Consumer<String> note;

    // What is on screen, as last bound.
    private MachineKey machine;
    private boolean live, reconnecting;
    private String hostId = "", hostName = "", session = "";
    private JSONObject pane;
    private List<? extends FleetTree.Member> members = java.util.Collections.emptyList();

    /**
     * {@code open} opens a pin's agent; {@code changed} redraws the rail after a
     * pin or unpin; {@code note} tells Wolf something briefly (the activity's note).
     */
    PinsRail(Activity activity, GpuiChrome chrome, Pins pins, Consumer<Pins.Pin> open, Runnable changed,
            Consumer<String> note) {
        this.pins = pins;
        this.changed = changed;
        this.note = note;
        button = new PinButton(activity, chrome, this::toggle);
        section = new PinnedSection(activity, chrome, open, this::unpin);
    }

    PinButton button() {
        return button;
    }

    /** The pane object for {@code paneId} in {@code panes}, or null. */
    static JSONObject paneOf(JSONArray panes, String paneId) {
        for (int i = 0; panes != null && i < panes.length(); i++) {
            JSONObject p = panes.optJSONObject(i);
            if (p != null && p.optString("id").equals(paneId)) return p;
        }
        return null;
    }

    /** The key of the agent on screen, or null when it can't be pinned (see {@link #cant}). */
    static Pins.Key keyOf(MachineKey machine, JSONObject pane) {
        if (pane == null) return null;
        long created = pane.optLong("created_ms", 0);
        return Pins.Key.of(machine, pane.optString("id", ""), created > 0 ? created : null);
    }

    /** Why the agent on screen can't be pinned yet, or null. */
    static String cant(boolean loaded, MachineKey machine, JSONObject pane) {
        if (!loaded) return "pins are loading";
        if (machine == null) return "this machine isn't confirmed yet";
        if (pane == null || pane.optLong("created_ms", 0) <= 0) return "this terminal's start time isn't known";
        if (!Pins.plainAddress(pane.optString("id", ""))) return "it runs on another machine";
        return null;
    }

    /**
     * What is on screen: its machine (null until proven), whether its connection
     * is live or reconnecting, the profile it came through, and the agent's pane
     * ({@code agentPane} false for a shell, where there is no pin button).
     */
    void bindScreen(MachineKey machine, boolean live, boolean reconnecting, String hostId, String hostName,
            String session, JSONArray panes, String paneId, boolean agentPane,
            List<? extends FleetTree.Member> members) {
        this.machine = machine;
        this.live = live;
        this.reconnecting = reconnecting;
        this.hostId = hostId == null ? "" : hostId;
        this.hostName = hostName == null ? "" : hostName;
        this.session = session == null ? "" : session;
        this.pane = agentPane ? paneOf(panes, paneId) : null;
        // The rail's members are read only while the rail is drawn: name the agent from the list itself too.
        this.members = membersFor(members, panes, paneId);
        Pins.Key key = keyOf(machine, pane);
        button.bind(agentPane && pane != null, key != null && pins.pinned(key), nameOnScreen(),
                PinnedSection.machineTag(this.hostName), cant(pins.loaded(), machine, pane));
    }

    private String nameOnScreen() {
        return pane == null ? "" : nameIn(members, null, pane.optString("id"));
    }

    /** The rail's members when they hold {@code paneId}, else the members of the list itself. */
    private static List<? extends FleetTree.Member> membersFor(List<? extends FleetTree.Member> members,
            JSONArray panes, String paneId) {
        for (FleetTree.Member m : members) if (m.id.equals(paneId)) return members;
        return FleetPanes.members(null, null, panes, FleetTree.Member::new);
    }

    /** An agent's name (AgentNames), from {@code members} or, missing there, from {@code panes}; never an id. */
    static String nameIn(List<? extends FleetTree.Member> members, JSONArray panes, String paneId) {
        for (FleetTree.Member m : membersFor(members, panes, paneId)) {
            if (m.id.equals(paneId)) return AgentNames.unnamed(m) ? AgentNames.UNNAMED : FleetPanes.displayTitle(m);
        }
        return AgentNames.UNNAMED;
    }

    private void toggle() {
        Pins.Key key = keyOf(machine, pane);
        if (key == null) return;
        String name = nameOnScreen();
        Pins.Toggle done = pins.toggle(key, pane.isNull("incarnation") ? null : pane.optString("incarnation", null),
                hostId, session, AgentNames.UNNAMED.equals(name) ? "" : name, hostName, pane.optString("status", ""));
        if (done == Pins.Toggle.FULL) {
            note.accept("Pins are full (" + Pins.MAX_PINS + "): unpin one first");
        }
        changed.run();
    }

    private void unpin(Pins.Pin pin) {
        pins.unpin(pin.key);
        changed.run();
    }

    /** The Pinned section, drawn from the pins now; {@code savedHostIds} are the profiles a pin can open through. */
    android.view.View section(Collection<String> savedHostIds) {
        Pins.Context now = new Pins.Context();
        now.onScreen = machine;
        now.screenLive = live;
        now.reconnecting = reconnecting;
        Set<Pins.Key> unresolved = new HashSet<>();
        List<Pins.Pin> all = pins.all();
        for (Pins.Pin pin : all) {
            boolean here = pin.key.machine.equals(machine);
            if (!here && !savedHostIds.contains(pin.profile)) unresolved.add(pin.key);
        }
        now.unresolved = unresolved;
        Map<String, FleetTree.Member> onScreen = new HashMap<>();
        for (FleetTree.Member m : members) onScreen.put(m.id, m);
        section.show(PinnedSection.rows(all, now, onScreen, keyOf(machine, pane), ZoneId.systemDefault()));
        return section.view();
    }

    /** What the section shows, for the rail's own redraw check. */
    String signature(Collection<String> savedHostIds) {
        StringBuilder s = new StringBuilder();
        for (Pins.Pin pin : pins.all()) {
            s.append(pin.key.address).append(pin.key.createdMs).append(pin.seen).append(pin.status)
                    .append(pin.restarted()).append(savedHostIds.contains(pin.profile)).append(';');
        }
        return s.append(live).append(reconnecting).append(machine == null ? "" : machine.hashCode()).toString();
    }

    /** Why a pin was not opened, for the note: its terminal ended, is not listed yet, or is another machine's. */
    static String refusal(Pins.Verify verdict, Pins.Pin pin) {
        String who = pin.label.isEmpty() ? "That pin" : pin.label;
        switch (verdict) {
            case ENDED: return who + " has ended: its terminal is gone or was replaced. Unpin it from its row";
            case NOT_LISTED_YET: return who + " isn't listed yet; try again in a moment";
            default: return who + " is on " + pin.machineName + ", not the machine on screen";
        }
    }
}
