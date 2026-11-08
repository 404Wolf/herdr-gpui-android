#!/usr/bin/env bash
# Separate Java-only native-view harness; does not load or replace the GPUI app.
set -euo pipefail
ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
: "${ANDROID_HOME:?Set ANDROID_HOME to an installed Android SDK}"
TOOLS="$ANDROID_HOME/build-tools/35.0.0"
API="$ANDROID_HOME/platforms/android-36/android.jar"
BUILD="$ROOT/build/review-tests"
mkdir -p "$BUILD/classes" "$BUILD/dex" "$BUILD/signing"
"$TOOLS/aapt2" link -I "$API" --manifest "$ROOT/review-tests/AndroidManifest.xml" --min-sdk-version 29 --target-sdk-version 35 --debug-mode -o "$BUILD/resources.apk"
SOURCES=("$ROOT"/review-tests/src/dev/herdr/android/*.java)
for name in DiffReviewModel DiffReviewPanel DiffReviewController GpuiChrome; do SOURCES+=("$ROOT/app/src/main/java/dev/herdr/android/$name.java"); done
SOURCES+=("$ROOT/tests/dev/herdr/android/DiffReviewSelfTest.java")
java com.sun.tools.javac.Main -source 8 -target 8 -encoding UTF-8 -Xlint:-options -bootclasspath "$API:$TOOLS/core-lambda-stubs.jar" -d "$BUILD/classes" "${SOURCES[@]}"
python3 - "$BUILD" <<'PY'
import pathlib,sys,zipfile
root=pathlib.Path(sys.argv[1])
with zipfile.ZipFile(root/'classes.jar','w') as jar:
    for p in sorted((root/'classes').rglob('*.class')): jar.write(p,p.relative_to(root/'classes').as_posix())
PY
java -cp "$TOOLS/lib/d8.jar" com.android.tools.r8.D8 --debug --min-api 29 --lib "$API" --output "$BUILD/dex" "$BUILD/classes.jar"
python3 - "$BUILD" <<'PY'
import pathlib,sys,shutil,zipfile
root=pathlib.Path(sys.argv[1]); shutil.copyfile(root/'resources.apk',root/'unsigned.apk')
with zipfile.ZipFile(root/'unsigned.apk','a') as apk:
    for p in sorted((root/'dex').glob('*.dex')): apk.write(p,p.name)
PY
if [[ ! -f "$BUILD/debug.keystore" ]]; then
    keytool -genkeypair -keystore "$BUILD/debug.keystore" -storepass android -keypass android -alias androiddebugkey -keyalg RSA -validity 10000 -dname 'CN=Android Debug' >/dev/null
fi
java com.sun.tools.javac.Main -cp "$TOOLS/lib/apksigner.jar" -d "$BUILD/signing" "$ROOT/scripts/SignDebugApk.java"
java -cp "$TOOLS/lib/apksigner.jar:$BUILD/signing" SignDebugApk "$BUILD/debug.keystore" "$BUILD/unsigned.apk" "$BUILD/review-tests.apk" 29
printf 'Built synthetic native-view harness: %s\n' "$BUILD/review-tests.apk"
