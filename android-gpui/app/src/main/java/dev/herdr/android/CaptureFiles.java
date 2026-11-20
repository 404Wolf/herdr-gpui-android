package dev.herdr.android;

import java.io.File;
import java.io.IOException;

/**
 * The camera's private capture files, in the app's cache and never in the media store, so a shot
 * cannot appear in the gallery or outlive the app's cleanup. {@link CaptureProvider} serves them to
 * the camera app through a URI granted for one capture.
 */
final class CaptureFiles {
    static final String DIR = "capture";

    private CaptureFiles() { }

    static File dir(File cache) {
        return new File(cache, DIR);
    }

    /** A new empty capture file; {@link IOException} when the cache cannot hold one. */
    static File create(File cache, long now) throws IOException {
        File dir = dir(cache);
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("no capture folder");
        File file = new File(dir, "herdr-camera-" + now + ".jpg");
        if (!file.createNewFile() && !file.isFile()) throw new IOException("no capture file");
        return file;
    }

    /** The capture file named {@code name} (one plain name, no path), or null. */
    static File resolve(File cache, String name) {
        if (name == null || !name.matches("[A-Za-z0-9._-]{1,100}") || name.contains("..")) return null;
        File file = new File(dir(cache), name);
        return file.getParentFile().equals(dir(cache)) ? file : null;
    }

    /** Removes every capture file: at startup (a killed app's leftovers) and after each use. */
    static void clear(File cache) {
        File[] files = dir(cache).listFiles();
        if (files != null) for (File file : files) file.delete();
    }
}
