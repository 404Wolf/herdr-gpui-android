package dev.herdr.android;

/**
 * Which URIs the share target may open under Herdr's own identity. A sender's data is untrusted: a
 * file:// path, a provider of this app's own, or a content URI the sender was never granted would
 * be read with Herdr's access, not the sender's. Pure, so each case is a JVM test.
 */
final class ShareUris {
    /** {@code Intent.FLAG_GRANT_READ_URI_PERMISSION}. */
    static final int GRANT_READ = 1;

    private ShareUris() { }

    /**
     * Only a content:// URI that the sender explicitly granted for reading (the framework honours
     * that flag only for URIs the sender itself may read), and never one of this app's own authorities.
     */
    static boolean allowed(String scheme, String authority, int intentFlags, String ownPackage) {
        if (!"content".equalsIgnoreCase(scheme)) return false;
        if ((intentFlags & GRANT_READ) == 0) return false;
        if (authority == null || authority.isEmpty()) return false;
        String lower = authority.toLowerCase(java.util.Locale.ROOT);
        String own = ownPackage.toLowerCase(java.util.Locale.ROOT);
        return !(lower.equals(own) || lower.startsWith(own + "."));
    }
}
