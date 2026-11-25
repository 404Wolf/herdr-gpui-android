package dev.herdr.android;

import static org.junit.Assert.assertEquals;

import dev.herdr.android.dictation.DictationEngine;
import java.util.List;
import org.junit.Test;

/**
 * factory-9zz.16, the Fold 11-25: while the phone's Whisper records (desktop out), the draft
 * stays empty for seconds; the status says so plainly instead of looking broken.
 */
public final class PhoneWordsTalkTest {
    private static String words(List<GhostText.Control> talks) {
        StringBuilder out = new StringBuilder();
        for (GhostText.Control c : talks) out.append(out.length() == 0 ? "" : " | ").append(c.spans[0]);
        return out.toString();
    }

    @Test public void thePhonesWhisperSaysItsWordsComeEveryFewSeconds() {
        assertEquals("on this phone · words every few seconds · rec 0:12 | phone 0:12 | stop",
                words(ComposerBar.talks(DictationEngine.State.ON_DEVICE, "0:12", false, false, false, null, false)));
    }

    @Test public void aStreamingRecognizerKeepsItsWords() {
        assertEquals("offline · rec 0:12 | offline 0:12 | stop",
                words(ComposerBar.talks(DictationEngine.State.ON_DEVICE, "0:12", false, false, false, null, true)));
        assertEquals("offline · rec 0:12 | offline 0:12 | stop",
                words(ComposerBar.talks(DictationEngine.State.ON_DEVICE, "0:12", false, false, false, null)));
    }

    @Test public void afterStopItSaysThePhoneIsTranscribing() {
        assertEquals("transcribing on this phone… | …",
                words(ComposerBar.talks(DictationEngine.State.TRANSCRIBING, "0:12", false, false, false, null, false)));
    }
}
