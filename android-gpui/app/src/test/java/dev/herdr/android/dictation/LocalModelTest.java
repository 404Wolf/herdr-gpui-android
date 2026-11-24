package dev.herdr.android.dictation;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import org.json.JSONObject;
import org.junit.Test;

/** The provisioned model: used only when it is there and its SHA-256 is the pinned one. */
public class LocalModelTest {
    private static String sha256(byte[] bytes) throws Exception {
        StringBuilder hex = new StringBuilder();
        for (byte b : MessageDigest.getInstance("SHA-256").digest(bytes)) hex.append(String.format("%02x", b));
        return hex.toString();
    }

    private static File dir() throws Exception {
        File d = Files.createTempDirectory("model").toFile();
        d.deleteOnExit();
        return d;
    }

    @Test
    public void theSettingNamesAPlainFileAndAFullChecksum() throws Exception {
        String sha = "4baf70dd0d7c4247ba2b81fafd9c01005ac77c2f9ef064e00dcf195d0e2fdd2f";
        LocalModel.Spec spec = LocalModel.Spec.parse(new JSONObject()
                .put("file", "ggml-base.en-q5_1.bin").put("sha256", sha.toUpperCase()));
        assertNotNull(spec);
        assertEquals("ggml-base.en-q5_1.bin", spec.file);
        assertEquals(sha, spec.sha256);
        for (String bad : new String[] {"../x.bin", "a/b.bin", ".hidden", "", new String(new char[129]).replace('\0', 'x')}) {
            assertNull(bad, LocalModel.Spec.parse(new JSONObject().put("file", bad).put("sha256", sha)));
        }
        for (String bad : new String[] {"", "abc", sha.substring(1), sha + "0", sha.replace('4', 'g')}) {
            assertNull(bad, LocalModel.Spec.parse(new JSONObject().put("file", "m.bin").put("sha256", bad)));
        }
        assertNull(LocalModel.Spec.parse(null));
    }

    @Test
    public void onlyAPresentFileWithThePinnedChecksumIsUsed() throws Exception {
        File files = dir();
        File state = dir();
        byte[] model = "a model".getBytes(StandardCharsets.UTF_8);
        LocalModel.Spec spec = new LocalModel.Spec("m.bin", sha256(model));

        assertEquals(LocalModel.State.NOT_PROVISIONED, LocalModel.check(files, null, state).state);
        assertEquals(LocalModel.State.MISSING, LocalModel.check(files, spec, state).state);

        Files.write(new File(files, "m.bin").toPath(), "another".getBytes(StandardCharsets.UTF_8));
        LocalModel.Result wrong = LocalModel.check(files, spec, state);
        assertEquals(LocalModel.State.MISMATCH, wrong.state);
        assertNull(wrong.file);

        Files.write(new File(files, "m.bin").toPath(), model);
        int before = LocalModel.hashes;
        LocalModel.Result ok = LocalModel.check(files, spec, state);
        assertEquals(LocalModel.State.OK, ok.state);
        assertEquals(new File(files, "m.bin"), ok.file);
        assertEquals("hashed once", before + 1, LocalModel.hashes);
        assertEquals(LocalModel.State.OK, LocalModel.check(files, spec, state).state);
        assertEquals("the verified size and time are remembered: not hashed again", before + 1, LocalModel.hashes);

        // Replaced by other bytes of the same size: its time changed, so it is hashed again.
        File m = new File(files, "m.bin");
        Files.write(m.toPath(), "a mode!".getBytes(StandardCharsets.UTF_8));
        m.setLastModified(m.lastModified() + 5000);
        assertEquals(LocalModel.State.MISMATCH, LocalModel.check(files, spec, state).state);
    }
}
