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
args = parser.parse_args()
if not re.fullmatch(r'emulator-\d+', args.serial):
    parser.error('Use an explicit emulator serial; this test never changes a phone')
args.out.mkdir(parents=True, exist_ok=True)

def adb(*command, binary=False):
    return subprocess.check_output(['adb', '-s', args.serial, *command], timeout=40, text=not binary)

def nodes():
    adb('shell', 'uiautomator', 'dump', '/sdcard/review-qa.xml')
    return ET.fromstring(adb('shell', 'cat', '/sdcard/review-qa.xml')).iter('node')

def find(text, contains=False, view_class=None, focused=False):
    deadline = time.monotonic() + 30
    while time.monotonic() < deadline:
        try:
            for node in nodes():
                if view_class and node.get('class') != view_class: continue
                if focused and node.get('focused') != 'true': continue
                value = node.get('text', '')
                if (text in value if contains else text == value):
                    return node
        except (subprocess.CalledProcessError, subprocess.TimeoutExpired, ET.ParseError):
            pass
        time.sleep(.25)
    raise AssertionError('Missing native view: ' + text)

def tap(text, contains=False):
    node = find(text, contains)
    x1, y1, x2, y2 = map(int, re.findall(r'\d+', node.get('bounds')))
    adb('shell', 'input', 'tap', str((x1+x2)//2), str((y1+y2)//2))

def body(text):
    find('', True, 'android.widget.EditText', True)
    adb('shell', 'input', 'text', text)
    find(text, True, 'android.widget.EditText')

def capture(name):
    (args.out / (name+'.png')).write_bytes(adb('exec-out', 'screencap', '-p', binary=True))

adb('shell', 'am', 'force-stop', 'dev.herdr.android.reviewtest')
adb('shell', 'am', 'start', '-W', '-n', 'dev.herdr.android.reviewtest/dev.herdr.android.FixtureActivity')
find('Live')
capture('files')
tap('src/review.rs', True)
find('ssh.forward_loopback', True)
capture('diff')
tap('ssh.forward_loopback', True)
find('src/review.rs:3 · new')
capture('line-comment')
body('Synthetic_line_comment')
tap('Send')
find('Sent · Live')
tap('Threads')
find('Closing it leaves the terminal running.', True)
capture('threads')
tap('Closing it leaves the terminal running.', True)
tap('Reply')
body('Synthetic_reply')
tap('Send')
find('Sent · Live')
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
find('Synthetic agent session')
capture('session')
tap('Review')
find('Live')
print('PASS: model startup, files/diff/comment, threads/reply/resolve, chat/ack, Session/Review')
