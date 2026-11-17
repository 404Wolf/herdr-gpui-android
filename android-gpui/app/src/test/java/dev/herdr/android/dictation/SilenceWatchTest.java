package dev.herdr.android.dictation;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** A silenced microphone told from a quiet room, from sample peaks alone. */
public final class SilenceWatchTest {
    private static byte[] chunk(int sample) {
        byte[] b = new byte[Microphone.CHUNK_BYTES];
        for (int i = 0; i < b.length; i += 2) {
            int s = (i / 2) % 2 == 0 ? sample : -sample;
            b[i] = (byte) s;
            b[i + 1] = (byte) (s >> 8);
        }
        return b;
    }

    @Test public void peaksAreReadAsSignedLittleEndianSamples() {
        assertEquals(0, SilenceWatch.peak(chunk(0), Microphone.CHUNK_BYTES));
        assertEquals(300, SilenceWatch.peak(chunk(300), Microphone.CHUNK_BYTES));
        assertEquals(32767, SilenceWatch.peak(chunk(32767), Microphone.CHUNK_BYTES));
        assertEquals(32768, SilenceWatch.peak(new byte[] {0, (byte) 0x80}, 2));
    }

    @Test public void threeSecondsOfDigitalSilenceIsASilencedMicrophone() {
        SilenceWatch w = new SilenceWatch();
        for (int i = 1; i < SilenceWatch.DEAD_CHUNKS; i++) {
            assertEquals(SilenceWatch.Verdict.LIVE, w.chunk(chunk(1), Microphone.CHUNK_BYTES, false));
        }
        assertEquals(SilenceWatch.Verdict.DIGITAL_SILENCE, w.chunk(chunk(0), Microphone.CHUNK_BYTES, false));
        assertFalse(w.heard());
        assertEquals(SilenceWatch.DEAD_CHUNKS, w.deadChunks());
    }

    @Test public void aQuietRoomIsNotSilence() {
        SilenceWatch w = new SilenceWatch();
        for (int i = 0; i < 10 * SilenceWatch.DEAD_CHUNKS; i++) {
            // Room noise a few steps above zero, with a digitally silent chunk now and then.
            byte[] c = chunk(i % 7 == 0 ? 0 : 6);
            assertEquals(SilenceWatch.Verdict.LIVE, w.chunk(c, c.length, false));
        }
        assertTrue(w.heard());
    }

    @Test public void androidsWordIsTakenAfterAMoment() {
        SilenceWatch w = new SilenceWatch();
        for (int i = 1; i < SilenceWatch.SILENCED_CHUNKS; i++) {
            assertEquals(SilenceWatch.Verdict.LIVE, w.chunk(chunk(400), Microphone.CHUNK_BYTES, true));
        }
        assertEquals(SilenceWatch.Verdict.SILENCED_BY_ANDROID, w.chunk(chunk(400), Microphone.CHUNK_BYTES, true));
    }

    @Test public void aMomentOfAndroidSilencingIsForgiven() {
        SilenceWatch w = new SilenceWatch();
        for (int i = 0; i < 5 * SilenceWatch.SILENCED_CHUNKS; i++) {
            assertEquals(SilenceWatch.Verdict.LIVE, w.chunk(chunk(400), Microphone.CHUNK_BYTES, i % 3 != 0));
        }
    }
}
