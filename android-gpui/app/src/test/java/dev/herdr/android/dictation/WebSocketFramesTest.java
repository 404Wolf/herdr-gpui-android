package dev.herdr.android.dictation;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Random;
import org.junit.Test;

public final class WebSocketFramesTest {
    @Test public void acceptKeyMatchesTheRfcExample() {
        assertEquals("s3pPLMBiTxaQ9kYGzzhZRbK+xOo=",
                WebSocketFrames.acceptFor("dGhlIHNhbXBsZSBub25jZQ=="));
    }

    @Test public void clientFramesAreMaskedAndRoundTripAtEveryLengthEncoding() throws IOException {
        for (int length : new int[] {0, 1, 125, 126, 65535, 65536, 200_000}) {
            byte[] payload = new byte[length];
            new Random(length).nextBytes(payload);
            byte[] frame = WebSocketFrames.encode(WebSocketFrames.BINARY, payload, 0, length, new Random(7));
            assertEquals(0x80 | WebSocketFrames.BINARY, frame[0] & 0xFF);
            assertTrue("mask bit", (frame[1] & 0x80) != 0);
            WebSocketFrames.Message m = WebSocketFrames.read(new ByteArrayInputStream(frame), 1 << 20);
            assertEquals(WebSocketFrames.BINARY, m.opcode);
            assertArrayEquals(payload, m.payload);
        }
    }

    @Test public void encodesOnlyTheRequestedSlice() throws IOException {
        byte[] buf = "xxhelloxx".getBytes(StandardCharsets.UTF_8);
        byte[] frame = WebSocketFrames.encode(WebSocketFrames.TEXT, buf, 2, 5, new Random(1));
        assertEquals("hello", WebSocketFrames.read(new ByteArrayInputStream(frame), 64).text());
    }

    @Test public void joinsFragmentsAndPassesControlFramesBetweenThem() throws IOException {
        ByteArrayOutputStream wire = new ByteArrayOutputStream();
        serverFrame(wire, false, WebSocketFrames.TEXT, "par");
        serverFrame(wire, true, WebSocketFrames.PING, "p");
        serverFrame(wire, true, 0x0, "tial");
        WebSocketFrames.Reader in =
                new WebSocketFrames.Reader(new ByteArrayInputStream(wire.toByteArray()), 64);
        assertEquals(WebSocketFrames.PING, in.next().opcode);
        WebSocketFrames.Message text = in.next();
        assertEquals(WebSocketFrames.TEXT, text.opcode);
        assertEquals("partial", text.text());
    }

    @Test public void rejectsOversizedFramesAndReservedBits() {
        ByteArrayOutputStream wire = new ByteArrayOutputStream();
        serverFrame(wire, true, WebSocketFrames.TEXT, "too long for the limit");
        expectIOException(wire.toByteArray(), 4);
        expectIOException(new byte[] {(byte) 0xC1, 0}, 64); // RSV1 set
        expectIOException(new byte[] {(byte) 0x09, 0}, 64); // fragmented ping
        expectIOException(new byte[] {(byte) 0x80, 0}, 64); // continuation without a start
    }

    @Test public void truncatedFrameIsEof() {
        try {
            WebSocketFrames.read(new ByteArrayInputStream(new byte[] {(byte) 0x81, 5, 'a'}), 64);
            fail();
        } catch (EOFException expected) {
            // A connection dropped mid-frame.
        } catch (IOException e) {
            fail(e.toString());
        }
    }

    @Test public void connectsAnswersPingsAndReadsUntilClose() throws Exception {
        try (ServerSocket server = new ServerSocket(0)) {
            Thread peer = new Thread(() -> {
                try (Socket s = server.accept()) {
                    BufferedReader r = new BufferedReader(
                            new InputStreamReader(s.getInputStream(), StandardCharsets.ISO_8859_1));
                    String key = null;
                    String path = r.readLine();
                    for (String line = r.readLine(); !line.isEmpty(); line = r.readLine()) {
                        if (line.startsWith("Sec-WebSocket-Key:")) key = line.substring(18).trim();
                    }
                    OutputStream out = s.getOutputStream();
                    out.write(("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\n"
                            + "Connection: Upgrade\r\nSec-WebSocket-Accept: "
                            + WebSocketFrames.acceptFor(key) + "\r\n\r\n")
                            .getBytes(StandardCharsets.US_ASCII));
                    ByteArrayOutputStream frames = new ByteArrayOutputStream();
                    serverFrame(frames, true, WebSocketFrames.PING, "hi");
                    serverFrame(frames, true, WebSocketFrames.TEXT, path);
                    serverFrame(frames, true, WebSocketFrames.CLOSE, "");
                    out.write(frames.toByteArray());
                    out.flush();
                    // The client's pong arrives masked.
                    WebSocketFrames.Message pong = WebSocketFrames.read(s.getInputStream(), 64);
                    assertEquals(WebSocketFrames.PONG, pong.opcode);
                    assertEquals("hi", pong.text());
                } catch (IOException e) {
                    throw new AssertionError(e);
                }
            });
            peer.start();
            URI uri = new URI("ws://127.0.0.1:" + server.getLocalPort() + "/v1/dictate");
            try (DictationSocket socket = DictationSocket.connect(uri, 2000)) {
                assertEquals("GET /v1/dictate HTTP/1.1", socket.read().text());
                assertNull(socket.read());
            }
            peer.join(2000);
        }
    }

    @Test public void refusesAServerThatDoesNotEchoTheKey() throws Exception {
        try (ServerSocket server = new ServerSocket(0)) {
            Thread peer = new Thread(() -> {
                try (Socket s = server.accept()) {
                    BufferedReader r = new BufferedReader(
                            new InputStreamReader(s.getInputStream(), StandardCharsets.ISO_8859_1));
                    while (!r.readLine().isEmpty()) {
                        // Skip the request.
                    }
                    s.getOutputStream().write(("HTTP/1.1 101 Switching Protocols\r\n"
                            + "Sec-WebSocket-Accept: wrong\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                } catch (IOException ignored) {
                    // The client hangs up.
                }
            });
            peer.start();
            try {
                DictationSocket.connect(new URI("ws://127.0.0.1:" + server.getLocalPort() + "/"), 2000);
                fail();
            } catch (IOException expected) {
                assertTrue(expected.getMessage().contains("echo"));
            }
            peer.join(2000);
        }
    }

    @Test public void onlyWebsocketUrlsWithAHostAreAccepted() {
        assertEquals("ws://100.1.2.3:8770/v1/dictate",
                ServerDictationEngine.parseUrl("ws://100.1.2.3:8770/v1/dictate").toString());
        assertEquals("wss", ServerDictationEngine.parseUrl(" wss://desktop.example.ts.net/x ").getScheme());
        assertNull(ServerDictationEngine.parseUrl(null));
        assertNull(ServerDictationEngine.parseUrl(""));
        assertNull(ServerDictationEngine.parseUrl("http://host/"));
        assertNull(ServerDictationEngine.parseUrl("ws:///nohost"));
        assertNull(ServerDictationEngine.parseUrl("not a url"));
    }

    @Test public void audioBufferedWhileConnectingKeepsTheNewestTenSeconds() {
        java.util.ArrayDeque<byte[]> pending = new java.util.ArrayDeque<>();
        for (int i = 0; i < ServerDictationEngine.MAX_PENDING_CHUNKS + 5; i++) {
            ServerDictationEngine.enqueue(pending, new byte[] {(byte) i});
        }
        assertEquals(ServerDictationEngine.MAX_PENDING_CHUNKS, pending.size());
        assertEquals(5, pending.peekFirst()[0]);
    }

    private static void expectIOException(byte[] wire, int max) {
        try {
            WebSocketFrames.read(new ByteArrayInputStream(wire), max);
            fail("accepted a bad frame");
        } catch (IOException expected) {
            // Rejected.
        }
    }

    /** An unmasked server frame. */
    private static void serverFrame(ByteArrayOutputStream out, boolean fin, int opcode, String text) {
        byte[] p = text.getBytes(StandardCharsets.UTF_8);
        out.write((fin ? 0x80 : 0) | opcode);
        out.write(p.length);
        out.write(p, 0, p.length);
    }
}
