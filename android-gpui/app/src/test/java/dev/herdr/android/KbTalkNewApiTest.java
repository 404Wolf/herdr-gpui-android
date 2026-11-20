package dev.herdr.android;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import dev.herdr.android.dictation.DictationEngine;
import java.util.List;
import org.junit.Test;

/** The new swipe detector and the transcribing state's controls. */
public final class KbTalkNewApiTest {
    @Test public void aDownwardDragFarEnoughAndMostlyVerticalHidesTheKeyboard() {
        assertTrue(KeyboardHide.isDownSwipe(0, 0, 10, 60, 16));
        assertFalse("up", KeyboardHide.isDownSwipe(0, 60, 10, 0, 16));
        assertFalse("too short", KeyboardHide.isDownSwipe(0, 0, 0, 10, 16));
        assertFalse("sideways", KeyboardHide.isDownSwipe(0, 0, 80, 40, 16));
    }

    @Test public void sendAndCancelAlsoShowWhileTheFinalTranscriptIsAwaited() {
        ControlLine.State in = new ControlLine.State();
        in.talks = ComposerBar.talks(DictationEngine.State.TRANSCRIBING, "0:05", false, false, false, null);
        in.dictating = true;
        for (int cols = 40; cols <= 120; cols++) {
            List<GhostText.Control> line = ControlLine.controls(in, cols);
            StringBuilder ids = new StringBuilder();
            for (GhostText.Control c : line) ids.append(c.id).append(' ');
            assertTrue(ids.toString(), ids.indexOf("cancel") >= 0 && ids.indexOf("send") >= 0);
        }
    }
}
