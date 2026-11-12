package dev.herdr.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Which panes get the composer (no bar, ghost text, the floating chip). */
public final class ComposerBarTest {
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
                new GhostText.Control("talk", "talk", "dim"), java.util.Collections.emptyList());
        assertEquals("type", idle.get(0).id);
        assertEquals("type", idle.get(0).spans[0]);
        java.util.List<GhostText.Control> typing = ComposerBar.controls(true,
                new GhostText.Control("talk", "talk", "dim"), java.util.Collections.emptyList());
        // Same control, same place: only its word changes, so the target stays put.
        assertEquals("type", typing.get(0).id);
        assertEquals("hide", typing.get(0).spans[0]);
        assertEquals(3, typing.size());
    }

    @Test public void sendCarriesTheQueuedCountAndTalkIsLeftOutWithoutDictation() {
        Outbox.Message queued = new Outbox.Message("a", "h", "t1", "b", "x", 1, Outbox.State.QUEUED, false);
        java.util.List<GhostText.Control> controls = ComposerBar.controls(false, null,
                java.util.Arrays.asList(queued, queued));
        assertEquals(2, controls.size());
        GhostText.Control send = controls.get(1);
        assertEquals("send", send.id);
        assertEquals("·2", send.spans[2]);
        assertEquals("info", send.spans[3]);
    }
}
