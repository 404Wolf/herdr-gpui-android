package dev.herdr.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

public final class AttachRound5Test {
    @Test public void theFieldKeepsTheWholeMixedPaste() throws Exception {
        String listener = section("ComposerBar", "private void listenForImages(", "A session that a pane switch");
        assertTrue("a clip already known to be mixed goes straight to Android",
                listener.contains("if (!AttachImages.isImageClip(payload.getClip())) return payload;"));
    }

    @Test public void theExplicitPasteMenuDoesNotQueryProvidersOnTheUiThread() throws Exception {
        String menu = section("GpuiTerminalActivity", "private void pasteImage()", "protected void onActivityResult(");
        assertFalse("the menu callback blocks on the clipboard provider's MIME query",
                menu.contains("getContentResolver().getType("));
        assertTrue("the menu must submit resolution to the paste worker", menu.contains("queueImagePaste("));
    }

    @Test public void aChangedPaneOrDraftCancelsThePasteBeforeItCanApply() throws Exception {
        String composer = source("ComposerBar");
        assertTrue("a paste discarded after a pane or draft change must report the interruption",
                composer.contains("Image paste cancelled: you switched panes"));
        assertTrue("switching panes or editing the draft must invalidate a pending paste",
                composer.contains("paneStamp != pastePaneStamp") && composer.contains("draftStamp != pasteDraftStamp"));
    }

    @Test public void aRepointedProfileKeepsPlainDraftsAndHidesSavedAttachmentDrafts() throws Exception {
        SavedRemoteHost original = SavedRemoteHost.create("Host", "tcp", "first.test", 22,
                "", "", "", "user", "default");
        SavedRemoteHost other = SavedRemoteHost.create("Host", "tcp", "second.test", 22,
                "", "", "", "user", "default");
        List<SavedRemoteHost> saved = new ArrayList<>();
        saved.add(original);
        SavedRemoteHost edited = RemoteHostStore.replaceProfile(saved, original, other);
        assertEquals(original.id, edited.id);
        assertFalse(original.connectionRevision.equals(edited.connectionRevision));

        ComposerDrafts drafts = new ComposerDrafts();
        select(drafts, original, "t1");
        append(drafts, original, "t1", "'/host-a/herdr-paste-1.png' ");
        select(drafts, original, "t2");
        drafts.edit("plain text");

        ComposerDrafts restored = new ComposerDrafts();
        restored.restore(drafts.encode());
        assertEquals("'/host-a/herdr-paste-1.png' ", select(restored, original, "t1"));
        assertEquals("plain text", select(restored, edited, "t2"));
        assertEquals("the old profile's saved attachment must not appear on the new computer",
                "", select(restored, edited, "t1"));
        assertEquals("a late completion must not put the old path back", null,
                restored.appendAttachment(original.id, original.connectionRevision, "t1", "/old.png "));
    }

    @Test public void aPendingUploadChecksTheSavedConnectionRevisionBeforePutting() throws Exception {
        String activity = source("GpuiTerminalActivity");
        String upload = section("GpuiTerminalActivity", "private void attachOnWorker(", "private void attached(");
        assertTrue("a profile edit keeps its ID, so ID alone cannot authorize a queued put",
                upload.contains("requireAttachmentCurrent(target, pasteCurrent)")
                        && upload.lastIndexOf("requireAttachmentCurrent(target, pasteCurrent)")
                        < upload.indexOf("RemoteNative.putFile("));
        assertTrue("the revision must come from the saved connection that opened the session",
                activity.contains("RemoteHostStore.connectionRevision(target.host)")
                        && activity.contains("next.connectionRevision = host.connectionRevision"));
    }

    @Test public void anUnversionedPreviewPathIsNeverAdoptedAsPlainText() throws Exception {
        ComposerDrafts drafts = new ComposerDrafts();
        drafts.restore(new org.json.JSONObject()
                .put(ComposerDrafts.key("host", "t1"), "look at '/state & photos/herdr-core/default/files/t1/herdr-paste-123-2.png' ")
                .put(ComposerDrafts.key("host", "t2"), "plain text")
                .toString());
        drafts.setHost("host", "new-revision", encoded -> true);
        assertEquals("look at  ", drafts.switchTo("t1"));
        assertEquals("plain text", drafts.switchTo("t2"));
    }

    private static String select(ComposerDrafts drafts, SavedRemoteHost host, String pane) {
        drafts.setHost(host.id, host.connectionRevision, encoded -> true);
        return drafts.switchTo(pane);
    }

    private static void append(ComposerDrafts drafts, SavedRemoteHost host, String pane, String paths) {
        drafts.appendAttachment(host.id, host.connectionRevision, pane, paths);
    }

    private static String section(String name, String start, String end) throws Exception {
        String source = source(name);
        int first = source.indexOf(start);
        assertTrue(start, first >= 0);
        int last = source.indexOf(end, first + start.length());
        assertTrue(end, last > first);
        return source.substring(first, last);
    }

    private static String source(String name) throws Exception {
        return new String(Files.readAllBytes(Paths.get("src/main/java/dev/herdr/android", name + ".java")),
                StandardCharsets.UTF_8);
    }
}
