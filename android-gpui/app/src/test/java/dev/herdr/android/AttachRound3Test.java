package dev.herdr.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.junit.Test;

/**
 * Round 3 of the image-attach review: scenarios that run against the existing APIs (they fail on
 * 1928a0c as they stand) and the source-level wiring of the Android-only paths.
 */
public final class AttachRound3Test {
    private static String source(String name) throws Exception {
        return new String(Files.readAllBytes(new File("src/main/java/dev/herdr/android/" + name + ".java").toPath()),
                StandardCharsets.UTF_8);
    }

    private static String section(String text, String start, String end) {
        int first = text.indexOf(start);
        assertTrue(start, first >= 0);
        int last = text.indexOf(end, first + start.length());
        assertTrue(end, last > first);
        return text.substring(first, last);
    }

    @Test public void anAuthorityWithAUserQualifierIsStillHerdrsOwn() {
        // content://10@dev.wolf.herdrgpui.capture/... names this app's provider for user 10.
        assertFalse(ShareUris.allowed("content", "10@dev.wolf.herdrgpui.capture", "dev.wolf.herdrgpui"));
        assertFalse(ShareUris.allowed("content", "0@dev.wolf.herdrgpui", "dev.wolf.herdrgpui"));
        assertTrue(ShareUris.allowed("content", "10@media", "dev.wolf.herdrgpui"));
    }

    @Test public void anAttachedPathSurvivesTheNextDictationPartialAndFinal() {
        ComposerDrafts drafts = new ComposerDrafts();
        drafts.switchTo("t1");
        drafts.dictationStarted();
        drafts.dictationPartial("describe this");
        drafts.appendAttachment("", null, "t1", "/run/t1/herdr-paste-1.png ");
        String partial = drafts.dictationPartial("describe this screenshot");
        assertTrue(partial, partial.contains("/run/t1/herdr-paste-1.png"));
        String done = drafts.dictationFinal("describe this screenshot please");
        assertTrue(done, done.contains("/run/t1/herdr-paste-1.png"));
        assertTrue(done, done.contains("please"));
    }

    @Test public void theCameraNeverCleansUpAnotherShotsFile() throws Exception {
        String activity = source("GpuiTerminalActivity");
        String camera = section(activity, "private void takePhoto()", "private void pasteImage()");
        assertFalse("a second shot must not erase the first, still queued", camera.contains("CaptureFiles.clear("));
        String result = section(activity, "@Override protected void onActivityResult(", "private void attachTo(");
        assertFalse("each job deletes only its own file", result.contains("CaptureFiles.clear("));
        assertTrue(result.contains("shot.delete()"));
        assertTrue("an empty OK result is reported", result.contains("shot.length() == 0") && result.contains("no picture"));
    }

    @Test public void aStaleConfirmationTakesOnlyItsOwnShare() throws Exception {
        String share = section(source("GpuiTerminalActivity"), "private void takeSharedImages()", "private final Runnable retrySharedImages");
        assertTrue("the dialog's own generation is bound to the take", share.contains("SharedImages.take(") && share.contains("generation"));
        assertTrue(share.contains("SharedImages.take(System.currentTimeMillis(), generation)"));
    }

    @Test public void validationDecodesTheWholeImageWithTheSystemDecoder() throws Exception {
        String attach = source("AttachImages");
        String reencoder = section(attach, "static final class Reencoder", "static final ImageAttach.Converter CONVERTER");
        assertTrue(reencoder.contains("ImageDecoder.decodeBitmap("));
        String copy = section(attach, "static File copyValidated(", "static final class Reencoder");
        assertTrue(copy.contains("ImageDecoder.decodeBitmap("));
        assertFalse("bounds alone admit a truncated body", copy.contains("inJustDecodeBounds"));
    }

    @Test public void shareUrisAreAuthorizedOneByOneAndStreamExtrasOutsideTheGrantAreIgnored() throws Exception {
        String share = source("ShareTargetActivity");
        assertTrue("each URI is checked before it is opened", share.contains("checkUriPermission("));
        assertFalse("a stream extra is never opened on its own", share.contains("found.add((Uri) stream)"));
        assertTrue("a malformed or uncovered stream is counted as refused", share.contains("refused[0]++"));
    }

    @Test public void aRefusedShareAlwaysTellsTheSenderEvenWithATerminalOpen() throws Exception {
        String activity = source("GpuiTerminalActivity");
        assertTrue(section(activity, "protected void onNewIntent(", "static final String EXTRA_FOCUS_HOST")
                .contains("SharedImages.takeNotice("));
    }

    @Test public void thePasteListenerDoesNoIoOnTheUiThread() throws Exception {
        String listener = section(source("ComposerBar"), "private void listenForImages(", "A session that a pane switch");
        assertFalse(listener.contains("copyValidated("));
        assertFalse(listener.contains("getContentResolver()"));
        assertTrue("the native fallback captures the original payload", listener.contains("edit.onReceiveContent(payload)"));
        String worker = section(source("GpuiTerminalActivity"), "private boolean queueImagePaste(", "protected void onActivityResult(");
        assertTrue("the cap applies before anything is copied", worker.contains("MAX_IMAGES"));
        assertTrue("the fallback retains the payload and its grant until the worker finishes",
                worker.contains("reachabilityFence(nativePaste)"));
    }

    @Test public void draftsAreScopedToTheHostThatOwnsThePane() throws Exception {
        String activity = source("GpuiTerminalActivity");
        assertTrue(activity.contains("composer.setPane(current == null ? \"\" : current.hostId"));
        assertTrue(source("ComposerBar").contains("drafts.setHost("));
        assertTrue("the completion appends under the host that received the image",
                activity.contains("composer.appendAttachment(target.host, target.revision, target.pane"));
    }
}
