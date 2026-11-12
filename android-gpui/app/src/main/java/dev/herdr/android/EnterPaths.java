package dev.herdr.android;

import android.view.KeyEvent;
import android.view.inputmethod.EditorInfo;

/**
 * The ways a keyboard says "send" to the composer's box, each of which must
 * end in the same send as the app's own button:
 * <ul>
 *   <li>an editor action: most keyboards' action key (Send, Done, Go, or none set);</li>
 *   <li>an Enter key event: what HeliBoard sends for apps it treats as terminals
 *       (Wolf's lists this app), through a separate, asynchronous path that does
 *       not depend on which view holds focus when it lands;</li>
 *   <li>a committed newline: what some keyboards type for Enter instead.</li>
 * </ul>
 * Shift+Enter is a new line in every path. Pure: no views.
 */
final class EnterPaths {
    private EnterPaths() { }

    /** An editor action sends, except moving between fields (Next, Previous). */
    static boolean sendsOnAction(int actionId) {
        return actionId != EditorInfo.IME_ACTION_NEXT && actionId != EditorInfo.IME_ACTION_PREVIOUS;
    }

    /** Whether a key is Enter (either one); its press sends unless Shift is held. */
    static boolean isEnter(int keyCode) {
        return keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER;
    }

    static boolean sendsOnKey(int keyCode, int action, boolean shift) {
        return isEnter(keyCode) && action == KeyEvent.ACTION_DOWN && !shift;
    }

    /**
     * After an edit that replaced {@code before} characters at {@code start}
     * with {@code count} new ones, the text without a newline the keyboard
     * typed in place of Enter (a single "\n", or one ending what it committed),
     * or null when the edit is not one: pasted text keeps its line breaks.
     */
    static String withoutTypedNewline(CharSequence text, int start, int before, int count) {
        if (count < 1 || start + count > text.length()) return null;
        int last = start + count - 1;
        if (text.charAt(last) != '\n') return null;
        // A paste of several lines is not Enter: only a lone newline, or one
        // closing a word the keyboard committed with it ("word\n").
        for (int i = start; i < last; i++) {
            if (text.charAt(i) == '\n') return null;
        }
        return text.subSequence(0, last).toString() + text.subSequence(last + 1, text.length());
    }
}
