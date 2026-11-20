package dev.herdr.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import dev.herdr.android.dictation.DictationEngine;
import java.util.List;
import org.junit.Test;

/** Ask 14: the clear is a findable word, not a lone dim cross, wherever there is room for it. */
public final class ClearButtonTest {
    private static ControlLine.State state(boolean clearable, DictationEngine.State dictation) {
        ControlLine.State in = new ControlLine.State();
        in.talks = ComposerBar.talks(dictation, "0:05", false, false, false, null);
        in.recording = ComposerBar.recording(dictation);
        in.clearable = clearable;
        return in;
    }

    private static GhostText.Control clear(List<GhostText.Control> line) {
        for (GhostText.Control c : line) if (c.id.equals("clear")) return c;
        return null;
    }

    @Test public void withTextThereIsAClearWordOnTheLineAtEveryUsualWidth() {
        for (int cols = 46; cols <= 140; cols++) {
            GhostText.Control c = clear(ControlLine.controls(state(true, DictationEngine.State.IDLE), cols));
            assertTrue("at " + cols, c != null);
            assertEquals("at " + cols, "clear", c.spans[0]);
        }
    }

    @Test public void onlyANarrowGridKeepsTheCrossAndNeverLosesTheControl() {
        for (int cols = 5; cols <= 140; cols++) {
            GhostText.Control c = clear(ControlLine.controls(state(true, DictationEngine.State.IDLE), cols));
            assertTrue("at " + cols, c != null);
            assertTrue("at " + cols, c.spans[0].equals("clear") || c.spans[0].equals("✕"));
        }
    }

    @Test public void nothingToClearShowsNoClear() {
        assertEquals(null, clear(ControlLine.controls(state(false, DictationEngine.State.IDLE), 100)));
    }

    @Test public void whileDictatingCancelIsTheClearerWordAndTheCrossStaysBesideIt() {
        List<GhostText.Control> line = ControlLine.controls(state(true, DictationEngine.State.LISTENING), 100);
        StringBuilder ids = new StringBuilder();
        for (GhostText.Control c : line) ids.append(c.id).append(' ');
        assertTrue(ids.toString(), ids.indexOf("cancel") >= 0);
        assertEquals("✕", clear(line).spans[0]);
    }
}
