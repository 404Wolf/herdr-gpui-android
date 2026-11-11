package dev.herdr.android.dictation;

import android.os.SystemClock;
import java.util.function.LongSupplier;

/**
 * Tries {@code primary} (the tailnet server) and, if it cannot be reached before it starts
 * listening, switches the same session to {@code fallback} (the phone's recognizer); the composer
 * sees only a longer CONNECTING. After such a miss it goes straight to the fallback for
 * {@link #RETRY_PRIMARY_AFTER_MS}, so every tap is not a connect timeout. Failures after
 * listening began are reported as they are: the server's partial text is already shown.
 *
 * <p>Whatever the inner engines do, the composer gets exactly one {@link State#IDLE} after each
 * onFinal or onError, and nothing after it.
 */
public final class FallbackDictationEngine implements DictationEngine {
    static final long RETRY_PRIMARY_AFTER_MS = 60_000;

    private final DictationEngine primary;
    private final DictationEngine fallback;
    private final LongSupplier elapsedMillis;
    /** The live session's relay; main thread only. */
    private Relay session;
    private long primaryMissedAt;
    private boolean primaryMissed;

    public FallbackDictationEngine(DictationEngine primary, DictationEngine fallback) {
        this(primary, fallback, SystemClock::elapsedRealtime);
    }

    FallbackDictationEngine(DictationEngine primary, DictationEngine fallback, LongSupplier elapsedMillis) {
        this.primary = primary;
        this.fallback = fallback;
        this.elapsedMillis = elapsedMillis;
    }

    @Override
    public void start(Listener listener) {
        if (session != null) return;
        boolean recentMiss = primaryMissed
                && elapsedMillis.getAsLong() - primaryMissedAt < RETRY_PRIMARY_AFTER_MS;
        boolean usePrimary = primary.isAvailable() && !(recentMiss && fallback.isAvailable());
        begin(usePrimary ? primary : fallback, listener);
    }

    private void begin(DictationEngine engine, Listener listener) {
        Relay relay = new Relay(engine, listener);
        session = relay;
        engine.start(relay);
    }

    @Override
    public void stop() {
        if (session != null) session.engine.stop();
    }

    @Override
    public void cancel() {
        Relay s = session;
        session = null;
        if (s != null) s.engine.cancel();
    }

    @Override
    public boolean isAvailable() {
        return primary.isAvailable() || fallback.isAvailable();
    }

    /** Forwards one inner engine's callbacks while it is the live session. */
    private final class Relay implements Listener {
        final DictationEngine engine;
        private final Listener out;
        private boolean listening;

        Relay(DictationEngine engine, Listener out) {
            this.engine = engine;
            this.out = out;
        }

        @Override
        public void onPartial(String text) {
            if (session == this) out.onPartial(text);
        }

        @Override
        public void onFinal(String text) {
            if (session != this) return;
            session = null;
            if (engine == primary) primaryMissed = false;
            out.onFinal(text);
            out.onState(State.IDLE);
        }

        @Override
        public void onState(State state) {
            if (session != this || state == State.IDLE) return;
            if (state == State.LISTENING) listening = true;
            out.onState(state);
        }

        @Override
        public void onError(DictationException error) {
            if (session != this) return;
            session = null;
            if (engine == primary && !listening
                    && error.reason() == DictationException.Reason.UNREACHABLE
                    && fallback.isAvailable()) {
                primaryMissed = true;
                primaryMissedAt = elapsedMillis.getAsLong();
                begin(fallback, out);
                return;
            }
            out.onError(error);
            out.onState(State.IDLE);
        }
    }
}
