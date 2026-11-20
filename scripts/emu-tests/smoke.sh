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
activity=$(sed -n "s/^launchable-activity: name='\([^']*\)'.*/\1/p" <<< "$badging")
# HomeActivity is the launcher declared in android-gpui/app/src/main/AndroidManifest.xml.
activity=${activity:-dev.herdr.android.HomeActivity}
[[ "$activity" != .* ]] || activity="$package$activity"
component="$package/$activity"
short_component="$component"
[[ "$activity" != "$package".* ]] || short_component="$package/${activity#"$package"}"

grep -qx 'application-debuggable' <<< "$badging" || {
    echo "The Home screenshot requires a debuggable APK" >&2
    exit 1
}
adb -s "$ADB_SERIAL" shell settings put global herdr_probe 1
adb -s "$ADB_SERIAL" shell input keyevent KEYCODE_WAKEUP
adb -s "$ADB_SERIAL" shell wm dismiss-keyguard
timeout -k 1s 60s adb -s "$ADB_SERIAL" shell am start -W -n "$component" \
    | tee "$EMU_OUT/launch.log"
grep -q '^Status: ok' "$EMU_OUT/launch.log"

deadline=$((SECONDS + 60))
while :; do
    if timeout -k 1s 5s adb -s "$ADB_SERIAL" shell dumpsys activity activities > "$EMU_OUT/activity.log" \
        && awk -v full="$component" -v short="$short_component" '
            /^[[:space:]]*(topResumedActivity=|mResumedActivity:|ResumedActivity:)/ {
                if (index($0, " " full " ") || index($0, " " short " ")) resumed = 1
            }
            END { exit !resumed }
        ' "$EMU_OUT/activity.log"; then
        break
    fi
    if (( SECONDS >= deadline )); then
        echo "Activity did not resume: $component. See activity.log." >&2
        exit 1
    fi
    sleep 1
done
adb -s "$ADB_SERIAL" exec-out screencap -p > "$EMU_OUT/home.png"
test -s "$EMU_OUT/home.png"
