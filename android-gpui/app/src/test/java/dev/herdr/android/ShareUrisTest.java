package dev.herdr.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.file.Files;
import org.junit.Test;

/** What the exported share target and the camera's file provider may open. */
public final class ShareUrisTest {
    private static final String OWN = "dev.wolf.herdrgpui";

    @Test public void onlyAGrantedContentUriIsOpened() {
        assertTrue(ShareUris.allowed("content", "com.android.providers.media.documents", ShareUris.GRANT_READ, OWN));
        assertTrue(ShareUris.allowed("CONTENT", "media", ShareUris.GRANT_READ | 2, OWN));
        // No explicit read grant: Herdr's own access would be used, not the sender's.
        assertFalse(ShareUris.allowed("content", "media", 0, OWN));
        assertFalse(ShareUris.allowed("content", "media", 2, OWN));
    }

    @Test public void fileAndOtherSchemesAreRefused() {
        assertFalse(ShareUris.allowed("file", "", ShareUris.GRANT_READ, OWN));
        assertFalse(ShareUris.allowed("file", "host", ShareUris.GRANT_READ, OWN));
        assertFalse(ShareUris.allowed("http", "example.com", ShareUris.GRANT_READ, OWN));
        assertFalse(ShareUris.allowed("android.resource", "x", ShareUris.GRANT_READ, OWN));
        assertFalse(ShareUris.allowed(null, "media", ShareUris.GRANT_READ, OWN));
        assertFalse(ShareUris.allowed("content", null, ShareUris.GRANT_READ, OWN));
        assertFalse(ShareUris.allowed("content", "", ShareUris.GRANT_READ, OWN));
    }

    @Test public void herdrsOwnProviderAuthoritiesAreRefused() {
        assertFalse(ShareUris.allowed("content", OWN + ".capture", ShareUris.GRANT_READ, OWN));
        assertFalse(ShareUris.allowed("content", OWN, ShareUris.GRANT_READ, OWN));
        assertFalse(ShareUris.allowed("content", "DEV.WOLF.HERDRGPUI.capture", ShareUris.GRANT_READ, OWN));
        // A lookalike that merely starts with the same letters is another app's.
        assertTrue(ShareUris.allowed("content", OWN + "x.files", ShareUris.GRANT_READ, OWN));
    }

    @Test public void captureFilesAreOnePlainNameInsideTheCaptureFolder() throws Exception {
        File cache = Files.createTempDirectory("cache").toFile();
        File shot = CaptureFiles.create(cache, 7);
        assertEquals("herdr-camera-7.jpg", shot.getName());
        assertEquals(shot, CaptureFiles.resolve(cache, "herdr-camera-7.jpg"));
        for (String bad : new String[] {null, "", "..", "../x.jpg", "a/b.jpg", "a\\b", ".hidden/../x", "x y", "%2e%2e"}) {
            assertNull(String.valueOf(bad), CaptureFiles.resolve(cache, bad));
        }
        CaptureFiles.clear(cache);
        assertFalse(shot.exists());
        shot.getParentFile().delete();
        cache.delete();
    }
}
