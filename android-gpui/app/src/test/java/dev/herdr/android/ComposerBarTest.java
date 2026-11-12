package dev.herdr.android;

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
}
