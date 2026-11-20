# Agent footer visibility

The API 34 x86_64 emulator used widths of 412 dp and 884 dp with a scratch
herdr-core serving Claude Code and Codex fixture panes. The before images
are from `60e4137`. The after images are from `0019e99` with the default
setting. The narrow Claude fixture wraps across two terminal rows. The
Codex warning row remains visible and moves up into the freed row.

| Fixture | 412 dp | 884 dp |
| --- | --- | --- |
| Claude Code | [Before](claude-before-narrow.png) · [After](claude-after-narrow.png) | [Before](claude-before-wide.png) · [After](claude-after-wide.png) |
| Codex | [Before](codex-before-narrow.png) · [After](codex-after-narrow.png) | [Before](codex-before-wide.png) · [After](codex-after-wide.png) |

[Toggle off](toggle-off.png) · [Toggle on](toggle-on.png) · [Claude footer restored](claude-toggle-shown.png).
Both settings survived a force-stop and relaunch.
