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

    private FileDocument(String path, List<String> lines, List<List<SyntaxHighlighter.Span>> spans,
            int total, int line, String error) {
        this.path = path;
        this.lines = lines;
        this.spans = spans;
        this.total = total;
        this.line = line;
        this.error = error;
    }

    static FileDocument failed(String path, int line, String error) {
        return new FileDocument(path, Collections.emptyList(), Collections.emptyList(), 0, line, error);
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
        if (!reply.optBoolean("queued", false)) return failed(path, line, reason(reply.optString("error", "")));
        String text = reply.optString("text", "");
        List<String> lines = split(text);
        int total = Math.max(reply.optInt("lines", lines.size()), lines.size());
        return new FileDocument(path, lines,
                SyntaxHighlighter.highlight(SyntaxHighlighter.forPath(path), lines), total, line, null);
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
        if (error.contains("not_found")) return "No such file";
        if (error.contains("not_text")) return "Not a text file";
        if (error.contains("machine_unknown") || error.contains("unavailable")) return "That computer is not reachable";
        if (error.contains("needs_update")) return "That computer's herdr-core is too old to read files";
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
