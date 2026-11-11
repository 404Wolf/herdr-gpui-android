package dev.herdr.android;

import android.content.Context;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Executor;

/**
 * Each pane's last terminal grid on disk (see the native snapshot module), so a
 * cold start with no link draws the conversation as it last was, dimmed, and
 * it still scrolls. Saved whenever a session closes (a lost link, leaving the
 * screen); the newest few panes are kept.
 *
 * <p>Files live in the app's no-backup directory, named by a hash of the
 * computer and pane, and are written and read on this class's own thread.
 */
final class PaneSnapshots {
    static final int MAX_FILES = 16;
    static final long MAX_TOTAL_BYTES = 32L * 1024 * 1024;
    private static final String SUFFIX = ".snap";

    private static PaneSnapshots shared;

    static synchronized PaneSnapshots shared(Context context) {
        if (shared == null) {
            File dir = new File(context.getApplicationContext().getNoBackupFilesDir(), "panes");
            Executor thread = java.util.concurrent.Executors.newSingleThreadExecutor(task -> {
                Thread worker = new Thread(task, "herdr-snapshots");
                worker.setDaemon(true);
                worker.setPriority(Thread.MIN_PRIORITY);
                return worker;
            });
            shared = new PaneSnapshots(dir, thread);
        }
        return shared;
    }

    /** What a saved pane draws with, once read: its render id and when it was saved. */
    static final class Shown {
        final long renderId;
        final long savedAt;

        Shown(long renderId, long savedAt) {
            this.renderId = renderId;
            this.savedAt = savedAt;
        }
    }

    interface Then {
        void shown(Shown shown);
    }

    private final File dir;
    private final Executor thread;

    PaneSnapshots(File dir, Executor thread) {
        this.dir = dir;
        this.thread = thread;
    }

    /** The session that showed {@code pane} on {@code host} just closed: keep its grid. */
    void saveClosed(String host, String pane) {
        if (host == null || pane == null || pane.isEmpty()) return;
        thread.execute(() -> {
            if (!dir.isDirectory() && !dir.mkdirs()) return;
            try {
                if (RemoteNative.saveClosed(pane, file(host, pane).getPath())) prune();
            } catch (RuntimeException | LinkageError unsaved) {
                // The previous copy, if any, stays: a failed save never truncates it.
            }
        });
    }

    /** Reads {@code pane}'s saved grid into the held frame; {@code then} runs on this thread if there was one. */
    void open(String host, String pane, Then then) {
        if (host == null || pane == null || pane.isEmpty()) return;
        thread.execute(() -> {
            File saved = file(host, pane);
            if (!saved.isFile()) return;
            long id;
            try {
                id = RemoteNative.openSaved(saved.getPath());
            } catch (RuntimeException | LinkageError unreadable) {
                saved.delete();
                return;
            }
            if (id > 0) then.shown(new Shown(id, saved.lastModified()));
        });
    }

    File file(String host, String pane) {
        return new File(dir, name(host, pane));
    }

    /** A fixed-length name for a computer and pane: ids may hold any character. */
    static String name(String host, String pane) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest((host + "\u0000" + pane).getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder();
            for (int i = 0; i < 16; i++) out.append(String.format("%02x", digest[i] & 0xff));
            return out.append(SUFFIX).toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private void prune() {
        File[] files = dir.listFiles((d, n) -> n.endsWith(SUFFIX));
        if (files == null) return;
        for (File old : stale(Arrays.asList(files), MAX_FILES, MAX_TOTAL_BYTES)) old.delete();
    }

    /** Beyond the newest {@code maxFiles}, or past {@code maxBytes} in all, oldest first to go. */
    static List<File> stale(List<File> files, int maxFiles, long maxBytes) {
        List<File> newest = new ArrayList<>(files);
        newest.sort((a, b) -> Long.compare(b.lastModified(), a.lastModified()));
        List<File> out = new ArrayList<>();
        long total = 0;
        for (int i = 0; i < newest.size(); i++) {
            File file = newest.get(i);
            total += file.length();
            if (i >= maxFiles || total > maxBytes) out.add(file);
        }
        return out;
    }
}
