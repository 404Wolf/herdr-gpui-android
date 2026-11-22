package dev.herdr.android;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A small, safe Markdown reader for untrusted doc, bead and comment text in
 * the right panel: the desktop beads mod's md.ts (t3fp's md.js, 6046ee51),
 * ported to plain text with styled ranges instead of HTML, so nothing in the
 * source is ever markup. One document is one text (so a selection can run
 * across blocks), with its blocks' ranges and sources (what a quote carries).
 *
 * <p>Blocks: fenced code; ATX headings; tables (shown as aligned monospace
 * rows); nested lists; blockquotes; rules; ASCII sketches (verbatim
 * monospace); paragraphs, single newlines kept. Inlines: `code`, [text](http
 * or a local /abs or ~/ path), **bold**, *em*, bare https links, and bead refs
 * only for ids that exist ({@code ref}). Pure: no Android types; the panel
 * turns {@link Style}s into spans.
 */
final class MdText {
    /** A styled range of the text. */
    static final class Style {
        enum Kind { BOLD, EM, CODE, LINK, LOCAL, BEAD, HEADING, QUOTE, PRE, BULLET, RULE }
        final Kind kind;
        final int start, end;
        /** LINK: the url; LOCAL: the path; BEAD: the id; HEADING: its level; BULLET: its depth. */
        final String value;

        Style(Kind kind, int start, int end, String value) {
            this.kind = kind;
            this.start = start;
            this.end = end;
            this.value = value;
        }
    }

    /** A top-level block: where it is in the text, and its own source lines. */
    static final class Block {
        final String kind, source;
        final int start, end;

        Block(String kind, int start, int end, String source) {
            this.kind = kind;
            this.start = start;
            this.end = end;
            this.source = source;
        }
    }

    final StringBuilder text = new StringBuilder();
    final List<Style> styles = new ArrayList<>();
    final List<Block> blocks = new ArrayList<>();
    private final Predicate<String> ref;

    private MdText(Predicate<String> ref) {
        this.ref = ref == null ? id -> false : ref;
    }

    /** {@code src} read; {@code ref} says which bead ids exist (they become refs). */
    static MdText of(String src, Predicate<String> ref) {
        MdText md = new MdText(ref);
        md.blocks(src == null ? "" : src, 0, true);
        return md;
    }

    String text() {
        return text.toString();
    }

    // ---- blocks ----

    private static final Pattern FENCE = Pattern.compile("^(\\s*)(```+|~~~+)\\s*([\\w+-]*)\\s*$");
    private static final Pattern HEADING = Pattern.compile("^(#{1,6})\\s+(.*?)\\s*#*\\s*$");
    private static final Pattern RULE = Pattern.compile("^\\s*([-*_])\\s*(\\1\\s*){2,}$");
    private static final Pattern LIST_ITEM = Pattern.compile("^(\\s*)([-*+•]|\\d+[.)])\\s+(.*)$");
    private static final Pattern TABLE_SEP = Pattern.compile("^\\s*\\|?\\s*:?-{2,}:?\\s*(\\|\\s*:?-{2,}:?\\s*)*\\|?\\s*$");
    private static final Pattern BOX = Pattern.compile("[─│┌┐└┘├┤┬┴┼═║╔╗╚╝]");
    private static final Pattern ART_START = Pattern.compile("^\\s*\\+[-=+]{3,}");
    private static final Pattern ART_MORE = Pattern.compile("^\\s*[|+]");
    private static final Pattern QUOTE_LINE = Pattern.compile("^\\s*>");

    private static boolean artStart(String l) { return ART_START.matcher(l).find() || BOX.matcher(l).find(); }
    private static boolean artMore(String l) { return ART_MORE.matcher(l).find() || BOX.matcher(l).find(); }

    /** Appends the blocks of {@code src}; {@code top} records them as the document's blocks. */
    private void blocks(String src, int depth, boolean top) {
        String[] lines = src.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
        int i = 0;
        boolean first = true;
        while (i < lines.length) {
            String line = lines[i];
            if (line.trim().isEmpty()) { i++; continue; }
            int from = i;
            // Nested content (a quote's, an item's) starts where its container put it.
            int at = top || !first ? startBlock() : text.length();
            first = false;
            String kind;
            Matcher m;
            if ((m = FENCE.matcher(line)).matches()) {
                String fence = m.group(2);
                List<String> body = new ArrayList<>();
                i++;
                while (i < lines.length && !lines[i].trim().startsWith(fence)) body.add(lines[i++]);
                i++;
                verbatim(String.join("\n", body));
                kind = "code";
            } else if ((m = HEADING.matcher(line)).matches()) {
                int s = text.length();
                inline(m.group(2), false);
                styles.add(new Style(Style.Kind.HEADING, s, text.length(), String.valueOf(m.group(1).length())));
                i++;
                kind = "heading";
            } else if (RULE.matcher(line).matches()) {
                int s = text.length();
                text.append("───");
                styles.add(new Style(Style.Kind.RULE, s, text.length(), null));
                i++;
                kind = "hr";
            } else if (artStart(line) && i + 1 < lines.length && artMore(lines[i + 1])) {
                List<String> body = new ArrayList<>();
                body.add(lines[i++]);
                while (i < lines.length && artMore(lines[i])) body.add(lines[i++]);
                verbatim(String.join("\n", body));
                kind = "art";
            } else if (line.contains("|") && i + 1 < lines.length && TABLE_SEP.matcher(lines[i + 1]).matches()) {
                List<List<String>> rows = new ArrayList<>();
                rows.add(cells(line));
                i += 2;
                while (i < lines.length && lines[i].contains("|") && !lines[i].trim().isEmpty()) rows.add(cells(lines[i++]));
                verbatim(table(rows));
                kind = "table";
            } else if (QUOTE_LINE.matcher(line).find()) {
                List<String> body = new ArrayList<>();
                while (i < lines.length && QUOTE_LINE.matcher(lines[i]).find()) body.add(lines[i++].replaceFirst("^\\s*> ?", ""));
                int s = text.length();
                blocks(String.join("\n", body), depth, false);
                styles.add(new Style(Style.Kind.QUOTE, s, text.length(), null));
                kind = "quote";
            } else if (LIST_ITEM.matcher(line).matches()) {
                i = list(lines, i, depth);
                kind = "list";
            } else {
                List<String> para = new ArrayList<>();
                while (i < lines.length && !lines[i].trim().isEmpty() && !LIST_ITEM.matcher(lines[i]).matches()
                        && !lines[i].matches("^(#{1,6})\\s.*") && !lines[i].matches("^\\s*(```|~~~).*")
                        && !QUOTE_LINE.matcher(lines[i]).find()
                        && !(artStart(lines[i]) && i + 1 < lines.length && artMore(lines[i + 1]))
                        && !(lines[i].contains("|") && i + 1 < lines.length && TABLE_SEP.matcher(lines[i + 1]).matches())) {
                    para.add(lines[i++]);
                }
                for (int k = 0; k < para.size(); k++) {
                    if (k > 0) text.append('\n');
                    inline(para.get(k).trim(), false);
                }
                kind = "para";
            }
            if (top) {
                StringBuilder source = new StringBuilder();
                for (int k = from; k < Math.min(i, lines.length); k++) source.append(k > from ? "\n" : "").append(lines[k]);
                blocks.add(new Block(kind, at, text.length(), source.toString().trim()));
            }
        }
    }

    /** A blank line between blocks: the panel sizes it as the gap. */
    private int startBlock() {
        if (text.length() > 0) {
            if (text.charAt(text.length() - 1) != '\n') text.append('\n');
            text.append('\n');
        }
        return text.length();
    }

    private void verbatim(String body) {
        int s = text.length();
        text.append(body);
        styles.add(new Style(Style.Kind.PRE, s, text.length(), null));
    }

    private static List<String> cells(String l) {
        String t = l.trim().replaceFirst("^\\|", "").replaceFirst("\\|$", "");
        List<String> out = new ArrayList<>();
        for (String c : t.split("(?<!\\\\)\\|", -1)) out.add(c.trim().replace("\\|", "|"));
        return out;
    }

    /** A table as aligned columns (the phone has no room for a grid). */
    private static String table(List<List<String>> rows) {
        int cols = rows.get(0).size();
        int[] width = new int[cols];
        for (List<String> r : rows) for (int k = 0; k < cols; k++) width[k] = Math.max(width[k], k < r.size() ? r.get(k).length() : 0);
        StringBuilder out = new StringBuilder();
        for (int n = 0; n < rows.size(); n++) {
            if (n > 0) out.append('\n');
            List<String> r = rows.get(n);
            for (int k = 0; k < cols; k++) {
                String c = k < r.size() ? r.get(k) : "";
                out.append(c);
                if (k < cols - 1) for (int p = c.length(); p < width[k] + 2; p++) out.append(' ');
            }
        }
        return out.toString().replaceAll(" +\n", "\n").replaceAll(" +$", "");
    }

    /** A list from line {@code i}: items at this indent; deeper ones nest. Returns the next line. */
    private int list(String[] lines, int i, int depth) {
        Matcher first = LIST_ITEM.matcher(lines[i]);
        if (!first.matches()) return i + 1;
        int indent = first.group(1).length();
        boolean ordered = Character.isDigit(first.group(2).charAt(0));
        int number = ordered ? Integer.parseInt(first.group(2).replaceAll("[.)]", "")) : 1;
        boolean firstItem = true;
        while (i < lines.length) {
            Matcher m = LIST_ITEM.matcher(lines[i]);
            if (!m.matches() || m.group(1).length() != indent || Character.isDigit(m.group(2).charAt(0)) != ordered) break;
            List<String> body = new ArrayList<>();
            body.add(m.group(3));
            i++;
            while (i < lines.length) {
                String l = lines[i];
                if (l.trim().isEmpty()) {
                    if (i + 1 < lines.length && lead(lines[i + 1]) > indent && !lines[i + 1].trim().isEmpty()) { body.add(""); i++; continue; }
                    break;
                }
                Matcher sub = LIST_ITEM.matcher(l);
                if (sub.matches() && sub.group(1).length() <= indent) break;
                if (!sub.matches() && !Character.isWhitespace(l.charAt(0))) { body.add(l); i++; continue; }
                body.add(l.substring(Math.min(indent + 2, lead(l))));
                i++;
            }
            if (!firstItem) text.append('\n');
            firstItem = false;
            int s = text.length();
            text.append(ordered ? number++ + ". " : "• ");
            item(body, depth + 1);
            styles.add(new Style(Style.Kind.BULLET, s, text.length(), String.valueOf(depth)));
        }
        return i;
    }

    /** An item's first paragraph on its own line; what follows (nested lists, more text) below it. */
    private void item(List<String> body, int depth) {
        int k = 0;
        StringBuilder first = new StringBuilder();
        while (k < body.size() && !body.get(k).trim().isEmpty() && !LIST_ITEM.matcher(body.get(k)).matches()) {
            if (first.length() > 0) first.append('\n');
            first.append(body.get(k++).trim());
        }
        String[] head = first.toString().split("\n", -1);
        for (int n = 0; n < head.length; n++) {
            if (n > 0) text.append('\n');
            inline(head[n], false);
        }
        if (k < body.size()) {
            List<String> rest = body.subList(k, body.size());
            while (!rest.isEmpty() && rest.get(0).trim().isEmpty()) rest = rest.subList(1, rest.size());
            if (rest.isEmpty()) return;
            text.append('\n');
            if (LIST_ITEM.matcher(rest.get(0)).matches()) list(rest.toArray(new String[0]), 0, depth);
            else blocks(String.join("\n", rest), depth, false);
        }
    }

    private static int lead(String l) {
        int n = 0;
        while (n < l.length() && Character.isWhitespace(l.charAt(n))) n++;
        return n;
    }

    // ---- inlines ----

    private static final Pattern WEB_LINK = Pattern.compile("^\\[([^\\]\\n]+)\\]\\((https?://[^\\s)]+)\\)");
    private static final Pattern LOCAL_LINK = Pattern.compile("^\\[([^\\]\\n]+)\\]\\(((?:/|~/)[^\\s)]*)\\)");
    private static final Pattern ESCAPED = Pattern.compile("[\\\\`*_\\[\\]()#|>-]");
    /** A bare url, or a bead-id shape: factory-setup.21, demo-1.2, bd-a1b. */
    private static final Pattern PLAIN = Pattern.compile(
            "(https?://[^\\s<>()]+[^\\s<>().,;:!?'\"])|((?<![\\w./-])[a-z][a-z0-9]*(?:-[a-z0-9]+)+(?:\\.\\d+)+|(?<![\\w./-])[a-z]+-[a-z0-9]{3}(?![\\w-]))");

    private void inline(String src, boolean noLinks) {
        StringBuilder plain = new StringBuilder();
        int i = 0;
        while (i < src.length()) {
            char c = src.charAt(i);
            if (c == '\\' && i + 1 < src.length() && ESCAPED.matcher(String.valueOf(src.charAt(i + 1))).matches()) {
                plain.append(src.charAt(i + 1));
                i += 2;
                continue;
            }
            if (c == '`') {
                int n = 0;
                while (i + n < src.length() && src.charAt(i + n) == '`') n++;
                String fence = src.substring(i, i + n);
                int end = src.indexOf(fence, i + n);
                if (end > i) {
                    flush(plain, noLinks);
                    String code = src.substring(i + n, end);
                    if (code.length() > 2 && code.startsWith(" ") && code.endsWith(" ")) code = code.substring(1, code.length() - 1);
                    int s = text.length();
                    text.append(code);
                    styles.add(new Style(Style.Kind.CODE, s, text.length(), null));
                    i = end + n;
                    continue;
                }
                plain.append(fence);
                i += n;
                continue;
            }
            if (c == '[') {
                Matcher m = WEB_LINK.matcher(src.substring(i));
                Style.Kind kind = Style.Kind.LINK;
                if (!m.find()) {
                    m = LOCAL_LINK.matcher(src.substring(i));
                    kind = m.find() ? Style.Kind.LOCAL : null;
                }
                if (kind != null) {
                    flush(plain, noLinks);
                    int s = text.length();
                    inline(m.group(1), true);
                    styles.add(new Style(kind, s, text.length(), m.group(2)));
                    i += m.end();
                    continue;
                }
            }
            if (c == '*' && i + 1 < src.length() && src.charAt(i + 1) == '*') {
                int end = src.indexOf("**", i + 2);
                if (end > i + 2 && !Character.isWhitespace(src.charAt(i + 2))) {
                    flush(plain, noLinks);
                    int s = text.length();
                    inline(src.substring(i + 2, end), noLinks);
                    styles.add(new Style(Style.Kind.BOLD, s, text.length(), null));
                    i = end + 2;
                    continue;
                }
            }
            if ((c == '*' || c == '_') && i + 1 < src.length() && src.charAt(i + 1) != c && !Character.isWhitespace(src.charAt(i + 1))
                    && (i == 0 || Character.isWhitespace(src.charAt(i - 1)) || src.charAt(i - 1) == '(')) {
                int end = src.indexOf(c, i + 1);
                if (end > i + 1 && !Character.isWhitespace(src.charAt(end - 1))
                        && (end + 1 >= src.length() || " \t.,;:!?)".indexOf(src.charAt(end + 1)) >= 0)) {
                    flush(plain, noLinks);
                    int s = text.length();
                    inline(src.substring(i + 1, end), noLinks);
                    styles.add(new Style(Style.Kind.EM, s, text.length(), null));
                    i = end + 1;
                    continue;
                }
            }
            plain.append(c);
            i++;
        }
        flush(plain, noLinks);
    }

    /** Plain text, with bare urls and known bead ids found in it as links. */
    private void flush(StringBuilder plain, boolean noLinks) {
        if (plain.length() == 0) return;
        String t = plain.toString();
        plain.setLength(0);
        Matcher m = PLAIN.matcher(t);
        int last = 0;
        while (m.find()) {
            text.append(t, last, m.start());
            int s = text.length();
            text.append(m.group());
            if (m.group(1) != null && !noLinks) styles.add(new Style(Style.Kind.LINK, s, text.length(), m.group(1)));
            else if (m.group(2) != null && ref.test(m.group(2))) styles.add(new Style(Style.Kind.BEAD, s, text.length(), m.group(2)));
            last = m.end();
        }
        text.append(t, last, t.length());
    }

    /** The source lines of the blocks the range {@code [start, end)} touches: what a quote carries. */
    String sourceOf(int start, int end) {
        StringBuilder out = new StringBuilder();
        for (Block b : blocks) {
            if (b.end <= start || b.start >= end) continue;
            if (out.length() > 0) out.append("\n\n");
            out.append(b.source);
        }
        return out.toString();
    }
}
