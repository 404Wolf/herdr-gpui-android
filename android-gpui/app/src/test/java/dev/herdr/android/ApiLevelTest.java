package dev.herdr.android;

import static org.junit.Assert.assertTrue;

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
 * The app runs from Android 10 (minSdk 29). The JVM the unit tests run on has the newer Java
 * library, so a call Android only has from a later API passes them and crashes on the phone
 * (review of #35: String.repeat, API 33). Android lint's NewApi check runs in Lane R; this
 * catches the ones that slipped through before, at once.
 */
public final class ApiLevelTest {
    /** Calls a minSdk 29 device does not have, with the API level that added them. */
    private static final String[][] NEWER = {
            {"\\.repeat\\(", "String.repeat (API 33)"},
            {"\\.isBlank\\(\\)", "String.isBlank (API 33)"},
            {"\\.strip(Leading|Trailing)?\\(\\)", "String.strip (API 33)"},
            {"\\b(List|Set|Map)\\.of(Entries)?\\(", "List/Set/Map.of (API 30)"},
            {"\\bList\\.copyOf\\(", "List.copyOf (API 30)"},
    };

    @Test public void noJavaLibraryCallNewerThanMinSdk29() throws Exception {
        List<String> found = new ArrayList<>();
        for (String root : new String[] {"src/main/java", "../../android/app/src/main/java"}) {
            try (Stream<Path> files = Files.walk(Paths.get(root))) {
                for (Path f : (Iterable<Path>) files.filter(p -> p.toString().endsWith(".java"))::iterator) {
                    List<String> lines = Files.readAllLines(f, StandardCharsets.UTF_8);
                    for (int i = 0; i < lines.size(); i++) {
                        String line = lines.get(i).replaceAll("//.*", "");
                        if (line.trim().startsWith("*")) continue;
                        for (String[] api : NEWER) {
                            Matcher m = Pattern.compile(api[0]).matcher(line);
                            if (m.find()) found.add(f + ":" + (i + 1) + " " + api[1]);
                        }
                    }
                }
            }
        }
        assertTrue("calls newer than minSdk 29:\n" + String.join("\n", found), found.isEmpty());
    }
}
