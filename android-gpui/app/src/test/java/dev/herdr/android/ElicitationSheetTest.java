package dev.herdr.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import org.junit.Test;

/** The question sheet's layout rules (review P1-3), without a window (synthetic dialogs). */
public final class ElicitationSheetTest {
    private static Elicitation.Dialog confirm(String question, String first) throws Exception {
        return Elicitation.parse("{\"harness\":\"claude\",\"kind\":\"confirm\",\"tabs\":[],\"active_tab\":-1,"
                + "\"question\":\"" + question + "\",\"detail\":[],\"multi\":false,"
                + "\"choices\":[{\"number\":1,\"label\":\"" + first + "\",\"description\":\"\",\"checked\":null,\"role\":\"answer\"}],"
                + "\"cursor\":0,\"note\":null}");
    }

    @Test public void onlyAToolsPermissionPromptAsksForPermission() throws Exception {
        assertTrue(ElicitationSheet.permission(confirm("Do you want to make this edit to a.rs?", "Yes")));
        assertTrue(ElicitationSheet.permission(confirm("Proceed?", "Yes, and don't ask again")));
        // The usage-limit menu only asks.
        assertFalse(ElicitationSheet.permission(confirm("What do you want to do?", "Stop and wait for limit to reset")));
    }

    @Test public void theCardSitsInTheAskingPaneAtMost420dpWide() {
        float density = 2.625f;
        assertEquals(Math.round(420 * density), ElicitationSheet.cardWidth(Math.round(475 * density), density));
        // A narrow pane: 16dp in from each side.
        assertEquals(Math.round(300 * density) - 2 * Math.round(16 * density), ElicitationSheet.cardWidth(Math.round(300 * density), density));
        assertEquals(0, ElicitationSheet.cardWidth(10, density));
    }

    @Test public void rowsRadiosAndButtonsAreTouchSizedAndCancelIsNotRed() throws IOException {
        assertEquals(56, ElicitationSheet.ROW_DP);
        assertEquals(20, ElicitationSheet.RADIO_DP);
        assertEquals(48, ElicitationSheet.BUTTON_DP);
        assertEquals(3, ElicitationSheet.CONTEXT_LINES);
        String sheet = new String(Files.readAllBytes(Paths.get("src/main/java/dev/herdr/android/ElicitationSheet.java")), StandardCharsets.UTF_8);
        assertTrue(sheet.contains("ActionSheet.button(activity, chrome, \"Cancel\", chrome.onSurfaceVariant)"));
        // The scrim is the sheet's own window over the asking pane, not a whole-window dim.
        assertTrue(sheet.contains("w.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);"));
        assertTrue(sheet.contains("over.setBackgroundColor(SCRIM);"));
        String gta = TerminalSource.text();
        assertTrue(gta.contains("terminalColumn.getLocationOnScreen(at);"));
    }
}
