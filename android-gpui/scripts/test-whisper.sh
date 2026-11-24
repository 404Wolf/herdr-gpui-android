#!/usr/bin/env bash
# The phone's Whisper transcribing for real, off-device: the host build of libherdr_whisper (same
# shim, same pinned whisper.cpp) with MODEL (the pinned ggml-base.en-q5_1.bin, SHA-256
# 4baf70dd0d7c4247ba2b81fafd9c01005ac77c2f9ef064e00dcf195d0e2fdd2f) on whisper.cpp's JFK sample.
# Run in nix develop .#android-emu: scripts/test-whisper.sh /path/to/ggml-base.en-q5_1.bin
set -euo pipefail
ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
MODEL="$(realpath "${1:?usage: test-whisper.sh MODEL}")"
echo "4baf70dd0d7c4247ba2b81fafd9c01005ac77c2f9ef064e00dcf195d0e2fdd2f  $MODEL" | sha256sum -c --quiet
"$ROOT/scripts/build-whisper.sh" --host "$ROOT/build/whisper-host"
cd "$ROOT"
HERDR_WHISPER_LIB="$ROOT/build/whisper-host/libherdr_whisper.so" HERDR_WHISPER_MODEL="$MODEL" \
    HERDR_WHISPER_SAMPLE="$WHISPER_CPP_SRC/samples/jfk.wav" \
    ./gradlew --no-daemon --max-workers=4 -q :app:testDebugUnitTest --tests '*LocalWhisperTest*'
grep -o 'tests="[0-9]*" skipped="[0-9]*" failures="[0-9]*" errors="[0-9]*"' \
    app/build/test-results/testDebugUnitTest/TEST-dev.herdr.android.dictation.LocalWhisperTest.xml
