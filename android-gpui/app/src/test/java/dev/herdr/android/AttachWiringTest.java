package dev.herdr.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;

/** The attach entry points are reachable: the control, the draft, the share sheet and the camera. */
public final class AttachWiringTest {
    private static ControlLine.State idle(boolean attach) {
        ControlLine.State in = new ControlLine.State();
        in.talks = java.util.Collections.singletonList(new GhostText.Control("talk", "talk", "dim"));
        in.attach = attach;
        return in;
    }

    private static String ids(List<GhostText.Control> line) {
        StringBuilder out = new StringBuilder();
        for (GhostText.Control c : line) out.append(out.length() == 0 ? "" : " ").append(c.id);
        return out.toString();
    }

    @Test public void theLineLeadsWithImgOnlyWhereThePaneCanTakeAnImage() {
        assertEquals("type talk send", ids(ControlLine.controls(idle(false), Integer.MAX_VALUE)));
        assertEquals("attach type talk send", ids(ControlLine.controls(idle(true), Integer.MAX_VALUE)));
        assertEquals("img", ControlLine.controls(idle(true), Integer.MAX_VALUE).get(0).spans[0]);
        assertTrue(ControlLine.tappable(ControlLine.ATTACH));
    }

    @Test public void imgIsTheFirstThingToGoOnANarrowGridAndNothingElseMoves() {
        for (int cols = 5; cols <= 140; cols++) {
            ControlLine.State with = idle(true), without = idle(false);
            with.queued = without.queued = 2;
            with.clearable = without.clearable = true;
            List<GhostText.Control> a = ControlLine.controls(with, cols);
            List<GhostText.Control> b = ControlLine.controls(without, cols);
            String at = "at " + cols + ": " + ids(a);
            String rest = ids(a).replaceFirst("^attach ", "");
            assertEquals(at, ids(b), rest);
            if (ids(a).startsWith("attach")) assertTrue(at, ControlLine.width(a) + ControlLine.MARGIN <= cols);
            assertTrue(at, ids(a).contains("clear") && ids(a).contains("queue") && ids(a).contains("talk"));
        }
        // The cover screen (~46 columns) idle keeps it; with a queue and a clear it leaves room for them.
        assertTrue(ids(ControlLine.controls(idle(true), 46)).startsWith("attach"));
    }

    @Test public void theLadderStaysWidestFirstWithImgAndStaysUnderTheRenderersLimit() {
        ControlLine.State in = idle(true);
        in.queued = 3;
        in.clearable = true;
        in.undoWide = "undo 5s";
        in.undoNarrow = "undo";
        in.talks = Arrays.asList(new GhostText.Control("talk", "● rec 0:05", "danger"),
                new GhostText.Control("talk", "0:05", "danger"), new GhostText.Control("talk", "stop", "danger"));
        int last = Integer.MAX_VALUE;
        for (List<GhostText.Control> line : ControlLine.ladder(in)) {
            assertTrue(ControlLine.width(line) <= last);
            assertTrue(line.size() <= ControlLine.MAX_CONTROLS);
            last = ControlLine.width(line);
        }
    }

    @Test public void attachedPathsJoinTheDraftWithASpaceAndKeepTheirTrailingSpace() {
        ComposerDrafts drafts = new ComposerDrafts();
        assertEquals("/a/b.png ", drafts.appendAttachment("t1", "/a/b.png "));
        assertEquals("/a/b.png /c.jpg ", drafts.appendAttachment("t1", "/c.jpg "));
        drafts.append("t3", "look at");
        assertEquals("look at /d.png ", drafts.appendAttachment("t3", "/d.png "));
    }

    @Test public void aSharedImageWaitsForAnAgentAndIsForgottenAfterTenMinutes() throws Exception {
        File one = File.createTempFile("share", ".bin");
        File two = File.createTempFile("share", ".bin");
        SharedImages.discard();
        assertFalse(SharedImages.waiting(0));
        SharedImages.offer(new ArrayList<>(Arrays.asList(one)), 0, 1000);
        assertTrue(SharedImages.waiting(1000 + SharedImages.KEEP_MS));
        // The newest share replaces an older one, whose copy is deleted.
        SharedImages.offer(new ArrayList<>(Arrays.asList(two)), 0, 2000);
        assertFalse(one.exists());
        assertEquals(Arrays.asList(two), SharedImages.take(2000));
        assertFalse(SharedImages.waiting(2000));
        SharedImages.offer(new ArrayList<>(Arrays.asList(one)), 0, 3000);
        assertTrue(SharedImages.take(3000 + SharedImages.KEEP_MS + 1).isEmpty());
        assertFalse(one.exists());
        two.delete();
    }

    private static String manifest() throws Exception {
        return new String(Files.readAllBytes(new File("src/main/AndroidManifest.xml").toPath()), StandardCharsets.UTF_8);
    }

    @Test public void theShareSheetOffersHerdrForImagesOnly() throws Exception {
        String m = manifest();
        int at = m.indexOf("dev.herdr.android.ShareTargetActivity");
        assertTrue("the share target is declared", at > 0);
        String activity = m.substring(at, m.indexOf("</activity>", at));
        assertTrue(activity.contains("android:exported=\"true\""));
        assertTrue(activity.contains("android.intent.action.SEND\""));
        assertTrue(activity.contains("android.intent.action.SEND_MULTIPLE\""));
        assertTrue(activity.contains("android:mimeType=\"image/*\""));
        assertFalse("images only", activity.contains("text/") || activity.contains("*/*"));
    }

    @Test public void theCameraAppIsVisibleToThisApp() throws Exception {
        assertTrue(manifest().contains("android.media.action.IMAGE_CAPTURE"));
    }

    @Test public void noNewPermissionIsNeeded() throws Exception {
        String m = manifest();
        assertFalse(m.contains("android.permission.CAMERA"));
        assertFalse(m.contains("READ_EXTERNAL_STORAGE"));
        assertFalse(m.contains("READ_MEDIA_IMAGES"));
    }

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

    @Test public void aShareIsAskedAboutNeverUploadedOnArrival() throws Exception {
        String activity = source("GpuiTerminalActivity");
        String share = section(activity, "private void takeSharedImages()", "private final Runnable retrySharedImages");
        // The take and the upload sit inside the sheet's "Attach" answer, not before it.
        assertTrue(share.indexOf("new ActionSheet(") < share.indexOf("SharedImages.take("));
        assertTrue(share.indexOf(".add(\"Attach\"") < share.indexOf("attach(live"));
        assertTrue("asked once per share", share.contains("askedShare == SharedImages.generation()"));
        assertFalse("the target's host and pane are checked again at the answer", !share.contains("live.host"));
        // A cold start's reopen of the remembered agent cannot consume a share (the sheet needs a tap).
        assertTrue(source("HomeActivity").contains("!SharedImages.waiting(System.currentTimeMillis())"));
    }

    @Test public void onlyAnAgentPaneIsAnAttachTargetAndOnlyTheSameHostGetsTheDraft() throws Exception {
        String activity = source("GpuiTerminalActivity");
        assertTrue(section(activity, "private AttachTarget attachTarget()", "private AttachTarget liveTarget")
                .contains("ComposerBar.isAgent(agentKind(c))"));
        String done = section(activity, "private void attached(AttachTarget target", "private int askedShare");
        // Drafts are host-qualified: the path goes to the draft of the host that received the image.
        assertTrue(done.contains("composer.appendAttachment(target.host, target.pane"));
    }

    @Test public void theShareTargetOpensOnlyGrantedContentUrisAndChecksTheyAreImages() throws Exception {
        String share = source("ShareTargetActivity");
        assertTrue(share.contains("ShareUris.filter("));
        assertTrue(share.contains("AttachImages.copyValidated("));
        assertFalse("no unchecked stream cast", share.contains("getParcelableExtra"));
        assertFalse("no unchecked list cast", share.contains("getParcelableArrayListExtra"));
        assertFalse("no popups", share.contains("Toast"));
        String copy = section(source("AttachImages"), "static File copyValidated(", "static final class Reencoder");
        assertTrue("streamed to a file, validated before it is kept",
                copy.indexOf("FileOutputStream") < copy.indexOf("ImageDecoder.decodeBitmap("));
    }

    @Test public void homeShowsAWaitingShareInItsContentNotJustTheTopBar() throws Exception {
        String home = source("HomeActivity");
        String content = section(home, "String waitingShare = SharedImages.notice(", "if (machines.isEmpty() && !hostsLoaded)");
        assertTrue(content.contains("body.addView(card"));
    }

    @Test public void thePasteListenerHandsBackWhatIsNotAnImage() throws Exception {
        String listener = section(source("ComposerBar"), "private void listenForImages(", "A session that a pane switch");
        assertTrue(listener.contains("hasMimeType(\"image/*\")"));
        String worker = section(source("GpuiTerminalActivity"), "private void attachPastedOnWorker(", "private boolean isImageUri(");
        assertTrue("the rest keeps the original payload metadata",
                worker.contains("ContentInfo.Builder(payload).setClip(rest)"));
        assertTrue("the rest goes back to the field", listener.contains("edit.onReceiveContent(rest)"));
        assertTrue("a clip with no images is a normal paste", listener.contains("return payload;"));
    }

    @Test public void theCameraWritesToAPrivateFileNotThePublishedMediaStore() throws Exception {
        assertFalse(source("AttachImages").contains("MediaStore.Images"));
        assertFalse(source("GpuiTerminalActivity").contains("cameraTarget"));
        String m = manifest();
        int at = m.indexOf("dev.herdr.android.CaptureProvider");
        assertTrue(at > 0);
        String provider = m.substring(at, m.indexOf("/>", at));
        assertTrue(provider.contains("android:exported=\"false\"") && provider.contains("android:grantUriPermissions=\"true\""));
        String camera = section(source("GpuiTerminalActivity"), "private void takePhoto()", "private void dropCapture()");
        assertTrue("storage and launch failures are handled", camera.contains("IOException") && camera.contains("RuntimeException"));
    }

    @Test public void thePhotoPickerOffersImagesOnly() throws Exception {
        assertTrue(section(source("AttachImages"), "static Intent pickIntent()", "static Intent cameraIntent")
                .contains("ACTION_PICK_IMAGES).setType(\"image/*\")"));
    }

    @Test public void aWaitingOrRefusedShareHasWordsForHomeAndAFreshNumberPerShare() throws Exception {
        File one = File.createTempFile("share", ".bin");
        SharedImages.discard();
        assertEquals(null, SharedImages.notice(0));
        int before = SharedImages.generation();
        SharedImages.offer(new ArrayList<>(Arrays.asList(one)), 2, 10);
        assertTrue(SharedImages.generation() != before);
        String words = SharedImages.notice(10);
        assertTrue(words, words.contains("1 shared image is waiting") && words.contains("pick an agent"));
        assertTrue(words, words.contains("2 shared items could not be used"));
        SharedImages.discard();
        // Everything refused: nothing waits, but Home still says why.
        SharedImages.offer(new ArrayList<File>(), 1, 20);
        assertFalse(SharedImages.waiting(20));
        assertTrue(SharedImages.notice(20).contains("1 shared item could not be used"));
        assertEquals(null, SharedImages.notice(20 + SharedImages.KEEP_MS + 1));
        one.delete();
    }
}
