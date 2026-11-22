#!/usr/bin/env bash
# Shared setup for the emulator scripts here (toolbar.sh, beadtap.sh), from pr48.sh: a scratch
# herdr-core and sshd from the android-emu shell in a short private directory, a provisioned host
# "tbhost" at 10.0.2.2, and the device key created in the app and read back through its UI. The
# sourcing script defines fixtures() (its terminals; $work/home is the scratch HOME) before
# sourcing this, and continues on the Home screen once it returns.
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
# The app's own last layout hint (what the renderer drew): "ids: ... | cols N | draft CELLS".
layout_now() {
    a logcat -d -s herdr-tap > "$EMU_OUT/herdr-tap.log" 2>&1
    $ui layout "$EMU_OUT/herdr-tap.log"
}
# True when the hint's control ids include every id given (the recording state, on the app side).
has_ids() {
    local line; line=$(layout_now) || return 1
    local id
    for id in "$@"; do [[ " ${line#ids: } " == *" $id "* ]] || return 1; done
    return 0
}
draft_cells() { layout_now | sed -n 's/.*draft \([0-9]*\).*/\1/p'; }
# Waits until the draft is exactly N cells (seed + space + the partial) in the app's own layout.
wait_draft_cells() { # cells [seconds=20]
    local end=$((SECONDS + ${2:-20})) c
    while (( SECONDS < end )); do
        c=$(draft_cells)
        [[ "$c" == "$1" ]] && return 0
        sleep 1
    done
    return 1
}
# Waits until the draft is at least N cells: after a typed character the keyboard may add its own
# (an auto-space), so the exact count is judged by what the agent finally receives.
wait_draft_at_least() { # cells [seconds=20]
    local end=$((SECONDS + ${2:-20})) c
    while (( SECONDS < end )); do
        c=$(draft_cells)
        [[ "$c" =~ ^[0-9]+$ ]] && (( c >= $1 )) && return 0
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
wait_ime() { # seconds=12: the keyboard can take a few seconds to appear on a loaded guest
    local end=$((SECONDS + ${1:-12}))
    while (( SECONDS < end )); do ime_shown && return 0; sleep 1; done
    return 1
}
ime_shown() { a shell dumpsys input_method 2> /dev/null | grep -q 'mInputShown=true'; }

# ---- the scratch host: herdr-core + sshd from Nix, in a short private directory ----
work=$(mktemp -d /tmp/tb.XXXXXX)
mkdir -p "$work/home" "$work/run" "$work/ssh"
cat > "$work/env.sh" << ENV
export HOME=$work/home XDG_RUNTIME_DIR=$work/run XDG_CONFIG_HOME=$work/home/.config
export XDG_STATE_HOME=$work/home/.local/state XDG_DATA_HOME=$work/home/.local/share
export XDG_CACHE_HOME=$work/home/.cache HC_SESSION=tbar TMPDIR=$work/run SHELL=$(command -v sh) TERM=xterm-256color
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
    [[ -n "$mem_pid" ]] && kill "$mem_pid" 2> /dev/null
    stop_sshd
    hc server stop > /dev/null 2>&1 || true
    cp "$work/ssh/sshd.log" "$EMU_OUT/sshd.log" 2> /dev/null || true
    rm -rf -- "$work"
}
trap cleanup_host EXIT

start_sshd || { record setup FAIL "sshd did not start (see sshd.log)"; exit 1; }
# The caller's terminals and any files they need ($work is the scratch host's home dir).
fixtures || { record setup FAIL "fixtures (see hc-*.log)"; exit 1; }
# Memory: the job's cgroup (cap 6 GiB) and its biggest processes, every 5 s, so an OOM kill can be
# read off rss.log; the peak is recorded as a row at the end.
( while :; do printf '%s %s\n' "$(date +%T)" "$($ui mem 2> /dev/null)" >> "$EMU_OUT/rss.log"; sleep 5; done ) &
mem_pid=$!
record setup PASS "ssh $SSH_PORT"
# ---- the phone: provisioned host + settings, microphone, first launch ----
pkg=$("$ANDROID_HOME/build-tools/35.0.0/aapt2" dump badging "$EMU_APK" | sed -n "s/^package: name='\([^']*\)'.*/\1/p")
[[ -n "$pkg" ]] || { record setup FAIL "no package name"; exit 1; }
fp=$(ssh-keygen -lf "$work/ssh/host.pub" | awk '{print $2}')
cat > "$work/hosts.json" << JSON
{"format": "remote-for-herdr-hosts", "version": 1, "hosts": [{"transport": "tcp", "host": "10.0.2.2", "port": $SSH_PORT, "endpoint_id": "", "endpoint_ticket": "", "relay_url": "", "username": "$(id -un)", "session": "tbar", "expected_fingerprint": "$fp", "name": "tbhost", "shortcuts": "", "quickCommands": []}]}
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
