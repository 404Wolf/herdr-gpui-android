package dev.herdr.android.dictation;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static dev.herdr.android.dictation.DictationFakes.*;
import dev.herdr.android.dictation.DictationFakes.Behaviour;
import dev.herdr.android.dictation.DictationFakes.FakeRecognizer;
import dev.herdr.android.dictation.DictationFakes.FakeServer;
import dev.herdr.android.dictation.DictationFakes.ScriptedMic;
import dev.herdr.android.dictation.DictationFakes.TestMain;

/**
 * The engine against a fake desktop dictation server (the real protocol over a local socket,
 * with the server's exact JSON as agreed for server.py) and a scripted on-device recognizer:
 * the phone always ends up with text, marked offline when it is the phone's, and never loses
 * audio.
 */
public final class ServerDictationEngineTest {
    @Rule public final TemporaryFolder dir = new TemporaryFolder();


    /** Short waits so the suite is quick; the shape of each wait is what is tested. */
    static final ServerDictationEngine.Timeouts TIMEOUTS =
            new ServerDictationEngine.Timeouts(800, 500, 2500, 1500);

    private final ScheduledExecutorService mainThread = Executors.newSingleThreadScheduledExecutor();
    private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor();
    private final List<String> log = Collections.synchronizedList(new ArrayList<>());
    private ClipStore store;
    private PendingDictations pending;
    private FakeServer server;

    @Before public void setUp() {
        store = new ClipStore(new File(dir.getRoot(), "pending"));
        pending = new PendingDictations(store, (clip, audio) -> {
            throw new IOException("the courier is not under test");
        }, worker, mainThread::execute);
    }

    @After public void tearDown() throws IOException {
        if (server != null) server.close();
        mainThread.shutdownNow();
        worker.shutdownNow();
    }

    // ---- the desktop answers in time --------------------------------------------------------

    @Test public void aFastDesktopFinalWinsAndItsRecordingIsDeleted() throws Exception {
        server = new FakeServer(Behaviour.fast(READY_GPU));
        FakeRecognizer phone = new FakeRecognizer(PHONE_WORDS, 0);
        Run run = dictate(server.uri(), phone, 300);
        run.awaitEnd();
        assertEquals(DESKTOP_WORDS, run.listener.finalText);
        assertFalse(run.listener.onDevice);
        assertTrue(phone.cancelled);
        assertTrue(store.keptClips().isEmpty());
        assertTrue(store.untranscribed().isEmpty());
        assertFalse(run.listener.states.contains(DictationEngine.State.ON_DEVICE));
        assertArrayEquals(run.mic.bytes(), server.audio());
    }

    @Test public void anOldServerWithoutABackendFieldCountsAsTheGpu() throws Exception {
        server = new FakeServer(Behaviour.fast(READY_OLD));
        Run run = dictate(server.uri(), new FakeRecognizer(PHONE_WORDS, 0), 300);
        run.awaitEnd();
        assertEquals(DESKTOP_WORDS, run.listener.finalText);
        assertFalse(run.listener.onDevice);
    }

    @Test public void theReadyBackendFieldIsReadAsAgreed() throws Exception {
        assertTrue(ServerDictationEngine.fastBackend(new JSONObject(READY_GPU)));
        assertTrue(ServerDictationEngine.fastBackend(new JSONObject(READY_OLD)));
        assertFalse(ServerDictationEngine.fastBackend(new JSONObject(READY_CPU)));
        assertFalse(ServerDictationEngine.fastBackend(new JSONObject(READY_DOWN)));
    }

    // ---- the desktop is slow or out: the phone's text, marked offline ------------------------

    @Test public void aSlowDesktopFinalGivesThePhonesTextAfterTheBudget() throws Exception {
        server = new FakeServer(Behaviour.fast(READY_GPU).finalAfter(4000));
        FakeRecognizer phone = new FakeRecognizer(PHONE_WORDS, 100);
        Run run = dictate(server.uri(), phone, 300);
        run.awaitEnd();
        assertPhoneText(run);
        long afterStop = run.listener.endedAt - run.stoppedAt;
        assertTrue("the phone's text came " + afterStop + " ms after stop",
                afterStop >= TIMEOUTS.finalBudgetMs - 50 && afterStop < TIMEOUTS.finalBudgetMs + 1500);
        assertKeptWithAllAudio(run);
        // The desktop's late final never lands a second text.
        Thread.sleep(500);
        assertEquals(1, run.listener.finals);
    }

    @Test public void anUnreachableDesktopGivesThePhonesText() throws Exception {
        Run run = dictate(closedPort(), new FakeRecognizer(PHONE_WORDS, 50), 300);
        run.awaitEnd();
        assertPhoneText(run);
        assertKeptWithAllAudio(run);
    }

    @Test public void aDesktopThatNeverSaysReadyGivesThePhonesText() throws Exception {
        server = new FakeServer(Behaviour.silent());
        Run run = dictate(server.uri(), new FakeRecognizer(PHONE_WORDS, 50), 1200);
        run.awaitEnd();
        assertPhoneText(run);
        assertTrue(run.listener.states.contains(DictationEngine.State.ON_DEVICE));
        assertKeptWithAllAudio(run);
    }

    @Test public void everyDesktopErrorGivesThePhonesText() throws Exception {
        for (String error : new String[] {ERROR_SLOW, ERROR_UNAVAILABLE, ERROR_BAD_START, ERROR_TOO_LONG}) {
            if (server != null) server.close();
            server = new FakeServer(Behaviour.fast(READY_GPU).errorOnAudio(error));
            Run run = dictate(server.uri(), new FakeRecognizer(PHONE_WORDS, 50), 300);
            run.awaitEnd();
            assertPhoneText(run);
            assertTrue(error, run.listener.states.contains(DictationEngine.State.ON_DEVICE));
            assertTrue(log.toString(), log.stream().anyMatch(l -> l.contains("desktop out (dropped")));
        }
        assertEquals(4, store.keptClips().size());
    }

    @Test public void aDesktopTranscribingOnItsCpuOrDownHandsOverAtOnce() throws Exception {
        for (String ready : new String[] {READY_CPU, READY_DOWN}) {
            if (server != null) server.close();
            server = new FakeServer(Behaviour.fast(ready).finalAfter(4000));
            FakeRecognizer phone = new FakeRecognizer(PHONE_WORDS, 50);
            log.clear();
            // Stops once the phone has taken over (on the state, not on time: a loaded machine).
            Run run = dictateUntil(server.uri(), phone, DictationEngine.State.ON_DEVICE);
            run.awaitEnd();
            assertPhoneText(run);
            // Handed over before stop, on the backend word: no budget waited after it.
            assertTrue(log.toString(), log.stream().anyMatch(l -> l.contains("desktop out (backend ")));
            assertFalse(log.toString(), log.stream().anyMatch(l -> l.contains("no final in time")));
        }
    }

    @Test public void aDropMidSessionGivesThePhonesText() throws Exception {
        server = new FakeServer(Behaviour.fast(READY_GPU).dropOnAudio());
        Run run = dictate(server.uri(), new FakeRecognizer(PHONE_WORDS, 50), 300);
        run.awaitEnd();
        assertPhoneText(run);
        assertKeptWithAllAudio(run);
    }

    @Test public void thePhonesPartialsReplaceTheDesktopsOnceItIsOut() throws Exception {
        server = new FakeServer(Behaviour.fast(READY_GPU).errorOnAudio(ERROR_SLOW));
        FakeRecognizer phone = new FakeRecognizer(PHONE_WORDS, 50);
        phone.partial = "words from";
        Run run = dictate(server.uri(), phone, 300);
        run.awaitEnd();
        assertTrue(run.listener.partials.toString(), run.listener.partials.contains("words from"));
    }

    @Test public void theRecognizerHearsExactlyTheRecordedAudio() throws Exception {
        FakeRecognizer phone = new FakeRecognizer(PHONE_WORDS, 50);
        Run run = dictate(closedPort(), phone, 300);
        run.awaitEnd();
        assertArrayEquals(run.mic.bytes(), phone.heard.toByteArray());
        assertTrue(phone.ended);
    }

    @Test public void theRecognizerStartsOnlyOnceOurMicrophoneIsOpen() throws Exception {
        FakeRecognizer phone = new FakeRecognizer(PHONE_WORDS, 50);
        Run run = dictate(closedPort(), phone, 300);
        run.awaitEnd();
        assertTrue(phone.started);
        assertFalse("started before our microphone was open", phone.startedBeforeTheMic);
    }

    @Test public void aMicrophoneThatDoesNotOpenStartsNoRecognizer() throws Exception {
        FakeRecognizer phone = new FakeRecognizer(PHONE_WORDS, 50);
        Run run = new Run();
        run.mic.failToOpen = true;
        dictate(closedPort(), phone, 100, run);
        run.awaitEnd();
        assertFalse(phone.started);
        assertTrue(run.listener.states.contains(null)); // onError
        assertEquals(DictationEngine.State.IDLE, last(run.listener.states));
        assertTrue(store.untranscribed().isEmpty());
        assertTrue(store.keptClips().isEmpty());
    }

    // ---- nothing on the phone: today's record-and-queue ------------------------------------

    @Test public void withoutARecognizerAnUnreachableDesktopQueuesTheRecording() throws Exception {
        Run run = dictate(closedPort(), null, 300);
        run.awaitEnd();
        assertNotNull(run.listener.pendingClip);
        assertTrue(run.listener.states.contains(DictationEngine.State.OFFLINE_RECORDING));
        assertEquals(DictationEngine.State.PENDING, last(run.listener.states));
        assertEquals(1, store.untranscribed().size());
        assertArrayEquals(run.mic.bytes(), Files.readAllBytes(store.audio(run.listener.pendingClip.id).toPath()));
    }

    @Test public void theLiveStartCarriesTheRecordingsIdThatAQueuedClipKeeps() throws Exception {
        // Never ready, no recognizer: the session records for later, under the id it started with.
        server = new FakeServer(Behaviour.silent());
        Run run = dictate(server.uri(), null, 300);
        run.awaitEnd();
        assertNotNull(run.listener.pendingClip);
        JSONObject start = server.starts.get(0);
        assertEquals("live", start.getString("mode"));
        assertEquals(16000, start.getInt("sample_rate"));
        assertEquals("the courier resends it under the same id", run.listener.pendingClip.id, start.getString("id"));
    }

    @Test public void theRecordingIsDeletedOnlyAfterTheFinalWasStored() throws Exception {
        server = new FakeServer(Behaviour.fast(READY_GPU));
        Run run = new Run();
        File dirFile = new File(dir.getRoot(), "pending");
        boolean[] audioAtFinal = {false};
        run.listener.atFinal = () -> {
            File[] parts = dirFile.listFiles((d, n) -> n.endsWith(".part"));
            audioAtFinal[0] = parts != null && parts.length == 1 && parts[0].length() > 0;
        };
        dictate(server.uri(), null, 300, run);
        run.awaitEnd();
        assertEquals(DESKTOP_WORDS, run.listener.finalText);
        assertTrue("the audio was still there when the final was handed over", audioAtFinal[0]);
        Thread.sleep(200);
        File[] left = dirFile.listFiles((d, n) -> n.endsWith(".part") || n.endsWith(".pcm"));
        assertEquals("deleted once stored", 0, left == null ? 0 : left.length);
    }

    @Test public void aFinalWhoseTextCouldNotBeStoredKeepsItsRecording() throws Exception {
        server = new FakeServer(Behaviour.fast(READY_GPU));
        Run run = new Run();
        run.listener.stores = false;
        dictate(server.uri(), null, 300, run);
        run.awaitEnd();
        Thread.sleep(300);
        assertEquals(DESKTOP_WORDS, run.listener.finalText);
        // Kept as rescue audio: never sent, never offered, but there.
        assertEquals(1, store.keptClips().size());
        assertArrayEquals(run.mic.bytes(), Files.readAllBytes(store.audio(store.keptClips().get(0).id).toPath()));
        assertTrue(store.untranscribed().isEmpty());
    }

    @Test public void aCrashWhileStoringTheFinalKeepsTheAudioForTheNextStart() throws Exception {
        server = new FakeServer(Behaviour.fast(READY_GPU));
        Run run = new Run();
        run.listener.atFinal = () -> {
            throw new IllegalStateException("killed while storing the text");
        };
        dictate(server.uri(), null, 300, run);
        Thread.sleep(1500);
        // The next start finds the audio and offers it as "not transcribed yet".
        ClipStore restarted = new ClipStore(new File(dir.getRoot(), "pending"));
        assertEquals(1, restarted.parked().size());
        assertArrayEquals(run.mic.bytes(), Files.readAllBytes(restarted.audio(restarted.parked().get(0).id).toPath()));
    }

    @Test public void finishingIsShownAndTheFinalStillLands() throws Exception {
        server = new FakeServer(Behaviour.fast(READY_GPU).finishing().finalAfter(300));
        Run run = dictate(server.uri(), null, 300);
        run.awaitEnd();
        assertEquals(1, run.listener.finishing);
        assertEquals(DESKTOP_WORDS, run.listener.finalText);
        assertFalse(run.listener.onDevice);
    }

    @Test public void withoutARecognizerASlowDesktopIsWaitedForLonger() throws Exception {
        server = new FakeServer(Behaviour.fast(READY_GPU).finalAfter(1200));
        Run run = dictate(server.uri(), null, 300);
        run.awaitEnd();
        assertEquals(DESKTOP_WORDS, run.listener.finalText);
        assertFalse(run.listener.onDevice);
    }

    @Test public void aFailingRecognizerFallsBackToTheQueue() throws Exception {
        FakeRecognizer phone = new FakeRecognizer(null, 50);
        Run run = dictate(closedPort(), phone, 300);
        run.awaitEnd();
        assertNotNull(run.listener.pendingClip);
        assertEquals(1, store.untranscribed().size());
        assertTrue(store.keptClips().isEmpty());
    }

    @Test public void aRecognizerThatHeardNothingLeavesTheRecordingForTheDesktop() throws Exception {
        Run run = dictate(closedPort(), new FakeRecognizer("", 50), 300);
        run.awaitEnd();
        assertNotNull(run.listener.pendingClip);
        assertEquals(0, run.listener.finals);
        assertEquals(1, store.untranscribed().size());
    }

    @Test public void aRecognizerThatNeverFinishesFallsBackToTheQueue() throws Exception {
        Run run = dictate(closedPort(), new FakeRecognizer(PHONE_WORDS, 60_000), 300);
        run.awaitEnd();
        assertNotNull(run.listener.pendingClip);
        assertEquals(1, store.untranscribed().size());
    }

    @Test public void aRecognizerFailingWhileTheDesktopIsFineChangesNothing() throws Exception {
        server = new FakeServer(Behaviour.fast(READY_GPU).finalAfter(1200));
        FakeRecognizer phone = new FakeRecognizer(null, 0);
        phone.failOnFirstAudio = true;
        Run run = dictate(server.uri(), phone, 300);
        run.awaitEnd();
        // No phone text to fall back on: the long wait applies, and the desktop is in time.
        assertEquals(DESKTOP_WORDS, run.listener.finalText);
    }

    // ---- helpers ---------------------------------------------------------------------------

    private void assertPhoneText(Run run) {
        assertEquals(PHONE_WORDS, run.listener.finalText);
        assertTrue("marked as the phone's", run.listener.onDevice);
        assertEquals(1, run.listener.finals);
        assertEquals(DictationEngine.State.IDLE, last(run.listener.states));
        assertEquals(null, run.listener.pendingClip);
    }

    private void assertKeptWithAllAudio(Run run) throws IOException {
        List<PendingClip> kept = store.keptClips();
        assertEquals(1, kept.size());
        assertTrue("never sent to the desktop", store.untranscribed().isEmpty());
        assertEquals("never counted as waiting", 0, pending.count());
        assertArrayEquals(run.mic.bytes(), Files.readAllBytes(store.audio(kept.get(0).id).toPath()));
    }

    private static <T> T last(List<T> list) {
        return list.get(list.size() - 1);
    }

    private static URI closedPort() throws IOException {
        int port;
        try (ServerSocket s = new ServerSocket(0)) {
            port = s.getLocalPort();
        }
        return URI.create("ws://127.0.0.1:" + port + "/v1/dictate");
    }

    /** One session: start, talk for {@code talkMs}, stop. */
    private Run dictate(URI uri, FakeRecognizer phone, long talkMs) throws Exception {
        return dictate(uri, phone, talkMs, new Run());
    }

    /** Like {@link #dictate}, stopping once the session reached {@code state} (10 s at most). */
    private Run dictateUntil(URI uri, FakeRecognizer phone, DictationEngine.State state) throws Exception {
        Run run = new Run();
        dictate(uri, phone, 0, run, () -> run.listener.states.contains(state));
        assertTrue("reached " + state, run.listener.states.contains(state));
        return run;
    }

    private Run dictate(URI uri, FakeRecognizer phone, long talkMs, Run run) throws Exception {
        return dictate(uri, phone, talkMs, run, null);
    }

    private Run dictate(URI uri, FakeRecognizer phone, long talkMs, Run run,
            java.util.function.BooleanSupplier stopWhen) throws Exception {
        if (phone != null) phone.mic = run.mic;
        ServerDictationEngine engine = new ServerDictationEngine(uri, pending, new TestMain(mainThread),
                run.mic, () -> true, phone == null ? null : cb -> phone.start(cb), TIMEOUTS, log::add);
        mainThread.submit(() -> engine.start("host/pane", run.listener)).get();
        Thread.sleep(talkMs);
        for (int i = 0; stopWhen != null && i < 2000 && !stopWhen.getAsBoolean(); i++) Thread.sleep(5);
        run.stoppedAt = System.currentTimeMillis();
        mainThread.submit(engine::stop).get();
        return run;
    }

    private static final class Run {
        final ScriptedMic mic = new ScriptedMic();
        final RecordingListener listener = new RecordingListener();
        long stoppedAt;

        void awaitEnd() throws InterruptedException {
            assertTrue("the session ended", listener.ended.await(15, TimeUnit.SECONDS));
        }
    }

    private static final class RecordingListener implements DictationEngine.Listener {
        final List<DictationEngine.State> states = Collections.synchronizedList(new ArrayList<>());
        final List<String> partials = Collections.synchronizedList(new ArrayList<>());
        final CountDownLatch ended = new CountDownLatch(1);
        volatile String finalText;
        volatile boolean onDevice;
        volatile int finals;
        volatile PendingClip pendingClip;
        volatile long endedAt;

        @Override public void onPartial(String text) {
            partials.add(text);
        }

        volatile int finishing;

        @Override public void onFinishing(double tailSeconds) {
            finishing++;
        }

        @Override public void onFinal(String text) {
            throw new AssertionError("engines call onFinal(text, onDevice)");
        }

        /** Run when the final arrives, before anything else (a check, or a simulated crash). */
        volatile Runnable atFinal;
        /** What finalStored() answers: false as when the draft's write failed. */
        volatile boolean stores = true;

        @Override public boolean finalStored() {
            return stores;
        }

        @Override public void onFinal(String text, boolean onDevice) {
            Runnable r = atFinal;
            if (r != null) r.run();
            finalText = text;
            this.onDevice = onDevice;
            finals++;
        }

        @Override public void onState(DictationEngine.State state) {
            states.add(state);
            if (state == DictationEngine.State.IDLE || state == DictationEngine.State.PENDING) {
                endedAt = System.currentTimeMillis();
                ended.countDown();
            }
        }

        @Override public void onError(DictationException error) {
            states.add(null);
        }

        @Override public void onPending(PendingClip clip) {
            pendingClip = clip;
        }
    }

}
