package dev.herdr.android.dictation;

import android.content.Context;
import android.content.Intent;
import android.media.AudioFormat;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.util.Log;
import java.io.IOException;
import java.util.ArrayList;

/**
 * The phone's own {@link SpeechRecognizer}, fed the session's audio through a pipe
 * ({@link RecognizerIntent#EXTRA_AUDIO_SOURCE}, Android 13+), so it never competes for the
 * microphone. The on-device service is used when the phone has one, else the default service
 * asked to prefer offline. A segmented session keeps it listening through pauses until the
 * audio ends; a service that ends a session on a pause anyway is restarted on a fresh pipe, and
 * its pieces are joined.
 *
 * <p>All SpeechRecognizer calls happen on the main thread. Audio goes through a {@link PcmFeeder},
 * so the microphone thread never blocks on the recognizer and no audio is dropped silently: a
 * recognizer that falls behind or whose pipe breaks fails, and the engine falls back to the
 * session's recording.
 */
@android.annotation.TargetApi(33)
final class AndroidOnDeviceRecognizer implements OnDeviceRecognizer {
    private static final String TAG = "HerdrDictation";
    /** 10 s of 100 ms chunks: past that the recognizer is not keeping up and is given up on. */
    private static final int QUEUE_CHUNKS = 100;
    /** How long a failed pipe write waits for the recognizer's restart on a fresh pipe. */
    private static final long SWAP_GRACE_MS = 2000;

    /** A factory for {@code context}, or null when this Android cannot feed a recognizer audio. */
    static OnDeviceRecognizer.Factory factory(Context context) {
        if (Build.VERSION.SDK_INT < 33) return null;
        Context app = context.getApplicationContext() != null ? context.getApplicationContext() : context;
        return callback -> {
            SpeechRecognizer r;
            if (SpeechRecognizer.isOnDeviceRecognitionAvailable(app)) {
                r = SpeechRecognizer.createOnDeviceSpeechRecognizer(app);
            } else if (SpeechRecognizer.isRecognitionAvailable(app)) {
                r = SpeechRecognizer.createSpeechRecognizer(app);
            } else {
                Log.i(TAG, "dictation: no speech recognizer on this phone");
                return null;
            }
            AndroidOnDeviceRecognizer rec = new AndroidOnDeviceRecognizer(r, callback);
            if (!rec.listen()) {
                rec.cancel();
                return null;
            }
            return rec;
        };
    }

    private final SpeechRecognizer recognizer;
    private final Callback callback;
    private final Handler main = new Handler(Looper.getMainLooper());
    /** Carries audio into the current pipe; never drops it silently. */
    private final PcmFeeder feeder;
    private boolean feederStarted;
    /** Main thread: the current pipe's read end, handed to the recognizer. */
    private ParcelFileDescriptor source;
    /** Main thread: text of the finished pieces (segments, or sessions ended on a pause). */
    private final StringBuilder done = new StringBuilder();
    /** Main thread: the feeder has written the last audio and closed the pipe. */
    private boolean audioEnded;
    /** No more callbacks: finished, failed or cancelled. */
    private volatile boolean over;

    private AndroidOnDeviceRecognizer(SpeechRecognizer recognizer, Callback callback) {
        this.recognizer = recognizer;
        this.callback = callback;
        recognizer.setRecognitionListener(new Listener());
        feeder = new PcmFeeder(QUEUE_CHUNKS, SWAP_GRACE_MS, new PcmFeeder.Listener() {
            @Override public void onDrained() {
                main.post(() -> {
                    audioEnded = true;
                    if (over) return;
                    try {
                        recognizer.stopListening();
                    } catch (RuntimeException ignored) {
                        // The session already ended; its results come or came through the listener.
                    }
                });
            }

            @Override public void onFailed(String why) {
                Log.w(TAG, "dictation: feeding the phone's recognizer failed: " + why);
                main.post(() -> fail(why));
            }
        });
    }

    /** Main thread: opens a pipe and starts a recognition session reading it. */
    private boolean listen() {
        ParcelFileDescriptor[] pipe;
        try {
            pipe = ParcelFileDescriptor.createPipe();
        } catch (IOException e) {
            Log.w(TAG, "dictation: no pipe for the phone's recognizer", e);
            return false;
        }
        ParcelFileDescriptor old = source;
        source = pipe[0];
        feeder.setSink(new ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]));
        closeQuietly(old);
        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US")
                .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, source)
                .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
                .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
                .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, Microphone.RATE)
                // One session until the audio ends, not until the first pause.
                .putExtra(RecognizerIntent.EXTRA_SEGMENTED_SESSION, RecognizerIntent.EXTRA_AUDIO_SOURCE);
        if (Build.VERSION.SDK_INT >= 34) {
            intent.putExtra(RecognizerIntent.EXTRA_ENABLE_FORMATTING,
                    RecognizerIntent.FORMATTING_OPTIMIZE_QUALITY);
        }
        try {
            recognizer.startListening(intent);
        } catch (RuntimeException e) {
            Log.w(TAG, "dictation: the phone's recognizer refused to start", e);
            return false;
        }
        if (!feederStarted) {
            feederStarted = true;
            feeder.start();
        }
        return true;
    }

    @Override
    public void write(byte[] chunk, int length) {
        // A full queue is a failure the feeder reports (onFailed), never a silent drop.
        if (!over) feeder.write(chunk, length);
    }

    @Override
    public void endOfAudio() {
        // Everything queued is written first; then the pipe closes and the recognizer finishes.
        feeder.end();
    }

    @Override
    public void cancel() {
        over = true;
        feeder.cancel();
        main.post(this::tearDown);
    }

    /** Main thread: releases the service, the feeder and the pipe; safe to repeat. */
    private void tearDown() {
        feeder.cancel();
        try {
            recognizer.cancel();
            recognizer.destroy();
        } catch (RuntimeException ignored) {
            // Already gone.
        }
        closeQuietly(source);
    }

    /** Main thread. */
    private void finish() {
        if (over) return;
        over = true;
        callback.onFinal(done.toString().trim());
        tearDown();
    }

    /** Main thread. */
    private void fail(String why) {
        if (over) return;
        over = true;
        callback.onError(why);
        tearDown();
    }

    private void append(String piece) {
        if (piece == null || piece.trim().isEmpty()) return;
        if (done.length() > 0) done.append(' ');
        done.append(piece.trim());
    }

    private String joinedWith(String piece) {
        if (piece == null || piece.trim().isEmpty()) return done.toString();
        return done.length() == 0 ? piece.trim() : done + " " + piece.trim();
    }

    private static String best(Bundle results) {
        if (results == null) return null;
        ArrayList<String> texts = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        return texts == null || texts.isEmpty() ? null : texts.get(0);
    }

    private static void closeQuietly(java.io.Closeable c) {
        if (c == null) return;
        try {
            c.close();
        } catch (IOException ignored) {
            // Nothing left to release.
        }
    }

    /** Main thread, from the recognition service. */
    private final class Listener implements RecognitionListener {
        @Override public void onReadyForSpeech(Bundle params) { }

        @Override public void onBeginningOfSpeech() { }

        @Override public void onRmsChanged(float rmsdB) { }

        @Override public void onBufferReceived(byte[] buffer) { }

        @Override public void onEndOfSpeech() { }

        @Override public void onEvent(int eventType, Bundle params) { }

        @Override public void onPartialResults(Bundle partial) {
            if (over) return;
            callback.onPartial(joinedWith(best(partial)));
        }

        @Override public void onSegmentResults(Bundle segment) {
            if (over) return;
            append(best(segment));
            callback.onPartial(done.toString());
        }

        @Override public void onEndOfSegmentedSession() {
            finish();
        }

        @Override public void onResults(Bundle results) {
            if (over) return;
            // A service without segmented sessions ends on a pause: keep the piece, go on.
            append(best(results));
            if (audioEnded) {
                finish();
            } else if (!listen()) {
                fail("could not restart");
            }
        }

        @Override public void onError(int error) {
            if (over) return;
            boolean quiet = error == SpeechRecognizer.ERROR_NO_MATCH
                    || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT;
            if (quiet && audioEnded) {
                finish();
            } else if (quiet) {
                if (!listen()) fail("could not restart");
            } else {
                Log.i(TAG, "dictation: the phone's recognizer failed, error " + error);
                fail("error " + error);
            }
        }
    }
}
