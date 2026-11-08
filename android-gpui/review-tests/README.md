# Synthetic native diffd review QA

This **separate Java-only APK** compiles the production `DiffReviewPanel`,
`DiffReviewController`, `DiffReviewModel` and `GpuiChrome` without modifying them.
Its clearly marked fixture activity supplies synthetic diffd state and theme
roles. A test-only `RemoteNative` replaces JNI with a synthetic event feed.
It never loads GPUI, authenticates SSH, contacts a daemon or changes phone profiles.
It is native widget/layout/interaction evidence, not an end-to-end transport test.

`DiffReviewSelfTest` runs first and fails startup on broken label precedence,
ambiguous cwd fallback, revision fencing/atomicity, delta ordering, thread
replacement or duplicate chat handling. The fixture also checks initial-share
notification suppression, changed labels, new agents and duplicate snapshots.
Look for `MODEL TESTS PASSED` in logcat.

With an installed SDK (API 36 and Build Tools 35) and JDK:

```sh
ANDROID_HOME=/path/to/android-sdk bash android-gpui/review-tests/build.sh
adb -s emulator-5580 install -r android-gpui/build/review-tests/review-tests.apk
adb -s emulator-5580 shell am start -n dev.herdr.android.reviewtest/dev.herdr.android.FixtureActivity
adb -s emulator-5580 logcat -d -s DiffReviewFixture:I '*:S'
python3 android-gpui/review-tests/run.py --serial emulator-5580 --out android-gpui/build/review-screenshots
adb -s emulator-5580 exec-out screencap -p > /tmp/review-files.png
```

Use an explicitly selected emulator. The package `dev.herdr.android.reviewtest`
is separate from the real app. Check Files, the diff and a line-comment draft,
Threads with reply/resolve, Chat with an acknowledged synthetic message, and
Session / Review switching. Repeat at narrow and wide display sizes and after
changing system font scale. Screenshots contain synthetic content only.

The same run checks the single navigation bar, pinned gutter during horizontal
scroll, and wrapping from the options menu. Use `--layout-only` to stop after
those checks. Use `--long-diff` to check a 600-row diff and jump to its final row;
the fixture asserts that no more than 64 row views are attached. The fixture and
real activity share the terminal/review layout method. Switching to Session
asserts that its viewport loses exactly the measured compact bar height.

Real SSH channel reuse/cancellation and WebSocket endpoint/message ordering are
covered by Rust fixtures in `android-remote/rust/src/tests.rs` and
`android-gpui/rust/src/review.rs`. Real phone GPUI + SSH + diffd still needs a
manual check with the built ARM64 APK and a trusted host.

For layout checks, launch the fixture with `--ez long_labels true`, change the
emulator display width/density and system font scale, then inspect a screenshot.
See [the validation record](../DIFFD-VALIDATION.md) for the actual checks and limits.
