#!/usr/bin/env bash
# factory-setup.33 (answering an agent's question dialog from the phone) on the job's emulator.
#   lease-run --kind android --outputs <dir> -- scripts/emu-test.sh scripts/emu-tests/elicit.sh
# A scratch herdr-core and sshd as in pr48.sh; one agent, fake-ask.py, draws Claude Code's
# AskUserQuestion dialog (two questions and a Submit tab) and reports it as blocked. The app must
# open its answer sheet by itself, send nothing while the answers are picked except the tab
# switch the user taps, and on Submit enter exactly those answers. Screenshots and summary.tsv
# go to $EMU_OUT; the exit status is 1 if any check FAILed.
set -uo pipefail

: "${ADB_SERIAL:?Run through scripts/emu-test.sh}"
: "${EMU_OUT:?Run through scripts/emu-test.sh}"
: "${EMU_APK:?Run through scripts/emu-test.sh}"
here=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
ui="python3 $here/ui.py"
SUMMARY="$EMU_OUT/summary.tsv"
: > "$SUMMARY"
FAILS=0

# The harness below (adb wrappers, UI lookups, the scratch host, the device key) is pr48.sh's.
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
# Through a device file: exec-out screencap gave empty PNGs on this job's guest.
shot() {
    a shell screencap -p "/sdcard/$1.png" >> "$EMU_OUT/shot.log" 2>&1
    a pull "/sdcard/$1.png" "$EMU_OUT/$1.png" >> "$EMU_OUT/shot.log" 2>&1
    a shell rm -f "/sdcard/$1.png" > /dev/null 2>&1
    return 0
}
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
work=$(mktemp -d /tmp/el.XXXXXX)
mkdir -p "$work/home" "$work/run" "$work/ssh"
cat > "$work/env.sh" << ENV
export HOME=$work/home XDG_RUNTIME_DIR=$work/run XDG_CONFIG_HOME=$work/home/.config
export XDG_STATE_HOME=$work/home/.local/state XDG_DATA_HOME=$work/home/.local/share
export XDG_CACHE_HOME=$work/home/.cache HC_SESSION=elicit TMPDIR=$work/run SHELL=$(command -v sh) TERM=xterm-256color
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
: > "$work/keys.log"
hc run --bg --label agent.kind=claude --name agent-q -- python3 "$here/fake-ask.py" "$work/keys.log" \
    > "$EMU_OUT/hc-q.log" 2>&1 || { record setup FAIL "hc run agent-q (see hc-q.log)"; exit 1; }
# agent-r asks only once $work/ask-r exists (after a draft is in the composer); agent-s at once.
: > "$work/keys-r.log"
hc run --bg --label agent.kind=claude --name agent-r -- python3 "$here/fake-ask.py" "$work/keys-r.log" "$work/ask-r" \
    > "$EMU_OUT/hc-r.log" 2>&1 || { record setup FAIL "hc run agent-r (see hc-r.log)"; exit 1; }
hc run --bg --label agent.kind=claude --name agent-s -- python3 "$here/fake-ask.py" "$work/keys-s.log" \
    > "$EMU_OUT/hc-s.log" 2>&1 || { record setup FAIL "hc run agent-s (see hc-s.log)"; exit 1; }
( while :; do printf '%s %s\n' "$(date +%T)" "$($ui mem 2> /dev/null)" >> "$EMU_OUT/rss.log"; sleep 5; done ) &
mem_pid=$!
record setup PASS "ssh $SSH_PORT"

# ---- the phone: provisioned host, first launch ----
pkg=$("$ANDROID_HOME/build-tools/35.0.0/aapt2" dump badging "$EMU_APK" | sed -n "s/^package: name='\([^']*\)'.*/\1/p")
[[ -n "$pkg" ]] || { record setup FAIL "no package name"; exit 1; }
fp=$(ssh-keygen -lf "$work/ssh/host.pub" | awk '{print $2}')
cat > "$work/hosts.json" << JSON
{"format": "remote-for-herdr-hosts", "version": 1, "hosts": [{"transport": "tcp", "host": "10.0.2.2", "port": $SSH_PORT, "endpoint_id": "", "endpoint_ticket": "", "relay_url": "", "username": "$(id -un)", "session": "elicit", "expected_fingerprint": "$fp", "name": "elichost", "shortcuts": "", "quickCommands": []}]}
JSON
files=/sdcard/Android/data/$pkg/files
a shell mkdir -p "$files"
a push "$work/hosts.json" "$files/remote-for-herdr-hosts.json" > /dev/null
# Screenshots allowed: without it the app (and the sheet) set FLAG_SECURE and every shot is empty.
echo '{"format":"remote-for-herdr-settings","version":1,"capturable":true}' > "$work/settings.json"
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

# ---- connect and open the asking agent ----
launch
open_agent() { # name
    tap_ui "Computer elichost" 40 || return 1
    tap_ui 'elichost.* agents?' 15 || true   # the machine entry connects it
    quiet 6
    # "Other agents" is folded at first.
    find_ui "$1" > /dev/null 2>&1 || tap_ui 'Other agents' 15 || true
    quiet 2
    tap_ui "$1" 30
}
# Keys the agent received, one per line (fake-ask.py's log).
received() { cat "$work/keys.log" 2> /dev/null; }
if open_agent agent-q; then
    record connect PASS "agent-q open"
else
    shot connect-fail
    record connect FAIL "could not open agent-q (see connect-fail.png)"
    exit 1
fi

# (a) The sheet opens by itself on the blocked question, and opening it sent nothing.
if wait_ui 'Claude asks' 30 > /dev/null; then
    shot sheet-open
    if [[ -z "$(received)" ]]; then
        record sheet-open PASS "the sheet opened on 'Which color?' and the agent received no key"
    else
        record sheet-open FAIL "keys reached the agent before any tap: $(received | tr '\n' ' ')"
    fi
else
    shot sheet-missing
    agent_screen agent-q > "$EMU_OUT/agent-q-screen.txt" 2>&1 || true
    record sheet-open FAIL "no answer sheet within 30 s (sheet-missing.png, agent-q-screen.txt)"
    exit 1
fi

# (b) Picking an option changes only the sheet.
tap_ui '^Green$' 15 > /dev/null || record pick FAIL "no Green option in the sheet"
quiet 2
if wait_ui 'Selected: Green' 10 > /dev/null && [[ -z "$(received)" ]]; then
    record pick PASS "Green is selected in the sheet; the agent received nothing"
else
    record pick FAIL "Green not selected, or keys reached the agent: $(received | tr '\n' ' ')"
fi

# (c) The Toppings tab: a tab switch is the one key sent before Submit.
tap_ui 'Question tab Toppings' 15 > /dev/null || record tab FAIL "no Toppings tab chip"
if wait_ui 'Which toppings' 15 > /dev/null; then
    keys=$(received | tr '\n' ' ')
    if [[ "$keys" == "right " ]]; then
        record tab PASS "the sheet shows 'Which toppings?' after sending only: right"
    else
        record tab FAIL "expected only 'right' before Submit, the agent received: $keys"
    fi
else
    shot tab-fail
    record tab FAIL "the sheet did not follow the agent to 'Which toppings?' (tab-fail.png)"
fi
tap_ui '^Cheese$' 15 > /dev/null || record pick-multi FAIL "no Cheese option"
quiet 1
tap_ui 'Type your own answer' 15 > /dev/null || record custom FAIL "no custom answer field"
wait_ime 12 || true
a shell input text pepper
quiet 1
# Hide the keyboard; Back with no keyboard up would close the sheet itself.
ime_shown && a shell input keyevent KEYCODE_BACK
quiet 2
shot sheet-filled
before=$(received | wc -l)

# (d) Submit enters exactly those answers.
tap_ui 'Submit the answers to the agent' 15 > /dev/null || record submit FAIL "no Submit button"
end=$((SECONDS + 40)) screen=
while (( SECONDS < end )); do
    screen=$(agent_screen agent-q) || screen=
    grep -q 'ANSWERS' <<< "$screen" && break
    sleep 2
done
echo "$screen" > "$EMU_OUT/agent-q-after.txt"
dump > /dev/null 2>&1 && cp "$EMU_OUT/ui.xml" "$EMU_OUT/after-submit-ui.xml"
a logcat -d -s herdr-elicit > "$EMU_OUT/herdr-elicit.log" 2>&1
received > "$EMU_OUT/keys.log"
shot after-submit
if grep -q 'ANSWERS color=Green toppings=Cheese,pepper' <<< "$screen"; then
    record submit PASS "the agent got color=Green toppings=Cheese,pepper in $(( $(received | wc -l) - before )) keys (keys.log)"
else
    record submit FAIL "the agent's screen after Submit (agent-q-after.txt, keys.log): $(grep -m1 'ANSWERS\|CANCELLED\|CHAT' <<< "$screen")"
fi
quiet 3
if find_ui 'Claude asks|review your answers' > /dev/null 2>&1; then
    record sheet-closed FAIL "the sheet is still open after the answers went in"
else
    record sheet-closed PASS "the sheet closed after the dialog was answered"
fi

# (e) Never over typing: a dialog that appears while the composer holds a draft waits for it.
sheet_up() { find_ui '(Claude|Codex|Gemini) asks|review your answers' > /dev/null 2>&1; }
# Another agent (a relaunch reopens the last pane, not Home).
# Done only when the strip marks the pane current (or its sheet is up: the sheet is modal and
# hides the strip from the dump), so nothing later runs against the wrong pane. Each way waits
# for the switch to land before the next is tried: the tab chip, the rail row (a wide screen),
# then the overflow menu's list of panes ('Spaces & panes', 'Panes' in older builds).
pane_is() { dump && $ui find "$EMU_OUT/ui.xml" "Window [0-9]+, $1, (blocked, )?current" > /dev/null; }
switched() { # name: up to 10 s
    local end=$((SECONDS + 10))
    while (( SECONDS < end )); do
        pane_is "$1" && return 0
        sheet_up && return 0
        sleep 1
    done
    return 1
}
switch_to() { # name
    sheet_up && a shell input keyevent KEYCODE_BACK && quiet 2
    pane_is "$1" && return 0
    tap_ui "Window [0-9]+, $1[.,]" 8 > /dev/null && switched "$1" && return 0
    tap_ui "Focus pane $1" 5 > /dev/null && switched "$1" && return 0
    if tap_ui 'Panes, hosts and terminal actions' 10 > /dev/null \
        && tap_ui '^(Spaces & panes|Panes)$' 10 > /dev/null; then
        quiet 2
        tap_ui "$1" 15 > /dev/null && switched "$1" && return 0
    fi
    # One more tab tap: a busy guest can drop the first.
    tap_ui "Window [0-9]+, $1[.,]" 8 > /dev/null && switched "$1" && return 0
    shot "switch-$1-fail"
    $ui list "$EMU_OUT/ui.xml" > "$EMU_OUT/switch-$1-fail.txt" 2>&1
    return 1
}
if switch_to agent-r; then
    quiet 4
    tap_ui 'Type a message, or put the keyboard away' 20 > /dev/null || record wait-draft FAIL "no composer field"
    wait_ime 12 || true
    a shell input text 'draft'
    quiet 1
    touch "$work/ask-r"
    # The agent now asks; the keyboard is up and the composer holds 'draft'.
    quiet 8
    shot wait-typing
    if sheet_up; then
        record wait-typing FAIL "the sheet opened over the keyboard and a draft"
    else
        record wait-typing PASS "with the keyboard up and a draft, the dialog did not open the sheet"
    fi
    # Keyboard away, the draft stays: still waits.
    tap_ui 'Type a message, or put the keyboard away' 10 > /dev/null || { ime_shown && a shell input keyevent KEYCODE_BACK; }
    quiet 6
    if sheet_up; then
        record wait-draft FAIL "the sheet opened while the composer still held a draft"
    else
        record wait-draft PASS "with the keyboard away but a draft kept, the sheet still waited"
    fi
    # Clear the draft in the composer; then the sheet opens by itself. Retried: a link drop in
    # the job (it reconnects) can take the composer's focus between the tap and the deletes.
    for _ in 1 2 3; do
        ime_shown || tap_ui 'Type a message, or put the keyboard away' 10 > /dev/null || true
        wait_ime 12 || true
        for _ in 1 2 3 4 5 6 7 8 9 10; do a shell input keyevent KEYCODE_DEL; done
        tap_ui 'Type a message, or put the keyboard away' 10 > /dev/null || { ime_shown && a shell input keyevent KEYCODE_BACK; }
        wait_ui 'Claude asks' 8 > /dev/null && break
    done
    if wait_ui 'Claude asks' 20 > /dev/null; then
        if [[ -s "$work/keys-r.log" ]]; then
            record wait-then-open FAIL "the sheet opened but keys reached agent-r: $(tr '\n' ' ' < "$work/keys-r.log")"
        else
            record wait-then-open PASS "once the draft was gone and the keyboard down, the sheet opened; agent-r received no key"
        fi
    else
        shot wait-then-open
        record wait-then-open FAIL "no sheet within 20 s after the draft was cleared (wait-then-open.png)"
    fi
    a shell input keyevent KEYCODE_BACK   # close the sheet: the dialog stays on the terminal
    quiet 2
else
    record wait-typing FAIL "could not open agent-r"
fi

# (f) The inner screen of a Fold, 884 x 1050 dp (1326 x 1575 px at density 240).
a shell wm size 1326x1575
a shell wm density 240
quiet 6
# Opening an agent that waits on a question raises the keyboard by itself (autoKeyboardPane).
# That keyboard is for the answer, so it must not hold the sheet back, and the sheet puts it away.
if switch_to agent-s && wait_ui 'Claude asks' 30 > /dev/null; then
    quiet 2
    if ime_shown; then
        record auto-keyboard FAIL "the sheet opened but the keyboard the app raised is still up"
    else
        record auto-keyboard PASS "the keyboard raised for the waiting agent did not hold the sheet back, and is away"
    fi
    shot sheet-inner
    tap_ui 'Question tab Toppings' 15 > /dev/null && wait_ui 'Which toppings' 15 > /dev/null && { quiet 2; shot sheet-inner-multi; }
    record inner PASS "the sheet opened on the 884 x 1050 dp screen (sheet-inner.png, sheet-inner-multi.png)"
else
    shot inner-fail
    record inner FAIL "no sheet on the 884 x 1050 dp screen (inner-fail.png)"
fi
a shell wm size reset
a shell wm density reset

for png in "$EMU_OUT"/*.png; do
    [[ -s "$png" ]] || record screenshots FAIL "$(basename "$png") is empty"
done

echo "$FAILS failure(s)"
(( FAILS == 0 ))
