# Remote for Herdr privacy policy

Effective 8 November 2026 (Wolf fork)

Remote for Herdr is an unofficial client for Herdr, which is owned by an unrelated team. This policy explains what the app does with your information. In short: the app's developer collects nothing; when you connect, information goes to the computer and network services you choose to use.

## What the app developer collects

Nothing. The app has no developer accounts, advertising, analytics, crash reporting or developer-operated servers. It does not send information to the developer.

## What stays on your device

- Host profiles and SSH host fingerprints.
- If you choose to save or create one, an SSH private key, and optional companion tokens. A key created here is made on the device and its private half never leaves it; its public half is also written to the app's folder under Android/data, which a computer connected over USB or wireless debugging can read.
- Display preferences and the last session.

Host profiles, fingerprints, saved keys and companion tokens are encrypted using Android Keystore. Passwords and passphrases are used for a connection only and are never saved. App data is excluded from backup and device transfer. Uninstalling the app removes its local data.

## Host files

If you choose **Export** under **Hosts file**, the app writes a file to a place you pick. It contains each saved host's name, address, user name, Herdr session, Iroh ticket and relay, extra keys, quick commands and trusted host fingerprint. It never contains passwords, passphrases, the device SSH key or companion tokens. The file is not encrypted, so keep it as you would an SSH configuration file. The app reads a hosts file when you choose one to import, and shows you its hosts before adding them. It also imports, once per version, a hosts file named remote-for-herdr-hosts.json placed in its own folder under Android/data; only a computer with USB or wireless debugging access, or the app itself, can put one there, so that file's host fingerprints are trusted without asking. It never sends a hosts file anywhere.

## Network connections

The app connects only to destinations you specify. SSH connects over TCP to a host you enter; your password or key goes only to that host, inside the encrypted SSH connection. Iroh connects to an endpoint ID or ticket you enter. By default, Iroh uses number0's public relays and address lookup at n0.computer; those services see your device's IP address, a random endpoint ID the app creates for each connection, and the endpoint IDs you connect to. If you set your own relay for a host, the app uses only that relay for it and makes no lookups at number0. Once a relay has introduced the two ends, Iroh may connect directly to the other endpoint's addresses. The optional companion feature connects over HTTPS to a host you enter, using a token you provide. The remote host and any relay or lookup service involved operate under their own terms. The app has no developer server.

## Tailnet discovery

When you tap **Discover tailnet machines**, the app runs `tailscale status --json` on the authenticated SSH bootstrap host through herdr-core. It lists that host's visible peers (name, DNS name, operating system and online status). No Tailscale API key or Android root access is needed.

The bootstrap host probes online SSH-capable peers with its existing SSH keys and known-host trust. It never accepts unknown host keys, forwards your phone's SSH credentials, or installs software. Unreachable or untrusted peers are marked unverified. Adding a peer saves a profile; you verify its SSH fingerprint separately when connecting. Peer lists are not sent to the developer.

## Clipboard

The app reads clipboard text only when you tap Paste for an Iroh endpoint. It writes text when you copy pane or screen text and when your Herdr host sends clipboard text using OSC 52.

## Notifications

Agent alerts come from your own Herdr host and appear only if you grant notification permission. The terminal bell can vibrate the device.

## Diagnostics

If you open local device diagnostics, the screen displays the device model, manufacturer, Android version, battery percentage and free app storage. These values are shown on screen only; they are never saved or sent.

## Children

Remote for Herdr is not designed for children and does not collect information for its developer.

## Changes

If this policy changes, the new version will be published at the same address with an updated effective date.

## Contact

Questions about this policy: [herdr@robitzs.ch](mailto:herdr@robitzs.ch)
