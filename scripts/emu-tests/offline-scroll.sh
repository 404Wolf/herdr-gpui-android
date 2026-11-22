#!/usr/bin/env bash
# The offline redesign (factory-9zz.1) on the job's emulator: drags offline are always answered,
# never sent over a dead link, and the link comes back as soon as the network does, with bounded
# retries.
#   lease-run --kind android --outputs <dir> -- scripts/emu-test.sh scripts/emu-tests/offline-scroll.sh
# A scratch core and sshd run in the job; the app connects at 10.0.2.2. Airplane mode is the
# emulator's own (cmd connectivity airplane-mode). summary.tsv rows: name, PASS|FAIL|WARN|SKIP,
# detail; exit 1 on a FAIL.
set -uo pipefail

: "${ADB_SERIAL:?Run through scripts/emu-test.sh}"
: "${EMU_OUT:?Run through scripts/emu-test.sh}"
: "${EMU_APK:?Run through scripts/emu-test.sh}"
here=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
ui="python3 $here/ui.py"
SUMMARY="$EMU_OUT/summary.tsv"
: > "$SUMMARY"
FAILS=0

# Every adb call is bounded: a lost emulator (an OOM kill on a small job) makes calls fail fast
# instead of blocking the job; once the device is gone the script stops with a FAIL row.
a() { timeout -k 2 90 adb -s "$ADB_SERIAL" "$@"; }
device_alive() { [[ "$(timeout 10 adb -s "$ADB_SERIAL" get-state 2> /dev/null)" == device ]]; }
record() { # name status detail
    if ! device_alive && [[ "$1" != emulator-lost ]]; then
        printf '%s\t%s\t%s\n' emulator-lost FAIL "the emulator is gone (ADB transport ended; check emulator.log and the job's oom_kill)" >> "$SUMMARY"
        echo "[FAIL] emulator-lost" >&2
        exit 1
    fi
    printf '%s\t%s\t%s\n' "$1" "$2" "${3:-}" >> "$SUMMARY"
    echo "[$2] $1 ${3:-}"
    [[ "$2" == FAIL ]] && FAILS=$((FAILS + 1))
    return 0
}
shot() { a exec-out screencap -p > "$EMU_OUT/$1.png"; }
dump() { # -> a FRESH $EMU_OUT/ui.xml (retried: uiautomator is flaky right after a transition)
    # No dump, no file. Each attempt dumps to a device path nobody used before and only a dump that
    # said it was written is pulled, so a failed attempt can never hand back an earlier screen's XML.
    local i path out
    rm -f "$EMU_OUT/ui.xml"
    for i in 1 2 3; do
        path="/sdcard/ui-$$-$SECONDS-$i.xml"
        out=$(a shell "uiautomator dump $path" 2>&1) || { sleep 1; continue; }
        grep -q 'dumped to' <<< "$out" || { sleep 1; continue; }
        if a pull "$path" "$EMU_OUT/ui.xml" > /dev/null 2>&1 && [[ -s "$EMU_OUT/ui.xml" ]]; then
            a shell rm -f "$path" > /dev/null 2>&1
            return 0
        fi
        rm -f "$EMU_OUT/ui.xml"
        sleep 1
    done
    return 1
}
# A system "isn't responding" dialog (System UI on a small, busy guest) covers the app and hides
# its controls from the dump: wait it out (Wait, never Close app) and let the caller look again.
dismiss_anr() {
    local hit
    hit=$($ui find "$EMU_OUT/ui.xml" "isn.t responding" 2> /dev/null) || return 1
    echo "[anr] $($ui text "$EMU_OUT/ui.xml" "isn.t responding" 2> /dev/null)" >> "$EMU_OUT/anr.log"
    hit=$($ui find "$EMU_OUT/ui.xml" '^Wait$' 2> /dev/null) || return 1
    local x y; read -r x y <<< "$hit"
    a shell input tap "$x" "$y"
    sleep 2
    return 0
}
find_ui() {
    dump || return 1
    $ui find "$EMU_OUT/ui.xml" "$1" && return 0
    dismiss_anr
    return 1
}
wait_ui() { # regex [seconds=25]  -> "x y"
    local end=$((SECONDS + ${2:-25})) hit
    while (( SECONDS < end )); do
        hit=$(find_ui "$1") && { echo "$hit"; return 0; }
        sleep 1
    done
    return 1
}
tap_ui() { # regex [seconds]
    local hit x y
    hit=$(wait_ui "$1" "${2:-25}") || return 1
    read -r x y <<< "$hit"
    a shell input tap "$x" "$y"
}
# One count (grep -c prints 0 and fails on no match: never add a second 0 with ||).
count() { # pattern file
    local n
    n=$(grep -c -- "$1" "$2" 2> /dev/null) || true
    echo "${n:-0}"
}
quiet() { sleep "${1:-2}"; }

# ---- the scratch host: herdr-core + sshd from Nix, in a short private directory ----
work=$(mktemp -d /tmp/osc.XXXXXX)
mkdir -p "$work/home" "$work/run" "$work/ssh"
cat > "$work/env.sh" << ENV
export HOME=$work/home XDG_RUNTIME_DIR=$work/run XDG_CONFIG_HOME=$work/home/.config
export XDG_STATE_HOME=$work/home/.local/state XDG_DATA_HOME=$work/home/.local/share
export XDG_CACHE_HOME=$work/home/.cache HC_SESSION=offsc TMPDIR=$work/run SHELL=$(command -v sh) TERM=xterm-256color
export PATH='$PATH'
unset HC_SCOPE HC_TERMINAL_ID HC_TERMINAL_NAME HC_SOCKET
ENV
# shellcheck disable=SC1091
. "$work/env.sh"
free_port() { python3 -c 'import socket; s = socket.socket(); s.bind(("127.0.0.1", 0)); print(s.getsockname()[1])'; }
SSH_PORT=$(free_port)
ssh-keygen -q -t ed25519 -N '' -f "$work/ssh/host" || { record setup FAIL "ssh-keygen"; exit 1; }
: > "$work/ssh/authorized_keys"
cat > "$work/ssh/wrap.sh" << WRAP
#!$(command -v sh)
. $work/env.sh
if [ -n "\$SSH_ORIGINAL_COMMAND" ]; then exec $(command -v sh) -c "\$SSH_ORIGINAL_COMMAND"; fi
exec $(command -v sh) -i
WRAP
chmod +x "$work/ssh/wrap.sh"
cat > "$work/ssh/sshd.conf" << CONF
Port $SSH_PORT
ListenAddress 127.0.0.1
HostKey $work/ssh/host
PidFile $work/ssh/sshd.pid
AuthorizedKeysFile $work/ssh/authorized_keys
PasswordAuthentication no
KbdInteractiveAuthentication no
PubkeyAuthentication yes
UsePAM no
StrictModes no
ForceCommand $work/ssh/wrap.sh
AllowUsers $(id -un)
AllowTcpForwarding no
PermitTTY yes
CONF
SSHD=$(command -v sshd) || { record setup FAIL "no sshd in the shell"; exit 1; }
start_sshd() { "$SSHD" -f "$work/ssh/sshd.conf" -E "$work/ssh/sshd.log"; }
stop_sshd() { [[ -s "$work/ssh/sshd.pid" ]] && kill "$(cat "$work/ssh/sshd.pid")" 2> /dev/null; return 0; }
mem_pid=
cleanup_host() {
    stop_sshd
    hc server stop > /dev/null 2>&1 || true
    cp "$work/ssh/sshd.log" "$EMU_OUT/sshd.log" 2> /dev/null || true
    rm -rf -- "$work"
}
trap cleanup_host EXIT

start_sshd || { record setup FAIL "sshd did not start (see sshd.log)"; exit 1; }
# A full-screen agent (the alternate screen with mouse reporting, as Claude Code and Codex draw;
# gap 2 of the redesign) and a main-screen shell with 200 numbered lines of history.
cat > "$work/fullscreen.py" << 'PY'
import sys, time
out = sys.stdout
out.write("\x1b[?1049h\x1b[?1000h\x1b[?1006h\x1b[H\x1b[2J")
for n in range(12):
    out.write("full-screen agent row %02d\r\n" % n)
out.write("> ")
out.flush()
while True:
    time.sleep(3600)
PY
hc run --bg --label agent.kind=codex --name agent-f -- python3 "$work/fullscreen.py" \
    > "$EMU_OUT/hc-f.log" 2>&1 || { record setup FAIL "hc run agent-f (see hc-f.log)"; exit 1; }
hc run --bg --label agent.kind=claude --name shell-a -- sh -c \
    'i=0; while [ $i -lt 200 ]; do printf "shell line %03d\n" $i; i=$((i + 1)); done; printf "$ "; exec cat' \
    > "$EMU_OUT/hc-a.log" 2>&1 || { record setup FAIL "hc run shell-a (see hc-a.log)"; exit 1; }
hc ls > "$EMU_OUT/terminals.json" 2>&1
mem_pid=
record setup PASS "ssh $SSH_PORT"

# ---- the phone: provisioned host + settings, microphone, first launch ----
pkg=$("$ANDROID_HOME/build-tools/35.0.0/aapt2" dump badging "$EMU_APK" | sed -n "s/^package: name='\([^']*\)'.*/\1/p")
[[ -n "$pkg" ]] || { record setup FAIL "no package name"; exit 1; }
fp=$(ssh-keygen -lf "$work/ssh/host.pub" | awk '{print $2}')
cat > "$work/hosts.json" << JSON
{"format": "remote-for-herdr-hosts", "version": 1, "hosts": [{"transport": "tcp", "host": "10.0.2.2", "port": $SSH_PORT, "endpoint_id": "", "endpoint_ticket": "", "relay_url": "", "username": "$(id -un)", "session": "offsc", "expected_fingerprint": "$fp", "name": "offschost", "shortcuts": "", "quickCommands": []}]}
JSON
cat > "$work/settings.json" << JSON
{"format":"remote-for-herdr-settings","version":1,"capturable":true}
JSON
files=/sdcard/Android/data/$pkg/files
a shell mkdir -p "$files"
a push "$work/hosts.json" "$files/remote-for-herdr-hosts.json" > /dev/null
a push "$work/settings.json" "$files/remote-for-herdr-settings.json" > /dev/null
a shell settings put global window_animation_scale 0
a shell settings put global transition_animation_scale 0
a shell settings put global animator_duration_scale 0
a shell input keyevent KEYCODE_WAKEUP
a shell wm dismiss-keyguard
launch() { a shell am force-stop "$pkg"; a shell am start -n "$pkg/dev.herdr.android.HomeActivity" > /dev/null; }
# Disabling Google's services (tried in 3b66180) makes Digital Wellbeing post "Enable Google Play
# services" as a heads-up notification that covers the top bar, so the overflow tap hit the
# notification (run 5922). They stay enabled; heads-up notifications are turned off instead, and the
# shade is collapsed before the steps that tap the top bar.
a shell settings put global heads_up_notifications_enabled 0
a shell cmd statusbar collapse > /dev/null 2>&1 || true
# A just-booted guest is still busy (System UI ANRs on the first dumps). Wait until boot has
# completed, the package manager answers, and 30 s pass with a fresh dump showing no "isn't
# responding" window; each one met is dismissed with Wait and counted. Up to 150 s.
settle_ui() {
    local end=$((SECONDS + 150)) calm_since=-1 anrs=0
    while (( SECONDS < end )); do
        if [[ "$(a shell getprop sys.boot_completed 2> /dev/null | tr -d '\r')" == 1 ]] \
            && a shell cmd package list packages > /dev/null 2>&1 && dump; then
            if dismiss_anr; then anrs=$((anrs + 1)); calm_since=-1
            elif (( calm_since < 0 )); then calm_since=$SECONDS
            elif (( SECONDS - calm_since >= 30 )); then
                (( anrs > 0 )) && record settle WARN "$anrs System UI/Play ANR dialog(s) were dismissed with Wait while the guest settled (anr.log)"
                return 0
            fi
        else
            calm_since=-1
        fi
        sleep 3
    done
    record settle WARN "the guest did not stay calm for 30 s within 150 s ($anrs ANR dialog(s) dismissed); continuing"
    return 1
}
settle_ui || true

# step TAP-REGEX EXPECT-REGEX [tries=3]: tap, then wait for the NEXT screen's element; if it did not
# appear (the tap landed on a screen that was still re-laying out, as on a first launch) look again
# and tap once more. Succeeds at once if the expected element is already on screen.
step() {
    local tap=$1 expect=$2 tries=${3:-3} n
    for (( n = 1; n <= tries; n++ )); do
        a shell cmd statusbar collapse > /dev/null 2>&1 || true
        wait_ui "$expect" 2 > /dev/null && return 0
        tap_ui "$tap" 20 > /dev/null || { sleep 2; continue; }
        wait_ui "$expect" 10 > /dev/null && return 0
    done
    return 1
}
device_key_fail() {
    shot device-key-fail
    dump > /dev/null 2>&1 || true
    {
        echo "what the screen showed when '$1' failed (labelled nodes):"
        [[ -s "$EMU_OUT/ui.xml" ]] && $ui list "$EMU_OUT/ui.xml"
        echo "--- window focus:"
        a shell dumpsys window 2> /dev/null | grep -E "mCurrentFocus|mFocusedApp" | head -3
        echo "--- notifications on screen:"
        a shell dumpsys notification --noredact 2> /dev/null | grep -E "NotificationRecord|android.title" | head -8
    } > "$EMU_OUT/device-key-fail.txt" 2>&1
    record device-key FAIL "$1 (see device-key-fail.png, device-key-fail.txt, ui.xml, anr.log)"
    exit 1
}

# Device key: create it in Hosts, copy its public key, paste it into a text field to read it back.
launch
# Home must be rendered and have finished its first rebuild ("Added 1 provisioned host") before the first tap.
wait_ui 'Hosts, device key and settings' 60 > /dev/null || device_key_fail "Home did not render"
quiet 4
# The overflow opens Remote hosts directly (or, on older builds, a menu with "Hosts and keys").
step 'Hosts, device key and settings' 'Create a new device SSH key|Copy the device SSH public key|Hosts and keys|Add host' \
    || device_key_fail "the overflow did not open the hosts screen"
wait_ui 'Create a new device SSH key|Copy the device SSH public key' 2 > /dev/null \
    || step 'Hosts and keys' 'Create a new device SSH key|Copy the device SSH public key' 2 \
    || device_key_fail "no Create key"
step 'Create a new device SSH key' 'Copy the device SSH public key' || device_key_fail "creating the key did not show Copy public key"
tap_ui 'Copy the device SSH public key' 15 > /dev/null || device_key_fail "no Copy public key"
quiet 1
# "Add host" opens a form whose first field is the host name.
step '^Add host$' 'SSH hostname or IP' || device_key_fail "Add host did not open the form"
tap_ui 'SSH hostname or IP' 15 > /dev/null || device_key_fail "no host field"
a shell input keyevent KEYCODE_PASTE
quiet 1
pub=$(wait_ui 'ssh-ed25519 ' 10 > /dev/null && $ui text "$EMU_OUT/ui.xml" 'ssh-ed25519 ') || device_key_fail "public key not readable"
echo "restrict,pty $pub" > "$work/ssh/authorized_keys"
record device-key PASS "authorized"

# ---- connect and open agent A ----
open_agent() { # name
    tap_ui "Computer offschost" 40 || return 1
    tap_ui 'offschost.* agents?' 15 || true   # the machine entry connects it
    quiet 6
    # "Other agents" is folded at first.
    find_ui "$1" > /dev/null 2>&1 || tap_ui 'Other agents' 15 || true
    quiet 2
    tap_ui "$1" 30
}
pane_is() { dump && $ui find "$EMU_OUT/ui.xml" "Window [0-9]+, $1, current" > /dev/null; }
# Another terminal: its tab (retried), else through the overflow menu's Panes; asserts it is now on
# screen, so nothing later runs against the wrong pane.
switch_to() { # name
    local n
    pane_is "$1" && return 0
    for n in 1 2 3; do
        tap_ui "Window [0-9]+, $1[.,]" 8 || tap_ui "$1" 8 || true
        quiet 4
        pane_is "$1" && return 0
    done
    tap_ui 'Panes, hosts and terminal actions' 10 && tap_ui '^Panes$' 10 && { quiet 1; tap_ui "$1" 15; }
    quiet 4
    pane_is "$1"
}


airplane() { a shell cmd connectivity airplane-mode "$1" > /dev/null 2>&1; }
# The app's own log lines since the last mark, with epoch times ("1795316219.567 ...").
mark_log() { a logcat -c > /dev/null 2>&1 || true; }
app_log() { a logcat -d -v epoch -s herdr-net:I herdr-reconnect:I 2> /dev/null | tr -d '\r'; }
# Seconds between the last "network arrived" line and the first "live: first paint" after it.
back_in() { # log-file -> seconds, or nothing
    python3 - "$1" << 'PY'
import re, sys
arrived = painted = None
for line in open(sys.argv[1], errors="replace"):
    m = re.match(r"\s*(\d+\.\d+)", line)
    if not m:
        continue
    t = float(m.group(1))
    if "network arrived or validated" in line:
        arrived, painted = t, None
    elif "live: first paint" in line and arrived is not None and painted is None:
        painted = t
if arrived is not None and painted is not None:
    print("%.1f" % (painted - arrived))
PY
}
wait_live() { # seconds -> 0 once a first paint is logged since the last mark
    local end=$((SECONDS + $1))
    while (( SECONDS < end )); do
        app_log | grep -q 'live: first paint' && return 0
        sleep 1
    done
    return 1
}
# Finger down: older lines (the app's drag direction).
drag_back() { a shell input swipe 540 900 540 1500 250; }
note_shown() { wait_ui "$1" "${2:-4}" > /dev/null; }

launch
if open_agent agent-f; then
    quiet 8
    shot f-open
    record connect PASS "agent-f open"
else
    shot connect-fail
    record connect FAIL "could not open agent-f (see connect-fail.png)"
    exit 1
fi

# (E-S2) A full-screen agent, airplane mode on, a drag at once (before any silence could say the
# link is dead): never unanswered. Then the held frame: the note says only the screen was saved.
mark_log
airplane enable
drag_back
if note_shown 'only the last screen was saved|the agent scrolls when the link is back' 4; then
    record fullscreen-drag-at-once PASS "$($ui text "$EMU_OUT/ui.xml" 'only the last screen|scrolls when the link' 2> /dev/null)"
else
    shot fullscreen-drag-fail
    record fullscreen-drag-at-once FAIL "no answer to a drag right after airplane mode (fullscreen-drag-fail.png)"
fi
quiet 6
app_log > "$EMU_OUT/app-offline-1.log"
if grep -q 'herdr-net.*no network' "$EMU_OUT/app-offline-1.log" && grep -q 'link lost: No network' "$EMU_OUT/app-offline-1.log"; then
    record no-network-fails-fast PASS "the link was given up on the no-network callback (app-offline-1.log)"
else
    record no-network-fails-fast FAIL "no 'no network' / 'link lost: No network' lines (app-offline-1.log)"
fi
drag_back
if note_shown 'only the last screen was saved' 4; then
    shot fullscreen-held-note
    record fullscreen-held-drag PASS "the held full-screen frame says only its screen was saved"
else
    shot fullscreen-held-fail
    record fullscreen-held-drag FAIL "no note for a drag on the held full-screen frame (fullscreen-held-fail.png)"
fi
if find_ui '. offline' > /dev/null; then
    record offline-indicator PASS "$($ui text "$EMU_OUT/ui.xml" '. offline' 2> /dev/null)"
else
    record offline-indicator FAIL "no offline indicator"
fi
# No attempt while there is no network at all.
quiet 10
app_log > "$EMU_OUT/app-offline-2.log"
tries=$(count 'attempt [0-9]* failed' "$EMU_OUT/app-offline-2.log")
if (( tries == 0 )); then
    record no-attempts-offline PASS "no reconnect attempt during 16 s with no network"
else
    record no-attempts-offline FAIL "$tries attempts failed with no network (app-offline-2.log)"
fi

# (E-R1) The network comes back: live again within 4 s of it (8 s at most).
airplane disable
if wait_live 30; then
    app_log > "$EMU_OUT/app-back-1.log"
    took=$(back_in "$EMU_OUT/app-back-1.log")
    if [[ -z "$took" ]]; then
        record reconnect-time WARN "live again, but no network line to time it from (app-back-1.log)"
    elif python3 -c "import sys; sys.exit(0 if float(sys.argv[1]) <= 4 else 1)" "$took"; then
        record reconnect-time PASS "live ${took} s after the network came back"
    elif python3 -c "import sys; sys.exit(0 if float(sys.argv[1]) <= 8 else 1)" "$took"; then
        record reconnect-time WARN "live ${took} s after the network came back (over 4 s, within 8 s)"
    else
        record reconnect-time FAIL "live only ${took} s after the network came back"
    fi
else
    app_log > "$EMU_OUT/app-back-1.log"
    shot not-back
    record reconnect-time FAIL "not live within 30 s of airplane mode off (app-back-1.log, not-back.png)"
fi

# (E-S1) A main-screen pane with history: held offline, a drag moves it.
if switch_to shell-a; then
    quiet 5
    mark_log
    airplane enable
    quiet 3
    shot shell-held-before
    drag_back
    quiet 2
    shot shell-held-after
    if ! cmp -s "$EMU_OUT/shell-held-before.png" "$EMU_OUT/shell-held-after.png"; then
        record shell-held-scroll PASS "the held shell frame moved (shell-held-before.png vs shell-held-after.png)"
    else
        record shell-held-scroll FAIL "the held shell frame did not move"
    fi
    if find_ui 'Latest' > /dev/null; then
        record shell-latest PASS "the Latest pill shows while scrolled back"
    else
        record shell-latest WARN "no Latest pill after the drag"
    fi
    airplane disable
    wait_live 30 || record shell-back FAIL "not live within 30 s after the shell check"
else
    record shell-held-scroll SKIP "no shell-a tab"
fi

# (E-R2) The network is up but the host is down: the attempts are bounded, then the indicator
# offers Reconnect, which works once the host is back.
mark_log
stop_sshd
if wait_ui 'offline · tap to reconnect' 150 > /dev/null; then
    shot stopped
    app_log > "$EMU_OUT/app-stopped.log"
    tries=$(count 'attempt [0-9]* failed' "$EMU_OUT/app-stopped.log")
    if (( tries >= 1 && tries <= 12 )); then
        record bounded-retries PASS "$tries attempts, then 'offline · tap to reconnect' (stopped.png)"
    else
        record bounded-retries FAIL "$tries attempts before stopping (app-stopped.log)"
    fi
    quiet 10
    app_log > "$EMU_OUT/app-stopped-2.log"
    after=$(count 'attempt [0-9]* failed' "$EMU_OUT/app-stopped-2.log")
    if (( after == tries )); then
        record stays-stopped PASS "no attempt for 10 s once stopped"
    else
        record stays-stopped FAIL "$((after - tries)) attempts after stopping"
    fi
    start_sshd || record setup FAIL "sshd did not restart"
    mark_log
    if tap_ui 'offline · tap to reconnect' 10 && tap_ui '^Reconnect$' 10 && wait_live 40; then
        record reconnect-by-hand PASS "Reconnect brought the pane back"
    else
        shot reconnect-by-hand-fail
        record reconnect-by-hand FAIL "Reconnect did not bring the pane back (reconnect-by-hand-fail.png)"
    fi
else
    shot not-stopped
    app_log > "$EMU_OUT/app-stopped.log"
    record bounded-retries FAIL "no 'offline · tap to reconnect' within 150 s (not-stopped.png, app-stopped.log)"
    start_sshd || true
fi

shot final
a logcat -b all -d > "$EMU_OUT/logcat-test.log" 2>&1
echo "--- summary ---"
cat "$SUMMARY"
(( FAILS == 0 ))
