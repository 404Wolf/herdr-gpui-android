package dev.herdr.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.nio.file.Files;
import java.nio.file.Paths;
import org.junit.Test;

public final class AttachRound6Test {
    private static final String RAW_PATH = "/state & photos/herdr-core/default/files/t1/herdr-paste-123-2.png";
    private static final String PATH = "'" + RAW_PATH + "'";

    @Test public void replacingTheLastAttachmentReleasesTheDraftRevision() {
        ComposerDrafts drafts = new ComposerDrafts();
        drafts.setHost("host", "original", encoded -> true);
        drafts.switchTo("t1");
        drafts.appendAttachment("host", "original", "t1", java.util.Collections.singletonList(RAW_PATH));
        drafts.edit("look at " + PATH);
        assertEquals("original", drafts.draftRevision());

        drafts.edit("a new prompt");
        assertNull(drafts.draftRevision());
        ComposerDrafts restored = new ComposerDrafts();
        restored.restore(drafts.encode());
        restored.setHost("host", "repointed", encoded -> true);
        assertEquals("a new prompt", restored.switchTo("t1"));
    }

    @Test public void previouslySavedPlainDraftsLoseTheirStaleAttachmentRevision() throws Exception {
        String saved = new org.json.JSONObject()
                .put(ComposerDrafts.key("host", "t1"), new org.json.JSONObject()
                        .put("text", "new prompt").put("revision", "original"))
                .put(ComposerDrafts.key("host", "t2"), new org.json.JSONObject()
                        .put("text", PATH).put("revision", "original"))
                .toString();
        ComposerDrafts restored = new ComposerDrafts();
        restored.restore(saved);
        restored.setHost("host", "repointed", encoded -> true);
        assertEquals("new prompt", restored.switchTo("t1"));
        assertEquals("", restored.switchTo("t2"));
    }

    @Test public void everyClipboardItemIsClassifiedBeforeAnyPutAndMixedClipsGoBackWhole() throws Exception {
        String activity = source("GpuiTerminalActivity");
        String worker = activity.substring(activity.indexOf("private boolean queueImagePaste("),
                activity.indexOf("@Override protected void onActivityResult("));
        assertTrue("classification must read the whole clip even if it exceeds the upload limit",
                worker.contains("i < clip.getItemCount();"));
        assertTrue("the mixed branch must post native insertion and bypass the image upload",
                worker.contains("main.post(") && worker.contains("nativePaste.run()")
                        && worker.indexOf("if (mixed || target == null)") < worker.indexOf("attachOnWorker(")
                        && source("ComposerBar").contains("edit.onReceiveContent(payload)"));
    }

    @Test public void anEditHasItsOwnPasteInterruptionNotice() throws Exception {
        assertTrue(source("ComposerBar").contains("Image paste cancelled: you edited the draft"));
    }

    private static String source(String name) throws Exception {
        return new String(Files.readAllBytes(Paths.get("src/main/java/dev/herdr/android", name + ".java")),
                java.nio.charset.StandardCharsets.UTF_8);
    }
}
