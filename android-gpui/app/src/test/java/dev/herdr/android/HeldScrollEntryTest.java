package dev.herdr.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * Wolf's ask: scrolling keeps working offline. The activity's own scroll
 * entry, run as it is: the drag and the fling call requestScroll, and for a
 * held frame (no pane, a closed one, a reconnect not yet attached) every
 * step must reach the held frame, sign and size unchanged.
 *
 * <p>The activity cannot be built on the JVM, so this compiles the real
 * {@code requestScroll} and {@code heldFrame} from GpuiTerminalActivity.java,
 * verbatim, with the real {@link HeldScroll}, among stand-ins for the few
 * things they read: the current connection, shell mode, and the two sinks,
 * which record what reached them. Any change to that method that drops,
 * zeroes or reroutes a held step fails here, however it is spelled.
 */
public final class HeldScrollEntryTest {
    private static final Path JAVA = Paths.get("src", "main", "java", "dev", "herdr", "android");

    private static Class<?> probe;

    @BeforeClass public static void compileTheActivitysEntry() throws Exception {
        String activity = new String(Files.readAllBytes(JAVA.resolve("GpuiTerminalActivity.java")), StandardCharsets.UTF_8);
        assertTrue(activity.contains("    private boolean requestScroll(int lines) {"));
        assertTrue(activity.contains("    private boolean heldFrame(Connection target) {"));
        String source = "package dev.herdr.android;\n"
                + "public final class EntryProbe {\n"
                + "    static final class Connection {\n"
                + "        boolean closed; long remoteHandle; boolean ready;\n"
                + "        boolean isClosed() { return closed; }\n"
                + "    }\n"
                + "    final Object stateLock = new Object();\n"
                + "    Connection connection;\n"
                + "    boolean shell;\n"
                + "    public final java.util.List<String> went = new java.util.ArrayList<String>();\n"
                + "    boolean shellMode() { return shell; }\n"
                + "    boolean scrollHeld(int lines, boolean latest) { went.add(\"held \" + lines); return true; }\n"
                + "    boolean scrollLive(Connection target, int lines) { went.add(\"live \" + lines); return true; }\n"
                + "    public void target(boolean present, boolean closed, long handle, boolean ready, boolean shellMode) {\n"
                + "        shell = shellMode;\n"
                + "        if (!present) { connection = null; return; }\n"
                + "        connection = new Connection();\n"
                + "        connection.closed = closed; connection.remoteHandle = handle; connection.ready = ready;\n"
                + "    }\n"
                + "    public boolean scroll(int lines) { return requestScroll(lines); }\n"
                + "    // From GpuiTerminalActivity.java, verbatim:\n"
                + "    private boolean requestScroll(int lines) " + OfflineNeverDimsTest.body(activity, "requestScroll") + "\n"
                + "    private boolean heldFrame(Connection target) " + OfflineNeverDimsTest.body(activity, "heldFrame") + "\n"
                + "}\n";
        File dir = Files.createTempDirectory("held-scroll-entry").toFile();
        File pkg = new File(dir, "src/dev/herdr/android");
        assertTrue(pkg.mkdirs());
        File probeSource = new File(pkg, "EntryProbe.java");
        Files.write(probeSource.toPath(), source.getBytes(StandardCharsets.UTF_8));
        File classes = new File(dir, "classes");
        assertTrue(classes.mkdirs());
        // The JDK's compiler, reached by reflection: the unit tests compile against android.jar.
        Object javac = Class.forName("javax.tools.ToolProvider").getMethod("getSystemJavaCompiler").invoke(null);
        assertNotNull("the unit tests run on a JDK with javac", javac);
        Method run = Class.forName("javax.tools.Tool")
                .getMethod("run", InputStream.class, OutputStream.class, OutputStream.class, String[].class);
        ByteArrayOutputStream errors = new ByteArrayOutputStream();
        int rc = (Integer) run.invoke(javac, null, errors, errors, new String[] {
            "-nowarn", "-encoding", "UTF-8", "-d", classes.getPath(),
            JAVA.resolve("HeldScroll.java").toString(), probeSource.getPath(),
        });
        assertEquals("the activity's requestScroll compiles as it is:\n" + errors + "\n" + source, 0, rc);
        URLClassLoader loader = new URLClassLoader(new URL[] {classes.toURI().toURL()}, null);
        probe = loader.loadClass("dev.herdr.android.EntryProbe");
    }

    /** Scrolls {@code steps} through the activity's entry against one target; what reached each sink. */
    @SuppressWarnings("unchecked")
    private static List<String> scroll(boolean present, boolean closed, long handle, boolean ready, boolean shell,
            int... steps) throws Exception {
        Object entry = probe.getConstructor().newInstance();
        probe.getMethod("target", boolean.class, boolean.class, long.class, boolean.class, boolean.class)
                .invoke(entry, present, closed, handle, ready, shell);
        Method scroll = probe.getMethod("scroll", int.class);
        for (int step : steps) {
            assertEquals("step " + step + " was taken", Boolean.TRUE, scroll.invoke(entry, step));
        }
        return (List<String>) probe.getField("went").get(entry);
    }

    private static final int[] DRAG = {7, -7};
    /** A fling's decaying steps, down then back up. */
    private static final int[] FLING = {12, 9, 6, 4, 2, 1, -12, -9, -6, -4, -2, -1};

    private static List<String> held(int... steps) {
        String[] went = new String[steps.length];
        for (int i = 0; i < steps.length; i++) went[i] = "held " + steps[i];
        return Arrays.asList(went);
    }

    @Test public void wolfAskOfflineScrolling_theActivitysEntryScrollsTheHeldFrameWhenNoPaneIsOpen() throws Exception {
        assertEquals(held(DRAG), scroll(false, false, 0, false, false, DRAG));
        assertEquals(held(FLING), scroll(false, false, 0, false, false, FLING));
    }

    @Test public void wolfAskOfflineScrolling_theActivitysEntryScrollsAClosedPanesHeldFrame() throws Exception {
        assertEquals(held(DRAG), scroll(true, true, 7, false, false, DRAG));
        assertEquals(held(FLING), scroll(true, true, 7, false, false, FLING));
        // Closed before it ever attached (no remote handle): still the held frame.
        assertEquals(held(DRAG), scroll(true, true, 0, false, false, DRAG));
    }

    @Test public void wolfAskOfflineScrolling_theActivitysEntryScrollsTheHeldFrameWhileAReconnectAttaches() throws Exception {
        assertEquals(held(DRAG), scroll(true, false, 7, false, false, DRAG));
        assertEquals(held(FLING), scroll(true, false, 7, false, false, FLING));
    }

    @Test public void wolfAskOfflineScrolling_theActivitysEntryScrollsALivePaneThroughItsSession() throws Exception {
        assertEquals(Arrays.asList("live 7", "live -7"), scroll(true, false, 7, true, false, DRAG));
    }
}
