package dev.herdr.android.dictation;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

public final class FallbackDictationEngineTest {
    /** An engine driven by the test: it records calls and lets the test deliver callbacks. */
    private static final class Fake implements DictationEngine {
        final String name;
        boolean available = true;
        Listener listener;
        int starts;
        int stops;
        int cancels;

        Fake(String name) {
            this.name = name;
        }

        @Override public void start(Listener l) {
            listener = l;
            starts++;
            l.onState(State.CONNECTING);
        }

        @Override public void stop() {
            stops++;
        }

        @Override public void cancel() {
            cancels++;
        }

        @Override public boolean isAvailable() {
            return available;
        }

        void listening() {
            listener.onState(State.LISTENING);
        }

        void unreachable() {
            listener.onError(new DictationException(DictationException.Reason.UNREACHABLE, name));
            listener.onState(State.IDLE);
        }

        void finish(String text) {
            listener.onFinal(text);
            listener.onState(State.IDLE);
        }
    }

    /** What the composer sees, in order. */
    private static final class Log implements DictationEngine.Listener {
        final List<String> events = new ArrayList<>();

        @Override public void onPartial(String text) {
            events.add("partial:" + text);
        }

        @Override public void onFinal(String text) {
            events.add("final:" + text);
        }

        @Override public void onState(DictationEngine.State state) {
            events.add(state.name());
        }

        @Override public void onError(DictationException error) {
            events.add("error:" + error.reason());
        }
    }

    private final Fake server = new Fake("server");
    private final Fake phone = new Fake("phone");
    private long now = 1000;
    private final FallbackDictationEngine engine =
            new FallbackDictationEngine(server, phone, () -> now);

    @Test public void serverSessionPassesThroughWithOneIdle() {
        Log log = new Log();
        engine.start(log);
        server.listening();
        server.listener.onPartial("hel");
        engine.stop();
        assertEquals(1, server.stops);
        server.finish("hello");
        server.listener.onState(DictationEngine.State.IDLE); // a stray extra IDLE is dropped
        assertEquals(List.of("CONNECTING", "LISTENING", "partial:hel", "final:hello", "IDLE"), log.events);
        assertEquals(0, phone.starts);
    }

    @Test public void unreachableServerFallsBackWithinTheSameSession() {
        Log log = new Log();
        engine.start(log);
        server.unreachable();
        assertEquals(1, phone.starts);
        phone.listening();
        engine.stop();
        assertEquals(1, phone.stops);
        assertEquals(0, server.stops);
        phone.finish("offline words");
        assertEquals(List.of("CONNECTING", "CONNECTING", "LISTENING", "final:offline words", "IDLE"),
                log.events);
    }

    @Test public void afterAMissTheServerIsSkippedForAMinute() {
        engine.start(new Log());
        server.unreachable();
        phone.finish("a");
        now += FallbackDictationEngine.RETRY_PRIMARY_AFTER_MS - 1;
        engine.start(new Log());
        assertEquals(1, server.starts);
        assertEquals(2, phone.starts);
        phone.finish("b");
        now += 1;
        engine.start(new Log());
        assertEquals(2, server.starts);
    }

    @Test public void aDropAfterListeningIsReportedNotHidden() {
        Log log = new Log();
        engine.start(log);
        server.listening();
        server.listener.onPartial("kept on screen");
        server.unreachable();
        assertEquals(0, phone.starts);
        assertEquals(List.of("CONNECTING", "LISTENING", "partial:kept on screen", "error:UNREACHABLE", "IDLE"),
                log.events);
    }

    @Test public void cancelSilencesTheSession() {
        Log log = new Log();
        engine.start(log);
        server.listening();
        engine.cancel();
        assertEquals(1, server.cancels);
        server.finish("late");
        assertEquals(List.of("CONNECTING", "LISTENING"), log.events);
        // And a new session can start.
        engine.start(log);
        assertEquals(2, server.starts);
    }

    @Test public void startWhileActiveIsIgnored() {
        engine.start(new Log());
        engine.start(new Log());
        assertEquals(1, server.starts);
    }

    @Test public void noServerConfiguredGoesStraightToThePhone() {
        server.available = false;
        engine.start(new Log());
        assertEquals(0, server.starts);
        assertEquals(1, phone.starts);
        assertTrue(engine.isAvailable());
        phone.available = false;
        assertFalse(engine.isAvailable());
    }

    @Test public void systemJoinSpacesUtterances() {
        assertEquals("one two", SystemDictationEngine.join("one ", " two"));
        assertEquals("two", SystemDictationEngine.join("", "two"));
        assertEquals("one", SystemDictationEngine.join("one", null));
    }
}
