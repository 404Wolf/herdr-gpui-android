package dev.herdr.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Wolf 11-23: with the keyboard up, the composer's icons ride it over the agent's session. */
public class KeyboardClusterTest {
    @Test public void wolf1123_withTheKeyboardUpAnAgentHasNoToolbarAndNoReservedStrip() {
        assertTrue(KeyboardCluster.overlay(true, true, false));
        // The toolbar (the separate bar) steps aside only then.
        assertFalse(KeyboardCluster.toolbarShown(true, true, false));
        // Keyboard down, a shell (its keys are its input), or the file page over it: as before.
        assertTrue(KeyboardCluster.toolbarShown(false, true, false));
        assertTrue(KeyboardCluster.toolbarShown(true, false, false));
        assertTrue(KeyboardCluster.toolbarShown(true, true, true));
    }

    @Test public void wolf1123_thePromptRowIsLiftedOnlyAsFarAsTheClusterWouldCoverIt() {
        // 168 px of cluster (with its margin); the prompt has 60 px of footer rows under it.
        assertEquals(108, KeyboardCluster.belowTerminal(true, 168, 60f));
        // Footer taller than the cluster: the terminal runs down to the keyboard.
        assertEquals(0, KeyboardCluster.belowTerminal(true, 168, 200f));
        // Where the prompt is not known: never cover it.
        assertEquals(168, KeyboardCluster.belowTerminal(true, 168, Float.NaN));
        // Keyboard down: the cluster keeps its own room under the terminal, as before.
        assertEquals(168, KeyboardCluster.belowTerminal(false, 168, 200f));
    }

    @Test public void wolf1123_theClusterRidesTheKeyboardWhileItMoves() {
        // Laid out for the keyboard's final height; while it is still 300 px short of that, the
        // cluster sits 300 px lower, on top of the keyboard as it rises.
        assertEquals(300f, KeyboardCluster.ride(900, 600f), 0f);
        assertEquals(0f, KeyboardCluster.ride(900, 900f), 0f);
        // Going down: laid out for no keyboard, the keyboard still 400 px up.
        assertEquals(-400f, KeyboardCluster.ride(0, 400f), 0f);
    }
}
