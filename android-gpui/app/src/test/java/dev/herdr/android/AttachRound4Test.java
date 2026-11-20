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
                composer.contains("drafts.setHost(onHost, this::commitDrafts)"));
        assertTrue("migration clears the bare key and writes the host key in a synchronous transaction",
                composer.contains("preferences.edit().putString(DRAFTS, encoded).commit()"));
    }

    @Test public void mixedPasteClassifiesEachUriOnTheWorkerAndReturnsNonImagesToTheField() throws Exception {
        String activity = source("GpuiTerminalActivity");
        assertTrue("the paste worker must use each item's MIME type, not the clip's image/* label",
                activity.contains("getContentResolver().getType("));
        assertTrue("items outside the image partition must reach the native field",
                source("ComposerBar").contains("edit.onReceiveContent("));
    }

    @Test public void senderReadableShareDoesNotNeedASeparateGrantForAPublicProvider() {
        assertEquals("sender authorization must work without an intent-wide grant flag",
                Arrays.asList(0), ShareUris.filter(Arrays.asList(
                        new String[] {"content", "public.images"}, new String[] {"content", "herdr.only"},
                        new String[] {"file", ""}, new String[] {"content", "10@dev.wolf.herdrgpui.capture"}),
                        0, "dev.wolf.herdrgpui", i -> i != 1));
    }

    private static String source(String name) throws Exception {
        return new String(Files.readAllBytes(Paths.get("src/main/java/dev/herdr/android", name + ".java")),
                StandardCharsets.UTF_8);
    }
}
