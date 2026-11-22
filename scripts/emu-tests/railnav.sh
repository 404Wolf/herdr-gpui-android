#!/usr/bin/env bash
# The rail (the cover's drawer, the inner screen's docked column) as Wolf sees it: a project with a
# PM and two workers unfolded, shot at both widths; never any beads in it; a worker's tap focuses its
# terminal (and closes the cover's drawer), nothing more.
#   lease-run --kind android --outputs <dir> -- scripts/emu-test.sh scripts/emu-tests/railnav.sh
# The scratch host (lib-host.sh) runs a fake `bd` with a synthetic bead, so a bead would show if the
# rail read one. Screenshots and summary.tsv go to $EMU_OUT; the exit status is 1 if any check FAILed.

# A synthetic store: project "demo", epic demo-1 and its task demo-1.2. Read-only.
fake_bd() {
    mkdir -p "$work/bin" "$work/home/beads/demo/.beads"
    cat > "$work/bin/bd" << 'BD'
#!/bin/sh
case "$1" in
  list) printf '%s\n' '[{"id":"demo-1","title":"Demo epic","status":"open","issue_type":"epic","priority":1},{"id":"demo-1.2","title":"Give the rail a bead view","status":"in_progress","issue_type":"task","priority":2,"parent":"demo-1","assignee":"agent-a"}]' ;;
  show) printf '%s\n' '[{"id":"demo-1.2","title":"Give the rail a bead view","status":"in_progress","issue_type":"task","priority":2,"assignee":"agent-a","description":"Tapping a worker shows the bead it works on.\nEvery section and comment is open.","design":"One column; Back returns to the agents.","acceptance_criteria":"Selectable text; No task linked when none.","notes":"Synthetic fixture for the emulator."}]' ;;
  comments) printf '%s\n' '[{"author":"wolf","text":"Looks right: ship it once the cover keeps the bead.","created_at":"2026-11-21T10:00:00Z"},{"author":"demo-pm","text":"Second comment, also shown in full.","created_at":"2026-11-21T10:05:00Z"}]' ;;
  *) exit 1 ;;
esac
BD
    chmod +x "$work/bin/bd"
}

fixtures() {
    fake_bd
    # The core's machine.exec runs with the core's PATH: the fake bd must be on it when it starts.
    export PATH="$work/bin:$PATH"
    local pm
    pm=$(hc run --bg --label agent.kind=claude --label role=pm --label project=demo --name demo-pm -- \
        sh -c 'printf "PM fixture\n\n> "; exec cat' 2> "$EMU_OUT/hc-pm.log" \
        | python3 -c 'import sys, json; print(json.load(sys.stdin)["address"])') || return 1
    [[ -n "$pm" ]] || return 1
    hc run --bg --label agent.kind=claude --label role=worker --label project=demo --label "parent=$pm" \
        --label issue=demo-1.2 --name agent-a -- sh -c 'printf "Worker A (on demo-1.2)\n\n> "; exec cat' \
        > "$EMU_OUT/hc-a.log" 2>&1 || return 1
    hc run --bg --label agent.kind=claude --label role=worker --label project=demo --label "parent=$pm" \
        --name agent-b -- sh -c 'printf "Worker B (no bead)\n\n> "; exec cat' > "$EMU_OUT/hc-b.log" 2>&1 || return 1
    # A way in from Home that needs no PM tree: an unlabelled agent under "Other agents". Its name is
    # longer than five characters: a shorter one reads as an id and is not shown (AgentNames).
    hc run --bg --label agent.kind=claude --name lobby-shell -- sh -c 'printf "Lobby shell\n\n> "; exec cat' \
        > "$EMU_OUT/hc-lobby.log" 2>&1 || return 1
}
here=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
# shellcheck source=lib-host.sh
. "$here/lib-host.sh"

# ---- connect and open the lobby terminal ----
launch
tap_ui "Computer tbhost" 40 > /dev/null || { shot connect-fail; record connect FAIL "no tbhost on Home"; exit 1; }
tap_ui 'tbhost.* agents?' 15 > /dev/null || true
quiet 6
find_ui 'lobby-shell' > /dev/null 2>&1 || tap_ui 'Other agents' 15 > /dev/null || true
quiet 2
if tap_ui 'lobby-shell' 30 > /dev/null; then quiet 8; record connect PASS "lobby-shell open"; else shot connect-fail; record connect FAIL "could not open lobby-shell"; exit 1; fi

# The rail: open it (a drawer on the cover), unfold the demo PM, tap a worker. An agent's pane has
# no toolbar, so the way in is its top bar's crumb ("Show it in the rail") or a shell's rail button.
open_rail() {
    find_ui 'Focus pane agent-a|Show .* under demo|Hide .* under demo' > /dev/null 2>&1 \
        || tap_ui 'Show the host and workspace sidebar|Show it in the rail' 20 > /dev/null \
        || { shot "rail-closed-$1"; dump > /dev/null 2>&1; cp "$EMU_OUT/ui.xml" "$EMU_OUT/ui-rail-closed-$1.xml" 2> /dev/null; return 1; }
    quiet 3
    find_ui 'Focus pane agent-a' > /dev/null 2>&1 || tap_ui 'Show .* under demo' 15 > /dev/null || true
    quiet 2
}
shot_rail() { # label: the drawer/rail as Wolf sees it, its demo PM unfolded
    open_rail "$1" || { record "$1" FAIL "no rail (rail-closed-$1.png)"; return 1; }
    quiet 2
    shot "$1"
    dump > /dev/null 2>&1 || true
    cp "$EMU_OUT/ui.xml" "$EMU_OUT/ui-$1.xml" 2> /dev/null
    # Never beads in the rail (Wolf): project and agent navigation only.
    if grep -Eq 'Beads|Give the rail a bead view' "$EMU_OUT/ui.xml"; then record "$1-no-beads" FAIL "beads in the rail"
    else record "$1-no-beads" PASS "no beads in the rail"; fi
    record "$1" PASS "rail shot ($1.png)"
}
tap_worker() { # label agent: the tap focuses its terminal; the cover's drawer closes
    open_rail "$1" || { record "$1" FAIL "no rail"; return; }
    if ! tap_ui "Focus pane $2" 15 > /dev/null; then shot "$1-norow"; record "$1" FAIL "no row for $2"; return; fi
    quiet 5
    shot "$1"
    dump > /dev/null 2>&1 || true
    if grep -q "Window [0-9]*, $2, current" "$EMU_OUT/ui.xml"; then record "$1" PASS "$2's tap focused its terminal"
    else record "$1" FAIL "$2 not the current window after its tap"; fi
    if grep -Eq 'Give the rail a bead view|No task linked' "$EMU_OUT/ui.xml"; then record "$1-no-bead" FAIL "a bead view opened"
    else record "$1-no-bead" PASS "no bead view"; fi
}

shot_rail rail-cover
tap_worker cover-tap agent-a
a shell input keyevent KEYCODE_BACK
quiet 3

a shell wm size 2208x1840
quiet 10
shot_rail rail-inner
tap_worker inner-tap agent-b
a shell wm size reset

if [[ -s "$EMU_OUT/anr.log" ]]; then
    record anr WARN "$(wc -l < "$EMU_OUT/anr.log") ANR dialog(s) were dismissed with Wait during the run (anr.log)"
fi
a logcat -b all -d > "$EMU_OUT/logcat-test.log" 2>&1
echo "--- summary ---"
cat "$SUMMARY"
(( FAILS == 0 ))
