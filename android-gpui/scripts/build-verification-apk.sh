#!/usr/bin/env bash
# Package a real single-ABI GPUI cdylib with the Android Java host.
# Build scripts never download the Android SDK/NDK or accept its license.
set -euo pipefail
ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
# shellcheck source=android-tools.sh
source "$ROOT/scripts/android-tools.sh"
ABI="${HERDR_ANDROID_ABI:-arm64-v8a}"
case "$ABI" in
    arm64-v8a) BUILD_SUFFIX=; APK_NAME=herdr-gpui-debug ;;
    x86_64) BUILD_SUFFIX=-x86_64; APK_NAME=herdr-gpui-x86_64-test ;;
    *) echo "Unsupported Android ABI: $ABI" >&2; exit 1 ;;
esac
BUILD="$ROOT/build/manual$BUILD_SUFFIX"
OUT="$ROOT/app/build/outputs/apk/manual"
NATIVE="$ROOT/app/src/main/jniLibs/$ABI/libherdr_gpui_android.so"
[[ -f "$NATIVE" ]] || { echo 'Run scripts/build-native.sh first; actual GPUI library missing.' >&2; exit 1; }
python3 "$ROOT/scripts/verify-native-library.py" --abi "$ABI" "$NATIVE"
python3 "$ROOT/scripts/native-build-record.py" verify "$ROOT" "$ROOT/build/native-build$BUILD_SUFFIX.json" "$NATIVE" --abi "$ABI"
require_host java keytool python3 curl unzip sha256sum
mkdir -p "$BUILD" "$OUT"
java com.sun.tools.javac.Main -version
configure_tools
WORK="$(mktemp -d "$BUILD/work.XXXXXX")"
trap 'rm -rf "$WORK"' EXIT
prepare_sources "$WORK"
link_resources "$WORK" "$WORK/resources.apk" --debug-mode
compile_dex "$WORK" debug
python3 - "$WORK" "$ROOT/app/src/main/jniLibs" "$ABI" <<'PY'
import pathlib, shutil, sys, zipfile
work = pathlib.Path(sys.argv[1])
shutil.copyfile(work / 'resources.apk', work / 'unsigned.apk')
with zipfile.ZipFile(work / 'unsigned.apk', 'a', zipfile.ZIP_STORED) as apk:
    for path in sorted((work / 'dex').glob('*.dex')):
        apk.write(path, path.name)
    libraries = pathlib.Path(sys.argv[2])
    for path in sorted((libraries / sys.argv[3]).glob('*.so')):
        apk.write(path, 'lib/' + path.relative_to(libraries).as_posix())
PY
# Conventional development-only key, generated locally; never use for release/publication.
if [[ ! -f "$BUILD/debug.keystore" ]]; then
    keytool -genkeypair -keystore "$BUILD/debug.keystore" -storetype JKS \
        -storepass android -keypass android -alias androiddebugkey \
        -keyalg RSA -keysize 2048 -validity 10000 \
        -dname 'CN=Android Debug,O=Android,C=US' >/dev/null
fi
java com.sun.tools.javac.Main -classpath "$APKSIG_JAR" -d "$WORK/signing" \
    "$ROOT/scripts/SignDebugApk.java"
java -classpath "$APKSIG_JAR:$WORK/signing" SignDebugApk \
    "$BUILD/debug.keystore" "$WORK/unsigned.apk" "$WORK/$APK_NAME.apk" "${SETTINGS[1]}"
python3 "$ROOT/scripts/verify-apk-structure.py" --abi "$ABI" "$WORK/$APK_NAME.apk"
"$AAPT2" dump badging "$WORK/$APK_NAME.apk" > "$WORK/badging.txt"
cat "$WORK/badging.txt"
cp "$WORK/$APK_NAME.apk" "$OUT/$APK_NAME.apk"
cp "$WORK/badging.txt" "$OUT/$APK_NAME.badging.txt"
sha256sum "$OUT/$APK_NAME.apk"
printf '\nBuilt %s\n' "$OUT/$APK_NAME.apk"
printf 'Verified actual %s GPUI library, packaging and signatures; no emulator or device execution is implied.\n' "$ABI"
