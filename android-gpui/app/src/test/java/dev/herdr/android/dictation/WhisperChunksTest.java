package dev.herdr.android.dictation;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import org.junit.Test;

/**
 * factory-9zz.16: the phone's Whisper shows words while Wolf speaks: every ~10 s of new audio is
 * transcribed and appended as a partial; the final, one pass over the whole recording, replaces
 * them. None of it runs while the desktop answers.
 */
public final class WhisperChunksTest {
    private final List<String> heard = new ArrayList<>();
    private final List<Integer> runs = new ArrayList<>();
    private int opened, closed;
    private final Executor now = Runnable::run;

    private final OnDeviceRecognizer.Callback callback = new OnDeviceRecognizer.Callback() {
        @Override public void onPartial(String text) { heard.add("partial " + text); }

        @Override public void onFinal(String text) { heard.add("final " + text); }

        @Override public void onError(String why) { heard.add("error " + why); }
    };

    /** Says "c<n>" for a chunk (by its length in seconds) and "all" for the whole recording. */
    private WhisperRecognizer recognizer() {
        return new WhisperRecognizer(() -> {
            opened++;
            return new WhisperRecognizer.Transcriber() {
                @Override public String transcribe(byte[] pcm, int length) {
                    runs.add(length / ClipStore.BYTES_PER_SECOND);
                    return runs.size() <= 2 && length < 15 * ClipStore.BYTES_PER_SECOND ? "c" + runs.size() : "all";
                }

                @Override public void abort() { }

                @Override public void close() { closed++; }
            };
        }, now, callback);
    }

    private static void talk(WhisperRecognizer r, int seconds) {
        byte[] chunk = new byte[ClipStore.BYTES_PER_SECOND / 10];
        for (int i = 0; i < seconds * 10; i++) r.write(chunk, chunk.length);
    }

    @Test public void withTheDesktopOutWordsComeEveryTenSecondsAndTheFinalReplacesThem() {
        WhisperRecognizer r = recognizer();
        assertFalse("its words do not come as it hears", r.streams());
        r.needed();
        talk(r, 25);
        assertEquals("two 10 s pieces so far", List.of(10, 10), runs);
        assertEquals(List.of("partial c1", "partial c1 c2"), heard);
        r.endOfAudio();
        assertEquals("the final reads the whole recording", List.of(10, 10, 25), runs);
        assertEquals("final all", heard.get(heard.size() - 1));
        assertEquals("one engine for the session", 1, opened);
        assertEquals("closed once it is done", 1, closed);
    }

    @Test public void nothingRunsWhileTheDesktopAnswers() {
        WhisperRecognizer r = recognizer();
        talk(r, 25);
        r.endOfAudio();
        r.cancel();
        assertEquals(0, opened);
        assertTrue(runs.isEmpty());
        assertTrue(heard.isEmpty());
    }

    @Test public void theDesktopGoingOutPartwayStartsThePiecesFromThere() {
        WhisperRecognizer r = recognizer();
        talk(r, 12);
        r.needed();
        assertEquals("the 12 s so far, at once, as one piece", List.of(12), runs);
        talk(r, 1);
        r.endOfAudio();
        assertEquals(List.of(12, 13), runs);
    }
}
