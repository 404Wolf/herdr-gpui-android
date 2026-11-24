#!/usr/bin/env bash
# Build libherdr_whisper (whisper.cpp, MIT, from the pinned source in WHISPER_CPP_SRC, plus the
# JNI shim in whisper/) for the APK's ABI, or with --host OUT for the JVM tests. CPU only; the
# model is not part of the APK (see DICTATION.md, "The phone's Whisper").
set -euo pipefail
ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
SRC="${WHISPER_CPP_SRC:-}"
if [[ -z "$SRC" || ! -f "$SRC/include/whisper.h" ]]; then
    echo 'Set WHISPER_CPP_SRC to the whisper.cpp source (nix develop .#android-emu does).' >&2
    exit 1
fi
command -v cmake >/dev/null || { echo 'cmake is required (nix develop .#android-emu has it).' >&2; exit 1; }
JOBS="${HERDR_NATIVE_JOBS:-4}"

if [[ "${1:-}" == --host ]]; then
    OUT="${2:?usage: build-whisper.sh --host OUT_DIR}"
    cmake -S "$ROOT/whisper" -B "$OUT" -DWHISPER_CPP_SRC="$SRC" -DCMAKE_BUILD_TYPE=Release -Wno-dev >/dev/null
    cmake --build "$OUT" -j "$JOBS" --target herdr_whisper
    printf 'Built host %s\n' "$OUT/libherdr_whisper.so"
    exit 0
fi

NDK="${ANDROID_NDK_HOME:-${ANDROID_NDK_ROOT:-}}"
[[ -n "$NDK" && -f "$NDK/build/cmake/android.toolchain.cmake" ]] || {
    echo 'Set ANDROID_NDK_HOME to an installed Android NDK.' >&2; exit 1; }
ABI="${HERDR_ANDROID_ABI:-arm64-v8a}"
case "$ABI" in arm64-v8a|x86_64) ;; *) echo "Unsupported Android ABI: $ABI" >&2; exit 1 ;; esac
API="$(sed -n 's/.*\bminSdk[[:space:]]\+\([0-9]\+\).*/\1/p' "$ROOT/app/build.gradle")"
[[ "$API" =~ ^[0-9]+$ ]] || { echo 'Cannot read minSdk from app/build.gradle' >&2; exit 1; }
BUILD="$ROOT/build/whisper-$ABI"
cmake -S "$ROOT/whisper" -B "$BUILD" -DWHISPER_CPP_SRC="$SRC" -DCMAKE_BUILD_TYPE=Release -Wno-dev \
    -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
    -DANDROID_ABI="$ABI" -DANDROID_PLATFORM="android-$API" -DANDROID_STL=c++_static >/dev/null
cmake --build "$BUILD" -j "$JOBS" --target herdr_whisper
LIB="$BUILD/libherdr_whisper.so"
"$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip" --strip-unneeded -o "$BUILD/stripped.so" "$LIB"
python3 "$ROOT/scripts/verify-native-library.py" --runtime --abi "$ABI" "$BUILD/stripped.so"
DEST="$ROOT/app/src/main/jniLibs/$ABI"
mkdir -p "$DEST"
install -m 0644 "$BUILD/stripped.so" "$DEST/libherdr_whisper.so"
sha256sum "$DEST/libherdr_whisper.so"
printf 'Built whisper.cpp %s library: %s (%s bytes)\n' "$ABI" "$DEST/libherdr_whisper.so" "$(stat -c %s "$DEST/libherdr_whisper.so")"
