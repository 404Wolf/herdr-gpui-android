# gpui-rio (modified copy)

A Rio terminal component for GPUI, vendored from gpui-rio 0.1.0
(<https://github.com/cachix/agentaps>, Apache-2.0) for Herdr GPUI Android's raw
SSH shell mode. Rio (`rio-vt`) owns VT parsing, scrollback and selection; GPUI
draws the grid.

Upstream's package ships no licence file; the Apache-2.0 text is this
repository's [LICENSE](../../../../LICENSE). The changes below are this copy's.

## Changes in this copy

- No local PTY or process. A `Session` parses output from any `Read` and writes
  input to any `Write`, on its own threads; here they are an SSH channel's PTY.
  The reader answers the queries a terminal owes the program (device and cursor
  reports, colour and text-area size) through the same input queue.
- No platform gate, terminfo, working directory or shell spawning, so it builds
  for Android. Without the PTY it needs no `rio-vt` `pty` feature.
- `Terminal::spawn` became `Terminal::attach(session, style, cx)`. Dropping the
  entity stops drawing only; the session's owner ends the stream.
- `set_host_input(true)` is for hosts that deliver keys and text themselves, as
  Android does through one IME bridge: no input handler, no key handling, no
  focus taken, and a cursor drawn without focus.
- `KeyPress` and `key_bytes` encode keys without GPUI types, and the session
  sends them with the program's application-cursor and bracketed-paste modes.
- A cell is the font's own line height (ascent plus descent), not 1.35 × its
  size, as other terminals size cells: box drawing meets row to row, and no
  added leading takes rows from a phone screen.
- `set_link_handler` makes links tappable: OSC 8 hyperlinks (http(s), and
  `file://` as a path), plain http(s) URLs and file paths (`src/a.rs:12`,
  `~/x`, `/etc/hosts`) in the text (`links.rs`). A link continues across rows
  the terminal wrapped, and across a program's own break at its right edge or
  box border, as Claude Code wraps long URLs. A tap highlights every row it
  covers and hands the host a `Link::Web` or `Link::File`.
- A tap or click that is not on a link, and that neither travelled nor was a
  held finger (`tap.rs`), reaches a program that asked for mouse events
  (DECSET 9, 1000, 1002 or 1003) as a left-button press and release on that
  cell, SGR-encoded when it asked for 1006 (`mouse.rs`, `Session::click`).
  This is how Claude Code's fullscreen view gets clicks. Without mouse
  reporting a tap does what it did before. `Session::wheel` uses the same
  encoder. `set_tap_listener` tells the host what each lifted press did, so
  a host that opens a keyboard on a tap can leave it shut for a click.
- Errors are a typed `Error`; `anyhow` and the GPUI tests against a local PTY
  were removed. The tests here drive a real socket pair.

## Use

```rust,ignore
mod terminal {
    gpui_rio::bind_gpui!(gpui);
}

let session = gpui_rio::Session::start(output, input, |columns, rows| { /* resize */ },
    style.theme, 80, 24)?;
let terminal = terminal::Terminal::attach(session.clone(), style, cx);
```

The adapter keeps upstream's per-cell painting, selection, wheel scrollback and
IME composition. Graphics and application mouse reporting are not implemented.
