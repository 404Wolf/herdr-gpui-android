package dev.herdr.android;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Images shared to Herdr from the Android share sheet, waiting for the user to pick an agent for
 * them. They are validated and copied to the app's own cache first (the sender's permission ends
 * with the share), kept in memory only, and forgotten after {@link #KEEP_MS}. Nothing here uploads:
 * only the user's explicit choice of an agent does. Main thread.
 */
final class SharedImages {
    static final long KEEP_MS = 10 * 60 * 1000L;

    private static final List<File> files = new ArrayList<>();
    private static long offeredAt;
    private static int skipped;
    private static int generation;

    private SharedImages() { }

    /** Replaces whatever waited: the newest share is the one meant. {@code unusable}: items refused or unreadable. */
    static synchronized void offer(List<File> shared, int unusable, long now) {
        discard();
        files.addAll(shared.subList(0, Math.min(shared.size(), ImageAttach.MAX_IMAGES)));
        for (int i = ImageAttach.MAX_IMAGES; i < shared.size(); i++) shared.get(i).delete();
        skipped = unusable + Math.max(0, shared.size() - ImageAttach.MAX_IMAGES);
        offeredAt = now;
        generation++;
    }

    /** Which share this is: a new one has a new number, so it is asked about once. */
    static synchronized int generation() {
        return generation;
    }

    static synchronized boolean waiting(long now) {
        expire(now);
        return !files.isEmpty();
    }

    static synchronized int count(long now) {
        expire(now);
        return files.size();
    }

    /** What Home says in its content while a share waits (or was refused); null when nothing to say. */
    static synchronized String notice(long now) {
        expire(now);
        if (files.isEmpty() && skipped == 0) return null;
        StringBuilder words = new StringBuilder();
        if (!files.isEmpty()) {
            words.append(files.size() == 1 ? "1 shared image is waiting" : files.size() + " shared images are waiting")
                    .append(": pick an agent to attach ").append(files.size() == 1 ? "it" : "them").append(" to.");
        }
        if (skipped > 0) {
            if (words.length() > 0) words.append(' ');
            words.append(skipped == 1 ? "1 shared item could not be used (not a readable image, or not shared with Herdr)."
                    : skipped + " shared items could not be used (not readable images, or not shared with Herdr).");
        }
        return words.toString();
    }

    private static void expire(long now) {
        if ((!files.isEmpty() || skipped > 0) && now - offeredAt > KEEP_MS) discard();
    }

    /**
     * Hands over the waiting images of share number {@code forGeneration} only (the caller deletes
     * each file when done): a confirmation made for an older share takes nothing of a newer one.
     */
    static synchronized List<File> take(long now, int forGeneration) {
        if (forGeneration != generation) return new ArrayList<>();
        return take(now);
    }

    /** The words about a share whose items were all refused (nothing waits), once; null otherwise. */
    static synchronized String takeNotice(long now) {
        expire(now);
        if (!files.isEmpty() || skipped == 0) return null;
        String words = notice(now);
        skipped = 0;
        return words;
    }

    /** Hands over the waiting images (the caller deletes each file when done); none when expired. */
    static synchronized List<File> take(long now) {
        if (!waiting(now)) return new ArrayList<>();
        List<File> out = new ArrayList<>(files);
        files.clear();
        skipped = 0;
        return out;
    }

    static synchronized void discard() {
        for (File file : files) file.delete();
        files.clear();
        skipped = 0;
    }
}
