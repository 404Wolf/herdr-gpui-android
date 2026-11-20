package dev.herdr.android;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Images shared to Herdr from the Android share sheet, waiting for an agent to attach them to. They
 * are copied to the app's own cache first (the sender's permission ends with the share), kept in
 * memory only, and forgotten after {@link #KEEP_MS}: a share never attaches to something opened
 * hours later. Main thread.
 */
final class SharedImages {
    static final long KEEP_MS = 10 * 60 * 1000L;

    private static final List<File> files = new ArrayList<>();
    private static long offeredAt;

    private SharedImages() { }

    /** Replaces whatever waited: the newest share is the one meant. */
    static synchronized void offer(List<File> shared, long now) {
        discard();
        files.addAll(shared.subList(0, Math.min(shared.size(), ImageAttach.MAX_IMAGES)));
        for (int i = ImageAttach.MAX_IMAGES; i < shared.size(); i++) shared.get(i).delete();
        offeredAt = now;
    }

    static synchronized boolean waiting(long now) {
        if (!files.isEmpty() && now - offeredAt > KEEP_MS) discard();
        return !files.isEmpty();
    }

    /** Hands over the waiting images (the caller deletes each file when done); none when expired. */
    static synchronized List<File> take(long now) {
        if (!waiting(now)) return new ArrayList<>();
        List<File> out = new ArrayList<>(files);
        files.clear();
        return out;
    }

    static synchronized void discard() {
        for (File file : files) file.delete();
        files.clear();
    }
}
