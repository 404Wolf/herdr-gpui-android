#!/usr/bin/env python3
"""Helpers for the emulator tests, stdlib only (no PIL, no uiautomator2).

  ui.py find XML REGEX        centre "x y" of the first node whose text or content-desc matches
  ui.py list XML              every labelled node: "x y text | desc"
  ui.py cols LOGCAT           the last "cols":N the renderer reported (herdr-tap layout lines)
  ui.py blank PNG TOP BOTTOM  fraction (0..1) of rows between TOP% and BOTTOM% of the height that
                              are one flat colour (nothing drawn there)
  ui.py text XML REGEX        the first text/desc matching REGEX, whole
"""
import re
import struct
import sys
import xml.etree.ElementTree as ET
import zlib


def nodes(path):
    out = []
    for node in ET.parse(path).iter("node"):
        text, desc = node.get("text", ""), node.get("content-desc", "")
        b = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", node.get("bounds", ""))
        if (text or desc) and b:
            x1, y1, x2, y2 = map(int, b.groups())
            out.append(((x1 + x2) // 2, (y1 + y2) // 2, text, desc))
    return out


def find(path, pattern):
    rx = re.compile(pattern)
    for x, y, text, desc in nodes(path):
        if rx.search(text) or rx.search(desc):
            return x, y, text or desc
    return None


def read_png(path):
    data = open(path, "rb").read()
    assert data[:8] == b"\x89PNG\r\n\x1a\n", "not a png"
    pos, idat, width = 8, b"", 0
    height = depth = ctype = 0
    while pos < len(data):
        (length,) = struct.unpack(">I", data[pos:pos + 4])
        kind = data[pos + 4:pos + 8]
        body = data[pos + 8:pos + 8 + length]
        if kind == b"IHDR":
            width, height, depth, ctype = struct.unpack(">IIBB", body[:10])
        elif kind == b"IDAT":
            idat += body
        pos += 12 + length
    assert depth == 8 and ctype in (2, 6), "unsupported png"
    bpp = 3 if ctype == 2 else 4
    raw = zlib.decompress(idat)
    stride = width * bpp
    rows, prev = [], bytearray(stride)
    for y in range(height):
        f = raw[y * (stride + 1)]
        line = bytearray(raw[y * (stride + 1) + 1:(y + 1) * (stride + 1)])
        for i in range(stride):
            a = line[i - bpp] if i >= bpp else 0
            b = prev[i]
            c = prev[i - bpp] if i >= bpp else 0
            if f == 1:
                line[i] = (line[i] + a) & 255
            elif f == 2:
                line[i] = (line[i] + b) & 255
            elif f == 3:
                line[i] = (line[i] + (a + b) // 2) & 255
            elif f == 4:
                p = a + b - c
                pa, pb, pc = abs(p - a), abs(p - b), abs(p - c)
                pred = a if pa <= pb and pa <= pc else (b if pb <= pc else c)
                line[i] = (line[i] + pred) & 255
        rows.append(line)
        prev = line
    return width, height, bpp, rows


def blank_fraction(path, top, bottom):
    width, height, bpp, rows = read_png(path)
    lo, hi = int(height * top / 100), int(height * bottom / 100)
    flat = 0
    for y in range(lo, hi):
        row = rows[y]
        first = row[0:3]
        # One flat colour all along the row: nothing was drawn on it.
        if all(abs(row[x * bpp + k] - first[k]) <= 6 for x in range(0, width, 4) for k in range(3)):
            flat += 1
    return flat / max(1, hi - lo)


def main(argv):
    cmd = argv[1]
    if cmd == "find":
        hit = find(argv[2], argv[3])
        if hit is None:
            return 1
        print(hit[0], hit[1])
    elif cmd == "text":
        hit = find(argv[2], argv[3])
        if hit is None:
            return 1
        print(hit[2])
    elif cmd == "list":
        for x, y, text, desc in nodes(argv[2]):
            print(x, y, text, "|", desc)
    elif cmd == "cols":
        last = None
        for line in open(argv[2], errors="replace"):
            m = re.search(r'"cols":(\d+)', line)
            if m and "herdr-tap" in line:
                last = m.group(1)
        if last is None:
            return 1
        print(last)
    elif cmd == "blank":
        print("%.4f" % blank_fraction(argv[2], float(argv[3]), float(argv[4])))
    else:
        return 2
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
