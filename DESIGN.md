# Phone sync engine (factory-9zz.14)

Status: proposal, for t2kg (and t3jk, t3qx where it touches their files) to
agree before anything is built. The root-cause fix for the Tasks first-entry
hang is already on `wolf/phone-cache-engine` (b4766ef) and does not depend on
this design.

## 1. What Wolf sees, and why

| Symptom | Cause (file:line at b9f7081) |
| --- | --- |
| First Tasks entry after a start loads "forever"; second is instant | The panel keeps the whole stores listing on disk so the tree draws at once (WorkPanel.java:431), but BeadCache.put dropped any entry over 128 KiB (BeadCache.java:26,78). Real listings: server 173 KB, macro 674 KB. So the first entry was always a cold full read, queued on the one core-session worker behind transcripts, ref prefetch, usage and doc reads, and skipped entirely while a draft is in the composer (WorkPanel.java:348). The second entry used the in-memory copy. **Fixed in b4766ef**, regression tests in BeadCacheTest. |
| Overview of an agent shows a skeleton "for a while" | Docs are kept in memory only (WorkPanel.docs): after every start, or on the cover's first open, the doc is a cold read behind the same queue. |
| Orange-dot beads open fast, grey ones slowly | Prefetch reads the first 8 non-closed beads in draw order (WorkPanel.java:661, PREFETCH=8), and in-progress sorts first (BeadsModel.statusOrder). Everything else is a cold `beads.read` (1–3 s of `bd show` + comments on a machine without an export, such as server) when tapped. BeadCache holds 64 entries in all. |
| The deacon has no Tasks | `Agent.hasTasks()` is false for role=deacon (PanelModel.java:38); and the panel only ever reads the stores of the machine on screen. |

## 2. Is the current caching coherent?

Partly. Two halves:

**Coherent, keep:** the offline stores (Transcripts, RefFiles, PaneSnapshots)
under `OfflineBudget`. They key by `MachineKey` (core id + authenticated SSH
host key, so an edited profile can never show another machine's data), are
bounded with LRU and a free-space floor, write tmp+rename, and say "as of
14:02 · may be outdated". t3jk owns them (and is adding `agent.history`
for 9zz.12); this design does not move them.

**Ad hoc, replace:** the live-data caches that the screens read.

| Cache | Key | Disk | Fresh/refresh | Scheduler |
| --- | --- | --- | --- | --- |
| BeadCache (panel beads + listing) | saved-profile `hostId` | one JSON file, 64 entries, whole file rewritten after every bead read | 20 s / 5 min / store fp | WorkPanel's own thread, 1 s tick, own typing gate |
| WorkPanel.docs (Overview) | hostId + doc name | none | 10 s | same thread |
| UsageFeed | hostId | usage-looks.json | 10 s sheet, rail cadence, backoff | its own thread, own gate, own backoff |
| HomeCache | computer | home-view.json | per poll | HomeActivity worker |

Problems that follow from having four of these, each found in the code:

1. **Three identity schemes.** Panel and usage key by `hostId`, a profile
   that can be edited to point at another machine; offline stores use the
   proven `MachineKey`. Wrong-machine crossover is possible for the former.
2. **Caps are per cache and silently drop data.** The 128 KiB cap made the
   listing cache dead on every real machine, and nothing logged it.
3. **No shared scheduler.** Every reader has a thread that blocks in
   `RemoteNative.sessionAction`, which feeds the one core-session worker
   FIFO (core_session.rs `pump` holds one op during a slow call). What
   Wolf is looking at waits behind background work. Nothing has priority.
4. **Hand-written redraw signatures.** WorkPanel decides whether to redraw
   from a string of the fields it remembered to include; `storesAt` is
   missing (WorkPanel.java:576), so a read with no stores and no error
   leaves the skeleton up.
5. **The typing gate starves visible reads.** "Never while Wolf types" also
   covers "a draft is in the composer", which can be true for hours, so a
   cold Tasks or Overview never reads at all.
6. **Little is observable.** Panel ops log only failures; success, the
   typing gate and HostSwitched leave nothing, so the hang left no trace.

So: build one engine for the live data, and leave the offline stores alone.

## 3. The engine

One Java class, `SyncEngine`, with a pure core and a thin Android shell, in
`dev.herdr.android.sync` (new files only).

### Keys and entries

```
Key   = (MachineKey machine, Scope scope, Kind kind)
Scope = MACHINE | Project(name) | Store(name) | Terminal(id, incarnation) | Doc(name)
Kind  = STORES | STORE_DUMP | DOC | USAGE | HOME_VIEW   (closed enum)

Entry = { bytes, fetchedAtWallMs, source: MachineKey + hostname as reported,
          fingerprint (store fp, doc key, ...), state: FRESH | STALE | ERROR,
          lastError (typed kind + words), version (monotonic) }
```

* `machine` is always the proven `MachineKey` of the machine the data came
  from, never a profile id. A deacon's cross-machine read (below) is keyed
  by the *source* machine, with the relaying machine recorded.
* An entry from another machine than the connection on screen is only ever
  shown labelled with its machine, never as "this machine".

### Behaviour

* **Stale-while-revalidate.** `subscribe(key, listener)` returns the kept
  entry at once (from memory, else disk) and, if it is older than its kind's
  TTL or missing, queues a read. The listener hears every new version on the
  main thread. Views render from `(entry, version)`; no hand signatures.
* **One queue per machine, with priorities.** VISIBLE (what is on screen) >
  PREFETCH (what is one tap away) > REFRESH (background). One op in flight
  per machine on the core session, as today; keys are deduplicated; a VISIBLE
  request jumps ahead of queued prefetch and refresh. Transcripts and refs
  keep their own threads in phase 1 but can register here later.
* **Gates.** Typing delays PREFETCH and REFRESH only. A VISIBLE read with
  nothing kept runs anyway (one short exec; today it waits forever). Paused
  app or offline machine: nothing runs; entries stay readable.
* **Background prefetch and refresh** within a budget: while connected and
  in front, every machine the phone knows is refreshed at its kind's
  cadence (stores every 30 s by fingerprint, which costs one `find` per
  store when nothing changed), so a switch lands on warm data.
* **Generations.** Each request carries `(machine, generation)`; a reply for
  a machine that is no longer connected, or an older generation, is kept as
  that machine's entry (it is still true of that machine) but never fires a
  listener for the wrong screen.
* **Bounded.** Disk: a new `SYNC` share of `OfflineBudget` (proposal:
  256 MiB, taken from the referenced-files share; needs t3jk's OK since the
  budget is his). Memory: an LRU of decoded entries, 32 MiB. An entry over its
  kind's cap is refused **with a logged reason and an ERROR state**, never
  silently. Files: one per key under `noBackupFilesDir/sync-v1/<machine>/`,
  tmp + fsync + rename, so a bead read no longer rewrites one large file.
* **Observable.** Every op logs one `herdr-ops` line like usage.look does:
  key, priority, queued ms, session ms, bytes, outcome (data / same / error
  kind / dropped: host switched). Gate waits over 5 s log once.

### Testability

The core takes its collaborators as plain values: a `Function<String,String>`
transport (op JSON to reply), an `Executor`, a clock (`LongSupplier`), and a
store interface with an in-memory and a file implementation. JVM tests then
cover, deterministically: first subscription issues exactly one read and the
listener fires once with data; a reply after a host switch is not delivered
to the new screen; a dropped (null) reply retries with backoff; VISIBLE
preempts PREFETCH; the typing gate delays prefetch but not a cold visible
read; an oversize entry is an ERROR, not a hole; eviction and the budget;
restart restores entries and their ages. Rust tests cover the new ops'
parsing, bounds and script output.

## 4. Whole beads store on the phone ("every bead opens instantly")

Not Dolt on the phone (no Android build, a second sync protocol, and the
phone would hold a writable database it must never write), and not a Dolt
server on the computer (a new listening service and port on the tailnet).
Instead, mirror each store's `bd export` (every issue with its description,
design, notes, labels, deps and comments), read-only:

* New phone op `beads.dump {known:[store=fp], on?}`: for each store whose
  fingerprint changed (the same `find -printf %T@` fingerprint STORES_SH
  uses), `BEADS_DIR=… bd export | gzip | base64`; unchanged stores answer
  `same`. Measured on server 11-22: all 10 stores 380 KB raw, 107 KB
  gzipped, about 2.1 s for the lot; macro's repo store 3.5 MB, 1.1 MB
  gzipped. A 30 s refresh with nothing changed costs one `find` per store.
* The Tasks tree, every bead's plan and its comments come from the mirror,
  so any bead opens with no round trip. The mirror's age shows ("beads from
  2m ago"); opening a bead still asks for a quick live confirm in the
  background when its store changed since the dump.
* This replaces `beads.stores` + per-bead `beads.read` for drawing; both
  ops stay for compatibility with older apps.

## 5. The deacon's Tasks: every task, every machine

* `hasTasks()` is true for the deacon; its scope is ALL stores.
* `beads.dump` takes an optional `on` (a machine name from `machine.list`),
  which herdr-core already supports for `machine.exec`. The deacon's machine
  relays to each of its machines; each answer is keyed by that machine.
* The tree groups Machine → Project → epics → beads, and a machine that
  could not be reached says so with its last dump's age.

## 6. Tighter rows (Wolf: gaps about 30% of now)

Visual target, not a cache item: in Tasks, the gap between rows today is
about 19 dp (36 dp minimum row height around a 17 dp title line). Target
about 6 dp: minimum height 23 dp, padding 2 dp, the same text sizes, the
same top bar. Touch targets stay 48 dp by extending into the gap (as the
header's TallTargets already does). t3qx owns WorkPanel's layout; I hand
the numbers over rather than edit it.

## 7. Plan, owners and PRs (all held for Wolf)

| Phase | What | Files | Owner |
| --- | --- | --- | --- |
| 0 | Listing cap fix + tests | BeadCache(Test) | me, done (b4766ef) |
| 1 | `SyncEngine` core, file store, tests; op timing log | new `sync/` package | me |
| 2 | `beads.dump` (+ `on`) in phone_ops with Rust tests | rust/src/phone_ops.rs, beads/dump.sh | me |
| 3 | WorkPanel reads stores, beads, docs through the engine; deacon scope | WorkPanel, PanelModel | with t3qx (his files): I write the adapter, he reviews or applies |
| 4 | UsageFeed and HomeCache onto the engine; `MachineKey` everywhere | UsageFeed, HomeCache, activity glue | with t3jk for activity glue |
| 5 | Row gaps | WorkPanel tokens | t3qx |

Open questions for t2kg / Wolf:

1. OK to take a 256 MiB `SYNC` share from `OfflineBudget` (t3jk's)?
2. VISIBLE cold reads run even with a draft in the composer: OK? (They are
   one short exec; typing still never waits behind background work.)
3. Phase 3 touches t3qx's WorkPanel: does t3qx want to apply it, or let me?
