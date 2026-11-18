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


    /**
     * The engine's waits, told apart by length. They never run on the wall clock here: the
     * engine's main thread is {@link TestMain#manual}, so a timeout fires only when a test fires
     * it, and none can beat a reply on a loaded machine.
     */
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

    @Test public void theDesktopsUnverifiedCutsReachTheComposerAsOffsetsInItsText() throws Exception {
        // server.py: "unverified_cuts" on a partial or final, code point offsets in its text.
        server = new FakeServer(Behaviour.fast(READY_GPU).cuts("[8, 0, 99, 8, 2.5]"));
        Run run = dictate(server.uri(), new FakeRecognizer(PHONE_WORDS, 0), 300);
        run.awaitEnd();
        assertEquals(DESKTOP_WORDS, run.listener.finalText);
        // "words fr|om the desktop": the ends, past the text, repeats and fractions are dropped.
        assertArrayEquals(new int[] {8}, run.listener.finalCuts);
        assertFalse(run.listener.partialCuts.isEmpty());
        // The partial "words" is 5 long: a cut at 8 is past it.
        assertArrayEquals(new int[0], run.listener.partialCuts.get(0));
    }

    @Test public void withoutTheFieldThereAreNoCuts() throws Exception {
        server = new FakeServer(Behaviour.fast(READY_GPU));
        Run run = dictate(server.uri(), new FakeRecognizer(PHONE_WORDS, 0), 300);
        run.awaitEnd();
        assertEquals(DESKTOP_WORDS, run.listener.finalText);
        assertArrayEquals(new int[0], run.listener.finalCuts);
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
        // The desktop's final is held until the phone's text has landed (a gate, not a timer):
        // however loaded the machine, the desktop is "slow" by construction.
        java.util.concurrent.CountDownLatch phoneLanded = new java.util.concurrent.CountDownLatch(1);
        server = new FakeServer(Behaviour.fast(READY_GPU).finalWhen(phoneLanded, 30_000));
        FakeRecognizer phone = new FakeRecognizer(PHONE_WORDS, 100);
        Run run = talkUntilListening(server.uri(), phone);
        // The phone's words are in; the one wait left is the budget for the desktop (ready's
        // timer is cleared), and nothing has landed.
        assertTrue("the phone answered", waitFor(() -> phone.answered));
        assertLive(run, Collections.singletonList(TIMEOUTS.finalBudgetMs));
        assertEquals("nothing before the budget", 0, run.listener.finals);
        // Not a moment before it: just short of the budget, still nothing.
        run.main.advance(TIMEOUTS.finalBudgetMs - 1);
        assertEquals("nothing before the budget", 0, run.listener.finals);
        run.main.advance(1);
        run.awaitEnd();
        assertPhoneText(run);
        // The phone's text came while the desktop's final was still held: the engine did not
        // wait for it. Then the desktop's late final goes, and lands nowhere.
        assertEquals(1, run.listener.finals);
        phoneLanded.countDown();
        assertTrue("the desktop's late final was sent", server.awaitFinalDone(10_000));
        assertFalse("the gate was opened by the phone's text, not by its safety timeout", server.gateTimedOut);
        // Anything the engine read from the socket is handled on its main thread: flush it.
        mainThread.submit(() -> { }).get();
        assertKeptWithAllAudio(run);
        assertEquals("the late desktop final never lands a second text", 1, run.listener.finals);
        assertEquals(PHONE_WORDS, run.listener.finalText);
    }

    @Test public void anUnreachableDesktopGivesThePhonesText() throws Exception {
        Run run = dictate(closedPort(), new FakeRecognizer(PHONE_WORDS, 50), 300);
        run.awaitEnd();
        assertPhoneText(run);
        assertKeptWithAllAudio(run);
    }

    @Test public void aDesktopThatNeverSaysReadyGivesThePhonesText() throws Exception {
        server = new FakeServer(Behaviour.silent());
        Run run = new Run();
        // While talking, the wait for "ready" runs out.
        dictate(server.uri(), new FakeRecognizer(PHONE_WORDS, 50), 300, run, null, () -> {
            assertLive(run, Collections.singletonList(TIMEOUTS.readyMs));
            run.main.advance(TIMEOUTS.readyMs);
        });
        run.awaitEnd();
        assertPhoneText(run);
        assertTrue(run.listener.states.contains(DictationEngine.State.ON_DEVICE));
        assertKeptWithAllAudio(run);
    }

    @Test public void everyDesktopErrorGivesThePhonesText() throws Exception {
        for (String error : new String[] {ERROR_SLOW, ERROR_UNAVAILABLE, ERROR_BAD_START, ERROR_TOO_LONG}) {
            if (server != null) server.close();
            server = new FakeServer(Behaviour.fast(READY_GPU).errorOnAudio(error));
            // Talks until the phone has taken over: the engine shows ON_DEVICE only when the
            // desktop drops out before the stop, and the error may come after a fixed 300 ms.
            Run run = talkUntilThePhoneTakesOver(server.uri(), new FakeRecognizer(PHONE_WORDS, 50));
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
        Run run = talkUntilThePhoneTakesOver(server.uri(), phone);
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
        // Stopped while waiting for "ready", alone: that wait, then the long one for a final.
        assertLive(run, java.util.Arrays.asList(TIMEOUTS.readyAloneMs, TIMEOUTS.finalMs));
        run.main.advance(TIMEOUTS.readyAloneMs);
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
        // A durable parked clip, "not transcribed yet · retry": no 48 h expiry, never sent on its own.
        assertTrue("not rescue audio that expires", store.keptClips().isEmpty());
        assertEquals(1, store.parked().size());
        assertArrayEquals(run.mic.bytes(), Files.readAllBytes(store.audio(store.parked().get(0).id).toPath()));
        assertTrue(store.untranscribed().isEmpty());
        // Still there after a restart, however long after.
        ClipStore later = new ClipStore(new File(dir.getRoot(), "pending"), () -> System.currentTimeMillis() + 3 * ClipStore.KEEP_MS);
        assertEquals(1, later.parked().size());
    }

    @Test public void aPhoneFinalWhoseTextCouldNotBeStoredIsParkedNeverExpiring() throws Exception {
        // The desktop is on its CPU: the phone transcribes; its text cannot be saved.
        server = new FakeServer(Behaviour.fast(READY_CPU));
        Run run = new Run();
        run.listener.stores = false;
        dictate(server.uri(), new FakeRecognizer(PHONE_WORDS, 50), 300, run);
        run.awaitEnd();
        Thread.sleep(300);
        assertEquals(PHONE_WORDS, run.listener.finalText);
        assertTrue(run.listener.onDevice);
        assertTrue("not rescue audio that expires", store.keptClips().isEmpty());
        assertEquals(1, store.parked().size());
        assertArrayEquals(run.mic.bytes(), Files.readAllBytes(store.audio(store.parked().get(0).id).toPath()));
        ClipStore later = new ClipStore(new File(dir.getRoot(), "pending"),
                () -> System.currentTimeMillis() + 3 * ClipStore.KEEP_MS);
        assertEquals("still there 3 x 48 h later", 1, later.parked().size());
    }

    @Test public void aStoredPhoneFinalKeepsItsRecordingAsRescueAudio() throws Exception {
        server = new FakeServer(Behaviour.fast(READY_CPU));
        Run run = dictate(server.uri(), new FakeRecognizer(PHONE_WORDS, 50), 300);
        run.awaitEnd();
        Thread.sleep(300);
        assertEquals(1, store.keptClips().size());
        assertTrue(store.parked().isEmpty());
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
        java.util.concurrent.CountDownLatch answer = new java.util.concurrent.CountDownLatch(1);
        server = new FakeServer(Behaviour.fast(READY_GPU).finalWhen(answer, 30_000));
        Run run = talkUntilListening(server.uri(), null);
        // The long wait for the final, and nothing else (ready's timer is cleared).
        assertLive(run, Collections.singletonList(TIMEOUTS.finalMs));
        // Past the budget and the wait for ready: still waiting for the desktop.
        run.main.advance(TIMEOUTS.finalBudgetMs + TIMEOUTS.readyMs);
        assertEquals(0, run.listener.finals);
        assertLive(run, Collections.singletonList(TIMEOUTS.finalMs));
        answer.countDown();
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
        assertLive(run, Collections.singletonList(TIMEOUTS.onDeviceFinalMs));
        run.main.advance(TIMEOUTS.onDeviceFinalMs);
        run.awaitEnd();
        assertNotNull(run.listener.pendingClip);
        assertEquals(1, store.untranscribed().size());
    }

    @Test public void aRecognizerFailingWhileTheDesktopIsFineChangesNothing() throws Exception {
        java.util.concurrent.CountDownLatch answer = new java.util.concurrent.CountDownLatch(1);
        server = new FakeServer(Behaviour.fast(READY_GPU).finalWhen(answer, 30_000));
        FakeRecognizer phone = new FakeRecognizer(null, 0);
        phone.failOnFirstAudio = true;
        Run run = new Run();
        dictate(server.uri(), phone, 300, run, () -> run.listener.states.contains(DictationEngine.State.LISTENING)
                && log.stream().anyMatch(l -> l.contains("the phone's recognizer failed")));
        // No phone text to fall back on: the long wait applies, past the budget and ready's.
        assertLive(run, Collections.singletonList(TIMEOUTS.finalMs));
        run.main.advance(TIMEOUTS.finalBudgetMs + TIMEOUTS.readyMs);
        assertEquals(0, run.listener.finals);
        answer.countDown();
        run.awaitEnd();
        // And the desktop is in time.
        assertEquals(DESKTOP_WORDS, run.listener.finalText);
    }

    // ---- nothing to transcribe, a silenced microphone, desktop errors -------------------------

    static String finalWithCode(String code) {
        return "{\"type\":\"final\",\"text\":\"\",\"code\":\"" + code + "\",\"message\":\"nothing\"}";
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
        assertTrue("never parked", store.parked().isEmpty());
    }

    @Test public void anErrorWithAKnownFinalCodeIsFinalToo() throws Exception {
        server = new FakeServer(Behaviour.fast(READY_GPU).errorOnAudio(
                "{\"type\":\"error\",\"code\":\"silent_audio\",\"message\":\"silence\"}"));
        // The desktop's error, after it heard audio, ends the session: stopped only after that.
        Run run = new Run();
        talkUntilItEndsItself(server.uri(), null, run);
        run.awaitEnd();
        assertEquals(DictationException.Reason.SILENT_AUDIO, run.listener.error.reason());
        assertEquals(null, run.listener.pendingClip);
        Thread.sleep(200);
        assertTrue(store.untranscribed().isEmpty());
    }

    @Test public void anErrorWithoutACodeIsADesktopErrorAndTheAudioIsKept() throws Exception {
        server = new FakeServer(Behaviour.fast(READY_GPU).errorOnAudio("{\"type\":\"error\",\"message\":\"x\"}"));
        // Stopped once the desktop's error is in (it comes after the desktop heard audio).
        Run run = new Run();
        dictate(server.uri(), null, 300, run, () -> run.listener.desktopErrors > 0);
        run.awaitEnd();
        assertEquals(1, run.listener.desktopErrors);
        assertTrue(run.listener.states.contains(DictationEngine.State.OFFLINE_RECORDING));
        assertNotNull("kept for the courier's counted retries", run.listener.pendingClip);
        assertTrue(log.toString(), log.stream().anyMatch(l -> l.contains("desktop error (no code)")));
    }

    @Test public void aMicrophoneGivingOnlyZerosEndsTheSessionAndKeepsNothing() throws Exception {
        Run run = new Run();
        run.mic.zerosFrom = 0;
        // 30 chunks of zeros; the session ends itself, and is only stopped after that.
        talkUntilItEndsItself(closedPort(), null, run);
        run.awaitEnd();
        assertEquals(DictationException.Reason.SILENCED, run.listener.error.reason());
        assertEquals(null, run.listener.pendingClip);
        Thread.sleep(200);
        assertTrue(store.untranscribed().isEmpty());
        assertTrue(store.parked().isEmpty());
        assertTrue(log.toString(), log.stream().anyMatch(l -> l.contains("microphone silenced (DIGITAL_SILENCE)")));
    }

    @Test public void androidSilencingTheRecordingEndsItAndTellsTheDesktopWhy() throws Exception {
        server = new FakeServer(Behaviour.fast(READY_GPU));
        Run run = new Run();
        run.mic.androidSilenced = true;
        run.mic.zerosFrom = 0;
        // The silence starts once the desktop is listening, so there is a desktop to tell: on a
        // loaded machine its "ready" could otherwise come after the session had already ended.
        run.mic.holdUntil = () -> run.listener.states.contains(DictationEngine.State.LISTENING);
        talkUntilItEndsItself(server.uri(), new FakeRecognizer(PHONE_WORDS, 0), run);
        run.awaitEnd();
        assertEquals(DictationException.Reason.SILENCED, run.listener.error.reason());
        assertEquals(0, run.listener.finals);
        assertTrue(log.toString(), log.stream().anyMatch(l -> l.contains("(SILENCED_BY_ANDROID)")));
        assertTrue(server.texts.toString(), waitFor(
                () -> server.texts.contains("{\"type\":\"cancel\",\"reason\":\"silenced\"}")));
    }

    @Test public void silenceAfterSpeechKeepsWhatWasHeard() throws Exception {
        Run run = new Run();
        run.mic.zerosFrom = 5;
        talkUntilItEndsItself(closedPort(), null, run);
        run.awaitEnd();
        assertNotNull("what was said before the silence is kept", run.listener.pendingClip);
        assertEquals(null, run.listener.error);
    }

    @Test public void aCancelTellsTheDesktopWhy() throws Exception {
        server = new FakeServer(Behaviour.fast(READY_GPU));
        Run run = new Run();
        ServerDictationEngine engine = new ServerDictationEngine(server.uri(), pending,
                TestMain.manual(mainThread), run.mic, () -> true, null, TIMEOUTS, log::add);
        mainThread.submit(() -> engine.start("host/pane", run.listener)).get();
        // Cancelled once the desktop is listening, so there is a desktop to tell.
        assertTrue("listening", waitFor(() -> run.listener.states.contains(DictationEngine.State.LISTENING)));
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

    @Test public void anUploadWithNothingToTranscribeIsFinalAndAnErrorWithoutACodeIsCounted() throws Exception {
        File clip = dir.newFile("clip.pcm");
        Files.write(clip.toPath(), new byte[ClipStore.BYTES_PER_SECOND]);
        server = new FakeServer(Behaviour.fast(READY_GPU).finalJson(finalWithCode("silent_audio")));
        try {
            PendingDictations.upload(server.uri(), "id-1", clip);
            throw new AssertionError("nothing to transcribe");
        } catch (UploadFailure f) {
            assertEquals(UploadFailure.Kind.NOTHING, f.kind);
            assertEquals(DictationException.Reason.SILENT_AUDIO, f.nothing);
        }
        server.close();
        server = new FakeServer(Behaviour.fast(READY_GPU).finalJson("{\"type\":\"error\",\"message\":\"x\"}"));
        try {
            PendingDictations.upload(server.uri(), "id-1", clip);
            throw new AssertionError("an error without a code");
        } catch (UploadFailure f) {
            assertEquals("the desktop took it: counted, retried a bounded number of times",
                    UploadFailure.Kind.FAILED, f.kind);
            assertTrue(f.attempted);
        }
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

    /** Like {@link #dictate}, stopping once the session reached {@code state} (10 s at most). */
    private Run dictateUntil(URI uri, FakeRecognizer phone, DictationEngine.State state) throws Exception {
        Run run = new Run();
        dictate(uri, phone, 0, run, () -> run.listener.states.contains(state));
        assertTrue("reached " + state, run.listener.states.contains(state));
        return run;
    }

    /**
     * Talks 300 ms at least, and on until the desktop's error is in and the phone has taken over
     * (10 s at most). The fake desktop holds its socket open after the error, so the take-over
     * is the error's, never a failed write's.
     */
    private Run talkUntilThePhoneTakesOver(URI uri, FakeRecognizer phone) throws Exception {
        Run run = new Run();
        dictate(uri, phone, 300, run, () -> run.listener.desktopErrors > 0
                && run.listener.states.contains(DictationEngine.State.ON_DEVICE));
        return run;
    }

    /**
     * The engine's live timers are {@code expected} (their lengths, earliest first): waits for
     * timers the engine sets from another thread (ready's, once the microphone is open), 10 s
     * at most.
     */
    private static void assertLive(Run run, List<Long> expected) throws Exception {
        List<Long> live = run.main.live();
        for (int i = 0; i < 500 && !live.equals(expected); i++) {
            Thread.sleep(20);
            live = run.main.live();
        }
        assertEquals("the engine's live timers", expected, live);
    }

    /** Talks 300 ms at least, and on until the desktop is listening (10 s at most). */
    private Run talkUntilListening(URI uri, FakeRecognizer phone) throws Exception {
        Run run = new Run();
        dictate(uri, phone, 300, run, () -> run.listener.states.contains(DictationEngine.State.LISTENING));
        assertTrue("the desktop is listening", run.listener.states.contains(DictationEngine.State.LISTENING));
        return run;
    }

    /** Talks until the session ends by itself (a silenced microphone; 10 s at most), then stops. */
    private void talkUntilItEndsItself(URI uri, FakeRecognizer phone, Run run) throws Exception {
        dictate(uri, phone, 0, run, () -> run.listener.ended.getCount() == 0);
    }

    private Run dictate(URI uri, FakeRecognizer phone, long talkMs, Run run) throws Exception {
        return dictate(uri, phone, talkMs, run, null);
    }

    private Run dictate(URI uri, FakeRecognizer phone, long talkMs, Run run,
            java.util.function.BooleanSupplier stopWhen) throws Exception {
        return dictate(uri, phone, talkMs, run, stopWhen, null);
    }

    /** What a test does while the user talks (fire a timer), after the start. */
    private interface WhileTalking {
        void run() throws Exception;
    }

    private Run dictate(URI uri, FakeRecognizer phone, long talkMs, Run run,
            java.util.function.BooleanSupplier stopWhen, WhileTalking during) throws Exception {
        if (phone != null) phone.mic = run.mic;
        // Timers run only when the test fires them (TestMain.manual).
        run.main = TestMain.manual(mainThread);
        ServerDictationEngine engine = new ServerDictationEngine(uri, pending, run.main,
                run.mic, () -> true, phone == null ? null : cb -> phone.start(cb), TIMEOUTS, log::add);
        mainThread.submit(() -> engine.start("host/pane", run.listener)).get();
        if (during != null) during.run();
        Thread.sleep(talkMs);
        for (int i = 0; stopWhen != null && i < 2000 && !stopWhen.getAsBoolean(); i++) Thread.sleep(5);
        run.stoppedAt = System.currentTimeMillis();
        mainThread.submit(engine::stop).get();
        return run;
    }

    private static final class Run {
        final ScriptedMic mic = new ScriptedMic();
        TestMain main;
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
        volatile DictationException error;
        volatile int desktopErrors;

        @Override public void onDesktopError() {
            desktopErrors++;
        }

        @Override public void onPartial(String text) {
            partials.add(text);
        }

        /** The unverified cuts that came with each partial, and with the final. */
        final List<int[]> partialCuts = Collections.synchronizedList(new ArrayList<>());
        volatile int[] finalCuts = new int[0];

        @Override public void onPartial(String text, int[] cuts) {
            partialCuts.add(cuts);
            onPartial(text);
        }

        @Override public void onFinal(String text, boolean onDevice, int[] cuts) {
            finalCuts = cuts;
            onFinal(text, onDevice);
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
            this.error = error;
            states.add(null);
        }

        @Override public void onPending(PendingClip clip) {
            pendingClip = clip;
        }
    }

}
