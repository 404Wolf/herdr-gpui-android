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
adb_before_deadline() {
    local remaining=$((deadline - SECONDS))
    (( remaining > 0 )) || return 1
    timeout -k 1s "${remaining}s" adb -s "$ADB_SERIAL" "$@"
}

home_is_rendered() {
    local hierarchy=/data/local/tmp/herdr-smoke-home.xml
    adb_before_deadline shell dumpsys window windows > "$EMU_OUT/windows.log" \
        && awk -v package="$package" '
            /^[[:space:]]*Window #[0-9]+ Window[{]/ {
                if (starting && app) pending = 1
                starting = tolower($0) ~ /splash screen|starting[[:space:]]*window/
                app = index($0, package) > 0
                window = 1
            }
            window && /mAttrs=|mActivityRecord=/ {
                if (/ty=APPLICATION_STARTING/) starting = 1
                if (index($0, package)) app = 1
            }
            END { exit (!window || pending || (starting && app)) }
        ' "$EMU_OUT/windows.log" \
        && adb_before_deadline shell "rm -f '$hierarchy' && uiautomator dump '$hierarchy'" \
            > "$EMU_OUT/uiautomator.log" 2>&1 \
        && adb_before_deadline exec-out cat "$hierarchy" > "$EMU_OUT/home.xml" \
        && grep -Fq -e 'text="No hosts yet.' \
            -e 'content-desc="Search machines, spaces and agents (Ctrl+K)"' "$EMU_OUT/home.xml"
}

# Activity resume can precede Home's first frame while the splash is still up.
while (( SECONDS < deadline )); do
    if adb_before_deadline shell dumpsys activity activities > "$EMU_OUT/activity.log" \
        && awk -v full="$component" -v short="$short_component" '
            /^[[:space:]]*(topResumedActivity=|mResumedActivity:|ResumedActivity:)/ {
                if (index($0, " " full " ") || index($0, " " short " ")) resumed = 1
            }
            END { exit !resumed }
        ' "$EMU_OUT/activity.log" && home_is_rendered; then
        timeout -k 1s 10s adb -s "$ADB_SERIAL" exec-out screencap -p > "$EMU_OUT/home.png"
        test -s "$EMU_OUT/home.png"
        exit 0
    fi
    sleep 1
done
echo "Home was not rendered within 60s: $component. See activity.log, windows.log and the UI dump." >&2
exit 1
