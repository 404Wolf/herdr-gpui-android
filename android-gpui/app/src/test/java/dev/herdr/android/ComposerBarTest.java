package dev.herdr.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
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

    @Test public void theMicrophoneIsOpenWhileConnectingListeningOnDeviceOrRecordingOffline() {
        assertTrue(ComposerBar.recording(dev.herdr.android.dictation.DictationEngine.State.CONNECTING));
        assertTrue(ComposerBar.recording(dev.herdr.android.dictation.DictationEngine.State.LISTENING));
        assertTrue(ComposerBar.recording(dev.herdr.android.dictation.DictationEngine.State.OFFLINE_RECORDING));
        assertTrue(ComposerBar.recording(dev.herdr.android.dictation.DictationEngine.State.ON_DEVICE));
        assertFalse(ComposerBar.recording(dev.herdr.android.dictation.DictationEngine.State.TRANSCRIBING));
        assertFalse(ComposerBar.recording(dev.herdr.android.dictation.DictationEngine.State.PENDING));
        assertFalse(ComposerBar.recording(dev.herdr.android.dictation.DictationEngine.State.IDLE));
    }

    @Test public void aPaneSwitchKeepsTheSessionsWhoseTextIsThePhonesOrOnItsWay() {
        // Kept: the phone transcribing, a queued recording, a final on its way.
        assertTrue(ComposerBar.keepsAcrossPaneSwitch(dev.herdr.android.dictation.DictationEngine.State.ON_DEVICE));
        assertTrue(ComposerBar.keepsAcrossPaneSwitch(dev.herdr.android.dictation.DictationEngine.State.OFFLINE_RECORDING));
        assertTrue(ComposerBar.keepsAcrossPaneSwitch(dev.herdr.android.dictation.DictationEngine.State.TRANSCRIBING));
        // Cancelled: a desktop utterance still being spoken belongs to its pane.
        assertFalse(ComposerBar.keepsAcrossPaneSwitch(dev.herdr.android.dictation.DictationEngine.State.LISTENING));
        assertFalse(ComposerBar.keepsAcrossPaneSwitch(dev.herdr.android.dictation.DictationEngine.State.CONNECTING));
        assertFalse(ComposerBar.keepsAcrossPaneSwitch(dev.herdr.android.dictation.DictationEngine.State.IDLE));
        assertFalse(ComposerBar.keepsAcrossPaneSwitch(dev.herdr.android.dictation.DictationEngine.State.PENDING));
    }

    @Test public void whereHerdrDrawsTalkTheKeyboardIsAskedToDropItsMicrophone() {
        String options = ComposerBar.privateImeOptions(true);
        // HeliBoard splits on "," exactly: no spaces, and its own key present
        // (InputAttributes.inPrivateImeOptions: package name + ".noMicrophoneKey").
        java.util.List<String> keys = java.util.Arrays.asList(options.split(","));
        assertFalse(options.contains(" "));
        assertTrue(keys.contains("helium314.keyboard.noMicrophoneKey"));
        assertTrue(keys.contains("helium314.keyboard.debug.noMicrophoneKey"));
        assertTrue(keys.contains("noMicrophoneKey"));
        assertTrue(keys.contains("nm"));
        assertTrue(keys.contains("com.google.android.inputmethod.latin.noMicrophoneKey"));
        // No Herdr talk (no dictation, a shell): the keyboard keeps its own.
        assertEquals(null, ComposerBar.privateImeOptions(false));
    }

    @Test public void theKeyboardsFieldSendsAndNeverOpensAFullScreenBox() {
        int ime = ComposerBar.FIELD_IME_OPTIONS;
        assertEquals(android.view.inputmethod.EditorInfo.IME_ACTION_SEND,
                ime & android.view.inputmethod.EditorInfo.IME_MASK_ACTION);
        assertTrue((ime & android.view.inputmethod.EditorInfo.IME_FLAG_NO_FULLSCREEN) != 0);
        assertTrue((ime & android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI) != 0);
        int type = ComposerBar.FIELD_INPUT_TYPE;
        assertEquals(android.text.InputType.TYPE_CLASS_TEXT, type & android.text.InputType.TYPE_MASK_CLASS);
        // Single-line, so the action key is "send", not a new line.
        assertEquals(0, type & android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        // Not a password or email field: those hide the mic for other reasons
        // and would also turn off suggestions.
        assertEquals(0, type & android.text.InputType.TYPE_MASK_VARIATION);
    }

    @Test public void switchingBetweenAgentAndShellRestartsTheKeyboardOnlyWhenItsMicChanges() {
        String agent = ComposerBar.privateImeOptions(true);
        String shell = ComposerBar.privateImeOptions(false);
        // Agent to shell: the keyboard gets its mic back (no Herdr talk there).
        assertTrue(dev.gpui.mobile.GpuiInputActivity.imeOptionsChanged(agent, shell));
        // Shell to agent: the keyboard drops its mic (Herdr's talk is drawn).
        assertTrue(dev.gpui.mobile.GpuiInputActivity.imeOptionsChanged(shell, agent));
        // Agent to agent, shell to shell: no restart, no flicker.
        assertFalse(dev.gpui.mobile.GpuiInputActivity.imeOptionsChanged(agent, ComposerBar.privateImeOptions(true)));
        assertFalse(dev.gpui.mobile.GpuiInputActivity.imeOptionsChanged(shell, ComposerBar.privateImeOptions(false)));
    }

    @Test public void aTouchBetweenTheControlsTargetsReachesTheTerminal() {
        // The strip around the targets lets a finger that went down on none of
        // them through to the terminal (a program's own tab under the gap)...
        assertFalse(ComposerBar.stripKeeps(android.view.MotionEvent.ACTION_DOWN));
        // ...and keeps the rest of a swipe it took over from a target.
        assertTrue(ComposerBar.stripKeeps(android.view.MotionEvent.ACTION_MOVE));
        assertTrue(ComposerBar.stripKeeps(android.view.MotionEvent.ACTION_UP));
    }

}
