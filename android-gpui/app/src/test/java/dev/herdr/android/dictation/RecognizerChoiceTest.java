package dev.herdr.android.dictation;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import org.junit.Test;

/** Only recognizers trusted to read our piped audio listen alongside the desktop. */
public final class RecognizerChoiceTest {
    static final String GOOGLE =
            "com.google.android.googlequicksearchbox/com.google.android.voicesearch.serviceapi.GoogleRecognitionService";
    static final String FUTO = "org.futo.voiceinput/org.futo.voiceinput.RecognitionService";

    @Test public void theSystemsOnDeviceRecognizerComesFirst() {
        Set<String> installed = new HashSet<>(Arrays.asList(GOOGLE));
        assertEquals(RecognizerChoice.ON_DEVICE,
                RecognizerChoice.choose(true, installed, Arrays.asList(GOOGLE)));
    }

    @Test public void anInstalledServiceIsNotUsedUnlessAllowed() {
        // It might ignore EXTRA_AUDIO_SOURCE and open the microphone itself: record and queue.
        Set<String> installed = new HashSet<>(Arrays.asList(GOOGLE, FUTO));
        assertNull(RecognizerChoice.choose(false, installed, Collections.emptyList()));
        assertNull(RecognizerChoice.choose(false, installed, null));
    }

    @Test public void theFirstAllowedInstalledServiceIsUsed() {
        Set<String> installed = new HashSet<>(Arrays.asList(GOOGLE));
        assertEquals(GOOGLE, RecognizerChoice.choose(false, installed, Arrays.asList(FUTO, GOOGLE)));
        assertNull("allowed but not installed",
                RecognizerChoice.choose(false, new HashSet<>(Arrays.asList(FUTO)), Arrays.asList(GOOGLE)));
    }

    @Test public void aRecordingStartingAfterTheRecognizerIsItsOwnMicrophone() {
        assertFalse(RecognizerChoice.foreignCapture(1, 1));
        assertFalse("ours ended", RecognizerChoice.foreignCapture(1, 0));
        assertTrue(RecognizerChoice.foreignCapture(1, 2));
    }
}
