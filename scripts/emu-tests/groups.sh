#!/usr/bin/env bash
# The rail's named groups (factory-r8f.6): PMs labelled herdr.group=<name> sit in that group, after the
# Deacon and above the repositories; a PM without the label keeps its place; a label set while the
# app runs moves its PM, and the group is still there after the app restarts. Shot at both widths.
#   lease-run --kind android --outputs <dir> -- scripts/emu-test.sh scripts/emu-tests/groups.sh
# Screenshots and summary.tsv go to $EMU_OUT; the exit status is 1 if any check FAILed.

# Each PM in a space of its own (herdr.space; the layout need not list it): a PM in no space is
# listed under Other agents, not as a project.
fixtures() {
    local p
    for p in demo web solo; do
        mkdir -p "$work/home/$p"
    done
    hc run --bg --cwd "$work/home/demo" --label agent.kind=claude --label role=pm --label project=demo --label herdr.space=sp-demo --label herdr.tab=pm \
        --label herdr.group=herdr --name demo-pm -- sh -c 'printf "demo PM\n\n> "; exec cat' > "$EMU_OUT/hc-demo.log" 2>&1 || return 1
    hc run --bg --cwd "$work/home/web" --label agent.kind=claude --label role=pm --label project=web --label herdr.space=sp-web --label herdr.tab=pm \
        --label herdr.group=herdr --name web-pm -- sh -c 'printf "web PM\n\n> "; exec cat' > "$EMU_OUT/hc-web.log" 2>&1 || return 1
    solo=$(hc run --bg --cwd "$work/home/solo" --label agent.kind=claude --label role=pm --label project=solo --label herdr.space=sp-solo --label herdr.tab=pm \
        --name solo-pm -- sh -c 'printf "solo PM\n\n> "; exec cat' 2> "$EMU_OUT/hc-solo.log" \
        | python3 -c 'import sys, json; print(json.load(sys.stdin)["address"])') || return 1
    [[ -n "$solo" ]] || return 1
    echo "$solo" > "$work/solo.addr"
    # A way in from Home that needs no PM tree (as railnav.sh).
    hc run --bg --label agent.kind=claude --name lobby-shell -- sh -c 'printf "Lobby shell\n\n> "; exec cat' \
        > "$EMU_OUT/hc-lobby.log" 2>&1 || return 1
}
here=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
# shellcheck source=lib-host.sh
. "$here/lib-host.sh"

open_pm() {
    launch
    if wait_ui 'Window [0-9]+, lobby-shell, current' 8 > /dev/null; then quiet 5; return 0; fi
    tap_ui "Computer tbhost" 40 > /dev/null || return 1
    tap_ui 'tbhost.* agents?' 15 > /dev/null || true
    quiet 6
    find_ui 'lobby-shell' > /dev/null 2>&1 || tap_ui 'Other agents' 15 > /dev/null || true
    quiet 2
    tap_ui 'lobby-shell' 30 > /dev/null || return 1
    quiet 10
}
open_rail() {
    find_ui '^herdr$' > /dev/null 2>&1 || tap_ui 'Show the host and workspace sidebar|Show it in the rail' 20 > /dev/null || true
    quiet 3
}
# The group heading's row and whether it lists $2..; solo's place (not in the group) is checked by order.
check() { # label
    local label=$1
    open_rail
    shot "$label"
    dump > /dev/null 2>&1 || true
    cp "$EMU_OUT/ui.xml" "$EMU_OUT/ui-$label.xml" 2> /dev/null
    if $ui list "$EMU_OUT/ui.xml" > "$EMU_OUT/$label.txt" 2>&1 && grep -q 'herdr, 2 projects\|herdr, 3 projects' "$EMU_OUT/$label.txt"; then
        record "$label-group" PASS "the herdr group heads its PMs ($label.png)"
    else
        record "$label-group" FAIL "no herdr group ($label.png, $label.txt)"
    fi
}

open_pm || { shot connect-fail; record connect FAIL "could not open lobby-shell"; exit 1; }
record connect PASS "lobby-shell open"
check cover
# Order: Deacon state, then the group and its PMs, then the repositories (solo, unlabelled).
python3 - "$EMU_OUT/cover.txt" > "$EMU_OUT/cover-order.txt" << 'PY'
import sys, re
text = open(sys.argv[1]).read()
def at(p):
    m = re.search(p, text)
    return m.start() if m else -1
print(at(r'herdr, [23] projects'), at(r'demo'), at(r'web'), at(r'solo'))
PY
read -r g d w s < "$EMU_OUT/cover-order.txt"
if (( g >= 0 && d > g && w > g && s > d && s > w )); then record cover-order PASS "group, its PMs, then solo"
else record cover-order FAIL "order group=$g demo=$d web=$w solo=$s (cover.txt)"; fi

# A label set now moves solo into the group: presentation follows the core's label.
hc label "$(cat "$work/solo.addr")" herdr.group=herdr > "$EMU_OUT/hc-label.log" 2>&1
quiet 8
open_rail
dump > /dev/null 2>&1 && $ui list "$EMU_OUT/ui.xml" > "$EMU_OUT/relabel.txt" 2>&1
shot relabel
if grep -q 'herdr, 3 projects' "$EMU_OUT/relabel.txt"; then record relabel PASS "solo joined the group live"
else record relabel FAIL "solo did not join (relabel.png)"; fi
a shell input keyevent KEYCODE_BACK
quiet 2

# Kept by the core: after the app restarts, the group is still there.
open_pm || { record restart FAIL "could not reopen lobby-shell"; exit 1; }
open_rail
dump > /dev/null 2>&1 && $ui list "$EMU_OUT/ui.xml" > "$EMU_OUT/restart.txt" 2>&1
shot restart
if grep -q 'herdr, 3 projects' "$EMU_OUT/restart.txt"; then record restart PASS "the group survived a restart"
else record restart FAIL "no group after the restart (restart.png)"; fi
a shell input keyevent KEYCODE_BACK
quiet 2

a shell wm size 2208x1840
quiet 10
check inner
a shell wm size reset

if [[ -s "$EMU_OUT/anr.log" ]]; then
    record anr WARN "$(wc -l < "$EMU_OUT/anr.log") ANR dialog(s) were dismissed with Wait during the run (anr.log)"
fi
a logcat -b all -d > "$EMU_OUT/logcat-test.log" 2>&1
echo "--- summary ---"
cat "$SUMMARY"
(( FAILS == 0 ))
