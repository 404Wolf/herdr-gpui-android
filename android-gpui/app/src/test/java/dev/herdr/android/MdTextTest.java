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

    @Test public void barePathsAndPathCodeSpansOpenTheFile() {
        MdText md = MdText.of("Details: /home/wolf/research/health-deploy-notes/. See `~/deacon/work/factory.md`, "
                + "~/notes/a.txt and /tmp. Not and/or, 1/2, https://example.com/a/b, `rm -rf /x` or /.", null);
        assertEquals(List.of("/home/wolf/research/health-deploy-notes/|/home/wolf/research/health-deploy-notes/",
                "~/deacon/work/factory.md|~/deacon/work/factory.md", "~/notes/a.txt|~/notes/a.txt", "/tmp|/tmp"),
                styled(md, MdText.Style.Kind.LOCAL));
        assertEquals(List.of("https://example.com/a/b|https://example.com/a/b"), styled(md, MdText.Style.Kind.LINK));
        // A link's own text never nests a path link.
        assertTrue(styled(MdText.of("[/etc/hosts](https://example.com/h)", null), MdText.Style.Kind.LOCAL).isEmpty());
    }

    @Test public void thePanelOpensAFileOnTheMachineItsTextCameFrom() throws java.io.IOException {
        String panel = new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(
                "src/main/java/dev/herdr/android/WorkPanel.java")), java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(panel.contains("void openFile(String hostId, String path);"));
        assertTrue(panel.contains("view.setText(spans(md, hostId), TextView.BufferType.SPANNABLE);"));
        assertTrue(panel.contains("case LOCAL: host.openFile(source, st.value); break;"));
        assertFalse(panel.contains("openPath("));
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

    /** t2kg 11-23: the drive-backups Overview's Links lines (one path per line, no blank lines between). */
    @Test public void linesOfLinksStayOnTheirOwnLinesAndEachPathOpens() {
        MdText md = MdText.of("### Links\nPlan and limits: /home/wolf/drive-backups/HANDOFF.md\n"
                + "Goals: /home/wolf/drive-backups/GOALS.md\n"
                + "Archive work brief: /home/wolf/deacon/work/backup-catalog/BRIEF.md\n"
                + "ZFS list brief: /home/wolf/drive-backups/zfs-export/BRIEF.md\n"
                + "Tasks: bd show dbk-drive-backups (store /home/wolf/beads/drive-backups)\n", null);
        assertTrue(md.text().contains("HANDOFF.md\nGoals: "));
        assertEquals(List.of("/home/wolf/drive-backups/HANDOFF.md|/home/wolf/drive-backups/HANDOFF.md",
                "/home/wolf/drive-backups/GOALS.md|/home/wolf/drive-backups/GOALS.md",
                "/home/wolf/deacon/work/backup-catalog/BRIEF.md|/home/wolf/deacon/work/backup-catalog/BRIEF.md",
                "/home/wolf/drive-backups/zfs-export/BRIEF.md|/home/wolf/drive-backups/zfs-export/BRIEF.md",
                "/home/wolf/beads/drive-backups|/home/wolf/beads/drive-backups"),
                styled(md, MdText.Style.Kind.LOCAL));
    }
}
