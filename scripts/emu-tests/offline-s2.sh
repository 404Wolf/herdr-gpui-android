#!/usr/bin/env bash
# Stage 2 of the phone offline design (offline transcripts) on the job's emulator.
#   lease-run --kind android --outputs <dir> -- scripts/emu-test.sh scripts/emu-tests/offline-s2.sh
# Needs the android-emu shell's scratch herdr-core to offer read-bounded and incarnation (the
# guarded-send patch); the setup checks that first. A scratch core and sshd run in the job, the app
# connects at 10.0.2.2, reads agent A's transcript while live, then the host is cut and the
# transcript is opened offline. summary.tsv rows: name, PASS|FAIL|SKIP, detail; exit 1 on a FAIL.
set -uo pipefail

: "${ADB_SERIAL:?Run through scripts/emu-test.sh}"
: "${EMU_OUT:?Run through scripts/emu-test.sh}"
: "${EMU_APK:?Run through scripts/emu-test.sh}"
here=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
ui="python3 $here/ui.py"
SUMMARY="$EMU_OUT/summary.tsv"
: > "$SUMMARY"
FAILS=0
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
work=$(mktemp -d /tmp/os2.XXXXXX)
mkdir -p "$work/home" "$work/run" "$work/ssh"
cat > "$work/env.sh" << ENV
export HOME=$work/home XDG_RUNTIME_DIR=$work/run XDG_CONFIG_HOME=$work/home/.config
export XDG_STATE_HOME=$work/home/.local/state XDG_DATA_HOME=$work/home/.local/share
export XDG_CACHE_HOME=$work/home/.cache HC_SESSION=offs2 TMPDIR=$work/run SHELL=$(command -v sh) TERM=xterm-256color
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
# The core must offer what stage 2 builds on; an old core keeps today's behaviour and is not this test.
hc server status > "$EMU_OUT/core-status.json" 2>&1
if grep -q '"read-bounded"' "$EMU_OUT/core-status.json" && grep -q '"incarnation"' "$EMU_OUT/core-status.json"; then
    record core-features PASS "the scratch core offers read-bounded and incarnation"
else
    record core-features FAIL "the scratch core lacks read-bounded/incarnation (see core-status.json)"
    exit 1
fi
# Agent A prints 300 numbered lines (a transcript longer than one screen); B is a plain prompt.
hc run --bg --label agent.kind=claude --name agent-a -- sh -c \
    'i=0; while [ $i -lt 300 ]; do printf "transcript line %03d\n" $i; i=$((i + 1)); done; printf "> "; exec cat' \
    > "$EMU_OUT/hc-a.log" 2>&1 || { record setup FAIL "hc run A (see hc-a.log)"; exit 1; }
hc run --bg --label agent.kind=claude --name agent-b -- sh -c 'printf "Claude Code (fixture B)\n\n> "; exec cat' \
    > "$EMU_OUT/hc-b.log" 2>&1 || { record setup FAIL "hc run B (see hc-b.log)"; exit 1; }
hc ls > "$EMU_OUT/terminals.json" 2>&1
mem_pid=
record setup PASS "ssh $SSH_PORT"

# ---- the phone: provisioned host + settings, microphone, first launch ----
pkg=$("$ANDROID_HOME/build-tools/35.0.0/aapt2" dump badging "$EMU_APK" | sed -n "s/^package: name='\([^']*\)'.*/\1/p")
[[ -n "$pkg" ]] || { record setup FAIL "no package name"; exit 1; }
fp=$(ssh-keygen -lf "$work/ssh/host.pub" | awk '{print $2}')
cat > "$work/hosts.json" << JSON
{"format": "remote-for-herdr-hosts", "version": 1, "hosts": [{"transport": "tcp", "host": "10.0.2.2", "port": $SSH_PORT, "endpoint_id": "", "endpoint_ticket": "", "relay_url": "", "username": "$(id -un)", "session": "offs2", "expected_fingerprint": "$fp", "name": "offs2host", "shortcuts": "", "quickCommands": []}]}
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
    tap_ui "Computer offs2host" 40 || return 1
    tap_ui 'offs2host.* agents?' 15 || true   # the machine entry connects it
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

launch
if open_agent agent-a; then
    quiet 8
    shot a-open
    record connect PASS "agent-a open"
else
    shot connect-fail
    record connect FAIL "could not open agent-a (see connect-fail.png)"
    exit 1
fi

# (1) Live: the pane on screen's transcript is read with the bounded read and kept, keyed by
# machine, terminal and incarnation; nothing is shown while the pane is live.
kept=
end=$((SECONDS + 60))
while (( SECONDS < end )); do
    kept=$(a shell "run-as $pkg sh -c 'cat no_backup/transcripts-v1/*/t*.txt 2>/dev/null'" 2> /dev/null | tr -d '\r')
    grep -q 'transcript line 299' <<< "$kept" && break
    sleep 3
done
printf '%s\n' "$kept" > "$EMU_OUT/transcript-kept.txt"
a shell "run-as $pkg sh -c 'ls -l no_backup/transcripts-v1/*/'" > "$EMU_OUT/transcript-files.txt" 2>&1
# The list's summary has no incarnation; the terminal's own record does.
addr_a=$(hc ls 2> /dev/null | python3 -c 'import json,sys; d=json.load(sys.stdin); print(next(t["address"] for t in d["terminals"] if t.get("name")=="agent-a"))' 2> /dev/null)
hc get "$addr_a" > "$EMU_OUT/agent-a-record.json" 2>&1
incarnation=$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1])).get("incarnation") or "")' "$EMU_OUT/agent-a-record.json" 2> /dev/null)
if grep -q 'transcript line 299' <<< "$kept" && head -1 <<< "$kept" | grep -q "\"incarnation\":\"$incarnation\""; then
    record transcript-read PASS "kept while live, headed with agent-a's incarnation $incarnation (transcript-files.txt)"
else
    record transcript-read FAIL "no transcript of agent-a with incarnation '$incarnation' within 60 s (transcript-kept.txt)"
fi
if find_ui 'transcript · as of' > /dev/null; then
    record live-hides FAIL "the offline transcript button shows while the pane is live"
else
    record live-hides PASS "no transcript button while live"
fi

# (2) Offline: the host is cut; the pane's transcript is offered with its time, opens at its
# newest line, scrolls back to its oldest, and says it may be outdated.
stop_sshd
hc server stop > /dev/null 2>&1
quiet 10
shot offline-indicator
if hit=$(wait_ui 'transcript · as of' 30); then
    shot offline-button
    record offline-offer PASS "$($ui text "$EMU_OUT/ui.xml" 'transcript · as of' 2> /dev/null)"
else
    record offline-offer FAIL "no transcript button for agent-a offline (offline-indicator.png)"
fi
# The corner says "offline" with no network, "connecting" while it retries a host that is down.
if find_ui '. (offline|connecting) · ' > /dev/null; then
    record offline-indicator PASS "$($ui text "$EMU_OUT/ui.xml" '. (offline|connecting) · ' 2> /dev/null)"
else
    record offline-indicator FAIL "no offline indicator (offline-indicator.png)"
fi
if tap_ui 'transcript · as of' 10 && wait_ui '· transcript as of .* may be outdated' 15 > /dev/null; then
    quiet 2
    shot transcript-newest
    if find_ui 'transcript line 299' > /dev/null; then
        record transcript-open PASS "titled '$($ui text "$EMU_OUT/ui.xml" 'transcript as of' 2> /dev/null)'; holds line 299"
    else
        record transcript-open FAIL "the transcript screen does not hold the newest line (transcript-newest.png)"
    fi
    for n in 1 2 3 4 5 6 7 8 9 10 11 12; do a shell input swipe 540 700 540 2000 150; done
    quiet 2
    shot transcript-oldest
    # The text is one view, so the dump holds all of it however far it is scrolled: the scroll is
    # judged by the screen itself changing, and the oldest line by the text that was kept.
    if ! cmp -s "$EMU_OUT/transcript-newest.png" "$EMU_OUT/transcript-oldest.png" \
        && grep -q 'transcript line 000' "$EMU_OUT/transcript-kept.txt"; then
        record transcript-scroll PASS "the view scrolled back (transcript-newest.png vs transcript-oldest.png); the kept text starts at line 000"
    else
        record transcript-scroll FAIL "the view did not scroll, or the kept text lacks line 000"
    fi
    a shell input keyevent KEYCODE_BACK
    quiet 2
else
    shot transcript-open-fail
    record transcript-open FAIL "tapping the button did not open the transcript (transcript-open-fail.png)"
fi

# (3) A pending switch: B's tab is tapped and, before B's lookup can answer, the place where A's
# button was is tapped at once. A's transcript must never open for B (review of #55, finding 1).
abtn=$(find_ui 'transcript · as of') || abtn=""
btab=$(find_ui "Window [0-9]+, agent-b[.,]") || btab=""
if [[ -n "$abtn" && -n "$btab" ]]; then
    read -r ax ay <<< "$abtn"
    read -r bx by <<< "$btab"
    a shell "input tap $bx $by; input tap $ax $ay; input tap $ax $ay"
    quiet 3
    shot pending-switch
    if pane_is agent-b && find_ui 'transcript line 299' > /dev/null; then
        record pending-switch FAIL "agent-a's transcript opened with agent-b selected (pending-switch.png)"
    elif find_ui 'transcript line 299' > /dev/null; then
        record pending-switch PASS "the tap landed before the switch: agent-a's own transcript, with agent-a still selected"
    else
        record pending-switch PASS "no agent-a transcript after a tap during the switch to agent-b"
    fi
    # Close whatever opened, without opening the rail when nothing did. The dialog's title is
    # '<pane> · transcript as of …'; the button's own description ('Read <pane>'s transcript as of
    # …') must not count, or Back with no dialog open opens the rail.
    find_ui '· transcript as of .* may be outdated' > /dev/null && a shell input keyevent KEYCODE_BACK
    quiet 2
else
    record pending-switch SKIP "no A button or B tab on screen to tap (offline-button.png)"
fi

# (4) Another pane: B's transcript, if read in the background, is B's alone; A's is never shown
# for B. B's dialog must carry B's title and fixture text and none of A's.
if switch_to agent-b; then
    quiet 4
    shot offline-b
    if find_ui 'transcript · as of' > /dev/null; then
        if tap_ui 'transcript · as of' 5 > /dev/null && wait_ui 'agent-b · transcript as of .* may be outdated' 10 > /dev/null; then
            shot b-dialog
            cp "$EMU_OUT/ui.xml" "$EMU_OUT/b-dialog.xml"
            if find_ui 'fixture B' > /dev/null && ! find_ui 'transcript line 299' > /dev/null; then
                record other-pane PASS "B's dialog with fixture B text and none of A's (b-dialog.png)"
            else
                record other-pane FAIL "B's dialog lacks fixture B text or shows A's (b-dialog.png, b-dialog.xml)"
            fi
            a shell input keyevent KEYCODE_BACK
        else
            shot other-pane-fail
            record other-pane FAIL "B's offer did not open a dialog titled for agent-b (other-pane-fail.png)"
        fi
    else
        record other-pane PASS "no transcript offered for agent-b (none read for it)"
    fi
else
    record other-pane SKIP "no agent-b tab offline"
fi

shot final
a logcat -b all -d > "$EMU_OUT/logcat-test.log" 2>&1
echo "--- summary ---"
cat "$SUMMARY"
(( FAILS == 0 ))
