package dev.herdr.android;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * A small, line-oriented highlighter for the read-only file view: comments,
 * strings, numbers, keywords and capitalised type names, by file extension.
 * It is a reader's aid, not a parser: a construct it does not know stays
 * plain text. Block comments and multi-line strings carry across lines, so
 * {@link #highlight} runs over the whole file once and each row binds its own
 * spans.
 */
final class SyntaxHighlighter {
    static final int PLAIN = 0, KEYWORD = 1, STRING = 2, COMMENT = 3, NUMBER = 4, TYPE = 5, HEADING = 6;

    /** One coloured run on a line: [start, end) in UTF-16 units. */
    static final class Span {
        final int start, end, kind;

        Span(int start, int end, int kind) {
            this.start = start;
            this.end = end;
            this.kind = kind;
        }

        @Override public boolean equals(Object other) {
            if (!(other instanceof Span)) return false;
            Span span = (Span) other;
            return start == span.start && end == span.end && kind == span.kind;
        }

        @Override public int hashCode() {
            return (start * 31 + end) * 31 + kind;
        }

        @Override public String toString() {
            return start + ".." + end + ":" + kind;
        }
    }

    /** How one family of languages writes comments, strings and keywords. */
    static final class Language {
        final String line;
        final String blockOpen, blockClose;
        final String quotes;
        /** A triple quote (Python, Nix's '' strings) that may span lines. */
        final String longString;
        final Set<String> keywords;
        final boolean markdown;

        Language(String line, String blockOpen, String blockClose, String quotes, String longString,
                boolean markdown, String... keywords) {
            this.line = line;
            this.blockOpen = blockOpen;
            this.blockClose = blockClose;
            this.quotes = quotes;
            this.longString = longString;
            this.markdown = markdown;
            this.keywords = new HashSet<>(Arrays.asList(keywords));
        }
    }

    static final Language C_LIKE = new Language("//", "/*", "*/", "\"'`", null, false,
            // Rust, Java/Kotlin, JS/TS, Go, C/C++, Swift, Zig: the common core.
            "as", "async", "await", "break", "case", "catch", "class", "const", "continue", "crate",
            "default", "defer", "do", "dyn", "else", "enum", "export", "extends", "false", "final", "finally",
            "fn", "for", "fun", "func", "function", "go", "if", "impl", "implements", "import", "in",
            "interface", "let", "loop", "match", "mod", "move", "mut", "new", "null", "nil", "override",
            "package", "private", "protected", "pub", "public", "ref", "return", "self", "Self", "static",
            "struct", "super", "switch", "this", "throw", "throws", "trait", "true", "try", "type",
            "typeof", "unsafe", "use", "val", "var", "void", "where", "while", "yield");
    static final Language HASH = new Language("#", null, null, "\"'", "\"\"\"", false,
            // Python, shell, Ruby, TOML/YAML values.
            "and", "as", "break", "case", "class", "continue", "def", "do", "done", "elif", "else", "end",
            "esac", "except", "export", "false", "False", "fi", "finally", "for", "from", "function", "if",
            "import", "in", "is", "lambda", "local", "None", "not", "null", "or", "pass", "raise", "return",
            "then", "true", "True", "try", "while", "with", "yield");
    static final Language NIX = new Language("#", "/*", "*/", "\"", "''", false,
            "assert", "builtins", "else", "false", "if", "import", "in", "inherit", "let", "null", "or",
            "rec", "then", "true", "with");
    static final Language JSON = new Language(null, null, null, "\"", null, false, "true", "false", "null");
    static final Language MARKDOWN = new Language(null, null, null, "`", null, true);
    static final Language TEXT = new Language(null, null, null, "", null, false);

    private SyntaxHighlighter() { }

    /** The language for a file name, by its extension. */
    static Language forPath(String path) {
        String name = path == null ? "" : path.substring(path.lastIndexOf('/') + 1).toLowerCase(Locale.ROOT);
        int dot = name.lastIndexOf('.');
        String ext = dot < 0 ? name : name.substring(dot + 1);
        switch (ext) {
            case "rs": case "java": case "kt": case "kts": case "js": case "jsx": case "ts": case "tsx":
            case "go": case "c": case "h": case "cc": case "cpp": case "hpp": case "swift": case "zig":
            case "css": case "scss": case "gradle": case "proto": case "dart": case "scala":
                return C_LIKE;
            case "py": case "sh": case "bash": case "zsh": case "rb": case "toml": case "yaml": case "yml":
            case "conf": case "cfg": case "ini": case "justfile": case "makefile": case "mk": case "dockerfile":
                return HASH;
            case "nix":
                return NIX;
            case "json": case "lock":
                return JSON;
            case "md": case "markdown":
                return MARKDOWN;
            default:
                return TEXT;
        }
    }

    /** The spans of every line of a file, in order. */
    static List<List<Span>> highlight(Language language, List<String> lines) {
        List<List<Span>> result = new ArrayList<>(lines.size());
        // What an unfinished construct at the end of the last line still is.
        String open = null;
        boolean fence = false;
        for (String line : lines) {
            List<Span> spans = new ArrayList<>();
            if (language.markdown) {
                fence = markdown(line, fence, spans);
            } else {
                open = line(language, line, open, spans);
            }
            result.add(spans);
        }
        return result;
    }

    /** Markdown: headings, fenced code, inline code. Returns whether a fence is still open. */
    private static boolean markdown(String line, boolean fence, List<Span> spans) {
        String trimmed = line.trim();
        if (trimmed.startsWith("```") || trimmed.startsWith("~~~")) {
            spans.add(new Span(0, line.length(), COMMENT));
            return !fence;
        }
        if (fence) {
            if (!line.isEmpty()) spans.add(new Span(0, line.length(), STRING));
            return true;
        }
        if (trimmed.startsWith("#")) {
            spans.add(new Span(0, line.length(), HEADING));
            return false;
        }
        int at = 0;
        while ((at = line.indexOf('`', at)) >= 0) {
            int end = line.indexOf('`', at + 1);
            if (end < 0) break;
            spans.add(new Span(at, end + 1, STRING));
            at = end + 1;
        }
        return false;
    }

    /**
     * One line of code. {@code open} is the closing delimiter a construct from
     * an earlier line still waits for; returns the one this line leaves open.
     */
    private static String line(Language lang, String text, String open, List<Span> spans) {
        int i = 0, n = text.length();
        if (open != null) {
            int end = text.indexOf(open);
            int kind = open.equals(lang.blockClose) ? COMMENT : STRING;
            if (end < 0) {
                if (n > 0) spans.add(new Span(0, n, kind));
                return open;
            }
            i = end + open.length();
            spans.add(new Span(0, i, kind));
        }
        while (i < n) {
            char c = text.charAt(i);
            if (lang.line != null && text.startsWith(lang.line, i)) {
                spans.add(new Span(i, n, COMMENT));
                return null;
            }
            if (lang.blockOpen != null && text.startsWith(lang.blockOpen, i)) {
                int end = text.indexOf(lang.blockClose, i + lang.blockOpen.length());
                if (end < 0) {
                    spans.add(new Span(i, n, COMMENT));
                    return lang.blockClose;
                }
                spans.add(new Span(i, end + lang.blockClose.length(), COMMENT));
                i = end + lang.blockClose.length();
                continue;
            }
            if (lang.longString != null && text.startsWith(lang.longString, i)) {
                int end = text.indexOf(lang.longString, i + lang.longString.length());
                if (end < 0) {
                    spans.add(new Span(i, n, STRING));
                    return lang.longString;
                }
                spans.add(new Span(i, end + lang.longString.length(), STRING));
                i = end + lang.longString.length();
                continue;
            }
            if (lang.quotes.indexOf(c) >= 0 && !(c == '\'' && lifetime(lang, text, i))) {
                int end = i + 1;
                while (end < n && text.charAt(end) != c) end += text.charAt(end) == '\\' ? 2 : 1;
                end = Math.min(end + 1, n);
                spans.add(new Span(i, end, STRING));
                i = end;
                continue;
            }
            if (Character.isDigit(c) && (i == 0 || !Character.isJavaIdentifierPart(text.charAt(i - 1)))) {
                int end = i + 1;
                while (end < n && (Character.isLetterOrDigit(text.charAt(end)) || text.charAt(end) == '_'
                        || text.charAt(end) == '.' && end + 1 < n && Character.isDigit(text.charAt(end + 1)))) end++;
                spans.add(new Span(i, end, NUMBER));
                i = end;
                continue;
            }
            if (Character.isJavaIdentifierStart(c)) {
                int end = i + 1;
                while (end < n && Character.isJavaIdentifierPart(text.charAt(end))) end++;
                String word = text.substring(i, end);
                if (lang.keywords.contains(word)) spans.add(new Span(i, end, KEYWORD));
                else if (lang != TEXT && Character.isUpperCase(c) && end - i > 1) spans.add(new Span(i, end, TYPE));
                i = end;
                continue;
            }
            i++;
        }
        return null;
    }

    /** Rust's {@code 'a} lifetimes and Kotlin/Java chars share a quote; a lone {@code 'ident} with no close is a lifetime. */
    private static boolean lifetime(Language lang, String text, int at) {
        if (lang != C_LIKE) return false;
        int end = at + 1;
        while (end < text.length() && Character.isJavaIdentifierPart(text.charAt(end))) end++;
        return end > at + 1 && (end >= text.length() || text.charAt(end) != '\'');
    }
}
