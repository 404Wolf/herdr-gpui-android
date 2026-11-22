#!/usr/bin/env bash
# The right panel (factory-r8f): on the unfolded screen beside the terminal with the PM's
# overview, then Tasks: a bead opened by its title (timed: its plan and its comments after
# the tap, cold, again, and after the app restarted), the tap showing the one worker on it;
# on the cover, the Work button's panel and Back. A fake `bd` (with bd's own pace) and a
# synthetic status doc on the scratch host.
#   lease-run --kind android --outputs <dir> -- scripts/emu-test.sh scripts/emu-tests/panel.sh
# timings.tsv: the app's own "panel.bead" lines; summary.tsv; exit 1 if any check FAILed.

fake_bd() {
    mkdir -p "$work/bin" "$work/home/beads/demo/.beads" "$work/home/deacon/work"
    cat > "$work/bin/bd" << 'BD'
#!/bin/sh
# bd's own pace: a show or comments read costs ~0.4 s.
case "$1" in
  list) printf '%s\n' '[{"id":"demo-1","title":"Demo epic","status":"open","issue_type":"epic","priority":1},{"id":"demo-1.2","title":"Give the panel a bead view","status":"in_progress","issue_type":"task","priority":2,"parent":"demo-1","assignee":"agent-a"},{"id":"demo-1.3","title":"Nobody on this one","status":"open","issue_type":"task","priority":2,"parent":"demo-1"}]' ;;
  show) sleep 0.4; printf '%s\n' "[{\"id\":\"$2\",\"title\":\"Give the panel a bead view\",\"status\":\"in_progress\",\"issue_type\":\"task\",\"priority\":2,\"comment_count\":2,\"description\":\"Tapping a bead shows its plan **at once**.\",\"design\":\"One column; comments under the plan.\",\"acceptance_criteria\":\"- Plan and comments together\\n- Selectable text\",\"updated_at\":\"2026-11-22T10:00:00Z\"}]" ;;
  comments) sleep 0.4; printf '%s\n' '[{"author":"wolf","text":"Looks right: comments must not pop in later.","created_at":"2026-11-22T10:00:00Z"},{"author":"demo-pm","text":"Second comment, shown in full.","created_at":"2026-11-22T10:05:00Z"}]' ;;
  *) exit 1 ;;
esac
BD
    chmod +x "$work/bin/bd"
    printf '%s\n' '# demo' '' 'Give the phone a right panel that reads **this doc**.' '' '### Now' '- Tasks open at once: demo-1.2' '- Overview shows the doc'"'"'s age' > "$work/home/deacon/work/demo.md"
}

fixtures() {
    fake_bd
    export PATH="$work/bin:$PATH"
    local pm
    pm=$(hc run --bg --label agent.kind=claude --label role=pm --label project=demo --label herdr.space=sp-demo \
        --label herdr.tab=pm --name demo-pm -- sh -c 'printf "PM fixture\n\n> "; exec cat' 2> "$EMU_OUT/hc-pm.log" \
        | python3 -c 'import sys, json; print(json.load(sys.stdin)["address"])') || return 1
    [[ -n "$pm" ]] || return 1
    hc run --bg --label agent.kind=claude --label role=worker --label project=demo --label "parent=$pm" \
        --label issue=demo-1.2 --label herdr.space=sp-demo --label herdr.tab=wa --name agent-a \
        -- sh -c 'printf "Worker A (on demo-1.2)\n\n> "; exec cat' > "$EMU_OUT/hc-a.log" 2>&1 || return 1
    hc run --bg --label agent.kind=claude --name lobby-shell -- sh -c 'printf "Lobby shell\n\n> "; exec cat' \
        > "$EMU_OUT/hc-lobby.log" 2>&1 || return 1
}
here=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
# shellcheck source=lib-host.sh
. "$here/lib-host.sh"

TIMINGS="$EMU_OUT/timings.tsv"
: > "$TIMINGS"
open_pm() {
    launch
    if wait_ui 'Window [0-9]+, (demo-pm|agent-a), current' 8 > /dev/null; then quiet 5; return 0; fi
    tap_ui "Computer tbhost" 40 > /dev/null || return 1
    tap_ui 'tbhost.* agents?' 15 > /dev/null || true
    quiet 6
    find_ui 'lobby-shell' > /dev/null 2>&1 || tap_ui 'Other agents' 15 > /dev/null || true
    quiet 2
    tap_ui 'lobby-shell' 30 > /dev/null || return 1
    quiet 8
    # To the PM through the rail (a drawer beside the panel on the unfolded screen).
    tap_ui 'Show the host and workspace sidebar|Show it in the rail' 20 > /dev/null || true
    quiet 3
    tap_ui 'Focus pane demo-pm' 20 > /dev/null || tap_ui '^demo$' 10 > /dev/null || return 1
    quiet 6
}
ops() { a logcat -d -s herdr-ops 2> /dev/null | grep 'panel.bead' >> "$TIMINGS"; a logcat -c; }
open_bead() { # label
    a logcat -c
    tap_ui '^Give the panel a bead view' 20 > /dev/null || { shot "$1-norow"; record "$1" FAIL "no bead row"; return 1; }
    if wait_ui 'Looks right: comments must not pop in later' 20 > /dev/null; then record "$1" PASS "plan and comments shown"
    else record "$1" FAIL "no comments within 20 s ($1.png)"; fi
    quiet 2
    shot "$1"
    echo "# $1" >> "$TIMINGS"
    ops
}
close_bead() { tap_ui '^Close its plan$' 5 > /dev/null || tap_ui '^Give the panel a bead view' 5 > /dev/null || true; quiet 2; }

a shell wm size 2208x1840
quiet 8
open_pm || { shot connect-fail; record connect FAIL "could not open demo-pm"; exit 1; }
record connect PASS "demo-pm open"
shot overview-fold
if find_ui 'Give the phone a right panel that reads this doc' > /dev/null 2>&1; then record overview PASS "the PM's doc shows (overview-fold.png)"
else record overview FAIL "no doc in the panel (overview-fold.png)"; fi
tap_ui '^Tasks' 15 > /dev/null || record tasks FAIL "no Tasks tab"
quiet 4
shot tasks-fold
open_bead bead-cold
# The title's tap showed the worker on it.
dump > /dev/null 2>&1 || true
if grep -q 'Window [0-9]*, agent-a, current' "$EMU_OUT/ui.xml"; then record focus PASS "the tap showed agent-a"
else record focus INFO "agent-a not current after the tap (a build before the change only opens the bead)"; fi
close_bead
open_bead bead-warm
close_bead
open_pm || { record restart FAIL "could not reopen"; exit 1; }
tap_ui '^Tasks' 15 > /dev/null || true
quiet 3
open_bead bead-restart
a shell wm size reset
quiet 8
# The cover: the Work button opens the panel over the terminal; Back closes it.
if tap_ui '^Work$|Show the work panel' 15 > /dev/null; then
    quiet 3
    shot cover-panel
    a shell input keyevent KEYCODE_BACK
    quiet 2
    if find_ui 'Show the work panel' > /dev/null 2>&1; then record cover PASS "Work opens it, Back closes it (cover-panel.png)"
    else record cover FAIL "Back did not close the cover's panel"; fi
else
    shot cover-nowork
    record cover FAIL "no Work button on the cover (cover-nowork.png)"
fi

if [[ -s "$EMU_OUT/anr.log" ]]; then
    record anr WARN "$(wc -l < "$EMU_OUT/anr.log") ANR dialog(s) were dismissed with Wait during the run (anr.log)"
fi
a logcat -b all -d > "$EMU_OUT/logcat-test.log" 2>&1
echo "--- timings ---"
cat "$TIMINGS"
echo "--- summary ---"
cat "$SUMMARY"
(( FAILS == 0 ))
