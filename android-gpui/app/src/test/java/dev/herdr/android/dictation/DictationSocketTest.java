package dev.herdr.android.dictation;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import org.junit.After;
import org.junit.Test;

/** Why a websocket did not open, as classes for the log, and a handshake slower than the connect. */
public final class DictationSocketTest {
    /** What a fake server does with a connection. */
    interface Answer {
        void answer(Socket s) throws Exception;
    }

    private ServerSocket server;

    @After public void close() throws IOException {
        if (server != null) server.close();
    }

    private URI serve(Answer answer) throws IOException {
        server = new ServerSocket(0);
        Thread t = new Thread(() -> {
            try (Socket s = server.accept()) {
                answer.answer(s);
            } catch (Exception ignored) {
                // The test is over or the phone hung up.
            }
        });
        t.setDaemon(true);
        t.start();
        return URI.create("ws://127.0.0.1:" + server.getLocalPort() + "/v1/dictate");
    }

    private static String key(InputStream in) throws IOException {
        String key = null;
        StringBuilder line = new StringBuilder();
        for (int c; (c = in.read()) >= 0; ) {
            if (c == '\r') continue;
            if (c != '\n') {
                line.append((char) c);
                continue;
            }
            if (line.length() == 0) return key;
            if (line.toString().startsWith("Sec-WebSocket-Key:")) key = line.substring(18).trim();
            line.setLength(0);
        }
        throw new IOException("closed");
    }

    private static DictationSocket.HandshakeException failure(URI uri, int connectMs, int handshakeMs) {
        try {
            DictationSocket.connect(uri, connectMs, handshakeMs).close();
        } catch (DictationSocket.HandshakeException e) {
            return e;
        } catch (IOException e) {
            throw new AssertionError("not classified", e);
        }
        fail("connected");
        return null;
    }

    @Test public void aServerThatNeverAnswersTheUpgradeTimesOutOnTheHandshakeTimeout() throws Exception {
        URI uri = serve(s -> Thread.sleep(5000));
        DictationSocket.HandshakeException e = failure(uri, 2000, 300);
        assertEquals(DictationSocket.HandshakeException.Step.UPGRADE, e.step);
        assertEquals(DictationSocket.HandshakeException.Kind.TIMEOUT, e.kind);
        assertTrue(e.elapsedMs + " ms", e.elapsedMs >= 250 && e.elapsedMs < 2000);
    }

    @Test public void aRefusedUpgradeGivesItsStatusOnly() throws Exception {
        URI uri = serve(s -> {
            key(s.getInputStream());
            s.getOutputStream().write("HTTP/1.1 403 Private reason\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
            Thread.sleep(500);
        });
        DictationSocket.HandshakeException e = failure(uri, 2000, 2000);
        assertEquals(DictationSocket.HandshakeException.Kind.STATUS, e.kind);
        assertEquals(403, e.status);
        assertTrue(e.getMessage(), !e.getMessage().contains("Private"));
    }

    @Test public void aServerClosingDuringTheUpgradeIsClosed() throws Exception {
        URI uri = serve(s -> key(s.getInputStream()));
        DictationSocket.HandshakeException e = failure(uri, 2000, 2000);
        assertEquals(DictationSocket.HandshakeException.Step.UPGRADE, e.step);
        assertTrue(e.kind.toString(), e.kind == DictationSocket.HandshakeException.Kind.CLOSED
                || e.kind == DictationSocket.HandshakeException.Kind.RESET);
    }

    @Test public void aClosedPortFailsToConnect() throws Exception {
        int port;
        try (ServerSocket s = new ServerSocket(0)) {
            port = s.getLocalPort();
        }
        DictationSocket.HandshakeException e = failure(URI.create("ws://127.0.0.1:" + port + "/"), 2000, 2000);
        assertEquals(DictationSocket.HandshakeException.Step.CONNECT, e.step);
        assertEquals(DictationSocket.HandshakeException.Kind.RESET, e.kind);
    }

    @Test public void anUpgradeSlowerThanTheConnectTimeoutStillOpens() throws Exception {
        URI uri = serve(s -> {
            String key = key(s.getInputStream());
            Thread.sleep(700); // a relayed path's round trips
            OutputStream out = s.getOutputStream();
            out.write(("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n"
                    + "Sec-WebSocket-Accept: " + WebSocketFrames.acceptFor(key) + "\r\n\r\n")
                    .getBytes(StandardCharsets.US_ASCII));
            out.flush();
            Thread.sleep(500);
        });
        DictationSocket.connect(uri, 300, 3000).close();
    }

    @Test public void theStatusCodeIsParsed() {
        assertEquals(101, DictationSocket.statusCode("HTTP/1.1 101 Switching Protocols"));
        assertEquals(502, DictationSocket.statusCode("HTTP/1.0 502"));
        assertEquals(0, DictationSocket.statusCode("garbage"));
    }
}
