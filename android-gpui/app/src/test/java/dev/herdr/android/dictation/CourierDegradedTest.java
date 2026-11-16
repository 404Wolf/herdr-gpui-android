package dev.herdr.android.dictation;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.Callable;
import java.util.concurrent.Delayed;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * Wolf's long dictations "took forever": the desktop log showed one 124.6 s clip sent four times
 * in two minutes, each cut off by backend_slow, and new live sessions reset the courier's wait. The
 * courier now counts the uploads the desktop took, parks a clip after {@link ClipStore#MAX_ATTEMPTS}
 * (its audio kept, its chip "not transcribed yet · retry"), waits long and growing while the
 * desktop is degraded, never cut short by sessions or the network, and goes on once a session
 * finds the desktop on its GPU. Its uploads carry the clip's id and "mode":"upload" so a resend
 * resumes on the desktop. Time is a fake clock with a scheduler run by hand.
 */
public final class CourierDegradedTest {
    @Rule public final TemporaryFolder dir = new TemporaryFolder();

    /** 124.6 s of PCM16 16 kHz mono: the clip from Wolf's log. */
    static final int LONG_CLIP_BYTES = 1246 * ClipStore.BYTES_PER_SECOND / 10;

    private final AtomicLong now = new AtomicLong(1_000_000);
    private final Scheduler worker = new Scheduler(now);

    private ClipStore store() {
        return new ClipStore(new File(dir.getRoot(), "pending"), now::get);
    }

    private static PendingClip record(ClipStore store, String target, int bytes) {
        ClipStore.Recording r = store.record(target, 1234);
        byte[] chunk = new byte[ClipStore.BYTES_PER_SECOND];
        for (int left = bytes; left > 0; left -= chunk.length) {
            assertTrue(r.write(chunk, Math.min(chunk.length, left)));
        }
        return r.keep();
    }

    /** A desktop that is slow: every upload it takes ends in backend_slow. */
    private static UploadFailure slow() {
        return new UploadFailure(UploadFailure.Kind.DEGRADED, true, "server error backend_slow");
    }

    @Test public void aLongClipUnderASlowBackendIsSentAtMostThreeTimesWithGrowingGaps() {
        ClipStore store = store();
        PendingClip clip = record(store, "pane", LONG_CLIP_BYTES);
        List<Long> sends = new ArrayList<>();
        PendingDictations pending = new PendingDictations(store, (c, file) -> {
            sends.add(now.get());
            assertEquals(LONG_CLIP_BYTES, file.length());
            throw slow();
        }, worker, Runnable::run, line -> { }, now::get);
        pending.saved();
        // Two hours: live sessions keep finding the desktop on its CPU, the network flaps.
        for (int s = 0; s < 2 * 60 * 60; s++) {
            // Off the passes' own seconds: a session at the very moment a wait ends starts a new one.
            if (s % 30 == 7) pending.desktopReady(false);
            if (s % 45 == 22) pending.kick(0);
            worker.advance(1000);
        }
        assertEquals("sends: " + sends, ClipStore.MAX_ATTEMPTS, sends.size());
        long first = sends.get(1) - sends.get(0), second = sends.get(2) - sends.get(1);
        assertTrue("gaps grow: " + first + " then " + second, second > first);
        assertTrue("a degraded wait is minutes, not seconds: " + first, first >= PendingDictations.FIRST_DEGRADED_RETRY_MS);
        // Parked: kept with its audio, no longer sent on its own.
        assertEquals(1, store.parked().size());
        assertTrue(store.untranscribed().isEmpty());
        assertTrue(store.audio(clip.id).isFile());
        assertEquals(LONG_CLIP_BYTES, store.audio(clip.id).length());
        assertEquals("still counted as waiting", 1, pending.count());
        // A restart remembers both the count and the parking.
        assertEquals(1, store().parked().size());
    }

    @Test public void newLiveSessionsOnTheCpuNeverCutTheDegradedWaitShort() {
        ClipStore store = store();
        record(store, "pane", 10);
        List<Long> sends = new ArrayList<>();
        PendingDictations pending = new PendingDictations(store, (c, file) -> {
            sends.add(now.get());
            throw slow();
        }, worker, Runnable::run, line -> { }, now::get);
        pending.saved();
        worker.advance(PendingDictations.FIRST_RETRY_MS);
        assertEquals(1, sends.size());
        long at = sends.get(0);
        for (int i = 0; i < 100; i++) {
            pending.desktopReady(false);
            pending.desktopDegraded();
            pending.kick(0);
            pending.saved();
            worker.advance(1000);
        }
        assertEquals("nothing sent in the degraded wait", 1, sends.size());
        worker.advance(PendingDictations.FIRST_DEGRADED_RETRY_MS);
        assertEquals(2, sends.size());
        assertTrue(sends.get(1) - at >= PendingDictations.FIRST_DEGRADED_RETRY_MS);
    }

    @Test public void theDesktopBackOnItsGpuSendsTheClipAtOnceAndItsFinalLandsOnce() {
        ClipStore store = store();
        PendingClip clip = record(store, "pane", LONG_CLIP_BYTES);
        boolean[] gpu = {false};
        List<Long> sends = new ArrayList<>();
        PendingDictations pending = new PendingDictations(store, (c, file) -> {
            sends.add(now.get());
            if (!gpu[0]) throw slow();
            return "the whole dictation";
        }, worker, Runnable::run, line -> { }, now::get);
        List<String> placed = new ArrayList<>();
        pending.setListener(new PendingDictations.Listener() {
            @Override public void onTranscribed(PendingClip c, String text) {
                // The audio is still there until the final was received and stored.
                placed.add(c.id + "=" + text);
                pending.delivered(c.id);
            }

            @Override public void onChanged() { }
        });
        pending.saved();
        worker.advance(PendingDictations.FIRST_RETRY_MS);
        assertEquals(1, sends.size());
        assertTrue(store.audio(clip.id).isFile());
        // A live session finds the desktop on its GPU: the wait ends, the clip goes now.
        gpu[0] = true;
        pending.desktopReady(true);
        worker.advance(0);
        assertEquals(2, sends.size());
        assertEquals(Collections.singletonList(clip.id + "=the whole dictation"), placed);
        assertFalse("deleted only after the final", store.audio(clip.id).exists());
        assertEquals(0, pending.count());
        // Nothing more goes out, and the text never lands a second time.
        pending.desktopReady(true);
        pending.kick(0);
        worker.advance(60 * 60 * 1000);
        assertEquals(2, sends.size());
        assertEquals(1, placed.size());
    }

    @Test public void aDesktopOnItsCpuIsNotSentTheClipAndThatIsNoAttempt() {
        ClipStore store = store();
        PendingClip clip = record(store, "pane", 10);
        int[] tries = {0};
        PendingDictations pending = new PendingDictations(store, (c, file) -> {
            tries[0]++;
            throw new UploadFailure(UploadFailure.Kind.DEGRADED, false, "backend cpu: not sent");
        }, worker, Runnable::run, line -> { }, now::get);
        pending.saved();
        worker.advance(24L * 60 * 60 * 1000);
        assertTrue("it keeps trying, slowly: " + tries[0], tries[0] >= 3 && tries[0] < 60);
        assertTrue("never parked for what the desktop did not take", store.parked().isEmpty());
        assertEquals(clip.id, store.untranscribed().get(0).id);
    }

    @Test public void retryAfterTheLastClipParkedWhileDegradedSendsAtOnce() {
        ClipStore store = store();
        PendingClip clip = record(store, "pane", 10);
        boolean[] slow = {true};
        List<Long> sends = new ArrayList<>();
        PendingDictations pending = new PendingDictations(store, (c, file) -> {
            sends.add(now.get());
            if (slow[0]) throw slow();
            return "finally";
        }, worker, Runnable::run, line -> { }, now::get);
        pending.saved();
        worker.advance(2 * 60 * 60 * 1000);
        assertEquals(ClipStore.MAX_ATTEMPTS, sends.size());
        assertEquals(1, store.parked().size());
        // The degraded pass that found nothing waiting left no job: retry must start one itself.
        slow[0] = false;
        pending.retry(clip.id);
        worker.advance(0);
        assertEquals(ClipStore.MAX_ATTEMPTS + 1, sends.size());
        assertTrue(store.parked().isEmpty());
        assertTrue(store.untranscribed().isEmpty());
    }

    @Test public void aParkedClipIsSentAgainOnlyByHand() {
        ClipStore store = store();
        PendingClip clip = record(store, "pane", 10);
        for (int i = 0; i < ClipStore.MAX_ATTEMPTS; i++) store.attempted(clip.id);
        List<String> sent = new ArrayList<>();
        PendingDictations pending = new PendingDictations(store, (c, file) -> {
            sent.add(c.id);
            return "text";
        }, worker, Runnable::run, line -> { }, now::get);
        pending.desktopReady(true);
        pending.kick(0);
        worker.advance(10 * 60 * 1000);
        assertTrue(sent.isEmpty());
        assertEquals(clip.id, pending.parked().get(0).id);
        // "retry" on its chip.
        pending.retry(clip.id);
        worker.advance(0);
        assertEquals(Collections.singletonList(clip.id), sent);
        assertTrue(pending.parked().isEmpty());
    }

    // ---- the upload itself, against a fake desktop ----------------------------------------

    @Test public void anUploadCarriesTheClipsIdAndModeAndWaitsThroughFinishing() throws Exception {
        try (FakeDesktop desktop = new FakeDesktop("{\"type\":\"ready\",\"backend\":\"gpu\"}",
                "{\"type\":\"status\",\"state\":\"finishing\",\"tail_s\":12.3}",
                "{\"type\":\"final\",\"text\":\"done\"}")) {
            File audio = dir.newFile("a.pcm");
            java.nio.file.Files.write(audio.toPath(), new byte[3 * ClipStore.BYTES_PER_SECOND]);
            assertEquals("done", PendingDictations.upload(desktop.uri(), "clip-7", audio));
            JSONObject start = desktop.start();
            assertEquals("start", start.getString("type"));
            assertEquals("clip-7", start.getString("id"));
            assertEquals("upload", start.getString("mode"));
            assertEquals(16000, start.getInt("sample_rate"));
            assertEquals(3 * ClipStore.BYTES_PER_SECOND, desktop.audioBytes());
        }
    }

    @Test public void anUploadToADesktopOnItsCpuSendsNoAudio() throws Exception {
        try (FakeDesktop desktop = new FakeDesktop("{\"type\":\"ready\",\"backend\":\"cpu\"}")) {
            File audio = dir.newFile("a.pcm");
            java.nio.file.Files.write(audio.toPath(), new byte[ClipStore.BYTES_PER_SECOND]);
            try {
                PendingDictations.upload(desktop.uri(), "clip-8", audio);
                fail("degraded");
            } catch (UploadFailure f) {
                assertEquals(UploadFailure.Kind.DEGRADED, f.kind);
                assertFalse("not an attempt", f.attempted);
            }
            assertEquals(0, desktop.audioBytes());
        }
    }

    @Test public void aReadyWithoutABackendGetsNoAudioFromTheCourier() throws Exception {
        // An old server says nothing of its backend: no resume, no upload mode. Not trusted.
        try (FakeDesktop desktop = new FakeDesktop("{\"type\":\"ready\"}")) {
            File audio = dir.newFile("a.pcm");
            java.nio.file.Files.write(audio.toPath(), new byte[ClipStore.BYTES_PER_SECOND / 5]);
            try {
                PendingDictations.upload(desktop.uri(), "clip-old", audio);
                fail("not sent");
            } catch (UploadFailure f) {
                assertEquals(UploadFailure.Kind.DEGRADED, f.kind);
                assertFalse(f.attempted);
            }
            assertEquals("no PCM sent", 0, desktop.audioBytes());
        }
    }

    @Test public void theAttemptIsCountedOnDiskBeforeTheSendAndRefundedIfNothingWasDecoded() {
        ClipStore store = store();
        PendingClip clip = record(store, "pane", 10);
        int[] seenAttempts = {-1};
        PendingDictations crashing = new PendingDictations(store, (c, file) -> {
            // What a fresh process would read now, mid-upload.
            seenAttempts[0] = store().attempts(c.id);
            throw new IllegalStateException("killed mid-upload");
        }, worker, Runnable::run, line -> { }, now::get);
        try {
            crashing.pass();
            fail("crashed");
        } catch (IllegalStateException expected) {
            // the process died
        }
        assertEquals("charged on disk before the send", 1, seenAttempts[0]);
        assertEquals("a restart remembers it", 1, store().attempts(clip.id));
        // A desktop on its CPU never decodes it: the charge comes back.
        ClipStore again = store();
        PendingDictations refusing = new PendingDictations(again, (c, file) -> {
            throw new UploadFailure(UploadFailure.Kind.DEGRADED, false, "backend cpu: not sent");
        }, worker, Runnable::run, line -> { }, now::get);
        refusing.pass();
        assertEquals(1, again.attempts(clip.id));
        assertEquals(1, store().attempts(clip.id));
    }

    @Test public void noSendWhenTheAttemptCannotBeWritten() throws Exception {
        ClipStore store = store();
        record(store, "pane", 10);
        File blocker = new File(new File(dir.getRoot(), "pending"), ClipStore.INDEX + ".tmp");
        assertTrue(blocker.mkdirs());
        assertTrue(new File(blocker, "x").createNewFile());
        int[] sends = {0};
        PendingDictations pending = new PendingDictations(store, (c, file) -> {
            sends[0]++;
            return "text";
        }, worker, Runnable::run, line -> { }, now::get);
        pending.pass();
        assertEquals("an uncounted upload could repeat without end", 0, sends[0]);
        // Room again: it goes.
        new File(blocker, "x").delete();
        assertTrue(blocker.delete());
        worker.advance(PendingDictations.FIRST_RETRY_MS);
        assertEquals(1, sends[0]);
    }

    @Test public void backendSlowAfterTheClipIsADegradedAttempt() throws Exception {
        try (FakeDesktop desktop = new FakeDesktop("{\"type\":\"ready\",\"backend\":\"gpu\"}",
                "{\"type\":\"error\",\"code\":\"backend_slow\",\"message\":\"transcription backend slow\","
                        + "\"kind\":\"final\",\"elapsed_s\":8.0,\"budget_s\":8.0}")) {
            File audio = dir.newFile("a.pcm");
            java.nio.file.Files.write(audio.toPath(), new byte[ClipStore.BYTES_PER_SECOND]);
            try {
                PendingDictations.upload(desktop.uri(), "clip-9", audio);
                fail("slow");
            } catch (UploadFailure f) {
                assertEquals(UploadFailure.Kind.DEGRADED, f.kind);
                assertTrue(f.attempted);
            }
        }
    }

    @Test public void theExactReplayOfTheLongClipEndsInOneFinal() throws Exception {
        // Wolf's 124.6 s clip, a desktop slow twice (backend_slow), then finishing and the final.
        ClipStore store = store();
        PendingClip clip = record(store, "pane", LONG_CLIP_BYTES);
        List<FakeDesktop> desktops = new ArrayList<>();
        List<String> starts = new ArrayList<>();
        String slowError = "{\"type\":\"error\",\"code\":\"backend_slow\",\"message\":\"slow\"}";
        PendingDictations pending = new PendingDictations(store, (c, file) -> {
            int n = desktops.size();
            FakeDesktop d = n < 2
                    ? new FakeDesktop("{\"type\":\"ready\",\"backend\":\"gpu\"}", slowError)
                    : new FakeDesktop("{\"type\":\"ready\",\"backend\":\"gpu\"}",
                            "{\"type\":\"status\",\"state\":\"finishing\",\"tail_s\":4.6}",
                            "{\"type\":\"final\",\"text\":\"the long dictation\"}");
            desktops.add(d);
            try {
                return PendingDictations.upload(d.uri(), c.id, file);
            } finally {
                try {
                    starts.add(d.start().getString("id"));
                } catch (Exception e) {
                    throw new AssertionError(e);
                }
                d.close();
            }
        }, worker, Runnable::run, line -> { }, now::get);
        List<String> placed = new ArrayList<>();
        pending.setListener(new PendingDictations.Listener() {
            @Override public void onTranscribed(PendingClip c, String text) {
                placed.add(text);
                pending.delivered(c.id);
            }

            @Override public void onChanged() { }
        });
        pending.saved();
        for (int s = 0; s < 3 * 60 * 60 && placed.isEmpty(); s++) {
            if (s % 30 == 7) pending.desktopReady(false);
            worker.advance(1000);
        }
        assertEquals("at most three automatic sends", ClipStore.MAX_ATTEMPTS, desktops.size());
        assertEquals("every send under the clip's own id", Collections.nCopies(3, clip.id), starts);
        assertEquals(Collections.singletonList("the long dictation"), placed);
        assertFalse("deleted only after the final", store.audio(clip.id).exists());
        assertEquals(LONG_CLIP_BYTES, desktops.get(2).audioBytes());
    }

    // ---- fakes -------------------------------------------------------------------------------

    /** A desktop that answers one upload with {@code script}, recording what it got. */
    private static final class FakeDesktop implements AutoCloseable {
        private final ServerSocket server = new ServerSocket(0);
        private final String[] script;
        private volatile JSONObject start;
        private volatile int audioBytes;
        private final Thread thread;

        FakeDesktop(String... script) throws IOException {
            this.script = script;
            thread = new Thread(this::serve, "fake-desktop");
            thread.setDaemon(true);
            thread.start();
        }

        URI uri() {
            return URI.create("ws://127.0.0.1:" + server.getLocalPort() + "/v1/dictate");
        }

        JSONObject start() throws InterruptedException {
            thread.join(10_000);
            assertNotNull("a start message", start);
            return start;
        }

        int audioBytes() throws InterruptedException {
            thread.join(10_000);
            return audioBytes;
        }

        private void serve() {
            try (Socket s = server.accept()) {
                InputStream in = s.getInputStream();
                OutputStream out = s.getOutputStream();
                String key = null;
                for (String line = line(in); !line.isEmpty(); line = line(in)) {
                    if (line.startsWith("Sec-WebSocket-Key:")) key = line.substring(18).trim();
                }
                out.write(("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n"
                        + "Sec-WebSocket-Accept: " + WebSocketFrames.acceptFor(key) + "\r\n\r\n")
                        .getBytes(StandardCharsets.US_ASCII));
                WebSocketFrames.Reader reader = new WebSocketFrames.Reader(in, 1 << 20);
                start = new JSONObject(reader.next().text());
                send(out, script[0]);
                if (script.length == 1) return; // ready only: the phone hangs up
                while (true) {
                    WebSocketFrames.Message m;
                    try {
                        m = reader.next();
                    } catch (IOException closed) {
                        return;
                    }
                    if (m.opcode == WebSocketFrames.BINARY) {
                        audioBytes += m.payload.length;
                    } else if (m.opcode == WebSocketFrames.TEXT
                            && "stop".equals(new JSONObject(m.text()).getString("type"))) {
                        for (int i = 1; i < script.length; i++) send(out, script[i]);
                        return;
                    }
                }
            } catch (Exception gone) {
                // The phone hung up.
            }
        }

        private static String line(InputStream in) throws IOException {
            StringBuilder b = new StringBuilder();
            for (int c = in.read(); c != '\n'; c = in.read()) {
                if (c < 0) throw new IOException("closed");
                if (c != '\r') b.append((char) c);
            }
            return b.toString();
        }

        private static void send(OutputStream out, String text) throws IOException {
            byte[] b = text.getBytes(StandardCharsets.UTF_8);
            ByteArrayOutputStream f = new ByteArrayOutputStream();
            f.write(0x81);
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
            server.close();
        }
    }

    /** A scheduler on a fake clock: work runs inline, delayed work when {@link #advance} passes it. */
    static final class Scheduler extends AbstractExecutorService implements ScheduledExecutorService {
        private final AtomicLong clock;
        private final List<Task> tasks = new ArrayList<>();

        Scheduler(AtomicLong clock) {
            this.clock = clock;
        }

        /** Moves time on by {@code ms}, running what falls due, in order. */
        void advance(long ms) {
            long until = clock.get() + ms;
            while (true) {
                Task next = null;
                for (Task t : tasks) if (!t.cancelled && t.due <= until && (next == null || t.due < next.due)) next = t;
                if (next == null) break;
                tasks.remove(next);
                clock.set(Math.max(clock.get(), next.due));
                next.done = true;
                next.run.run();
            }
            clock.set(until);
            tasks.removeIf(t -> t.cancelled);
        }

        private final class Task implements ScheduledFuture<Object> {
            final long due;
            final Runnable run;
            boolean cancelled, done;

            Task(long due, Runnable run) {
                this.due = due;
                this.run = run;
            }

            @Override public long getDelay(TimeUnit unit) {
                return unit.convert(due - clock.get(), TimeUnit.MILLISECONDS);
            }

            @Override public int compareTo(Delayed o) {
                return Long.compare(getDelay(TimeUnit.MILLISECONDS), o.getDelay(TimeUnit.MILLISECONDS));
            }

            @Override public boolean cancel(boolean interrupt) {
                if (done) return false;
                cancelled = true;
                return true;
            }

            @Override public boolean isCancelled() { return cancelled; }

            @Override public boolean isDone() { return done || cancelled; }

            @Override public Object get() { return null; }

            @Override public Object get(long timeout, TimeUnit unit) { return null; }
        }

        @Override public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
            Task t = new Task(clock.get() + unit.toMillis(delay), command);
            tasks.add(t);
            return t;
        }

        @Override public <V> ScheduledFuture<V> schedule(Callable<V> callable, long delay, TimeUnit unit) {
            throw new UnsupportedOperationException();
        }

        @Override public ScheduledFuture<?> scheduleAtFixedRate(Runnable command, long initialDelay, long period,
                TimeUnit unit) {
            throw new UnsupportedOperationException();
        }

        @Override public ScheduledFuture<?> scheduleWithFixedDelay(Runnable command, long initialDelay, long delay,
                TimeUnit unit) {
            throw new UnsupportedOperationException();
        }

        @Override public void execute(Runnable command) {
            command.run();
        }

        @Override public void shutdown() { }

        @Override public List<Runnable> shutdownNow() { return Collections.emptyList(); }

        @Override public boolean isShutdown() { return false; }

        @Override public boolean isTerminated() { return false; }

        @Override public boolean awaitTermination(long timeout, TimeUnit unit) { return true; }

        @SuppressWarnings("unused")
        private static void unused(Collection<?> c) { }
    }
}
