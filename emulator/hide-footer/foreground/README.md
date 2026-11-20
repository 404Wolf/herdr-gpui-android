# Agent foreground regression

An API 34 x86_64 emulator opened a saved transcript in Neovim in a pane
labelled `agent.kind=claude`. The core reported `nvim` as the foreground
program. The first build hid the transcript's recorded mode line. The fixed
build preserves it and the link below it with **Show agent footer** off.

- Neovim at 884 dp. [Before the fix](editor-case-before.png) and [after the fix](editor-case-after.png)
- The core reported `claude` for the Claude fixture. [412 dp](claude-narrow.png) and [884 dp](claude-wide.png)
- The core reported `codex` for the Codex fixture. [412 dp](codex-narrow.png) and [884 dp](codex-wide.png)
- [The default setting](setting-default.png)
