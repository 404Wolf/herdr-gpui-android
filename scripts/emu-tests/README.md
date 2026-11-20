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

Checks, in order: the recording controls at about 46 and 100 columns (display width is calibrated
from the columns the renderer reports); the "seed" + dictated partial + `z` Cancel case (the draft is
sent and read back from the agent's terminal); and offline, with a saved grid: the core and sshd are
stopped, the app relaunched, the pane opened from Home's cached tree, then the keyboard is hidden
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
