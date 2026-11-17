package dev.herdr.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** The grid's drawable symbols, as {@link GridGlyphs} knows them. */
public final class GridGlyphsTest {
    @Test public void theRenderersStandInsAreReadFromItsOwnSource() {
        // Claude's prompt mark, check, cross and tool bullet are drawn through stand-ins.
        assertEquals("", GridGlyphs.boxes("❯ ✓ ✕ ✗ ⏺ ⎿"));
        // The spinner's stars: the range arm.
        assertEquals("", GridGlyphs.boxes("✢✳✶✻✽"));
        assertTrue(GridGlyphs.STAND_INS.contains((int) '⏵'));
    }

    @Test public void whatShowedAsBoxesOnThePhoneIsFlagged() {
        assertEquals("▴↻▸▾", GridGlyphs.boxes("2 queued ▴ ↻ ▸ ▾"));
        assertEquals("", GridGlyphs.boxes("2 queued ▲ ◌ ≡2 · … ↓ ● ─"));
    }
}
