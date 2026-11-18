package dev.herdr.android;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.util.Arrays;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Seals the offline cache at rest (item 77, ask 2): pane transcripts and the
 * Fleet list can hold secrets a terminal printed, so they never reach the
 * disk as plain text. AES-256-GCM with a random data key; the data key is
 * kept wrapped by a non-exportable Android Keystore key (no user
 * authentication, so a save on the lock screen works) and unwrapped once
 * per process. Each file is bound to its place by its associated data, so a
 * copy moved to another pane's name does not open.
 *
 * <p>Sealed bytes: {@code "HSC2"}, a 12-byte IV, then the ciphertext and tag.
 * If the Keystore key is lost (app data cleared, a reset), nothing opens and
 * the caller deletes what it cannot read and fetches it again.
 */
final class CacheSeal {
    static final String DIR = "cache-v2";
    private static final String ALIAS = "herdr.offline-cache.v2";
    private static final String WRAPPED = "key.wrapped";
    private static final byte[] MAGIC = {'H', 'S', 'C', '2'};
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final SecureRandom RANDOM = new SecureRandom();

    private static CacheSeal shared;

    private final SecretKey key;

    /** A seal with this data key (the Keystore-backed one, or a test's). */
    CacheSeal(SecretKey key) {
        this.key = key;
    }

    /** A fresh random data key, for a new cache (and tests). */
    static SecretKey newKey() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        SecretKey key = new SecretKeySpec(bytes, "AES");
        Arrays.fill(bytes, (byte) 0);
        return key;
    }

    /** The app's cache directory, {@code no_backup/cache-v2}. */
    static File dir(Context context) {
        return new File(context.getApplicationContext().getNoBackupFilesDir(), DIR);
    }

    /**
     * The app's seal: its data key unwrapped by the Keystore, or a new one
     * made and wrapped. Null when the Keystore cannot be used at all; then
     * nothing is cached. Disk and Keystore work: never on the UI thread.
     */
    static synchronized CacheSeal shared(Context context) {
        if (shared != null) return shared;
        File dir = dir(context);
        File wrapped = new File(dir, WRAPPED);
        try {
            if (!dir.isDirectory() && !dir.mkdirs()) return null;
            SecretKey master = master();
            SecretKey key = null;
            if (wrapped.isFile()) {
                try {
                    key = unwrap(master, readBytes(wrapped, 256));
                } catch (GeneralSecurityException | IOException lost) {
                    // A key that no longer opens: everything sealed with it is unreadable.
                    key = null;
                }
            }
            if (key == null) {
                clear(dir);
                key = newKey();
                writeAtomically(wrapped, wrap(master, key));
            }
            shared = new CacheSeal(key);
        } catch (GeneralSecurityException | IOException | RuntimeException unavailable) {
            shared = null;
        }
        return shared;
    }

    /** Seals {@code plain}, bound to {@code place} (a file's name). */
    byte[] seal(byte[] plain, String place) throws GeneralSecurityException {
        byte[] iv = new byte[IV_BYTES];
        RANDOM.nextBytes(iv);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
        cipher.updateAAD(aad(place));
        byte[] body = cipher.doFinal(plain);
        byte[] out = new byte[MAGIC.length + IV_BYTES + body.length];
        System.arraycopy(MAGIC, 0, out, 0, MAGIC.length);
        System.arraycopy(iv, 0, out, MAGIC.length, IV_BYTES);
        System.arraycopy(body, 0, out, MAGIC.length + IV_BYTES, body.length);
        return out;
    }

    /** Opens what {@link #seal} made for {@code place}; anything else throws. */
    byte[] open(byte[] sealed, String place) throws GeneralSecurityException {
        if (sealed == null || sealed.length < MAGIC.length + IV_BYTES + TAG_BITS / 8
                || !Arrays.equals(Arrays.copyOf(sealed, MAGIC.length), MAGIC)) {
            throw new GeneralSecurityException("not a sealed cache file");
        }
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key,
                new GCMParameterSpec(TAG_BITS, sealed, MAGIC.length, IV_BYTES));
        cipher.updateAAD(aad(place));
        int at = MAGIC.length + IV_BYTES;
        return cipher.doFinal(sealed, at, sealed.length - at);
    }

    /** Seals {@code plain} into {@code file} (bound to its name), atomically. */
    void write(File file, byte[] plain) throws IOException {
        try {
            writeAtomically(file, seal(plain, file.getName()));
        } catch (GeneralSecurityException unsealable) {
            throw new IOException(unsealable);
        }
    }

    /**
     * {@code file}'s contents, opened; null when there is none. A file that
     * cannot be opened (damaged, another key, plain text) is deleted.
     */
    byte[] read(File file, int maxBytes) {
        if (!file.isFile()) return null;
        try {
            return open(readBytes(file, maxBytes + MAGIC.length + IV_BYTES + TAG_BITS / 8), file.getName());
        } catch (GeneralSecurityException | IOException unreadable) {
            file.delete();
            return null;
        }
    }

    private static byte[] aad(String place) {
        return ("herdr-offline-cache-v2\u0000" + place).getBytes(StandardCharsets.UTF_8);
    }

    static byte[] readBytes(File file, int maxBytes) throws IOException {
        long length = file.length();
        if (length > maxBytes) throw new IOException(file.getName() + " is larger than a cache file");
        byte[] bytes = new byte[(int) length];
        try (InputStream in = new FileInputStream(file)) {
            int at = 0;
            for (int n; at < bytes.length && (n = in.read(bytes, at, bytes.length - at)) > 0; ) at += n;
            return at == bytes.length ? bytes : Arrays.copyOf(bytes, at);
        }
    }

    /** Replaces {@code file} through a temporary file, so a reader never sees half a write. */
    static void writeAtomically(File file, byte[] bytes) throws IOException {
        File tmp = new File(file.getPath() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(bytes);
            out.getFD().sync();
        }
        if (!tmp.renameTo(file)) {
            tmp.delete();
            throw new IOException("Could not replace " + file.getName());
        }
    }

    /** Everything sealed in {@code dir} (and its folders), when its key is gone. */
    private static void clear(File dir) {
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File file : files) {
            if (file.isDirectory()) clear(file);
            file.delete();
        }
    }

    private static SecretKey master() throws GeneralSecurityException, IOException {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        if (store.containsAlias(ALIAS)) return (SecretKey) store.getKey(ALIAS, null);
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).setRandomizedEncryptionRequired(true).build());
        return generator.generateKey();
    }

    private static byte[] wrap(SecretKey master, SecretKey key) throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, master);
        cipher.updateAAD(aad(WRAPPED));
        byte[] iv = cipher.getIV();
        byte[] body = cipher.doFinal(key.getEncoded());
        byte[] out = new byte[1 + iv.length + body.length];
        out[0] = (byte) iv.length;
        System.arraycopy(iv, 0, out, 1, iv.length);
        System.arraycopy(body, 0, out, 1 + iv.length, body.length);
        return out;
    }

    private static SecretKey unwrap(SecretKey master, byte[] wrapped) throws GeneralSecurityException {
        if (wrapped.length < 2 || wrapped[0] <= 0 || 1 + wrapped[0] >= wrapped.length) {
            throw new GeneralSecurityException("damaged wrapped key");
        }
        int ivLength = wrapped[0];
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, master, new GCMParameterSpec(TAG_BITS, wrapped, 1, ivLength));
        cipher.updateAAD(aad(WRAPPED));
        byte[] raw = cipher.doFinal(wrapped, 1 + ivLength, wrapped.length - 1 - ivLength);
        try {
            if (raw.length != 32) throw new GeneralSecurityException("wrapped key has the wrong size");
            return new SecretKeySpec(raw, "AES");
        } finally {
            Arrays.fill(raw, (byte) 0);
        }
    }
}
