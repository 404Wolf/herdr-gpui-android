#!/usr/bin/env bash
# How fast ⋮ › Usage opens (factory-setup.32), timed: the first open, a second one, and one after the
# app was stopped; then how often it asks the machine while the rail shows, and that it stops when the
# app is in the background. The scratch host runs a fake `aswap` (synthetic accounts, read-only).
#   lease-run --kind android --outputs <dir> -- scripts/emu-test.sh scripts/emu-tests/usage.sh
# timings.tsv: open, ms from the tap until the accounts showed, as this script saw it; the app's own
# herdr-ops lines (where a look's time went) are in herdr-ops.log. Exit 1 if any check FAILed.

fake_aswap() {
    mkdir -p "$work/bin"
    cat > "$work/bin/aswap" << 'ASWAP'
#!/bin/sh
[ "$1 $2" = "list --json" ] || exit 2
now=$(date +%s)
printf '%s\n' "{\"schema_version\":1,\"auto\":{\"claude\":{\"enabled\":true,\"threshold\":90}},\"accounts\":[{\"label\":\"demo-a\",\"provider\":\"claude\",\"active\":true,\"usage_status\":\"ok\",\"remaining_percent\":40,\"fetched_at\":$((now - 120)),\"usage\":{\"windows\":[{\"name\":\"five_hour\",\"duration_minutes\":300,\"used_percent\":60,\"resets_at\":$((now + 3600))},{\"name\":\"seven_day\",\"duration_minutes\":10080,\"used_percent\":84,\"resets_at\":$((now + 200000))}]}},{\"label\":\"demo-c\",\"provider\":\"codex\",\"active\":false,\"usage_status\":\"ok\",\"fetched_at\":$((now - 60)),\"usage\":{\"windows\":[{\"name\":\"window_300m\",\"duration_minutes\":300,\"used_percent\":12,\"resets_at\":$((now + 9000))}]}}]}"
ASWAP
    chmod +x "$work/bin/aswap"
}

fixtures() {
    fake_aswap
    # The core's machine.exec runs with the core's PATH: the fake aswap must be on it when it starts.
    export PATH="$work/bin:$PATH"
    hc run --bg --label agent.kind=claude --name lobby-shell -- sh -c 'printf "Lobby shell\n\n> "; exec cat' \
        > "$EMU_OUT/hc-lobby.log" 2>&1 || return 1
}
here=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
# shellcheck source=lib-host.sh
. "$here/lib-host.sh"

now_ms() { date +%s%3N; }
TIMINGS="$EMU_OUT/timings.tsv"
: > "$TIMINGS"

open_agent() {
    launch
    # A restart may come back straight to the terminal it showed last.
    if wait_ui 'Window [0-9]+, lobby-shell, current' 8 > /dev/null; then quiet 5; return 0; fi
    tap_ui "Computer tbhost" 40 > /dev/null || return 1
    tap_ui 'tbhost.* agents?' 15 > /dev/null || true
    quiet 6
    find_ui 'lobby-shell' > /dev/null 2>&1 || tap_ui 'Other agents' 15 > /dev/null || true
    quiet 2
    tap_ui 'lobby-shell' 30 > /dev/null || return 1
    quiet 10
}

# Opens ⋮ › Usage and times the tap until demo-a's row shows (polled through uiautomator, so a
# couple of hundred ms coarse; the app's own lines in herdr-ops.log are exact).
open_usage() { # label
    local label=$1 t0 t1
    tap_ui 'Panes, hosts and terminal actions' 20 > /dev/null || { record "$label" FAIL "no ⋮"; return 1; }
    quiet 1
    hit=$(wait_ui '^Usage$' 10) || { shot "$label-nomenu"; record "$label" FAIL "no Usage in ⋮"; return 1; }
    read -r x y <<< "$hit"
    a logcat -c
    t0=$(now_ms)
    a shell input tap "$x" "$y"
    if wait_ui 'demo-a, claude' 30 > /dev/null; then
        t1=$(now_ms)
        printf '%s\t%s\n' "$label" "$((t1 - t0))" >> "$TIMINGS"
        shot "$label"
        record "$label" PASS "accounts after $((t1 - t0)) ms ($label.png)"
    else
        shot "$label"
        record "$label" FAIL "no accounts within 30 s ($label.png)"
    fi
    quiet 3
    a logcat -d -s herdr-ops >> "$EMU_OUT/herdr-ops.log" 2>&1
    echo "--- after $label" >> "$EMU_OUT/herdr-ops.log"
}
close_usage() { a shell input keyevent KEYCODE_BACK; quiet 2; }
looks_in() { # seconds -> how many usage.look lines the app logged in that window
    a logcat -c
    sleep "$1"
    a logcat -d -s herdr-ops > "$EMU_OUT/looks.tmp" 2>&1
    cat "$EMU_OUT/looks.tmp" >> "$EMU_OUT/herdr-ops.log"
    count 'usage.look ' "$EMU_OUT/looks.tmp"
}

open_agent || { shot connect-fail; record connect FAIL "could not open lobby-shell"; exit 1; }
record connect PASS "lobby-shell open"

open_usage open-first
n=$(looks_in 25)
record sheet-cadence INFO "$n looks in 25 s with the sheet open"
close_usage
open_usage open-second
close_usage
# The app stopped and started again: what it kept, if anything, shows first.
open_agent || { record relaunch FAIL "could not reopen lobby-shell"; exit 1; }
open_usage open-after-restart
close_usage

# The rail on the inner screen is docked, so it shows: does anything ask while only it is visible?
a shell wm size 2208x1840
quiet 10
n=$(looks_in 25)
record rail-cadence INFO "$n looks in 25 s with only the rail visible"
shot rail-inner
a shell input keyevent KEYCODE_HOME
quiet 3
n=$(looks_in 25)
if (( n == 0 )); then record background PASS "no looks in 25 s in the background"
else record background FAIL "$n looks in 25 s in the background"; fi
a shell wm size reset

if [[ -s "$EMU_OUT/anr.log" ]]; then
    record anr WARN "$(wc -l < "$EMU_OUT/anr.log") ANR dialog(s) were dismissed with Wait during the run (anr.log)"
fi
a logcat -b all -d > "$EMU_OUT/logcat-test.log" 2>&1
echo "--- timings ---"
cat "$TIMINGS"
echo "--- summary ---"
cat "$SUMMARY"
(( FAILS == 0 ))
