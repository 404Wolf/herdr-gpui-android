package dev.herdr.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Which panes get the composer (no bar, ghost text, the floating chip). */
public final class ComposerBarTest {
    private static final GhostText.Control TALK = new GhostText.Control("talk", "talk", "dim");
    private static final GhostText.Control REC =
            new GhostText.Control("talk", "● rec 0:05 · tap to stop", "danger");
    private static final GhostText.Control REC_SHORT = new GhostText.Control("talk", "● 0:05 stop", "danger");
    private static final GhostText.Control REC_TINY = new GhostText.Control("talk", "● stop", "danger");
    private static final java.util.List<GhostText.Control> RECORDING =
            java.util.Arrays.asList(REC, REC_SHORT, REC_TINY);
    private static final java.util.List<GhostText.Control> OFFLINE = java.util.Arrays.asList(
            new GhostText.Control("talk", "desktop offline · rec 0:05 · tap to stop", "warn"),
            new GhostText.Control("talk", "● offline 0:05 stop", "warn"),
            new GhostText.Control("talk", "● stop", "warn"));
    private static final java.util.List<GhostText.Control> IDLE = java.util.Collections.singletonList(TALK);
    private static final java.util.List<GhostText.Control> NONE = java.util.Collections.emptyList();
    private static final Outbox.Message QUEUED =
            new Outbox.Message("a", "h", "t1", "b", "x", 1, Outbox.State.QUEUED, false);

    @Test public void agentsGetTheComposerAndShellsKeepTheirBar() {
        assertTrue(ComposerBar.isAgent("claude"));
        assertTrue(ComposerBar.isAgent("codex"));
        // An agent the renderer has no prompt shape for still dictates (strip fallback).
        assertTrue(ComposerBar.isAgent("aider"));
        assertFalse(ComposerBar.isAgent(""));
        assertFalse(ComposerBar.isAgent(null));
        assertFalse(ComposerBar.isAgent("zsh"));
        assertFalse(ComposerBar.isAgent("shell"));
        assertFalse(ComposerBar.isAgent("bash"));
    }

    @Test public void typeTogglesTheKeyboardAndReadsHideWhileItIsUp() {
        java.util.List<GhostText.Control> idle = ComposerBar.controls(false,
                IDLE, java.util.Collections.emptyList(), Integer.MAX_VALUE);
        assertEquals("type", idle.get(0).id);
        assertEquals("type", idle.get(0).spans[0]);
        java.util.List<GhostText.Control> typing = ComposerBar.controls(true,
                IDLE, java.util.Collections.emptyList(), Integer.MAX_VALUE);
        // Same control, same place: only its word changes, so the target stays put.
        assertEquals("type", typing.get(0).id);
        assertEquals("hide", typing.get(0).spans[0]);
        assertEquals(3, typing.size());
    }

    @Test public void sendCarriesTheQueuedCountAndTalkIsLeftOutWithoutDictation() {
        Outbox.Message queued = new Outbox.Message("a", "h", "t1", "b", "x", 1, Outbox.State.QUEUED, false);
        java.util.List<GhostText.Control> controls = ComposerBar.controls(false, NONE,
                java.util.Arrays.asList(queued, queued), Integer.MAX_VALUE);
        assertEquals(2, controls.size());
        GhostText.Control send = controls.get(1);
        assertEquals("send", send.id);
        assertEquals("·2", send.spans[2]);
        assertEquals("info", send.spans[3]);
    }

    private static String ids(java.util.List<GhostText.Control> controls) {
        StringBuilder out = new StringBuilder();
        for (GhostText.Control c : controls) out.append(out.length() == 0 ? "" : " ").append(c.id);
        return out.toString();
    }

    @Test public void theLineIsMeasuredAsTheRendererDrawsIt() {
        // " type ─ talk ─ send ──"
        assertEquals(22, ComposerBar.lineWidth(ComposerBar.controls(false, IDLE,
                java.util.Collections.emptyList(), Integer.MAX_VALUE)));
        // " type ─ ● rec 0:05 · tap to stop ─ send·2 ──": the dot and rules are one cell each.
        assertEquals(44, ComposerBar.lineWidth(ComposerBar.controls(false, RECORDING,
                java.util.Arrays.asList(QUEUED, QUEUED), Integer.MAX_VALUE)));
    }

    @Test public void stopAlwaysFitsWhileRecordingEvenOnTheCoverScreen() {
        java.util.List<Outbox.Message> queued = java.util.Arrays.asList(QUEUED, QUEUED, QUEUED);
        for (int cols = 24; cols <= 120; cols++) {
            java.util.List<GhostText.Control> line = ComposerBar.controls(false, RECORDING, queued, cols);
            assertTrue("talk at " + cols, ids(line).contains("talk"));
            if (cols >= 18) { // " ● stop ──" is 10 cells
                assertTrue("fits at " + cols, ComposerBar.lineWidth(line) + ComposerBar.CONTROLS_MARGIN <= cols);
            }
        }
        // Wide enough (the inner screen): every word.
        java.util.List<GhostText.Control> wide = ComposerBar.controls(false, RECORDING, queued, 100);
        assertEquals("type talk send", ids(wide));
        assertEquals("● rec 0:05 · tap to stop", wide.get(1).spans[0]);
        assertEquals("·3", wide.get(2).spans[2]);
        // The cover screen (about 50 columns): the short stop, everything else kept.
        java.util.List<GhostText.Control> cover = ComposerBar.controls(false, RECORDING, queued, 50);
        assertEquals("type talk send", ids(cover));
        assertEquals("● 0:05 stop", cover.get(1).spans[0]);
        assertEquals("·3", cover.get(2).spans[2]);
        // Narrower: "type" goes, then the count, then "send"; never the stop.
        assertEquals("talk send", ids(ComposerBar.controls(false, RECORDING, queued, 38)));
        java.util.List<GhostText.Control> narrow = ComposerBar.controls(false, RECORDING, queued, 30);
        assertEquals("talk send", ids(narrow));
        assertEquals(2, narrow.get(1).spans.length);
        assertEquals("● 0:05 stop", ComposerBar.controls(false, RECORDING, queued, 24).get(0).spans[0]);
        // Pinch zoom at 24 pt on the cover screen (~22 to 28 columns): the shortest stop.
        java.util.List<GhostText.Control> tiny = ComposerBar.controls(false, RECORDING, queued, 22);
        assertEquals("talk", ids(tiny));
        assertEquals("● stop", tiny.get(0).spans[0]);
        // Narrower than any line: the barest one still has the stop.
        assertEquals("● stop", ComposerBar.controls(false, RECORDING, queued, 5).get(0).spans[0]);
    }

    @Test public void withoutDictationTheNarrowLineKeepsSend() {
        assertEquals("send", ids(ComposerBar.controls(false, NONE,
                java.util.Collections.emptyList(), 5)));
    }

    @Test public void offlineStopFitsAtPinchZoomOnTheCoverScreen() {
        java.util.List<Outbox.Message> queued = java.util.Arrays.asList(QUEUED, QUEUED, QUEUED);
        for (int cols : new int[] {22, 28}) {
            java.util.List<GhostText.Control> line = ComposerBar.controls(false, OFFLINE, queued, cols);
            assertTrue("talk at " + cols, ids(line).contains("talk"));
            assertTrue("fits at " + cols, ComposerBar.lineWidth(line) + ComposerBar.CONTROLS_MARGIN <= cols);
        }
        assertEquals("● stop", ComposerBar.controls(false, OFFLINE, queued, 28).get(0).spans[0]);
        // The fold's first frame at 62 columns: no longer the full line, but every control.
        java.util.List<GhostText.Control> folded = ComposerBar.controls(false, OFFLINE, queued, 62);
        assertEquals("type talk send", ids(folded));
        assertEquals("● offline 0:05 stop", folded.get(1).spans[0]);
    }

    @Test public void theMicrophoneIsOpenWhileConnectingListeningOrRecordingOffline() {
        assertTrue(ComposerBar.recording(dev.herdr.android.dictation.DictationEngine.State.CONNECTING));
        assertTrue(ComposerBar.recording(dev.herdr.android.dictation.DictationEngine.State.LISTENING));
        assertTrue(ComposerBar.recording(dev.herdr.android.dictation.DictationEngine.State.OFFLINE_RECORDING));
        assertFalse(ComposerBar.recording(dev.herdr.android.dictation.DictationEngine.State.TRANSCRIBING));
        assertFalse(ComposerBar.recording(dev.herdr.android.dictation.DictationEngine.State.PENDING));
        assertFalse(ComposerBar.recording(dev.herdr.android.dictation.DictationEngine.State.IDLE));
    }

    @Test public void theKeyboardsUnderlinedWordIsTheRevisableTailOnlyAtTheEnd() {
        // "fix the bu|g": composing "bug" at the end of an 11-character draft.
        assertEquals(8, ComposerBar.composingFrom(11, 8, 11));
        // No composing word, an empty one, or one not at the end (the ghost
        // caret is always at the end, so nothing is marked).
        assertEquals(-1, ComposerBar.composingFrom(11, -1, -1));
        assertEquals(-1, ComposerBar.composingFrom(11, 11, 11));
        assertEquals(-1, ComposerBar.composingFrom(11, 0, 3));
        assertEquals(0, ComposerBar.composingFrom(3, 0, 3));
    }

    @Test public void theTypedDraftsCursorAlwaysGoesBackToTheEnd() {
        // "abc" with the cursor after it: where the ghost caret is drawn.
        assertFalse(ComposerBar.keepsAtEnd(3, 3, 3));
        // Left arrow (ab|c), a spacebar swipe to the start, a selection, a
        // selection running to the end: all go back.
        assertTrue(ComposerBar.keepsAtEnd(2, 2, 3));
        assertTrue(ComposerBar.keepsAtEnd(0, 0, 3));
        assertTrue(ComposerBar.keepsAtEnd(0, 3, 3));
        assertTrue(ComposerBar.keepsAtEnd(1, 3, 3));
        // No selection at all (the field is not focused), and an empty draft.
        assertFalse(ComposerBar.keepsAtEnd(-1, -1, 3));
        assertFalse(ComposerBar.keepsAtEnd(0, 0, 0));
    }

    @Test public void cursorKeysDoNothingWhileTypingButEnterAndLettersStillWork() {
        for (int code : new int[] {android.view.KeyEvent.KEYCODE_DPAD_LEFT, android.view.KeyEvent.KEYCODE_DPAD_RIGHT,
                android.view.KeyEvent.KEYCODE_DPAD_UP, android.view.KeyEvent.KEYCODE_DPAD_DOWN,
                android.view.KeyEvent.KEYCODE_MOVE_HOME, android.view.KeyEvent.KEYCODE_MOVE_END,
                android.view.KeyEvent.KEYCODE_PAGE_UP, android.view.KeyEvent.KEYCODE_PAGE_DOWN}) {
            assertTrue(String.valueOf(code), ComposerBar.movesCursor(code));
        }
        assertFalse(ComposerBar.movesCursor(android.view.KeyEvent.KEYCODE_ENTER));
        assertFalse(ComposerBar.movesCursor(android.view.KeyEvent.KEYCODE_DEL));
        assertFalse(ComposerBar.movesCursor(android.view.KeyEvent.KEYCODE_A));
    }
}
