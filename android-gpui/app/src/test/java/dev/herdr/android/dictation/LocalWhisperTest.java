package dev.herdr.android.dictation;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Locale;
import org.junit.Assume;
import org.junit.Test;

/**
 * The phone's fallback engine for real, off-device: the host build of libherdr_whisper (the
 * same shim and pinned whisper.cpp source as the APK's) with the pinned base.en model, on
 * whisper.cpp's own JFK sample. Needs HERDR_WHISPER_LIB, HERDR_WHISPER_MODEL and
 * HERDR_WHISPER_SAMPLE (scripts/test-whisper.sh sets them); skipped without them.
 */
public class LocalWhisperTest {
    private static File need(String env) {
        String v = System.getenv(env);
        Assume.assumeTrue(env + " not set: the real transcription test is skipped", v != null && !v.isEmpty());
        File f = new File(v);
        Assume.assumeTrue(env + " missing: " + v, f.isFile());
        return f;
    }

    @Test
    public void aSavedClipIsTranscribedOnThePhonesCpuWithoutTheNetwork() throws Exception {
        System.setProperty("herdr.whisper.lib", need("HERDR_WHISPER_LIB").getAbsolutePath());
        File model = need("HERDR_WHISPER_MODEL");
        byte[] wav = Files.readAllBytes(need("HERDR_WHISPER_SAMPLE").toPath());
        // A 16 kHz mono PCM16 WAV: the samples after its 44-byte header, as the app's clips are.
        byte[] pcm = Arrays.copyOfRange(wav, 44, wav.length);
        File clip = File.createTempFile("clip", ".pcm");
        clip.deleteOnExit();
        Files.write(clip.toPath(), pcm);

        try (LocalWhisper whisper = LocalWhisper.open(model, 4)) {
            assertNotNull("the library and the model load", whisper);
            String fromFile = whisper.transcribe(clip);
            assertNotNull(fromFile);
            String said = fromFile.toLowerCase(Locale.ROOT);
            assertTrue(said, said.contains("ask not what your country can do for you"));
            // The same audio from memory (the live fallback) gives the same text.
            assertEquals(fromFile, whisper.transcribe(pcm, pcm.length));
            // Silence is no speech, not an error.
            String quiet = whisper.transcribe(new byte[16000 * 2], 16000 * 2);
            assertNotNull(quiet);
        }
    }

    @Test
    public void noModelMeansNoEngine() throws Exception {
        System.setProperty("herdr.whisper.lib", need("HERDR_WHISPER_LIB").getAbsolutePath());
        assertNull(LocalWhisper.open(new File("/nonexistent/ggml-base.en.bin"), 4));
        File bogus = File.createTempFile("model", ".bin");
        bogus.deleteOnExit();
        Files.write(bogus.toPath(), new byte[] {1, 2, 3});
        assertNull("a file that is not a model is refused", LocalWhisper.open(bogus, 4));
    }
}
