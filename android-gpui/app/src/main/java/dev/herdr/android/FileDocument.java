package dev.herdr.android;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.json.JSONObject;

/**
 * A file read for the file view (core_session's {@code file.read} action),
 * already split and highlighted off the UI thread, or the reason it could not
 * be read. Also decides which {@code open_file} events (an agent's proposed
 * {@code hc open-file}) the phone acts on.
 */
final class FileDocument {
    final String path;
    final List<String> lines;
    final List<List<SyntaxHighlighter.Span>> spans;
    /** Lines in the whole file; more than {@code lines.size()} when it was cut. */
    final int total;
    /** The 1-based line to show and mark, or 0. */
    final int line;
    /** Why it could not be shown, or null. */
    final String error;
    /** When the phone's saved copy was read (wall ms), or 0 for a live read. */
    final long savedAtMs;
    /** A folder's entries, one per line of {@code lines} (t2kg 11-23); null for a file. */
    final List<Entry> entries;
    /** A folder with more entries than were listed. */
    final boolean more;

    /** One entry of a folder listing. Size and time are -1 when the machine did not say. */
    static final class Entry {
        final String name;
        final boolean dir;
        final long size;
        final long mtimeSec;

        Entry(String name, boolean dir, long size, long mtimeSec) {
            this.name = name;
            this.dir = dir;
            this.size = size;
            this.mtimeSec = mtimeSec;
        }
    }

    private FileDocument(String path, List<String> lines, List<List<SyntaxHighlighter.Span>> spans,
            int total, int line, String error, long savedAtMs) {
        this(path, lines, spans, total, line, error, savedAtMs, null, false);
    }

    private FileDocument(String path, List<String> lines, List<List<SyntaxHighlighter.Span>> spans,
            int total, int line, String error, long savedAtMs, List<Entry> entries, boolean more) {
        this.path = path;
        this.lines = lines;
        this.spans = spans;
        this.total = total;
        this.line = line;
        this.error = error;
        this.savedAtMs = savedAtMs;
        this.entries = entries;
        this.more = more;
    }

    /** Whether this is a folder's listing. */
    boolean folder() {
        return entries != null;
    }

    /** The path of entry {@code row} of this folder, or null. */
    String child(int row) {
        if (entries == null || row < 0 || row >= entries.size()) return null;
        return path.endsWith("/") ? path + entries.get(row).name : path + "/" + entries.get(row).name;
    }

    /** A folder's row: {@code name/} for a folder, then size and modified time when known. */
    static String row(Entry e, int nameWidth, java.util.TimeZone zone, long nowSec) {
        StringBuilder out = new StringBuilder(e.name).append(e.dir ? "/" : "");
        if (e.size < 0 && e.mtimeSec < 0) return out.toString();
        while (out.length() < nameWidth + 2) out.append(' ');
        String size = e.dir || e.size < 0 ? "" : size(e.size);
        for (int i = size.length(); i < 8; i++) out.append(' ');
        out.append(size);
        if (e.mtimeSec >= 0) out.append("  ").append(time(e.mtimeSec, zone, nowSec));
        return out.toString();
    }

    /** 0 B, 512 B, 4.0 KB, 12 MB, 1.2 GB. */
    static String size(long bytes) {
        if (bytes < 1024) return bytes + " B";
        String[] units = {"KB", "MB", "GB", "TB"};
        double v = bytes;
        int u = -1;
        while (v >= 1024 && u < units.length - 1) {
            v /= 1024;
            u++;
        }
        return (v < 10 ? String.format(java.util.Locale.ROOT, "%.1f", v) : String.valueOf(Math.round(v))) + " " + units[u];
    }

    /** "Nov 22 18:19" within the year, "Nov 22 2025" otherwise. */
    static String time(long sec, java.util.TimeZone zone, long nowSec) {
        java.text.SimpleDateFormat sameYear = new java.text.SimpleDateFormat("MMM d HH:mm", java.util.Locale.US);
        java.text.SimpleDateFormat older = new java.text.SimpleDateFormat("MMM d yyyy", java.util.Locale.US);
        sameYear.setTimeZone(zone);
        older.setTimeZone(zone);
        java.util.Date at = new java.util.Date(sec * 1000);
        boolean recent = Math.abs(nowSec - sec) < 180L * 24 * 3600;
        return (recent ? sameYear : older).format(at);
    }

    static FileDocument failed(String path, int line, String error) {
        return failed(path, line, error, 0);
    }

    private static FileDocument failed(String path, int line, String error, long savedAtMs) {
        return new FileDocument(path, Collections.emptyList(), Collections.emptyList(), 0, line, error, savedAtMs);
    }

    /** No copy of the file was kept for reading offline. */
    static final String NOT_SAVED = "Not saved for offline reading yet";

    /**
     * What the header says of a saved copy, offline: "saved copy · as of 14:02 · may be outdated",
     * with why there is no text, or how much of the file was kept.
     */
    static String savedNote(String when, FileDocument copy) {
        String note = "saved copy · as of " + when + " · may be outdated";
        if (copy.error != null) return note + " · " + copy.error.toLowerCase(java.util.Locale.ROOT);
        if (copy.total > copy.lines.size()) return note + " · first " + copy.lines.size() + " of " + copy.total + " lines";
        return note + " · " + copy.total + " lines";
    }

    /** Parses core_session's reply: {@code {"queued":true,"path","text","lines"}} or an error. */
    static FileDocument parse(String requested, int line, String raw) {
        JSONObject reply;
        try {
            reply = new JSONObject(raw);
        } catch (Exception malformed) {
            return failed(requested, line, "unreadable reply");
        }
        String path = reply.optString("path", requested);
        long saved = reply.optLong("saved_as_of_ms", 0);
        if (!reply.optBoolean("queued", false)) return failed(path, line, reason(reply.optString("error", "")), saved);
        if (reply.optBoolean("folder", false)) return folder(path, reply, saved);
        String text = reply.optString("text", "");
        List<String> lines = split(text);
        int total = Math.max(reply.optInt("lines", lines.size()), lines.size());
        return new FileDocument(path, lines,
                SyntaxHighlighter.highlight(SyntaxHighlighter.forPath(path), lines), total, line, null, saved);
    }

    /** A folder's reply ({@code entries}): one row per entry, in the order the machine sent. */
    private static FileDocument folder(String path, JSONObject reply, long saved) {
        org.json.JSONArray list = reply.optJSONArray("entries");
        List<Entry> entries = new ArrayList<>();
        for (int i = 0; list != null && i < list.length(); i++) {
            JSONObject e = list.optJSONObject(i);
            String name = e == null ? "" : e.optString("name", "");
            if (name.isEmpty() || name.contains("/") || name.equals(".") || name.equals("..")) continue;
            entries.add(new Entry(name, e.optBoolean("dir", false),
                    e.isNull("size") ? -1 : e.optLong("size", -1), e.isNull("mtime") ? -1 : e.optLong("mtime", -1)));
        }
        int width = 0;
        for (Entry e : entries) width = Math.max(width, Math.min(40, e.name.length() + (e.dir ? 1 : 0)));
        List<String> lines = new ArrayList<>();
        List<List<SyntaxHighlighter.Span>> spans = new ArrayList<>();
        java.util.TimeZone zone = java.util.TimeZone.getDefault();
        long now = System.currentTimeMillis() / 1000;
        for (Entry e : entries) {
            lines.add(row(e, width, zone, now));
            spans.add(Collections.emptyList());
        }
        return new FileDocument(path, lines, spans, lines.size(), 0, null, saved, entries, reply.optBoolean("more", false));
    }

    /** Lines without their ends, tabs as four spaces; a final line end starts no extra line. */
    static List<String> split(String text) {
        List<String> lines = new ArrayList<>();
        int start = 0;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) != '\n') continue;
            int end = i > start && text.charAt(i - 1) == '\r' ? i - 1 : i;
            lines.add(text.substring(start, end).replace("\t", "    "));
            start = i + 1;
        }
        if (start < text.length()) lines.add(text.substring(start).replace("\t", "    "));
        return lines;
    }

    /** herdr-core's error kinds, as a person reads them. */
    static String reason(String error) {
        if (error.contains("not_saved")) return NOT_SAVED;
        if (error.contains("not_found")) return "No such file";
        if (error.contains("not_text")) return "Not a text file";
        if (error.contains("machine_unknown") || error.contains("unavailable")) return "That computer is not reachable";
        if (error.contains("needs_update")) return "That computer's herdr-core is too old to read files";
        if (error.contains("Permission denied")) return "Permission denied";
        if (error.contains("is not a regular file")) return "Not a file (a folder?)";
        java.util.regex.Matcher over = java.util.regex.Pattern.compile("is over (\\d+) MB").matcher(error);
        if (over.find()) return "Too large to open (over " + over.group(1) + " MB)";
        if (error.contains("io")) return "Could not read it";
        return error.isEmpty() ? "Could not read it" : error;
    }

    /** The 0-based row to scroll to: a little above the marked line, so it has context. */
    int scrollRow() {
        if (line <= 0 || lines.isEmpty()) return 0;
        return Math.max(0, Math.min(line - 1, lines.size() - 1) - 3);
    }

    /** "foo.rs" from "/a/b/foo.rs". */
    String name() {
        int slash = path.lastIndexOf('/');
        return slash < 0 || slash == path.length() - 1 ? path : path.substring(slash + 1);
    }

    /**
     * Whether an {@code open_file} event from the view should open now: newer
     * than the last one handled, for the terminal on screen (an agent cannot
     * pull Wolf away from another), with an absolute path.
     */
    static boolean shouldOpen(JSONObject event, String shownTerminal, long lastSeq) {
        if (event == null || shownTerminal == null || shownTerminal.isEmpty()) return false;
        long seq = event.optLong("seq", 0);
        String path = event.optString("path", "");
        return seq > lastSeq && shownTerminal.equals(event.optString("terminal")) && path.startsWith("/");
    }
}
