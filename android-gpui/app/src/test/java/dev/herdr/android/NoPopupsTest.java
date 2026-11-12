package dev.herdr.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.Test;

/**
 * Herdr shows no toasts or snackbars (Wolf: "kill all notifications and popups
 * always"). Anything worth saying goes in place: the status line (the
 * terminal's note()), a row's state, or the hosts screen's status. This fails
 * on any new Toast or Snackbar in the app's sources.
 */
public final class NoPopupsTest {
    private static final Pattern POPUP = Pattern.compile(
            "\\bToast\\s*\\.\\s*makeText\\b|\\bSnackbar\\s*\\.\\s*make\\b|import\\s+android\\.widget\\.Toast\\b"
                    + "|import\\s+com\\.google\\.android\\.material\\.snackbar\\b");

    @Test public void theAppSourcesMakeNoToastsOrSnackbars() throws IOException {
        Path sources = Paths.get("src", "main", "java");
        assertTrue("run from the app module: " + sources.toAbsolutePath(), Files.isDirectory(sources));
        List<String> found = new ArrayList<>();
        try (Stream<Path> files = Files.walk(sources)) {
            for (Path file : (Iterable<Path>) files.filter(p -> p.toString().endsWith(".java"))::iterator) {
                List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
                for (int i = 0; i < lines.size(); i++) {
                    Matcher match = POPUP.matcher(lines.get(i));
                    if (match.find()) found.add(sources.relativize(file) + ":" + (i + 1) + ": " + lines.get(i).trim());
                }
            }
        }
        assertEquals("Use note() or in-place UI, not a pop-up:\n" + String.join("\n", found), 0, found.size());
    }

    @Test public void thePatternCatchesTheUsualSpellings() {
        assertTrue(POPUP.matcher("Toast.makeText(this, \"x\", Toast.LENGTH_SHORT).show();").find());
        assertTrue(POPUP.matcher("android.widget.Toast.makeText(c, m, 0)").find());
        assertTrue(POPUP.matcher("Snackbar.make(view, \"x\", Snackbar.LENGTH_LONG)").find());
        assertTrue(POPUP.matcher("import android.widget.Toast;").find());
    }
}
