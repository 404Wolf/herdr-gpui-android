# Building the actual GPUI Android preview

This defaults to an ARM64 Android build of the Rust GPUI UI, hosted by an Android SurfaceView and GPUI Mobile’s process-lived render thread.
The separate `../../android` client remains the Java-native preview.
No script creates a replacement `.so` when Rust compilation fails. The packager
checks Rust source, build recipe, minSdk, and native library hashes against the
last successful build record, and rejects stale native binaries.

## Native build

Requirements: Rust 1.96 or newer, `aarch64-linux-android` Rust standard library,
an installed Android NDK with its license already accepted, Python 3, and a
Linux x86_64 or macOS build host. The GPUI APK requires Android 10/API 29+
because its upstream backend directly calls an API-29 native function; the
separate Java-native preview retains its older-device support. Install the Rust target with
`rustup target add aarch64-linux-android` if using rustup. The scripts neither
download the Android SDK/NDK nor accept Google's license.

From `android-gpui/`:

```sh
export ANDROID_NDK_HOME=/absolute/path/to/android-ndk
./scripts/build-native.sh
```

This builds the pinned standalone Rust workspace with `--locked`,
`--features android-ui`, Android API 29 (Android 10+), release optimization, 16KB ELF segment alignment,
and `--no-undefined` linker validation. It copies the verified library into
`app/src/main/jniLibs/arm64-v8a/libherdr_gpui_android.so`. A dependent NDK shared
C++ runtime is included only if the built ELF declares it; it currently does,
because the raw shell's terminal parser (rio-vt) uses the C++ `simdutf`
library. Rust tools and
`CARGO_HOME` may be task-local; no global Cargo configuration is written.
`CARGO_TARGET_DIR` optionally overrides the default `build/rust` output. The application
link rejects unresolved symbols; GPUI Mobile’s intermediate dependency cdylib is
not packaged, since its activity entry is supplied by the final application.

Before a full native build, this check can run without an NDK linker:

```sh
cargo check --manifest-path rust/Cargo.toml --locked \
  --features android-ui --target aarch64-linux-android
```

## Normal APK build

With an Android SDK installed and `ANDROID_HOME`/`local.properties` configured,
run `./gradlew assembleDebug` after the native build. Never use a debug key for
release distribution. The GPUI preview has a distinct application ID so it can
coexist with the Java-native preview.

## Isolated Linux verification APK

The verification packager prefers an installed SDK when `ANDROID_HOME` (or
`ANDROID_SDK_ROOT`) is set. It uses that SDK's API 36 `android.jar` and Build
Tools 35.0.0 AAPT2, D8, and apksig. This validates against the public Android
API surface without Gradle's dependency resolution:

```sh
export ANDROID_HOME=/absolute/path/to/android-sdk
./scripts/build-verification-apk.sh
```

If a full Android SDK is unavailable and those variables are unset, the verification packager uses official
Google Maven AAPT2, D8 and apksig plus Robolectric's API 36 framework JAR as a
build-time reference. Downloads are pinned by SHA-256 and cached locally:

```sh
./scripts/build-verification-apk.sh
```

`HERDR_BUILD_CACHE=/absolute/cache/path` can reuse those Maven artifacts. It
compiles the shared Java sources and the GPUI host, packages the real Rust
library, signs with a locally generated development-only key, verifies APK
v2/v3 signatures, and checks:

- ARM64 ELF type, SurfaceView lifecycle/input JNI exports, known system dependencies
- 16KB ELF load segments and 16KB ZIP alignment for native libraries
- Compiled resources, ZIP CRC, DEX checksums, required Java host classes

Output: `app/build/outputs/apk/manual/herdr-gpui-debug.apk`, with a sibling
AAPT2 badging report. This is a build-and-packaging check, not Android Lint,
Gradle verification, emulator execution, or proof of native rendering/IME.
No script installs on a device, connects to a user's daemon, or publishes.

Parser regression tests use in-memory non-executable fixtures:

```sh
python3 scripts/test-native-verifier.py
python3 scripts/test-native-build-record.py
python3 scripts/test-apk-verifier.py
```

Release automation numbers its per-commit preview release from the source version:

```sh
printf '%s\n' refs/tags/android-preview-0.5.0-gpui-remote.1 | python3 scripts/next-preview-release.py
```

`scripts/test-next-preview-release.py` covers that numbering, and
[RELEASE.md](../RELEASE.md) describes the releases it names: the first free
`android-preview-<versionName>.<number>` is claimed as a tag at the built commit,
and the release is published only after its assets match the verified build.

The native verifier intentionally rejects missing entry points, foreign CPU
architectures, 4KB-only load segments, and writable/executable segments. The
APK packager refuses to proceed without the real compiled Rust library.

## x86_64 VM test build

To test the same application in an x86_64 Android VM, install the official
`x86_64-linux-android` Rust standard library into your chosen toolchain, then run:

```sh
export HERDR_ANDROID_ABI=x86_64
export CARGO_BUILD_JOBS=3
./scripts/build-native.sh
./scripts/build-verification-apk.sh
```

The scripts use a separate `build/rust-x86_64` target directory,
`app/src/main/jniLibs/x86_64` staging directory, `build/native-build-x86_64.json`
build record, `build/manual-x86_64` packaging directory and
`app/build/outputs/apk/manual/herdr-gpui-x86_64-test.apk` output. Each APK contains
only the explicitly selected ABI. Gradle also defaults to ARM64 and honors the
same variable. Architecture, API 29, JNI exports and 16KB alignment checks remain
mandatory for both architectures. This test build has the same application ID
but its own development signing key, and cannot update an app signed by the
ARM64 packager's key without uninstalling it first. It should be installed only
on the selected disposable VM. x86_64 execution is not ARM64 device validation.

Build records include architecture and source/recipe hashes. Changing the
contents, names or presence of files under `rust/assets/` (including font
licenses) also invalidates the native freshness record. Changing the
build scripts invalidates an older native freshness record; rebuild its selected
architecture before repackaging. Existing output APKs and ARM64 libraries are
not replaced when building x86_64.

References: [Android 16KB pages](https://developer.android.com/guide/practices/page-sizes),
[Android NDK downloads and license](https://developer.android.com/ndk/downloads).

## Google Play App Bundle

The shipped app uses application ID `ch.robitzs.herdr`, compile/target SDK 36,
and Android App Bundles. Gradle uses the pinned Android Gradle Plugin 8.7.3 with
`android.suppressUnsupportedCompileSdk=36`; Build Tools 35.0.0 remain sufficient
for the current build. CI installs `platforms;android-36`, but never holds or
uses the Play upload key.

Create an upload key once, outside the repository, with a JDK:

```sh
./scripts/make-upload-key.sh
```

The default files are `~/.config/remote-for-herdr/upload-keystore.p12` and
`~/.config/remote-for-herdr/upload-keystore.pass`, with alias `upload`. The
script refuses to overwrite either file, uses a random 24-byte password, creates
a 4096-bit RSA PKCS12 key valid for 10,950 days, and prints its SHA-256
certificate fingerprint. Back up the keystore and password securely in separate
approved storage; never commit either file. For an isolated test key, override
`HERDR_UPLOAD_KEYSTORE`, `HERDR_UPLOAD_PASSWORD_FILE`, and optionally
`HERDR_UPLOAD_KEY_ALIAS` to paths in a temporary directory.

Build from `android-gpui/` after installing an Android NDK and Rust targets for
both `aarch64-linux-android` and `x86_64-linux-android`:

```sh
export ANDROID_NDK_HOME=/absolute/path/to/android-ndk
./scripts/build-bundle.sh
```

The script builds both 16KB-aligned native libraries with `build-native.sh`,
then uses the shared `android-tools.sh` resource-linking and Java/D8 pipeline in
release mode. Its local tool cache pins AAPT2, D8, apksig, Robolectric's
`android-all-16-robolectric-13921718` framework fallback, and bundletool 1.18.3
by SHA-256. It creates and signs the bundle with jarsigner, validates the bundle
and its non-debuggable manifest, then builds complete representative install
split sets for ARM64/API 29, ARM64/API 36 and x86_64/API 36. The APK-set verifier
checks unique split ownership, DEX integrity, the actual ABI libraries and
16KB ZIP alignment. The result is
`build/outputs/remote-for-herdr-<versionName>-<versionCode>.aab`; the script
prints its SHA-256. `HERDR_SKIP_NATIVE=1` reuses existing native builds only
when their source-bound build records still verify. To install a checked bundle
on an explicitly selected device, set `HERDR_INSTALL_SERIAL` to its adb serial.

`HERDR_UPLOAD_KEYSTORE`, `HERDR_UPLOAD_PASSWORD_FILE`, and
`HERDR_UPLOAD_KEY_ALIAS` configure the upload key paths and alias for the bundle
builder. If `ANDROID_HOME` or `ANDROID_SDK_ROOT` is set, the packaging helpers
use SDK platform 36 and Build Tools 35.0.0. Without an SDK they use pinned Google
Maven tools and the Robolectric API 36 framework JAR; the scripts do not install
the SDK or NDK, accept licenses, or communicate with Play.

## In-app About pages

`scripts/about-pages.py` generates the committed offline WebView assets in
`app/src/main/assets/about/`: `index.html`, `privacy.html`, `licenses.html`,
and `style.css`. The About page names the app and version, describes it as an
unofficial third-party Herdr client, and links to the privacy policy, licence
notices, public policy URL, and `herdr@robitzs.ch`. Privacy HTML is rendered
from `android-gpui/PRIVACY.md` using only headings, paragraphs, bullet lists,
bold text, and links; the stylesheet follows the system light/dark preference.

Run the generator after changing the policy, app version, or notices, then use
`python3 scripts/about-pages.py --check` to fail if the committed pages are
missing or stale. The licence page consumes the committed
`android-remote/THIRD-PARTY-NOTICES.txt` and `.inventory.json` rather than
walking Cargo dependencies again. It deduplicates text and fills packages with
missing upstream texts from SPDX standard texts under `scripts/licenses/`,
fetched from `spdx/license-list-data` at pinned revision
`31846481de7ea17d92f2f46fad76abe58b2e9bb0`. Generation fails if a package has
no declared non-copyleft option or a required standard text is absent, and
enforces a 1,500,000-byte maximum for `licenses.html` so the page stays suitable
for an offline WebView. The bundle build runs `--check` before packaging.
