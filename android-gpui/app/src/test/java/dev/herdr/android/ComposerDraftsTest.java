package dev.herdr.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Test;

public final class ComposerDraftsTest {
    private static final String STAGING = "/state/herdr-core/default/files/t1/";
    private static final String IMAGE = STAGING + "herdr-paste-123.png";

    @Test
    public void repointingRemovesAParenthesizedShellQuotedPath() {
        String path = "/state & 'photos/herdr-core/default/files/t1/herdr-paste-123-2.png";
        assertAttachmentRemoved(Collections.singletonList(path),
                "Compare (" + ImageAttach.quotePath(path) + ") with [" + path + "].", "Compare () with [].");
    }

    @Test
    public void repointingKeepsQuotesTheUserAdded() {
        assertAttachmentRemoved(Collections.singletonList(IMAGE),
                "Compare \"" + IMAGE + "\" and '" + IMAGE + "' to the last build.",
                "Compare \"\" and '' to the last build.");
    }

    @Test
    public void repointingKeepsATrailingComma() {
        assertAttachmentRemoved(Collections.singletonList(IMAGE), "Compare " + IMAGE + ", then review the result.",
                "Compare , then review the result.");
    }

    @Test
    public void repointingKeepsATrailingPeriod() {
        assertAttachmentRemoved(Collections.singletonList(IMAGE), "Compare " + IMAGE + ".", "Compare .");
    }

    @Test
    public void repointingFindsPathsAtBothEndsOfTheDraft() {
        String second = STAGING + "herdr-paste-456.gif";
        assertAttachmentRemoved(Arrays.asList(IMAGE, second), IMAGE + ":compare with=" + second, ":compare with=");
    }

    @Test
    public void repointingRemovesTwoAdjacentPathsAndKeepsAdjacentText() {
        String second = STAGING + "herdr-paste-456-2.webp";
        String reference = "/reference/herdr-paste-456-2.webp";
        assertAttachmentRemoved(Arrays.asList(IMAGE, second),
                "before/notes:" + IMAGE + second + "after " + reference, "before/notes:after " + reference);
    }

    private static void assertAttachmentRemoved(List<String> paths, String edited, String remaining) {
        ComposerDrafts drafts = new ComposerDrafts();
        drafts.setHost("host", "original", encoded -> true);
        drafts.switchTo("t1");
        drafts.appendAttachment("host", "original", "t1", paths);
        drafts.edit(edited);
        assertEquals("original", drafts.draftRevision());

        ComposerDrafts restored = new ComposerDrafts();
        restored.setHost("host", "repointed", encoded -> true);
        restored.restore(drafts.encode());
        assertEquals(remaining, restored.switchTo("t1"));
        assertNull(restored.draftRevision());

        drafts.setHost("host", "repointed", encoded -> true);
        assertEquals(remaining, drafts.text());
        assertNull(drafts.draftRevision());
    }

    @Test
    public void cuttingAndPastingAnAttachmentKeepsTheReorderedDraftOnReopen() {
        String prompt = "Explain what changed in the second panel for the release notes.";
        String path = "/state/herdr-core/default/files/t1/herdr-paste-123.png";
        ComposerDrafts drafts = new ComposerDrafts();
        drafts.setHost("host", "original", encoded -> true);
        drafts.switchTo("t1");
        drafts.edit(prompt);
        drafts.appendAttachment("host", "original", "t1", Collections.singletonList(path));
        drafts.edit(prompt);
        assertNull(drafts.draftRevision());

        String reordered = path + " " + prompt;
        drafts.edit(reordered);
        ComposerDrafts restored = new ComposerDrafts();
        restored.restore(drafts.encode());
        restored.setHost("host", "original", encoded -> true);
        assertEquals(reordered, restored.switchTo("t1"));
        assertEquals("original", restored.draftRevision());
    }

    @Test
    public void repointingKeepsThePromptAndRemovesEveryGeneratedPath() {
        List<String> paths = Arrays.asList(
                "/state & 'photos/herdr-core/default/files/t1/herdr-paste-123-2.png",
                "/other/files/t1/herdr-paste-124.jpg");
        String prompt = "Compare " + ImageAttach.quotePaths(paths) + "with /tmp/reference.png.\nKeep the 'second panel'.";
        String expected = "Compare   with /tmp/reference.png.\nKeep the 'second panel'.";
        ComposerDrafts drafts = new ComposerDrafts();
        drafts.setHost("host", "original", encoded -> true);
        drafts.switchTo("t1");
        drafts.edit("Compare");
        drafts.appendAttachment("host", "original", "t1", paths);
        drafts.edit(prompt);
        String saved = drafts.encode();

        drafts.setHost("host", "repointed", encoded -> true);
        assertEquals(expected, drafts.text());
        assertNull(drafts.draftRevision());

        ComposerDrafts restored = new ComposerDrafts();
        restored.setHost("host", "repointed", encoded -> true);
        restored.restore(saved);
        assertEquals(expected, restored.switchTo("t1"));
        assertNull(restored.draftRevision());
    }

    @Test
    public void clearingAnAttachmentOnlyDraftLeavesRoomForAllTextDrafts() throws Exception {
        ComposerDrafts drafts = new ComposerDrafts();
        drafts.setHost("host", "original", encoded -> true);
        drafts.switchTo("first");
        drafts.edit("first unsent prompt");
        drafts.switchTo("image");
        drafts.appendAttachment("host", "original", "image", Collections.singletonList(IMAGE));
        drafts.edit("");
        for (int i = 1; i < ComposerDrafts.MAX_PANES; i++) {
            drafts.switchTo("p" + i);
            drafts.edit("prompt " + i);
        }
        String saved = drafts.encode();
        assertEquals(ComposerDrafts.MAX_PANES, new org.json.JSONObject(saved).length());
        assertEquals("first unsent prompt", drafts.switchTo("first"));

        ComposerDrafts restored = new ComposerDrafts();
        restored.restore(saved);
        restored.setHost("host", "original", encoded -> true);
        assertEquals("first unsent prompt", restored.switchTo("first"));
    }

    @Test
    public void adoptingAPreAttachmentPlainDraftPreservesEveryCharacter() throws Exception {
        String prompt = " \nCompare ('/notes and the reference " + IMAGE + "') today.\n  ";
        ComposerDrafts drafts = new ComposerDrafts();
        drafts.restore(new org.json.JSONObject().put("t1", prompt).toString());
        drafts.setHost("host", "original", encoded -> true);
        assertEquals(prompt, drafts.switchTo("t1"));
        assertNull(drafts.draftRevision());
        org.json.JSONObject saved = new org.json.JSONObject(drafts.encode());
        assertEquals(prompt, saved.getString(ComposerDrafts.key("host", "t1")));
        assertFalse(saved.has("t1"));
    }

    @Test
    public void adoptingALegacyDraftKeepsItsTextAndRemovesUnownedPaths() throws Exception {
        ComposerDrafts drafts = new ComposerDrafts();
        drafts.restore(new org.json.JSONObject().put("t1",
                "Please inspect '/state & '\\''photos/herdr-core/default/files/t1/herdr-paste-123.png'\n"
                        + "and the screenshot.").toString());
        drafts.setHost("host", "original", encoded -> true);
        assertEquals("Please inspect \nand the screenshot.", drafts.switchTo("t1"));
        assertNull(drafts.draftRevision());
    }

    @Test
    public void draftsArePerPane() {
        ComposerDrafts drafts = new ComposerDrafts();
        drafts.switchTo("a");
        drafts.edit("hello");
        assertEquals("", drafts.switchTo("b"));
        drafts.edit("other");
        assertEquals("hello", drafts.switchTo("a"));
        assertEquals("other", drafts.switchTo("b"));
    }

    @Test
    public void oldestPaneDraftIsDroppedPastTheBound() {
        ComposerDrafts drafts = new ComposerDrafts();
        for (int i = 0; i <= ComposerDrafts.MAX_PANES; i++) {
            drafts.switchTo("p" + i);
            drafts.edit("t" + i);
        }
        assertEquals("", drafts.switchTo("p0"));
        assertEquals("t1", drafts.switchTo("p1"));
    }

    @Test
    public void partialsReplaceTheUtteranceNotAppend() {
        ComposerDrafts drafts = new ComposerDrafts();
        drafts.switchTo("a");
        drafts.edit("Fix");
        drafts.dictationStarted();
        assertTrue(drafts.dictating());
        assertEquals("Fix the", drafts.dictationPartial("the"));
        assertEquals("Fix the bug", drafts.dictationPartial("the bug"));
        assertEquals("Fix the bug now", drafts.dictationFinal(" the bug now "));
        assertFalse(drafts.dictating());
        // A second utterance builds on the committed text.
        drafts.dictationStarted();
        assertEquals("Fix the bug now please", drafts.dictationPartial("please"));
    }

    @Test
    public void thePartialStartsAfterTheSettledDraft() {
        ComposerDrafts drafts = new ComposerDrafts();
        drafts.switchTo("a");
        drafts.edit("Fix");
        assertEquals(-1, drafts.partialFrom());
        drafts.dictationStarted();
        assertEquals(-1, drafts.partialFrom());
        drafts.dictationPartial("the bug");
        assertEquals(4, drafts.partialFrom());
        assertEquals("the bug", drafts.text().substring(drafts.partialFrom()));
        drafts.dictationFinal("the bug");
        assertEquals(-1, drafts.partialFrom());
    }

    @Test
    public void anEndedUtteranceKeepsItsLastPartial() {
        ComposerDrafts drafts = new ComposerDrafts();
        drafts.switchTo("a");
        drafts.edit("keep");
        drafts.dictationStarted();
        drafts.dictationPartial("these");
        drafts.dictationPartial("these words");
        assertEquals("keep these words", drafts.dictationEnded());
        assertFalse(drafts.dictating());
        // The next utterance appends after the kept words, not over them.
        drafts.dictationStarted();
        assertEquals("keep these words too", drafts.dictationPartial("too"));
    }

    @Test
    public void switchingPanesEndsTheUtteranceBase() {
        ComposerDrafts drafts = new ComposerDrafts();
        drafts.switchTo("a");
        drafts.dictationStarted();
        drafts.switchTo("b");
        assertFalse(drafts.dictating());
    }

    @Test
    public void outgoingTrimsAndClearsOnlyWhenSent() {
        ComposerDrafts drafts = new ComposerDrafts();
        drafts.switchTo("a");
        assertNull(drafts.outgoing());
        drafts.edit("  \n");
        assertNull(drafts.outgoing());
        drafts.edit("\n two\nlines \n");
        assertEquals("two\nlines", drafts.outgoing());
        assertEquals("\n two\nlines \n", drafts.text());
        drafts.sent();
        assertEquals("", drafts.text());
    }

    @Test
    public void draftsSurviveARestartWithoutHidingWhatWasTypedSince() {
        ComposerDrafts before = new ComposerDrafts();
        before.switchTo("a");
        before.edit("kept\nacross a kill");
        before.switchTo("b");
        before.edit("old b");
        ComposerDrafts after = new ComposerDrafts();
        after.switchTo("b");
        after.edit("typed before the restore");
        after.restore(before.encode());
        assertEquals("typed before the restore", after.text());
        after.switchTo("a");
        assertEquals("kept\nacross a kill", after.text());
        ComposerDrafts damaged = new ComposerDrafts();
        damaged.restore("{not json");
        damaged.switchTo("a");
        assertEquals("", damaged.text());
    }

    @Test
    public void aRollbackPutsEveryDraftBackAsItWas() {
        ComposerDrafts drafts = new ComposerDrafts();
        drafts.switchTo("a");
        drafts.edit("before " + DictationToken.of("c1"));
        Object snap = drafts.snapshot();
        assertEquals("a", drafts.placeDictation("c1", "spoken"));
        drafts.append("b", "elsewhere");
        drafts.rollback(snap);
        assertEquals("before " + DictationToken.of("c1"), drafts.text());
        assertEquals("", drafts.switchTo("b"));
    }
}
