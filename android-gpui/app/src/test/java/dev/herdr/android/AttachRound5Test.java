package dev.herdr.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

public final class AttachRound5Test {
    @Test public void theFieldKeepsTheWholeMixedPaste() throws Exception {
        String listener = section("ComposerBar", "private void listenForImages(", "A session that a pane switch");
        assertFalse("the listener must not consume and asynchronously reinsert part of a mixed clip",
                listener.contains("edit.onReceiveContent("));
        assertTrue("classification must check every advertised MIME type and reject non-URI items",
                listener.contains("AttachImages.isImageClip("));
    }

    @Test public void theExplicitPasteMenuDoesNotQueryProvidersOnTheUiThread() throws Exception {
        String menu = section("GpuiTerminalActivity", "private void pasteImage()", "protected void onActivityResult(");
        assertFalse("the menu callback blocks on the clipboard provider's MIME query",
                menu.contains("getContentResolver().getType("));
        assertTrue("the menu must submit resolution to the paste worker", menu.contains("queueImagePaste("));
    }

    @Test public void aChangedPaneOrDraftCancelsThePasteBeforeItCanApply() throws Exception {
        String activity = source("GpuiTerminalActivity");
        assertTrue("a paste discarded after a pane or draft change must report the interruption",
                activity.contains("Image paste cancelled: you switched panes"));
        String composer = source("ComposerBar");
        assertTrue("pane switches and draft edits must invalidate the same captured stamp",
                composer.contains("pasteDraftStamp") && composer.split("pasteDraftStamp\\+\\+", -1).length >= 3);
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
        append(drafts, original, "t1", "'/host-a/image.png' ");
        select(drafts, original, "t2");
        drafts.edit("plain text");

        ComposerDrafts restored = new ComposerDrafts();
        restored.restore(drafts.encode());
        assertEquals("'/host-a/image.png' ", select(restored, original, "t1"));
        assertEquals("plain text", select(restored, edited, "t2"));
        assertEquals("the old profile's saved attachment must not appear on the new computer",
                "", select(restored, edited, "t1"));
    }

    @Test public void aPendingUploadChecksTheSavedConnectionRevisionBeforePutting() throws Exception {
        String activity = source("GpuiTerminalActivity");
        String upload = section("GpuiTerminalActivity", "private void attachOnWorker(", "private void attached(");
        assertTrue("a profile edit keeps its ID, so ID alone cannot authorize a queued put",
                upload.contains("profileStillCurrent(target)") && upload.indexOf("profileStillCurrent(target)")
                        < upload.indexOf("RemoteNative.putFile("));
        assertTrue("the revision must come from the saved connection that opened the session",
                activity.contains("connectionRevision"));
    }

    // Run on the baseline as well, where the equivalent entry points only accept a host ID.
    private static String select(ComposerDrafts drafts, SavedRemoteHost host, String pane) throws Exception {
        try {
            Method bind = ComposerDrafts.class.getDeclaredMethod("setHost", String.class, String.class,
                    java.util.function.Predicate.class);
            bind.invoke(drafts, host.id, host.connectionRevision,
                    (java.util.function.Predicate<String>) encoded -> true);
        } catch (NoSuchMethodException baseline) {
            drafts.setHost(host.id);
        }
        return drafts.switchTo(pane);
    }

    private static void append(ComposerDrafts drafts, SavedRemoteHost host, String pane, String paths)
            throws Exception {
        try {
            Method append = ComposerDrafts.class.getDeclaredMethod("appendAttachment", String.class,
                    String.class, String.class, String.class);
            append.invoke(drafts, host.id, host.connectionRevision, pane, paths);
        } catch (NoSuchMethodException baseline) {
            drafts.appendAttachment(host.id, pane, paths);
        }
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
