package dev.herdr.android;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/** A waiting dictation's chip in the draft: its words, and what a backspace into it leaves. */
public final class DraftChipsTest {
    private static final String TOKEN = DictationToken.of("c-1a2b");

    @Test public void anIntactTokenIsLeftAlone() {
        String draft = "fix " + TOKEN + " then " + DictationToken.of("c-9") + " ok";
        assertEquals(draft, DraftChips.repair(draft));
        assertEquals("plain words", DraftChips.repair("plain words"));
    }

    @Test public void aBackspaceIntoAChipTakesTheWholeChip() {
        String cutEnd = "fix " + TOKEN.substring(0, TOKEN.length() - 1);
        assertEquals("fix ", DraftChips.repair(cutEnd));
        String cutStart = "fix " + TOKEN.substring(1) + " now";
        assertEquals("fix  now", DraftChips.repair(cutStart));
        // Only the cut chip goes; a whole one beside it stays.
        assertEquals(TOKEN + " ", DraftChips.repair(TOKEN + " " + TOKEN.substring(0, TOKEN.length() - 1)));
    }

    @Test public void aChipShowsItsLengthWhenKnown() {
        assertEquals("0:12 waiting for desktop", DraftChips.label(12_400L));
        assertEquals("waiting for desktop", DraftChips.label(null));
    }
}
