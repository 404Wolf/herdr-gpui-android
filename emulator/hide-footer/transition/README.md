# Foreground transition

An API 34 x86_64 emulator watched a Claude-labelled Bash pane switch from a
Claude fixture to a saved transcript in Neovim. Neovim took the foreground in
12 ms in both runs. The core reported the previous process for 509 ms on
`2f09bfb` and for 500 ms on the fixed build. The early complete Neovim frames
show the transcript row missing on the original build and present on the fix.

- [Early Neovim frame on the original build](before-editor-early.png)
- [Early Neovim frame on the fixed build](after-editor-early.png)
- [Claude fixture before exit on the fixed build](after-agent.png)
- Claude footers collapse after the foreground is confirmed. [412 dp](claude-narrow.png) · [884 dp](claude-wide.png)
- Codex warnings remain visible after the footer collapses. [412 dp](codex-narrow.png) · [884 dp](codex-wide.png)
- The setting restores the wrapped Claude footer and survived relaunch. [Footer restored](claude-toggle-shown.png)
