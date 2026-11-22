package dev.herdr.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.List;
import org.junit.Test;

/** The app's bottom-right buttons: the same controls as the old prompt words, as buttons. */
public final class ActionBarTest {
    private static ControlLine.State idle() {
        ControlLine.State in = new ControlLine.State();
        in.talks = java.util.Collections.singletonList(new GhostText.Control("talk", "talk", "dim"));
        in.attach = true;
        in.clearable = true;
        in.queued = 2;
        return in;
    }

    private static String ids(List<ActionBar.Item> items) {
        StringBuilder ids = new StringBuilder();
        for (ActionBar.Item i : items) ids.append(i.id).append(' ');
        return ids.toString().trim();
    }

    @Test public void theQueueThenFiveFixedSlotsWithSendLast() {
        List<ActionBar.Item> items = ActionBar.items(ControlLine.ladder(idle()).get(0));
        assertEquals("queue clear attach type talk send", ids(items));
        assertEquals(R.drawable.ic_act_mic, ActionBar.icon(items.get(4)));
        assertEquals(R.drawable.ic_act_send, ActionBar.icon(items.get(5)));
        assertEquals(R.drawable.ic_act_image, ActionBar.icon(items.get(2)));
    }

    @Test public void typeShowsTheHideKeyboardIconWhileTheKeyboardIsUp() {
        ControlLine.State in = idle();
        in.typing = true;
        List<ActionBar.Item> items = ActionBar.items(ControlLine.ladder(in).get(0));
        for (ActionBar.Item i : items) if ("type".equals(i.id)) assertEquals(R.drawable.ic_act_keyboard_hide, ActionBar.icon(i));
    }

    @Test public void recordingChangesIconsInPlaceAndNothingMoves() {
        ControlLine.State in = idle();
        in.queued = 0;
        in.recording = true;
        in.dictating = true;
        in.talks = java.util.Arrays.asList(new GhostText.Control("talk", "rec 0:05", "danger"),
                new GhostText.Control("talk", "stop", "danger"));
        List<ActionBar.Item> items = ActionBar.items(ControlLine.ladder(in).get(0));
        assertEquals("status cancel attach type talk send", ids(items));
        assertEquals("rec 0:05", items.get(0).label);
        assertEquals("one discard button while recording", R.drawable.ic_act_close, ActionBar.icon(items.get(1)));
        assertEquals(R.drawable.ic_act_stop, ActionBar.icon(items.get(4)));
        // The five slots are the same ids, in the same order, idle or recording.
        ControlLine.State idle = idle();
        idle.queued = 0;
        List<ActionBar.Item> calm = ActionBar.items(ControlLine.ladder(idle).get(0));
        assertEquals(calm.size(), 5);
        for (int n = 0; n < 5; n++) {
            assertTrue(ActionBar.isSlot(calm.get(n).id) && ActionBar.isSlot(items.get(n + 1).id));
        }
    }

    @Test public void theRowTakesTheWidestLineThatFits() {
        List<List<GhostText.Control>> ladder = ControlLine.ladder(idle());
        java.util.function.ToIntFunction<List<ActionBar.Item>> chars = items -> {
            int n = 0;
            for (ActionBar.Item i : items) n += i.label.length() + 1;
            return n;
        };
        assertEquals(0, ActionBar.pick(ladder, Integer.MAX_VALUE, chars));
        int narrow = ActionBar.pick(ladder, 1, chars);
        assertEquals("nothing fits: the barest line", ladder.size() - 1, narrow);
    }

    @Test public void targetsAre48dpAndTheClusterTakesNoStrip() throws Exception {
        assertTrue(ActionBar.TARGET_DP >= 48 && ActionBar.BUTTON_DP == 48 && ActionBar.GAP_DP == 4);
        String gta = new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(
                "src/main/java/dev/herdr/android/GpuiTerminalActivity.java")), java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(gta.contains("new FrameLayout.LayoutParams(-2, -2, android.view.Gravity.BOTTOM | android.view.Gravity.END);"));
        assertFalse("no bar reserving height", gta.contains("fitAboveActionBar"));
    }

    @Test public void clearKeepsItsSlotDisabledWhenThereIsNothingToClear() {
        ControlLine.State in = idle();
        in.clearable = false;
        in.queued = 0;
        List<ActionBar.Item> items = ActionBar.items(ControlLine.ladder(in).get(0));
        assertEquals("clear attach type talk send", ids(items));
        assertEquals(ActionBar.DISABLED, items.get(0).style);
    }

    @Test public void anIdleSlotKeepsASolidButtonAndAGreyIcon() throws Exception {
        String bar = new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(
                "src/main/java/dev/herdr/android/ActionBar.java")), java.nio.charset.StandardCharsets.UTF_8);
        assertFalse("never see-through", bar.contains("setAlpha(0.38f)"));
        assertTrue(bar.contains("idle ? chrome.onSurfaceVariant"));
    }

    @Test public void overTheTerminalTheGridEndsAboveTheClusterAndOverThePanelNothingChanges() throws Exception {
        String gta = new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(
                "src/main/java/dev/herdr/android/GpuiTerminalActivity.java")), java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(gta.contains("int below = shows && !overPanel ? cluster.getHeight() + dp(ActionBar.MARGIN_DP) : 0;"));
        String panel = new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(
                "src/main/java/dev/herdr/android/WorkPanel.java")), java.nio.charset.StandardCharsets.UTF_8);
        assertTrue("the header's targets reach below the 30 dp bar", panel.contains("stack.addView(tabs, new FrameLayout.LayoutParams(-1, reach, Gravity.TOP));"));
    }

    @Test public void thePromptGetsNoControlWords() throws Exception {
        String composer = new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(
                "src/main/java/dev/herdr/android/ComposerBar.java")), java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(composer.contains("shownRows, queueOpen, selectedRow, cols, Collections.emptyList(), GhostText.Colors.of(chrome));"));
        assertFalse(composer.contains("GhostText.withRungs(json, ladder)"));
    }
}
