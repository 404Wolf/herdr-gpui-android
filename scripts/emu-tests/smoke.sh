#!/usr/bin/env bash
set -euo pipefail

: "${ADB_SERIAL:?Run through scripts/emu-test.sh}"
: "${EMU_OUT:?Run through scripts/emu-test.sh}"
: "${EMU_APK:?Run through scripts/emu-test.sh}"

adb devices -l > "$EMU_OUT/adb-devices.log"
aapt2="$ANDROID_HOME/build-tools/35.0.0/aapt2"
badging=$("$aapt2" dump badging "$EMU_APK")
package=$(sed -n "s/^package: name='\([^']*\)'.*/\1/p" <<< "$badging")
[[ -n "$package" ]] || { echo "Could not read the APK package name" >&2; exit 1; }
grep -qx 'application-debuggable' <<< "$badging" || {
    echo "The Home screenshot requires a debuggable APK" >&2
    exit 1
}
adb -s "$ADB_SERIAL" shell settings put global herdr_probe 1
adb -s "$ADB_SERIAL" shell input keyevent KEYCODE_WAKEUP
adb -s "$ADB_SERIAL" shell wm dismiss-keyguard
adb -s "$ADB_SERIAL" shell am start -W -a android.intent.action.MAIN \
    -c android.intent.category.LAUNCHER -p "$package" | tee "$EMU_OUT/launch.log"
grep -q '^Status: ok' "$EMU_OUT/launch.log"
adb -s "$ADB_SERIAL" exec-out screencap -p > "$EMU_OUT/home.png"
test -s "$EMU_OUT/home.png"
