package dev.herdr.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.json.JSONObject;
import org.junit.Test;

/** The rail's Pinned section and the pin button (factory-9zz.2, P3), without a window. */
public final class PinnedSectionTest {
    private static final ZoneId UTC = ZoneId.of("UTC");
    private static final MachineKey DESK = MachineKey.of("core-desk", "SHA256:desk");
    private static final MachineKey LAPTOP = MachineKey.of("core-lap", "SHA256:lap");
    /** 2026-11-22 14:02 UTC. */
    private static final long AT = 1795356120000L;

    private static Pins.Pin pin(MachineKey m, String address, String label, String machine, Pins.Seen seen,
            String status, String incarnation, String seenIncarnation) {
        return new Pins.Pin(Pins.Key.of(m, address, 1000L), incarnation, seenIncarnation, "p-" + machine, "s", label,
                machine, seen, status, AT, AT);
    }

    private static Pins.Context onDesk(boolean live, boolean reconnecting) {
        Pins.Context now = new Pins.Context();
        now.onScreen = DESK;
        now.screenLive = live;
        now.reconnecting = reconnecting;
        return now;
    }

    @Test public void aLivePinIsItsDotAndNameOthersSayTheirStateAndTheMachineIsAlwaysTagged() {
        Pins.Pin here = pin(DESK, "t3qx", "phone-rail", "desktop", Pins.Seen.LISTED, "working", "claude", "claude");
        Pins.Pin there = pin(LAPTOP, "t9", "billing-pm", "wolf-laptop-2026", Pins.Seen.LISTED, "idle", null, null);
        Pins.Pin ended = pin(DESK, "t4", "old-worker", "desktop", Pins.Seen.GONE, "", null, null);
        FleetTree.Member live = new FleetTree.Member();
        live.id = "t3qx";
        live.name = "phone-rail";
        live.status = "working";
        Map<String, FleetTree.Member> onScreen = new HashMap<>();
        onScreen.put("t3qx", live);
        List<PinnedSection.Row> rows = PinnedSection.rows(Arrays.asList(here, there, ended), onDesk(true, false),
                onScreen, here.key, UTC);
        assertEquals("phone-rail", rows.get(0).name);
        assertEquals("", rows.get(0).words);
        assertTrue(rows.get(0).live && rows.get(0).selected);
        assertEquals("desktop", rows.get(0).machine);
        assertEquals("phone-rail on desktop, working", rows.get(0).described());
        // Another machine, not mirrored: offline, with when it was last seen; the tag is compact.
        assertEquals("billing-pm", rows.get(1).name);
        assertEquals("offline · 14:02", rows.get(1).words);
        assertEquals("wolf-laptop…", rows.get(1).machine);
        assertEquals("ended", rows.get(2).words);
    }

    @Test public void reconnectingUnavailableAndRestartedAreSaid() {
        Pins.Pin here = pin(DESK, "t3qx", "phone-rail", "desktop", Pins.Seen.LISTED, "idle", "claude", "codex");
        List<PinnedSection.Row> rows = PinnedSection.rows(Collections.singletonList(here), onDesk(false, true),
                Collections.emptyMap(), null, UTC);
        assertEquals("reconnecting · restarted", rows.get(0).words);
        Pins.Context gone = onDesk(true, false);
        gone.unresolved = Collections.singleton(here.key);
        assertEquals("unavailable · restarted",
                PinnedSection.rows(Collections.singletonList(here), gone, Collections.emptyMap(), null, UTC).get(0).words);
        Pins.Context live = onDesk(true, false);
        assertEquals("restarted",
                PinnedSection.rows(Collections.singletonList(here), live, Collections.emptyMap(), null, UTC).get(0).words);
    }

    @Test public void aPinIsNeverNamedByAnId() {
        Pins.Pin bare = pin(LAPTOP, "t9", "t9", "laptop", Pins.Seen.LISTED, "", null, null);
        PinnedSection.Row row = PinnedSection.rows(Collections.singletonList(bare), onDesk(true, false),
                Collections.emptyMap(), null, UTC).get(0);
        assertEquals(AgentNames.UNNAMED, row.name);
        assertTrue(row.unnamed);
        assertEquals("?", PinnedSection.machineTag(""));
        assertEquals("laptop", PinnedSection.machineTag(" laptop "));
    }

    @Test public void theSectionRedrawsOnlyWhenWhatItShowsChanges() {
        Pins.Pin here = pin(DESK, "t3qx", "phone-rail", "desktop", Pins.Seen.LISTED, "idle", null, null);
        String a = PinnedSection.signature(PinnedSection.rows(Collections.singletonList(here), onDesk(true, false),
                Collections.emptyMap(), null, UTC));
        String b = PinnedSection.signature(PinnedSection.rows(Collections.singletonList(here), onDesk(true, false),
                Collections.emptyMap(), null, UTC));
        assertEquals(a, b);
        String c = PinnedSection.signature(PinnedSection.rows(Collections.singletonList(here), onDesk(false, false),
                Collections.emptyMap(), null, UTC));
        assertFalse(a.equals(c));
    }

    @Test public void theButtonSaysWhatATapDoesOrWhyItCant() throws Exception {
        assertEquals("Pin phone-rail on desktop", PinButton.described(false, "phone-rail", "desktop", null));
        assertEquals("Unpin phone-rail on desktop", PinButton.described(true, "phone-rail", "desktop", null));
        assertEquals("Can't pin yet: this machine isn't confirmed yet",
                PinButton.described(false, "x", "y", "this machine isn't confirmed yet"));
        JSONObject pane = new JSONObject().put("id", "t3qx").put("created_ms", 1000L);
        assertNull(PinsRail.cant(true, DESK, pane));
        assertEquals("pins are loading", PinsRail.cant(false, DESK, pane));
        assertEquals("this machine isn't confirmed yet", PinsRail.cant(true, null, pane));
        assertEquals("this terminal's start time isn't known", PinsRail.cant(true, DESK, new JSONObject().put("id", "t3qx")));
        assertEquals("it runs on another machine",
                PinsRail.cant(true, DESK, new JSONObject().put("id", "laptop/t1").put("created_ms", 5L)));
        assertEquals(Pins.Key.of(DESK, "t3qx", 1000L), PinsRail.keyOf(DESK, pane));
        assertTrue(PinButton.TARGET_DP >= 48);
        assertEquals(FleetTree.LEAD_ROW_DP, PinnedSection.ROW_DP);
    }

    @Test public void thePinIsWhiteAndSitsAtTheWholeScreensRightWhenThePanelIsDocked() throws Exception {
        // Wolf: white, not grey, pinned or not; the outline/filled glyph tells them apart.
        assertEquals(0xFFFFFFFF, PinButton.tint(0xFFFFFFFF));
        String gta = new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(
                "src/main/java/dev/herdr/android/GpuiTerminalActivity.java")), java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(gta.contains("placeTopRight(layout.shown && !layout.overlay);"));
        assertTrue(gta.contains("android.view.ViewGroup to = docked ? workPanel.trailing() : tabRow;"));
        for (String glyph : new String[] {"ic_pin_outline", "ic_pin_filled"}) {
            String xml = new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(
                    "../../android/app/src/main/res/drawable/" + glyph + ".xml")), java.nio.charset.StandardCharsets.UTF_8);
            assertTrue(glyph, xml.contains("android:width=\"18dp\"") && xml.contains("android:height=\"18dp\""));
        }
    }

    @Test public void thePinNamesTheAgentFromTheListBeforeTheRailWasEverDrawn() throws Exception {
        org.json.JSONArray panes = new org.json.JSONArray("[{\"id\":\"t3qx\",\"name\":\"lobby-shell\",\"kind\":\"claude\","
                + "\"created_ms\":1000,\"workspace_id\":\"s1\",\"tab_id\":\"a\"}]");
        java.util.List<FleetTree.Member> none = Collections.emptyList();
        assertEquals("lobby-shell", PinsRail.nameIn(none, panes, "t3qx"));
        assertEquals(AgentNames.UNNAMED, PinsRail.nameIn(none, new org.json.JSONArray(), "t3qx"));
    }

    @Test public void aRefusedOpenSaysWhy() {
        Pins.Pin p = pin(DESK, "t3qx", "phone-rail", "desktop", Pins.Seen.GONE, "", null, null);
        assertTrue(PinsRail.refusal(Pins.Verify.ENDED, p).startsWith("phone-rail has ended"));
        assertTrue(PinsRail.refusal(Pins.Verify.NOT_LISTED_YET, p).contains("isn't listed yet"));
        assertTrue(PinsRail.refusal(Pins.Verify.WRONG_MACHINE, p).contains("not the machine on screen"));
        // A pin whose terminal address now names another terminal (another creation time) is ended, never opened.
        org.json.JSONArray panes = new org.json.JSONArray().put(new org.json.JSONObject(
                java.util.Map.of("id", "t3qx", "created_ms", 2000L)));
        assertEquals(Pins.Verify.ENDED, Pins.verify(p.key, "core-desk", "SHA256:desk", panes));
    }

    @Test public void theActivityWiresTheButtonTheSectionTheObserveAndTheOpen() throws IOException {
        String rail = new String(Files.readAllBytes(Paths.get("src/main/java/dev/herdr/android/GpuiTerminalActivity.java")),
                StandardCharsets.UTF_8);
        assertTrue(rail.contains("tabRow.addView(pinsRail.button().view()"));
        assertTrue(rail.contains("pinsRail.button().extendTouch(crumb);"));
        assertTrue(rail.contains("if (pinsRail != null) order.add(pinsRail.section(savedIds));"));
        // Observed only from the proven list of the connection on screen, where seenLists is kept.
        int seen = rail.indexOf("seenLists.put(current.hostId");
        assertTrue(seen > 0 && rail.indexOf("Pins.shared(this).observe(current.machine, current.panes)", seen) - seen < 300);
        assertTrue(rail.contains("private void openPin(Pins.Pin pin)"));
        // Only a proven list (the poll's selected: ready, pane and boot known) updates pins.
        assertTrue(rail.contains("if (selected && current.ownList && pinsRail != null && current.machine != null) Pins.shared(this).observe("));
        // Opening refuses an ended pin and a reused address: verified against the list now.
        int open = rail.indexOf("private void openPin(Pins.Pin pin)");
        String body = rail.substring(open, rail.indexOf("closeRailDrawer();", open));
        assertTrue(body.indexOf("Pins.verify(pin.key, now.remoteBoot, now.fingerprint, now.panes)") < body.indexOf("focusRemotePane("));
        assertTrue(body.contains("pin.ended() ? Pins.Verify.ENDED"));
    }
}
