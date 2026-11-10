# Remote SSH preview

This work adds an embedded SSH client to the existing GPUI Android terminal.
Direct SSH speaks Herdr's binary `remote-client-bridge` protocol and does not
require the HTTP companion. The optional HTTPS companion workflow remains
available through **Companion hosts**. Existing Compact profiles remain in
Compact; this separately installed app does not migrate them automatically.

The app, Remote for Herdr, has application ID `ch.robitzs.herdr`. It installs
alongside earlier previews (`dev.herdr.android.gpui.remote` and Compact 0.4.0)
and does not overwrite them or their saved profiles.
See REMOTE-VALIDATION.md for the exact build and runtime evidence. Accompanying dependency notices are in android-remote/THIRD-PARTY-NOTICES.txt.

## Host prerequisites

Use a reachable SSH server and an existing SSH account, with Herdr already
installed and its requested session running. The application does not install
or upgrade Herdr, configure SSH access, or change a firewall.
Use password authentication or provide an existing compatible OpenSSH/PEM
private key and its optional passphrase. Passwords and passphrases are used for
one connection and are not stored in the profile. A private key can be saved
explicitly as one device-wide, Android Keystore-encrypted identity outside every
profile; that copy is decrypted only for a connection that uses it, and its
passphrase is never saved. The app does not generate or install SSH keys.
This build enables Ed25519 and ECDSA key algorithms; RSA and DSA support is not
enabled. SSH configuration files, agent forwarding, jump hosts and interactive
multi-factor authentication are not implemented.

Herdr must advertise endpoint generation 1 and the `surface_interest`,
`presentation_effects_fence` and `health_check` capabilities. Official Herdr
0.9.3 reports these prerequisites. The client still checks the actual host's
status rather than assuming compatibility from its version string. It preserves
candidate discovery and the conditional `--idle-timeout-v1` bridge negotiation.

The app refuses a session reported stopped by its initial probe. However,
[Herdr 0.9.3's own bridge](https://github.com/herdrdev/herdr/blob/v0.9.3/src/remote/host.rs)
starts the daemon if it is down when the bridge executes. A stop between the
probe and bridge can therefore start it again. The Connect dialog discloses
this; this preview cannot promise attach-only behavior with that upstream
bridge. It does not reconnect or restart it automatically in the background.

The Herdr session name is explicit in each profile. Leave it at `default` only
if that is the session you want. The daemon and terminal processes stay on the
host when the phone disconnects.

## Connecting

1. Open **Remote hosts**, tap **Add host** for a direct TCP profile, and enter
   the host, SSH port, username and Herdr session
2. Inspect the server key. Compare the displayed SHA-256 fingerprint with a
   trusted copy from the host administrator before explicitly trusting it
3. Connect. With a key saved once from **Remote hosts**, Connect signs in with
   it straight away and asks only for an encrypted key's passphrase. Without
   one, enter the SSH password or choose **Use one-use private key**. If the
   saved key is refused, or the connection fails before the panes appear, the
   sign-in sheet opens with the reason, so a password or another key can be
   used instead. Passwords and pasted keys are used once; only an explicitly
   saved identity is kept
4. Select a pane, then use the GPUI terminal and **Commands** palette

Inspection retrieves the public server key without authenticating. Credentials
are sent only after the next connection verifies the explicitly saved key.
A changed key blocks connection; it is never silently accepted. Verify the new
key separately before using the explicit forget/retrust flow. Changes to the
profile's connection identity clear its trust, and stale dialogs cannot save a
pin for a different profile revision.

The host list leads with the saved hosts, one card each: its name, address and
session, and either the pinned SHA-256 fingerprint or a warning that the host
key is not trusted yet. Each card has a single **Connect** action; its ⋮ menu
holds **Edit profile**, **Edit extra keys**, **Forget SSH trust** and **Delete
profile**, and forgetting trust or deleting still asks for confirmation.
**Add host** sits beside the title. The device's saved key, **Reopen the last
session at launch**, **Hosts file**, **Companion hosts** and **Local device
diagnostics** form one group under **This device**. The list is painted in the terminal's theme
(**View > Theme**, including **Follow system**) and its dialogs use the
platform's light or dark style to match. While a host key is checked, during
sign-in and while choosing a pane, the title names the host with a back arrow;
the arrow and system Back cancel the step and return to the list. On panels
wider than 720 dp the list keeps a centred 720 dp column, and below 600 dp each
card moves **Connect** under the host's details.

**Hosts file** exports every saved host to a JSON file chosen in Android's file
picker, and imports such a file; an empty list offers **Import from a file** too.
A host's name, address, user, session, Iroh ticket and relay, extra keys, quick
commands and trusted fingerprint go into the file. Passwords are never saved, and
the device SSH key and companion tokens are not part of profiles, so none of them
can be exported. Import names every host it will add and skips any whose account
and session are already saved, keeping the saved profile and its trust. The
file's trusted fingerprints are trusted once you confirm that list. A file holds
at most 32 hosts, the list's own limit, and an import that would pass it adds
nothing. Imported hosts are new profiles; no device-local ID crosses over.

For a trusted host, with a device SSH key saved, the ⋮ menu also offers **Open
shell**: the account's login shell on a remote PTY, without Herdr. It needs the
same pinned host key and transport as Connect, and an SSH server that grants
the account a PTY and a shell (no `ForceCommand` or `PermitTTY no`). Herdr need
not be installed. The shell is told `xterm-256color` and gets each new size the
terminal lays out. Leaving the terminal closes the connection, which ends the
shell and anything it started without a multiplexer; nothing reconnects or
replays. Input is queued in order to the PTY; as with a pane, a key that was
queued may already have run when a connection fails.

Host settings, trusted fingerprints, extra keys and OMP quick commands are saved
in an AndroidKeyStore-encrypted profile file excluded from backup. An explicitly
saved SSH identity is stored separately in the same Keystore-backed encrypted
form and is removed only by its own **Remove saved SSH key** action. Password
drafts, pasted keys and passphrases are not saved in profiles or framework
state. Authentication failure does not trigger automatic retries.

## Input and lifecycle

The binary client preserves its remote health checks, bounded queues, surface
validation and daemon boot-ID fences. Only the selected pane receives input.
Focus changes are explicit and input waits for the corresponding host state.

Binary semantic input has no daemon acknowledgement. **Input queued over SSH**
means it entered the client queue, not that the shell consumed it. Send + Enter
is one ordered semantic batch containing the text and Enter. The palette still
requires confirmation that OMP is idle with an empty prompt and never clears
existing input. A failed connection can leave an action applied at the host;
inspect the terminal before retrying. Nothing is replayed automatically.

Leaving the remote terminal closes its connection and discards pending input.
Reconnect from Remote hosts and enter credentials again. A daemon restart,
deleted pane or stale launch cannot silently retarget a command.

The remote renderer paints the validated binary surface frame: named, indexed and
truecolor foregrounds and backgrounds, bold, dim, italic, underline, reverse,
strikethrough and hidden attributes, the block cursor, and every split pane with
its borders, the active pane's border carrying the accent ring. Runs must tile
each row exactly or the row falls back to plain text, so a malformed surface can
never misalign colors. Graphics, images and unlimited scrollback are not
rendered yet.

Native projection, buffered history and renderer decoding share a typed row/run
format rather than allocating a generic JSON tree for every span. The JSON wire
shape and existing byte, row, span, Unicode-boundary and hyperlink limits remain
unchanged. See the [Pixel measurements](docs/pixel-fold-notes.md) for the measured
cost and device-verification scope.

The active SSH terminal's `pollTerminal` prepares its generation-fenced GPUI
snapshot directly in Rust on the existing background worker. Java receives
navigation, notifications and scroll state, without transferring either terminal
body through Java JSON decoding. **Copy pane text** reads the bounded, held native
snapshot on the existing worker only when requested, without a daemon round
trip. Its capture is generation-fenced; a delayed result cannot write the
clipboard after the connection or pane changes or the activity pauses. The
activity's connection lock covers native painting and metadata adoption, so
pause or replacement cannot publish a late frame. Local title,
status and shortcut updates change only snapshot chrome, preserving the held
styled body. The HTTPS workflow keeps its plain-text snapshot and immediate Copy
path. Frame validation and input/render scheduling are unchanged.

Terminal polls acknowledge the frame and navigation tokens independently. When
Java already holds the current navigation token, a changed terminal frame does
not resend the pane/workspace/tab trees or header title; Java retains its cached
navigation while Rust still prepares the complete styled frame. Label, status,
focus, pane and boot changes invalidate navigation normally. A zero frame token
requests the complete view. A zero or stale navigation token forces navigation
delivery even if the frame token is current, so a missing cache never waits for
another terminal update to recover. Clipboard writes, notices and bells retain
their once-only delivery. Background mirrors and the host picker's poll contract
are unchanged.

Rows without hyperlinks are shaped and painted as one styled-text element, with
their existing color and decoration runs. This avoids a separate flex child,
element ID and text layout for every span. Rows containing hyperlinks retain the
interactive span path and the same bounded Android link handling.

Links the pane publishes (OSC 8) open in the platform browser when tapped. The
client forwards only `http` and `https` targets, at most 2 KiB without control
characters, and the activity rechecks the scheme before starting the intent; no
file, `intent:` or custom scheme is reachable this way.

Launching reopens the remembered session: the app reads the last host and pane,
verifies the pinned fingerprint, connects with the saved device key and starts
the terminal without showing the host list. The list remains reachable from the
terminal's **Session > Remote hosts…**, where **Reopen the last session at launch**
switches the behaviour back to starting on the list.
Reopening happens once per launch. Back from a terminal always lands on the
list, including on a first run, when the pane just opened becomes the
remembered one.

Every saved host is mirrored in the rail by its own read-only session, polled
every three seconds. A mirror negotiates `surface_active: false` in the initial
hello, preserving host/workspace/agent snapshots and endpoint health checks
without subscribing to unseen terminal surfaces. The terminal itself still
owns exactly one connection at a time. Tapping a pane under another host opens
a new surface-active connection and focuses the pane; it does not reuse an
inactive mirror as a terminal. The client keeps at most eight live
sessions (terminal plus mirrors plus a transient connection while switching) and
refuses more. Mirrors exist only while the activity is resumed and the rail is
visible. A hidden cold start opens only the terminal; hiding the rail or a
narrow-window transition closes its mirrors. A later visible rail repopulates
them. Late connection results are closed if their original sidebar owner has
stopped. Leaving the activity closes all client connections, not the host's
terminal processes.

The view stops tailing the moment a scroll target is outstanding, so incoming
output no longer yanks a reader back to the newest line, and it only re-pins to
the tail when lines are actually appended or the session is switched.

Dragging in the terminal scrolls the client's own row buffer: the session keeps
the last 512 rows it has seen, keyed by their position in the pane's history,
and serves motion from memory; a flick keeps travelling under momentum. Reading
back pins the rows under the finger, so output arriving meanwhile does not move
them. The daemon draws one screen per `pane.scroll` request, so the session asks
for the screen holding the newest row its view lacks: one screen ahead of the
finger, or a gap a burst of output left between two frames it saw. Until that
frame arrives the last complete screen stays up rather than jumping to live.
Returning to the tail, or **Jump to live output**, follows new output again and
puts the pane back at offset zero if this client scrolled it. Requests go only to
the pane the daemon has focused, so another client's focus never moves a pane
this client is not showing. Switching panes starts the new one at its live
output with an empty buffer, so no rows from the previous pane can appear in it.

A snapshot and its matching surface arrive as separate messages, so their
revisions disagree for a frame or two; the client keeps the last drawn pane
instead of blanking it during that skew.

The terminal polls its session for a delta: the native side hashes everything the
client renders and answers an idle poll with a few hundred bytes instead of the
whole tree. A mirror poll omits the surface and pane text it never draws, and the
cadence drops from twenty polls a second to ten when no input arrived recently.

Rail, toolbar, status-bar and sheet colours come from chrome roles the renderer
derives from the terminal palette (`ChromeRoles` in `model.rs`, read once per
theme through `nativeThemeColors`), so every Android surface follows the
selected theme. Each text role (primary text, secondary text, accent, danger and
warning) is blended in 5% steps toward the primary text colour - and that colour
toward black or white - until it reaches 4.5:1 against its surface. Keep colour
literals opaque (a 24-bit literal has a zero alpha channel and renders nothing).

Each host prints as a row with a circular status dot, its name and its state
word. Workspaces print as sentence-case labels, panes below them with a dot,
up to two lines of title and the agent's state word. The connected host and the
focused pane sit on the theme's tonal selection container and report themselves
as selected to accessibility services; the workspace holding the focused pane is
tinted with the primary colour. A one-pixel rule separates hosts, a persistent
rail keeps a matching edge line, and a short gradient at the bottom edge shows
the list continues past the screen. A host dot is solid while its session is
connected and a ring while it dials or is down; a dot pulses once when its agent
changes state, so a background refresh is visible without being noise.

The rail opens by default at 600 dp and wider and remembers the wide-panel
preference across narrow-window transitions. The **Hosts** icon toggles it and
shows a selected container while it is open; its tooltip and spoken label say
what a tap will do. Below 600 dp - the Pixel Fold's cover, for example - the
rail is a modal drawer instead: the smaller of 320 dp and 86% of the window,
raised over a scrim, and closed by Back, a tap on the scrim or any choice in it. The drawer
never changes the remembered wide-panel preference, so unfolding restores the
rail the way it was. The rail also answers to gestures: swipe right anywhere in
the left half of the terminal to open it, and swipe left on the rail itself to
close it, or swipe across (or tap) the scrim beside it. Android's Back gesture
owns the screen edges and ignores exclusion requests, so on Android 14 and
newer the drawer rides on Back itself: while the rail is closed, a Back swipe
from the left edge opens it, and the right edge and the navigation buttons
still go back. Below Android 14 the open swipe starts inside the content. It lists every saved host above the session tree. Choosing one
opens a new SSH connection with the saved device key, focuses the pane this phone
last used there - or an agent pane that is not idle - and bumps the connection
generation, so the old handle is closed before
anything from it can reach the new surface. A host without a pinned fingerprint
or without a saved key is refused with a message rather than guessed at.

Half-open with its fold across the window - tabletop, propped like a small
laptop - the terminal ends at the fold and the host's keys move from the
toolbar strip to a deck of 56 dp keys below it, above the unchanged toolbar. The
fold comes from the device's own `androidx.window.extensions`, loaded as an
optional library and read while the activity is started; a device without it
keeps the normal layout. The split needs room on both sides, so an open
keyboard restores the normal layout until it closes. A fold running down the
window (book posture) is not split, as that would halve the terminal's width.

A vertical drag on the terminal scrolls the pane's scrollback: the client sends
`pane.scroll` with an absolute `offset_from_bottom`, clamped to the window the
daemon reports, and a tap shorter than 18 dp still reaches the terminal. **Session >
Jump to live output** returns to offset zero.

**Session** sends only allowlisted methods - `tab.create`, `tab.close`,
`pane.split`, `pane.close`, `pane.zoom` - against the daemon's focused
workspace, tab and pane. Close requests are confirmed first, and nothing else
can be invoked from the renderer.

Daemon notifications (for example an agent waiting on you) are posted on the
`herdr-agent-alerts` channel: at most eight pending, one per pane, newest wins,
with the pane as the notification id so a newer alert replaces the older one on
the same pane. A terminal bell vibrates briefly. Both are delivered once by the
poll that follows them.

A program in a pane can set the phone clipboard through OSC 52. The daemon
forwards it as base64; the client decodes it strictly and writes plain text
only, at most 256 KiB of valid UTF-8, once per write. Invalid, oversized or
non-UTF-8 payloads never reach the pasteboard, and an empty write is ignored
rather than clearing it.

**View > Theme** switches between the built-in palettes (Default, Nord, Mocha
and the light Latte). The palette is applied when a frame is painted, so an open
terminal repaints immediately without reconnecting or waiting for output.
**Follow system** uses Latte while the phone is in light mode and Mocha, the
same family's dark half, in dark mode; switching the system theme repaints the
open terminal at once, with no restart.
Applying a theme also recolours the status and navigation bars (with dark icons
on a light theme), the toolbar, the rail and every sheet; sheets and menus use
the device's own light or dark dialog style to match the palette's lightness.

Like the desktop client, the phone reports its palette to Herdr as the host
terminal theme: the default foreground and background, all 256 indexed colours
as the phone paints them, and light or dark from the palette's own lightness
(Latte is light). Herdr answers its programs' colour queries (OSC 10, 11 and 4)
with the theme of the client in the foreground, and an attaching terminal
becomes that client, so a program that follows its terminal's theme, such as
omp, repaints to match the phone, live when the theme or the system appearance
changes. Using the desktop client makes its theme current again for the whole
session. Output printed before a change keeps the colours it was drawn with.

A pane shorter than the body spreads its rows over the body's height, up to
twice their natural pitch; the type size and columns do not change, and cell
backgrounds fill each taller row so painted panels stay solid. Box-drawing and
block characters are drawn to fill each taller cell as well, so borders and
scrollbars stay continuous.
Around the pane, the body shows the palette background unless at least three
quarters of the pane's outer ring of cells is painted visibly unlike it (any
channel more than 32 levels apart), as an agent drawing its own background is.
Then the commonest such colour fills the letterbox, and it keeps filling until
the share drops under half, so content scrolling past the edge cannot make the
surround flicker. The native projection marks the daemon's borders and
scrollbars - every cell outside each pane's content rectangle - with a
renderer-only `FRAME` bit: those cells do not vote, and on the default
background they show the surround. Cells inside the pane keep their own
background, so a default cell next to painted ones still draws the palette's.

**Hosts** shows a workspace/agent rail and **Panes** the same session tree as a
sheet, both from the daemon snapshot and each row carrying its agent status.
The sheet lists tabs only for a workspace that has more than one; a lone tab is
the workspace itself, so its panes sit directly under the workspace.
Choosing a pane sends one `pane.focus`; choosing a tab or workspace sends
`tab.focus` or `workspace.focus` and then follows the pane the daemon focuses,
while another client's focus move is ignored. Every surface that follows is
validated against the same boot ID and projection revision.

## SSH over Iroh

Choose **Iroh + SSH** and paste the receiver's endpoint ID or an `endpoint…`
ticket, then the SSH username and Herdr session. A ticket also names the relay
URL and IP addresses the receiver published. The app dials those alongside
Iroh's N0 address discovery and relays, so it can reach a receiver that
publishes no discovery record. An ephemeral client identity is sufficient.
Credentials still belong to SSH, and the server key is verified through the
same explicit trust flow as TCP.

The profile's identity is the endpoint ID, saved as lowercase hex. Pasting a
newer ticket for the same endpoint keeps saved SSH trust; a different endpoint
clears it. A ticket is refused when it is longer than 4096 characters, lists
more than 32 addresses, names a relay that is not an `http` or `https` URL, uses
a custom transport, or is not in its canonical encoding.

**Paste endpoint or ticket** fills the field from the clipboard and selects
**Iroh + SSH**; text that is neither an endpoint ID nor a valid ticket leaves
the field unchanged. To move a ticket from the host, show it as a QR code there,
for example with `qrencode -t ansiutf8 '<ticket>'`. Scan it with the phone's
camera app, copy the text it offers, then tap Paste.

**Your own relay.** To keep connections off Iroh's public infrastructure, set
**Iroh relay URL** to your own
[iroh-relay](https://github.com/n0-computer/iroh/tree/main/iroh-relay)
server, for example `https://relay.example.com`, and give the receiver the same
relay as its only relay. For iroh-ssh 0.2.12 that is
`iroh-ssh server --relay-url https://relay.example.com`; `--extra-relay-url`
keeps Iroh's public relays, so the receiver may choose one of those instead and
the app cannot reach it. dumbpipe 0.40.1 has no relay option, so it cannot be
used with this setting. With a relay set, the app uses only that relay. It does
not contact Iroh's public relays, it neither looks up nor publishes endpoint
addresses, and from a ticket it dials only the IP addresses, not the ticket's
relay. Once the relay has introduced the two endpoints, Iroh may still connect
directly to addresses the receiver announces.

- An `https` relay needs a certificate from a public certificate authority: the
  app checks it against Mozilla's root list built into Iroh, not against
  certificates installed on the phone.
- `http` relays work, for example `iroh-relay --dev` on a trusted network. The
  relay never sees the SSH traffic, which stays encrypted end to end, but
  without TLS the network can see which endpoints use the relay.
- The URL must not contain a user name or password, and is limited to 512
  characters. The relay replaces the URL's path with its own.
- The relay only routes to the endpoint, which Iroh authenticates by its ID, so
  changing or clearing the relay keeps saved SSH trust.

The wire interoperability target is
[rustonbsd/iroh-ssh 0.2.12](https://github.com/rustonbsd/iroh-ssh/tree/d0e9b6a823d25bf5b02757bbf9c17edeb39cfad5):
ALPN `/iroh/ssh`, one bidirectional stream carrying raw SSH bytes. That receiver
must run on the remote host and forward to its existing SSH server; Iroh does
not replace SSH authentication or host-key verification. The receiver chooses
the SSH destination port. It is not configured or deployed by this app. No
companion bearer token is required. The reference receiver does not add its own
peer allowlist; knowing its endpoint ID permits reaching the SSH server, whose
authentication remains essential.

[dumbpipe 0.40.1](https://github.com/n0-computer/dumbpipe) also works as a
receiver when told to accept that ALPN, and it prints a ticket to paste:

```sh
IROH_SECRET=<64 hex characters> dumbpipe listen-tcp --host 127.0.0.1:22 --custom-alpn utf8:/iroh/ssh
```

Without `IROH_SECRET`, dumbpipe creates a new endpoint on every start, so its
ticket names a different endpoint and pasting it clears saved trust; it prints
the generated secret so you can reuse it. Like iroh-ssh, it has no peer
allowlist: anyone holding the ticket or endpoint ID can reach the SSH server.

Transport tests use local Iroh endpoints with discovery and relays disabled.
They verify raw SSH, exact ALPN, pinning, output bounds and closure even when the
client runtime ends. Ticket parsing is tested for both endpoint ID forms, the
canonical encoding, the length and address bounds, and refused address kinds.
On an x86_64 emulator, a real dumbpipe 0.40.1 ticket (one N0 relay and 13 direct
addresses) reached a local synthetic SSH fixture through inspection, trust,
sign-in and the terminal. A newer ticket for the same endpoint kept trust, and a
different endpoint cleared it. With the relay URL set to a local
`iroh-relay --dev`, the emulator reached a receiver that had no IP addresses at
all, so the relay was its only path, through inspection, trust, sign-in and the
terminal. A packet capture of that connection contained no DNS queries and no
traffic to Iroh's public servers; the same profile without the relay looked up
`iroh.link` names and connected to N0 relays. **Paste endpoint or ticket**
filled the field from text copied in the app and refused non-Iroh text.
iroh-ssh 0.2.12 with `--relay-url` also worked through the terminal, both on the
local `http` relay and with an `https` relay given by hostname (N0's public
`use1-1` relay entered as the app's relay URL). In that capture the app
resolved and contacted only that relay's hostname before trying the receiver's
own addresses. The same `https` case reached the pane picker on a physical
Pixel 9 Pro Fold. A profile saved by the previous build kept loading after the
update and accepted a relay. A receiver on a different network than the phone,
a real Herdr host behind it, and physical Android network transitions require
separate verification.

## Address and authentication compatibility

Direct TCP accepts a hostname, IPv4, bare IPv6 (`2001:db8::1`), or bracketed IPv6
(`[2001:db8::1`). Enter the SSH port in its separate field; `[address]:port` and
URLs are not hostnames. Brackets are stripped only for valid IPv6 literals when
dialing; saved profile identity and SSH trust are not rewritten. Bracketed scoped
IPv6 addresses (`[fe80::1%interface]`) are not supported by this normalization.

The same SSH authentication policy applies over TCP and Iroh. A failed attempt
now distinguishes a rejected credential, a method the server does not offer at
that stage, and a credential accepted only as the first authentication step.
An unavailable method can reflect server policy or account policy, not necessarily
a bad password or key. Empty method lists remain a generic authentication failure.
The app never retries with a different method automatically. Keyboard-interactive,
interactive MFA and multi-step authentication remain unsupported; no authentication
or host-key restrictions are relaxed by these diagnostics.

See [compatibility validation](TRANSPORT-COMPAT-VALIDATION.md) for tested cases,
upstream source references, and the distinction between local synthetic Iroh
coverage and deployed receiver interoperability.
