# Dictation

The **talk** control drawn in an agent's prompt box (`type ─ talk ─ send`)
dictates text: tap to start, tap it again (it reads `● rec 0:05 · tap to stop`,
or `● 0:05 stop` on a narrow screen) to stop. While the keyboard is up, `type`
is left out; the keyboard's own hide key, Back or a swipe down put it away. While recording, the whole bottom-right
corner of the terminal is the stop, and **send** is hidden so a tap meant for
stop never sends; Enter on the keyboard still stops and sends. Silence never
ends a session. Speech is transcribed on the desktop's Whisper when it answers
in time; otherwise the phone's own speech recognizer gives the text, marked
**offline**, so there is never no dictation (see "When the desktop is away or
slow" below).

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
- The same audio goes from the first chunk to the phone's own recognizer too
  (`OnDeviceRecognizer`, below), which listens alongside the desktop.
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

## Nothing heard, desktop errors and slow handshakes

Some dictations reached the desktop as near-silence (window RMS under 120), so
its final was empty; some sessions opened and closed with no audio at all.

- The server answers audio with nothing to transcribe with
  `{"type":"final","text":"","code":"silent_audio"|"quiet_audio"|"no_audio","message":...}`.
  A final with any `code` is permanent: nothing is inserted (not even the
  phone's own text), the recording is not kept, and a waiting clip that gets it
  is dropped (its chip goes, as for an empty text) so it never holds up the
  queue. `talk` then reads `talk mic sent silence`, `talk too quiet` or
  `talk no audio` until the next dictation, a send or a pane switch. The same
  codes on an `error` are permanent too; any other `error` (no code, or
  `backend_slow` and the like) is transient.
- A transient desktop error in a live session goes on as if the desktop were
  away, but `talk` says `desktop error` instead of `offline`. A waiting clip the
  desktop took and failed on is counted (`ClipStore.MAX_ATTEMPTS`, below) and
  passed over in that pass, so newer clips still go; after the third it is
  parked with its audio, for its queue row's `retry`.
- The phone looks for a silenced microphone itself: Android's
  `AudioRecordingConfiguration.isClientSilenced()` for our recording (the
  privacy toggle, a call, another app) held for 0.3 s, or 3 s of samples within
  ±2 of zero (`SilenceWatch`). With nothing heard before, the session ends with
  `talk mic silenced` and keeps nothing; after real audio, what was heard is
  kept as if stop was tapped. Why the microphone did not open (`DENIED`,
  `NOT_INITIALIZED`, `START_FAILED`, `IN_USE`, ...) is logged with a count.
- One recording at a time: a new session in the app takes the microphone over
  from the one recording (that one ends as if stop was tapped, keeping what
  it heard; `Microphone.TAKE_OVER_WAIT_MS`), and across apps (the keyboard's
  own dictation) Android silences all but one, which the silenced session
  sees within 0.3 s and ends on, letting go of its recorder. The microphone
  opens on the session's own thread, so that wait never holds the UI.
- A session the phone ends while the desktop is still in it says why:
  `{"type":"cancel","reason":"cancelled"|"silenced"|"not_ready"|"no_final"|"slow_backend"}`
  and a websocket close. The server reads only `type`, so the reason is
  harmless to an older one.
- The TCP connect has 2.5 s, but the TLS and websocket upgrade reads after it
  have 8 s each (`HANDSHAKE_TIMEOUT_MS`): a relayed tailnet path (DERP on
  cellular) needs several round trips of 200 ms and more. A failed handshake is
  logged as its step and kind (`CONNECT`/`TLS`/`UPGRADE`, `TIMEOUT`, `RESET`,
  `CLOSED`, `STATUS` with the HTTP code, `BAD_ANSWER`). With no recognizer on
  the phone the session waits 9 s for the desktop's ready (4 s with one).
- None of the log lines carry audio or text: counts, classes, codes and timings.

## When the desktop is away or slow

On 15 November 2026 the desktop's Whisper silently ran on its CPU: a 2.6 s
dictation took 34 s, and the phone showed nothing for a minute. Since then the
phone falls back to its own recognizer, so there is always text:

```text
mic ──▶ recording (app storage, always)
    ──▶ desktop (ws, as above)
    ──▶ phone's recognizer (SpeechRecognizer fed the same PCM through a pipe,
        EXTRA_AUDIO_SOURCE, Android 13+; never a second microphone)
```

- The phone's text is used when the desktop cannot be reached, is not ready in
  4 s, drops, sends an `error` (any `code`: `backend_slow`,
  `backend_unavailable`, `bad_start`, `session_too_long`), says its backend is
  not the GPU (`{"type":"ready","backend":"cpu"}` or `"down"`; an old server
  without the field counts as the GPU), or has no final within
  `dictationFinalTimeoutMs` of stop (provisioned, default 5000, 1000..60000).
- While the phone transcribes, `talk` reads `● offline · rec 0:05 · tap to stop`
  (`● offline 0:05 stop` when narrow). Its final goes into the draft like the
  desktop's; `talk` then reads `talk offline` until the next dictation, a send
  or a pane switch, and a notice says so once.
- The recording of such a session is kept for 48 h (`ClipStore.KEEP_MS`): never
  sent to the desktop, never replacing the text (which may be edited or sent by
  then), and the first to go when a new recording needs the room. Expired
  ones are deleted when the app starts, at each session start and at each
  courier pass.
- The recognizer: only one trusted to read the piped audio, because Android
  documents that a service without `EXTRA_AUDIO_SOURCE` support silently opens
  the microphone itself. That is the phone's on-device service when it has one
  (`SpeechRecognizer.createOnDeviceSpeechRecognizer`), else the first installed
  service listed in the provisioned `"dictationRecognizers": ["package/class"]`
  (empty by default: add one only after checking it on the phone), asked to
  prefer offline. It starts only once our own microphone is open, and if a new
  recording appears after it starts (the service opened the microphone after
  all), it is given up on. A segmented session keeps it listening through
  pauses; a service that ends on a pause anyway is restarted and the pieces
  joined. A pane switch while the phone transcribes (or a final is on its way)
  stops the session rather than cancelling it: the text joins the end of the
  draft of the pane it was spoken for. Enter pressed while a session finishes
  waits for it and is settled exactly once at its end (final, queued clip,
  error, cancel), in the pane it was pressed in: it sends that pane's draft if
  that pane is on screen, else it is dropped; it never carries over to a later
  dictation (`DictationRouting`). The manifest's `<queries>` for `android.speech.RecognitionService`
  makes the services visible (Android 11+ package visibility). Audio reaches
  it through `PcmFeeder`: every chunk in order, or a failure (it falls behind,
  or its pipe breaks and no restart comes within 2 s), never a silent drop.
  If the phone has none (or it fails, falls behind, or hears nothing)
  the session records and queues as before, and `talk` reads
  `offline · queued · rec 0:05 · tap to stop`. Without the phone's recognizer the
  desktop's final is waited for 20 s, as before.
- Tests: `ServerDictationEngineTest` runs the engine against a fake dictation
  server speaking the real protocol (the server's exact JSON: ready with each
  backend, every error code, a slow final, a drop, never ready, unreachable)
  and a scripted recognizer; `ClipStoreKeptTest` covers the 48 h clips and
  `PcmFeederTest` the recognizer's audio (no drop at the end, falling behind,
  a broken pipe, a restart).
- Not yet checked on the Fold: which recognizer its build has, and whether that
  service honours `EXTRA_AUDIO_SOURCE` and segmented sessions. Logcat tag
  `HerdrDictation` says which path each session took (reasons and timings,
  never text).

## Long dictations and a slow desktop: never resent without end

On 15 November 2026 one 124.6 s clip was uploaded four times in two minutes. Each upload was cut off by `backend_slow`, and every new live session reset the courier's wait. Now:

- **Every start carries the recording's id:**
  `{"type":"start","sample_rate":16000,"id":"<recording uuid>","mode":"live"|"upload"}`.
  The live session and every courier resend use the same id. The desktop resumes from what it already decoded, or answers from its cached final, and it holds an upload (`mode: upload`) to no realtime budget (agreed with server.py).
- **The courier sends nothing to a desktop that is not on its GPU.** It reads `ready` first, and if the backend is not `gpu` it closes without sending audio, and that is not counted.
- **A degraded desktop means a long wait.** That is `ready.backend` other than `gpu`, or `backend_slow` / `backend_unavailable`, on an upload or in a live session. The wait is 2 min, doubling to 30 min. New sessions, the network coming back and new clips do not cut it short. Only a live session finding the desktop on its GPU ends it.
- **An upload the desktop took and did not finish is counted.** After 3 (`ClipStore.MAX_ATTEMPTS`, kept on disk) the clip is parked. Its audio is kept and its chip reads `[2:04 not transcribed yet · retry]`. Its row in the queue (above the prompt) has a `retry` action that sends it again. A clip's audio is deleted only once its final has been received and stored.
- **A recording is never lost: its audio stays on disk until a final has been received and stored.**
  - A live session's recording is deleted only after the composer has saved the text (`persistNow`, a synchronous write whose result is checked). If that write fails, the recording stays as a parked clip with no expiry, "not transcribed yet · retry".
  - A queued clip's audio is deleted only after its text is in the index.
  - A failed index write (a full disk) keeps the audio and the clip, and the index is written again at the next courier pass.
  - Audio no index entry holds (a kill mid-session or mid-write) is recovered at the next start as a parked clip, "not transcribed yet", shown on every pane since its pane is unknown.
  - Only audio whose final is stored, or whose 48 h as rescue audio are up, is deleted.
  - A manual retry always starts a pass at once, whatever the desktop was last seen doing.
- **After stop the desktop may say** `{"type":"status","state":"finishing","tail_s":12.3}`. `talk` then reads `finishing…`, and the final follows; it is not an error.
- Tests: `NoLostAudioTest` covers a kill mid-session, an orphan, a full disk, a failed final store, and a crash while storing. `CourierDegradedTest` covers the 124.6 s replay (3 sends at most, growing gaps, one final, audio deleted only after it), sessions on the CPU never cutting the wait short, resuming on the GPU, a manual retry, and the upload protocol against a fake desktop.

## Clearing a dictation (items 74, 76)

The prompt line's `✕` clears the draft. While a dictation is recording or transcribing, it also cancels the dictation for good:
- the engine is cancelled and the routing listener abandoned, so no partial, final (desktop or phone) or queued clip of that session lands afterwards;
- an Enter waiting on it is dropped;
- the draft returns to what it was before the dictation began.

Recordings whose place is cleared are held. That covers chips in the draft or in cleared queued messages, and, when the queue is cleared, the pane's loose waiting and parked recordings. Held recordings are quarantined like rescue audio: never sent or placed. The courier checks the hold again when it is about to send each clip (`ClipStore.charge` refuses a held or parked clip). A pass that began before the clear therefore never uploads one.

`undo` brings the draft, the messages and the recordings back as they were (parked ones stay parked). It lasts 6 s (`ClearUndo.DRAFT_WINDOW_MS`), or 8 s after clearing the queue too.

A clear survives being killed at any step. Each write that makes the next step safe lands first, and `ClearUndo.reconcile` (after the outbox is read, on every outbox change, and soon after a failed write) puts right whatever a crash between steps left:

- **A queue clear or a drop** first saves its intent in the record: the message and recording ids it is about to take. After a crash, reconcile finishes the step if its messages were cleared on disk (any pane's, GONE rows too). Otherwise it gives the recordings back, loose ones included.
- **Clearing** holds the recordings first (one index write for all of them, all or none). Then it marks the messages cleared in the outbox (`clearedUntil`: kept, never sent, shown or counted). Last comes the undo record (`composer_clear`: pane, deadline, draft text, ids), written together with the emptied draft in one preferences commit.
  - Killed after the holds: the draft and messages still have their chips, and reconcile lets those recordings go.
  - Killed after the messages: they are cleared on disk with no record. Reconcile adopts them into the window, so undo still brings them back.
- **Undo** writes the record, marked undone, together with the restored draft. If that write fails, the undo fails, says so, and stays on offer. Then the messages and recordings come back. Until those are saved too, the record stays, and reconcile finishes it (soon, or after a restart). A new clear waits until it is done.
- **Always:** a recording a live draft or message holds is never held, so a refused or interrupted clear never strands a chip on held audio.
- **A message the sender picked before the clear** is refused at dispatch: `Outbox.sending` checks `clearedUntil`.
- **After a restart** the first pane shown is no pane switch: a resumed undo stays on offer. Past its window, the clear is finished. Expired cleared messages are dropped by reconcile, after any accepted undo was applied, not when the outbox is read.

The queue UI and its gestures are described in the PR (#20) and `QueueView`/`ClearUndo`.

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
  but makes about four times the server's errors. Neither is bundled: the
  fallback uses the recognizer the phone already has (see above).

Raw outputs and scripts for these runs were kept outside the repository; the
numbers above are copied from them.
