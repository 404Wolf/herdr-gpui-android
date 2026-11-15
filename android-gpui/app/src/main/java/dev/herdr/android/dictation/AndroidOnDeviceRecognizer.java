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
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

/**
 * The phone's own {@link SpeechRecognizer}, fed the session's audio through a pipe
 * ({@link RecognizerIntent#EXTRA_AUDIO_SOURCE}, Android 13+), so it never competes for the
 * microphone. The on-device service is used when the phone has one, else the default service
 * asked to prefer offline. A segmented session keeps it listening through pauses until the
 * audio ends; a service that ends a session on a pause anyway is restarted on a fresh pipe, and
 * its pieces are joined.
 *
 * <p>All SpeechRecognizer calls happen on the main thread. Audio goes through a bounded queue
 * to a writer thread, so the microphone thread never blocks on the recognizer.
 */
@android.annotation.TargetApi(33)
final class AndroidOnDeviceRecognizer implements OnDeviceRecognizer {
    private static final String TAG = "HerdrDictation";
    /** 10 s of 100 ms chunks: past that the recognizer is not keeping up and is given up on. */
    private static final int QUEUE_CHUNKS = 100;
    private static final byte[] END = new byte[0];

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
    private final BlockingQueue<byte[]> queue = new ArrayBlockingQueue<>(QUEUE_CHUNKS);
    private final Thread writer;
    /** Where the writer puts audio: the current pipe's write end. */
    private volatile OutputStream sink;
    /** Main thread: the current pipe's read end, handed to the recognizer. */
    private ParcelFileDescriptor source;
    /** Main thread: text of the finished pieces (segments, or sessions ended on a pause). */
    private final StringBuilder done = new StringBuilder();
    /** Main thread: the writer has sent the last audio and closed the pipe. */
    private boolean audioEnded;
    /** No more callbacks: finished, failed or cancelled. */
    private volatile boolean over;
    /** The queue overflowed: the failure is on its way to the main thread. */
    private volatile boolean overflowed;

    private AndroidOnDeviceRecognizer(SpeechRecognizer recognizer, Callback callback) {
        this.recognizer = recognizer;
        this.callback = callback;
        recognizer.setRecognitionListener(new Listener());
        writer = new Thread(this::writeAll, "dictation-on-device");
        writer.setDaemon(true);
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
        OutputStream previous = sink;
        sink = new ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]);
        closeQuietly(previous);
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
        if (writer.getState() == Thread.State.NEW) writer.start();
        return true;
    }

    @Override
    public void write(byte[] chunk, int length) {
        if (over || overflowed) return;
        if (!queue.offer(java.util.Arrays.copyOf(chunk, length))) {
            overflowed = true;
            main.post(() -> fail("not keeping up"));
        }
    }

    @Override
    public void endOfAudio() {
        if (over || overflowed) return;
        // The writer closes the pipe after the last chunk; the recognizer then finishes.
        while (!queue.offer(END)) queue.poll();
    }

    @Override
    public void cancel() {
        over = true;
        queue.clear();
        queue.offer(END);
        main.post(this::tearDown);
    }

    /** Main thread: releases the service and both pipe ends; safe to repeat. */
    private void tearDown() {
        try {
            recognizer.cancel();
            recognizer.destroy();
        } catch (RuntimeException ignored) {
            // Already gone.
        }
        closeQuietly(sink);
        closeQuietly(source);
    }

    /** Writer thread: copies queued audio into the current pipe until the end. */
    private void writeAll() {
        try {
            while (true) {
                byte[] chunk = queue.take();
                if (chunk == END) break;
                OutputStream out = sink;
                try {
                    if (out != null) out.write(chunk);
                } catch (IOException e) {
                    // A pipe closed by a restart: this chunk misses the recognizer, not the recording.
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        closeQuietly(sink);
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
        queue.clear();
        queue.offer(END);
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
