# Dictation

The **talk** control drawn in an agent's prompt box (`type ─ talk ─ send`)
dictates text: tap to start, tap it again (it reads `● rec 0:05 · tap to stop`,
or `● 0:05 stop` on a narrow screen) to stop. While the keyboard is up, `↓ hide`
(in place of `type`) puts it away. While recording, the whole bottom-right
corner of the terminal is the stop, and **send** is hidden so a tap meant for
stop never sends; Enter on the keyboard still stops and sends. Silence never
ends a session. Speech is always transcribed on the desktop's Whisper; the phone
never guesses at it with a weaker model.

## How it works

```text
tap → ServerDictationEngine ── ws:// over the tailnet ──▶ dictation server (desktop)
        16 kHz PCM16, 100 ms frames                       websocket bridge (server.py)
        ◀── partial (~every 0.6 s), final after stop ──    whisper.cpp, Vulkan, RX 6600
        │                                                   large-v3-turbo q8_0
        └─ the same audio, always, to app storage
   desktop unreachable (or gone mid-session)?
        └─▶ OFFLINE_RECORDING: keep recording to app storage; on stop the
            recording is a PendingClip and its place in the draft a token
   desktop back (network change, a live session, or backoff 10 s … 5 min)?
        └─▶ PendingDictations uploads each clip, oldest first, and the text
            replaces its token: in the draft, else in the queued message that
            holds it (which then goes out), else at the end of the pane's draft
```

- `dev.herdr.android.dictation.DictationEngine` is the composer's interface;
  `Dictation.create(context)` picks the engine. A session goes CONNECTING,
  LISTENING, TRANSCRIBING, then the final and IDLE; or OFFLINE_RECORDING, then
  onPending and PENDING. IDLE and PENDING both end a session.
- The server URL is provisioned, never built in: put `"dictationUrl":
  "ws://<desktop tailnet address>:8770/v1/dictate"` in
  `remote-for-herdr-settings.json` (see `ProvisionedSettings`). Without it
  dictation is not available.
- Recording starts on the tap and is buffered while the socket connects, so the
  first words are not lost. If the server is not ready within 4 s, the session
  records offline; a drop later does the same, and the partial already shown is
  withdrawn, since the whole recording comes back as one text.
- Recordings live in `noBackupFilesDir/dictation-pending/` (raw PCM plus an
  index written atomically), survive a killed app, and are bounded: 15 minutes
  per clip (the server's limit) and 30 minutes in all; recording stops at
  either. A clip's audio is deleted once transcribed, and its text is kept
  until the composer has placed it.
- In a draft, a waiting dictation is `⟦dictation <clip id>⟧` (`DictationToken`),
  which the composer shows as a chip. A message sent with one waits at the head
  of its pane's outbox line until the text fills it in; an empty transcription
  removes the token.
- The server is a home-manager module, `users/wolf/programs/dictation` in
  `~/Config/home-manager` (`mine.dictation`): whisper-server on loopback, the
  websocket only on the tailnet address. Its protocol is documented at the top
  of `server.py`; a waiting clip uses the same protocol, sent as fast as the
  link allows.

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
| Vosk small-en 0.15 (laptop, real-time chunks; not used) | 15.2% | 17.3% | streams per chunk | 0.02 / 0.04 s | 0.02 s | 0 | yes |
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
  than the server even with the small model; Vosk streams and finishes at once
  but makes about four times the server's errors. Neither is used: while the
  desktop is away the phone records, and the desktop transcribes later.

Raw outputs and scripts for these runs were kept outside the repository; the
numbers above are copied from them.
