# Shared by build-verification-apk.sh (development APK) and build-bundle.sh.
# Sourced, not executed. ROOT must be the android-gpui directory.
CACHE="${HERDR_BUILD_CACHE:-$ROOT/build/manual-tools}"

require_host() {
    for program in "$@"; do
        command -v "$program" >/dev/null || { echo "Missing required command: $program" >&2; exit 1; }
    done
    [[ "$(uname -s)-$(uname -m)" == Linux-x86_64 ]] || {
        echo 'Manual Android packaging requires Linux x86_64 (AAPT2).' >&2; exit 1;
    }
}

fetch() {
    local name="$1" sha="$2" url="$3"
    mkdir -p "$CACHE"
    if [[ ! -f "$CACHE/$name" ]]; then
        local part
        part="$(mktemp "$CACHE/.download.XXXXXX")"
        if ! curl --fail --location --silent --show-error --retry 2 "$url" -o "$part"; then
            rm -f "$part"; return 1
        fi
        if ! echo "$sha  $part" | sha256sum --check --status; then
            rm -f "$part"; echo "Downloaded checksum mismatch: $name" >&2; return 1
        fi
        mv "$part" "$CACHE/$name"
    fi
    echo "$sha  $CACHE/$name" | sha256sum --check --quiet
}

fetch_bundletool() {
    fetch bundletool-all-1.18.3.jar a099cfa1543f55593bc2ed16a70a7c67fe54b1747bb7301f37fdfd6d91028e29 \
        https://github.com/google/bundletool/releases/download/1.18.3/bundletool-all-1.18.3.jar
    BUNDLETOOL_JAR="$CACHE/bundletool-all-1.18.3.jar"
}

configure_tools() {
    SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
    if [[ -n "$SDK" ]]; then
        TOOLS="$SDK/build-tools/35.0.0"
        FRAMEWORK="$SDK/platforms/android-36/android.jar"
        AAPT2="$TOOLS/aapt2"
        R8_JAR="$TOOLS/lib/d8.jar"
        APKSIG_JAR="$TOOLS/lib/apksigner.jar"
        BOOT_CLASSES="$FRAMEWORK:$TOOLS/core-lambda-stubs.jar"
        for path in "$FRAMEWORK" "$AAPT2" "$R8_JAR" "$APKSIG_JAR" "$TOOLS/core-lambda-stubs.jar"; do
            [[ -f "$path" ]] || { echo "Missing Android SDK API 36 / Build Tools 35 component: $path" >&2; exit 1; }
        done
        printf 'Using Android SDK API 36 and Build Tools 35.0.0 from %s\n' "$SDK"
        return
    fi
    fetch aapt2-8.7.3-12006047-linux.jar 028916a040b58f34c9c89984e1bc48f8da6581662014f2b66391860def8b9c36 \
        https://dl.google.com/dl/android/maven2/com/android/tools/build/aapt2/8.7.3-12006047/aapt2-8.7.3-12006047-linux.jar
    fetch r8-8.7.18.jar 58366f77067207c39a17d469de7b05701d2877212a9c55201bcb0af43e59e903 \
        https://dl.google.com/dl/android/maven2/com/android/tools/r8/8.7.18/r8-8.7.18.jar
    fetch apksig-8.7.3.jar c070ed1394629d74641aa0906f60b2ffa1ee77e6366a1f93437f59717b1aeb89 \
        https://dl.google.com/dl/android/maven2/com/android/tools/build/apksig/8.7.3/apksig-8.7.3.jar
    fetch android-all-16-robolectric-13921718.jar 8b74a0a137330658d2f33f0dc715d42734f74ba8b2d7014fc2e95aa40d3f682d \
        https://repo.maven.apache.org/maven2/org/robolectric/android-all/16-robolectric-13921718/android-all-16-robolectric-13921718.jar
    FRAMEWORK="$CACHE/android-all-16-robolectric-13921718.jar"
    R8_JAR="$CACHE/r8-8.7.18.jar"
    APKSIG_JAR="$CACHE/apksig-8.7.3.jar"
    BOOT_CLASSES=''
    printf 'No Android SDK configured; using pinned tools and Robolectric API 36 framework reference\n'
}

read_app_settings() {
    local work="$1"
    python3 - "$ROOT" "$work" <<'PY'
import os, pathlib, re, sys, xml.etree.ElementTree as ET
root, work = map(pathlib.Path, sys.argv[1:])
gradle = (root / 'app/build.gradle').read_text(encoding='utf-8')
if re.search(r'^\s*(?:implementation|api|compileOnly|runtimeOnly|kapt|annotationProcessor)\b', gradle, re.MULTILINE):
    raise SystemExit('Manual build does not resolve app dependencies; use Gradle.')
def value(key, pattern):
    found = re.findall(r'^\s*' + re.escape(key) + r'\s+' + pattern + r'\s*$', gradle, re.MULTILINE)
    if len(found) != 1:
        raise SystemExit('Cannot read unambiguous ' + key + '; use Gradle.')
    return found[0]
settings = [value('applicationId', r"['\"]([A-Za-z0-9_.]+)['\"]"),
            value('minSdk', r'(\d+)'), value('targetSdk', r'(\d+)'),
            value('versionCode', r'(\d+)'), value('versionName', r"['\"]([^'\"]+)['\"]"),
            value('compileSdk', r'(\d+)'), value('namespace', r"['\"]([A-Za-z0-9_.]+)['\"]")]
settings[3] = os.environ.get('HERDR_VERSION_CODE', settings[3])
if settings[5] != '36':
    raise SystemExit('The pinned framework is Android API 36; use Gradle for another compileSdk.')
work.mkdir(parents=True, exist_ok=True)
(work / 'settings').write_text('\n'.join(settings) + '\n', encoding='utf-8')
ET.register_namespace('android', 'http://schemas.android.com/apk/res/android')
manifest = ET.parse(root / 'app/src/main/AndroidManifest.xml')
manifest.getroot().set('package', settings[0])
manifest.write(work / 'AndroidManifest.xml', encoding='utf-8', xml_declaration=True)
PY
}

prepare_sources() {
    local work="$1"
    mkdir -p "$work/classes" "$work/generated" "$work/dex" "$work/assets"
    read_app_settings "$work"
    if [[ -z "$SDK" ]]; then
        unzip -q "$CACHE/aapt2-8.7.3-12006047-linux.jar" aapt2 -d "$work"
        chmod +x "$work/aapt2"
        AAPT2="$work/aapt2"
    fi
    python3 - "$ROOT" "$work/assets" <<'PY'
import pathlib, shutil, sys
root, assets = map(pathlib.Path, sys.argv[1:])
for source in (root.parent / 'android/app/src/main/assets', root / 'app/src/main/assets'):
    if source.is_dir():
        shutil.copytree(source, assets, dirs_exist_ok=True)
PY
    local privacy="${HERDR_PRIVACY_SOURCE:-$ROOT/PRIVACY.md}"
    python3 "$ROOT/scripts/about-pages.py" --out "$work/assets/about" --privacy "$privacy"
    "$AAPT2" compile --dir "$ROOT/../android/app/src/main/res" -o "$work/resources.zip"
}

link_resources() {
    local work="$1" output="$2"
    shift 2
    mapfile -t SETTINGS < "$work/settings"
    "$AAPT2" link -o "$output" -I "$FRAMEWORK" \
        --manifest "$work/AndroidManifest.xml" --java "$work/generated" --custom-package "${SETTINGS[6]}" \
        --min-sdk-version "${SETTINGS[1]}" --target-sdk-version "${SETTINGS[2]}" \
        --version-code "${SETTINGS[3]}" --version-name "${SETTINGS[4]}" \
        --compile-sdk-version-code "${SETTINGS[5]}" -A "$work/assets" "$@" "$work/resources.zip"
}

compile_dex() {
    local work="$1" mode="$2"; local -a sources d8_mode javac_api
    mapfile -d '' sources < <(find "$ROOT/../android/app/src/main/java" "$ROOT/app/src/main/java" "$work/generated" -name '*.java' -print0)
    if [[ -n "$SDK" ]]; then
        javac_api=(-bootclasspath "$BOOT_CLASSES")
    else
        javac_api=(-classpath "$FRAMEWORK")
    fi
    java com.sun.tools.javac.Main -source 8 -target 8 -encoding UTF-8 -Xlint:-options \
        "${javac_api[@]}" -d "$work/classes" "${sources[@]}"
    python3 - "$work" <<'PY'
import pathlib, sys, zipfile
work = pathlib.Path(sys.argv[1])
with zipfile.ZipFile(work / 'classes.jar', 'w', zipfile.ZIP_DEFLATED) as jar:
    for path in sorted((work / 'classes').rglob('*.class')):
        jar.write(path, path.relative_to(work / 'classes').as_posix())
PY
    case "$mode" in
        debug) d8_mode=(--debug) ;;
        release) d8_mode=(--release) ;;
        *) echo "Unknown D8 mode: $mode" >&2; return 2 ;;
    esac
    local jdk
    jdk="$(dirname -- "$(dirname -- "$(readlink -f -- "$(command -v java)")")")"
    java -cp "$R8_JAR" com.android.tools.r8.D8 "${d8_mode[@]}" --min-api "${SETTINGS[1]}" \
        --lib "$FRAMEWORK" --lib "$jdk" --output "$work/dex" "$work/classes.jar"
}
