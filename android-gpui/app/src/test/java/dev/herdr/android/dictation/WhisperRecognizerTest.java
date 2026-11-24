package dev.herdr.android.dictation;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import org.junit.Test;

/** The phone's Whisper as a session recognizer: it runs only when its text is needed. */
public final class WhisperRecognizerTest {
    private final List<String> heard = new ArrayList<>();
    private int opened;
    private final Executor now = Runnable::run;

    private final OnDeviceRecognizer.Callback callback = new OnDeviceRecognizer.Callback() {
        @Override public void onPartial(String text) { heard.add("partial " + text); }

        @Override public void onFinal(String text) { heard.add("final " + text); }

        @Override public void onError(String why) { heard.add("error " + why); }
    };

    private WhisperRecognizer recognizer() {
        return new WhisperRecognizer(() -> {
            opened++;
            return null; // no model on the JVM: the failure path
        }, now, callback);
    }

    @Test public void itNeverRunsUnlessTheDesktopIsOut() {
        WhisperRecognizer r = recognizer();
        r.write(new byte[3200], 3200);
        r.endOfAudio();
        r.cancel();
        assertEquals("the desktop answered: the model was never even loaded", 0, opened);
        assertEquals(List.of(), heard);
    }

    @Test public void itRunsOnceWhenNeededAndTheAudioHasEndedInEitherOrder() {
        WhisperRecognizer r = recognizer();
        r.write(new byte[3200], 3200);
        r.needed();
        assertEquals("not before the audio ends", 0, opened);
        r.endOfAudio();
        r.needed();
        assertEquals(1, opened);
        assertEquals(List.of("error the model did not load"), heard);
    }

    @Test public void itsBudgetGrowsWithTheAudio() {
        WhisperRecognizer r = recognizer();
        assertEquals(10_000 + 64_000, r.finalBudgetMs(64_000));
        assertNull(WhisperRecognizer.factory(null, now));
    }
}
