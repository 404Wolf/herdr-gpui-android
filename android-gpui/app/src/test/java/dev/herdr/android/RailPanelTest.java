package dev.herdr.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import dev.herdr.android.RailPanel.Mode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import org.junit.Test;

/**
 * Wolf's item 83 on the phone: "the ability to close or minimize the work
 * panel if I want to" (the rail docked beside the terminal when unfolded).
 * Requirement tests: close, minimize, restore; a key and a button for each;
 * the choice kept per device; nothing lost; the terminal takes the freed
 * width; the needs-you badge stays in sight; unfolded, it is docked by default.
 */
public final class RailPanelTest {
    private static RailPanel stored(RailPanel p) {
        // As the activity keeps it: names in "terminal-display", read back on the next start.
        return RailPanel.read(RailPanel.name(p.mode), RailPanel.name(p.last), p.mode == Mode.OPEN);
    }

    @Test public void unfoldedTheRailIsDockedOpenByDefaultAndAnOlderClosedChoiceStaysClosed() {
        assertEquals(Mode.OPEN, RailPanel.read(null, null, null).mode);
        assertEquals(Mode.OPEN, RailPanel.read(null, null, true).mode);
        // Closed with the toolbar's toggle before item 83: still closed after the update.
        RailPanel before = RailPanel.read(null, null, false);
        assertEquals(Mode.CLOSED, before.mode);
        assertEquals(Mode.OPEN, before.toggle().mode);
        // Something unreadable is the default, never a crash or a lost rail.
        assertEquals(Mode.OPEN, RailPanel.read("sideways", "?", null).mode);
    }

    @Test public void closeMinimizeAndRestore() {
        RailPanel open = RailPanel.DEFAULT;
        RailPanel strip = open.minimize();
        assertEquals(Mode.MINIMIZED, strip.mode);
        assertEquals(Mode.OPEN, strip.minimize().mode);
        RailPanel closed = open.with(Mode.CLOSED);
        assertEquals(Mode.CLOSED, closed.mode);
        assertEquals(Mode.OPEN, closed.toggle().mode);
        // Minimizing a closed rail brings it back as the strip.
        assertEquals(Mode.MINIMIZED, closed.minimize().mode);
        assertEquals(Mode.OPEN, strip.with(Mode.OPEN).mode);
    }

    @Test public void closingAndReopeningBringsBackWhatWasThereAStripStaysAStrip() {
        RailPanel strip = RailPanel.DEFAULT.minimize();
        RailPanel closed = strip.toggle();
        assertEquals(Mode.CLOSED, closed.mode);
        assertEquals(Mode.MINIMIZED, closed.last);
        assertEquals(Mode.MINIMIZED, closed.toggle().mode);
        // Closed with the header's ×, then back with the key: the open rail.
        assertEquals(Mode.OPEN, RailPanel.DEFAULT.with(Mode.CLOSED).toggle().mode);
        // Closing twice remembers the first: never "back to closed".
        assertEquals(Mode.MINIMIZED, closed.with(Mode.CLOSED).last);
    }

    @Test public void theChoiceIsKeptAcrossARestart() {
        for (RailPanel p : new RailPanel[] {RailPanel.DEFAULT, RailPanel.DEFAULT.minimize(),
                RailPanel.DEFAULT.with(Mode.CLOSED), RailPanel.DEFAULT.minimize().toggle()}) {
            RailPanel again = stored(p);
            assertEquals(p.mode, again.mode);
            assertEquals(p.last, again.last);
        }
    }

    @Test public void theCoverHasNoDockedRailAndUnfoldingBringsTheChoiceBack() {
        RailPanel strip = RailPanel.DEFAULT.minimize();
        assertEquals(Mode.CLOSED, strip.shown(false, false));
        assertEquals(Mode.MINIMIZED, strip.shown(true, false));
        assertEquals(Mode.OPEN, RailPanel.DEFAULT.shown(true, false));
        // A raw shell has no rail at all.
        assertEquals(Mode.CLOSED, RailPanel.DEFAULT.shown(true, true));
    }

    @Test public void aDrawerOpenAcrossAFoldNeverHidesTheStoredStrip() {
        // t20y's probe on 298fd5c: MINIMIZED/44 -> gesture drawer -> fold -> unfold gave 0, not 44.
        RailPanel stored = RailPanel.DEFAULT.minimize();
        RailPanel.Shown strip = RailPanel.Shown.docked(stored);
        assertEquals(RailPanel.STRIP_DP, strip.widthDp(false, 240));
        RailPanel.Shown drawer = strip.floated(false);
        assertTrue(drawer.open && drawer.drawer(false));
        assertEquals(320, drawer.widthDp(false, 320));
        RailPanel.Shown folded = RailPanel.Shown.configured(stored, false, false);
        assertEquals(0, folded.widthDp(true, 320));
        RailPanel.Shown unfolded = RailPanel.Shown.configured(stored, true, false);
        assertFalse(unfolded.overlay);
        assertEquals(RailPanel.STRIP_DP, unfolded.widthDp(false, 240));
        // Unfolding straight from the drawer, no fold: the same.
        assertEquals(RailPanel.STRIP_DP, RailPanel.Shown.configured(stored, true, false).widthDp(false, 240));
    }

    @Test public void aTransientDrawerShutsToWhatWasDocked() {
        RailPanel.Shown strip = RailPanel.Shown.docked(RailPanel.DEFAULT.minimize());
        // The gesture's drawer over a wide terminal, then Back: the strip again.
        RailPanel.Shown back = strip.floated(false).toggled(false, false);
        assertTrue(back.strip(false));
        assertFalse(back.overlay);
        // The cover's drawer opened and shut: still the strip once unfolded.
        RailPanel.Shown cover = strip.toggled(true, true).toggled(false, true);
        assertFalse(cover.strip(true));
        assertTrue(cover.strip(false));
        // Pinned open or closed with the toolbar's toggle, the strip is gone.
        RailPanel.Shown pinned = strip.toggled(true, false);
        assertTrue(pinned.open && !pinned.minimized);
        assertEquals(0, pinned.toggled(false, false).widthDp(false, 240));
    }

    @Test public void theBadgeSaysHowManyWaitAndNothingWhenNobodyDoes() {
        assertEquals("", RailPanel.badge(0));
        assertEquals("3", RailPanel.badge(3));
        assertEquals("99+", RailPanel.badge(140));
    }

    @Test public void eachActionHasAButtonAndAKey() throws IOException {
        String ui = source("GpuiTerminalActivity");
        String header = section(ui, "private LinearLayout createRailControls(", "private TextView railControl(");
        assertTrue(header.contains("RailPanel.Mode.MINIMIZED"));
        assertTrue(header.contains("RailPanel.Mode.CLOSED"));
        String strip = section(ui, "private LinearLayout createRailStrip(", "private TextView needYouBadge(");
        assertTrue("the strip opens the rail", strip.contains("RailPanel.Mode.OPEN"));
        // ctrl-alt-w and ctrl-alt-b, caught before any key goes to the terminal.
        String keys = section(ui, "static boolean railKeyCode(", "@Override public boolean dispatchKeyEvent(");
        assertTrue(keys.contains("KEYCODE_W") && keys.contains("KEYCODE_B"));
        assertTrue(keys.contains("isCtrlPressed()") && keys.contains("isAltPressed()"));
        String dispatch = section(ui, "@Override public boolean dispatchKeyEvent(", "nativeSendKey(");
        assertTrue("the rail keys come before the terminal's", dispatch.contains("railKey(code == KeyEvent.KEYCODE_W)"));
        String key = section(ui, "private void railKey(", "private LinearLayout createRailControls(");
        assertTrue(key.contains(".toggle()") && key.contains(".minimize()"));
    }

    @Test public void theTerminalTakesTheFreedWidthAndTheBadgeStaysInSight() throws IOException {
        String ui = source("GpuiTerminalActivity");
        String width = section(ui, "private void applySidebarWidth(", "private void updateRailToggle(");
        // Closed: no width; minimized: the strip's; the terminal column (weight 1) takes the rest.
        assertTrue(width.contains("strip ? dp(RailPanel.STRIP_DP) : 0"));
        assertTrue(ui.contains("railColumns.addView(column, new LinearLayout.LayoutParams(0, -1, 1));"));
        String badge = section(ui, "private void updateRailBadge(", "private void applySidebarWidth(");
        assertTrue(badge.contains("railStripBadge") && badge.contains("sidebarBadge"));
        assertTrue("the toolbar keeps the badge while the rail is not open", badge.contains("!sidebarOpen"));
        String bar = section(ui, "private void refreshTabStrip(", "private boolean tabCompact;");
        assertTrue("the badge is the bar's own count", bar.contains("barNeedYou = needYou;"));
        // Closing hides the rail's rows; it never drops them, and opening rebuilds them at once.
        String set = section(ui, "private void setRailPanel(", "private void railKey(");
        assertTrue(set.contains("refreshSidebar(true)"));
    }

    @Test public void everyRailChangeGoesThroughTheTestedTransitions() throws IOException {
        String ui = source("GpuiTerminalActivity");
        assertTrue(section(ui, "private void applySidebarDefault(", "private static SurfaceView findSurface(")
                .contains("RailPanel.Shown.configured("));
        assertTrue(section(ui, "private void showRailOverlay(", "private RailPanel.Shown railShown(")
                .contains(".floated(railCompact())"));
        assertTrue(section(ui, "private void setSidebarOpen(", "private RailPanel storedRailPanel(")
                .contains(".toggled(open, railCompact())"));
        assertTrue(section(ui, "private void setRailPanel(", "private void railKey(")
                .contains("RailPanel.Shown.docked(next)"));
        assertTrue(section(ui, "private void applySidebarWidth(", "private void updateRailToggle(")
                .contains("railShown().strip(railCompact())"));
        // The three fields change in one place only.
        for (String field : new String[] {"railOverlay = ", "railMinimized = ", "sidebarOpen = "}) {
            assertEquals(field, 1, count(ui, field));
        }
    }

    @Test public void aRebuiltToolbarKeepsTheBadge() throws IOException {
        // t20y: a theme or posture rebuild made a new, hidden badge; the count had not changed,
        // so nothing showed it again.
        String bar = section(source("GpuiTerminalActivity"), "private View createShortcutBar(", "return bar;");
        assertTrue(bar.indexOf("sidebarBadge = needYouBadge(") < bar.lastIndexOf("updateRailBadge();"));
    }

    @Test public void theRailsButtonsAreFullTouchTargets() throws IOException {
        String ui = source("GpuiTerminalActivity");
        assertTrue(section(ui, "private TextView railControl(", "private LinearLayout createRailStrip(")
                .contains("new LinearLayout.LayoutParams(dp(48), dp(48))"));
        assertTrue(ui.contains("railBody.addView(railControls, new LinearLayout.LayoutParams(-1, dp(48)));"));
    }

    private static int count(String text, String part) {
        int n = 0;
        for (int at = text.indexOf(part); at >= 0; at = text.indexOf(part, at + 1)) n++;
        return n;
    }

    private static String source(String name) throws IOException {
        return new String(Files.readAllBytes(Paths.get("src/main/java/dev/herdr/android/" + name + ".java")),
                StandardCharsets.UTF_8);
    }

    private static String section(String source, String start, String end) {
        int first = source.indexOf(start);
        assertTrue(start, first >= 0);
        int last = source.indexOf(end, first);
        assertTrue(end, last > first);
        return source.substring(first, last);
    }
}
