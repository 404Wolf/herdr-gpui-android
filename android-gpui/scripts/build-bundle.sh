#!/usr/bin/env bash
# Build and verify a Play-signed Android App Bundle without Gradle or CI secrets.
# Both ABI libraries are produced by build-native.sh unless HERDR_SKIP_NATIVE=1.
set -euo pipefail
ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
# shellcheck source=android-tools.sh
source "$ROOT/scripts/android-tools.sh"
ABIS=(arm64-v8a x86_64)
KEYSTORE="${HERDR_UPLOAD_KEYSTORE:-$HOME/.config/remote-for-herdr/upload-keystore.p12}"
PASSWORD_FILE="${HERDR_UPLOAD_PASSWORD_FILE:-${KEYSTORE%.*}.pass}"
ALIAS="${HERDR_UPLOAD_KEY_ALIAS:-upload}"
PRIVACY_SOURCE="${HERDR_PRIVACY_SOURCE:-$ROOT/PRIVACY.md}"
OUT="$ROOT/build/outputs"
require_host java jarsigner python3 curl unzip sha256sum grep
[[ -f "$KEYSTORE" && -f "$PASSWORD_FILE" ]] || {
    echo "Upload key not found at $KEYSTORE; create it with scripts/make-upload-key.sh" >&2
    exit 1
}
[[ -f "$PRIVACY_SOURCE" ]] || { echo "Privacy policy not found: $PRIVACY_SOURCE" >&2; exit 1; }
mkdir -p "$ROOT/build" "$OUT"
WORK="$(mktemp -d "$ROOT/build/bundle.XXXXXX")"
trap 'rm -rf "$WORK"' EXIT
configure_tools
read_app_settings "$WORK"
mapfile -t SETTINGS < "$WORK/settings"
APP_ID="${SETTINGS[0]}"
MIN_SDK="${SETTINGS[1]}"
TARGET_SDK="${SETTINGS[2]}"
VERSION_CODE="${SETTINGS[3]}"
VERSION_NAME="${SETTINGS[4]}"
BUNDLE="$OUT/remote-for-herdr-$VERSION_NAME-$VERSION_CODE.aab"

python3 "$ROOT/scripts/about-pages.py" --check --privacy "$PRIVACY_SOURCE"
if [[ "${HERDR_SKIP_NATIVE:-0}" != 1 ]]; then
    for abi in "${ABIS[@]}"; do
        HERDR_ANDROID_ABI="$abi" "$ROOT/scripts/build-native.sh"
    done
fi
for abi in "${ABIS[@]}"; do
    case "$abi" in
        arm64-v8a) suffix= ;;
        x86_64) suffix=-x86_64 ;;
    esac
    library="$ROOT/app/src/main/jniLibs/$abi/libherdr_gpui_android.so"
    [[ -f "$library" ]] || { echo "Native $abi library missing; run scripts/build-native.sh" >&2; exit 1; }
    python3 "$ROOT/scripts/verify-native-library.py" --abi "$abi" --min-sdk "$MIN_SDK" "$library"
    python3 "$ROOT/scripts/native-build-record.py" verify "$ROOT" "$ROOT/build/native-build$suffix.json" "$library" --abi "$abi"
done
fetch_bundletool
prepare_sources "$WORK"
mapfile -t SETTINGS < "$WORK/settings"
# No --debug-mode: Google Play rejects debuggable apps. D8 also runs in release mode.
link_resources "$WORK" "$WORK/linked.apk" --proto-format
compile_dex "$WORK" release

# Convert AAPT2's proto resource package to bundletool's base module layout.
python3 - "$WORK" "$ROOT/app/src/main/jniLibs" "${ABIS[@]}" <<'PY'
import pathlib, sys, zipfile
work, libraries, abis = pathlib.Path(sys.argv[1]), pathlib.Path(sys.argv[2]), sys.argv[3:]
with zipfile.ZipFile(work / 'linked.apk') as linked, \
        zipfile.ZipFile(work / 'base.zip', 'w', zipfile.ZIP_DEFLATED) as module:
    for info in linked.infolist():
        name = info.filename
        if name == 'AndroidManifest.xml':
            name = 'manifest/AndroidManifest.xml'
        elif name != 'resources.pb' and not name.startswith(('res/', 'assets/')):
            raise SystemExit(f'Unexpected entry in linked resources: {name}')
        module.writestr(name, linked.read(info))
    for path in sorted((work / 'dex').glob('*.dex')):
        module.write(path, f'dex/{path.name}')
    for abi in abis:
        paths = sorted((libraries / abi).glob('*.so'))
        if not paths:
            raise SystemExit(f'No shared libraries found for {abi}')
        for path in paths:
            module.write(path, f'lib/{abi}/{path.name}')
PY
cat > "$WORK/BundleConfig.json" <<'JSON'
{
  "optimizations": {
    "splitsConfig": {
      "splitDimension": [
        {"value": "ABI"},
        {"value": "SCREEN_DENSITY"},
        {"value": "LANGUAGE"}
      ]
    },
    "uncompressNativeLibraries": {
      "enabled": true,
      "alignment": "PAGE_ALIGNMENT_16K"
    }
  }
}
JSON
BUNDLETOOL=(java -jar "$BUNDLETOOL_JAR")
"${BUNDLETOOL[@]}" build-bundle --modules="$WORK/base.zip" --config="$WORK/BundleConfig.json" --output="$WORK/unsigned.aab"
jarsigner -keystore "$KEYSTORE" -storetype PKCS12 -storepass:file "$PASSWORD_FILE" \
    -sigalg SHA256withRSA -digestalg SHA-256 -signedjar "$WORK/remote-for-herdr.aab" \
    "$WORK/unsigned.aab" "$ALIAS" >/dev/null
jarsigner -verify "$WORK/remote-for-herdr.aab" | grep -q '^jar verified' || {
    echo 'Bundle signature does not verify' >&2; exit 1;
}
"${BUNDLETOOL[@]}" validate --bundle="$WORK/remote-for-herdr.aab"
"${BUNDLETOOL[@]}" dump manifest --bundle="$WORK/remote-for-herdr.aab" > "$WORK/manifest.xml"
python3 - "$WORK/manifest.xml" "$APP_ID" "$VERSION_CODE" "$VERSION_NAME" "$MIN_SDK" "$TARGET_SDK" <<'PY'
import sys, xml.etree.ElementTree as ET
path, app_id, version_code, version_name, min_sdk, target_sdk = sys.argv[1:]
android = '{http://schemas.android.com/apk/res/android}'
root = ET.parse(path).getroot()
sdk = root.find('uses-sdk')
application = root.find('application')
if sdk is None or application is None:
    raise SystemExit('Bundle manifest has no uses-sdk or application element')
found = {
    'package': root.get('package'),
    'versionCode': root.get(android + 'versionCode'),
    'versionName': root.get(android + 'versionName'),
    'minSdkVersion': sdk.get(android + 'minSdkVersion'),
    'targetSdkVersion': sdk.get(android + 'targetSdkVersion'),
}
wanted = {'package': app_id, 'versionCode': version_code, 'versionName': version_name,
          'minSdkVersion': min_sdk, 'targetSdkVersion': target_sdk}
if found != wanted:
    raise SystemExit(f'Bundle manifest {found} does not match app/build.gradle {wanted}')
if application.get(android + 'debuggable') not in (None, 'false'):
    raise SystemExit('Bundle is debuggable; Google Play rejects it')
print('Bundle manifest:', ', '.join(f'{key}={value}' for key, value in found.items()), '(not debuggable)')
PY

SIGNING=(--ks="$KEYSTORE" --ks-pass="file:$PASSWORD_FILE" --ks-key-alias="$ALIAS" --key-pass="file:$PASSWORD_FILE")
for device in "arm64-v8a $MIN_SDK 420" "arm64-v8a $TARGET_SDK 420" "x86_64 $TARGET_SDK 320"; do
    read -r abi sdk density <<< "$device"
    spec="$WORK/device-$abi-$sdk.json"
    printf '{"supportedAbis":["%s"],"supportedLocales":["en-US"],"screenDensity":%s,"sdkVersion":%s}\n' \
        "$abi" "$density" "$sdk" > "$spec"
    apks="$WORK/device-$abi-$sdk.apks"
    set_dir="$WORK/device-$abi-$sdk"
    "${BUNDLETOOL[@]}" build-apks --bundle="$WORK/remote-for-herdr.aab" --output="$apks" \
        --device-spec="$spec" --aapt2="$AAPT2" "${SIGNING[@]}"
    mkdir -p "$set_dir"
    unzip -q "$apks" '*.apk' -d "$set_dir"
    mapfile -t split_apks < <(find "$set_dir" -type f -name '*.apk' | sort)
    (( ${#split_apks[@]} > 0 )) || { echo "No APK splits generated for API $sdk $abi" >&2; exit 1; }
    printf 'Checking device split set: API %s %s (%s split APKs)\n' "$sdk" "$abi" "${#split_apks[@]}"
    python3 "$ROOT/scripts/verify-apk-structure.py" --abi "$abi" "${split_apks[@]}"
done

cp "$WORK/remote-for-herdr.aab" "$BUNDLE"
if [[ -n "${HERDR_INSTALL_SERIAL:-}" ]]; then
    command -v adb >/dev/null || { echo 'HERDR_INSTALL_SERIAL is set but adb is unavailable' >&2; exit 1; }
    "${BUNDLETOOL[@]}" build-apks --bundle="$BUNDLE" --output="$WORK/connected-device.apks" \
        --connected-device --device-id="$HERDR_INSTALL_SERIAL" --adb="$(command -v adb)" \
        --aapt2="$AAPT2" "${SIGNING[@]}"
    "${BUNDLETOOL[@]}" install-apks --apks="$WORK/connected-device.apks" \
        --device-id="$HERDR_INSTALL_SERIAL" --adb="$(command -v adb)"
fi
sha256sum "$BUNDLE"
printf '\nBuilt %s\n' "$BUNDLE"
