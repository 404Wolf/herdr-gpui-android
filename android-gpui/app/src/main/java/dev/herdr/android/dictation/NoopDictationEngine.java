package dev.herdr.android.dictation;

/** An engine that is never available; {@link #start} reports {@link DictationException.Reason#UNAVAILABLE}. */
public final class NoopDictationEngine implements DictationEngine {
    @Override
    public void start(String target, Listener listener) {
        listener.onError(
                new DictationException(
                        DictationException.Reason.UNAVAILABLE, "Dictation is not set up"));
        listener.onState(State.IDLE);
    }

    @Override
    public void stop() {}

    @Override
    public void cancel() {}

    @Override
    public boolean isAvailable() {
        return false;
    }
}
