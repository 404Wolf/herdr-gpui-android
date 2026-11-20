package dev.herdr.android;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;

/**
 * Attaching an image puts it on the host as the desktop's paste does and types its quoted path:
 * what is named, quoted, converted, uploaded and reported (the ask: gallery, camera, clipboard and
 * share sheet all end here).
 */
public final class ImageAttachTest {
    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10, 1, 2, 3};
    private static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 16};
    private static final byte[] BMP = {'B', 'M', 0, 0, 0, 0};
    private static final ImageAttach.Converter NO_CONVERSION = (raw, oversized) -> {
        throw new ImageAttach.AttachException("not converted");
    };

    @Test public void theFileIsNamedAsTheDesktopsPasteNamesIt() {
        // herdr-app's image_file(): herdr-paste-<unix nanos>.<ext>, png for anything it re-encodes.
        assertEquals("herdr-paste-1700000000000000001.png", ImageAttach.fileName(ImageAttach.Kind.PNG, 1700000000000000001L));
        assertEquals("herdr-paste-5.jpg", ImageAttach.fileName(ImageAttach.Kind.JPEG, 5));
        assertEquals("herdr-paste-5.gif", ImageAttach.fileName(ImageAttach.Kind.GIF, 5));
        assertEquals("herdr-paste-5.webp", ImageAttach.fileName(ImageAttach.Kind.WEBP, 5));
        assertEquals("herdr-paste-5.png", ImageAttach.fileName(ImageAttach.Kind.BMP, 5));
    }

    @Test public void theKindComesFromTheBytesNotAContentType() {
        assertEquals(ImageAttach.Kind.PNG, ImageAttach.sniff(PNG));
        assertEquals(ImageAttach.Kind.JPEG, ImageAttach.sniff(JPEG));
        assertEquals(ImageAttach.Kind.GIF, ImageAttach.sniff("GIF89a".getBytes()));
        assertEquals(ImageAttach.Kind.WEBP, ImageAttach.sniff("RIFF\0\0\0\0WEBPVP8 ".getBytes()));
        assertEquals(ImageAttach.Kind.BMP, ImageAttach.sniff(BMP));
        assertEquals(ImageAttach.Kind.TIFF, ImageAttach.sniff(new byte[] {'I', 'I', 42, 0}));
        assertEquals(ImageAttach.Kind.UNKNOWN, ImageAttach.sniff("<html>".getBytes()));
        assertEquals(ImageAttach.Kind.UNKNOWN, ImageAttach.sniff(new byte[0]));
        assertEquals(ImageAttach.Kind.UNKNOWN, ImageAttach.sniff(null));
    }

    @Test public void pathsAreQuotedAsTheDesktopsPasteQuotesThem() {
        // herdr-app's quote_paths(): bare when plain, else single-quoted; a space ends each path.
        assertEquals("/tmp/herdr-paste-1.png ", ImageAttach.quotePaths(Arrays.asList("/tmp/herdr-paste-1.png")));
        assertEquals("/a/b.png /c/d.jpg ", ImageAttach.quotePaths(Arrays.asList("/a/b.png", "/c/d.jpg")));
        assertEquals("'/my dir/a.png' ", ImageAttach.quotePaths(Arrays.asList("/my dir/a.png")));
        assertEquals("'/it'\\''s/a.png' ", ImageAttach.quotePaths(Arrays.asList("/it's/a.png")));
        assertEquals("'/a$b/x.png' ", ImageAttach.quotePaths(Arrays.asList("/a$b/x.png")));
        assertEquals("", ImageAttach.quotePaths(new ArrayList<String>()));
    }

    @Test public void anImageAgentsReadPassesThroughUntouched() throws Exception {
        ImageAttach.Prepared png = ImageAttach.prepare(PNG, NO_CONVERSION, 7);
        assertEquals("herdr-paste-7.png", png.name);
        assertArrayEquals(PNG, png.bytes);
        assertEquals("herdr-paste-8.jpg", ImageAttach.prepare(JPEG, NO_CONVERSION, 8).name);
    }

    @Test public void aFormatAnAgentCannotReadIsReencodedAndNamedForWhatItBecame() throws Exception {
        List<Boolean> asked = new ArrayList<>();
        ImageAttach.Prepared file = ImageAttach.prepare(BMP, (raw, oversized) -> {
            asked.add(oversized);
            return PNG;
        }, 9);
        assertEquals("herdr-paste-9.png", file.name);
        assertArrayEquals(PNG, file.bytes);
        assertEquals(Arrays.asList(false), asked);
        try {
            ImageAttach.prepare(BMP, (raw, oversized) -> new byte[] {1, 2, 3}, 9);
            fail("a converter that returns no image is refused");
        } catch (ImageAttach.AttachException expected) {
            assertEquals("That is not an image Herdr can attach", expected.getMessage());
        }
    }

    @Test public void aPhotoOverTheCoresBoundIsShrunkBeforeItIsSent() throws Exception {
        byte[] big = new byte[ImageAttach.MAX_BYTES + 1];
        System.arraycopy(JPEG, 0, big, 0, JPEG.length);
        List<Boolean> asked = new ArrayList<>();
        ImageAttach.Prepared file = ImageAttach.prepare(big, (raw, oversized) -> {
            asked.add(oversized);
            return JPEG;
        }, 3);
        assertEquals(Arrays.asList(true), asked);
        assertEquals("herdr-paste-3.jpg", file.name);
        assertTrue(file.bytes.length <= ImageAttach.MAX_BYTES);
        try {
            ImageAttach.prepare(big, (raw, oversized) -> raw, 3);
            fail("a shrink that stays over the bound is refused, never sent");
        } catch (ImageAttach.AttachException expected) {
            assertEquals("That image is too large to attach", expected.getMessage());
        }
        try {
            ImageAttach.prepare(new byte[0], NO_CONVERSION, 3);
            fail("empty");
        } catch (ImageAttach.AttachException expected) {
            assertEquals("That image is empty", expected.getMessage());
        }
    }

    @Test public void samplingBringsTheLongSideUnderTheLimit() {
        assertEquals(1, ImageAttach.sampleSize(4000, 3000, 4096));
        assertEquals(2, ImageAttach.sampleSize(8000, 6000, 4096));
        assertEquals(4, ImageAttach.sampleSize(12000, 9000, 4096));
        assertEquals(1, ImageAttach.sampleSize(0, 0, 4096));
    }

    private static ImageAttach.Source source(byte[] bytes) {
        return () -> bytes;
    }

    private static String reply(String path) {
        return "{\"queued\":true,\"path\":\"" + path + "\",\"bytes\":3}";
    }

    @Test public void everyImageIsPutOnceUnderItsOwnNameAndItsPathsComeBackInOrder() {
        List<String> names = new ArrayList<>();
        List<byte[]> sent = new ArrayList<>();
        ImageAttach.Outcome out = ImageAttach.attach(Arrays.asList(source(PNG), source(JPEG), source(PNG)),
                NO_CONVERSION, (name, bytes) -> {
                    names.add(name);
                    sent.add(bytes);
                    return reply("/run/t7/" + name);
                }, () -> 100L /* a clock that has not moved */);
        // Two images in one pick never share a name, even when the clock has not moved.
        assertEquals(Arrays.asList("herdr-paste-100.png", "herdr-paste-101.jpg", "herdr-paste-102.png"), names);
        assertEquals(names.size(), sent.size());
        assertArrayEquals(PNG, sent.get(0));
        assertArrayEquals(JPEG, sent.get(1));
        assertEquals(Arrays.asList("/run/t7/herdr-paste-100.png", "/run/t7/herdr-paste-101.jpg", "/run/t7/herdr-paste-102.png"), out.paths);
        assertTrue(out.failures.isEmpty());
    }

    @Test public void oneImageFailingDoesNotStopTheOthers() {
        ImageAttach.Outcome out = ImageAttach.attach(Arrays.asList(source(PNG), source(new byte[0]), source(JPEG)),
                NO_CONVERSION, (name, bytes) -> reply("/x/" + name), () -> 1L);
        assertEquals(2, out.paths.size());
        assertEquals(Arrays.asList("That image is empty"), out.failures);
    }

    @Test public void aPickOfMoreThanTheLimitKeepsTheFirstOnes() {
        List<ImageAttach.Source> many = new ArrayList<>();
        for (int i = 0; i < ImageAttach.MAX_IMAGES + 3; i++) many.add(source(PNG));
        ImageAttach.Outcome out = ImageAttach.attach(many, NO_CONVERSION, (name, bytes) -> reply("/x/" + name), () -> 1L);
        assertEquals(ImageAttach.MAX_IMAGES, out.paths.size());
    }

    @Test public void aCoreThatRefusesSaysWhyInWordsFitToShow() {
        String old = "{\"queued\":false,\"kind\":\"needs_update\"}";
        ImageAttach.Outcome out = ImageAttach.attach(Arrays.asList(source(PNG)), NO_CONVERSION, (n, b) -> old, () -> 1L);
        assertTrue(out.paths.isEmpty());
        assertEquals(Arrays.asList("This computer's Herdr is too old to take images; update it"), out.failures);
        assertEquals("That terminal is gone; open the agent again", ImageAttach.refusal("not_found"));
        assertEquals("That computer is not reachable right now", ImageAttach.refusal("unavailable"));
        assertEquals("The host could not save the image", ImageAttach.refusal("io"));
    }

    @Test public void aReplyWithoutAnAbsolutePathIsNeverTypedIntoTheComposer() {
        for (String bad : new String[] {"{\"queued\":true}", "{\"queued\":true,\"path\":\"rel/a.png\"}", "not json", "{}"}) {
            ImageAttach.Outcome out = ImageAttach.attach(Arrays.asList(source(PNG)), NO_CONVERSION, (n, b) -> bad, () -> 1L);
            assertTrue(bad, out.paths.isEmpty());
            assertFalse(bad, out.failures.isEmpty());
        }
    }

    @Test public void aDroppedConnectionIsAFailureNotACrash() {
        ImageAttach.Outcome out = ImageAttach.attach(Arrays.asList(source(PNG)), NO_CONVERSION, (n, b) -> {
            throw new ImageAttach.AttachException("The connection dropped before the image arrived");
        }, () -> 1L);
        assertEquals(Arrays.asList("The connection dropped before the image arrived"), out.failures);
    }
}
