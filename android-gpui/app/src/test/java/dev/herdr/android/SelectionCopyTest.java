package dev.herdr.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import org.junit.Test;

/** What a finished selection copies and says, and when a panel's settles (factory-9zz.4). */
public final class SelectionCopyTest {
    @Test public void theNoteCountsLinesOrCharacters() {
        assertEquals("Copied 3 lines", SelectionCopy.of("a\nb\nc").said());
        assertEquals("Copied 2 lines", SelectionCopy.of("a\nb\n").said());
        assertEquals("Copied 12 characters", SelectionCopy.of("hello, world").said());
        assertEquals("Copied 1 character", SelectionCopy.of("x").said());
        // A character outside the BMP is one character, not two.
        assertEquals("Copied 2 characters", SelectionCopy.of("a😀").said());
    }

    @Test public void aHugeSelectionIsCutAtACharacterBoundaryUnderTheCap() {
        StringBuilder big = new StringBuilder();
        while (big.length() < SelectionCopy.CAP_BYTES / 2 + 10) big.append('\uD83D').append('\uDE00');
        SelectionCopy.Clip clip = SelectionCopy.of(big.toString());
        assertTrue(clip.cut);
        assertTrue(clip.text.getBytes(StandardCharsets.UTF_8).length <= SelectionCopy.CAP_BYTES);
        assertFalse(Character.isHighSurrogate(clip.text.charAt(clip.text.length() - 1)));
        assertEquals("Copied (cut to 512 KiB)", clip.said());
        String fits = new String(new char[1000]).replace('\0', 'x');
        assertFalse(SelectionCopy.of(fits).cut);
    }

    @Test public void aPanelsSelectionIsCopiedOnceItSettlesAndNotAgainWhenUnchanged() {
        SelectionCopy.Settle settle = new SelectionCopy.Settle();
        settle.changed("hel", 0);
        settle.changed("hello", 300);
        assertNull(settle.due(800));
        assertEquals("hello", settle.due(900));
        // The toolbar closing after that copies nothing new.
        assertNull(settle.ended());
        // The same words selected again: not copied or said again.
        settle.changed("hello", 2000);
        assertNull(settle.due(3000));
        // The toolbar closing before it settled copies at once.
        settle.changed("hello world", 4000);
        assertEquals("hello world", settle.ended());
        // A long press on blanks selects nothing.
        settle.changed("   ", 5000);
        assertNull(settle.due(6000));
        assertNull(settle.ended());
    }

    @Test public void theClipIsHerdrsMarkedSensitiveAndTheRailNeverClosesUnderASelection() throws IOException {
        String text = read("SelectableText");
        assertTrue(text.contains("ClipData.newPlainText(SelectionCopy.LABEL, clip.text)"));
        assertTrue(text.contains("extras.putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true);"));
        assertTrue(text.contains("catch (RuntimeException e)"));
        assertTrue(text.contains("menu.add(Menu.NONE, QUOTE, Menu.FIRST, \"Quote\")"));
        assertTrue(text.contains("setTextIsSelectable(true);"));
        assertTrue(read("RailScrollView").contains("!SelectableText.anySelected(this)"));
    }

    private static String read(String name) throws IOException {
        return new String(Files.readAllBytes(Paths.get("src/main/java/dev/herdr/android/" + name + ".java")), StandardCharsets.UTF_8);
    }
}
