# Emulator tests

Each test is a script that `scripts/emu-test.sh` runs against a disposable API 34 emulator inside a
Slurm `android` job (see `android-gpui/scripts/README.md` for the runner and `smoke.sh`).

## pr48.sh: keyboard hide, stop-and-send, cancel, offline

```sh
lease-run --kind android --outputs <dir> -- scripts/emu-test.sh scripts/emu-tests/pr48.sh
```

Everything runs inside the job. The `android-emu` shell supplies a scratch `herdr-core` (`hc`, built
by `nix/herdr-core` from the pinned source and patch stack) and `sshd`; the script starts both on the
job host in a private directory, starts `fake-dictation.py` (a fake dictation WebSocket, reached
through a job-local `adb reverse`), provisions a host at `10.0.2.2`, and creates the device key in
the app and reads it back through the UI to authorize it.

Memory: the job has a 6 GiB cap, so the emulator guest gets 3 GiB (`EMU_MEMORY` overrides it, in
`scripts/emu-test.sh`). The narrow case uses `wm size` smaller than the default (a smaller
framebuffer); the display density is never raised: density 900 made the renderer fail with
"Texture with 'atlas' label is invalid" and stay black for the rest of a run. `rss.log` holds the job
cgroup's memory and its biggest processes every 5 s, and a `memory` row in `summary.tsv` shows the
figures at the end.

Checks, in order: the recording controls at 46 columns (display width is calibrated
from the columns the renderer reports) and at the default width, 62 columns (about 100 columns is not
reachable on the 1080 px guest). A recording only counts when the fake server has sent a partial, so
the session is listening, and the app's own layout hint lists its controls. The timer is judged only
at 46 columns, where it is on the line: its own region must change between screenshots 3 s apart (two
intervals). At 62 columns the timer is not on the line, so only the controls and partials are checked; Stop-and-Send (Send pressed during recording
must deliver the fake server's final, "fixture final words", which differs from the last partial, as
new agent output, from this run's log); the "seed" + dictated
partial + `z` Cancel case (the draft is checked in the app's layout as `seed fixture partial`
before the keyboard opens, then sent and the lines added to the agent's terminal are inspected); and offline, with a saved grid: the core and sshd are
stopped, the pane is opened by switching tabs in the tab strip (agent-a, with the app staying open), then the keyboard is hidden
with the hide control, with Back, and with the navigation-bar key.

### Reading the results

Results are in `<dir>/emu-out/run.*/`:

- `summary.tsv`: one row per check, `name<TAB>PASS|FAIL|SKIP<TAB>detail`. The script exits 1 if any
  row is FAIL. A SKIP says why (for example the emulator uses gesture navigation, so there is no
  navigation-bar key). `emulator-lost` means the emulator died (see `emulator.log` and the job's
  `oom_kill`).
- PNGs beside it: `recording-46cols`, `recording-98cols`, `cancel-z-*`, `offline-*`.
- `dictation.log` (what the fake server sent), `agent-b-screen.txt` (what the agent received),
  `sshd.log`, `herdr-tap.log` (the renderer's reported columns), `logcat*.log`.

### Maintaining it

`ui.py` is stdlib-only: it finds nodes in a `uiautomator dump`, reads the renderer's column count
from logcat and measures how much of a screenshot band is one flat colour (a blank region). Its
parsers can be tried on any saved `ui.xml` or PNG without an emulator. The selectors in `pr48.sh`
are the app's content descriptions; change them there when a control's words change.

### What this harness does not prove

- **Draft text.** The app's layout hint (`herdr-tap` in logcat) reports control ids, their cells and
  the draft's cell spans, not text. Cancel is therefore checked by the draft's length in cells before
  and after the typed `z`, and by what the agent finally receives, not by reading the draft itself.
- **Timer text.** The same hint has no timer text: a ticking timer is only inferred: the `status` control is
  present in the hint and its screen region (rows from the accessibility dump, columns from the hint, grid
  assumed to fill the display width) changes between two screenshots 3 s apart. A repaint of that
  region that is not a counting timer would also pass, so this is evidence, not proof.
- **Extra output.** Stop-and-Send passes when a new line of the agent's terminal is exactly the final
  text; other new lines, even ones that look wrong, on separate lines do not fail it (only a sent
  `fixture partial` does).
- **The system image.** One API 34 google_apis guest with Google's services left enabled (disabling them posts a covering notification) and
  gesture navigation: the navigation-bar key check is a SKIP, other keyboards, other densities and
  physical devices are not covered.
- **The network.** The core and sshd are loopback fixtures reached through the emulator alias and
  `adb reverse`; real links, Tailscale and a real Whisper server are not exercised.
- **Offline.** The link is cut by stopping the fixtures and a pane is opened through the tab strip
  without relaunching the app (opening from Home's cached tree after a relaunch is not covered); a link
  that drops with a pane open and recovers is not covered.
- **Cleanup.** Early setup failures can leave the scratch directory behind; Slurm's cgroup teardown
  is what stops surviving processes.

## offline-s2.sh: offline transcripts (stage 2 of the offline design)

```sh
lease-run --kind android --outputs <dir> -- scripts/emu-test.sh scripts/emu-tests/offline-s2.sh
```

Same fixtures as `pr48.sh` (scratch core and sshd in the job, a provisioned host at `10.0.2.2`, the
device key authorized through the UI), with a core built with `guarded-send.patch` so it offers
`read-bounded` and `incarnation` (checked first: `core-features`). Agent A prints 300 numbered lines.

Checks: `transcript-read` (while A is live its transcript is kept under
`no_backup/transcripts-v1/`, holding its last line and headed with A's incarnation as `hc ls` reports
it), `live-hides` (no transcript button while live), then with sshd and the core stopped:
`offline-offer` (the button reads "transcript · as of HH:MM"), `offline-indicator`,
`transcript-open` (the screen is titled "transcript as of HH:MM · may be outdated" and holds the newest
line), `transcript-scroll` (swiping changes what is on screen; the kept text starts at line 000), and
`other-pane` (agent B never shows A's transcript).

Not covered here: an old core (it is never read; the JVM and Rust tests cover the gate), a program
replaced on the same address (the JVM tests cover the incarnation proof), and a cold start with the
core down (Home then has no agents to open).

## offline-scroll.sh: offline drags and the way back (factory-9zz.1)

```sh
lease-run --kind android --outputs <dir> -- scripts/emu-test.sh scripts/emu-tests/offline-scroll.sh
```

The same scratch host as `offline-s2.sh`, with a full-screen fake agent (`agent-f`: the alternate
screen with mouse reporting, as Claude Code and Codex draw) and a main-screen shell (`shell-a`, 200
lines). Airplane mode is the emulator's own (`cmd connectivity airplane-mode`). The checks:

- a drag right after airplane mode is answered by a note, never by nothing;
- the link is given up on the no-network callback;
- a drag on the held full-screen frame says only its screen was saved;
- no reconnect attempt is made with no network;
- the pane is live again within 4 s of the network's return (WARN up to 8 s), timed from the app's
  own `herdr-net` and `herdr-reconnect` lines;
- a held shell frame scrolls;
- with the host down, the attempts stop at 12 or fewer with "offline · tap to reconnect", and
  Reconnect brings the pane back once the host is up.

