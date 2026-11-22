#!/usr/bin/env python3
"""Stand-ins for the agents' question dialogs, for elicit.sh: no agent CLI runs.

    fake-ask.py KEYS_LOG [--wait FILE] [--harness claude|codex|gemini]

Every key received is logged to KEYS_LOG, one per line. With --wait, a plain prompt shows and
nothing is asked until FILE exists, so a test can put a draft in the phone's composer first.
On submit each prints `ANSWERS ...` and idles like an agent prompt.

claude (default): Claude Code 2.1.322's AskUserQuestion, as recorded live (ELICITATION.md):
a tab row with the active tab
on a background, the question, numbered options with descriptions, "Type something." and
"Chat about this", and the key hint. It reports `blocked` (kind question) to herdr-core with
OSC 7501, as the wrapped Claude does, and behaves like the live dialog: arrows move, Enter
selects (advancing a single-select), toggles a box or ends a multi-select at Next, typing on
the custom row replaces its label, left/right switch tabs, and Submit answers prints
`ANSWERS color=<..> toppings=<..>`.

codex: Codex 0.167.1's request_user_input (Plan mode), as recorded live: `Question i/n
(k unanswered)`, `›` on the highlighted option with its description in a column, Tab opening
a notes editor under the options, Enter answering and moving on (submitting all on the last),
left/right between questions. Prints `ANSWERS color=<..> note=<..> size=<..>`.

gemini: Gemini CLI 0.70.0's AskUserDialog, from its source and render snapshots (no live
session): `← □ Color │ □ Toppings │ ≡ Review →` with the active tab bold and underlined, `●`
on the highlighted row, check boxes, `All of the above`, the custom row ("Enter a custom
value", typing in it selects it; Enter on it submits the question), `Done`, and a Review tab
where Enter submits. Prints `ANSWERS color=<..> toppings=<..>`.

stdlib only; runs on the job host inside a herdr-core terminal.
"""
import argparse
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


class ClaudeDialog:
    STATUS = "\x1b]7501;state=blocked:kind=question:app=claude-code\x1b\\"

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


class CodexDialog:
    """Codex's request_user_input: one question at a time, options with a description column."""
    # Codex reports its state only through its wrapper; the phone reads its screen while idle.
    STATUS = ""
    QUESTIONS = [
        ("color", "Which color?", [("Red", "Choose red."), ("Green", "Choose green."), ("Blue", "Choose blue.")]),
        ("size", "What size?", [("Small", "Choose small."), ("Large", "Choose large.")]),
    ]
    OTHER = ("None of the above", "Optionally, add details in notes (tab)")

    def __init__(self):
        self.index = 0
        self.cursor = [0 for _ in self.QUESTIONS]
        self.answer = [None for _ in self.QUESTIONS]
        self.notes = ["" for _ in self.QUESTIONS]
        self.notes_open = False
        self.done = None

    def options(self):
        return self.QUESTIONS[self.index][2] + [self.OTHER]

    def draw(self):
        unanswered = sum(1 for a in self.answer if a is None)
        name, question, _ = self.QUESTIONS[self.index]
        out = ["\x1b[2J\x1b[H\r\n", f"  Question {self.index + 1}/{len(self.QUESTIONS)} ({unanswered} unanswered)\r\n",
               f"  {question}\r\n\r\n"]
        for i, (label, about) in enumerate(self.options()):
            mark = "›" if i == self.cursor[self.index] else " "
            out.append(f"  {mark} {i + 1}. {label:<18} {about}\r\n")
        if self.notes_open:
            out.append(f"\r\n  › {self.notes[self.index] or 'Add notes'}\r\n")
            out.append("\r\n  tab or esc to clear notes | enter to submit answer\r\n")
        else:
            last = "enter to submit all" if self.index == len(self.QUESTIONS) - 1 else "enter to submit answer"
            out.append(f"\r\n  tab to add notes | {last} | ←/→ to navigate questions | esc to interrupt\r\n")
        sys.stdout.write("".join(out))
        sys.stdout.flush()

    def key(self, key):
        if self.notes_open and key not in ("enter", "tab", "esc", "up", "down"):
            if key == "backspace":
                self.notes[self.index] = self.notes[self.index][:-1]
            elif len(key) == 1:
                self.notes[self.index] += key
            return
        if key == "up":
            self.cursor[self.index] = max(0, self.cursor[self.index] - 1)
        elif key == "down":
            self.cursor[self.index] = min(len(self.options()) - 1, self.cursor[self.index] + 1)
        elif key in ("left", "right") and not self.notes_open:
            step = 1 if key == "right" else -1
            self.index = min(len(self.QUESTIONS) - 1, max(0, self.index + step))
        elif key == "tab":
            # Tab opens the notes; with them open, Tab clears and closes them.
            if self.notes_open:
                self.notes[self.index] = ""
            self.notes_open = not self.notes_open
        elif key == "esc":
            if self.notes_open:
                self.notes[self.index], self.notes_open = "", False
            else:
                self.done = "INTERRUPTED"
        elif key == "enter":
            self.answer[self.index] = self.options()[self.cursor[self.index]][0]
            self.notes_open = False
            if self.index == len(self.QUESTIONS) - 1:
                self.done = self.answers()
            else:
                self.index += 1

    def answers(self):
        parts = []
        for i, (name, _, _) in enumerate(self.QUESTIONS):
            parts.append(f"{name}={self.answer[i] or '-'}")
            if self.notes[i]:
                parts.append(f"note={self.notes[i]}")
        return "ANSWERS " + " ".join(parts)


class GeminiDialog:
    """Gemini CLI's AskUserDialog: tabs, a Review tab, `●` rows, check boxes, an inline custom row."""
    # Gemini reports no state to herdr-core: the phone reads its screen.
    STATUS = ""
    QUESTIONS = [
        ("Color", "Which color?", [("Red", "Red paint"), ("Green", "Green paint"), ("Blue", "Blue paint")], False),
        ("Toppings", "Which toppings?", [("Cheese", "Cheese topping"), ("Ham", "Ham topping")], True),
    ]
    BOLD_UNDERLINE, PLAIN = "\x1b[1;4m", "\x1b[22;24m"

    def __init__(self):
        self.tab = 0
        self.cursor = 0
        self.picked = [set() for _ in self.QUESTIONS]
        self.custom = ["" for _ in self.QUESTIONS]
        self.custom_on = [False for _ in self.QUESTIONS]
        self.answer = [None for _ in self.QUESTIONS]
        self.done = None

    def review(self):
        return self.tab == len(self.QUESTIONS)

    def rows(self):
        """Row kinds of the current question: option indices, 'all', 'other', 'done'."""
        _, _, options, multi = self.QUESTIONS[self.tab]
        rows = list(range(len(options)))
        if multi:
            rows.append("all")
        rows.append("other")
        if multi:
            rows.append("done")
        return rows

    def draw(self):
        tabs = []
        for i, (label, _, _, _) in enumerate(self.QUESTIONS):
            mark = "✓" if self.answer[i] is not None else "□"
            name = self.BOLD_UNDERLINE + label + self.PLAIN if i == self.tab else label
            tabs.append(f"{mark} {name}")
        review = self.BOLD_UNDERLINE + "Review" + self.PLAIN if self.review() else "Review"
        out = ["\x1b[2J\x1b[H", "← " + " │ ".join(tabs + [f"≡ {review}"]) + " →\r\n\r\n"]
        if self.review():
            out.append("Review your answers:\r\n\r\n")
            for i, (label, _, _, _) in enumerate(self.QUESTIONS):
                out.append(f"{label} → {self.answer[i] or '(not answered)'}\r\n")
            out.append("\r\nEnter to submit · Tab/Shift+Tab to edit answers · Esc to cancel")
        else:
            _, question, options, multi = self.QUESTIONS[self.tab]
            out.append(question + "\r\n")
            if multi:
                out.append("(Select all that apply)\r\n")
            out.append("\r\n")
            number = 0
            for i, row in enumerate(self.rows()):
                lead = "●" if i == self.cursor else " "
                if row == "done":
                    out.append(f"{lead}  Done\r\n   Finish selection\r\n")
                    continue
                number += 1
                if row == "all":
                    box = "[x]" if len(self.picked[self.tab]) == len(options) else "[ ]"
                    out.append(f"{lead} {number}. {box} All of the above\r\n         Select all options\r\n")
                elif row == "other":
                    text = self.custom[self.tab] or "Enter a custom value"
                    if multi:
                        box = "[x]" if self.custom_on[self.tab] else "[ ]"
                        out.append(f"{lead} {number}. {box} {text}\r\n")
                    else:
                        out.append(f"{lead} {number}.  {text}\r\n")
                else:
                    label, about = options[row]
                    if multi:
                        box = "[x]" if row in self.picked[self.tab] else "[ ]"
                        out.append(f"{lead} {number}. {box} {label}\r\n         {about}\r\n")
                    else:
                        out.append(f"{lead} {number}.  {label}\r\n      {about}\r\n")
            out.append("\r\nEnter to select · ←/→ to switch questions · Esc to cancel")
        sys.stdout.write("".join(out))
        sys.stdout.flush()

    def on_custom(self):
        return not self.review() and self.rows()[self.cursor] == "other"

    def key(self, key):
        if key == "esc":
            self.done = "CANCELLED"
        elif key == "up" and not self.review():
            self.cursor = max(0, self.cursor - 1)
        elif key == "down" and not self.review():
            self.cursor = min(len(self.rows()) - 1, self.cursor + 1)
        elif key in ("right", "tab"):
            self.tab, self.cursor = min(len(self.QUESTIONS), self.tab + 1), 0
        elif key in ("left", "shift+tab"):
            self.tab, self.cursor = max(0, self.tab - 1), 0
        elif key == "backspace" and self.on_custom():
            self.custom[self.tab] = self.custom[self.tab][:-1]
        elif key == "enter":
            self.enter()
        elif len(key) == 1 and self.on_custom():
            self.custom[self.tab] += key
            self.custom_on[self.tab] = True   # typing selects the custom row

    def enter(self):
        if self.review():
            self.done = self.answers()
            return
        _, _, options, multi = self.QUESTIONS[self.tab]
        row = self.rows()[self.cursor]
        if multi and isinstance(row, int):
            self.picked[self.tab] ^= {row}
            return
        if row == "all":
            self.picked[self.tab] = set() if len(self.picked[self.tab]) == len(options) else set(range(len(options)))
            return
        if row == "other" and not self.custom[self.tab].strip():
            return   # nothing typed: Enter does nothing
        if multi:
            chosen = [options[j][0] for j in sorted(self.picked[self.tab])]
            if self.custom_on[self.tab] and self.custom[self.tab]:
                chosen.append(self.custom[self.tab])
            self.answer[self.tab] = ",".join(chosen) or None
        else:
            self.answer[self.tab] = self.custom[self.tab] if row == "other" else options[row][0]
        self.tab, self.cursor = self.tab + 1, 0

    def answers(self):
        return "ANSWERS " + " ".join(f"{label.lower()}={self.answer[i] or '-'}" for i, (label, _, _, _) in enumerate(self.QUESTIONS))


HARNESSES = {"claude": ClaudeDialog, "codex": CodexDialog, "gemini": GeminiDialog}


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
    parser = argparse.ArgumentParser()
    parser.add_argument("log")
    parser.add_argument("--wait")
    parser.add_argument("--harness", choices=sorted(HARNESSES), default="claude")
    args = parser.parse_args()
    log = open(args.log, "a", buffering=1)
    if args.wait:
        wait_for(args.wait)
    fd = sys.stdin.fileno()
    old = termios.tcgetattr(fd)
    tty.setraw(fd)
    dialog = HARNESSES[args.harness]()
    try:
        sys.stdout.write(dialog.STATUS)
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
