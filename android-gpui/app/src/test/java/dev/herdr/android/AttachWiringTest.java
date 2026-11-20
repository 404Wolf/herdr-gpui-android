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
        SharedImages.offer(new ArrayList<>(Arrays.asList(one)), 1000);
        assertTrue(SharedImages.waiting(1000 + SharedImages.KEEP_MS));
        // The newest share replaces an older one, whose copy is deleted.
        SharedImages.offer(new ArrayList<>(Arrays.asList(two)), 2000);
        assertFalse(one.exists());
        assertEquals(Arrays.asList(two), SharedImages.take(2000));
        assertFalse(SharedImages.waiting(2000));
        SharedImages.offer(new ArrayList<>(Arrays.asList(one)), 3000);
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
}
