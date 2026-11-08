package dev.herdr.android;

import dev.herdr.android.core.QuickCommand;
import dev.herdr.android.core.Shortcut;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Immutable remote profile. Passwords are deliberately absent from this schema. */
final class SavedRemoteHost {
    /** Matches the native parser's limit for pasted Iroh text. */
    static final int MAX_TICKET = 4096;
    /** Matches the native parser's limit for relay URLs. */
    static final int MAX_RELAY = 512;
    final String id, name, transport, host, endpointId, endpointTicket, relayUrl, username, session;
    final int port;
    final String expectedFingerprint, shortcuts, connectionRevision;
    final List<QuickCommand> quickCommands;

    SavedRemoteHost(String id, String name, String transport, String host, int port,
            String endpointId, String endpointTicket, String relayUrl, String username,
            String session, String expectedFingerprint, String shortcuts,
            List<QuickCommand> quickCommands, String connectionRevision) {
        this.id = id == null ? UUID.randomUUID().toString() : uuid(id);
        this.name = plain(name, 80, "Profile name");
        if (!"tcp".equals(transport) && !"iroh".equals(transport)) {
            throw new IllegalArgumentException("Choose Direct TCP or Iroh");
        }
        this.transport = transport;
        if ("tcp".equals(transport)) {
            this.host = plain(host, 253, "SSH host");
            if (this.host.matches(".*[\\s/@?#\\\\].*")) throw new IllegalArgumentException("Enter a hostname or IP address without a URL");
            if (port < 1 || port > 65535) throw new IllegalArgumentException("SSH port must be 1–65535");
            this.port = port;
            this.endpointId = "";
            this.endpointTicket = "";
            this.relayUrl = "";
        } else {
            this.endpointId = plain(endpointId, 128, "Iroh endpoint ID");
            if (!this.endpointId.matches("[A-Za-z0-9]+")) throw new IllegalArgumentException("Enter the Iroh endpoint ID, without a URL");
            this.endpointTicket = ticket(endpointTicket);
            this.relayUrl = relay(relayUrl);
            this.host = "";
            this.port = 22; // The receiver chooses its SSH target; this value is unused by Iroh.
        }
        this.username = plain(username, 128, "SSH username");
        if (this.username.matches(".*\\s.*")) throw new IllegalArgumentException("SSH username cannot contain spaces");
        this.session = plain(session == null || session.trim().isEmpty() ? "default" : session, 64, "Herdr session");
        if (!this.session.matches("[A-Za-z0-9_.-]+")) throw new IllegalArgumentException("Session may contain letters, digits, dots, underscores and hyphens");
        this.expectedFingerprint = fingerprint(expectedFingerprint);
        Shortcut.parseConfiguration(shortcuts);
        this.shortcuts = shortcuts;
        this.quickCommands = QuickCommand.validatedCopy(quickCommands);
        this.connectionRevision = connectionRevision == null ? UUID.randomUUID().toString() : uuid(connectionRevision);
    }

    static SavedRemoteHost create(String name, String transport, String host, int port,
            String endpointId, String endpointTicket, String relayUrl, String username, String session) {
        return new SavedRemoteHost(null, name, transport, host, port, endpointId, endpointTicket,
                relayUrl, username, session, "", SavedHost.DEFAULT_KEYS, java.util.Collections.emptyList(), null);
    }

    /**
     * A ticket and a relay only route to the same endpoint, which Iroh authenticates by its
     * ID, so neither changes identity or trust.
     */
    boolean sameIdentity(SavedRemoteHost other) {
        return other != null && transport.equals(other.transport) && host.equals(other.host)
            && port == other.port && endpointId.equals(other.endpointId) && username.equals(other.username)
            && session.equals(other.session);
    }

    boolean matchesConnection(SavedRemoteHost expected) {
        return expected != null && id.equals(expected.id) && sameIdentity(expected)
            && expectedFingerprint.equals(expected.expectedFingerprint)
            && connectionRevision.equals(expected.connectionRevision);
    }

    SavedRemoteHost copy(String newName, String pin, String keys, List<QuickCommand> commands, String revision) {
        return new SavedRemoteHost(id, newName, transport, host, port, endpointId, endpointTicket,
                relayUrl, username, session, pin, keys, commands, revision);
    }

    String connectionJson() throws JSONException {
        return new JSONObject().put("transport", transport).put("host", host).put("port", port)
            .put("endpoint_id", endpointId).put("endpoint_ticket", endpointTicket)
            .put("relay_url", relayUrl).put("username", username).put("session", session)
            .put("expected_fingerprint", expectedFingerprint).toString();
    }

    JSONObject toJson() throws JSONException {
        return toPortableJson().put("id", id).put("connectionRevision", connectionRevision);
    }

    /** The profile without its device-local ID and revision, for a hosts file. */
    JSONObject toPortableJson() throws JSONException {
        JSONArray commands = new JSONArray();
        for (QuickCommand command : quickCommands) commands.put(new JSONObject()
            .put("label", command.getLabel()).put("text", command.getText()).put("pinned", command.isPinned()));
        return new JSONObject(connectionJson()).put("name", name).put("shortcuts", shortcuts)
            .put("quickCommands", commands);
    }

    static SavedRemoteHost fromJson(JSONObject value) throws JSONException {
        return parse(value, string(value, "id"), string(value, "connectionRevision"));
    }

    /** A profile from a hosts file, as a new profile with its own ID and revision. */
    static SavedRemoteHost fromPortableJson(JSONObject value) throws JSONException {
        return parse(value, null, null);
    }

    private static SavedRemoteHost parse(JSONObject value, String id, String revision) throws JSONException {
        JSONArray array = value.getJSONArray("quickCommands");
        if (array.length() > QuickCommand.MAX_COMMANDS) throw new IllegalArgumentException("Too many quick commands");
        List<QuickCommand> commands = new ArrayList<>();
        for (int i = 0; i < array.length(); i++) {
            JSONObject command = array.getJSONObject(i);
            Object pinned = command.get("pinned");
            if (!(pinned instanceof Boolean)) throw new IllegalArgumentException("Invalid pinned setting");
            commands.add(new QuickCommand(string(command, "label"), string(command, "text"), (Boolean) pinned));
        }
        Object port = value.get("port");
        if (!(port instanceof Integer) && !(port instanceof Long)) throw new IllegalArgumentException("Invalid SSH port");
        long portValue = ((Number) port).longValue();
        if (portValue < 1 || portValue > 65535) throw new IllegalArgumentException("Invalid SSH port");
        return new SavedRemoteHost(id, string(value, "name"), string(value, "transport"),
            string(value, "host"), (int) portValue, string(value, "endpoint_id"),
            // Profiles saved before tickets and relays existed have neither.
            value.has("endpoint_ticket") ? string(value, "endpoint_ticket") : "",
            value.has("relay_url") ? string(value, "relay_url") : "",
            string(value, "username"),
            string(value, "session"), string(value, "expected_fingerprint"), string(value, "shortcuts"),
            commands, revision);
    }

    static String fingerprint(String value) {
        if (value == null) throw new IllegalArgumentException("Fingerprint is required");
        if (!value.isEmpty() && !value.matches("SHA256:[A-Za-z0-9+/]{43}=?")) {
            throw new IllegalArgumentException("Invalid SSH SHA-256 fingerprint");
        }
        return value;
    }

    static String string(JSONObject value, String key) throws JSONException {
        Object result = value.get(key);
        if (!(result instanceof String)) throw new IllegalArgumentException("Invalid saved remote profile");
        return (String) result;
    }

    /** Empty, or the canonical ticket from the native parser, which also checks its endpoint. */
    private static String ticket(String value) {
        if (value == null || value.length() > MAX_TICKET
                || !value.isEmpty() && !value.matches("endpoint[a-z2-7]+")) {
            throw new IllegalArgumentException("Invalid Iroh ticket");
        }
        return value;
    }

    /** Empty, or the canonical relay URL from the native parser. */
    private static String relay(String value) {
        if (value == null || value.length() > MAX_RELAY
                || !value.isEmpty() && !value.matches("https?://\\S+")) {
            throw new IllegalArgumentException("Invalid Iroh relay URL");
        }
        return value;
    }

    private static String plain(String value, int max, String label) {
        if (value == null || value.trim().isEmpty() || value.length() > max) {
            throw new IllegalArgumentException(label + " is required (maximum " + max + " characters)");
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isISOControl(c) || Character.isSurrogate(c) || c == '\u2028' || c == '\u2029') {
                throw new IllegalArgumentException(label + " contains unsupported characters");
            }
        }
        return value.trim();
    }

    private static String uuid(String value) {
        if (!UUID.fromString(value).toString().equals(value)) throw new IllegalArgumentException("Invalid profile revision");
        return value;
    }
}
