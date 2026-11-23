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
# A posture: on a foldable AVD (EMU_DEVICE=pixel_9_pro_fold) the emulator's own hinge, else the
# inner or cover screen's size.
FOLDABLE=0
[[ "${EMU_DEVICE:-}" == *fold* ]] && FOLDABLE=1
posture() { # unfolded|cover
    if (( FOLDABLE )); then
        if [[ $1 == cover ]]; then a emu fold > /dev/null; else a emu unfold > /dev/null; fi
        quiet 6
    elif [[ $1 == cover ]]; then size 1080x2400
    else size 2076x2152
    fi
}
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
    go_to demo-pm || return 1
    quiet 4
}
back_closes_menus() { a shell input keyevent KEYCODE_BACK; quiet 2; }
# To an agent by its rail row: the rail opened, the demo project unfolded when the row is hidden,
# the row tapped, and the drawer closed again when it stays over the terminal.
go_to() { # name
    pane_now "$1" && return 0
    tap_ui 'Show the host and workspace sidebar|Show it in the rail' 15 > /dev/null || true
    quiet 3
    local i
    # Groups, projects and repos fold: unfold what hides the row, a level at a time.
    for i in 1 2 3 4; do
        find_ui "Focus pane $1|^$1" > /dev/null 2>&1 && break
        tap_ui '^Unfold |^Show [0-9]+ rows? under' 5 > /dev/null || break
        quiet 2
    done
    tap_ui "Focus pane $1|^$1|, $1" 15 > /dev/null || { shot "goto-$1-fail"; return 1; }
    quiet 5
    if find_ui 'No Deacon on this computer|Start Deacon' > /dev/null 2>&1; then back_closes_menus; fi
    pane_now "$1"
}
pane_now() { dump > /dev/null 2>&1 && grep -q "$1" <<< "$($ui text "$EMU_OUT/ui.xml" "Window [0-9]+, $1|› $1" 2> /dev/null)"; }
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
posture unfolded
open_pm || { shot connect-fail; record connect FAIL "could not open demo-pm"; exit 1; }
record connect PASS "demo-pm open on the inner screen ($(width)x$(height))"
shot unfolded-overview
tap_ui 'Show the host and workspace sidebar' 10 > /dev/null && { quiet 3; shot unfolded-drawer; back_closes_menus; }
keyboard_check unfolded
# A question sheet (Claude's AskUserQuestion, fake-ask.py).
go_to agent-q || true
if wait_ui 'Claude asks' 30 > /dev/null; then quiet 2; shot unfolded-question; record unfolded-question PASS "the sheet opened"
else shot unfolded-question-fail; record unfolded-question WARN "no question sheet (unfolded-question-fail.png)"; fi
back_closes_menus
go_to demo-pm || true
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

# ---- fold transitions in one live session (Wolf's spec, 11-23), on the emulated hinge ----
# (1) Folding with the inner split visible: the panel closes and the cover shows the terminal.
# (2) On the cover the panel is a full page (only after an explicit reveal) or hidden; never a split.
# (3) Unfolding with the cover's page open: the inner split at once, same tab and agent, the saved
#     inner width; never full screen on the inner display.
# (4) Repeated fold/unfold cycles without a restart keep to (1)-(3).
POSTURES="$EMU_OUT/postures.tsv"
printf 'step\twm_size\tdevice_state\tdivider\tsurface\tpanel\ttab\tagent\tprefs\n' > "$POSTURES"
divider_x() { local db dl dr; db=$(bounds 'Work panel divider') || { echo none; return; }; read -r dl _ dr _ <<< "$db"; echo $(((dl + dr) / 2)); }
# The panel as drawn: none (no Overview tab), split (a divider), full (its tab row starts at the
# screen's left with no divider), or partial (anything else).
panel_state() {
    local ob ol div
    ob=$(bounds '^Overview|Overview, ') || { echo none; return; }
    div=$(divider_x)
    [[ $div != none ]] && { echo split; return; }
    read -r ol _ _ _ <<< "$ob"
    (( ol < 120 )) && echo full || echo partial
}
selected_tab() { dump > /dev/null 2>&1; grep -o '[A-Za-z]*, selected tab' "$EMU_OUT/ui.xml" | head -1 | cut -d, -f1; }
agent_now() { dump > /dev/null 2>&1; grep -o 'Window [0-9]*, [^,"]*, [^"]*current' "$EMU_OUT/ui.xml" | head -1 | cut -d, -f2 | tr -d ' '; }
log_step() { # step
    local wm ds prefs
    wm=$(a shell wm size 2> /dev/null | tr -d '\r' | tail -1 | sed 's/.*: //')
    ds=$(a shell cmd device_state print-state 2> /dev/null | tr -d '\r' | tr '\n' ' ')
    prefs=$(a shell "run-as $pkg cat shared_prefs/terminal-display.xml" 2> /dev/null | tr -d '\r\n' | grep -o 'work_panel[^<]*<[^>]*>[^<]*' | tr '\n' ' ')
    STEP_DIV=$(divider_x); STEP_PANEL=$(panel_state); STEP_TAB=$(selected_tab); STEP_AGENT=$(agent_now)
    printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n' "$1" "$wm" "$ds" "$STEP_DIV" "$(surface_bounds || echo none)" \
        "$STEP_PANEL" "$STEP_TAB" "$STEP_AGENT" "$prefs" >> "$POSTURES"
    a logcat -d -v epoch 2> /dev/null | grep -E 'herdr-(panel|posture|fold)|work panel:' | sed "s/^/$1 /" >> "$EMU_OUT/postures-log.txt"
    a logcat -c
    shot "posture-$1"
}
show_panel() { # unfolded: the split through ⋮ › Overview and tasks when it is hidden
    bounds 'Work panel divider' > /dev/null && return 0
    tap_ui 'Panes, hosts and terminal actions' 10 > /dev/null && tap_ui '^Overview and tasks$' 10 > /dev/null
    quiet 3
}
cover_reveal() { # the cover's explicit reveal: in from the right edge's lower half, let go far in
    local w h y x
    w=$(width); h=$(height); y=$((h * 3 / 4))
    a shell input motionevent DOWN $((w - 2)) $y
    for x in $((w - 40)) $((w - 200)) $((w - 400)) $((w - 600)) $((w - 800)); do a shell input motionevent MOVE "$x" "$y"; sleep 0.12; done
    a shell input motionevent UP $((w - 800)) "$y"
    quiet 3
}
cover_ok() { # step: the cover is never a split; with no reveal, the terminal
    if [[ $STEP_PANEL == split || $STEP_PANEL == partial ]]; then
        record "$1" FAIL "the cover shows a $STEP_PANEL panel (posture-$1.png, postures.tsv)"; return 1
    fi
    return 0
}
a logcat -c
(( FOLDABLE )) || record fold-hinge WARN "not a foldable AVD (EMU_DEVICE=${EMU_DEVICE:-}): postures are emulated with wm size"
posture unfolded; go_to demo-pm > /dev/null 2>&1 || true; show_panel
log_step s0-unfolded-split
inner_width=$STEP_DIV
[[ $STEP_PANEL == split ]] || record s0-split FAIL "no inner split to start from (posture-s0-unfolded-split.png)"

# Sequence 1: split open, fold -> closed on the cover, the terminal shown.
posture cover
log_step s1-folded
if cover_ok s1-folded; then
    if [[ $STEP_PANEL == none ]] && surface_bounds > /dev/null; then record s1-fold-closes PASS "folding closed the panel; the cover shows the terminal"
    else record s1-fold-closes FAIL "after folding the panel is '$STEP_PANEL' (posture-s1-folded.png)"; fi
fi

# Sequence 2: the cover's full page (explicit reveal, Tasks selected) -> unfold -> the split,
# same tab and agent, the saved inner width.
cover_reveal
tap_ui '^Tasks' 10 > /dev/null || true
quiet 2
log_step s2-cover-page
before_tab=$STEP_TAB; before_agent=$STEP_AGENT
cover_ok s2-cover-page && { [[ $STEP_PANEL == full ]] && record s2-cover-full PASS "the reveal gives a full page (tab $before_tab)" \
    || record s2-cover-full FAIL "the reveal gave '$STEP_PANEL', not a full page (posture-s2-cover-page.png)"; }
posture unfolded
log_step s2-unfolded
if [[ $STEP_PANEL != split ]]; then
    record s2-unfold-splits FAIL "unfolding the cover's page gave '$STEP_PANEL', not the split (posture-s2-unfolded.png)"
elif [[ "$STEP_TAB" != "$before_tab" || "$STEP_AGENT" != "$before_agent" ]]; then
    record s2-unfold-splits FAIL "the split came back but tab/agent changed: '$before_tab'/'$before_agent' -> '$STEP_TAB'/'$STEP_AGENT'"
elif [[ $inner_width != none ]] && (( STEP_DIV - inner_width > 24 || inner_width - STEP_DIV > 24 )); then
    record s2-unfold-splits FAIL "the split's divider at x=$STEP_DIV, not the saved inner width x=$inner_width"
else
    record s2-unfold-splits PASS "the split at once (divider x=$STEP_DIV, saved $inner_width), tab $STEP_TAB, agent $STEP_AGENT"
fi

# Sequence 3: repeated cycles, no restart: fold with the split open, unfold from the cover's
# page and from a closed cover. The cover is never a split; the inner display never a full page.
for i in 1 2 3; do
    show_panel
    posture cover; log_step "s3-$i-folded"
    cover_ok "s3-$i-folded" && { [[ $STEP_PANEL == none ]] || record "s3-$i-folded" FAIL "folding left the panel '$STEP_PANEL'"; }
    (( i % 2 )) && cover_reveal
    posture unfolded; log_step "s3-$i-unfolded"
    if [[ $STEP_PANEL == full || $STEP_PANEL == partial ]]; then
        record "s3-$i-unfolded" FAIL "the inner display shows the panel as '$STEP_PANEL' (posture-s3-$i-unfolded.png)"
    elif (( i % 2 )) && [[ $STEP_PANEL != split ]]; then
        record "s3-$i-unfolded" FAIL "unfolding the cover's page gave '$STEP_PANEL', not the split"
    else
        record "s3-$i-unfolded" PASS "cycle $i: unfolded with panel '$STEP_PANEL' (divider $STEP_DIV)"
    fi
done
# Control: a cold restart, unfolded.
a shell am force-stop "$pkg"; quiet 2; open_pm > /dev/null 2>&1 || true; show_panel
log_step control-restart
record control-restart INFO "after a cold restart: panel '$STEP_PANEL', divider $STEP_DIV"

# ---- the cover ----
posture cover
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
go_to agent-q || true
if wait_ui 'Claude asks' 30 > /dev/null; then quiet 2; shot cover-question; record cover-question PASS "the sheet opened"
else shot cover-question-fail; record cover-question WARN "no question sheet on the cover"; fi
back_closes_menus

(( FOLDABLE )) || a shell wm size reset
if [[ -s "$EMU_OUT/anr.log" ]]; then
    record anr WARN "$(wc -l < "$EMU_OUT/anr.log") ANR dialog(s) were dismissed with Wait during the run (anr.log)"
fi
a logcat -b all -d > "$EMU_OUT/logcat-test.log" 2>&1
echo "--- summary ---"
cat "$SUMMARY"
(( FAILS == 0 ))
