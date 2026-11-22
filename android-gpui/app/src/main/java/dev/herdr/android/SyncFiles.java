package dev.herdr.android;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.function.LongSupplier;

/**
 * The sync engine's entries on disk ({@link SyncEngine.Store}): one file per key under
 * {@code noBackupFilesDir/sync-v1/<machine>/}, written tmp + fsync + rename. A file holds its
 * full key and is only ever read back for that same key, so a hash collision or a copied file
 * cannot show one machine's answer as another's.
 *
 * <p>Bounded by the {@link OfflineBudget}'s sync share: past {@code maxBytes} in all, the
 * least recently written files go first; a file not written for {@link OfflineBudget#GONE_MS}
 * goes on the next sweep; nothing is written under the free-space floor. Eviction only ever
 * touches these files. Every method does disk work (the engine's disk thread).
 */
final class SyncFiles implements SyncEngine.Store {
    static final String DIR = "sync-v1";

    private final File root;
    private final long maxBytes, freeFloorBytes;
    private final LongSupplier clock, freeBytes;

    SyncFiles(File root, long maxBytes, long freeFloorBytes, LongSupplier clock, LongSupplier freeBytes) {
        this.root = root;
        this.maxBytes = maxBytes;
        this.freeFloorBytes = freeFloorBytes;
        this.clock = clock;
        this.freeBytes = freeBytes;
    }

    /** The app's store, with the budget's sync share of what is free now. */
    static SyncFiles in(android.content.Context context) {
        File dir = new File(context.getApplicationContext().getNoBackupFilesDir(), DIR);
        OfflineBudget budget = OfflineBudget.forFree(dir.getParentFile().getUsableSpace());
        return new SyncFiles(dir, budget.syncBytes, budget.freeFloorBytes, System::currentTimeMillis,
                () -> dir.getParentFile().getUsableSpace());
    }

    @Override public SyncEngine.Entry load(SyncEngine.Key key) {
        File file = fileOf(key);
        if (!file.isFile() || file.length() > (long) key.kind.maxChars * 4 + 4096) return null;
        try {
            JSONObject o = new JSONObject(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
            if (o.optInt("v", 0) != 1 || !keyString(key).equals(o.optString("key", null))) return null;
            String text = o.optString("text", "");
            long at = o.optLong("at", 0);
            if (text.isEmpty() || text.length() > key.kind.maxChars || at <= 0) return null;
            return new SyncEngine.Entry(text, o.optString("fp", ""), at, o.optString("source", ""), SyncEngine.Entry.State.FRESH, null, 0);
        } catch (IOException | JSONException e) {
            return null;
        }
    }

    @Override public void save(SyncEngine.Key key, SyncEngine.Entry entry) {
        if (!entry.hasData() || entry.text.length() > key.kind.maxChars) return;
        if (freeBytes.getAsLong() < freeFloorBytes) return;
        byte[] bytes;
        try {
            bytes = new JSONObject().put("v", 1).put("key", keyString(key)).put("at", entry.atMs).put("fp", entry.fp)
                    .put("source", entry.source).put("text", entry.text).toString().getBytes(StandardCharsets.UTF_8);
        } catch (JSONException impossible) {
            return;
        }
        if (bytes.length > maxBytes) return;
        File file = fileOf(key);
        File dir = file.getParentFile();
        if (!dir.isDirectory() && !dir.mkdirs()) return;
        File tmp = new File(dir, file.getName() + ".tmp");
        try {
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                out.write(bytes);
                out.getFD().sync();
            }
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            if (!tmp.delete()) tmp.deleteOnExit();
            return;
        }
        sweep(file);
    }

    /** Old files go, then the least recently written ones while over the budget; never {@code keep}. */
    void sweep(File keep) {
        List<File> all = new ArrayList<>();
        File[] machines = root.listFiles(File::isDirectory);
        for (File m : machines == null ? new File[0] : machines) {
            File[] files = m.listFiles();
            if (files != null) all.addAll(Arrays.asList(files));
        }
        long now = clock.getAsLong(), total = 0;
        List<File> kept = new ArrayList<>();
        for (File f : all) {
            if (f.getName().endsWith(".tmp") && !f.equals(keep)) { delete(f); continue; }
            if (!f.equals(keep) && now - f.lastModified() > OfflineBudget.GONE_MS) { delete(f); continue; }
            kept.add(f);
            total += f.length();
        }
        kept.sort(Comparator.comparingLong(File::lastModified));
        for (File f : kept) {
            if (total <= maxBytes) break;
            if (f.equals(keep)) continue;
            total -= f.length();
            delete(f);
        }
    }

    private static void delete(File f) {
        if (!f.delete()) f.deleteOnExit();
    }

    File fileOf(SyncEngine.Key key) {
        return new File(new File(root, hash(key.machine.toString())), key.kind.name().toLowerCase(java.util.Locale.ROOT) + "-" + hash(key.name) + ".json");
    }

    private static String keyString(SyncEngine.Key key) {
        return key.machine + "\n" + key.kind + "\n" + key.name;
    }

    private static String hash(String s) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder();
            for (int i = 0; i < 12; i++) out.append(String.format("%02x", d[i]));
            return out.toString();
        } catch (NoSuchAlgorithmException impossible) {
            return Integer.toHexString(s.hashCode());
        }
    }
}
