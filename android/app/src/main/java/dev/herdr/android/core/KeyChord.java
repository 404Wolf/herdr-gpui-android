package dev.herdr.android.core;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** The deliberately limited named-key vocabulary implemented by Herdr's JSON API. */
public final class KeyChord {
    public static final int MAX_KEYS = 16;
    private static final Set<String> NAMED = Collections.unmodifiableSet(new HashSet<String>(
            Arrays.asList("enter", "esc", "tab", "space", "backspace", "up", "down", "left", "right")));

    private KeyChord() { }

    public static String normalize(String input) {
        if (input == null || input.length() > 64) throw invalid();
        String key = input.trim().toLowerCase(Locale.ROOT);
        if (key.equals("escape")) key = "esc";
        if (key.equals("return")) key = "enter";
        if (NAMED.contains(key) || key.equals("shift+tab") || key.equals("shift+enter") || key.equals("y") || key.equals("n")
                || key.matches("[1-9]") || key.matches("(?:ctrl|alt)\\+[a-z]")
                || key.matches("f(?:[1-9]|1[0-2])")) return key;
        throw invalid();
    }

    public static List<String> parseSequence(String input) {
        if (input == null || input.trim().isEmpty() || input.length() > 1024) {
            throw new IllegalArgumentException("Enter 1–16 key chords");
        }
        String value = input.trim();
        String[] pieces = value.indexOf(',') >= 0 ? value.split(",", -1) : value.split("\\s+");
        return validate(Arrays.asList(pieces));
    }

    public static List<String> validate(List<String> input) {
        if (input == null || input.isEmpty() || input.size() > MAX_KEYS) {
            throw new IllegalArgumentException("A key sequence must contain 1–16 keys");
        }
        List<String> keys = new ArrayList<String>(input.size());
        for (String key : input) keys.add(normalize(key));
        return Collections.unmodifiableList(keys);
    }

    /** Pass a logical key (e.g. c, tab, f1), never an Android key-code number. */
    public static String fromPhysical(String key, boolean ctrl, boolean alt, boolean shift) {
        if (key == null || key.length() > 64 || (ctrl && alt)) throw invalid();
        String base = key.toLowerCase(Locale.ROOT);
        // The daemon cannot preserve these combined modifiers, so fail rather than strip them.
        if (ctrl || alt) {
            if (shift || !base.matches("[a-z]")) throw invalid();
            return normalize((ctrl ? "ctrl+" : "alt+") + base);
        }
        if (shift) {
            if (base.equals("tab")) return "shift+tab";
            // A newline without submitting, as agents read it (Claude Code, Codex).
            if (base.equals("enter") || base.equals("return")) return "shift+enter";
            throw invalid();
        }
        return normalize(base);
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("Unsupported key. Use named navigation keys, Ctrl+A–Z, Alt+A–Z, Shift+Tab or F1–F12");
    }
}
