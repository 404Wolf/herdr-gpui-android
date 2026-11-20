package dev.herdr.android;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.AtomicFile;
import dev.herdr.android.core.QuickCommand;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import org.json.JSONArray;
import org.json.JSONObject;

/** Atomic, authenticated encrypted profiles; a stale UI can never retarget or retrust a host. */
final class RemoteHostStore {
    private static final String ALIAS = "herdr.remote-hosts.v1";
    private static final byte[] AAD = "herdr-remote-hosts-v1".getBytes(StandardCharsets.UTF_8);
    private static final int MAX_BYTES = 2 * 1024 * 1024;
    static final int MAX_PROFILES = 32;
    private static final Object LOCK = new Object();
    /** Published under LOCK after a read or successful write, for I/O-free attachment checks. */
    private static volatile Map<String, String> connectionRevisions = java.util.Collections.emptyMap();
    private final AtomicFile file;

    RemoteHostStore(Context context) {
        file = new AtomicFile(new File(context.getNoBackupFilesDir(), "remote-hosts-v1.enc"));
    }

    static String connectionRevision(String hostId) {
        return connectionRevisions.get(hostId);
    }

    private static void publishRevisions(List<SavedRemoteHost> values) {
        Map<String, String> revisions = new HashMap<>();
        for (SavedRemoteHost host : values) revisions.put(host.id, host.connectionRevision);
        connectionRevisions = revisions;
    }

    List<SavedRemoteHost> read() throws Exception {
        synchronized (LOCK) { return readLocked(); }
    }

    SavedRemoteHost requireCurrent(SavedRemoteHost expected) throws Exception {
        synchronized (LOCK) {
            List<SavedRemoteHost> values = readLocked();
            return values.get(findExpected(values, expected));
        }
    }

    SavedRemoteHost saveProfile(SavedRemoteHost expected, SavedRemoteHost draft) throws Exception {
        synchronized (LOCK) {
            List<SavedRemoteHost> values = readLocked();
            SavedRemoteHost saved = replaceProfile(values, expected, draft);
            writeLocked(values);
            return saved;
        }
    }

    SavedRemoteHost trust(SavedRemoteHost expected, String fingerprint) throws Exception {
        synchronized (LOCK) {
            List<SavedRemoteHost> values = readLocked();
            SavedRemoteHost saved = replaceTrust(values, expected, fingerprint);
            writeLocked(values);
            return saved;
        }
    }

    SavedRemoteHost forgetTrust(SavedRemoteHost expected) throws Exception {
        synchronized (LOCK) {
            List<SavedRemoteHost> values = readLocked();
            int index = findExpected(values, expected);
            SavedRemoteHost current = values.get(index);
            SavedRemoteHost saved = current.copy(current.name, "", current.shortcuts, current.quickCommands, null);
            values.set(index, saved);
            writeLocked(values);
            return saved;
        }
    }

    void delete(SavedRemoteHost expected) throws Exception {
        synchronized (LOCK) {
            List<SavedRemoteHost> values = readLocked();
            values.remove(findExpected(values, expected));
            writeLocked(values);
        }
    }

    SavedRemoteHost updateQuickCommands(SavedRemoteHost expected, List<QuickCommand> commands) throws Exception {
        synchronized (LOCK) {
            List<SavedRemoteHost> values = readLocked();
            SavedRemoteHost saved = replaceQuickCommands(values, expected, commands);
            writeLocked(values);
            return saved;
        }
    }

    SavedRemoteHost updateShortcuts(SavedRemoteHost expected, String shortcuts) throws Exception {
        synchronized (LOCK) {
            List<SavedRemoteHost> values = readLocked();
            SavedRemoteHost saved = replaceShortcuts(values, expected, shortcuts);
            writeLocked(values);
            return saved;
        }
    }

    /**
     * Adds the hosts of a file that are not saved yet, all or none. Rechecked under
     * the lock, so a list changed since the import was planned cannot gain duplicates.
     * Returns how many were added.
     */
    int importProfiles(List<SavedRemoteHost> incoming) throws Exception {
        synchronized (LOCK) {
            List<SavedRemoteHost> values = readLocked();
            List<SavedRemoteHost> added = newProfiles(values, incoming);
            if (added.isEmpty()) return 0;
            values.addAll(added);
            writeLocked(values);
            return added.size();
        }
    }

    /**
     * The hosts of `incoming` that `saved` does not already hold, each once, in file
     * order. A host is the same one when it reaches the same account and session, so
     * a saved profile, with its trust, always wins over a file's copy.
     */
    static List<SavedRemoteHost> newProfiles(List<SavedRemoteHost> saved, List<SavedRemoteHost> incoming) {
        List<SavedRemoteHost> added = new ArrayList<>();
        for (SavedRemoteHost host : incoming) {
            if (!containsIdentity(saved, host) && !containsIdentity(added, host)) added.add(host);
        }
        if (saved.size() + added.size() > MAX_PROFILES) {
            throw new IllegalArgumentException("Importing " + added.size() + " more hosts would pass the limit of "
                + MAX_PROFILES + "; delete some first");
        }
        return added;
    }

    private static boolean containsIdentity(List<SavedRemoteHost> values, SavedRemoteHost host) {
        for (SavedRemoteHost item : values) if (item.sameIdentity(host)) return true;
        return false;
    }

    static SavedRemoteHost replaceShortcuts(List<SavedRemoteHost> values, SavedRemoteHost expected,
            String shortcuts) {
        dev.herdr.android.core.Shortcut.parseConfiguration(shortcuts);
        int index = findExpected(values, expected);
        SavedRemoteHost current = values.get(index);
        SavedRemoteHost saved = current.copy(current.name, current.expectedFingerprint, shortcuts,
                current.quickCommands, current.connectionRevision);
        values.set(index, saved);
        return saved;
    }

    static SavedRemoteHost replaceProfile(List<SavedRemoteHost> values, SavedRemoteHost expected,
            SavedRemoteHost draft) {
        if (draft == null) throw new IllegalArgumentException("Profile is required");
        if (expected == null) {
            if (values.size() >= MAX_PROFILES) {
                throw new IllegalArgumentException("At most " + MAX_PROFILES + " remote profiles can be saved");
            }
            for (SavedRemoteHost item : values) if (item.id.equals(draft.id)) {
                throw new IllegalStateException("This profile already exists");
            }
            SavedRemoteHost saved = draft.copy(draft.name, "", draft.shortcuts, draft.quickCommands, null);
            values.add(saved);
            return saved;
        }
        int index = findExpected(values, expected);
        SavedRemoteHost current = values.get(index);
        boolean identityChanged = !current.sameIdentity(draft);
        SavedRemoteHost saved = new SavedRemoteHost(current.id, draft.name, draft.transport, draft.host,
            draft.port, draft.endpointId, draft.endpointTicket, draft.relayUrl, draft.username, draft.session,
            identityChanged ? "" : current.expectedFingerprint, current.shortcuts, current.quickCommands,
            identityChanged ? null : current.connectionRevision);
        values.set(index, saved);
        return saved;
    }

    static SavedRemoteHost replaceTrust(List<SavedRemoteHost> values, SavedRemoteHost expected, String fingerprint) {
        String pin = SavedRemoteHost.fingerprint(fingerprint);
        if (pin.isEmpty()) throw new IllegalArgumentException("SSH fingerprint is required");
        int index = findExpected(values, expected);
        SavedRemoteHost current = values.get(index);
        if (!current.expectedFingerprint.isEmpty()) throw new IllegalStateException("This host is already trusted; changed keys are blocked");
        SavedRemoteHost saved = current.copy(current.name, pin, current.shortcuts, current.quickCommands, null);
        values.set(index, saved);
        return saved;
    }

    static SavedRemoteHost replaceQuickCommands(List<SavedRemoteHost> values, SavedRemoteHost expected,
            List<QuickCommand> commands) {
        List<QuickCommand> validated = QuickCommand.validatedCopy(commands);
        int index = findExpected(values, expected);
        SavedRemoteHost current = values.get(index);
        SavedRemoteHost saved = current.copy(current.name, current.expectedFingerprint, current.shortcuts,
            validated, current.connectionRevision);
        values.set(index, saved);
        return saved;
    }

    static int findExpected(List<SavedRemoteHost> values, SavedRemoteHost expected) {
        if (expected != null) for (int i = 0; i < values.size(); i++) {
            SavedRemoteHost current = values.get(i);
            if (current.id.equals(expected.id)) {
                if (!current.matchesConnection(expected)) throw new IllegalStateException("Profile or SSH trust changed; reopen the remote profile");
                return i;
            }
        }
        throw new IllegalStateException("Remote profile no longer exists; reopen the profile list");
    }

    private List<SavedRemoteHost> readLocked() throws Exception {
        byte[] bytes;
        try (FileInputStream stream = file.openRead()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int count;
            while ((count = stream.read(buffer)) != -1) {
                if (count > MAX_BYTES - out.size()) throw new IllegalStateException("Saved remote profiles are too large");
                out.write(buffer, 0, count);
            }
            bytes = out.toByteArray();
        } catch (FileNotFoundException missing) {
            File base = file.getBaseFile();
            if (base.exists() || new File(base.getPath() + ".bak").exists()
                    || new File(base.getPath() + ".new").exists()) throw missing;
            publishRevisions(java.util.Collections.emptyList());
            return new ArrayList<>();
        }
        if (bytes.length < 30 || bytes[0] != 1) throw new IllegalStateException("Unsupported remote profile format");
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key(false), new GCMParameterSpec(128, Arrays.copyOfRange(bytes, 1, 13)));
        cipher.updateAAD(AAD);
        byte[] plaintext = cipher.doFinal(bytes, 13, bytes.length - 13);
        try {
            JSONObject document = new JSONObject(new String(plaintext, StandardCharsets.UTF_8));
            if (document.getInt("version") != 1) throw new IllegalStateException("Unsupported remote profile version");
            JSONArray array = document.getJSONArray("hosts");
            if (array.length() > 32) throw new IllegalStateException("Too many remote profiles");
            List<SavedRemoteHost> values = new ArrayList<>();
            Set<String> ids = new HashSet<>();
            for (int i = 0; i < array.length(); i++) {
                SavedRemoteHost host = SavedRemoteHost.fromJson(array.getJSONObject(i));
                if (!ids.add(host.id)) throw new IllegalStateException("Duplicate remote profile identity");
                values.add(host);
            }
            publishRevisions(values);
            return values;
        } finally { Arrays.fill(plaintext, (byte) 0); }
    }

    private void writeLocked(List<SavedRemoteHost> values) throws Exception {
        if (values.size() > 32) throw new IllegalArgumentException("At most 32 remote profiles can be saved");
        JSONArray array = new JSONArray();
        for (SavedRemoteHost host : values) array.put(host.toJson());
        byte[] plaintext = new JSONObject().put("version", 1).put("hosts", array).toString().getBytes(StandardCharsets.UTF_8);
        try {
            if (plaintext.length > MAX_BYTES - 32) throw new IllegalArgumentException("Remote profile settings are too large");
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key(true));
            cipher.updateAAD(AAD);
            byte[] encrypted = cipher.doFinal(plaintext);
            if (cipher.getIV().length != 12) throw new IllegalStateException("Unsupported encryption IV");
            byte[] payload = ByteBuffer.allocate(13 + encrypted.length).put((byte) 1)
                .put(cipher.getIV()).put(encrypted).array();
            FileOutputStream out = null;
            try {
                out = file.startWrite();
                out.write(payload);
                file.finishWrite(out);
                publishRevisions(values);
            } catch (Exception error) {
                if (out != null) file.failWrite(out);
                throw error;
            }
        } finally { Arrays.fill(plaintext, (byte) 0); }
    }

    private SecretKey key(boolean create) throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        if (store.containsAlias(ALIAS)) return (SecretKey) store.getKey(ALIAS, null);
        if (!create) throw new IllegalStateException("Remote profile encryption key is unavailable");
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256).setRandomizedEncryptionRequired(true).build());
        return generator.generateKey();
    }
}
