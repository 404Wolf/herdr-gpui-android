#!/usr/bin/env bash
# The terminal's top bar at the cover's width and at the inner screen's (factory-setup.29, PR #60):
# screenshots of the bar and its ⋮ menu, for a before/after of a toolbar change.
#   lease-run --kind android --outputs <dir> -- scripts/emu-test.sh scripts/emu-tests/toolbar.sh
# The setup is lib-host.sh's (pr48.sh's scratch host and device key, without its dictation fixture).
# Screenshots and summary.tsv go to $EMU_OUT; the exit status is 1 if any check FAILed.
fixtures() {
    # Agent A fills its screen with coloured lines (a saved grid with real output); B is a plain prompt.
    hc run --bg --label agent.kind=claude --name agent-a -- sh -c \
        'i=0; while [ $i -lt 140 ]; do printf "\033[48;5;%dm line %03d %-100s\033[0m\n" $((17 + i % 200)) $i ""; i=$((i + 1)); done; printf "> "; exec cat' \
        > "$EMU_OUT/hc-a.log" 2>&1 || { record setup FAIL "hc run A (see hc-a.log)"; exit 1; }
    hc run --bg --label agent.kind=claude --name agent-b -- sh -c 'printf "Claude Code (fixture B)\n\n> "; exec cat' \
        > "$EMU_OUT/hc-b.log" 2>&1 || { record setup FAIL "hc run B (see hc-b.log)"; exit 1; }
}
here=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
# shellcheck source=lib-host.sh
. "$here/lib-host.sh"


# ---- connect and open agent A ----
launch
open_agent() { # name
    tap_ui "Computer tbhost" 40 || return 1
    tap_ui 'tbhost.* agents?' 15 || true   # the machine entry connects it
    quiet 6
    # "Other agents" is folded at first.
    find_ui "$1" > /dev/null 2>&1 || tap_ui 'Other agents' 15 || true
    quiet 2
    tap_ui "$1" 30
}
if open_agent agent-a; then
    quiet 8
    record connect PASS "agent-a open"
else
    shot connect-fail
    record connect FAIL "could not open agent-a (see connect-fail.png)"
    exit 1
fi

# An agent's pane has no toolbar (its controls are in the prompt line); the toolbar is a shell's.
# Its top bar's ⋮ opens Session actions, whose New shell opens one and shows it.
agent_menu() { # label: the agent pane's own ⋮ menu, for the record
    tap_ui 'Panes, hosts and terminal actions' 20 > /dev/null || return 1
    quiet 2
    shot "agent-menu-$1"
    dump > /dev/null 2>&1 && $ui list "$EMU_OUT/ui.xml" > "$EMU_OUT/agent-menu-$1.txt" 2>&1
}
agent_menu cover || record agent-menu FAIL "no ⋮ on the agent pane"
if tap_ui '^Session actions$' 15 > /dev/null && tap_ui '^New shell$' 15 > /dev/null; then
    quiet 8
    record shell PASS "a shell opened from Session actions"
else
    shot shell-fail
    record shell FAIL "could not open a shell (see shell-fail.png)"
    exit 1
fi

# ---- the bar and its menu at the cover's width, then at the inner screen's ----
# The inner screen: the Fold's own 2208 x 1840 px at the guest's own density (never a higher one,
# see pr48.sh), about 841 dp wide, so the app takes its wide layout as on the phone.
bar() { # label
    local label=$1
    a shell cmd statusbar collapse > /dev/null 2>&1 || true
    quiet 3
    shot "bar-$label"
    dump > /dev/null 2>&1 && cp "$EMU_OUT/ui.xml" "$EMU_OUT/bar-$label-ui.xml"
    # What the bar offers, by its controls' descriptions (the evidence beside the picture).
    $ui list "$EMU_OUT/ui.xml" > "$EMU_OUT/bar-$label-controls.txt" 2>&1 || true
    # The ⋮ a Herdr pane has: the top bar's (the bottom bar has one only on the wide layout before PR #60).
    if tap_ui 'Panes, hosts and terminal actions' 15 > /dev/null; then
        quiet 2
        shot "bar-$label-menu"
        dump > /dev/null 2>&1 && $ui list "$EMU_OUT/ui.xml" > "$EMU_OUT/bar-$label-menu.txt" 2>&1
        a shell input keyevent KEYCODE_BACK
        quiet 2
        record "bar-$label" PASS "bar and its ⋮ menu (bar-$label.png, bar-$label-menu.png)"
    else
        record "bar-$label" PASS "bar only, no ⋮ on this layout (bar-$label.png, bar-$label-controls.txt)"
    fi
}
bar cover
a shell wm size 2208x1840
quiet 8
bar inner
a shell wm size reset

if [[ -s "$EMU_OUT/anr.log" ]]; then
    record anr WARN "$(wc -l < "$EMU_OUT/anr.log") ANR dialog(s) were dismissed with Wait during the run (anr.log)"
fi
a logcat -b all -d > "$EMU_OUT/logcat-test.log" 2>&1
echo "--- summary ---"
cat "$SUMMARY"
(( FAILS == 0 ))
