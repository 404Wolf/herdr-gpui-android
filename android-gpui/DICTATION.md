# Dictation

The composer's microphone button dictates text: tap to start, tap again to
stop. Silence never ends a session. Text appears as you speak, and the final
text replaces it about half a second after the second tap.

## How it works

```text
tap → ServerDictationEngine ── ws:// over the tailnet ──▶ dictation server (desktop)
        16 kHz PCM16, 100 ms frames                       websocket bridge (server.py)
        ◀── partial (~every 0.6 s), final after stop ──    whisper.cpp, Vulkan, RX 6600
                                                            large-v3-turbo q8_0
   server unreachable before listening?
        └─▶ VoskDictationEngine: offline, vosk-model-small-en-us-0.15 bundled in the APK
```

- `dev.herdr.android.dictation.DictationEngine` is the composer's interface.
  `Dictation.create(context)` picks the engine.
- The server URL is provisioned, never built in: put `"dictationUrl":
  "ws://<desktop tailnet address>:8770/v1/dictate"` in
  `remote-for-herdr-settings.json` (see `ProvisionedSettings`). Without it the
  offline Vosk engine is used alone.
- Recording starts on the tap and is buffered while the socket connects, so the
  first words are not lost.
- If the server cannot be reached within 2.5 s, the same session continues on
  offline Vosk, and the server is skipped for the next minute. A
  drop after listening began is reported as an error; the partial text already
  shown stays in the composer.
- Vosk ends an utterance at each pause and the engine keeps going, so only the
  second tap ends a session. The model (about 40 MB) is bundled by the build,
  not committed: Gradle takes `-PvoskAssetsDir=<dir holding vosk-model/>` and
  the manual scripts `HERDR_VOSK_ASSETS`; the Nix build in `~/Config/phone`
  passes vosk-model-small-en-us-0.15, as HeliBoard's does. A build without it
  has no offline fallback (the fallback reports UNAVAILABLE). The first offline
  use after an install copies the model to app storage, which takes a moment.
- Why not the phone's own SpeechRecognizer: on this GrapheneOS Fold the default
  recognizer is the disabled Google app, and the only registered recognition
  services are the Claude app's (online) and FUTO's placeholder, while
  `SpeechRecognizer.isRecognitionAvailable()` still answers yes.
- The server is a home-manager module, `users/wolf/programs/dictation` in
  `~/Config/home-manager` (`mine.dictation`): whisper-server on loopback, the
  websocket only on the tailnet address. Its protocol is documented at the top
  of `server.py`.

## Why this design: measurements (11 November 2026)

Clips: 73 LibriSpeech dev-clean utterances (8 min, 6.6 s mean); 3 of the same
joined into ~54 s dictations; 20 coding-dictation sentences synthesized with
Piper (en_US ryan). Streaming runs send 100 ms chunks at real-time pace from
the laptop over the tailnet, like the phone. WER uses one normalizer for all
engines (lower case, no punctuation).

| Engine | WER 73 clips | WER coding | first partial p50 | stop→final p50 / p95 | 54 s dictation stop→final | cost / hour of speech | offline |
| --- | --- | --- | --- | --- | --- | --- | --- |
| Desktop RX 6600, whisper.cpp large-v3-turbo q8, this server | **3.65%** | 3.56% | **0.95 s** | **0.50 / 0.75 s** | 1.0 s | ~0 (desktop already on) | tailnet only |
| OpenAI `gpt-live-transcribe`, realtime | 5.65% | **2.67%** | 1.58 s | 0.61 / 0.75 s | **0.54 s** | $1.02 ($0.017/min) | no |
| Vosk small-en 0.15 (the offline fallback; laptop, real-time chunks) | 15.2% | 17.3% | streams per chunk | 0.02 / 0.04 s | 0.02 s | 0 | yes |
| Fold CPU, whisper.cpp base.en q5_1 (4 threads, screen off) | 8.96% | 7.11% | n/a | ~5 s after a 6 s clip | 28 s (64 s clip) | 0 | yes |
| Fold CPU, whisper.cpp small.en q5_1 | 5.48% | 4.00% | n/a | ~13 s after a 6 s clip | not run | 0 | yes |

- One-shot (non-streaming) RX 6600 decodes: 0.43 s for a 6.6 s clip, 1.4 s for
  a 46 s clip; the f16 and q5_0 models are within 0.2 points of q8_0.
- CPU-only hosts are not usable: the server (Ryzen 9950X3D, shared, load ~100)
  took 13.5 s for a 3 s clip (faster-whisper int8: 64 s for a 54 s clip); the
  desktop's Ryzen 7 2700X was similar.
- whisper.cpp's timestamped decoding (`verbose_json`) costs ~0.8 points of WER
  (4.5% vs 3.7%), so the server requests timestamps only when a long window
  needs a commit point.
- OpenAI's misses on the 73 clips are mostly fantasy-novel names (Kaliko →
  calico) but include real ones ("the king has fled" → "the king is flooded").
  Whisper was likely trained on these LibriVox audiobooks; on the coding
  sentences OpenAI was better (it heard "rebase", "commit" and "pull request").
- The phone runs were with its screen off (a condition of running them), so
  its cores may have been held back; even 3× faster, base.en stays well behind.
- On-device Whisper would need a model in the APK (60–190 MB) and is 10× slower
  than the server even with the small model, so the offline fallback is Vosk:
  it streams and finishes at once, but makes about four times the server's
  errors. Its WER was measured with the same model on the laptop (accuracy does
  not depend on the device); its speed on the phone was not measured, since
  that needs driving the phone's UI.

Raw outputs and scripts for these runs were kept outside the repository; the
numbers above are copied from them.
