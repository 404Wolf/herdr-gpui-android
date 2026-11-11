package dev.herdr.android.dictation;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import java.util.ArrayList;

/**
 * The phone's own recognizer (on this Fold, the Google app's), used when the tailnet server is
 * unreachable. It prefers the on-device model, so it keeps working offline when that model is
 * installed. The platform recognizer ends an utterance on silence; this engine starts the next
 * one at once and joins the pieces, so only {@link #stop} ends a session.
 */
public final class SystemDictationEngine implements DictationEngine {
    private final Context context;
    private SpeechRecognizer recognizer;
    private Listener listener;
    private final StringBuilder committed = new StringBuilder();
    private String partial = "";
    private boolean stopping;

    public SystemDictationEngine(Context context) {
        this.context = context;
    }

    @Override
    public boolean isAvailable() {
        return SpeechRecognizer.isRecognitionAvailable(context);
    }

    @Override
    public void start(Listener l) {
        if (listener != null) return;
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            l.onError(new DictationException(
                    DictationException.Reason.PERMISSION, "Microphone permission is not granted"));
            return;
        }
        if (!isAvailable()) {
            l.onError(new DictationException(
                    DictationException.Reason.UNAVAILABLE, "No speech recognizer on this phone"));
            return;
        }
        listener = l;
        committed.setLength(0);
        partial = "";
        stopping = false;
        recognizer = SpeechRecognizer.createSpeechRecognizer(context);
        recognizer.setRecognitionListener(new Callbacks());
        l.onState(State.CONNECTING);
        listen();
    }

    @Override
    public void stop() {
        if (listener == null || stopping) return;
        stopping = true;
        listener.onState(State.FINISHING);
        recognizer.stopListening();
    }

    @Override
    public void cancel() {
        if (listener == null) return;
        listener = null;
        release();
    }

    private void listen() {
        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                .putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.getPackageName());
        recognizer.startListening(intent);
    }

    private void release() {
        if (recognizer != null) {
            recognizer.destroy();
            recognizer = null;
        }
    }

    static String join(CharSequence committed, String next) {
        String a = committed.toString().trim();
        String b = next == null ? "" : next.trim();
        if (a.isEmpty()) return b;
        if (b.isEmpty()) return a;
        return a + " " + b;
    }

    private static String best(Bundle results) {
        ArrayList<String> texts = results == null ? null
                : results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        return texts == null || texts.isEmpty() ? "" : texts.get(0);
    }

    /** Ends the session with what was heard, or continues with the next utterance. */
    private void utteranceEnded(String text) {
        Listener l = listener;
        if (l == null) return;
        String all = join(committed, text);
        committed.setLength(0);
        committed.append(all);
        partial = "";
        if (stopping) {
            listener = null;
            release();
            l.onFinal(all);
            l.onState(State.IDLE);
        } else {
            l.onPartial(all);
            listen();
        }
    }

    private final class Callbacks implements RecognitionListener {
        @Override
        public void onReadyForSpeech(Bundle params) {
            if (listener != null && !stopping) listener.onState(State.LISTENING);
        }

        @Override
        public void onPartialResults(Bundle results) {
            if (listener == null) return;
            partial = best(results);
            listener.onPartial(join(committed, partial));
        }

        @Override
        public void onResults(Bundle results) {
            utteranceEnded(best(results));
        }

        @Override
        public void onError(int error) {
            Listener l = listener;
            if (l == null) return;
            switch (error) {
                case SpeechRecognizer.ERROR_NO_MATCH:
                case SpeechRecognizer.ERROR_SPEECH_TIMEOUT:
                    // Silence: keep what the last partial had and listen again (or finish).
                    utteranceEnded(partial);
                    return;
                case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS:
                    end(l, DictationException.Reason.PERMISSION, "Microphone permission is not granted");
                    return;
                case SpeechRecognizer.ERROR_AUDIO:
                    end(l, DictationException.Reason.AUDIO, "The microphone could not be used");
                    return;
                case SpeechRecognizer.ERROR_NETWORK:
                case SpeechRecognizer.ERROR_NETWORK_TIMEOUT:
                case SpeechRecognizer.ERROR_SERVER_DISCONNECTED:
                    end(l, DictationException.Reason.UNREACHABLE,
                            "The phone's recognizer needs a network it cannot reach");
                    return;
                default:
                    end(l, DictationException.Reason.SERVER, "The phone's recognizer failed (" + error + ")");
            }
        }

        private void end(Listener l, DictationException.Reason reason, String message) {
            listener = null;
            release();
            l.onError(new DictationException(reason, message));
            l.onState(State.IDLE);
        }

        @Override
        public void onBeginningOfSpeech() {}

        @Override
        public void onRmsChanged(float rmsdB) {}

        @Override
        public void onBufferReceived(byte[] buffer) {}

        @Override
        public void onEndOfSpeech() {}

        @Override
        public void onEvent(int eventType, Bundle params) {}
    }
}
