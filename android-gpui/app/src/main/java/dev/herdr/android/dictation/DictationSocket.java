package dev.herdr.android.dictation;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Locale;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/**
 * A blocking websocket client for one dictation session. A raw socket rather than an HTTP
 * library: ws:// over the tailnet (already WireGuard-encrypted) needs no cleartext-traffic
 * exception, and wss:// uses the platform's trusted certificates. Writes are synchronized so
 * the recorder thread and the session thread can both send.
 */
final class DictationSocket implements Closeable {
    static final int MAX_MESSAGE = 1 << 20;

    private final Socket socket;
    private final WebSocketFrames.Reader in;
    private final OutputStream out;
    private final SecureRandom random = new SecureRandom();

    private DictationSocket(Socket socket, InputStream in, OutputStream out) {
        this.socket = socket;
        this.in = new WebSocketFrames.Reader(in, MAX_MESSAGE);
        this.out = out;
    }

    static DictationSocket connect(URI uri, int timeoutMillis) throws IOException {
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        boolean tls = scheme.equals("wss");
        if (!tls && !scheme.equals("ws")) throw new IOException("not a ws:// or wss:// URL: " + uri);
        String host = uri.getHost();
        if (host == null) throw new IOException("no host in " + uri);
        int port = uri.getPort() > 0 ? uri.getPort() : tls ? 443 : 80;
        Socket raw = new Socket();
        try {
            raw.connect(new InetSocketAddress(host, port), timeoutMillis);
            raw.setTcpNoDelay(true);
            raw.setSoTimeout(timeoutMillis);
            Socket socket = raw;
            if (tls) {
                SSLSocket ssl = (SSLSocket) ((SSLSocketFactory) SSLSocketFactory.getDefault())
                        .createSocket(raw, host, port, true);
                ssl.startHandshake();
                if (!javax.net.ssl.HttpsURLConnection.getDefaultHostnameVerifier()
                        .verify(host, ssl.getSession())) {
                    throw new IOException("certificate does not match " + host);
                }
                socket = ssl;
            }
            InputStream in = new BufferedInputStream(socket.getInputStream());
            OutputStream out = socket.getOutputStream();
            handshake(uri, host, port, tls, in, out);
            // Reads after the handshake block until the server speaks; a session can be silent.
            socket.setSoTimeout(0);
            return new DictationSocket(socket, in, out);
        } catch (IOException e) {
            raw.close();
            throw e;
        }
    }

    private static void handshake(URI uri, String host, int port, boolean tls, InputStream in,
            OutputStream out) throws IOException {
        byte[] nonce = new byte[16];
        new SecureRandom().nextBytes(nonce);
        String key = Base64.getEncoder().encodeToString(nonce);
        String path = uri.getRawPath() == null || uri.getRawPath().isEmpty() ? "/" : uri.getRawPath();
        if (uri.getRawQuery() != null) path += "?" + uri.getRawQuery();
        boolean defaultPort = port == (tls ? 443 : 80);
        String request = "GET " + path + " HTTP/1.1\r\n"
                + "Host: " + host + (defaultPort ? "" : ":" + port) + "\r\n"
                + "Upgrade: websocket\r\n"
                + "Connection: Upgrade\r\n"
                + "Sec-WebSocket-Key: " + key + "\r\n"
                + "Sec-WebSocket-Version: 13\r\n\r\n";
        out.write(request.getBytes(StandardCharsets.US_ASCII));
        out.flush();
        String status = readLine(in);
        if (!status.startsWith("HTTP/1.1 101")) throw new IOException("websocket refused: " + status);
        String accept = null;
        for (String line = readLine(in); !line.isEmpty(); line = readLine(in)) {
            int colon = line.indexOf(':');
            if (colon > 0 && line.substring(0, colon).trim().equalsIgnoreCase("Sec-WebSocket-Accept")) {
                accept = line.substring(colon + 1).trim();
            }
        }
        if (!WebSocketFrames.acceptFor(key).equals(accept)) {
            throw new IOException("websocket handshake did not echo the key");
        }
    }

    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        while (true) {
            int b = in.read();
            if (b < 0) throw new IOException("connection closed during handshake");
            if (b == '\n') break;
            if (b != '\r') line.write(b);
            if (line.size() > 8192) throw new IOException("handshake line too long");
        }
        return new String(line.toByteArray(), StandardCharsets.ISO_8859_1);
    }

    void sendText(String text) throws IOException {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        send(WebSocketFrames.TEXT, bytes, 0, bytes.length);
    }

    void sendBinary(byte[] bytes, int offset, int length) throws IOException {
        send(WebSocketFrames.BINARY, bytes, offset, length);
    }

    private synchronized void send(int opcode, byte[] bytes, int offset, int length)
            throws IOException {
        out.write(WebSocketFrames.encode(opcode, bytes, offset, length, random));
        out.flush();
    }

    /** The next text or binary message, answering pings; null once the server closes. */
    WebSocketFrames.Message read() throws IOException {
        while (true) {
            WebSocketFrames.Message m = in.next();
            if (m.opcode == WebSocketFrames.PING) {
                send(WebSocketFrames.PONG, m.payload, 0, m.payload.length);
            } else if (m.opcode == WebSocketFrames.CLOSE) {
                return null;
            } else if (m.opcode != WebSocketFrames.PONG) {
                return m;
            }
        }
    }

    /** Closes without the closing handshake; unblocks a pending {@link #read}. */
    @Override
    public void close() {
        try {
            socket.close();
        } catch (IOException ignored) {
            // Already closed or reset: nothing left to release.
        }
    }
}
