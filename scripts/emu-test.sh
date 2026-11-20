#!/usr/bin/env bash
# Slurm: lease-run --kind android --outputs <local-dir> -- scripts/emu-test.sh <test> [apk]
set -euo pipefail

if [[ $# -lt 1 || $# -gt 2 ]]; then
    echo "Usage: $0 <test-script> [apk]" >&2
    exit 2
fi

die() {
    echo "emu-test: $*" >&2
    exit 1
}

test_script=$1
[[ -f "$test_script" && -r "$test_script" ]] || die "Cannot read $test_script"
root=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
: "${ANDROID_HOME:?Enter the android-emu dev shell first}"

out_dir="${JOBRUN_OUTPUTS:-.}/emu-out"
mkdir -p -- "$out_dir"
EMU_OUT=$(mktemp -d "$(realpath -- "$out_dir")/run.XXXXXXXX")
export EMU_OUT
echo "Emulator artifacts: $EMU_OUT"

if [[ $# == 2 ]]; then
    [[ -f "$2" && -r "$2" ]] || die "Cannot read $2"
    EMU_APK=$(realpath -- "$2")
else
    (
        cd "$root/android-gpui" || exit 1
        export HERDR_ANDROID_ABI=x86_64
        ./scripts/build-native.sh && ./gradlew --no-daemon :app:assembleDebug \
            "-Pandroid.aapt2FromMavenOverride=$ANDROID_HOME/build-tools/35.0.0/aapt2"
    ) > "$EMU_OUT/build.log" 2>&1 || die "APK build failed. See build.log."
    EMU_APK="$root/android-gpui/app/build/outputs/apk/debug/app-debug.apk"
fi
export EMU_APK
[[ -f "$EMU_APK" ]] || die "APK is missing: $EMU_APK"

# Start the host ADB server before isolating the AVD's home and target every
# device command by the console port reported by our emulator.
timeout 15s adb start-server
emu_home=$(mktemp -d)
emu_pid=
test_pid=
ADB_SERIAL=
# ShellCheck does not see the EXIT trap call when the script exits explicitly.
# shellcheck disable=SC2329
cleanup() {
    local status=$?
    trap '' HUP INT TERM
    if [[ -n "$test_pid" ]]; then
        kill "$test_pid" 2>/dev/null || true
    fi
    if [[ -n "$emu_pid" ]]; then
        if [[ -n "$ADB_SERIAL" ]] && kill -0 "$emu_pid" 2>/dev/null; then
            timeout -k 1s 5s adb -s "$ADB_SERIAL" logcat -b all -d \
                > "$EMU_OUT/logcat.log" 2>&1 || true
        fi
        kill -TERM "$emu_pid" 2>/dev/null || true
        local stop_deadline=$((SECONDS + 5))
        while kill -0 "$emu_pid" 2>/dev/null && (( SECONDS < stop_deadline )); do
            sleep 0.2
        done
        kill -KILL "$emu_pid" 2>/dev/null || true
        wait "$emu_pid" 2>/dev/null || true
    fi
    rm -rf -- "$emu_home"
    exit "$status"
}
trap 'cleanup' EXIT
trap 'exit 129' HUP
trap 'exit 130' INT
trap 'exit 143' TERM

export ANDROID_USER_HOME="$emu_home/.android"
export ANDROID_EMULATOR_HOME="$ANDROID_USER_HOME"
export ANDROID_AVD_HOME="$ANDROID_USER_HOME/avd"
mkdir -p "$ANDROID_AVD_HOME"
HOME="$emu_home" avdmanager create avd --name herdr-test \
    --package 'system-images;android-34;google_apis;x86_64' \
    --device pixel_6 <<< 'no' > "$EMU_OUT/avdmanager.log" 2>&1 \
    || die "Could not create AVD. See avdmanager.log."

(
    cd "$emu_home"
    HOME="$emu_home" exec emulator -avd herdr-test \
        -no-window -no-audio -no-snapshot-save -gpu swiftshader_indirect -memory 4096 \
        -report-console unix:console.sock,server
) > "$EMU_OUT/emulator.log" 2>&1 &
emu_pid=$!

console_deadline=$((SECONDS + 30))
while :; do
    kill -0 "$emu_pid" 2>/dev/null || die "Emulator exited. See emulator.log."
    if [[ -S "$emu_home/console.sock" ]] \
        && port=$(cd "$emu_home" && timeout 5s socat -u UNIX-CONNECT:console.sock - 2>/dev/null); then
        break
    fi
    (( SECONDS < console_deadline )) || die "Emulator did not report a console. See emulator.log."
    sleep 0.2
done
[[ "$port" =~ ^[0-9]+$ ]] || die "Invalid emulator console port: $port"
export ADB_SERIAL="emulator-$port"
export ANDROID_SERIAL="$ADB_SERIAL"

boot_deadline=$((SECONDS + 180))
while :; do
    kill -0 "$emu_pid" 2>/dev/null || die "Emulator exited. See emulator.log."
    boot=$(timeout 3s adb -s "$ADB_SERIAL" shell getprop sys.boot_completed 2>/dev/null) || boot=
    [[ "${boot//$'\r'/}" == 1 ]] && break
    (( SECONDS < boot_deadline )) || die "Emulator boot timed out. See emulator.log and logcat.log."
    sleep 1
done

timeout 120s adb -s "$ADB_SERIAL" install "$EMU_APK" > "$EMU_OUT/install.log" 2>&1 \
    || die "APK installation failed. See install.log."
HOME="$emu_home" bash "$test_script" > "$EMU_OUT/test.log" 2>&1 &
test_pid=$!
test_status=0
wait "$test_pid" || test_status=$?
test_pid=
exit "$test_status"
