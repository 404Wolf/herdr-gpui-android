#!/usr/bin/env python3
"""Exercise only the explicit synthetic native-view APK on a selected emulator."""
import argparse
import pathlib
import re
import subprocess
import time
import xml.etree.ElementTree as ET

parser = argparse.ArgumentParser()
parser.add_argument('--serial', required=True)
parser.add_argument('--out', type=pathlib.Path, required=True)
parser.add_argument('--long-diff', action='store_true')
parser.add_argument('--layout-only', action='store_true')
args = parser.parse_args()
if not re.fullmatch(r'emulator-\d+', args.serial):
    parser.error('Use an explicit emulator serial; this test never changes a phone')
args.out.mkdir(parents=True, exist_ok=True)

def adb(*command, binary=False):
    return subprocess.check_output(['adb', '-s', args.serial, *command], timeout=40, text=not binary)

def nodes():
    adb('shell', 'uiautomator', 'dump', '/sdcard/review-qa.xml')
    return ET.fromstring(adb('shell', 'cat', '/sdcard/review-qa.xml')).iter('node')

def find(text, contains=False, view_class=None, focused=False, description=False):
    deadline = time.monotonic() + 30
    while time.monotonic() < deadline:
        try:
            for node in nodes():
                if view_class and node.get('class') != view_class: continue
                if focused and node.get('focused') != 'true': continue
                value = node.get('content-desc' if description else 'text', '')
                if (text in value if contains else text == value):
                    return node
        except (subprocess.CalledProcessError, subprocess.TimeoutExpired, ET.ParseError):
            pass
        time.sleep(.25)
    raise AssertionError('Missing native view: ' + text)

def absent(text):
    deadline = time.monotonic() + 30
    while time.monotonic() < deadline:
        if not any(n.get('text') == text for n in nodes()): return
        time.sleep(.25)
    raise AssertionError('Native popup did not close: ' + text)

def tap(text, contains=False, description=False):
    node = find(text, contains, description=description)
    x1, y1, x2, y2 = map(int, re.findall(r'\d+', node.get('bounds')))
    adb('shell', 'input', 'tap', str((x1+x2)//2), str((y1+y2)//2))

def body(text):
    find('', True, 'android.widget.EditText', True)
    adb('shell', 'input', 'text', text)
    find(text, True, 'android.widget.EditText')

def capture(name):
    (args.out / (name+'.png')).write_bytes(adb('exec-out', 'screencap', '-p', binary=True))

adb('shell', 'am', 'force-stop', 'dev.herdr.android.reviewtest')
adb('shell', 'am', 'start', '-W', '-n', 'dev.herdr.android.reviewtest/dev.herdr.android.FixtureActivity', '--ez', 'long_diff', str(args.long_diff).lower(), '--ez', 'jump_end', str(args.long_diff).lower())
find('Review: Live', description=True)
capture('files')
bar = find('Session').get('bounds')
assert re.findall(r'\d+', bar)[1::2] == re.findall(r'\d+', find('Files').get('bounds'))[1::2], 'All tabs must share one bar'
assert not any(n.get('text', '').startswith(('‹ Previous', 'Next ›')) for n in nodes()), 'No paging controls'
tap('src/review.rs', True)
if args.long_diff:
    find('CONTINUOUS_END', True)
    capture('continuous-end')
    print('PASS: continuous 600-row diff reaches its end without paging')
    raise SystemExit(0)
find('ssh.forward_loopback', True)
capture('diff')
comment = find('HORIZONTAL_END', True)
x1, y1, x2, y2 = map(int, re.findall(r'\d+', comment.get('bounds')))
gutter_before = find('2 +').get('bounds')
for _ in range(3): adb('shell', 'input', 'swipe', str(x2-80), str((y1+y2)//2), str(x1+80), str((y1+y2)//2), '350')
assert find('2 +').get('bounds') == gutter_before, 'Horizontal scrolling must pin the gutter'
capture('horizontal')
for _ in range(3): adb('shell', 'input', 'swipe', str(x1+80), str((y1+y2)//2), str(x2-80), str((y1+y2)//2), '350')
tap('Review options', description=True)
tap('Wrap code')
absent('Wrap code')
wrapped = find('HORIZONTAL_END', True)
wy = list(map(int, re.findall(r'\d+', wrapped.get('bounds'))))
assert wy[3] - wy[1] > y2 - y1, 'Wrap must produce multiple code lines'
capture('wrapped')
tap('Review options', description=True)
tap('Wrap code')
absent('Wrap code')
if args.layout_only:
    capture('diff-reset')
    print('PASS: compact bar, shared pan with pinned gutter, wrap/unwrapped')
    raise SystemExit(0)
tap('ssh.forward_loopback', True)
find('src/review.rs:3 · new')
capture('line-comment')
body('Synthetic_line_comment')
tap('Send')
find('Review: Sent · Live', description=True)
tap('Threads')
find('Closing it leaves the terminal running.', True)
capture('threads')
tap('Closing it leaves the terminal running.', True)
tap('Reply')
body('Synthetic_reply')
tap('Send')
find('Review: Sent · Live', description=True)
tap('Closing it leaves the terminal running.', True)
tap('Resolve')
find('✓ src/review.rs:3', True)
capture('resolved-thread')
tap('Chat')
find('Ready for review.', True)
tap('Message agent')
body('Synthetic_chat')
tap('Send')
find('Synthetic_chat', True)
capture('chat')
tap('Session')
find('Terminal viewport (synthetic)')
capture('session')
tap('Review')
find('Review: Live', description=True)
print('PASS: model startup, files/diff/comment, threads/reply/resolve, chat/ack, Session/Review, shared horizontal pan/pinned gutter/wrap')
