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
            "linkTouch", "linkRegions", "pumpShell");

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
                "\\n    (?:private |static |final |synchronized )*[\\w.<>\\[\\]]+ " + name + "\\([^)]*\\)[^{;]*\\{")
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
        String shellBranch = reconnect.substring(0, reconnect.indexOf("if (reconnectRunning) return;"));
        assertTrue(shellBranch.contains("startShell();"));
        assertTrue(!shellBranch.contains("pendingInput") && !shellBranch.contains("holdUnsent"));
    }

    @Test public void wolfAskOfflineNeverDims_aHeldFramesScrollReachesTheHeldFrame() throws IOException {
        // The routing is HeldScroll's (HeldScrollTest); the drag and the fling use it, and
        // its held side scrolls the phone's own rows.
        String scroll = body(terminal(), "requestScroll");
        assertTrue(scroll.contains("return HeldScroll.scroll("));
        assertTrue(scroll.contains("public boolean held(int step) { return scrollHeld(step, false); }"));
    }
}
