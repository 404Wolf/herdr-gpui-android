package dev.herdr.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.Test;

/**
 * Wolf's ask: offline "should be a small indicator in a top corner, NEVER
 * gray/dim the screen". The screens that show a held, cached or offline pane
 * (the terminal's GPUI view, the terminal activity, Home) may not fade any
 * content: no element opacity in the GPUI view, and no setAlpha in the two
 * activities but the few listed here, none of which follows the link. The
 * banner that used to rise above the bottom bar is the corner indicator now,
 * in a raw shell too, and nothing the link runs opens a dialog, a scrim or a
 * view over the terminal: the indicator's actions open in place.
 */
public final class OfflineNeverDimsTest {
    private static final Path JAVA = Paths.get("src", "main", "java", "dev", "herdr", "android");
    private static final Path UI_RS = Paths.get("..", "rust", "src", "ui.rs");

    /** Colour math on one cell (a glyph's own alpha) is not fading the screen. */
    private static final Pattern ELEMENT_OPACITY = Pattern.compile("\\.opacity\\(");
    private static final Pattern COLOR_OPACITY = Pattern.compile("colou?r\\.opacity\\(");
    private static final Pattern SET_ALPHA = Pattern.compile("\\bsetAlpha\\(");

    /** Every setAlpha allowed in the activities: none is about the link. */
    private static final List<String> ALLOWED_ALPHA = Arrays.asList(
            // A project with no PM is a quieter row, online or not.
            "HomeActivity.java: line.setAlpha(0.65f);",
            "GpuiTerminalActivity.java: header.row.setAlpha(0.65f);",
            // The + is disabled where nothing can be asked.
            "GpuiTerminalActivity.java: header.plus.setAlpha(canAsk ? 1f : 0.4f);");

    private static List<String> lines(Path file) throws IOException {
        assertTrue("run from the app module: " + file.toAbsolutePath(), Files.isRegularFile(file));
        return Files.readAllLines(file, StandardCharsets.UTF_8);
    }

    @Test public void wolfAskOfflineNeverDims_theGpuiViewFadesNoElementWhileHeldOrOffline() throws IOException {
        List<String> found = new ArrayList<>();
        List<String> ui = lines(UI_RS);
        for (int i = 0; i < ui.size(); i++) {
            String line = ui.get(i);
            if (line.trim().startsWith("//")) continue;
            if (ELEMENT_OPACITY.matcher(line).find() && !COLOR_OPACITY.matcher(line).find()) {
                found.add("ui.rs:" + (i + 1) + ": " + line.trim());
            }
        }
        assertEquals("Offline keeps full colour; say it with the corner indicator:\n"
                + String.join("\n", found), 0, found.size());
    }

    @Test public void wolfAskOfflineNeverDims_theTerminalAndHomeSetNoAlphaButTheListed() throws IOException {
        List<String> found = new ArrayList<>();
        for (String name : new String[] {"GpuiTerminalActivity.java", "HomeActivity.java"}) {
            List<String> source = lines(JAVA.resolve(name));
            for (int i = 0; i < source.size(); i++) {
                String line = source.get(i).trim();
                if (line.startsWith("//") || !SET_ALPHA.matcher(line).find()) continue;
                if (!ALLOWED_ALPHA.contains(name + ": " + line)) found.add(name + ":" + (i + 1) + ": " + line);
            }
        }
        assertEquals("Offline keeps full colour; say it with the corner indicator:\n"
                + String.join("\n", found), 0, found.size());
    }

    /**
     * Every method the link runs when it drops, fails, comes back or ends a
     * shell, and the indicator's own in-place actions and touch routing.
     */
    private static final List<String> LINK_PATH = Arrays.asList(
            "fail", "showLinkProblem", "showShellEnd", "clearLinkProblem", "showLinkDown", "showLinkUp",
            "autoReconnect", "updateLinkBadge", "toggleLinkActions", "useLinkAction", "renderLinkActions",
            "linkTouch", "linkRegions", "linkRowBottom", "postLinkCollapse", "linkAccessibilityFocused",
            "pumpShell");

    /**
     * What the link path may never do: open a dialog, sheet or pop-up, put a
     * view over (or a foreground, scrim or alpha on) the screen, or add any
     * view at all (no view or layout is created there); the indicator and its
     * actions are built once, with the tab row.
     */
    private static final Pattern COVERS_THE_SCREEN = Pattern.compile(
            "Dialog|ActionSheet|PopupWindow|PopupMenu|GestureHint|WindowManager|showRailOverlay"
            + "|setForeground|addView\\(|removeView|new [\\w.]*(View|Layout)\\("
            + "|setAlpha\\(|ColorDrawable|setBackgroundColor\\(|setClickable\\(true|setDim|FLAG_DIM");

    /** The body of the activity's method {@code name}, braces matched outside literals and comments. */
    static String body(String source, String name) {
        Matcher declared = Pattern.compile(
                "\\n    (?:@Override |public |protected |private |static |final |synchronized )*[\\w.<>\\[\\]]+ " + name + "\\([^)]*\\)[^{;]*\\{")
                .matcher(source);
        assertTrue("the link path still has " + name + "()", declared.find());
        int start = declared.end() - 1, depth = 0;
        for (int i = start; i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == '"' || c == '\'') {
                for (i++; i < source.length() && source.charAt(i) != c; i++) {
                    if (source.charAt(i) == '\\') i++;
                }
            } else if (c == '/' && i + 1 < source.length() && source.charAt(i + 1) == '/') {
                i = source.indexOf('\n', i);
            } else if (c == '/' && i + 1 < source.length() && source.charAt(i + 1) == '*') {
                i = source.indexOf("*/", i) + 1;
            } else if (c == '{') {
                depth++;
            } else if (c == '}' && --depth == 0) {
                return source.substring(start, i + 1);
            }
        }
        throw new AssertionError(name + "() never closes");
    }

    private static String terminal() throws IOException {
        return String.join("\n", lines(JAVA.resolve("GpuiTerminalActivity.java")));
    }

    @Test public void wolfAskOfflineNeverDims_theLinkPathOpensNoDialogScrimOrOverlay() throws IOException {
        String terminal = terminal();
        List<String> found = new ArrayList<>();
        for (String name : LINK_PATH) {
            String[] code = body(terminal, name).split("\n");
            for (String line : code) {
                String trimmed = line.trim();
                if (trimmed.startsWith("//") || trimmed.startsWith("*") || trimmed.startsWith("/*")) continue;
                if (COVERS_THE_SCREEN.matcher(trimmed).find()) found.add(name + "(): " + trimmed);
            }
        }
        assertEquals("Offline is only the corner indicator: no dialog, scrim, overlay or new view:\n"
                + String.join("\n", found), 0, found.size());
    }

    @Test public void wolfAskOfflineNeverDims_theIndicatorAndItsActionsLiveInTheTabRowOnly() throws IOException {
        String terminal = terminal();
        // Built once, in the top row beside More: never over the terminal or above the bar.
        assertTrue(terminal.contains("tabRow.addView(linkBox, badgeAt);"));
        assertTrue(terminal.contains("linkBox.addView(linkPrimary, linkActionParams());"));
        assertTrue(terminal.contains("linkBox.addView(linkHosts, linkActionParams());"));
        // No banner left to build, in a Herdr pane or a raw shell.
        assertTrue(!Pattern.compile("\\bbanner\\s*[=.;]").matcher(terminal).find());
        // The crumb keeps saying where this is: no link text replaces it.
        assertTrue(!terminal.contains("OfflineSwitch.label("));
        assertTrue(!terminal.contains("Reconnecting to "));
        // A tap on the indicator opens its actions in place.
        assertTrue(body(terminal, "toggleLinkActions").contains("linkActions.tap(linkLook.shown)"));
    }

    @Test public void wolfAskOfflineNeverDims_aRawShellsEndIsTheSameIndicator() throws IOException {
        String terminal = terminal();
        assertTrue(body(terminal, "updateLinkBadge").contains("LinkBadge.shell(shellEnd, linkProblem)"));
        // Exited and lost stay apart, from what the host said.
        String pump = body(terminal, "pumpShell");
        assertTrue(pump.contains("RemoteNative.shellExited(handle)"));
        assertTrue(pump.contains("LinkBadge.ShellEnd.EXITED") && pump.contains("LinkBadge.ShellEnd.LOST"));
        // A shell never holds or replays input: only a Herdr pane is resumable.
        assertTrue(body(terminal, "resumable").contains("!shellMode()"));
        String reconnect = body(terminal, "reconnectCurrentHost");
        String shellBranch = reconnect.substring(0, reconnect.indexOf("if (reconnectRunning) {"));
        assertTrue(shellBranch.contains("startShell();"));
        assertTrue(!shellBranch.contains("pendingInput") && !shellBranch.contains("holdUnsent"));
    }

    /** {@code code} without comments: what runs. */
    private static String code(String source) {
        return source.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("//[^\n]*", "");
    }

    private static int count(String text, String part) {
        int n = 0;
        for (int at = text.indexOf(part); at >= 0; at = text.indexOf(part, at + 1)) n++;
        return n;
    }

    @Test public void wolfAskOfflineNeverDims_aHeldFramesScrollReachesTheHeldFrame() throws IOException {
        // The routing is HeldScroll's (HeldScrollTest); the drag and the fling use it, and
        // its held side scrolls the phone's own rows.
        String terminal = terminal();
        String scroll = code(body(terminal, "requestScroll"));
        assertTrue(scroll.contains("return HeldScroll.scroll("));
        assertTrue(scroll.contains("public boolean held(int step) { return scrollHeld(step, false); }"));
        // The entry decides nothing itself: no early return or branch can refuse a held
        // frame before HeldScroll routes it (one return in the method; the sinks' own two).
        String entry = scroll.substring(0, scroll.indexOf("return HeldScroll.scroll("));
        assertEquals("requestScroll returns only HeldScroll's answer:\n" + entry, 0, count(entry, "return"));
        for (String branch : new String[] {"if (", "if(", "switch", "while", "throw", "? false", "?false"}) {
            assertTrue("requestScroll may not branch before HeldScroll: " + branch, !entry.contains(branch));
        }
        assertEquals(4, count(scroll, "return "));
        // The drag and the fling reach it with every step.
        assertTrue(terminal.contains("boolean atEdge = step != 0 && !requestScroll(step);"));
    }

    @Test public void wolfAskOfflineNeverDims_theTerminalSurfaceDrawsNoFocusWash() throws IOException {
        // When the keyboard's input proxy lets focus go (a shell ends, the link drops after
        // typing), focus returns to the terminal out of touch mode and Android's default focus
        // highlight would wash the whole terminal grey (#2d3138). The surface keeps its focus
        // and input; only that highlight is off, and nothing turns it back on.
        String terminal = terminal();
        String create = code(body(terminal, "createSurface"));
        int surface = create.indexOf("SurfaceView surface = new SurfaceView(this);");
        assertTrue(surface >= 0);
        String setup = create.substring(surface, create.indexOf("surface.getHolder().addCallback(this);"));
        assertTrue(setup.contains("surface.setDefaultFocusHighlightEnabled(false);"));
        assertTrue(setup.contains("surface.setFocusable(true);"));
        assertTrue(setup.contains("surface.setFocusableInTouchMode(true);"));
        assertTrue(!code(terminal).contains("setDefaultFocusHighlightEnabled(true)"));
    }

    @Test public void wolfAskOfflineNeverDims_theIndicatorsTouchesAreDecidedByItsPress() throws IOException {
        // LinkActions.Press decides every event (LinkActionsTest): a press begun under the
        // row goes on to the terminal, a drag is never a click. The activity only does what
        // it says: it clicks only the control Press names and keeps an event only when told.
        String terminal = terminal();
        assertTrue(code(body(terminal, "dispatchTouchEvent")).contains("if (linkTouch(event)) return true;"));
        String touch = code(body(terminal, "linkTouch"));
        assertTrue(touch.contains("linkPress.down(linkRegions(targets), linkRowBottom(), x, y)"));
        assertTrue(touch.contains("linkPress.move(x, y, event.getPointerCount(), linkTouchSlop())"));
        assertTrue(touch.contains("case MotionEvent.ACTION_POINTER_DOWN:"));
        assertTrue(touch.contains("linkPress.up(x, y, pressed >= 0 && targets[pressed].isShown(), linkTouchSlop())"));
        assertTrue(touch.contains("verdict = linkPress.cancel();"));
        assertTrue(touch.contains("if (verdict == LinkActions.Press.CANCEL_THEN_KEEP) {"));
        assertTrue(touch.contains("cancel.setAction(MotionEvent.ACTION_CANCEL);\n            super.dispatchTouchEvent(cancel);"));
        assertTrue(touch.contains("int click = linkPress.takeClick();\n        if (click >= 0) targets[click].performClick();"));
        assertEquals(1, count(touch, "performClick"));
        assertEquals(2, count(touch, "return "));
        assertTrue(touch.contains("return verdict != LinkActions.Press.PASS;"));
        // The row's own bottom, not the grown regions, is where the terminal begins.
        assertTrue(code(body(terminal, "linkRowBottom")).contains("(View) linkBox.getParent()"));
    }

    @Test public void wolfAskOfflineNeverDims_theActionsStayAsLongAsTheUserGivesControls() throws IOException {
        // Six seconds by default; the user's "Time to take action" when longer; never while
        // TalkBack is on one of them (LinkActionsTest has the rules).
        String terminal = terminal();
        assertTrue(code(body(terminal, "toggleLinkActions")).contains("postLinkCollapse(linkActions.generation());"));
        String collapse = code(body(terminal, "postLinkCollapse"));
        assertTrue(collapse.contains(".getRecommendedTimeoutMillis("));
        assertTrue(collapse.contains("AccessibilityManager.FLAG_CONTENT_CONTROLS"));
        assertTrue(collapse.contains("AccessibilityManager.FLAG_CONTENT_TEXT"));
        assertTrue(collapse.contains("}, LinkActions.collapseAfter(recommended));"));
        assertTrue(collapse.contains("linkActions.timeout(at, linkAccessibilityFocused())"));
        assertTrue(collapse.contains("else if (linkActions.waiting(at)) postLinkCollapse(at);"));
        assertTrue(code(body(terminal, "linkAccessibilityFocused")).contains("view.isAccessibilityFocused()"));
    }
}
