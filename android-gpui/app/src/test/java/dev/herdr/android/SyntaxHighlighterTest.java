package dev.herdr.android;

import static dev.herdr.android.SyntaxHighlighter.*;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.List;
import org.junit.Test;

public class SyntaxHighlighterTest {
    private static List<List<Span>> run(Language language, String... lines) {
        return highlight(language, Arrays.asList(lines));
    }

    private static Span span(int start, int end, int kind) {
        return new Span(start, end, kind);
    }

    @Test public void languagesComeFromTheExtension() {
        assertSame(C_LIKE, forPath("/w/src/main.rs"));
        assertSame(C_LIKE, forPath("A.JAVA"));
        assertSame(HASH, forPath("pyproject.toml"));
        assertSame(HASH, forPath("/w/justfile"));
        assertSame(NIX, forPath("flake.nix"));
        assertSame(MARKDOWN, forPath("README.md"));
        assertSame(JSON, forPath("Cargo.lock"));
        assertSame(TEXT, forPath("/etc/hosts"));
        assertSame(TEXT, forPath(null));
    }

    @Test public void keywordsStringsNumbersTypesAndLineComments() {
        List<Span> spans = run(C_LIKE, "let x: Vec<u8> = \"a // b\"; // 42").get(0);
        assertEquals(Arrays.asList(span(0, 3, KEYWORD), span(7, 10, TYPE), span(17, 25, STRING),
                span(27, 32, COMMENT)), spans);
        assertEquals(Arrays.asList(span(4, 5, NUMBER), span(8, 12, NUMBER)), run(C_LIKE, "x = 1 + 3.14;").get(0));
    }

    @Test public void numbersInsideNamesAreNotNumbers() {
        List<Span> spans = run(C_LIKE, "u8 x2 0x1f").get(0);
        assertEquals(Arrays.asList(span(6, 10, NUMBER)), spans);
    }

    @Test public void blockCommentsAndLongStringsCarryAcrossLines() {
        List<List<Span>> lines = run(C_LIKE, "a /* one", "two", "three */ fn");
        assertEquals(Arrays.asList(span(2, 8, COMMENT)), lines.get(0));
        assertEquals(Arrays.asList(span(0, 3, COMMENT)), lines.get(1));
        assertEquals(Arrays.asList(span(0, 8, COMMENT), span(9, 11, KEYWORD)), lines.get(2));
        List<List<Span>> python = run(HASH, "s = \"\"\"doc", "more\"\"\" # c");
        assertEquals(Arrays.asList(span(4, 10, STRING)), python.get(0));
        assertEquals(Arrays.asList(span(0, 7, STRING), span(8, 11, COMMENT)), python.get(1));
    }

    @Test public void escapedQuotesStayInTheString() {
        assertEquals(Arrays.asList(span(0, 6, STRING)), run(C_LIKE, "\"a\\\"b\" ").get(0));
    }

    @Test public void rustLifetimesAreNotStrings() {
        List<Span> spans = run(C_LIKE, "fn f<'a>(x: &'a str) -> char { 'c' }").get(0);
        assertTrue(spans.contains(span(31, 34, STRING)));
        for (Span s : spans) assertTrue(s.toString(), s.kind != STRING || s.start == 31);
    }

    @Test public void markdownHeadingsFencesAndInlineCode() {
        List<List<Span>> lines = run(MARKDOWN, "# Title", "use `hc` now", "```sh", "echo # not a heading", "```", "## Next");
        assertEquals(Arrays.asList(span(0, 7, HEADING)), lines.get(0));
        assertEquals(Arrays.asList(span(4, 8, STRING)), lines.get(1));
        assertEquals(Arrays.asList(span(0, 5, COMMENT)), lines.get(2));
        assertEquals(Arrays.asList(span(0, 20, STRING)), lines.get(3));
        assertEquals(Arrays.asList(span(0, 7, HEADING)), lines.get(5));
    }

    @Test public void plainTextIsLeftPlain() {
        for (Span s : run(TEXT, "Hello World").get(0)) assertTrue(s.kind != TYPE && s.kind != KEYWORD);
    }
}
