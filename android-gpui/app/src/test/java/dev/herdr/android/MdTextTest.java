package dev.herdr.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

/** The panel's Markdown: text with styled ranges, never markup (synthetic docs). */
public final class MdTextTest {
    private static List<String> styled(MdText md, MdText.Style.Kind kind) {
        List<String> out = new ArrayList<>();
        for (MdText.Style s : md.styles) if (s.kind == kind) out.add(md.text().substring(s.start, s.end) + (s.value == null ? "" : "|" + s.value));
        return out;
    }

    @Test public void headingsParagraphsAndInlinesBecomeTextAndRanges() {
        MdText md = MdText.of("# factory\nThe **phone** half, *nicely*.\nSecond line with `code`.\n\n### Next\n- one\n- two", null);
        assertEquals("factory\n\nThe phone half, nicely.\nSecond line with code.\n\nNext\n\n• one\n• two", md.text());
        assertEquals(List.of("factory|1", "Next|3"), styled(md, MdText.Style.Kind.HEADING));
        assertEquals(List.of("phone"), styled(md, MdText.Style.Kind.BOLD));
        assertEquals(List.of("nicely"), styled(md, MdText.Style.Kind.EM));
        assertEquals(List.of("code"), styled(md, MdText.Style.Kind.CODE));
        assertEquals(4, md.blocks.size());
        assertEquals("### Next", md.blocks.get(2).source);
    }

    @Test public void rawHtmlAndEscapesAreShownAsText() {
        MdText md = MdText.of("<script>alert(1)</script> \\*not em\\*", null);
        assertEquals("<script>alert(1)</script> *not em*", md.text());
        assertTrue(md.styles.isEmpty());
    }

    @Test public void onlyWebAndLocalPathLinksAreLinksAndBeadsOnlyWhenTheyExist() {
        MdText md = MdText.of("See [the spec](https://example.com/s) and [the doc](~/deacon/work/factory.md), "
                + "not [this](javascript:alert(1)). Bare https://example.com/x. Beads factory-setup.21 and demo-9.9.",
                id -> id.equals("factory-setup.21"));
        assertEquals(List.of("the spec|https://example.com/s", "https://example.com/x|https://example.com/x"),
                styled(md, MdText.Style.Kind.LINK));
        assertEquals(List.of("the doc|~/deacon/work/factory.md"), styled(md, MdText.Style.Kind.LOCAL));
        assertEquals(List.of("factory-setup.21|factory-setup.21"), styled(md, MdText.Style.Kind.BEAD));
        assertTrue(md.text().contains("[this](javascript:alert(1))"));
    }

    @Test public void codeFencesSketchesAndTablesAreVerbatimMonospace() {
        MdText md = MdText.of("```sh\nrm -rf **x**\n```\n\n+---+\n| a |\n+---+\n\n| a | bb |\n|---|---|\n| ccc | d |", null);
        assertEquals(List.of("rm -rf **x**", "+---+\n| a |\n+---+", "a    bb\nccc  d"), styled(md, MdText.Style.Kind.PRE));
        assertEquals(List.of("code", "art", "table"),
                List.of(md.blocks.get(0).kind, md.blocks.get(1).kind, md.blocks.get(2).kind));
    }

    @Test public void listsNestAndNumberAndQuotesAreMarked() {
        MdText md = MdText.of("1. first\n2. second\n   - inner\n\n> quoted **line**", null);
        assertEquals("1. first\n2. second\n• inner\n\nquoted line", md.text());
        assertEquals(List.of("quoted line"), styled(md, MdText.Style.Kind.QUOTE));
        assertEquals(List.of("line"), styled(md, MdText.Style.Kind.BOLD));
        assertFalse(styled(md, MdText.Style.Kind.BULLET).isEmpty());
    }

    @Test public void aQuoteCarriesTheSourceOfTheBlocksItTouches() {
        MdText md = MdText.of("# Title\n\nFirst **para**.\n\nSecond para.", null);
        int at = md.text().indexOf("para.");
        assertEquals("First **para**.", md.sourceOf(at, at + 3));
        assertEquals("First **para**.\n\nSecond para.", md.sourceOf(at, md.text().length()));
    }
}
