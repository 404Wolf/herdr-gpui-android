package dev.herdr.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;
import org.junit.Test;

public final class AttachRound4Test {
    @Test public void clearUndoUsesTheDraftOwnerForSaveVisibilityAndRestore() throws Exception {
        String composer = source("ComposerBar");
        assertTrue("the clear record must capture the same host + pane key as the draft",
                composer.contains("clearing.tap(drafts.owner(),"));
        assertTrue("B/t1 must not offer A/t1's Undo",
                composer.contains("clearing.open(drafts.owner(), wall())"));
        assertTrue("stale taps must also be checked before the draft is restored",
                composer.contains("clearing.undo(drafts.owner(), wall())"));
    }

    @Test public void adoptingALegacyDraftSavesItBeforeTheFirstEditOrLifecycleCallback() throws Exception {
        String composer = source("ComposerBar");
        assertTrue("selecting the first host must persist the legacy draft adoption before showing it",
                composer.contains("drafts.setHost(onHost, revision, this::commitDrafts)"));
        assertTrue("migration clears the bare key and writes the host key in a synchronous transaction",
                composer.contains("preferences.edit().putString(DRAFTS, encoded).commit()"));
    }

    @Test public void senderReadableShareDoesNotNeedASeparateGrantForAPublicProvider() {
        assertEquals("sender authorization must work without an intent-wide grant flag",
                Arrays.asList(0), ShareUris.filter(Arrays.asList(
                        new String[] {"content", "public.images"}, new String[] {"content", "herdr.only"},
                        new String[] {"file", ""}, new String[] {"content", "10@dev.wolf.herdrgpui.capture"}),
                        "dev.wolf.herdrgpui", i -> i != 1));
    }

    @Test public void nativePasteNewlinesDoNotSubmitTheDraft() throws Exception {
        String composer = source("ComposerBar");
        int start = composer.indexOf("ContentInfo onReceiveContent(ContentInfo payload)");
        assertTrue("the native text field inserts a newline between clipboard items", start >= 0);
        String receive = composer.substring(start, composer.indexOf("edit.setSaveEnabled(false)", start));
        assertTrue("native paste must bypass the keyboard's newline-to-Enter logic",
                receive.indexOf("insertingNewline = true") >= 0
                        && receive.indexOf("insertingNewline = true") < receive.indexOf("super.onReceiveContent(payload)")
                        && receive.contains("finally"));
    }

    private static String source(String name) throws Exception {
        return new String(Files.readAllBytes(Paths.get("src/main/java/dev/herdr/android", name + ".java")),
                StandardCharsets.UTF_8);
    }
}
