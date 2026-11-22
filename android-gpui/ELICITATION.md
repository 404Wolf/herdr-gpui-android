# Answering agent questions on the phone

When an agent on the pane on screen asks something through a dialog, the phone
opens a sheet with native controls for it. The supported dialogs are Claude
Code's AskUserQuestion and permission prompts, Codex's `request_user_input`
(Plan mode) and approvals, and Gemini CLI's ask_user dialog and tool
confirmations. The sheet shows the question tabs, the options (radio buttons or
check boxes, with their descriptions), a field for your own answer, Codex's
notes, and **Submit**, **Chat about this** (Claude) and **Cancel**.

Picking options changes only the sheet. The agent's dialog changes in two
cases, and both are your own taps:

- **Switching tabs.** The agent shows one question at a time, so the phone
  switches the agent's own tab to read that question's options. This answers
  nothing.
- **Submit, Chat about this or Cancel.** The phone enters your answers with the
  agent's own keys (arrows, Enter, typing in its custom-answer row, Tab for
  Codex notes, Esc for Cancel). It chooses each key from the screen as it is
  then, and sends the next key only after the agent has redrawn. If the dialog
  on screen no longer asks what you answered, the phone stops and says so. The
  agent keeps what it already received; finish on the terminal.

The sheet opens once per dialog, and never over your typing: while the
composer holds a draft, you have the keyboard up or a dictation is recording
or transcribing, it waits and opens when you stop. The keyboard the app raises
by itself when you open an agent that is waiting on you does not count: the
sheet opens and puts it away. If you close it, the dialog
stays on the terminal until the agent asks something else. Nothing is ever
answered on its own. Like the terminal, the sheet is hidden from screenshots
and recents unless the app's capturable setting allows them.

## How it works

| Part | Responsibility |
| --- | --- |
| `rust/src/elicitation.rs` | Reads the dialog from the bottom 60 rows of the pane's live grid, then plans the next keys toward the chosen answers |
| `rust/src/elicitation_jni.rs` | `ElicitationNative.parse` and `step`: read-only, against the pane's renderer |
| `Elicitation.java` | The dialog model, the answers held until Submit, and the goal sent to the planner |
| `ElicitationRun.java` | Runs the planner one step at a time through the pane's ordered input queue |
| `ElicitationSheet.java` | The sheet |
| `ElicitationPolicy.java` | When to read the pane, open and close the sheet (pure, unit-tested) |
| `ElicitationOffer.java` | Runs those decisions: reads on a worker thread, shows the sheet |

The parser recognizes one shape: a numbered option list with exactly one
cursor row (`❯` Claude, `›` Codex, `●` Gemini), option 1 on screen, and the
dialog's key hint just below (`Enter to select`, `enter to submit`, `Press
enter to confirm`, `Esc to cancel`). A numbered list in an agent's reply has no
cursor and no hint, and is not read as a dialog. On a pane that the core does
not list as Claude or Codex, only Gemini's shape is offered. The active tab is
the one the agent highlights: Claude paints a background, Gemini underlines the
label, and Codex writes `Question 2/3`.

Claude reports `blocked` while it asks, so the phone reads a Claude pane only
then. Codex and Gemini do not report it reliably, so their panes are read while
they are not `working`, at most every 400 ms.

## Limits

- A list scrolled past option 1 (a very long list on a short pane) is not
  offered. Answer it on the terminal.
- Claude's permission prompt "Tab to amend" is not offered. Choose an option, or
  amend on the terminal.
- Codex submits on the last question's Enter, so every question needs an
  answer before Submit.
- Gemini's dialogs were checked against Gemini CLI 0.70.0's source and render
  snapshots. A live Gemini question dialog has not been tried yet.
- The inventory behind the parser (Claude Code 2.1.322 and Codex 0.167.1, seen
  live) is in the herdr-android reports as `elicitation-inventory.md`. A new
  agent version that redraws these dialogs needs its screens added to
  `elicitation_tests.rs`.
