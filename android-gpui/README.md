# Remote for Herdr: unofficial Android client

## Remote connection update

Version 0.5.0 adds embedded SSH over direct TCP or Iroh. Open **Remote hosts**,
verify the SSH server fingerprint, authenticate with a one-use password, a
one-use private key, or a device-wide key saved encrypted on the phone, and
select a pane. It uses Herdr's binary bridge and does not require an HTTP
companion. See [remote setup, trust and limits](REMOTE.md).

The app is **Remote for Herdr**, an unofficial client not affiliated with
Herdr's authors. It installs as `ch.robitzs.herdr`, alongside earlier previews
(`dev.herdr.android.gpui.remote` and the Compact app), which keep their own saved
hosts. **Hosts file** under **This device** exports host profiles to a file and
imports them from one. **Companion hosts** still opens the HTTPS workflow.

**Local device diagnostics** opens a separate, read-only screen that reads
nothing until you choose **Read diagnostics**. It then collects one bounded
device/battery/storage snapshot and shows it only on that screen; leaving the
screen clears it. There is no service, notification, remote endpoint,
shell/root access, or background monitoring. See the
[local diagnostics contract](../docs/android-control-agent.md).

This is the GPUI-backed continuation of the native Android companion preview.
The saved-host manager, authentication and agent screens remain Android Views;
the terminal activity is rendered by **Rust GPUI and its wgpu Android backend**.
It is not a WebView. The original `android/` application can still be built
without the GPUI native library.

## Raw SSH shell

A trusted host's ⋮ menu in **Remote hosts** offers **Open shell** when a device
SSH key is saved. It opens the host's own login shell on a remote PTY instead
of a Herdr pane, through the same pinned host key and transport. A modified
copy of [gpui-rio](https://github.com/cachix/agentaps) and
[rio-vt](https://github.com/raphamorim/rio) under `rust/vendor/` parses and
draws it; their READMEs list what changed. The terminal reports
`xterm-256color` and uses the whole surface: no header, the font's own line
height, and auto type at the app's 13 px default, smaller on narrow panels down
to 11 px to keep close to 80 columns. The PTY gets every size it lays out
(47 × 109 on the inner panel, 60 × 67 on the cover). It follows the theme and
scrolls its own 10,000-line history with a drag. Typing, the on-screen keyboard,
physical keys, the toolbar keys and quick commands use the same ordered input
path as a pane. **Session** offers Copy screen text, Remote hosts and Save
screenshot.

There is no Herdr behind a shell: no rail, Panes, pane actions or agent status,
and leaving the terminal ends the shell. When the shell exits, its last screen
stays and **Reconnect** opens a new one.

## Quick commands

Version 0.4.0 adds a per-host Oh My Pi command palette with editable previews,
pinning, Insert, and separately confirmed Send + Enter. Tap **Commands** (the
lightning-bolt icon; **Cmds** in earlier builds) in a GPUI terminal. See
[usage and verified presets](QUICK-COMMANDS.md) and
[0.4.0 validation](QUICK-COMMANDS-VALIDATION.md). That Compact release updated
0.3.0 in place. Its command palette is also available per host in Remote 0.5.0.

## Compact phone update

Version 0.3.0 adds a selectable compact, keyboard-first profile for the Titan 2
Elite. See [device setup and differences](TITAN-2-ELITE.md) and
[0.3.0 validation](COMPACT-VALIDATION.md). The earlier Compact package is
`dev.herdr.android.gpui.compact` (Herdr GPUI Compact), installed alongside the
earlier preview because its old development signing key did not survive the
cloud workspace reset. Saved hosts in the earlier app remain untouched.

## Architecture

```text
Native Android host manager (encrypted profiles)
  -> non-exported GpuiTerminalActivity
     -> GpuiInputActivity / Activity + SurfaceView / Android lifecycle
        -> herdr_gpui_android.so
           -> gpui-pre 0.3.7 + gpui-pre-wgpu 0.3.7
           -> Longbridge gpui-mobile Android platform
     -> RemoteNative (Rust background workers)
        -> embedded russh over TCP or Iroh /iroh/ssh
        -> Herdr remote-client-bridge -> generation-1 client/protocol
        -> or a raw PTY shell -> vendored gpui-rio session + rio-vt grid
     -> existing HTTPS CompanionClient (Java worker)
        -> authenticated herdr-companion from this source
           -> Herdr pane.read / pane.send_text / pane.send_keys
```

The Java host owns encrypted profile storage and the credential/trust dialogs.
SSH credentials cross JNI once into zeroizing native buffers. Rust owns the
embedded SSH/Iroh connection and binary client. Companion bearer tokens remain
within the Java HTTPS transport. Display state and input use bounded,
generation-fenced queues. A private process-local one-shot launch slot transfers
the selected host or connection to the non-exported terminal activity; credentials
never enter Intents or framework saved state.

GPUI Mobile is pinned to
`9075e3aa3eea812127f2c60ed66f0cd5798ff245` from
[longbridge/gpui-mobile](https://github.com/longbridge/gpui-mobile).
Its Apache-2.0 license option is selected. The Rust application has its own Cargo
workspace and lockfile so the desktop application's pinned GPUI 0.3.6 is unchanged.
GPUI core, mobile backend and wgpu renderer are aligned at 0.3.7.
The upstream SurfaceView host keeps one render thread alive across Activity
instances, avoiding the separate NativeActivity path’s single-initialization
restriction. The copied IME helper uses Activity instead of NativeActivity and a protected
callback hook. Android composition remains local; committed IME input and
physical keys enter one generation- and IME-session-fenced FIFO. This prevents
a later Enter from overtaking text. Commands are rejected during preedit.

## What this proves, and what it does not

This is a real Android GPUI application integration: the native platform,
renderer, terminal layout and input handling are GPUI/Rust. Remote SSH uses the
existing binary client, while the optional HTTPS workflow uses companion snapshots.
It does not move the full desktop application, sidebar or browser to Android.

The SSH workflow paints the daemon's whole surface frame from its own cells: 16
named colors, indexed and truecolor cells, bold, dim, italic, underline, reverse,
strikethrough, hidden text and a block cursor, with every run bounded and
validated before it reaches the renderer. Split panes and their borders are
painted together and the panes' focused border carries the accent ring.
Every row is painted on one cell grid at the bundled Noto Mono advance, so
characters the bundled font lacks and Android draws from a proportional system
font, such as braille and arrows, stay in their columns, and the second cell of
a wide character keeps its own column without entering copied text. Box-drawing
and block characters do not come from a font at all: they are drawn as lines,
arcs, blocks and shades that fill their whole cell, so borders and scrollbars
stay joined however far apart rows are spread. Only the three diagonals keep
their glyphs. Horizontal pan stops when the pane's last column reaches the
screen edge.
When at least three quarters of the pane's outer cells are painted in colours
clearly unlike the palette background - an agent drawing its own background,
for instance - the commonest of them fills the area around the pane. Once it
fills, it holds until under half, so scrolling cannot make the surround flicker.
The daemon's borders and scrollbar on the default background take that colour
too; cells inside the pane keep their own.
Terminal hyperlinks in the daemon's table become tappable runs; only `http(s)`
targets are forwarded, to the platform browser, never to files or custom
schemes. Launching the app reopens the remembered host and pane directly, skipping the
**Remote Herdr** list; **Session > Remote hosts…** opens the list from a terminal, and
its **Reopen the last session at launch** switch turns the behaviour off. The
rail opens by default on panels 600 dp and wider (so the inner fold display, not
the cover) and remembers whether you hid it. On a narrower window **Hosts** opens
it as a modal drawer over the terminal instead; Back, a tap outside it or a
choice closes it, and it never changes that remembered preference.
Half-open with the fold across the screen (tabletop), the terminal ends at the
fold and the host's keys move from the toolbar strip to a deck of large keys
below it, over the unchanged toolbar. An open keyboard leaves no room under the
fold, so the normal layout returns until it closes. A fold down the screen (book
posture) keeps the normal layout: splitting there would halve the terminal.
Hidden or automatically collapsed rails do not open background mirror sessions,
including at startup. Returning to a wide window restores mirrors only when the
sidebar is wanted; leaving the activity closes them.
Activity startup timing (`am start -W`) does not include SSH and pane readiness,
and a quiet single session is not representative of a busy multi-host sidebar.
See the [Pixel measurements](docs/pixel-fold-notes.md) for the measured workload,
APK identities and verification scope. A `dumpsys SurfaceFlinger --latency` read on
the GPUI SurfaceView layer comes back empty while the surface is idle, so frame
cadence has to be sampled during interaction rather than at rest.
Terminal type follows the pane's own wrap width and Android's system font scale;
pick a fixed size in **View** to override it. A lost link to a Herdr host is
resumed on its own while the terminal is on screen: at once, then every 250 ms
to 2 s, and again immediately when a network appears or changes. The last frame
stays, dimmed, with the keyboard up, and the line under the tabs says
"Reconnecting…" in place of the breadcrumb; nothing moves. A link that goes
quiet (herdr-core is refreshed every two seconds) is replaced rather than left
for TCP to time out. Input that never left the phone waits for the same pane
and is sent once, in order; input that was being sent when the link failed is
never resent. A raw shell, or a host that is no longer saved, still offers
**Hosts** and **Reconnect**. **Hosts** opens one tree
of saved hosts, workspaces and agents, with the current host marked. Background
mirrors negotiate inactive surface interest in their initial hello: they keep
receiving metadata without requesting terminal frames they never display.
The active host's navigation has a separate change token, so terminal output and
scrolling do not rebuild the sidebar or recalculate pane titles. Confirmed pane
selection and pane/workspace/tab metadata changes still refresh navigation.
Tapping another host opens a surface-active connection with the saved device key
and the rail switches to that host's workspaces and agents; the GPUI surface and
your scroll position stay in the app. The terminal opens the pane you last used
on that host, otherwise an agent that is doing something, so a switch never
lands on an idle shell and looks blank. The header names the pane and host.

Swipe down on the terminal to read scrollback and up to return; **Session > Jump to
live output** returns to the newest line. The activity converts a drag into
`pane.scroll` offsets bounded by the daemon's own `max_offset_from_bottom`, and
a short tap still reaches the terminal.

**Session** controls the focused session through allowlisted daemon methods: new
tab, split right or down, zoom toggle, close pane and close tab (both confirmed).
The client follows whichever pane the daemon focuses afterwards.

Agent alerts the daemon broadcasts appear as Android notifications on one
bounded channel (newest per pane, at most eight pending), and a terminal bell
gives a short vibration instead of overriding the ring mode. Notifications need
the system notification permission; without it the terminal still runs.

An OSC 52 clipboard write from a pane reaches the Android clipboard as
plain text only: the payload must be base64 of valid UTF-8 within 256 KiB, and
anything else is dropped rather than guessed at. A
surface whose runs do not tile the extracted text exactly falls back to bounded
**plain text**, which is also what the HTTPS companion workflow shows. Graphics,
images, responsive daemon resize and full scrollback are not rendered yet. No
terminal escape sequence is executed just because it appears in output. GPUI
backend lifecycle and mobile IME support are experimental upstream; device
verification is essential.

The configurable extra-key strip uses native Android controls so its actions
share the immediate ordered input path with hardware keys and IME commits.
GPUI owns the terminal surface, scrolling, composition display and view controls.

SSH and Iroh are embedded libraries. No system SSH executable or VPN is required.

### VM-tested update

Version 0.2.1 fixes a font-resolution crash discovered on the first real Android
VM run. Bundled Noto Sans and Noto Mono make baseline rendering independent of
OEM font aliases. Android 15 x86_64 VM tests exercise actual GPUI frames and
input/lifecycle paths; see [VM-TESTS.md](VM-TESTS.md). Transport in those tests is
synthetic, and ARM64 phone execution still needs verification. Emoji coverage
remains incomplete on the tested VM.

## Build and run

You need Rust 1.96.1, the `aarch64-linux-android` target, a compatible Android NDK,
and Android SDK 35 with JDK 17+ for the standard Gradle build. NDK/SDK usage is
subject to Google's Android SDK License Agreement. Build scripts must not accept
agreements on your behalf; install those tools only after you accept their terms.

The scripts in `scripts/` cross-build the real ARM64 Rust shared library and
package the Java host with it. They validate native ELF architecture and 16 KiB
page alignment, JNI host/surface entry points, APK contents and signatures.
See the scripts' usage/help and the validation record for the exact commands
actually exercised in the build environment.

For normal Android Studio development, open this `android-gpui/` directory.
After producing `app/src/main/jniLibs/arm64-v8a/libherdr_gpui_android.so`:

```sh
./gradlew :app:assembleDebug :app:lintDebug
```

The current app ID is `ch.robitzs.herdr`, installed alongside older previews
such as `dev.herdr.android.gpui.remote`. Profiles from those separate
applications must be entered again.
The GPUI build requires Android 10+ because this mobile backend links an Android
API 29 function; the original Java-only preview still supports Android 8+.
It requires an ARM64 Android device, API 29+ (Android 10+), with the GPU capabilities required
by GPUI Mobile. Remote sessions use the Internet permission, the terminal bell
vibrates, and agent alerts post notifications; no accessibility, storage, root,
screen-capture or foreground-service permission is requested.

1. Use an existing SSH account and compatible running Herdr session on the host.
   Iroh additionally needs its SSH receiver. See [remote setup](REMOTE.md).
2. Install the Remote preview and add a TCP or Iroh profile. Each host shows one
   **Connect** action; its ⋮ menu holds **Edit profile**, **Edit extra keys**,
   **Forget SSH trust** and **Delete profile**, and the last two still ask for
   confirmation.
3. Independently verify its SSH fingerprint, authenticate, and select a pane.
4. Use **Hosts**, the first toolbar icon, for the workspace/agent rail (it opens
   by default where width allows) or **Panes** for the same session tree as a
   sheet. Workspace, tab and pane rows carry the agent status dot (idle,
   working, blocked, done); a tap switches workspace or tab, or focuses that
   pane, without leaving the terminal. **Commands** opens editable OMP commands
   and **View** the compact display options. Below 600 dp, **View**, **Session**
   and **Close** move into the **More** menu. Long-press an icon for its name.

For the optional HTTPS workflow, open **Companion hosts** and use the
[companion setup](../android/README.md#host-prerequisites), then tap **GPUI** from
an existing terminal. That workflow still requires trusted HTTPS and a token.

Closing or backgrounding this activity does not stop remote terminals. Pending
input is discarded and never automatically replayed. A dispatched action may
already have reached the host when the connection fails. SSH semantic input is
reported as queued and has no daemon receipt; see the remote guide.

See [../android/VALIDATION.md](../android/VALIDATION.md) for the previous native
preview's checks. GPUI-specific build and runtime evidence belongs in
`VALIDATION.md` in this directory; the earlier Java APK's validation is not proof
that the native GPUI activity runs correctly.
