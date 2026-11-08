# Native diffd review validation

Validated on 2026-11-08 with the pinned Rust 1.96.1 toolchain, JDK 21,
Android API 36 / Build Tools 35, and NDK 28.2.13676358 already installed through
Nix. Builds used at most four Cargo jobs. No SDK license was accepted by a script.

## Verified

From the repository root, with the host C++ runtime on `LD_LIBRARY_PATH`:

```sh
cargo test --locked --manifest-path android-gpui/rust/Cargo.toml --workspace --no-default-features
cargo test --locked --manifest-path android-gpui/rust/Cargo.toml --workspace --features remote
cargo clippy --locked --manifest-path android-gpui/rust/Cargo.toml --workspace --all-targets --features remote -- -D warnings
CARGO_TARGET_DIR="$PWD/android-gpui/build/transport-target" cargo test --locked --manifest-path android-remote/rust/Cargo.toml
CARGO_TARGET_DIR="$PWD/android-gpui/build/transport-target" cargo clippy --locked --manifest-path android-remote/rust/Cargo.toml --all-targets -- -D warnings
cargo fmt --manifest-path android-gpui/rust/Cargo.toml -p herdr-gpui-android -p gpui-rio -- --check
cargo fmt --manifest-path android-remote/rust/Cargo.toml -- --check
```

All passed: default app tests 53, remote app tests 65, gpui-rio tests 9,
and transport tests 41. The transport tests verify one authentication for multiple
loopback channels, channel closure even when the remote peer does not send EOF,
continued session use afterwards, and cancellation. Review tests cover HTTP
bounds/status/source errors, WebSocket endpoint and message ordering, aggregate
queue bounds, and cancellation during a write without flushing remaining commands.

The normal ARM64 build and manual packaging scripts were used:

```sh
CARGO_NET_OFFLINE=true CARGO_BUILD_JOBS=4 ANDROID_NDK_HOME="$NDK" bash android-gpui/scripts/build-native.sh
ANDROID_HOME="$SDK" bash android-gpui/scripts/build-verification-apk.sh
```

Native freshness, ARM64 ELF/API 29, 16 KiB load-segment alignment, required JNI
exports, APK contents and development signatures are checked by those scripts.
The build also replaces an existing read-only Nix NDK C++ runtime successfully.
The accompanying notice inventory includes the four new WebSocket dependencies;
the About generator validated 577 packages and 408 distinct notice texts.

## Native Android views

The separate `dev.herdr.android.reviewtest` harness compiles the production
review widgets/model with a synthetic feed; it does not load GPUI or connect SSH.
On an isolated API 35 x86_64 emulator:

```sh
ANDROID_HOME="$SDK" bash android-gpui/review-tests/build.sh
adb -s emulator-5580 install -r android-gpui/build/review-tests/review-tests.apk
python3 android-gpui/review-tests/run.py --serial emulator-5580 --out android-gpui/build/review-screenshots
```

Passed Files → diff → line comment, thread reply, resolve, chat acknowledgement,
and Session / Review switching. Startup checks passed label precedence, ambiguous
cwd refusal, atomic revision deltas, duplicate chat/thread updates, initial-share
notification suppression, changed labels, new agents and repeated snapshots.

Screenshots were captured and visually inspected: files, syntax/novel-token diff,
a line-comment draft with the keyboard, threads, resolved/replied threads, chat,
and the synthetic session. Long paths/titles were also inspected at 360 dp and
800 dp widths with system font scale 1.3: labels ellipsize within fixed-height
headers and rows, with no overlapping controls. Generated PNGs are under
`android-gpui/build/review-screenshots/`; they contain only synthetic content.
The emulator was stopped after validation.

### Density follow-up

The navigation now occupies one bar measuring 36 physical pixels at the tested
default font scales (it grows when needed for larger text). There is no separate
live-status row; status diagnostics are available from the dot or options menu.
Code uses a 1.3× font pitch, a padded title, continuous virtualized aligned rows,
shared horizontal scrolling with pinned gutters, and a menu wrap toggle.

The complete widget interaction run passed again with the compact controls,
including comments, replies, resolve, chat acknowledgement and Session switching.
The shared production layout measured exactly one 36-pixel bar removed from the
synthetic terminal viewport. A 600-row fixture reached its final row without
paging; only 44 row views were attached at the tested narrow size.

The layout-only run passed at 360 dp (720 px, density 320) and 800 dp (1200 px,
density 240). It checks co-located tabs, fixed gutter bounds after horizontal
gestures and increased code height after enabling wrapping. Files, unwrapped,
horizontally scrolled and wrapped screenshots were captured and inspected under
`android-gpui/build/review-screenshots-dense/`, with the wide set in `wide/` and
the final continuous row in `long/`. These remain synthetic native-view checks;
the actual activity uses the same layout method, but its GPUI terminal painting
and attached SSH session still require the phone check below. The ARM64 APK was
repackaged and its native freshness, contents and signatures verified. This
follow-up changes Java UI and test/docs only; no Rust sources changed.

## Remaining platform checks

The full ARM64 app was not installed on a physical phone. Native GPUI surface
composition, fold posture, physical keyboard/IME isolation with an attached
terminal, real diffd feedback delivery, and Android notification permission/delivery
still need a trusted-host phone check. The native-view harness is not proof of
that combined path. No personal daemon, terminal or phone profiles were changed.

`just format` and `git diff --check` passed. Repository-wide `just ci` reached an
environment failure: `fontconfig.pc` was missing from the shell's pkg-config path.
`just lint-windows` could not run without the Windows Rust target. `just lint-linux`
failed while rsync copied changing Android build artifacts (exit 24); it did not
complete its container lint. `just test-build` was stopped for the manager's shared
machine build hold. Those desktop gates are not reported as passed.
