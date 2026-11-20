package dev.herdr.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import dev.herdr.android.dictation.DictationEngine;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;

/**
 * Asks 9 and 10: a visible way to hide the keyboard that Back and a downward swipe back up, and
 * Stop-and-Send plus Cancel for the whole time a dictation is on.
 */
public final class KbTalkTest {
    private static String source(String name) throws Exception {
        return new String(Files.readAllBytes(new File("src/main/java/dev/herdr/android/" + name + ".java").toPath()),
                StandardCharsets.UTF_8);
    }

    private static String section(String text, String start, String end) {
        int first = text.indexOf(start);
        assertTrue(start, first >= 0);
        int last = text.indexOf(end, first + start.length());
        assertTrue(end, last > first);
        return text.substring(first, last);
    }

    private static String ids(List<GhostText.Control> line) {
        StringBuilder out = new StringBuilder();
        for (GhostText.Control c : line) out.append(out.length() == 0 ? "" : " ").append(c.id);
        return out.toString();
    }

    private static ControlLine.State state(boolean typing, DictationEngine.State dictation) {
        ControlLine.State in = new ControlLine.State();
        in.typing = typing;
        in.talks = ComposerBar.talks(dictation, "0:05", false, false, false, null);
        in.recording = ComposerBar.recording(dictation);
        in.clearable = true;
        in.queued = 2;
        return in;
    }

    @Test public void whileTheKeyboardIsUpTheTypeSlotIsAVisibleTappableHide() {
        List<GhostText.Control> line = ControlLine.controls(state(true, DictationEngine.State.IDLE), Integer.MAX_VALUE);
        assertTrue(ids(line), ids(line).contains("type"));
        assertFalse(ids(line), ids(line).contains("gap-type"));
        for (GhostText.Control c : line) {
            if (c.id.equals("type")) assertEquals("hide", c.spans[0].trim());
        }
        // The slot keeps its width: nothing beside it moves.
        List<GhostText.Control> idle = ControlLine.controls(state(false, DictationEngine.State.IDLE), Integer.MAX_VALUE);
        assertEquals(ControlLine.width(idle), ControlLine.width(line));
    }

    @Test public void sendAndCancelStayOnTheLineForTheWholeRecording() {
        for (DictationEngine.State s : new DictationEngine.State[] {DictationEngine.State.CONNECTING,
                DictationEngine.State.LISTENING, DictationEngine.State.ON_DEVICE, DictationEngine.State.OFFLINE_RECORDING}) {
            for (int cols = 40; cols <= 120; cols++) {
                String line = ids(ControlLine.controls(state(false, s), cols));
                assertTrue(s + " at " + cols + ": " + line, line.contains("send") && !line.contains("gap-send"));
                assertTrue(s + " at " + cols + ": " + line, line.contains("cancel") && line.contains("talk"));
            }
        }
        assertTrue(ControlLine.tappable("cancel"));
    }

    @Test public void cancelStopsAndDiscardsWhatWasDictatedKeepingTheEarlierText() throws Exception {
        String bar = source("ComposerBar");
        String tapped = section(bar, "private void targetTapped(String id)", "private static String describe(");
        assertTrue(tapped.contains("case \"cancel\""));
        assertTrue(section(tapped, "case \"cancel\"", "case \"send\"").contains("cancelDictationForClear()")
                || tapped.indexOf("cancelDictationForClear()") > tapped.indexOf("case \"cancel\""));
        assertTrue(section(bar, "private static String describe(", "/** Recordings the desktop gave up on").contains("case \"cancel\""));
        // The draft as it was before the dictation began.
        ComposerDrafts drafts = new ComposerDrafts();
        drafts.switchTo("t1");
        drafts.edit("earlier text");
        drafts.dictationStarted();
        drafts.dictationPartial("dictated words");
        assertEquals("earlier text", drafts.dictationCancelled());
    }

    @Test public void backPutsTheKeyboardAwayFirstOnEveryScreen() throws Exception {
        for (String screen : new String[] {"GpuiTerminalActivity", "HomeActivity", "RemoteHostsActivity"}) {
            String code = source(screen);
            String back = section(code, "public void onBackPressed()", "}\n");
            assertTrue(screen, back.contains("KeyboardHide.hideIfShown(this)"));
            assertTrue(screen + ": before anything else", back.indexOf("KeyboardHide.hideIfShown(this)") < back.indexOf("super.onBackPressed()") || !back.contains("super.onBackPressed()"));
        }
    }

    @Test public void aDownwardSwipeAlsoPutsTheKeyboardAwayOnHome() throws Exception {
        String home = source("HomeActivity");
        assertTrue(section(home, "public boolean dispatchTouchEvent(", "\n    }\n").contains("KeyboardHide."));
    }
}
