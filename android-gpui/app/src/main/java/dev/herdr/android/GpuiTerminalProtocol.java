package dev.herdr.android;

import android.util.JsonReader;
import android.util.JsonToken;
import dev.herdr.android.core.KeyChord;
import dev.herdr.android.core.QuickCommand;
import dev.herdr.android.core.Shortcut;
import java.io.IOException;
import java.io.StringReader;
import java.nio.CharBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Validates the small renderer/HTTPS boundary without disclosing remote diagnostics. */
final class GpuiTerminalProtocol {
    static final int MAX_SCREEN_BYTES = 262144;
    static final int MAX_COMMAND_JSON_CHARS = Shortcut.MAX_TEXT_BYTES * 6 + 128;

    private GpuiTerminalProtocol() { }

    static Command command(String source) throws IOException {
        if (source == null || source.length() > MAX_COMMAND_JSON_CHARS) throw invalid();
        String type = null, text = null;
        List<String> keys = null;
        try (JsonReader reader = reader(source)) {
            reader.beginObject();
            while (reader.hasNext()) {
                String field = reader.nextName();
                if ("type".equals(field) && type == null) type = string(reader);
                else if ("text".equals(field) && text == null) text = string(reader);
                else if ("keys".equals(field) && keys == null) {
                    keys = new ArrayList<>();
                    reader.beginArray();
                    while (reader.hasNext()) {
                        if (keys.size() == KeyChord.MAX_KEYS) throw invalid();
                        keys.add(string(reader));
                    }
                    reader.endArray();
                } else throw invalid();
            }
            reader.endObject();
            end(reader);
        }
        if ("close".equals(type) && text == null && keys == null) {
            return new Command(type, null, Collections.<String>emptyList());
        }
        if ("text".equals(type) && text != null && keys == null) {
            Shortcut.validateText(text);
            return new Command(type, text, Collections.<String>emptyList());
        }
        if ("submit".equals(type) && text != null && keys == null) {
            QuickCommand.validateText(text);
            return new Command(type, text, Collections.<String>emptyList());
        }
        if ("compose".equals(type) && text != null && keys == null) {
            Shortcut.validateText(text);
            if (text.trim().isEmpty()) throw invalid();
            return new Command(type, text, Collections.<String>emptyList());
        }
        if ("keys".equals(type) && text == null && keys != null) {
            return new Command(type, null, KeyChord.validate(keys));
        }
        throw invalid();
    }

    static String screen(String response) throws IOException {
        String text = null;
        try (JsonReader reader = reader(response)) {
            reader.beginObject();
            int fields = 0;
            while (reader.hasNext()) {
                if (++fields > 128) throw invalid();
                if ("text".equals(reader.nextName())) {
                    if (text != null) throw invalid();
                    text = string(reader);
                } else skip(reader);
            }
            reader.endObject();
            end(reader);
        }
        if (text == null || text.length() > MAX_SCREEN_BYTES
                || StandardCharsets.UTF_8.newEncoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .encode(CharBuffer.wrap(text)).remaining() > MAX_SCREEN_BYTES) throw invalid();
        // These characters remain inert text. Neither Java nor GPUI interprets terminal escapes.
        return text;
    }

    static void requireAck(String response) throws IOException {
        boolean seen = false, accepted = false;
        try (JsonReader reader = reader(response)) {
            reader.beginObject();
            int fields = 0;
            while (reader.hasNext()) {
                if (++fields > 128) throw invalid();
                if ("ok".equals(reader.nextName())) {
                    if (seen || reader.peek() != JsonToken.BOOLEAN) throw invalid();
                    seen = true;
                    accepted = reader.nextBoolean();
                } else skip(reader);
            }
            reader.endObject();
            end(reader);
        }
        if (!seen || !accepted) throw invalid();
    }

    static String shortcutCommand(Shortcut shortcut) throws JSONException {
        if (shortcut == null) throw new IllegalArgumentException("Shortcut is required");
        return new JSONObject(shortcut.toJsonBody())
                .put("type", shortcut.isText() ? "text" : "keys").toString();
    }

    /**
     * A shortcut that is exactly Enter. The bar pins its own Enter at the end,
     * so a configured one would be a duplicate key in the row.
     */
    static boolean isPlainEnter(Shortcut shortcut) {
        return !shortcut.isText() && shortcut.getKeys().size() == 1 && "enter".equals(shortcut.getKeys().get(0));
    }

    /** A composed message: one bracketed paste and Enter, in one input slot. */
    static String composeCommand(String text) throws JSONException {
        Shortcut.validateText(text);
        if (text.trim().isEmpty()) throw new IllegalArgumentException("Nothing to send");
        return new JSONObject().put("type", "compose").put("text", text).toString();
    }

    static String shortcuts(String configuration) throws JSONException {
        JSONArray result = new JSONArray();
        for (Shortcut shortcut : Shortcut.parseConfiguration(configuration)) {
            JSONObject value = new JSONObject().put("label", shortcut.getLabel());
            if (shortcut.isText()) value.put("text", shortcut.getText());
            else value.put("keys", new JSONArray(shortcut.getKeys()));
            result.put(value);
        }
        return result.toString();
    }

    static String physicalText(int codepoint) {
        // Android's COMBINING_ACCENT marker has its high bit set and is not a Unicode scalar.
        if (!Character.isValidCodePoint(codepoint) || Character.isISOControl(codepoint)
                || (codepoint >= Character.MIN_SURROGATE && codepoint <= Character.MAX_SURROGATE)) {
            throw new IllegalArgumentException("Unsupported physical character");
        }
        return new String(Character.toChars(codepoint));
    }

    static String title(String value) {
        if (value == null || value.trim().isEmpty()) return "Terminal";
        StringBuilder out = new StringBuilder();
        for (int offset = 0; offset < value.length() && out.length() < 256;) {
            int codepoint = value.codePointAt(offset);
            offset += Character.charCount(codepoint);
            if (Character.isISOControl(codepoint)
                    || (codepoint <= Character.MAX_VALUE && Character.isSurrogate((char) codepoint))) {
                out.append(' ');
            } else if (out.length() + Character.charCount(codepoint) <= 256) {
                out.appendCodePoint(codepoint);
            }
        }
        return out.toString();
    }

    private static JsonReader reader(String source) throws IOException {
        if (source == null || source.length() > 1024 * 1024) throw invalid();
        JsonReader reader = new JsonReader(new StringReader(source));
        reader.setLenient(false);
        return reader;
    }

    private static String string(JsonReader reader) throws IOException {
        if (reader.peek() != JsonToken.STRING) throw invalid();
        return reader.nextString();
    }

    private static void end(JsonReader reader) throws IOException {
        if (reader.peek() != JsonToken.END_DOCUMENT) throw invalid();
    }

    /** Bound nesting even in fields this version does not use; do not recurse on untrusted JSON. */
    private static void skip(JsonReader reader) throws IOException {
        int depth = 0;
        do {
            JsonToken token = reader.peek();
            if (token == JsonToken.BEGIN_OBJECT || token == JsonToken.BEGIN_ARRAY) {
                if (++depth > 16) throw invalid();
                if (token == JsonToken.BEGIN_OBJECT) reader.beginObject(); else reader.beginArray();
            } else if (token == JsonToken.END_OBJECT || token == JsonToken.END_ARRAY) {
                if (token == JsonToken.END_OBJECT) reader.endObject(); else reader.endArray();
                depth--;
            } else if (token == JsonToken.NAME) reader.nextName();
            else reader.skipValue();
        } while (depth > 0);
    }

    private static IOException invalid() { return new IOException("Invalid terminal data"); }

    static final class Command {
        final String type;
        final String text;
        final List<String> keys;

        Command(String type, String text, List<String> keys) {
            this.type = type;
            this.text = text;
            this.keys = keys;
        }
    }
}
