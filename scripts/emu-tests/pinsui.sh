#!/usr/bin/env bash
# Pins (factory-9zz.2, P3) at the cover's width and at the inner screen's: the pin at the top right
# of an agent's view, pinned by a tap, and the Pinned section at the top of the rail.
#   lease-run --kind android --outputs <dir> -- scripts/emu-test.sh scripts/emu-tests/pinsui.sh
# The setup is lib-host.sh's. Screenshots and summary.tsv go to $EMU_OUT; the exit status is 1 if
# any check FAILed (a build without pins fails at "pin-button", which is what a before-shot shows).

fixtures() {
    hc run --bg --label agent.kind=claude --name lobby-shell -- sh -c 'printf "Lobby shell\n\n> "; exec cat' \
        > "$EMU_OUT/hc-lobby.log" 2>&1 || return 1
    hc run --bg --label agent.kind=claude --name second-agent -- sh -c 'printf "Second agent\n\n> "; exec cat' \
        > "$EMU_OUT/hc-second.log" 2>&1 || return 1
}
here=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
# shellcheck source=lib-host.sh
. "$here/lib-host.sh"

launch
tap_ui "Computer tbhost" 40 > /dev/null || { shot connect-fail; record connect FAIL "no tbhost on Home"; exit 1; }
tap_ui 'tbhost.* agents?' 15 > /dev/null || true
quiet 6
find_ui 'lobby-shell' > /dev/null 2>&1 || tap_ui 'Other agents' 15 > /dev/null || true
quiet 2
if tap_ui 'lobby-shell' 30 > /dev/null; then quiet 10; record connect PASS "lobby-shell open"; else shot connect-fail; record connect FAIL "could not open lobby-shell"; exit 1; fi

pins() { # label
    local label=$1
    shot "$label-unpinned"
    if tap_ui '^Pin lobby-shell on ' 20 > /dev/null; then
        quiet 3
        shot "$label-pinned"
        if wait_ui '^Unpin lobby-shell on ' 10 > /dev/null; then record "$label-pin" PASS "the pin turned to Unpin"
        else record "$label-pin" FAIL "no Unpin after the tap ($label-pinned.png)"; fi
    else
        dump > /dev/null 2>&1 && cp "$EMU_OUT/ui.xml" "$EMU_OUT/ui-$label-nopin.xml"
        record "$label-pin-button" FAIL "no pin button ($label-unpinned.png)"
    fi
    # The rail: a drawer on the cover (its crumb opens it), docked on the inner screen.
    find_ui '^Pinned$' > /dev/null 2>&1 || tap_ui 'Show the host and workspace sidebar|Show it in the rail' 15 > /dev/null || true
    quiet 3
    shot "$label-rail"
    if find_ui '^Pinned$' > /dev/null 2>&1 && find_ui 'lobby-shell on tbhost' > /dev/null 2>&1; then
        record "$label-section" PASS "Pinned section with lobby-shell ($label-rail.png)"
    else
        dump > /dev/null 2>&1 && cp "$EMU_OUT/ui.xml" "$EMU_OUT/ui-$label-rail.xml"
        record "$label-section" FAIL "no Pinned section ($label-rail.png)"
    fi
}

pins cover
a shell input keyevent KEYCODE_BACK
quiet 3
# Unpin first, so the inner screen shows the whole flow again.
tap_ui '^Unpin lobby-shell on ' 10 > /dev/null || true
quiet 2
a shell wm size 2208x1840
quiet 10
pins inner
a shell wm size reset

if [[ -s "$EMU_OUT/anr.log" ]]; then
    record anr WARN "$(wc -l < "$EMU_OUT/anr.log") ANR dialog(s) were dismissed with Wait during the run (anr.log)"
fi
a logcat -b all -d > "$EMU_OUT/logcat-test.log" 2>&1
echo "--- summary ---"
cat "$SUMMARY"
(( FAILS == 0 ))
