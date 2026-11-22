package dev.herdr.android;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * The terminal screen's source as the source-reading tests see it: the
 * activity and the rail moved out of it (RailController, audit PR 11),
 * one after the other.
 */
final class TerminalSource {
    private static final Path JAVA = Paths.get("src/main/java/dev/herdr/android");

    private TerminalSource() {}

    static String text() throws IOException {
        return read("GpuiTerminalActivity.java") + "\n" + read("RailController.java");
    }

    static String rail() throws IOException {
        return read("RailController.java");
    }

    private static String read(String name) throws IOException {
        return new String(Files.readAllBytes(JAVA.resolve(name)), StandardCharsets.UTF_8);
    }
}
