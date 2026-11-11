#!/usr/bin/env bash
# Package the native library from build-native.sh into an unsigned, zipaligned
# release APK for a caller that signs it (a distribution's own key, or Nix
# installing with a key it keeps out of the build). Same Java host and
# resources as build-verification-apk.sh; release D8, not debuggable, and
# byte-for-byte reproducible from the same inputs.
#
# Usage: package-release-apk.sh OUT.apk
# Needs ANDROID_HOME (API 36, Build Tools 35.0.0). HERDR_VERSION_CODE overrides
# the versionCode in app/build.gradle.
set -euo pipefail
ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
# shellcheck source=android-tools.sh
source "$ROOT/scripts/android-tools.sh"
OUT="${1:?usage: package-release-apk.sh OUT.apk}"
ABI="${HERDR_ANDROID_ABI:-arm64-v8a}"
case "$ABI" in
    arm64-v8a) BUILD_SUFFIX= ;;
    x86_64) BUILD_SUFFIX=-x86_64 ;;
    *) echo "Unsupported Android ABI: $ABI" >&2; exit 1 ;;
esac
NATIVE="$ROOT/app/src/main/jniLibs/$ABI"
[[ -f "$NATIVE/libherdr_gpui_android.so" ]] || { echo 'Run scripts/build-native.sh first; actual GPUI library missing.' >&2; exit 1; }
python3 "$ROOT/scripts/native-build-record.py" verify "$ROOT" "$ROOT/build/native-build$BUILD_SUFFIX.json" \
    "$NATIVE/libherdr_gpui_android.so" --abi "$ABI"
[[ -n "${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}" ]] || { echo 'Set ANDROID_HOME to an Android SDK with API 36 and Build Tools 35.0.0.' >&2; exit 1; }
require_host java python3 unzip
configure_tools
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
prepare_sources "$WORK"
link_resources "$WORK" "$WORK/resources.apk"
compile_dex "$WORK" release
python3 - "$WORK" "$NATIVE" "$ABI" <<'PY'
import pathlib, shutil, sys, zipfile
work, native, abi = pathlib.Path(sys.argv[1]), pathlib.Path(sys.argv[2]), sys.argv[3]
shutil.copyfile(work / 'resources.apk', work / 'unsigned.apk')
entries = [(path.name, path) for path in sorted((work / 'dex').glob('*.dex'))]
for libraries in (native, work / 'aar-jni' / abi):  # the GPUI library, then Vosk and JNA's
    entries += [(f'lib/{abi}/{path.name}', path) for path in sorted(libraries.glob('*.so'))]
with zipfile.ZipFile(work / 'unsigned.apk', 'a', zipfile.ZIP_STORED) as apk:
    for name, path in entries:
        # A fixed timestamp and mode keep the archive independent of build time.
        info = zipfile.ZipInfo(name, (1980, 1, 1, 0, 0, 0))
        info.external_attr = 0o644 << 16
        apk.writestr(info, path.read_bytes())
PY
# 16 KiB pages for native libraries (Android 15+ devices), 4 bytes for the rest.
"$TOOLS/zipalign" -P 16 -f 4 "$WORK/unsigned.apk" "$WORK/aligned.apk"
python3 "$ROOT/scripts/verify-apk-structure.py" --abi "$ABI" "$WORK/aligned.apk"
# sed reads to the end: head would SIGPIPE aapt2 and fail under pipefail.
"$AAPT2" dump badging "$WORK/aligned.apk" | sed -n 1p
mkdir -p "$(dirname -- "$OUT")"
cp "$WORK/aligned.apk" "$OUT"
printf 'Built unsigned release APK %s; sign it before installing.\n' "$OUT"
