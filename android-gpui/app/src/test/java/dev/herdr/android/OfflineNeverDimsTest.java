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
import java.util.regex.Pattern;
import org.junit.Test;

/**
 * Wolf's ask: offline "should be a small indicator in a top corner, NEVER
 * gray/dim the screen". The screens that show a held, cached or offline pane
 * (the terminal's GPUI view, the terminal activity, Home) may not fade any
 * content: no element opacity in the GPUI view, and no setAlpha in the two
 * activities but the few listed here, none of which follows the link. The
 * banner that used to rise above the bottom bar is the corner indicator now.
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

    @Test public void wolfAskOfflineNeverDims_theLinkHasOneIndicatorNotABannerOrAStatusLine() throws IOException {
        String terminal = String.join("\n", lines(JAVA.resolve("GpuiTerminalActivity.java")));
        // The crumb keeps saying where this is: no link text replaces it.
        assertTrue(!terminal.contains("OfflineSwitch.label("));
        assertTrue(!terminal.contains("Reconnecting to "));
        // A Herdr pane's failure goes to the indicator before any banner is built.
        int banner = terminal.indexOf("private void showReconnectBanner(String message)");
        int routed = terminal.indexOf("linkProblem = BannerCopy.human(message);", banner);
        int built = terminal.indexOf("banner = new LinearLayout(this);", banner);
        assertTrue("showReconnectBanner routes to the indicator", banner >= 0 && routed > banner && routed < built);
    }
}
