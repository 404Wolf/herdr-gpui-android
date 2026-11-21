package dev.herdr.android;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import org.junit.Test;

/** One toolbar at every width: rail, keyboard and >_, with the rest in the top bar's ⋮ (factory-setup.29). */
public final class ToolbarTest {
    @Test public void theBarIsOneLayoutAndItsMenuStartsWithSpacesAndPanes() throws IOException {
        String rail = PendingShellTest.source();
        int bar = rail.indexOf("private View createShortcutBar(");
        String shortcut = rail.substring(bar, rail.indexOf("LinearLayout keys = new LinearLayout(this);", bar));
        assertFalse(shortcut.contains("ic_bar_panes"));
        assertFalse(shortcut.contains("ic_bar_view"));
        assertFalse(shortcut.contains("if (compact)"));
        // One ⋮ on screen: the top bar's; the bar has its own only for a raw shell, which has no top bar.
        assertTrue(shortcut.contains("if (shellMode()) {\n            pinned.addView(iconButton(R.drawable.ic_bar_more"));
        assertTrue(rail.contains("tabMenu.setVisibility(shellMode() ? View.GONE : View.VISIBLE)"));
        int more = rail.indexOf("private void showMoreActions(");
        String menu = rail.substring(more, rail.indexOf("menu.setForceShowIcon(true);", more));
        assertTrue(menu.indexOf("\"Spaces & panes\"") < menu.indexOf("if (navigation && !shellMode())"));
    }
}
