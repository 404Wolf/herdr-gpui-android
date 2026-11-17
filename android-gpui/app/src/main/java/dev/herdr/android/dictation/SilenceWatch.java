package dev.herdr.android.dictation;

/**
 * Tells a silenced microphone from a quiet room, one 100 ms chunk at a time. Android silences a
 * recording without an error (the microphone privacy toggle, a call, another app holding the
 * microphone, or the recorder out of the foreground): the recorder then gives exact zeros. A real
 * microphone, even in a quiet room, never holds its samples within a step or two of zero for
 * seconds, so that is what the fallback looks for; Android's own word
 * ({@code AudioRecordingConfiguration.isClientSilenced}) is taken after a shorter wait.
 *
 * <p>Single-threaded (the mic thread). Sees only sample peaks, never keeps audio.
 */
final class SilenceWatch {
    /** A chunk whose largest sample is at most this is digital silence, not a room. */
    static final int DEAD_PEAK = 2;
    /** Digital silence for this long (3 s) means the microphone is silenced. */
    static final int DEAD_CHUNKS = 30;
    /** Android saying the recording is silenced for this long (1 s) means it is. */
    static final int SILENCED_CHUNKS = 10;

    /** What the microphone has been doing. */
    enum Verdict {
        /** Working, or not long enough to tell. */
        LIVE,
        /** Android silenced this recording. */
        SILENCED_BY_ANDROID,
        /** Only digital silence came, with no word from Android. */
        DIGITAL_SILENCE
    }

    private int deadRun;
    private int silencedRun;
    private int chunks;
    private int deadChunks;
    private boolean heard;

    /** One chunk of PCM16 little-endian; {@code androidSilenced} is Android's word for it. */
    Verdict chunk(byte[] pcm, int length, boolean androidSilenced) {
        chunks++;
        boolean dead = peak(pcm, length) <= DEAD_PEAK;
        if (dead) {
            deadChunks++;
            deadRun++;
        } else {
            deadRun = 0;
            heard = true;
        }
        silencedRun = androidSilenced ? silencedRun + 1 : 0;
        if (silencedRun >= SILENCED_CHUNKS) return Verdict.SILENCED_BY_ANDROID;
        if (deadRun >= DEAD_CHUNKS) return Verdict.DIGITAL_SILENCE;
        return Verdict.LIVE;
    }

    /** Whether any chunk so far held more than digital silence: something was recorded. */
    boolean heard() {
        return heard;
    }

    int chunks() {
        return chunks;
    }

    int deadChunks() {
        return deadChunks;
    }

    /** The largest absolute PCM16 sample in the first {@code length} bytes. */
    static int peak(byte[] pcm, int length) {
        int max = 0;
        for (int i = 0; i + 1 < length; i += 2) {
            int s = (short) ((pcm[i] & 0xFF) | (pcm[i + 1] << 8));
            int a = s < 0 ? -s : s;
            if (a > max) max = a;
        }
        return max;
    }
}
