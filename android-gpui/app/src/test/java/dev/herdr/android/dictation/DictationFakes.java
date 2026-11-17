package dev.herdr.android.dictation;

import static org.junit.Assert.assertEquals;

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
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.json.JSONObject;

/**
 * Test doubles for dictation: a fake desktop dictation server speaking server.py's protocol
 * with its agreed JSON, a scripted microphone, a scripted on-device recognizer, the main thread
 * on an executor, and a {@link Rig} wiring a real {@link ServerDictationEngine} to them, for
 * tests in other packages (the composer's).
 */
public final class DictationFakes {
    private DictationFakes() { }

    // The desktop's messages, exactly as server.py sends them.
    public static final String READY_GPU = "{\"type\":\"ready\",\"backend\":\"gpu\"}";
    public static final String READY_CPU = "{\"type\":\"ready\",\"backend\":\"cpu\"}";
    public static final String READY_DOWN = "{\"type\":\"ready\",\"backend\":\"down\"}";
    public static final String READY_OLD = "{\"type\":\"ready\"}";
    public static final String ERROR_SLOW = "{\"type\":\"error\",\"code\":\"backend_slow\","
            + "\"message\":\"transcription backend slow\",\"kind\":\"partial\",\"elapsed_s\":3.0,\"budget_s\":3.0}";
    public static final String ERROR_UNAVAILABLE = "{\"type\":\"error\",\"code\":\"backend_unavailable\","
            + "\"message\":\"transcription backend unavailable\"}";
    public static final String ERROR_BAD_START = "{\"type\":\"error\",\"code\":\"bad_start\","
            + "\"message\":\"expected start at 16000 Hz\"}";
    public static final String ERROR_TOO_LONG = "{\"type\":\"error\",\"code\":\"session_too_long\","
            + "\"message\":\"session too long\"}";

    public static final String DESKTOP_WORDS = "words from the desktop";
    public static final String PHONE_WORDS = "words from the phone";


    /** The main thread's Handler, on a single-thread executor. */
    public static final class TestMain implements ServerDictationEngine.Main {
        private final ScheduledExecutorService exec;
        private final Map<Runnable, List<ScheduledFuture<?>>> delayed = new ConcurrentHashMap<>();
        /** Every delay the engine asked for, in order: its timeouts, checked without a clock. */
        public final List<Long> delays = Collections.synchronizedList(new ArrayList<>());

        TestMain(ScheduledExecutorService exec) {
            this.exec = exec;
        }

        @Override public void post(Runnable r) {
            exec.execute(r);
        }

        @Override public void postDelayed(Runnable r, long delayMs) {
            delays.add(delayMs);
            delayed.computeIfAbsent(r, k -> Collections.synchronizedList(new ArrayList<>()))
                    .add(exec.schedule(r, delayMs, TimeUnit.MILLISECONDS));
        }

        @Override public void remove(Runnable r) {
            List<ScheduledFuture<?>> futures = delayed.remove(r);
            if (futures != null) for (ScheduledFuture<?> f : futures) f.cancel(false);
        }
    }

    /** A microphone giving a distinct 100 ms chunk every 20 ms until stopped. */
    public static final class ScriptedMic implements AudioInput.Opener, AudioInput {
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

        public byte[] bytes() {
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
    public static final class FakeRecognizer implements OnDeviceRecognizer {
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

        public FakeRecognizer(String text, long finalDelayMs) {
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

    /** What the fake desktop does in a session. */
    public static final class Behaviour {
        String ready;
        long finalDelayMs;
        String errorOnAudio;
        boolean dropOnAudio;

        public static Behaviour fast(String ready) {
            Behaviour b = new Behaviour();
            b.ready = ready;
            return b;
        }

        public static Behaviour silent() {
            return new Behaviour();
        }

        public Behaviour finalAfter(long ms) {
            finalDelayMs = ms;
            return this;
        }

        java.util.concurrent.CountDownLatch finalGate;
        long finalGateSafetyMs;

        /**
         * The final waits for {@code gate}, not for a time: the test opens it once it has seen what
         * must come first (e.g. the phone's text), so load cannot reorder the two. After
         * {@code safetyMs} unopened, the final goes anyway and {@link FakeServer#gateTimedOut} says so.
         */
        public Behaviour finalWhen(java.util.concurrent.CountDownLatch gate, long safetyMs) {
            finalGate = gate;
            finalGateSafetyMs = safetyMs;
            return this;
        }

        public Behaviour errorOnAudio(String error) {
            errorOnAudio = error;
            return this;
        }

        public Behaviour dropOnAudio() {
            dropOnAudio = true;
            return this;
        }

        /** The desktop's answer to stop, instead of its words. */
        String finalJson;

        public Behaviour finalJson(String json) {
            finalJson = json;
            return this;
        }

        boolean finishing;

        /** After stop: {"type":"status","state":"finishing"} before the final. */
        public Behaviour finishing() {
            finishing = true;
            return this;
        }
    }

    /** The desktop's dictation server, speaking server.py's protocol on a local port. */
    public static final class FakeServer implements java.io.Closeable {
        private final ServerSocket socket = new ServerSocket(0);
        private final Behaviour behaviour;
        private final ByteArrayOutputStream audio = new ByteArrayOutputStream();
        private final List<Socket> open = Collections.synchronizedList(new ArrayList<>());
        /** Every session's start message, in order. */
        public final List<JSONObject> starts = Collections.synchronizedList(new ArrayList<>());
        /** Every other text message the phone sent (stop, cancel), in order. */
        public final List<String> texts = Collections.synchronizedList(new ArrayList<>());

        /** A {@link Behaviour#finalWhen} gate was never opened: the final went on its safety timeout. */
        public volatile boolean gateTimedOut;
        private final java.util.concurrent.CountDownLatch finalDone = new java.util.concurrent.CountDownLatch(1);

        /** Waits until the final was sent (or failed: the phone had hung up) and the session closed. */
        public boolean awaitFinalDone(long timeoutMs) throws InterruptedException {
            return finalDone.await(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS);
        }

        public FakeServer(Behaviour behaviour) throws IOException {
            this.behaviour = behaviour;
            Thread t = new Thread(this::serve, "fake-dictation-server");
            t.setDaemon(true);
            t.start();
        }

        public URI uri() {
            return URI.create("ws://127.0.0.1:" + socket.getLocalPort() + "/v1/dictate");
        }

        public byte[] audio() {
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
                assertEquals("start", new JSONObject(start.text()).getString("type"));
                starts.add(new JSONObject(start.text()));
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
                        if (behaviour.finishing) {
                            send(out, "{\"type\":\"status\",\"state\":\"finishing\",\"tail_s\":12.3}");
                        }
                        Thread.sleep(behaviour.finalDelayMs);
                        if (behaviour.finalGate != null && !behaviour.finalGate.await(
                                behaviour.finalGateSafetyMs, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                            gateTimedOut = true;
                        }
                        try {
                            send(out, behaviour.finalJson != null ? behaviour.finalJson
                                    : "{\"type\":\"final\",\"text\":\"" + DESKTOP_WORDS + "\"}");
                        } finally {
                            s.close();
                            finalDone.countDown();
                        }
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

    /** Short waits so suites are quick; the shape of each wait is what is tested. */
    static final ServerDictationEngine.Timeouts TIMEOUTS =
            new ServerDictationEngine.Timeouts(800, 500, 2500, 1500);

    /**
     * A real {@link ServerDictationEngine} on its own main thread, with the scripted microphone,
     * an optional fake desktop ({@code behaviour}, null = unreachable) and an optional phone
     * recognizer ({@code phone}, null = none).
     */
    public static final class Rig implements java.io.Closeable {
        public final ScriptedMic mic = new ScriptedMic();
        public final FakeRecognizer phone;
        public final FakeServer server;
        public final DictationEngine engine;
        public final List<String> log = Collections.synchronizedList(new ArrayList<>());
        private final ScheduledExecutorService main = Executors.newSingleThreadScheduledExecutor();
        private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor();
        final ClipStore store;

        public Rig(File dir, Behaviour behaviour, FakeRecognizer phone) throws IOException {
            this.phone = phone;
            server = behaviour == null ? null : new FakeServer(behaviour);
            URI uri = server == null ? closedPort() : server.uri();
            store = new ClipStore(new File(dir, "pending"));
            PendingDictations pending = new PendingDictations(store, (clip, audio) -> {
                throw new IOException("the courier is not under test");
            }, worker, main::execute);
            if (phone != null) phone.mic = mic;
            engine = new ServerDictationEngine(uri, pending, new TestMain(main), mic, () -> true,
                    phone == null ? null : phone::start, TIMEOUTS, log::add);
        }

        /** Recordings saved for the desktop (waiting, not transcribed). */
        public int waitingClips() {
            return store.untranscribed().size();
        }

        /** Runs {@code work} on the engine's main thread and waits for it. */
        public <T> T onMain(Callable<T> work) throws Exception {
            return main.submit(work).get(10, TimeUnit.SECONDS);
        }

        public void onMain(Runnable work) throws Exception {
            main.submit(work).get(10, TimeUnit.SECONDS);
        }

        /** Waits until everything already posted to the main thread has run, {@code ms} from now. */
        public void settle(long ms) throws Exception {
            Thread.sleep(ms);
            onMain(() -> { });
        }

        @Override public void close() throws IOException {
            if (server != null) server.close();
            main.shutdownNow();
            worker.shutdownNow();
        }
    }

    static URI closedPort() throws IOException {
        int port;
        try (ServerSocket s = new ServerSocket(0)) {
            port = s.getLocalPort();
        }
        return URI.create("ws://127.0.0.1:" + port + "/v1/dictate");
    }
}
