#!/usr/bin/env python3
"""A stand-in for Claude Code's AskUserQuestion dialog, for elicit.sh.

Draws the dialog as Claude Code 2.1.322 does (ELICITATION.md): a tab row with the active tab
on a background, the question, numbered options with descriptions, "Type something." and
"Chat about this", and the key hint. It reports `blocked` (kind question) to herdr-core with
OSC 7501, as the wrapped Claude does, and behaves like the live dialog: arrows move, Enter
selects (advancing a single-select), toggles a box or ends a multi-select at Next, typing on
the custom row replaces its label, left/right switch tabs, and Submit answers prints
`ANSWERS color=<..> toppings=<..>`. Every key it receives is logged to argv[1], one per line.
With argv[2], it shows a plain prompt and asks nothing until that file exists, so a test can
put a draft in the phone's composer first.

stdlib only; runs on the job host inside a herdr-core terminal.
"""
import os
import sys
import termios
import time
import tty

QUESTIONS = [
    ("Color", "Which color?", ["Red", "Green", "Blue"], False),
    ("Toppings", "Which toppings?", ["Cheese", "Ham"], True),
]
BG = "\x1b[48;2;177;185;249m\x1b[38;2;0;0;0m"
RESET = "\x1b[39m\x1b[49m"


class Dialog:
    def __init__(self):
        self.tab = 0
        self.cursor = 0
        self.picked = [set() for _ in QUESTIONS]
        self.custom = ["" for _ in QUESTIONS]
        self.done = None

    def on_submit(self):
        return self.tab == len(QUESTIONS)

    def rows(self):
        if self.on_submit():
            return 2
        _, _, options, multi = QUESTIONS[self.tab]
        return len(options) + 2 + (1 if multi else 0)

    def answered(self, i):
        return bool(self.picked[i] or self.custom[i])

    def draw(self):
        out = ["\x1b[2J\x1b[H", "─" * 60, "\r\n"]
        header = "← "
        for i, (label, _, _, _) in enumerate(QUESTIONS):
            box = "☒" if self.answered(i) else "☐"
            cell = f" {box} {label} "
            header += (BG + cell + RESET if i == self.tab else cell) + " "
        cell = " ✔ Submit "
        header += (BG + cell + RESET if self.on_submit() else cell) + " →"
        out += [header, "\r\n\r\n"]
        mark = lambda i: "❯" if i == self.cursor else " "
        if self.on_submit():
            out += ["Ready to submit your answers?\r\n\r\n",
                    f"{mark(0)} 1. Submit answers\r\n", f"{mark(1)} 2. Cancel\r\n"]
        else:
            _, question, options, multi = QUESTIONS[self.tab]
            out += ["\x1b[1m" + question + "\x1b[22m\r\n\r\n"]
            for i, option in enumerate(options):
                label = option
                if multi:
                    label = f"[{'✔' if i in self.picked[self.tab] else ' '}] {option}"
                out += [f"{mark(i)} {i + 1}. {label}\r\n", f"     About {option}\r\n"]
            c = len(options)
            typed = self.custom[self.tab]
            custom = typed or "Type something."
            if multi:
                custom = f"[{'✔' if typed else ' '}] {custom}"
            out += [f"{mark(c)} {c + 1}. {custom}\r\n"]
            if multi:
                out += [f"{mark(c + 1)}    Next\r\n"]
            out += ["─" * 60 + "\r\n", f"{mark(self.rows() - 1)} {c + 2}. Chat about this\r\n"]
        out += ["\r\nEnter to select · Tab/Arrow keys to navigate · Esc to cancel"]
        sys.stdout.write("".join(out))
        sys.stdout.flush()

    def custom_row(self):
        return not self.on_submit() and self.cursor == len(QUESTIONS[self.tab][2])

    def key(self, key):
        if key == "up":
            self.cursor = max(0, self.cursor - 1)
        elif key == "down":
            self.cursor = min(self.rows() - 1, self.cursor + 1)
        elif key in ("right", "tab"):
            self.tab, self.cursor = min(len(QUESTIONS), self.tab + 1), 0
        elif key in ("left", "shift+tab"):
            self.tab, self.cursor = max(0, self.tab - 1), 0
        elif key == "esc":
            self.done = "CANCELLED"
        elif key == "backspace":
            if self.custom_row():
                self.custom[self.tab] = self.custom[self.tab][:-1]
        elif key == "enter":
            self.enter()
        elif len(key) == 1 and self.custom_row():
            if not QUESTIONS[self.tab][3]:
                self.picked[self.tab].clear()
            self.custom[self.tab] += key

    def enter(self):
        if self.on_submit():
            self.done = self.answers() if self.cursor == 0 else "CANCELLED"
            return
        _, _, options, multi = QUESTIONS[self.tab]
        c = len(options)
        if self.cursor == self.rows() - 1:
            self.done = "CHAT"
        elif multi and self.cursor < c:
            self.picked[self.tab] ^= {self.cursor}
        elif multi and self.cursor == c:
            self.custom[self.tab] = ""
        else:
            if self.cursor < c:
                self.picked[self.tab] = {self.cursor}
                self.custom[self.tab] = ""
            self.tab, self.cursor = self.tab + 1, 0

    def answers(self):
        parts = []
        for i, (label, _, options, _) in enumerate(QUESTIONS):
            chosen = [options[j] for j in sorted(self.picked[i])]
            if self.custom[i]:
                chosen.append(self.custom[i])
            parts.append(f"{label.lower()}={','.join(chosen) or '-'}")
        return "ANSWERS " + " ".join(parts)


SEQUENCES = {"\x1b[A": "up", "\x1b[B": "down", "\x1b[C": "right", "\x1b[D": "left",
             "\x1b[Z": "shift+tab", "\r": "enter", "\n": "enter", "\x7f": "backspace",
             "\x08": "backspace", "\t": "tab"}


def keys(data):
    """Splits what arrived into key names; a bracketed paste is its characters."""
    data = data.replace("\x1b[200~", "").replace("\x1b[201~", "")
    i = 0
    while i < len(data):
        for seq, name in SEQUENCES.items():
            if data.startswith(seq, i):
                yield name
                i += len(seq)
                break
        else:
            if data[i] == "\x1b":
                yield "esc"
            else:
                yield data[i]
            i += 1


def wait_for(trigger):
    """A quiet prompt until `trigger` exists; what is typed meanwhile goes nowhere."""
    sys.stdout.write("\x1b[2J\x1b[H(fixture) waiting\r\n\r\n> ")
    sys.stdout.flush()
    while not os.path.exists(trigger):
        time.sleep(0.2)


def main():
    log = open(sys.argv[1], "a", buffering=1)
    if len(sys.argv) > 2:
        wait_for(sys.argv[2])
    fd = sys.stdin.fileno()
    old = termios.tcgetattr(fd)
    tty.setraw(fd)
    dialog = Dialog()
    try:
        sys.stdout.write("\x1b]7501;state=blocked:kind=question:app=claude-code\x1b\\")
        dialog.draw()
        while dialog.done is None:
            data = os.read(fd, 4096).decode("utf-8", "replace")
            if not data:
                break
            for key in keys(data):
                log.write(key + "\n")
                dialog.key(key)
                if dialog.done:
                    break
            dialog.draw()
    finally:
        termios.tcsetattr(fd, termios.TCSADRAIN, old)
    sys.stdout.write("\x1b]7501;state=clear\x1b\\\x1b[2J\x1b[H")
    sys.stdout.write(f"{dialog.done}\r\n\r\n> ")
    sys.stdout.flush()
    log.write(f"result {dialog.done}\n")
    os.execvp("cat", ["cat"])


if __name__ == "__main__":
    main()
