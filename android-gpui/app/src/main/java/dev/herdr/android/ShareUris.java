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
        // content://10@authority/... names the same provider for another Android user.
        String lower = authority.substring(authority.lastIndexOf('@') + 1).toLowerCase(java.util.Locale.ROOT);
        if (lower.isEmpty()) return false;
        String own = ownPackage.toLowerCase(java.util.Locale.ROOT);
        return !(lower.equals(own) || lower.startsWith(own + "."));
    }

    /**
     * The indexes of {@code uris} ({scheme, authority} each) that may be opened: allowed by
     * {@link #allowed} and also authorized on their own by {@code granted} (the per-URI check
     * {@code checkUriPermission}), never on the intent-wide flag alone.
     */
    static java.util.List<Integer> filter(java.util.List<String[]> uris, int intentFlags, String ownPackage,
            java.util.function.IntPredicate granted) {
        java.util.List<Integer> out = new java.util.ArrayList<>();
        for (int i = 0; i < uris.size(); i++) {
            String[] uri = uris.get(i);
            if (allowed(uri[0], uri[1], intentFlags, ownPackage) && granted.test(i)) out.add(i);
        }
        return out;
    }
}
