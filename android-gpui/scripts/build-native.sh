#!/usr/bin/env bash
# Build the actual GPUI Vulkan activity; never creates a stand-in shared library.
set -euo pipefail
ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
NDK="${ANDROID_NDK_HOME:-${ANDROID_NDK_ROOT:-}}"
if [[ -z "$NDK" || ! -f "$NDK/source.properties" ]]; then
    echo 'Set ANDROID_NDK_HOME to an installed Android NDK whose license you have accepted.' >&2
    echo 'This script does not download an SDK/NDK or accept its license.' >&2
    exit 1
fi
case "$(uname -s)-$(uname -m)" in
    Linux-x86_64) HOST_TAG=linux-x86_64 ;;
    Darwin-*) HOST_TAG=darwin-x86_64 ;;
    *) echo 'Unsupported NDK build host; use Linux x86_64 or macOS.' >&2; exit 1 ;;
esac
BIN="$NDK/toolchains/llvm/prebuilt/$HOST_TAG/bin"
ABI="${HERDR_ANDROID_ABI:-arm64-v8a}"
case "$ABI" in
    arm64-v8a) TARGET=aarch64-linux-android; BUILD_SUFFIX= ;;
    x86_64) TARGET=x86_64-linux-android; BUILD_SUFFIX=-x86_64 ;;
    *) echo "Unsupported Android ABI: $ABI" >&2; exit 1 ;;
esac
TARGET_ENV="${TARGET//-/_}"
TARGET_UPPER="$(printf '%s' "$TARGET_ENV" | tr '[:lower:]' '[:upper:]')"
# Link against the same minimum API advertised by the APK.
API="$(python3 - "$ROOT/app/build.gradle" <<'PYAPI'
import pathlib, re, sys
matches = re.findall(r"\bminSdk\s+(\d+)", pathlib.Path(sys.argv[1]).read_text())
if len(matches) != 1:
    raise SystemExit('Cannot read unambiguous minSdk from app/build.gradle')
print(matches[0])
PYAPI
)"
for tool in "$BIN/${TARGET}${API}-clang" "$BIN/${TARGET}${API}-clang++" "$BIN/llvm-ar"; do
    [[ -x "$tool" ]] || { echo "Missing NDK tool: $tool" >&2; exit 1; }
done
command -v cargo >/dev/null
command -v rustc >/dev/null
[[ -d "$(rustc --print target-libdir --target "$TARGET")" ]] || {
    echo "Install the $TARGET Rust standard library before building." >&2; exit 1;
}
export CARGO_TARGET_DIR="${CARGO_TARGET_DIR:-$ROOT/build/rust$BUILD_SUFFIX}"
export "CARGO_TARGET_${TARGET_UPPER}_LINKER=$BIN/${TARGET}${API}-clang"
export "CC_${TARGET_ENV}=$BIN/${TARGET}${API}-clang"
export "CXX_${TARGET_ENV}=$BIN/${TARGET}${API}-clang++"
export "AR_${TARGET_ENV}=$BIN/llvm-ar"
# Scope target flags to Android only; build scripts and proc-macros run on the host.
FLAGS_VARIABLE="CARGO_TARGET_${TARGET_UPPER}_RUSTFLAGS"
export "$FLAGS_VARIABLE=${!FLAGS_VARIABLE:-} -C link-arg=-Wl,-z,max-page-size=16384 -C link-arg=-Wl,-z,common-page-size=16384"
export ANDROID_NDK_HOME="$NDK"
export ANDROID_NDK_ROOT="$NDK"
export ANDROID_API_LEVEL="$API"
mkdir -p "$ROOT/build"
RECORD="$(mktemp "$ROOT/build/native-inputs.XXXXXX")"
trap 'rm -f "$RECORD"' EXIT
python3 "$ROOT/scripts/native-build-record.py" snapshot "$ROOT" "$RECORD" --abi "$ABI"
# Only the application must resolve every symbol: the upstream dependency
# also emits a standalone cdylib whose activity entry is supplied by its consumer.
cargo rustc --lib --manifest-path "$ROOT/rust/Cargo.toml" --locked --target "$TARGET" \
    --release --features android-ui -- -C link-arg=-Wl,--no-undefined
LIB="$CARGO_TARGET_DIR/$TARGET/release/libherdr_gpui_android.so"
python3 "$ROOT/scripts/verify-native-library.py" --abi "$ABI" --min-sdk "$API" "$LIB"
python3 "$ROOT/scripts/native-build-record.py" record "$ROOT" "$RECORD" "$LIB" --min-sdk "$API" --abi "$ABI"
DEST="$ROOT/app/src/main/jniLibs/$ABI"
mkdir -p "$DEST"
cp "$LIB" "$DEST/libherdr_gpui_android.so"
mv "$RECORD" "$ROOT/build/native-build$BUILD_SUFFIX.json"
# Package the NDK C++ runtime only when this build actually needs it.
if "$BIN/llvm-readelf" -d "$LIB" | grep -q 'Shared library: \[libc++_shared.so\]'; then
    CPP="$NDK/toolchains/llvm/prebuilt/$HOST_TAG/sysroot/usr/lib/$TARGET/libc++_shared.so"
    python3 "$ROOT/scripts/verify-native-library.py" --abi "$ABI" --runtime "$CPP"
    # The NDK's copy is read-only (a Nix store, say): cp would carry that
    # mode over and refuse to overwrite it on the next build.
    install -m 0644 "$CPP" "$DEST/libc++_shared.so"
fi
sha256sum "$DEST/libherdr_gpui_android.so"
printf '\nBuilt actual GPUI %s Android library: %s\n' "$ABI" "$DEST/libherdr_gpui_android.so"
