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
        └─▶ SystemDictationEngine: the phone's SpeechRecognizer, offline model preferred
```

- `dev.herdr.android.dictation.DictationEngine` is the composer's interface.
  `Dictation.create(context)` picks the engine.
- The server URL is provisioned, never built in: put `"dictationUrl":
  "ws://<desktop tailnet address>:8770/v1/dictate"` in
  `remote-for-herdr-settings.json` (see `ProvisionedSettings`). Without it the
  phone's recognizer is used alone.
- Recording starts on the tap and is buffered while the socket connects, so the
  first words are not lost.
- If the server cannot be reached within 2.5 s, the same session continues on
  the phone's recognizer, and the server is skipped for the next minute. A
  drop after listening began is reported as an error; the partial text already
  shown stays in the composer.
- The phone's recognizer ends each utterance on silence; the engine starts the
  next one at once and joins them. Words spoken in that restart gap (a few
  hundred ms) can be lost; the server path has no such gap.
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
  than the server even with the small model, so the fallback is the phone's own
  recognizer instead. Its latency and offline behaviour were not measured: that
  needs driving the phone's UI.

Raw outputs and scripts for these runs were kept outside the repository; the
numbers above are copied from them.
