package dev.herdr.android;

/**
 * Which shared URIs may be opened. Both an allowed content authority and per-URI authorization
 * are required. Pure, so each case is a JVM test.
 */
final class ShareUris {
    private ShareUris() { }

    static boolean allowed(String scheme, String authority, String ownPackage) {
        if (!"content".equalsIgnoreCase(scheme)) return false;
        if (authority == null || authority.isEmpty()) return false;
        // content://10@authority/... names the same provider for another Android user.
        String lower = authority.substring(authority.lastIndexOf('@') + 1).toLowerCase(java.util.Locale.ROOT);
        if (lower.isEmpty()) return false;
        String own = ownPackage.toLowerCase(java.util.Locale.ROOT);
        return !(lower.equals(own) || lower.startsWith(own + "."));
    }

    /**
     * The indexes of {@code uris} ({scheme, authority} each) that may be opened: allowed by
     * {@link #allowed} and authorized on their own by {@code authorized}.
     */
    static java.util.List<Integer> filter(java.util.List<String[]> uris, String ownPackage,
            java.util.function.IntPredicate authorized) {
        java.util.List<Integer> out = new java.util.ArrayList<>();
        for (int i = 0; i < uris.size(); i++) {
            String[] uri = uris.get(i);
            if (allowed(uri[0], uri[1], ownPackage) && authorized.test(i)) out.add(i);
        }
        return out;
    }
}
