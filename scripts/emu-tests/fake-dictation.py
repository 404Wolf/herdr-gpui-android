#!/usr/bin/env python3
"""Usage: fake-dictation.py PORT [--final=TEXT] [PARTIAL_WORDS...]  (TEXT is sent as the final on stop;
without it the last partial is the final)
A fake dictation websocket for screenshots: 'ready' mode streams partials; 'stall' never says ready;
'cut' is 'ready' with an unverified cut ("unverified_cuts", server.py) inside "bi|gger"."""
import base64, hashlib, json, os, socket, struct, sys, threading, time
MODE = os.path.join(os.path.dirname(os.path.abspath(__file__)), "dict-mode")
PORT = int(sys.argv[1]) if len(sys.argv) > 1 else 8770
ARGS = sys.argv[2:]
FINAL = next((a[len("--final="):] for a in ARGS if a.startswith("--final=")), None)
PARTIALS = [a for a in ARGS if not a.startswith("--final=")] or ["make the stop", "make the stop button bigger", "make the stop button bigger and keep it", "make the stop button bigger and keep it reachable while I scroll"]

def log(*parts):
    print(time.strftime("%H:%M:%S"), *parts, flush=True)


def send(conn, text):
    data = text.encode()
    header = bytes([0x81]) + (bytes([len(data)]) if len(data) < 126 else bytes([126]) + struct.pack(">H", len(data)))
    conn.sendall(header + data)

def frames(conn):
    while True:
        h = conn.recv(2, socket.MSG_WAITALL)
        if len(h) < 2: return
        op, n = h[0] & 0x0F, h[1] & 0x7F
        if n == 126: n = struct.unpack(">H", conn.recv(2, socket.MSG_WAITALL))[0]
        elif n == 127: n = struct.unpack(">Q", conn.recv(8, socket.MSG_WAITALL))[0]
        mask = conn.recv(4, socket.MSG_WAITALL) if h[1] & 0x80 else b"\0\0\0\0"
        payload = bytearray(conn.recv(n, socket.MSG_WAITALL)) if n else bytearray()
        for i in range(len(payload)): payload[i] ^= mask[i % 4]
        yield op, bytes(payload)

def handle(conn):
    log("connection accepted")
    req = b""
    while b"\r\n\r\n" not in req:
        chunk = conn.recv(4096)
        if not chunk: return
        req += chunk
    key = [l.split(b":", 1)[1].strip() for l in req.split(b"\r\n") if l.lower().startswith(b"sec-websocket-key")][0]
    accept = base64.b64encode(hashlib.sha1(key + b"258EAFA5-E914-47DA-95CA-C5AB0DC85B11").digest())
    conn.sendall(b"HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: " + accept + b"\r\n\r\n")
    log("websocket upgraded")
    mode = open(MODE).read().strip() if os.path.exists(MODE) else "ready"
    if mode == "stall":
        for _ in frames(conn): pass
        return
    send(conn, json.dumps({"type": "ready"}))
    def msg(kind, text):
        out = {"type": kind, "text": text}
        if mode == "cut" and len(text) > 23: out["unverified_cuts"] = [23]
        return json.dumps(out)
    stop = threading.Event()
    def partials():
        i = 0
        while not stop.is_set():
            send(conn, msg("partial", PARTIALS[min(i, len(PARTIALS) - 1)]))
            log("sent partial:", PARTIALS[min(i, len(PARTIALS) - 1)])
            i += 1
            time.sleep(1.0)
    threading.Thread(target=partials, daemon=True).start()
    for op, payload in frames(conn):
        if op == 1 and b"stop" in payload:
            stop.set(); time.sleep(0.3)
            send(conn, msg("final", FINAL if FINAL is not None else PARTIALS[-1]))
            log("sent final:", FINAL if FINAL is not None else PARTIALS[-1])
            return
        if op == 8: break
    stop.set()

srv = socket.socket(); srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
srv.bind(("127.0.0.1", PORT)); srv.listen(4)
log("listening on", PORT, "partials", PARTIALS)
while True:
    c, _ = srv.accept()
    def serve(c=c):
        try:
            handle(c)
        except Exception as error:  # the harness reads this log: never fail silently
            import traceback
            log("handler failed:", error)
            traceback.print_exc()
        finally:
            c.close()
    threading.Thread(target=serve, daemon=True).start()
