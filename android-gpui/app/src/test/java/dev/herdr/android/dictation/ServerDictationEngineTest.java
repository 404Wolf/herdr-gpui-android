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

/**
 * The engine against a fake desktop dictation server (the real protocol over a local socket,
 * with the server's exact JSON as agreed for server.py) and a scripted on-device recognizer:
 * the phone always ends up with text, marked offline when it is the phone's, and never loses
 * audio.
 */
public final class ServerDictationEngineTest {
    @Rule public final TemporaryFolder dir = new TemporaryFolder();

    // The desktop's messages, exactly as server.py sends them.
    static final String READY_GPU = "{\"type\":\"ready\",\"backend\":\"gpu\"}";
    static final String READY_CPU = "{\"type\":\"ready\",\"backend\":\"cpu\"}";
    static final String READY_DOWN = "{\"type\":\"ready\",\"backend\":\"down\"}";
    static final String READY_OLD = "{\"type\":\"ready\"}";
    static final String ERROR_SLOW = "{\"type\":\"error\",\"code\":\"backend_slow\","
            + "\"message\":\"transcription backend slow\",\"kind\":\"partial\",\"elapsed_s\":3.0,\"budget_s\":3.0}";
    static final String ERROR_UNAVAILABLE = "{\"type\":\"error\",\"code\":\"backend_unavailable\","
            + "\"message\":\"transcription backend unavailable\"}";
    static final String ERROR_BAD_START = "{\"type\":\"error\",\"code\":\"bad_start\","
            + "\"message\":\"expected start at 16000 Hz\"}";
    static final String ERROR_TOO_LONG = "{\"type\":\"error\",\"code\":\"session_too_long\","
            + "\"message\":\"session too long\"}";

    static final String DESKTOP_WORDS = "words from the desktop";
    static final String PHONE_WORDS = "words from the phone";

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
            assertTrue(log.toString(), log.stream().anyMatch(l -> l.contains("desktop out (desktop error")));
            assertEquals("shown as a desktop error, not as away", 1, run.listener.desktopErrors);
        }
        assertEquals(4, store.keptClips().size());
    }

    @Test public void aDesktopTranscribingOnItsCpuOrDownHandsOverAtOnce() throws Exception {
        for (String ready : new String[] {READY_CPU, READY_DOWN}) {
            if (server != null) server.close();
            server = new FakeServer(Behaviour.fast(ready).finalAfter(4000));
            FakeRecognizer phone = new FakeRecognizer(PHONE_WORDS, 50);
            Run run = dictate(server.uri(), phone, 300);
            run.awaitEnd();
            assertPhoneText(run);
            // Handed over before stop: no budget waited after it.
            assertTrue(run.listener.states.indexOf(DictationEngine.State.ON_DEVICE)
                    < run.listener.states.indexOf(DictationEngine.State.TRANSCRIBING));
            assertTrue(run.listener.endedAt - run.stoppedAt < TIMEOUTS.finalBudgetMs);
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

    // ---- nothing to transcribe, a silenced microphone, desktop errors -------------------------

    static String finalWithCode(String code) {
        return "{\"type\":\"final\",\"text\":\"\",\"code\":\"" + code + "\",\"message\":\"nothing\"}";
    }

    @Test public void theStartMessageIsTheAgreedOneWithNoCapabilities() throws Exception {
        server = new FakeServer(Behaviour.fast(READY_GPU));
        dictate(server.uri(), null, 300).awaitEnd();
        assertEquals("{\"type\":\"start\",\"sample_rate\":16000}", server.texts.get(0));
    }

    @Test public void aFinalWithACodeInsertsNothingAndKeepsNothing() throws Exception {
        String[] codes = {"silent_audio", "quiet_audio", "no_audio", "a_future_code"};
        DictationException.Reason[] reasons = {DictationException.Reason.SILENT_AUDIO,
                DictationException.Reason.QUIET_AUDIO, DictationException.Reason.NO_AUDIO,
                DictationException.Reason.NOTHING};
        for (int i = 0; i < codes.length; i++) {
            for (boolean withPhone : new boolean[] {true, false}) {
                if (server != null) server.close();
                server = new FakeServer(Behaviour.fast(READY_GPU).finalJson(finalWithCode(codes[i])));
                FakeRecognizer phone = withPhone ? new FakeRecognizer(PHONE_WORDS, 0) : null;
                Run run = dictate(server.uri(), phone, 300);
                run.awaitEnd();
                assertEquals(codes[i], reasons[i], run.listener.error.reason());
                assertEquals("nothing inserted, not even the phone's words", 0, run.listener.finals);
                assertEquals(null, run.listener.pendingClip);
                assertEquals(DictationEngine.State.IDLE, last(run.listener.states));
            }
        }
        Thread.sleep(200); // the mic thread's last chunk
        assertTrue("never queued", store.untranscribed().isEmpty());
        assertTrue("never kept", store.keptClips().isEmpty());
    }

    @Test public void anErrorWithAKnownFinalCodeIsFinalToo() throws Exception {
        server = new FakeServer(Behaviour.fast(READY_GPU).errorOnAudio(
                "{\"type\":\"error\",\"code\":\"silent_audio\",\"message\":\"silence\"}"));
        Run run = dictate(server.uri(), null, 300);
        run.awaitEnd();
        assertEquals(DictationException.Reason.SILENT_AUDIO, run.listener.error.reason());
        assertEquals(null, run.listener.pendingClip);
        Thread.sleep(200);
        assertTrue(store.untranscribed().isEmpty());
    }

    @Test public void anErrorWithoutACodeIsADesktopErrorAndTheAudioIsKept() throws Exception {
        server = new FakeServer(Behaviour.fast(READY_GPU).errorOnAudio("{\"type\":\"error\",\"message\":\"x\"}"));
        Run run = dictate(server.uri(), null, 300);
        run.awaitEnd();
        assertEquals(1, run.listener.desktopErrors);
        assertTrue(run.listener.states.contains(DictationEngine.State.OFFLINE_RECORDING));
        assertNotNull("kept for the courier's bounded retries", run.listener.pendingClip);
        assertTrue(log.toString(), log.stream().anyMatch(l -> l.contains("desktop error (no code)")));
    }

    @Test public void aMicrophoneGivingOnlyZerosEndsTheSessionAndKeepsNothing() throws Exception {
        Run run = new Run();
        run.mic.zerosFrom = 0;
        // 30 chunks of zeros come in 0.6 s here; the session ends itself before the stop.
        dictate(closedPort(), null, 1500, run);
        run.awaitEnd();
        assertEquals(DictationException.Reason.SILENCED, run.listener.error.reason());
        assertEquals(null, run.listener.pendingClip);
        Thread.sleep(200);
        assertTrue(store.untranscribed().isEmpty());
        assertTrue(log.toString(), log.stream().anyMatch(l -> l.contains("microphone silenced (DIGITAL_SILENCE)")));
    }

    @Test public void androidSilencingTheRecordingEndsItSooner() throws Exception {
        server = new FakeServer(Behaviour.fast(READY_GPU));
        Run run = new Run();
        run.mic.androidSilenced = true;
        run.mic.zerosFrom = 0;
        dictate(server.uri(), new FakeRecognizer(PHONE_WORDS, 0), 1500, run);
        run.awaitEnd();
        assertEquals(DictationException.Reason.SILENCED, run.listener.error.reason());
        assertEquals(0, run.listener.finals);
        assertTrue(log.toString(), log.stream().anyMatch(l -> l.contains("(SILENCED_BY_ANDROID)")));
        // The desktop is told why it was let go.
        assertTrue(waitFor(() -> server.texts.contains("{\"type\":\"cancel\",\"reason\":\"silenced\"}")));
    }

    @Test public void silenceAfterSpeechKeepsWhatWasHeard() throws Exception {
        Run run = new Run();
        run.mic.zerosFrom = 5;
        dictate(closedPort(), null, 1500, run);
        run.awaitEnd();
        assertNotNull("what was said before the silence is kept", run.listener.pendingClip);
        assertEquals(null, run.listener.error);
    }

    @Test public void aCancelTellsTheDesktopWhy() throws Exception {
        server = new FakeServer(Behaviour.fast(READY_GPU));
        Run run = new Run();
        ServerDictationEngine engine = new ServerDictationEngine(server.uri(), pending, new TestMain(mainThread),
                run.mic, () -> true, null, TIMEOUTS, log::add);
        mainThread.submit(() -> engine.start("host/pane", run.listener)).get();
        Thread.sleep(300);
        mainThread.submit(engine::cancel).get();
        assertTrue(server.texts.toString(), waitFor(
                () -> server.texts.contains("{\"type\":\"cancel\",\"reason\":\"cancelled\"}")));
    }

    @Test public void aMicrophoneThatDoesNotOpenIsLoggedByCause() throws Exception {
        Run run = new Run();
        run.mic.failToOpen = true;
        dictate(closedPort(), null, 100, run);
        run.awaitEnd();
        assertEquals(DictationException.Reason.AUDIO, run.listener.error.reason());
        assertTrue(log.toString(), log.stream().anyMatch(l -> l.matches(
                "dictation: the microphone did not open: IN_USE \\(\\d+ times? since the app started\\)")));
    }

    @Test public void anUploadWithNothingToTranscribeIsFinalAndAnErrorWithoutACodeIsNot() throws Exception {
        File clip = dir.newFile("clip.pcm");
        Files.write(clip.toPath(), new byte[ClipStore.BYTES_PER_SECOND]);
        server = new FakeServer(Behaviour.fast(READY_GPU).finalJson(finalWithCode("silent_audio")));
        PendingDictations.Transcript t = PendingDictations.upload(server.uri(), clip);
        assertEquals(DictationException.Reason.SILENT_AUDIO, t.nothing);
        assertEquals("", t.text);
        assertEquals("{\"type\":\"start\",\"sample_rate\":16000}", server.texts.get(0));
        server.close();
        server = new FakeServer(Behaviour.fast(READY_GPU).errorOnAudio("{\"type\":\"error\",\"message\":\"x\"}"));
        try {
            PendingDictations.upload(server.uri(), clip);
            throw new AssertionError("an error without a code is the desktop's, to retry");
        } catch (PendingDictations.DesktopError expected) {
            assertEquals("", expected.code);
        }
        server.close();
        server = new FakeServer(Behaviour.fast(READY_GPU));
        assertEquals(DESKTOP_WORDS, PendingDictations.upload(server.uri(), clip).text);
    }

    private static boolean waitFor(BooleanSupplier condition) throws InterruptedException {
        for (int i = 0; i < 100; i++) {
            if (condition.getAsBoolean()) return true;
            Thread.sleep(20);
        }
        return condition.getAsBoolean();
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

    private Run dictate(URI uri, FakeRecognizer phone, long talkMs, Run run) throws Exception {
        if (phone != null) phone.mic = run.mic;
        ServerDictationEngine engine = new ServerDictationEngine(uri, pending, new TestMain(mainThread),
                run.mic, () -> true, phone == null ? null : cb -> phone.start(cb), TIMEOUTS, log::add);
        mainThread.submit(() -> engine.start("host/pane", run.listener)).get();
        Thread.sleep(talkMs);
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

    /** The main thread's Handler, on a single-thread executor. */
    private static final class TestMain implements ServerDictationEngine.Main {
        private final ScheduledExecutorService exec;
        private final Map<Runnable, List<ScheduledFuture<?>>> delayed = new ConcurrentHashMap<>();

        TestMain(ScheduledExecutorService exec) {
            this.exec = exec;
        }

        @Override public void post(Runnable r) {
            exec.execute(r);
        }

        @Override public void postDelayed(Runnable r, long delayMs) {
            delayed.computeIfAbsent(r, k -> Collections.synchronizedList(new ArrayList<>()))
                    .add(exec.schedule(r, delayMs, TimeUnit.MILLISECONDS));
        }

        @Override public void remove(Runnable r) {
            List<ScheduledFuture<?>> futures = delayed.remove(r);
            if (futures != null) for (ScheduledFuture<?> f : futures) f.cancel(false);
        }
    }

    /** A microphone giving a distinct 100 ms chunk every 20 ms until stopped. */
    private static final class ScriptedMic implements AudioInput.Opener, AudioInput {
        private final ByteArrayOutputStream given = new ByteArrayOutputStream();
        private int index;
        volatile boolean opened;
        volatile boolean failToOpen;
        /** From this chunk on, exact zeros (a silenced microphone); -1 never. */
        volatile int zerosFrom = -1;
        volatile boolean androidSilenced;

        @Override public AudioInput open() throws Microphone.Failure {
            if (failToOpen) throw new Microphone.Failure(Microphone.Failure.Cause.IN_USE, "in use");
            opened = true;
            return this;
        }

        @Override public int read(byte[] chunk, BooleanSupplier stop) {
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return 0;
            }
            if (stop.getAsBoolean()) return 0;
            index++;
            boolean zero = zerosFrom >= 0 && index > zerosFrom;
            for (int i = 0; i < chunk.length; i++) chunk[i] = zero ? 0 : (byte) (index * 31 + i);
            synchronized (given) {
                given.write(chunk, 0, chunk.length);
            }
            return chunk.length;
        }

        byte[] bytes() {
            synchronized (given) {
                return given.toByteArray();
            }
        }

        @Override public boolean androidSilenced() {
            return androidSilenced;
        }

        @Override public void close() { }
    }

    /**
     * The phone's recognizer: hears the audio, says {@code text} {@code finalDelayMs} after the
     * end of the audio, or fails at the end when {@code text} is null.
     */
    private static final class FakeRecognizer implements OnDeviceRecognizer {
        final String text;
        final long finalDelayMs;
        final ByteArrayOutputStream heard = new ByteArrayOutputStream();
        String partial;
        boolean failOnFirstAudio;
        /** The microphone it was started with; it must be open already. */
        ScriptedMic mic;
        volatile boolean startedBeforeTheMic;
        volatile boolean started;
        volatile boolean cancelled;
        volatile boolean ended;
        private Callback callback;

        FakeRecognizer(String text, long finalDelayMs) {
            this.text = text;
            this.finalDelayMs = finalDelayMs;
        }

        OnDeviceRecognizer start(Callback cb) {
            started = true;
            if (mic != null && !mic.opened) startedBeforeTheMic = true;
            callback = cb;
            return this;
        }

        @Override public synchronized void write(byte[] chunk, int length) {
            boolean first = heard.size() == 0;
            heard.write(chunk, 0, length);
            if (first && failOnFirstAudio) callback.onError("scripted failure");
            if (first && partial != null) callback.onPartial(partial);
        }

        @Override public void endOfAudio() {
            ended = true;
            Thread t = new Thread(() -> {
                try {
                    Thread.sleep(finalDelayMs);
                } catch (InterruptedException e) {
                    return;
                }
                if (cancelled) return;
                if (text == null) callback.onError("scripted failure");
                else callback.onFinal(text);
            });
            t.setDaemon(true);
            t.start();
        }

        @Override public void cancel() {
            cancelled = true;
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
        volatile DictationException error;
        volatile int desktopErrors;

        @Override public void onPartial(String text) {
            partials.add(text);
        }

        @Override public void onFinal(String text) {
            throw new AssertionError("engines call onFinal(text, onDevice)");
        }

        @Override public void onFinal(String text, boolean onDevice) {
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
            this.error = error;
            states.add(null);
        }

        @Override public void onDesktopError() {
            desktopErrors++;
        }

        @Override public void onPending(PendingClip clip) {
            pendingClip = clip;
        }
    }

    /** What the fake desktop does in a session. */
    private static final class Behaviour {
        String ready;
        long finalDelayMs;
        String errorOnAudio;
        boolean dropOnAudio;
        /** The desktop's answer to stop, instead of its words. */
        String finalJson;

        static Behaviour fast(String ready) {
            Behaviour b = new Behaviour();
            b.ready = ready;
            return b;
        }

        static Behaviour silent() {
            return new Behaviour();
        }

        Behaviour finalAfter(long ms) {
            finalDelayMs = ms;
            return this;
        }

        Behaviour errorOnAudio(String error) {
            errorOnAudio = error;
            return this;
        }

        Behaviour finalJson(String json) {
            finalJson = json;
            return this;
        }

        Behaviour dropOnAudio() {
            dropOnAudio = true;
            return this;
        }
    }

    /** The desktop's dictation server, speaking server.py's protocol on a local port. */
    private static final class FakeServer implements java.io.Closeable {
        private final ServerSocket socket = new ServerSocket(0);
        private final Behaviour behaviour;
        private final ByteArrayOutputStream audio = new ByteArrayOutputStream();
        private final List<Socket> open = Collections.synchronizedList(new ArrayList<>());
        /** Every text message the phone sent, start included, in order. */
        final List<String> texts = Collections.synchronizedList(new ArrayList<>());

        FakeServer(Behaviour behaviour) throws IOException {
            this.behaviour = behaviour;
            Thread t = new Thread(this::serve, "fake-dictation-server");
            t.setDaemon(true);
            t.start();
        }

        URI uri() {
            return URI.create("ws://127.0.0.1:" + socket.getLocalPort() + "/v1/dictate");
        }

        byte[] audio() {
            synchronized (audio) {
                return audio.toByteArray();
            }
        }

        private void serve() {
            while (!socket.isClosed()) {
                try {
                    Socket s = socket.accept();
                    open.add(s);
                    Thread t = new Thread(() -> session(s), "fake-dictation-session");
                    t.setDaemon(true);
                    t.start();
                } catch (IOException closed) {
                    return;
                }
            }
        }

        private void session(Socket s) {
            try {
                InputStream in = s.getInputStream();
                OutputStream out = s.getOutputStream();
                handshake(in, out);
                WebSocketFrames.Reader reader = new WebSocketFrames.Reader(in, 1 << 20);
                WebSocketFrames.Message start = reader.next();
                texts.add(start.text());
                assertEquals("start", new JSONObject(start.text()).getString("type"));
                if (behaviour.ready == null) {
                    while (true) { // never ready: read until the phone gives up
                        WebSocketFrames.Message m = reader.next();
                        if (m.opcode == WebSocketFrames.TEXT) texts.add(m.text());
                    }
                }
                send(out, behaviour.ready);
                boolean heard = false;
                while (true) {
                    WebSocketFrames.Message m = reader.next();
                    if (m.opcode == WebSocketFrames.BINARY) {
                        synchronized (audio) {
                            audio.write(m.payload, 0, m.payload.length);
                        }
                        if (!heard) {
                            heard = true;
                            if (behaviour.errorOnAudio != null) {
                                send(out, behaviour.errorOnAudio);
                                s.close();
                                return;
                            }
                            if (behaviour.dropOnAudio) {
                                s.close();
                                return;
                            }
                            send(out, "{\"type\":\"partial\",\"text\":\"words\"}");
                        }
                    } else if (m.opcode == WebSocketFrames.TEXT) {
                        texts.add(m.text());
                        if (!new JSONObject(m.text()).getString("type").equals("stop")) continue;
                        Thread.sleep(behaviour.finalDelayMs);
                        send(out, behaviour.finalJson != null ? behaviour.finalJson
                                : "{\"type\":\"final\",\"text\":\"" + DESKTOP_WORDS + "\"}");
                        s.close();
                        return;
                    }
                }
            } catch (Exception gone) {
                // The phone hung up: that is its right.
            }
        }

        private static void handshake(InputStream in, OutputStream out) throws IOException {
            String key = null;
            for (String line = line(in); !line.isEmpty(); line = line(in)) {
                if (line.startsWith("Sec-WebSocket-Key:")) key = line.substring(18).trim();
            }
            out.write(("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n"
                    + "Sec-WebSocket-Accept: " + WebSocketFrames.acceptFor(key) + "\r\n\r\n")
                    .getBytes(StandardCharsets.US_ASCII));
            out.flush();
        }

        /** One request line, read a byte at a time so no websocket bytes are taken with it. */
        private static String line(InputStream in) throws IOException {
            StringBuilder b = new StringBuilder();
            for (int c = in.read(); c != '\n'; c = in.read()) {
                if (c < 0) throw new IOException("closed during the handshake");
                if (c != '\r') b.append((char) c);
            }
            return b.toString();
        }

        /** Unmasked server text frame. */
        private static synchronized void send(OutputStream out, String text) throws IOException {
            byte[] b = text.getBytes(StandardCharsets.UTF_8);
            ByteArrayOutputStream f = new ByteArrayOutputStream();
            f.write(0x80 | WebSocketFrames.TEXT);
            if (b.length < 126) {
                f.write(b.length);
            } else {
                f.write(126);
                f.write(b.length >>> 8);
                f.write(b.length & 0xFF);
            }
            f.write(b, 0, b.length);
            out.write(f.toByteArray());
            out.flush();
        }

        @Override public void close() throws IOException {
            socket.close();
            synchronized (open) {
                for (Socket s : open) s.close();
            }
        }
    }
}
