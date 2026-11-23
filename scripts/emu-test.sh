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
boot_timeout=${EMU_BOOT_TIMEOUT_SECONDS:-300}
[[ "$boot_timeout" =~ ^[1-9][0-9]*$ ]] || die "EMU_BOOT_TIMEOUT_SECONDS must be a positive integer."

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

# Android jobs put TMPDIR on disk to make room for the AVD's userdata.
emu_home=$(mktemp -d --tmpdir herdr-emu.XXXXXXXX)
emu_home=$(realpath -- "$emu_home")
emu_pid=
adb_pid=
test_pid=
ADB_SERIAL=
stop_process() {
    local pid=$1 deadline=$((SECONDS + 5))
    kill -TERM "$pid" 2>/dev/null || true
    while kill -0 "$pid" 2>/dev/null && (( SECONDS < deadline )); do
        sleep 0.2
    done
    kill -KILL "$pid" 2>/dev/null || true
    wait "$pid" 2>/dev/null || true
}

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
        stop_process "$emu_pid"
    fi
    if [[ -n "$adb_pid" ]]; then
        stop_process "$adb_pid"
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

# Pass an already bound loopback socket to ADB so concurrent jobs cannot claim
# the same port between picking it and starting the server.
# The emulator registers itself with this server, so disable discovery.
HOME="$emu_home" ADB_USB=0 ADB_MDNS=0 ADB_LOCAL_TRANSPORT_MAX_PORT=0 \
    python3 - "$emu_home/adb-port" > "$EMU_OUT/adb-server.log" 2>&1 <<'PY' &
import os
from pathlib import Path
import socket
import sys

with socket.socket() as listener:
    listener.bind(("127.0.0.1", 0))
    listener.listen()
    fd = listener.fileno()
    os.set_inheritable(fd, True)
    Path(sys.argv[1]).write_text(str(listener.getsockname()[1]))
    os.execvp("adb", ["adb", "-L", f"acceptfd:{fd}", "nodaemon", "server"])
PY
adb_pid=$!
adb_deadline=$((SECONDS + 15))
while [[ ! -s "$emu_home/adb-port" ]]; do
    kill -0 "$adb_pid" 2>/dev/null || die "ADB server exited. See adb-server.log."
    (( SECONDS < adb_deadline )) || die "ADB server did not report its port. See adb-server.log."
    sleep 0.2
done
ANDROID_ADB_SERVER_PORT=$(< "$emu_home/adb-port")
export ANDROID_ADB_SERVER_PORT
# ADB clients treat an explicit IP as remote and leave server startup to us.
export ADB_SERVER_SOCKET="tcp:127.0.0.1:$ANDROID_ADB_SERVER_PORT"
timeout -k 1s 15s adb server-status > /dev/null || die "ADB server did not start. See adb-server.log."

HOME="$emu_home" avdmanager create avd --name herdr-test \
    --package 'system-images;android-34;google_apis;x86_64' \
    --device "${EMU_DEVICE:-pixel_6}" <<< 'no' > "$EMU_OUT/avdmanager.log" 2>&1 \
    || die "Could not create AVD. See avdmanager.log."

(
    cd "$emu_home"
    HOME="$emu_home" exec emulator -avd herdr-test \
        -no-window -no-audio -no-snapshot-save -gpu swiftshader_indirect -memory "${EMU_MEMORY:-3072}" \
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

boot_deadline=$((SECONDS + boot_timeout))
while :; do
    kill -0 "$emu_pid" 2>/dev/null || die "Emulator exited. See emulator.log."
    boot=$(timeout 3s adb -s "$ADB_SERIAL" shell getprop sys.boot_completed 2>/dev/null) || boot=
    [[ "${boot//$'\r'/}" == 1 ]] && break
    (( SECONDS < boot_deadline )) || die "Emulator boot timed out after ${boot_timeout}s. See emulator.log and logcat.log."
    sleep 1
done

timeout 120s adb -s "$ADB_SERIAL" install "$EMU_APK" > "$EMU_OUT/install.log" 2>&1 \
    || die "APK installation failed. See install.log."
HOME="$emu_home" bash "$test_script" > "$EMU_OUT/test.log" 2>&1 &
test_pid=$!
test_status=0
wait "$test_pid" || test_status=$?
test_pid=
if (( test_status != 0 )); then
    exit "$test_status"
fi
