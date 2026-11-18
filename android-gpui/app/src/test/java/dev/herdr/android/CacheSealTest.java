package dev.herdr.android;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.GeneralSecurityException;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** Item 77, ask 2: the offline cache is sealed at rest and bound to its place. */
public final class CacheSealTest {
    @Rule public final TemporaryFolder dir = new TemporaryFolder();

    private static final byte[] SECRET = "export TOKEN=hunter2 ● Claude said so".getBytes(StandardCharsets.UTF_8);

    @Test public void ask2SealedBytesOpenOnlyForTheirPlaceAndKey() throws Exception {
        CacheSeal seal = new CacheSeal(CacheSeal.newKey());
        byte[] sealed = seal.seal(SECRET, "a.snap2");
        assertFalse(new String(sealed, StandardCharsets.ISO_8859_1).contains("hunter2"));
        assertArrayEquals(SECRET, seal.open(sealed, "a.snap2"));
        refused(() -> seal.open(sealed, "b.snap2"));
        refused(() -> new CacheSeal(CacheSeal.newKey()).open(sealed, "a.snap2"));
        byte[] tampered = sealed.clone();
        tampered[tampered.length - 1] ^= 1;
        refused(() -> seal.open(tampered, "a.snap2"));
        refused(() -> seal.open(SECRET, "a.snap2"));
        // Each seal is fresh: the same bytes never seal the same way twice.
        assertFalse(java.util.Arrays.equals(sealed, seal.seal(SECRET, "a.snap2")));
    }

    @Test public void ask2AFileThatDoesNotOpenIsDeletedNeverShown() throws Exception {
        CacheSeal seal = new CacheSeal(CacheSeal.newKey());
        File file = dir.newFile("p.snap2");
        seal.write(file, SECRET);
        assertFalse(new String(Files.readAllBytes(file.toPath()), StandardCharsets.ISO_8859_1).contains("hunter2"));
        assertArrayEquals(SECRET, seal.read(file, 1024));
        assertNull(new CacheSeal(CacheSeal.newKey()).read(file, 1024));
        assertFalse(file.exists());
        assertNull(seal.read(new File(dir.getRoot(), "missing.snap2"), 1024));
    }

    private interface Throwing { void run() throws Exception; }

    private static void refused(Throwing open) throws Exception {
        try {
            open.run();
            fail("opened");
        } catch (GeneralSecurityException expected) {
            assertTrue(true);
        }
    }
}
