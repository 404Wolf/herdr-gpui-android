package dev.herdr.android;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * One computer's Herdr, as the phone can prove it: the core's id from the {@code hello} of a
 * direct connection, and the SSH host key that connection authenticated. The core id alone is
 * not enough (a cloned disk keeps it, with another host key), and the host key alone is not
 * either (one host runs several sessions, each with its own core).
 *
 * <p>Held messages, and in later stages the cache, are kept under this key, never under a
 * profile: a profile can be edited to point at another machine.
 */
final class MachineKey {
    /** Longer values are not an id this phone accepts. */
    static final int MAX_CHARS = 256;

    final String core;
    final String hostKey;

    private MachineKey(String core, String hostKey) {
        this.core = core;
        this.hostKey = hostKey;
    }

    /** The key, or null when either part is missing or not a plain bounded string. */
    static MachineKey of(String core, String hostKey) {
        if (!plain(core) || !plain(hostKey)) return null;
        return new MachineKey(core, hostKey);
    }

    private static boolean plain(String value) {
        if (value == null || value.isEmpty() || value.length() > MAX_CHARS) return false;
        for (int i = 0; i < value.length(); i++) if (Character.isISOControl(value.charAt(i))) return false;
        return true;
    }

    JSONObject json() throws JSONException {
        return new JSONObject().put("core", core).put("hostkey", hostKey);
    }

    /** The key a file holds, or null for a missing or damaged one. */
    static MachineKey from(JSONObject value) {
        return value == null ? null : of(value.optString("core", ""), value.optString("hostkey", ""));
    }

    @Override public boolean equals(Object other) {
        if (!(other instanceof MachineKey)) return false;
        MachineKey that = (MachineKey) other;
        return core.equals(that.core) && hostKey.equals(that.hostKey);
    }

    @Override public int hashCode() {
        return core.hashCode() * 31 + hostKey.hashCode();
    }

    @Override public String toString() {
        return core + " " + hostKey;
    }
}
