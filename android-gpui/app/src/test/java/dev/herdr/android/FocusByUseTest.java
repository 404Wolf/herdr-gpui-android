package dev.herdr.android;

import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import org.junit.Test;

/**
 * Item 71, latest-touched device wins (the phone's side): every way Wolf
 * uses the phone reaches {@code RemoteNative.used()}, which the session on
 * screen turns into WINDOW_FOCUS 1 and the next attach into its idle time
 * (core_session's tests cover the frames). Source guards, without a window
 * or JNI.
 */
public final class FocusByUseTest {
    private static final String UI = "GpuiTerminalActivity";

    @Test public void aTouchAnywhereInHerdrIsAUseBeforeAnyViewSeesIt() throws IOException {
        String touch = section(source(UI), "@Override public boolean dispatchTouchEvent(", "@Override public boolean dispatchKeyEvent(");
        assertTrue(touch.contains("ACTION_DOWN) markUsed();"));
        int used = touch.indexOf("markUsed()");
        assertTrue("a use, then the touch as before", used >= 0 && used < touch.indexOf("super.dispatchTouchEvent(event)"));
    }

    @Test public void keysTypingTheKeyboardFoldsAndComingToTheFrontAreUsesToo() throws IOException {
        String ui = source(UI);
        String keys = section(ui, "@Override public boolean dispatchKeyEvent(", "if (displayDialog != null");
        assertTrue("every key down, before any routing", keys.contains("ACTION_DOWN && event.getRepeatCount() == 0) markUsed();"));
        assertTrue(section(ui, "@Override protected void onResume(", "@Override protected void onPause(").contains("markUsed();"));
        assertTrue("the keyboard showing", ui.contains("if (ime && !imeShown) markUsed();"));
        String config = section(ui, "@Override public void onConfigurationChanged(", "terminalRoot.post(");
        assertTrue("a fold, an unfold or a turn", config.contains("markUsed();") && config.contains("screenWidthDp"));
        // A posture change (tabletop) that keeps the window's size, from the watcher.
        String watcher0 = section(ui, "FoldPosture.Uses folds = new FoldPosture.Uses();", "applyPosture();");
        int fold = watcher0.indexOf("if (folds.isUse(posture, update)) markUsed();");
        assertTrue("counted before the same-posture return", fold >= 0 && fold < watcher0.indexOf("if (update.equals(posture)) return;"));
        // Typing into the composer: the keyboard's window sends Herdr no touches.
        assertTrue(section(ui, "private ComposerBar.Host composerHost(", "@Override public void enterKey(")
                .contains("public void typed() { markUsed(); }"));
        String watcher = section(source("ComposerBar"), "@Override public void afterTextChanged(", "EnterPaths.withoutTypedNewline(");
        int typed = watcher.indexOf("host.typed();");
        assertTrue("an edit the program made is not Wolf's", typed > watcher.indexOf("if (updatingField) return;"));
    }

    @Test public void theRailsKeysAreAUseAndStillNeverReachTheTerminal() throws IOException {
        // Item 83's ctrl-alt-w / ctrl-alt-b and item 71's use share dispatchKeyEvent.
        String keys = section(source(UI), "@Override public boolean dispatchKeyEvent(", "nativeSendKey(");
        int used = keys.indexOf("markUsed();");
        int rail = keys.indexOf("if (railKeyCode(event) && !shellMode())");
        assertTrue("every key down is a use, the rail's too", used >= 0 && rail > used);
        String branch = keys.substring(rail, keys.indexOf("return true;", rail));
        assertTrue("the rail branch acts and consumes the key", branch.contains("railKey(code == KeyEvent.KEYCODE_W)"));
        assertTrue("before anything is sent to the terminal", !branch.contains("nativeSend"));
    }

    @Test public void onlyAHerdrSessionOnScreenReportsUse() throws IOException {
        String used = section(source(UI), "private void markUsed(", "@Override public boolean dispatchTouchEvent(");
        assertTrue(used.contains("launch.shell") && used.contains("!hostStarted"));
        assertTrue(used.contains("RemoteNative.used();"));
        assertTrue(source("RemoteNative").contains("static native void used();"));
    }

    private static String source(String name) throws IOException {
        return new String(Files.readAllBytes(Paths.get("src/main/java/dev/herdr/android/" + name + ".java")),
                StandardCharsets.UTF_8);
    }

    private static String section(String source, String start, String end) {
        int first = source.indexOf(start);
        assertTrue(start, first >= 0);
        int last = source.indexOf(end, first);
        assertTrue(end, last > first);
        return source.substring(first, last);
    }
}
