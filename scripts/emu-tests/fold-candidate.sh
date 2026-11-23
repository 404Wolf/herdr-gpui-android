#!/usr/bin/env bash
# A Fold-sized look at a phone candidate (factory-9zz, 11-23), both postures on one emulator:
# the inner screen as 2076x2152 and the cover as 1080x2400 (wm size, density unchanged). Shots for
# the UI pass, and the checks for that day's fixes: the keyboard-up composer cluster (it sits over
# the session just above the keyboard, no toolbar, prompt row clear), the right edge (lower half
# grows the panel from zero under the finger, upper half is Back), an unfolded window narrower
# than 640 dp still splits, and the cover's full page.
#   scripts/emu-test.sh scripts/emu-tests/fold-candidate.sh   (inside a kvm Slurm job)
# summary.tsv rows: name, PASS|FAIL|WARN|INFO, detail; exit 1 if any check FAILed.

fixtures() {
    mkdir -p "$work/bin" "$work/home/deacon/work"
    cat > "$work/bin/bd" << 'BD'
#!/bin/sh
case "$1" in
  list) printf '%s\n' '[{"id":"demo-1","title":"Demo epic","status":"open","issue_type":"epic","priority":1},{"id":"demo-1.2","title":"Give the panel a bead view","status":"in_progress","issue_type":"task","priority":2,"parent":"demo-1","assignee":"agent-a"}]' ;;
  show) printf '%s\n' "[{\"id\":\"$2\",\"title\":\"Give the panel a bead view\",\"status\":\"in_progress\",\"issue_type\":\"task\",\"priority\":2,\"comment_count\":0,\"description\":\"A plan.\",\"updated_at\":\"2026-11-22T10:00:00Z\"}]" ;;
  comments) printf '%s\n' '[]' ;;
  *) exit 1 ;;
esac
BD
    chmod +x "$work/bin/bd"
    printf '%s\n' '# demo' '' 'Give the phone a right panel that reads **this doc**.' '' '### Now' '- Fold candidate shots' > "$work/home/deacon/work/demo.md"
    export PATH="$work/bin:$PATH"
    local pm
    pm=$(hc run --bg --label agent.kind=claude --label role=pm --label project=demo --label herdr.space=sp-demo \
        --label herdr.tab=pm --name demo-pm -- sh -c 'i=0; while [ $i -lt 40 ]; do printf "PM line %02d\n" $i; i=$((i + 1)); done; printf "\n> "; exec cat' \
        2> "$EMU_OUT/hc-pm.log" | python3 -c 'import sys, json; print(json.load(sys.stdin)["address"])') || return 1
    [[ -n "$pm" ]] || return 1
    hc run --bg --label agent.kind=claude --label role=worker --label project=demo --label "parent=$pm" \
        --label herdr.space=sp-demo --label herdr.tab=wa --name agent-a \
        -- sh -c 'printf "Worker A\n\n> "; exec cat' > "$EMU_OUT/hc-a.log" 2>&1 || return 1
    : > "$work/keys-q.log"
    hc run --bg --label agent.kind=claude --label project=demo --label herdr.space=sp-demo --label herdr.tab=q \
        --name agent-q -- python3 "$here/fake-ask.py" "$work/keys-q.log" > "$EMU_OUT/hc-q.log" 2>&1 || return 1
    hc run --bg --label agent.kind=claude --name lobby-shell -- sh -c 'printf "Lobby shell\n\n> "; exec cat' \
        > "$EMU_OUT/hc-lobby.log" 2>&1 || return 1
}
here=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
# shellcheck source=lib-host.sh
. "$here/lib-host.sh"

# Through a device file: exec-out screencap gave empty PNGs on some guests (elicit.sh).
shot() {
    a shell screencap -p "/sdcard/$1.png" >> "$EMU_OUT/shot.log" 2>&1
    a pull "/sdcard/$1.png" "$EMU_OUT/$1.png" >> "$EMU_OUT/shot.log" 2>&1
    a shell rm -f "/sdcard/$1.png" > /dev/null 2>&1
    return 0
}
# Bounds "l t r b" of the first node whose text or content-desc matches $1, in a fresh dump.
bounds() {
    dump > /dev/null 2>&1 || return 1
    python3 - "$EMU_OUT/ui.xml" "$1" << 'PY'
import re, sys, xml.etree.ElementTree as ET
pat = re.compile(sys.argv[2])
for n in ET.parse(sys.argv[1]).iter("node"):
    if pat.search(n.get("text", "")) or pat.search(n.get("content-desc", "")):
        m = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", n.get("bounds", ""))
        if m:
            print(*m.groups())
            sys.exit(0)
sys.exit(1)
PY
}
# Bounds of the terminal's SurfaceView.
surface_bounds() {
    dump > /dev/null 2>&1 || return 1
    python3 - "$EMU_OUT/ui.xml" << 'PY'
import re, sys, xml.etree.ElementTree as ET
for n in ET.parse(sys.argv[1]).iter("node"):
    if n.get("class") == "android.view.SurfaceView":
        print(*re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", n.get("bounds")).groups())
        sys.exit(0)
sys.exit(1)
PY
}
size() { a shell wm size "$1" > /dev/null; quiet 6; }
width() { a shell wm size | sed -n 's/.*: \([0-9]*\)x\([0-9]*\).*/\1/p' | tail -1; }
height() { a shell wm size | sed -n 's/.*: \([0-9]*\)x\([0-9]*\).*/\2/p' | tail -1; }
open_pm() {
    launch
    if wait_ui 'Window [0-9]+, (demo-pm|agent-a|agent-q), current' 8 > /dev/null; then quiet 5; return 0; fi
    tap_ui "Computer tbhost" 40 > /dev/null || return 1
    tap_ui 'tbhost.* agents?' 15 > /dev/null || true
    quiet 6
    find_ui 'lobby-shell' > /dev/null 2>&1 || tap_ui 'Other agents' 15 > /dev/null || true
    quiet 2
    tap_ui 'lobby-shell' 30 > /dev/null || return 1
    quiet 8
    tap_ui 'Show the host and workspace sidebar|Show it in the rail' 20 > /dev/null || true
    quiet 3
    tap_ui 'Focus pane demo-pm' 20 > /dev/null || tap_ui '^demo$' 10 > /dev/null || return 1
    quiet 6
}
back_closes_menus() { a shell input keyevent KEYCODE_BACK; quiet 2; }
focused_activity() { a shell dumpsys activity activities 2> /dev/null | sed -n 's/.*topResumedActivity=.*{[^ ]* [^ ]* \([^ ]*\) .*/\1/p' | head -1; }

# The keyboard up on an agent's pane: the cluster over the session, just above the keyboard, no
# toolbar, the prompt's row above the cluster.
keyboard_check() { # posture
    local p=$1 sb cb
    tap_ui 'Type a message, or put the keyboard away' 15 > /dev/null || { record "$p-keyboard" FAIL "no Type button"; return; }
    if ! wait_ime 20; then record "$p-keyboard" FAIL "the keyboard did not come up"; return; fi
    a shell input text 'draft%sfrom%sthe%sfold%scheck'
    quiet 3
    shot "$p-keyboard"
    if find_ui 'Show the host and workspace sidebar' > /dev/null 2>&1; then
        record "$p-keyboard-toolbar" FAIL "the bottom toolbar still shows with the keyboard up ($p-keyboard.png)"
    else
        record "$p-keyboard-toolbar" PASS "no toolbar between the session and the keyboard"
    fi
    sb=$(surface_bounds) || sb=
    cb=$(bounds 'Send, or press Enter') || cb=
    if [[ -n "$sb" && -n "$cb" ]]; then
        read -r _ _ _ sbot <<< "$sb"
        read -r _ ctop _ cbot <<< "$cb"
        if (( sbot > ctop )); then
            record "$p-keyboard-overlay" PASS "the cluster (top $ctop) overlays the session (bottom $sbot), no strip"
        else
            record "$p-keyboard-overlay" FAIL "the session ends at $sbot, above the cluster at $ctop: a strip is kept"
        fi
    else
        record "$p-keyboard-overlay" WARN "could not read the surface ($sb) or the cluster ($cb)"
    fi
    a shell input keyevent KEYCODE_BACK
    quiet 3
}

# ---- unfolded ----
size 2076x2152
open_pm || { shot connect-fail; record connect FAIL "could not open demo-pm"; exit 1; }
record connect PASS "demo-pm open on the inner screen ($(width)x$(height))"
shot unfolded-overview
tap_ui 'Show the host and workspace sidebar' 10 > /dev/null && { quiet 3; shot unfolded-drawer; back_closes_menus; }
keyboard_check unfolded
# A question sheet (Claude's AskUserQuestion, fake-ask.py).
tap_ui 'Window [0-9]+, agent-q' 10 > /dev/null || tap_ui 'Focus pane agent-q' 10 > /dev/null || true
if wait_ui 'Claude asks' 30 > /dev/null; then quiet 2; shot unfolded-question; record unfolded-question PASS "the sheet opened"
else shot unfolded-question-fail; record unfolded-question WARN "no question sheet (unfolded-question-fail.png)"; fi
back_closes_menus
tap_ui 'Window [0-9]+, demo-pm' 10 > /dev/null || true
quiet 4

# (a) The panel away: the right edge's lower half grows it from zero under the finger; the
# release keeps it there. The divider dragged all the way right hides it.
W=$(width); H=$(height)
if db=$(bounds 'Work panel divider'); then
    read -r dl _ dr _ <<< "$db"
    a shell input swipe $(((dl + dr) / 2)) $((H / 2)) $((W - 2)) $((H / 2)) 600
    quiet 3
fi
shot unfolded-panel-hidden
if bounds 'Work panel divider' > /dev/null; then record panel-hide WARN "the divider still shows after dragging it right"; fi
y=$((H * 3 / 4))
a shell input motionevent DOWN $((W - 2)) $y
for x in $((W - 30)) $((W - 120)) $((W - 260)); do a shell input motionevent MOVE "$x" "$y"; sleep 0.15; done
shot unfolded-panel-reveal-mid
for x in $((W - 420)) $((W - 600)); do a shell input motionevent MOVE "$x" "$y"; sleep 0.15; done
a shell input motionevent UP $((W - 600)) "$y"
quiet 3
shot unfolded-panel-revealed
if db=$(bounds 'Work panel divider'); then
    read -r dl _ dr _ <<< "$db"
    record edge-lower-grows PASS "the panel came in; divider at x=$(((dl + dr) / 2)) after a release at x=$((W - 600)) (unfolded-panel-reveal-mid.png, unfolded-panel-revealed.png)"
else
    record edge-lower-grows FAIL "no panel after the lower-half edge swipe (unfolded-panel-revealed.png)"
fi
# (d) The panel shown: the right edge is Back.
a shell input swipe $((W - 2)) $((H * 3 / 4)) $((W - 400)) $((H * 3 / 4)) 300
quiet 3
shot unfolded-edge-with-panel
record edge-with-panel INFO "after a right-edge swipe with the panel shown, on top: $(focused_activity)"
open_pm > /dev/null 2>&1 || true
# The upper half, panel away: Back.
if db=$(bounds 'Work panel divider'); then
    read -r dl _ dr _ <<< "$db"
    a shell input swipe $(((dl + dr) / 2)) $((H / 2)) $((W - 2)) $((H / 2)) 600
    quiet 3
fi
a shell input swipe $((W - 2)) $((H / 4)) $((W - 400)) $((H / 4)) 300
quiet 3
shot unfolded-edge-upper
record edge-upper-back INFO "after an upper-half right-edge swipe, on top: $(focused_activity)"
open_pm > /dev/null 2>&1 || true

# (b) An unfolded window narrower than 640 dp (freeform, as split screen narrows it) still splits.
a shell settings put global enable_freeform_support 1 > /dev/null 2>&1
a shell settings put global force_resizable_activities 1 > /dev/null 2>&1
task=$(a shell am stack list 2> /dev/null | tr -d '\r' | sed -n "s/.*taskId=\([0-9]*\): $pkg.*/\1/p" | head -1)
if [[ -n "$task" ]] && a shell am task resize "$task" 0 0 1400 2000 > /dev/null 2>&1; then
    quiet 5
    shot unfolded-narrow-window
    if bounds 'Work panel divider' > /dev/null; then record narrow-splits PASS "a 1400 px (<640 dp) window still splits (unfolded-narrow-window.png)"
    else record narrow-splits WARN "no divider in the narrowed window (unfolded-narrow-window.png): check by eye"; fi
else
    record narrow-splits INFO "this guest could not narrow the window (no freeform resize); not checked"
fi
open_pm > /dev/null 2>&1 || true

# ---- the cover ----
size 1080x2400
W=$(width); H=$(height)
shot cover-terminal
tap_ui 'Show the host and workspace sidebar' 10 > /dev/null && { quiet 3; shot cover-drawer; back_closes_menus; }
keyboard_check cover
# (c) The right edge's lower half: the full page follows the finger and settles open or closed.
y=$((H * 3 / 4))
a shell input motionevent DOWN $((W - 2)) $y
for x in $((W - 40)) $((W - 200)) $((W - 400)); do a shell input motionevent MOVE "$x" "$y"; sleep 0.15; done
shot cover-panel-mid
for x in $((W - 600)) $((W - 800)); do a shell input motionevent MOVE "$x" "$y"; sleep 0.15; done
a shell input motionevent UP $((W - 800)) "$y"
quiet 3
shot cover-panel-full
if find_ui 'Overview|Tasks' > /dev/null 2>&1; then record cover-page PASS "the full page settled open (cover-panel-mid.png, cover-panel-full.png)"
else record cover-page FAIL "no full page after the cover's edge swipe (cover-panel-full.png)"; fi
back_closes_menus
tap_ui 'Window [0-9]+, agent-q' 10 > /dev/null || true
if wait_ui 'Claude asks' 30 > /dev/null; then quiet 2; shot cover-question; record cover-question PASS "the sheet opened"
else shot cover-question-fail; record cover-question WARN "no question sheet on the cover"; fi
back_closes_menus

a shell wm size reset
if [[ -s "$EMU_OUT/anr.log" ]]; then
    record anr WARN "$(wc -l < "$EMU_OUT/anr.log") ANR dialog(s) were dismissed with Wait during the run (anr.log)"
fi
a logcat -b all -d > "$EMU_OUT/logcat-test.log" 2>&1
echo "--- summary ---"
cat "$SUMMARY"
(( FAILS == 0 ))
