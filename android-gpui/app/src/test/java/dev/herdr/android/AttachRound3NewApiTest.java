package dev.herdr.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;

/** Round 3 scenarios that need the new host-scoped draft, generation-bound share and per-URI grant APIs. */
public final class AttachRound3NewApiTest {
    @Test public void aPathCommittedForOneHostsT1NeverLoadsOrSendsUnderAnotherHostsT1() {
        ComposerDrafts drafts = new ComposerDrafts();
        drafts.setHost("hostA", "revision-a", encoded -> true);
        drafts.switchTo("t1");
        drafts.appendAttachment("hostA", "revision-a", "t1", "/a/files/t1/herdr-paste-1.png ");
        drafts.setHost("hostB");
        drafts.switchTo("t1");
        assertEquals("B's t1 has its own, empty draft", "", drafts.text());
        assertEquals(null, drafts.outgoing());
        drafts.edit("hello from B");
        drafts.setHost("hostA", "revision-a", encoded -> true);
        drafts.switchTo("t1");
        assertEquals("/a/files/t1/herdr-paste-1.png ", drafts.text());
    }

    @Test public void anAttachmentForAHostNotOnScreenGoesToThatHostsDraft() {
        ComposerDrafts drafts = new ComposerDrafts();
        drafts.setHost("hostB");
        drafts.switchTo("t1");
        drafts.appendAttachment("hostA", "revision-a", "t1", "/a/x.png ");
        assertEquals("", drafts.text());
        drafts.setHost("hostA", "revision-a", encoded -> true);
        drafts.switchTo("t1");
        assertEquals("/a/x.png ", drafts.text());
    }

    @Test public void hostScopedDraftsSurviveProcessDeathAndStayApart() {
        ComposerDrafts first = new ComposerDrafts();
        first.setHost("hostA", "revision-a", encoded -> true);
        first.switchTo("t1");
        first.appendAttachment("hostA", "revision-a", "t1", "/a/x.png ");
        first.setHost("hostB");
        first.switchTo("t1");
        first.edit("b text");
        String saved = first.encode();
        ComposerDrafts second = new ComposerDrafts();
        second.restore(saved);
        second.setHost("hostB");
        second.switchTo("t1");
        assertEquals("b text", second.text());
        second.setHost("hostA", "revision-a", encoded -> true);
        second.switchTo("t1");
        assertEquals("/a/x.png ", second.text());
    }

    @Test public void draftsSavedBeforeThisChangeMigrateToTheHostOnScreenFirst() {
        // The old store had one draft per pane id and no host.
        ComposerDrafts drafts = new ComposerDrafts();
        drafts.restore("{\"t1\":\"old draft\",\"t2\":\"other\"}");
        java.util.concurrent.atomic.AtomicReference<String> saved = new java.util.concurrent.atomic.AtomicReference<>();
        drafts.setHost("hostA", "revision-a", encoded -> {
            saved.set(encoded);
            return true;
        });
        drafts.switchTo("t1");
        assertEquals("old draft", drafts.text());
        drafts.setHost("hostB");
        drafts.switchTo("t1");
        assertEquals("", drafts.text());
        // Restart from what migration wrote, without a subsequent edit or save.
        ComposerDrafts next = new ComposerDrafts();
        next.restore(saved.get());
        next.setHost("hostB");
        next.switchTo("t1");
        assertEquals("", next.text());
        next.setHost("hostA", "revision-a", encoded -> true);
        next.switchTo("t2");
        assertEquals("other", next.text());
    }

    @Test public void aConfirmationTakesOnlyTheFilesOfItsOwnShare() throws Exception {
        File a = File.createTempFile("share", ".bin");
        File b = File.createTempFile("share", ".bin");
        SharedImages.discard();
        SharedImages.offer(new ArrayList<>(Arrays.asList(a)), 0, 100);
        int older = SharedImages.generation();
        SharedImages.offer(new ArrayList<>(Arrays.asList(b)), 0, 200);
        // The older dialog's Attach finds a newer share: it takes nothing and leaves B waiting.
        assertTrue(SharedImages.take(300, older).isEmpty());
        assertTrue(SharedImages.waiting(300));
        assertEquals(Arrays.asList(b), SharedImages.take(300, SharedImages.generation()));
        b.delete();
    }

    @Test public void aRefusalNoticeIsTakenOnceWhenNothingWaits() {
        SharedImages.discard();
        SharedImages.offer(new ArrayList<File>(), 2, 10);
        assertTrue(SharedImages.takeNotice(10).contains("2 shared items could not be used"));
        assertEquals(null, SharedImages.takeNotice(10));
    }

    @Test public void eachUriIsAuthorizedOnItsOwnBeforeItIsOpened() {
        List<String[]> uris = Arrays.asList(
                new String[] {"content", "media"}, new String[] {"content", "other"},
                new String[] {"file", ""}, new String[] {"content", "10@dev.wolf.herdrgpui.capture"});
        // Only "media" holds the sender's grant; "other" is a content URI the sender was not granted.
        List<Integer> ok = ShareUris.filter(uris, "dev.wolf.herdrgpui", i -> i == 0);
        assertEquals(Arrays.asList(0), ok);
        assertFalse(ShareUris.filter(uris, "dev.wolf.herdrgpui", i -> false).contains(0));
    }
}
