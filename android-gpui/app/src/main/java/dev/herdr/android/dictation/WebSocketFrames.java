package dev.herdr.android.dictation;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.Random;

/**
 * The client half of RFC 6455 framing: masked frames out, unmasked frames in. Only what the
 * dictation protocol needs (text, binary, close, ping/pong, continuation); no extensions.
 */
final class WebSocketFrames {
    static final int TEXT = 0x1;
    static final int BINARY = 0x2;
    static final int CLOSE = 0x8;
    static final int PING = 0x9;
    static final int PONG = 0xA;
    private static final int CONTINUATION = 0x0;
    private static final String GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";

    private WebSocketFrames() {}

    /** One complete message read from the server. */
    static final class Message {
        final int opcode;
        final byte[] payload;

        Message(int opcode, byte[] payload) {
            this.opcode = opcode;
            this.payload = payload;
        }

        String text() {
            return new String(payload, StandardCharsets.UTF_8);
        }
    }

    /** A final, masked client frame. */
    static byte[] encode(int opcode, byte[] payload, int offset, int length, Random random) {
        int header = length < 126 ? 2 : length <= 0xFFFF ? 4 : 10;
        byte[] out = new byte[header + 4 + length];
        out[0] = (byte) (0x80 | opcode);
        if (length < 126) {
            out[1] = (byte) (0x80 | length);
        } else if (length <= 0xFFFF) {
            out[1] = (byte) (0x80 | 126);
            out[2] = (byte) (length >>> 8);
            out[3] = (byte) length;
        } else {
            out[1] = (byte) (0x80 | 127);
            for (int i = 0; i < 8; i++) out[2 + i] = (byte) ((long) length >>> (56 - 8 * i));
        }
        byte[] mask = new byte[4];
        random.nextBytes(mask);
        System.arraycopy(mask, 0, out, header, 4);
        for (int i = 0; i < length; i++) {
            out[header + 4 + i] = (byte) (payload[offset + i] ^ mask[i & 3]);
        }
        return out;
    }

    /**
     * Reads whole messages, joining continuation frames. Control frames are returned as they
     * arrive, even between the fragments of a message, which then continues on the next call.
     */
    static final class Reader {
        private final InputStream in;
        private final int maxBytes;
        private ByteArrayOutputStream fragments;
        private int fragmentedOpcode;

        Reader(InputStream in, int maxBytes) {
            this.in = in;
            this.maxBytes = maxBytes;
        }

        Message next() throws IOException {
            while (true) {
                int b0 = readByte(in);
                int b1 = readByte(in);
                boolean fin = (b0 & 0x80) != 0;
                int opcode = b0 & 0x0F;
                if ((b0 & 0x70) != 0) throw new IOException("reserved websocket bits set");
                long length = b1 & 0x7F;
                if (length == 126) {
                    length = (readByte(in) << 8) | readByte(in);
                } else if (length == 127) {
                    length = 0;
                    for (int i = 0; i < 8; i++) length = (length << 8) | readByte(in);
                }
                if (length < 0 || length > maxBytes) throw new IOException("websocket frame too large");
                byte[] mask = null;
                if ((b1 & 0x80) != 0) {
                    mask = new byte[4];
                    readFully(in, mask);
                }
                byte[] payload = new byte[(int) length];
                readFully(in, payload);
                if (mask != null) {
                    for (int i = 0; i < payload.length; i++) payload[i] ^= mask[i & 3];
                }
                if (opcode >= CLOSE) {
                    if (!fin || length > 125) throw new IOException("invalid websocket control frame");
                    return new Message(opcode, payload);
                }
                if (opcode != CONTINUATION) {
                    if (fragments != null) throw new IOException("websocket message interleaved");
                    if (fin) return new Message(opcode, payload);
                    fragments = new ByteArrayOutputStream();
                    fragmentedOpcode = opcode;
                } else if (fragments == null) {
                    throw new IOException("websocket continuation without a start");
                }
                if (fragments.size() + payload.length > maxBytes) {
                    throw new IOException("websocket message too large");
                }
                fragments.write(payload, 0, payload.length);
                if (fin) {
                    Message whole = new Message(fragmentedOpcode, fragments.toByteArray());
                    fragments = null;
                    return whole;
                }
            }
        }
    }

    /** One message from a fresh reader; for single-message inputs. */
    static Message read(InputStream in, int maxBytes) throws IOException {
        return new Reader(in, maxBytes).next();
    }

    /** The Sec-WebSocket-Accept value a server must answer {@code key} with. */
    static String acceptFor(String key) {
        try {
            MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
            byte[] digest = sha1.digest((key + GUID).getBytes(StandardCharsets.US_ASCII));
            return Base64.getEncoder().encodeToString(digest);
        } catch (NoSuchAlgorithmException e) {
            // Every Java and Android runtime is required to provide SHA-1.
            throw new IllegalStateException(e);
        }
    }

    private static int readByte(InputStream in) throws IOException {
        int b = in.read();
        if (b < 0) throw new EOFException("websocket closed");
        return b;
    }

    private static void readFully(InputStream in, byte[] buf) throws IOException {
        int read = 0;
        while (read < buf.length) {
            int n = in.read(buf, read, buf.length - read);
            if (n < 0) throw new EOFException("websocket closed");
            read += n;
        }
    }
}
