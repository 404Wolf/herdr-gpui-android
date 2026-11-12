package dev.herdr.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.view.KeyEvent;
import android.view.inputmethod.EditorInfo;
import org.junit.Test;

/** Every way a keyboard says "send" ends in the box's one send. */
public final class EnterPathsTest {
    @Test public void anyEditorActionSendsExceptMovingBetweenFields() {
        for (int action : new int[] {EditorInfo.IME_ACTION_SEND, EditorInfo.IME_ACTION_DONE,
                EditorInfo.IME_ACTION_GO, EditorInfo.IME_ACTION_UNSPECIFIED, EditorInfo.IME_ACTION_NONE,
                EditorInfo.IME_ACTION_SEARCH}) {
            assertTrue("action " + action, EnterPaths.sendsOnAction(action));
        }
        assertFalse(EnterPaths.sendsOnAction(EditorInfo.IME_ACTION_NEXT));
        assertFalse(EnterPaths.sendsOnAction(EditorInfo.IME_ACTION_PREVIOUS));
    }

    @Test public void anEnterKeyPressSendsAndShiftEnterDoesNot() {
        // What HeliBoard sends in a terminal app: a bare Enter, down then up.
        assertTrue(EnterPaths.sendsOnKey(KeyEvent.KEYCODE_ENTER, KeyEvent.ACTION_DOWN, false));
        assertTrue(EnterPaths.sendsOnKey(KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.ACTION_DOWN, false));
        assertFalse("the release sends nothing more",
                EnterPaths.sendsOnKey(KeyEvent.KEYCODE_ENTER, KeyEvent.ACTION_UP, false));
        // Its Shift+Enter is a new line in the message.
        assertFalse(EnterPaths.sendsOnKey(KeyEvent.KEYCODE_ENTER, KeyEvent.ACTION_DOWN, true));
        assertFalse(EnterPaths.sendsOnKey(KeyEvent.KEYCODE_A, KeyEvent.ACTION_DOWN, false));
    }

    @Test public void aCommittedNewlineIsEnterButAPastedOneIsNot() {
        // "hello" then the keyboard commits "\n" at the end: send "hello".
        assertEquals("hello", EnterPaths.withoutTypedNewline("hello\n", 5, 0, 1));
        // A word committed together with its newline.
        assertEquals("fix it", EnterPaths.withoutTypedNewline("fix it\n", 4, 0, 3));
        // In the middle of the text (the caret was moved back) it is still Enter.
        assertEquals("ab", EnterPaths.withoutTypedNewline("a\nb", 1, 0, 1));
        // A pasted block keeps its line breaks.
        assertNull(EnterPaths.withoutTypedNewline("one\ntwo\n", 0, 0, 8));
        // Edits that are not a newline.
        assertNull(EnterPaths.withoutTypedNewline("hello", 4, 0, 1));
        assertNull(EnterPaths.withoutTypedNewline("hello", 5, 1, 0));
    }
}
