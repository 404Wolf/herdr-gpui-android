package dev.herdr.android;

import java.io.InputStream;

/** The provisioned hosts file; see {@link #importIfChanged}. */
final class ProvisionedHosts {
    private ProvisionedHosts() { }

    /**
     * Hosts provisioned by whoever administers the device: a hosts file (the same
     * format as Export) pushed over adb, for example by nix-android, to this app's
     * own external files directory, which other apps can neither read nor write.
     * Each new version of the file is imported once, with its trusted host keys,
     * since only adb or this app can write there. Hosts already saved, with their
     * trust, win; hosts deleted here are added back only by a changed file.
     * Runs on the worker; returns a status line, or null when nothing changed.
     */
    static String importIfChanged(android.content.Context context, RemoteHostStore store) {
        java.io.File dir = context.getExternalFilesDir(null);
        java.io.File file = dir == null ? null : new java.io.File(dir, HostFile.FILE_NAME);
        if (file == null || !file.isFile()) return null;
        try {
            byte[] bytes;
            try (InputStream in = new java.io.FileInputStream(file)) {
                bytes = HostFile.readBounded(in);
            }
            StringBuilder digest = new StringBuilder();
            for (byte b : java.security.MessageDigest.getInstance("SHA-256").digest(bytes)) {
                digest.append(String.format(java.util.Locale.ROOT, "%02x", b));
            }
            android.content.SharedPreferences prefs = context.getSharedPreferences("remote-startup", android.content.Context.MODE_PRIVATE);
            if (digest.toString().equals(prefs.getString("provisioned_hosts", ""))) return null;
            int added = store.importProfiles(HostFile.read(bytes));
            prefs.edit().putString("provisioned_hosts", digest.toString()).apply();
            if (added == 0) return null;
            return added == 1 ? "Added 1 provisioned host" : "Added " + added + " provisioned hosts";
        } catch (Exception failure) {
            // A bad file must not hide the hosts already saved.
            return "Provisioned hosts file not imported: " + (failure.getMessage() == null ? "unreadable" : failure.getMessage());
        }
    }
}
