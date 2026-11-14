# Phone rail: “More”, sub-agents and unmerged counts

Initial read-only audit at `1f3d634` on `wolf/phone-rail-always-clickable` (PR #11, re-review pending). That audit changed no repository files. The later implementation status and deferred bridge proposal are below. No builds, compiles, tests or emulators run. References below are relative to `android-gpui/app/src/main/java/dev/herdr/android/` at that commit.

## What the phone shows today

| Item | Current behavior and source |
| --- | --- |
| Repo-grouped PM list | Both surfaces iterate every repo and every project under it, without a PM overflow cap or “More” section: `HomeActivity.java:1116`, `GpuiTerminalActivity.java:2658`. Repo headers show `N project(s)` and an optional blocked marker, from `FleetTree.java:784`. |
| Catch-all group | Both render a folded **Other terminals** section after repos: Home `HomeActivity.java:1127`, rail `GpuiTerminalActivity.java:2667`. Its count is all member terminals. This contains unlabelled/inferred projects, loose shells, additional Deacons and terminals without a managed project (`FleetTree.java:887`). It is a semantic group, not an overflow for excess PMs. |
| Other “More” text | Unopened-worktree catalogs show **+N more** after the first 30 entries: `HomeActivity.java:1234`, `GpuiTerminalActivity.java:2748`; paging is shared in `FleetTree.java:141`. Separate toolbar overflow buttons are labelled **More** (`GpuiTerminalActivity.java:365`, `:864`) and open view/session/navigation controls (`:1008`). Neither is a Fleet section. |
| PM badge in Home | PM rows show `pm` plus nonzero state tallies such as `1! 2✓ 3▶`: `HomeActivity.java:1158`, `:1249`. These mean blocked/error, done and working. The tally includes the PM itself as well as its project's other terminals with those statuses (`FleetTree.java:383`, `:386`). It is not a total sub-agent count, and idle agents have no displayed subtotal. |
| PM badge in terminal rail | A normal PM row shows its own status and a folded-child blocked marker, not a descendant count: `GpuiTerminalActivity.java:2698`, `:3635`. A **no PM** row shows `flatten(pm.workers).size()` (`:2692`), which counts every descendant terminal, including bare shells. Home's no-PM row instead uses the state tallies. |
| Unopened worktree count | Catalog child rows show `N unopened worktree(s)`, with an incomplete marker when applicable: `HomeActivity.java:1200`, `GpuiTerminalActivity.java:2712`, `FleetTree.java:772`. These appear when the PM/Other terminals fold opens. This is not an unmerged count. |
| Unmerged badge | Neither Home nor terminal rail renders one. Java's `Place` contains common repo, checkout root and branch; `GitWorktree` contains path, branch, bare and prunable flags (`FleetTree.java:45`, `:55`). The `git.look` parser stores those fields and truncation state, but no merge ancestry or unmerged count (`:567`, `:603`). |

## Data already available, and gaps

`FleetPanes.members` supplies each machine's local terminal ids, roles, project labels, parent ids, harness/status and cwd (`FleetPanes.java:30`, `:50`). `PmRow.workers` already contains the complete per-project nested forest across checkouts, excluding the PM (`FleetTree.java:733`, `:911`). `Member.isAgent()` distinguishes agents from bare shells (`:35`). This supports a shared per-PM agent count, provided the desktop definition is applied consistently: count recursive descendants, avoid counting only root rows, exclude PM/shells, and deduplicate by terminal id. `flatten` counts terminals rather than filtering agents (`:952`); `Project.agents()` also counts member terminals and includes the PM (`:120`), so neither is the desired subtotal as-is.

Unmerged state needs an authoritative definition/source before a badge can be honest. Unopened catalogs are assigned once per **repo**, sometimes onto another PM in that repo (`FleetTree.java:390`, `:494`, `:944`); they do not establish which PM owns every unmerged checkout. They omit open checkouts and filter bare/prunable entries (`:509`). Do not use `closed.size()`, a “done” status or a repo's project count as a substitute for unmerged work. Missing/truncated git data must remain distinguishable from a known zero. The current Java git queries run off the UI thread and share a bounded cache (`HomeActivity.java:1818`, `GpuiTerminalActivity.java:3114`; folder probes capped at 96 in `FleetPanes.java:104`).

## Planned change when implementation is assigned

Align with the desktop change on two points: whether “no More section” also changes the phone's **Other terminals** placement, and what exactly an unmerged count measures (including its integration ref and per-PM ownership). There is no literal phone Fleet “More” section to remove today. Preserve access to every currently reachable terminal/catalog and keep worktree paging bounded unless an explicit replacement is agreed.

1. Keep every PM directly reachable under its repo, without adding an overflow section. If the desktop's removal also includes the phone's **Other terminals** grouping, agree its replacement placement through the PM before removing that fold; its terminals and catalogs must remain reachable. Preserve toolbar actions and bounded worktree paging unless those are explicitly included in the request.
2. Add a shared per-PM agent subtotal to `FleetTree.PmRow`, counting the recursive forest with the desktop's agent definition, excluding the PM and bare shells. Use that same subtotal/label in Home's `pmRow` and terminal `railPm`, including no-PM rows; keep blocked status and repo project totals accurate.
3. Obtain the desktop-defined merge metadata with per-checkout identity and explicit known/unknown state, map it to the correct PM, and expose a shared unmerged subtotal alongside the sub-agent subtotal. Extend the background git/cache path only within the next task's authorized scope; the existing prohibition on editing `core_session.rs`, terminal sizing and vendored code remains in force. Do not infer merge state from agent status or unopened catalogs.

Relevant existing JUnit fixtures cover recursive parent nesting/cycles (`FleetTreeTest.java:664`), shell/worker distinctions (`RailClicksTest.java`), repo counts/folds (`FleetReposTest.java`) and bounded worktree pagination (`FleetTreeTest.java:402`). Planned coverage: identical Home/rail count labels, nested descendants, shells, no-PM projects, cross-machine isolation, shared-repo ownership and unknown/truncated merge metadata. No code or tests were changed for this audit; no tests were executed.

## Desktop-aligned design — approved

Read [home-manager-conf PR #15](https://github.com/404Wolf/home-manager-conf/pull/15) and `origin/main` at `e202e8c`, specifically [hierarchy](https://github.com/404Wolf/home-manager-conf/blob/e202e8cdc95421fb0530630f6eab519954c34ec9/users/wolf/programs/herdr-core/app/mods/spaces-wolf/main.js#L211), [sidebarGroups / projectCounts](https://github.com/404Wolf/home-manager-conf/blob/e202e8cdc95421fb0530630f6eab519954c34ec9/users/wolf/programs/herdr-core/app/mods/spaces-wolf/main.js#L714), [refs-only GIT_LOOK](https://github.com/404Wolf/home-manager-conf/blob/e202e8cdc95421fb0530630f6eab519954c34ec9/users/wolf/programs/herdr-core/app/mods/spaces-wolf/main.js#L104) and [treeRows](https://github.com/404Wolf/home-manager-conf/blob/e202e8cdc95421fb0530630f6eab519954c34ec9/users/wolf/programs/herdr-core/app/mods/spaces-wolf/main.js#L1610). The following resolves the definition questions above.

| Count | Exact desktop meaning and phone plan |
| --- | --- |
| `≡N` | All project agent members, including nested sub-agents, minus its actual PM or inferred lead when one exists. Bare shells are excluded. It is not just `role=worker`, not a root-row count, and a no-PM row's tap target must not be subtracted merely because it is first. Use the same live-agent membership definition as desktop, not raw terminal totals or role-only shell labels. |
| `↑N` | For each distinct checkout top represented by the project's open spaces and agents' own cwd, take the positive **ahead** value from `git rev-list --left-right --count 'HEAD...@{upstream}'` and sum once per top. Include the PM's checkout; avoid double-counting multiple agents/subdirectories in it. Each checkout uses its own upstream; no upstream contributes zero. This is commits ahead, not unmerged worktree count, PR state, dirty files or comparison with an assumed main branch. Unopened catalog entries do not contribute just because they exist. |
| Presentation | Show both subtotals on PM and dimmed no-PM rows, folded or open; suppress zero glyphs as desktop does. Keep project status columns, which include the PM. Worker rows show their own checkout's `↑ahead ↓behind`, including nested workers and those beside the PM. A folded repo totals its shown PM/live no-PM projects; idle projects do not contribute. |

**Where the phone gets it, without a new server.** Core `terminal.list` is already projected into the phone's `panes` payload (`android-gpui/rust/src/core_session.rs:1520`, `:1597`), including live terminals without a desktop pane, labels, parent, cwd, normalized status and foreground program. `FleetPanes.members` consumes those fields. Reuse this inventory to form the same project agent membership and inferred lead; no additional `hc` process, agent service or polling daemon is needed. Put the computed counts in a shared project/sidebar projection consumed by Home and the rail.

Ahead/behind is absent from the current payload. The existing `git.look` action already performs one bounded `machine.exec` over the selected computer's existing session (`core_session.rs:537`), with a client-owned script (`:1041`). Extend that script with the desktop's refs-only count, once per distinct checkout, and append optional ahead/behind fields to its existing place records; extend `FleetTree.Place`/parser/cache to consume them. Older records remain readable; an absent or failed answer stays unknown internally, while a successfully resolved checkout with no upstream contributes zero. Preserve existing timeouts, output/probe limits and background scheduling, coalesce per host/top, and avoid `git status`, fetches, new SSH sessions or one exec per agent. **Scope dependency:** the minimal script/bridge change is in currently protected `core_session.rs`; Java alone cannot produce this number. The PM's implementation scope must explicitly authorize that narrow change or assign it to that file's owner. No edits to that file have been made.

**Replace Other terminals with the desktop projection.** For each computer, prepare all known repo names before placing projects. Each repo contains labelled PM projects; dimmed live no-PM projects; a remembered, initially folded `▸ N idle` group for no-PM projects with no agents; then stray open checkouts and one unopened-worktree list per canonical repo. A no-PM project joins by resolved git repo, conventional worktree path, then a known repo name; unmatched projects/checkouts go to folded **Outside git** at the foot, and unplaced agents to folded **Other agents**, each only when nonempty. Move catalogs out from individual PM folds into the repo list. Toolbar More and bounded catalog paging stay separate from the removed Fleet catch-all. Desktop groups duplicate repo basenames under one header while preserving distinct canonical repo identities/catalogs; phone currently separates those headers, so the next projection must align this too. Repos with git come first, then other groups in project order. Preserve explicit terminal/checkout taps, epoch fences and remembered folds; do not promote an inferred lead to labelled PM.

**Implementation boundary and validation.** After the go, create a new branch stacked on `wolf/phone-rail-always-clickable`. First implement/test the pure shared membership, grouping and count projection; wire both Java renderers to it; add the narrowly authorized git metadata extension. Planned fixtures: a PM plus three agents including a nested sub-agent gives `≡3`; distinct checkout ahead values `3+2+1` give `↑6` despite duplicate cwd probes; no-upstream zero; inferred-lead exclusion; shells; mixed projects sharing a space; live/idle no-PM projects; duplicate basenames retaining both catalogs; outside/other foot groups; worker ↑/↓; folded repo totals; old/failed/truncated metadata; focus reveals and stale completions. This design preceded the implementation go; implementation status is recorded below. PR #11 remains at `1f3d634`; the server load restriction is observed.


## Java implementation and the approved bridge diff

The authorized Java work is on `wolf/phone-rail-project-counts`, stacked on PR #11 at `1f3d634`. Both views now consume the same repo/live/idle/checkout/catalog/foot placement and `≡N`/status totals. Known shell foreground programs do not count as agents even if old role/harness labels remain. Inferred leads are excluded from the agent subtotal while their statuses remain in the project totals. At the Java-only milestone `89b877f`, ahead/behind remained deferred. The subsequent owner clearance and implementation are recorded below; unknown values are never replaced with a zero.

Confirmed by reading the Java native surface (`RemoteNative.java:40`), the `TabEdit::parse` operation allowlist (`core_session.rs:918`), `git_look` (`:537`) and `GIT_LOOK` (`:1041`): there is no Java-reachable generic exec operation, and the existing `git.look` output has no ahead/behind fields. `file.read` returns file content, not a git reachability calculation. Sending shell commands into a terminal or opening a second SSH connection would bypass the existing path and is not proposed. This required owner clearance rather than a Java-only workaround; that clearance is now granted by the laptop deacon via t1jf.

**Owner-cleared and applied.** At `android-gpui/rust/src/core_session.rs:1041–1051` on `1f3d634`, extend only the client-owned `GIT_LOOK` script as below. This keeps the existing `git.look` operation, its 96-path validation, one existing-session `machine.exec`, 10-second timeout and bounded response. Cache counts by canonical checkout top within the batch, since several folders may be in the same checkout. Read only refs; missing upstream is zero, an unexpected count failure is `?` (unknown). Paths stay positional/quoted; no eval, working-tree walk, fetch or write is introduced.

```diff
--- a/android-gpui/rust/src/core_session.rs
+++ b/android-gpui/rust/src/core_session.rs
@@ -1040,11 +1040,36 @@
 /// The phone's git look, one `sh -c` over the folders it shows (see git_look).
 const GIT_LOOK: &str = r#"seen='
 '
+tab=$(printf '\t')
+counts=''
 for p in "$@"; do
   c=$(git -C "$p" rev-parse --path-format=absolute --git-common-dir 2>/dev/null) || { printf 'P\t%s\n' "$p"; continue; }
   t=$(git -C "$p" rev-parse --show-toplevel 2>/dev/null)
   b=$(git -C "$p" symbolic-ref --short -q HEAD 2>/dev/null)
-  printf 'P\t%s\t%s\t%s\t%s\n' "$p" "$c" "$t" "$b"
+  hit=0
+  a='?'; d='?'
+  while IFS="$tab" read -r top ahead behind; do
+    if [ "$top" = "$t" ]; then
+      a=$ahead; d=$behind; hit=1; break
+    fi
+  done <<EOF
+$counts
+EOF
+  if [ "$hit" = 0 ]; then
+    if git -C "$p" rev-parse --verify '@{upstream}' >/dev/null 2>&1; then
+      ab=$(git -C "$p" rev-list --left-right --count 'HEAD...@{upstream}' 2>/dev/null) || ab=''
+      if [ -n "$ab" ]; then
+        IFS="$tab" read -r a d <<EOF
+$ab
+EOF
+      fi
+    else
+      a=0; d=0
+    fi
+    counts="$counts
+$t$tab$a$tab$d"
+  fi
+  printf 'P\t%s\t%s\t%s\t%s\t%s\t%s\n' "$p" "$c" "$t" "$b" "$a" "$d"
   case "$seen" in *"
 $c
 "*) continue;; esac
```

The unified diff was generated against the unchanged current script, and no surrounding attach/resize or terminal code is involved. The Java follow-up parses the optional fifth/sixth place-value columns after branch into bounded nonnegative ahead/behind values (absent/`?` stays unknown), attaches them to each canonical checkout, sums each top once per project including `pmRow`, shows worker ↑/↓, and adds ↑ to shared project and folded-repo totals. Older clients ignore these extra columns; newer clients continue reading old records. The only production change in `core_session.rs` is the approved `GIT_LOOK` script constant; its existing git-look unit test is extended as requested. The script and tests have not been executed under the build/test restriction.

`FleetSidebarTest` is written for nested agents and shell exclusions; inferred/no inferred lead counts; repo/live/idle/foot placement; no lost terminals; a known repo discovered from a later stray checkout; duplicate names retaining both catalogs; a repo with only shells and a catalog; shared physical spaces and separate machines; and both Java renderers consuming the same labels. Existing grouping/tap fixtures are updated. Validation is static source/diff review only; JUnit, builds and emulator QA remain pending the heavy slot. Only the approved git script constant and its existing unit test change in Rust; vendored code and terminal sizing are unchanged. No-PM and stray checkout headers retain selection feedback and can be revealed when their terminal is focused.

Publication: Java-only milestone `89b877f` is pushed on `wolf/phone-rail-project-counts`; [PR #12](https://github.com/404Wolf/herdr-gpui-android/pull/12) targets `wolf/phone-rail-always-clickable` at `1f3d634`. The protected bridge proposal remains unapplied. Static diff/commit checks passed; no build/test/emulator run.


## Refs-count follow-up and checkout-row review fix

The approved batch now parses optional ahead/behind columns after the branch while continuing to accept old place records. Counts are bounded nonnegative integers; absent, malformed, out-of-range, `?`, or unterminated numeric records remain unknown. A completed outside-git answer is tracked separately from an unanswered folder. Project ahead totals include the PM and all represented agent/checkout folders, deduplicate canonical tops, and use a long accumulator. Any unknown checkout keeps the total unknown, including folded-repo sums, instead of publishing a partial sum. Worker ↑/↓ is read from its own cwd independently of whether its branch label is suppressed. Newlines and existing bounded response/cache behavior are retained.

The review's duplicate-checkout finding is also fixed: all existing loose nodes and unplaced shells are copied and merged by checkout key before a new forest is built once. The original Fleet nodes/forests are not mutated. `FleetSidebarTest` now enumerates the actual emitted rail cache-key shapes and primary/expanded terminal targets, rather than unioning raw member ids. It covers a spaced shell plus two unplaced shells in one checkout, key uniqueness, all three terminals remaining targets, repeated projection of the same Fleet, and a git answer arriving after the initial projection.

`FleetGitCountsTest` is written for old/new records, unknown and invalid numbers, unterminated records, known zero/no-upstream, PM checkout inclusion, nested folders sharing one top, project/idle/folded-repo totals, unknown folders in a shared space, 64-bit sums and worker renderer wiring. The existing Rust GIT_LOOK test now writes a temporary repo/worktree with one ahead and one behind commit, checks the new columns for main/no-upstream and repeated checkout/subdirectory probes, and retains catalog-format checks. No build, compile, test, emulator or live git-fixture execution was performed. Verification remains static source review and `git diff --check`; the heavy-slot hold is observed.
