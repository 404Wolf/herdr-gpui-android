package dev.herdr.android;

import android.content.Context;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executor;

/**
 * Each pane's last terminal state on disk (see the native snapshot module),
 * so the conversation reads and scrolls with no link, even after the app was
 * killed (item 77, Wolf's ask 2). A full-screen program's copy (Claude Code)
 * is what the phone watched of it, so it scrolls to the top of what the
 * phone has, not one screen. Saved whenever a session closes (a lost link,
 * leaving the screen), before every pane switch, and as a checkpoint of the
 * pane on screen (PaneCheckpoint). A copy is for reading with no link only:
 * the live pane replaces it as soon as it paints.
 *
 * <p>Copies belong to a computer as {@link #computer} names it: the saved
 * profile and its connection revision, as they were when the session was
 * connected. Editing a profile to point at another computer keeps its id but
 * changes its revision, so the old computer's copies are never the new one's,
 * even for a save queued before the edit.
 *
 * <p>Every file is sealed ({@link CacheSeal}): nothing a terminal printed is
 * on disk as plain text. Files live in {@code no_backup/cache-v2/panes},
 * named by a hash of the computer and pane, and are written and read on this
 * class's own thread. The cache keeps at most {@link #MAX_FILES} panes and
 * {@link #MAX_TOTAL_BYTES} in all, newest first by when each was last saved
 * or opened (a copy's mtime is when it was saved; an empty {@code .used} file
 * beside it, when it was last opened); pinned panes (the one on screen, the Deacon and PMs, panes with
 * messages waiting) go last, and nothing unpinned older than {@link
 * #MAX_AGE_MS} stays. Version 1 copies (plain, {@code no_backup/panes}) are
 * sealed into the cache once, then deleted.
 */
final class PaneSnapshots {
    static final int MAX_FILES = 48;
    static final long MAX_TOTAL_BYTES = 64L * 1024 * 1024;
    static final long MAX_AGE_MS = 14L * 24 * 60 * 60 * 1000;
    /** A copy read back is at most this (the native side bounds it again). */
    static final int MAX_FILE_BYTES = 4 * 1024 * 1024 + 8192;
    static final String SUFFIX = ".snap2";
    private static final String OLD_SUFFIX = ".snap";

    /** The native side: a pane's copy, and drawing one read back. */
    interface Native {
        byte[] copy(long handle, String pane);
        long open(byte[] copy);
    }

    static final Native NATIVE = new Native() {
        @Override public byte[] copy(long handle, String pane) { return RemoteNative.copyPane(handle, pane); }
        @Override public long open(byte[] copy) { return RemoteNative.openSaved(copy); }
    };

    /**
     * The computer a copy belongs to: {@code host}'s profile id and connection
     * revision. Null (nothing is saved or opened) when either is unknown.
     */
    static String computer(SavedRemoteHost host) {
        return host == null ? null : computer(host.id, host.connectionRevision);
    }

    static String computer(String profile, String revision) {
        if (profile == null || profile.isEmpty() || revision == null || revision.isEmpty()) return null;
        return profile + "\u0000" + revision;
    }

    private static PaneSnapshots shared;

    static synchronized PaneSnapshots shared(Context context) {
        if (shared == null) {
            Context app = context.getApplicationContext();
            File dir = new File(CacheSeal.dir(app), "panes");
            File old = new File(app.getNoBackupFilesDir(), "panes");
            Executor thread = java.util.concurrent.Executors.newSingleThreadExecutor(task -> {
                Thread worker = new Thread(task, "herdr-snapshots");
                worker.setDaemon(true);
                worker.setPriority(Thread.MIN_PRIORITY);
                return worker;
            });
            shared = new PaneSnapshots(dir, thread, () -> CacheSeal.shared(app), NATIVE);
            shared.migrate(old);
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

    interface Seal {
        /** The cache's seal, or null when nothing may be cached. Worker thread. */
        CacheSeal get();
    }

    private final File dir;
    private final Executor thread;
    private final Seal seal;
    private final Native natives;
    /** File names that go last when the cache is over its bounds. */
    private volatile Set<String> pinned = Collections.emptySet();

    PaneSnapshots(File dir, Executor thread, Seal seal, Native natives) {
        this.dir = dir;
        this.thread = thread;
        this.seal = seal;
        this.natives = natives;
    }

    /**
     * Keeps {@code pane}'s copy on disk, sealed, as session {@code handle}
     * showed it: its live grid while it shows the pane, else the copy kept
     * when a switch or a close left it. {@code computer} is the one that
     * session was connected to ({@link #computer}, taken when it connected):
     * every core has its own t1, so a save never takes another session's
     * grid, and a profile edited since never gets it. A failed save never
     * truncates the previous copy.
     */
    void save(String computer, String pane, long handle) {
        if (computer == null || pane == null || pane.isEmpty()) return;
        final File into = file(computer, pane);
        thread.execute(() -> {
            CacheSeal sealer = seal.get();
            if (sealer == null || !dir.isDirectory() && !dir.mkdirs()) return;
            try {
                byte[] copy = natives.copy(handle, pane);
                if (copy == null) return;
                sealer.write(into, copy);
                prune(System.currentTimeMillis());
            } catch (IOException | RuntimeException | LinkageError unsaved) {
                // The previous copy, if any, stays.
            }
        });
    }

    interface Missing {
        void missing();
    }

    /** Reads {@code pane}'s saved grid into the held frame; {@code then} runs on this thread if there was one. */
    void open(String computer, String pane, Then then) {
        open(computer, pane, then, () -> { });
    }

    /**
     * As {@link #open(String, String, Then)}, and {@code missing} runs on this
     * thread instead when there is no readable copy. Opens run in the order
     * asked, so the last pane picked is the one drawn. Opening counts as using
     * the copy: it is kept ahead of panes not looked at since.
     */
    void open(String computer, String pane, Then then, Missing missing) {
        if (computer == null || pane == null || pane.isEmpty()) {
            if (pane != null && !pane.isEmpty()) thread.execute(missing::missing);
            return;
        }
        final File saved = file(computer, pane);
        thread.execute(() -> {
            byte[] copy = read(saved);
            if (copy == null) {
                missing.missing();
                return;
            }
            long savedAt = saved.lastModified();
            long id;
            try {
                id = natives.open(copy);
            } catch (RuntimeException | LinkageError unreadable) {
                saved.delete();
                missing.missing();
                return;
            }
            if (id > 0) {
                // Opening counts as using it, for eviction; its own time stays
                // the time it was saved, which the pane's "cached" label shows.
                touch(used(saved), System.currentTimeMillis());
                then.shown(new Shown(id, savedAt));
            } else {
                missing.missing();
            }
        });
    }

    /** {@code (computer, pane)} pairs kept last when the cache is over its bounds. */
    void pin(List<String[]> panes) {
        Set<String> names = new HashSet<>();
        for (String[] pane : panes) {
            if (pane.length == 2 && pane[0] != null && pane[1] != null) names.add(name(pane[0], pane[1]));
        }
        pinned = Collections.unmodifiableSet(names);
    }

    /** {@code saved}'s copy, opened; null when there is none or it cannot be read (then it is deleted). */
    private byte[] read(File saved) {
        CacheSeal sealer = seal.get();
        if (sealer == null || !saved.isFile()) return null;
        return sealer.read(saved, MAX_FILE_BYTES);
    }

    File file(String computer, String pane) {
        return new File(dir, name(computer, pane));
    }

    /** A fixed-length name for a computer and pane: ids may hold any character. */
    static String name(String host, String pane) {
        return hash(host, pane) + SUFFIX;
    }

    private static String hash(String host, String pane) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest((host + "\u0000" + pane).getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder();
            for (int i = 0; i < 16; i++) out.append(String.format("%02x", digest[i] & 0xff));
            return out.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    /**
     * Seals the plain version 1 copies in {@code old} into the cache (same
     * names, so the same panes), then deletes them and their folder. A copy
     * that cannot be sealed is deleted all the same: plain text must not stay.
     */
    void migrate(File old) {
        thread.execute(() -> {
            File[] files = old.listFiles();
            if (files == null) return;
            CacheSeal sealer = seal.get();
            for (File file : files) {
                String base = file.getName();
                if (sealer != null && base.endsWith(OLD_SUFFIX) && (dir.isDirectory() || dir.mkdirs())) {
                    File into = new File(dir, base.substring(0, base.length() - OLD_SUFFIX.length()) + SUFFIX);
                    try {
                        if (!into.isFile()) {
                            long savedAt = file.lastModified();
                            sealer.write(into, CacheSeal.readBytes(file, MAX_FILE_BYTES));
                            into.setLastModified(savedAt);
                        }
                    } catch (IOException tooBig) {
                        // Deleted below, like every version 1 file.
                    }
                }
                file.delete();
            }
            old.delete();
            prune(System.currentTimeMillis());
        });
    }

    private void prune(long now) {
        File[] all = dir.listFiles();
        if (all == null) return;
        List<File> files = new ArrayList<>();
        for (File file : all) {
            String name = file.getName();
            if (name.endsWith(SUFFIX)) {
                files.add(file);
            } else if (name.endsWith(".tmp")) {
                // Every write runs on this thread and is done by now: a .tmp
                // left over is from a write that failed or a process that died.
                file.delete();
            } else if (name.endsWith(USED) && !new File(dir, name.substring(0, name.length() - USED.length())).isFile()) {
                file.delete();
            }
        }
        Set<String> keep = pinned;
        for (File old : stale(files, keep, MAX_FILES, MAX_TOTAL_BYTES, now, MAX_AGE_MS)) {
            old.delete();
            used(old).delete();
        }
    }

    /** Beside a copy, an empty file whose mtime is when the copy was last opened. */
    private static final String USED = ".used";

    static File used(File file) {
        return new File(file.getPath() + USED);
    }

    /** When {@code file} was last saved or opened: what eviction goes by. */
    static long recency(File file) {
        return Math.max(file.lastModified(), used(file).lastModified());
    }

    private static void touch(File file, long at) {
        try {
            if (file.isFile() || file.createNewFile()) file.setLastModified(at);
        } catch (IOException ignored) {
            // Only eviction order is affected.
        }
    }

    /**
     * What goes (newest of them first): beyond the newest {@code maxFiles}, past
     * {@code maxBytes} in all, or older than {@code maxAge}; pinned files
     * (by name) are counted first and never go.
     */
    static List<File> stale(List<File> files, Set<String> pinned, int maxFiles, long maxBytes, long now, long maxAge) {
        List<File> order = new ArrayList<>(files);
        order.sort((a, b) -> {
            boolean pa = pinned.contains(a.getName()), pb = pinned.contains(b.getName());
            if (pa != pb) return pa ? -1 : 1;
            return Long.compare(recency(b), recency(a));
        });
        List<File> out = new ArrayList<>();
        long total = 0;
        int kept = 0;
        for (File file : order) {
            boolean pin = pinned.contains(file.getName());
            long size = file.length();
            boolean over = kept >= maxFiles || total + size > maxBytes || now - recency(file) > maxAge;
            if (over && !pin) {
                out.add(file);
                continue;
            }
            total += size;
            kept++;
        }
        return out;
    }

    /** {@code stale} with no pins and no age limit (the version 1 bounds' shape). */
    static List<File> stale(List<File> files, int maxFiles, long maxBytes) {
        return stale(files, Collections.emptySet(), maxFiles, maxBytes, Long.MIN_VALUE / 2, Long.MAX_VALUE);
    }

    /** All the cache's bytes on disk now, for the setting that shows its size. */
    long size() {
        File[] files = dir.listFiles((d, n) -> n.endsWith(SUFFIX));
        long total = 0;
        if (files != null) for (File file : files) total += file.length();
        return total;
    }
}
