#!/usr/bin/env bash
# PR #48 (hide keyboard, stop-and-send, cancel, offline black region) on the job's emulator.
#   lease-run --kind android --outputs <dir> -- scripts/emu-test.sh scripts/emu-tests/pr48.sh
# Runs entirely inside the job: a scratch herdr-core and sshd from the android-emu shell, the app
# connecting to them at 10.0.2.2, a fake dictation server. Screenshots and summary.tsv (name,
# PASS|FAIL|SKIP, detail) go to $EMU_OUT; the exit status is 1 if any check FAILed.
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
    # No dump, no file: a failed dump must never leave an earlier screen's XML to be read as this one.
    rm -f "$EMU_OUT/ui.xml"
    a shell rm -f /sdcard/ui.xml > /dev/null 2>&1
    for _ in 1 2 3; do
        a shell uiautomator dump /sdcard/ui.xml > /dev/null 2>&1 \
            && a pull /sdcard/ui.xml "$EMU_OUT/ui.xml" > /dev/null 2>&1 && [[ -s "$EMU_OUT/ui.xml" ]] && return 0
        rm -f "$EMU_OUT/ui.xml"
        sleep 1
    done
    return 1
}
find_ui() { dump && $ui find "$EMU_OUT/ui.xml" "$1"; }
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
partials() { count 'sent partial: fixture partial' "$EMU_OUT/dictation.log"; }
# True once the fake server has sent a partial newer than count $1: the app's session is LISTENING
# (a partial only exists after the server said ready), not still connecting.
wait_partial() { # count-before [seconds=25]
    local end=$((SECONDS + ${2:-25})) now
    while (( SECONDS < end )); do
        now=$(partials)
        (( now > $1 )) && return 0
        sleep 1
    done
    return 1
}
# The terminal screen of a named agent as the core sees it.
agent_screen() { # name
    local addr
    addr=$(hc ls 2> /dev/null | NAME="$1" python3 -c 'import json,os,sys; d=json.load(sys.stdin); print(next(t["address"] for t in d["terminals"] if t.get("name")==os.environ["NAME"]))' 2> /dev/null) || return 1
    hc read "$addr" 2> /dev/null
}
quiet() { sleep "${1:-2}"; }
ime_shown() { a shell dumpsys input_method 2> /dev/null | grep -q 'mInputShown=true'; }

# ---- the scratch host: herdr-core + sshd from Nix, in a short private directory ----
work=$(mktemp -d /tmp/p48.XXXXXX)
mkdir -p "$work/home" "$work/run" "$work/ssh"
cat > "$work/env.sh" << ENV
export HOME=$work/home XDG_RUNTIME_DIR=$work/run XDG_CONFIG_HOME=$work/home/.config
export XDG_STATE_HOME=$work/home/.local/state XDG_DATA_HOME=$work/home/.local/share
export XDG_CACHE_HOME=$work/home/.cache HC_SESSION=pr48 TMPDIR=$work/run SHELL=$(command -v sh) TERM=xterm-256color
export PATH='$PATH'
unset HC_SCOPE HC_TERMINAL_ID HC_TERMINAL_NAME HC_SOCKET
ENV
# shellcheck disable=SC1091
. "$work/env.sh"
free_port() { python3 -c 'import socket; s = socket.socket(); s.bind(("127.0.0.1", 0)); print(s.getsockname()[1])'; }
SSH_PORT=$(free_port)
DICT_PORT=$(free_port)
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
dict_pid=
cleanup_host() {
    stop_sshd
    hc server stop > /dev/null 2>&1 || true
    [[ -n "$dict_pid" ]] && kill "$dict_pid" 2> /dev/null
    cp "$work/ssh/sshd.log" "$EMU_OUT/sshd.log" 2> /dev/null || true
    rm -rf -- "$work"
}
trap cleanup_host EXIT

start_sshd || { record setup FAIL "sshd did not start (see sshd.log)"; exit 1; }
# Agent A fills its screen with coloured lines (a saved grid with real output); B is a plain prompt.
hc run --bg --label agent.kind=claude --name agent-a -- sh -c \
    'i=0; while [ $i -lt 140 ]; do printf "\033[48;5;%dm line %03d %-100s\033[0m\n" $((17 + i % 200)) $i ""; i=$((i + 1)); done; printf "> "; exec cat' \
    > "$EMU_OUT/hc-a.log" 2>&1 || { record setup FAIL "hc run A (see hc-a.log)"; exit 1; }
hc run --bg --label agent.kind=claude --name agent-b -- sh -c 'printf "Claude Code (fixture B)\n\n> "; exec cat' \
    > "$EMU_OUT/hc-b.log" 2>&1 || { record setup FAIL "hc run B (see hc-b.log)"; exit 1; }
nohup python3 -u "$here/fake-dictation.py" "$DICT_PORT" "fixture" "fixture partial" > "$EMU_OUT/dictation.log" 2>&1 &
dict_pid=$!
record setup PASS "ssh $SSH_PORT, dictation $DICT_PORT"
# The dictation fixture is reached through the job's own ADB (adb reverse), not through the
# emulator's 10.0.2.2 alias, which did not carry it on the job host; the scratch SSH stays at 10.0.2.2.
# A probe from the device must reach it, or every later dictation check would measure the harness.
a reverse "tcp:$DICT_PORT" "tcp:$DICT_PORT" > "$EMU_OUT/adb-reverse.log" 2>&1 \
    || echo "adb reverse failed" >> "$EMU_OUT/adb-reverse.log"
# Wait for the server's own listening line, then probe from the device, retrying: a node can be slow
# to start the server or to carry the first connection, and that is not a verdict on the app.
reach=0
end=$((SECONDS + 40))
while (( SECONDS < end )); do
    if grep -q 'listening on' "$EMU_OUT/dictation.log" 2> /dev/null; then
        a shell "echo ping | nc -w 2 127.0.0.1 $DICT_PORT" > /dev/null 2>&1 || true
        sleep 2
        if grep -q 'connection accepted' "$EMU_OUT/dictation.log"; then reach=1; break; fi
    else
        sleep 1
    fi
done
if (( reach )); then
    record dictation-reach PASS "the emulator reached the fake dictation server at 127.0.0.1:$DICT_PORT through adb reverse"
else
    record dictation-reach SKIP "no probe connection within 40 s (see dictation.log, adb-reverse.log); the cancel check still waits for the app's own partial and FAILs there if none comes"
fi

# ---- the phone: provisioned host + settings, microphone, first launch ----
pkg=$("$ANDROID_HOME/build-tools/35.0.0/aapt2" dump badging "$EMU_APK" | sed -n "s/^package: name='\([^']*\)'.*/\1/p")
[[ -n "$pkg" ]] || { record setup FAIL "no package name"; exit 1; }
fp=$(ssh-keygen -lf "$work/ssh/host.pub" | awk '{print $2}')
cat > "$work/hosts.json" << JSON
{"format": "remote-for-herdr-hosts", "version": 1, "hosts": [{"transport": "tcp", "host": "10.0.2.2", "port": $SSH_PORT, "endpoint_id": "", "endpoint_ticket": "", "relay_url": "", "username": "$(id -un)", "session": "pr48", "expected_fingerprint": "$fp", "name": "pr48host", "shortcuts": "", "quickCommands": []}]}
JSON
cat > "$work/settings.json" << JSON
{"format":"remote-for-herdr-settings","version":1,"capturable":true,"dictationUrl":"ws://127.0.0.1:$DICT_PORT/v1/dictate"}
JSON
files=/sdcard/Android/data/$pkg/files
a shell mkdir -p "$files"
a push "$work/hosts.json" "$files/remote-for-herdr-hosts.json" > /dev/null
a push "$work/settings.json" "$files/remote-for-herdr-settings.json" > /dev/null
a shell pm grant "$pkg" android.permission.RECORD_AUDIO
a shell settings put global window_animation_scale 0
a shell settings put global transition_animation_scale 0
a shell settings put global animator_duration_scale 0
a shell input keyevent KEYCODE_WAKEUP
a shell wm dismiss-keyguard
launch() { a shell am force-stop "$pkg"; a shell am start -n "$pkg/dev.herdr.android.HomeActivity" > /dev/null; }

# Device key: create it in Hosts, copy its public key, paste it into a text field to read it back.
launch
tap_ui 'Hosts, device key and settings' 40 || { record device-key FAIL "no overflow menu on Home"; exit 1; }
# The overflow opens Remote hosts directly on this build; older ones show a menu with "Hosts and keys".
tap_ui 'Hosts and keys' 4 || true
tap_ui 'Create a new device SSH key' 20 || { record device-key FAIL "no Create key"; shot device-key-fail; exit 1; }
quiet 2
tap_ui 'Copy the device SSH public key' 15 || { record device-key FAIL "no Copy public key"; shot device-key-fail; exit 1; }
tap_ui '^Add host$' 15 || { record device-key FAIL "no Add host"; exit 1; }
tap_ui 'SSH hostname or IP' 15 || { record device-key FAIL "no host field"; exit 1; }
a shell input keyevent KEYCODE_PASTE
quiet 1
pub=$(wait_ui 'ssh-ed25519 ' 10 > /dev/null && $ui text "$EMU_OUT/ui.xml" 'ssh-ed25519 ') || { record device-key FAIL "public key not readable"; shot device-key-fail; exit 1; }
echo "restrict,pty $pub" > "$work/ssh/authorized_keys"
record device-key PASS "authorized"

# ---- connect and open agent A, then switch to B (this saves A's grid) ----
launch
open_agent() { # name
    tap_ui "Computer pr48host" 40 || return 1
    tap_ui 'pr48host.* agents?' 15 || true   # the machine entry connects it
    quiet 6
    # "Other agents" is folded at first.
    find_ui "$1" > /dev/null 2>&1 || tap_ui 'Other agents' 15 || true
    quiet 2
    tap_ui "$1" 30
}
if open_agent agent-a; then
    quiet 8
    shot a-open
    record connect PASS "agent-a open"
else
    shot connect-fail
    record connect FAIL "could not open agent-a (see connect-fail.png)"
    exit 1
fi
cols_now() { a logcat -d -s herdr-tap > "$EMU_OUT/herdr-tap.log" 2>&1; $ui cols "$EMU_OUT/herdr-tap.log"; }

# (a) recording controls at 46 and 98 columns
# Sets the display width so the renderer reports about `target` columns. The pixels per column differ
# by system image and density, so it is measured: start from a guess, then rescale from what the
# renderer reports (herdr-tap layout lines), a few times. Echoes the columns reached.
set_width() { # target-cols  (narrow: 44..48 is "cover-like", wide: 96..110)
    local target=$1 ppc=${PPC:-24} w c i lo hi
    if (( target <= 50 )); then lo=44; hi=48; else lo=96; hi=110; fi
    for i in 1 2 3 4 5 6; do
        w=$(python3 -c "print(max(500, min(3200, round($target * $ppc))))")
        a shell wm size "${w}x2000"
        quiet 4
        c=$(cols_now) || continue
        [[ "$c" =~ ^[0-9]+$ ]] || continue
        if (( c >= lo && c <= hi )); then PPC=$ppc; echo "$c"; return 0; fi
        ppc=$(python3 -c "print($w / $c)")
    done
    echo "${c:-?}"
    return 1
}
check_recording_controls() { # label
    local label=$1 cols before
    cols=$(cols_now || echo '?')
    before=$(partials)
    tap_ui 'Dictate; tap again to stop' 20 || { record "recording-$label" FAIL "no Talk control at $cols columns"; return; }
    # Connecting also shows cancel/send/type, so the controls prove nothing yet: wait until the fake
    # server has sent a partial (the session is LISTENING), then require the timer to tick.
    if ! wait_partial "$before" 25; then
        shot "recording-$label"
        record "recording-$label" FAIL "$cols columns: dictation never became active (no partial from the fake server; still connecting?)"
        tap_ui 'Stop and discard what was dictated' 10 || a shell input keyevent KEYCODE_BACK
        quiet 2
        return
    fi
    quiet 2
    shot "recording-$label-a"
    sleep 3
    shot "recording-$label"
    local moved
    moved=$($ui diff "$EMU_OUT/recording-$label-a.png" "$EMU_OUT/recording-$label.png" 40 100 2> /dev/null || echo 0)
    local missing=""
    if dump; then
        for need in 'Stop and discard what was dictated' 'Send, or press Enter' 'Type a message, or put the keyboard away'; do
            $ui find "$EMU_OUT/ui.xml" "$need" > /dev/null || missing="$missing [$need]"
        done
    else
        missing=" [no fresh UI dump]"
    fi
    if [[ -n "$missing" ]]; then
        record "recording-$label" FAIL "$cols columns, missing:$missing"
    elif python3 -c "import sys; sys.exit(0 if float('$moved') > 0 else 1)"; then
        record "recording-$label" PASS "$cols columns: recording is active (partials flowing, timer ticking: $moved of the lower rows changed in 3 s); cancel, send, type and stop are on the line"
    else
        record "recording-$label" FAIL "$cols columns: the controls are there but nothing moved in 3 s (no ticking timer: not recording)"
    fi
    # Cancel ends it.
    tap_ui 'Stop and discard what was dictated' 10 || a shell input keyevent KEYCODE_BACK
    quiet 2
}
wide=$(set_width 98) && record width-98 PASS "$wide columns" || record width-98 FAIL "got $wide columns"
check_recording_controls 98cols
narrow=$(set_width 46) && record width-46 PASS "$narrow columns" || record width-46 FAIL "got $narrow columns"
check_recording_controls 46cols
a shell wm size reset
quiet 4

# Which pane is on screen: the strip's active tab, "Window N, <name>, current".
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

# switch to B (A's grid is saved), then (b) the 'seed' + partial + 'z' case on B
quiet 4
if switch_to agent-b; then
    record switch-b PASS "agent-b is the active pane"
    B_OK=1
else
    record switch-b FAIL "agent-b did not become the active pane (switch-fail.png)"
    shot switch-fail
    B_OK=0
fi
shot b-open
stop_and_send() {
    # Talk, wait until the session is active, press Send DURING recording: the app stops, waits for
    # the final transcript and sends the draft. The final must arrive at agent-b.
    local before
    before=$(partials)
    tap_ui 'Dictate; tap again to stop' 20 || { record stop-and-send FAIL "no Talk control"; return; }
    if ! wait_partial "$before" 25; then
        record stop-and-send FAIL "dictation never became active (no partial from the fake server); Send was not exercised"
        tap_ui 'Stop and discard what was dictated' 10 || a shell input keyevent KEYCODE_BACK
        return
    fi
    quiet 2
    shot stop-and-send-recording
    tap_ui 'Send, or press Enter' 15 || { record stop-and-send FAIL "no Send control while recording"; return; }
    local end=$((SECONDS + 30)) got=0 screen
    while (( SECONDS < end )); do
        screen=$(agent_screen agent-b) || screen=
        # The echo of the sent final: a line holding "fixture partial" (the banner says "fixture B").
        if grep -v 'Claude Code (fixture B)' <<< "$screen" | grep -q 'fixture partial'; then got=1; break; fi
        sleep 2
    done
    echo "$screen" > "$EMU_OUT/stop-and-send-screen.txt"
    shot stop-and-send-after
    if (( got )) && grep -q 'sent final: fixture partial' "$EMU_OUT/dictation.log"; then
        record stop-and-send PASS "Send during recording stopped it, took the final transcript and sent it: agent-b received 'fixture partial'"
    else
        record stop-and-send FAIL "the final transcript did not reach agent-b (see stop-and-send-screen.txt, dictation.log)"
    fi
}

cancel_z() {
    # type 'seed', hide the keyboard with the hide control
    tap_ui 'Type a message, or put the keyboard away' 20 || { record cancel-z FAIL "no composer"; return; }
    quiet 2
    a shell input text seed
    quiet 1
    tap_ui 'Type a message, or put the keyboard away' 10   # now says hide
    quiet 2
    shot cancel-z-pre
    # What agent-b already shows (the stop-and-send final): only newer lines count below.
    agent_screen agent-b > "$EMU_OUT/agent-b-before-cancel.txt" 2> /dev/null || : > "$EMU_OUT/agent-b-before-cancel.txt"
    # dictate: the fake server says "fixture", then "fixture partial"
    local before
    before=$(partials)
    tap_ui 'Dictate; tap again to stop' 20 || { record cancel-z FAIL "no Talk control"; return; }
    if ! wait_partial "$before" 25; then
        shot cancel-z-dictating
        record cancel-z FAIL "the fake dictation server never sent the partial (harness connectivity, see dictation.log and logcat); Cancel was not exercised"
        tap_ui 'Stop and discard what was dictated' 10 || a shell input keyevent KEYCODE_BACK
        return
    fi
    quiet 3   # the partial reaches the draft
    shot cancel-z-dictating
    # The CURRENT draft must hold the partial before it is edited: the control line's Talk is
    # still recording and the draft row changed since the screenshot taken before the partial.
    # keyboard up, cursor to just before "fixture" (15 characters back), type 'z'
    tap_ui 'Type a message, or put the keyboard away' 15
    quiet 2
    for _ in $(seq 15); do a shell input keyevent KEYCODE_DPAD_LEFT; done
    a shell input text z
    quiet 1
    shot cancel-z-before
    local seen
    seen=$($ui diff "$EMU_OUT/cancel-z-pre.png" "$EMU_OUT/cancel-z-before.png" 60 100 2> /dev/null || echo 0)
    if ! python3 -c "import sys; sys.exit(0 if float('$seen') > 0 else 1)"; then
        record cancel-z FAIL "the draft area did not change after the partial and the typed text (no partial in the current draft); Cancel was not exercised"
        tap_ui 'Stop and discard what was dictated' 10 || a shell input keyevent KEYCODE_BACK
        return
    fi
    tap_ui 'Stop and discard what was dictated' 15 || { record cancel-z FAIL "no Cancel control"; return; }
    quiet 2
    shot cancel-z-after
    # send the draft; the agent (cat) echoes it into its terminal, which the core can read back
    tap_ui 'Send, or press Enter' 15
    quiet 4
    local screen body
    screen=$(agent_screen agent-b)
    echo "$screen" > "$EMU_OUT/agent-b-screen.txt"
    # Only what arrived after the draft was started, and not the banner "Claude Code (fixture B)".
    body=$(grep -vxF -f "$EMU_OUT/agent-b-before-cancel.txt" <<< "$screen" | grep -v 'Claude Code (fixture B)')
    if grep -Eq 'seed z *$' <<< "$body" && ! grep -qi 'fixture' <<< "$body"; then
        record cancel-z PASS "draft after Cancel was 'seed z': the typed z stayed, the dictated words went"
    else
        record cancel-z FAIL "expected a new line 'seed z' and no 'fixture' (see agent-b-screen.txt, cancel-z-*.png)"
    fi
}
if (( B_OK )); then
    stop_and_send
    cancel_z
else
    record stop-and-send SKIP "agent-b was not the active pane"
    record cancel-z SKIP "agent-b was not the active pane"
fi
# the keyboard may still be up; put it away
a shell input keyevent KEYCODE_BACK
quiet 2

# (c) offline: A's grid was saved when B was opened; cut the host, open A from the cache
stop_sshd
hc server stop > /dev/null 2>&1
quiet 8
shot offline-b
# Open A the way a person does with no link: restart the app (the saved grids are on disk) and pick
# the agent from Home's cached tree, which draws its saved copy. Switching tabs while the session is
# still retrying ("connecting") shows only a blank held surface, which is not what this checks.
launch
quiet 10
shot offline-home
tap_ui 'Computer pr48host' 20 > /dev/null 2>&1 || true
find_ui 'agent-a' > /dev/null 2>&1 || tap_ui 'Other agents' 10 || true
quiet 2
tap_ui 'agent-a' 25 || record offline-open FAIL "no agent-a on Home while offline"
quiet 8
shot offline-a-before-keyboard
base=$($ui blank "$EMU_OUT/offline-a-before-keyboard.png" 18 80)
if ! pane_is agent-a; then
    record offline-open FAIL "agent-a is not the active pane while offline"
elif python3 -c "import sys; sys.exit(0 if float('$base') < 0.9 else 1)"; then
    record offline-open PASS "a rendered saved grid: blank rows in the terminal band $base"
else
    record offline-open FAIL "no saved grid is drawn (blank rows $base): agent-a was probably not saved, so the checks below test a stand-in"
fi

keyboard_cycle() { # name how
    local name=$1 how=$2 y0 y1 y2 blank
    y0=$(wait_ui 'Type a message, or put the keyboard away' 10 | awk '{print $2}')
    tap_ui 'Type a message, or put the keyboard away' 10
    quiet 3
    ime_shown || { record "offline-$name" FAIL "the keyboard did not come up"; return; }
    y1=$(wait_ui 'Type a message, or put the keyboard away' 10 | awk '{print $2}')
    shot "offline-$name-up"
    case "$how" in
        hide) tap_ui 'Type a message, or put the keyboard away' 10 ;;
        back) a shell input keyevent KEYCODE_BACK ;;
        navkey) tap_ui '^(Back|Hide keyboard)$' 5 || { record "offline-$name" SKIP "no navigation-bar key (gesture navigation)"; a shell input keyevent KEYCODE_BACK; quiet 2; return; } ;;
    esac
    quiet 3
    y2=$(wait_ui 'Type a message, or put the keyboard away' 10 | awk '{print $2}')
    shot "offline-$name-down"
    blank=$($ui blank "$EMU_OUT/offline-$name-down.png" 18 80)
    local detail="composer target y: $y0 down, $y1 up, $y2 after; blank rows $blank (before $base)"
    if ime_shown; then
        record "offline-$name" FAIL "the keyboard is still up; $detail"
    elif [[ -z "$y0" || -z "$y2" ]] || (( y0 - y2 > 6 || y2 - y0 > 6 )); then
        record "offline-$name" FAIL "the composer did not return to its place; $detail"
    elif python3 -c "import sys; sys.exit(0 if float('$blank') <= float('$base') + 0.05 else 1)"; then
        record "offline-$name" PASS "$detail"
    else
        record "offline-$name" FAIL "a blank region appeared; $detail"
    fi
}
keyboard_cycle hide-control hide
keyboard_cycle back-key back
keyboard_cycle ime-nav-key navkey

shot final
a logcat -b all -d > "$EMU_OUT/logcat-test.log" 2>&1
echo "--- summary ---"
cat "$SUMMARY"
(( FAILS == 0 ))
