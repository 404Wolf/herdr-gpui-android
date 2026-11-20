#!/usr/bin/env python3
"""Helpers for the emulator tests, stdlib only (no PIL, no uiautomator2).

  ui.py find XML REGEX        centre "x y" of the first node whose text or content-desc matches
  ui.py list XML              every labelled node: "x y text | desc"
  ui.py cols LOGCAT           the last "cols":N the renderer reported (herdr-tap layout lines)
  ui.py blank PNG TOP BOTTOM  fraction (0..1) of rows between TOP% and BOTTOM% of the height that
                              are one flat colour (nothing drawn there)
  ui.py text XML REGEX        the first text/desc matching REGEX, whole
  ui.py layout LOGCAT         "ids: a b c | cols N | draft CELLS" of the app's last reported layout hint
                              (what the renderer actually drew, not what a screenshot happens to show)
  ui.py timer LOGCAT PNG PNG [XML]  fraction of the recording timer's own region that changed between two
                              screenshots (the "status" control of the last layout hint)
  ui.py newlines BEFORE AFTER the lines of AFTER that are not part of BEFORE, in order; repeats of an
                              earlier line still count when they are new (a sequence diff, not a set)
  ui.py mem                   this job's cgroup memory: "cur MiB peak MiB | top: comm RSS MiB, ..."
  ui.py diff PNG PNG TOP BOTTOM  fraction (0..1) of rows between TOP% and BOTTOM% that differ
                              between two screenshots (a ticking timer changes rows; a still screen none)
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


def last_layout(log_path):
    import json
    last = None
    for line in open(log_path, errors="replace"):
        i = line.find("herdr-tap: layout ")
        if i < 0:
            continue
        try:
            last = json.loads(line[i + len("herdr-tap: layout "):])
        except ValueError:
            pass
    return last


def draft_cells(layout):
    return sum(max(0, s[2] - s[1]) for s in layout.get("draftSpans", []) if len(s) >= 3)


def timer_region_change(log_path, png_a, png_b, xml=None):
    layout = last_layout(log_path)
    status = next((c for c in (layout or {}).get("controls", []) if c["id"] == "status"), None)
    if status is None:
        return None
    wa, ha, bppa, ra = read_png(png_a)
    wb, hb, bppb, rb = read_png(png_b)
    assert (wa, ha, bppa) == (wb, hb, bppb), "screenshots differ in size"
    scale = wa / (layout["cols"] * layout["cellWidth"])  # px per layout unit: the grid fills the width
    x0 = max(0, int((status["start"] - 1) * layout["cellWidth"] * scale))
    x1 = min(wa, int((status["end"] + 1) * layout["cellWidth"] * scale))
    top = layout.get("top", 0.0)
    y0 = max(0, int((top + status["row"] * layout["cellHeight"]) * scale))
    y1 = min(ha, int((top + (status["row"] + 1) * layout["cellHeight"]) * scale))
    if xml:
        # The hint's rows are relative to the terminal surface, whose origin on the screen is not in it:
        # take the controls' row from the accessibility dump (the Send target is drawn on that row).
        hit = find(xml, "Send, or press Enter")
        if hit is None:
            return None
        half = max(2, int(layout["cellHeight"] * scale * 0.6))
        y0, y1 = max(0, hit[1] - half), min(ha, hit[1] + half)
    changed = total = 0
    for y in range(y0, y1):
        for x in range(x0, x1):
            total += 1
            if ra[y][x * bppa:x * bppa + 3] != rb[y][x * bppb:x * bppb + 3]:
                changed += 1
    return changed / max(1, total)


def new_lines(before, after):
    import difflib
    a = [l.rstrip() for l in open(before, errors="replace").read().splitlines()]
    b = [l.rstrip() for l in open(after, errors="replace").read().splitlines()]
    out = []
    for tag, i1, i2, j1, j2 in difflib.SequenceMatcher(None, a, b, autojunk=False).get_opcodes():
        if tag in ("insert", "replace"):
            out.extend(b[j1:j2])
    return [l for l in out if l.strip()]


def cgroup_memory():
    import os
    rel = ""
    for line in open("/proc/self/cgroup"):
        rel = line.strip().split(":", 2)[2]
    base = "/sys/fs/cgroup" + rel

    def read(name):
        try:
            return int(open(os.path.join(base, name)).read().strip())
        except (OSError, ValueError):
            return None

    cur, peak = read("memory.current"), read("memory.peak")
    top = []
    try:
        for pid in open(os.path.join(base, "cgroup.procs")).read().split():
            try:
                status = open("/proc/%s/status" % pid).read()
                rss = int(re.search(r"VmRSS:\s+(\d+) kB", status).group(1)) // 1024
                name = re.search(r"Name:\s+(\S+)", status).group(1)
                top.append((rss, name))
            except (OSError, AttributeError):
                pass
    except OSError:
        pass
    top.sort(reverse=True)
    mib = lambda v: "?" if v is None else str(v // (1024 * 1024))
    return "cur %s MiB peak %s MiB | top: %s" % (mib(cur), mib(peak), ", ".join("%s %d MiB" % (n, r) for r, n in top[:5]))


def diff_fraction(a, b, top, bottom):
    wa, ha, bppa, ra = read_png(a)
    wb, hb, bppb, rb = read_png(b)
    assert (wa, ha, bppa) == (wb, hb, bppb), "screenshots differ in size"
    lo, hi = int(ha * top / 100), int(ha * bottom / 100)
    changed = 0
    for y in range(lo, hi):
        if ra[y] != rb[y]:
            changed += 1
    return changed / max(1, hi - lo)


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
    elif cmd == "layout":
        layout = last_layout(argv[2])
        if layout is None:
            return 1
        ids = " ".join(c["id"] for c in layout.get("controls", []))
        print("ids: %s | cols %s | draft %d" % (ids, layout.get("cols"), draft_cells(layout)))
    elif cmd == "timer":
        fraction = timer_region_change(argv[2], argv[3], argv[4], argv[5] if len(argv) > 5 else None)
        if fraction is None:
            return 1
        print("%.4f" % fraction)
    elif cmd == "newlines":
        print("\n".join(new_lines(argv[2], argv[3])))
    elif cmd == "mem":
        print(cgroup_memory())
    elif cmd == "diff":
        print("%.4f" % diff_fraction(argv[2], argv[3], float(argv[4]), float(argv[5])))
    elif cmd == "blank":
        print("%.4f" % blank_fraction(argv[2], float(argv[3]), float(argv[4])))
    else:
        return 2
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
