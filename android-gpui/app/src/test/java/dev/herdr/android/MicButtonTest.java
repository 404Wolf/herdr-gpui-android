package dev.herdr.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import dev.herdr.android.dictation.DictationEngine.State;
import org.junit.Test;

/** What the floating mic says in each dictation state. */
public final class MicButtonTest {
    @Test public void eachStateHasItsWords() {
        assertEquals("", MicButton.text(State.IDLE, 0));
        assertEquals("connecting", MicButton.text(State.CONNECTING, 0));
        assertEquals("0:07", MicButton.text(State.LISTENING, 7_900));
        assertEquals("transcribing", MicButton.text(State.TRANSCRIBING, 0));
        assertEquals("Desktop offline, recording 0:12", MicButton.text(State.OFFLINE_RECORDING, 12_000));
        assertEquals("", MicButton.text(State.PENDING, 0));
    }

    @Test public void theClockRunsOnlyWhileTheMicrophoneIsOpen() {
        assertTrue(MicButton.recording(State.CONNECTING));
        assertTrue(MicButton.recording(State.LISTENING));
        assertTrue(MicButton.recording(State.OFFLINE_RECORDING));
        assertFalse(MicButton.recording(State.TRANSCRIBING));
        assertFalse(MicButton.recording(State.PENDING));
        assertFalse(MicButton.recording(State.IDLE));
        assertEquals("1:05", MicButton.clock(65_000));
        assertEquals("0:00", MicButton.clock(-5));
    }

    @Test public void theDescriptionSaysWhatATapDoesAndWhatWaits() {
        assertEquals("Dictate into the draft", MicButton.description(State.IDLE, 0));
        assertEquals("Dictate into the draft. 2 waiting for desktop", MicButton.description(State.PENDING, 2));
        assertEquals("Listening; tap to stop", MicButton.description(State.LISTENING, 0));
    }

    @Test public void aTintIsOpaqueAndBetweenItsColours() {
        assertEquals(0xff000000, MicButton.mix(0xff000000, 0xffF07178, 0f));
        assertEquals(0xff261213, MicButton.mix(0xff000000, 0xffF07178, 0.16f));
    }
}
